import Foundation

/// What a live lookup found while the user is still typing a ticker.
struct QuotePreview {
    var price: Double?
    var name: String?
    var found: Bool
}

/// A portfolio value line, together with what it took to get there.
///
/// The points on their own cannot answer "how did I do over this period?". A
/// line that climbs from 43,000 to 68,000 has climbed either because the market
/// did well or because the user paid 25,000 into it, and nothing in the shape
/// of the line distinguishes the two. The money that moved in and out travels
/// with the series so the figure printed above the chart can subtract it.
struct PortfolioSeries {
    var points: [(Date, Double)] = []

    /// Money paid in less money taken out, inside the window the points cover,
    /// in the reporting currency. Flows before the opening point are already
    /// priced into it and are not counted here.
    var netContributions: Double = 0

    /// False when the line starts later than the range asked for — a portfolio
    /// three weeks old has no year to draw, and the card says so rather than
    /// labelling three weeks "1Y".
    var coversRequestedRange: Bool = true

    var isEmpty: Bool { points.count < 2 }
    var startDate: Date? { points.first?.0 }
    var startValue: Double { points.first?.1 ?? 0 }
    var endValue: Double { points.last?.1 ?? 0 }

    /// What the portfolio actually made over the window.
    ///
    /// End minus start calls a deposit a gain, which is how a screen comes to
    /// report +55% in a month the market moved 2%. Netting the flows out leaves
    /// the part the holdings earned.
    var gain: Double { endValue - startValue - netContributions }

    /// `gain` against what was actually at risk to earn it.
    ///
    /// The denominator is the opening value plus the money added, not the
    /// opening value alone: measuring a whole month's gain against a starting
    /// balance of nearly nothing turns a modest return into a meaningless
    /// multiple. It is a simple approximation of a money-weighted return, not
    /// a true one — the daily-weighted version lives in PerformanceCalculator,
    /// where a report can afford the work.
    var gainPercent: Double {
        let invested = startValue + netContributions
        guard invested > 0.01 else { return 0 }
        return gain / invested * 100
    }

    /// Points in the shape the charts take.
    var chartPoints: [ChartPoint] { points.map { ChartPoint(date: $0.0, value: $0.1) } }
}

/// The layer between the view model and the store/network.
/// Ported from `data/PortfolioRepository.kt`.
///
/// Everything that changes the portfolio goes through here, so the rules that
/// must hold across a change — how a sale reduces cost basis, that saving a
/// tickered holding fetches its price straight away — live in one place rather
/// than being re-derived on each screen.
@MainActor
final class PortfolioRepository {

    private let store = PortfolioStore.shared

    // MARK: - Alerts

    func alerts() async -> [PriceAlert] {
        await store.mutate { $0.alerts }
    }

    func addAlert(ticker: String, kind: AlertKind, threshold: Double) async {
        await store.mutate { doc in
            doc.alerts.append(
                PriceAlert(
                    // Uppercased and trimmed here rather than trusted from the
                    // text field: the quote endpoint is case-sensitive about
                    // symbols, and "vdy.to " would simply never match anything
                    // — an alert that looks saved and can never fire.
                    ticker: ticker.trimmingCharacters(in: .whitespaces).uppercased(),
                    kind: kind,
                    threshold: threshold
                )
            )
        }
    }

    func setAlertEnabled(_ id: UUID, enabled: Bool) async {
        await store.mutate { doc in
            guard let i = doc.alerts.firstIndex(where: { $0.id == id }) else { return }
            doc.alerts[i].enabled = enabled
            // Re-arming on re-enable, deliberately. A rule switched off while
            // its condition was true would otherwise come back already latched
            // and stay silent through the next genuine crossing.
            if enabled {
                doc.alerts[i].triggeredAt = nil
                doc.alerts[i].lastValue = nil
            }
        }
    }

    func deleteAlert(_ id: UUID) async {
        await store.mutate { doc in
            doc.alerts.removeAll { $0.id == id }
        }
    }

    /// Writes back the latch state the engine computed.
    ///
    /// Takes ONLY the rows the engine actually changed, and matches each by id
    /// rather than replacing the array: the engine's network calls take
    /// seconds, and writing back its whole pre-fetch snapshot would undo an
    /// alert the user added, deleted or re-armed on the Alerts screen in the
    /// meantime. Only `triggeredAt` and `lastValue` are copied, for the same
    /// reason — a threshold edited mid-pass stays edited.
    func applyAlertState(_ changed: [PriceAlert]) async {
        guard !changed.isEmpty else { return }
        await store.mutate { doc in
            for change in changed {
                guard let i = doc.alerts.firstIndex(where: { $0.id == change.id }) else { continue }
                doc.alerts[i].triggeredAt = change.triggeredAt
                doc.alerts[i].lastValue = change.lastValue
            }
        }
    }
    private let client = QuoteClient.shared
    private let news = NewsClient.shared
    private let fx = FxRates.shared


    /// Currency every portfolio-level total is expressed in.
    ///
    /// Per-holding screens still show a position in its own currency — that's
    /// the number on the statement — but anything that adds two holdings
    /// together has to pick one currency and convert, or it is adding US
    /// dollars to Canadian ones.
    var baseCurrency: String = "CAD"

    // MARK: - Reading

    func load() async -> PortfolioDocument {
        await store.load()
    }

    /// Fetches any FX rate needed to value `holdings` in `baseCurrency`.
    func ensureRates(for holdings: [Holding]) async {
        await fx.refresh(currencies: holdings.map(\.normalizedCurrency), base: baseCurrency)
    }

    // MARK: - Accounts

