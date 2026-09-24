package ca.tristan.portfolio.net

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Prices, history, search and screeners from a Finance Query server.
 *
 * ## Why this exists
 *
 * [YahooQuoteClient] talks to the provider's undocumented endpoints straight
 * from the handset, which makes every device its own unthrottled client. That
 * is what [RateLimitBackoff] is for — and once it trips it trips for
 * everything, because one host served quotes, history, dividends and news
 * alike. A throttled price refresh took the news feed down with it for minutes
 * at a time.
 *
 * Finance Query (github.com/Verdenroz/finance-query) is a Rust server wrapping
 * the same public data behind a REST/WebSocket API with a Redis cache in front.
 * The upstream is unchanged, so this does not make the data more reliable in
 * principle — what changes is WHO absorbs the throttling. The server takes the
 * rate limiting and hands the app a cached answer, so a phone in a bad minute
 * gets a slightly stale price instead of a 429.
 *
 * ## It is not a hard dependency
 *
 * Every function returns null or an empty list rather than throwing, and
 * [YahooQuoteClient] falls back to its own direct implementation whenever one
 * does. If the server is down, or the public instance disappears, the app keeps
 * working exactly as it did before.
 *
 * ## Self-hosting
 *
 * The public instance at finance-query.com is free but carries no SLA, no
 * documented rate limit and no guarantee it will still be there next year. Set
 * [baseUrlOverride] once at startup to point at your own deployment:
 *
 *     FinanceQueryClient.baseUrlOverride = "https://finance.example.com"
 *
 * The project ships a Docker Compose file (server, Nginx, Redis) and is MIT
 * licensed, so running it yourself is the supported path for a shipping build.
 */
object FinanceQueryClient {

    private const val DEFAULT_BASE_URL = "https://finance-query.com"

    /** Set to a self-hosted server's origin (no trailing slash needed). */
    @Volatile
    var baseUrlOverride: String? = null

    private val baseUrl: String
        get() = (baseUrlOverride?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_BASE_URL)
            .removeSuffix("/")

    /**
     * Its own client, deliberately NOT sharing [YahooQuoteClient]'s — that one
     * carries the rate-limit interceptor this whole class exists to get out
     * from under.
     *
     * Timeouts are shorter than the direct client's on purpose: this is the
     * first thing tried on every call, so a server that has gone away has to
     * fail fast enough that the fallback still feels like one request.
     */
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    /**
     * Set after a failure so a dead server is not re-tried on every row of a
     * list, and cleared by the next success. Without it, a watchlist of thirty
     * symbols on a phone with no route to the server pays the timeout thirty
     * times before falling back.
     */
    @Volatile
    private var unavailableUntilMs: Long = 0L

    private val isAvailable: Boolean
        get() = System.currentTimeMillis() >= unavailableUntilMs

    // ── Transport ────────────────────────────────────────────────────────────

