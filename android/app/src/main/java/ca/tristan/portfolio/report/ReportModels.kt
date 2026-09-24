package ca.tristan.portfolio.report

import ca.tristan.portfolio.data.db.HoldingType
import java.util.Calendar

/**
 * The period a performance report covers.
 *
 * [SINCE_INCEPTION] runs from the first recorded transaction, which is the
 * only window where "your whole history" is meaningful for a hand-entered
 * portfolio. Everything else is a trailing window ending today.
 */
enum class ReportPeriod(val label: String) {
    MONTH_1("1 Month"),
    MONTH_3("3 Months"),
    MONTH_6("6 Months"),
    YTD("Year to Date"),
    YEAR_1("1 Year"),
    YEAR_3("3 Years"),
    YEAR_5("5 Years"),
    SINCE_INCEPTION("Since Inception");

    /**
     * Start of the window in epoch millis.
     *
     * [inceptionMillis] is the date of the earliest transaction on file and is
     * used both for [SINCE_INCEPTION] and as a floor for every other window —
     * there is no point charting a 5-year period for an account opened last
     * March, and doing so makes the equity curve start with a long flat zero.
     */
    fun startMillis(nowMillis: Long, inceptionMillis: Long?): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = nowMillis }
        val raw = when (this) {
            MONTH_1 -> cal.apply { add(Calendar.MONTH, -1) }.timeInMillis
            MONTH_3 -> cal.apply { add(Calendar.MONTH, -3) }.timeInMillis
            MONTH_6 -> cal.apply { add(Calendar.MONTH, -6) }.timeInMillis
            YTD -> Calendar.getInstance().apply {
                timeInMillis = nowMillis
                set(Calendar.MONTH, Calendar.JANUARY)
                set(Calendar.DAY_OF_MONTH, 1)
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            YEAR_1 -> cal.apply { add(Calendar.YEAR, -1) }.timeInMillis
            YEAR_3 -> cal.apply { add(Calendar.YEAR, -3) }.timeInMillis
            YEAR_5 -> cal.apply { add(Calendar.YEAR, -5) }.timeInMillis
            SINCE_INCEPTION -> inceptionMillis ?: cal.apply { add(Calendar.YEAR, -1) }.timeInMillis
        }
        return if (inceptionMillis != null && raw < inceptionMillis) inceptionMillis else raw
    }

    /** Yahoo range string wide enough to cover this window with margin. */
    fun yahooRange(): String = when (this) {
        MONTH_1 -> "3mo"
        MONTH_3 -> "6mo"
        MONTH_6 -> "1y"
        YTD -> "1y"
        YEAR_1 -> "2y"
        YEAR_3 -> "5y"
        YEAR_5 -> "10y"
        SINCE_INCEPTION -> "max"
    }
}

/** A benchmark the portfolio's time-weighted return is measured against. */
data class Benchmark(val ticker: String, val displayName: String) {
    companion object {
        val SPY = Benchmark("SPY", "S&P 500 (SPY)")
        val QQQ = Benchmark("QQQ", "Nasdaq 100 (QQQ)")
        val VTI = Benchmark("VTI", "Total US Market (VTI)")
        val DIA = Benchmark("DIA", "Dow Jones (DIA)")

        val PRESETS = listOf(SPY, QQQ, VTI, DIA)
    }
}

/** One day on the portfolio's value / return curve. */
data class EquityPoint(
    val atMillis: Long,
    /** Market value of all positions plus uninvested dividend cash. */
    val value: Double,
    /** Growth of 1.0 invested at the start, linking daily time-weighted returns. */
    val returnIndex: Double,
    /** External money in (+) or out (-) recorded on this day. */
    val netFlow: Double
)

/** Headline return figures for the period. */
data class ReturnMetrics(
    /** Time-weighted return — strips out the timing of deposits. Comparable to a benchmark. */
    val timeWeightedReturn: Double,
    /** Money-weighted return (XIRR), annualized — what the investor personally earned. */
    val moneyWeightedReturn: Double?,
    /** Simple change in value net of flows, over average capital. */
    val simpleReturn: Double,
    /** TWR restated as a per-year rate. Null for periods under a year. */
    val annualizedReturn: Double?,
    val startValue: Double,
    val endValue: Double,
    val netContributions: Double,
    val totalGain: Double,
    val dividendIncome: Double,
    val realizedGain: Double,
    val unrealizedGain: Double,
    val periodDays: Int
)

/** Volatility and downside figures, all derived from the daily return series. */
data class RiskMetrics(
    val annualizedVolatility: Double?,
    val sharpeRatio: Double?,
    val maxDrawdown: Double?,
    val maxDrawdownPeakMillis: Long?,
    val maxDrawdownTroughMillis: Long?,
    /** True when the curve climbed back to its pre-drawdown peak before period end. */
    val drawdownRecovered: Boolean,
    val bestMonth: Pair<Long, Double>?,
    val worstMonth: Pair<Long, Double>?,
    val positiveMonths: Int,
    val totalMonths: Int,
    val riskFreeRateUsed: Double
)

