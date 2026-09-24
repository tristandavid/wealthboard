import SwiftUI

/// The Dividends tab: passive-income goal, received income, the monthly income
/// chart, historical breakdown, the upcoming-payments deck, and the received /
/// projected chart with the payment log beneath it.
struct DividendsView: View {
    @EnvironmentObject private var viewModel: PortfolioViewModel
    @Environment(\.colorScheme) private var scheme

    @State private var incomeToggle = "MTD"
    @State private var monthlyToggle = "TTM"
    @State private var historyToggle = "Monthly"
    @State private var chartPeriod: DividendPeriod = .month
    @State private var selectedBar: Int?
    @State private var showGoalSheet = false
    @State private var logPaymentFor: Holding?
    @State private var upcomingPage = 0
    /// Base height of an upcoming-dividend card, before any account split.
    @ScaledMetric private var deckBaseHeight: CGFloat = 230

    private var calendar: Calendar {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = .current
        return c
    }

    var body: some View {
        ScrollView {
            LazyVStack(spacing: 12) {
                goalCard
                receivedIncomeCard
                monthlyIncomeCard
                historicalIncomeCard

                SectionHeader("Upcoming Dividends")
                upcomingSection

                receivedChartCard
                paymentLogSection

                Color.clear.frame(height: 40)
            }
            .padding(.top, 8)
        }
        .wbScreenBackground(scheme)
        .wbNavigationBar("Dividends")
        .refreshable {
            // `reload()` first: every figure above the forecast — received MTD
            // and YTD, the monthly chart, the payment log — is read from the
            // stored document, not from the network. Without it a pull on this
            // screen refreshed only the upcoming-payment cards and left the
            // rest showing whatever was loaded when the tab was first opened.
            await viewModel.reload()
            await viewModel.refreshUpcomingDividendsForced()
            await viewModel.refreshRates()
        }
        .sheet(isPresented: $showGoalSheet) {
            GoalSheet(goal: $viewModel.dividendGoal, currency: viewModel.baseCurrency)
                .presentationDetents([.height(280)])
        }
        .sheet(item: $logPaymentFor) { holding in
            NavigationStack { AddDividendView(holding: holding) }
        }
        .task {
            // Every time the tab is opened, not only the first.
            //
            // Guarding on an empty list meant the upcoming-payment cards were
            // fetched once per launch and then frozen: a distribution declared
            // during the day never appeared, and an amount that changed from
            // estimated to announced stayed an estimate. The scrape is cheap
            // and short-lived now (see `calendarTTL`), so opening the tab is
            // allowed to cost a request.
            if !viewModel.holdings.isEmpty {
                await viewModel.refreshUpcomingDividends()
            }
        }
    }

    // MARK: - Derived figures
    //
    // Every window below is closed at BOTH ends.
    //
    // They used to be open at the top — "paid on or after the start of the
    // month", with no upper bound — which is correct only while no payment is
    // dated in the future. The dividend import records announced-but-unpaid
    // dividends, so that assumption breaks: a dividend payable on 27 October
    // satisfies ">= 1 September" and is reported as money received
    // month-to-date, six weeks before it exists.
    //
    // "Received" has to mean received.

    private var now: Date { Date() }

    private var monthStart: Date {
        calendar.date(from: calendar.dateComponents([.year, .month], from: now)) ?? now
    }

    private var yearStart: Date {
        calendar.date(from: calendar.dateComponents([.year], from: now)) ?? now
    }

    private func inBase(_ payment: DividendPayment) -> Double {
        viewModel.amountInBase(payment.amount, from: payment.normalizedCurrency)
    }

    private var mtdAmount: Double {
        viewModel.dividends
            .filter { $0.paidAt >= monthStart && $0.paidAt <= now }
            .reduce(0) { $0 + inBase($1) }
    }

    private var ytdAmount: Double {
        viewModel.dividends
            .filter { $0.paidAt >= yearStart && $0.paidAt <= now }
            .reduce(0) { $0 + inBase($1) }
    }

    private var trailingTotal: Double {
        let start = now.addingTimeInterval(-365 * 24 * 60 * 60)
        return viewModel.dividends
            .filter { $0.paidAt >= start && $0.paidAt <= now }
            .reduce(0) { $0 + inBase($1) }
    }