    private fun get(path: String): JSONObject? {
        if (!isAvailable) return null
        val request = Request.Builder()
            .url(baseUrl + path)
            .header("Accept", "application/json")
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    // A 404 is this symbol's answer, not the server's health —
                    // it sends the caller to the fallback without blacklisting
                    // the host for everything else.
                    if (response.code >= 500 || response.code == 429) {
                        unavailableUntilMs = System.currentTimeMillis() + 120_000
                    }
                    return@use null
                }
                unavailableUntilMs = 0L
                val body = response.body?.string() ?: return@use null
                JSONObject(body)
            }
        } catch (_: Exception) {
            unavailableUntilMs = System.currentTimeMillis() + 120_000
            null
        }
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    // ── Field readers ────────────────────────────────────────────────────────
    //
    // The payload is the upstream's own shape flattened to plain scalars —
    // `regularMarketPrice: 45.01` rather than `{"raw": 45.01, "fmt": "45.01"}`
    // — so these stay simple. JSONObject.optDouble returns NaN for a missing
    // or null key, which is the one thing worth normalising.

    private fun JSONObject.num(key: String): Double? {
        if (!has(key) || isNull(key)) return null
        val value = optDouble(key, Double.NaN)
        return if (value.isNaN() || value.isInfinite()) null else value
    }

    private fun JSONObject.long(key: String): Long? = num(key)?.toLong()

    private fun JSONObject.text(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key).takeIf { it.isNotBlank() }
    }

    /** Epoch SECONDS in the payload, epoch millis everywhere in this app. */
    private fun JSONObject.epochMillis(key: String): Long? =
        num(key)?.takeIf { it > 0 }?.let { (it * 1000L).toLong() }

    private fun JSONObject.firstText(vararg keys: String): String? {
        for (key in keys) text(key)?.let { return it }
        return null
    }

    private fun JSONObject.firstNum(vararg keys: String): Double? {
        for (key in keys) num(key)?.let { return it }
        return null
    }

    private fun JSONObject.firstLong(vararg keys: String): Long? {
        for (key in keys) long(key)?.let { return it }
        return null
    }

    // ── Quotes ───────────────────────────────────────────────────────────────

    /**
     * Quotes for several symbols in ONE request.
     *
     * The batch endpoint reports per-symbol failures in its own `errors` array
     * rather than failing the whole call, so a delisted ticker in a watchlist
     * costs that row and nothing else. Symbols that came back empty are simply
     * absent from the result, which is what tells the caller which ones still
     * need the fallback.
     */
    fun fetchQuotes(tickers: List<String>): Map<String, Quote> {
        val cleaned = tickers.map { it.trim().uppercase() }.filter { it.isNotEmpty() }.distinct()
        if (cleaned.isEmpty()) return emptyMap()

        val out = LinkedHashMap<String, Quote>()
        // Chunked so one long watchlist cannot build a URL the server rejects.
        cleaned.chunked(40).forEach { chunk ->
            val root = get("/v2/quotes?symbols=${enc(chunk.joinToString(","))}") ?: return@forEach
            val rows = root.optJSONArray("quotes") ?: return@forEach
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                val symbol = row.text("symbol")?.uppercase() ?: continue
                val quote = parseQuote(row) ?: continue
                out[symbol] = quote
            }
        }
        return out
    }

    /** One symbol's full quote. */
    fun fetchQuote(ticker: String): Quote? {
        val clean = ticker.trim()
        if (clean.isEmpty()) return null
        val root = get("/v2/quote/${enc(clean)}") ?: return null
        // The single-symbol endpoint returns the quote at the top level; the
        // batch wraps it. Both shapes are accepted so a server version change
        // in either direction does not silently return nothing.
        parseQuote(root)?.let { return it }
        val rows = root.optJSONArray("quotes") ?: return null
        val first = rows.optJSONObject(0) ?: return null
        return parseQuote(first)
    }

    private fun parseQuote(row: JSONObject): Quote? {
        val price = row.firstNum("regularMarketPrice", "currentPrice") ?: return null
        if (price <= 0.0) return null

        return Quote(
            price = price,
            name = row.firstText("shortName", "longName", "displayName"),
            previousClose = row.firstNum("regularMarketPreviousClose", "previousClose"),
            currency = row.text("currency")?.uppercase(),
            dayHigh = row.firstNum("regularMarketDayHigh", "dayHigh"),
            dayLow = row.firstNum("regularMarketDayLow", "dayLow"),
            open = row.firstNum("regularMarketOpen", "open"),
            volume = row.firstLong("regularMarketVolume", "volume"),
            fiftyTwoWeekHigh = row.num("fiftyTwoWeekHigh"),
            fiftyTwoWeekLow = row.num("fiftyTwoWeekLow"),
            avgVolume3Month = row.firstLong("averageDailyVolume3Month", "averageVolume"),
            marketTimeMillis = row.epochMillis("regularMarketTime"),
            exchangeTimezone = row.firstText("timeZoneFullName", "exchangeTimezoneName"),
            // Normalised: the lookup endpoint lower-cases this ("etf") while
            // the quote endpoint upper-cases it ("ETF"), and HoldingType
            // .fromQuoteType downstream compares against upper-case names.
            instrumentType = row.firstText("quoteType")?.uppercase(),
            logoUrl = row.firstText("companyLogoUrl", "logoUrl")
        )
    }

    /**
     * The after-hours or pre-market line shown under a live price.
     *
     * Read from the same quote payload rather than a second request: the
     * pre/post fields ride along on every quote, so the extended line costs
     * nothing once the quote has been fetched. Labelled "Extended" to match
     * the direct client's own wording, so the row reads the same whichever
     * path served it.
     */
    fun fetchExtendedQuote(ticker: String): ExtendedQuote? {
        val clean = ticker.trim()
        if (clean.isEmpty()) return null
        val root = get("/v2/quote/${enc(clean)}") ?: return null
        val row = if (root.has("regularMarketPrice")) {
            root
        } else {
            root.optJSONArray("quotes")?.optJSONObject(0) ?: return null
        }

        val zone = row.firstText("timeZoneFullName", "exchangeTimezoneName")

        // Post-market first — once a session has closed, the after-hours print
        // is the newer of the two and is what the reader is asking about.
        val postPrice = row.num("postMarketPrice")
        val postChange = row.num("postMarketChange")
        val postPct = row.num("postMarketChangePercent")
        val postAt = row.epochMillis("postMarketTime")
        if (postPrice != null && postChange != null && postPct != null && postAt != null) {
            return ExtendedQuote("Extended", postPrice, postChange, postPct, postAt, clean, zone)
        }

        val prePrice = row.num("preMarketPrice")
        val preChange = row.num("preMarketChange")
        val prePct = row.num("preMarketChangePercent")
        val preAt = row.epochMillis("preMarketTime")
        if (prePrice != null && preChange != null && prePct != null && preAt != null) {
            return ExtendedQuote("Extended", prePrice, preChange, prePct, preAt, clean, zone)
        }
        return null
    }

    // ── History ──────────────────────────────────────────────────────────────

    /**
     * Price bars for a range.
     *
     * One caveat worth knowing rather than discovering: the server does not
     * always honour [interval] exactly — asking for `max`/`1mo` comes back with
     * weekly granularity. Nothing downstream depends on the bar spacing being
     * what was requested (the chart plots whatever timestamps it gets), but it
     * means bar COUNTS are not a reliable way to tell ranges apart.
     */
    fun fetchHistoryBars(ticker: String, range: String, interval: String): List<HistoryBar> {
        val clean = ticker.trim()
        if (clean.isEmpty()) return emptyList()
        val root = get("/v2/chart/${enc(clean)}?range=${enc(range)}&interval=${enc(interval)}")
            ?: return emptyList()
        val candles: JSONArray = root.optJSONArray("candles") ?: return emptyList()

        val out = ArrayList<HistoryBar>(candles.length())
        for (i in 0 until candles.length()) {
            val candle = candles.optJSONObject(i) ?: continue
            val timestamp = candle.epochMillis("timestamp") ?: continue
            val close = candle.num("close")?.takeIf { it > 0 } ?: continue
            out.add(HistoryBar(timestamp, close, candle.long("volume")))
        }
        return out.sortedBy { it.timestampMs }
    }

    // ── Search ───────────────────────────────────────────────────────────────

    fun searchSymbols(query: String): List<SymbolSearchResult> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        val root = get("/v2/lookup?q=${enc(trimmed)}") ?: return emptyList()
        val rows = root.optJSONArray("quotes") ?: return emptyList()

        val out = ArrayList<SymbolSearchResult>(rows.length())
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val symbol = row.text("symbol") ?: continue
            out.add(
                SymbolSearchResult(
                    symbol = symbol,
                    name = row.firstText("shortName", "longName"),
                    exchange = row.firstText("exchDisp", "exchange"),
                    quoteType = row.text("quoteType")?.uppercase()
                )
            )
        }
        return out
    }

    // ── Screeners ────────────────────────────────────────────────────────────

    /** A named screener — "most-actives", "day-gainers", "day-losers". */
    fun fetchScreener(name: String, limit: Int = 25): List<MarketMover> {
        val root = get("/v2/screeners/${enc(name)}") ?: return emptyList()
        val rows = root.optJSONArray("quotes") ?: return emptyList()

        val out = ArrayList<MarketMover>()
        for (i in 0 until minOf(rows.length(), limit)) {
            val row = rows.optJSONObject(i) ?: continue
            val ticker = row.text("symbol") ?: continue
            val price = row.num("regularMarketPrice")?.takeIf { it > 0 } ?: continue
            val previousClose = row.num("regularMarketPreviousClose")?.takeIf { it > 0 } ?: continue
            // Taken from the payload where it is given, derived where it is
            // not: the percentage is what the list sorts on, so computing it
            // rather than dropping the row keeps a good name in the table.
            val changePct = row.num("regularMarketChangePercent")
                ?: ((price - previousClose) / previousClose * 100.0)

            out.add(
                MarketMover(
                    ticker = ticker,
                    name = row.firstText("shortName", "displayName", "longName"),
                    price = price,
                    previousClose = previousClose,
                    changePct = changePct,
                    exchange = row.firstText("fullExchangeName", "exchange"),
                    volume = row.long("regularMarketVolume"),
                    averageVolume = row.firstLong(
                        "averageDailyVolume3Month", "averageDailyVolume10Day"
                    ),
                    marketTimeMillis = row.epochMillis("regularMarketTime"),
                    exchangeTimezone = row.text("timeZoneFullName")
                )
            )
        }
        return out
    }
}
