import SwiftUI

/// Range options for the portfolio value chart — the same set a single stock
/// gets, so switching between the two feels like the same control.
struct PortfolioRange: Hashable, Identifiable {
    let label: String
    let range: String
    let interval: String

    var id: String { label }

    static let all: [PortfolioRange] = [
        PortfolioRange(label: "1D",  range: "1d",  interval: "30m"),
        PortfolioRange(label: "1W",  range: "5d",  interval: "1h"),
        PortfolioRange(label: "1M",  range: "1mo", interval: "1d"),
        PortfolioRange(label: "6M",  range: "6mo", interval: "1d"),
        PortfolioRange(label: "YTD", range: "ytd", interval: "1d"),
        PortfolioRange(label: "1Y",  range: "1y",  interval: "1d"),
        PortfolioRange(label: "5Y",  range: "5y",  interval: "1wk"),
        PortfolioRange(label: "ALL", range: "max", interval: "1mo")
    ]
}

/// "My Portfolio": total value, day change, allocation by holdings with a
/// donut drill-down, the tax-drag warning, and the holdings list grouped by
/// currency.
struct PortfolioView: View {
    @EnvironmentObject private var viewModel: PortfolioViewModel
    @Environment(\.colorScheme) private var scheme

    @State private var valueRange = PortfolioRange.all[2]   // 1M
    @State private var valueSeries = PortfolioSeries()
    @State private var showAllocation = false
    @State private var showAddSheet = false