    /// Trailing average, and a forward one to fall back on.
    ///
    /// A goal measured only against the last twelve months reads zero for every
    /// new user, and stays there until a real payment lands — which for a
    /// quarterly payer can be three months of a card saying 0%. That is
    /// accurate and useless: the portfolio has a perfectly good expected
    /// income, it just has not been collected yet.
    ///
    /// So when nothing has actually been received, the card falls back to what
    /// the holdings are projected to pay and says which basis it is using. It
    /// never silently mixes the two.
    private var forwardMonthlyAverage: Double {
        let byMonth = viewModel.projectedIncomeByMonth(months: 12)
        guard !byMonth.isEmpty else { return 0 }
        return byMonth.values.reduce(0, +) / 12
    }

    private var usingForecast: Bool {
        trailingTotal / 12 <= 0 && forwardMonthlyAverage > 0
    }

    private var monthlyAverage: Double {
        usingForecast ? forwardMonthlyAverage : trailingTotal / 12
    }

    private var goalProgress: Double {
        guard viewModel.dividendGoal > 0 else { return 0 }
        return min(max(monthlyAverage / viewModel.dividendGoal, 0), 1)
    }

    // MARK: - Per-holding series
    //
    // Every income chart on this tab is stacked by holding, so they all need
    // the same two things: a stable key per holding and a colour map shared
    // across charts. The map is built from the WHOLE portfolio rather than from
    // whichever holdings happen to have income inside one chart's window —
    // otherwise a holding could be blue on the TTM chart and violet on the
    // yearly one purely because a third holding had no payments last year.

    private var seriesKeyById: [UUID: String] {
        var out: [UUID: String] = [:]
        for holding in viewModel.holdings {
            out[holding.id] = holding.ticker?.nilIfEmpty ?? holding.name
        }
        return out
    }

    private var seriesColors: [String: Color] {
        SeriesPalette.colors(for: seriesKeyById.values)
    }

    private func slices(_ byHolding: [UUID: Double]) -> [IncomeSlice] {
        byHolding
            .filter { $0.value > 0 }
            .map { id, amount in
                let key = seriesKeyById[id] ?? "—"
                return IncomeSlice(key: key, label: key, amount: amount)
            }
            .sorted { $0.amount > $1.amount }
    }

    /// Received income, bucketed by the last twelve calendar months.
    private var monthlyBarsTTM: [StackedIncomeBar] {
        var bars: [StackedIncomeBar] = []
        for offset in stride(from: 11, through: 0, by: -1) {
            guard let bucket = calendar.date(byAdding: .month, value: -offset, to: now) else { continue }
            let components = calendar.dateComponents([.year, .month], from: bucket)
            guard let year = components.year, let month = components.month else { continue }

            var byHolding: [UUID: Double] = [:]
            for payment in viewModel.dividends {
                // Received means received — an announced-but-unpaid payment is
                // not history, however it got into the store.
                guard payment.paidAt <= now else { continue }
                let paid = calendar.dateComponents([.year, .month], from: payment.paidAt)
                guard paid.year == year, paid.month == month else { continue }
                byHolding[payment.holdingId, default: 0] += inBase(payment)
            }

            bars.append(StackedIncomeBar(
                label: shortMonth(bucket),
                bucketKey: year * 100 + (month - 1),
                slices: slices(byHolding)
            ))
        }
        return bars
    }

    /// The next twelve months, projected from each holding's own seasonal
    /// payment record by the shared forecaster.
    ///
    /// Two bugs lived here on Android. The first divided an annual rate by
    /// twelve and drew twelve identical bars. The second — the one that
    /// survived the first fix — repeated a single per-payment estimate at a
    /// fixed cadence, so a quarterly ETF still drew four bars of exactly equal
    /// height. Real distributions are seasonal, and a forward view exists
    /// precisely to show which months are heavy.
    private var monthlyBarsFWD: [StackedIncomeBar] {
        let byMonth = viewModel.projectedIncomeByMonthAndHolding(months: 12)
        var bars: [StackedIncomeBar] = []
        for offset in 1...12 {
            guard let bucket = calendar.date(byAdding: .month, value: offset, to: now) else { continue }
            let components = calendar.dateComponents([.year, .month], from: bucket)
            guard let year = components.year, let month = components.month else { continue }
            let key = year * 100 + (month - 1)
            bars.append(StackedIncomeBar(
                label: shortMonth(bucket),
                bucketKey: key,
                slices: slices(byMonth[key] ?? [:])
            ))
        }
        return bars
    }

