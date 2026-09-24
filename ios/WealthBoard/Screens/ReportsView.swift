import SwiftUI
import UIKit

enum ReportUIState {
    case idle
    case running
    case ready(PerformanceReport)
    case failed(String)
}

/// Builds a multi-page PDF performance report from the recorded ledger, with
/// an on-screen summary of the same figures so the document is never the only
/// place to read them.
struct ReportsView: View {
    @EnvironmentObject private var viewModel: PortfolioViewModel
    @Environment(\.colorScheme) private var scheme

    @State private var options = ReportOptions()
    @State private var state: ReportUIState = .idle
    @State private var exportURL: URL?
    @State private var exportError: String?
    @State private var showPremiumGate = false

    /// PDF export is Premium-gated — see `export()` below. Building and
    /// viewing the on-screen summary above stays free.
    @ObservedObject private var subscription = SubscriptionSession.shared

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                periodSection
                holdingsSection
                benchmarkSection
                sectionsSection
                generateButton
                results
                Color.clear.frame(height: 30)
            }
            .padding(.horizontal, WbDimens.screenPadding)
            .padding(.top, 8)
        }
        .wbScreenBackground(scheme)
        .wbNavigationBar("Reports")
        .sheet(item: Binding(
            get: { exportURL.map { ShareItem(url: $0) } },
            set: { if $0 == nil { exportURL = nil } }
        )) { item in
            ShareSheet(items: [item.url])
        }
        .alert("Couldn't build that file", isPresented: Binding(
            get: { exportError != nil },
            set: { if !$0 { exportError = nil } }
        )) {
            Button("OK", role: .cancel) { exportError = nil }
        } message: {
            Text(exportError ?? "")
        }
        .sheet(isPresented: $showPremiumGate) {
            NavigationStack { PremiumView() }
        }
    }

    // MARK: - Options

    private var periodSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            label("Reporting period")
            ChipRow(items: ReportPeriod.allCases, title: \.label, selection: $options.period)
        }
    }

    private var holdingsSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            label("Holdings")
            Menu {
                Button("All holdings") { options.holdingId = nil }
                // One entry per SECURITY. The same fund in three accounts is
                // three stored rows, and listing it three times gave the reader
                // three identical-looking choices that produced three partial
                // reports. Picking one now scopes the report to the whole
                // position, wherever it is held.
                ForEach(viewModel.positions) { position in
                    Button("\(position.ticker ?? position.name) — \(position.name)") {
                        options.holdingId = position.slices[0].holding.id
                    }
                }
            } label: {
                HStack {
                    Text(selectedHoldingLabel)
                        .foregroundStyle(Palette.accent(scheme))
                    Spacer()
                    Image(systemName: "chevron.down")
                        .foregroundStyle(Palette.onSurfaceVariant(scheme))
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 14)
                .overlay(
                    RoundedRectangle(cornerRadius: 24, style: .continuous)
                        .stroke(Palette.outline(scheme), lineWidth: 1)
                )
            }
        }
    }

    private var selectedHoldingLabel: String {
        guard let id = options.holdingId,
              let holding = viewModel.holding(id) else { return "All holdings" }
        return holding.ticker ?? holding.name
    }


    private var benchmarkSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            label("Compare against")
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(Benchmark.presets) { preset in
                        benchmarkChip(title: preset.ticker, selected: options.benchmark?.ticker == preset.ticker) {
                            options.benchmark = options.benchmark?.ticker == preset.ticker ? nil : preset
                        }
                    }
                    benchmarkChip(title: "None", selected: options.benchmark == nil) {
                        options.benchmark = nil
                    }
                }
            }
        }
    }

    private func benchmarkChip(title: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(.system(size: 13, weight: selected ? .semibold : .regular))
                .foregroundStyle(selected ? Palette.onAccent(scheme) : Palette.onSurfaceVariant(scheme))
                .padding(.horizontal, 16)
                .padding(.vertical, 8)
                .background(Capsule().fill(selected ? Palette.accent(scheme) : .clear))
                .overlay(Capsule().stroke(selected ? .clear : Palette.outline(scheme), lineWidth: 1))
        }
        .buttonStyle(.plain)
    }

    private var sectionsSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            label("Include in the PDF")
            WbCard(padding: 0) {
                toggleRow("Risk metrics", "Volatility, Sharpe, drawdown, beta", $options.includeRiskMetrics)
                WbDivider().padding(.horizontal, 16)
                toggleRow("Holdings table", "Per-position cost, value and gain", $options.includeHoldingsTable)
                WbDivider().padding(.horizontal, 16)
                toggleRow("Realized gains", "Closed positions — useful at tax time", $options.includeRealizedGains)
                WbDivider().padding(.horizontal, 16)
                toggleRow("Dividend income", "Income by month and by holding", $options.includeIncome)
                WbDivider().padding(.horizontal, 16)
                toggleRow("Allocation", "Weights by type, account and position", $options.includeAllocation)
                WbDivider().padding(.horizontal, 16)
                toggleRow("Activity", "Buys, sells and reinvestments", $options.includeTransactionActivity)
            }
        }
    }

    private func toggleRow(_ title: String, _ subtitle: String, _ binding: Binding<Bool>) -> some View {
        Toggle(isOn: binding) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.wbBodyLarge)
                    .foregroundStyle(Palette.onSurface(scheme))
                Text(subtitle)
                    .font(.wbBodySmall)
                    .foregroundStyle(Palette.onSurfaceVariant(scheme))
            }
        }
        .tint(Palette.accent(scheme))
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
    }

    private func label(_ text: String) -> some View {
        Text(text.uppercased())
            .font(.system(size: 11, weight: .semibold))
            .foregroundStyle(Palette.onSurfaceVariant(scheme))
    }

    // MARK: - Action

    private var generateButton: some View {
        Button {
            // No gate. Reports used to sit behind a rewarded ad, which stopped
            // being a gate and became a dead end the moment there was no ad
            // network to serve one: the sheet's only exit was a video that
            // could never play. Gone entirely rather than bypassed, so there is
            // nothing left to come back if an ad network is added later without
            // someone thinking about it again.
            Task { await generate() }
        } label: {
            HStack(spacing: 8) {
                if case .running = state {
                    ProgressView().controlSize(.small).tint(Palette.onAccent(scheme))
                    Text("Building report…")
                } else {
                    Image(systemName: "chart.bar.doc.horizontal")
                    Text(isReady ? "Rebuild report" : "Generate report")
                }
            }
            .font(.wbBodyLarge)
            .fontWeight(.semibold)
            .foregroundStyle(Palette.onAccent(scheme))
            .frame(maxWidth: .infinity)
            .frame(height: 48)
            .background(Palette.accent(scheme), in: Capsule())
        }
        .buttonStyle(.plain)
        .disabled(isRunning)
    }

    private var isRunning: Bool {
        if case .running = state { return true }
        return false
    }

    private var isReady: Bool {
        if case .ready = state { return true }
        return false
    }

    private func generate() async {
        state = .running

        // Snapshot everything the calculator needs before handing it off.
        //
        // The calculator runs off the main actor, so it cannot reach into the
        // view model — a rate looked up lazily from inside it would be an
        // isolation violation. Reading the rates once here also means every
        // figure in one report is converted at the same instant, rather than
        // at whatever moment each holding happened to be valued.
        let currency = viewModel.baseCurrency
        var rates: [String: Double] = [:]
        for code in viewModel.heldCurrencies {
            rates[code] = viewModel.rateToBase(from: code) ?? 1.0
        }

        let report = await PerformanceCalculator.build(
            options: options,
            accounts: viewModel.accounts,
            allHoldings: viewModel.holdings,
            allTransactions: viewModel.transactions,
            allDividends: viewModel.dividends,
            currency: currency,
            fxRate: { rates[$0.uppercased()] ?? 1.0 },
            priceHistory: { ticker, range in
                await QuoteClient.shared.fetchHistory(ticker: ticker, range: range, interval: "1d")
            }
        )
        state = .ready(report)
    }

    // MARK: - Results

    @ViewBuilder
    private var results: some View {
        switch state {
        case .idle:
            WbCard {
                Text("What you'll get")
                    .font(.wbBodyLarge)
                    .fontWeight(.semibold)
                    .foregroundStyle(Palette.onSurface(scheme))
                    .padding(.bottom, 6)
                Text("A multi-page PDF covering time-weighted and money-weighted returns, a benchmark comparison, risk metrics, every position's contribution to your gain, realized gains, dividend income and allocation — built from the transactions you've recorded.")
                    .font(.wbBodySmall)
                    .foregroundStyle(Palette.onSurfaceVariant(scheme))
            }

        case .running:
            EmptyView()

        case .failed(let message):
            Text(message)
                .font(.wbBodySmall)
                .foregroundStyle(Brand.loss)
                .padding(14)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Brand.loss.opacity(0.1))
                .clipShape(RoundedRectangle(cornerRadius: WbDimens.cardRadius, style: .continuous))

        case .ready(let report):
            ReportSummary(report: report)

            Button {
                export(report)
            } label: {
                HStack(spacing: 8) {
                    Image(systemName: "square.and.arrow.up")
                    Text("Export as PDF")
                }
                .font(.wbBodyLarge)
                .fontWeight(.semibold)
                .foregroundStyle(Palette.accent(scheme))
                .frame(maxWidth: .infinity)
                .frame(height: 46)
                .overlay(Capsule().stroke(Palette.accent(scheme), lineWidth: 1.5))
            }
            .buttonStyle(.plain)

            Button {
                exportCSV(report)
            } label: {
                HStack(spacing: 8) {
                    Image(systemName: "tablecells")
                    Text("Export as CSV")
                }
                .font(.wbBodyLarge)
                .fontWeight(.semibold)
                .foregroundStyle(Palette.accent(scheme))
                .frame(maxWidth: .infinity)
                .frame(height: 46)
                .overlay(Capsule().stroke(Palette.accent(scheme), lineWidth: 1.5))
            }
            .buttonStyle(.plain)
        }
    }

    private func export(_ report: PerformanceReport) {
        // Premium-gated — the paywall's own copy promises "PDF reports" as a
        // subscriber perk; the free on-screen summary above already covers
        // everyone else.
        guard subscription.isPremium else {
            showPremiumGate = true
            return
        }
        do {
            exportURL = try PDFReportGenerator.write(report, options: options)
        } catch {
            exportError = error.localizedDescription
        }
    }

    /// The same report as a spreadsheet, gated alongside the PDF: both are
    /// "take the report away with you", and gating one while leaving the other
    /// open would make the paywall a formality.
    private func exportCSV(_ report: PerformanceReport) {
        guard subscription.isPremium else {
            showPremiumGate = true
            return
        }
        let label = options.holdingId.flatMap { id in
            viewModel.holdings.first { $0.id == id }.map { $0.ticker ?? $0.name }
        }
        do {
            exportURL = try CSVReportGenerator.write(
                report, options: options, holdingLabel: label
            )
        } catch {
            exportError = error.localizedDescription
        }
    }
}

