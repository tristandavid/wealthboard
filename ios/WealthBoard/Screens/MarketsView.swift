import SwiftUI

private enum StocksTab: String, CaseIterable, Identifiable {
    case markets = "Markets"
    case hot = "Hot Stocks"
    case holdings = "My Holdings"
    case crypto = "Cryptocurrencies"

    var id: String { rawValue }
    var label: String { rawValue }
}

/// The Markets dashboard: a closure banner and four sub-tabs of price rows.
///
/// The news feed that used to sit under the tables has moved to the News tab.
/// It was a second screen's worth of content stacked below this one, so neither
/// half was reachable without scrolling past the other, and the same articles
/// were also filed under Menu — one feature in three places.
struct MarketsView: View {
    @EnvironmentObject private var viewModel: PortfolioViewModel
    @Environment(\.colorScheme) private var scheme

    @State private var selectedTab: StocksTab = .markets
    @State private var showSearch = false
    @State private var dismissedClosures: Set<String> = []

    var body: some View {
        ScrollView {
            LazyVStack(spacing: 0) {
                closureBanner
                tabPicker
                rows
                Color.clear.frame(height: 30)
            }
        }
        .wbScreenBackground(scheme)
        .wbNavigationBar("Markets")
        .refreshable {
            await viewModel.refreshWatchlistQuotes()
            await viewModel.refreshMarketMovers()
        }
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Button {
                    showSearch = true
                } label: {
                    Image(systemName: "magnifyingglass")
                        .foregroundStyle(Palette.onTopBar(scheme))
                }
                .accessibilityLabel("Search ticker")
            }
        }
        .sheet(isPresented: $showSearch) {
            SymbolSearchSheet()
        }
        .task {
            if viewModel.hotStocks.isEmpty { await viewModel.refreshMarketMovers() }
        }
        .task(id: selectedTab) {
            if selectedTab == .holdings { await viewModel.refreshHoldingQuotes() }
        }
    }

    // MARK: - Closure banner

    @ViewBuilder
    private var closureBanner: some View {
        let today = Date()
        let country = viewModel.homeCountry ?? MarketIndices.deviceCountry()
        let todaysClosures = MarketCalendar.closures(on: today, countryCode: country)

        if let closure = todaysClosures.first,
           !dismissedClosures.contains(closureKey(today, closure)) {
            ClosureBanner(
                text: "Closed today: " + todaysClosures
                    .map { "\($0.market) (\($0.holidayName))" }
                    .joined(separator: " · "),
                onDismiss: { dismiss(closureKey(today, closure)) }
            )
        } else if let next = MarketCalendar.nextClosure(after: today, countryCode: country),
                  shouldWarn(about: next.date),
                  !dismissedClosures.contains(closureKey(next.date, next.closure)) {
            ClosureBanner(
                text: "\(next.closure.market) closed \(next.date.formatted(date: .abbreviated, time: .omitted)) for \(next.closure.holidayName)",
                onDismiss: { dismiss(closureKey(next.date, next.closure)) }
            )
        }
    }

    /// Monday holidays warn three days ahead; everything else, one.
    ///
    /// A Friday-afternoon reader has the weekend between them and a Monday
    /// closure, so a one-day warning would only appear once the market was
    /// already shut.
    private func shouldWarn(about date: Date) -> Bool {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = .current
        let days = calendar.dateComponents([.day], from: Date(), to: date).day ?? 0
        let isMonday = calendar.component(.weekday, from: date) == 2
        let threshold = isMonday ? 3 : 1
        return days >= 1 && days <= threshold
    }

    /// Dismissal is remembered per closure rather than per session.
    ///
    /// The banner is a standing notice, not an event: without persistence it
    /// reappears on every launch for the three days before a Monday holiday,
    /// and a notice that cannot be acknowledged stops being read. Keyed by date
    /// and market so dismissing Thanksgiving says nothing about Christmas.
    private func closureKey(_ date: Date, _ closure: MarketClosure) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd"
        return Prefs.Key.closurePrefix + f.string(from: date) + "_" + closure.countryCode
    }

    private func dismiss(_ key: String) {
        Prefs.set(true, key)
        dismissedClosures.insert(key)
    }

    // MARK: - Sub-tabs

    /// Four tabs sharing the width, not a scrolling strip.
    ///
    /// Scrolled, "Cryptocurrencies" sat half off the edge and nothing said the
    /// row could be scrolled at all, so the fourth tab was effectively hidden.
    /// Four fixed tabs fit a phone if each takes a quarter and the labels are
    /// allowed to shrink slightly on the narrowest devices.
    private var tabPicker: some View {
        HStack(spacing: 0) {
            ForEach(StocksTab.allCases) { tab in
                Button {
                    selectedTab = tab
                } label: {
                    VStack(spacing: 6) {
                        Text(tab.label)
                            .font(.system(size: 12, weight: selectedTab == tab ? .bold : .medium))
                            .lineLimit(1)
                            .minimumScaleFactor(0.75)
                            .foregroundStyle(
                                selectedTab == tab
                                    ? Palette.accent(scheme)
                                    : Palette.onSurfaceVariant(scheme)
                            )
                            .frame(maxWidth: .infinity)
                        Rectangle()
                            .fill(selectedTab == tab ? Palette.accent(scheme) : .clear)
                            .frame(height: 2)
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 8)
        .padding(.top, 8)
        .background(Palette.surface(scheme))
    }

    // MARK: - Rows

    @ViewBuilder
    private var rows: some View {
        StockTableHeader()

        switch selectedTab {
        case .markets:
            ForEach(watchlistRows(crypto: false)) { item in
                quoteRow(for: item)
            }
        case .crypto:
            let cryptoRows = watchlistRows(crypto: true)
            if cryptoRows.isEmpty {
                EmptyNote(text: "No cryptocurrencies on your list yet. Search for one — BTC-USD, ETH-USD — and add it.")
                    .padding(WbDimens.screenPadding)
            } else {
                ForEach(cryptoRows) { item in
                    quoteRow(for: item)
                }
            }
        case .holdings:
            if viewModel.holdings.isEmpty {
                EmptyNote(text: "Nothing held yet. Add a holding from the Portfolio tab and its price appears here.")
                    .padding(WbDimens.screenPadding)
            } else {
                // One row per SECURITY. This is a quote list: the price of
                // XEQT.TO is the same number whether it is held in one account
                // or three, so printing it three times says nothing.
                ForEach(viewModel.positions) { position in
                    holdingRow(position)
                }
            }
        case .hot:
            if viewModel.hotStocks.isEmpty {
                EmptyNote(text: "Loading today's movers…")
                    .padding(WbDimens.screenPadding)
            } else {
                ForEach(viewModel.hotStocks) { mover in
                    moverRow(mover)
                }
            }
        }
    }

    /// Indices lead the Markets tab in registry order (US benchmarks, then the
    /// user's home market, then the other majors); individual stocks follow in
    /// the order they were added.
    private func watchlistRows(crypto: Bool) -> [WatchlistItem] {
        let indexOrder = viewModel.marketIndices.map(\.symbol)

        func isCrypto(_ ticker: String) -> Bool {
            ticker.contains("-USD") || ticker.contains("-BTC") || ticker.contains("-ETH")
        }
        func isFuture(_ ticker: String) -> Bool { ticker.hasSuffix("=F") }

        let filtered = viewModel.watchlist.filter { item in
            if isFuture(item.ticker) { return false }
            return crypto ? isCrypto(item.ticker) : !isCrypto(item.ticker)
        }
        guard !crypto else { return filtered }

        return filtered.sorted { a, b in
            let aIndex = indexOrder.firstIndex(of: a.ticker) ?? Int.max
            let bIndex = indexOrder.firstIndex(of: b.ticker) ?? Int.max
            if aIndex != bIndex { return aIndex < bIndex }
            return a.addedAt < b.addedAt
        }
    }

    @ViewBuilder
    private func quoteRow(for item: WatchlistItem) -> some View {
        let quote = viewModel.quotes[item.ticker]
        let isIndex = MarketIndices.isIndex(item.ticker)

        NavigationLink {
            QuoteDetailView(ticker: item.ticker, watchlistId: item.id)
        } label: {
            StockTableRow(
                title: item.customName
                    ?? (isIndex ? MarketIndices.displayName(for: item.ticker) : item.ticker),
                subtitle: isIndex ? item.ticker : (quote?.name ?? item.cachedName),
                flag: TickerFlag.forTicker(item.ticker, currency: quote?.currency),
                price: quote?.price,
                previousClose: quote?.previousClose,
                sparkline: viewModel.sparklines[item.ticker] ?? [],
                marketTime: quote?.marketTime,
                quoteSymbol: item.ticker,
                exchangeTimezone: quote?.exchangeTimezone,
                extended: viewModel.extendedQuote(for: item.ticker)
            )
        }
        .buttonStyle(.plain)
        .contextMenu {
            Button("Remove from list", role: .destructive) {
                Task { await viewModel.removeFromWatchlist(item.id) }
            }
        }
    }

    @ViewBuilder
    private func holdingRow(_ position: SecurityPosition) -> some View {
        let quote = position.ticker.flatMap { viewModel.quotes[$0] }

        NavigationLink {
            HoldingDetailView(holdingId: position.principal.id)
        } label: {
            StockTableRow(
                title: position.ticker ?? position.name,
                // The combined unit count, and how many accounts it is spread
                // over when that is more than one.
                subtitle: position.isSplit
                    ? "\(position.name) · \(Money.units(position.units)) units · \(position.accountCount) accounts"
                    : "\(position.name) · \(Money.units(position.units)) units",
                flag: TickerFlag.forTicker(
                    position.ticker ?? "",
                    currency: position.currency
                ),
                price: quote?.price ?? position.price,
                previousClose: quote?.previousClose,
                sparkline: position.ticker.flatMap { viewModel.sparklines[$0] } ?? [],
                marketTime: quote?.marketTime,
                quoteSymbol: position.ticker,
                exchangeTimezone: quote?.exchangeTimezone,
                // Held rows get the extended line too. Hard-coded nil here was
                // why an after-hours print showed on a watchlist row and not on
                // the same security under My Holdings.
                extended: viewModel.extendedQuote(for: position.ticker)
            )
        }
        .buttonStyle(.plain)
    }

    @ViewBuilder
    private func moverRow(_ mover: MarketMover) -> some View {
        NavigationLink {
            QuoteDetailView(ticker: mover.ticker, watchlistId: nil)
        } label: {
            StockTableRow(
                title: mover.ticker,
                subtitle: mover.name,
                flag: TickerFlag.forTicker(mover.ticker, exchange: mover.exchange),
                price: mover.price,
                previousClose: mover.previousClose,
                sparkline: viewModel.moverSparklines[mover.ticker] ?? [],
                marketTime: mover.marketTime,
                quoteSymbol: mover.ticker,
                exchangeTimezone: mover.exchangeTimezone,
                // Hard-coded nil here was the same oversight the holdings rows
                // had: the list most likely to be read after the close was the
                // one that never mentioned the close had happened.
                extended: viewModel.extendedQuote(for: mover.ticker)
            )
        }
        .buttonStyle(.plain)
    }

}

// MARK: - Table chrome

private struct StockTableHeader: View {
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text("Market / Name")
                    .frame(maxWidth: .infinity, alignment: .leading)
                Text("Chart")
                    .frame(width: 44)
                Text("Last Price")
                    .frame(width: 92, alignment: .trailing)
                Text("Change")
                    .frame(width: 74, alignment: .trailing)
            }
            .font(.system(size: 11, weight: .medium))
            .foregroundStyle(Palette.onSurfaceVariant(scheme))
            .padding(.horizontal, WbDimens.screenPadding)
            .padding(.vertical, 6)
            .background(Palette.surfaceVariant(scheme).opacity(0.5))

            WbDivider()
        }
    }
}

