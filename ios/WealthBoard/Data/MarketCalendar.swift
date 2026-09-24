import Foundation

/// One exchange closure on a given date.
///
/// `countryCode` is what callers filter on — matching on `market` text was fine
/// when there were two markets whose labels happened to contain "US" and
/// "Canadian", but it does not survive a third.
struct MarketClosure: Hashable, Identifiable {
    let market: String
    let holidayName: String
    let countryCode: String

    var id: String { "\(countryCode)-\(holidayName)" }
}

/// A locally-computed market holiday calendar for the Markets tab's closures
/// banner — NOT fetched live (there is no free, reliable "is the market open"
/// API this app calls), so it is a best-effort calendar of the well-known,
/// uncontroversial holidays each exchange observes, computed algorithmically
/// (nth-weekday-of-month rules, and the Anonymous Gregorian algorithm for
/// Easter/Good Friday) so it stays correct for any year rather than being a
/// list of hardcoded dates that goes stale.
///
/// Half-day early closes are not modelled, only full-day closures.
enum MarketCalendar {

    /// Exchange timezone for the North American markets. The TSX keeps the same
    /// hours as the US exchanges, so one clock covers both.
    private static let exchangeZone = TimeZone(identifier: "America/New_York") ?? .current

    private static var calendar: Calendar = {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = exchangeZone
        return c
    }()

    private static let openMinutes = 9 * 60 + 30   // 09:30
    private static let closeMinutes = 16 * 60      // 16:00

    // MARK: - Open / closed

    /// Whether the North American exchanges are trading right now.
    ///
    /// Weekends and full-day holidays are excluded; half-day early closes are
    /// not modelled, so the last couple of hours of a half-day reads as open.
    /// That errs toward fetching slightly too often rather than missing a real
    /// session, which is the right way round to be wrong.
    static func isMarketOpen(now: Date = Date()) -> Bool {
        let components = calendar.dateComponents([.year, .month, .day, .hour, .minute, .weekday], from: now)
        guard let weekday = components.weekday else { return false }
        if weekday == 1 || weekday == 7 { return false }   // Sunday = 1, Saturday = 7

        let day = DayKey(
            year: components.year ?? 0,
            month: components.month ?? 1,
            day: components.day ?? 1
        )
        // North America only, deliberately. This gates the background quote
        // refresh, which runs on New York hours — a London or Manila holiday
        // must not stop it.
        //
        // Closed only when BOTH are. This used to treat either market's
        // holiday as a full closure, so on US Thanksgiving — TSX trading
        // normally — no price alert on a Canadian holding was evaluated, and
        // every Canadian holiday did the same to US listings.
        let closed = Set(northAmericanClosures(on: day).map(\.countryCode))
        if closed.isSuperset(of: ["US", "CA"]) { return false }

        let minutes = (components.hour ?? 0) * 60 + (components.minute ?? 0)
        return minutes >= openMinutes && minutes < closeMinutes
    }

    /// Every market this calendar knows that is closed on `date`, for the
    /// markets a reader in `countryCode` cares about: their own, plus the US.
    ///
    /// The US is included for everyone because it sets the tone for every other
    /// market and nearly every portfolio holds something priced there. The
    /// reverse is not true, which is why a US reader gets only the US — showing
    /// a Frankfurt closure to someone who holds none of it is noise.
    ///
    /// An unknown or blank country falls back to the US alone rather than to
    /// everything.
    static func closures(on date: Date, countryCode: String?) -> [MarketClosure] {
        let day = dayKey(date)
        let home = countryCode?.uppercased()
        let wanted: [String]
        if let home, markets[home] != nil, home != "US" {
            wanted = [home, "US"]
        } else {
            wanted = ["US"]
        }
        return wanted.compactMap { code in
            guard let market = markets[code],
                  let name = market.holidays(day.year)[day] else { return nil }
            return MarketClosure(market: market.label, holidayName: name, countryCode: code)
        }
    }

    /// Whether this calendar has holiday data for `countryCode` at all.
    static func covers(_ countryCode: String?) -> Bool {
        guard let code = countryCode?.uppercased() else { return false }
        return markets[code] != nil
    }

    private static func northAmericanClosures(on day: DayKey) -> [MarketClosure] {
        ["US", "CA"].compactMap { code in
            guard let market = markets[code],
                  let name = market.holidays(day.year)[day] else { return nil }
            return MarketClosure(market: market.label, holidayName: name, countryCode: code)
        }
    }

