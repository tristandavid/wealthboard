import Foundation

// Ported from `report/ReportModels.kt`.

/// The period a performance report covers.
///
/// `sinceInception` runs from the first recorded transaction, which is the only
/// window where "your whole history" is meaningful for a hand-entered
/// portfolio. Everything else is a trailing window ending today.
enum ReportPeriod: String, CaseIterable, Identifiable, Hashable {
    case month1, month3, month6, ytd, year1, year3, year5, sinceInception

    var id: String { rawValue }

    var label: String {
        switch self {
        case .month1: return "1 Month"
        case .month3: return "3 Months"
        case .month6: return "6 Months"
        case .ytd: return "Year to Date"
        case .year1: return "1 Year"
        case .year3: return "3 Years"
        case .year5: return "5 Years"
        case .sinceInception: return "Since Inception"
        }
    }

    /// Start of the window.
    ///
    /// `inception` is the date of the earliest transaction on file and is used
    /// both for `sinceInception` and as a floor for every other window — there
    /// is no point charting five years for an account opened last March, and
    /// doing so makes the equity curve start with a long flat zero.
    func start(now: Date, inception: Date?) -> Date {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = .current

        let raw: Date
        switch self {
        case .month1: raw = calendar.date(byAdding: .month, value: -1, to: now) ?? now
        case .month3: raw = calendar.date(byAdding: .month, value: -3, to: now) ?? now
        case .month6: raw = calendar.date(byAdding: .month, value: -6, to: now) ?? now
        case .ytd: raw = calendar.date(from: calendar.dateComponents([.year], from: now)) ?? now
        case .year1: raw = calendar.date(byAdding: .year, value: -1, to: now) ?? now
        case .year3: raw = calendar.date(byAdding: .year, value: -3, to: now) ?? now
        case .year5: raw = calendar.date(byAdding: .year, value: -5, to: now) ?? now
        case .sinceInception:
            raw = inception ?? calendar.date(byAdding: .year, value: -1, to: now) ?? now
        }

        if let inception, raw < inception { return inception }
        return raw
    }

    /// A price range wide enough to cover this window with margin.
    var priceRange: String {
        switch self {
        case .month1: return "3mo"
        case .month3: return "6mo"
        case .month6: return "1y"
        case .ytd: return "1y"
        case .year1: return "2y"
        case .year3: return "5y"
        case .year5: return "10y"
        case .sinceInception: return "max"
        }
    }
}

/// A benchmark the portfolio's time-weighted return is measured against.
struct Benchmark: Hashable, Identifiable {
    let ticker: String
    let displayName: String

    var id: String { ticker }

    static let spy = Benchmark(ticker: "SPY", displayName: "S&P 500 (SPY)")
    static let qqq = Benchmark(ticker: "QQQ", displayName: "Nasdaq 100 (QQQ)")
    static let vti = Benchmark(ticker: "VTI", displayName: "Total US Market (VTI)")
    static let dia = Benchmark(ticker: "DIA", displayName: "Dow Jones (DIA)")

    static let presets: [Benchmark] = [spy, qqq, vti, dia]
}

/// One day on the portfolio's value / return curve.
struct EquityPoint: Hashable {
    let date: Date
    /// Market value of all positions plus uninvested dividend cash.
    let value: Double
    /// Growth of 1.0 invested at the start, linking daily time-weighted returns.
    let returnIndex: Double
    /// External money in (+) or out (−) recorded on this day.
    let netFlow: Double
}

/// Headline return figures for the period.
struct ReturnMetrics {
    /// Time-weighted return — strips out the timing of deposits. Comparable to
    /// a benchmark.
    var timeWeightedReturn: Double = 0
    /// Money-weighted return (XIRR), annualized — what the investor personally
    /// earned.
    var moneyWeightedReturn: Double?
    /// Simple change in value net of flows, over average capital.
    var simpleReturn: Double = 0
    /// TWR restated as a per-year rate. nil for periods under a year.
    var annualizedReturn: Double?
    var startValue: Double = 0
    var endValue: Double = 0
    var netContributions: Double = 0
    var totalGain: Double = 0
    var dividendIncome: Double = 0
    var realizedGain: Double = 0
    var unrealizedGain: Double = 0
    var periodDays: Int = 1
}

/// Volatility and downside figures, all derived from the daily return series.
struct RiskMetrics {
    var annualizedVolatility: Double?
    var sharpeRatio: Double?
    var maxDrawdown: Double?
    var maxDrawdownPeak: Date?
    var maxDrawdownTrough: Date?
    /// True when the curve climbed back to its pre-drawdown peak before the
    /// period ended.
    var drawdownRecovered: Bool = false
    var bestMonth: (date: Date, value: Double)?
    var worstMonth: (date: Date, value: Double)?
    var positiveMonths: Int = 0
    var totalMonths: Int = 0
    var riskFreeRateUsed: Double = 0
}