    @discardableResult
    func upsertAccount(_ account: Account) async -> UUID {
        await store.mutate { (doc: inout PortfolioDocument) -> UUID in
            if let index = doc.accounts.firstIndex(where: { $0.id == account.id }) {
                doc.accounts[index] = account
            } else {
                doc.accounts.append(account)
            }
            return account.id
        }
    }

    /// Changes an existing account's tax treatment without touching its holdings.
    func setAccountTaxTreatment(accountId: UUID, treatment: TaxTreatment?) async {
        await store.mutate { doc in
            guard let index = doc.accounts.firstIndex(where: { $0.id == accountId }) else { return }
            doc.accounts[index].taxTreatment = treatment
        }
    }

    /// Deleting an account cascades to its holdings, and through them to their
    /// transactions and dividends — the same `ON DELETE CASCADE` the Room
    /// schema declared, done explicitly because a document store has no
    /// foreign keys to do it for us.
    func deleteAccount(_ id: UUID) async {
        await store.mutate { doc in
            let holdingIds = Set(doc.holdings.filter { $0.accountId == id }.map(\.id))
            doc.accounts.removeAll { $0.id == id }
            doc.holdings.removeAll { $0.accountId == id }
            doc.dividends.removeAll { holdingIds.contains($0.holdingId) }
            doc.transactions.removeAll { holdingIds.contains($0.holdingId) }
            doc.priceSnapshots.removeAll { holdingIds.contains($0.holdingId) }
        }
    }

    /// The account a new holding should default to, or nil when there are none.
    ///
    /// This used to get-or-CREATE an account called "My holdings" with no tax
    /// treatment set. That default was the problem: a holding filed into it
    /// silently produced no tax information at all, which on screen is
    /// indistinguishable from "there is no tax to report". Nothing is created
    /// here now — the transaction form asks, and will not save until it has an
    /// answer.
    func defaultAccount() async -> UUID? {
        await store.mutate { (doc: inout PortfolioDocument) -> UUID? in
            doc.accounts.first?.id
        }
    }

    // MARK: - Holdings

    func deleteHolding(_ id: UUID) async {
        await store.mutate { doc in
            doc.holdings.removeAll { $0.id == id }
            doc.dividends.removeAll { $0.holdingId == id }
            doc.transactions.removeAll { $0.holdingId == id }
            doc.priceSnapshots.removeAll { $0.holdingId == id }
        }
    }

    /// Saves a holding, either creating a new one or updating one in place
    /// (e.g. "bought more XEQT") without deleting and recreating it.
    ///
    /// Fetches a live quote immediately afterwards for market-priced holdings
    /// with a ticker, so a new position doesn't sit at $0 until the next
    /// background poll.
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
        let cleanTicker: String? = ticker.flatMap { $0.trimmingCharacters(in: .whitespaces).uppercased().nilIfEmpty }

        let id: UUID = await store.mutate { (doc: inout PortfolioDocument) -> UUID in
            if let existingId, let index = doc.holdings.firstIndex(where: { $0.id == existingId }) {
                doc.holdings[index].accountId = accountId
                doc.holdings[index].name = name
                doc.holdings[index].ticker = cleanTicker
                doc.holdings[index].type = type
                doc.holdings[index].units = units
                doc.holdings[index].manualPrice = manualPrice
                doc.holdings[index].currency = currency
                doc.holdings[index].costBasis = costBasis
                return existingId
            }
            let holding = Holding(
                accountId: accountId,
                name: name,
                ticker: cleanTicker,
                type: type,
                units: units,
                manualPrice: manualPrice,
                currency: currency,
                lastKnownPrice: nil,
                lastPriceAt: nil,
                costBasis: costBasis
            )
            doc.holdings.append(holding)
            return holding.id
        }