    /// Next upcoming closure strictly after `date`, searching up to ~14 months.
    static func nextClosure(after date: Date, countryCode: String? = nil) -> (date: Date, closure: MarketClosure)? {
        guard var cursor = calendar.date(byAdding: .day, value: 1, to: calendar.startOfDay(for: date)),
              let limit = calendar.date(byAdding: .month, value: 14, to: date) else { return nil }
        while cursor < limit {
            let hits = closures(on: cursor, countryCode: countryCode)
            if let first = hits.first { return (cursor, first) }
            guard let next = calendar.date(byAdding: .day, value: 1, to: cursor) else { break }
            cursor = next
        }
        return nil
    }

    // MARK: - Day key
    //
    // Holiday tables are keyed by calendar date rather than by `Date`, which
    // carries a time of day and would never match.

    struct DayKey: Hashable {
        let year: Int
        let month: Int
        let day: Int
    }

    private static func dayKey(_ date: Date) -> DayKey {
        let c = calendar.dateComponents([.year, .month, .day], from: date)
        return DayKey(year: c.year ?? 0, month: c.month ?? 1, day: c.day ?? 1)
    }

    private static func date(_ key: DayKey) -> Date {
        var c = DateComponents()
        c.year = key.year; c.month = key.month; c.day = key.day
        c.hour = 12
        return calendar.date(from: c) ?? Date()
    }

    private static func make(_ year: Int, _ month: Int, _ day: Int) -> DayKey {
        DayKey(year: year, month: month, day: day)
    }

    // MARK: - Market registry

    private struct Market {
        let label: String
        let holidays: (Int) -> [DayKey: String]
    }

    private static let markets: [String: Market] = [
        "US": Market(label: "US stock markets") { usHolidays($0) },
        "CA": Market(label: "Canadian stock markets (TSX)") { caHolidays($0) },
        "GB": Market(label: "London Stock Exchange") { gbHolidays($0) },
        "DE": Market(label: "Frankfurt / XETRA") { deHolidays($0) },
        "AU": Market(label: "Australian Securities Exchange") { auHolidays($0) },
        "PH": Market(label: "Philippine Stock Exchange") { phHolidays($0) },
        "JP": Market(label: "Tokyo Stock Exchange") { jpHolidays($0) }
    ]

    // MARK: - US (NYSE/NASDAQ)

    private static func usHolidays(_ year: Int) -> [DayKey: String] {
        var map: [DayKey: String] = [:]
        map[observedWeekday(make(year, 1, 1))] = "New Year's Day"
        map[nthWeekday(year, 1, weekday: .monday, n: 3)] = "Martin Luther King Jr. Day"
        map[nthWeekday(year, 2, weekday: .monday, n: 3)] = "Washington's Birthday"
        map[addDays(easterSunday(year), -2)] = "Good Friday"
        map[lastWeekday(year, 5, weekday: .monday)] = "Memorial Day"
        map[observedWeekday(make(year, 6, 19))] = "Juneteenth"
        map[observedWeekday(make(year, 7, 4))] = "Independence Day"
        map[nthWeekday(year, 9, weekday: .monday, n: 1)] = "Labor Day"
        map[nthWeekday(year, 11, weekday: .thursday, n: 4)] = "Thanksgiving Day"
        map[observedWeekday(make(year, 12, 25))] = "Christmas Day"
        return map
    }

    // MARK: - Canada (TSX)

    private static func caHolidays(_ year: Int) -> [DayKey: String] {
        var map: [DayKey: String] = [:]
        map[observedWeekday(make(year, 1, 1))] = "New Year's Day"
        map[nthWeekday(year, 2, weekday: .monday, n: 3)] = "Family Day"
        map[addDays(easterSunday(year), -2)] = "Good Friday"
        // Victoria Day: Monday on or before May 24.
        map[previousOrSame(make(year, 5, 24), weekday: .monday)] = "Victoria Day"
        map[observedWeekday(make(year, 7, 1))] = "Canada Day"
        map[nthWeekday(year, 9, weekday: .monday, n: 1)] = "Labour Day"
        map[nthWeekday(year, 10, weekday: .monday, n: 2)] = "Thanksgiving"
        map[observedWeekday(make(year, 12, 25))] = "Christmas Day"
        map[observedWeekday(make(year, 12, 26))] = "Boxing Day"
        return map
    }

    // MARK: - United Kingdom (LSE)

