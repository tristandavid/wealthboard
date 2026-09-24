import Foundation

// Ported from `ui/HoldingDetail.kt`. Everything the holding detail screen
// renders, assembled in one place so the view stays declarative and the
// arithmetic is reviewable on its own.

/// One historical or scheduled distribution shown in the payouts list.
///
/// `date` is when the MONEY ARRIVES, because that is what a payout schedule is
/// for. The two dates are not the same day — the gap between a distribution
/// going ex and the cash landing runs from a couple of days to several weeks
/// depending on the fund — so `exDate` is carried separately rather than being
/// allowed to stand in silently. XEQT's September 2026 distribution goes ex on
/// the 24th and pays on the 29th; a schedule that printed the 24th under a
/// "payout" heading was answering a different question from the one asked.
struct PayoutRow: Identifiable, Hashable {
    /// The pay date where one is published, and the ex-date as a labelled
    /// stand-in where it is not.
    let date: Date
    /// The ex-dividend date, when it is known and distinct from `date`.
    let exDate: Date?
    /// True when `date` IS the ex-date because no pay date was published, so
    /// the UI can say so instead of mislabelling it.
    let isExDateFallback: Bool
    let amountPerUnit: Double
    let totalForPosition: Double
    let isUpcoming: Bool
    /// True when an upcoming row is the amount the fund actually declared,
    /// rather than a forecast.
    var isAnnounced: Bool = false

    var id: String { "\(date.timeIntervalSince1970)-\(isUpcoming)" }
}

/// A point on the trailing-yield line (yield % at a past date).
struct YieldPoint: Identifiable, Hashable {
    let at: Date
    let yieldPercent: Double

    var id: TimeInterval { at.timeIntervalSince1970 }
}

/// All money figures are in the holding's own currency; the screen converts at
/// the point of display, so one rate applies to the whole page rather than
/// being threaded through every calculation here.
struct HoldingDetail {
    /// The row the screen was opened from. Edits, new transactions and logged
    /// dividends act on this one; every FIGURE below covers the whole position.
    var holding: Holding
    /// Every account's slice of this security, which is what the numbers here
    /// are summed from.
    var position: SecurityPosition
    var quote: Quote?
    var upcoming: UpcomingDividend?
    var payouts: [PayoutRow] = []
    var yieldHistory: [YieldPoint] = []

    /// The fund's own distribution record: (ex-date, amount per unit).
    ///
    /// This is the security's payment history, not the user's — it covers
    /// periods before the position existed, which is exactly what makes it
    /// useful for showing when a fund started paying and how the per-unit
    /// distribution has moved since. Money the user actually received lives in
    /// `allTimeReceived` and the logged payments instead.
    var dividendEvents: [(Date, Double)] = []

    // MARK: Position
    var marketValue: Double = 0
    var averageCost: Double?
    var totalContributions: Double?
    var priceReturn: Double?
    var priceReturnPercent: Double?
    var totalReturn: Double?
    var totalReturnPercent: Double?
    var portfolioWeightPercent: Double?

    // MARK: Dividends
    var trailingAnnualPerUnit: Double?
    var yieldTTMPercent: Double?
    var yieldOnCostPercent: Double?
    var divGrowth1YPercent: Double?
    var divGrowth5YPercent: Double?
    var divChangeRecentPercent: Double?
    var divChangeRecentLabel: String?
    var divChangeVs1YPercent: Double?
    var frequencyPerYear: Int?
    var allTimeReceived: Double = 0
    var estimatedAnnualIncome: Double?
    var tenYearAverageYieldPercent: Double?

    // MARK: Income timeline
    /// Payments the user has on record, and the forward projection — both
    /// already scaled by the position size.
    var receivedFlows: [(Date, Double)] = []
    var projectedFlows: [(Date, Double)] = []

    /// Where the current price sits in the 52-week band, as 0…1.
    var fiftyTwoWeekFraction: Double? {
        guard let quote,
              let low = quote.fiftyTwoWeekLow,
              let high = quote.fiftyTwoWeekHigh,
              high > low else { return nil }
        return min(max((quote.price - low) / (high - low), 0), 1)
    }

    var frequencyLabel: String {
        switch frequencyPerYear {
        case 12: return "Monthly"
        case 6: return "Bi-monthly"
        case 4: return "Quarterly"
        case 2: return "Semi-annual"
        case 1: return "Annual"
        case .none: return "—"
        case .some(let n): return "×\(n) / yr"
        }
    }

    /// Qualitative tag for a growth rate, mirroring how dividend trackers label
    /// them. nil for an unknown rate — a missing number gets no adjective.
    func growthTag(_ percent: Double?) -> String? {
        guard let percent else { return nil }
        if percent < 0 { return "Negative" }
        if percent < 3 { return "Slow" }
        if percent < 8 { return "Moderate" }
        if percent < 15 { return "Fast" }
        return "Very Fast"
    }
}

// MARK: - Math

/// Pure computation helpers for `HoldingDetail`, kept out of the view model.
enum HoldingDetailMath {

    private static let day: TimeInterval = 24 * 60 * 60
    private static let year: TimeInterval = 365 * 24 * 60 * 60

