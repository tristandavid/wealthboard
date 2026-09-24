package ca.tristan.portfolio.net

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * The most recently DECLARED distribution, from the listing exchange's own
 * public quote feed: TMX Money for TSX / TSX-V / NEO, Nasdaq for US listings.
 *
 * Why this exists: the payout calendar (dividendhistory.org) is a scraped
 * third-party page. When it is down, blocked, or has not caught up with an
 * announcement yet, the only other record — Yahoo's events block — cannot
 * help: it lists a distribution once it has gone ex, never before, and never
 * with a pay date. So an announced payment was invisible exactly when it
 * mattered, and XEQT's September 2026 distribution sat on screen as "same
 * quarter last year, growth-adjusted" with no pay date on the day it went ex.
 *
 * Best-effort throughout, like every other source here: any failure returns an
 * empty list and the calendar + events path carries on exactly as before.
 * Mirrors the "Declared dividends" section of QuoteClient.swift on iOS.
 */
object DeclaredDividends {

    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** Same window as the payout calendar: an announcement this morning shows up when the tab is next opened. */
    private const val TTL_MS = 10L * 60 * 1000

    private class Cached(val rows: List<DividendHistoryOrgEntry>, val atMs: Long)

    private val cache = ConcurrentHashMap<String, Cached>()

    // Its own client: a different host from the pricing endpoint, so no shared
    // rate-limit cooldown (see YahooQuoteClient.plainClient for the history).
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    /** Declared rows for [ticker], newest first. Blocking; call off the main thread. */
    fun fetch(ticker: String): List<DividendHistoryOrgEntry> {
        val key = ticker.trim().uppercase()
        if (key.isEmpty()) return emptyList()
        cache[key]?.let { hit ->
            if (System.currentTimeMillis() - hit.atMs < TTL_MS) return hit.rows
        }
        val rows = runCatching {
            tmxSymbol(key)?.let { fetchTmx(it) }
                ?: nasdaqSymbol(key)?.let { fetchNasdaq(it) }
                ?: emptyList()
        }.getOrDefault(emptyList())
        // An empty answer is cached too — a symbol neither exchange lists
        // should not cost a request on every screen that asks.
        cache[key] = Cached(rows, System.currentTimeMillis())
        return rows
    }

    fun invalidate(ticker: String? = null) {
        if (ticker == null) cache.clear() else cache.remove(ticker.trim().uppercase())
    }

    /** TMX's spelling of a Canadian listing, or null. "XEQT.TO" → "XEQT", "RCI-B.TO" → "RCI.B". */
    fun tmxSymbol(ticker: String): String? {
        val upper = ticker.uppercase()
        for (suffix in listOf(".TO", ".TSX", ".V", ".NE", ".CN")) {
            if (upper.endsWith(suffix)) {
                val base = upper.removeSuffix(suffix)
                return base.takeIf { it.isNotEmpty() }?.replace('-', '.')
            }
        }
        return null
    }

    /**
     * A plain US symbol Nasdaq's quote API answers for, or null. Anything with
     * an exchange suffix, a class separator, or index/currency syntax is
     * skipped rather than guessed at.
     */
    fun nasdaqSymbol(ticker: String): String? {
        val upper = ticker.uppercase()
        return upper.takeIf { it.length in 1..5 && it.all { c -> c in 'A'..'Z' } }
    }