    var body: some View {
        let dashboard = viewModel.dashboard
        let move = headlineChange(dashboard: dashboard)

        ScrollView {
            LazyVStack(spacing: 12) {
                TotalValueCard(
                    total: dashboard.totalValue,
                    changeAbsolute: move.absolute,
                    changePercent: move.percent,
                    periodLabel: move.label,
                    baseCurrency: dashboard.baseCurrency,
                    unconverted: dashboard.unconvertedCurrencies,
                    onRefresh: { Task { await viewModel.refreshPortfolio() } }
                )
                .padding(.horizontal, WbDimens.screenPadding)
                .padding(.top, 8)

                PortfolioTrendCard(
                    series: valueSeries,
                    selected: $valueRange,
                    baseCurrency: dashboard.baseCurrency
                )
                .padding(.horizontal, WbDimens.screenPadding)

                if !dashboard.allocations.isEmpty {
                    AllocationCard(
                        allocations: dashboard.allocations,
                        onTap: { showAllocation = true }
                    )
                    .padding(.horizontal, WbDimens.screenPadding)
                }

                // Tax drag, above the holdings list.
                //
                // The per-holding notes answer "is this costing me anything?"
                // one position at a time. The action they imply — move it to a
                // sheltered account — is a portfolio decision, so the total is
                // what makes it worth acting on. Shown only when there is a
                // real, avoidable cost; silence is correct the rest of the time.
                if let drag = viewModel.portfolioTaxDrag {
                    TaxDragCard(drag: drag)
                        .padding(.horizontal, WbDimens.screenPadding)
                }

                holdingsSection(dashboard: dashboard)

                // Accounts summarised AFTER the holdings, not before them.
                //
                // This screen answers "what do I own?", and the holdings are
                // that answer; the account totals are a second cut of the same
                // money. Above the list they pushed the holdings below the fold
                // and read as the main event. The dividend-income card that
                // used to sit here as well has gone: it repeated, three figures
                // at a time, what the whole Dividends tab exists to show.
                AccountsCard()
                    .padding(.horizontal, WbDimens.screenPadding)
                    .padding(.top, 6)

                Color.clear.frame(height: 40)
            }
        }
        .wbScreenBackground(scheme)
        .wbNavigationBar("My Portfolio")
        .refreshable {
            await viewModel.refreshPortfolio()
            // The chart is keyed on the range chip, not on the refresh, so
            // without this a pull would update every figure on the screen
            // except the line in the middle of it.
            await loadHistory()
        }
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Button {
                    showAddSheet = true
                } label: {
                    Image(systemName: "plus")
                        .foregroundStyle(Palette.onTopBar(scheme))
                }
                .accessibilityLabel("Add holding")
            }
        }
        .sheet(isPresented: $showAddSheet) {
            NavigationStack { AddTransactionView() }
        }
        .sheet(isPresented: $showAllocation) {
            AllocationSheet(
                allocations: viewModel.dashboard.allocations,
                baseCurrency: viewModel.baseCurrency
            )
            .presentationDetents([.medium, .large])
        }
        // Staleness-gated, not appearance-gated: this runs again on every
        // return to the tab. Pull-to-refresh above still forces a fetch.
        .task { await viewModel.refreshPortfolioIfStale() }
        .task(id: valueRange) { await loadHistory() }
        .task(id: viewModel.holdings.count) { await loadHistory() }
    }

    private func loadHistory() async {
        let fresh = await viewModel.portfolioValueSeries(
            range: valueRange.range,
            interval: valueRange.interval
        )

        // A failed refetch must not erase the chart.
        //
        // Pull-to-refresh invalidates the chart cache and then fires a quote
        // request per ticker; this runs straight afterwards and needs a history
        // request per security, every one of which now has to hit the network.
        // That burst is exactly what the provider's rate limiter exists to
        // stop, and a throttled response comes back as an empty series — which
        // was being assigned over a perfectly good line, so the card fell back
        // to "Not enough price history yet" and stayed there until the range
        // was changed. The gesture that means "show me current data" was the
        // one gesture that reliably emptied the chart.
        //
        // The same rule the news feed already follows: a refresh that returns
        // nothing leaves what is on screen alone. Stale is better than blank,
        // and an empty answer here means the request failed rather than that
        // the portfolio has no history.
        if !fresh.isEmpty || valueSeries.isEmpty {
            valueSeries = fresh
        }
    }

    /// The figure under the total, for the range the user has selected.
    ///
    /// It used to be the day change, full stop, whatever the chips said. Tapping
    /// 1W moved the chart and left the headline still talking about this
    /// afternoon, so the two halves of the card described different periods and
    /// only one of them was labelled.
    ///
    /// 1D keeps using the dashboard's own day change rather than the series:
    /// that one is computed from each holding's previous close, which is the
    /// exact number the exchange would quote, where the series would have to
    /// infer it from whichever bar happened to open the session.
    private func headlineChange(dashboard: DashboardState) -> HeadlineChange {
        guard valueRange.label != "1D" else {
            return HeadlineChange(
                absolute: dashboard.dayChangeAbsolute,
                percent: dashboard.dayChangePercent,
                label: "today"
            )
        }
        guard !valueSeries.isEmpty else {
            return HeadlineChange(
                absolute: dashboard.dayChangeAbsolute,
                percent: dashboard.dayChangePercent,
                label: "today"
            )
        }
        return HeadlineChange(
            absolute: valueSeries.gain,
            percent: valueSeries.gainPercent,
            label: periodLabel
        )
    }

    /// How the period is worded under the total. "over 1M" reads as a span
    /// where the bare chip label reads as a date.
    private var periodLabel: String {
        if valueSeries.coversRequestedRange { return "over \(valueRange.label)" }
        // Three weeks of history under a chip marked 1Y is not a year, and
        // saying "over 1Y" there would be the same overstatement the percentage
        // itself used to make.
        guard let start = valueSeries.startDate else { return "over \(valueRange.label)" }
        return "since " + start.formatted(.dateTime.month(.abbreviated).day())
    }

    // MARK: - Holdings list

    @ViewBuilder
    private func holdingsSection(dashboard: DashboardState) -> some View {
        SectionHeader("My Holdings")

        if viewModel.holdings.isEmpty {
            EmptyNote(text: "No holdings yet — tap + to add one and it will appear here.")
                .padding(.horizontal, WbDimens.screenPadding)
        } else {
            ForEach(currencyGroups()) { group in
                CurrencySectionHeader(currency: group.currency, subtotal: group.subtotal)
                ForEach(group.securities) { security in
                    MergedHoldingRow(rows: security.rows)
                }
            }
        }
    }

    /// Holdings grouped for display: by currency, then by security.
    ///
    /// Built as concrete values rather than inline dictionary groupings so the
    /// list has stable identities to diff against — SwiftUI re-creates every
    /// row when the identity of a group changes, which is visible as a flicker
    /// on every price refresh.
    private func currencyGroups() -> [CurrencyGroup] {
        // Currency first (CAD, USD, GBP …) so positions in different currencies
        // are easy to read separately at a glance, with the reporting currency
        // leading and the rest alphabetical.
        let byCurrency = Dictionary(grouping: viewModel.holdings, by: \.normalizedCurrency)
        let base = viewModel.baseCurrency

        return byCurrency.keys
            .sorted { a, b in
                if a == base { return true }
                if b == base { return false }
                return a < b
            }
            .map { currency in
                let rows = byCurrency[currency] ?? []

                // One entry per SECURITY, not per stored holding row.
                //
                // The same fund bought in a TFSA, an RRSP and an FHSA is three
                // rows in the store — necessarily, since cost basis and tax
                // treatment are per account. But the user owns ONE position in
                // it, and listing the same name three times reads as duplicate
                // data rather than as an account split. So rows are merged on
                // the security and the per-account detail moves inside.
                let bySecurity = Dictionary(grouping: rows, by: \.securityKey)
                let securities = bySecurity.keys
                    .map { key in
                        SecurityGroup(key: key, rows: bySecurity[key] ?? [])
                    }
                    .sorted { a, b in
                        let aValue = a.rows.reduce(0.0) { $0 + viewModel.nativeValue(of: $1) }
                        let bValue = b.rows.reduce(0.0) { $0 + viewModel.nativeValue(of: $1) }
                        return aValue > bValue
                    }

                return CurrencyGroup(
                    currency: currency,
                    subtotal: rows.reduce(0.0) { $0 + viewModel.nativeValue(of: $1) },
                    securities: securities
                )
            }
    }
}

