import SwiftUI

/// A watched ticker's own screen: price, chart, the day's stats, and the option
/// to rename or stop watching it.
struct QuoteDetailView: View {
    @EnvironmentObject private var viewModel: PortfolioViewModel
    @Environment(\.colorScheme) private var scheme
    @Environment(\.dismiss) private var dismiss

    let ticker: String
    /// nil when the row came from Hot Stocks rather than the watchlist — the
    /// rename and remove actions only make sense for something being watched.
    let watchlistId: UUID?

    @State private var quote: Quote?
    @State private var extended: ExtendedQuote?
    @State private var bars: [HistoryBar] = []
    @State private var range = PortfolioRange.all[2]   // 1M
    @State private var showRename = false
    @State private var renameText = ""

    private var isIndex: Bool { MarketIndices.isIndex(ticker) }

    /// The move printed under the price, for the range the chips are on.
    ///
    /// 1D keeps the quote's own day change — the exchange's number — and every
    /// other range is measured across the bars that are actually drawn, so the
    /// figure and the line below it describe the same stretch of time.
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
        if range.label == "1D" { return quote?.previousClose ?? bars.first?.close }
        return bars.first?.close
    }

    private var headerMove: RangeMove? {
        if range.label == "1D" { return RangeMove.today(quote) }
        let move = RangeMove.over(bars: bars, livePrice: quote?.price, label: range.label)
        // Before the bars for a newly selected range land there is nothing to
        // measure, and a blank where a figure was is worse than the day change
        // for the half-second it takes.
        return move ?? RangeMove.today(quote)
    }

    private var title: String {
        watchlistId
            .flatMap { id in viewModel.watchlist.first { $0.id == id }?.customName }
            ?? (isIndex ? MarketIndices.displayName(for: ticker) : ticker)
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 12) {
                header
                chartCard
                statsCard
                if watchlistId == nil {
                    addToWatchlistButton
                }
                Color.clear.frame(height: 30)
            }
            .padding(.horizontal, WbDimens.screenPadding)
            .padding(.top, 8)
        }
        .wbScreenBackground(scheme)
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if let watchlistId {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Menu {
                        Button("Rename") {
                            renameText = title
                            showRename = true
                        }
                        Button("Remove from list", role: .destructive) {
                            Task {
                                await viewModel.removeFromWatchlist(watchlistId)
                                dismiss()
                            }
                        }
                    } label: {
                        Image(systemName: "ellipsis.circle")
                    }
                }
            }
        }
        .alert("Rename", isPresented: $showRename) {
            TextField("Display name", text: $renameText)
            Button("Save") {
                guard let watchlistId else { return }
                Task { await viewModel.renameWatchlistItem(watchlistId, name: renameText.nilIfEmpty) }
            }
            Button("Use the real name", role: .destructive) {
                guard let watchlistId else { return }
                Task { await viewModel.renameWatchlistItem(watchlistId, name: nil) }
            }
            Button("Cancel", role: .cancel) {}
        }
        .task(id: ticker) { await load() }
        .task(id: range) { await loadBars() }
        .refreshable { await load() }
    }

    // MARK: - Sections

    private var header: some View {
        WbCard {
            HStack(spacing: 12) {
                // No avatar for an index: there is no company behind ^GSPC, so
                // the fallback drew a circled "G" that looked like a logo that
                // had failed to load. The flag beside the symbol already says
                // which market this is.
                if !isIndex {
                    TickerLogo(
                        logoURL: quote?.logoURL ?? QuoteClient.logoURL(ticker: ticker, name: quote?.name),
                        label: ticker,
                        size: 44
                    )
                }
                VStack(alignment: .leading, spacing: 2) {
                    Text(quote?.name ?? ticker)
                        .font(.wbTitleMedium)
                        .foregroundStyle(Palette.onSurface(scheme))
                        .lineLimit(2)
                    HStack(spacing: 4) {
                        let flag = TickerFlag.forTicker(ticker, currency: quote?.currency)
                        if !flag.isEmpty { Text(flag) }
                        Text(ticker)
                            .font(.wbBodySmall)
                            .foregroundStyle(Palette.onSurfaceVariant(scheme))
                    }
                }
                Spacer(minLength: 0)
            }
            .padding(.bottom, 12)

            if let quote {
                Text(Money.plain(quote.price))
                    .font(.wbHeadline)
                    .foregroundStyle(Palette.onSurface(scheme))

                if let move = headerMove {
                    let amount = Money.signed(move.absolute, nil).replacingOccurrences(of: "$", with: "")
                    Text("\(amount) (\(Money.signedPercent(move.percent)))  \(move.label)")
                        .font(.wbBodyMedium)
                        .fontWeight(.medium)
                        .foregroundStyle(Palette.change(move.absolute))
                }

                if let marketTime = quote.marketTime {
                    Text("As of " + TradeTime.label(
                        at: marketTime,
                        providerZone: quote.exchangeTimezone,
                        ticker: ticker
                    ))
                    .font(.wbBodySmall)
                    .foregroundStyle(Palette.onSurfaceVariant(scheme))
                    .padding(.top, 2)
                }
            } else {
                ProgressView()
            }

            if let extended {
                WbDivider().padding(.vertical, 10)
                HStack(spacing: 8) {
                    Text(extended.label)
                        .font(.wbBodySmall)
                        .fontWeight(.semibold)
                        .foregroundStyle(Palette.onSurfaceVariant(scheme))
                    Text(Money.plain(extended.price))
                        .font(.wbBodyMedium)
                        .foregroundStyle(Palette.onSurface(scheme))
                    Text("\(Money.signed(extended.change, nil).replacingOccurrences(of: "$", with: "")) (\(Money.signedPercent(extended.changePercent)))")
                        .font(.wbBodySmall)
                        .foregroundStyle(Palette.change(extended.change))
                    Spacer(minLength: 0)
                }
            }
        }
    }

    /// Thin card padding on purpose: a price chart is read by its shape, and
    /// every point of inset is width the line does not get.
    private var chartCard: some View {
        WbCard(padding: 10) {
            // Chips first, chart under them — the control that decides what
            // the chart shows sits above the thing it changes, the way the
            // Android build lays this out.
            ChipRow(items: PortfolioRange.all, title: \.label, selection: $range)
                .padding(.bottom, 12)

            AreaChart(
                points: bars.map { ChartPoint(date: $0.date, value: $0.close) },
                zoneId: quote?.exchangeTimezone,
                intraday: range.interval.hasSuffix("m") || range.interval.hasSuffix("h"),
                rangeLabel: range.label,
                baseline: chartBaseline
            )
            .frame(height: 250)
        }
    }

    /// Key stats as one labelled column, the same set and the same order as the
    /// Android build.
    ///
    /// Two-up pairs read as a grid of unrelated numbers and wrapped badly once
    /// an index printed five digits and a separator. One row per figure is
    /// longer but scannable, and it leaves room for the fields that were
    /// missing here: the 52-week high and low as numbers, and the currency.
    @ViewBuilder
    private var statsCard: some View {
        if let quote {
            WbCard {
                Text("Key stats")
                    .font(.wbTitleMedium)
                    .foregroundStyle(Palette.onSurface(scheme))
                    .padding(.bottom, 4)

                // Grouped: a ViewBuilder takes ten children, and these are nine
                // of them on their own.
                Group {
                    statRow("Currency", quote.currency)
                    statRow("Prev close", quote.previousClose.map { Money.plain($0) })
                    statRow("Open", quote.open.map { Money.plain($0) })
                    statRow("Day high", quote.dayHigh.map { Money.plain($0) })
                    statRow("Day low", quote.dayLow.map { Money.plain($0) })
                    statRow("52-wk high", quote.fiftyTwoWeekHigh.map { Money.plain($0) })
                    statRow("52-wk low", quote.fiftyTwoWeekLow.map { Money.plain($0) })
                    statRow("Volume", quote.volume.map { Money.volume($0) })
                    statRow("Avg vol (3mo)", quote.avgVolume3Month.map { Money.volume($0) }, last: true)
                }

                // Where today's price sits in the year's band — the one thing
                // the numbers above cannot show at a glance.
                if let low = quote.fiftyTwoWeekLow, let high = quote.fiftyTwoWeekHigh, high > low {
                    WbDivider().padding(.vertical, 12)
                    Text("52-week range")
                        .font(.wbBodySmall)
                        .foregroundStyle(Palette.onSurfaceVariant(scheme))
                        .padding(.bottom, 6)
                    RangeBar(fraction: (quote.price - low) / (high - low))
                    HStack {
                        Text(Money.plain(low))
                        Spacer()
                        Text(Money.plain(high))
                    }
                    .font(.system(size: 10))
                    .foregroundStyle(Palette.onSurfaceVariant(scheme))
                    .padding(.top, 4)
                }
            }
        }
    }

    /// One stat line. An absent figure prints "—" rather than vanishing, so the
    /// list keeps the same shape for an index as for a stock.
    @ViewBuilder
    private func statRow(_ label: String, _ value: String?, last: Bool = false) -> some View {
        HStack {
            Text(label)
                .font(.system(size: 14))
                .foregroundStyle(Palette.onSurfaceVariant(scheme))
                .lineLimit(1)
            Spacer(minLength: 8)
            Text(value ?? "—")
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(Palette.onSurface(scheme))
                .lineLimit(1)
        }
        .padding(.vertical, 8)

        if !last { WbDivider() }
    }

    private var addToWatchlistButton: some View {
        Button {
            Task { _ = await viewModel.addToWatchlist(ticker) }
        } label: {
            Text("Add to my list")
                .font(.wbBodyLarge)
                .fontWeight(.semibold)
                .foregroundStyle(Palette.accent(scheme))
                .frame(maxWidth: .infinity)
                .frame(height: 46)
                .overlay(Capsule().stroke(Palette.accent(scheme), lineWidth: 1.5))
        }
        .buttonStyle(.plain)
    }

    // MARK: - Loading

    private func load() async {
        quote = await viewModel.quote(forTicker: ticker)
        await loadBars()
        if QuoteClient.hasFuture(ticker) || !isIndex {
            extended = await QuoteClient.shared.fetchExtended(ticker: ticker)
        }
    }

    private func loadBars() async {
        bars = await viewModel.historyBars(ticker, range: range.range, interval: range.interval)
    }
}
