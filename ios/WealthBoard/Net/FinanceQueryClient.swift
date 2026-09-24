import Foundation

/// Prices, history, search and screeners from a Finance Query server.
///
/// Why this exists, given the app already had a working quote client:
///
/// The old client talked to the provider's undocumented endpoints directly from
/// the phone, which made every device its own unthrottled client. That is what
/// the `RateLimitBackoff` actor is for — and once it trips, it trips for
/// everything, because one host served quotes, history, dividends and news
/// alike. A throttled price refresh took the news screen down with it for ten
/// minutes at a time.
///
/// Finance Query (github.com/Verdenroz/finance-query) is a Rust server that
/// wraps the same public data behind a REST/WebSocket API with a Redis cache in
/// front of it. The upstream is unchanged, so this does not make the data more
/// reliable in principle — what changes is WHO absorbs the throttling. The
/// server takes the rate limiting and hands this app a cached answer, so a
/// phone in a bad minute gets a slightly stale price instead of a 429.
///
/// It is deliberately NOT a hard dependency. Every method returns nil or an
/// empty result rather than throwing, and `QuoteClient` falls back to its own
/// direct implementation whenever one does. If the server is down, or the
/// public instance disappears, the app keeps working exactly as it did before.
///
/// ## Self-hosting
///
/// The public instance at finance-query.com is free, but it carries no SLA, no
/// documented rate limit and no guarantee it will still be there next year.
/// `baseURL` reads `financeQueryBaseURL` from `UserDefaults` first, so pointing
/// the app at your own deployment is a one-line change with no rebuild:
///
///     UserDefaults.standard.set("https://finance.example.com", forKey: "financeQueryBaseURL")
///
/// The project ships a Docker Compose file (server, Nginx, Redis) and is MIT
/// licensed, so running it yourself is the supported path for a shipping build.
actor FinanceQueryClient {

    static let shared = FinanceQueryClient()

    /// Where requests go. Overridable at runtime for a self-hosted server.
    private var baseURL: String {
        let override = UserDefaults.standard.string(forKey: "financeQueryBaseURL")?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        if let override, !override.isEmpty {
            return override.hasSuffix("/") ? String(override.dropLast()) : override
        }
        return "https://finance-query.com"
    }

    private let session: URLSession = {
        let config = URLSessionConfiguration.default
        // Shorter than the direct client's timeouts on purpose. This is the
        // FIRST thing tried on every call, so a server that has gone away must
        // fail fast enough that the fallback still feels like one request.
        config.timeoutIntervalForRequest = 8
        config.timeoutIntervalForResource = 15
        config.requestCachePolicy = .useProtocolCachePolicy
        config.httpAdditionalHeaders = ["Accept": "application/json"]
        return URLSession(configuration: config)
    }()

    /// Set after a failure so a dead server is not re-tried on every row of a
    /// list, and cleared by the next success.
    ///
    /// Without it, a watchlist of thirty symbols on a phone with no route to
    /// the server pays the 8-second timeout thirty times before falling back.
    private var unavailableUntil: Date = .distantPast

    private var isAvailable: Bool { Date() >= unavailableUntil }

    private func markUnavailable() {
        unavailableUntil = Date().addingTimeInterval(120)
    }

    private func markAvailable() {
        unavailableUntil = .distantPast
    }

    // MARK: - Transport

    private func get(_ path: String) async -> [String: Any]? {
        guard isAvailable, let url = URL(string: baseURL + path) else { return nil }
        guard let (data, response) = try? await session.data(from: url) else {
            markUnavailable()
            return nil
        }
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        // A 404 is this symbol's answer, not the server's health — it should
        // send the caller to the fallback without blacklisting the host.
        guard (200..<300).contains(status) else {
            if status >= 500 || status == 429 { markUnavailable() }
            return nil
        }
        markAvailable()
        return try? JSONSerialization.jsonObject(with: data) as? [String: Any]
    }

    private func escaped(_ value: String) -> String {
        value.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? value
    }

    // MARK: - Field readers
    //
    // The payload is the upstream's own shape flattened to plain scalars —
    // `regularMarketPrice: 45.01` rather than `{"raw": 45.01, "fmt": "45.01"}`
    // — so these stay simple. Numbers arrive as either Int or Double depending
    // on the value, which is the one thing worth normalising.

    private nonisolated func num(_ value: Any?) -> Double? {
        if value is NSNull { return nil }
        if let d = value as? Double { return d.isFinite ? d : nil }
        if let i = value as? Int { return Double(i) }
        if let n = value as? NSNumber { return n.doubleValue.isFinite ? n.doubleValue : nil }
        return nil
    }

    private nonisolated func int(_ value: Any?) -> Int64? {
        guard let d = num(value) else { return nil }
        return Int64(d)
    }

    private nonisolated func str(_ value: Any?) -> String? {
        guard let s = value as? String, !s.isEmpty else { return nil }
        return s
    }

    private nonisolated func date(_ value: Any?) -> Date? {
        guard let seconds = num(value), seconds > 0 else { return nil }
        return Date(timeIntervalSince1970: seconds)
    }

    // MARK: - Quotes

    /// Quotes for several symbols in ONE request.
    ///
    /// The batch endpoint reports per-symbol failures in its own `errors`
    /// array rather than failing the whole call, so a delisted ticker in a
    /// watchlist costs that row and nothing else. Symbols that came back
    /// empty are simply absent from the result, which is what tells the caller
    /// which ones still need the fallback.
    func quotes(_ symbols: [String]) async -> [String: Quote] {
        let cleaned = symbols
            .map { $0.trimmingCharacters(in: .whitespaces).uppercased() }
            .filter { !$0.isEmpty }
        guard !cleaned.isEmpty else { return [:] }

        var out: [String: Quote] = [:]
        // Chunked so one long watchlist cannot build a URL the server rejects.
        for chunk in stride(from: 0, to: cleaned.count, by: 40).map({
            Array(cleaned[$0..<min($0 + 40, cleaned.count)])
        }) {
            let joined = escaped(chunk.joined(separator: ","))
            guard let root = await get("/v2/quotes?symbols=\(joined)"),
                  let rows = root["quotes"] as? [[String: Any]] else { continue }
            for row in rows {
                guard let symbol = str(row["symbol"])?.uppercased(),
                      let parsed = quote(from: row) else { continue }
                out[symbol] = parsed
            }
        }
        return out
    }

    /// One symbol's full quote.
    func quote(_ symbol: String) async -> Quote? {
        let clean = symbol.trimmingCharacters(in: .whitespaces)
        guard !clean.isEmpty else { return nil }
        guard let root = await get("/v2/quote/\(escaped(clean))") else { return nil }

        // The single-symbol endpoint returns the quote object at the top level;
        // the batch wraps it. Both shapes are accepted so a server version
        // change in either direction does not silently return nothing.
        if let quote = quote(from: root) { return quote }
        if let rows = root["quotes"] as? [[String: Any]], let first = rows.first {
            return quote(from: first)
        }
        return nil
    }

    private nonisolated func quote(from row: [String: Any]) -> Quote? {
        guard let price = num(row["regularMarketPrice"]) ?? num(row["currentPrice"]),
              price > 0 else { return nil }

        return Quote(
            price: price,
            name: str(row["shortName"]) ?? str(row["longName"]) ?? str(row["displayName"]),
            previousClose: num(row["regularMarketPreviousClose"]) ?? num(row["previousClose"]),
            currency: str(row["currency"])?.uppercased(),
            dayHigh: num(row["regularMarketDayHigh"]) ?? num(row["dayHigh"]),
            dayLow: num(row["regularMarketDayLow"]) ?? num(row["dayLow"]),
            open: num(row["regularMarketOpen"]) ?? num(row["open"]),
            volume: int(row["regularMarketVolume"]) ?? int(row["volume"]),
            fiftyTwoWeekHigh: num(row["fiftyTwoWeekHigh"]),
            fiftyTwoWeekLow: num(row["fiftyTwoWeekLow"]),
            avgVolume3Month: int(row["averageDailyVolume3Month"]) ?? int(row["averageVolume"]),
            marketTime: date(row["regularMarketTime"]),
            exchangeTimezone: str(row["timeZoneFullName"]) ?? str(row["exchangeTimezoneName"]),
            // Normalised: the lookup endpoint lower-cases this ("etf") while
            // the quote endpoint upper-cases it ("ETF"), and the holding-type
            // correction downstream compares against upper-case names.
            instrumentType: str(row["quoteType"])?.uppercased(),
            logoURL: str(row["companyLogoUrl"]) ?? str(row["logoUrl"])
        )
    }

    /// Extended-hours lines for many symbols in ONE request.
    ///
    /// The pre/post fields ride along on the batch quote payload, so a whole
    /// watchlist's after-hours prints cost the same single call the prices did.
    /// Fetching them per symbol — which is what the caller did before this
    /// existed — turned one refresh into one request per row, which is exactly
    /// the traffic pattern worth avoiding.
    ///
    /// Symbols with no extended print are simply absent from the result, which
    /// is what tells the caller to clear any stale line rather than leave last
    /// night's price under a live one.
    func extendedQuotes(_ symbols: [String]) async -> [String: ExtendedQuote] {
        let cleaned = symbols
            .map { $0.trimmingCharacters(in: .whitespaces).uppercased() }
            .filter { !$0.isEmpty }
        guard !cleaned.isEmpty else { return [:] }

        var out: [String: ExtendedQuote] = [:]
        for chunk in stride(from: 0, to: cleaned.count, by: 40).map({
            Array(cleaned[$0..<min($0 + 40, cleaned.count)])
        }) {
            let joined = escaped(chunk.joined(separator: ","))
            guard let root = await get("/v2/quotes?symbols=\(joined)"),
                  let rows = root["quotes"] as? [[String: Any]] else { continue }
            for row in rows {
                guard let symbol = str(row["symbol"])?.uppercased(),
                      let parsed = extended(from: row, symbol: symbol) else { continue }
                out[symbol] = parsed
            }
        }
        return out
    }

    /// The after-hours or pre-market line shown under a live price.
    ///
    /// Read from the same quote payload rather than a second request: the
    /// pre/post fields ride along on every quote, so the extended line costs
    /// nothing once the quote has been fetched.
    func extended(_ symbol: String) async -> ExtendedQuote? {
        let clean = symbol.trimmingCharacters(in: .whitespaces)
        guard !clean.isEmpty, let root = await get("/v2/quote/\(escaped(clean))") else { return nil }

        let row: [String: Any]
        if root["regularMarketPrice"] != nil {
            row = root
        } else if let rows = root["quotes"] as? [[String: Any]], let first = rows.first {
            row = first
        } else {
            return nil
        }

        return extended(from: row, symbol: clean)
    }

    /// Labelled "Extended" to match the direct client's own wording, so the row
    /// reads identically whichever path served it.
    private nonisolated func extended(from row: [String: Any], symbol: String) -> ExtendedQuote? {
        let zone = str(row["timeZoneFullName"]) ?? str(row["exchangeTimezoneName"])

        // Post-market first — once a session has closed, the after-hours print
        // is the newer of the two and is what the reader is asking about.
        if let price = num(row["postMarketPrice"]),
           let change = num(row["postMarketChange"]),
           let percent = num(row["postMarketChangePercent"]),
           let at = date(row["postMarketTime"]) {
            return ExtendedQuote(
                label: "Extended", price: price, change: change,
                changePercent: percent, at: at, symbol: symbol, zoneId: zone
            )
        }
        if let price = num(row["preMarketPrice"]),
           let change = num(row["preMarketChange"]),
           let percent = num(row["preMarketChangePercent"]),
           let at = date(row["preMarketTime"]) {
            return ExtendedQuote(
                label: "Extended", price: price, change: change,
                changePercent: percent, at: at, symbol: symbol, zoneId: zone
            )
        }
        return nil
    }

    // MARK: - History

    /// Price bars for a range.
    ///
    /// One caveat worth knowing rather than discovering: the server does not
    /// always honour `interval` exactly — asking for `max`/`1mo` comes back
    /// with weekly granularity. Nothing downstream depends on the bar spacing
    /// being what was requested (the chart plots whatever timestamps it gets),
    /// but it means bar COUNTS are not a reliable way to tell ranges apart.
    func historyBars(symbol: String, range: String, interval: String) async -> [HistoryBar] {
        let clean = symbol.trimmingCharacters(in: .whitespaces)
        guard !clean.isEmpty else { return [] }
        guard let root = await get(
            "/v2/chart/\(escaped(clean))?range=\(escaped(range))&interval=\(escaped(interval))"
        ), let candles = root["candles"] as? [[String: Any]] else {
            return []
        }

        var out: [HistoryBar] = []
        out.reserveCapacity(candles.count)
        for candle in candles {
            guard let seconds = num(candle["timestamp"]), seconds > 0,
                  let close = num(candle["close"]), close > 0 else { continue }
            out.append(
                HistoryBar(
                    date: Date(timeIntervalSince1970: seconds),
                    close: close,
                    volume: int(candle["volume"])
                )
            )
        }
        return out.sorted { $0.date < $1.date }
    }

    // MARK: - Search

    func lookup(_ query: String) async -> [SymbolSearchResult] {
        let trimmed = query.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return [] }
        guard let root = await get("/v2/lookup?q=\(escaped(trimmed))"),
              let rows = root["quotes"] as? [[String: Any]] else {
            return []
        }
        return rows.compactMap { row in
            guard let symbol = str(row["symbol"]) else { return nil }
            return SymbolSearchResult(
                symbol: symbol,
                name: str(row["shortName"]) ?? str(row["longName"]),
                exchange: str(row["exchDisp"]) ?? str(row["exchange"]),
                quoteType: str(row["quoteType"])?.uppercased()
            )
        }
    }

    // MARK: - Screeners

    /// A named screener — "most-actives", "day-gainers", "day-losers".
    func screener(_ name: String, limit: Int = 25) async -> [MarketMover] {
        guard let root = await get("/v2/screeners/\(escaped(name))"),
              let rows = root["quotes"] as? [[String: Any]] else {
            return []
        }

        return rows.prefix(limit).compactMap { row -> MarketMover? in
            guard let ticker = str(row["symbol"]),
                  let price = num(row["regularMarketPrice"]), price > 0,
                  let previousClose = num(row["regularMarketPreviousClose"]),
                  previousClose > 0 else { return nil }

            // Taken from the payload where it is given, derived where it is
            // not: the percentage is what the list sorts on, so computing it
            // rather than dropping the row keeps a good name in the table.
            let changePct = num(row["regularMarketChangePercent"])
                ?? ((price - previousClose) / previousClose * 100)

            return MarketMover(
                ticker: ticker,
                name: str(row["shortName"]) ?? str(row["displayName"]) ?? str(row["longName"]),
                price: price,
                previousClose: previousClose,
                changePct: changePct,
                exchange: str(row["fullExchangeName"]) ?? str(row["exchange"]),
                volume: int(row["regularMarketVolume"]),
                averageVolume: int(row["averageDailyVolume3Month"])
                    ?? int(row["averageDailyVolume10Day"]),
                marketTime: date(row["regularMarketTime"]),
                exchangeTimezone: str(row["timeZoneFullName"])
            )
        }
    }
}
