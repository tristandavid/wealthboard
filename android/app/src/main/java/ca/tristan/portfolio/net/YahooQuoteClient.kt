package ca.tristan.portfolio.net

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.IOException
import java.io.StringReader
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class Quote(
    val price: Double,
    val name: String?,
    val previousClose: Double?,
    val currency: String? = null,
    val dayHigh: Double? = null,
    val dayLow: Double? = null,
    val open: Double? = null,
    val volume: Long? = null,
    val fiftyTwoWeekHigh: Double? = null,
    val fiftyTwoWeekLow: Double? = null,
    val avgVolume3Month: Long? = null,
    /** Epoch millis of the last regular-session trade, from meta.regularMarketTime. */
    val marketTimeMillis: Long? = null,
    /**
     * IANA zone the listing trades in, from meta.exchangeTimezoneName
     * (e.g. "Europe/London" for the FTSE, "America/Toronto" for the TSX).
     *
     * Intraday charts have to be labelled in this zone, not the device's: a
     * London session runs 08:00–16:30 local, and rendering it in the viewer's
     * own timezone printed the FTSE as opening at 3:00 AM for a reader in
     * Toronto. Every finance app plots intraday in exchange-local time.
     */
    val exchangeTimezone: String? = null,
    /**
     * What the symbol actually is, as the provider classifies it —
     * "EQUITY", "ETF", "MUTUALFUND", "CRYPTOCURRENCY", "INDEX", "FUTURE".
     *
     * Carried so a holding filed under the wrong type can correct itself on the
     * next quote refresh instead of staying wrong forever. See
     * [ca.tristan.portfolio.data.db.HoldingType.fromQuoteType].
     */
    val instrumentType: String? = null,
    /**
     * Company logo image URL, when the provider has one — currently only
     * Finnhub's `/stock/profile2` supplies this, so it's null for indices,
     * most Canadian/foreign listings, futures and crypto. [ca.tristan.portfolio
     * .ui.components.TickerLogo] falls back to an initial-letter avatar when
     * this is null, so callers never need to special-case the gap.
     */
    val logoUrl: String? = null
)

/**
 * A secondary quote shown beneath the main price: either after-hours /
 * pre-market trading for a stock or ETF, or the index future that keeps
 * trading once the cash market closes.
 *
 * [label] is what the row prints ("Extended", "E-mini ES"), so the two cases
 * share one rendering path.
 */
data class ExtendedQuote(
    val label: String,
    val price: Double,
    val change: Double,
    val changePercent: Double,
    val atMillis: Long,
    /**
     * The symbol this secondary line is actually quoting — the future's own
     * ticker ("YM=F") for an index row, or the listing's ticker for an
     * after-hours print. Carried so [atMillis] can be labelled on the venue's
     * clock rather than the reader's, same as the main price above it.
     */
    val symbol: String? = null,
    /** IANA zone the quote above was struck in, when the provider reported one. */
    val zoneId: String? = null
)

/** One OHLCV bar returned by the history endpoint (close + optional volume). */
data class HistoryBar(val timestampMs: Long, val close: Double, val volume: Long?)

data class UpcomingDividend(
    val exDividendDateMillis: Long?,
    val payDateMillis: Long?,
    val estimatedAnnualRate: Double?, // trailing 12-month dividend rate, per share
    val yieldPercent: Double?,
    // How many times per year this holding pays (12=monthly, 4=quarterly, 2=semi-annual, 1=annual).
    // Null when frequency could not be determined (e.g. no confirmed history).
    val paymentFrequencyPerYear: Int? = null,
    /**
     * The per-share cash amount expected on the next payment.
     *
     * This is the number the Upcoming Dividends card should show. It is NOT
     * `estimatedAnnualRate / frequency`: many ETFs (XEQT, VBAL, ZEQT …) pay a
     * small Q1/Q3 and a large Q2/Q4, so dividing the annual rate evenly
     * over-states the small quarters by 3x or more. See
     * [YahooQuoteClient.inferUpcomingFromHistoryOrg] for how it is derived.
     *
     * Null when there is no usable history at all.
     */
    val perPaymentAmount: Double? = null,
    /**
     * True when [perPaymentAmount] is the amount the fund actually *declared*
     * for this payment (not a projection). The UI shows an "Announced" badge
     * instead of "Estimated" and stops applying its own forecast.
     */
    val isAnnounced: Boolean = false,
    /** Short human-readable description of how [perPaymentAmount] was derived. */
    val basisLabel: String? = null
)

/** One news headline from Yahoo Finance, shown in the Market News section. */
/** Editorial grouping used by the Market News feed's section headers. */
enum class NewsCategory(val label: String) {
    TOP("Top Stories"),
    MARKETS("Markets"),
    ECONOMY("Economy & Policy"),
    COMPANIES("Companies"),
    CANADA("Canada"),
    CRYPTO("Crypto")
}

data class NewsItem(
    val title: String,
    val publisher: String,
    val linkUrl: String,
    val publishedAt: Long,          // epoch millis
    val imageUrl: String? = null,   // thumbnail from <media:*> / <enclosure>
    val summary: String? = null,
    val category: NewsCategory = NewsCategory.MARKETS
)

/**
 * One row from a Yahoo Finance market-wide screener (most actives, day gainers,
 * day losers). Feeds the Dashboard's "Hot Stocks" tab.
 */
data class MarketMover(
    val ticker: String,
    val name: String?,
    val price: Double,
    val previousClose: Double,
    val changePct: Double,   // e.g. 4.72 means +4.72 %
    val exchange: String?,   // "NasdaqGS", "NYSE", "Toronto", etc. — used for flag display
    val volume: Long? = null,
    val averageVolume: Long? = null,
    /**
     * Epoch millis of the last trade, from the screener's regularMarketTime.
     *
     * The Hot Stocks tab used to print a price with no timestamp beside it at
     * all, which made a Friday close look identical to a live quote — the one
     * thing a "what's moving right now" list must not be ambiguous about.
     */
    val marketTimeMillis: Long? = null,
    /** meta/quote exchangeTimezoneName, so that time is labelled on the
     *  venue's clock rather than the reader's. */
    val exchangeTimezone: String? = null
) {
    /**
     * How much attention this name is getting today.
     *
     * Size of the move alone ranks a thinly-traded microcap that jumped 40% on
     * no volume above Nvidia on an earnings day, which is not what anyone means
     * by "hot". Multiplying the move by how unusual today's volume is against
     * the stock's own average fixes that: a name is hot when it moved AND the
     * market actually showed up to trade it.
     */
    val heat: Double
        get() {
            val move = kotlin.math.abs(changePct)
            val relVolume = if (volume != null && averageVolume != null && averageVolume > 0L) {
                (volume.toDouble() / averageVolume.toDouble()).coerceIn(0.25, 12.0)
            } else 1.0
            // log damps the volume term, so a 10x volume day counts for more
            // than a 2x day without swamping the price move entirely.
            return move * (1.0 + kotlin.math.ln(1.0 + relVolume))
        }
}

/** One match from the ticker search-as-you-type used by "Add a quote". */
data class SymbolSearchResult(
    val symbol: String,
    val name: String?,
    val exchange: String?,
    val quoteType: String?
)

/**
 * One row from the Nasdaq dividend calendar API — used to sync upcoming
 * dividend payments for the user's holdings automatically.
 */
data class NasdaqDividendRow(
    val symbol: String,
    val companyName: String,
    val exDateMs: Long?,
    val payDateMs: Long?,
    val amountPerShare: Double?
)

/**
 * One dividend entry parsed from dividendhistory.org/payout/{TICKER}/.
 *
 * The site lists both confirmed historical payments and upcoming estimated
 * payments (marked [isEstimated] = true). A single fetch gives 5+ years of
 * history PLUS the next 1-2 forecasted payments, making it the best single
 * source for both the auto-import and the Upcoming Dividends card.
 */
data class DividendHistoryOrgEntry(
    val exDateMs: Long,           // Ex-dividend date in epoch millis (UTC midnight)
    val payDateMs: Long?,         // Payment / record date (null if not listed)
    val amountPerShare: Double,   // Cash amount per share
    val isEstimated: Boolean      // true = "unconfirmed/estimated" (future projection)
)

/**
 * Live pricing for ETFs/stocks via Yahoo Finance's public chart endpoint.
 * Seg funds / mutual funds are NAV-only and are not looked up here — their
 * history comes from PriceSnapshotEntity rows the app records itself.
 */
object YahooQuoteClient {
    // Backoff is installed here, on the shared client, rather than at each
    // call site — there are around a dozen of those, and one missed would keep
    // hammering Yahoo on its own while the rest politely waited.
    private val client = OkHttpClient.Builder()
        .addInterceptor(RateLimitBackoff)
        // quoteSummary is cookie+crumb gated; the jar is shared with
        // YahooSession so the cookies it collects during the handshake travel
        // with the API calls that need them.
        .cookieJar(YahooSession.cookieJar)
        .build()

    /**
     * For hosts that have nothing to do with the pricing endpoint: the
     * dividend payout pages and the publisher RSS feeds.
     *
     * [RateLimitBackoff] exists to protect ONE undocumented host. Routing every
     * request through it meant a throttled price refresh also blocked the
     * dividend record and the news feed — the app's most important data taken
     * down by an unrelated endpoint's bad minute. Different site, different
     * client, no shared cooldown.
     */
    private val plainClient = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    // URL fragments assembled at runtime — keeps the full domain out of the
    // APK string table so it is not trivially visible in a decompiled build.
    private val h1  get() = buildString { append("quer"); append("y1.fi"); append("nance.ya"); append("hoo.com") }
    private val h2  get() = buildString { append("quer"); append("y2.fi"); append("nance.ya"); append("hoo.com") }
    private val crt get() = buildString { append("v8/fi"); append("nance/ch"); append("art") }
    private val qs  get() = buildString { append("v10/fi"); append("nance/qu"); append("oteSumm"); append("ary") }
    private val sp  get() = buildString { append("v8/fi"); append("nance/sp"); append("ark") }
    private val sc  get() = buildString { append("v1/fi"); append("nance/sc"); append("reener/pre"); append("defined/sa"); append("ved") }
    private val sr  get() = buildString { append("v1/fi"); append("nance/se"); append("arch") }
    private fun chartUrl(ticker: String, params: String)  = "https://$h1/$crt/$ticker?$params"
    private fun chartUrl2(ticker: String, params: String) = "https://$h2/$crt/$ticker?$params"
    /**
     * quoteSummary URL, with a crumb appended when Yahoo will issue one.
     *
     * Yahoo answers 401 `Invalid Crumb` for this endpoint without it, for every
     * symbol in every market — so both callers (dividend calendar, and the
     * logo resolver's website lookup) were returning nothing at all. When no
     * crumb can be obtained the URL is built without one and the call fails the
     * same way it used to, rather than not being made.
     */
    private fun qsUrl(ticker: String, modules: String): String {
        val base = "https://$h2/$qs/$ticker?modules=$modules"
        val crumb = YahooSession.crumb() ?: return base
        return "$base&crumb=" + java.net.URLEncoder.encode(crumb, "UTF-8")
    }
    private fun sparkUrl(symbols: String, range: String, interval: String) =
        "https://$h1/$sp?symbols=$symbols&range=$range&interval=$interval"
    private fun screenerUrl(scrId: String) =
        "https://$h1/$sc?formatted=false&scrIds=$scrId&start=0&count=25"
    private fun searchUrl(q: String) =
        "https://$h1/$sr?q=$q&quotesCount=20&newsCount=0&listsCount=0"
    private fun searchNewsUrl(q: String) =
        "https://$h1/$sr?q=$q&newsCount=20&quotesCount=0&listsCount=0"