    /// Yearly history — most recent first.
    private var yearlyHistory: [StackedIncomeBar] {
        let thisYear = calendar.component(.year, from: now)
        return (0..<5).map { offset -> StackedIncomeBar in
            let year = thisYear - offset
            var byHolding: [UUID: Double] = [:]
            for payment in viewModel.dividends where payment.paidAt <= now {
                guard calendar.component(.year, from: payment.paidAt) == year else { continue }
                byHolding[payment.holdingId, default: 0] += inBase(payment)
            }
            return StackedIncomeBar(
                label: String(year),
                bucketKey: year,
                slices: slices(byHolding)
            )
        }
    }

    /// Payments visible in the log: anything auto-imported that pre-dates the
    /// holding's creation is filtered out, because the user did not own it then.
    private var visiblePayments: [DividendPayment] {
        viewModel.dividends.filter { payment in
            guard let holding = viewModel.holding(payment.holdingId) else { return true }
            return payment.paidAt >= holding.createdAt
        }
    }

    private func shortMonth(_ date: Date) -> String {
        let f = DateFormatter()
        f.locale = Locale.current
        f.dateFormat = "MMM"
        return f.string(from: date)
    }

    // MARK: - Cards

    private var goalCard: some View {
        PassiveIncomeGoalCard(
            monthlyAverage: monthlyAverage,
            goal: viewModel.dividendGoal,
            progress: goalProgress,
            currency: viewModel.baseCurrency,
            isForecast: usingForecast,
            onEditGoal: { showGoalSheet = true }
        )
        .padding(.horizontal, WbDimens.screenPadding)
    }

    private var receivedIncomeCard: some View {
        WbCard {
            HStack {
                Text("Received Income")
                    .font(.wbTitleMedium)
                    .foregroundStyle(Palette.onSurface(scheme))
                Spacer()
                ToggleChips(left: "MTD", right: "YTD", selection: $incomeToggle)
            }
            .padding(.bottom, 10)

            Text(Money.format(incomeToggle == "MTD" ? mtdAmount : ytdAmount, viewModel.baseCurrency))
                .font(.system(size: 30, weight: .heavy))
                .foregroundStyle(Brand.divGreen)
                .lineLimit(1)
                .minimumScaleFactor(0.6)

            Text(incomeToggle == "MTD" ? "Month to date" : "Year to date")
                .font(.wbBodySmall)
                .foregroundStyle(Palette.onSurfaceVariant(scheme))
        }
        .padding(.horizontal, WbDimens.screenPadding)
    }

    private var monthlyIncomeCard: some View {
        let bars = monthlyToggle == "TTM" ? monthlyBarsTTM : monthlyBarsFWD
        let isForward = monthlyToggle == "FWD"
        let present = legendEntries(for: bars)

        return WbCard {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Monthly Income")
                        .font(.wbTitleMedium)
                        .foregroundStyle(Palette.onSurface(scheme))
                    Text(isForward
                         ? "Projected income · from each fund's own payment record"
                         : "Tap a bar to see the amount")
                        .font(.wbBodySmall)
                        .foregroundStyle(Palette.onSurfaceVariant(scheme))
                }
                Spacer()
                ToggleChips(left: "TTM", right: "FWD", selection: $monthlyToggle)
            }
            .padding(.bottom, 12)

