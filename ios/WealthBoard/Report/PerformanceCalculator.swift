import Foundation

/// Builds a `PerformanceReport` from the recorded ledger and fetched prices.
/// Ported from `report/PerformanceCalculator.kt`.
///
/// The method is a daily replay: walk the transaction ledger forward one day at
/// a time, value the positions held that morning at that day's close, and link
/// the daily returns. That is what makes the time-weighted return comparable to
/// a benchmark — it strips out the timing of deposits, which a simple
/// start-to-end percentage cannot.
enum PerformanceCalculator {

    private static let secondsPerDay: Double = 24 * 60 * 60
    private static let tradingDaysPerYear: Double = 252
    /// A flat assumption, stated in the report. Carrying a live risk-free curve
    /// would mean another data source for a figure that moves the Sharpe ratio
    /// by a rounding error at these horizons.
    static let defaultRiskFreeRate: Double = 0.04

    private static var calendar: Calendar = {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = .current
        return c
    }()

    /// Days since the epoch, so a date can key a dictionary without its time of
    /// day making every entry unique.
    private static func dayIndex(_ date: Date) -> Int {
        Int(floor(date.timeIntervalSince1970 / secondsPerDay))
    }

    private static func dayStart(_ index: Int) -> Date {
        Date(timeIntervalSince1970: Double(index) * secondsPerDay)
    }

    private static func monthKey(_ date: Date) -> Date {
        calendar.date(from: calendar.dateComponents([.year, .month], from: date)) ?? date
    }

    // MARK: - Build