    private static func gbHolidays(_ year: Int) -> [DayKey: String] {
        var map: [DayKey: String] = [:]
        map[substituteForward(make(year, 1, 1))] = "New Year's Day"
        let easter = easterSunday(year)
        map[addDays(easter, -2)] = "Good Friday"
        map[addDays(easter, 1)] = "Easter Monday"
        map[nthWeekday(year, 5, weekday: .monday, n: 1)] = "Early May bank holiday"
        map[lastWeekday(year, 5, weekday: .monday)] = "Spring bank holiday"
        map[lastWeekday(year, 8, weekday: .monday)] = "Summer bank holiday"
        // Christmas and Boxing Day are substituted FORWARD in the UK, never
        // back: a Saturday Christmas moves to the following Monday, pushing
        // Boxing Day to the Tuesday.
        let christmas = substituteForward(make(year, 12, 25))
        map[christmas] = "Christmas Day"
        var boxing = substituteForward(make(year, 12, 26))
        if boxing == christmas { boxing = addDays(boxing, 1) }
        map[boxing] = "Boxing Day"
        return map
    }

    // MARK: - Germany (Frankfurt / XETRA)

    private static func deHolidays(_ year: Int) -> [DayKey: String] {
        var map: [DayKey: String] = [:]
        let easter = easterSunday(year)
        map[make(year, 1, 1)] = "New Year's Day"
        map[addDays(easter, -2)] = "Good Friday"
        map[addDays(easter, 1)] = "Easter Monday"
        map[make(year, 5, 1)] = "Labour Day"
        map[addDays(easter, 50)] = "Whit Monday"
        // XETRA closes the last two trading days of the year outright; these
        // are full closures, not half-days.
        map[make(year, 12, 24)] = "Christmas Eve"
        map[make(year, 12, 25)] = "Christmas Day"
        map[make(year, 12, 26)] = "St. Stephen's Day"
        map[make(year, 12, 31)] = "New Year's Eve"
        return map
    }

    // MARK: - Australia (ASX)

    private static func auHolidays(_ year: Int) -> [DayKey: String] {
        var map: [DayKey: String] = [:]
        let easter = easterSunday(year)
        map[substituteForward(make(year, 1, 1))] = "New Year's Day"
        map[substituteForward(make(year, 1, 26))] = "Australia Day"
        map[addDays(easter, -2)] = "Good Friday"
        map[addDays(easter, 1)] = "Easter Monday"
        map[make(year, 4, 25)] = "ANZAC Day"
        map[nthWeekday(year, 6, weekday: .monday, n: 2)] = "King's Birthday"
        let christmas = substituteForward(make(year, 12, 25))
        map[christmas] = "Christmas Day"
        var boxing = substituteForward(make(year, 12, 26))
        if boxing == christmas { boxing = addDays(boxing, 1) }
        map[boxing] = "Boxing Day"
        return map
    }

    // MARK: - Philippines (PSE)

    /// The PSE's regular, date-certain closures.
    ///
    /// Deliberately omits the two Eid holidays, which follow the Islamic lunar
    /// calendar and are proclaimed each year rather than falling on a fixed
    /// date — and the "special non-working days" declared annually, which are
    /// not predictable at all. Those days read as open. Erring toward open is
    /// the right way round: the banner stays silent rather than announcing a
    /// closure that isn't real.
    private static func phHolidays(_ year: Int) -> [DayKey: String] {
        var map: [DayKey: String] = [:]
        let easter = easterSunday(year)
        map[make(year, 1, 1)] = "New Year's Day"
        map[addDays(easter, -3)] = "Maundy Thursday"
        map[addDays(easter, -2)] = "Good Friday"
        map[make(year, 4, 9)] = "Araw ng Kagitingan"
        map[make(year, 5, 1)] = "Labor Day"
        map[make(year, 6, 12)] = "Independence Day"
        map[lastWeekday(year, 8, weekday: .monday)] = "National Heroes Day"
        map[make(year, 11, 1)] = "All Saints' Day"
        map[make(year, 11, 30)] = "Bonifacio Day"
        map[make(year, 12, 25)] = "Christmas Day"
        map[make(year, 12, 30)] = "Rizal Day"
        map[make(year, 12, 31)] = "Last day of the year"
        return map
    }

    // MARK: - Japan (TSE)