            if bars.allSatisfy({ $0.total <= 0 }) {
                Text(isForward
                     ? "No projected income yet. Add a holding with a ticker and its schedule will appear here."
                     : "No dividends recorded in the last 12 months. Tap FWD for projected income, or log a payment from a holding.")
                    .font(.wbBodyMedium)
                    .foregroundStyle(Palette.onSurfaceVariant(scheme))
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 40)
            } else {
                StackedColumnChart(
                    bars: bars,
                    seriesColors: seriesColors,
                    selectedIndex: $selectedBar
                )

                if let index = selectedBar, index < bars.count {
                    SelectedBarDetail(
                        bar: bars[index],
                        currency: viewModel.baseCurrency,
                        seriesColors: seriesColors
                    )
                    .padding(.top, 10)
                }

                IncomeSeriesLegend(entries: present)
                    .padding(.top, 12)
            }
        }
        .padding(.horizontal, WbDimens.screenPadding)
        .onChange(of: monthlyToggle) { _ in selectedBar = nil }
    }

    private var historicalIncomeCard: some View {
        let shown = historyToggle == "Monthly" ? monthlyBarsTTM : yearlyHistory
        let fallback = historyToggle == "Monthly" ? Brand.divBarActual : Brand.divIndigo
        let maxAmount = max(shown.map(\.total).max() ?? 0, 0.0001)
        let present = legendEntries(for: shown)

        return WbCard {
            HStack {
                Text("Historical Income")
                    .font(.wbTitleMedium)
                    .foregroundStyle(Palette.onSurface(scheme))
                Spacer()
                ToggleChips(left: "Monthly", right: "Yearly", selection: $historyToggle)
            }
            .padding(.bottom, 14)

            ForEach(shown) { bar in
                HistoryBarRow(
                    label: bar.label,
                    bar: bar,
                    fraction: bar.total / maxAmount,
                    currencyCode: viewModel.baseCurrency,
                    seriesColors: seriesColors,
                    fallbackColor: fallback
                )
            }

            if !present.isEmpty {
                IncomeSeriesLegend(entries: present)
                    .padding(.top, 12)
            }
        }
        .padding(.horizontal, WbDimens.screenPadding)
    }

    /// Legend limited to the holdings that actually contributed something in
    /// this view, largest first.
    private func legendEntries(for bars: [StackedIncomeBar]) -> [(String, Color)] {
        var totals: [String: Double] = [:]
        for bar in bars {
            for slice in bar.slices { totals[slice.key, default: 0] += slice.amount }
        }
        return totals
            .sorted { $0.value > $1.value }
            .map { ($0.key, seriesColors[$0.key] ?? SeriesPalette.unknown) }
    }

    // MARK: - Upcoming deck

    /// One height for the whole deck, and it is the tallest card's.
    ///
    /// Sizing each card to its own content was not enough: a position split
    /// across three accounts draws three extra rows, so the card beside it came
    /// out visibly shorter and the deck changed height as you swiped. Deriving
    /// one height from the widest split means every page measures the same on
    /// the first frame.
    ///
    /// `@ScaledMetric` on the base so the deck grows with the reader's text
    /// size rather than clipping the last row.
    private var upcomingDeckHeight: CGFloat {
        let widestSplit = viewModel.upcomingDividends.map(\.rows.count).max() ?? 1
        // The "Paid into" heading plus one row per account, when anything in
        // the deck is split at all.
        let splitExtra = widestSplit > 1 ? CGFloat(26 + widestSplit * 24) : 0
        return deckBaseHeight + splitExtra
    }

    @ViewBuilder
    private var upcomingSection: some View {
        if viewModel.upcomingDividends.isEmpty {
            EmptyNote(text: "No upcoming dividend data found. Make sure your holdings have ticker symbols set.")
                .padding(.horizontal, WbDimens.screenPadding)
        } else {
            // A swipeable deck rather than a stack. One card per holding,
            // stacked vertically, would push everything below it — the
            // received-payments chart and the whole payment log — several
            // screens down for anyone holding more than a handful of dividend
            // payers. Horizontally, the section costs one card's height no
            // matter how many there are.
            TabView(selection: $upcomingPage) {
                ForEach(Array(viewModel.upcomingDividends.enumerated()), id: \.element.id) { index, row in
                    UpcomingDividendCard(row: row)
                        // Every card fills the deck, so all of them are the
                        // same height — see `upcomingDeckHeight`.
                        .frame(maxHeight: .infinity)
                        .padding(.horizontal, WbDimens.screenPadding)
                        .tag(index)
                }
            }
            .tabViewStyle(.page(indexDisplayMode: viewModel.upcomingDividends.count > 1 ? .automatic : .never))
            .indexViewStyle(.page(backgroundDisplayMode: .interactive))
            .frame(height: upcomingDeckHeight)
        }
    }

    // MARK: - Received chart and log

    private var receivedChartCard: some View {
        let flows = viewModel.dividendCashFlowsInBase()
        let yield = viewModel.portfolioForwardYield

        return WbCard {
            DividendBarChart(
                bars: buildDividendBars(
                    received: flows.received,
                    projected: flows.projected,
                    period: chartPeriod,
                    // Reinvesting compounds units at the portfolio's own blended
                    // yield rather than a flat guess.
                    dripRate: yield
                ),
                period: $chartPeriod,
                currencyCode: viewModel.baseCurrency,
                title: chartPeriod == .year ? "Yearly Income" : "Monthly Income",
                // No yield means no reinvestment to draw. Showing the DRIP
                // swatch anyway promises a series that isn't there.
                showDrip: chartPeriod == .year && yield > 0
            )
        }
        .padding(.horizontal, WbDimens.screenPadding)
    }

    /// The holding picker behind "Log past".
    ///
    /// One entry per SECURITY, not per stored row. The same fund held in a
    /// TFSA, an FHSA and an RRSP is three rows in the store, and this menu
    /// listed all three under the identical name — three indistinguishable
    /// buttons, with no way to tell which account each one meant. It is one
    /// line per holding now; where a holding really does sit in more than one
    /// account, picking it opens a second menu of those accounts, which is the
    /// choice that was being asked for all along.
    @ViewBuilder
    private func logPaymentMenu(label: String) -> some View {
        Menu {
            ForEach(viewModel.positions) { position in
                if position.slices.count == 1, let slice = position.slices.first {
                    Button(position.name) { logPaymentFor = slice.holding }
                } else {
                    Menu(position.name) {
                        ForEach(position.slices) { slice in
                            Button(slice.accountName) { logPaymentFor = slice.holding }
                        }
                    }
                }
            }
        } label: {
            Text(label)
                .font(.wbBodySmall)
                .fontWeight(.semibold)
                .foregroundStyle(Brand.divIndigo)
        }
        .disabled(viewModel.positions.isEmpty)
    }

    @ViewBuilder
    private var paymentLogSection: some View {
        SectionHeader(
            title: "Dividends Received",
            subtitle: nil,
            trailing: {
                // No "Import" here. It pulled a public dividend calendar and
                // wrote every past distribution for every ticker into the log
                // as though the user had received it — which is only true if
                // they held the whole position for the whole history. What it
                // produced was an income record nobody could reconcile against
                // a statement, and undoing it meant deleting the rows by hand.
                // Payments are logged from a real statement instead.
                //
                // "Log past" sits where Import sat, and is here rather than
                // only in the empty state: the empty state disappears with the
                // first payment, so with Import gone that would have left no
                // way at all to record a second one.
                HStack(spacing: 10) {
                    Text("\(visiblePayments.count) payment\(visiblePayments.count == 1 ? "" : "s")")
                        .font(.wbBodySmall)
                        .foregroundStyle(Palette.onSurfaceVariant(scheme))
                    logPaymentMenu(label: "+ Log past")
                }
            }
        )

        if visiblePayments.isEmpty {
            VStack(alignment: .leading, spacing: 10) {
                Text("No payments logged yet. Received dividends appear here and in the chart above.")
                    .font(.wbBodySmall)
                    .foregroundStyle(Palette.onSurfaceVariant(scheme))
                logPaymentMenu(label: "+ Log a past payment manually")
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(WbDimens.cardPadding)
            .background(Palette.surface(scheme))
            .clipShape(RoundedRectangle(cornerRadius: WbDimens.cardRadius, style: .continuous))
            .padding(.horizontal, WbDimens.screenPadding)
        } else {
            WbCard(padding: 0) {
                ForEach(visiblePayments) { payment in
                    PaymentRow(payment: payment)
                    if payment.id != visiblePayments.last?.id {
                        WbDivider().padding(.leading, WbDimens.cardPadding)
                    }
                }
            }
            .padding(.horizontal, WbDimens.screenPadding)
        }
    }
}