    /// - Parameters:
    ///   - fxRate: multiplier converting an amount in the given currency into
    ///     the report currency. Supplied by the caller so this stays
    ///     independent of the network and FX layers.
    ///   - priceHistory: closing prices for a ticker over a range.
    static func build(
        options: ReportOptions,
        accounts: [Account],
        allHoldings: [Holding],
        allTransactions: [PortfolioTransaction],
        allDividends: [DividendPayment],
        currency: String,
        now: Date = Date(),
        fxRate: (String) -> Double = { _ in 1.0 },
        priceHistory: (String, String) async -> [(Date, Double)]
    ) async -> PerformanceReport {

        var warnings: [String] = []

        // MARK: Scope
        //
        // Scoped to the SECURITY, not the stored row. Picking XEQT.TO means the
        // whole position in it — the report is about how a holding has done,
        // and splitting the same fund across a TFSA and an RRSP does not make
        // it two investments.
        let scopedHoldings = options.holdingId.map { id in
            guard let picked = allHoldings.first(where: { $0.id == id }) else { return allHoldings }
            return allHoldings.filter { $0.securityKey == picked.securityKey }
        } ?? allHoldings
        let holdingIds = Set(scopedHoldings.map(\.id))

        // MARK: Normalise into the report currency
        //
        // Every total below — portfolio value, contributions, the equity curve,
        // allocation weights, Sharpe, the benchmark comparison — is a sum
        // across holdings. Summed at face value, a US position's dollars added
        // straight to a Canadian one's is not a number in any currency, and
        // every percentage derived from it is wrong alongside it.
        //
        // Converting the inputs once, here, means the arithmetic downstream is
        // untouched and automatically consistent: a rate is a constant
        // multiplier on prices and cash, so returns and risk come out the same
        // as they would per-currency.
        //
        // The caveat, stated in the report: today's rate is applied to
        // historical values too, so a period's return is measured as if FX had
        // been flat. Measuring FX drift properly needs a historical rate series
        // this app does not carry, and a flat rate is far closer to right than
        // adding unconverted currencies together.
        func rate(for code: String?) -> Double {
            guard let c = code?.trimmingCharacters(in: .whitespaces).uppercased(), !c.isEmpty else {
                return 1.0
            }
            if c.caseInsensitiveCompare(currency) == .orderedSame { return 1.0 }
            let r = fxRate(c)
            return r.isFinite && r > 0 ? r : 1.0
        }

        let foreignCurrencies = Set(scopedHoldings.map(\.normalizedCurrency))
            .filter { $0.caseInsensitiveCompare(currency) != .orderedSame }
            .sorted()
        if !foreignCurrencies.isEmpty {
            warnings.append(
                "Holdings in \(foreignCurrencies.joined(separator: ", ")) were converted to "
                + "\(currency) at today's exchange rate, including for past dates — this report "
                + "measures investment return, not currency movement."
            )
        }

        let holdings: [Holding] = scopedHoldings.map { holding in
            let r = rate(for: holding.currency)
            guard r != 1.0 else { return holding }
            var converted = holding
            converted.manualPrice = holding.manualPrice.map { $0 * r }
            converted.lastKnownPrice = holding.lastKnownPrice.map { $0 * r }
            converted.costBasis = holding.costBasis.map { $0 * r }
            converted.currency = currency
            return converted
        }

        let transactions = allTransactions
            .filter { holdingIds.contains($0.holdingId) }
            .map { tx -> PortfolioTransaction in
                let r = rate(for: tx.currency)
                guard r != 1.0 else { return tx }
                var converted = tx
                converted.pricePerShare = tx.pricePerShare * r
                converted.currency = currency
                return converted
            }
            .sorted { $0.at < $1.at }

        let dividends = allDividends
            .filter { holdingIds.contains($0.holdingId) }
            .map { payment -> DividendPayment in
                let r = rate(for: payment.currency)
                guard r != 1.0 else { return payment }
                var converted = payment
                converted.amount = payment.amount * r
                converted.perUnit = payment.perUnit.map { $0 * r }
                converted.currency = currency
                return converted
            }

        // MARK: Window
        let inception = transactions.first?.at
        let periodStart = options.period.start(now: now, inception: inception)
        let startDay = dayIndex(periodStart)
        let endDay = dayIndex(now)

        guard !holdings.isEmpty, endDay >= startDay else {
            return empty(options: options, start: periodStart, end: now, currency: currency, warnings: warnings)
        }

        // MARK: Prices
        var priceSeries: [String: [Int: Double]] = [:]
        for ticker in Set(holdings.compactMap { $0.ticker?.uppercased() }) {
            let bars = await priceHistory(ticker, options.period.priceRange)
            if bars.isEmpty {
                warnings.append("No price history for \(ticker) — its value is held flat at the last known price.")
                continue
            }
            // Prices are already in the listing's own currency, so they get the
            // same conversion the holding did.
            let holdingCurrency = scopedHoldings.first { $0.ticker?.uppercased() == ticker }?.normalizedCurrency
            let r = rate(for: holdingCurrency)
            var series: [Int: Double] = [:]
            for (date, close) in bars { series[dayIndex(date)] = close * r }
            priceSeries[ticker] = series
        }

        /// Forward-filled close for a ticker on a given day, falling back to the
        /// holding's stored price when the series has not started yet.
        func price(ticker: String?, day: Int, fallback: Double?) -> Double? {
            guard let ticker, !ticker.isEmpty,
                  let series = priceSeries[ticker.uppercased()], !series.isEmpty else {
                return fallback
            }
            if let exact = series[day] { return exact }
            // Markets close on weekends and holidays, so a gap here is the
            // norm; a run of more than a few days means the series has not
            // started yet.
            var cursor = day - 1
            var steps = 0
            while steps < 10 {
                if let close = series[cursor] { return close }
                cursor -= 1
                steps += 1
            }
            if let priorDay = series.keys.filter({ $0 <= day }).max() { return series[priorDay] }
            return fallback
        }

        // MARK: Replay the ledger into per-day state

        struct PositionState { var units: Double = 0; var cost: Double = 0 }

        var stateByHolding: [UUID: PositionState] = [:]
        for holding in holdings { stateByHolding[holding.id] = PositionState() }

        var realizedRows: [RealizedGainRow] = []
        var realizedByHolding: [UUID: Double] = [:]
        /// Day → external net flow (buys positive, sells negative).
        var flowByDay: [Int: Double] = [:]
        /// Day → cash added (dividends) / removed (DRIP cost).
        var cashDeltaByDay: [Int: Double] = [:]

        let holdingById = Dictionary(uniqueKeysWithValues: holdings.map { ($0.id, $0) })
        let accountNameById = Dictionary(uniqueKeysWithValues: accounts.map { ($0.id, $0.displayName) })

        // Dividends first so a DRIP on the same day has cash to consume.
        for payment in dividends {
            cashDeltaByDay[dayIndex(payment.paidAt), default: 0] += payment.amount
        }

        var activity = ActivitySummary()

        for tx in transactions {
            guard var state = stateByHolding[tx.holdingId] else { continue }
            let day = dayIndex(tx.at)
            let cash = tx.shares * tx.pricePerShare
            let inPeriod = day >= startDay && day <= endDay

            switch tx.type {
            case .buy:
                state.units += tx.shares
                state.cost += cash
                flowByDay[day, default: 0] += cash
                if inPeriod {
                    activity.buyCount += 1
                    activity.totalInvested += cash
                }
            case .drip:
                // Reinvested income: units up, cost up (the distribution was
                // taxable money put back into the position), cash bucket down.
                state.units += tx.shares
                state.cost += cash
                cashDeltaByDay[day, default: 0] -= cash
                if inPeriod {
                    activity.dripCount += 1
                    activity.totalReinvested += cash
                }
            case .sell:
                let average = state.units > 0 ? state.cost / state.units : 0
                let soldUnits = min(tx.shares, state.units)
                let costOut = average * soldUnits
                let gain = soldUnits * (tx.pricePerShare - average)
                state.units -= soldUnits
                state.cost -= costOut
                if state.units <= 1e-9 { state.units = 0; state.cost = 0 }
                flowByDay[day, default: 0] -= cash
                realizedByHolding[tx.holdingId, default: 0] += gain

                if inPeriod {
                    activity.sellCount += 1
                    activity.totalWithdrawn += cash
                    let holding = holdingById[tx.holdingId]
                    realizedRows.append(RealizedGainRow(
                        holdingName: holding?.name ?? "Unknown",
                        ticker: holding?.ticker,
                        soldAt: tx.at,
                        shares: soldUnits,
                        salePrice: tx.pricePerShare,
                        averageCostAtSale: average,
                        proceeds: soldUnits * tx.pricePerShare,
                        costOfUnitsSold: costOut,
                        realizedGain: gain,
                        realizedGainPercent: costOut > 0 ? gain / costOut : nil,
                        currency: tx.currency
                    ))
                }
            }
            stateByHolding[tx.holdingId] = state
        }

        // MARK: Daily equity curve
        //
        // Replay again day by day so each day's units are known, then value them.

        var txByDay: [Int: [PortfolioTransaction]] = [:]
        for tx in transactions { txByDay[dayIndex(tx.at), default: []].append(tx) }

        var running: [UUID: Double] = [:]
        for holding in holdings { running[holding.id] = 0 }

        // Wind the ledger forward to the day before the period starts, so the
        // curve opens with the position the user actually held that morning.
        let firstLedgerDay = txByDay.keys.min() ?? startDay
        if firstLedgerDay < startDay {
            for day in firstLedgerDay..<startDay {
                for tx in txByDay[day] ?? [] {
                    let delta = tx.type == .sell ? -tx.shares : tx.shares
                    running[tx.holdingId] = max((running[tx.holdingId] ?? 0) + delta, 0)
                }
            }
        }
        // Dividend cash accrued before the window is not part of this period's
        // return, so the curve starts the cash bucket flat.
        var runningCash: Double = 0

        var curve: [EquityPoint] = []
        var previousValue: Double?
        var index: Double = 1

        for day in startDay...endDay {
            for tx in txByDay[day] ?? [] {
                let delta = tx.type == .sell ? -tx.shares : tx.shares
                running[tx.holdingId] = max((running[tx.holdingId] ?? 0) + delta, 0)
            }
            runningCash += cashDeltaByDay[day] ?? 0

            var positions: Double = 0
            for holding in holdings {
                let units = running[holding.id] ?? 0
                guard units > 0 else { continue }
                let fallback = holding.lastKnownPrice ?? holding.manualPrice
                guard let close = price(ticker: holding.ticker, day: day, fallback: fallback) else { continue }
                positions += units * close
            }

            let value = positions + runningCash
            let flow = flowByDay[day] ?? 0

            // Daily time-weighted return with flows treated as arriving at the
            // END of the day: a buy is recorded at that day's own price, so the
            // new money did not participate in the move from yesterday's close
            // and must be excluded from the day's earning base. Crediting it
            // (start-of-day) understates the return on days money came in.
            if let previousValue {
                let r = previousValue > 1e-9 ? (value - flow - previousValue) / previousValue : 0
                index *= (1 + r)
            }
            curve.append(EquityPoint(date: dayStart(day), value: value, returnIndex: index, netFlow: flow))
            previousValue = value
        }

        // MARK: Returns

        // Opening balance is the value the portfolio carried INTO the window,
        // so the first day's own buys are excluded — they are contributions,
        // not starting capital. Without this a position opened on day one is
        // counted twice (once in the opening value, once in contributions) and
        // the period's gain comes out short by that amount.
        let startValue = max((curve.first.map { $0.value - $0.netFlow }) ?? 0, 0)
        let endValue = curve.last?.value ?? 0
        let periodDays = max(Int(now.timeIntervalSince(periodStart) / secondsPerDay), 1)
        let years = Double(periodDays) / 365.25

        let twr = (curve.last?.returnIndex ?? 1) - 1
        let netContributions = curve.reduce(0) { $0 + $1.netFlow }
        let dividendIncomeInPeriod = dividends
            .filter { dayIndex($0.paidAt) >= startDay && dayIndex($0.paidAt) <= endDay }
            .reduce(0) { $0 + $1.amount }
        let realizedInPeriod = realizedRows.reduce(0) { $0 + $1.realizedGain }
        let totalGain = endValue - startValue - netContributions

        let mwr = xirr(cashFlows(
            startValue: startValue,
            startDate: periodStart,
            curve: curve,
            endValue: endValue,
            endDate: now
        ))

        let annualized = (years >= 1 && twr > -1) ? pow(1 + twr, 1 / years) - 1 : nil

        let positiveValues = curve.map(\.value).filter { $0 > 0 }
        let averageCapital = positiveValues.isEmpty
            ? 0
            : positiveValues.reduce(0, +) / Double(positiveValues.count)
        let simpleReturn = averageCapital > 0 ? totalGain / averageCapital : 0

        let unrealizedNow = holdings.reduce(0.0) { total, holding in
            guard let state = stateByHolding[holding.id] else { return total }
            let close = holding.lastKnownPrice ?? holding.manualPrice ?? 0
            return total + (state.units * close - state.cost)
        }

        let returns = ReturnMetrics(
            timeWeightedReturn: twr,
            moneyWeightedReturn: mwr,
            simpleReturn: simpleReturn,
            annualizedReturn: annualized,
            startValue: startValue,
            endValue: endValue,
            netContributions: netContributions,
            totalGain: totalGain,
            dividendIncome: dividendIncomeInPeriod,
            realizedGain: realizedInPeriod,
            unrealizedGain: unrealizedNow,
            periodDays: periodDays
        )

        // MARK: Risk

        var dailyReturns: [Double] = []
        for i in 1..<max(curve.count, 1) {
            let a = curve[i - 1].returnIndex
            let b = curve[i].returnIndex
            dailyReturns.append(a > 1e-12 ? b / a - 1 : 0)
        }
        let risk = computeRisk(
            curve: curve,
            dailyReturns: dailyReturns,
            annualizedReturn: annualized ?? twr
        )

        // MARK: Benchmark

        var benchmark: BenchmarkComparison?
        if let chosen = options.benchmark {
            let bars = await priceHistory(chosen.ticker, options.period.priceRange)
            if bars.isEmpty {
                warnings.append("Benchmark \(chosen.ticker) could not be loaded — comparison omitted.")
            } else {
                benchmark = buildBenchmark(
                    chosen,
                    bars: bars,
                    curve: curve,
                    portfolioDaily: dailyReturns,
                    portfolioTwr: twr,
                    portfolioAnnualized: annualized,
                    years: years,
                    riskFree: risk.riskFreeRateUsed
                )
            }
        }

        // MARK: Per-holding performance

        var dividendByHolding: [UUID: Double] = [:]
        for payment in dividends {
            let day = dayIndex(payment.paidAt)
            guard day >= startDay, day <= endDay else { continue }
            dividendByHolding[payment.holdingId, default: 0] += payment.amount
        }

        var rows: [HoldingPerformance] = []
        for holding in holdings {
            guard let state = stateByHolding[holding.id] else { continue }
            let realized = realizedByHolding[holding.id] ?? 0
            let income = dividendByHolding[holding.id] ?? 0
            // A position that was never held, never sold and never paid has
            // nothing to say — listing it as a row of zeros is noise.
            if state.units <= 0, realized == 0, income == 0 { continue }

            let close = holding.lastKnownPrice ?? holding.manualPrice
            let marketValue = state.units * (close ?? 0)
            let unrealized = marketValue - state.cost

            rows.append(HoldingPerformance(
                holdingId: holding.id,
                name: holding.name,
                ticker: holding.ticker,
                type: holding.type,
                accountName: accountNameById[holding.accountId] ?? "—",
                units: state.units,
                averageCost: state.units > 0 ? state.cost / state.units : nil,
                currentPrice: close,
                marketValue: marketValue,
                costBasis: state.cost,
                unrealizedGain: unrealized,
                unrealizedGainPercent: state.cost > 0 ? unrealized / state.cost : nil,
                realizedGain: realized,
                dividendIncome: income,
                totalGain: unrealized + realized + income,
                currency: holding.currency
            ))
        }

        let totalMarketValue = rows.reduce(0) { $0 + $1.marketValue }
        let gainMagnitude = rows.reduce(0) { $0 + abs($1.totalGain) }
        let holdingRows = rows
            .map { row -> HoldingPerformance in
                var updated = row
                updated.weight = totalMarketValue > 0 ? row.marketValue / totalMarketValue : 0
                updated.contributionShare = gainMagnitude > 0 ? row.totalGain / gainMagnitude : nil
                return updated
            }
            .sorted { $0.totalGain > $1.totalGain }

        // MARK: Income and allocation

        let income = buildIncome(
            dividends: dividends,
            transactions: transactions,
            holdingById: holdingById,
            startDay: startDay,
            endDay: endDay,
            now: now,
            currentCost: holdingRows.reduce(0) { $0 + $1.costBasis },
            currentValue: totalMarketValue
        )

        let allocation = buildAllocation(rows: holdingRows, totalMarketValue: totalMarketValue)

        let untickered = holdings
            .filter { ($0.ticker ?? "").isEmpty && (stateByHolding[$0.id]?.units ?? 0) > 0 }
            .map(\.name)

        return PerformanceReport(
            generatedAt: now,
            period: options.period,
            periodStart: periodStart,
            periodEnd: now,
            currency: currency,
            returns: returns,
            risk: risk,
            benchmark: benchmark,
            equityCurve: curve,
            holdings: holdingRows,
            realizedGains: realizedRows.sorted { $0.soldAt > $1.soldAt },
            income: income,
            allocation: allocation,
            activity: activity,
            untickeredHoldingNames: untickered,
            warnings: warnings
        )
    }