private struct CurrencyGroup: Identifiable {
    let currency: String
    let subtotal: Double
    let securities: [SecurityGroup]
    var id: String { currency }
}

private struct SecurityGroup: Identifiable {
    let key: String
    let rows: [Holding]
    var id: String { key }
}

// MARK: - Total value

/// The figure printed under the total, and the period it belongs to.
private struct HeadlineChange {
    let absolute: Double
    let percent: Double
    /// Worded for the sentence it lands in: "today", "over 1M", "since Sep 8".
    let label: String
}

private struct TotalValueCard: View {
    let total: Double
    let changeAbsolute: Double
    let changePercent: Double
    let periodLabel: String
    let baseCurrency: String
    let unconverted: [String]
    let onRefresh: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text("Total value")
                    .font(.wbLabelLarge)
                    .foregroundStyle(.white.opacity(0.75))
                Spacer()
                // Naming the currency matters once holdings span more than one:
                // without it there is no way to tell whether this number is
                // Canadian or US dollars.
                Text(baseCurrency.uppercased())
                    .font(.system(size: 11, weight: .bold))
                    .foregroundStyle(.white.opacity(0.6))
            }

            Text(Money.format(total, baseCurrency))
                .font(.wbHeadline)
                .foregroundStyle(.white)
                .minimumScaleFactor(0.6)
                .lineLimit(1)

            Button(action: onRefresh) {
                let positive = changeAbsolute >= 0
                // signed() carries the sign itself, so a loss can't come out as
                // "--CA$12.50" the way a hand-prefixed sign could.
                Text("\(Money.signed(changeAbsolute, baseCurrency))  (\(Money.signedPercent(changePercent))) \(periodLabel)  ↻")
                    .font(.wbBodyMedium)
                    .fontWeight(.medium)
                    .foregroundStyle(positive ? Brand.gainOnDark : Brand.lossOnDark)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .background(
                        (positive ? Brand.gain : Brand.loss).opacity(0.18),
                        in: RoundedRectangle(cornerRadius: 8, style: .continuous)
                    )
            }
            .buttonStyle(.plain)

            // The card carries the gain and nothing else.
            //
            // It briefly explained itself underneath — "CA$58,465.01 added in
            // this period, not counted as a gain" — which was there because the
            // figure above it could not be trusted on its own. Now that
            // contributions are read off the unit schedule rather than a
            // transaction log most of this portfolio does not have, the number
            // stands up by itself and the sentence is just a second number to
            // read on a card whose whole job is one.

            // Only shown when a rate is genuinely missing. Those holdings are
            // counted at face value, which overstates or understates the total,
            // and the user deserves to know before acting on the number.
            if !unconverted.isEmpty {
                Text("\(unconverted.joined(separator: ", ")) not converted — no exchange rate yet. Pull to refresh once you're online.")
                    .font(.wbBodySmall)
                    .foregroundStyle(Brand.lossOnDark)
                    .padding(.top, 6)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(20)
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

// MARK: - Trend

/// Portfolio value over time, with the same range selector a single stock gets.
///
/// Separate from the headline card so the line has room to be read: an
/// unlabelled sparkline squeezed under the headline number could not be read
/// against any particular period.
private struct PortfolioTrendCard: View {
    @Environment(\.colorScheme) private var scheme

    let series: PortfolioSeries
    @Binding var selected: PortfolioRange
    let baseCurrency: String

    var body: some View {
        // Thin padding: every point of card inset is width the line does not
        // get, and this chart is the point of the card.
        WbCard(padding: 10) {
            HStack {
                Text("Portfolio value")
                    .font(.wbTitleMedium)
                    .foregroundStyle(Palette.onSurface(scheme))
                Spacer()
                if !series.isEmpty {
                    // The return the HOLDINGS produced, not the distance the
                    // line travelled. A month containing a deposit moves the
                    // line by the size of the deposit, and printing that as a
                    // percentage announced a 55% month on a market that moved
                    // about two.
                    Text("\(Money.signedPercent(series.gainPercent))  \(selected.label)")
                        .font(.wbBodyMedium)
                        .fontWeight(.semibold)
                        .foregroundStyle(Palette.change(series.gain))
                }
            }

            // Range chips above the chart, the way the Android build has them.
            //
            // Underneath, the control that decides what the chart shows sat
            // below the thing it changes, and on a tall card it could be off
            // the bottom of the screen while the chart was in full view.
            ChipRow(items: PortfolioRange.all, title: \.label, selection: $selected)
                .padding(.top, 10)

            // The same chart a holding gets, not a bare sparkline.
            //
            // The line alone showed only shape: no value down the side, no
            // dates along the bottom, so nothing said what the portfolio was
            // worth at any point of it or what stretch of time it covered. The
            // Android build draws this same control here, which also makes the
            // portfolio chart and a holding's chart read as one thing —
            // something the shared range chips already imply.
            // No baseline rule here, unlike the price charts.
            //
            // On a share, the line starting above or below where it began is
            // the whole answer. On a portfolio it is not: the line also steps
            // up every time money is paid in, so a rule at the opening value
            // would invite exactly the reading the percentage above was just
            // fixed to stop making — that the distance travelled is the return.
            AreaChart(
                points: series.chartPoints,
                intraday: selected.interval.hasSuffix("m") || selected.interval.hasSuffix("h"),
                rangeLabel: selected.label,
                // Whole dollars in the scrub readout: cents on a six-figure
                // total are noise, and the axis beside it is already rounded.
                readoutDecimals: 0
            )
            .frame(height: 200)
            .padding(.top, 12)

            // Says what the line actually covers when it is short of its chip.
            //
            // A three-week-old portfolio under a chip marked 1Y draws three
            // weeks, and the dates along the bottom then contradict the chip
            // with nothing to reconcile them.
            if !series.coversRequestedRange, let start = series.startDate {
                Text("History starts " + start.formatted(.dateTime.month(.abbreviated).day().year()) + " — the whole range isn't held yet.")
                    .font(.system(size: 11))
                    .foregroundStyle(Palette.onSurfaceVariant(scheme))
                    .padding(.top, 8)
            }
        }
    }
}

// MARK: - Allocation

private struct AllocationCard: View {
    @Environment(\.colorScheme) private var scheme

    let allocations: [AccountAllocation]
    let onTap: () -> Void

    var body: some View {
        let total = max(allocations.reduce(0) { $0 + $1.value }, 0.01)

        Button(action: onTap) {
            WbCard {
                HStack {
                    Text("Allocation by Holdings")
                        .font(.wbLabelLarge)
                        .foregroundStyle(Palette.accent(scheme))
                    Spacer()
                    Text("View chart →")
                        .font(.wbBodySmall)
                        .foregroundStyle(Palette.accent(scheme).opacity(0.7))
                }
                .padding(.bottom, 10)

                ForEach(Array(allocations.enumerated()), id: \.element.id) { index, allocation in
                    let fraction = allocation.value / total
                    VStack(alignment: .leading, spacing: 4) {
                        HStack {
                            Text(allocation.label)
                                .font(.wbBodyMedium)
                                .foregroundStyle(Palette.onSurface(scheme))
                            Spacer()
                            Text(String(format: "%.1f%%", fraction * 100))
                                .font(.wbBodyMedium)
                                .fontWeight(.medium)
                                .foregroundStyle(Palette.onSurface(scheme))
                        }
                        WbProgressBar(
                            fraction: fraction,
                            fill: Brand.allocationColor(at: index),
                            track: Brand.allocationColor(at: index).opacity(0.15)
                        )
                    }
                    .padding(.vertical, 4)
                }
            }
        }
        .buttonStyle(.plain)
    }
}

/// Drill-down shown when the user taps "Allocation by Holdings".
private struct AllocationSheet: View {
    @Environment(\.colorScheme) private var scheme
    @Environment(\.dismiss) private var dismiss

    let allocations: [AccountAllocation]
    let baseCurrency: String

    var body: some View {
        // Allocation values arrive already converted into the base currency —
        // they have to be, or slices from different currencies wouldn't be
        // comparable and the percentages would be meaningless.
        let total = max(allocations.reduce(0) { $0 + $1.value }, 0.01)
        let slices = allocations.enumerated().map { index, allocation in
            DonutSlice(
                label: allocation.label,
                value: allocation.value,
                color: Brand.allocationColor(at: index)
            )
        }

        NavigationStack {
            ScrollView {
                VStack(spacing: 20) {
                    DonutChart(slices: slices)
                        .padding(.top, 16)

                    VStack(spacing: 10) {
                        ForEach(Array(slices.enumerated()), id: \.element.id) { _, slice in
                            HStack(spacing: 10) {
                                Circle().fill(slice.color).frame(width: 12, height: 12)
                                Text(slice.label)
                                    .font(.wbBodyMedium)
                                    .foregroundStyle(Palette.onSurface(scheme))
                                Spacer()
                                Text(String(format: "%.1f%%", slice.value / total * 100))
                                    .font(.wbBodyMedium)
                                    .fontWeight(.bold)
                                    .foregroundStyle(Palette.onSurface(scheme))
                                Text(Money.format(slice.value, baseCurrency))
                                    .font(.wbBodySmall)
                                    .foregroundStyle(Palette.onSurfaceVariant(scheme))
                            }
                        }
                    }
                    .padding(.horizontal, WbDimens.screenPadding)
                }
            }
            .wbScreenBackground(scheme)
            .navigationTitle("Allocation by Holdings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Close") { dismiss() }
                }
            }
        }
    }
}

