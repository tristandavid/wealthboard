package ca.tristan.portfolio.data

import android.content.SharedPreferences
import ca.tristan.portfolio.net.YahooQuoteClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Exchange rates, so a portfolio holding more than one currency can be totalled
 * honestly.
 *
 * Without this the app summed a USD position and a CAD position as though a
 * dollar were a dollar — a portfolio a third in SPY read roughly 13% light at a
 * 1.38 USD/CAD rate, and every allocation percentage was wrong alongside it.
 *
 * Rates come from Yahoo, which quotes FX as an ordinary symbol ("USDCAD=X"), so
 * this needs no second data source, no API key and no new dependency: it goes
 * through the same client as every other quote in the app.
 *
 * Lookups are deliberately synchronous. [PortfolioRepository.valueOfHolding] is
 * called from flow combines and list rendering where suspending isn't an option,
 * so rates are fetched ahead of time by [refresh] and read from cache here.
 */
object FxRates {

    /** Rates older than this are refetched on the next [refresh]. */
    private const val STALE_AFTER_MS = 6L * 60 * 60 * 1000

    private data class Cached(val rate: Double, val atMillis: Long)

    private val cache = ConcurrentHashMap<String, Cached>()

    @Volatile
    private var prefs: SharedPreferences? = null

    private const val PREF_PREFIX = "fx_"

    private fun key(from: String, to: String) = "${from.uppercase()}_${to.uppercase()}"

    /**
     * Seeds the cache from disk and keeps later fetches persisted.
     *
     * Without a warm cache a cold start would total the portfolio unconverted
     * for the second or two before the first rate lands, so the headline number
     * would visibly jump. A stored rate is stale but far closer to right than
     * treating a US dollar as a Canadian one.
     */
    fun attach(p: SharedPreferences) {
        prefs = p
        p.all.forEach { (k, v) ->
            if (!k.startsWith(PREF_PREFIX) || v !is String) return@forEach
            val parts = v.split('|')
            val rate = parts.getOrNull(0)?.toDoubleOrNull()
            val at = parts.getOrNull(1)?.toLongOrNull()
            if (rate != null && rate > 0 && at != null) {
                cache[k.removePrefix(PREF_PREFIX)] = Cached(rate, at)
            }
        }
    }

    private fun store(from: String, to: String, rate: Double, atMillis: Long) {
        val k = key(from, to)
        cache[k] = Cached(rate, atMillis)
        prefs?.edit()?.putString("$PREF_PREFIX$k", "$rate|$atMillis")?.apply()
    }

    /**
     * Latest known rate to multiply a [from] amount by to get [to], or null when
     * none has been fetched. Falls back to the inverse of the opposite pair, so
     * having fetched USD→CAD also answers CAD→USD.
     */
    fun rateOrNull(from: String, to: String): Double? {
        if (from.equals(to, ignoreCase = true)) return 1.0
        direct(from, to)?.let { return it }

        // Triangulate through any currency both sides are already quoted
        // against — in practice the reporting currency, since that is what
        // refresh() fetches everything against.
        //
        // Without this, a pair neither side of which is the base currency
        // simply had no rate: a US-dollar dividend logged against a Stockholm
        // listing needs USD→SEK, and the cache only ever holds USD→CAD and
        // SEK→CAD for a Canadian user. convert() then returned the amount
        // untouched, which reads as a real figure rather than a missing one.
        // The two legs are already in hand, so this costs no network call.
        val f = from.uppercase()
        val t = to.uppercase()
        val pivots = LinkedHashSet<String>()
        for (k in cache.keys) {
            val parts = k.split('_')
            if (parts.size == 2) { pivots += parts[0]; pivots += parts[1] }
        }
        for (p in pivots) {
            if (p == f || p == t) continue
            val leg1 = direct(f, p) ?: continue
            val leg2 = direct(p, t) ?: continue
            return leg1 * leg2
        }
        return null
    }

    /** A rate held outright, or the inverse of the opposite pair. No hops. */
    private fun direct(from: String, to: String): Double? {
        cache[key(from, to)]?.let { return it.rate }
        cache[key(to, from)]?.let { if (it.rate > 0) return 1.0 / it.rate }
        return null
    }

    /** True when [from] can be expressed in [to] right now. */
    fun hasRate(from: String?, to: String): Boolean {
        val f = from?.takeIf { it.isNotBlank() } ?: return true
        return rateOrNull(f, to) != null
    }

    /**
     * Converts [amount] from one currency to another.
     *
     * With no rate available the amount is returned unchanged rather than zeroed
     * — a slightly wrong total beats a portfolio that reads as empty. Callers
     * that need to tell the user about it use [hasRate] to spot the gap; the
     * dashboard does exactly that and labels the total as partly unconverted.
     */
    fun convert(amount: Double, from: String?, to: String): Double {
        val f = from?.takeIf { it.isNotBlank() } ?: return amount
        val rate = rateOrNull(f, to) ?: return amount
        return amount * rate
    }

    /** How long ago the rate between these two was fetched, or null if never. */
    fun ageMillis(from: String, to: String): Long? {
        val at = cache[key(from, to)]?.atMillis
            ?: cache[key(to, from)]?.atMillis
            ?: return null
        return System.currentTimeMillis() - at
    }

    /**
     * Fetches whatever is missing or stale to express [currencies] in [base].
     *
     * Call before any summing. Failures are swallowed on purpose: a portfolio
     * total that renders with a stale rate is far more useful than a screen that
     * refuses to draw because one FX request timed out.
     */
    suspend fun refresh(currencies: Collection<String>, base: String) = withContext(Dispatchers.IO) {
        val b = base.uppercase()
        currencies
            .map { it.uppercase() }
            .filter { it.isNotBlank() && it != b }
            .distinct()
            .forEach { c ->
                val age = ageMillis(c, b)
                if (age != null && age < STALE_AFTER_MS) return@forEach
                val rate = runCatching { YahooQuoteClient.fetch("$c$b=X")?.price }.getOrNull()
                if (rate != null && rate > 0) store(c, b, rate, System.currentTimeMillis())
            }
    }
}