// MARK: - On-screen summary

private struct ReportSummary: View {
    @Environment(\.colorScheme) private var scheme
    let report: PerformanceReport

    var body: some View {
        VStack(spacing: 12) {
            if !report.warnings.isEmpty {
                VStack(alignment: .leading, spacing: 6) {
                    ForEach(report.warnings, id: \.self) { warning in
                        Text("• " + warning)
                            .font(.system(size: 11))
                            .foregroundStyle(Palette.onSurfaceVariant(scheme))
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(12)
                .background(Brand.divAmber.opacity(0.1))
                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
            }

            WbCard {
                Text(report.period.label)
                    .font(.wbLabelLarge)
                    .foregroundStyle(Palette.accent(scheme))
                    .padding(.bottom, 8)

                HStack(spacing: 12) {
                    metric("Opening value", Money.format(report.returns.startValue, report.currency), nil)
                    metric("Closing value", Money.format(report.returns.endValue, report.currency), nil)
                }
                .padding(.bottom, 12)

                HStack(spacing: 12) {
                    metric("Net contributions",
                           Money.signed(report.returns.netContributions, report.currency),
                           nil)
                    metric("Total gain",
                           Money.signed(report.returns.totalGain, report.currency),
                           Palette.change(report.returns.totalGain))
                }
                .padding(.bottom, 12)

                HStack(spacing: 12) {
                    metric("Time-weighted",
                           Money.signedPercent(report.returns.timeWeightedReturn * 100),
                           Palette.change(report.returns.timeWeightedReturn))
                    metric("Money-weighted (XIRR)",
                           report.returns.moneyWeightedReturn.map { Money.signedPercent($0 * 100) } ?? "—",
                           Palette.change(report.returns.moneyWeightedReturn ?? 0))
                }

                if let annualized = report.returns.annualizedReturn {
                    WbDivider().padding(.vertical, 12)
                    KeyValueRow(
                        label: "Annualized (TWR)",
                        value: Money.signedPercent(annualized * 100),
                        valueColor: Palette.change(annualized)
                    )
                }

                // Where the gain actually came from. A single "total gain"
                // hides whether a year was carried by price or by income, which
                // is the difference between a lucky year and a working one.
                WbDivider().padding(.vertical, 12)
                KeyValueRow(label: "From price change",
                            value: Money.signed(report.returns.unrealizedGain, report.currency),
                            valueColor: Palette.change(report.returns.unrealizedGain))
                KeyValueRow(label: "From dividends",
                            value: Money.signed(report.returns.dividendIncome, report.currency),
                            valueColor: Brand.divGreen)
                KeyValueRow(label: "From sales",
                            value: Money.signed(report.returns.realizedGain, report.currency),
                            valueColor: Palette.change(report.returns.realizedGain))
            }

            if !report.equityCurve.isEmpty {
                WbCard {
                    Text("Portfolio value")
                        .font(.wbTitleMedium)
                        .foregroundStyle(Palette.onSurface(scheme))
                        .padding(.bottom, 8)
                    AreaChart(
                        points: report.equityCurve.map { ChartPoint(date: $0.date, value: $0.value) }
                    )
                    .frame(height: 190)
                }
            }

            if let benchmark = report.benchmark {
                WbCard {
                    Text("vs \(benchmark.benchmark.displayName)")
                        .font(.wbTitleMedium)
                        .foregroundStyle(Palette.onSurface(scheme))
                        .padding(.bottom, 8)
                    KeyValueRow(label: "Benchmark return",
                                value: Money.signedPercent(benchmark.benchmarkReturn * 100))
                    KeyValueRow(label: "Excess return",
                                value: Money.signedPercent(benchmark.excessReturn * 100),
                                valueColor: Palette.change(benchmark.excessReturn))
                    KeyValueRow(label: "Beta",
                                value: benchmark.beta.map { String(format: "%.2f", $0) } ?? "—")
                    KeyValueRow(label: "Alpha (annualized)",
                                value: benchmark.alpha.map { Money.signedPercent($0 * 100) } ?? "—",
                                valueColor: benchmark.alpha.map { Palette.change($0) })
                    KeyValueRow(label: "Correlation",
                                value: benchmark.correlation.map { String(format: "%.2f", $0) } ?? "—")

                    Text(verdict(benchmark))
                        .font(.wbBodySmall)
                        .foregroundStyle(Palette.onSurfaceVariant(scheme))
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.top, 8)
                }
            }

            if report.risk.annualizedVolatility != nil || report.risk.maxDrawdown != nil {
                WbCard {
                    Text("Risk")
                        .font(.wbTitleMedium)
                        .foregroundStyle(Palette.onSurface(scheme))
                        .padding(.bottom, 8)
                    KeyValueRow(label: "Annualized volatility",
                                value: report.risk.annualizedVolatility.map { Money.percent($0 * 100) } ?? "—")
                    KeyValueRow(label: "Sharpe ratio",
                                value: report.risk.sharpeRatio.map { String(format: "%.2f", $0) } ?? "—")
                    KeyValueRow(label: "Maximum drawdown",
                                value: report.risk.maxDrawdown.map { Money.signedPercent($0 * 100) } ?? "—",
                                valueColor: Brand.loss)
                    KeyValueRow(label: "Positive months",
                                value: report.risk.totalMonths > 0
                                    ? "\(report.risk.positiveMonths) of \(report.risk.totalMonths)"
                                    : "—")

                    if let best = report.risk.bestMonth {
                        KeyValueRow(label: "Best month (\(Self.month.string(from: best.date)))",
                                    value: Money.signedPercent(best.value * 100),
                                    valueColor: Brand.gain)
                    }
                    if let worst = report.risk.worstMonth {
                        KeyValueRow(label: "Worst month (\(Self.month.string(from: worst.date)))",
                                    value: Money.signedPercent(worst.value * 100),
                                    valueColor: Brand.loss)
                    }

                    if report.risk.maxDrawdown != nil {
                        Text(report.risk.drawdownRecovered
                            ? "The portfolio has since returned to its previous high."
                            : "The portfolio has not yet regained that high.")
                            .font(.wbBodySmall)
                            .foregroundStyle(Palette.onSurfaceVariant(scheme))
                            .padding(.top, 6)
                    }
                }
            }

            // Grouped so the stack stays inside ViewBuilder's ten-child limit.
            Group {
                incomeCard
                realizedCard
                allocationCard
                activityCard
                dataNotesCard
            }

            if !report.holdings.isEmpty {
                WbCard {
                    Text("Top contributors")
                        .font(.wbTitleMedium)
                        .foregroundStyle(Palette.onSurface(scheme))
                        .padding(.bottom, 8)
                    ForEach(topContributors()) { row in
                        KeyValueRow(
                            label: row.accountCount > 1
                                ? "\(row.label)  ·  \(row.accountCount) accounts"
                                : row.label,
                            value: Money.signed(row.gain, report.currency),
                            valueColor: Palette.change(row.gain)
                        )
                    }
                }
            }
        }
    }

    /// One security's contribution, added up across every account holding it.
    private struct ContributorRow: Identifiable {
        let label: String
        let gain: Double
        let accountCount: Int
        var id: String { label }
    }

    /// Top contributors, one row per SECURITY rather than per stored holding.
    ///
    /// The report's rows are per holding, because cost basis and tax treatment
    /// are per account — necessary everywhere else, and wrong here. A fund held
    /// in a TFSA, an RRSP and an FHSA listed itself three times, three
    /// partial amounts deep in a list of the top six, so the position that
    /// actually contributed most could be beaten by a name that contributed
    /// less but happened to sit in one account. Grouping first makes "top"
    /// mean what it says, and the account count keeps the split visible.
    /// Running total for one security while the rows are being folded together.
    ///
    /// A named struct rather than a labelled tuple in a dictionary value. The
    /// tuple version type-checked in isolation and then timed the compiler out
    /// once it was the receiver of a `map`/`sorted`/`prefix` chain — Swift has
    /// to consider every overload of each link against an inferred tuple
    /// element type, and the search explodes. Concrete types at each step keep
    /// it linear.
    private struct ContributorTotal {
        var gain: Double = 0
        var accounts: Set<String> = []
    }

    private func topContributors() -> [ContributorRow] {
        var totals: [String: ContributorTotal] = [:]
        for row in report.holdings {
            // The same key the portfolio screen groups on: the ticker where
            // there is one, the name where there isn't.
            let trimmed: String? = row.ticker?.trimmingCharacters(in: .whitespaces).uppercased().nilIfEmpty
            let key: String = trimmed ?? row.name
            var entry: ContributorTotal = totals[key] ?? ContributorTotal()
            entry.gain += row.totalGain
            entry.accounts.insert(row.accountName)
            totals[key] = entry
        }

        // Built with a loop and sorted in place rather than chained. Same
        // result, and the compiler has one expression to solve at a time.
        var rows: [ContributorRow] = []
        rows.reserveCapacity(totals.count)
        for (key, total) in totals {
            rows.append(
                ContributorRow(label: key, gain: total.gain, accountCount: total.accounts.count)
            )
        }
        // Largest contribution first, and a tie broken by name so the order
        // does not shuffle between two renders of the same report.
        rows.sort { (a: ContributorRow, b: ContributorRow) -> Bool in
            if a.gain == b.gain { return a.label < b.label }
            return a.gain > b.gain
        }
        if rows.count > 6 { rows.removeSubrange(6..<rows.count) }
        return rows
    }

    private func metric(_ label: String, _ value: String, _ tint: Color?) -> some View {
        StatCell(label: label, value: value, valueColor: tint, emphasis: true)
    }

    private func verdict(_ benchmark: BenchmarkComparison) -> String {
        if abs(benchmark.excessReturn) < 0.0005 {
            return "You matched the benchmark over this period."
        }
        let direction = benchmark.excessReturn > 0 ? "ahead of" : "behind"
        return "You were \(Money.percent(abs(benchmark.excessReturn) * 100)) \(direction) "
            + "\(benchmark.benchmark.displayName) over this period, before any tax."
    }

    // MARK: - Income

    @ViewBuilder
    private var incomeCard: some View {
        if report.income.totalInPeriod > 0 || report.income.trailing12Months > 0 {
            WbCard {
                Text("Dividend income")
                    .font(.wbTitleMedium)
                    .foregroundStyle(Palette.onSurface(scheme))
                    .padding(.bottom, 8)

                KeyValueRow(label: "Income this period",
                            value: Money.format(report.income.totalInPeriod, report.currency))
                KeyValueRow(label: "Trailing 12 months",
                            value: Money.format(report.income.trailing12Months, report.currency))
                if let yieldOnCost = report.income.yieldOnCost {
                    KeyValueRow(label: "Yield on cost (TTM)",
                                value: Money.percent(yieldOnCost * 100))
                }
                if let currentYield = report.income.currentYield {
                    KeyValueRow(label: "Current yield (TTM)",
                                value: Money.percent(currentYield * 100))
                }
                if report.income.reinvestedAmount > 0 {
                    KeyValueRow(label: "Reinvested (DRIP)",
                                value: Money.format(report.income.reinvestedAmount, report.currency))
                }
                KeyValueRow(label: "Payments received",
                            value: "\(report.income.paymentCount)")
            }
        }
    }

    // MARK: - Realized

    @ViewBuilder
    private var realizedCard: some View {
        if !report.realizedGains.isEmpty {
            let total = report.realizedGains.reduce(0) { $0 + $1.realizedGain }
            WbCard {
                Text("Realized Gains")
                    .font(.wbTitleMedium)
                    .foregroundStyle(Palette.onSurface(scheme))
                    .padding(.bottom, 2)
                Text("Closed positions — useful at tax time")
                    .font(.wbBodySmall)
                    .foregroundStyle(Palette.onSurfaceVariant(scheme))
                    .padding(.bottom, 8)

                ForEach(report.realizedGains.prefix(8)) { row in
                    KeyValueRow(
                        label: "\(row.ticker ?? row.holdingName) · \(row.soldAt.formatted(date: .abbreviated, time: .omitted))",
                        value: Money.signed(row.realizedGain, row.currency),
                        valueColor: Palette.change(row.realizedGain)
                    )
                }

                WbDivider().padding(.top, 4)
                KeyValueRow(
                    label: "Total realized",
                    value: Money.signed(total, report.currency),
                    valueColor: Palette.change(total)
                )
            }
        }
    }

    // MARK: - Allocation

    @ViewBuilder
    private var allocationCard: some View {
        if !report.allocation.byType.isEmpty || !report.allocation.byHolding.isEmpty {
            WbCard {
                Text("Allocation")
                    .font(.wbTitleMedium)
                    .foregroundStyle(Palette.onSurface(scheme))
                    .padding(.bottom, 8)

                if !report.allocation.byType.isEmpty {
                    Text("By asset type")
                        .font(.wbBodySmall)
                        .foregroundStyle(Palette.onSurfaceVariant(scheme))
                    ForEach(report.allocation.byType) { slice in
                        KeyValueRow(label: slice.label, value: Money.percent(slice.percent * 100, decimals: 1))
                    }
                }

                if !report.allocation.byHolding.isEmpty {
                    WbDivider().padding(.vertical, 8)
                    Text("Largest positions")
                        .font(.wbBodySmall)
                        .foregroundStyle(Palette.onSurfaceVariant(scheme))
                    ForEach(report.allocation.byHolding.prefix(5)) { slice in
                        KeyValueRow(label: slice.label, value: Money.percent(slice.percent * 100, decimals: 1))
                    }
                }

                if let largest = report.allocation.largestPositionName,
                   report.allocation.largestPositionWeight > 0.25 {
                    Text("You only hold \(Money.percent(report.allocation.largestPositionWeight * 100, decimals: 1)) of the portfolio outside your largest position, \(largest). Concentration is not by itself a problem; it is worth knowing it is there.")
                        .font(.wbBodySmall)
                        .foregroundStyle(Brand.divAmber)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.top, 8)
                }
            }
        }
    }

