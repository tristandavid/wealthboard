import Foundation

/// Forward dividend projection, shared by every chart that shows future income.
/// Ported from `data/DividendForecast.kt`.
///
/// Three screens each rolled their own version of this on Android and all three
/// made the same two mistakes:
///
/// 1. They repeated one per-payment amount at a fixed cadence, so a quarterly
///    ETF drew four identical bars. Real distributions are seasonal — XEQT pays
///    a token Q1 and a large Q4, and dividing the annual rate into four equal
///    parts overstates the small quarters by 3x and understates December by as
///    much. The forward view exists precisely to show which months are heavy.
/// 2. They stepped forward by `365 / frequency` days. That is not a quarter; it
///    drifts about five days a year, so a ten-year projection walks payments
///    into the wrong months and eventually the wrong calendar year.
///
/// This projects from the fund's own record instead: each payment in the last
/// twelve months is assumed to repeat on the same date next year, grown by the
/// distribution growth rate. The seasonal shape, the payment months and the
/// annual total all come out right.
enum DividendForecast {

    private static let day: TimeInterval = 24 * 60 * 60

    /// One projected payment: when it lands and what it pays per unit.
    struct ProjectedPayment: Hashable {
        let date: Date
        let perUnit: Double
        /// True when this is the amount the fund actually declared, not a forecast.
        var isAnnounced: Bool = false
    }

    private static var calendar: Calendar = {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = .current
        return c
    }()

    /// Projects per-unit payments falling in `(from, until]`.
    ///
    /// - Parameters:
    ///   - history: the fund's own record as (date, amountPerUnit), any order
    ///   - upcoming: the next announced/estimated payment, when one is known
    ///   - growthRate: annual distribution growth as a decimal (0.03 = 3 %/yr)
    /// Annual growth assumed when a fund has too little history to measure.
    ///
    /// A floor, not the model. See `measuredGrowth`.
    static let fallbackGrowthRate = 0.03

    /// The fund's own distribution growth, measured from its record.
    ///
    /// ## Why this measures several years, not one
    ///
    /// This used to compare the last cycle of payments against the cycle
    /// before it — one year against one year — and then that single ratio was
    /// compounded across the whole ten-year projection. For a fund with lumpy
    /// distributions that is not a trend, it is noise with a decade-long lever
    /// attached: XEQT's trailing year came in about 2% under the year before,
    /// so the Yearly Income chart sloped DOWNWARD for ten straight bars off one
    /// ordinary quarter. A broad equity ETF does not have a negative
    /// distribution trend; the measurement window was simply too short to tell
    /// a trend from a wobble.
    ///
    /// So it now spans as many WHOLE cycles as the record supports, up to
    /// `maxCycles` on each side, and takes the geometric mean per year. Three
    /// years against three years still moves with a real change in
    /// distributions, and barely notices one heavy or light quarter.
    ///
    /// Clamped tighter than the per-payment estimate for the same reason as
    /// before — that estimate applies its ratio once, this one compounds — and
    /// `dampedYears` fades it further as the horizon extends.
    ///
    /// Returns nil when there is not a full cycle on each side to compare,
    /// which is when the caller's fallback earns its place.
    static let maxCycles = 3

    static func measuredGrowth(history: [(Date, Double)], frequency: Int) -> Double? {
        guard frequency > 0 else { return nil }
        let ordered = history.sorted { $0.0 > $1.0 }

        // Whole cycles only, on BOTH sides. A partial cycle on either side
        // compares four quarters against three and reads the missing payment
        // as a cut.
        let cycles = min(maxCycles, ordered.count / (frequency * 2))
        guard cycles >= 1 else { return nil }

        let span = frequency * cycles
        let recent = ordered.prefix(span).reduce(0.0) { $0 + $1.1 }
        let prior = ordered.dropFirst(span).prefix(span).reduce(0.0) { $0 + $1.1 }
        guard prior > 0, recent > 0 else { return nil }

        // Geometric mean: the ratio spans `cycles` YEARS, so the annual rate is
        // its `cycles`-th root, not the whole thing.
        let annual = pow(recent / prior, 1 / Double(cycles)) - 1
        return min(max(annual, -0.05), 0.10)
    }