// MARK: - Goal card

private struct PassiveIncomeGoalCard: View {
    let monthlyAverage: Double
    let goal: Double
    let progress: Double
    let currency: String
    let isForecast: Bool
    let onEditGoal: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text("Passive Income Goal")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(.white.opacity(0.75))
                Spacer()
                Button(action: onEditGoal) {
                    Text("Edit Goal")
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(.white.opacity(0.9))
                        .padding(.horizontal, 12)
                        .padding(.vertical, 5)
                        .background(.white.opacity(0.12),
                                    in: RoundedRectangle(cornerRadius: 8, style: .continuous))
                }
                .buttonStyle(.plain)
            }
            .padding(.bottom, 18)

            // One figure leads, the goal reads as its context, and the ring
            // sits apart from both.
            //
            // The three used to share a row as equals — a big number, a second
            // slightly smaller number and a percentage — with two 10pt captions
            // above them. Nothing said which was the answer and which was the
            // target, and on a narrow phone the captions wrapped into the
            // numbers underneath.
            HStack(alignment: .center, spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(isForecast ? "Projected monthly income" : "Monthly average income")
                        .font(.system(size: 11, weight: .medium))
                        .foregroundStyle(.white.opacity(0.6))
                    Text(Money.format(monthlyAverage, currency))
                        .font(.system(size: 30, weight: .heavy))
                        .foregroundStyle(.white)
                        .lineLimit(1)
                        .minimumScaleFactor(0.6)
                    Text(goal > 0
                         ? "of \(Money.format(goal, currency)) a month"
                         : "No monthly goal set yet")
                        .font(.system(size: 12))
                        .foregroundStyle(.white.opacity(0.6))
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                }
                Spacer(minLength: 0)

                ZStack {
                    Circle().stroke(.white.opacity(0.15), lineWidth: 5)
                    Circle()
                        .trim(from: 0, to: max(0, min(progress, 1)))
                        .stroke(
                            Brand.divIndigoPale,
                            style: StrokeStyle(lineWidth: 5, lineCap: .round)
                        )
                        .rotationEffect(.degrees(-90))
                    Text(goal > 0 ? "\(Int(progress * 100))%" : "—")
                        .font(.system(size: 14, weight: .heavy))
                        .foregroundStyle(.white)
                }
                .frame(width: 58, height: 58)
            }
            .padding(.bottom, 14)

            WbProgressBar(
                fraction: progress,
                height: 8,
                gradient: LinearGradient(
                    colors: [Brand.divIndigoLight, Brand.divIndigoPale],
                    startPoint: .leading,
                    endPoint: .trailing
                ),
                track: .white.opacity(0.15)
            )
            .padding(.bottom, 6)

            // One line, left to right, rather than two captions fighting for
            // the same row.
            HStack(spacing: 6) {
                if goal > 0 {
                    if progress >= 1 {
                        Text("🎯 Goal reached")
                            .font(.system(size: 11, weight: .semibold))
                            .foregroundStyle(Brand.divIndigoPale)
                    } else {
                        Text("\(Money.format(goal - monthlyAverage, currency)) to go")
                            .font(.system(size: 11, weight: .medium))
                            .foregroundStyle(.white.opacity(0.75))
                    }
                }
                Spacer(minLength: 8)
                Text(isForecast ? "Projected — nothing received yet" : "Received to date")
                    .font(.system(size: 10))
                    .foregroundStyle(.white.opacity(0.4))
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
            }
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 18)
        .background(
            LinearGradient(
                colors: [Brand.goalIndigoDark, Brand.goalIndigoMid],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
        )
        .clipShape(RoundedRectangle(cornerRadius: WbDimens.cardRadius, style: .continuous))
    }
}