    // MARK: - Cash flows for XIRR

    private static func cashFlows(
        startValue: Double,
        startDate: Date,
        curve: [EquityPoint],
        endValue: Double,
        endDate: Date
    ) -> [(Date, Double)] {
        var flows: [(Date, Double)] = []
        // Opening balance is money already committed at the start of the window.
        if startValue > 0 { flows.append((startDate, -startValue)) }
        for point in curve where abs(point.netFlow) > 1e-9 {
            flows.append((point.date, -point.netFlow))
        }
        if endValue > 0 { flows.append((endDate, endValue)) }
        return flows
    }

    /// Annualized money-weighted return, solved by bisection.
    ///
    /// Bisection rather than Newton-Raphson: irregular hand-entered flows can
    /// produce a derivative near zero and send Newton off to infinity, and a
    /// report that prints a wrong number is worse than one that prints none.
    static func xirr(_ flows: [(Date, Double)]) -> Double? {
        guard flows.count >= 2 else { return nil }
        guard flows.contains(where: { $0.1 > 0 }), flows.contains(where: { $0.1 < 0 }) else {
            return nil
        }
        // Parenthesised, not a trailing closure: in a `guard` condition a
        // trailing closure is confusable with the statement's own body, which
        // Swift rejects. (`map(\.0)` is not an option — no key paths to tuple
        // elements.)
        guard let t0 = flows.map({ $0.0 }).min() else { return nil }

        func npv(_ rate: Double) -> Double {
            flows.reduce(0.0) { total, flow in
                let years = flow.0.timeIntervalSince(t0) / (365.25 * secondsPerDay)
                return total + flow.1 / pow(1 + rate, years)
            }
        }

        var low = -0.9999
        var high = 10.0
        var fLow = npv(low)
        guard fLow * npv(high) <= 0 else { return nil }

        for _ in 0..<200 {
            let mid = (low + high) / 2
            let fMid = npv(mid)
            if abs(fMid) < 1e-9 { return mid }
            if fLow * fMid < 0 {
                high = mid
            } else {
                low = mid
                fLow = fMid
            }
        }
        return (low + high) / 2
    }