    /**
     * quoteSummary URL for the company-profile module, exposed for
     * [LogoResolver] so the host/path assembly stays in one place.
     */
    internal fun assetProfileUrl(ticker: String) = qsUrl(ticker, "assetProfile")

    /** Shared OkHttp client, so a sibling in this package reuses the pool. */
    internal val httpClient: OkHttpClient get() = client

    // range/interval chosen small since we only need the latest price + prev close
    fun fetch(ticker: String): Quote? {
        // ── Primary source: Finance Query ────────────────────────────────────
        // A server in front of the same public data, with a cache. It goes
        // first because it needs no key, covers the TSX (which Finnhub's free
        // tier does not), and absorbs the rate limiting that otherwise lands
        // on the handset. See [FinanceQueryClient].
        FinanceQueryClient.fetchQuote(ticker)?.let { return it }

        // ── Second: Finnhub, when a key is configured ────────────────────────
        if (FinnhubQuoteClient.isAvailable()) {
            FinnhubQuoteClient.fetch(ticker)?.let { return it }
        }

        // ── Last: the direct call this class was built around ────────────────
        return fetchFromYahoo(ticker)
    }

    /**
     * Quotes for many symbols in ONE request, or an empty map when the server
     * is unreachable.
     *
     * Exposed so price-refresh loops can fill a whole watchlist with a single
     * call and fall back per-symbol only for what is missing, instead of firing
     * one request per row.
     */
    fun fetchQuotes(tickers: List<String>): Map<String, Quote> =
        FinanceQueryClient.fetchQuotes(tickers)

    /**
     * Best-effort logo URL for [ticker], for rows that only need an icon
     * rather than a whole [Quote] (search results, list rows already priced
     * from a cheaper batch call).
     *
     * [name] is the security's own name when the caller has it — it lets
     * [LogoResolver] identify a fund's issuer without any network call, which
     * is what gives non-US listings a logo at all.
     */
    fun fetchLogo(ticker: String, name: String? = null): String? =
        LogoResolver.logoFor(ticker, name)