// MARK: - Selected bar readout

private struct SelectedBarDetail: View {
    @Environment(\.colorScheme) private var scheme

    let bar: StackedIncomeBar
    let currency: String
    let seriesColors: [String: Color]

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(bar.label)
                    .font(.wbBodyMedium)
                    .fontWeight(.semibold)
                    .foregroundStyle(Palette.onSurface(scheme))
                Spacer()
                Text(Money.format(bar.total, currency))
                    .font(.wbBodyMedium)
                    .fontWeight(.bold)
                    .foregroundStyle(Palette.onSurface(scheme))
            }
            ForEach(bar.slices) { slice in
                HStack(spacing: 6) {
                    RoundedRectangle(cornerRadius: 2)
                        .fill(seriesColors[slice.key] ?? SeriesPalette.unknown)
                        .frame(width: 8, height: 8)
                    Text(slice.label)
                        .font(.wbBodySmall)
                        .foregroundStyle(Palette.onSurfaceVariant(scheme))
                    Spacer()
                    Text(Money.format(slice.amount, currency))
                        .font(.wbBodySmall)
                        .foregroundStyle(Palette.onSurfaceVariant(scheme))
                }
            }
        }
        .padding(10)
        .background(Palette.surfaceVariant(scheme).opacity(0.4))
        .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
    }
}

// MARK: - Upcoming card

private struct UpcomingDividendCard: View {
    @EnvironmentObject private var viewModel: PortfolioViewModel
    @Environment(\.colorScheme) private var scheme

    let row: UpcomingDividendRow

