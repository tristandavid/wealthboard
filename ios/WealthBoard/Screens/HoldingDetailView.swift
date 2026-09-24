import SwiftUI

/// Holding detail: price header, position stats, tax, dividend metrics, the
/// fund's own distribution history, a trailing-yield chart, the received-vs-
/// projected income chart and the payout schedule.
///
/// Laid out as labelled stat pairs inside the app's standard cards so it reads
/// the same way as the Dividends and Markets screens.
struct HoldingDetailView: View {
    @EnvironmentObject private var viewModel: PortfolioViewModel
    @Environment(\.colorScheme) private var scheme
    @Environment(\.dismiss) private var dismiss

    let holdingId: UUID

    @State private var detail: HoldingDetail?
    @State private var bars: [HistoryBar] = []
    @State private var range = PortfolioRange.all[5]   // 1Y
    @State private var chartPeriod: DividendPeriod = .month
    @State private var showAddDividend = false
    @State private var showDeleteConfirm = false
    @State private var showEditHolding = false

    /// Currency toggle.
    ///
    /// Every figure below is computed in the holding's own trading currency.
    /// For a holding that trades somewhere other than the user's reporting
    /// currency — AAPL held by a CAD-default user, say — the screen opens
    /// converted into the reporting currency, since that is the number that
    /// lines up with the rest of the portfolio, with a one-tap switch back to
    /// the currency the position actually trades in.
    @State private var viewInBase = true

    /// The account the tax deck is showing.
    @State private var taxPage = 0

    private var holding: Holding? { viewModel.holding(holdingId) }

    private var nativeCurrency: String {
        detail?.quote?.currency?.uppercased()
            ?? holding?.normalizedCurrency
            ?? viewModel.baseCurrency
    }

    private var needsToggle: Bool {
        holding != nil && nativeCurrency.caseInsensitiveCompare(viewModel.baseCurrency) != .orderedSame
    }

    /// `viewInBase` is the request; `fxRate` is whether it can be honoured.
    ///
    /// A currency there is no pair for — or one whose rate simply hasn't landed
    /// yet — leaves this nil, and the figures then stay in, and are labelled
    /// in, the currency they are actually in. Labelling an unconverted SEK
    /// figure "CA$" is the one outcome worse than not converting it at all: it
    /// is not a rounding error, it is a number that reads roughly 7× too large
    /// with no hint that anything went wrong.
    private var fxRate: Double? {
        guard needsToggle, viewInBase else { return nil }
        // Touch fxTick so the whole screen re-renders the moment a requested
        // rate actually lands.
        _ = viewModel.fxTick
        return viewModel.rateToBase(from: nativeCurrency)
    }

    private var activeCurrency: String {
        fxRate != nil ? viewModel.baseCurrency : nativeCurrency
    }

    /// One converter+formatter every money figure on the screen goes through.
    private func money(_ value: Double) -> String {
        Money.format(fxRate.map { value * $0 } ?? value, activeCurrency)
    }

    /// Same conversion, for the few figures that need the number rather than
    /// the formatted string — per-unit amounts use their own higher-precision
    /// formatter.
    private func inActive(_ value: Double) -> Double {
        fxRate.map { value * $0 } ?? value
    }

    var body: some View {
        Group {
            if let holding {
                content(holding)
            } else {
                // The holding was deleted while this screen was open — say so
                // rather than showing an empty shell of a position.
                EmptyNote(text: "This holding is no longer in your portfolio.")
                    .padding(WbDimens.screenPadding)
            }
        }
        .wbScreenBackground(scheme)
        .navigationTitle(navigationTitle)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Menu {
                    // No "Record a transaction" here. Buys and sells are
                    // recorded from the Portfolio "+", where the account is
                    // part of the entry; offering it from one account's holding
                    // as well made the same trade reachable two ways.
                    Button("Record dividend") { showAddDividend = true }
                    Button("Edit holding") { showEditHolding = true }
                    Divider()
                    Button("Delete holding", role: .destructive) { showDeleteConfirm = true }
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
            }
        }
        .sheet(isPresented: $showAddDividend) {
            if let holding {
                NavigationStack { AddDividendView(holding: holding) }
            }
        }
        .sheet(isPresented: $showEditHolding) {
            NavigationStack { ManualHoldingView(editing: holdingId) }
        }
        .confirmationDialog(
            "Delete holding?",
            isPresented: $showDeleteConfirm,
            titleVisibility: .visible
        ) {
            Button("Delete", role: .destructive) {
                Task {
                    await viewModel.deleteHolding(holdingId)
                    dismiss()
                }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("\(holding?.name ?? "This holding") and its recorded dividends will be removed.")
        }
        .task(id: holdingId) { await load() }
        .task(id: nativeCurrency) { await viewModel.ensureRate(for: nativeCurrency) }
        .task(id: range) { await loadBars() }
    }

    private var navigationTitle: String {
        guard let holding else { return "Holding" }
        let flag = TickerFlag.forTicker(holding.ticker ?? "", currency: nativeCurrency)
        let name = holding.ticker ?? holding.name
        return flag.isEmpty ? name : "\(flag)  \(name)"
    }

    // MARK: - Content

