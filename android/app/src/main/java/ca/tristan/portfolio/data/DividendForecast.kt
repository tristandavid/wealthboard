package ca.tristan.portfolio.data

import ca.tristan.portfolio.net.UpcomingDividend
import java.util.Calendar

/**
 * Forward dividend projection, shared by every chart that shows future income.
 *
 * Three screens each rolled their own version of this and all three made the
 * same two mistakes:
 *
 *  1. They repeated one per-payment amount at a fixed cadence, so a quarterly
 *     ETF drew four identical bars. Real distributions are seasonal — XEQT pays
 *     a token Q1 and a large Q4, and dividing the annual rate into four equal
 *     parts overstates the small quarters by 3x and understates December by as
 *     much. The forward view exists precisely to show which months are heavy.
 *  2. They stepped forward by `365 / frequency` days. That is not a quarter; it
 *     drifts about five days a year, so a ten-year projection walks payments
 *     into the wrong months and eventually the wrong calendar year.
 *
 * This projects from the fund's own record instead: each payment in the last
 * twelve months is assumed to repeat on the same date next year, grown by the
 * distribution growth rate. The seasonal shape, the payment months and the
 * annual total all come out right, and the annual total stays consistent with
 * the trailing yield shown elsewhere on the same screen.
 */
object DividendForecast {

    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** One projected payment: when it lands and what it pays per unit. */
    data class ProjectedPayment(
        val atMillis: Long,
        val perUnit: Double,
        /** True when this is the amount the fund actually declared, not a forecast. */
        val isAnnounced: Boolean = false
    )

    /**
     * Projects per-unit payments falling in (fromMillis, untilMillis].
     *
     * @param history    the fund's own record as (dateMillis, amountPerUnit), any order
     * @param upcoming   the next announced/estimated payment, when one is known
     * @param growthRate annual distribution growth as a decimal (0.03 = 3 %/yr)
     */
    /**
     * Annual growth assumed when a fund has too little history to measure.
     * A floor, not the model — see [measuredGrowth].
     */
    const val FALLBACK_GROWTH_RATE = 0.03

    /**
     * The fund's own distribution growth, measured from its record.
     *
     * ## Why this measures several years, not one
     *
     * This used to compare the last cycle of payments against the cycle
     * before it — one year against one year — and then compounded that single
     * ratio across the whole ten-year projection. For a fund with lumpy
     * distributions that is not a trend, it is noise with a decade-long lever
     * attached: XEQT's trailing year came in a couple of percent under the
     * year before, so the Yearly Income chart sloped DOWNWARD for ten straight
     * bars off one ordinary quarter. A broad equity ETF does not have a
     * negative distribution trend; the measurement window was simply too
     * short to tell a trend from a wobble.
     *
     * So it now spans as many WHOLE cycles as the record supports, up to
     * [MAX_CYCLES] on each side, and takes the geometric mean per year. Three
     * years against three years still moves with a real change in
     * distributions, and barely notices one heavy or light quarter.
     *
     * Clamped tighter than the per-payment estimate for the same reason as
     * before — that estimate applies its ratio once, this one compounds — and
     * [dampedYears] fades it further as the horizon extends.
     *
     * Null when there is not a full cycle on each side to compare, which is
     * when the caller's fallback earns its place.
     */
    const val MAX_CYCLES = 3

    fun measuredGrowth(history: List<Pair<Long, Double>>, frequency: Int): Double? {
        if (frequency <= 0) return null
        val ordered = history.sortedByDescending { it.first }

        // Whole cycles only, on BOTH sides. A partial cycle on either side
        // compares four quarters against three and reads the missing payment
        // as a cut.
        val cycles = minOf(MAX_CYCLES, ordered.size / (frequency * 2))
        if (cycles < 1) return null

        val span = frequency * cycles
        val recent = ordered.take(span).sumOf { it.second }
        val prior = ordered.drop(span).take(span).sumOf { it.second }
        if (prior <= 0.0 || recent <= 0.0) return null

        // Geometric mean: the ratio spans `cycles` YEARS, so the annual rate is
        // its cycles-th root, not the whole thing.
        val annual = Math.pow(recent / prior, 1.0 / cycles) - 1.0
        return annual.coerceIn(-0.05, 0.10)
    }