    var body: some View {
        // Prefer the directly-estimated (or declared) per-payment amount. Only
        // fall back to annual ÷ frequency when the source gave us nothing
        // better — that even split badly overstates ETFs with lumpy quarterly
        // distributions.
        let frequency = row.info.paymentFrequencyPerYear ?? 4
        let perUnit = row.info.perPaymentAmount
            ?? row.info.estimatedAnnualRate.map { $0 / Double(frequency) }
        let estimated = perUnit.map { $0 * row.units }
        let nativeCurrency = row.holding.normalizedCurrency
        let converted: Double? = nativeCurrency.caseInsensitiveCompare(viewModel.baseCurrency) == .orderedSame
            ? nil
            : estimated.map { viewModel.amountInBase($0, from: nativeCurrency) }

        NavigationLink {
            HoldingDetailView(holdingId: row.holding.id)
        } label: {
            WbCard(padding: 14) {
                HStack(alignment: .top) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(row.holding.name)
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundStyle(Palette.onSurface(scheme))
                            .lineLimit(1)
                        if let ticker = row.holding.ticker {
                            Text(ticker)
                                .font(.system(size: 11, weight: .semibold))
                                .foregroundStyle(Brand.divIndigo)
                        }
                    }
                    Spacer(minLength: 8)

                    if let estimated, estimated > 0 {
                        VStack(alignment: .trailing, spacing: 1) {
                            Text(Money.format(estimated, nativeCurrency))
                                .font(.system(size: 19, weight: .heavy))
                                .foregroundStyle(Brand.divAmber)
                                .lineLimit(1)
                                .minimumScaleFactor(0.7)
                            // The same payment, converted — shown whenever this
                            // holding's currency isn't the user's default, so a
                            // USD payment on a CAD portfolio still reads as a
                            // comparable number at a glance.
                            if let converted {
                                Text("≈ \(Money.format(converted, viewModel.baseCurrency))")
                                    .font(.wbLabelSmall)
                                    .foregroundStyle(Palette.onSurfaceVariant(scheme))
                            }
                            if let perUnit {
                                Text("\(String(format: "%.4f", perUnit)) × \(String(format: "%.2f", row.units)) shares")
                                    .font(.wbLabelSmall)
                                    .foregroundStyle(Palette.onSurfaceVariant(scheme))
                            }
                        }
                    }
                }
                .padding(.bottom, 6)

                // Announced (green) once the fund declares the distribution,
                // otherwise Estimated (amber) with how the figure was derived.
                StatusPill(
                    text: badgeText(frequency: frequency),
                    color: row.info.isAnnounced ? Brand.gain : Brand.divAmber
                )

                WbDivider().padding(.vertical, 12)

                HStack(spacing: 16) {
                    DateInfoBlock(
                        label: "Ex-Dividend Date",
                        date: row.info.exDividendDate,
                        tint: Brand.divIndigo
                    )
                    DateInfoBlock(
                        label: "Payment Date",
                        date: row.info.payDate,
                        tint: Brand.divGreen
                    )
                }

                accountSplit(perUnit: perUnit, currency: nativeCurrency)

                // Pushes the card out to whatever height the deck proposes, so
                // a card with a two-account split is the same size as one with
                // none instead of stopping short of the deck's edge.
                Spacer(minLength: 0)
            }
        }
        .buttonStyle(.plain)
    }

    /// Where the payment actually lands.
    ///
    /// One distribution is paid into each account holding the fund, and which
    /// account matters: the same payment is kept whole in one and quietly
    /// docked 15% in another. The card shows the combined figure because that
    /// is what arrives; this says where.
    @ViewBuilder
    private func accountSplit(perUnit: Double?, currency: String) -> some View {
        if row.isSplit, let perUnit {
            WbDivider().padding(.vertical, 10)

            Text("Paid into")
                .font(.wbLabelSmall)
                .foregroundStyle(Palette.onSurfaceVariant(scheme))
                .padding(.bottom, 4)

            ForEach(row.rows) { slice in
                HStack(spacing: 6) {
                    Text(viewModel.account(for: slice)?.displayName ?? "Unassigned")
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(Palette.onSurface(scheme))
                        .lineLimit(1)
                    if let treatment = viewModel.account(for: slice)?.taxTreatment {
                        Text(treatment.label)
                            .font(.system(size: 10))
                            .foregroundStyle(Palette.onSurfaceVariant(scheme))
                    }
                    Spacer(minLength: 6)
                    Text(Money.format(perUnit * slice.units, currency))
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(Brand.divAmber)
                }
                .padding(.vertical, 3)
            }
        }
    }

    private func badgeText(frequency: Int) -> String {
        let label: String
        switch frequency {
        case 12: label = "monthly"
        case 4: label = "quarterly"
        case 2: label = "semi-annual"
        case 1: label = "annual"
        default: label = "×\(frequency)/yr"
        }
        return row.info.isAnnounced
            ? "Announced · declared by fund (\(label))"
            // "Next payment", not "Estimated".
            //
            // This card and the forward chart used to be labelled "Estimated"
            // and "Projected" — two words that mean the same thing in English,
            // attached to two quantities that are not the same thing at all:
            // one payment against a year of them. Naming what each figure IS
            // costs nothing and removes the question.
            : "Next payment · \(row.info.basisLabel ?? "recent payments") (\(label))"
    }
}