private struct StockTableRow: View {
    @Environment(\.colorScheme) private var scheme

    let title: String
    let subtitle: String?
    let flag: String
    let price: Double?
    let previousClose: Double?
    let sparkline: [Double]
    let marketTime: Date?
    let quoteSymbol: String?
    let exchangeTimezone: String?
    let extended: ExtendedQuote?

    var body: some View {
        let change: Double? = {
            guard let price, let previousClose else { return nil }
            return price - previousClose
        }()
        let changePct: Double? = {
            guard let change, let previousClose, previousClose != 0 else { return nil }
            return change / previousClose * 100
        }()
        let tint = Palette.change(change ?? 0)

        VStack(spacing: 4) {
            HStack(alignment: .center, spacing: 6) {
                VStack(alignment: .leading, spacing: 1) {
                    HStack(spacing: 4) {
                        if !flag.isEmpty { Text(flag).font(.system(size: 14)) }
                        Text(title)
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundStyle(Palette.onSurface(scheme))
                            .lineLimit(1)
                    }
                    if let subtitle, !subtitle.isEmpty {
                        Text(subtitle)
                            .font(.system(size: 11))
                            .foregroundStyle(Palette.onSurfaceVariant(scheme))
                            .lineLimit(1)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                MiniSparkline(data: sparkline, color: tint)
                    .frame(width: 44, height: 28)

                // Price, and the time that price was last struck. Without the
                // timestamp a stale close is indistinguishable from a live quote.
                VStack(alignment: .trailing, spacing: 1) {
                    if let price {
                        Text(Money.plain(price))
                            .font(.system(size: 13, weight: .medium))
                            .foregroundStyle(Palette.onSurface(scheme))
                            .lineLimit(1)
                            .minimumScaleFactor(0.7)
                    } else {
                        ProgressView().controlSize(.mini)
                    }
                    if let marketTime, price != nil {
                        // Exchange-local, and named: see `TradeTime`.
                        Text(TradeTime.label(
                            at: marketTime,
                            providerZone: exchangeTimezone,
                            ticker: quoteSymbol
                        ))
                        .font(.system(size: 9))
                        .foregroundStyle(Palette.onSurfaceVariant(scheme))
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                    }
                }
                .frame(width: 92, alignment: .trailing)

                VStack(alignment: .trailing, spacing: 1) {
                    if let change, let changePct {
                        Text(Money.signed(change, nil, decimals: 2)
                            .replacingOccurrences(of: "$", with: ""))
                            .font(.system(size: 12))
                            .foregroundStyle(tint)
                            .lineLimit(1)
                        Text(Money.signedPercent(changePct))
                            .font(.system(size: 11))
                            .foregroundStyle(tint)
                            .lineLimit(1)
                    } else {
                        Text("—")
                            .font(.system(size: 12))
                            .foregroundStyle(Palette.onSurfaceVariant(scheme))
                    }
                }
                .frame(width: 74, alignment: .trailing)
            }

            // The secondary line: the tracking future while the cash market is
            // shut, which is the number people actually want at 10pm.
            if let extended {
                HStack(spacing: 6) {
                    Text(extended.label)
                        .font(.system(size: 10, weight: .semibold))
                        .foregroundStyle(Palette.onSurfaceVariant(scheme))
                    Text(Money.plain(extended.price))
                        .font(.system(size: 10, weight: .medium))
                        .foregroundStyle(Palette.onSurface(scheme))
                    Text("\(Money.signed(extended.change, nil).replacingOccurrences(of: "$", with: "")) (\(Money.signedPercent(extended.changePercent)))")
                        .font(.system(size: 10))
                        .foregroundStyle(Palette.change(extended.change))
                    Text("· " + TradeTime.label(
                        at: extended.at,
                        providerZone: extended.zoneId,
                        ticker: extended.symbol
                    ))
                    .font(.system(size: 9))
                    .foregroundStyle(Palette.onSurfaceVariant(scheme))
                    Spacer(minLength: 0)
                }
                .lineLimit(1)
            }

            WbDivider()
        }
        .padding(.horizontal, WbDimens.screenPadding)
        .padding(.vertical, 9)
        .contentShape(Rectangle())
    }
}

// MARK: - Closure banner

private struct ClosureBanner: View {
    @Environment(\.colorScheme) private var scheme
    let text: String
    let onDismiss: () -> Void

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: "calendar.badge.exclamationmark")
                .foregroundStyle(Brand.divAmber)
            Text(text)
                .font(.wbBodySmall)
                .foregroundStyle(Palette.onSurface(scheme))
            Spacer(minLength: 8)
            Button(action: onDismiss) {
                Image(systemName: "xmark")
                    .font(.system(size: 11, weight: .bold))
                    .foregroundStyle(Palette.onSurfaceVariant(scheme))
            }
            .buttonStyle(.plain)
        }
        .padding(12)
        .background(Brand.divAmber.opacity(0.12))
        .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
        .padding(.horizontal, WbDimens.screenPadding)
        .padding(.vertical, 8)
    }
}