    /// How fast the measured rate stops being believed further out.
    ///
    /// Each year ahead counts a little less than the one before it, so a rate
    /// measured from a few years of history does not run unchecked for a
    /// decade. Nothing here can know a fund's distribution policy in 2036.
    static let growthFade = 0.85

    /// Years of growth actually applied `yearsOut` years ahead.
    ///
    /// The plain model charges the full rate every year, so ten years out it
    /// has compounded ten times on a reading taken from the recent past. This
    /// sums a decaying series instead — 1 + f + f² + … — which converges, so
    /// the projection tapers toward a flat line rather than trending forever in
    /// whichever direction the last few years happened to point.
    ///
    /// At `growthFade` 0.85 a ten-year horizon applies about 5.3 years of
    /// growth. Year one is unchanged, which is what keeps the near-term bars
    /// agreeing with the Upcoming card.
    static func dampedYears(_ yearsOut: Double) -> Double {
        guard yearsOut > 0 else { return 0 }
        guard growthFade < 1 else { return yearsOut }
        return (1 - pow(growthFade, yearsOut)) / (1 - growthFade)
    }

    static func project(
        history: [(Date, Double)],
        upcoming: UpcomingDividend?,
        from: Date,
        until: Date,
        growthRate: Double? = nil
    ) -> [ProjectedPayment] {
        guard until > from else { return [] }

        let freq = normalizeFrequency(upcoming?.paymentFrequencyPerYear ?? inferFrequency(history))

        // The fund's own record first, the caller's figure second, and the
        // fallback only when neither exists. Callers pass nil to mean "use
        // whatever this fund actually does", which is what every chart wants.
        let resolved = growthRate
            ?? measuredGrowth(history: history, frequency: freq)
            ?? Self.fallbackGrowthRate
        let growth = min(max(resolved, -0.5), 0.5)

        // The template year: the most recent full cycle of payments. Anything
        // older is history, not a pattern — a fund that changed its cadence
        // should be projected on the cadence it uses now.
        let template = recentCycle(history, frequency: freq, now: from)

        let out: [ProjectedPayment] = template.isEmpty
            ? flatSchedule(upcoming: upcoming, frequency: freq, from: from, until: until, growth: growth)
            : seasonalSchedule(template: template, from: from, until: until, growth: growth)

        return applyAnnouncedPayment(out, upcoming: upcoming, from: from, until: until)
            .sorted { $0.date < $1.date }
    }

    /// Total per-unit distribution projected over the next year.
    static func forwardAnnualPerUnit(
        history: [(Date, Double)],
        upcoming: UpcomingDividend?,
        now: Date = Date(),
        growthRate: Double? = nil
    ) -> Double? {
        let year = project(
            history: history,
            upcoming: upcoming,
            from: now,
            until: now.addingTimeInterval(365 * day),
            growthRate: growthRate
        )
        let total = year.reduce(0) { $0 + $1.perUnit }
        return total > 0 ? total : nil
    }

    // MARK: - Internals

    private static func normalizeFrequency(_ raw: Int?) -> Int {
        guard let raw else { return 4 }
        if raw >= 12 { return 12 }
        if raw >= 6 { return 6 }
        if raw >= 4 { return 4 }
        if raw >= 2 { return 2 }
        return 1
    }

    /// Payment cadence implied by the median gap between the recent payments.
    ///
    /// Median rather than mean: one skipped or special distribution should not
    /// reclassify a monthly payer as quarterly.
    static func inferFrequency(_ history: [(Date, Double)]) -> Int? {
        guard history.count >= 2 else { return nil }
        let sorted = history.sorted { $0.0 > $1.0 }.prefix(9)
        var gaps: [Double] = []
        for i in 0..<(sorted.count - 1) {
            let gap = abs(sorted[sorted.startIndex + i].0.timeIntervalSince(sorted[sorted.startIndex + i + 1].0)) / day
            if gap > 3 { gaps.append(gap) }
        }
        guard !gaps.isEmpty else { return nil }
        gaps.sort()
        let median = gaps[gaps.count / 2]
        if median <= 45 { return 12 }
        if median <= 80 { return 6 }
        if median <= 135 { return 4 }
        if median <= 250 { return 2 }
        return 1
    }