    // MARK: - Activity

    @ViewBuilder
    private var activityCard: some View {
        let activity = report.activity
        if activity.buyCount + activity.sellCount + activity.dripCount > 0 {
            WbCard {
                Text("Activity")
                    .font(.wbTitleMedium)
                    .foregroundStyle(Palette.onSurface(scheme))
                    .padding(.bottom, 2)
                Text("Transactions recorded inside the reporting window.")
                    .font(.wbBodySmall)
                    .foregroundStyle(Palette.onSurfaceVariant(scheme))
                    .padding(.bottom, 8)

                KeyValueRow(label: "Buys", value: "\(activity.buyCount)")
                KeyValueRow(label: "Sells", value: "\(activity.sellCount)")
                KeyValueRow(label: "Dividend reinvestments", value: "\(activity.dripCount)")
                KeyValueRow(label: "Total invested",
                            value: Money.format(activity.totalInvested, report.currency))
                if activity.totalWithdrawn > 0 {
                    KeyValueRow(label: "Total withdrawn",
                                value: Money.format(activity.totalWithdrawn, report.currency))
                }
            }
        }
    }

    // MARK: - Notes

    /// What the figures above are and are not. A report that does not say where
    /// its numbers stop is read as saying more than it does.
    private var dataNotesCard: some View {
        WbCard {
            Text("Notes & Methodology")
                .font(.wbTitleMedium)
                .foregroundStyle(Palette.onSurface(scheme))
                .padding(.bottom, 8)

            note("Time-weighted return strips out the effect of money going in and out, so it measures the holdings rather than the timing. Money-weighted (XIRR) does the opposite — it is your actual return on the money you put in, when you put it in.")
            note("Everything is built from the transactions you recorded. A buy you never entered is a gain this report cannot see.")
            note("Values are converted into \(report.currency) at today's rate, including for past dates — the app does not keep historical FX.")
            if !report.untickeredHoldingNames.isEmpty {
                note("Held flat at their entered price across the window, because they have no ticker to price: \(report.untickeredHoldingNames.joined(separator: ", ")).")
            }
            note("Generated \(report.generatedAt.formatted(date: .abbreviated, time: .shortened)).")
        }
    }

    private func note(_ text: String) -> some View {
        Text("• " + text)
            .font(.system(size: 11))
            .foregroundStyle(Palette.onSurfaceVariant(scheme))
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.bottom, 6)
    }

    private static let month: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "MMM yyyy"
        return f
    }()
}

// MARK: - Share sheet

private struct ShareItem: Identifiable {
    let url: URL
    var id: String { url.absoluteString }
}

/// `UIActivityViewController` wrapped for SwiftUI. `ShareLink` would do for a
/// single file on iOS 16+, but this keeps the presentation identical whether
/// the payload is a file, a URL or text.
private struct ShareSheet: UIViewControllerRepresentable {
    let items: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }

    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}
