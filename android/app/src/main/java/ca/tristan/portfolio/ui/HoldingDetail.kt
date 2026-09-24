package ca.tristan.portfolio.ui

import ca.tristan.portfolio.data.db.HoldingEntity
import ca.tristan.portfolio.net.Quote
import ca.tristan.portfolio.net.UpcomingDividend
import ca.tristan.portfolio.net.YahooQuoteClient

/**
 * One historical or scheduled distribution shown in the payouts list.
 *
 * Both dates are carried because the schedule is about WHEN THE MONEY ARRIVES,
 * and the two are not the same day — the gap between a distribution going ex
 * and the cash landing runs from a couple of days to several weeks depending on
 * the fund. This row used to hold only [exDateMs], so the "Dividend Payout
 * Schedule" was quietly listing ex-dates under a heading that promised payment
 * dates: XEQT's September 2026 distribution goes ex on the 24th and pays on the
 * 29th, and the screen said the 24th.
 *
 * For a payment the user logged by hand there is no ex-date to know, so
 * [payDateMs] is the only one set; for a forecast from the payout calendar both
 * are usually known.
 */
data class PayoutRow(
    val exDateMs: Long?,
    val payDateMs: Long?,
    val amountPerUnit: Double,
    val totalForPosition: Double,
    val isUpcoming: Boolean,
    /** True when an upcoming row is the amount the fund declared, not a forecast. */
    val isAnnounced: Boolean = false
) {
    /** The date the row is filed under: when the cash lands, where that is
     *  known, and the ex-date only as a stand-in. */
    val displayDateMs: Long? get() = payDateMs ?: exDateMs

    /** True when the date above is a stand-in, so the UI can say so rather
     *  than presenting an ex-date as a payment date. */
    val isExDateFallback: Boolean get() = payDateMs == null && exDateMs != null
}

/** A point on the trailing-yield sparkline (yield % at a past date). */
data class YieldPoint(val atMs: Long, val yieldPercent: Double)

/**
 * Everything the holding detail screen renders, assembled in one place so the
 * UI stays declarative and the arithmetic is testable/reviewable on its own.
 *
 * All money figures are in the holding's own currency; no FX conversion is
 * applied, matching how the rest of the app treats per-holding values.
 */
data class HoldingDetail(
    /**
     * The row the screen was opened from. Edits, new transactions and logged
     * dividends act on this one; every FIGURE here covers the whole position.
     */
    val holding: HoldingEntity,
    /**
     * Every account's row for this security, largest slice first — what the
     * figures are summed from, and what the per-account breakdown lists.
     */
    val slices: List<HoldingEntity> = emptyList(),
    val quote: Quote? = null,
    val upcoming: UpcomingDividend? = null,
    val payouts: List<PayoutRow> = emptyList(),
    val yieldHistory: List<YieldPoint> = emptyList(),
    /**
     * The fund's own distribution record: (ex-date millis, amount per unit).
     *
     * This is the security's payment history, not the user's — it covers
     * periods before the position existed, which is exactly what makes it
     * useful for showing when a fund started paying and how the per-unit
     * distribution has moved since. Money the user actually received lives
     * in [allTimeReceived] and the logged payments instead.
     */
    val dividendEvents: List<Pair<Long, Double>> = emptyList(),

    // ── Position ──
    val marketValue: Double = 0.0,
    val averageCost: Double? = null,
    val totalContributions: Double? = null,
    val priceReturn: Double? = null,
    val priceReturnPct: Double? = null,
    val totalReturn: Double? = null,
    val totalReturnPct: Double? = null,
    val portfolioWeightPct: Double? = null,

    // ── Dividends ──
    val trailingAnnualPerUnit: Double? = null,
    val yieldTtmPct: Double? = null,
    val yieldOnCostPct: Double? = null,
    val divGrowth1YPct: Double? = null,
    val divGrowth5YPct: Double? = null,
    val divChangeRecentPct: Double? = null,
    val divChangeRecentLabel: String? = null,
    val divChangeVs1YPct: Double? = null,
    val frequencyPerYear: Int? = null,
    val allTimeReceived: Double = 0.0,
    val estimatedAnnualIncome: Double? = null,
    val tenYearAvgYieldPct: Double? = null,

    // ── Income timeline ──
    /**
     * Payments the user has on record, as (paidAt, cash), and the forward
     * projection, as (expectedAt, cash) — both already scaled by the position.
     *
     * These live on the detail model rather than being fetched separately by
     * the chart. They were separate, and the chart read a cache the detail
     * screen never populated, so a holding could print "Nov 9 — upcoming" in
     * its payout schedule with an entirely empty chart sitting above it.
     */
    val receivedFlows: List<Pair<Long, Double>> = emptyList(),
    val projectedFlows: List<Pair<Long, Double>> = emptyList()
) {
    /** True when this security is held in more than one account. */
    val isSplit: Boolean get() = slices.size > 1

    /** Where the current price sits in the 52-week band, as 0..1. */
    val fiftyTwoWeekFraction: Float?
        get() {
            val q = quote ?: return null
            val lo = q.fiftyTwoWeekLow ?: return null
            val hi = q.fiftyTwoWeekHigh ?: return null
            if (hi <= lo) return null
            return (((q.price - lo) / (hi - lo)).coerceIn(0.0, 1.0)).toFloat()
        }

    /** Human label for payment cadence. */
    val frequencyLabel: String
        get() = when (frequencyPerYear) {
            12 -> "Monthly"; 4 -> "Quarterly"; 2 -> "Semi-annual"; 1 -> "Annual"
            null -> "—"
            else -> "×$frequencyPerYear / yr"
        }

    /** Qualitative tag for a growth rate, mirroring how dividend trackers label them. */
    fun growthTag(pct: Double?): String? = when {
        pct == null -> null
        pct < 0     -> "Negative"
        pct < 3     -> "Slow"
        pct < 8     -> "Moderate"
        pct < 15    -> "Fast"
        else        -> "Very Fast"
    }
}