// MARK: - Tax drag

private struct TaxDragCard: View {
    @Environment(\.colorScheme) private var scheme
    let drag: TaxDrag

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Losing about \(Money.format(drag.annualCost, drag.currency)) a year to tax")
                .font(.system(size: 15, weight: .bold))
                .foregroundStyle(Brand.loss)

            Text("\(drag.holdingCount) holding\(drag.holdingCount == 1 ? "" : "s") in a registered account pay foreign dividends. The tax withheld abroad can't be claimed back there, because the account owes no domestic tax to credit it against. Open a holding to see which, and whether a different account would avoid it.")
                .font(.wbBodySmall)
                .foregroundStyle(Palette.onSurfaceVariant(scheme))

            Text("Estimate only — see \"Taxes\" in the Menu.")
                .font(.system(size: 11))
                .foregroundStyle(Palette.onSurfaceVariant(scheme))
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .background(Brand.loss.opacity(0.12))
        .clipShape(RoundedRectangle(cornerRadius: WbDimens.cardRadius, style: .continuous))
    }
}

// MARK: - Holdings rows

private struct CurrencySectionHeader: View {
    @Environment(\.colorScheme) private var scheme
    let currency: String
    let subtotal: Double

    var body: some View {
        HStack {
            Text(TickerFlag.forCurrency(currency))
            Text(currency)
                .font(.wbLabelLarge)
                .fontWeight(.bold)
                .foregroundStyle(Palette.accent(scheme))
            Spacer()
            Text(Money.format(subtotal, currency))
                .font(.wbBodyMedium)
                .fontWeight(.semibold)
                .foregroundStyle(Palette.onSurfaceVariant(scheme))
        }
        .padding(.horizontal, WbDimens.screenPadding)
        .padding(.vertical, 8)
        .background(Palette.surfaceVariant(scheme).opacity(0.5))
    }
}