    /**
     * Calendar dates read at the reader's LOCAL midnight — the same rule the
     * payout-page parser uses, so the sources agree on which day a payment
     * falls. Anything after the date part (a time, a zone) is ignored.
     */
    fun parseDay(raw: String?, pattern: String = "yyyy-MM-dd"): Long? {
        val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() && it != "null" && it != "N/A" } ?: return null
        if (trimmed.length < pattern.length) return null
        val sdf = java.text.SimpleDateFormat(pattern, java.util.Locale.US)
        sdf.timeZone = java.util.TimeZone.getDefault()
        sdf.isLenient = false
        return runCatching { sdf.parse(trimmed.take(pattern.length))?.time }.getOrNull()
    }

    // ── TMX Money ────────────────────────────────────────────────────────────

    private fun fetchTmx(symbol: String): List<DividendHistoryOrgEntry> {
        val query = """
            query getQuoteBySymbol(${'$'}symbol: String, ${'$'}locale: String) {
              getQuoteBySymbol(symbol: ${'$'}symbol, locale: ${'$'}locale) {
                symbol exDividendDate dividendPayDate dividendAmount dividendFrequency
              }
            }
        """.trimIndent()
        val payload = JSONObject()
            .put("operationName", "getQuoteBySymbol")
            .put("variables", JSONObject().put("symbol", symbol).put("locale", "en"))
            .put("query", query)
        val request = Request.Builder()
            .url("https://app-money.tmx.com/graphql")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .header("Accept", "application/json")
            .header("Origin", "https://money.tmx.com")
            .header("Referer", "https://money.tmx.com/en/quote/$symbol")
            .header("locale", "en")
            .build()
        return http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use emptyList()
            val body = response.body?.string() ?: return@use emptyList()
            parseTmx(JSONObject(body))
        }
    }

    /** Reads `data.getQuoteBySymbol`: the latest declared ex-date, pay date and per-payment amount. */
    fun parseTmx(root: JSONObject): List<DividendHistoryOrgEntry> {
        val quote = root.optJSONObject("data")?.optJSONObject("getQuoteBySymbol") ?: return emptyList()
        val exDate = parseDay(quote.optString("exDividendDate")) ?: return emptyList()
        val amount = when (val raw = quote.opt("dividendAmount")) {
            is Number -> raw.toDouble()
            is String -> raw.replace("$", "").trim().toDoubleOrNull()
            else -> null
        }?.takeIf { it > 0 && !it.isNaN() && !it.isInfinite() } ?: return emptyList()
        return listOf(
            DividendHistoryOrgEntry(
                exDateMs = exDate,
                payDateMs = parseDay(quote.optString("dividendPayDate")),
                amountPerShare = amount,
                isEstimated = false
            )
        )
    }

    // ── Nasdaq ───────────────────────────────────────────────────────────────

    private fun fetchNasdaq(symbol: String): List<DividendHistoryOrgEntry> {
        // The asset class is part of the path and a symbol does not say
        // whether it is an ETF, so ask as a stock and then as an ETF.
        for (assetClass in listOf("stocks", "etf")) {
            val request = Request.Builder()
                .url("https://api.nasdaq.com/api/quote/$symbol/dividends?assetclass=$assetClass")
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                )
                .header("Accept", "application/json, text/plain, */*")
                .header("Origin", "https://www.nasdaq.com")
                .header("Referer", "https://www.nasdaq.com/")
                .build()
            val rows = runCatching {
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use emptyList()
                    val body = response.body?.string() ?: return@use emptyList()
                    parseNasdaq(JSONObject(body))
                }
            }.getOrDefault(emptyList())
            if (rows.isNotEmpty()) return rows
        }
        return emptyList()
    }

    /**
     * Reads `data.dividends.rows`: every cash distribution with its ex and
     * payment dates. Only the recent slice is kept — this feed is here for
     * announcements; the long record already comes from the events block.
     */
    fun parseNasdaq(root: JSONObject, nowMs: Long = System.currentTimeMillis()): List<DividendHistoryOrgEntry> {
        val rows = root.optJSONObject("data")?.optJSONObject("dividends")?.optJSONArray("rows")
            ?: return emptyList()
        val cutoff = nowMs - 450L * DAY_MS
        val out = ArrayList<DividendHistoryOrgEntry>()
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val type = row.optString("type")
            if (type.isNotBlank() && !type.equals("Cash", ignoreCase = true)) continue
            val exDate = parseDay(row.optString("exOrEffDate"), "MM/dd/yyyy") ?: continue
            if (exDate < cutoff) continue
            val amount = row.optString("amount").replace("$", "").replace(",", "").trim()
                .toDoubleOrNull()?.takeIf { it > 0 } ?: continue
            out += DividendHistoryOrgEntry(
                exDateMs = exDate,
                payDateMs = parseDay(row.optString("paymentDate"), "MM/dd/yyyy"),
                amountPerShare = amount,
                isEstimated = false
            )
        }
        return out.sortedByDescending { it.exDateMs }
    }

    // ── Merge ────────────────────────────────────────────────────────────────

    /**
     * Lays [declared] rows over [record]: a declared row replaces whatever the
     * record had for the same distribution (an "unconfirmed" projection, or an
     * ex-date-only event), and is added when the record has nothing for it.
     *
     * A declared amount wildly out of line with the fund's own payments is kept
     * for its DATES only, as an estimated row, rather than trusted — the feeds
     * are undocumented, and one reporting an annual figure where a per-payment
     * one belongs must not triple the card.
     */
    fun merge(
        record: List<DividendHistoryOrgEntry>,
        declared: List<DividendHistoryOrgEntry>,
        nowMs: Long = System.currentTimeMillis()
    ): List<DividendHistoryOrgEntry> {
        if (declared.isEmpty()) return record
        val tolerance = 5L * DAY_MS
        val recentCutoff = nowMs - 2L * 365 * DAY_MS
        val largestRecent = record
            .filter { !it.isEstimated && it.exDateMs >= recentCutoff }
            .maxOfOrNull { it.amountPerShare }

        val merged = record.toMutableList()
        for (row in declared) {
            val incoming = if (largestRecent != null && largestRecent > 0 && row.amountPerShare > largestRecent * 3) {
                row.copy(isEstimated = true)
            } else row
            val index = merged.indexOfFirst { kotlin.math.abs(it.exDateMs - row.exDateMs) <= tolerance }
            if (index >= 0) {
                val existing = merged[index]
                // A confirmed row already on file wins over a doubted one.
                if (incoming.isEstimated && !existing.isEstimated) continue
                merged[index] = incoming.copy(payDateMs = incoming.payDateMs ?: existing.payDateMs)
            } else {
                merged += incoming
            }
        }
        return merged.sortedByDescending { it.exDateMs }
    }
}