    /// The most recent complete cycle of payments — at most `frequency` of them,
    /// and none older than ~14 months, so the template reflects what the fund
    /// pays now rather than what it paid before a cut or a raise.
    private static func recentCycle(
        _ history: [(Date, Double)],
        frequency: Int,
        now: Date
    ) -> [(Date, Double)] {
        guard !history.isEmpty else { return [] }
        let cutoff = now.addingTimeInterval(-425 * day)
        let recent = history
            .filter { $0.0 >= cutoff && $0.0 <= now && $0.1 > 0 }
            .sorted { $0.0 > $1.0 }
            .prefix(frequency)
        // A partial cycle would project a year with holes in it — a fund that
        // has only paid twice cannot tell us what its other two quarters look
        // like, so fall back to the flat estimate instead of inventing zeros.
        guard recent.count >= frequency else { return [] }
        return recent.sorted { $0.0 < $1.0 }
    }

    /// Repeats each payment in the template on its own anniversary, compounding
    /// the growth rate once per year out.
    private static func seasonalSchedule(
        template: [(Date, Double)],
        from: Date,
        until: Date,
        growth: Double
    ) -> [ProjectedPayment] {
        var out: [ProjectedPayment] = []
        for (date, amount) in template {
            // Year 0 is the template payment itself, which is already history;
            // start at the first anniversary that lands inside the window.
            var year = 1
            while year <= 40 {
                guard let anniversary = calendar.date(byAdding: .year, value: year, to: date) else { break }
                if anniversary > until { break }
                if anniversary > from {
                    out.append(ProjectedPayment(
                        date: anniversary,
                        perUnit: amount * pow(1 + growth, dampedYears(Double(year)))
                    ))
                }
                year += 1
            }
        }
        return out
    }

    /// Even cadence from the next known payment — used only when the fund's
    /// record is too short to show a seasonal pattern.
    ///
    /// Steps in whole calendar months rather than 365/freq days, so payments
    /// stay in the months they belong to however far out the projection runs.
    private static func flatSchedule(
        upcoming: UpcomingDividend?,
        frequency: Int,
        from: Date,
        until: Date,
        growth: Double
    ) -> [ProjectedPayment] {
        let perUnit: Double? = upcoming?.perPaymentAmount
            ?? upcoming?.estimatedAnnualRate.map { $0 / Double(frequency) }
        guard let perUnit, perUnit > 0 else { return [] }

        let stepMonths = max(12 / frequency, 1)
        var cursor = upcoming?.payDate ?? upcoming?.exDividendDate ?? from
        // Roll an already-past anchor forward rather than emitting nothing.
        var guardCount = 0
        while cursor <= from, guardCount < 500 {
            guard let next = calendar.date(byAdding: .month, value: stepMonths, to: cursor) else { break }
            cursor = next
            guardCount += 1
        }

        var out: [ProjectedPayment] = []
        guardCount = 0
        while cursor <= until, guardCount < 500 {
            let yearsOut = cursor.timeIntervalSince(from) / (365 * day)
            out.append(ProjectedPayment(
                date: cursor,
                perUnit: perUnit * pow(1 + growth, dampedYears(yearsOut))
            ))
            guard let next = calendar.date(byAdding: .month, value: stepMonths, to: cursor) else { break }
            cursor = next
            guardCount += 1
        }
        return out
    }

    /// Replaces the first projected payment with the declared one when the fund
    /// has actually announced it.
    ///
    /// Matched by date within half a cycle so the announcement supersedes the
    /// forecast for that payment rather than being added alongside it, which
    /// would double-count the nearest quarter.
    private static func applyAnnouncedPayment(
        _ projected: [ProjectedPayment],
        upcoming: UpcomingDividend?,
        from: Date,
        until: Date
    ) -> [ProjectedPayment] {
        guard let upcoming,
              let amount = upcoming.perPaymentAmount, amount > 0,
              upcoming.isAnnounced,
              let at = upcoming.payDate ?? upcoming.exDividendDate,
              at > from, at <= until else { return projected }

        let window: TimeInterval = 60 * day
        var replaced = projected
        if let nearestIndex = replaced.indices.min(by: {
            abs(replaced[$0].date.timeIntervalSince(at)) < abs(replaced[$1].date.timeIntervalSince(at))
        }), abs(replaced[nearestIndex].date.timeIntervalSince(at)) <= window {
            replaced.remove(at: nearestIndex)
        }
        replaced.append(ProjectedPayment(date: at, perUnit: amount, isAnnounced: true))
        return replaced
    }
}
