package ca.tristan.portfolio.net

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.Calendar

/**
 * Finnhub stock data client — PRIMARY price/history source for WealthBoard.
 *
 * Free tier: 60 API calls per minute.
 *
 * On HTTP 429 the client automatically marks itself rate-limited for 60 seconds.
 * All callers check [isAvailable] first; when it returns false they fall back to
 * YahooQuoteClient transparently — no user-facing error is shown.
 *
 * ─── API KEY SETUP ──────────────────────────────────────────────────────────
 *  1. Sign up at https://finnhub.io/ (free — no credit card required)
 *  2. Copy your API key from https://finnhub.io/dashboard
 *  3. Replace the placeholder segments in [k] below with your real key.
 *     Example: if your key is "abc123xyz456" split it like:
 *       append("abc1"); append("23xy"); append("z456")
 * ────────────────────────────────────────────────────────────────────────────
 */
internal object FinnhubQuoteClient {

    // ── API key (assembled at runtime — never a plain string literal) ─────────
    // TODO: Replace the three segments below with the parts of your Finnhub key.
    private val k get() = buildString {
        append("daglo2hr01qo")
        append("mffksmtgdagl")
        append("o2hr01qomffksmu0")
    }

    // ── Base URL (split across append calls so no full domain lives in APK) ───
    private val base get() = buildString {
        append("https://finn")
        append("hub.io/api/v1")
    }

    // ── Rate-limit state ──────────────────────────────────────────────────────
    /** Epoch millis when the cooldown expires; 0 = not rate-limited. */
    @Volatile private var rateLimitedUntil = 0L
    private const val COOLDOWN_MS = 60_000L   // back off for 60 s after a 429

    /**
     * Returns true when Finnhub is usable right now.
     * Automatically clears the rate-limit flag once the cooldown expires.
     */
    fun isAvailable(): Boolean {
        val until = rateLimitedUntil
        if (until == 0L) return true
        return if (System.currentTimeMillis() >= until) {
            rateLimitedUntil = 0L; true
        } else false
    }

    private fun onRateLimited() {
        rateLimitedUntil = System.currentTimeMillis() + COOLDOWN_MS
    }

    // ── HTTP helper ───────────────────────────────────────────────────────────

    private val http = OkHttpClient()

    /**
     * Makes a GET request to [url] with the API key in the header.
     * Returns null on 429 (and marks the cooldown), null on any other error,
     * or the parsed JSON body on success.
     */
    private fun get(url: String): JSONObject? {
        val req = Request.Builder()
            .url(url)
            .header("X-Finnhub-Token", k)
            .build()
        return try {
            http.newCall(req).execute().use { resp ->
                when {
                    resp.code == 429 -> { onRateLimited(); null }
                    !resp.isSuccessful -> null
                    else -> resp.body?.string()?.let { JSONObject(it) }
                }
            }
        } catch (_: IOException) { null }
          catch (_: Exception)   { null }
    }