/// How the portfolio did against its benchmark over the same window.
struct BenchmarkComparison {
    let benchmark: Benchmark
    let benchmarkReturn: Double
    let portfolioReturn: Double
    /// Portfolio TWR minus benchmark return.
    let excessReturn: Double
    var beta: Double?
    /// Jensen's alpha, annualized.
    var alpha: Double?
    var correlation: Double?
    /// Benchmark growth-of-1.0 curve, aligned to the portfolio's dates.
    var benchmarkIndex: [EquityPoint] = []
}

/// One row of the per-holding performance table.
struct HoldingPerformance: Identifiable, Hashable {
    let holdingId: UUID
    let name: String
    let ticker: String?
    let type: HoldingType
    let accountName: String
    let units: Double
    var averageCost: Double?
    var currentPrice: Double?
    var marketValue: Double
    var costBasis: Double
    var unrealizedGain: Double
    var unrealizedGainPercent: Double?
    var realizedGain: Double
    var dividendIncome: Double
    /// Unrealized + realized + income. What this position actually made you.
    var totalGain: Double
    /// Share of the portfolio's market value.
    var weight: Double = 0
    /// Share of the portfolio's total gain — signed, so losers read negative.
    var contributionShare: Double?
    let currency: String

    var id: UUID { holdingId }
}

/// A closed (sold) lot, for the realized-gains section.
struct RealizedGainRow: Identifiable, Hashable {
    var id = UUID()
    let holdingName: String
    let ticker: String?
    let soldAt: Date
    let shares: Double
    let salePrice: Double
    let averageCostAtSale: Double
    let proceeds: Double
    let costOfUnitsSold: Double
    let realizedGain: Double
    var realizedGainPercent: Double?
    let currency: String
}

/// A labelled amount — one month's income, one holding's income, one slice.
struct LabelledAmount: Identifiable, Hashable {
    let label: String
    let amount: Double
    var id: String { label }
}

/// Dividend income aggregated for the income section.
struct IncomeSummary {
    var totalInPeriod: Double = 0
    var trailing12Months: Double = 0
    var byMonth: [LabelledAmount] = []
    var byHolding: [LabelledAmount] = []
    /// Trailing 12 months of income over current cost basis.
    var yieldOnCost: Double?
    /// Trailing 12 months of income over current market value.
    var currentYield: Double?
    var reinvestedAmount: Double = 0
    var cashAmount: Double = 0
    var paymentCount: Int = 0
}

/// One slice of an allocation breakdown.
struct AllocationSlice: Identifiable, Hashable {
    let label: String
    let value: Double
    let percent: Double
    var id: String { label }
}

struct AllocationSummary {
    var byType: [AllocationSlice] = []
    var byAccount: [AllocationSlice] = []
    var byHolding: [AllocationSlice] = []
    /// Largest single position's weight — flagged when concentrated.
    var largestPositionWeight: Double = 0
    var largestPositionName: String?
    /// Herfindahl index over holding weights, 0…1. Higher means more concentrated.
    var concentrationIndex: Double = 0
}

/// Buys/sells/DRIPs that happened inside the window.
struct ActivitySummary {
    var buyCount: Int = 0
    var sellCount: Int = 0
    var dripCount: Int = 0
    var totalInvested: Double = 0
    var totalWithdrawn: Double = 0
    var totalReinvested: Double = 0
}

/// Everything a generated report contains.
///
/// Built once by `PerformanceCalculator` and handed to both the screen and the
/// PDF writer, so the two always show the same numbers.
struct PerformanceReport {
    var generatedAt: Date = Date()
    var period: ReportPeriod = .year1
    var periodStart: Date = Date()
    var periodEnd: Date = Date()
    var currency: String = "CAD"
    var returns = ReturnMetrics()
    var risk = RiskMetrics()
    var benchmark: BenchmarkComparison?
    var equityCurve: [EquityPoint] = []
    var holdings: [HoldingPerformance] = []
    var realizedGains: [RealizedGainRow] = []
    var income = IncomeSummary()
    var allocation = AllocationSummary()
    var activity = ActivitySummary()
    /// Positions with no ticker (seg funds, mutual funds, cash) that were held
    /// flat at their entered price across the window. Surfaced so a reader
    /// knows which part of the curve is estimated rather than priced.
    var untickeredHoldingNames: [String] = []
    /// Non-fatal problems worth showing, e.g. a benchmark that failed to load.
    var warnings: [String] = []
}

/// What the user chose on the Reports screen before generating.
struct ReportOptions: Equatable {
    var period: ReportPeriod = .year1
    var benchmark: Benchmark? = .spy
    var includeRiskMetrics = true
    var includeHoldingsTable = true
    var includeRealizedGains = true
    var includeIncome = true
    var includeAllocation = true
    var includeTransactionActivity = true
    /// Restrict the report to a single holding. nil means the whole portfolio.
    ///
    /// Scoped by holding rather than by account: "how has this position done"
    /// is the question people actually ask of a report, and an account-level
    /// split is already visible in the allocation section.
    var holdingId: UUID?
}