/// One security, with its per-account split disclosed inside rather than
/// spread across several identical-looking rows.
/// One security, priced and sized as the single position the user owns.
///
/// The per-account split used to hang off this row behind a "Show split"
/// toggle. It moved to the holding screen — the same place Wealthsimple puts
/// it — because a list is for scanning what you own, and a three-line
/// expansion inside a scannable list is neither the summary nor the detail.
private struct MergedHoldingRow: View {
    @EnvironmentObject private var viewModel: PortfolioViewModel
    @Environment(\.colorScheme) private var scheme

    let rows: [Holding]

    var body: some View {
        let first = rows[0]
        let totalUnits = rows.reduce(0.0) { $0 + $1.units }
        let totalValue = rows.reduce(0.0) { $0 + viewModel.nativeValue(of: $1) }
        let currency = first.normalizedCurrency

        VStack(spacing: 0) {
            NavigationLink {
                // Opens the largest slice. Every figure on that screen covers the
                // whole position anyway, so which row it is bound to only decides
                // what an edit acts on — and the largest is the likeliest.
                HoldingDetailView(holdingId: principal.id)
            } label: {
                HStack(alignment: .center) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(first.name)
                            .font(.wbBodyLarge)
                            .fontWeight(.semibold)
                            .foregroundStyle(Palette.onSurface(scheme))
                            .lineLimit(1)
                        Text(subtitle(units: totalUnits))
                            .font(.wbBodySmall)
                            .foregroundStyle(Palette.onSurfaceVariant(scheme))
                            .lineLimit(1)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.trailing, 12)

                    Text(Money.format(totalValue, currency))
                        .font(.wbBodyLarge)
                        .fontWeight(.semibold)
                        .foregroundStyle(Palette.onSurface(scheme))

                    Image(systemName: "chevron.right")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(Palette.onSurfaceVariant(scheme).opacity(0.5))
                }
                .padding(.horizontal, 20)
                .padding(.vertical, 14)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            WbDivider()
        }
    }

    private var principal: Holding {
        rows.max { viewModel.nativeValue(of: $0) < viewModel.nativeValue(of: $1) } ?? rows[0]
    }

    private func subtitle(units: Double) -> String {
        var parts: [String] = []
        if let ticker = rows[0].ticker, !ticker.isEmpty { parts.append(ticker) }
        parts.append(rows[0].type.label)
        parts.append("\(Money.units(units)) units")
        if rows.count > 1 {
            parts.append("\(rows.count) accounts")
        }
        return parts.joined(separator: " · ")
    }
}

