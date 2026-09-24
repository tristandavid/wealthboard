import Foundation

/// Exchange rates, so a portfolio holding more than one currency can be
/// totalled honestly. Ported from `data/FxRates.kt`.
///
/// Without this the app sums a USD position and a CAD position as though a
/// dollar were a dollar — a portfolio a third in SPY reads roughly 13% light at
/// a 1.38 USD/CAD rate, and every allocation percentage is wrong alongside it.
///
/// Rates come from the same quote endpoint as everything else, which quotes FX
/// as an ordinary symbol ("USDCAD=X"), so this needs no second data source and
/// no API key.
///
/// Lookups are deliberately synchronous: `convert` is called from view-model
/// computed properties and list rendering where awaiting isn't an option, so
/// rates are fetched ahead of time by `refresh` and read from cache here.
final class FxRates: @unchecked Sendable {

    static let shared = FxRates()

    /// Rates older than this are refetched on the next `refresh`.
    private let staleAfter: TimeInterval = 6 * 60 * 60

    private struct Cached {
        let rate: Double
        let at: Date
    }

    private var cache: [String: Cached] = [:]
    private let lock = NSLock()

    private init() {
        restore()
    }

    private func key(_ from: String, _ to: String) -> String {
        "\(from.uppercased())_\(to.uppercased())"
    }

    // MARK: - Persistence
    //
    // Without a warm cache a cold start totals the portfolio unconverted for
    // the second or two before the first rate lands, so the headline number
    // visibly jumps. A stored rate is stale but far closer to right than
    // treating a US dollar as a Canadian one.

    private func restore() {
        for storedKey in Prefs.allKeys(withPrefix: Prefs.Key.fxPrefix) {
            guard let value = Prefs.string(storedKey) else { continue }
            let parts = value.split(separator: "|")
            guard parts.count == 2,
                  let rate = Double(parts[0]), rate > 0,
                  let millis = Double(parts[1]) else { continue }
            let pair = String(storedKey.dropFirst(Prefs.Key.fxPrefix.count))
            cache[pair] = Cached(rate: rate, at: Date(timeIntervalSince1970: millis / 1000))
        }
    }

    private func store(_ from: String, _ to: String, rate: Double, at date: Date) {
        let k = key(from, to)
        lock.lock()
        cache[k] = Cached(rate: rate, at: date)
        lock.unlock()
        Prefs.set("\(rate)|\(date.timeIntervalSince1970 * 1000)", Prefs.Key.fxPrefix + k)
    }

    // MARK: - Lookup

    /// Latest known rate to multiply a `from` amount by to get `to`, or nil when
    /// none has been fetched.
    func rate(from: String, to: String) -> Double? {
        if from.caseInsensitiveCompare(to) == .orderedSame { return 1.0 }
        if let d = direct(from, to) { return d }

        // Triangulate through any currency both sides are already quoted
        // against — in practice the reporting currency, since that is what
        // `refresh` fetches everything against.
        //
        // Without this, a pair neither side of which is the base currency has
        // no rate at all: a US-dollar dividend logged against a Stockholm
        // listing needs USD→SEK, and the cache only ever holds USD→CAD and
        // SEK→CAD for a Canadian user. Both legs are already in hand, so this
        // costs no network call.
        let f = from.uppercased()
        let t = to.uppercased()
        lock.lock()
        let keys = Array(cache.keys)
        lock.unlock()

        var pivots: [String] = []
        for k in keys {
            let parts = k.split(separator: "_")
            if parts.count == 2 {
                pivots.append(String(parts[0]))
                pivots.append(String(parts[1]))
            }
        }
        for pivot in Array(NSOrderedSet(array: pivots)) as? [String] ?? [] {
            if pivot == f || pivot == t { continue }
            guard let leg1 = direct(f, pivot), let leg2 = direct(pivot, t) else { continue }
            return leg1 * leg2
        }
        return nil
    }

    /// A rate held outright, or the inverse of the opposite pair. No hops.
    private func direct(_ from: String, _ to: String) -> Double? {
        lock.lock()
        defer { lock.unlock() }
        if let c = cache[key(from, to)] { return c.rate }
        if let c = cache[key(to, from)], c.rate > 0 { return 1.0 / c.rate }
        return nil
    }

    /// True when `from` can be expressed in `to` right now.
    func hasRate(from: String?, to: String) -> Bool {
        guard let from, !from.isEmpty else { return true }
        return rate(from: from, to: to) != nil
    }

    /// Converts `amount` from one currency to another.
    ///
    /// With no rate available the amount is returned unchanged rather than
    /// zeroed — a slightly wrong total beats a portfolio that reads as empty.
    /// Callers that need to tell the user about it use `hasRate` to spot the
    /// gap; the Total Value card does exactly that and labels the total as
    /// partly unconverted.
    func convert(_ amount: Double, from: String?, to: String) -> Double {
        guard let from, !from.isEmpty else { return amount }
        guard let r = rate(from: from, to: to) else { return amount }
        return amount * r
    }

    /// How long ago the rate between these two was fetched, or nil if never.
    func age(from: String, to: String) -> TimeInterval? {
        lock.lock()
        defer { lock.unlock() }
        let at = cache[key(from, to)]?.at ?? cache[key(to, from)]?.at
        guard let at else { return nil }
        return Date().timeIntervalSince(at)
    }

    // MARK: - Refresh

    /// Fetches whatever is missing or stale to express `currencies` in `base`.
    ///
    /// Call before any summing. Failures are swallowed on purpose: a portfolio
    /// total that renders with a stale rate is far more useful than a screen
    /// that refuses to draw because one FX request timed out.
    func refresh(currencies: some Collection<String>, base: String) async {
        let b = base.uppercased()
        let wanted = Set(currencies.map { $0.uppercased() })
            .filter { !$0.isEmpty && $0 != b }

        for code in wanted {
            if let age = age(from: code, to: b), age < staleAfter { continue }
            let quote = try? await QuoteClient.shared.fetchQuote(ticker: "\(code)\(b)=X")
            if let price = quote?.price, price > 0 {
                store(code, b, rate: price, at: Date())
            }
        }
    }
}
