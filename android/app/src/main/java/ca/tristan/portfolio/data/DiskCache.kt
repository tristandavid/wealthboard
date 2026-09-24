package ca.tristan.portfolio.data

import ca.tristan.portfolio.net.DividendHistoryOrgEntry
import ca.tristan.portfolio.net.NewsCategory
import ca.tristan.portfolio.net.NewsItem
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * An on-disk cache for things that are expensive to fetch and cheap to keep:
 * news headlines and scraped dividend calendars.
 *
 * ## Why files rather than Room
 *
 * Room holds what the user typed, and adding tables to it means a schema
 * migration — real risk, for data that can be thrown away at any moment. These
 * caches live as JSON files in `cacheDir`, which is the honest location:
 * Android is free to delete that directory under storage pressure, which is
 * exactly the right outcome for something re-fetchable. Nothing here is
 * load-bearing; every read can return null and every caller copes.
 *
 * ## Why hand-rolled JSON
 *
 * The project has no Gson, Moshi or kotlinx-serialization, and adding one to
 * persist two small shapes would be a dependency for its own sake. `org.json`
 * is already used throughout the networking layer, so this matches how the
 * rest of the code reads and writes JSON.
 *
 * Attached once at startup, the same way [FxRates] is, so the object itself
 * needs no Context.
 */
object DiskCache {

    private const val MARKET_NEWS = "market-news.json"
    private const val DIVIDEND_NEWS = "dividend-news.json"
    private const val DIVIDEND_CALENDARS = "dividend-calendars.json"

    @Volatile
    private var dir: File? = null

    /** Call once at startup with `application.cacheDir`. */
    fun attach(cacheDir: File) {
        val target = File(cacheDir, "wealthboard")
        if (!target.exists()) target.mkdirs()
        dir = target
    }

    private fun file(name: String): File? = dir?.let { File(it, name) }

    /**
     * Reads a cache file and returns its payload with the age of the write.
     *
     * Age travels with the value rather than being checked here, because the
     * three callers want three different policies: a cold launch paints
     * whatever it has however old, a pull-to-refresh skips the cache entirely,
     * and a background refresh wants to know whether to bother.
     */
    private fun read(name: String): Pair<JSONObject, Long>? {
        val f = file(name) ?: return null
        if (!f.exists()) return null
        return try {
            val root = JSONObject(f.readText())
            val storedAt = root.optLong("storedAt", 0L)
            if (storedAt <= 0L) null else root to (System.currentTimeMillis() - storedAt)
        } catch (_: Exception) {
            // A file written by an older build that no longer parses is a miss,
            // not an error. A cache that cannot be read is a cache that is empty.
            null
        }
    }

    private fun write(name: String, body: JSONObject) {
        val f = file(name) ?: return
        try {
            body.put("storedAt", System.currentTimeMillis())
            // Via a temp file, so a crash mid-write leaves the previous good
            // copy rather than a truncated one.
            val tmp = File(f.parentFile, "${f.name}.tmp")
            tmp.writeText(body.toString())
            if (!tmp.renameTo(f)) {
                f.writeText(body.toString())
                tmp.delete()
            }
        } catch (_: Exception) {
            // Best effort by design.
        }
    }

    // ── News ─────────────────────────────────────────────────────────────────

    private fun newsFile(dividend: Boolean) = if (dividend) DIVIDEND_NEWS else MARKET_NEWS