// MARK: - Accounts

/// The accounts themselves, with the value in each and its tax treatment.
///
/// Reachable from here because an account's treatment is the setting that
/// decides whether any tax figure in the app means anything, and a screen the
/// app never navigates to is a setting nobody can find.
private struct AccountsCard: View {
    @EnvironmentObject private var viewModel: PortfolioViewModel
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        if !viewModel.accounts.isEmpty {
            WbCard(padding: 0) {
                Text("Accounts Summary")
                    .font(.wbLabelLarge)
                    .foregroundStyle(Palette.accent(scheme))
                    .padding(.horizontal, WbDimens.cardPadding)
                    .padding(.top, WbDimens.cardPadding)
                    .padding(.bottom, 6)

                ForEach(Array(viewModel.accounts.enumerated()), id: \.element.id) { index, account in
                    if index > 0 { WbDivider().padding(.leading, WbDimens.cardPadding) }
                    NavigationLink {
                        AccountView(accountId: account.id)
                    } label: {
                        row(account)
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }

    private func row(_ account: Account) -> some View {
        let held = viewModel.holdings.filter { $0.accountId == account.id }
        let value = held.reduce(0.0) { $0 + viewModel.value(of: $1) }

        return HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(account.displayName)
                    .font(.wbBodyMedium)
                    .fontWeight(.medium)
                    .foregroundStyle(Palette.onSurface(scheme))
                Text(account.taxTreatment?.label ?? "Tax type not set")
                    .font(.wbBodySmall)
                    .foregroundStyle(
                        account.taxTreatment == nil
                            ? Brand.divAmber
                            : Palette.onSurfaceVariant(scheme)
                    )
            }
            Spacer(minLength: 8)
            Text(Money.format(value, viewModel.baseCurrency))
                .font(.wbBodyMedium)
                .foregroundStyle(Palette.onSurface(scheme))
            Image(systemName: "chevron.right")
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(Palette.onSurfaceVariant(scheme).opacity(0.6))
        }
        .padding(.horizontal, WbDimens.cardPadding)
        .padding(.vertical, 12)
        .contentShape(Rectangle())
    }
}