    /**
     * How fast the measured rate stops being believed further out.
     *
     * Each year ahead counts a little less than the one before it, so a rate
     * measured from a few years of history does not run unchecked for a
     * decade. Nothing here can know a fund's distribution policy in 2036.
     */
    const val GROWTH_FADE = 0.85

    /**
     * Years of growth actually applied `yearsOut` years ahead.
     *
     * The plain model charges the full rate every year, so ten years out it
     * has compounded ten times on a reading taken from the recent past. This
     * sums a decaying series instead — 1 + f + f² + … — which converges, so
     * the projection tapers toward a flat line rather than trending forever in
     * whichever direction the last few years happened to point.
     *
     * At [GROWTH_FADE] 0.85 a ten-year horizon applies about 5.3 years of
     * growth. Year one is unchanged, which is what keeps the near-term bars
     * agreeing with the Upcoming card.
     */
    fun dampedYears(yearsOut: Double): Double {
        if (yearsOut <= 0.0) return 0.0
        if (GROWTH_FADE >= 1.0) return yearsOut
        return (1.0 - Math.pow(GROWTH_FADE, yearsOut)) / (1.0 - GROWTH_FADE)
    }

    fun project(
        history: List<Pair<Long, Double>>,
        upcoming: UpcomingDividend?,
        fromMillis: Long,
        untilMillis: Long,
        growthRate: Double? = null
    ): List<ProjectedPayment> {
        if (untilMillis <= fromMillis) return emptyList()

        val freq = normalizeFrequency(
            upcoming?.paymentFrequencyPerYear ?: inferFrequency(history)
        )

        // The fund's own record first, the caller's figure second, and the
        // fallback only when neither exists. Callers pass null to mean "use
        // whatever this fund actually does", which is what every chart wants.
        val resolved = growthRate
            ?: measuredGrowth(history, freq)
            ?: FALLBACK_GROWTH_RATE
        val growth = resolved.coerceIn(-0.5, 0.5)

        // The template year: the most recent full cycle of payments. Anything
        // older is history, not a pattern — a fund that changed its cadence
        // should be projected on the cadence it uses now.
        val template = recentCycle(history, freq, fromMillis)

        val out = if (template.isNotEmpty()) {
            seasonalSchedule(template, fromMillis, untilMillis, growth)
        } else {
            flatSchedule(upcoming, freq, fromMillis, untilMillis, growth)
        }

        return applyAnnouncedPayment(out, upcoming, fromMillis, untilMillis)
            .sortedBy { it.atMillis }
    }