/** Pure computation helpers for [HoldingDetail], kept out of the ViewModel. */
object HoldingDetailMath {

    private const val DAY_MS = 24L * 60 * 60 * 1000

    /**
     * Compound annual growth rate between the dividends paid in the 12 months
     * ending [years] ago and those paid in the last 12 months.
     *
     * Returns null when either window has no payments, which is the honest
     * answer for a fund with too little history rather than a misleading 0%.
     */
    fun dividendCagr(events: List<Pair<Long, Double>>, years: Int, now: Long): Double? {
        if (events.isEmpty() || years <= 0) return null
        fun windowSum(endAgoYears: Int): Double {
            val end   = now - endAgoYears * 365L * DAY_MS
            val start = end - 365L * DAY_MS
            return events.filter { it.first in start..end }.sumOf { it.second }
        }
        val recent = windowSum(0)
        val past   = windowSum(years)
        if (recent <= 0.0 || past <= 0.0) return null
        return (Math.pow(recent / past, 1.0 / years) - 1.0) * 100.0
    }

    /** Percent change between the two most recent distributions. */
    fun mostRecentChangePct(events: List<Pair<Long, Double>>): Pair<Double, Long>? {
        val sorted = events.sortedByDescending { it.first }
        if (sorted.size < 2) return null
        val (latestMs, latest) = sorted[0]
        val prior = sorted[1].second
        if (prior <= 0.0) return null
        return ((latest - prior) / prior) * 100.0 to latestMs
    }

    /**
     * Percent change between the latest distribution and the one closest to a
     * year earlier — the seasonally-comparable payment.
     */
    fun changeVsYearAgoPct(events: List<Pair<Long, Double>>): Double? {
        val sorted = events.sortedByDescending { it.first }
        val latest = sorted.firstOrNull() ?: return null
        val target = latest.first - 365L * DAY_MS
        val yearAgo = sorted.drop(1)
            .minByOrNull { kotlin.math.abs(it.first - target) } ?: return null
        if (kotlin.math.abs(yearAgo.first - target) > 45L * DAY_MS) return null
        if (yearAgo.second <= 0.0) return null
        return ((latest.second - yearAgo.second) / yearAgo.second) * 100.0
    }

    /** Sum of distributions in the trailing 12 months. */
    fun trailingTwelveMonths(events: List<Pair<Long, Double>>, now: Long): Double =
        events.filter { it.first >= now - 365L * DAY_MS }.sumOf { it.second }

    /**
     * Trailing-yield series: for each historical close, the trailing-12-month
     * dividend as of that date over the price on that date.
     */
    fun buildYieldHistory(
        priceBars: List<Pair<Long, Double>>,
        events: List<Pair<Long, Double>>
    ): List<YieldPoint> {
        if (priceBars.isEmpty() || events.isEmpty()) return emptyList()
        return priceBars.mapNotNull { (ts, price) ->
            if (price <= 0.0) return@mapNotNull null
            val ttm = events.filter { it.first in (ts - 365L * DAY_MS)..ts }.sumOf { it.second }
            if (ttm <= 0.0) null else YieldPoint(ts, ttm / price * 100.0)
        }
    }

    /**
     * Per-unit distributions summed per calendar year, oldest first.
     *
     * The current year is dropped when it is still in progress, because a
     * part-year total plotted beside full years reads as a dividend cut
     * rather than as a year that hasn't finished paying out yet.
     */
    fun annualDividends(
        events: List<Pair<Long, Double>>,
        now: Long = System.currentTimeMillis()
    ): List<Pair<Int, Double>> {
        if (events.isEmpty()) return emptyList()
        val cal = java.util.Calendar.getInstance()
        fun yearOf(ts: Long): Int {
            cal.timeInMillis = ts
            return cal.get(java.util.Calendar.YEAR)
        }
        cal.timeInMillis = now
        val thisYear = cal.get(java.util.Calendar.YEAR)
        // Treat the current year as complete only once December has passed.
        val currentYearComplete = cal.get(java.util.Calendar.MONTH) >= 11

        return events
            .groupBy { yearOf(it.first) }
            .mapValues { (_, v) -> v.sumOf { it.second } }
            .filterKeys { it < thisYear || currentYearComplete }
            .toSortedMap()
            .map { (y, amount) -> y to amount }
    }

    /** Payment frequency implied by the median gap between distributions. */
    fun inferFrequency(events: List<Pair<Long, Double>>): Int? {
        if (events.size < 2) return null
        val sorted = events.sortedByDescending { it.first }
        val gaps = sorted.zipWithNext()
            .take(6)
            .map { kotlin.math.abs((it.first.first - it.second.first) / DAY_MS) }
            .sorted()
        if (gaps.isEmpty()) return null
        val median = gaps[gaps.size / 2]
        return when {
            median <= 45  -> 12
            median <= 105 -> 4
            median <= 200 -> 2
            else          -> 1
        }
    }
}
