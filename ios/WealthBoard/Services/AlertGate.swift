import Foundation

/// Decides which alert rules are worth evaluating on a given pass.
///
/// Exists because the two families of rule have nothing in common about when
/// their answer can change, and gating them together gets one of them wrong
/// whichever way you choose:
///
/// - A PRICE rule is a function of a quote. Markets trade roughly 32 of the
///   168 hours in a week, so evaluating one outside a session spends a request
///   against an undocumented endpoint to re-read a number that cannot have
///   moved.
/// - An EX-DIVIDEND rule is a function of the CALENDAR. "Within 3 days"
///   becomes true because today advanced, not because the ex-date changed — so
///   it has to be evaluated on days the market never opens. Gating it on
///   market hours would mean nothing is evaluated between Friday's close and
///   Monday's open, and an ex-date falling on the Monday is announced on the
///   Monday: after the last chance to buy in, which is the entire thing the
///   rule exists to prevent.
///
/// So: price rules follow the session, ex-dividend rules follow the date.
///
/// Mirrors `AlertGate` on Android decision for decision.
enum AlertGate {

    private static let lastExDividendDayKey = "wealthboard.alertGate.lastExDividendDay"

    /// Exchange clock, matching `MarketCalendar`. The "day" an ex-dividend
    /// check belongs to is a New York day, not the device's: a user in Manila
    /// would otherwise get two checks on one North American date and none on
    /// the next.
    private static let exchangeZone = TimeZone(identifier: "America/New_York") ?? .current

    private static var exchangeCalendar: Calendar = {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = exchangeZone
        return c
    }()

    /// Whether a ticker trades around the clock.
    ///
    /// Decided from the TICKER, not from whether the user holds any crypto.
    /// Alerts are keyed by ticker precisely so they can watch something that is
    /// not held — a watchlist row, or a coin being waited on before buying —
    /// and a holdings-based test means an alert on BTC-USD the user has not
    /// bought yet is evaluated only during North American equity hours, which
    /// is close to the least useful window crypto has.
    ///
    /// Suffix-matched rather than substring-matched: "-USD" ends every crypto
    /// pair this provider returns, but a substring test would also catch a
    /// hypothetical "USD-HEDGED" equity listing.
    static func isCryptoTicker(_ ticker: String) -> Bool {
        let symbol = ticker.trimmingCharacters(in: .whitespaces).uppercased()
        return cryptoQuoteSuffixes.contains { symbol.hasSuffix($0) }
    }

    private static let cryptoQuoteSuffixes = [
        "-USD", "-USDT", "-USDC", "-CAD", "-EUR", "-GBP", "-BTC", "-ETH"
    ]

    /// The rule kinds this pass should evaluate, given the tickers the user's
    /// enabled alerts mention.
    ///
    /// An empty result means the pass has nothing to do and should make no
    /// network calls at all.
    static func kinds(
        forTickers tickers: [String],
        now: Date = Date()
    ) -> Set<AlertKind> {
        var kinds: Set<AlertKind> = []

        // Price rules: only while something they watch is actually trading.
        if MarketCalendar.isMarketOpen(now: now) || tickers.contains(where: isCryptoTicker) {
            kinds.insert(.priceAbove)
            kinds.insert(.priceBelow)
            kinds.insert(.dayMovePercent)
        }

        // Ex-dividend rules: once per exchange day, whenever that day's first
        // pass happens to be. More often than that is wasted — the answer is a
        // date compared against today, and neither operand changes again until
        // midnight. Less often risks skipping a day entirely.
        if isExDividendDue(now: now) {
            kinds.insert(.exDividendWithinDays)
        }

        return kinds
    }

    /// Whether today's ex-dividend check has yet to run.
    static func isExDividendDue(now: Date = Date()) -> Bool {
        let stored = UserDefaults.standard.integer(forKey: lastExDividendDayKey)
        return stored != exchangeDay(now)
    }

    /// Records that today's ex-dividend check has run.
    ///
    /// Called only after a pass that actually evaluated those rules, so a pass
    /// skipped for any other reason — not Premium, no alerts, a thrown fetch —
    /// does not consume the day's single check.
    static func markExDividendChecked(now: Date = Date()) {
        UserDefaults.standard.set(exchangeDay(now), forKey: lastExDividendDayKey)
    }

    /// The exchange-local date as `yyyyMMdd`, which compares and stores as one Int.
    private static func exchangeDay(_ now: Date) -> Int {
        let c = exchangeCalendar.dateComponents([.year, .month, .day], from: now)
        return (c.year ?? 0) * 10_000 + (c.month ?? 1) * 100 + (c.day ?? 1)
    }
}