// MARK: - Search sheet

struct SymbolSearchSheet: View {
    @EnvironmentObject private var viewModel: PortfolioViewModel
    @Environment(\.colorScheme) private var scheme
    @Environment(\.dismiss) private var dismiss

    @State private var query = ""
    @State private var added: String?

    var body: some View {
        NavigationStack {
            List {
                if viewModel.searchResults.isEmpty, !query.isEmpty {
                    Text("No matches. Try the full symbol, including its exchange suffix — VEQT.TO, JFC.PS.")
                        .font(.wbBodySmall)
                        .foregroundStyle(Palette.onSurfaceVariant(scheme))
                }
                // Two targets in one row: the row opens the ticker, the button
                // on the right adds it to the watchlist.
                //
                // The whole row used to be the add button, so a search was a
                // one-way door — the only thing you could do with a result was
                // commit it to your list, and the obvious question ("what IS
                // this?") had no answer anywhere on the screen. Looking before
                // adding is the normal order.
                ForEach(viewModel.searchResults) { result in
                    HStack(spacing: 10) {
                        NavigationLink {
                            QuoteDetailView(ticker: result.symbol, watchlistId: nil)
                        } label: {
                            resultLabel(result)
                        }

                        Button {
                            Task {
                                _ = await viewModel.addToWatchlist(result.symbol)
                                added = result.symbol
                            }
                        } label: {
                            Image(systemName: added == result.symbol ? "checkmark" : "plus")
                                .font(.system(size: 16, weight: .semibold))
                                .foregroundStyle(Palette.accent(scheme))
                                .frame(width: 44, height: 44)
                                .contentShape(Rectangle())
                        }
                        // `.borderless` rather than `.plain`: inside a List row
                        // that already holds a NavigationLink, a plain button
                        // hands its taps to the row and the "+" stops working.
                        .buttonStyle(.borderless)
                        .accessibilityLabel(added == result.symbol
                                            ? "\(result.symbol) added to watchlist"
                                            : "Add \(result.symbol) to watchlist")
                    }
                }
            }
            .listStyle(.plain)
            .searchable(text: $query, prompt: "Search a ticker or company")
            .onChange(of: query) { newValue in viewModel.search(newValue) }
            .navigationTitle("Add a ticker")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") {
                        viewModel.clearSearch()
                        dismiss()
                    }
                }
            }
        }
    }

    private func resultLabel(_ result: SymbolSearchResult) -> some View {
        HStack(spacing: 10) {
            Text(TickerFlag.forTicker(result.symbol, exchange: result.exchange))
            VStack(alignment: .leading, spacing: 2) {
                Text(result.symbol)
                    .font(.wbBodyMedium)
                    .fontWeight(.semibold)
                    .foregroundStyle(Palette.onSurface(scheme))
                if let name = result.name {
                    Text(name)
                        .font(.wbBodySmall)
                        .foregroundStyle(Palette.onSurfaceVariant(scheme))
                        .lineLimit(1)
                }
            }
            Spacer(minLength: 0)
        }
        .contentShape(Rectangle())
    }
}