    // MARK: - Risk

    private static func computeRisk(
        curve: [EquityPoint],
        dailyReturns: [Double],
        annualizedReturn: Double
    ) -> RiskMetrics {
        let riskFree = defaultRiskFreeRate

        let volatility = dailyReturns.count >= 5
            ? stdev(dailyReturns) * sqrt(tradingDaysPerYear)
            : nil
        let sharpe: Double? = {
            guard let volatility, volatility > 1e-9 else { return nil }
            return (annualizedReturn - riskFree) / volatility
        }()

        // Drawdown is measured on the return index, not raw value: a deposit
        // lifts the value curve and would otherwise hide a real decline.
        var peak = -Double.infinity
        var peakDate: Date?
        var worst: Double = 0
        var worstPeak: Date?
        var worstTrough: Date?

        for point in curve {
            if point.returnIndex > peak {
                peak = point.returnIndex
                peakDate = point.date
            }
            if peak > 0 {
                let drawdown = point.returnIndex / peak - 1
                if drawdown < worst {
                    worst = drawdown
                    worstPeak = peakDate
                    worstTrough = point.date
                }
            }
        }

        var recovered = false
        if let worstTrough, let worstPeak,
           let peakValue = curve.first(where: { $0.date == worstPeak })?.returnIndex {
            recovered = curve.contains { $0.date > worstTrough && $0.returnIndex >= peakValue }
        }

        // Monthly buckets: compound each month's daily returns, then convert
        // the accumulated (1+r) product back into a plain return.
        var monthProducts: [Date: Double] = [:]
        for i in 1..<max(curve.count, 1) {
            let a = curve[i - 1].returnIndex
            let b = curve[i]
            let key = monthKey(b.date)
            let r = a > 1e-12 ? b.returnIndex / a - 1 : 0
            monthProducts[key] = (monthProducts[key] ?? 1) * (1 + r)
        }
        let monthlyReturns = monthProducts
            .map { (date: $0.key, value: $0.value - 1) }
            .sorted { $0.date < $1.date }

        return RiskMetrics(
            annualizedVolatility: volatility,
            sharpeRatio: sharpe,
            maxDrawdown: worst < 0 ? worst : nil,
            maxDrawdownPeak: worstPeak,
            maxDrawdownTrough: worstTrough,
            drawdownRecovered: recovered,
            bestMonth: monthlyReturns.max { $0.value < $1.value },
            worstMonth: monthlyReturns.min { $0.value < $1.value },
            positiveMonths: monthlyReturns.filter { $0.value > 0 }.count,
            totalMonths: monthlyReturns.count,
            riskFreeRateUsed: riskFree
        )
    }