    /**
     * The last headlines fetched, or null when there are none or they are old
     * enough that showing them would mislead.
     *
     * A day. Past that the feed is history rather than news, and an empty tab
     * that fills in beats yesterday's headlines presented as today's.
     */
    fun loadNews(dividend: Boolean, maxAgeMs: Long = 24L * 60 * 60 * 1000): List<NewsItem>? {
        val (root, age) = read(newsFile(dividend)) ?: return null
        if (age > maxAgeMs) return null
        val arr = root.optJSONArray("items") ?: return null
        val out = ArrayList<NewsItem>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val title = o.optString("title").takeIf { it.isNotBlank() } ?: continue
            val link = o.optString("linkUrl").takeIf { it.isNotBlank() } ?: continue
            out.add(
                NewsItem(
                    title = title,
                    publisher = o.optString("publisher").ifBlank { "Market news" },
                    linkUrl = link,
                    publishedAt = o.optLong("publishedAt", 0L),
                    imageUrl = o.optString("imageUrl").takeIf { it.isNotBlank() },
                    summary = o.optString("summary").takeIf { it.isNotBlank() },
                    category = runCatching {
                        NewsCategory.valueOf(o.optString("category", NewsCategory.MARKETS.name))
                    }.getOrDefault(NewsCategory.MARKETS)
                )
            )
        }
        return out.ifEmpty { null }
    }

    /** Bounded at 60 — the feeds return about that and nobody scrolls further. */
    fun saveNews(dividend: Boolean, items: List<NewsItem>) {
        if (items.isEmpty()) return
        val arr = JSONArray()
        items.take(60).forEach { item ->
            arr.put(
                JSONObject().apply {
                    put("title", item.title)
                    put("publisher", item.publisher)
                    put("linkUrl", item.linkUrl)
                    put("publishedAt", item.publishedAt)
                    item.imageUrl?.let { put("imageUrl", it) }
                    item.summary?.let { put("summary", it) }
                    put("category", item.category.name)
                }
            )
        }
        write(newsFile(dividend), JSONObject().put("items", arr))
    }

    // ── Dividend calendars ───────────────────────────────────────────────────

    /**
     * Every cached payout calendar, keyed by uppercased ticker, with the age of
     * the file.
     *
     * Scraping these is the slowest thing the app does — up to four candidate
     * URLs per ticker — and a fund declares a distribution a few times a year,
     * so re-running it on every launch was the worst effort-to-value ratio in
     * the codebase.
     */
    fun loadCalendars(): Pair<Map<String, List<DividendHistoryOrgEntry>>, Long>? {
        val (root, age) = read(DIVIDEND_CALENDARS) ?: return null
        val byTicker = root.optJSONObject("tickers") ?: return null
        val out = HashMap<String, List<DividendHistoryOrgEntry>>()
        for (ticker in byTicker.keys()) {
            val arr = byTicker.optJSONArray(ticker) ?: continue
            val rows = ArrayList<DividendHistoryOrgEntry>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val ex = o.optLong("exDateMs", 0L)
                val amount = o.optDouble("amountPerShare", Double.NaN)
                if (ex <= 0L || amount.isNaN()) continue
                rows.add(
                    DividendHistoryOrgEntry(
                        exDateMs = ex,
                        payDateMs = o.optLong("payDateMs", 0L).takeIf { it > 0L },
                        amountPerShare = amount,
                        isEstimated = o.optBoolean("isEstimated", false)
                    )
                )
            }
            if (rows.isNotEmpty()) out[ticker.uppercase()] = rows
        }
        return if (out.isEmpty()) null else out to age
    }

    fun saveCalendars(calendars: Map<String, List<DividendHistoryOrgEntry>>) {
        if (calendars.isEmpty()) return
        val byTicker = JSONObject()
        calendars.forEach { (ticker, rows) ->
            val arr = JSONArray()
            rows.forEach { row ->
                arr.put(
                    JSONObject().apply {
                        put("exDateMs", row.exDateMs)
                        row.payDateMs?.let { put("payDateMs", it) }
                        put("amountPerShare", row.amountPerShare)
                        put("isEstimated", row.isEstimated)
                    }
                )
            }
            byTicker.put(ticker.uppercase(), arr)
        }
        write(DIVIDEND_CALENDARS, JSONObject().put("tickers", byTicker))
    }

    /** For a "clear cached data" action, or a sign-out. */
    fun clearAll() {
        listOf(MARKET_NEWS, DIVIDEND_NEWS, DIVIDEND_CALENDARS).forEach { file(it)?.delete() }
    }
}