    private func content(_ holding: Holding) -> some View {
        ScrollView {
            // Grouped rather than flat: ViewBuilder takes ten children and this
            // screen has more sections than that.
            VStack(spacing: 12) {
                // Two groups rather than one flat stack: ViewBuilder takes at
                // most ten children and this screen has twelve sections.
                Group {
                    header(holding)
                    if holding.ticker != nil { priceChart }
                    overviewCard(holding)
                    positionCard(holding)
                    taxSection(holding)
                    dividendMetricsCard()
                }
                Group {
                    dividendHistorySection()
                    yieldHistorySection()
                    incomeChartSection()
                    payoutScheduleSection(holding)
                    Color.clear.frame(height: 30)
                }
            }
            .padding(.horizontal, WbDimens.screenPadding)
            .padding(.top, 8)
        }
        .refreshable { await load() }
    }

    // MARK: - Header

    private func header(_ holding: Holding) -> some View {
        let price = detail?.quote?.price ?? viewModel.currentPrice(for: holding)
        let move = headerMove

        return WbCard {
            // The name side is weighted and the toggle is not: an unweighted
            // row measures at its content's full width, so a long company name
            // ("Ericsson, Telefonab. L M ser. A") takes the whole header and
            // lays the toggle out past the right edge of the screen.
            HStack(alignment: .top, spacing: 8) {
                HStack(alignment: .top, spacing: 12) {
                    TickerLogo(
                        logoURL: detail?.quote?.logoURL
                            ?? QuoteClient.logoURL(ticker: holding.ticker ?? holding.name, name: holding.name),
                        label: holding.ticker ?? holding.name,
                        size: 44
                    )
                    VStack(alignment: .leading, spacing: 2) {
                        Text(holding.name)
                            .font(.wbTitleLarge)
                            .fontWeight(.bold)
                            .foregroundStyle(Palette.onSurface(scheme))
                            .lineLimit(3)
                            .fixedSize(horizontal: false, vertical: true)

                        Text(money(price))
                            .font(.wbHeadline)
                            .foregroundStyle(Palette.onSurface(scheme))

                        if let move {
                            Text("\(Money.signed(inActive(move.absolute), activeCurrency)) (\(Money.signedPercent(move.percent)))  \(move.label)")
                                .font(.wbBodyMedium)
                                .fontWeight(.medium)
                                .foregroundStyle(Palette.change(move.absolute))
                        }

                        if let marketTime = detail?.quote?.marketTime {
                            Text("As of " + TradeTime.label(
                                at: marketTime,
                                providerZone: detail?.quote?.exchangeTimezone,
                                ticker: holding.ticker
                            ))
                            .font(.wbBodySmall)
                            .foregroundStyle(Palette.onSurfaceVariant(scheme))
                        } else if let at = holding.lastPriceAt {
                            Text("As of " + at.formatted(date: .abbreviated, time: .omitted))
                                .font(.wbBodySmall)
                                .foregroundStyle(Palette.onSurfaceVariant(scheme))
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                if needsToggle {
                    // The highlighted chip is `activeCurrency`, not the
                    // requested one: while a rate is in flight the figures
                    // really are in the native currency, and a toggle claiming
                    // otherwise would be the same lie as a CA$-labelled krona.
                    CurrencyToggle(
                        nativeCurrency: nativeCurrency,
                        baseCurrency: viewModel.baseCurrency,
                        viewInBase: activeCurrency.caseInsensitiveCompare(viewModel.baseCurrency) == .orderedSame,
                        onChange: { viewInBase = $0 }
                    )
                }
            }
        }
    }

    /// The per-share move printed under the price, for the range the chips are
    /// on — the same rule the quote screen follows, so a holding and the ticker
    /// it tracks do not disagree about the year.
    /// The dashed rule the line is read against.
    ///
    /// Yesterday's close on 1D — the price the day's move is actually quoted
    /// from, and which the open can sit either side of after an overnight gap.
    /// On every longer range it is the period's own opening close, where
    /// "previous close" means nothing but "where this range started" means a
    /// great deal: once a chart has been panned up by a rally, the line's own
    /// starting height is genuinely hard to locate by eye, and being above or
    /// below it is the whole question the chart is being asked.
    private var chartBaseline: Double? {
        if range.label == "1D" { return detail?.quote?.previousClose ?? bars.first?.close }
        return bars.first?.close
    }

    private var headerMove: RangeMove? {
        if range.label == "1D" { return RangeMove.today(detail?.quote) }
        let move = RangeMove.over(bars: bars, livePrice: detail?.quote?.price, label: range.label)
        return move ?? RangeMove.today(detail?.quote)
    }

    private var priceChart: some View {
        WbCard(padding: 10) {
            ChipRow(items: PortfolioRange.all, title: \.label, selection: $range)
                .padding(.bottom, 12)

            AreaChart(
                points: bars.map { ChartPoint(date: $0.date, value: $0.close) },
                zoneId: detail?.quote?.exchangeTimezone,
                intraday: range.interval.hasSuffix("m") || range.interval.hasSuffix("h"),
                rangeLabel: range.label,
                // Native-currency close against a native-currency price line,
                // whichever way the currency toggle is set — the bars are not
                // converted either, so the rule stays on the same scale.
                baseline: chartBaseline
            )
            .frame(height: 220)
        }
    }

    // MARK: - Overview

    private func overviewCard(_ holding: Holding) -> some View {
        WbCard {
            StatPair(
                // `label`, not the raw case name: the constant spelling prints
                // "SEG FUND" where the rest of the app says "Seg Funds/
                // Variable Annuities".
                leftLabel: "Holding Type",
                leftValue: holding.type.label,
                rightLabel: "Currency",
                rightValue: nativeCurrency
            )

            if let fraction = detail?.fiftyTwoWeekFraction {
                VStack(alignment: .leading, spacing: 6) {
                    Text("52-week High/Low")
                        .font(.wbBodySmall)
                        .foregroundStyle(Palette.onSurfaceVariant(scheme))
                    RangeBar(fraction: fraction)
                    HStack {
                        Text(detail?.quote?.fiftyTwoWeekLow.map { money($0) } ?? "—")
                        Spacer()
                        Text(detail?.quote?.fiftyTwoWeekHigh.map { money($0) } ?? "—")
                    }
                    .font(.wbBodySmall)
                    .foregroundStyle(Palette.onSurfaceVariant(scheme))
                }
                .padding(.top, 14)
            }
        }
    }

    // MARK: - Position

    private func positionCard(_ holding: Holding) -> some View {
        WbCard {
            Text("Position")
                .font(.wbTitleMedium)
                .fontWeight(.bold)
                .foregroundStyle(Palette.onSurface(scheme))
                .padding(.bottom, 12)

            StatPair(
                leftLabel: "Shares",
                leftValue: Money.units(detail?.position.units ?? holding.units),
                rightLabel: "Market Value",
                rightValue: money(detail?.marketValue ?? viewModel.nativeValue(of: holding))
            )
            .padding(.bottom, 14)

            StatPair(
                leftLabel: "Average Cost",
                leftValue: detail?.averageCost.map { money($0) } ?? "—",
                rightLabel: "Portfolio Weight",
                rightValue: Money.percent(detail?.portfolioWeightPercent, decimals: 1)
            )
            .padding(.bottom, 14)

            StatPair(
                leftLabel: "Total Contributions",
                leftValue: detail?.totalContributions.map { money($0) } ?? "—",
                rightLabel: "Dividends Received",
                rightValue: money(detail?.allTimeReceived ?? 0)
            )
            .padding(.bottom, 14)

            WbDivider()

            // Returns get their own emphasis — they're what the screen is for.
            ReturnLine(
                label: "Price Return",
                amount: detail?.priceReturn.map { money($0) },
                percent: detail?.priceReturnPercent
            )
            .padding(.top, 12)

            ReturnLine(
                label: "Total Return (incl. dividends)",
                amount: detail?.totalReturn.map { money($0) },
                percent: detail?.totalReturnPercent
            )
            .padding(.top, 10)

            if needsToggle, fxRate == nil {
                Text("Shown in \(nativeCurrency) — no \(viewModel.baseCurrency) rate has arrived yet.")
                    .font(.wbBodySmall)
                    .foregroundStyle(Brand.divAmber)
                    .padding(.top, 10)
            }

            breakdownLink
        }
    }

    /// The way back down to the per-account split.
    ///
    /// Everything above it is the combined position, which is what the user
    /// owns; this is where it comes apart again. Shown only when the security
    /// really is held in more than one account — a single-account position has
    /// nothing to break down, and the row would be a dead end.
    @ViewBuilder
    private var breakdownLink: some View {
        if let position = detail?.position, position.isSplit {
            WbDivider().padding(.vertical, 12)
            NavigationLink {
                AccountBreakdownView(securityKey: position.key)
            } label: {
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Breakdown by account")
                            .font(.wbBodyLarge)
                            .fontWeight(.semibold)
                            .foregroundStyle(Palette.onSurface(scheme))
                        Text("Held in \(position.accountCount) accounts"
                            + (position.treatments.count > 1 ? " with different tax treatments" : ""))
                            .font(.wbBodySmall)
                            .foregroundStyle(Palette.onSurfaceVariant(scheme))
                    }
                    Spacer(minLength: 8)
                    Image(systemName: "chevron.right")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(Palette.onSurfaceVariant(scheme).opacity(0.6))
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
    }

    // MARK: - Tax

    @ViewBuilder
    private func taxSection(_ holding: Holding) -> some View {
        // One block per account, because tax is the one thing that genuinely
        // differs between two slices of the same fund: the TFSA slice keeps
        // nothing it loses to withholding, the RRSP slice loses nothing at all.
        let slices = detail?.position.slices ?? []
        let rows = slices.isEmpty ? [holding] : slices.map(\.holding)

        if rows.count > 1 {
            // A swipeable deck, one page per account — the same shape as the
            // Upcoming Dividends deck.
            //
            // Stacked, three accounts were three full-height cards each
            // repeating the same paragraph and disclaimer, several screens of
            // near-identical text between the position and the dividends. The
            // free-scrolling row that replaced them stopped wherever the
            // finger let go and left each card its own height, so it read as
            // a strip of clipped cards rather than as pages. Now each swipe
            // lands on one account, every card is the height of the tallest,
            // and the dots say how many there are.
            VStack(alignment: .leading, spacing: 8) {
                // Sizing layer: every card, laid out at the page width but
                // never shown. The stack takes the TALLEST one's height, and
                // the pager drawn over it gets exactly that frame — so cards
                // of different lengths do not make the deck resize as it is
                // swiped.
                ZStack(alignment: .top) {
                    ForEach(rows, id: \.id) { row in
                        taxDeckPage(row, fillsHeight: false)
                            .padding(.horizontal, WbDimens.screenPadding)
                    }
                }
                .opacity(0)
                .allowsHitTesting(false)
                .accessibilityHidden(true)
                .overlay {
                    TabView(selection: $taxPage) {
                        ForEach(Array(rows.enumerated()), id: \.element.id) { index, row in
                            taxDeckPage(row, fillsHeight: true)
                                .frame(maxHeight: .infinity, alignment: .top)
                                .padding(.horizontal, WbDimens.screenPadding)
                                .tag(index)
                        }
                    }
                    .tabViewStyle(.page(indexDisplayMode: .never))
                }

                DeckDots(count: rows.count, current: taxPage)
                    .frame(maxWidth: .infinity)
                    .padding(.top, 2)

                taxFooter
                    .padding(.horizontal, WbDimens.screenPadding)
            }
            // Cancels the screen's own gutter so the deck can run edge to edge,
            // the way the Upcoming Dividends deck does.
            .padding(.horizontal, -WbDimens.screenPadding)
            .onChange(of: rows.count) { count in
                if taxPage >= count { taxPage = max(count - 1, 0) }
            }
        } else {
            // A single-account position renders exactly as it always did.
            ForEach(rows, id: \.id) { row in
                if viewModel.needsTaxTreatmentPrompt(for: row) {
                    taxSetupCard(row)
                } else {
                    taxFiguresCard(row)
                }
            }
        }
    }

    /// One page of the tax deck: the setup question for an account with no
    /// tax treatment yet, otherwise its figures.
    @ViewBuilder
    private func taxDeckPage(_ row: Holding, fillsHeight: Bool) -> some View {
        if viewModel.needsTaxTreatmentPrompt(for: row) {
            taxSetupCard(row, fillsHeight: fillsHeight)
        } else {
            taxFiguresCard(row, showsFooter: false, fillsHeight: fillsHeight, alwaysShowCard: true)
        }
    }

    /// The help link and the disclaimer, which say the same thing whatever
    /// account is on screen.
    private var taxFooter: some View {
        VStack(alignment: .leading, spacing: 8) {
            NavigationLink {
                TaxHelpView()
            } label: {
                Text("New to this? Read how tax works here →")
                    .font(.wbBodySmall)
                    .fontWeight(.medium)
                    .foregroundStyle(Palette.accent(scheme))
            }
            .buttonStyle(.plain)

            Text(TaxRules.disclaimer)
                .font(.system(size: 11))
                .foregroundStyle(Palette.onSurfaceVariant(scheme))
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    /// The forward income attributable to one account's slice.
    ///
    /// The trailing per-unit distribution is a property of the fund, so the
    /// slice's share is simply its own unit count — not a pro-rating of a total
    /// that was itself derived from those units.
    private func annualIncome(for row: Holding) -> Double? {
        guard let perUnit = detail?.trailingAnnualPerUnit else { return nil }
        let income = perUnit * row.units
        return income > 0 ? income : nil
    }

    /// Asked right here, on the screen the answer affects.
    ///
    /// A missing treatment silently disables every tax figure for everything in
    /// the account, so the gap is surfaced rather than left to look like "no
    /// tax applies".
    private func taxSetupCard(_ holding: Holding, fillsHeight: Bool = false) -> some View {
        WbCard {
            Text(accountHeading(holding, prefix: "How is", suffix: "taxed?"))
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(Palette.onSurface(scheme))
                .padding(.bottom, 4)

            Text("Pick one and the app can tell you when tax is quietly being deducted from your dividends. Not sure? See \"How tax works here\" in the Menu.")
                .font(.wbBodySmall)
                .foregroundStyle(Palette.onSurfaceVariant(scheme))
                .fixedSize(horizontal: false, vertical: true)
                .padding(.bottom, 10)

            ForEach(TaxTreatment.allCases) { treatment in
                Button {
                    Task {
                        await viewModel.setAccountTaxTreatment(holding.accountId, treatment)
                        await load()
                    }
                } label: {
                    HStack(alignment: .top, spacing: 10) {
                        Image(systemName: "circle")
                            .font(.system(size: 13))
                            .foregroundStyle(Palette.onSurfaceVariant(scheme))
                            .padding(.top, 2)
                        VStack(alignment: .leading, spacing: 1) {
                            Text(treatment.label)
                                .font(.system(size: 13, weight: .medium))
                                .foregroundStyle(Palette.onSurface(scheme))
                            Text(TaxRules.examples(for: treatment, residency: viewModel.residency))
                                .font(.wbBodySmall)
                                .foregroundStyle(Palette.onSurfaceVariant(scheme))
                        }
                        Spacer(minLength: 0)
                    }
                    .padding(.vertical, 8)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }

            // In the deck, the card stretches to the page so every account's
            // card is the same height.
            if fillsHeight { Spacer(minLength: 0) }
        }
    }

    @ViewBuilder
    private func taxFiguresCard(
        _ holding: Holding,
        showsFooter: Bool = true,
        fillsHeight: Bool = false,
        alwaysShowCard: Bool = false
    ) -> some View {
        let income = annualIncome(for: holding)
        let notes = viewModel.taxNotes(for: holding, annualDividendIncome: income)
        let dividendTax = income.flatMap {
            viewModel.dividendTaxEstimate(for: holding, annualIncome: $0)
        }
        // The unrealized gain on THIS slice, not on the whole position — the
        // other accounts' gains are not taxable here, and two of them may not
        // be taxable anywhere.
        let sliceGain: Double? = {
            guard let cost = holding.costBasis else { return nil }
            let price = detail?.quote?.price ?? viewModel.currentPrice(for: holding)
            return price * holding.units - cost
        }()
        let gainsTax = viewModel.capitalGainsTaxEstimate(
            for: holding,
            unrealizedGain: sliceGain
        )

        // In the deck every account gets a page, even one with nothing to
        // report — an empty slot would leave a dot that swipes to nothing.
        if alwaysShowCard || !notes.isEmpty || dividendTax != nil || gainsTax != nil {
            WbCard {
                Text(accountHeading(holding, prefix: "Tax in", suffix: ""))
                    .font(.wbTitleMedium)
                    .fontWeight(.bold)
                    .foregroundStyle(Palette.onSurface(scheme))
                    .padding(.bottom, 12)

                if let dividendTax, dividendTax.totalTax > 0 {
                    StatPair(
                        leftLabel: "Dividend income (est.)",
                        leftValue: money(dividendTax.grossIncome),
                        rightLabel: "After tax",
                        rightValue: money(dividendTax.afterTax)
                    )
                    .padding(.bottom, 10)

                    StatPair(
                        leftLabel: "Tax on dividends",
                        leftValue: money(dividendTax.totalTax),
                        rightLabel: "Effective rate",
                        rightValue: String(format: "%.1f%%", dividendTax.effectiveRatePct)
                    )

                    if dividendTax.withheld > 0, dividendTax.domesticTax > 0 {
                        Text("\(money(dividendTax.withheld)) withheld abroad, \(money(dividendTax.domesticTax)) payable at home")
                            .font(.wbBodySmall)
                            .foregroundStyle(Palette.onSurfaceVariant(scheme))
                            .padding(.top, 6)
                    }

                    WbDivider().padding(.vertical, 12)
                }

                if let gainsTax, gainsTax > 0 {
                    StatPair(
                        leftLabel: "If you sold today",
                        leftValue: money(sliceGain ?? 0),
                        rightLabel: "Estimated tax",
                        rightValue: money(gainsTax)
                    )
                    WbDivider().padding(.vertical, 12)
                }

                ForEach(Array(notes.enumerated()), id: \.element.id) { index, note in
                    if index > 0 { WbDivider().padding(.vertical, 12) }
                    HStack(alignment: .top, spacing: 10) {
                        Text(note.severity == .warning ? "⚠" : "ℹ")
                            .font(.system(size: 14))
                            .foregroundStyle(note.severity == .warning ? Brand.loss : Palette.onSurfaceVariant(scheme))
                        VStack(alignment: .leading, spacing: 4) {
                            Text(note.title)
                                .font(.system(size: 14, weight: .semibold))
                                .foregroundStyle(Palette.onSurface(scheme))
                            if let cost = note.estimatedAnnualCost, cost > 0 {
                                Text("About \(money(cost)) a year at the current payout")
                                    .font(.system(size: 13, weight: .medium))
                                    .foregroundStyle(Brand.loss)
                            }
                            Text(note.detail)
                                .font(.wbBodySmall)
                                .foregroundStyle(Palette.onSurfaceVariant(scheme))
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        Spacer(minLength: 0)
                    }
                }

                if showsFooter {
                    NavigationLink {
                        TaxHelpView()
                    } label: {
                        Text("New to this? Read how tax works here →")
                            .font(.wbBodySmall)
                            .fontWeight(.medium)
                            .foregroundStyle(Palette.accent(scheme))
                    }
                    .buttonStyle(.plain)
                    .padding(.top, 12)

                    Text(TaxRules.disclaimer)
                        .font(.system(size: 11))
                        .foregroundStyle(Palette.onSurfaceVariant(scheme))
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.top, 8)
                }

                if notes.isEmpty && dividendTax == nil && gainsTax == nil {
                    Text("Nothing is deducted from this account's dividends.")
                        .font(.wbBodySmall)
                        .foregroundStyle(Palette.onSurfaceVariant(scheme))
                }

                // In the deck, the card stretches to the page so every
                // account's card is the same height.
                if fillsHeight { Spacer(minLength: 0) }
            }
        }
    }

    /// "Tax" on a single-account position; "Tax in TFSA" once there is more
    /// than one, so three stacked cards can be told apart.
    private func accountHeading(_ row: Holding, prefix: String, suffix: String) -> String {
        let split = (detail?.position.isSplit ?? false)
        let name = viewModel.account(for: row)?.displayName
        guard split, let name else {
            return suffix.isEmpty ? "Tax" : "How is this account \(suffix)"
        }
        return [prefix, name, suffix]
            .filter { !$0.isEmpty }
            .joined(separator: " ")
    }

    // MARK: - Dividend metrics

    @ViewBuilder
    private func dividendMetricsCard() -> some View {
        if let detail {
            WbCard {
                Text("Dividends")
                    .font(.wbTitleMedium)
                    .fontWeight(.bold)
                    .foregroundStyle(Palette.onSurface(scheme))
                    .padding(.bottom, 12)

                StatPair(
                    leftLabel: "Yield (TTM)",
                    leftValue: Money.percent(detail.yieldTTMPercent),
                    rightLabel: "Yield on Cost",
                    rightValue: Money.percent(detail.yieldOnCostPercent)
                )
                .padding(.bottom, 14)

                GrowthPair(
                    leftLabel: "Div Growth, 1 Year",
                    leftPercent: detail.divGrowth1YPercent,
                    rightLabel: "Div Growth, 5 Years",
                    rightPercent: detail.divGrowth5YPercent,
                    tag: detail.growthTag
                )
                .padding(.bottom, 14)

                GrowthPair(
                    leftLabel: "Div Change, Recent" + (detail.divChangeRecentLabel.map { " (\($0))" } ?? ""),
                    leftPercent: detail.divChangeRecentPercent,
                    rightLabel: "Div Change, vs 1Y Ago",
                    rightPercent: detail.divChangeVs1YPercent,
                    tag: detail.growthTag
                )
                .padding(.bottom, 14)

                StatPair(
                    leftLabel: "Frequency",
                    leftValue: detail.frequencyLabel,
                    // Named for the window it covers. It is the same twelve
                    // months as Yield (TTM) directly above, so the two can be
                    // checked against each other.
                    rightLabel: "Annual / Unit (TTM)",
                    rightValue: detail.trailingAnnualPerUnit
                        .map { Money.perUnit(inActive($0), activeCurrency) } ?? "—"
                )
                .padding(.bottom, 14)

                WbDivider()

                KeyValueRow(
                    label: "All-Time Dividends Received",
                    value: money(detail.allTimeReceived)
                )
                KeyValueRow(
                    label: "Est. Annual Income (next 12m)",
                    value: detail.estimatedAnnualIncome.map { money($0) } ?? "—"
                )
            }
        }
    }

    // MARK: - Dividend history

    private static let dividendHistoryProjectionYears = 5

    /// The fund's own per-unit distribution record, by year, extended with
    /// five years projected from that same record. Sits above the yield chart
    /// because "has this thing ever paid, and is the payment growing" is the
    /// question you ask before "is the yield high or low versus its own
    /// history" — and now "is it expected to keep growing" follows right after.
    ///
    /// The projection reuses `DividendForecast` directly on the fund's own
    /// events (not `detail.projectedFlows`, which is already multiplied by
    /// position size) so these bars stay PER UNIT, matching the actual years
    /// beside them.
    @ViewBuilder
    private func dividendHistorySection() -> some View {
        let actual: [(year: Int, perUnit: Double, isProjected: Bool)] = (detail?.dividendEvents).map {
            HoldingDetailMath.annualDividends($0).map { (year: $0.year, perUnit: inActive($0.perUnit), isProjected: false) }
        } ?? []

        let projected: [(year: Int, perUnit: Double, isProjected: Bool)] = {
            guard let detail, !detail.dividendEvents.isEmpty else { return [] }
            var calendar = Calendar(identifier: .gregorian)
            calendar.timeZone = .current
            let now = Date()
            let thisYear = calendar.component(.year, from: now)
            // `annualDividends` drops the current year while it's still in
            // progress, so the projection picks up right where that left off
            // — starting AT this year, not after it — which keeps the bars one
            // unbroken run of calendar years with no gap at "now".
            let startYear = actual.contains { $0.year == thisYear } ? thisYear + 1 : thisYear
            let endYear = startYear + Self.dividendHistoryProjectionYears - 1
            guard let until = calendar.date(from: DateComponents(year: endYear + 1, month: 1, day: 1)) else {
                return []
            }

            // The current year is never in `actual` (annualDividends drops it
            // while still in progress), so its bar has to be built here: what's
            // already been paid this year, plus a projection for the rest of
            // it. Projecting from `now` alone would miss real payments made
            // earlier in the year, so start from Jan 1 unless something has
            // already been received this year — in which case starting at
            // `now` avoids double-counting that real payment.
            let receivedThisYearTotal = detail.dividendEvents
                .filter { calendar.component(.year, from: $0.0) == thisYear }
                .reduce(0) { $0 + $1.1 }
            let receivedThisYear = receivedThisYearTotal > 0
            var projectionStart = now
            if startYear == thisYear, !receivedThisYear,
               let startOfYear = calendar.date(from: DateComponents(year: thisYear, month: 1, day: 1)) {
                projectionStart = startOfYear
            }

            let payments = DividendForecast.project(
                history: detail.dividendEvents,
                upcoming: detail.upcoming,
                from: projectionStart,
                until: until,
                growthRate: nil
            )
            var byYear: [Int: Double] = [:]
            if startYear == thisYear {
                byYear[thisYear] = receivedThisYearTotal
            }
            for payment in payments {
                let y = calendar.component(.year, from: payment.date)
                guard y >= startYear, y <= endYear else { continue }
                byYear[y, default: 0] += payment.perUnit
            }
            return (startYear...endYear).map { y in
                (year: y, perUnit: inActive(byYear[y] ?? 0), isProjected: true)
            }
        }()

        let yearly = actual + projected

        if !yearly.isEmpty {
            VStack(alignment: .leading, spacing: 0) {
                SectionHeader(
                    "Dividend History",
                    subtitle: "Distributions per unit — first paid \(String(yearly[0].year))"
                )
                .padding(.horizontal, -WbDimens.screenPadding)

                WbCard {
                    DividendHistoryChart(yearly: yearly, currencyCode: activeCurrency)
                }
            }
        }
    }

    // MARK: - Yield history

    @ViewBuilder
    private func yieldHistorySection() -> some View {
        if let detail, detail.yieldHistory.count >= 5 {
            VStack(alignment: .leading, spacing: 0) {
                SectionHeader(
                    "Historical Dividend Yield",
                    subtitle: detail.tenYearAverageYieldPercent
                        .map { "10-year average: \(Money.percent($0))" }
                )
                .padding(.horizontal, -WbDimens.screenPadding)

                WbCard {
                    if let current = detail.yieldTTMPercent,
                       let average = detail.tenYearAverageYieldPercent,
                       average > 0 {
                        let delta = (current - average) / average * 100
                        StatusPill(
                            text: delta < -5
                                ? "Yield \(Money.percent(-delta, decimals: 1)) below its 10-year average"
                                : (delta > 5
                                    ? "Yield \(Money.percent(delta, decimals: 1)) above its 10-year average"
                                    : "Yield in line with its 10-year average"),
                            color: delta < -5 ? Brand.loss : (delta > 5 ? Brand.gain : Palette.onSurfaceVariant(scheme))
                        )
                        .padding(.bottom, 12)
                    }

                    YieldHistoryChart(
                        points: detail.yieldHistory,
                        averagePercent: detail.tenYearAverageYieldPercent
                    )
                    .frame(height: 200)

                    // Indented by the chart's y-axis gutter so the start date
                    // sits under the start of the line, not under the labels.
                    HStack {
                        Text(detail.yieldHistory.first.map {
                            $0.at.formatted(date: .abbreviated, time: .omitted)
                        } ?? "")
                        Spacer()
                        Text(detail.yieldHistory.last.map {
                            $0.at.formatted(date: .abbreviated, time: .omitted)
                        } ?? "")
                    }
                    .font(.wbBodySmall)
                    .foregroundStyle(Palette.onSurfaceVariant(scheme))
                    .padding(.top, 6)
                    .padding(.leading, 46)
                }
            }
        }
    }

    // MARK: - Received vs projected

    @ViewBuilder
    private func incomeChartSection() -> some View {
        if let detail, !(detail.receivedFlows.isEmpty && detail.projectedFlows.isEmpty) {
            VStack(alignment: .leading, spacing: 0) {
                SectionHeader(
                    "Recent and Upcoming Dividends",
                    subtitle: "Received vs projected income from this holding"
                )
                .padding(.horizontal, -WbDimens.screenPadding)

                WbCard {
                    DividendBarChart(
                        bars: buildDividendBars(
                            // Both series converted with the same rate the rest
                            // of the screen uses, so the chart's axis agrees
                            // with the figures above it.
                            received: detail.receivedFlows.map { ($0.0, inActive($0.1)) },
                            projected: detail.projectedFlows.map { ($0.0, inActive($0.1)) },
                            period: chartPeriod,
                            // Reinvestment compounds the unit count at the
                            // holding's own yield. Feeding it the 5-year
                            // dividend CAGR instead — which is nil for plenty
                            // of ETFs — resolves the DRIP series to zero and
                            // draws the forward bars perfectly flat.
                            dripRate: min(max((detail.yieldTTMPercent ?? 0) / 100, 0), 0.25)
                            // No growthRate here (defaults to 0): `projected`
                            // already covers the chart's whole 10-year window
                            // via `detail.projectedFlows`, which is built with
                            // DividendForecast's own measured, damped growth.
                            // This parameter only extrapolates PAST that
                            // window, and passing the 5-year CAGR badge here
                            // too used to double up on an undamped, floored-
                            // at-zero rate — the actual bug behind this
                            // chart's runaway climb.
                        ),
                        period: $chartPeriod,
                        currencyCode: activeCurrency,
                        title: chartPeriod == .year ? "Yearly Income" : "Monthly Income",
                        showDrip: chartPeriod == .year
                    )
                }
            }
        }
    }

    // MARK: - Payout schedule

    @ViewBuilder
    private func payoutScheduleSection(_ holding: Holding) -> some View {
        if let detail, !detail.payouts.isEmpty {
            VStack(alignment: .leading, spacing: 0) {
                SectionHeader(
                    "Dividend Payout Schedule",
                    subtitle: "Dates are payment dates. Upcoming is the declared payment once the fund announces it, a forecast until then; past entries come from Dividends Received"
                )
                .padding(.horizontal, -WbDimens.screenPadding)

                WbCard(padding: 4) {
                    // Make the "why is there no history here" question answer
                    // itself, rather than looking like missing data.
                    if !detail.payouts.contains(where: { !$0.isUpcoming }) {
                        Text("No past payments recorded for this holding. Dividends paid before you added it aren't assumed — log them under Dividends Received if you got them.")
                            .font(.wbBodySmall)
                            .foregroundStyle(Palette.onSurfaceVariant(scheme))
                            .fixedSize(horizontal: false, vertical: true)
                            .padding(.horizontal, WbDimens.cardPadding)
                            .padding(.vertical, 10)
                    }

                    ForEach(Array(detail.payouts.enumerated()), id: \.element.id) { index, row in
                        if index > 0 { WbDivider() }
                        HStack {
                            VStack(alignment: .leading, spacing: 3) {
                                Text(row.date.formatted(date: .abbreviated, time: .omitted))
                                    .font(.wbBodyMedium)
                                    .fontWeight(.medium)
                                    .foregroundStyle(Palette.onSurface(scheme))

                                // Where the calendar published no pay date, the
                                // ex-date stands in — and says so, rather than
                                // being passed off as the day the cash lands.
                                if row.isExDateFallback {
                                    Text("ex-dividend date")
                                        .font(.wbLabelSmall)
                                        .foregroundStyle(Palette.onSurfaceVariant(scheme))
                                } else if let exDate = row.exDate, row.isUpcoming {
                                    Text("ex-div \(exDate.formatted(date: .abbreviated, time: .omitted))")
                                        .font(.wbLabelSmall)
                                        .foregroundStyle(Palette.onSurfaceVariant(scheme))
                                }

                                if row.isUpcoming {
                                    // Says whether the figure is the fund's
                                    // own declaration or the app's forecast —
                                    // "Upcoming" alone read the same for both.
                                    StatusPill(
                                        text: row.isAnnounced ? "Upcoming · Announced" : "Upcoming · Estimated",
                                        color: row.isAnnounced ? Brand.gain : Brand.divAmber
                                    )
                                }
                            }
                            Spacer()
                            VStack(alignment: .trailing, spacing: 2) {
                                Text(money(row.totalForPosition))
                                    .font(.wbBodyMedium)
                                    .fontWeight(.semibold)
                                    .foregroundStyle(Palette.onSurface(scheme))
                                Text("\(Money.perUnit(inActive(row.amountPerUnit), activeCurrency)) / unit")
                                    .font(.wbBodySmall)
                                    .foregroundStyle(Palette.onSurfaceVariant(scheme))
                            }
                        }
                        .padding(.horizontal, WbDimens.cardPadding)
                        .padding(.vertical, 10)
                    }
                }
            }
        }
    }

    // MARK: - Loading

    private func load() async {
        detail = await viewModel.loadHoldingDetail(holdingId)
        await loadBars()
    }

    private func loadBars() async {
        guard let ticker = holding?.ticker else { return }
        bars = await viewModel.historyBars(ticker, range: range.range, interval: range.interval)
    }
}

// MARK: - Currency toggle

/// Two-way pill switch between the user's reporting currency and the currency
/// this holding actually trades in. Only shown when the two differ — a CAD
/// holding for a CAD-default user has nothing to switch between.
struct CurrencyToggle: View {
    @Environment(\.colorScheme) private var scheme

    let nativeCurrency: String
    let baseCurrency: String
    let viewInBase: Bool
    let onChange: (Bool) -> Void

    var body: some View {
        HStack(spacing: 0) {
            chip(baseCurrency, selected: viewInBase) { onChange(true) }
            chip(nativeCurrency, selected: !viewInBase) { onChange(false) }
        }
        .padding(3)
        .background(Palette.surfaceVariant(scheme).opacity(0.6))
        .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
    }

    private func chip(_ label: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label)
                .font(.system(size: 12, weight: .bold))
                .foregroundStyle(selected ? Palette.onAccent(scheme) : Palette.onSurfaceVariant(scheme))
                .padding(.horizontal, 11)
                .padding(.vertical, 6)
                .background(selected ? Palette.accent(scheme) : .clear)
                .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Return line

/// Money and percent on one line, coloured by sign.
struct ReturnLine: View {
    @Environment(\.colorScheme) private var scheme

    let label: String
    let amount: String?
    let percent: Double?

    private var color: Color {
        guard let percent else { return Palette.onSurface(scheme) }
        return percent >= 0 ? Brand.gain : Brand.loss
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label)
                .font(.wbBodySmall)
                .foregroundStyle(Palette.onSurfaceVariant(scheme))
            HStack(alignment: .firstTextBaseline, spacing: 6) {
                Text(amount ?? "—")
                    .font(.system(size: 19, weight: .bold))
                    .foregroundStyle(color)
                if let percent {
                    Text("(\(Money.signedPercent(percent)))")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(color)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

// MARK: - Growth pair

/// Two growth metrics with their qualitative tags underneath.
struct GrowthPair: View {
    let leftLabel: String
    let leftPercent: Double?
    let rightLabel: String
    let rightPercent: Double?
    let tag: (Double?) -> String?

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            GrowthCell(label: leftLabel, percent: leftPercent, tag: tag(leftPercent))
            GrowthCell(label: rightLabel, percent: rightPercent, tag: tag(rightPercent))
        }
    }
}

private struct GrowthCell: View {
    @Environment(\.colorScheme) private var scheme

    let label: String
    let percent: Double?
    let tag: String?

    private var color: Color {
        guard let percent else { return Palette.onSurface(scheme) }
        return percent >= 0 ? Brand.gain : Brand.loss
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label)
                .font(.wbBodySmall)
                .foregroundStyle(Palette.onSurfaceVariant(scheme))
                .lineLimit(2)
            Text(percent == nil ? "—" : Money.signedPercent(percent))
                .font(.system(size: 16, weight: .bold))
                .foregroundStyle(color)
            if let tag {
                Text(tag)
                    .font(.wbLabelSmall)
                    .foregroundStyle(color)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// Page dots under a deck, matching the Upcoming Dividends deck's.
private struct DeckDots: View {
    @Environment(\.colorScheme) private var scheme

    let count: Int
    let current: Int

    var body: some View {
        HStack(spacing: 6) {
            ForEach(0..<count, id: \.self) { index in
                Circle()
                    .fill(index == current
                          ? Palette.accent(scheme)
                          : Palette.onSurfaceVariant(scheme).opacity(0.35))
                    .frame(width: index == current ? 7 : 5, height: index == current ? 7 : 5)
            }
        }
        .animation(.easeOut(duration: 0.15), value: current)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Account \(current + 1) of \(count)")
    }
}