    private static func stdev(_ xs: [Double]) -> Double {
        guard xs.count >= 2 else { return 0 }
        let mean = xs.reduce(0, +) / Double(xs.count)
        let sumSquares = xs.reduce(0.0) { $0 + ($1 - mean) * ($1 - mean) }
        return sqrt(sumSquares / Double(xs.count - 1))
    }

    // MARK: - Benchmark

    private static func buildBenchmark(
        _ benchmark: Benchmark,
        bars: [(Date, Double)],
        curve: [EquityPoint],
        portfolioDaily: [Double],
        portfolioTwr: Double,
        portfolioAnnualized: Double?,
        years: Double,
        riskFree: Double
    ) -> BenchmarkComparison {
        var byDay: [Int: Double] = [:]
        for (date, close) in bars { byDay[dayIndex(date)] = close }
        let sortedDays = byDay.keys.sorted()

        func close(on day: Int) -> Double? {
            if let exact = byDay[day] { return exact }
            guard let prior = sortedDays.last(where: { $0 <= day }) else { return nil }
            return byDay[prior]
        }

        var aligned: [(Date, Double)] = []
        for point in curve {
            guard let value = close(on: dayIndex(point.date)) else { continue }
            aligned.append((point.date, value))
        }

        guard aligned.count >= 2, let base = aligned.first?.1, base > 0 else {
            return BenchmarkComparison(
                benchmark: benchmark,
                benchmarkReturn: 0,
                portfolioReturn: portfolioTwr,
                excessReturn: portfolioTwr
            )
        }

        let benchmarkIndex = aligned.map { date, close in
            EquityPoint(date: date, value: close, returnIndex: close / base, netFlow: 0)
        }
        let benchmarkReturn = (benchmarkIndex.last?.returnIndex ?? 1) - 1

        var benchmarkDaily: [Double] = []
        for i in 1..<benchmarkIndex.count {
            let a = benchmarkIndex[i - 1].returnIndex
            benchmarkDaily.append(a > 1e-12 ? benchmarkIndex[i].returnIndex / a - 1 : 0)
        }

        var beta: Double?
        var correlation: Double?
        let n = min(portfolioDaily.count, benchmarkDaily.count)
        if n >= 5 {
            let p = Array(portfolioDaily.suffix(n))
            let b = Array(benchmarkDaily.suffix(n))
            let pMean = p.reduce(0, +) / Double(n)
            let bMean = b.reduce(0, +) / Double(n)

            var covariance: Double = 0
            var varianceB: Double = 0
            var varianceP: Double = 0
            for i in 0..<n {
                let dp = p[i] - pMean
                let db = b[i] - bMean
                covariance += dp * db
                varianceB += db * db
                varianceP += dp * dp
            }
            covariance /= Double(n - 1)
            varianceB /= Double(n - 1)
            varianceP /= Double(n - 1)

            if varianceB > 1e-12 { beta = covariance / varianceB }
            if varianceB > 1e-12, varianceP > 1e-12 {
                correlation = covariance / sqrt(varianceB * varianceP)
            }
        }

        let benchmarkAnnualized = (years >= 1 && benchmarkReturn > -1)
            ? pow(1 + benchmarkReturn, 1 / years) - 1
            : benchmarkReturn
        let portfolioAnn = portfolioAnnualized ?? portfolioTwr
        let alpha = beta.map { portfolioAnn - (riskFree + $0 * (benchmarkAnnualized - riskFree)) }

        return BenchmarkComparison(
            benchmark: benchmark,
            benchmarkReturn: benchmarkReturn,
            portfolioReturn: portfolioTwr,
            excessReturn: portfolioTwr - benchmarkReturn,
            beta: beta,
            alpha: alpha,
            correlation: correlation,
            benchmarkIndex: benchmarkIndex
        )
    }