    /// Compound annual growth rate between the dividends paid in the 12 months
    /// ending `years` ago and those paid in the last 12 months.
    ///
    /// Returns nil when either window has no payments, which is the honest
    /// answer for a fund with too little history rather than a misleading 0%.
    static func dividendCAGR(_ events: [(Date, Double)], years: Int, now: Date = Date()) -> Double? {
        guard !events.isEmpty, years > 0 else { return nil }

        func windowSum(endYearsAgo: Int) -> Double {
            let end = now.addingTimeInterval(-Double(endYearsAgo) * year)
            let start = end.addingTimeInterval(-year)
            return events
                .filter { $0.0 >= start && $0.0 <= end }
                .reduce(0) { $0 + $1.1 }
        }

        let recent = windowSum(endYearsAgo: 0)
        let past = windowSum(endYearsAgo: years)
        guard recent > 0, past > 0 else { return nil }
        return (pow(recent / past, 1.0 / Double(years)) - 1.0) * 100.0
    }

    /// Percent change between the two most recent distributions, with the date
    /// of the latest so the label can name the period it covers.
    static func mostRecentChange(_ events: [(Date, Double)]) -> (percent: Double, at: Date)? {
        let sorted = events.sorted { $0.0 > $1.0 }
        guard sorted.count >= 2 else { return nil }
        let latest = sorted[0]
        let prior = sorted[1].1
        guard prior > 0 else { return nil }
        return ((latest.1 - prior) / prior * 100.0, latest.0)
    }

    /// Percent change between the latest distribution and the one closest to a
    /// year earlier — the seasonally comparable payment.
    ///
    /// A seasonal payer's small Q1 compared against its large Q4 is not a
    /// dividend cut, which is what a naive most-recent-versus-previous
    /// comparison would call it.
    static func changeVsYearAgo(_ events: [(Date, Double)]) -> Double? {
        let sorted = events.sorted { $0.0 > $1.0 }
        guard let latest = sorted.first, sorted.count >= 2 else { return nil }
        let target = latest.0.addingTimeInterval(-year)
        let candidates = sorted.dropFirst()
        guard let yearAgo = candidates.min(by: {
            abs($0.0.timeIntervalSince(target)) < abs($1.0.timeIntervalSince(target))
        }) else { return nil }
        guard abs(yearAgo.0.timeIntervalSince(target)) <= 45 * day else { return nil }
        guard yearAgo.1 > 0 else { return nil }
        return (latest.1 - yearAgo.1) / yearAgo.1 * 100.0
    }

    /// Sum of distributions in the trailing 12 months.
    static func trailingTwelveMonths(_ events: [(Date, Double)], now: Date = Date()) -> Double {
        let cutoff = now.addingTimeInterval(-year)
        return events.filter { $0.0 >= cutoff }.reduce(0) { $0 + $1.1 }
    }

    /// Trailing-yield series: for each historical close, the trailing-12-month
    /// distribution as of that date over the price on that date.
    static func buildYieldHistory(
        priceBars: [(Date, Double)],
        events: [(Date, Double)]
    ) -> [YieldPoint] {
        guard !priceBars.isEmpty, !events.isEmpty else { return [] }
        return priceBars.compactMap { bar in
            guard bar.1 > 0 else { return nil }
            let start = bar.0.addingTimeInterval(-year)
            let ttm = events
                .filter { $0.0 >= start && $0.0 <= bar.0 }
                .reduce(0) { $0 + $1.1 }
            guard ttm > 0 else { return nil }
            return YieldPoint(at: bar.0, yieldPercent: ttm / bar.1 * 100.0)
        }
    }

    /// Per-unit distributions summed per calendar year, oldest first.
    ///
    /// The current year is dropped while it is still in progress, because a
    /// part-year total plotted beside full years reads as a dividend cut rather
    /// than as a year that hasn't finished paying out yet.
    static func annualDividends(_ events: [(Date, Double)], now: Date = Date()) -> [(year: Int, perUnit: Double)] {
        guard !events.isEmpty else { return [] }
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = .current

        let thisYear = calendar.component(.year, from: now)
        // Treat the current year as complete only once December has passed.
        let currentYearComplete = calendar.component(.month, from: now) >= 12

        var totals: [Int: Double] = [:]
        for (date, amount) in events {
            let y = calendar.component(.year, from: date)
            guard y < thisYear || currentYearComplete else { continue }
            totals[y, default: 0] += amount
        }
        return totals.keys.sorted().map { ($0, totals[$0] ?? 0) }
    }

    /// Payment frequency implied by the median gap between distributions.
    static func inferFrequency(_ events: [(Date, Double)]) -> Int? {
        DividendForecast.inferFrequency(events)
    }

    /// The five-year average of a trailing-yield series, or nil when the series
    /// does not reach back far enough to mean anything.
    static func tenYearAverageYield(_ history: [YieldPoint], now: Date = Date()) -> Double? {
        let cutoff = now.addingTimeInterval(-10 * year)
        let window = history.filter { $0.at >= cutoff }
        guard window.count >= 5 else { return nil }
        return window.reduce(0) { $0 + $1.yieldPercent } / Double(window.count)
    }
}