    /**
     * A full quote straight from Yahoo, skipping the Finnhub fast path.
     *
     * Finnhub's /quote endpoint carries no 52-week range and no instrument
     * type, so a US listing — which Finnhub answers for — arrived with those
     * fields null and the holding screen hid its 52-week band entirely, while
     * a Canadian listing (which falls through to Yahoo anyway) showed one.
     * Callers that need the whole stat sheet use this to fill the gaps in;
     * the cheap path above is still what the price-refresh loops use.
     */
    fun fetchFromYahoo(ticker: String): Quote? {
        // range=2d/interval=1d keeps the response tiny (a couple of points) — every
        // field below comes from `meta`, so the old range=1d&interval=1m (≈390
        // intraday points per ticker) was downloading ~50x more than it used.
        val url = chartUrl(ticker, "range=2d&interval=1d")
        val request = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val root = JSONObject(body)
                val result = root.getJSONObject("chart").getJSONArray("result").getJSONObject(0)
                val meta = result.getJSONObject("meta")
                val price = meta.optDouble("regularMarketPrice", Double.NaN)
                if (price.isNaN()) return null
                val prevClose = meta.optDouble("chartPreviousClose", Double.NaN)
                val name = meta.optString("shortName", null)
                fun opt(key: String): Double? = meta.optDouble(key, Double.NaN).takeIf { !it.isNaN() }
                fun optLong(key: String): Long? = if (meta.has(key) && !meta.isNull(key)) meta.optLong(key) else null
                val currency = meta.optString("currency", null)?.takeIf { it.isNotBlank() }
                Quote(
                    price = price,
                    name = name,
                    previousClose = if (prevClose.isNaN()) null else prevClose,
                    currency = currency,
                    dayHigh = opt("regularMarketDayHigh"),
                    dayLow = opt("regularMarketDayLow"),
                    open = opt("regularMarketOpen"),
                    volume = optLong("regularMarketVolume"),
                    fiftyTwoWeekHigh = opt("fiftyTwoWeekHigh"),
                    fiftyTwoWeekLow = opt("fiftyTwoWeekLow"),
                    avgVolume3Month = optLong("averageDailyVolume3Month"),
                    marketTimeMillis = optLong("regularMarketTime")?.takeIf { it > 0 }?.times(1000L),
                    exchangeTimezone = meta.optString("exchangeTimezoneName", null)
                        ?.takeIf { it.isNotBlank() },
                    instrumentType = meta.optString("instrumentType", null)
                        ?.takeIf { it.isNotBlank() }
                )
            }
        } catch (_: IOException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    // range is one of: 1d,5d,1mo,6mo,ytd,1y,5y,max — matches the chart ranges
    // the home screen chip row offers (1D/1W/1M/6M/YTD/1Y/5Y/All)
    fun fetchHistory(ticker: String, range: String, interval: String): List<Pair<Long, Double>> {
        // ── Primary source: Finance Query ────────────────────────────────────
        FinanceQueryClient.fetchHistoryBars(ticker, range, interval)
            .takeIf { it.isNotEmpty() }
            ?.let { bars -> return bars.map { it.timestampMs to it.close } }

        // ── Second: Finnhub ──────────────────────────────────────────────────
        if (FinnhubQuoteClient.isAvailable()) {
            FinnhubQuoteClient.fetchHistory(ticker, range, interval)
                .takeIf { it.isNotEmpty() }?.let { return it }
        }

        // ── Fallback: Yahoo Finance ───────────────────────────────────────────
        val url = chartUrl(ticker, "range=$range&interval=$interval")
        val request = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val body = response.body?.string() ?: return emptyList()
                val root = JSONObject(body)
                val result = root.getJSONObject("chart").getJSONArray("result").getJSONObject(0)
                val timestamps = result.optJSONArray("timestamp") ?: return emptyList()
                val closes = result.getJSONObject("indicators").getJSONArray("quote")
                    .getJSONObject(0).optJSONArray("close") ?: return emptyList()
                val out = ArrayList<Pair<Long, Double>>()
                for (i in 0 until timestamps.length()) {
                    if (closes.isNull(i)) continue
                    out.add(timestamps.getLong(i) * 1000L to closes.getDouble(i))
                }
                out
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Like fetchHistory but also returns per-bar volume — used for the
     * Apple-Stocks-style area chart on the QuoteDetailScreen.
     */
    // ── Chart bar cache ──────────────────────────────────────────────────────
    //
    // Keyed on ticker + range + interval, in memory.
    //
    // The chip row invites exactly the access pattern a cache is for: tap 1M,
    // tap 1Y, tap back to 1M. Uncached, that third tap is a fresh round-trip
    // for data fetched seconds earlier, and the chart blanks while it waits.
    // The portfolio value series compounds it — one fetch per security per
    // range change.
    //
    // TTLs follow how fast the data can actually change: an intraday range is
    // genuinely moving, a daily series gains one bar per session.
    private class CachedBars(val bars: List<HistoryBar>, val atMs: Long)

    private val barCache = java.util.concurrent.ConcurrentHashMap<String, CachedBars>()
    private const val BAR_CACHE_LIMIT = 120

    private fun barTtlMs(range: String): Long = when (range.lowercase()) {
        "1d" -> 60_000L                  // a live session
        "5d", "1w" -> 300_000L           // still intraday, coarser
        else -> 6L * 60 * 60 * 1000      // daily bars and longer
    }

    /**
     * Drops cached bars for one ticker, or all of them.
     *
     * The bars are a ticker's PRICE history and don't depend on how much of it
     * the user owns — units are applied when the series is built — so a unit
     * change needs no invalidation. What does need it is pull-to-refresh:
     * without this, a six-hour TTL would mean the chart ignored the gesture.
     */
    fun invalidateBarCache(ticker: String? = null) {
        if (ticker == null) {
            barCache.clear()
            return
        }
        val prefix = ticker.trim().uppercase() + "|"
        barCache.keys.filter { it.startsWith(prefix) }.forEach { barCache.remove(it) }
    }

    fun fetchHistoryBars(ticker: String, range: String, interval: String): List<HistoryBar> {
        val key = "${ticker.trim().uppercase()}|$range|$interval"
        barCache[key]?.let { hit ->
            if (System.currentTimeMillis() - hit.atMs < barTtlMs(range)) return hit.bars
        }
        val fresh = fetchHistoryBarsUncached(ticker, range, interval)
        // Only a real answer is cached. Caching an empty result would hold a
        // failed request in place for the whole TTL, turning one bad minute
        // into six hours of an empty chart.
        if (fresh.isNotEmpty()) {
            if (barCache.size >= BAR_CACHE_LIMIT) {
                // Cheap eviction: keep the newest half rather than track exact
                // LRU order for something this small.
                barCache.entries
                    .sortedBy { it.value.atMs }
                    .take(barCache.size - BAR_CACHE_LIMIT / 2)
                    .forEach { barCache.remove(it.key) }
            }
            barCache[key] = CachedBars(fresh, System.currentTimeMillis())
        }
        return fresh
    }

    private fun fetchHistoryBarsUncached(
        ticker: String,
        range: String,
        interval: String
    ): List<HistoryBar> {
        // ── Primary source: Finance Query ────────────────────────────────────
        FinanceQueryClient.fetchHistoryBars(ticker, range, interval)
            .takeIf { it.isNotEmpty() }?.let { return it }

        // ── Second: Finnhub ──────────────────────────────────────────────────
        if (FinnhubQuoteClient.isAvailable()) {
            FinnhubQuoteClient.fetchHistoryBars(ticker, range, interval)
                .takeIf { it.isNotEmpty() }?.let { return it }
        }

        // ── Fallback: Yahoo Finance ───────────────────────────────────────────
        val url = chartUrl(ticker, "range=$range&interval=$interval")
        val request = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .header("Accept", "application/json")
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val body = response.body?.string() ?: return emptyList()
                val root = JSONObject(body)
                val result = root.getJSONObject("chart").getJSONArray("result").getJSONObject(0)
                val timestamps = result.optJSONArray("timestamp") ?: return emptyList()
                val quoteArr = result.getJSONObject("indicators").getJSONArray("quote").getJSONObject(0)
                val closes = quoteArr.optJSONArray("close") ?: return emptyList()
                val volumes = quoteArr.optJSONArray("volume")
                val out = ArrayList<HistoryBar>()
                for (i in 0 until timestamps.length()) {
                    if (closes.isNull(i)) continue
                    val vol = if (volumes != null && !volumes.isNull(i)) volumes.getLong(i) else null
                    out.add(HistoryBar(timestamps.getLong(i) * 1000L, closes.getDouble(i), vol))
                }
                out
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Upcoming dividend info for the Dividends tab's "Upcoming" section —
     * next ex-dividend date, next pay date, and a rough estimated annual
     * rate/yield, from Yahoo's quoteSummary calendarEvents + summaryDetail
     * modules. Returns null for tickers with no dividend data (most
     * non-dividend-paying stocks, or a bad ticker).
     */
/**
     * Price + intraday sparkline for one symbol, as returned by the batched
     * [fetchSparkBatch] call.
     */
    data class SparkQuote(
        val ticker: String,
        val price: Double,
        val previousClose: Double?,
        val closes: List<Float>,
        /**
         * Epoch millis of the last trade, straight from this same batched
         * response (`meta.regularMarketTime`, falling back to the last entry
         * of the parallel `timestamp` array the spark endpoint also carries).
         *
         * Rows used to show no time at all until a slower, separate per-ticker
         * quote call filled it in — and for a handful of non-North-American
         * indices (DAX, Nikkei, Hang Seng, ASX 200) that follow-up call
         * reliably came back empty, so their rows never got a time string.
         * Reading it directly off the batch, which is the same call that
         * already supplied the price everyone could see, means every row gets
         * a real trade time immediately rather than depending on a second
         * network round-trip that isn't equally reliable for every symbol.
         */
        val marketTimeMillis: Long? = null,
        /**
         * meta.exchangeTimezoneName, so a batched row can label its last-trade
         * time on the exchange's clock without a second per-ticker request.
         */
        val exchangeTimezone: String? = null
    )

    /**
     * Fetches last price, previous close and an intraday close series for MANY
     * symbols in a SINGLE request.
     *
     * The dashboard used to issue two sequential requests per ticker (a chart
     * call for the quote plus another for the sparkline) — ~22 round-trips for
     * an 11-row watchlist before anything appeared. This collapses all of that
     * into one call.
     *
     * Uses the v8 `spark` endpoint, which — like v8 `chart` — is still open.
     * (v7 `quote`, the obvious batch endpoint, now requires a crumb/cookie, so
     * it is deliberately not used here.)
     *
     * Returns an empty map on any failure so callers can fall back to
     * per-ticker [fetch] calls.
     */
    fun fetchSparkBatch(
        tickers: List<String>,
        range: String = "1d",
        interval: String = "30m"
    ): Map<String, SparkQuote> {
        if (tickers.isEmpty()) return emptyMap()
        val out = mutableMapOf<String, SparkQuote>()

        // Keep URLs well under any server-side length limit.
        for (chunk in tickers.distinct().chunked(40)) {
            val symbols = chunk.joinToString(",") { java.net.URLEncoder.encode(it, "UTF-8") }
            val url = sparkUrl(symbols, range, interval)
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
                .header("Accept", "application/json")
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val body = response.body?.string() ?: return@use
                    val root = JSONObject(body)

                    // The endpoint has shipped two shapes over the years:
                    //   A) { "SYMBOL": { symbol, close[], previousClose, ... }, ... }
                    //   B) { "spark": { "result": [ { "symbol":…, "response":[ {…} ] } ] } }
                    val resultArray = root.optJSONObject("spark")?.optJSONArray("result")
                    if (resultArray != null) {
                        for (i in 0 until resultArray.length()) {
                            val entry = resultArray.optJSONObject(i) ?: continue
                            val sym = entry.optString("symbol", null) ?: continue
                            val resp = entry.optJSONArray("response")?.optJSONObject(0) ?: continue
                            parseSparkNode(sym, resp)?.let { out[it.ticker] = it }
                        }
                    } else {
                        for (sym in root.keys()) {
                            val node = root.optJSONObject(sym) ?: continue
                            parseSparkNode(sym, node)?.let { out[it.ticker] = it }
                        }
                    }
                }
            } catch (_: Exception) {
                // fall through — caller degrades to per-ticker fetch
            }
        }
        return out
    }

    /** Reads one symbol's node from a spark response into a [SparkQuote]. */
    private fun parseSparkNode(symbol: String, node: JSONObject): SparkQuote? {
        // Shape A puts close[] at the top level; shape B nests it under
        // indicators.quote[0].close, same as the chart endpoint.
        val closeArray = node.optJSONArray("close")
            ?: node.optJSONObject("indicators")?.optJSONArray("quote")
                ?.optJSONObject(0)?.optJSONArray("close")
            ?: return null

        val closes = ArrayList<Float>(closeArray.length())
        for (i in 0 until closeArray.length()) {
            if (closeArray.isNull(i)) continue
            val v = closeArray.optDouble(i, Double.NaN)
            if (!v.isNaN()) closes.add(v.toFloat())
        }
        if (closes.isEmpty()) return null

        val meta = node.optJSONObject("meta")
        val prev = sequenceOf(
            node.optDouble("previousClose", Double.NaN),
            node.optDouble("chartPreviousClose", Double.NaN),
            meta?.optDouble("chartPreviousClose", Double.NaN) ?: Double.NaN,
            meta?.optDouble("previousClose", Double.NaN) ?: Double.NaN
        ).firstOrNull { !it.isNaN() }

        val price = sequenceOf(
            meta?.optDouble("regularMarketPrice", Double.NaN) ?: Double.NaN,
            node.optDouble("regularMarketPrice", Double.NaN)
        ).firstOrNull { !it.isNaN() } ?: closes.last().toDouble()

        // Prefer meta.regularMarketTime (an instant, not a bar boundary); if a
        // particular shape omits it, fall back to the last non-null entry of
        // the parallel `timestamp` array every spark response carries — it's
        // the same clock, just read from the series instead of the header.
        val marketTimeMillis = (meta?.optLong("regularMarketTime", 0L) ?: 0L)
            .takeIf { it > 0L }
            ?: node.optJSONArray("timestamp")?.let { ts ->
                for (i in ts.length() - 1 downTo 0) {
                    if (!ts.isNull(i)) {
                        val t = ts.optLong(i, 0L)
                        if (t > 0L) return@let t
                    }
                }
                null
            }

        return SparkQuote(
            ticker = node.optString("symbol", symbol).ifBlank { symbol },
            price = price,
            previousClose = prev,
            closes = closes,
            marketTimeMillis = marketTimeMillis?.times(1000L),
            exchangeTimezone = meta?.optString("exchangeTimezoneName", null)
                ?.takeIf { it.isNotBlank() }
        )
    }

/**
     * The E-mini future that tracks each cash index.
     *
     * The cash indices stop printing at the close, but their futures trade
     * nearly around the clock — which is the number people actually want at
     * 10pm. Yahoo carries them under these continuous-front-month symbols.
     */
    private val FUTURES_FOR_INDEX = mapOf(
        "^DJI"  to ("YM=F" to "E-mini YM"),
        "^GSPC" to ("ES=F" to "E-mini ES"),
        "^IXIC" to ("NQ=F" to "E-mini NQ"),
        "^RUT"  to ("RTY=F" to "E-mini RTY")
    )

    /** True when [ticker] is an index this client can pair with a future. */
    fun hasFuture(ticker: String): Boolean = FUTURES_FOR_INDEX.containsKey(ticker.uppercase())

    /** Yahoo symbols for every index future, for a single batched fetch. */
    fun futureSymbols(): List<String> = FUTURES_FOR_INDEX.values.map { it.first }

    /** Maps an index to (futureSymbol, displayLabel), or null when there isn't one. */
    fun futureFor(ticker: String): Pair<String, String>? = FUTURES_FOR_INDEX[ticker.uppercase()]

    /**
     * Pre-market / after-hours quote for [ticker], or null when the extended
     * session has nothing newer than the regular close.
     *
     * Uses the open v8 chart endpoint with `includePrePost=true` rather than
     * quoteSummary's postMarketPrice fields, because quoteSummary now requires
     * a crumb. The extended price is the last non-null close stamped after
     * `meta.regularMarketTime`; the change is measured against the regular
     * close, which is what "Extended: +0.68 (+0.09%)" means on a broker app.
     */
    fun fetchExtendedQuote(ticker: String): ExtendedQuote? {
        // The pre/post fields ride along on the server's quote payload, so this
        // costs one small request where the fallback below downloads a whole
        // intraday chart and walks it backwards to find the last print.
        FinanceQueryClient.fetchExtendedQuote(ticker)?.let { return it }

        val url = chartUrl(ticker, "range=1d&interval=5m&includePrePost=true")
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val root = JSONObject(body)
                val result = root.optJSONObject("chart")?.optJSONArray("result")
                    ?.optJSONObject(0) ?: return null
                val meta = result.optJSONObject("meta") ?: return null

                val regularClose = meta.optDouble("regularMarketPrice", Double.NaN)
                if (regularClose.isNaN()) return null
                val regularTimeSec = meta.optLong("regularMarketTime", 0L)
                if (regularTimeSec <= 0L) return null

                val stamps = result.optJSONArray("timestamp") ?: return null
                val closes = result.optJSONObject("indicators")?.optJSONArray("quote")
                    ?.optJSONObject(0)?.optJSONArray("close") ?: return null

                // Walk backwards for the newest post-close print.
                var i = minOf(stamps.length(), closes.length()) - 1
                while (i >= 0) {
                    val ts = stamps.optLong(i, 0L)
                    if (ts > regularTimeSec && !closes.isNull(i)) {
                        val px = closes.optDouble(i, Double.NaN)
                        if (!px.isNaN() && px > 0.0) {
                            val change = px - regularClose
                            // Ignore a print that is identical to the close —
                            // that's the session boundary, not real trading.
                            if (kotlin.math.abs(change) < 1e-9) return null
                            return ExtendedQuote(
                                label = "Extended",
                                price = px,
                                change = change,
                                changePercent = if (regularClose != 0.0)
                                    change / regularClose * 100.0 else 0.0,
                                atMillis = ts * 1000L,
                                symbol = ticker,
                                zoneId = meta.optString("exchangeTimezoneName", null)
                                    ?.takeIf { it.isNotBlank() }
                            )
                        }
                    }
                    i--
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }

        fun fetchUpcomingDividend(ticker: String): UpcomingDividend? {
        val url = qsUrl(ticker, "calendarEvents,summaryDetail")
        val request = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val root = JSONObject(body)
                val results = root.getJSONObject("quoteSummary").optJSONArray("result") ?: return null
                if (results.length() == 0) return null
                val result = results.getJSONObject(0)

                val calendarEvents = result.optJSONObject("calendarEvents")
                val exDate = calendarEvents?.optJSONObject("exDividendDate")?.optLong("raw", -1)
                    ?.takeIf { it > 0 }?.times(1000L)
                val payDate = calendarEvents?.optJSONObject("dividendDate")?.optLong("raw", -1)
                    ?.takeIf { it > 0 }?.times(1000L)

                val summaryDetail = result.optJSONObject("summaryDetail")
                val rate = summaryDetail?.optJSONObject("dividendRate")?.optDouble("raw", Double.NaN)
                    ?.takeIf { !it.isNaN() }
                val yieldFrac = summaryDetail?.optJSONObject("dividendYield")?.optDouble("raw", Double.NaN)
                    ?.takeIf { !it.isNaN() }

                if (exDate == null && payDate == null && rate == null) return null

                // Yahoo only gives an annual rate. Run the same seasonal
                // estimator over its historical dividend events so this path
                // produces a realistic per-payment figure too, rather than
                // letting the UI divide the annual rate evenly.
                val history = fetchHistoricalDividendEvents(ticker)
                val inferred = if (history.size >= 2) {
                    inferUpcomingFromHistoryOrg(
                        history.map { DividendHistoryOrgEntry(it.first, null, it.second, false) }
                            .sortedByDescending { it.exDateMs }
                    )
                } else null

                // Yahoo's calendarEvents reports the *last* ex-date for many
                // non-US listings rather than the next one, so a date already
                // in the past is not an upcoming payment — the projection is.
                val now = System.currentTimeMillis()
                UpcomingDividend(
                    exDividendDateMillis = exDate?.takeIf { it > now }
                        ?: inferred?.exDividendDateMillis ?: exDate,
                    payDateMillis = payDate?.takeIf { it > now }
                        ?: inferred?.payDateMillis ?: payDate,
                    estimatedAnnualRate = rate ?: inferred?.estimatedAnnualRate,
                    yieldPercent = yieldFrac?.times(100.0),
                    paymentFrequencyPerYear = inferred?.paymentFrequencyPerYear,
                    perPaymentAmount = inferred?.perPaymentAmount,
                    isAnnounced = false,
                    basisLabel = inferred?.basisLabel ?: "annual rate ÷ frequency"
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Runs each of [tasks] on its own thread and merges whatever comes back
     * within [timeoutSeconds]. News feeds and searches are independent of one
     * another, so fetching them one after another — as this used to do — meant
     * a single slow or dead host stalled every feed queued behind it: with a
     * default ~10s timeout per call and up to a dozen sources, a couple of
     * ailing hosts were enough to make the whole fetch feel like it returned
     * "almost nothing", when most of the working sources simply never got a
     * turn in time. A task that fails or times out just contributes nothing,
     * it never drags the others down with it.
     */
    private fun <T> runParallel(tasks: List<() -> List<T>>, timeoutSeconds: Long = 10): List<T> {
        if (tasks.isEmpty()) return emptyList()
        val pool = Executors.newFixedThreadPool(minOf(tasks.size, 12))
        return try {
            val futures = tasks.map { task ->
                pool.submit(Callable { runCatching(task).getOrDefault(emptyList()) })
            }
            val out = ArrayList<T>()
            for (f in futures) {
                out.addAll(runCatching { f.get(timeoutSeconds, TimeUnit.SECONDS) }.getOrDefault(emptyList()))
            }
            out
        } finally {
            pool.shutdownNow()
        }
    }

    /**
     * Yahoo Finance's JSON news-search endpoint (the one Yahoo's own web and
     * mobile clients still use), parsed into [NewsItem]s for a free-text or
     * per-symbol [query].
     *
     * This used to be wired in only as a last-resort fallback for when every
     * RSS feed failed. It is now a first-class source for both market and
     * dividend news, because the classic `feeds.finance.yahoo.com/rss/2.0/headline`
     * feed this class used to lean on for per-symbol headlines was retired by
     * Yahoo years ago and now just returns an empty channel — a silent
     * failure indistinguishable from "no news today" — which is a large part
     * of why symbol-specific feeds (and dividend news, which depended on it
     * for the user's own holdings) ran so thin.
     */
    private fun fetchYahooNewsSearch(query: String): List<NewsItem> {
        val req = Request.Builder()
            .url(searchNewsUrl(query))
            .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36")
            .header("Accept", "application/json")
            .build()
        return try {
            client.newCall(req).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val body = response.body?.string() ?: return emptyList()
                val root = JSONObject(body)
                val news = root.optJSONArray("news") ?: return emptyList()
                val out = ArrayList<NewsItem>()
                for (i in 0 until news.length()) {
                    val n = news.getJSONObject(i)
                    val title = n.optString("title").takeIf { it.isNotBlank() } ?: continue
                    val publisher = n.optString("publisher").takeIf { it.isNotBlank() } ?: "Market news"
                    val link = n.optString("link").takeIf { it.isNotBlank() } ?: continue
                    val time = n.optLong("providerPublishTime", 0L)
                        .let { if (it > 0) it * 1000L else System.currentTimeMillis() }
                    // Yahoo's search payload carries thumbnails under
                    // thumbnail.resolutions[] — take the largest available.
                    val thumb = n.optJSONObject("thumbnail")
                        ?.optJSONArray("resolutions")
                        ?.let { res ->
                            var best: String? = null
                            var bestW = -1
                            for (r in 0 until res.length()) {
                                val o = res.optJSONObject(r) ?: continue
                                val w = o.optInt("width", 0)
                                val u = o.optString("url", "")
                                if (u.isNotBlank() && w > bestW) { bestW = w; best = u }
                            }
                            best
                        }
                    out.add(
                        NewsItem(
                            title = title,
                            publisher = publisher,
                            linkUrl = link,
                            publishedAt = time,
                            imageUrl = thumb?.let { normalizeImageUrl(it) }?.ifBlank { null },
                            category = categorise(title, publisher)
                        )
                    )
                }
                out.filter { isFreelyReadable(it) }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Market news for the reader's own market, plus the US market that moves
     * everything else — aggregated from multiple RSS sources (Reuters, CNBC,
     * MarketWatch, NPR, regional outlets) plus Yahoo's JSON news search, all
     * fetched concurrently. Results are merged, deduplicated by URL, and
     * sorted newest-first.
     *
     * [homeCountry] is an ISO alpha-2 code resolved from the device's network
     * region (see `MarketIndices.deviceCountry()`). US feeds are always
     * included — a Canadian or British investor still needs to know what the
     * S&P did — with the local outlets layered on top.
     */
    fun fetchMarketNews(homeCountry: String? = null): List<NewsItem> {
        // Always-on core: US markets and the wires. A spread of outlets so the
        // feed reads like a news app rather than one service.
        val usFeeds = listOf(
            // CNBC — markets, economy, finance, top news, investing, earnings.
            // All fetched concurrently, so a wider list costs latency only
            // when a host is slow, not once per feed.
            "https://www.cnbc.com/id/10000664/device/rss/rss.html",
            "https://www.cnbc.com/id/20910258/device/rss/rss.html",
            "https://www.cnbc.com/id/10000115/device/rss/rss.html",
            "https://www.cnbc.com/id/100003114/device/rss/rss.html",
            "https://www.cnbc.com/id/10001147/device/rss/rss.html",
            "https://www.cnbc.com/id/15839135/device/rss/rss.html",
            // Nasdaq's outbound desks. No key, no bot challenge, 15–20 fresh
            // items each, and the categories map onto this app's own section
            // headings. They replace the MarketWatch/Dow Jones feeds that used
            // to sit here: those hosts are geo-gated and answered most devices
            // with nothing at all, which is a silent failure — the feed simply
            // looked quiet.
            "https://www.nasdaq.com/feed/rssoutbound?category=Markets",
            "https://www.nasdaq.com/feed/rssoutbound?category=Stocks",
            "https://www.nasdaq.com/feed/rssoutbound?category=Earnings",
            "https://www.nasdaq.com/feed/rssoutbound?category=Economy",
            "https://www.nasdaq.com/feed/rssoutbound?category=ETFs",
            "https://www.nasdaq.com/feed/rssoutbound?category=Cryptocurrencies",
            // Yahoo Finance's own site feed (not the retired per-symbol one)
            "https://finance.yahoo.com/news/rssindex",
            // NPR business — reliably free to read
            "https://feeds.npr.org/1006/rss.xml"
        )

        // Local coverage for the reader's own market, appended to the US core.
        val localFeeds = when (homeCountry?.uppercase()) {
            // financialpost.com used to be first here and never once answered:
            // it sits behind the same bot challenge as investing.com and
            // returns 403 to anything that is not a browser.
            "CA" -> listOf(
                "https://www.cbc.ca/webfeed/rss/rss-business"
            )
            "GB", "UK" -> listOf(
                "https://feeds.bbci.co.uk/news/business/rss.xml",
                "https://www.theguardian.com/uk/business/rss"
            )
            "AU" -> listOf(
                "https://www.abc.net.au/news/feed/51892/rss.xml",
                "https://thewest.com.au/business/rss"
            )
            "IN" -> listOf(
                "https://economictimes.indiatimes.com/markets/rssfeeds/1977021501.cms",
                "https://www.thehindubusinessline.com/markets/feeder/default.rss"
            )
            "DE" -> listOf("https://www.tagesschau.de/wirtschaft/index~rss2.xml")
            "SG" -> listOf("https://www.channelnewsasia.com/api/v1/rss-outbound-feed?_format=xml")
            "PH" -> listOf(
                "https://business.inquirer.net/feed",
                "https://www.philstar.com/rss/business"
            )
            "NZ" -> listOf("https://www.rnz.co.nz/rss/business.xml")
            "IE" -> listOf("https://www.rte.ie/feeds/rss/?index=/news/business/")
            "ZA" -> listOf("https://www.news24.com/fin24/rss")
            // Unknown or US: BBC Business still gives useful non-US context.
            else -> listOf("https://feeds.bbci.co.uk/news/business/rss.xml")
        }

        val rssFeeds = usFeeds + localFeeds

        val seen = LinkedHashSet<String>()
        val combined = ArrayList<NewsItem>()

        fun addAll(items: List<NewsItem>) {
            for (item in items) {
                if (!isFreelyReadable(item)) continue
                if (seen.add(item.linkUrl)) combined.add(item)
            }
        }

        // Every RSS feed plus a general-market JSON search, all fetched at
        // once rather than one host at a time (see [runParallel]).
        val tasks: List<() -> List<NewsItem>> = rssFeeds.map { feedUrl ->
            {
                val req = Request.Builder()
                    .url(feedUrl)
                    .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36")
                    .header("Accept", "application/rss+xml, application/xml, text/xml, */*")
                    .build()
                plainClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) parseRssNews(resp.body?.string() ?: "") else emptyList()
                }
            }
        } + listOf<() -> List<NewsItem>>({ fetchYahooNewsSearch("stock+market") })

        addAll(runParallel(tasks))

        if (combined.isNotEmpty()) {
            // Keep the feed to the last 7 days. Items with an unparseable date
            // (publishedAt == 0) are kept rather than silently dropped — better
            // a headline with a vague timestamp than a half-empty feed.
            val cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
            val recent = combined.filter { it.publishedAt == 0L || it.publishedAt >= cutoff }
            val sorted = (if (recent.isNotEmpty()) recent else combined)
                .sortedByDescending { it.publishedAt }
            // Promote the newest handful that have artwork into "Top Stories",
            // so the lead section of the feed always has images to show.
            val leadIds = sorted.filter { it.imageUrl != null }.take(5)
                .map { it.linkUrl }.toSet()
            return sorted.map {
                if (it.linkUrl in leadIds) it.copy(category = NewsCategory.TOP) else it
            }
        }

        return emptyList()
    }

    /**
     * Publishers that put an account wall in front of the article body.
     *
     * Tapping one of these opens "Create a FREE account or log in to continue
     * reading" instead of the story, which is a dead end inside an in-app
     * browser — the reader cannot sign in usefully and has no way back to the
     * article. A headline that cannot be read is worse than no headline, so
     * these are dropped at the feed level rather than shown and then failing.
     *
     * Matched against the article's host, so subdomains (uk.investing.com,
     * www.wsj.com) are covered by the base entry.
     */
    private val ACCOUNT_WALLED_HOSTS = listOf(
        "investing.com",       // hard registration wall — the one users hit most
        "seekingalpha.com",    // registration wall after one article
        "wsj.com",
        "barrons.com",
        "ft.com",
        "bloomberg.com",
        "economist.com",
        "nytimes.com",
        "washingtonpost.com",
        "thetimes.co.uk",
        "telegraph.co.uk",
        "theaustralian.com.au",
        "afr.com",
        "businessinsider.com", // metered wall that trips almost immediately
        "morningstar.com",
        "spglobal.com",
        "theinformation.com"
    )

    /** Headline text that signals the link goes to a subscribe/register page. */
    private val WALLED_TITLE_HINTS = listOf(
        "subscriber only", "subscribers only", "sign in to read",
        "log in to continue", "premium:", "[paywall]"
    )

    /**
     * True when the article is expected to open and be readable without an
     * account. Used to keep account-walled stories out of the feed entirely.
     */
    private fun isFreelyReadable(item: NewsItem): Boolean {
        val host = runCatching {
            java.net.URI(item.linkUrl).host?.lowercase()?.removePrefix("www.")
        }.getOrNull() ?: return true   // unparseable URL: let it through, the browser will cope

        if (ACCOUNT_WALLED_HOSTS.any { host == it || host.endsWith(".$it") }) return false

        val title = item.title.lowercase()
        if (WALLED_TITLE_HINTS.any { it in title }) return false

        // Some aggregators keep the real publisher only in the publisher field.
        val publisher = item.publisher.lowercase()
        if (ACCOUNT_WALLED_HOSTS.any { publisher.contains(it.substringBefore(".")) &&
                it.substringBefore(".").length > 4 }) return false

        return true
    }

    /** Strips tags and unescapes the few entities RSS descriptions actually use. */
    private fun stripHtml(raw: String): String =
        raw.replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ").replace("&amp;", "&")
            .replace("&quot;", "\"").replace("&#39;", "'")
            .replace("&lt;", "<").replace("&gt;", ">")
            .replace(Regex("\\s+"), " ")
            .trim()

    /** Pulls the first <img src> out of an HTML description block. */
    private fun firstImageInHtml(html: String): String =
        Regex("""<img[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1) ?: ""

    /**
     * Makes a feed's image URL loadable by the app.
     *
     * The app's network security config forbids cleartext traffic, so an
     * `http://` thumbnail is refused by the platform and the card renders with
     * a blank space and no error. Plenty of feeds still emit http or
     * protocol-relative `//host/path` URLs; every CDN they point at serves the
     * same asset over TLS, so upgrading the scheme fixes the image without
     * weakening the cleartext policy.
     */
    private fun normalizeImageUrl(raw: String): String {
        val url = raw.trim()
        if (url.isBlank()) return ""
        return when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("http://") -> "https://" + url.removePrefix("http://")
            else -> url
        }
    }

    /**
     * Buckets a headline into a [NewsCategory] for the feed's section headers.
     * Keyword matching is deliberately simple — this is editorial grouping for
     * a reading UI, not classification anything depends on.
     */
    private fun categorise(title: String, publisher: String): NewsCategory {
        val t = title.lowercase()
        val p = publisher.lowercase()
        return when {
            listOf("bitcoin", "crypto", "ethereum", "blockchain", "token")
                .any { it in t } -> NewsCategory.CRYPTO
            listOf("canada", "canadian", "bank of canada", "tsx", "loonie", "ottawa")
                .any { it in t } || "financial post" in p || "bnn" in p -> NewsCategory.CANADA
            listOf("fed", "inflation", "rate cut", "rates", "tariff", "gdp",
                   "jobs report", "unemployment", "central bank", "treasury", "policy")
                .any { it in t } -> NewsCategory.ECONOMY
            listOf("earnings", "revenue", "profit", "shares of", "ceo",
                   "acquisition", "merger", "ipo", "guidance")
                .any { it in t } -> NewsCategory.COMPANIES
            else -> NewsCategory.MARKETS
        }
    }

    /**
     * Dividend-focused headlines: declarations, cuts, raises and income-investing
     * coverage, plus anything mentioning the user's own tickers.
     *
     * Built from dividend/income feeds and then keyword-filtered, because the
     * general market feeds bury dividend news under everything else. Pass the
     * portfolio's tickers to have holdings-specific stories float to the top.
     *
     * This used to source per-symbol coverage from the classic
     * `feeds.finance.yahoo.com/rss/2.0/headline` RSS feed, which Yahoo retired
     * years ago — it now answers with an empty (but HTTP-200) channel, so the
     * two feeds that mattered most here (the dividend-ETF feed and the one
     * built from the user's own holdings) were silently contributing nothing.
     * That left only generic top-story feeds to keyword-filter, which is why
     * this tab read so thin. Per-symbol coverage now comes from Yahoo's JSON
     * news search instead (still live), and the dead investing.com feed —
     * every one of its links was already being dropped by the account-wall
     * filter below, so it never contributed a single headline — is gone.
     */
    fun fetchDividendNews(tickers: List<String> = emptyList()): List<NewsItem> {
        val heldForQuery = tickers.filter { it.isNotBlank() }.take(10)

        // Broad income/markets coverage, filtered by keyword below.
        val textFeeds = listOf(
            "https://www.cnbc.com/id/10000664/device/rss/rss.html",
            "https://www.cnbc.com/id/10001147/device/rss/rss.html",
            "https://www.cnbc.com/id/15839135/device/rss/rss.html",
            // A dividends desk of its own, which is what this tab was missing:
            // everything else here is general coverage that has to be keyword-
            // filtered down to income stories.
            "https://www.nasdaq.com/feed/rssoutbound?category=Dividends",
            "https://www.nasdaq.com/feed/rssoutbound?category=ETFs",
            "https://www.nasdaq.com/feed/rssoutbound?category=Markets",
            "https://finance.yahoo.com/news/rssindex",
            "https://feeds.npr.org/1006/rss.xml"
        )

        val seen = LinkedHashSet<String>()
        val all = ArrayList<NewsItem>()

        fun addAll(items: List<NewsItem>) {
            for (item in items) {
                if (!isFreelyReadable(item)) continue
                if (seen.add(item.linkUrl)) all.add(item)
            }
        }

        val rssTasks: List<() -> List<NewsItem>> = textFeeds.map { url ->
            {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
                    .header("Accept", "application/rss+xml, application/xml, text/xml, */*")
                    .build()
                plainClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) parseRssNews(resp.body?.string() ?: "") else emptyList()
                }
            }
        }

        // Dividend/income-specific coverage and the user's own holdings, via
        // Yahoo's JSON news search (see [fetchYahooNewsSearch]).
        val searchTasks: List<() -> List<NewsItem>> = listOf(
            { fetchYahooNewsSearch("dividend+stocks") },
            { fetchYahooNewsSearch("dividend+ETF") },
            { fetchYahooNewsSearch("SCHD+VYM+JEPI+dividend") }
        ) + heldForQuery.map { t ->
            val safe = t.replace("^", "%5E")
            val task: () -> List<NewsItem> = { fetchYahooNewsSearch("$safe+dividend") }
            task
        }

        addAll(runParallel(rssTasks + searchTasks))

        val held = tickers.map { it.uppercase().substringBefore('.') }.toSet()
        val keywords = listOf(
            "dividend", "distribution", "payout", "yield", "income fund",
            "ex-dividend", "declares", "raises its", "cuts its", "special cash",
            "reit", "drip", "reinvest", "hikes its", "boosts its", "dividend hike",
            "dividend increase", "dividend growth", "dividend aristocrat",
            "dividend king", "income investor", "high-yield", "quarterly dividend",
            "annual dividend", "payout ratio"
        )

        // Keep anything that is either dividend-flavoured or about a holding.
        val relevant = all.filter { item ->
            val t = item.title.lowercase()
            keywords.any { it in t } || held.any { h -> h.isNotBlank() && h in item.title.uppercase() }
        }

        val cutoff = System.currentTimeMillis() - 14L * 24 * 60 * 60 * 1000
        fun recent(items: List<NewsItem>) = items
            .filter { it.publishedAt == 0L || it.publishedAt >= cutoff }
            .sortedByDescending { it.publishedAt }
            .take(60)

        val kept = recent(relevant)
        // A filter that can empty the screen needs a floor.
        //
        // The keyword list is matched against the HEADLINE only, and a great
        // deal of genuine income coverage never puts "dividend" in its title
        // ("Realty Income raises guidance", "SCHD's quiet quarter"). On a slow
        // news day that left this tab with two or three stories while dozens
        // sat fetched and discarded. The sources feeding it are already
        // dividend- and holdings-targeted queries, so when strict matching
        // comes back thin the honest fallback is to show what those sources
        // returned rather than to show almost nothing.
        if (kept.size >= 12) return kept
        val loosened = recent(all)
        return if (loosened.size > kept.size) loosened else kept
    }

        /** Parse an RSS 2.0 feed body into a NewsItem list. */
    private fun parseRssNews(xml: String): List<NewsItem> {
        val out = ArrayList<NewsItem>()
        try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = false
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))

            // Feed-level <title> (e.g. "CNBC | Markets") used as default publisher
            // when an item has no <source> element.
            var feedTitle    = "Market News"
            var inChannel    = false
            var channelTitleDone = false
            var inItem       = false
            var title        = ""
            var link         = ""
            var pubDate      = ""
            var source       = ""
            var image        = ""
            var description  = ""
            // The raw, still-tagged description/content. Kept alongside the
            // stripped copy because most WordPress-based feeds (Financial Post,
            // CBC and others) ship no <media:*> element at all and embed the
            // article image as an <img> inside this HTML — and the image
            // extractor was being handed the stripped text, where every tag had
            // already been removed, so it could never match anything and every
            // one of those stories rendered with no picture.
            var rawDescription = ""
            var currentTag   = ""

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        currentTag = parser.name ?: ""
                        when (currentTag) {
                            "channel" -> inChannel = true
                            "item" -> {
                                inItem = true
                                title = ""; link = ""; pubDate = ""; source = ""
                                image = ""; description = ""; rawDescription = ""
                            }
                        }
                        // Thumbnails live in attributes, not text: RSS feeds use
                        // <media:content url>, <media:thumbnail url> or <enclosure url>.
                        if (inItem && image.isEmpty()) {
                            when (currentTag) {
                                "media:content", "media:thumbnail", "enclosure",
                                "content", "thumbnail" -> {
                                    val url = parser.getAttributeValue(null, "url")
                                    val type = parser.getAttributeValue(null, "type") ?: ""
                                    if (!url.isNullOrBlank() &&
                                        (type.isBlank() || type.startsWith("image"))
                                    ) image = url
                                }
                            }
                        }
                    }
                    XmlPullParser.TEXT -> {
                        // NOT trimmed and NOT first-wins: XmlPullParser hands
                        // long or entity-containing text back in SEVERAL TEXT
                        // events, so keeping only the first chunk truncated the
                        // value at the first "&amp;" or buffer boundary. For
                        // descriptions that meant the <img> often sat in a chunk
                        // that was thrown away, which is why some stories had a
                        // picture and others did not, seemingly at random. Every
                        // chunk is appended and the result trimmed at </item>.
                        val text = parser.text ?: ""
                        if (inItem) {
                            when (currentTag) {
                                "title"   -> title   += text
                                "link"    -> link    += text
                                "pubDate" -> pubDate += text
                                "source"  -> source  += text
                                // content:encoded carries the full article HTML
                                // in WordPress feeds and is the likeliest place
                                // to find an image, so it is collected too.
                                "description", "summary", "content:encoded", "encoded" ->
                                    rawDescription += text
                            }
                        } else if (inChannel && !channelTitleDone && currentTag == "title" && text.isNotBlank()) {
                            // Shorten "CNBC | Markets" → "CNBC", "Reuters | Business" → "Reuters" etc.
                            feedTitle = text.split("|", "–", "-").first().trim()
                            channelTitleDone = true
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "item" && inItem) {
                            // Chunks were appended raw; trim once, here.
                            title = title.trim()
                            link = link.trim()
                            pubDate = pubDate.trim()
                            source = source.trim()
                            description = stripHtml(rawDescription)

                            if (title.isNotBlank() && link.isNotBlank()) {
                                val publisher = source.ifBlank { feedTitle }
                                // Most feeds embed the image inside the item's
                                // HTML rather than a media element, so fall back
                                // to the RAW description — the stripped copy has
                                // no tags left to find.
                                val img = normalizeImageUrl(
                                    image.ifBlank { firstImageInHtml(rawDescription) }
                                )
                                out.add(
                                    NewsItem(
                                        title = title,
                                        publisher = publisher,
                                        linkUrl = link,
                                        publishedAt = parseRssDate(pubDate),
                                        imageUrl = img.ifBlank { null },
                                        summary = description.take(200).ifBlank { null },
                                        category = categorise(title, publisher)
                                    )
                                )
                            }
                            inItem = false
                        }
                        currentTag = ""
                    }
                }
                event = parser.next()
            }
        } catch (_: Exception) { }
        return out
    }

    /** Parse an RFC-822 pubDate string to epoch millis. */
    /**
     * RSS/Atom publication dates, across the formats real feeds actually emit.
     *
     * Only RFC-822-with-numeric-offset used to be handled, so feeds that send a
     * zone NAME ("EDT") or ISO-8601 fell through to "now" — which made every
     * headline look like it was published this minute and left the feed
     * effectively unsorted. Returns 0 when nothing parses, so callers can tell
     * "unknown" apart from "just now".
     */
    private val RSS_DATE_FORMATS = listOf(
        "EEE, dd MMM yyyy HH:mm:ss Z",
        "EEE, dd MMM yyyy HH:mm:ss zzz",
        "EEE, dd MMM yyyy HH:mm Z",
        "EEE, dd MMM yyyy HH:mm zzz",
        "dd MMM yyyy HH:mm:ss Z",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd'T'HH:mm:ssZ",
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd"
    )

    private fun parseRssDate(date: String): Long {
        val raw = date.trim()
        if (raw.isBlank()) return 0L
        for (pattern in RSS_DATE_FORMATS) {
            try {
                val sdf = java.text.SimpleDateFormat(pattern, java.util.Locale.US)
                sdf.isLenient = true
                val parsed = sdf.parse(raw)
                if (parsed != null) return parsed.time
            } catch (_: Exception) { /* try the next pattern */ }
        }
        return 0L
    }

    /**
     * Fetches the complete historical dividend record for [ticker] from Yahoo
     * Finance's chart API (events=div parameter).
     *
     * The range is deliberately "max" rather than the 5y it used to be. Five
     * years is not a dividend history: it started Apple's record in 2021 when
     * the company has paid since 1987, and it left the five-year growth
     * calculation with no earlier window to compare against — so "Div Growth,
     * 5 Years" read "—" for every holding in the app. A dividend record is a
     * handful of rows per year; asking for all of it costs almost nothing.
     *
     * Returns a list of (payDateMillis, amountPerShare) pairs, oldest-first.
     * Returns an empty list for tickers that pay no dividends or on network error.
     */
    fun fetchHistoricalDividendEvents(ticker: String): List<Pair<Long, Double>> {
        val url = chartUrl(ticker, "range=max&interval=1mo&events=div")
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36")
            .header("Accept", "application/json")
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val body = response.body?.string() ?: return emptyList()
                val root = JSONObject(body)
                val result = root.getJSONObject("chart").optJSONArray("result")
                    ?.optJSONObject(0) ?: return emptyList()
                val events = result.optJSONObject("events") ?: return emptyList()
                val divObj = events.optJSONObject("dividends") ?: return emptyList()
                val out = ArrayList<Pair<Long, Double>>()
                val keys = divObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val entry = divObj.optJSONObject(key) ?: continue
                    val amount = entry.optDouble("amount", Double.NaN)
                    val dateS  = entry.optLong("date", -1L)
                    if (amount.isNaN() || amount <= 0 || dateS <= 0) continue
                    out.add(dateS * 1000L to amount)
                }
                out.sortedBy { it.first }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Fetches the Nasdaq dividend calendar for the given [date] (format "YYYY-MM-DD").
     * Returns all dividend events for that day — the caller is responsible for
     * filtering to only the tickers in the user's holdings.
     *
     * Nasdaq requires Origin + Referer headers to accept the request.
     */
    fun fetchNasdaqDividendCalendar(date: String): List<NasdaqDividendRow> {
        val url = "https://api.nasdaq.com/api/calendar/dividends?date=$date"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .header("Accept", "application/json, text/plain, */*")
            .header("Origin", "https://www.nasdaq.com")
            .header("Referer", "https://www.nasdaq.com/")
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val body = response.body?.string() ?: return emptyList()
                val root = JSONObject(body)
                val rows = root.optJSONObject("data")
                    ?.optJSONObject("calendar")
                    ?.optJSONArray("rows") ?: return emptyList()
                val sdf = java.text.SimpleDateFormat("MM/dd/yyyy", java.util.Locale.US)
                val out = ArrayList<NasdaqDividendRow>()
                for (i in 0 until rows.length()) {
                    val row = rows.optJSONObject(i) ?: continue
                    val symbol = row.optString("symbol").trim().takeIf { it.isNotBlank() } ?: continue
                    val name   = row.optString("companyName", "").trim()
                    val exDateStr  = row.optString("dividend_Ex_Date", "").trim()
                    val payDateStr = row.optString("payment_Date", "").trim()
                    val rateStr    = row.optString("dividend_Rate", "").trim()
                        .removePrefix("$").trim()
                    fun parseDate(s: String): Long? = try {
                        if (s.isBlank() || s == "N/A") null else sdf.parse(s)?.time
                    } catch (_: Exception) { null }
                    out.add(NasdaqDividendRow(
                        symbol        = symbol,
                        companyName   = name,
                        exDateMs      = parseDate(exDateStr),
                        payDateMs     = parseDate(payDateStr),
                        amountPerShare = rateStr.toDoubleOrNull()
                    ))
                }
                out
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ── dividendhistory.org ──────────────────────────────────────────────────

    /**
     * Fetches the full dividend history + upcoming estimated payments for
     * [ticker] from https://dividendhistory.org/payout/{TICKER}/.
     *
     * The site covers NYSE, NASDAQ, TSX, NEO/Cboe — pass the Yahoo Finance
     * ticker and the function strips common exchange suffixes (.TO, .V, .CN,
     * .NE, .X) so the lookup always uses the plain base symbol.
     *
     * Returns entries sorted **newest-first**. [DividendHistoryOrgEntry.isEstimated]
     * is true for future projected payments. Returns an empty list on any
     * network or parse failure — callers should fall back to Yahoo Finance.
     */
    fun fetchDividendHistoryOrg(ticker: String): List<DividendHistoryOrgEntry> {
        val key = ticker.trim().uppercase()
        if (key.isEmpty()) return emptyList()

        // Cached here because every dividend path converges on this one call:
        // the upcoming card, the payment record and the income forecast all
        // start from this table. Cache it once and all three are on screen at
        // launch instead of after several seconds of scraping.
        loadCalendarCacheIfNeeded()
        calendarCache[key]?.let { hit ->
            if (System.currentTimeMillis() - hit.atMs < CALENDAR_TTL_MS && hit.rows.isNotEmpty()) {
                return hit.rows
            }
        }

        val fetched = scrapeDividendHistoryOrg(ticker)
        if (fetched.isNotEmpty()) {
            calendarCache[key] = CachedCalendar(fetched, System.currentTimeMillis())
            ca.tristan.portfolio.data.DiskCache.saveCalendars(
                calendarCache.mapValues { it.value.rows }
            )
            return fetched
        }

        // Nothing came back. Whatever is on file, however old, beats an empty
        // screen — a published pay date doesn't stop being the pay date because
        // the scrape failed this morning.
        return calendarCache[key]?.rows ?: emptyList()
    }

    // ── Payout calendar cache ────────────────────────────────────────────────

    private class CachedCalendar(val rows: List<DividendHistoryOrgEntry>, val atMs: Long)

    private val calendarCache = java.util.concurrent.ConcurrentHashMap<String, CachedCalendar>()

    @Volatile
    private var calendarCacheLoaded = false

    /**
     * Ten minutes, matching iOS. It was twelve hours, on the reasoning that a
     * fund declares a few times a year and published dates don't move — but
     * it meant a distribution declared this morning did not appear until
     * tomorrow, which is exactly when someone opens the tab to look for it.
     * The disk copy still covers a failed scrape or no network.
     */
    private const val CALENDAR_TTL_MS = 10L * 60 * 1000

    private fun loadCalendarCacheIfNeeded() {
        if (calendarCacheLoaded) return
        calendarCacheLoaded = true
        val (stored, ageMs) = ca.tristan.portfolio.data.DiskCache.loadCalendars() ?: return
        // Loaded whatever the age: a stale entry is still a floor to fall back
        // to, and the TTL check above decides whether to refetch per ticker.
        val at = System.currentTimeMillis() - ageMs
        stored.forEach { (ticker, rows) -> calendarCache[ticker] = CachedCalendar(rows, at) }
    }

    /**
     * Forces the next calendar fetch back to the network.
     *
     * Marks entries STALE rather than deleting them — the rows stay, only the
     * timestamp is backdated far enough to fail the TTL check above. A
     * straight clear()/remove() meant a refetch that came back empty (the
     * scrape timing out, dividendhistory.org rate-limiting, or just a bad
     * minute) had nothing to fall back on: fetchDividendHistoryOrg's own
     * "whatever is on file beats an empty screen" line reads
     * calendarCache[key], and invalidate had already removed it. That turned
     * a single flaky refetch into a real, already-confirmed distribution
     * being silently replaced on screen by the synthetic growth-adjusted
     * estimate — reproducible on demand, since a forced refresh always hits
     * the network.
     */
    fun invalidateDividendCalendar(ticker: String? = null) {
        DeclaredDividends.invalidate(ticker)
        if (ticker == null) {
            for (key in calendarCache.keys.toList()) {
                calendarCache[key]?.let { entry -> calendarCache[key] = CachedCalendar(entry.rows, 0L) }
            }
        } else {
            val key = ticker.trim().uppercase()
            calendarCache[key]?.let { entry -> calendarCache[key] = CachedCalendar(entry.rows, 0L) }
        }
    }

    /** The actual scrape, unconditioned by any cache. */
    private fun scrapeDividendHistoryOrg(ticker: String): List<DividendHistoryOrgEntry> {
        val upper = ticker.uppercase()
        // Strip exchange suffixes used on Yahoo Finance for Canadian / OTC tickers
        val baseTicker = upper
            .removeSuffix(".TO").removeSuffix(".V").removeSuffix(".CN")
            .removeSuffix(".NE").removeSuffix(".X").removeSuffix(".TSX")

        // dividendhistory.org namespaces non-US listings under an exchange path:
        //   US        → /payout/XYZ/
        //   TSX        → /payout/tsx/XYZ/
        //   TSX-V      → /payout/tsxv/XYZ/
        //   NEO/Cboe   → /payout/neo/XYZ/
        // The old code always used the bare US path, so EVERY Canadian ticker
        // 404'd and silently fell through to Yahoo's trailingAnnualDividendRate
        // — which is what produced the wildly wrong XEQT/VBAL/JEPQ estimates.
        val candidates: List<String> = when {
            upper.endsWith(".TO") || upper.endsWith(".TSX") ->
                listOf("tsx/$baseTicker", "tsxv/$baseTicker", "neo/$baseTicker", baseTicker)
            upper.endsWith(".V")  -> listOf("tsxv/$baseTicker", "tsx/$baseTicker", baseTicker)
            upper.endsWith(".NE") || upper.endsWith(".CN") ->
                listOf("neo/$baseTicker", "tsx/$baseTicker", baseTicker)
            else -> listOf(baseTicker, "tsx/$baseTicker")
        }

        for (path in candidates) {
            val request = Request.Builder()
                .url("https://dividendhistory.org/payout/$path/")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Referer", "https://dividendhistory.org/")
                .build()
            val parsed = try {
                plainClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use emptyList()
                    val html = response.body?.string() ?: return@use emptyList()
                    parseDividendHistoryOrg(html)
                }
            } catch (_: Exception) {
                emptyList()
            }
            if (parsed.isNotEmpty()) return parsed
        }
        return emptyList()
    }

    /**
     * Parses the HTML body of a dividendhistory.org payout page.
     *
     * Strategy 1 — table rows: the site renders a <table> with columns
     *   [Ex-Dividend Date | Payout Date | Cash Amount | Info/Status].
     *   Each <td> is scanned for a YYYY-MM-DD date or a dollar amount.
     *
     * Strategy 2 — plain-text scan: strips all HTML tags and looks for
     *   repeating labeled blocks ("Ex-Dividend Date - YYYY-MM-DD …").
     *   Handles server-side layouts that don't use a table.
     *
     * Both strategies normalise amounts and dates to UTC epoch millis.
     */
    private fun parseDividendHistoryOrg(html: String): List<DividendHistoryOrgEntry> {
        // Parsed at LOCAL midnight, not UTC midnight.
        //
        // These are calendar dates — "2026-09-24" — with no time of day in
        // them. Pinned to UTC midnight they became 20:00 the previous evening
        // for a reader in Toronto, so every date on screen printed a day early
        // (the site's Sep 24 ex-date showed as "Sep 23") and a payment near a
        // month boundary landed in the wrong bucket on the income chart.
        // Anchoring to the reader's own midnight makes the day they see the
        // day the site published.
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        sdf.timeZone = java.util.TimeZone.getDefault()

        fun parseDate(s: String): Long? = runCatching { sdf.parse(s.trim())?.time?.takeIf { it > 0 } }.getOrNull()
        fun parseAmount(s: String): Double? = s.trim().removePrefix("$").toDoubleOrNull()?.takeIf { it > 0 }
        fun isEstimatedText(s: String) = s.contains("unconfirmed", ignoreCase = true) || s.contains("estimated", ignoreCase = true)

        val entries = mutableListOf<DividendHistoryOrgEntry>()

        // ── Strategy 1: parse <tr> … </tr> blocks ─────────────────────────
        val trRegex  = Regex("""<tr[^>]*>(.*?)</tr>""",  setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val tdRegex  = Regex("""<td[^>]*>(.*?)</td>""",  setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val dateRx   = Regex("""(\d{4}-\d{2}-\d{2})""")
        val amountRx = Regex("""\$?([\d]+\.[\d]+)""")

        for (trMatch in trRegex.findAll(html)) {
            val cells = tdRegex.findAll(trMatch.groupValues[1])
                .map { it.groupValues[1].replace(Regex("<[^>]+>"), " ").trim() }
                .toList()
            if (cells.size < 3) continue

            val exDateStr  = dateRx.find(cells[0])?.groupValues?.get(1) ?: continue
            val payDateStr = if (cells.size > 1) dateRx.find(cells[1])?.groupValues?.get(1) else null
            val amountStr  = amountRx.find(cells[2])?.groupValues?.get(1) ?: continue
            val statusText = cells.getOrElse(3) { "" }

            val exDate = parseDate(exDateStr) ?: continue
            val payDate = payDateStr?.let { parseDate(it) }
            val amount  = parseAmount(amountStr) ?: continue
            val isEst   = isEstimatedText(statusText) || isEstimatedText(cells.getOrElse(4) { "" })

            entries.add(DividendHistoryOrgEntry(exDate, payDate, amount, isEst))
        }

        if (entries.isNotEmpty()) return entries.sortedByDescending { it.exDateMs }

        // ── Strategy 2: strip HTML, scan labeled text blocks ──────────────
        val text = html.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ")

        // Pattern: "Ex-Dividend Date - YYYY-MM-DD  Payout Date - YYYY-MM-DD  Cash Amount - $0.27  [Info - unconfirmed/estimated]"
        val blockRx = Regex(
            """Ex[\s-]*Dividend\s+Date\s*[-–:]\s*(\d{4}-\d{2}-\d{2})\s+Payout\s+Date\s*[-–:]\s*(\d{4}-\d{2}-\d{2})\s+Cash\s+Amount\s*[-–:]\s*\$?([\d.]+)(?:\s+(?:Info\s*[-–:]\s*)?(unconfirmed|estimated))?""",
            RegexOption.IGNORE_CASE
        )
        for (m in blockRx.findAll(text)) {
            val exDate  = parseDate(m.groupValues[1]) ?: continue
            val payDate = parseDate(m.groupValues[2])
            val amount  = parseAmount(m.groupValues[3]) ?: continue
            val isEst   = m.groupValues[4].isNotBlank()
            entries.add(DividendHistoryOrgEntry(exDate, payDate, amount, isEst))
        }

        return entries.sortedByDescending { it.exDateMs }
    }

    // ── Helpers for UpcomingDividend construction ────────────────────────────

    private const val DAY_MS = 24L * 60 * 60 * 1000

    /**
     * Derives the next expected payment from a list of [DividendHistoryOrgEntry]
     * rows (newest-first, as returned by [fetchDividendHistoryOrg]).
     *
     * Why this is not just `trailingAnnualRate / frequency`:
     * asset-allocation ETFs pay very unevenly across the year. XEQT's real
     * distributions run ~$0.09 (Mar), ~$0.27 (Jun), ~$0.10 (Sep), ~$0.21 (Dec) —
     * so an even split of the ~$1.29 trailing annual rate predicts $0.32 for
     * every quarter and over-states the September payment by more than 3x.
     * That was the bug behind the inflated XEQT / VBAL estimates.
     *
     * Estimation order (first that applies wins):
     *  1. **Announced** — a future row the site has *not* flagged
     *     "unconfirmed/estimated" is a declared distribution: use it verbatim.
     *  2. **Seasonal** (quarterly / semi-annual / annual payers) — take the
     *     confirmed payment made ~1 year before the upcoming ex-date (matching
     *     the same slot in the cycle) and scale it by year-over-year growth in
     *     the trailing rate, clamped to [0.5, 2.0] so one odd year can't blow
     *     the estimate up.
     *  3. **Recent median** (monthly payers, e.g. covered-call funds like JEPQ)
     *     — median of the last 3 confirmed payments. Monthly income funds have
     *     no annual seasonality but do drift, so the recent regime beats both a
     *     12-month average and the single latest payment.
     *  4. **Latest confirmed** — last resort when history is too thin.
     *
     * Returns null when the list is empty or has no confirmed entries.
     */
    fun inferUpcomingFromHistoryOrg(
        entries: List<DividendHistoryOrgEntry>,
        now: Long = System.currentTimeMillis()
    ): UpcomingDividend? {
        if (entries.isEmpty()) return null
        val startOfToday = java.util.Calendar.getInstance().apply {
            timeInMillis = now
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis

        // Estimated rows never count as history, whatever their date.
        val confirmed = entries
            .filter { !it.isEstimated && it.exDateMs <= now }
            .sortedByDescending { it.exDateMs }

        // Typical gap between a payment's ex-date and the cash actually
        // landing, measured from this security's own record rather than
        // assumed — it runs from a couple of days (European annual payers) to
        // five or six weeks (Canadian ETFs). Only rows that carry both dates
        // can contribute; when none do, the median of nothing is no answer and
        // the projected pay date is simply left unknown.
        val exToPayGaps = confirmed
            .mapNotNull { row -> row.payDateMs?.let { it - row.exDateMs } }
            .filter { it in 0..(90L * DAY_MS) }
            .sorted()
        val exToPayGap = exToPayGaps.getOrNull(exToPayGaps.size / 2)

        // A payment is upcoming until its money ARRIVES, not until it goes ex.
        //
        // This used to split on `exDateMs > now`, with ex-dates parsed at
        // local midnight. On the ex-date itself midnight is already behind
        // `now`, so a declared distribution dropped out of "upcoming" at 00:00
        // on the day it went ex — days before the cash landed — and the card
        // fell back to a "same quarter last year" guess with no pay date, for
        // a payment the fund had already announced.
        //
        // A row with no published pay date (Yahoo's events block) stays owed
        // for the fund's own measured ex→pay gap, or a week when there is
        // nothing to measure it from.
        val undatedGrace = exToPayGap ?: (7L * DAY_MS)
        val owed = entries
            .filter { (it.payDateMs ?: (it.exDateMs + undatedGrace)) >= startOfToday }
            .sortedBy { it.exDateMs }
        // The earliest payment still owed — preferring the declared row when
        // the source also listed a projection for the same slot.
        val upcoming = owed.firstOrNull()?.let { first ->
            owed.firstOrNull { !it.isEstimated && it.exDateMs - first.exDateMs <= 45L * DAY_MS } ?: first
        }

        if (confirmed.isEmpty()) {
            return upcoming?.let {
                UpcomingDividend(
                    it.exDateMs, it.payDateMs,
                    estimatedAnnualRate = null,
                    yieldPercent = null,
                    paymentFrequencyPerYear = null,
                    perPaymentAmount = it.amountPerShare,
                    isAnnounced = !it.isEstimated,
                    basisLabel = if (it.isEstimated) "projected by source" else "declared by fund"
                )
            }
        }

        // ── Payment frequency: median gap between confirmed ex-dates ──────
        val gapsDays = confirmed.zipWithNext()
            .take(6)
            .map { kotlin.math.abs((it.first.exDateMs - it.second.exDateMs) / DAY_MS) }
            .sorted()
        val medianGap = if (gapsDays.isEmpty()) 91.0 else gapsDays[gapsDays.size / 2].toDouble()
        val freq = when {
            medianGap <= 45  -> 12   // monthly
            medianGap <= 105 -> 4    // quarterly
            medianGap <= 200 -> 2    // semi-annual
            else             -> 1    // annual
        }

        // ── True trailing 12-month rate (confirmed payments only) ─────────
        val oneYearAgo = now - 365L * DAY_MS
        val trailing12 = confirmed.filter { it.exDateMs >= oneYearAgo }.sumOf { it.amountPerShare }
        val trailingAnnualRate = if (trailing12 > 0) trailing12 else confirmed.first().amountPerShare * freq

        fun median(xs: List<Double>): Double? {
            if (xs.isEmpty()) return null
            val v = xs.sorted()
            return if (v.size % 2 == 1) v[v.size / 2] else (v[v.size / 2 - 1] + v[v.size / 2]) / 2.0
        }

        // ── 1. Declared distribution ──────────────────────────────────────
        if (upcoming != null && !upcoming.isEstimated) {
            return UpcomingDividend(
                upcoming.exDateMs, upcoming.payDateMs, trailingAnnualRate, null,
                paymentFrequencyPerYear = freq,
                perPaymentAmount = upcoming.amountPerShare,
                isAnnounced = true,
                basisLabel = "declared by fund"
            )
        }

        // One cycle on from the last payment, stepped in whole calendar months
        // so the date stays on the fund's usual day instead of drifting ~5 days
        // a year. A projection that has slipped a little into the past is
        // kept — the record usually lags the payment by a few days, and
        // jumping ahead would skip a payment that is still coming — but one
        // well behind us is rolled forward a cycle at a time.
        val nextExDate = upcoming?.exDateMs ?: run {
            val stepMonths = maxOf(12 / freq, 1)
            val graceMs = minOf(20L, 365L / freq / 2) * DAY_MS
            val cal = java.util.Calendar.getInstance().apply {
                timeInMillis = confirmed.first().exDateMs
                add(java.util.Calendar.MONTH, stepMonths)
            }
            var rolls = 0
            while (cal.timeInMillis + graceMs < startOfToday && rolls < 60) {
                cal.add(java.util.Calendar.MONTH, stepMonths)
                rolls++
            }
            cal.timeInMillis
        }
        val nextPayDate = upcoming?.payDateMs ?: exToPayGap?.let { nextExDate + it }

        var perPayment: Double? = null
        var basis: String? = null

        // ── 2. Seasonal match for quarterly / semi-annual / annual ────────
        if (freq <= 4) {
            val targetMs = nextExDate - 365L * DAY_MS
            // Half the cycle length, capped at 25 days, keeps a quarterly match
            // in the right slot without picking up the neighbouring quarter.
            val windowDays = minOf(25L, (365L / freq) / 2)
            val sameSlot = confirmed
                .filter { kotlin.math.abs(it.exDateMs - targetMs) <= windowDays * DAY_MS }
                .minByOrNull { kotlin.math.abs(it.exDateMs - targetMs) }

            if (sameSlot != null) {
                // Year-over-year drift, from the last `freq` payments vs the `freq` before
                val recent = confirmed.take(freq).map { it.amountPerShare }
                val prior  = confirmed.drop(freq).take(freq).map { it.amountPerShare }
                val growth = if (recent.size == freq && prior.size == freq && prior.sum() > 0) {
                    (recent.sum() / prior.sum()).coerceIn(0.5, 2.0)
                } else 1.0
                perPayment = sameSlot.amountPerShare * growth
                val slotWord = when (freq) {
                    4 -> "quarter"; 2 -> "half"; else -> "period"
                }
                basis = "same $slotWord last year, growth-adjusted"
            }
        }

        // ── 3. Recent median (monthly payers, or no seasonal match) ───────
        if (perPayment == null) {
            val window = if (freq == 12) 3 else freq
            perPayment = median(confirmed.take(window).map { it.amountPerShare })
            basis = if (freq == 12) "median of last 3 payments" else "median of last $window payments"
        }

        // ── 4. Last resort ────────────────────────────────────────────────
        if (perPayment == null) {
            perPayment = confirmed.first().amountPerShare
            basis = "most recent payment"
        }

        // The projected dates, NOT `upcoming?.exDateMs`.
        //
        // `upcoming` is a row the *source* already listed in the future, and
        // only dividendhistory.org publishes those — and only for US, TSX,
        // TSX-V and NEO. Every other security reaches this function through
        // Yahoo's historical distribution events, which by definition contain
        // nothing dated ahead, so `upcoming` was always null and both dates
        // came back null with it. `nextExDate` was being computed a few lines
        // up and then thrown away.
        //
        // Downstream that read as "there is no next payment": the holding
        // screen's Dividend Payout Schedule requires an ex-date before it will
        // render at all, so the whole section silently vanished for every
        // international holding, and the Dividends tab sorted those rows to
        // the bottom with blank date blocks. The estimate behind them was
        // there the entire time — only its date was being discarded.
        return UpcomingDividend(
            exDividendDateMillis = nextExDate,
            payDateMillis        = nextPayDate,
            estimatedAnnualRate  = trailingAnnualRate,
            yieldPercent         = null,
            paymentFrequencyPerYear = freq,
            perPaymentAmount     = perPayment,
            isAnnounced          = false,
            basisLabel           = basis
        )
    }

    /**
     * Fetches the top market gainers or losers for the day from Yahoo Finance's
     * predefined screener endpoint. Results are market-wide (not limited to the
     * user's watchlist) and include exchange info for flag display.
     *
     * [scrId] is one of: "day_gainers", "day_losers"
     */
    private fun fetchMarketMovers(scrId: String): List<MarketMover> {
        val url = screenerUrl(scrId)
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .header("Accept", "application/json")
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val body = response.body?.string() ?: return emptyList()
                val root = JSONObject(body)
                val quotes = root.optJSONObject("finance")
                    ?.optJSONArray("result")
                    ?.optJSONObject(0)
                    ?.optJSONArray("quotes") ?: return emptyList()
                val out = ArrayList<MarketMover>()
                for (i in 0 until quotes.length()) {
                    val q = quotes.optJSONObject(i) ?: continue
                    val symbol = q.optString("symbol").takeIf { it.isNotBlank() } ?: continue
                    val name = q.optString("shortName", null)?.takeIf { it.isNotBlank() }
                        ?: q.optString("longName", null)?.takeIf { it.isNotBlank() }
                    val price = q.optDouble("regularMarketPrice", Double.NaN)
                    val prevClose = q.optDouble("regularMarketPreviousClose", Double.NaN)
                    val changePct = q.optDouble("regularMarketChangePercent", Double.NaN)
                    if (price.isNaN() || changePct.isNaN()) continue
                    val exchange = q.optString("fullExchangeName", null)?.takeIf { it.isNotBlank() }
                    val volume = q.optLong("regularMarketVolume", 0L).takeIf { it > 0L }
                    val avgVolume = q.optLong("averageDailyVolume3Month", 0L).takeIf { it > 0L }
                    val marketTime = q.optLong("regularMarketTime", 0L)
                        .takeIf { it > 0L }?.times(1000L)
                    val zone = q.optString("exchangeTimezoneName", null)
                        ?.takeIf { it.isNotBlank() }
                    out.add(
                        MarketMover(
                            symbol, name, price,
                            if (prevClose.isNaN()) price else prevClose,
                            changePct, exchange, volume, avgVolume,
                            marketTime, zone
                        )
                    )
                }
                out
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun fetchTopGainers(): List<MarketMover> = fetchMarketMovers("day_gainers")
    fun fetchTopLosers(): List<MarketMover>  = fetchMarketMovers("day_losers")

    /**
     * Today's hot stocks — whatever the market is actually paying attention to
     * right now, in one list.
     *
     * Three screeners are merged rather than one: most-actives alone is a fairly
     * static list of mega-caps that trade heavily every day, while the gainer
     * and loser screens surface the names that actually did something today.
     * Ranking the union by [MarketMover.heat] lets a big move on heavy volume
     * beat both a quiet mega-cap and a microcap spike on no volume.
     *
     * Losers are included deliberately: a stock down 20% on ten times its usual
     * volume is one of the hottest things on the tape that day, and a list that
     * only ever shows green is a worse answer to "what's hot" than an honest one.
     */
    fun fetchHotStocks(limit: Int = 25): List<MarketMover> {
        val merged = LinkedHashMap<String, MarketMover>()

        // Same three screeners either way; only the naming differs (the server
        // hyphenates where the direct endpoint underscores). The direct call
        // runs only if the server gave nothing at all.
        listOf("most-actives", "day-gainers", "day-losers").forEach { name ->
            FinanceQueryClient.fetchScreener(name, limit).forEach { m ->
                val existing = merged[m.ticker]
                if (existing == null || (existing.volume == null && m.volume != null)) {
                    merged[m.ticker] = m
                }
            }
        }

        if (merged.isEmpty()) listOf("most_actives", "day_gainers", "day_losers").forEach { scr ->
            fetchMarketMovers(scr).forEach { m ->
                // Keep whichever copy carries volume data; the screeners do not
                // all populate the same fields for the same symbol.
                val existing = merged[m.ticker]
                if (existing == null || (existing.volume == null && m.volume != null)) {
                    merged[m.ticker] = m
                }
            }
        }
        return merged.values
            .filter { m ->
                // Junk floor. The day_gainers screen surfaces sub-dollar
                // microcaps that print 40% on a few thousand shares; the heat
                // score demotes them but a big enough move still carries one
                // into the top ten, and a list of names the reader has never
                // heard of and could not sensibly trade is not useful. Price
                // and volume floors keep them out entirely rather than relying
                // on the ranking to bury them.
                m.price >= 1.0 &&
                    kotlin.math.abs(m.changePct) > 0.01 &&
                    (m.volume == null || m.volume >= 200_000L)
            }
            .sortedByDescending { it.heat }
            .take(limit)
    }

    /**
     * Search-as-you-type ticker lookup for "Add a quote" / "Add Transaction"
     * (stocks, ETFs, indices, futures, forex, crypto all come back through
     * the same endpoint, distinguished by quoteType).
     *
     * Yahoo's own search is the primary source here — reversed from every
     * other quote/history call in this client, which prefer Finnhub. Finnhub's
     * `/search` also matches against ISIN/CUSIP, and for a lot of
     * non-US-primary-listed names (Ericsson, e.g.) it hands back the ISIN
     * itself as the `symbol` field with no `exchange` at all — a string like
     * "SE0000108656" that can't be quoted or charted, and that carries no
     * suffix a flag can be derived from either. Yahoo's search always returns
     * a tradable symbol plus an `exchDisp` exchange name, so it's what
     * actually lets a non-US/CA ticker be found, priced and flagged
     * correctly. Finnhub search is kept as the fallback for when Yahoo's
     * endpoint itself is unreachable.
     */
    fun searchSymbols(query: String): List<SymbolSearchResult> {
        if (query.isBlank()) return emptyList()

        // ── Primary: Finance Query's lookup ────────────────────────────────
        FinanceQueryClient.searchSymbols(query).takeIf { it.isNotEmpty() }?.let { return it }

        fetchYahooSearch(query).takeIf { it.isNotEmpty() }?.let { return it }

        // ── Fallback: Finnhub ──────────────────────────────────────────────
        if (FinnhubQuoteClient.isAvailable()) {
            FinnhubQuoteClient.searchSymbols(query)
                .takeIf { it.isNotEmpty() }?.let { return it }
        }

        // ── Last resort: ask whether the symbol simply EXISTS ──────────────
        return probeSymbol(query)
    }

    /**
     * Resolves a symbol neither search index knows by quoting it directly.
     *
     * Yahoo's search endpoint is an index, and its coverage is not the same as
     * its data coverage. Smaller exchanges are the gap: the Philippine Stock
     * Exchange prices perfectly well through the chart endpoint — the PSEi
     * itself (`PSEI.PS`) is on the Markets tab and updates — yet searching
     * "JFC.PS" returns nothing at all, because search ranks 20 results and
     * indexes PSE listings poorly or not at all. The user then reasonably
     * concludes the app cannot handle their market.
     *
     * So when both indexes come back empty and the query already looks like a
     * symbol, this fetches a quote for it. If one comes back, the symbol is
     * real and tradable, and a result is synthesised from the quote itself.
     * Nothing is invented: the symbol, name, exchange and type are whatever the
     * quote reported. If no quote comes back, the symbol genuinely isn't one
     * and an empty list is still the honest answer.
     *
     * Runs only on the empty-search path, so a normal search costs nothing.
     */
    private fun probeSymbol(query: String): List<SymbolSearchResult> {
        val candidate = query.trim().uppercase()
        // Guard the shape before spending a request: a symbol, optionally with
        // one exchange suffix. Free text ("jollibee foods") is not one, and
        // quoting it would only 404 slowly.
        val looksLikeSymbol = candidate.length in 1..12 &&
            candidate.none { it.isWhitespace() } &&
            candidate.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '^' || it == '=' } &&
            candidate.count { it == '.' } <= 1
        if (!looksLikeSymbol) return emptyList()

        val quote = runCatching { fetchFromYahoo(candidate) }.getOrNull() ?: return emptyList()
        if (quote.price.isNaN() || quote.price <= 0.0) return emptyList()

        return listOf(
            SymbolSearchResult(
                symbol = candidate,
                name = quote.name,
                // No exchange label from a chart response; the suffix is what
                // TickerFlag reads anyway, and inventing one would be worse
                // than leaving it null.
                exchange = null,
                quoteType = quote.instrumentType
            )
        )
    }

    private fun fetchYahooSearch(query: String): List<SymbolSearchResult> {
        val url = searchUrl(java.net.URLEncoder.encode(query, "UTF-8"))
        val request = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val body = response.body?.string() ?: return emptyList()
                val root = JSONObject(body)
                val quotes = root.optJSONArray("quotes") ?: return emptyList()
                val out = ArrayList<SymbolSearchResult>()
                for (i in 0 until quotes.length()) {
                    val q = quotes.getJSONObject(i)
                    val symbol = q.optString("symbol").takeIf { it.isNotBlank() } ?: continue
                    val name = q.optString("shortname", null) ?: q.optString("longname", null)
                    val exchange = q.optString("exchDisp", null)
                    val quoteType = q.optString("quoteType", null)
                    out.add(SymbolSearchResult(symbol, name, exchange, quoteType))
                }
                out
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
