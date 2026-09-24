import Foundation

/// "As of" labels for a quote's last-trade timestamp.
///
/// Ported from `ui/format/TradeTime.kt`. A price is struck on the exchange's
/// clock, not the reader's: rendering that instant in the device's zone is
/// arithmetically correct and reads as nonsense — the FTSE's 4:30 p.m. London
/// close came out as "11:30 a.m." in Toronto. So the label is built in the
/// listing's own zone and *names* that zone. When the timestamp isn't from
/// today in that zone, the date is shown too, so a Friday close can't
/// masquerade as a live Sunday price.
enum TradeTime {

    private static func zone(_ id: String?) -> TimeZone {
        guard let id, !id.isEmpty, let tz = TimeZone(identifier: id) else { return .current }
        return tz
    }

    private static func timeFormatter(_ tz: TimeZone) -> DateFormatter {
        let f = DateFormatter()
        f.locale = Locale.current
        f.timeZone = tz
        f.dateFormat = "h:mm a"
        return f
    }

    private static func dateTimeFormatter(_ tz: TimeZone) -> DateFormatter {
        let f = DateFormatter()
        f.locale = Locale.current
        f.timeZone = tz
        f.dateFormat = "MMM d, h:mm a"
        return f
    }

    /// Short name for `zoneId` at `date` — "EDT", "BST", "JST", or a
    /// "GMT+5:30" style offset for zones with no abbreviation.
    ///
    /// Whether the instant fell in daylight time matters: a July close in
    /// London is BST and a January one GMT, and printing the wrong one of those
    /// against a correct time is a subtler error than printing no zone at all.
    static func zoneLabel(_ zoneId: String?, at date: Date) -> String {
        let tz = zone(zoneId)
        let style: NSTimeZone.NameStyle = tz.isDaylightSavingTime(for: date)
            ? .shortDaylightSaving
            : .shortStandard
        let name = tz.localizedName(for: style, locale: Locale(identifier: "en_US")) ?? tz.abbreviation(for: date) ?? ""
        if name.isEmpty {
            return offsetLabel(tz, at: date)
        }
        if name.hasPrefix("GMT") { return name }
        if name.count <= 5 { return name }
        // Anything long or wordy ("Japan Standard Time") is trimmed to initials.
        let initials = name.split(separator: " ").compactMap { $0.first }.map { String($0).uppercased() }.joined()
        return initials.isEmpty ? name : initials
    }

    private static func offsetLabel(_ tz: TimeZone, at date: Date) -> String {
        let seconds = tz.secondsFromGMT(for: date)
        let sign = seconds < 0 ? "-" : "+"
        let mins = abs(seconds) / 60
        return String(format: "GMT%@%d:%02d", sign, mins / 60, mins % 60)
    }

    private static func isSameDay(_ a: Date, _ b: Date, in tz: TimeZone) -> Bool {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = tz
        return cal.isDate(a, inSameDayAs: b)
    }

    /// The row label for a quote struck at `date`.
    ///
    /// `providerZone` is `meta.exchangeTimezoneName` when the provider sent one;
    /// `ticker` is used to work out the venue when it didn't. Falls back to the
    /// device's own clock only when neither says anything — crypto and FX,
    /// which have no home exchange.
    ///
    /// - Parameter showZone: false for tight layouts (a secondary extended-hours
    ///   line) where the zone is already established by the row above.
    static func label(
        at date: Date,
        providerZone: String? = nil,
        ticker: String? = nil,
        now: Date = Date(),
        showZone: Bool = true
    ) -> String {
        let zoneId = ExchangeZones.resolve(providerZone: providerZone, ticker: ticker)
        let tz = zone(zoneId)
        let sameDay = isSameDay(date, now, in: tz)
        let base = sameDay
            ? timeFormatter(tz).string(from: date)
            : dateTimeFormatter(tz).string(from: date)
        // No zone id resolved means the device's own clock is what was used,
        // and naming it would suggest the exchange trades on the reader's hours.
        guard showZone, let zoneId else { return base }
        return base + " " + zoneLabel(zoneId, at: date)
    }
}