    /** Convenience: total per-unit distribution projected over the next year. */
    fun forwardAnnualPerUnit(
        history: List<Pair<Long, Double>>,
        upcoming: UpcomingDividend?,
        nowMillis: Long = System.currentTimeMillis(),
        growthRate: Double? = null
    ): Double? {
        val year = project(
            history, upcoming,
            fromMillis = nowMillis,
            untilMillis = nowMillis + 365L * DAY_MS,
            growthRate = growthRate
        )
        return year.sumOf { it.perUnit }.takeIf { it > 0.0 }
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private fun normalizeFrequency(raw: Int?): Int = when {
        raw == null -> 4
        raw >= 12 -> 12
        raw >= 6 -> 6
        raw >= 4 -> 4
        raw >= 2 -> 2
        else -> 1
    }

    /**
     * Payment cadence implied by the median gap between the recent payments.
     *
     * Median rather than mean: one skipped or special distribution should not
     * reclassify a monthly payer as quarterly.
     */
    fun inferFrequency(history: List<Pair<Long, Double>>): Int? {
        if (history.size < 2) return null
        val sorted = history.sortedByDescending { it.first }.take(9)
        val gaps = sorted.zipWithNext()
            .map { (a, b) -> kotlin.math.abs((a.first - b.first) / DAY_MS) }
            .filter { it > 3 }
            .sorted()
        if (gaps.isEmpty()) return null
        val median = gaps[gaps.size / 2]
        return when {
            median <= 45 -> 12
            median <= 80 -> 6
            median <= 135 -> 4
            median <= 250 -> 2
            else -> 1
        }
    }

    /**
     * The most recent complete cycle of payments — at most [freq] of them, and
     * none older than ~14 months, so the template reflects what the fund pays
     * now rather than what it paid before a cut or a raise.
     */
    private fun recentCycle(
        history: List<Pair<Long, Double>>,
        freq: Int,
        nowMillis: Long
    ): List<Pair<Long, Double>> {
        if (history.isEmpty()) return emptyList()
        val cutoff = nowMillis - 425L * DAY_MS
        val recent = history
            .filter { it.first in cutoff..nowMillis && it.second > 0.0 }
            .sortedByDescending { it.first }
            .take(freq)
        // A partial cycle would project a year with holes in it — a fund that
        // has only paid twice cannot tell us what its other two quarters look
        // like, so fall back to the flat estimate instead of inventing zeros.
        return if (recent.size >= freq) recent.sortedBy { it.first } else emptyList()
    }

    /**
     * Repeats each payment in the template on its own anniversary, compounding
     * the growth rate once per year out.
     */
    private fun seasonalSchedule(
        template: List<Pair<Long, Double>>,
        fromMillis: Long,
        untilMillis: Long,
        growth: Double
    ): List<ProjectedPayment> {
        val out = mutableListOf<ProjectedPayment>()
        val cal = Calendar.getInstance()
        for ((atMs, amount) in template) {
            // Year 0 is the template payment itself, which is already history;
            // start at the first anniversary that lands inside the window.
            var year = 1
            while (year <= 40) {
                cal.timeInMillis = atMs
                cal.add(Calendar.YEAR, year)
                val ts = cal.timeInMillis
                if (ts > untilMillis) break
                if (ts > fromMillis) {
                    out += ProjectedPayment(ts, amount * Math.pow(1.0 + growth, dampedYears(year.toDouble())))
                }
                year++
            }
        }
        return out
    }

    /**
     * Even cadence from the next known payment — used only when the fund's
     * record is too short to show a seasonal pattern.
     *
     * Steps in whole calendar months rather than 365/freq days, so payments
     * stay in the months they belong to however far out the projection runs.
     */
    private fun flatSchedule(
        upcoming: UpcomingDividend?,
        freq: Int,
        fromMillis: Long,
        untilMillis: Long,
        growth: Double
    ): List<ProjectedPayment> {
        val perUnit = upcoming?.perPaymentAmount
            ?: upcoming?.estimatedAnnualRate?.div(freq.toDouble())
            ?: return emptyList()
        if (perUnit <= 0.0) return emptyList()

        val stepMonths = (12 / freq).coerceAtLeast(1)
        val anchor = upcoming?.payDateMillis ?: upcoming?.exDividendDateMillis ?: fromMillis
        val cal = Calendar.getInstance().apply { timeInMillis = anchor }
        // Roll an already-past anchor forward rather than emitting nothing.
        while (cal.timeInMillis <= fromMillis) cal.add(Calendar.MONTH, stepMonths)

        val out = mutableListOf<ProjectedPayment>()
        var guard = 0
        while (cal.timeInMillis <= untilMillis && guard < 500) {
            val yearsOut = (cal.timeInMillis - fromMillis).toDouble() / (365.0 * DAY_MS)
            out += ProjectedPayment(
                cal.timeInMillis,
                perUnit * Math.pow(1.0 + growth, dampedYears(yearsOut))
            )
            cal.add(Calendar.MONTH, stepMonths)
            guard++
        }
        return out
    }

    /**
     * Replaces the first projected payment with the declared one when the fund
     * has actually announced it.
     *
     * Matched by date within half a cycle so the announcement supersedes the
     * forecast for that payment rather than being added alongside it, which
     * would double-count the nearest quarter.
     */
    private fun applyAnnouncedPayment(
        projected: List<ProjectedPayment>,
        upcoming: UpcomingDividend?,
        fromMillis: Long,
        untilMillis: Long
    ): List<ProjectedPayment> {
        val amount = upcoming?.perPaymentAmount?.takeIf { it > 0.0 } ?: return projected
        if (!upcoming.isAnnounced) return projected
        val at = upcoming.payDateMillis ?: upcoming.exDividendDateMillis ?: return projected
        if (at <= fromMillis || at > untilMillis) return projected

        val windowMs = 60L * DAY_MS
        val nearest = projected.minByOrNull { kotlin.math.abs(it.atMillis - at) }
        val replaced = projected.toMutableList()
        if (nearest != null && kotlin.math.abs(nearest.atMillis - at) <= windowMs) {
            replaced.remove(nearest)
        }
        replaced += ProjectedPayment(at, amount, isAnnounced = true)
        return replaced
    }
}