    // MARK: - Income

    private static func buildIncome(
        dividends: [DividendPayment],
        transactions: [PortfolioTransaction],
        holdingById: [UUID: Holding],
        startDay: Int,
        endDay: Int,
        now: Date,
        currentCost: Double,
        currentValue: Double
    ) -> IncomeSummary {
        let inPeriod = dividends.filter {
            let day = dayIndex($0.paidAt)
            return day >= startDay && day <= endDay
        }
        let trailingCutoff = now.addingTimeInterval(-365 * secondsPerDay)
        let trailing = dividends.filter { $0.paidAt >= trailingCutoff }.reduce(0) { $0 + $1.amount }

        let monthFormatter = DateFormatter()
        monthFormatter.locale = Locale.current
        monthFormatter.dateFormat = "MMM yyyy"

        var monthTotals: [Date: Double] = [:]
        for payment in inPeriod { monthTotals[monthKey(payment.paidAt), default: 0] += payment.amount }
        let byMonth = monthTotals
            .sorted { $0.key < $1.key }
            .map { LabelledAmount(label: monthFormatter.string(from: $0.key), amount: $0.value) }

        var holdingTotals: [String: Double] = [:]
        for payment in inPeriod {
            let holding = holdingById[payment.holdingId]
            let label = holding?.ticker ?? holding?.name ?? "Unknown"
            holdingTotals[label, default: 0] += payment.amount
        }
        let byHolding = holdingTotals
            .sorted { $0.value > $1.value }
            .map { LabelledAmount(label: $0.key, amount: $0.value) }

        let reinvestedIds = Set(transactions.compactMap(\.sourceDividendId))
        let reinvested = inPeriod.filter { reinvestedIds.contains($0.id) }.reduce(0) { $0 + $1.amount }
        let total = inPeriod.reduce(0) { $0 + $1.amount }

        return IncomeSummary(
            totalInPeriod: total,
            trailing12Months: trailing,
            byMonth: byMonth,
            byHolding: byHolding,
            yieldOnCost: currentCost > 0 ? trailing / currentCost : nil,
            currentYield: currentValue > 0 ? trailing / currentValue : nil,
            reinvestedAmount: reinvested,
            cashAmount: total - reinvested,
            paymentCount: inPeriod.count
        )
    }

