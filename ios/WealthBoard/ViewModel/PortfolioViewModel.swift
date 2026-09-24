import Foundation
import SwiftUI

// MARK: - Derived state
//
// Ported from `ui/PortfolioViewModel.kt`.

/// One slice of the allocation chart.
///
/// One slice per SECURITY, not per holding row. The same fund held in three
/// accounts is three `Holding` rows — it has to be, because cost basis and tax
/// treatment differ per account — but it is still one position in the
/// portfolio, and slicing the pie per row draws "XEQT.TO 64%, XEQT.TO 22%,
/// XEQT.TO 13%", which says nothing about diversification and looks broken.
struct AccountAllocation: Identifiable, Hashable {
    let label: String
    let value: Double
    let holdings: [Holding]
    let accountName: String

    var id: String { label }
}

struct DashboardState: Equatable {
    var totalValue: Double = 0
    var dayChangeAbsolute: Double = 0
    var dayChangePercent: Double = 0
    var allocations: [AccountAllocation] = []
    var dividendsThisMonth: Double = 0
    var dividendsThisYear: Double = 0
    var dividendsAllTime: Double = 0
    var baseCurrency: String = "CAD"
    /// Currencies held that we have no rate for yet. Their holdings are in the
    /// total at face value, so the figure is wrong until a rate lands — the
    /// Total Value card says so rather than quietly overstating things.
    var unconvertedCurrencies: [String] = []
}

/// A holding's next expected distribution, with the record it was derived from.
struct UpcomingDividendRow: Identifiable, Hashable {
    /// The largest slice — what the card opens, and what names the payment.
    let holding: Holding
    let info: UpcomingDividend
    var history: [PerUnitPayment] = []

    /// Every account's holding row for this security, largest first. A payment
    /// lands in each of them, so the card shows one combined amount and breaks
    /// it back down underneath.
    var rows: [Holding] = []

    /// Units the payment is actually paid on, across every account.
    var units: Double {
        rows.isEmpty ? holding.units : rows.reduce(0) { $0 + $1.units }
    }

    var isSplit: Bool { rows.count > 1 }

    /// Keyed on the security, not the row: one card per fund.
    var id: String { holding.securityKey }
}

/// A (date, per-unit amount) pair. A named type rather than a tuple because
/// tuples aren't `Hashable` and these live inside `Identifiable` state.
struct PerUnitPayment: Hashable {
    let date: Date
    let perUnit: Double
}

/// Progress of a long-running import or sync.
/// Progress of a multi-ticker fetch, for a view that wants to show a spinner
/// with a count rather than an indeterminate one.
enum FetchProgress: Equatable {
    case idle
    case running(current: Int, total: Int, ticker: String)
    case done(imported: Int, skipped: Int)
    case failed(String)
}

/// Portfolio-wide withholding that a different account would avoid.
///
/// This is the number worth surfacing, because the per-holding notes answer "is
/// this costing me anything?" one holding at a time, while the action they
/// imply — move it to a sheltered account — is a portfolio decision. Seeing
/// "$420 a year across 3 holdings" is what makes it worth doing.
struct TaxDrag: Equatable {
    let annualCost: Double
    let holdingCount: Int
    let currency: String
}

// MARK: - View model

@MainActor
final class PortfolioViewModel: ObservableObject {

    private let repository = PortfolioRepository()
    private let fx = FxRates.shared

    // MARK: Stored portfolio

    @Published private(set) var accounts: [Account] = []
    @Published private(set) var holdings: [Holding] = []

    /// Alert rules, mirrored into the view model so the Alerts screen and the
    /// Menu badge update the moment one is added or deleted.
    @Published private(set) var alerts: [PriceAlert] = []
    @Published private(set) var dividends: [DividendPayment] = []
    @Published private(set) var transactions: [PortfolioTransaction] = []
    @Published private(set) var watchlist: [WatchlistItem] = []

    // MARK: Live market data

    @Published private(set) var quotes: [String: Quote] = [:]
    @Published private(set) var sparklines: [String: [Double]] = [:]
    @Published private(set) var extendedQuotes: [String: ExtendedQuote] = [:]
    @Published private(set) var hotStocks: [MarketMover] = []
    @Published private(set) var moverSparklines: [String: [Double]] = [:]
    @Published private(set) var marketNews: [NewsItem] = []
    @Published private(set) var dividendNews: [NewsItem] = []
    /// What the news screen should say when it has nothing to draw.
    ///
    /// An empty feed and an unreachable one look identical to a reader, and
    /// telling them apart is the difference between "pull to refresh" — which
    /// does nothing when the device is offline — and an honest explanation.
    @Published private(set) var marketNewsState: NewsLoadState = .idle
    @Published private(set) var dividendNewsState: NewsLoadState = .idle
    @Published private(set) var upcomingDividends: [UpcomingDividendRow] = []
    @Published private(set) var searchResults: [SymbolSearchResult] = []
    @Published private(set) var isRefreshing = false
    @Published private(set) var upcomingProgress: FetchProgress = .idle

    /// Bumped whenever an FX rate lands, so views that convert on the fly
    /// recompute. The rate cache is deliberately synchronous and lives outside
    /// the published state, so this is what tells SwiftUI it moved.
    @Published private(set) var fxTick = 0

    // MARK: Settings

    @Published var baseCurrency: String {
        didSet {
            Prefs.set(baseCurrency, Prefs.Key.baseCurrency)
            repository.baseCurrency = baseCurrency
            Task { await refreshRates() }
        }
    }

    @Published var themeMode: ThemeMode {
        didSet { Prefs.set(themeMode.rawValue, Prefs.Key.themeMode) }
    }

    /// nil means "follow the device", which is what the Markets tab uses to
    /// decide which home index to pin after the three US benchmarks.
    @Published var homeCountry: String? {
        didSet { Prefs.set(homeCountry, Prefs.Key.homeCountry) }
    }

    @Published var dividendGoal: Double {
        didSet { Prefs.set(dividendGoal, Prefs.Key.dividendGoal) }
    }

    /// Where the user files. Seeded from the device region on first run rather
    /// than left blank — see `init` — and labelled as such wherever it is shown.
    @Published var residency: Residency {
        didSet {
            Prefs.set(residency.rawValue, Prefs.Key.residency)
            // Any assignment after init is somebody choosing, so the value stops
            // being the app's guess. `resetResidencyToDevice()` puts the flag
            // back on purpose, after this has run.
            if isResidencyAuto { setResidencyAuto(false) }
        }
    }

    /// True while `residency` is still the device-derived guess. The Taxes
    /// screen says so while it is, which is the whole reason guessing is
    /// acceptable: a wrong guess that announces itself invites correction,
    /// where a blank tax screen just looks like "nothing to report".
    @Published private(set) var isResidencyAuto: Bool = false

    @Published var taxRates: TaxRules.UserRates {
        didSet {
            Prefs.set(taxRates.marginalPct ?? -1, Prefs.Key.marginalRate)
            Prefs.set(taxRates.preferentialPct ?? -1, Prefs.Key.preferredRate)
        }
    }

    @Published var dripEnabled: Bool {
        didSet { Prefs.set(dripEnabled, Prefs.Key.dripEnabled) }
    }

    private var searchTask: Task<Void, Never>?

    var isHomeCountryAuto: Bool { Prefs.string(Prefs.Key.homeCountry) == nil }

    // MARK: - Init

    init() {
        let storedCurrency = Prefs.string(Prefs.Key.baseCurrency) ?? Self.deviceCurrency()
        baseCurrency = storedCurrency
        themeMode = ThemeMode.from(Prefs.string(Prefs.Key.themeMode))
        homeCountry = Prefs.string(Prefs.Key.homeCountry)
        dividendGoal = Prefs.double(Prefs.Key.dividendGoal, default: 5000)
        // First run seeds from the device region; afterwards the stored answer
        // wins, whether it came from the seed or from the user. `didSet` does
        // not fire during init, so the seed writes its prefs by hand.
        if let stored = Prefs.string(Prefs.Key.residency) {
            residency = Residency.from(code: stored)
            isResidencyAuto = Prefs.bool(Prefs.Key.residencyAuto)
        } else if let guess = Self.deviceResidency() {
            residency = guess
            Prefs.set(guess.rawValue, Prefs.Key.residency)
            Prefs.set(true, Prefs.Key.residencyAuto)
            isResidencyAuto = true
        } else {
            residency = .other
        }
        dripEnabled = Prefs.bool(Prefs.Key.dripEnabled)

        let marginal = Prefs.double(Prefs.Key.marginalRate, default: -1)
        let preferential = Prefs.double(Prefs.Key.preferredRate, default: -1)
        taxRates = TaxRules.UserRates(
            marginalPct: marginal >= 0 ? marginal : nil,
            preferentialPct: preferential >= 0 ? preferential : nil
        )

        repository.baseCurrency = storedCurrency
    }