private struct DateInfoBlock: View {
    @Environment(\.colorScheme) private var scheme

    let label: String
    let date: Date?
    let tint: Color

    var body: some View {
        HStack(spacing: 8) {
            ZStack {
                RoundedRectangle(cornerRadius: 10, style: .continuous)
                    .fill(tint.opacity(0.12))
                Image(systemName: "calendar")
                    .font(.system(size: 14))
                    .foregroundStyle(tint)
            }
            .frame(width: 34, height: 34)

            VStack(alignment: .leading, spacing: 1) {
                Text(label)
                    .font(.wbLabelSmall)
                    .foregroundStyle(Palette.onSurfaceVariant(scheme))
                if let date {
                    Text(date.formatted(date: .abbreviated, time: .omitted))
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(Palette.onSurface(scheme))
                    Text(daysAwayText(date))
                        .font(.wbLabelSmall)
                        .foregroundStyle(tint)
                } else {
                    Text("Not yet announced")
                        .font(.wbLabelSmall)
                        .foregroundStyle(Palette.onSurfaceVariant(scheme))
                }
            }
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func daysAwayText(_ date: Date) -> String {
        let days = max(Int(date.timeIntervalSinceNow / 86400), 0)
        if days == 0 { return "Today" }
        return "in \(days) day\(days == 1 ? "" : "s")"
    }
}

// MARK: - Payment log row

private struct PaymentRow: View {
    @EnvironmentObject private var viewModel: PortfolioViewModel
    @Environment(\.colorScheme) private var scheme

    let payment: DividendPayment

    var body: some View {
        let holding = viewModel.holding(payment.holdingId)

        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(holding?.ticker ?? holding?.name ?? "Holding")
                    .font(.wbBodyMedium)
                    .fontWeight(.semibold)
                    .foregroundStyle(Palette.onSurface(scheme))
                Text(payment.paidAt.formatted(date: .abbreviated, time: .omitted))
                    .font(.wbBodySmall)
                    .foregroundStyle(Palette.onSurfaceVariant(scheme))
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 2) {
                Text(Money.format(payment.amount, payment.normalizedCurrency))
                    .font(.wbBodyMedium)
                    .fontWeight(.semibold)
                    .foregroundStyle(Brand.divGreen)
                if let perUnit = payment.perUnit {
                    Text("\(Money.perUnit(perUnit, payment.normalizedCurrency)) / unit")
                        .font(.wbBodySmall)
                        .foregroundStyle(Palette.onSurfaceVariant(scheme))
                }
            }
        }
        .padding(WbDimens.cardPadding)
        .contentShape(Rectangle())
        .contextMenu {
            Button("Delete payment", role: .destructive) {
                Task { await viewModel.deleteDividend(payment.id) }
            }
        }
    }
}

// MARK: - Goal sheet

private struct GoalSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var scheme

    @Binding var goal: Double
    let currency: String

    @State private var text = ""

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 14) {
                Text("How much monthly dividend income are you aiming for? The card tracks your trailing twelve-month average against it, or your projected income until a payment lands.")
                    .font(.wbBodySmall)
                    .foregroundStyle(Palette.onSurfaceVariant(scheme))

                HStack {
                    Text(Money.symbol(currency))
                        .font(.wbTitleMedium)
                        .foregroundStyle(Palette.onSurfaceVariant(scheme))
                    TextField("5,000", text: $text)
                        .keyboardType(.decimalPad)
                        .font(.wbTitleLarge)
                }
                .padding(12)
                .background(Palette.surfaceVariant(scheme).opacity(0.4))
                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))

                Spacer()
            }
            .padding(WbDimens.screenPadding)
            .background(Palette.background(scheme).ignoresSafeArea())
            .navigationTitle("Monthly goal")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        if let value = Double(text.replacingOccurrences(of: ",", with: "")) {
                            goal = max(value, 0)
                        }
                        dismiss()
                    }
                }
            }
            .onAppear {
                text = goal > 0 ? String(format: "%.0f", goal) : ""
            }
        }
    }
}