        if cleanTicker != nil, type == .etf || type == .stock || type == .crypto {
            // refreshQuote also adopts the exchange's real currency, so a US
            // listing stops being labelled CAD just because that's the default.
            await refreshQuote(holdingId: id)
        }
        return id
    }

    /// Debounced live lookup for the add/edit form, shown as
    /// "Live now: $X.XX · <name>" before the user saves. Doesn't touch the store.
    func previewQuote(ticker: String) async -> QuotePreview {
        let trimmed = ticker.trimmingCharacters(in: .whitespaces).uppercased()
        guard !trimmed.isEmpty else { return QuotePreview(price: nil, name: nil, found: false) }
        guard let quote = try? await client.fetchQuote(ticker: trimmed) else {
            return QuotePreview(price: nil, name: nil, found: false)
        }
        return QuotePreview(price: quote.price, name: quote.name, found: true)
    }

    func refreshQuote(holdingId: UUID) async {
        let holding: Holding? = await store.load().holdings.first { $0.id == holdingId }
        guard let holding, let ticker = holding.ticker else { return }
        guard let quote = try? await client.fetchQuote(ticker: ticker) else { return }

        await store.mutate { doc in
            guard let index = doc.holdings.firstIndex(where: { $0.id == holdingId }) else { return }
            doc.holdings[index].lastKnownPrice = quote.price
            doc.holdings[index].lastPriceAt = Date()
            // The exchange knows what the listing actually trades in; the
            // stored value is only a default until a quote tells us otherwise.
            if let currency = quote.currency { doc.holdings[index].currency = currency }
            // Same argument for the instrument type. A holding created before
            // the search results were classified correctly can be sitting on
            // the wrong type; the provider knows better, so let the refresh
            // repair it rather than making the user delete and re-add.
            doc.holdings[index].type = Self.correctedType(
                current: doc.holdings[index].type,
                instrumentType: quote.instrumentType
            )
            doc.priceSnapshots.append(
                PriceSnapshot(holdingId: holdingId, at: Date(), price: quote.price)
            )
        }
    }

    /// Writes back the classification and currency a freshly-fetched quote
    /// implies, without spending another request.
    ///
    /// The detail screen already has a full quote in hand, and a holding filed
    /// under the wrong type is most likely to be noticed there — so opening the
    /// screen is what repairs it, rather than waiting for a background refresh.
    func applyQuoteMetadata(holdingId: UUID, quote: Quote) async {
        await store.mutate { doc in
            guard let index = doc.holdings.firstIndex(where: { $0.id == holdingId }) else { return }
            let type = Self.correctedType(
                current: doc.holdings[index].type,
                instrumentType: quote.instrumentType
            )
            let currency = quote.currency?.uppercased() ?? doc.holdings[index].currency
            guard type != doc.holdings[index].type || currency != doc.holdings[index].currency else {
                return
            }
            doc.holdings[index].type = type
            doc.holdings[index].currency = currency
        }
    }

    /// The holding type a quote implies, or the stored one when the quote says
    /// nothing useful.
    ///
    /// Only the market-priced types are ever overwritten. Seg funds, cash and
    /// "other" are the user's own classification of something the market does
    /// not label, so a provider's guess must not replace them.
    private static func correctedType(current: HoldingType, instrumentType: String?) -> HoldingType {
        if current == .segFund || current == .cash || current == .other { return current }
        return HoldingType.fromQuoteType(instrumentType) ?? current
    }

    /// Refreshes every held ticker and returns what came back.
    ///
    /// One batched request for every held ticker rather than a sequential
    /// per-holding call, which is what made the portfolio slow to settle.
    ///
    /// Returns the quotes rather than a day-change figure. The day change used
    /// to be computed here and handed to the view model as a single number,
    /// which meant it was a snapshot of ONE fetch while the portfolio total it
    /// was shown against came from the stored document — two different moments,
    /// two different sets of holdings whenever a fetch failed. It is derived
    /// from stored state now (see `PortfolioViewModel.dayChange`), and this
    /// method's job is just to bring that state up to date.
    ///
    /// Handing the quotes back also lets the caller fill its own quote cache
    /// without a second round of requests for the same tickers — which was
    /// doubling the request count of every pull-to-refresh and pushing the
    /// rate limiter into a cooldown that made the NEXT refresh fail.
    @discardableResult
    func refreshAllQuotes() async -> [String: Quote] {
        let holdings = await store.load().holdings.filter { $0.ticker != nil }
        guard !holdings.isEmpty else { return [:] }

        let tickers = Array(Set(holdings.compactMap(\.ticker)))
        var quotes: [String: Quote] = [:]

        for ticker in tickers {
            guard let quote = try? await client.fetchQuote(ticker: ticker) else { continue }
            quotes[ticker] = quote
        }
        guard !quotes.isEmpty else { return [:] }

        let now = Date()
        await store.mutate { doc in
            for index in doc.holdings.indices {
                guard let ticker = doc.holdings[index].ticker,
                      let quote = quotes[ticker] else { continue }
                doc.holdings[index].lastKnownPrice = quote.price
                // Stored together, so the pair is always from one quote. A
                // price updated without its close would report a day change
                // measured against a different session.
                if let previous = quote.previousClose, previous > 0 {
                    doc.holdings[index].previousClose = previous
                }
                doc.holdings[index].lastPriceAt = now
                if let currency = quote.currency { doc.holdings[index].currency = currency }
                doc.holdings[index].type = Self.correctedType(
                    current: doc.holdings[index].type,
                    instrumentType: quote.instrumentType
                )
                doc.priceSnapshots.append(
                    PriceSnapshot(holdingId: doc.holdings[index].id, at: now, price: quote.price)
                )
            }
            // Snapshots accumulate on every refresh and only the recent ones
            // are ever charted, so the tail is trimmed rather than left to grow
            // without bound.
            if doc.priceSnapshots.count > 4000 {
                doc.priceSnapshots = Array(doc.priceSnapshots.suffix(3000))
            }
        }

        await ensureRates(for: holdings)
        return quotes
    }

    // MARK: - Valuation

    /// A holding's market value converted into `baseCurrency`, for anything
    /// that sums across the portfolio — totals, allocation weights, weight.
    func value(of holding: Holding) -> Double {
        fx.convert(holding.nativeValue, from: holding.normalizedCurrency, to: baseCurrency)
    }

    /// The currency a ticker's listing most likely trades in, used to label a
    /// figure before a quote has arrived.
    func currency(forTicker ticker: String?, fallback: String = "CAD") -> String {
        guard let t = ticker?.trimmingCharacters(in: .whitespaces).uppercased(), !t.isEmpty else {
            return fallback
        }
        if t.hasSuffix("-USD") { return "USD" }
        guard let dot = t.lastIndex(of: ".") else { return "USD" }
        switch String(t[dot...]) {
        case ".TO", ".V", ".NE", ".CN", ".TSX": return "CAD"
        case ".L": return "GBP"
        case ".DE", ".F", ".BE", ".PA", ".AS", ".MC", ".MI", ".BR", ".LS", ".VI", ".IR": return "EUR"
        case ".SW": return "CHF"
        case ".ST": return "SEK"
        case ".OL": return "NOK"
        case ".CO": return "DKK"
        case ".HE": return "EUR"
        case ".T": return "JPY"
        case ".HK": return "HKD"
        case ".SS", ".SZ": return "CNY"
        case ".AX": return "AUD"
        case ".NZ": return "NZD"
        case ".NS", ".BO": return "INR"
        case ".SA": return "BRL"
        case ".MX": return "MXN"
        case ".KS", ".KQ": return "KRW"
        case ".TW", ".TWO": return "TWD"
        case ".SI": return "SGD"
        case ".JK": return "IDR"
        case ".PS": return "PHP"
        case ".KL": return "MYR"
        case ".BK": return "THB"
        case ".JO": return "ZAR"
        default: return fallback
        }
    }

    // MARK: - Portfolio value history

    /// The portfolio's value over a range, built from each holding's own price
    /// history so the longer ranges have real data from day one rather than
    /// only what the app has locally recorded.
    ///
    /// Units are held constant at today's count. A position's history is the
    /// history of what is owned *now*, which is the honest reading for a
    /// "portfolio value" line — the alternative, replaying every transaction
    /// backwards, turns a price chart into a contributions chart and makes a
    /// good month indistinguishable from a deposit.
    /// Portfolio value over time, rebuilt the way the Android build does it.
    ///
    /// Two things were wrong with summing each day's bars as they arrived.
    ///
    /// A day on which the TSX traded and NASDAQ did not — or the other way
    /// round — produced a total containing only the listings that happened to
    /// print that day, so the line dropped to a fraction of the portfolio and
    /// came straight back. Those were the spikes to the floor. Prices are now
    /// carried forward across a shared timeline: a holding keeps its last known
    /// close on a day its own exchange was shut.
    ///
    /// And the series ran across the whole range regardless of when anything
    /// was actually bought, drawing a year of "portfolio value" for a position
    /// held since August. Each holding now carries a unit schedule replayed
    /// from its transactions, so it contributes nothing before the day it was
    /// first held, and the chart starts where ownership does.
    func portfolioValueSeries(range: String, interval: String) async -> PortfolioSeries {
        let document = await store.load()
        // Device zone: a "day" on this axis is the user's calendar day, which
        // is what the labels under the chart are read as.
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = .current
        let transactionsByHolding = Dictionary(grouping: document.transactions, by: \.holdingId)

        // A position sold down to zero still counts toward what the portfolio
        // was worth before it was sold, so it stays in the series as long as
        // the log says it was once held.
        let holdings = document.holdings.filter { holding in
            guard let ticker = holding.ticker, !ticker.isEmpty else { return false }
            return holding.units > 0 || !(transactionsByHolding[holding.id] ?? []).isEmpty
        }
        guard !holdings.isEmpty else { return PortfolioSeries() }

        await ensureRates(for: holdings)

        // One request per SECURITY, not per holding row: the same fund in three
        // accounts is one price history.
        //
        // Keyed on the UPPERCASED ticker, the same key the dashboard totals
        // group on. Keyed on the raw string, "XEQT.TO" and "xeqt.to" were two
        // securities here and one everywhere else, which is the kind of
        // disagreement that makes a chart and a headline figure describe
        // different portfolios.
        var rowsByTicker: [String: [Holding]] = [:]
        for holding in holdings {
            let ticker = (holding.ticker ?? "")
                .trimmingCharacters(in: .whitespacesAndNewlines)
                .uppercased()
            guard !ticker.isEmpty else { continue }
            rowsByTicker[ticker, default: []].append(holding)
        }

        struct Series {
            let bars: [(Date, Double)]
            let currency: String
            let schedule: UnitSchedule
        }

        var series: [Series] = []
        // Securities whose price history could not be fetched. They are NOT
        // dropped: a security that silently leaves the chart takes its whole
        // market value with it, and the line then sits below the total on the
        // card above it with nothing to explain the gap. That is the bug this
        // guards against — one failed history request and the Y axis reads a
        // portfolio the user does not have. Their current value rides along as
        // a flat contribution instead, exactly as hand-priced holdings do.
        var unchartedValue = 0.0

        for (ticker, rows) in rowsByTicker {
            let bars = await client.fetchHistory(ticker: ticker, range: range, interval: interval)
                .filter { $0.1 > 0 }
                .sorted { $0.0 < $1.0 }
            guard !bars.isEmpty else {
                unchartedValue += rows.reduce(0.0) { $0 + value(of: $1) }
                continue
            }

            // The rows share a price history but not a transaction log, so the
            // schedules are merged into one for the security.
            let merged = rows
                .map { unitSchedule(for: $0, transactions: transactionsByHolding[$0.id] ?? [], historyStart: bars[0].0) }
                .reduce(UnitSchedule.empty) { $0.merged(with: $1) }

            series.append(
                Series(bars: bars, currency: rows[0].normalizedCurrency, schedule: merged)
            )
        }
        guard !series.isEmpty else { return PortfolioSeries() }

        // Start when the user first held anything, but never before the
        // earliest price we actually have.
        let ownedFrom = series.map(\.schedule.ownedFrom).min() ?? Date.distantPast
        let firstBar = series.compactMap { $0.bars.first?.0 }.min() ?? Date.distantPast
        let start = max(ownedFrom, firstBar)

        // `map { $0.0 }`, not `map(\.0)`: Swift has no key paths to tuple
        // elements, which is a compile error rather than a runtime one.
        var timeline = Array(Set(series.flatMap { $0.bars.map { $0.0 } }.filter { $0 >= start })).sorted()

        // One point per calendar day, on any range whose bars are daily or
        // coarser.
        //
        // The timeline is the UNION of every security's bars, and the providers
        // do not agree on what time of day a daily bar is stamped: the fallback
        // chart endpoint returns midnight UTC while Yahoo returns the opening
        // bell, so one TSX holding and one NYSE holding could put two points on
        // the axis for the same Tuesday — a sawtooth in the line, and a date
        // under the middle of the chart that belonged to neither bar. Keeping
        // the latest stamp in each day keeps the close and drops the twin.
        let intradayBars = interval.hasSuffix("m") || interval.hasSuffix("h")
        if !intradayBars {
            var latestPerDay: [Date: Date] = [:]
            for stamp in timeline {
                let day = calendar.startOfDay(for: stamp)
                latestPerDay[day] = max(latestPerDay[day] ?? stamp, stamp)
            }
            timeline = latestPerDay.values.sorted()
        }
        guard timeline.count >= 2 else { return PortfolioSeries() }

        // Walk the shared timeline once, advancing a price cursor and a units
        // cursor per series, so this stays linear rather than re-scanning.
        var priceCursor = [Int](repeating: 0, count: series.count)
        var lastPrice = [Double](repeating: 0, count: series.count)
        var unitCursor = [Int](repeating: 0, count: series.count)
        var heldUnits = series.map(\.schedule.openingUnits)

        // Hand-priced holdings have no series to fetch, so their value rides
        // along as a flat line rather than dropping out of the total.
        let manualValue = document.holdings
            .filter { ($0.ticker ?? "").isEmpty }
            .reduce(0.0) { $0 + value(of: $1) }
        let flatValue = manualValue + unchartedValue

        var points = timeline.map { timestamp -> (Date, Double) in
            var total = flatValue
            for (index, entry) in series.enumerated() {
                var p = priceCursor[index]
                while p < entry.bars.count, entry.bars[p].0 <= timestamp {
                    lastPrice[index] = entry.bars[p].1
                    p += 1
                }
                priceCursor[index] = p

                var u = unitCursor[index]
                while u < entry.schedule.steps.count, entry.schedule.steps[u].0 <= timestamp {
                    heldUnits[index] = entry.schedule.steps[u].1
                    u += 1
                }
                unitCursor[index] = u

                // Nothing held, or nothing priced yet, contributes nothing
                // rather than a zero-price point.
                if heldUnits[index] > 0, lastPrice[index] > 0 {
                    // Today's rate across the whole series. A true historical
                    // reconstruction would need the rate on each date; this
                    // keeps the line's shape driven by the assets rather than
                    // by FX drift.
                    total += fx.convert(lastPrice[index] * heldUnits[index], from: entry.currency, to: baseCurrency)
                }
            }
            return (timestamp, total)
        }

        // The last point is the portfolio as it stands RIGHT NOW.
        //
        // Everything above is built from closing bars, and the newest bar can
        // be up to a whole interval stale — half an hour on the 1D chart, a
        // full session on the longer ones. The card above this chart shows the
        // live total, so leaving the series to end on a stale bar puts two
        // different numbers for the same thing on one screen, and the axis
        // labels are read as wrong because next to the headline figure they
        // are. Replacing the final point costs nothing — it is the same
        // quantity, measured a few minutes later — and makes the chart end
        // where the total says it should.
        let liveTotal = document.holdings.reduce(0.0) { $0 + value(of: $1) }
        var endsOnLiveTotal = false
        if liveTotal > 0, let last = points.last {
            points[points.count - 1] = (last.0, liveTotal)
            endsOnLiveTotal = true
        }

        // A weekend point that repeats Friday's number is a forward fill, not a
        // session.
        //
        // Some providers pad daily series out to the calendar, which put a
        // Saturday and a Sunday on a chart of the Toronto exchange and let the
        // axis label a Sunday as the middle of the week. Dropping only the ones
        // whose value is unchanged is deliberately narrow: a crypto holding
        // really does move over a weekend, and that point stays.
        if !intradayBars, points.count > 2 {
            var filtered: [(Date, Double)] = [points[0]]
            for index in 1..<points.count {
                let (date, value) = points[index]
                let isWeekend = calendar.isDateInWeekend(date)
                let unchanged = abs(value - (filtered.last?.1 ?? value)) < 0.01
                // The closing point always survives — it is the portfolio as it
                // stands now, whatever day of the week it falls on.
                if isWeekend, unchanged, index != points.count - 1 { continue }
                filtered.append((date, value))
            }
            points = filtered
        }
        guard points.count >= 2 else { return PortfolioSeries() }

        // Money in and out WITHIN the drawn window, read off the UNIT SCHEDULE
        // rather than the transaction log.
        //
        // The log was the obvious source and it was the wrong one. A holding
        // typed straight in — a name, a ticker and a unit count, which is how
        // most of this portfolio was entered — has no transactions at all. Its
        // schedule still steps from zero to its full size on the day it was
        // added, so the line jumps by the whole position, and with nothing in
        // the log to subtract, that jump was reported as growth. Hence
        // "+71.60% over 1M" on a month the market moved a couple of per cent.
        //
        // The schedule is the honest source because it is what the line is
        // actually drawn from: every step in it moves the line, whether a
        // transaction explains it or not. Units gained times the price at that
        // moment is the money that arrived; there is nothing left for a jump to
        // hide behind.
        let windowStart = points[0].0
        // Up to NOW, not up to the last bar, whenever the line ends on the live
        // total — which it almost always does. The closing value is every unit
        // held at this moment, so a buy entered at eight this evening is
        // already in it while the newest daily bar is stamped this morning;
        // bounding the flows at the bar would leave that purchase counted as
        // growth and report the deposit as a gain.
        let windowEnd = endsOnLiveTotal
            ? max(points[points.count - 1].0, Date())
            : points[points.count - 1].0

        var netContributions = 0.0
        for entry in series {
            // Where the position stood as the window opened. Steps at or before
            // that moment are already priced into the opening value, and
            // counting them would subtract the whole position twice.
            var units: Double = entry.schedule.openingUnits
            for step in entry.schedule.steps where step.0 <= windowStart {
                units = step.1
            }

            for step in entry.schedule.steps where step.0 > windowStart && step.0 <= windowEnd {
                let delta: Double = step.1 - units
                units = step.1
                // A millionth of a share is a rounding artefact, not a deposit.
                guard abs(delta) > 1e-6 else { continue }
                let price: Double = Self.price(in: entry.bars, at: step.0)
                guard price > 0 else { continue }
                let cash: Double = delta * price
                netContributions += fx.convert(cash, from: entry.currency, to: baseCurrency)
            }
        }

        // Hand-priced and unchartable holdings are deliberately NOT counted
        // here. They ride the window as a flat line at today's value, so one
        // added mid-window never produced a jump for a contribution to explain;
        // subtracting it would invent a loss the size of the holding.

        return PortfolioSeries(
            points: points,
            netContributions: netContributions,
            // `start` is where the clipping put the line and `firstBar` is the
            // oldest price we were given for the range that was asked for. When
            // the first is later than the second, the chart is showing less
            // than its own chip claims, and the card above it has to say so.
            coversRequestedRange: start <= firstBar
        )
    }

    /// How many units of a holding were held over time.
    ///
    /// `openingUnits` is in force from `ownedFrom` until the first entry in
    /// `steps`; each step then gives the unit count from its own timestamp
    /// onward. `ownedFrom` is what pulls the chart's start forward to the day
    /// the user actually began holding.
    struct UnitSchedule {
        var ownedFrom: Date
        var openingUnits: Double
        var steps: [(Date, Double)]

        static let empty = UnitSchedule(ownedFrom: .distantFuture, openingUnits: 0, steps: [])

        /// Two rows of the same security, added together. The merged schedule
        /// starts at the earlier of the two and steps wherever either steps.
        func merged(with other: UnitSchedule) -> UnitSchedule {
            guard ownedFrom != Date.distantFuture else { return other }
            guard other.ownedFrom != Date.distantFuture else { return self }

            let dates = Array(Set(steps.map { $0.0 } + other.steps.map { $0.0 })).sorted()
            func units(_ schedule: UnitSchedule, at date: Date) -> Double {
                var value = schedule.openingUnits
                for step in schedule.steps where step.0 <= date { value = step.1 }
                return value
            }
            return UnitSchedule(
                ownedFrom: min(ownedFrom, other.ownedFrom),
                openingUnits: openingUnits + other.openingUnits,
                steps: dates.map { date in (date, units(self, at: date) + units(other, at: date)) }
            )
        }
    }

    /// The last close at or before `date`, or the earliest one there is.
    ///
    /// A step's flow has to be valued at the price that applied when it
    /// happened, not at today's — a buy made a month ago put in what the shares
    /// cost a month ago, and valuing it at the current price would fold this
    /// month's move into the deposit and back out of the gain.
    ///
    /// Linear rather than a binary search: a range holds at most a few hundred
    /// bars and a schedule a handful of steps, so this runs a few hundred
    /// comparisons on a screen that has just made a network request.
    private static func price(in bars: [(Date, Double)], at date: Date) -> Double {
        var result: Double = bars.first?.1 ?? 0
        for bar in bars {
            if bar.0 > date { break }
            result = bar.1
        }
        return result
    }

    /// Replays a holding's transactions into a unit-count timeline.
    ///
    /// The holding row, not the transaction log, is the position of record, so
    /// the replay is reconciled against `holding.units`:
    ///
    ///  - No transactions at all — a position typed in directly — is a constant
    ///    unit count from the day it was added to the app, and zero before it.
    ///  - Transactions that net to the current position start the holding at
    ///    its first buy, with zero units before that.
    ///  - Transactions netting to LESS than the position leave a remainder that
    ///    was evidently held before recording began; it becomes an opening
    ///    balance carried from the start of the available price history.
    ///  - Transactions netting to MORE mean the log and the row disagree.
    ///    Rather than draw a shape the data doesn't support, that holding falls
    ///    back to its current unit count across the whole range.
    private func unitSchedule(
        for holding: Holding,
        transactions: [PortfolioTransaction],
        historyStart: Date
    ) -> UnitSchedule {
        guard !transactions.isEmpty else {
            // The step, rather than an opening balance, is what keeps the
            // holding out of the chart before the date it was added.
            return UnitSchedule(
                ownedFrom: holding.createdAt,
                openingUnits: 0,
                steps: [(holding.createdAt, holding.units)]
            )
        }

        let ordered = transactions.sorted { $0.at < $1.at }
        var running = 0.0
        var steps: [(Date, Double)] = []
        for transaction in ordered {
            switch transaction.type {
            case .buy, .drip: running += transaction.shares
            case .sell: running -= transaction.shares
            }
            steps.append((transaction.at, running))
        }

        // Tolerance rather than equality: fractional DRIP units don't
        // round-trip through a Double exactly, and a millionth of a share is
        // not a difference anyone is charting.
        let unexplained = holding.units - running
        if unexplained > 1e-6 {
            return UnitSchedule(
                ownedFrom: historyStart,
                openingUnits: unexplained,
                steps: steps.map { ($0.0, $0.1 + unexplained) }
            )
        }
        if unexplained < -1e-6 {
            return UnitSchedule(ownedFrom: historyStart, openingUnits: holding.units, steps: [])
        }
        return UnitSchedule(ownedFrom: ordered[0].at, openingUnits: 0, steps: steps)
    }

    // MARK: - Transactions

    /// Records a trade and applies it to the position.
    ///
    /// Not a log: a transaction is the only thing that moves units and cost
    /// basis, so recording one and updating the holding happen together, in
    /// one mutation. Splitting them is how a saved trade ends up in the
    /// history with a position that never changed.
    ///
    /// The basis rules, matching the Android build:
    ///
    ///  - **Buy** and **DRIP** add units and add the cash spent to the basis.
    ///    A reinvestment is a purchase — shares bought with the dividend still
    ///    cost what they cost, and leaving them out would understate the basis
    ///    and overstate the return forever after.
    ///  - **Sell** removes units and removes basis *proportionally*, not at the
    ///    sale price. Selling a third of a position retires a third of what was
    ///    paid for it; using the sale price instead would let a profitable sale
    ///    drive the remaining basis negative.
    func addTransaction(
        holdingId: UUID,
        type: TransactionType,
        at date: Date,
        shares: Double,
        pricePerShare: Double,
        note: String? = nil,
        sourceDividendId: UUID? = nil
    ) async {
        guard shares > 0 else { return }

        await store.mutate { doc in
            guard let index = doc.holdings.firstIndex(where: { $0.id == holdingId }) else { return }
            let holding = doc.holdings[index]
            let cash = shares * pricePerShare
            let oldUnits = holding.units
            let oldBasis = holding.costBasis ?? 0

            switch type {
            case .buy, .drip:
                doc.holdings[index].units = oldUnits + shares
                doc.holdings[index].costBasis = oldBasis + cash
            case .sell:
                // Never sell more than is held: the alternative is a negative
                // position, which no screen is built to display and which
                // silently corrupts every total it feeds.
                let sold = min(shares, oldUnits)
                let basisOut = oldUnits > 0 ? oldBasis * (sold / oldUnits) : 0
                let remainingBasis = max(oldBasis - basisOut, 0)
                doc.holdings[index].units = max(oldUnits - sold, 0)
                doc.holdings[index].costBasis = remainingBasis > 0 ? remainingBasis : nil
            }

            doc.transactions.append(PortfolioTransaction(
                holdingId: holdingId,
                type: type,
                at: date,
                shares: shares,
                pricePerShare: pricePerShare,
                // The holding's own currency, not the base one. A trade is
                // struck in the currency the security trades in.
                currency: holding.currency,
                note: note,
                sourceDividendId: sourceDividendId
            ))
        }
    }

    /// Removes a transaction and reverses what it did to the position.
    ///
    /// A mistyped trade has to be undoable, and deleting only the row would
    /// leave the units and basis it moved behind — a position quietly wrong
    /// with nothing in the history to explain why.
    func deleteTransaction(_ id: UUID) async {
        await store.mutate { doc in
            guard let txIndex = doc.transactions.firstIndex(where: { $0.id == id }) else { return }
            let tx = doc.transactions[txIndex]

            if let index = doc.holdings.firstIndex(where: { $0.id == tx.holdingId }) {
                let holding = doc.holdings[index]
                let cash = tx.shares * tx.pricePerShare
                let oldBasis = holding.costBasis ?? 0

                switch tx.type {
                case .buy, .drip:
                    let basis = max(oldBasis - cash, 0)
                    doc.holdings[index].units = max(holding.units - tx.shares, 0)
                    doc.holdings[index].costBasis = basis > 0 ? basis : nil
                case .sell:
                    // Basis comes back at the average the position carried when
                    // the sale happened, which is the figure the sale removed.
                    // The sale price is the wrong number — undoing a profitable
                    // sale at it would inflate the basis.
                    let perUnit = holding.units > 0 ? oldBasis / holding.units : tx.pricePerShare
                    doc.holdings[index].units = holding.units + tx.shares
                    doc.holdings[index].costBasis = oldBasis + perUnit * tx.shares
                }
            }

            doc.transactions.remove(at: txIndex)
        }
    }

    // MARK: - Watchlist

    /// Seeds the watchlist, but only when it has never had anything in it.
    ///
    /// Guarded on empty rather than on absence of each symbol, so a user who
    /// deliberately cleared the list does not find it refilled on next launch.
    func seedWatchlistIfEmpty(_ tickers: [String]) async {
        await store.mutate { doc in
            guard doc.watchlist.isEmpty else { return }
            let now = Date()
            doc.watchlist = tickers.enumerated().map { offset, ticker in
                // Staggered timestamps so the seeded order is stable rather
                // than depending on how a sort breaks ties.
                WatchlistItem(
                    ticker: ticker.trimmingCharacters(in: .whitespaces).uppercased(),
                    addedAt: now.addingTimeInterval(Double(offset))
                )
            }
        }
    }

    /// Adds a ticker. Returns false when it was already there.
    ///
    /// The result is what tells the caller whether to bother refreshing
    /// quotes, and what lets the UI say "already on your watchlist" instead of
    /// appearing to do nothing.
    @discardableResult
    func addToWatchlist(_ ticker: String) async -> Bool {
        let clean = ticker.trimmingCharacters(in: .whitespaces).uppercased()
        guard !clean.isEmpty else { return false }

        // Explicitly typed, like `upsertAccount`: a multi-statement closure
        // with a mutation between the returns is where inference gives up.
        return await store.mutate { (doc: inout PortfolioDocument) -> Bool in
            guard !doc.watchlist.contains(where: { $0.ticker.uppercased() == clean }) else {
                return false
            }
            doc.watchlist.append(WatchlistItem(ticker: clean, addedAt: Date()))
            return true
        }
    }

    func removeFromWatchlist(_ id: UUID) async {
        await store.mutate { doc in
            doc.watchlist.removeAll { $0.id == id }
        }
    }

    /// Renames a row, or clears the override when `name` is nil or blank.
    func renameWatchlistItem(_ id: UUID, name: String?) async {
        let trimmed = name?.trimmingCharacters(in: .whitespaces)
        await store.mutate { doc in
            guard let index = doc.watchlist.firstIndex(where: { $0.id == id }) else { return }
            doc.watchlist[index].customName = (trimmed?.isEmpty ?? true) ? nil : trimmed
        }
    }

    /// Stores a fetched quote on its watchlist row.
    ///
    /// So the Markets tab paints real numbers the instant the app opens rather
    /// than a column of spinners waiting on the network. Display-only, and
    /// stamped with `cachedAt` so a stale price can be labelled as one instead
    /// of passing for live.
    func cacheWatchlistQuote(ticker: String, quote: Quote) async {
        let clean = ticker.trimmingCharacters(in: .whitespaces).uppercased()
        await store.mutate { doc in
            guard let index = doc.watchlist.firstIndex(where: {
                $0.ticker.uppercased() == clean
            }) else { return }
            doc.watchlist[index].cachedPrice = quote.price
            doc.watchlist[index].cachedPreviousClose = quote.previousClose
            // Only overwrite a name or currency we actually got: a quote that
            // came back thin should not blank out what a fuller one supplied.
            if let name = quote.name { doc.watchlist[index].cachedName = name }
            if let currency = quote.currency { doc.watchlist[index].cachedCurrency = currency }
            doc.watchlist[index].cachedAt = Date()
        }
    }

    // MARK: - Network pass-throughs
    //
    // Thin by design. The view model talks to one object, so which client
    // answers a given question — and the fact that quotes and news come from
    // different ones — stays here rather than being spread across the screens.

    func fetchQuote(_ ticker: String) async -> Quote? {
        try? await client.fetchQuote(ticker: ticker)
    }

    func fetchHistory(_ ticker: String, range: String, interval: String) async -> [(Date, Double)] {
        await client.fetchHistory(ticker: ticker, range: range, interval: interval)
    }

    func fetchHistoryBars(_ ticker: String, range: String, interval: String) async -> [HistoryBar] {
        await client.fetchHistoryBars(ticker: ticker, range: range, interval: interval)
    }

    func fetchSparkBatch(_ tickers: [String]) async -> [String: SparkQuote] {
        await client.fetchSparkBatch(tickers: tickers)
    }

    /// Extended-hours print for one symbol. The batch form is below, under
    /// Dividends, where it was added.
    func fetchExtended(_ ticker: String) async -> ExtendedQuote? {
        await client.fetchExtended(ticker: ticker)
    }

    func searchSymbols(_ query: String) async -> [SymbolSearchResult] {
        await client.searchSymbols(query: query)
    }

    func fetchMarketNews(homeCountry: String?) async -> NewsResult {
        await news.fetchMarketNews(homeCountry: homeCountry)
    }

    func fetchDividendNews(tickers: [String]) async -> NewsResult {
        await news.fetchDividendNews(tickers: tickers)
    }

    // MARK: - Dividends

    func addDividendPayment(
        holdingId: UUID,
        paidAt: Date,
        amount: Double,
        perUnit: Double?,
        currency: String,
        note: String?
    ) async {
        await store.mutate { doc in
            doc.dividends.append(DividendPayment(
                holdingId: holdingId,
                paidAt: paidAt,
                amount: amount,
                perUnit: perUnit,
                currency: currency,
                note: note
            ))
        }
    }

    func deleteDividendPayment(_ id: UUID) async {
        await store.mutate { doc in
            doc.dividends.removeAll { $0.id == id }
        }
    }

    /// True when this dividend payment has already been reinvested.
    func isDividendReinvested(_ dividendId: UUID) async -> Bool {
        await store.load().transactions.contains { $0.sourceDividendId == dividendId }
    }

    /// The next expected distribution for a ticker, and the record it came from.
    func upcomingDividend(ticker: String) async -> (UpcomingDividend?, [(Date, Double)]) {
        let history = await client.fetchHistoricalDividends(ticker: ticker)
        let upcoming = await client.fetchUpcomingDividend(ticker: ticker)
        return (upcoming, history)
    }

    /// Extended-hours prints for many symbols in one request.
    func fetchExtendedBatch(_ tickers: [String]) async -> [String: ExtendedQuote] {
        await FinanceQueryClient.shared.extendedQuotes(tickers)
    }

    /// Clears cached payout calendars so the next fetch re-scrapes.
    func invalidateDividendCalendars(ticker: String? = nil) async {
        await client.invalidateDividendCalendar(ticker: ticker)
    }

    /// Clears cached chart bars so the next fetch goes to the network.
    func invalidateChartCache(ticker: String? = nil) async {
        await client.invalidateBars(ticker: ticker)
    }

    // MARK: News cache

    /// Headlines kept between launches. Nil when nothing is stored or the
    /// stored copy is old enough that showing it would mislead.
    func cachedNews(_ key: DiskCache.Key) async -> [NewsItem]? {
        guard let stored = await DiskCache.shared.load(key, as: [NewsItem].self) else { return nil }
        // A day. Past that the feed is history rather than news, and an empty
        // tab that fills in is better than yesterday's headlines presented as
        // today's.
        guard !stored.isStale(after: 24 * 60 * 60) else { return nil }
        return stored.value
    }

    func cacheNews(_ key: DiskCache.Key, _ items: [NewsItem]) async {
        // Bounded: the feeds return up to sixty items each and nobody scrolls
        // that far, so the file stays small.
        await DiskCache.shared.save(key, Array(items.prefix(60)))
    }

    func fetchHotStocks() async -> [MarketMover] {
        await client.fetchHotStocks()
    }

    // MARK: - Backup payload

    /// The whole portfolio as a value, for a sync service to upload.
    func backupPayload() async -> PortfolioDocument {
        await store.load()
    }

    func restore(from document: PortfolioDocument) async {
        await store.replace(with: document)
    }
}

extension String {
    /// nil rather than "" — the model uses nil to mean "not set", and an empty
    /// string sneaking in reads as a holding with a blank ticker.
    var nilIfEmpty: String? {
        let trimmed = trimmingCharacters(in: .whitespaces)
        return trimmed.isEmpty ? nil : trimmed
    }
}