    /// Japan's fixed and nth-weekday national holidays.
    ///
    /// The two equinox holidays are set astronomically and formally announced
    /// only the February before, so they are approximated from the standard
    /// 20th/21st and 22nd/23rd rule. A year where the announcement differs will
    /// be one day out on those two dates alone.
    private static func jpHolidays(_ year: Int) -> [DayKey: String] {
        var map: [DayKey: String] = [:]
        // Japan's substitute-holiday rule: a Sunday holiday moves to the Monday.
        func put(_ key: DayKey, _ name: String) {
            map[weekday(of: key) == .sunday ? addDays(key, 1) : key] = name
        }
        map[make(year, 1, 1)] = "New Year's Day"
        map[make(year, 1, 2)] = "New Year holiday"
        map[make(year, 1, 3)] = "New Year holiday"
        map[nthWeekday(year, 1, weekday: .monday, n: 2)] = "Coming of Age Day"
        put(make(year, 2, 11), "National Foundation Day")
        put(make(year, 2, 23), "Emperor's Birthday")
        put(make(year, 3, year % 4 == 0 ? 20 : 21), "Vernal Equinox Day")
        put(make(year, 4, 29), "Showa Day")
        put(make(year, 5, 3), "Constitution Memorial Day")
        put(make(year, 5, 4), "Greenery Day")
        put(make(year, 5, 5), "Children's Day")
        map[nthWeekday(year, 7, weekday: .monday, n: 3)] = "Marine Day"
        put(make(year, 8, 11), "Mountain Day")
        map[nthWeekday(year, 9, weekday: .monday, n: 3)] = "Respect for the Aged Day"
        put(make(year, 9, year % 4 == 0 ? 22 : 23), "Autumnal Equinox Day")
        map[nthWeekday(year, 10, weekday: .monday, n: 2)] = "Sports Day"
        put(make(year, 11, 3), "Culture Day")
        put(make(year, 11, 23), "Labour Thanksgiving Day")
        map[make(year, 12, 31)] = "Year-end holiday"
        return map
    }

    // MARK: - Date helpers

    enum Weekday: Int {
        case sunday = 1, monday, tuesday, wednesday, thursday, friday, saturday
    }

    private static func weekday(of key: DayKey) -> Weekday {
        let index = calendar.component(.weekday, from: date(key))
        return Weekday(rawValue: index) ?? .monday
    }

    private static func addDays(_ key: DayKey, _ days: Int) -> DayKey {
        guard let shifted = calendar.date(byAdding: .day, value: days, to: date(key)) else { return key }
        return dayKey(shifted)
    }

    /// The `n`th `weekday` of a month, 1-based.
    private static func nthWeekday(_ year: Int, _ month: Int, weekday target: Weekday, n: Int) -> DayKey {
        var key = make(year, month, 1)
        var found = 0
        for _ in 0..<31 {
            if weekday(of: key) == target {
                found += 1
                if found == n { return key }
            }
            key = addDays(key, 1)
            if key.month != month { break }
        }
        return make(year, month, 1)
    }

    /// The last `weekday` of a month.
    private static func lastWeekday(_ year: Int, _ month: Int, weekday target: Weekday) -> DayKey {
        let daysInMonth = calendar.range(of: .day, in: .month, for: date(make(year, month, 1)))?.count ?? 28
        var key = make(year, month, daysInMonth)
        for _ in 0..<7 {
            if weekday(of: key) == target { return key }
            key = addDays(key, -1)
        }
        return key
    }

    /// The `weekday` on or before `key`.
    private static func previousOrSame(_ key: DayKey, weekday target: Weekday) -> DayKey {
        var cursor = key
        for _ in 0..<7 {
            if weekday(of: cursor) == target { return cursor }
            cursor = addDays(cursor, -1)
        }
        return key
    }

    /// Some markets never move a holiday backwards: a weekend date is always
    /// observed on the following Monday (UK, Australia).
    private static func substituteForward(_ key: DayKey) -> DayKey {
        switch weekday(of: key) {
        case .saturday: return addDays(key, 2)
        case .sunday: return addDays(key, 1)
        default: return key
        }
    }

    /// The US rule: a fixed date observed on the nearest weekday (Saturday →
    /// the Friday before; Sunday → the Monday after).
    private static func observedWeekday(_ key: DayKey) -> DayKey {
        switch weekday(of: key) {
        case .saturday: return addDays(key, -1)
        case .sunday: return addDays(key, 1)
        default: return key
        }
    }

    /// Anonymous Gregorian algorithm (Meeus/Jones/Butcher) for Easter Sunday.
    private static func easterSunday(_ year: Int) -> DayKey {
        let a = year % 19
        let b = year / 100
        let c = year % 100
        let d = b / 4
        let e = b % 4
        let f = (b + 8) / 25
        let g = (b - f + 1) / 3
        let h = (19 * a + b - d - g + 15) % 30
        let i = c / 4
        let k = c % 4
        let l = (32 + 2 * e + 2 * i - h - k) % 7
        let m = (a + 11 * h + 22 * l) / 451
        let month = (h + l - 7 * m + 114) / 31
        let day = ((h + l - 7 * m + 114) % 31) + 1
        return make(year, month, day)
    }
}