    // MARK: - Allocation

    private static func buildAllocation(
        rows: [HoldingPerformance],
        totalMarketValue: Double
    ) -> AllocationSummary {
        func slices(_ totals: [String: Double]) -> [AllocationSlice] {
            totals
                .filter { $0.value > 0 }
                .sorted { $0.value > $1.value }
                .map {
                    AllocationSlice(
                        label: $0.key,
                        value: $0.value,
                        percent: totalMarketValue > 0 ? $0.value / totalMarketValue : 0
                    )
                }
        }

        var byType: [String: Double] = [:]
        var byAccount: [String: Double] = [:]
        var byHolding: [String: Double] = [:]
        for row in rows {
            byType[row.type.label, default: 0] += row.marketValue
            byAccount[row.accountName, default: 0] += row.marketValue
            byHolding[row.ticker ?? row.name, default: 0] += row.marketValue
        }

        let holdingSlices = slices(byHolding)
        let largest = holdingSlices.first
        let hhi = totalMarketValue > 0
            ? rows.reduce(0.0) { total, row in
                let weight = row.marketValue / totalMarketValue
                return total + weight * weight
            }
            : 0

        return AllocationSummary(
            byType: slices(byType),
            byAccount: slices(byAccount),
            byHolding: holdingSlices,
            largestPositionWeight: largest?.percent ?? 0,
            largestPositionName: largest?.label,
            concentrationIndex: hhi
        )
    }

    // MARK: - Empty state

    private static func empty(
        options: ReportOptions,
        start: Date,
        end: Date,
        currency: String,
        warnings: [String]
    ) -> PerformanceReport {
        var report = PerformanceReport()
        report.generatedAt = end
        report.period = options.period
        report.periodStart = start
        report.periodEnd = end
        report.currency = currency
        report.risk.riskFreeRateUsed = defaultRiskFreeRate
        report.warnings = warnings.isEmpty
            ? ["Nothing to report yet — record a buy from the Portfolio tab and the numbers appear here."]
            : warnings
        return report
    }
}
