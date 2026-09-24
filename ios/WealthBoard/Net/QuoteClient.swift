import Foundation

/// Live pricing, history, dividends, search and news.
///
/// Ported from `net/YahooQuoteClient.kt`, with the blocking OkHttp calls
/// replaced by async/await. Seg funds and mutual funds are NAV-only and are not
/// looked up here — their history comes from the `PriceSnapshot` rows the app
/// records itself.
///
/// Every method here is best-effort: a failed request returns nil or an empty
/// list rather than throwing into the UI, because a screen that refuses to draw
/// because one request timed out is worse than one showing a slightly stale
/// number. The one exception is the rate limiter, which throws so a caller
/// looping over tickers can stop early instead of queueing a hundred rejections.
actor QuoteClient {

    static let shared = QuoteClient()

    private let backoff = RateLimitBackoff()

    /// Tried before every direct call below. See `FinanceQueryClient` for why,
    /// and for how to point it at a self-hosted server.
    private let financeQuery = FinanceQueryClient.shared

    /// Transport for the dividend payout pages — a separate host, so a
    /// separate session with no quote backoff attached.
    private let payoutSession: URLSession = {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 12
        config.timeoutIntervalForResource = 25
        config.httpAdditionalHeaders = [
            "User-Agent": "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) "
                + "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile Safari/605.1.15"
        ]
        return URLSession(configuration: config)
    }()

    private let session: URLSession = {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 15
        config.timeoutIntervalForResource = 30
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        config.httpAdditionalHeaders = [
            "User-Agent": "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) "
                + "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile Safari/605.1.15",
            "Accept": "application/json,text/plain,*/*"
        ]
        return URLSession(configuration: config)
    }()

    // MARK: - Endpoints
    //
    // URL fragments are assembled at runtime for the same reason as on Android:
    // it keeps the full host out of the binary's string table so it is not
    // trivially visible in a dumped build.

    private var host1: String { "quer" + "y1.fi" + "nance.ya" + "hoo.com" }
    private var host2: String { "quer" + "y2.fi" + "nance.ya" + "hoo.com" }
    private var chartPath: String { "v8/fi" + "nance/ch" + "art" }
    private var sparkPath: String { "v8/fi" + "nance/sp" + "ark" }
    private var screenerPath: String { "v1/fi" + "nance/sc" + "reener/pre" + "defined/sa" + "ved" }
    private var searchPath: String { "v1/fi" + "nance/se" + "arch" }

    private func chartURL(_ ticker: String, _ query: String) -> URL? {
        let escaped = ticker.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? ticker
        return URL(string: "https://\(host1)/\(chartPath)/\(escaped)?\(query)")
    }

    private func sparkURL(symbols: String, range: String, interval: String) -> URL? {
        URL(string: "https://\(host1)/\(sparkPath)?symbols=\(symbols)&range=\(range)&interval=\(interval)")
    }

    private func screenerURL(_ scrId: String) -> URL? {
        URL(string: "https://\(host1)/\(screenerPath)?formatted=false&scrIds=\(scrId)&start=0&count=25")
    }

    private func searchURL(_ query: String) -> URL? {
        let escaped = query.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? query
        return URL(string: "https://\(host1)/\(searchPath)?q=\(escaped)&quotesCount=20&newsCount=0&listsCount=0")
    }

    // MARK: - Transport

    private func getJSON(_ url: URL?) async throws -> [String: Any] {
        guard let url else { throw QuoteError.badResponse }
        try await backoff.checkBeforeRequest()

        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.data(from: url)
        } catch {
            throw QuoteError.network(error)
        }

        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        await backoff.recordResponse(statusCode: status)
        guard (200..<300).contains(status) else {
            throw status == 429 ? QuoteError.rateLimited : QuoteError.badResponse
        }
        guard let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw QuoteError.badResponse
        }
        return object
    }

    private func getText(_ url: URL?) async throws -> String {
        guard let url else { throw QuoteError.badResponse }
        try await backoff.checkBeforeRequest()

        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.data(from: url)
        } catch {
            throw QuoteError.network(error)
        }
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        await backoff.recordResponse(statusCode: status)
        guard (200..<300).contains(status) else { throw QuoteError.badResponse }
        return String(data: data, encoding: .utf8) ?? ""
    }

    // MARK: - Quotes

    /// A full quote for one symbol.
    ///
    /// `range=2d&interval=1d` keeps the response tiny (a couple of points) —
    /// every field below comes from `meta`, so a 1-minute intraday range would
    /// download roughly fifty times more than it uses.
    func fetchQuote(ticker: String) async throws -> Quote? {
        // Finance Query first, this client's own direct call second.
        //
        // The order is the whole point of the change: the server in front
        // caches and absorbs the throttling, so the phone stops being an
        // unthrottled client of an undocumented endpoint. When the server is
        // unreachable — or the public instance is gone for good — this falls
        // straight through to the original implementation below and the app
        // behaves exactly as it did before.
        if let viaServer = await financeQuery.quote(ticker) { return viaServer }

        let root = try await getJSON(chartURL(ticker, "range=2d&interval=1d"))
        guard let result = firstChartResult(root), let meta = result["meta"] as? [String: Any] else {
            return nil
        }
        guard let price = meta["regularMarketPrice"] as? Double, price.isFinite else { return nil }

        func optDouble(_ key: String) -> Double? {
            guard let v = meta[key] as? Double, v.isFinite else { return nil }
            return v
        }
        func optInt(_ key: String) -> Int64? {
            if let v = meta[key] as? Int64 { return v }
            if let v = meta[key] as? Int { return Int64(v) }
            if let v = meta[key] as? Double, v.isFinite { return Int64(v) }
            return nil
        }
        func optString(_ key: String) -> String? {
            guard let v = meta[key] as? String, !v.isEmpty else { return nil }
            return v
        }

        return Quote(
            price: price,
            name: optString("shortName") ?? optString("longName"),
            previousClose: optDouble("chartPreviousClose") ?? optDouble("previousClose"),
            currency: optString("currency")?.uppercased(),
            dayHigh: optDouble("regularMarketDayHigh"),
            dayLow: optDouble("regularMarketDayLow"),
            open: optDouble("regularMarketOpen"),
            volume: optInt("regularMarketVolume"),
            fiftyTwoWeekHigh: optDouble("fiftyTwoWeekHigh"),
            fiftyTwoWeekLow: optDouble("fiftyTwoWeekLow"),
            avgVolume3Month: optInt("averageDailyVolume3Month"),
            marketTime: optInt("regularMarketTime").flatMap { $0 > 0 ? Date(timeIntervalSince1970: Double($0)) : nil },
            exchangeTimezone: optString("exchangeTimezoneName"),
            instrumentType: optString("instrumentType")
        )
    }

    private func firstChartResult(_ root: [String: Any]) -> [String: Any]? {
        guard let chart = root["chart"] as? [String: Any],
              let results = chart["result"] as? [[String: Any]],
              let first = results.first else { return nil }
        return first
    }

    // MARK: - History

    /// Closing prices for a range. `range` is one of 1d, 5d, 1mo, 6mo, ytd, 1y,
    /// 5y, max — matching the chart chip row's 1D/1W/1M/6M/YTD/1Y/5Y/ALL.
    func fetchHistory(ticker: String, range: String, interval: String) async -> [(Date, Double)] {
        let bars = await fetchHistoryBars(ticker: ticker, range: range, interval: interval)
        return bars.map { ($0.date, $0.close) }
    }

    // MARK: Chart cache
    //
    // Bars are cached in memory, keyed on ticker + range + interval.
    //
    // The chip row invites exactly the access pattern a cache is for: tap 1M,
    // tap 1Y, tap back to 1M. Uncached, that third tap is a fresh network
    // round-trip for data the app fetched seconds earlier, and the chart blanks
    // while it waits. The portfolio value series compounds it — one fetch per
    // security per range change.
    //
    // TTLs follow how fast the data can actually change. An intraday range is
    // genuinely moving, so it expires in a minute; a daily or weekly series
    // gains one bar per session, so re-fetching it more than a few times a day
    // is pure waste.
    private struct CachedBars {
        let bars: [HistoryBar]
        let at: Date
    }

    private var barCache: [String: CachedBars] = [:]

    /// Bounded so a long browsing session can't grow this without limit.
    private static let barCacheLimit = 120

    private nonisolated func barCacheTTL(range: String) -> TimeInterval {
        switch range.lowercased() {
        case "1d": return 60          // a live session
        case "5d", "1w": return 300   // still intraday, but coarser
        default: return 6 * 60 * 60   // daily bars and longer
        }
    }

    /// Drops the cached bars for one ticker, or all of them.
    ///
    /// The bars are a ticker's PRICE history and don't depend on how much of it
    /// the user owns — the portfolio series applies units at draw time — so a
    /// unit change needs no invalidation. What does need it is an explicit
    /// pull-to-refresh: without this, a six-hour TTL would mean the chart
    /// ignored the gesture entirely and the user would be pulling at a chart
    /// that had quietly decided it was still fresh.
    func invalidateBars(ticker: String? = nil) {
        guard let ticker else {
            barCache.removeAll()
            return
        }
        let prefix = ticker.uppercased() + "|"
        for key in barCache.keys where key.hasPrefix(prefix) {
            barCache.removeValue(forKey: key)
        }
    }

    /// Like `fetchHistory` but also returns per-bar volume.
    func fetchHistoryBars(ticker: String, range: String, interval: String) async -> [HistoryBar] {
        let key = "\(ticker.uppercased())|\(range)|\(interval)"
        if let hit = barCache[key], Date().timeIntervalSince(hit.at) < barCacheTTL(range: range) {
            return hit.bars
        }

        let bars = await fetchHistoryBarsUncached(ticker: ticker, range: range, interval: interval)

        // Only a real answer is cached. Caching an empty result would hold a
        // failed request in place for the whole TTL, turning one bad minute
        // into six hours of an empty chart.
        if !bars.isEmpty {
            if barCache.count >= Self.barCacheLimit {
                // Cheap eviction: drop the oldest half rather than track exact
                // LRU order for something this small.
                let keep = barCache
                    .sorted { $0.value.at > $1.value.at }
                    .prefix(Self.barCacheLimit / 2)
                barCache = Dictionary(uniqueKeysWithValues: keep.map { ($0.key, $0.value) })
            }
            barCache[key] = CachedBars(bars: bars, at: Date())
        }
        return bars
    }

    private func fetchHistoryBarsUncached(
        ticker: String, range: String, interval: String
    ) async -> [HistoryBar] {
        let viaServer = await financeQuery.historyBars(
            symbol: ticker, range: range, interval: interval
        )
        if !viaServer.isEmpty { return viaServer }

        guard let root = try? await getJSON(chartURL(ticker, "range=\(range)&interval=\(interval)")),
              let result = firstChartResult(root),
              let timestamps = result["timestamp"] as? [Any],
              let indicators = result["indicators"] as? [String: Any],
              let quoteArray = indicators["quote"] as? [[String: Any]],
              let quote = quoteArray.first,
              let closes = quote["close"] as? [Any] else {
            return []
        }
        let volumes = quote["volume"] as? [Any]

        var out: [HistoryBar] = []
        out.reserveCapacity(timestamps.count)
        for i in 0..<min(timestamps.count, closes.count) {
            guard let close = numeric(closes[i]), close.isFinite else { continue }
            guard let ts = numeric(timestamps[i]) else { continue }
            var volume: Int64?
            if let volumes, i < volumes.count, let raw = numeric(volumes[i]) {
                volume = Int64(raw)
            }
            out.append(HistoryBar(date: Date(timeIntervalSince1970: ts), close: close, volume: volume))
        }
        return out
    }

    private func numeric(_ value: Any) -> Double? {
        if value is NSNull { return nil }
        if let d = value as? Double { return d.isFinite ? d : nil }
        if let i = value as? Int { return Double(i) }
        if let n = value as? NSNumber { return n.doubleValue }
        return nil
    }

    // MARK: - Batched intraday traces

    /// Intraday traces for many symbols in one request — what keeps the Markets
    /// tab from firing one chart call per visible row.
    ///
    /// The endpoint has shipped two response shapes over the years and both are
    /// handled: a flat symbol-keyed object, and a nested `spark.result` array.
    func fetchSparkBatch(
        tickers: [String],
        range: String = "1d",
        interval: String = "30m"
    ) async -> [String: SparkQuote] {
        guard !tickers.isEmpty else { return [:] }
        var out: [String: SparkQuote] = [:]

        // Keep URLs well under any server-side length limit.
        let unique = Array(Set(tickers))
        for chunk in stride(from: 0, to: unique.count, by: 40).map({
            Array(unique[$0..<min($0 + 40, unique.count)])
        }) {
            let symbols = chunk
                .map { $0.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? $0 }
                .joined(separator: ",")
            guard let root = try? await getJSON(sparkURL(symbols: symbols, range: range, interval: interval)) else {
                // No batched trace endpoint on the server, so the sparkline
                // itself cannot be recovered here — but the price and previous
                // close can, in one request, and those are what the row's
                // numbers are made of. A table with live figures and no tiny
                // chart beats a table of spinners.
                let quotes = await financeQuery.quotes(chunk)
                for (symbol, quote) in quotes {
                    out[symbol] = SparkQuote(
                        ticker: symbol,
                        price: quote.price,
                        previousClose: quote.previousClose,
                        closes: [],
                        marketTime: quote.marketTime,
                        exchangeTimezone: quote.exchangeTimezone
                    )
                }
                continue
            }

            if let spark = root["spark"] as? [String: Any],
               let results = spark["result"] as? [[String: Any]] {
                for entry in results {
                    guard let symbol = entry["symbol"] as? String,
                          let responses = entry["response"] as? [[String: Any]],
                          let node = responses.first,
                          let parsed = parseSparkNode(symbol: symbol, node: node) else { continue }
                    out[parsed.ticker] = parsed
                }
            } else {
                for (symbol, value) in root {
                    guard let node = value as? [String: Any],
                          let parsed = parseSparkNode(symbol: symbol, node: node) else { continue }
                    out[parsed.ticker] = parsed
                }
            }
        }
        return out
    }

    private func parseSparkNode(symbol: String, node: [String: Any]) -> SparkQuote? {
        // Shape A puts close[] at the top level; shape B nests it under
        // indicators.quote[0].close, same as the chart endpoint.
        var closeArray = node["close"] as? [Any]
        if closeArray == nil,
           let indicators = node["indicators"] as? [String: Any],
           let quotes = indicators["quote"] as? [[String: Any]],
           let first = quotes.first {
            closeArray = first["close"] as? [Any]
        }
        guard let closeArray else { return nil }

        let closes = closeArray.compactMap { numeric($0) }
        guard let lastClose = closes.last else { return nil }

        let meta = node["meta"] as? [String: Any] ?? [:]

        // The two shapes spell the previous close four different ways between
        // them; take whichever one this response actually carries.
        var previousClose: Double?
        for key in ["previousClose", "chartPreviousClose"] {
            if previousClose == nil, let v = node[key], let d = numeric(v) { previousClose = d }
            if previousClose == nil, let v = meta[key], let d = numeric(v) { previousClose = d }
        }

        let price = meta["regularMarketPrice"].flatMap { numeric($0) }
            ?? node["regularMarketPrice"].flatMap { numeric($0) }
            ?? lastClose

        // Prefer meta.regularMarketTime (an instant, not a bar boundary); if a
        // particular shape omits it, fall back to the last non-null entry of the
        // parallel `timestamp` array — the same clock, read from the series.
        var marketSeconds = meta["regularMarketTime"].flatMap { numeric($0) }
        if marketSeconds == nil || marketSeconds == 0 {
            let timestamps = node["timestamp"] as? [Any] ?? []
            marketSeconds = timestamps.reversed().compactMap { numeric($0) }.first { $0 > 0 }
        }

        let reportedSymbol = (node["symbol"] as? String).flatMap { $0.isEmpty ? nil : $0 }
        let zone = (meta["exchangeTimezoneName"] as? String).flatMap { $0.isEmpty ? nil : $0 }

        return SparkQuote(
            ticker: reportedSymbol ?? symbol,
            price: price,
            previousClose: previousClose,
            closes: closes,
            marketTime: marketSeconds.flatMap { $0 > 0 ? Date(timeIntervalSince1970: $0) : nil },
            exchangeTimezone: zone
        )
    }

    // MARK: - Index futures

    /// The E-mini future that tracks each cash index.
    ///
    /// The cash indices stop printing at the close, but their futures trade
    /// nearly around the clock — which is the number people actually want at
    /// 10pm. They are carried under these continuous front-month symbols.
    private static let futuresForIndex: [String: (symbol: String, label: String)] = [
        "^DJI":  ("YM=F", "E-mini YM"),
        "^GSPC": ("ES=F", "E-mini ES"),
        "^IXIC": ("NQ=F", "E-mini NQ"),
        "^RUT":  ("RTY=F", "E-mini RTY")
    ]

    /// True when `ticker` is an index this client can pair with a future.
    nonisolated static func hasFuture(_ ticker: String) -> Bool {
        futuresForIndex[ticker.uppercased()] != nil
    }

    /// Maps an index to its future symbol and display label.
    nonisolated static func future(for ticker: String) -> (symbol: String, label: String)? {
        futuresForIndex[ticker.uppercased()]
    }

    /// Symbols for every index future, for a single batched fetch.
    nonisolated static func futureSymbols() -> [String] {
        futuresForIndex.values.map(\.symbol)
    }

    /// The secondary line under an index or listing: the tracking future when
    /// there is one, otherwise the extended-hours print.
    ///
    /// Uses the chart endpoint with `includePrePost=true` rather than a
    /// quote-summary field, because that endpoint now requires a crumb. The
    /// extended price is the last non-nil close stamped after
    /// `meta.regularMarketTime`; the change is measured against the regular
    /// close, which is what "Extended: +0.68 (+0.09%)" means on a broker app.
    func fetchExtended(ticker: String) async -> ExtendedQuote? {
        // Index rows quote their tracking future, which is a different symbol
        // and a different question — that path stays as it was and is tried
        // first, because a future is never an after-hours print.
        if let future = QuoteClient.future(for: ticker) {
            guard let quote = try? await fetchQuote(ticker: future.symbol),
                  let previous = quote.previousClose, previous > 0 else { return nil }
            return ExtendedQuote(
                label: future.label,
                price: quote.price,
                change: quote.price - previous,
                changePercent: (quote.price - previous) / previous * 100,
                at: quote.marketTime ?? Date(),
                symbol: future.symbol,
                zoneId: quote.exchangeTimezone
            )
        }

        // The pre/post fields ride along on the server's quote payload, so
        // this costs one request where the direct path costs a full intraday
        // chart download parsed backwards to find the last print.
        if let viaServer = await financeQuery.extended(ticker) { return viaServer }

        guard let root = try? await getJSON(
            chartURL(ticker, "range=1d&interval=5m&includePrePost=true")
        ), let result = firstChartResult(root),
           let meta = result["meta"] as? [String: Any],
           let regularClose = numeric(meta["regularMarketPrice"] ?? 0), regularClose > 0,
           let regularTime = numeric(meta["regularMarketTime"] ?? 0), regularTime > 0,
           let timestamps = result["timestamp"] as? [Any],
           let indicators = result["indicators"] as? [String: Any],
           let quotes = indicators["quote"] as? [[String: Any]],
           let closes = quotes.first?["close"] as? [Any] else { return nil }

        var latestPrice: Double?
        var latestAt: Double?
        for i in stride(from: min(timestamps.count, closes.count) - 1, through: 0, by: -1) {
            guard let ts = numeric(timestamps[i]), ts > regularTime else { continue }
            guard let close = numeric(closes[i]) else { continue }
            latestPrice = close
            latestAt = ts
            break
        }
        guard let latestPrice, let latestAt, latestPrice != regularClose else { return nil }

        return ExtendedQuote(
            label: "Extended",
            price: latestPrice,
            change: latestPrice - regularClose,
            changePercent: (latestPrice - regularClose) / regularClose * 100,
            at: Date(timeIntervalSince1970: latestAt),
            symbol: ticker,
            zoneId: meta["exchangeTimezoneName"] as? String
        )
    }

    // MARK: - Dividend history

    /// Historical per-share distributions. Returns (payment date, amount per
    /// share), oldest first.
    ///
    /// dividendhistory.org leads, and the chart endpoint's `events` block is
    /// the fallback. That order matters for two reasons:
    ///
    ///  - **The events block has no pay dates.** It reports ex-dates only, and
    ///    the gap between a distribution going ex and the cash arriving runs
    ///    from two days to six weeks depending on the fund. An income record
    ///    keyed on ex-dates puts money in the wrong month, which is exactly
    ///    what the Dividends tab is charting.
    ///  - **The site marks declared-but-unpaid rows "unconfirmed/estimated".**
    ///    Those are dropped here, so an announced distribution cannot land in
    ///    the received record before it has actually been paid. The events
    ///    block offers nothing to distinguish them by.
    ///
    /// Coverage is NYSE, NASDAQ, TSX, TSX-V and NEO, which is the whole of
    /// what this app prices in practice.
    func fetchHistoricalDividends(ticker: String) async -> [(Date, Double)] {
        let calendar = await fetchDividendCalendar(ticker: ticker)
            .filter { !$0.isEstimated && $0.amountPerShare > 0 }
        let events = await fetchDividendEvents(ticker: ticker)
        return Self.payDatedHistory(events: events, calendar: calendar)
    }

    /// The distribution record every income figure is bucketed on, in PAY dates.
    ///
    /// `events` is the chart endpoint's dividend block — ex-dates only, but
    /// reaching back to the security's first ever distribution. `calendar` is
    /// the payout page — ex AND pay dates, but only a recent slice.
    ///
    /// This used to pick whichever list was LONGER and take its dates with it,
    /// which quietly decided something it had no business deciding: one source
    /// carries ex-dates and the other pay dates, and for a fund like XEQT the
    /// two records are the same length, so the tie-break chose the date TYPE.
    /// iOS happened to land on pay dates and Android, whose tie-break ran the
    /// other way, landed on ex-dates — which is why one build put XEQT's
    /// year-end distribution in December and the other put it in January. Same
    /// fund, same engine, opposite answers, and iOS was only right by accident.
    ///
    /// Pay dates are the correct ones. Everything downstream is about money
    /// arriving — the monthly income bars, the trailing twelve-month total, the
    /// forward projection — and money arrives on the pay date. Nobody can spend
    /// an ex-dividend date.
    ///
    /// So: keep the events block's reach and re-date it from the payout page.
    /// An exact ex-date match within a few days supplies a real pay date, and
    /// anything older than the page falls back to the fund's own median ex→pay
    /// gap rather than a guess at a fixed offset. Ported from `payDatedHistory`
    /// on the Android side, so the two cannot drift apart again.
    static func payDatedHistory(
        events: [(Date, Double)],
        calendar: [DividendCalendarEntry]
    ) -> [(Date, Double)] {
        let day: TimeInterval = 24 * 60 * 60

        // The fund's own ex→pay gap, from the rows carrying both dates. Median
        // rather than mean: one row with a mis-parsed date should not shift
        // every projected payment.
        var gaps: [TimeInterval] = []
        for entry in calendar {
            guard let payDate = entry.payDate else { continue }
            let gap = payDate.timeIntervalSince(entry.exDate)
            if gap >= 0, gap <= 90 * day { gaps.append(gap) }
        }
        gaps.sort()
        let medianGap: TimeInterval? = gaps.isEmpty ? nil : gaps[gaps.count / 2]

        // No pay date anywhere — nothing to re-date with. Keep the old
        // longest-record rule and stay on ex-dates, which is honest rather than
        // an invented offset.
        guard let medianGap else {
            let asPairs = calendar.map { ($0.exDate, $0.amountPerShare) }
            let chosen = events.count >= asPairs.count ? events : asPairs
            return chosen.sorted { $0.0 < $1.0 }
        }

        // The two feeds can disagree by a day on the same distribution, so
        // matching is by proximity rather than equality.
        let tolerance: TimeInterval = 3 * day

        var redated: [(Date, Double)] = []
        for (exDate, amount) in events {
            let nearest = calendar
                .filter { abs($0.exDate.timeIntervalSince(exDate)) <= tolerance }
                .min { abs($0.exDate.timeIntervalSince(exDate)) < abs($1.exDate.timeIntervalSince(exDate)) }
            let payDate = nearest?.payDate ?? exDate.addingTimeInterval(medianGap)
            redated.append((payDate, amount))
        }

        // Distributions the payout page knows about and the events block
        // missed, deduplicated against what is already in so an overlap cannot
        // bill one payment twice.
        var extras: [(Date, Double)] = []
        for entry in calendar {
            let payDate = entry.payDate ?? entry.exDate.addingTimeInterval(medianGap)
            let clash = redated.contains { abs($0.0.timeIntervalSince(payDate)) <= tolerance }
            if !clash { extras.append((payDate, entry.amountPerShare)) }
        }

        return (redated + extras).sorted { $0.0 < $1.0 }
    }

    /// The chart endpoint's `events.dividends` block: ex-dates and amounts,
    /// no pay dates. Oldest first.
    private func fetchDividendEvents(ticker: String) async -> [(Date, Double)] {
        guard let root = try? await getJSON(
            chartURL(ticker, "range=10y&interval=1d&events=div")
        ), let result = firstChartResult(root),
           let events = result["events"] as? [String: Any],
           let dividends = events["dividends"] as? [String: Any] else {
            return []
        }

        var out: [(Date, Double)] = []
        for (_, value) in dividends {
            guard let entry = value as? [String: Any],
                  let amount = numeric(entry["amount"] ?? 0), amount > 0,
                  let date = numeric(entry["date"] ?? 0), date > 0 else { continue }
            out.append((Date(timeIntervalSince1970: date), amount))
        }
        return out.sorted { $0.0 < $1.0 }
    }

    // MARK: - Dividend calendar
    //
    // Where the next ex-date and pay date come from, and why this is more than
    // a projection off the chart's dividend events.
    //
    // This screen used to extrapolate: next ex-date = last payment + one
    // cycle, pay date = ex-date + five days. Both parts were guesses, and the
    // second was a bad one — the gap between a distribution going ex and the
    // cash landing runs from two days for some European annual payers to five
    // or six weeks for Canadian ETFs, so every pay date on iOS was wrong and
    // disagreed with the Android build, which reads a real calendar.
    //
    // dividendhistory.org publishes both dates, and publishes the next one as
    // soon as a fund declares it, for NYSE, NASDAQ, TSX, TSX-V and NEO. That is
    // the source Android uses. Yahoo's chart events stay as the fallback for
    // anything the site doesn't cover.

    /// One row of a dividendhistory.org payout table.
    ///
    /// `Codable` so the whole calendar can be cached to disk. Scraping it is by
    /// far the slowest thing the app does — up to four candidate URLs per
    /// ticker — and a payout calendar changes a few times a year, so re-running
    /// it on every launch was the worst effort-to-value ratio in the codebase.
    struct DividendCalendarEntry: Codable {
        let exDate: Date
        let payDate: Date?
        let amountPerShare: Double
        /// The site marks not-yet-declared rows "unconfirmed"/"estimated".
        let isEstimated: Bool
    }

    /// Full payment record plus any declared upcoming payment, newest first.
    ///
    /// Empty on any network or parse failure, which is what sends the caller
    /// back to the Yahoo-events estimator.
    func fetchDividendCalendar(ticker: String) async -> [DividendCalendarEntry] {
        let key = ticker.trimmingCharacters(in: .whitespaces).uppercased()
        guard !key.isEmpty else { return [] }

        // Cached here rather than in a caller, because every dividend path in
        // the app converges on this one method: the upcoming card, the payment
        // record and the income forecast all start from this table. Cache it
        // once and all three are on screen at launch.
        await loadCalendarCacheIfNeeded()
        if let hit = calendarCache[key], !hit.entries.isEmpty,
           Date().timeIntervalSince(hit.at) < Self.calendarTTL {
            return hit.entries
        }

        let fetched = await scrapeDividendCalendar(ticker: ticker)
        if !fetched.isEmpty {
            calendarCache[key] = CachedCalendar(entries: fetched, at: Date())
            await persistCalendarCache()
            return fetched
        }

        // Nothing came back. Whatever is on file, however old, beats an empty
        // screen — a published pay date does not stop being the pay date
        // because the scrape failed this morning.
        return calendarCache[key]?.entries ?? []
    }

    // MARK: Calendar cache

    private struct CachedCalendar: Codable {
        let entries: [DividendCalendarEntry]
        let at: Date
    }

    private var calendarCache: [String: CachedCalendar] = [:]
    private var calendarCacheLoaded = false

    /// How long a scraped calendar is trusted.
    ///
    /// Ten minutes, down from twelve hours. The original reasoning was sound
    /// on its own terms — a fund declares a distribution a few times a year and
    /// published dates don't move — but it made the Dividends tab feel broken:
    /// a payment declared this morning did not appear until tomorrow, and there
    /// was no way to tell from the screen whether the app had looked. The cost
    /// is a handful of requests when the tab is opened after a break, which is
    /// the only time the window now expires.
    ///
    /// The cache is still written to disk, so it keeps its other job: something
    /// to draw when the scrape fails or the phone is offline.
    private static let calendarTTL: TimeInterval = 10 * 60

    private func loadCalendarCacheIfNeeded() async {
        guard !calendarCacheLoaded else { return }
        calendarCacheLoaded = true
        if let stored = await DiskCache.shared.load(
            .dividendCalendars, as: [String: CachedCalendar].self
        ) {
            calendarCache = stored.value
        }
    }

    private func persistCalendarCache() async {
        await DiskCache.shared.save(.dividendCalendars, calendarCache)
    }

    /// Forces the next calendar fetch for a ticker — or every ticker — back to
    /// the network. Pull-to-refresh on the Dividends tab uses this.
    ///
    /// Marks the entry STALE rather than deleting it — the entries stay, only
    /// the timestamp is backdated far enough to fail the TTL check above. A
    /// straight delete meant a refetch that came back empty (the scrape
    /// timing out, dividendhistory.org rate-limiting, or just a bad minute —
    /// none of which are rare for a site with no official API, and by the
    /// user's report this correlates with off-hours) had nothing to fall back
    /// on: `fetchDividendCalendar`'s own "whatever is on file beats an empty
    /// screen" line reads `calendarCache[key]`, and invalidate had already
    /// removed it. That turned a single flaky refetch into a real, already-
    /// confirmed distribution being silently replaced on screen by the
    /// synthetic growth-adjusted estimate — reproducible on demand, since a
    /// forced refresh always hits the network.
    func invalidateDividendCalendar(ticker: String? = nil) {
        guard let ticker else {
            for key in calendarCache.keys {
                if let entry = calendarCache[key] {
                    calendarCache[key] = CachedCalendar(entries: entry.entries, at: .distantPast)
                }
            }
            return
        }
        let key = ticker.trimmingCharacters(in: .whitespaces).uppercased()
        guard let entry = calendarCache[key] else { return }
        calendarCache[key] = CachedCalendar(entries: entry.entries, at: .distantPast)
    }

    /// The actual scrape, unconditioned by any cache.
    private func scrapeDividendCalendar(ticker: String) async -> [DividendCalendarEntry] {
        let upper = ticker.uppercased()
        var base = upper
        for suffix in [".TO", ".TSX", ".V", ".CN", ".NE", ".X"] where base.hasSuffix(suffix) {
            base = String(base.dropLast(suffix.count))
        }

        // The site namespaces non-US listings under an exchange path. A bare US
        // path for a Canadian ticker 404s, which is how the old Android build
        // ended up estimating XEQT from an annual rate.
        let candidates: [String]
        if upper.hasSuffix(".TO") || upper.hasSuffix(".TSX") {
            candidates = ["tsx/\(base)", "tsxv/\(base)", "neo/\(base)", base]
        } else if upper.hasSuffix(".V") {
            candidates = ["tsxv/\(base)", "tsx/\(base)", base]
        } else if upper.hasSuffix(".NE") || upper.hasSuffix(".CN") {
            candidates = ["neo/\(base)", "tsx/\(base)", base]
        } else {
            candidates = [base, "tsx/\(base)"]
        }

        for path in candidates {
            guard let html = await fetchPayoutPage(path) else { continue }
            let parsed = Self.parseDividendCalendar(html)
            if !parsed.isEmpty { return parsed }
        }
        return []
    }

    /// One payout page, fetched OUTSIDE the quote rate limiter.
    ///
    /// `getText` runs every request through `backoff`, which exists to protect
    /// one undocumented pricing host. dividendhistory.org is a different site
    /// with no relationship to it, and routing the two through one limiter
    /// meant a throttled price refresh also blocked the dividend record — the
    /// app's most important data taken down by an unrelated endpoint's bad
    /// minute. Same mistake the news feed used to make, same fix.
    ///
    /// Note the paths are case-sensitive: `/payout/tsx/XEQT/` resolves and
    /// `/payout/tsx/xeqt/` returns 404, which is why the caller uppercases the
    /// base symbol before building them.
    private func fetchPayoutPage(_ path: String) async -> String? {
        guard let url = URL(string: "https://dividendhistory.org/payout/\(path)/") else { return nil }
        var request = URLRequest(url: url)
        request.setValue(
            "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            forHTTPHeaderField: "Accept"
        )
        request.setValue("https://dividendhistory.org/", forHTTPHeaderField: "Referer")
        guard let (data, response) = try? await payoutSession.data(for: request) else { return nil }
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else { return nil }
        return String(data: data, encoding: .utf8)
    }

    /// Parses a dividendhistory.org payout page.
    ///
    /// Mirrors `YahooQuoteClient.parseDividendHistoryOrg` on Android exactly —
    /// this used to be a single naive strategy (split on the literal string
    /// "<tr", then "<td") that broke silently whenever the page's markup
    /// didn't match that exact shape: nested tags inside a cell, no closing
    /// `</td>` on the last cell of a row, or a non-table layout entirely. A
    /// silent break here doesn't throw — it just returns too few or zero
    /// rows, and the caller (`inferUpcomingFromHistoryOrg`) reads "no
    /// confirmed future row" as "nothing declared yet" and falls back to the
    /// growth-adjusted estimate. That is why a distribution Android correctly
    /// showed as "Announced · declared by fund" could sit on iOS forever as
    /// "same quarter last year, growth-adjusted" — not stale data, a parser
    /// that was silently coming back empty (or losing the status column) for
    /// pages the regex-based Android parser reads fine.
    ///
    /// Strategy 1 — table rows via regex (`<tr>…</tr>`, `<td>…</td>`,
    /// tolerant of attributes and nested tags via DOTALL) — [Ex-Dividend Date
    /// | Payout Date | Cash Amount | Info/Status].
    /// Strategy 2 — only if strategy 1 finds nothing: strip all tags and scan
    /// the plain text for repeating "Ex-Dividend Date - YYYY-MM-DD …" blocks,
    /// for a server-side layout that isn't a table at all.
    static func parseDividendCalendar(_ html: String) -> [DividendCalendarEntry] {
        // Read at LOCAL midnight, not UTC midnight.
        //
        // These are calendar dates — "2026-09-24" — with no time of day in
        // them. Pinned to UTC midnight they become 20:00 the previous evening
        // for a reader in Toronto, so every date on screen prints a day early
        // (the site's Sep 24 ex-date showed as "Sep 23") and a payment near a
        // month boundary lands in the wrong bucket on the income chart.
        // Anchoring to the reader's own midnight makes the day they see the day
        // the site published.
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = .current
        formatter.dateFormat = "yyyy-MM-dd"

        func parseDate(_ s: String) -> Date? {
            formatter.date(from: s.trimmingCharacters(in: .whitespaces))
        }
        func parseAmount(_ s: String) -> Double? {
            var t = s.trimmingCharacters(in: .whitespaces)
            if t.hasPrefix("$") { t.removeFirst() }
            return Double(t).flatMap { $0 > 0 ? $0 : nil }
        }
        func isEstimatedText(_ s: String) -> Bool {
            let lower = s.lowercased()
            return lower.contains("unconfirmed") || lower.contains("estimated")
        }
        func firstMatch(_ pattern: String, in text: String) -> String? {
            guard let range = text.range(of: pattern, options: .regularExpression) else { return nil }
            return String(text[range])
        }
        func allMatches(_ pattern: String, in text: String) -> [NSTextCheckingResult] {
            guard let regex = try? NSRegularExpression(
                pattern: pattern, options: [.dotMatchesLineSeparators, .caseInsensitive]
            ) else { return [] }
            let ns = text as NSString
            return regex.matches(in: text, range: NSRange(location: 0, length: ns.length))
        }
        func group(_ match: NSTextCheckingResult, _ index: Int, in text: String) -> String? {
            guard index < match.numberOfRanges else { return nil }
            let range = match.range(at: index)
            guard range.location != NSNotFound else { return nil }
            return (text as NSString).substring(with: range)
        }

        // ── Strategy 1: <tr>…</tr> / <td>…</td>, via regex ────────────────
        var out: [DividendCalendarEntry] = []
        for trMatch in allMatches("<tr[^>]*>(.*?)</tr>", in: html) {
            guard let rowHTML = group(trMatch, 1, in: html) else { continue }
            let cells = allMatches("<td[^>]*>(.*?)</td>", in: rowHTML).compactMap { tdMatch -> String? in
                guard let cell = group(tdMatch, 1, in: rowHTML) else { return nil }
                return cell.replacingOccurrences(of: "<[^>]+>", with: " ", options: .regularExpression)
                    .trimmingCharacters(in: .whitespacesAndNewlines)
            }
            guard cells.count >= 3,
                  let exDateStr = firstMatch("[0-9]{4}-[0-9]{2}-[0-9]{2}", in: cells[0]),
                  let exDate = parseDate(exDateStr),
                  let amountStr = firstMatch("[0-9]+\\.[0-9]+", in: cells[2]),
                  let amount = parseAmount(amountStr) else { continue }

            let payDate = firstMatch("[0-9]{4}-[0-9]{2}-[0-9]{2}", in: cells.count > 1 ? cells[1] : "")
                .flatMap(parseDate)
            let status = cells.count > 3 ? cells[3] : ""
            let extra = cells.count > 4 ? cells[4] : ""
            out.append(
                DividendCalendarEntry(
                    exDate: exDate,
                    payDate: payDate,
                    amountPerShare: amount,
                    isEstimated: isEstimatedText(status) || isEstimatedText(extra)
                )
            )
        }
        if !out.isEmpty { return out.sorted { $0.exDate > $1.exDate } }

        // ── Strategy 2: strip tags, scan labeled text blocks ──────────────
        let text = html
            .replacingOccurrences(of: "<[^>]+>", with: " ", options: .regularExpression)
            .replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
        let blockPattern =
            #"Ex[\s-]*Dividend\s+Date\s*[-–:]\s*(\d{4}-\d{2}-\d{2})\s+Payout\s+Date\s*[-–:]\s*(\d{4}-\d{2}-\d{2})\s+Cash\s+Amount\s*[-–:]\s*\$?([\d.]+)(?:\s+(?:Info\s*[-–:]\s*)?(unconfirmed|estimated))?"#
        for match in allMatches(blockPattern, in: text) {
            guard let exDateStr = group(match, 1, in: text), let exDate = parseDate(exDateStr),
                  let amountStr = group(match, 3, in: text), let amount = parseAmount(amountStr) else { continue }
            let payDate = group(match, 2, in: text).flatMap(parseDate)
            let isEst = !(group(match, 4, in: text) ?? "").isEmpty
            out.append(
                DividendCalendarEntry(exDate: exDate, payDate: payDate, amountPerShare: amount, isEstimated: isEst)
            )
        }
        return out.sorted { $0.exDate > $1.exDate }
    }

    /// The next expected distribution, from a real payment record.
    ///
    /// Ported from the Android build so the two apps answer with the same dates
    /// and the same amount. In order of preference: a payment the fund has
    /// actually declared, the same slot last year adjusted for the fund's own
    /// growth, the median of recent payments, and finally the last payment.
    func inferUpcoming(from entries: [DividendCalendarEntry]) -> UpcomingDividend? {
        guard !entries.isEmpty else { return nil }
        let now = Date()

        // A row dated in the future is a projection whether or not the site
        // labelled it — never let one contaminate the confirmed history.
        let confirmed = entries.filter { !$0.isEstimated && $0.exDate <= now }
            .sorted { $0.exDate > $1.exDate }
        let upcoming = entries.filter { $0.exDate > now }
            .sorted { $0.exDate < $1.exDate }
            .first

        guard !confirmed.isEmpty else {
            guard let upcoming else { return nil }
            return UpcomingDividend(
                exDividendDate: upcoming.exDate,
                payDate: upcoming.payDate,
                estimatedAnnualRate: nil,
                yieldPercent: nil,
                paymentFrequencyPerYear: nil,
                perPaymentAmount: upcoming.amountPerShare,
                isAnnounced: !upcoming.isEstimated,
                basisLabel: upcoming.isEstimated ? "projected by source" : "declared by fund"
            )
        }

        let day: TimeInterval = 24 * 60 * 60

        // Frequency from the median gap between confirmed ex-dates, rather than
        // from a field no free source fills in reliably.
        let gaps = zip(confirmed, confirmed.dropFirst())
            .prefix(6)
            .map { abs($0.0.exDate.timeIntervalSince($0.1.exDate)) / day }
            .sorted()
        let medianGap = gaps.isEmpty ? 91 : gaps[gaps.count / 2]
        let frequency: Int
        switch medianGap {
        case ..<45: frequency = 12
        case ..<105: frequency = 4
        case ..<200: frequency = 2
        default: frequency = 1
        }

        let trailing = confirmed
            .filter { $0.exDate >= now.addingTimeInterval(-365 * day) }
            .reduce(0) { $0 + $1.amountPerShare }
        let annualRate = trailing > 0 ? trailing : confirmed[0].amountPerShare * Double(frequency)

        func median(_ values: [Double]) -> Double? {
            guard !values.isEmpty else { return nil }
            let sorted = values.sorted()
            return sorted.count % 2 == 1
                ? sorted[sorted.count / 2]
                : (sorted[sorted.count / 2 - 1] + sorted[sorted.count / 2]) / 2
        }

        // 1. A declared distribution needs no estimate at all.
        if let upcoming, !upcoming.isEstimated {
            return UpcomingDividend(
                exDividendDate: upcoming.exDate,
                payDate: upcoming.payDate,
                estimatedAnnualRate: annualRate,
                yieldPercent: nil,
                paymentFrequencyPerYear: frequency,
                perPaymentAmount: upcoming.amountPerShare,
                isAnnounced: true,
                basisLabel: "declared by fund"
            )
        }

        let nextExDate = upcoming?.exDate
            ?? confirmed[0].exDate.addingTimeInterval(365 / Double(frequency) * day)

        // The ex-to-pay gap is MEASURED from this security's own record rather
        // than assumed. Where no row carries both dates, the median of nothing
        // is no answer and the pay date is left unknown — which is honest,
        // where "ex-date + 5 days" was simply wrong for most funds.
        let exToPayGaps = confirmed
            .compactMap { row in row.payDate.map { $0.timeIntervalSince(row.exDate) } }
            .filter { $0 >= 0 && $0 <= 90 * day }
            .sorted()
        let exToPay = exToPayGaps.isEmpty ? nil : exToPayGaps[exToPayGaps.count / 2]
        let nextPayDate = upcoming?.payDate ?? exToPay.map { nextExDate.addingTimeInterval($0) }

        var perPayment: Double?
        var basis: String?

        // 2. Seasonal match: many ETFs pay a small Q1/Q3 and a large Q2/Q4, so
        // dividing the annual rate evenly overstates the small quarters 3x.
        if frequency <= 4 {
            let target = nextExDate.addingTimeInterval(-365 * day)
            // Half the cycle, capped at 25 days, keeps a quarterly match in the
            // right slot without picking up the neighbouring quarter.
            let window = min(25.0, 365.0 / Double(frequency) / 2) * day
            let sameSlot = confirmed
                .filter { abs($0.exDate.timeIntervalSince(target)) <= window }
                .min { abs($0.exDate.timeIntervalSince(target)) < abs($1.exDate.timeIntervalSince(target)) }

            if let sameSlot {
                let recent = confirmed.prefix(frequency).map(\.amountPerShare)
                let prior = confirmed.dropFirst(frequency).prefix(frequency).map(\.amountPerShare)
                let growth: Double
                if recent.count == frequency, prior.count == frequency, prior.reduce(0, +) > 0 {
                    growth = min(max(recent.reduce(0, +) / prior.reduce(0, +), 0.5), 2.0)
                } else {
                    growth = 1
                }
                perPayment = sameSlot.amountPerShare * growth
                let slot = frequency == 4 ? "quarter" : (frequency == 2 ? "half" : "period")
                basis = "same \(slot) last year, growth-adjusted"
            }
        }

        // 3. Recent median — monthly payers, or anything with no seasonal match.
        if perPayment == nil {
            let window = frequency == 12 ? 3 : frequency
            perPayment = median(confirmed.prefix(window).map(\.amountPerShare))
            basis = frequency == 12
                ? "median of last 3 payments"
                : "median of last \(window) payments"
        }

        // 4. Last resort.
        if perPayment == nil {
            perPayment = confirmed[0].amountPerShare
            basis = "most recent payment"
        }

        return UpcomingDividend(
            exDividendDate: nextExDate,
            payDate: nextPayDate,
            estimatedAnnualRate: annualRate,
            yieldPercent: nil,
            paymentFrequencyPerYear: frequency,
            perPaymentAmount: perPayment,
            isAnnounced: false,
            basisLabel: basis ?? "recent payments"
        )
    }

    /// The next expected distribution for `ticker`.
    ///
    /// The payout calendar first, because it carries real pay dates and
    /// declared payments; Yahoo's own distribution events as the fallback for
    /// listings the calendar does not cover.
    func fetchUpcomingDividend(ticker: String) async -> UpcomingDividend? {
        let calendar = await fetchDividendCalendar(ticker: ticker)
        if let fromCalendar = inferUpcoming(from: calendar) { return fromCalendar }

        // Straight to the events fallback, NOT back through
        // `fetchHistoricalDividends` — that now reads the same calendar first,
        // and calling it here would re-fetch up to four payout URLs that have
        // already just come back empty.
        let history = await fetchDividendEvents(ticker: ticker)
        guard !history.isEmpty else { return nil }
        return inferUpcoming(
            from: history.map {
                DividendCalendarEntry(
                    exDate: $0.0,
                    payDate: nil,
                    amountPerShare: $0.1,
                    isEstimated: false
                )
            }
        )
    }

    // MARK: - Search

    /// Ticker search-as-you-type.
    func searchSymbols(query: String) async -> [SymbolSearchResult] {
        let trimmed = query.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return [] }

        let viaServer = await financeQuery.lookup(trimmed)
        if !viaServer.isEmpty { return viaServer }

        let indexed = await searchIndex(query: trimmed)
        if !indexed.isEmpty { return indexed }

        // Last resort, and still worth keeping: neither index covers smaller
        // exchanges well, and quoting a symbol directly is what finds a PSE or
        // NEO listing that search has never heard of.
        return await probeSymbol(trimmed)
    }

    private func searchIndex(query: String) async -> [SymbolSearchResult] {
        guard let root = try? await getJSON(searchURL(query)),
              let quotes = root["quotes"] as? [[String: Any]] else { return [] }

        return quotes.compactMap { q in
            guard let symbol = q["symbol"] as? String, !symbol.isEmpty else { return nil }
            return SymbolSearchResult(
                symbol: symbol,
                name: (q["shortname"] as? String) ?? (q["longname"] as? String),
                exchange: q["exchDisp"] as? String,
                quoteType: q["quoteType"] as? String
            )
        }
    }

    /// Resolves a symbol the search index doesn't know by quoting it directly.
    ///
    /// The search endpoint is an index, and its coverage is not the same as its
    /// data coverage. Smaller exchanges are the gap: the Philippine Stock
    /// Exchange prices perfectly well through the chart endpoint — the PSEi
    /// itself is on the Markets tab and updates — yet searching "JFC.PS"
    /// returns nothing, because search ranks 20 results and indexes PSE
    /// listings poorly or not at all. The user then reasonably concludes the
    /// app cannot handle their market.
    ///
    /// So when the index comes back empty and the query already looks like a
    /// symbol, this fetches a quote for it. Nothing is invented: the symbol,
    /// name and type are whatever the quote reported. If no quote comes back,
    /// the symbol genuinely isn't one and an empty list is the honest answer.
    private func probeSymbol(_ query: String) async -> [SymbolSearchResult] {
        let candidate = query.uppercased()
        // Guard the shape before spending a request: a symbol, optionally with
        // one exchange suffix. Free text ("jollibee foods") is not one, and
        // quoting it would only 404 slowly.
        let allowed = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: ".-^="))
        let looksLikeSymbol = (1...12).contains(candidate.count)
            && !candidate.contains(where: { $0.isWhitespace })
            && candidate.unicodeScalars.allSatisfy { allowed.contains($0) }
            && candidate.filter({ $0 == "." }).count <= 1
        guard looksLikeSymbol else { return [] }

        guard let quote = try? await fetchQuote(ticker: candidate),
              quote.price.isFinite, quote.price > 0 else { return [] }

        return [SymbolSearchResult(
            symbol: candidate,
            name: quote.name,
            // No exchange label from a chart response; the suffix is what
            // TickerFlag reads anyway, and inventing one would be worse than
            // leaving it nil.
            exchange: nil,
            quoteType: quote.instrumentType
        )]
    }

    // MARK: - Market movers

    /// The merged "Hot Stocks" list: most actives, day gainers and day losers
    /// in one pass, ranked by `MarketMover.heat` so a thinly-traded microcap
    /// that jumped 40% on no volume doesn't outrank an earnings-day mega-cap.
    func fetchHotStocks(limit: Int = 25) async -> [MarketMover] {
        var merged: [String: MarketMover] = [:]

        // Same three screeners either way; only the naming differs (the server
        // hyphenates where the direct endpoint underscores).
        for name in ["most-actives", "day-gainers", "day-losers"] {
            for mover in await financeQuery.screener(name, limit: limit) {
                merged[mover.ticker] = mover
            }
        }
        if !merged.isEmpty {
            return merged.values.sorted { $0.heat > $1.heat }.prefix(limit).map { $0 }
        }

        for screener in ["most_actives", "day_gainers", "day_losers"] {
            for mover in await fetchMovers(screener) {
                merged[mover.ticker] = mover
            }
        }
        return merged.values.sorted { $0.heat > $1.heat }.prefix(limit).map { $0 }
    }

    private func fetchMovers(_ scrId: String) async -> [MarketMover] {
        guard let root = try? await getJSON(screenerURL(scrId)),
              let finance = root["finance"] as? [String: Any],
              let results = finance["result"] as? [[String: Any]],
              let quotes = results.first?["quotes"] as? [[String: Any]] else { return [] }

        return quotes.compactMap { q in
            guard let ticker = q["symbol"] as? String,
                  let price = numeric(q["regularMarketPrice"] ?? 0), price > 0,
                  let previous = numeric(q["regularMarketPreviousClose"] ?? 0), previous > 0 else {
                return nil
            }
            let changePct = numeric(q["regularMarketChangePercent"] ?? 0)
                ?? ((price - previous) / previous * 100)
            return MarketMover(
                ticker: ticker,
                name: (q["shortName"] as? String) ?? (q["longName"] as? String),
                price: price,
                previousClose: previous,
                changePct: changePct,
                exchange: q["fullExchangeName"] as? String ?? q["exchange"] as? String,
                volume: numeric(q["regularMarketVolume"] ?? 0).map { Int64($0) },
                averageVolume: numeric(q["averageDailyVolume3Month"] ?? 0).map { Int64($0) },
                marketTime: numeric(q["regularMarketTime"] ?? 0)
                    .flatMap { $0 > 0 ? Date(timeIntervalSince1970: $0) : nil },
                exchangeTimezone: q["exchangeTimezoneName"] as? String
            )
        }
    }

    // MARK: - Logos

    /// Best-effort logo URL for a ticker.
    ///
    /// Resolved from the symbol's own domain where one is derivable, and nil
    /// otherwise — `TickerLogo` draws a colour-coded initial in that case, so a
    /// missing logo is a designed state rather than blank space.
    nonisolated static func logoURL(ticker: String, name: String? = nil) -> String? {
        LogoResolver.imageURL(ticker: ticker, name: name)
    }
}