/** How the portfolio did against its benchmark over the same window. */
data class BenchmarkComparison(
    val benchmark: Benchmark,
    val benchmarkReturn: Double,
    val portfolioReturn: Double,
    /** Portfolio TWR minus benchmark return. */
    val excessReturn: Double,
    val beta: Double?,
    /** Jensen's alpha, annualized. */
    val alpha: Double?,
    val correlation: Double?,
    /** Benchmark growth-of-1.0 curve, aligned to the portfolio's dates. */
    val benchmarkIndex: List<EquityPoint>
)

/** One row of the per-holding performance table. */
data class HoldingPerformance(
    val holdingId: Long,
    val name: String,
    val ticker: String?,
    val type: HoldingType,
    val accountName: String,
    val units: Double,
    val averageCost: Double?,
    val currentPrice: Double?,
    val marketValue: Double,
    val costBasis: Double,
    val unrealizedGain: Double,
    val unrealizedGainPercent: Double?,
    val realizedGain: Double,
    val dividendIncome: Double,
    /** Unrealized + realized + income. What this position actually made you. */
    val totalGain: Double,
    /** Share of the portfolio's market value. */
    val weight: Double,
    /** Share of the portfolio's total gain — signed, so losers read negative. */
    val contributionShare: Double?,
    val currency: String
)

/** A closed (sold) lot, for the realized-gains section. */
data class RealizedGainRow(
    val holdingName: String,
    val ticker: String?,
    val soldAtMillis: Long,
    val shares: Double,
    val salePrice: Double,
    val averageCostAtSale: Double,
    val proceeds: Double,
    val costOfUnitsSold: Double,
    val realizedGain: Double,
    val realizedGainPercent: Double?,
    val currency: String
)

/** Dividend income aggregated for the income section. */
data class IncomeSummary(
    val totalInPeriod: Double,
    val trailing12Months: Double,
    val byMonth: List<Pair<Long, Double>>,
    val byHolding: List<Pair<String, Double>>,
    /** Trailing 12 months of income over current cost basis. */
    val yieldOnCost: Double?,
    /** Trailing 12 months of income over current market value. */
    val currentYield: Double?,
    val reinvestedAmount: Double,
    val cashAmount: Double,
    val paymentCount: Int
)

/** One slice of an allocation breakdown. */
data class AllocationSlice(
    val label: String,
    val value: Double,
    val percent: Double
)

data class AllocationSummary(
    val byType: List<AllocationSlice>,
    val byAccount: List<AllocationSlice>,
    val byHolding: List<AllocationSlice>,
    /** Largest single position's weight — flagged in the report when concentrated. */
    val largestPositionWeight: Double,
    val largestPositionName: String?,
    /** Herfindahl index over holding weights, 0..1. Higher means more concentrated. */
    val concentrationIndex: Double
)

/** Buys/sells/DRIPs that happened inside the window. */
data class ActivitySummary(
    val buyCount: Int,
    val sellCount: Int,
    val dripCount: Int,
    val totalInvested: Double,
    val totalWithdrawn: Double,
    val totalReinvested: Double
)

/**
 * Everything a generated report contains.
 *
 * Built once by [PerformanceCalculator] and handed to the PDF writer, so the
 * screen and the document always show the same numbers.
 */
data class PerformanceReport(
    val generatedAtMillis: Long,
    val period: ReportPeriod,
    val periodStartMillis: Long,
    val periodEndMillis: Long,
    val currency: String,
    val returns: ReturnMetrics,
    val risk: RiskMetrics,
    val benchmark: BenchmarkComparison?,
    val equityCurve: List<EquityPoint>,
    val holdings: List<HoldingPerformance>,
    val realizedGains: List<RealizedGainRow>,
    val income: IncomeSummary,
    val allocation: AllocationSummary,
    val activity: ActivitySummary,
    /**
     * Positions with no ticker (seg funds, mutual funds, cash) that were held
     * flat at their entered price across the window. Surfaced in the report so
     * a reader knows which part of the curve is estimated rather than priced.
     */
    val untickeredHoldingNames: List<String>,
    /** Non-fatal problems worth showing, e.g. a benchmark that failed to load. */
    val warnings: List<String>
)

/** What the user ticked on the Reports screen before generating. */
data class ReportOptions(
    val period: ReportPeriod = ReportPeriod.YEAR_1,
    val benchmark: Benchmark? = Benchmark.SPY,
    val includeRiskMetrics: Boolean = true,
    val includeHoldingsTable: Boolean = true,
    val includeRealizedGains: Boolean = true,
    val includeIncome: Boolean = true,
    val includeAllocation: Boolean = true,
    val includeTransactionActivity: Boolean = true,
    /**
     * Restrict the report to a single holding. Null means the whole portfolio.
     *
     * Scoped by holding rather than by account: "how has this position done"
     * is the question people actually ask of a report, and an account-level
     * split is already visible in the allocation section.
     */
    val holdingId: Long? = null
)