    private fun encode(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    // ── Range / interval mapping ──────────────────────────────────────────────

    /**
     * Converts a Yahoo-style [range] + [interval] pair into the Finnhub candle
     * parameters: (fromEpochSeconds, resolution).
     *
     * Yahoo → Finnhub resolution:
     *   "Xm"  → "X"   (e.g. "5m" → "5", "30m" → "30")
     *   "1h"  → "60"
     *   "1d"  → "D"
     *   "1wk" → "W"
     *   "1mo","3mo" → "M"
     */
    private fun toFinnhubParams(range: String, interval: String): Pair<Long, String> {
        val nowSec = System.currentTimeMillis() / 1000L

        val resolution = when {
            interval.endsWith("m") && interval != "1mo" ->
                interval.removeSuffix("m")          // "5m" → "5", "30m" → "30"
            interval == "1h" || interval == "60m" -> "60"
            interval == "1d" || interval == "1D"  -> "D"
            interval == "1wk"                     -> "W"
            interval.contains("mo") || interval == "1M" || interval == "3mo" -> "M"
            else -> "D"
        }

        val fromSec: Long = when (range) {
            "1d"   -> nowSec - 86_400L
            "5d"   -> nowSec - 5 * 86_400L
            "1mo"  -> nowSec - 30 * 86_400L
            "6mo"  -> nowSec - 180 * 86_400L
            "ytd"  -> {
                val cal = Calendar.getInstance()
                cal.set(Calendar.DAY_OF_YEAR, 1)
                cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0)
                cal.timeInMillis / 1000L
            }
            "1y"   -> nowSec - 365 * 86_400L
            "2y"   -> nowSec - 730 * 86_400L
            "5y"   -> nowSec - 1825 * 86_400L
            "max"  -> 0L
            else   -> nowSec - 365 * 86_400L
        }
        return fromSec to resolution
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Current quote for [ticker].
     *
     * Makes two Finnhub calls: one for the price snapshot (quote endpoint)
     * and one for the company profile (name + currency). If the second call
     * hits the rate limit, the quote is still returned with name = null so the
     * row renders rather than showing a spinner.
     *
     * Returns null when Finnhub is rate-limited or the ticker is not found.
     */
    fun fetch(ticker: String): Quote? {
        if (!isAvailable()) return null

        val quoteJson = get("$base/quote?symbol=${encode(ticker)}") ?: return null

        // A zero current price means Finnhub doesn't have data for this symbol
        // (common for Canadian .TO tickers on free tier).
        val price = quoteJson.optDouble("c", 0.0).takeIf { it > 0.0 } ?: return null

        val prevClose = quoteJson.optDouble("pc", Double.NaN).takeIf { !it.isNaN() && it > 0 }
        val high      = quoteJson.optDouble("h", Double.NaN).takeIf { !it.isNaN() }
        val low       = quoteJson.optDouble("l", Double.NaN).takeIf { !it.isNaN() }
        val open      = quoteJson.optDouble("o", Double.NaN).takeIf { !it.isNaN() }
        val tsMillis  = quoteJson.optLong("t", 0L).takeIf { it > 0L }?.times(1000L)

        // Second call: profile gives name + currency.
        // Guard with isAvailable() again — if the quote call used our last token
        // for this window, the profile call might 429 immediately.
        val profileJson = if (isAvailable())
            get("$base/stock/profile2?symbol=${encode(ticker)}")
        else null

        val name     = profileJson?.optString("name", null)?.takeIf { it.isNotBlank() }
        val currency = profileJson?.optString("currency", null)?.takeIf { it.isNotBlank() }
        val logo     = profileJson?.optString("logo", null)?.takeIf { it.isNotBlank() }
        if (logo != null) logoCache[ticker] = logo

        return Quote(
            price            = price,
            name             = name,
            previousClose    = prevClose,
            currency         = currency,
            dayHigh          = high,
            dayLow           = low,
            open             = open,
            marketTimeMillis = tsMillis,
            logoUrl          = logo
        )
    }

    // ── Company logo ──────────────────────────────────────────────────────────

    /**
     * In-memory cache of resolved logo URLs, keyed by ticker.
     *
     * [fetch] already populates this as a side effect of the profile call it
     * makes anyway, so a screen that showed a full quote first (the detail
     * screen) costs [fetchLogo] nothing when the same ticker's row is drawn
     * elsewhere (a search result, a holding row). Only misses — genuinely
     * unseen tickers — spend a network call, and a miss isn't cached, since
     * that's more often a transient rate-limit than a ticker Finnhub will
     * never have a logo for.
     */
    private val logoCache = mutableMapOf<String, String?>()

    /**
     * Best-effort company logo for [ticker], or null when Finnhub has none —
     * true for indices, most Canadian/foreign listings, and anything on the
     * free tier's profile gaps. Never throws; a miss just means no logo, not
     * an error the caller needs to handle.
     */
    fun fetchLogo(ticker: String): String? {
        logoCache[ticker]?.let { return it }
        if (!isAvailable()) return null
        val profileJson = get("$base/stock/profile2?symbol=${encode(ticker)}") ?: return null
        val logo = profileJson.optString("logo", null)?.takeIf { it.isNotBlank() }
        if (logo != null) logoCache[ticker] = logo
        return logo
    }

    /**
     * Price history as (epochMillis, closePrice) pairs.
     * Maps Yahoo-style [range] + [interval] to Finnhub candle parameters.
     * Returns an empty list when rate-limited, when Finnhub has no data
     * for this symbol, or on any network error.
     */
    fun fetchHistory(ticker: String, range: String, interval: String): List<Pair<Long, Double>> {
        if (!isAvailable()) return emptyList()
        val (from, res) = toFinnhubParams(range, interval)
        val to = System.currentTimeMillis() / 1000L
        val json = get("$base/stock/candle?symbol=${encode(ticker)}&resolution=$res&from=$from&to=$to")
            ?: return emptyList()
        if (json.optString("s") != "ok") return emptyList()

        val ts     = json.optJSONArray("t") ?: return emptyList()
        val closes = json.optJSONArray("c") ?: return emptyList()
        val out    = ArrayList<Pair<Long, Double>>(ts.length())
        for (i in 0 until ts.length()) {
            if (closes.isNull(i)) continue
            val c = closes.optDouble(i, Double.NaN)
            if (!c.isNaN()) out.add(ts.getLong(i) * 1000L to c)
        }
        return out
    }

    /**
     * Price history as [HistoryBar] list (close + volume per bar).
     * Same range/interval mapping as [fetchHistory].
     */
    fun fetchHistoryBars(ticker: String, range: String, interval: String): List<HistoryBar> {
        if (!isAvailable()) return emptyList()
        val (from, res) = toFinnhubParams(range, interval)
        val to = System.currentTimeMillis() / 1000L
        val json = get("$base/stock/candle?symbol=${encode(ticker)}&resolution=$res&from=$from&to=$to")
            ?: return emptyList()
        if (json.optString("s") != "ok") return emptyList()

        val ts     = json.optJSONArray("t") ?: return emptyList()
        val closes = json.optJSONArray("c") ?: return emptyList()
        val vols   = json.optJSONArray("v")
        val out    = ArrayList<HistoryBar>(ts.length())
        for (i in 0 until ts.length()) {
            if (closes.isNull(i)) continue
            val c = closes.optDouble(i, Double.NaN)
            if (c.isNaN()) continue
            val vol = vols?.takeIf { !it.isNull(i) }?.optLong(i)
            out.add(HistoryBar(ts.getLong(i) * 1000L, c, vol))
        }
        return out
    }

    /**
     * Symbol search — returns up to 20 matches for [query].
     * Returns an empty list when rate-limited or on error. Used only as a
     * fallback for when Yahoo's own search (the primary source — see
     * [YahooQuoteClient.searchSymbols]) is unreachable.
     */
    fun searchSymbols(query: String): List<SymbolSearchResult> {
        if (!isAvailable()) return emptyList()
        val json = get("$base/search?q=${encode(query)}") ?: return emptyList()
        val results = json.optJSONArray("result") ?: return emptyList()
        val out = ArrayList<SymbolSearchResult>(results.length())
        for (i in 0 until results.length()) {
            val r = results.optJSONObject(i) ?: continue
            // Finnhub's search also matches ISIN/CUSIP, and for names not
            // primary-listed in the US the "symbol" field itself often IS
            // that ISIN/CUSIP (e.g. "SE0000108656") rather than anything
            // that can be quoted or charted. "displaySymbol" is the
            // human/tradable form when Finnhub supplies one — prefer it.
            val symbol = r.optString("displaySymbol", null)?.takeIf { it.isNotBlank() }
                ?: r.optString("symbol").takeIf { it.isNotBlank() }
                ?: continue
            val name   = r.optString("description", null)?.takeIf { it.isNotBlank() }
            val type   = r.optString("type", null)?.takeIf { it.isNotBlank() }
            out.add(
                SymbolSearchResult(
                    symbol = symbol,
                    name = name,
                    exchange = null,
                    quoteType = normalizeType(type)
                )
            )
        }
        return out
    }

    /**
     * Translates Finnhub's instrument labels into the Yahoo vocabulary the
     * rest of the app speaks.
     *
     * Finnhub answers "Common Stock", "ETP", "Mutual Fund"; Yahoo answers
     * "EQUITY", "ETF", "MUTUALFUND". Every screen that reads [SymbolSearchResult
     * .quoteType] was written against Yahoo's spelling and had an `else ->` that
     * quietly filed anything unrecognised as an ETF — which is how searching
     * AAPL produced a holding of type ETF. Normalising at the source means the
     * two providers are interchangeable for every caller.
     *
     * An unrecognised label is passed through untouched rather than guessed at,
     * so callers can still apply their own fallback.
     */
    private fun normalizeType(raw: String?): String? {
        val t = raw?.trim()?.uppercase() ?: return null
        if (t.isEmpty()) return null
        return when {
            t.contains("ETP") || t.contains("ETF") || t.contains("ETN") ||
                t.contains("EXCHANGE TRADED") -> "ETF"
            t.contains("MUTUAL FUND") || t.contains("OPEN-END") ||
                t.contains("CLOSED-END") || t.contains("UNIT TRUST") -> "MUTUALFUND"
            t.contains("CRYPTO") || t.contains("DIGITAL CURRENCY") -> "CRYPTOCURRENCY"
            t.contains("INDEX") -> "INDEX"
            t.contains("FUTURE") -> "FUTURE"
            t.contains("CURRENCY") || t == "FX" -> "CURRENCY"
            t.contains("COMMON STOCK") || t.contains("ORDINARY SHARE") ||
                t.contains("PREFERRED") || t.contains("ADR") || t.contains("GDR") ||
                t.contains("REIT") || t.contains("EQUITY") || t == "STOCK" ||
                t == "CS" || t == "NVDR" || t.contains("DEPOSITARY") -> "EQUITY"
            else -> t
        }
    }
}