    private static func deviceCurrency() -> String {
        if #available(iOS 16.0, *) {
            if let code = Locale.current.currency?.identifier, !code.isEmpty { return code }
        }
        return "CAD"
    }

    // MARK: - Loading

    /// Reads the stored portfolio and seeds the Markets tab. Called once from
    /// the app's root — everything else refreshes on top of this.
    func bootstrap() async {
        await reload()
        await reloadAlerts()

        // The world-markets list has grown over time, so people who installed
        // earlier only ever got the original seed. Topping up is a no-op for
        // tickers already present.
        let symbols = MarketIndices.symbols(homeCountry: homeCountry)
        await repository.seedWatchlistIfEmpty(symbols)
        for symbol in symbols {
            await repository.addToWatchlist(symbol)
        }
        await reload()

        // Paint the Markets tab from the last cached quotes immediately, then
        // refresh over the network. Without this the list shows a column of
        // spinners on every cold start, because the in-memory quote map begins
        // empty and the network is the only source.
        seedQuotesFromCache()

        await refreshRates()
        await refreshWatchlistQuotes()
    }

    func reload() async {
        let document = await repository.load()
        accounts = document.accounts
        holdings = document.holdings
        dividends = document.dividends.sorted { $0.paidAt > $1.paidAt }
        transactions = document.transactions.sorted { $0.at > $1.at }
        watchlist = document.watchlist.sorted { $0.addedAt < $1.addedAt }
    }

    private func seedQuotesFromCache() {
        var seeded = quotes
        for item in watchlist {
            guard seeded[item.ticker] == nil, let price = item.cachedPrice else { continue }
            seeded[item.ticker] = Quote(
                price: price,
                name: item.cachedName,
                previousClose: item.cachedPreviousClose,
                currency: item.cachedCurrency
            )
        }
        quotes = seeded
    }

    // MARK: - Day change

    /// Today's move, derived from stored state rather than from a fetch.
    ///
    /// Both halves come from the SAME `holdings` array the total is built
    /// from, which is the whole point. It used to be a figure captured during
    /// one refresh and held in a published property, then divided by a total
    /// computed separately — so the moment a quote request failed, the
    /// numerator covered fewer holdings than the denominator and the
    /// percentage was wrong until the next successful pull. That is the
    /// "correct, then wrong, then correct" flapping: a refresh that hit the
    /// rate limiter returned nothing and the day change silently became zero.
    ///
    /// `measured` is the PREVIOUS CLOSE value of only those holdings that
    /// actually have one. Percentages are taken against that, not against the
    /// whole portfolio, so a partial picture reports an honest percentage of
    /// what it could measure instead of a diluted one.
    private var dayChange: (absolute: Double, measured: Double) {
        var absolute = 0.0
        var measured = 0.0
        for holding in holdings {
            guard let previous = holding.previousClose, previous > 0 else { continue }
            let price = holding.effectivePrice
            guard price > 0 else { continue }
            let currency = holding.normalizedCurrency
            absolute += fx.convert((price - previous) * holding.units, from: currency, to: baseCurrency)
            // The PREVIOUS CLOSE value, not today's. A day change percentage is
            // the move divided by what the position was worth BEFORE the move —
            // what every quote screen means by "+1.41% today", including this
            // app's own security detail screen. Dividing by the current value
            // puts the gain in its own denominator, so a rising portfolio always
            // read slightly under the holding it was made of.
            measured += fx.convert(previous * holding.units, from: currency, to: baseCurrency)
        }
        return (absolute, measured)
    }

    // MARK: - Dashboard

    var dashboard: DashboardState {
        // Grouped on the ticker where there is one, and on the name where there
        // isn't — two untickered holdings with the same name are the same thing
        // in different accounts, while two with different names are not.
        var grouped: [String: [Holding]] = [:]
        for holding in holdings {
            grouped[holding.securityKey, default: []].append(holding)
        }

        let allocations = grouped.compactMap { key, rows -> AccountAllocation? in
            let value = rows.reduce(0.0) { $0 + repository.value(of: $1) }
            guard value > 0 else { return nil }
            let accountName = accounts.first { $0.id == rows[0].accountId }?.displayName
                ?? rows[0].name
            return AccountAllocation(
                label: key,
                value: value,
                holdings: rows,
                accountName: accountName
            )
        }
        .sorted { $0.value > $1.value }

        let total = allocations.reduce(0.0) { $0 + $1.value }

        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = .current
        let now = Date()
        let monthStart = calendar.date(from: calendar.dateComponents([.year, .month], from: now)) ?? now
        let yearStart = calendar.date(from: calendar.dateComponents([.year], from: now)) ?? now

        // Converted per payment before being added up. These totals sit on a
        // card labelled with the base currency, and a US distribution summed
        // into a Canadian total at face value is not money in either currency.
        func inBase(_ payment: DividendPayment) -> Double {
            fx.convert(payment.amount, from: payment.normalizedCurrency, to: baseCurrency)
        }

        let thisMonth = dividends
            .filter { $0.paidAt >= monthStart && $0.paidAt <= now }
            .reduce(0) { $0 + inBase($1) }
        let thisYear = dividends
            .filter { $0.paidAt >= yearStart && $0.paidAt <= now }
            .reduce(0) { $0 + inBase($1) }
        let allTime = dividends.reduce(0) { $0 + inBase($1) }

        let unconverted = Set(holdings.map(\.normalizedCurrency))
            .filter { !fx.hasRate(from: $0, to: baseCurrency) }
            .sorted()

        let move = dayChange
        return DashboardState(
            totalValue: total,
            dayChangeAbsolute: move.absolute,
            dayChangePercent: move.measured > 0 ? move.absolute / move.measured * 100 : 0,
            allocations: allocations,
            dividendsThisMonth: thisMonth,
            dividendsThisYear: thisYear,
            dividendsAllTime: allTime,
            baseCurrency: baseCurrency,
            unconvertedCurrencies: unconverted
        )
    }

    /// The currencies the portfolio actually touches, for the FX refresh and
    /// for the Settings picker's "held" section.
    var heldCurrencies: [String] {
        Array(Set(holdings.map(\.normalizedCurrency) + dividends.map(\.normalizedCurrency))).sorted()
    }

    // MARK: - FX

    func refreshRates() async {
        let currencies = heldCurrencies
        guard !currencies.isEmpty else { return }
        await fx.refresh(currencies: currencies, base: baseCurrency)
        fxTick += 1
    }

    /// Converts into the reporting currency. Views call this directly rather
    /// than reaching into `FxRates`, so the base currency is applied in one place.
    func amountInBase(_ amount: Double, from currency: String) -> Double {
        fx.convert(amount, from: currency, to: baseCurrency)
    }

    func rateToBase(from currency: String) -> Double? {
        fx.rate(from: currency, to: baseCurrency)
    }

    /// Fetches the rate for one currency on demand.
    ///
    /// The bulk `refreshRates` covers currencies the portfolio holds. A holding
    /// detail screen can be opened on a currency that is not among them yet —
    /// a newly added position whose quote has only just told us where it
    /// trades — and without this the screen would offer a currency toggle it
    /// could not honour.
    func ensureRate(for currency: String) async {
        let code = currency.trimmingCharacters(in: .whitespaces).uppercased()
        guard !code.isEmpty, code != baseCurrency else { return }
        guard fx.rate(from: code, to: baseCurrency) == nil else { return }
        await fx.refresh(currencies: [code], base: baseCurrency)
        fxTick += 1
    }

    // MARK: - Refresh

    func refreshPortfolio() async {
        isRefreshing = true
        defer { isRefreshing = false }

        // One pass over the held tickers, and the quotes it returns fill the
        // cache directly. This used to call `refreshHoldingQuotes()` afterwards
        // as well, re-fetching every ticker a second time — doubling the
        // requests behind a single pull, which is what drove the quote
        // endpoint's rate limiter into a cooldown and made the next pull come
        // back empty.
        // An explicit pull means the user wants current data, so the chart
        // cache steps aside for this one pass.
        await repository.invalidateChartCache()

        let fresh = await repository.refreshAllQuotes()
        if !fresh.isEmpty {
            var updated = quotes
            for (ticker, quote) in fresh { updated[ticker] = quote }
            quotes = updated
        }
        await reload()
        await refreshRates()
        lastPortfolioRefreshAt = Date()
    }

    /// When `refreshPortfolio` last completed, for `refreshPortfolioIfStale`.
    private var lastPortfolioRefreshAt: Date?

    /// How long a refresh stays "fresh enough" for a screen that merely
    /// re-appeared. Two minutes: long enough that tab-hopping costs nothing,
    /// short enough that returning after a break shows current figures.
    private static let portfolioFreshness: TimeInterval = 120

    /// Refreshes only if the prices on screen are old enough to be worth
    /// replacing.
    ///
    /// The Portfolio screen used to call `refreshPortfolio()` from `.task`,
    /// which runs again every time the view re-appears — and switching back to
    /// the tab does exactly that. `refreshAllQuotes` fetches one ticker at a
    /// time, so each visit fired a request per holding and the figures visibly
    /// changed on a navigation the user never thought of as a refresh.
    ///
    /// A window rather than a one-shot flag, so the screen still catches up
    /// after the app has been backgrounded for a while. Pull-to-refresh calls
    /// `refreshPortfolio()` directly and always fetches, which is what makes
    /// the gesture mean something.
    func refreshPortfolioIfStale(
        maxAge: TimeInterval = PortfolioViewModel.portfolioFreshness
    ) async {
        if let last = lastPortfolioRefreshAt, Date().timeIntervalSince(last) < maxAge {
            return
        }
        await refreshPortfolio()
    }

    /// Held tickers aren't necessarily on the watchlist, so the My Holdings tab
    /// needs its own price fetch to avoid showing stale cached values.
    ///
    /// Only fetches tickers `refreshPortfolio()` hasn't priced yet (a holding
    /// added since the last refresh). A ticker already in `quotes` keeps the
    /// figures that call priced it at: this used to refetch every held ticker
    /// unconditionally, a second live call to the same endpoint minutes apart,
    /// which is exactly the kind of pair that has been seen to disagree with
    /// itself — so opening this tab could quietly move a holding's price and
    /// previousClose away from what the Portfolio total and Holding Detail
    /// screen were already showing, off the same closing price.
    func refreshHoldingQuotes() async {
        let tickers = Array(Set(holdings.compactMap(\.ticker)))
            .filter { quotes[$0]?.previousClose == nil }
        guard !tickers.isEmpty else { return }
        var updated = quotes
        for ticker in tickers {
            guard let quote = await repository.fetchQuote(ticker) else { continue }
            updated[ticker] = quote
        }
        quotes = updated
    }

    /// One batched call for the whole Markets tab, with a per-ticker fallback
    /// for anything the batch endpoint drops.
    func refreshWatchlistQuotes() async {
        let tickers = watchlist.map(\.ticker)
        guard !tickers.isEmpty else { return }

        var updatedQuotes = quotes
        var updatedSparks = sparklines
        var filled = Set<String>()
        var tracedByBatch = Set<String>()

        let batch = await repository.fetchSparkBatch(tickers)

        // Tickers the PORTFOLIO prices, and prices from a different provider.
        //
        // `quotes` is one dictionary shared by every screen, and
        // `currentPrice(for:)` prefers it over the stored `lastKnownPrice`. So
        // whatever lands here is what My Holdings and the totals display.
        //
        // `refreshAllQuotes` fills it from the Finance Query chain; this
        // function used to overwrite the same entries from the spark endpoint,
        // which is a different server with a different cache — and a chart
        // endpoint at that, whose price falls back to the last 30-minute bar
        // close when the payload carries no `meta`. For a held ticker that was
        // also on the watchlist, simply visiting the Markets tab rewrote the
        // portfolio's price, and going back showed different numbers after a
        // navigation the user never thought of as a refresh.
        //
        // The trace is still taken from spark for every row — that is what the
        // Chart column is made of, and no other endpoint provides it. Only the
        // PRICE is left alone, and only where the portfolio has already
        // established one.
        let heldTickers = Set(holdings.compactMap { $0.ticker?.uppercased() })

        for (symbol, spark) in batch {
            // The batch endpoint answers with symbols spelled its own way and
            // drops any it cannot serve, so match case-insensitively and note
            // what came back rather than assuming the keys line up.
            let ticker = tickers.first { $0.caseInsensitiveCompare(symbol) == .orderedSame } ?? symbol

            // A held ticker with no quote yet still takes this one: a row with
            // a slightly different price beats a row with none, and the next
            // portfolio refresh corrects it.
            let pricedByPortfolio = heldTickers.contains(ticker.uppercased())
                && updatedQuotes[ticker] != nil

            if !pricedByPortfolio {
                updatedQuotes[ticker] = Quote(
                    price: spark.price,
                    name: updatedQuotes[ticker]?.name,
                    previousClose: spark.previousClose,
                    currency: updatedQuotes[ticker]?.currency,
                    marketTime: spark.marketTime,
                    exchangeTimezone: spark.exchangeTimezone
                )
            }

            if spark.closes.count >= 2 {
                updatedSparks[ticker] = spark.closes
                tracedByBatch.insert(ticker)
            }
            filled.insert(ticker)
        }

        // Anything the batch missed falls back to the per-ticker endpoint. The
        // Chart column sat empty for every row whenever the batch refused the
        // request, and one silent failure left it that way until restart.
        for ticker in tickers where !filled.contains(ticker) {
            guard let quote = await repository.fetchQuote(ticker) else { continue }
            updatedQuotes[ticker] = quote
        }

        quotes = updatedQuotes
        sparklines = updatedSparks

        // A price is not a trace.
        //
        // `filled` marked a ticker done as soon as the batch returned anything
        // for it — and when the server has no batched trace endpoint the batch
        // falls back to plain quotes, which carry a price and no closes at all.
        // Every one of those rows was then skipped by the fallback below and
        // sat with an empty Chart column for the life of the app. The trace is
        // tracked separately now, so a row with a price and no chart still gets
        // one fetched.
        let untraced = tickers.filter { !tracedByBatch.contains($0) }
        await fetchSparkTraces(untraced) { [weak self] ticker, closes in
            guard let self else { return }
            self.sparklines[ticker] = closes
        }

        for (ticker, quote) in updatedQuotes where tickers.contains(ticker) {
            await repository.cacheWatchlistQuote(ticker: ticker, quote: quote)
        }
        await refreshExtendedQuotes()
    }

    /// The secondary line under a row: an index's tracking future while the cash
    /// market is shut, or a stock's after-hours / pre-market print.
    ///
    /// It used to fetch ONLY for symbols that have a tracking future — that is,
    /// indices — so a stock or ETF never got an extended line at all. After
    /// hours an index row showed its future moving while AAPL beside it showed
    /// nothing, which reads as missing data rather than as a design. Both cases
    /// go through `fetchExtended`, which already picks the right one per
    /// symbol, so the filter was the only thing stopping it.
    ///
    /// Held tickers are included as well as watchlisted ones: the Markets tab
    /// has a My Holdings section, and those rows were the most conspicuous
    /// blanks.
    /// The extended-hours line for a ticker, looked up case-insensitively.
    ///
    /// The cache is keyed on the uppercased symbol because that is what the
    /// batch endpoint echoes back, while callers hold whatever the user or the
    /// search result gave them. Normalising here rather than at each call site
    /// keeps the two from silently missing each other.
    func extendedQuote(for ticker: String?) -> ExtendedQuote? {
        guard let ticker, !ticker.isEmpty else { return nil }
        return extendedQuotes[ticker.trimmingCharacters(in: .whitespaces).uppercased()]
    }

    private func refreshExtendedQuotes() async {
        let symbols = Array(Set(watchlist.map(\.ticker) + holdings.compactMap(\.ticker)))
        guard !symbols.isEmpty else { return }

        // ONE request for every stock and ETF. The pre/post fields travel on
        // the batch quote payload, so this costs the same as fetching the
        // prices did — where a per-symbol loop would turn one refresh into one
        // request per row.
        var updated = await repository.fetchExtendedBatch(symbols)

        // Indices are the exception and stay per-symbol. Their extended line is
        // a DIFFERENT instrument — the tracking future, quoted under its own
        // ticker — so it cannot come from the batch, and there are only a
        // handful of them on the list.
        for ticker in symbols where QuoteClient.hasFuture(ticker) {
            if let future = await repository.fetchExtended(ticker) {
                updated[ticker.uppercased()] = future
            }
        }

        // Assigned wholesale rather than merged into what was there. A symbol
        // absent from the fresh result has no extended print right now, and
        // keeping the previous one would leave last night's after-hours price
        // sitting under a live price all through the next session.
        extendedQuotes = updated
    }

    /// Paints the last fetched feeds, then refreshes.
    ///
    /// Headlines are worth keeping between launches: they are still readable
    /// an hour later, and without this a cold start showed an empty News tab
    /// with a spinner while several feeds were fetched. Now the tab opens on
    /// the last set and quietly replaces it.
    func loadCachedNews() async {
        if marketNews.isEmpty,
           let stored = await repository.cachedNews(.marketNews) {
            marketNews = stored
            marketNewsState = .loaded
        }
        if dividendNews.isEmpty,
           let stored = await repository.cachedNews(.dividendNews) {
            dividendNews = stored
            dividendNewsState = .loaded
        }
    }

    func refreshMarketNews() async {
        marketNewsState = .loading
        let result = await repository.fetchMarketNews(homeCountry: homeCountry)
        // Headlines already on screen are kept when a refresh comes back empty:
        // a failed pull should not erase a feed the reader was part-way through.
        if !result.items.isEmpty {
            marketNews = result.items
            await repository.cacheNews(.marketNews, result.items)
        }
        marketNewsState = NewsLoadState(result: result, existing: marketNews)
    }

    func refreshDividendNews() async {
        dividendNewsState = .loading
        let tickers = Array(Set(holdings.compactMap(\.ticker)))
        let result = await repository.fetchDividendNews(tickers: tickers)
        if !result.items.isEmpty {
            dividendNews = result.items
            await repository.cacheNews(.dividendNews, result.items)
        }
        dividendNewsState = NewsLoadState(result: result, existing: dividendNews)
    }

    /// Refreshes the Hot Stocks list, then fetches intraday sparklines for each
    /// name in one batched call rather than dozens of sequential requests.
    func refreshMarketMovers() async {
        let hot = await repository.fetchHotStocks()
        guard !hot.isEmpty else { return }
        hotStocks = hot

        let tickers = hot.map(\.ticker)
        var sparks = moverSparklines
        let batch = await repository.fetchSparkBatch(tickers)
        var filled = Set<String>()
        for (symbol, spark) in batch where spark.closes.count >= 2 {
            let ticker = tickers.first { $0.caseInsensitiveCompare(symbol) == .orderedSame } ?? symbol
            sparks[ticker] = spark.closes
            filled.insert(ticker)
        }
        moverSparklines = sparks

        // The rest are fetched a few at a time and published as they land.
        //
        // This was a plain `for … await` over every name the batch had missed —
        // up to two dozen requests, strictly one after another, with the whole
        // dictionary assigned only after the last one returned. So the column
        // filled in for the first handful, and everything below them stayed
        // blank: either the run was still grinding through the queue, or the
        // rate-limit backoff had started answering with nothing. Concurrency
        // stays capped for that same reason — a burst of twenty-five parallel
        // requests is a good way to get rate-limited into an empty column — but
        // six at a time finishes in a fraction of the time, and each row now
        // appears the moment its own trace arrives.
        let untraced = tickers.filter { !filled.contains($0) }
        await fetchSparkTraces(untraced) { [weak self] ticker, closes in
            guard let self else { return }
            self.moverSparklines[ticker] = closes
        }

        // Hot Stocks rows get the after-hours line too.
        //
        // `refreshExtendedQuotes` covers the watchlist and the holdings, which
        // is every list except this one, and the row was passing nil regardless
        // — so the one screen most likely to be read after the close was the
        // one screen that never said a price had moved since it.
        await refreshMoverExtendedQuotes(tickers)
    }

    /// Extended prints for the Hot Stocks names.
    ///
    /// Merged into the map rather than replacing it, because
    /// `refreshExtendedQuotes` owns the watchlist and holdings keys and the two
    /// lists overlap. A mover absent from the fresh result has its old entry
    /// cleared, so last night's after-hours price cannot outlive the session.
    private func refreshMoverExtendedQuotes(_ tickers: [String]) async {
        guard !tickers.isEmpty else { return }
        let fresh = await repository.fetchExtendedBatch(tickers)
        var updated = extendedQuotes
        for ticker in tickers {
            let key = ticker.trimmingCharacters(in: .whitespaces).uppercased()
            updated[key] = fresh[key]
        }
        extendedQuotes = updated
    }

    /// One intraday trace, as it comes back from a child task.
    private struct SparkTrace: Sendable {
        let ticker: String
        let closes: [Double]
    }

    /// Intraday traces for several tickers, a few requests in flight at a time.
    ///
    /// `onResult` is called as each one lands rather than at the end, so a long
    /// list fills in from the top instead of appearing all at once — or, when a
    /// later request fails, never appearing at all.
    private func fetchSparkTraces(
        _ tickers: [String],
        limit: Int = 6,
        onResult: (String, [Double]) -> Void
    ) async {
        guard !tickers.isEmpty else { return }

        await withTaskGroup(of: SparkTrace.self) { group in
            var pending = tickers.makeIterator()
            var started = 0

            while started < limit, let ticker = pending.next() {
                group.addTask { [repository] in
                    let history = await repository.fetchHistory(ticker, range: "1d", interval: "30m")
                    return SparkTrace(ticker: ticker, closes: history.map { $0.1 })
                }
                started += 1
            }

            for await trace in group {
                if trace.closes.count >= 2 {
                    onResult(trace.ticker, trace.closes)
                }
                // One in, one out: the number of requests in flight never rises
                // above `limit` however long the list is.
                if let next = pending.next() {
                    group.addTask { [repository] in
                        let history = await repository.fetchHistory(next, range: "1d", interval: "30m")
                        return SparkTrace(ticker: next, closes: history.map { $0.1 })
                    }
                }
            }
        }
    }

    // MARK: - Search

    func search(_ query: String) {
        searchTask?.cancel()
        guard !query.trimmingCharacters(in: .whitespaces).isEmpty else {
            searchResults = []
            return
        }
        searchTask = Task { [weak self] in
            // Debounced: search-as-you-type against a remote index otherwise
            // fires a request per keystroke.
            try? await Task.sleep(nanoseconds: 300_000_000)
            guard !Task.isCancelled, let self else { return }
            let results = await self.repository.searchSymbols(query)
            guard !Task.isCancelled else { return }
            self.searchResults = results
        }
    }

    func clearSearch() {
        searchTask?.cancel()
        searchResults = []
    }

    // MARK: - Accounts

    /// Creates an account, or returns nil when a free install is already at
    /// `PremiumLimits.freeAccountLimit`.
    ///
    /// `AccountPicker` already refuses at the chip, but this is the call every
    /// entry point goes through, so the limit lives here too rather than only
    /// in whichever view remembered to check. A restore from backup
    /// deliberately does NOT come through here: data someone already entered
    /// is theirs, and silently dropping accounts on restore would be data loss
    /// dressed up as a paywall.
    func createAccount(name: String, treatment: TaxTreatment?) async -> UUID? {
        guard PremiumLimits.canCreateAccount(
            currentCount: accounts.count,
            isPremium: SubscriptionSession.shared.isPremium
        ) else { return nil }

        let account = Account(displayName: name, taxTreatment: treatment)
        let id = await repository.upsertAccount(account)
        await reload()
        return id
    }

    // MARK: - Alerts

    func reloadAlerts() async {
        alerts = await repository.alerts()
    }

    func addAlert(ticker: String, kind: AlertKind, threshold: Double) async {
        // Premium-gated, alongside the screen that offers it and the runner
        // that fires it. Three checks for one rule is not redundancy here:
        // each is the last line of defence for a different entry point.
        guard SubscriptionSession.shared.isPremium else { return }
        await repository.addAlert(ticker: ticker, kind: kind, threshold: threshold)
        await reloadAlerts()
    }

    func setAlertEnabled(_ id: UUID, enabled: Bool) async {
        await repository.setAlertEnabled(id, enabled: enabled)
        await reloadAlerts()
    }

    func deleteAlert(_ id: UUID) async {
        await repository.deleteAlert(id)
        await reloadAlerts()
    }

    func setAccountTaxTreatment(_ accountId: UUID, _ treatment: TaxTreatment?) async {
        await repository.setAccountTaxTreatment(accountId: accountId, treatment: treatment)
        await reload()
    }

    func deleteAccount(_ id: UUID) async {
        await repository.deleteAccount(id)
        await reload()
    }

    /// The account a new holding should default to, or nil when the user has
    /// not created one yet.
    func defaultAccount() async -> UUID? {
        await repository.defaultAccount()
    }

    func account(for holding: Holding) -> Account? {
        accounts.first { $0.id == holding.accountId }
    }

    // MARK: - Positions

    /// Every stored row for the security this holding belongs to, largest
    /// slice first.
    func slices(forSecurity key: String) -> [AccountPosition] {
        holdings
            .filter { $0.securityKey == key }
            .map { row in
                let quote = row.ticker.flatMap { quotes[$0] }
                return AccountPosition(
                    holding: row,
                    account: account(for: row),
                    units: row.units,
                    price: quote?.price ?? row.effectivePrice,
                    previousClose: quote?.previousClose,
                    costBasis: row.costBasis
                )
            }
            .sorted { $0.marketValue > $1.marketValue }
    }

    /// The whole position one holding row belongs to.
    func position(for holding: Holding) -> SecurityPosition? {
        position(forSecurity: holding.securityKey)
    }

    func position(forSecurity key: String) -> SecurityPosition? {
        let slices = slices(forSecurity: key)
        guard !slices.isEmpty else { return nil }
        return SecurityPosition(key: key, slices: slices)
    }

    /// One entry per security the user actually owns, largest first. This is
    /// what every list of holdings is built from, so the same fund held three
    /// times never appears three times.
    var positions: [SecurityPosition] {
        let keys = holdings.map(\.securityKey)
        var seen = Set<String>()
        var out: [SecurityPosition] = []
        for key in keys where seen.insert(key).inserted {
            if let position = position(forSecurity: key) { out.append(position) }
        }
        return out.sorted { $0.marketValue > $1.marketValue }
    }

    /// Dividends recorded against any account's slice of a security.
    func dividends(forSecurity key: String) -> [DividendPayment] {
        let ids = Set(holdings.filter { $0.securityKey == key }.map(\.id))
        return dividends
            .filter { ids.contains($0.holdingId) }
            .sorted { $0.paidAt > $1.paidAt }
    }

    /// Transactions recorded against any account's slice of a security.
    func transactions(forSecurity key: String) -> [PortfolioTransaction] {
        let ids = Set(holdings.filter { $0.securityKey == key }.map(\.id))
        return transactions
            .filter { ids.contains($0.holdingId) }
            .sorted { $0.at > $1.at }
    }

    // MARK: - Holdings

    @discardableResult
    func saveHolding(
        existingId: UUID?,
        accountId: UUID,
        name: String,
        ticker: String?,
        type: HoldingType,
        units: Double,
        manualPrice: Double?,
        currency: String,
        costBasis: Double?
    ) async -> UUID {
        let id = await repository.saveHolding(
            existingId: existingId,
            accountId: accountId,
            name: name,
            ticker: ticker,
            type: type,
            units: units,
            manualPrice: manualPrice,
            currency: currency,
            costBasis: costBasis
        )
        await reload()
        await refreshRates()
        return id
    }

    func deleteHolding(_ id: UUID) async {
        await repository.deleteHolding(id)
        await reload()
    }

    func holding(_ id: UUID) -> Holding? {
        holdings.first { $0.id == id }
    }

    /// The price a holding is currently marked at, preferring a live quote over
    /// the stored one.
    func currentPrice(for holding: Holding) -> Double {
        if let ticker = holding.ticker, let quote = quotes[ticker] { return quote.price }
        return holding.effectivePrice
    }

    func value(of holding: Holding) -> Double {
        repository.value(of: holding)
    }

    func nativeValue(of holding: Holding) -> Double {
        currentPrice(for: holding) * holding.units
    }

    func previewQuote(_ ticker: String) async -> QuotePreview {
        await repository.previewQuote(ticker: ticker)
    }

    func quote(forTicker ticker: String) async -> Quote? {
        await repository.fetchQuote(ticker)
    }

    func currency(forTicker ticker: String?) -> String {
        repository.currency(forTicker: ticker, fallback: baseCurrency)
    }

    func historyBars(_ ticker: String, range: String, interval: String) async -> [HistoryBar] {
        await repository.fetchHistoryBars(ticker, range: range, interval: interval)
    }

    func portfolioValueSeries(range: String, interval: String) async -> PortfolioSeries {
        await repository.portfolioValueSeries(range: range, interval: interval)
    }

    // MARK: - Transactions

    func addTransaction(
        holdingId: UUID,
        type: TransactionType,
        at date: Date,
        shares: Double,
        pricePerShare: Double,
        note: String? = nil,
        sourceDividendId: UUID? = nil
    ) async {
        await repository.addTransaction(
            holdingId: holdingId,
            type: type,
            at: date,
            shares: shares,
            pricePerShare: pricePerShare,
            note: note,
            sourceDividendId: sourceDividendId
        )
        await reload()
    }

    /// Creates the holding, then records the transaction against it — so the
    /// first buy of something new is still a single step for the user.
    func createHoldingThenTransact(
        accountId: UUID,
        name: String,
        ticker: String?,
        type: HoldingType,
        currency: String,
        transactionType: TransactionType,
        at date: Date,
        shares: Double,
        pricePerShare: Double
    ) async {
        let id = await repository.saveHolding(
            existingId: nil,
            accountId: accountId,
            name: name,
            ticker: ticker,
            // A new position starts at zero units; the transaction is what puts
            // shares into it, so the two can't disagree about the size.
            type: type,
            units: 0,
            manualPrice: nil,
            currency: currency,
            costBasis: nil
        )
        await repository.addTransaction(
            holdingId: id,
            type: transactionType,
            at: date,
            shares: shares,
            pricePerShare: pricePerShare
        )
        await reload()
        await refreshRates()
    }

    func deleteTransaction(_ id: UUID) async {
        await repository.deleteTransaction(id)
        await reload()
    }

    func transactions(for holdingId: UUID) -> [PortfolioTransaction] {
        transactions.filter { $0.holdingId == holdingId }.sorted { $0.at > $1.at }
    }

    // MARK: - Dividends

    func addDividend(
        holdingId: UUID,
        paidAt: Date,
        amount: Double,
        perUnit: Double?,
        currency: String,
        note: String?
    ) async {
        await repository.addDividendPayment(
            holdingId: holdingId,
            paidAt: paidAt,
            amount: amount,
            perUnit: perUnit,
            currency: currency,
            note: note
        )
        await reload()
    }

    func deleteDividend(_ id: UUID) async {
        await repository.deleteDividendPayment(id)
        await reload()
    }

    func dividends(for holdingId: UUID) -> [DividendPayment] {
        dividends.filter { $0.holdingId == holdingId }.sorted { $0.paidAt > $1.paidAt }
    }

    /// Fetches upcoming dividend info for each tickered holding.
    ///
    /// The payment record is kept, not just consumed: the forward charts
    /// project from the fund's actual seasonal shape, and this is the only
    /// place it gets fetched.
    /// Re-scrapes the payout calendars, ignoring the cache.
    ///
    /// What pull-to-refresh on the Dividends tab should do: the cache holds a
    /// calendar for twelve hours, so without this the gesture would return the
    /// same cached answer and look like it had done nothing.
    func refreshUpcomingDividendsForced() async {
        await repository.invalidateDividendCalendars()
        await refreshUpcomingDividends()
    }

    func refreshUpcomingDividends() async {
        // One entry per SECURITY, not per stored row. A fund held in three
        // accounts pays one distribution on the combined unit count, and
        // fetching the same ticker three times to build three identical cards
        // was both wrong on screen and three times the network.
        let securities = positions.filter { $0.ticker?.isEmpty == false }
        guard !securities.isEmpty else {
            upcomingDividends = []
            return
        }

        upcomingProgress = .running(current: 0, total: securities.count, ticker: "")
        var rows: [UpcomingDividendRow] = []

        for (index, position) in securities.enumerated() {
            guard let ticker = position.ticker else { continue }
            upcomingProgress = .running(current: index + 1, total: securities.count, ticker: ticker)
            let (info, history) = await repository.upcomingDividend(ticker: ticker)
            guard let info else { continue }
            rows.append(UpcomingDividendRow(
                holding: position.principal,
                info: info,
                history: history.map { PerUnitPayment(date: $0.0, perUnit: $0.1) },
                rows: position.slices.map(\.holding)
            ))
        }

        // Only replace the list when the refresh actually produced something.
        //
        // Assigning unconditionally meant a pull-to-refresh that came back
        // empty — one throttled minute, or a payout page that timed out —
        // erased cards that were already on screen and correct. A refresh
        // should improve what is shown or leave it alone; it should never be
        // able to take data away.
        if !rows.isEmpty {
            upcomingDividends = rows.sorted {
                ($0.info.exDividendDate ?? .distantFuture) < ($1.info.exDividendDate ?? .distantFuture)
            }
        }
        upcomingProgress = .idle
    }

    // MARK: - Income series

    /// Received and projected cash flows in the reporting currency, as the
    /// Dividends Received chart needs them.
    func dividendCashFlowsInBase() -> (received: [(Date, Double)], projected: [(Date, Double)]) {
        let received = dividends.map {
            ($0.paidAt, fx.convert($0.amount, from: $0.normalizedCurrency, to: baseCurrency))
        }

        var projected: [(Date, Double)] = []
        let now = Date()
        let horizon = now.addingTimeInterval(365 * 24 * 60 * 60 * 10)

        // Projecting only from TODAY made the current year's bar read as a
        // collapse: with nothing logged as received, a quarterly payer's
        // year showed just whatever payment was still ahead of today — one
        // quarter out of four — sitting next to full years on either side.
        // Nothing about the fund's income actually dropped; the other three
        // quarters were simply never asked for.
        //
        // So when nothing has been received yet THIS calendar year, the
        // projection starts at January 1st instead of today. `recentCycle`
        // then builds its seasonal template from the cycle before that (the
        // fund's own real record), and projects every quarter of the current
        // year from it — the same "fall back to the forecast" rule the goal
        // card already uses when nothing is logged. Whichever of those
        // quarters is actually announced still gets replaced with the real
        // figure by `applyAnnouncedPayment`, exactly as before.
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = .current
        let thisYear = calendar.component(.year, from: now)
        let receivedThisYear = received.contains { calendar.component(.year, from: $0.0) == thisYear }
        let projectionStart: Date
        if !receivedThisYear,
           let startOfYear = calendar.date(from: DateComponents(year: thisYear, month: 1, day: 1)) {
            projectionStart = startOfYear
        } else {
            projectionStart = now
        }

        for row in upcomingDividends {
            let payments = DividendForecast.project(
                history: row.history.map { ($0.date, $0.perUnit) },
                upcoming: row.info,
                from: projectionStart,
                until: horizon,
                // nil, not a constant: the forecast measures the fund's own
                // growth from the history it was handed, and falls back only
                // when there is too little of it to measure.
                growthRate: nil
            )
            for payment in payments {
                projected.append((
                    payment.date,
                    fx.convert(
                        payment.perUnit * row.units,
                        from: row.holding.normalizedCurrency,
                        to: baseCurrency
                    )
                ))
            }
        }
        return (received, projected)
    }

    /// Projected income for the next `months` months, bucketed by calendar
    /// month and split per holding — what the FWD bars on the Monthly Income
    /// card are drawn from.
    ///
    /// The bucket key is `year * 100 + monthIndex`, which sorts chronologically
    /// and can't collide across years the way a bare month number would.
    func projectedIncomeByMonthAndHolding(months: Int = 12) -> [Int: [UUID: Double]] {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = .current
        let now = Date()
        guard let until = calendar.date(byAdding: .month, value: months, to: now) else { return [:] }

        var out: [Int: [UUID: Double]] = [:]
        for row in upcomingDividends {
            let payments = DividendForecast.project(
                history: row.history.map { ($0.date, $0.perUnit) },
                upcoming: row.info,
                from: now,
                until: until,
                // nil, not a constant: the forecast measures the fund's own
                // growth from the history it was handed, and falls back only
                // when there is too little of it to measure.
                growthRate: nil
            )
            for payment in payments {
                let components = calendar.dateComponents([.year, .month], from: payment.date)
                guard let year = components.year, let month = components.month else { continue }
                let key = year * 100 + (month - 1)
                let amount = fx.convert(
                    payment.perUnit * row.units,
                    from: row.holding.normalizedCurrency,
                    to: baseCurrency
                )
                out[key, default: [:]][row.holding.id, default: 0] += amount
            }
        }
        return out
    }

    /// The same projection, flattened to a per-month total. Used by the goal
    /// card's forward average.
    func projectedIncomeByMonth(months: Int = 12) -> [Int: Double] {
        projectedIncomeByMonthAndHolding(months: months).mapValues { byHolding in
            byHolding.values.reduce(0, +)
        }
    }

    /// Projected income for the next `years` calendar years, bucketed by year
    /// and split per holding — what the projected rows on "Historical Income
    /// ▸ Yearly" extend into, so a fund's future distributions read as the
    /// same seasonal, per-holding stack as its past ones rather than a flat
    /// guess bolted onto the end of a real chart.
    func projectedIncomeByYearAndHolding(years: Int) -> [Int: [UUID: Double]] {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = .current
        let now = Date()
        guard let until = calendar.date(byAdding: .year, value: years, to: now) else { return [:] }

        var out: [Int: [UUID: Double]] = [:]
        for row in upcomingDividends {
            let payments = DividendForecast.project(
                history: row.history.map { ($0.date, $0.perUnit) },
                upcoming: row.info,
                from: now,
                until: until,
                // nil: measure this fund's own growth rather than assume one
                // figure for every holding.
                growthRate: nil
            )
            for payment in payments {
                let year = calendar.component(.year, from: payment.date)
                let amount = fx.convert(
                    payment.perUnit * row.units,
                    from: row.holding.normalizedCurrency,
                    to: baseCurrency
                )
                out[year, default: [:]][row.holding.id, default: 0] += amount
            }
        }
        return out
    }

    /// Blended forward yield across the whole portfolio — the rate at which
    /// reinvested distributions buy new units.
    ///
    /// Built from the upcoming payment schedule, NOT from logged payments.
    /// Keying it on trailing logged income means a portfolio with nothing
    /// logged yet — the normal state for a new user, and the state this app
    /// starts in — computes a yield of exactly zero, so the DRIP series has
    /// zero height on every bar while still printing a DRIP swatch in the legend.
    ///
    /// The year is projected payment by payment rather than by annualising the
    /// NEXT payment, which is wrong for any seasonal payer: XEQT's small Q3
    /// times four reads as 0.408 a unit when the fund actually distributes
    /// about 0.71, so the DRIP rate came out barely half what it should be and
    /// the yearly chart drifted DOWNWARD — reinvestment too small to offset a
    /// negative measured distribution growth. It is the same mistake
    /// `DividendForecast` was written to avoid, and the same one the holding
    /// detail screen already fixed; this was the last place still making it.
    var portfolioForwardYield: Double {
        let value = holdings.reduce(0.0) { $0 + repository.value(of: $1) }
        let now = Date()
        let forwardIncome = upcomingDividends.reduce(0.0) { total, row in
            let frequency = row.info.paymentFrequencyPerYear ?? 4
            // Falls back to the old flat annualisation only when there is too
            // little history to project a year — a rate that is roughly right
            // beats no DRIP series at all.
            let perUnit = DividendForecast.forwardAnnualPerUnit(
                history: row.history.map { ($0.date, $0.perUnit) },
                upcoming: row.info,
                now: now
            ) ?? row.info.estimatedAnnualRate
                ?? row.info.perPaymentAmount.map { $0 * Double(frequency) }
                ?? 0
            return total + fx.convert(
                perUnit * row.units,
                from: row.holding.normalizedCurrency,
                to: baseCurrency
            )
        }
        guard value > 0, forwardIncome > 0 else { return 0 }
        return min(max(forwardIncome / value, 0), 0.25)
    }

    // MARK: - Watchlist

    func addToWatchlist(_ ticker: String) async -> Bool {
        let added = await repository.addToWatchlist(ticker)
        await reload()
        if added { await refreshWatchlistQuotes() }
        return added
    }

    func removeFromWatchlist(_ id: UUID) async {
        await repository.removeFromWatchlist(id)
        await reload()
    }

    func renameWatchlistItem(_ id: UUID, name: String?) async {
        await repository.renameWatchlistItem(id, name: name)
        await reload()
    }

    /// The Markets tab's index rows, in registry order: the three US
    /// benchmarks, then the home market, then the other majors.
    var marketIndices: [MarketIndex] {
        MarketIndices.indexes(homeCountry: homeCountry)
    }

    // MARK: - Tax

    /// True when the tax features have enough to say anything at all: the user
    /// has told us where they file, and at least one account is classified.
    var taxFeaturesEnabled: Bool {
        residency != .other && accounts.contains { $0.taxTreatment != nil }
    }

    /// Accounts the user has not classified. Each one silently disables every
    /// tax figure for everything inside it, so the Tax screen lists them.
    var accountsMissingTaxTreatment: [Account] {
        guard residency != .other else { return [] }
        return accounts.filter { $0.taxTreatment == nil }
    }

    /// The residency the device's region implies, or nil where the app has no
    /// rules for that country. Static because `init` needs it before `self` is
    /// fully formed.
    static func deviceResidency() -> Residency? {
        switch MarketIndices.deviceCountry().uppercased() {
        case "CA": return .canada
        case "US": return .unitedStates
        default: return nil
        }
    }

    func suggestedResidency() -> Residency? { Self.deviceResidency() }

    /// Hand the answer back to the device region — the way out for someone who
    /// changed it by mistake, or moved back.
    func resetResidencyToDevice() {
        guard let guess = Self.deviceResidency() else { return }
        residency = guess          // clears the flag, via didSet…
        setResidencyAuto(true)     // …and this puts it back, deliberately.
    }

    private func setResidencyAuto(_ value: Bool) {
        Prefs.set(value, Prefs.Key.residencyAuto)
        isResidencyAuto = value
    }

    func needsTaxTreatmentPrompt(for holding: Holding) -> Bool {
        guard residency != .other else { return false }
        return account(for: holding)?.taxTreatment == nil
    }

    func taxNotes(for holding: Holding, annualDividendIncome: Double?) -> [TaxNote] {
        TaxRules.notes(
            ticker: holding.ticker,
            residency: residency,
            treatment: account(for: holding)?.taxTreatment,
            annualDividendIncome: annualDividendIncome
        )
    }

    /// Forward twelve-month income for one holding, in its own currency — the
    /// figure the tax notes put a dollar cost on.
    func forwardAnnualIncome(for holding: Holding) -> Double? {
        // By SECURITY. A card is keyed on the security and named after its
        // largest slice, so matching on the row id found nothing for the same
        // fund's smaller slices in other accounts — their tax notes had no
        // income to put a cost on.
        guard let row = upcomingDividends.first(where: { $0.id == holding.securityKey }),
              let perUnit = forwardAnnualPerUnit(row) else { return nil }
        let income = perUnit * holding.units
        return income > 0 ? income : nil
    }

    /// Next twelve months of per-unit distributions for one card.
    ///
    /// The fund's own seasonal projection first — the same one the income
    /// charts draw. "Next payment × frequency" was used here before, and for a
    /// fund with lumpy quarters it depends on WHICH quarter is next: XEQT's
    /// small September payment × 4 is barely half its real annual income, its
    /// December payment × 4 well over it, so every tax figure built on it
    /// swung from quarter to quarter.
    private func forwardAnnualPerUnit(_ row: UpcomingDividendRow) -> Double? {
        let frequency = Double(max(row.info.paymentFrequencyPerYear ?? 4, 1))
        return DividendForecast.forwardAnnualPerUnit(
            history: row.history.map { ($0.date, $0.perUnit) },
            upcoming: row.info
        )
            ?? row.info.estimatedAnnualRate
            ?? row.info.perPaymentAmount.map { $0 * frequency }
    }

    /// Dividend tax on a stated annual income, rather than on the one this view
    /// model can derive. One account's slice of a position earns its own share
    /// of the income, and that share is what its own tax has to be worked out
    /// from — a TFSA slice and an RRSP slice of the same fund are taxed
    /// differently on different amounts.
    func dividendTaxEstimate(
        for holding: Holding,
        annualIncome: Double
    ) -> TaxRules.DividendTaxEstimate? {
        TaxRules.estimateDividendTax(
            ticker: holding.ticker,
            residency: residency,
            treatment: account(for: holding)?.taxTreatment,
            annualDividendIncome: annualIncome,
            rates: taxRates
        )
    }

    func dividendTaxEstimate(for holding: Holding) -> TaxRules.DividendTaxEstimate? {
        guard let income = forwardAnnualIncome(for: holding) else { return nil }
        return TaxRules.estimateDividendTax(
            ticker: holding.ticker,
            residency: residency,
            treatment: account(for: holding)?.taxTreatment,
            annualDividendIncome: income,
            rates: taxRates
        )
    }

    func capitalGainsTaxEstimate(for holding: Holding, unrealizedGain: Double?) -> Double? {
        guard let unrealizedGain else { return nil }
        return TaxRules.estimateCapitalGainsTax(
            residency: residency,
            treatment: account(for: holding)?.taxTreatment,
            unrealizedGain: unrealizedGain,
            rates: taxRates
        )
    }

    /// Only counts avoidable loss: tax withheld inside a registered account,
    /// where there is no domestic tax bill to claim a foreign tax credit
    /// against. Withholding in a taxable account is normally recoverable at
    /// filing time, so counting it here would invent a loss the user doesn't have.
    var portfolioTaxDrag: TaxDrag? {
        guard residency != .other else { return nil }

        var treatmentByAccount: [UUID: TaxTreatment?] = [:]
        for account in accounts { treatmentByAccount[account.id] = account.taxTreatment }

        var total = 0.0
        var count = 0

        // Per SLICE, not per card: the whole point of this figure is that the
        // same fund loses withholding in the TFSA and loses none of it in the
        // RRSP, so a single treatment for the merged position would be wrong
        // whichever one it picked.
        for row in upcomingDividends {
            for holding in (row.rows.isEmpty ? [row.holding] : row.rows) {
                guard let treatment = treatmentByAccount[holding.accountId] ?? nil else { continue }
                // Any registered account, not just the tax-free one. A Canadian
                // holding German or Swedish stock in an RRSP is losing withholding
                // permanently too — the treaty exemption that covers US dividends
                // there does not extend to anywhere else.
                if TaxRules.isRecoverable(treatment) { continue }

                let rate = TaxRules.withholdingRate(
                    ticker: holding.ticker,
                    residency: residency,
                    treatment: treatment
                )
                guard rate > 0 else { continue }

                guard let perUnit = forwardAnnualPerUnit(row) else { continue }
                let annualIncome = perUnit * holding.units
                guard annualIncome > 0 else { continue }

                total += fx.convert(
                    annualIncome * rate,
                    from: holding.normalizedCurrency,
                    to: baseCurrency
                )
                count += 1
            }
        }

        return count > 0 ? TaxDrag(annualCost: total, holdingCount: count, currency: baseCurrency) : nil
    }

    // MARK: - Backup

    func backupPayload() async -> PortfolioDocument {
        await repository.backupPayload()
    }

    func restore(from document: PortfolioDocument) async {
        await repository.restore(from: document)
        await reload()
        await refreshRates()
    }

    /// Flushes pending writes. Called when the app leaves the foreground, where
    /// waiting out the store's debounce could mean losing the last edit.
    // MARK: - Holding detail

    /// Assembles everything the holding detail screen shows.
    ///
    /// Done as one call rather than a dozen `.task` modifiers on the view: the
    /// figures are interdependent — yield needs the trailing distribution,
    /// which needs the event history, which the payout schedule also needs —
    /// and fetching them separately is how a screen ends up printing a yield
    /// computed from one set of events beside a chart drawn from another.
    /// Combines a live "detailed" fetch with whatever the shared `quotes`
    /// cache already holds for the same ticker.
    ///
    /// Price and previous close come from `cached` — the one figure every
    /// other screen agrees on — so a detail screen can never show a
    /// different day change for a security than the Portfolio list does.
    /// Everything the cache does not carry (52-week range, volume, logo,
    /// instrument type) comes from `fetched`, the detailed call made just for
    /// this screen. Mirrors the merge `QuoteDetailScreen` has always done on
    /// Android for the Markets tab.
    private func mergedQuote(cached: Quote?, fetched: Quote?) -> Quote? {
        guard let cached else { return fetched }
        guard let fetched else { return cached }
        return Quote(
            price: cached.price,
            name: fetched.name ?? cached.name,
            previousClose: cached.previousClose ?? fetched.previousClose,
            currency: cached.currency ?? fetched.currency,
            dayHigh: fetched.dayHigh,
            dayLow: fetched.dayLow,
            open: fetched.open,
            volume: fetched.volume,
            fiftyTwoWeekHigh: fetched.fiftyTwoWeekHigh,
            fiftyTwoWeekLow: fetched.fiftyTwoWeekLow,
            avgVolume3Month: fetched.avgVolume3Month,
            marketTime: fetched.marketTime ?? cached.marketTime,
            exchangeTimezone: fetched.exchangeTimezone ?? cached.exchangeTimezone,
            instrumentType: fetched.instrumentType ?? cached.instrumentType,
            logoURL: fetched.logoURL ?? cached.logoURL
        )
    }

    func loadHoldingDetail(_ id: UUID) async -> HoldingDetail? {
        guard let holding = holding(id) else { return nil }
        // Every figure below covers the WHOLE position — all accounts holding
        // this security, not just the row that was tapped. Three rows in the
        // store are one thing the user owns, and the top of this screen has to
        // agree with the total the Portfolio list printed.
        guard let position = position(for: holding) else { return nil }
        var detail = HoldingDetail(holding: holding, position: position)

        let ticker = holding.ticker?.trimmingCharacters(in: .whitespaces)
        var events: [(Date, Double)] = []

        if let ticker, !ticker.isEmpty {
            let fetched = await quote(forTicker: ticker)
            // The SAME price and previous close the rest of the app shows for
            // this ticker, not a second live answer to the same question.
            //
            // This screen used to fetch its own quote independently of
            // `quotes`, the cache every other screen reads through
            // `currentPrice(for:)`. Two live calls to the same undocumented
            // endpoint, minutes apart, do not always agree — previousClose in
            // particular has been seen to differ between two calls for the
            // very same closed session — so the Portfolio screen and this one
            // could print two different day-change percentages for one
            // holding, using the same closing price. See `mergedQuote`.
            detail.quote = mergedQuote(cached: quotes[ticker], fetched: fetched)

            // The SAME payment record the Dividends tab shows for this
            // security, not a second live answer to the same question.
            //
            // This used to call `repository.upcomingDividend` fresh every
            // time the screen opened, independent of `upcomingDividends` —
            // the list `refreshUpcomingDividends()` populates and the
            // Dividends tab reads. Both go through the same merge logic
            // (dividendhistory.org, Yahoo fallback) but as two SEPARATE live
            // calls, which a feed that has already been seen to disagree with
            // itself between calls (see `mergedQuote`) can still answer
            // differently for — so a holding's own detail page could show a
            // different next-payment date, or a different monthly shape, than
            // the tab built to summarise it. Falls back to a fresh fetch only
            // when the cache has not covered this security yet — a holding
            // just added, before the next background refresh.
            let history: [(Date, Double)]
            if let cachedRow = upcomingDividends.first(where: { $0.id == holding.securityKey }) {
                detail.upcoming = cachedRow.info
                history = cachedRow.history.map { ($0.date, $0.perUnit) }
            } else {
                let (upcoming, fetchedHistory) = await repository.upcomingDividend(ticker: ticker)
                detail.upcoming = upcoming
                history = fetchedHistory
            }
            events = history
            detail.dividendEvents = history

            // Five years of weekly closes: enough to compute a trailing-yield
            // line without pulling daily bars the chart would only average away.
            // 10y, not 5y: a longer lookback gives a steadier reference band,
            // and this only ever returns as much as the provider actually has
            // — a fund younger than ten years just gets its whole history, the
            // same graceful degradation the 5-year window always had.
            let bars = await repository.fetchHistory(ticker, range: "10y", interval: "1wk")
            detail.yieldHistory = HoldingDetailMath.buildYieldHistory(priceBars: bars, events: history)
            detail.tenYearAverageYieldPercent =
                HoldingDetailMath.tenYearAverageYield(detail.yieldHistory)
        }

        // ── Position ──────────────────────────────────────────────────────
        let price = detail.quote?.price ?? currentPrice(for: holding)
        let units = position.units
        detail.marketValue = price * units
        if let cost = position.costBasis, units > 0 {
            detail.averageCost = cost / units
            detail.totalContributions = cost
            detail.priceReturn = detail.marketValue - cost
            if cost > 0 {
                detail.priceReturnPercent = (detail.marketValue - cost) / cost * 100
            }
        }

        let received = dividends(forSecurity: position.key)
        detail.allTimeReceived = received.reduce(0) { $0 + $1.amount }
        detail.receivedFlows = received.map { ($0.paidAt, $0.amount) }

        if let priceReturn = detail.priceReturn {
            detail.totalReturn = priceReturn + detail.allTimeReceived
            if let cost = holding.costBasis, cost > 0 {
                detail.totalReturnPercent = (priceReturn + detail.allTimeReceived) / cost * 100
            }
        }

        let portfolioValue = holdings.reduce(0.0) { $0 + repository.value(of: $1) }
        if portfolioValue > 0 {
            let positionValue = position.slices.reduce(0.0) { $0 + repository.value(of: $1.holding) }
            detail.portfolioWeightPercent = positionValue / portfolioValue * 100
        }

        // ── Dividends ─────────────────────────────────────────────────────
        if !events.isEmpty {
            let ttm = HoldingDetailMath.trailingTwelveMonths(events)
            detail.trailingAnnualPerUnit = ttm > 0 ? ttm : nil
            if ttm > 0, price > 0 {
                detail.yieldTTMPercent = ttm / price * 100
            }
            if ttm > 0, let cost = position.costBasis, cost > 0, units > 0 {
                detail.yieldOnCostPercent = (ttm * units) / cost * 100
            }
            detail.divGrowth1YPercent = HoldingDetailMath.dividendCAGR(events, years: 1)
            detail.divGrowth5YPercent = HoldingDetailMath.dividendCAGR(events, years: 5)
            if let recent = HoldingDetailMath.mostRecentChange(events) {
                detail.divChangeRecentPercent = recent.percent
                detail.divChangeRecentLabel = Self.shortMonth.string(from: recent.at)
            }
            detail.divChangeVs1YPercent = HoldingDetailMath.changeVsYearAgo(events)
            detail.frequencyPerYear = detail.upcoming?.paymentFrequencyPerYear
                ?? HoldingDetailMath.inferFrequency(events)
        } else {
            detail.frequencyPerYear = detail.upcoming?.paymentFrequencyPerYear
        }

        // ── Forward projection ────────────────────────────────────────────
        let now = Date()
        // nil, not `divGrowth5YPercent`: that figure is a DISPLAY stat —
        // floored at 0% and clamped to 15% so a badge never reads negative or
        // absurd — and feeding it to the forecast bypassed every safeguard
        // DividendForecast.measuredGrowth has. It could not go negative even
        // for a fund actually cutting distributions, and it compounded
        // undamped for the full ten years, which is why this chart climbed
        // ~12%/yr in a straight line while the portfolio-wide Dividends chart
        // — built from the SAME history through `measuredGrowth` — came out
        // nearly flat for the very same holding. `nil` measures this fund's
        // own growth the same way every other forecast call in the app does,
        // so the two charts finally agree.
        let projection = DividendForecast.project(
            history: events,
            upcoming: detail.upcoming,
            from: now,
            until: now.addingTimeInterval(365 * 24 * 60 * 60 * 10),
            growthRate: nil
        )
        detail.projectedFlows = projection.map { ($0.date, $0.perUnit * units) }

        let nextYear = projection.filter {
            $0.date <= now.addingTimeInterval(365 * 24 * 60 * 60)
        }
        let forwardPerUnit = nextYear.reduce(0) { $0 + $1.perUnit }
        if forwardPerUnit > 0 {
            detail.estimatedAnnualIncome = forwardPerUnit * units
        }

        // ── Payout schedule ───────────────────────────────────────────────
        //
        // The next expected payment, and payments the user actually has on
        // record. The same rule the Android build uses, and for the same two
        // reasons.
        //
        // It listed a year of projections before, so a quarterly payer filled
        // the screen with four dated forecasts — "Jul 7, 2027" reads as a fact
        // when it is a guess extrapolated from a growth rate. One upcoming row
        // is a forecast; five look like a schedule somebody published.
        //
        // And past entries came from the FUND's distribution history, which is
        // not this user's: buying XEQT today does not mean you were paid its
        // June distribution. Anything from before the holding existed has to be
        // entered by hand under Dividends Received.
        let logged = dividends(forSecurity: position.key)

        // Announced payments are imported into the user's own records, so the
        // next forecast can be the same dividend a second time. Deduped within
        // half a payment interval, derived from how often this holding pays.
        let perYear = max(detail.upcoming?.paymentFrequencyPerYear ?? 4, 1)
        let dedupeWindow = 365.0 / Double(perYear) / 2 * 24 * 60 * 60
        func alreadyLogged(_ date: Date) -> Bool {
            logged.contains { abs($0.paidAt.timeIntervalSince(date)) <= dedupeWindow }
        }

        var payouts: [PayoutRow] = []
        if let next = nextYear.first, !alreadyLogged(next.date) {
            // The forecaster anchors on the payout calendar's PAY date where
            // one was published, falling back to the ex-date only when it was
            // not — so a row is only labelled as an ex-date when that is
            // genuinely all the calendar gave.
            let hasPayDate = detail.upcoming?.payDate != nil
            payouts.append(
                PayoutRow(
                    date: next.date,
                    exDate: detail.upcoming?.exDividendDate,
                    isExDateFallback: !hasPayDate,
                    amountPerUnit: next.perUnit,
                    totalForPosition: next.perUnit * units,
                    isUpcoming: true,
                    isAnnounced: next.isAnnounced
                )
            )
        }
        payouts += logged
            .sorted { $0.paidAt > $1.paidAt }
            .prefix(24)
            .map { payment in
                // A logged payment records the day the money arrived. There is
                // no ex-date to know for it, and inventing one would be worse
                // than leaving it out.
                PayoutRow(
                    date: payment.paidAt,
                    exDate: nil,
                    isExDateFallback: false,
                    amountPerUnit: units > 0 ? payment.amount / units : 0,
                    totalForPosition: payment.amount,
                    isUpcoming: false
                )
            }

        detail.payouts = payouts.sorted { $0.date > $1.date }

        return detail
    }

    private static let shortMonth: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "MMM yyyy"
        return f
    }()

    func flush() async {
        await PortfolioStore.shared.flush()
    }
}
