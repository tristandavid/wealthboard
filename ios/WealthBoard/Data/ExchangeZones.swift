import Foundation

/// The IANA time zone a listing actually trades in, derived from its ticker.
/// Ported from `data/ExchangeZones.kt`.
///
/// A quote endpoint reports `meta.exchangeTimezoneName` and that is always
/// preferred — but it is not always there. Batched price calls carry no zone at
/// all. Without a fallback, those rows print their last-trade time on the
/// *device's* clock, which is how the FTSE's 4:30 p.m. London close ends up
/// rendered as "11:30 a.m." for a reader in Toronto: a correct instant, labelled
/// in a way that reads as though London trades through the morning.
///
/// The suffix groups mirror `TickerFlag` exactly, so a ticker that gets a flag
/// also gets a zone.
enum ExchangeZones {

    /// Default for anything with no suffix — unsuffixed symbols are US.
    private static let us = "America/New_York"

    /// Zones for the index symbols the app lists, which have no suffix to read.
    /// Suffixed indices (FTSEMIB.MI, 000001.SS, ^SET.BK …) resolve through the
    /// suffix table below and are deliberately absent here.
    private static let indexZones: [String: String] = [
        "^DJI": us,
        "^GSPC": us,
        "^IXIC": us,
        "^RUT": us,
        "^VIX": us,
        "^NYA": us,
        "^GSPTSE": "America/Toronto",
        "^MXX": "America/Mexico_City",
        "^BVSP": "America/Sao_Paulo",
        "^FTSE": "Europe/London",
        "^GDAXI": "Europe/Berlin",
        "^STOXX50E": "Europe/Berlin",
        "^FCHI": "Europe/Paris",
        "^AEX": "Europe/Amsterdam",
        "^IBEX": "Europe/Madrid",
        "^SSMI": "Europe/Zurich",
        "^OMX": "Europe/Stockholm",
        "^OSEAX": "Europe/Oslo",
        "^OMXC25": "Europe/Copenhagen",
        "^N225": "Asia/Tokyo",
        "^HSI": "Asia/Hong_Kong",
        "^KS11": "Asia/Seoul",
        "^TWII": "Asia/Taipei",
        "^STI": "Asia/Singapore",
        "^KLSE": "Asia/Kuala_Lumpur",
        "^JKSE": "Asia/Jakarta",
        "^NSEI": "Asia/Kolkata",
        "^BSESN": "Asia/Kolkata",
        "^AXJO": "Australia/Sydney",
        "^AORD": "Australia/Sydney",
        "^NZ50": "Pacific/Auckland"
    ]

    private static let suffixZones: [String: String] = [
        ".TO": "America/Toronto", ".V": "America/Toronto", ".NE": "America/Toronto",
        ".CN": "America/Toronto", ".TSX": "America/Toronto",
        ".MX": "America/Mexico_City",
        ".SA": "America/Sao_Paulo",
        ".L": "Europe/London",
        ".IR": "Europe/Dublin",
        ".DE": "Europe/Berlin", ".F": "Europe/Berlin", ".BE": "Europe/Berlin",
        ".SG": "Europe/Berlin", ".HM": "Europe/Berlin", ".DU": "Europe/Berlin",
        ".MU": "Europe/Berlin", ".HA": "Europe/Berlin",
        ".PA": "Europe/Paris",
        ".AS": "Europe/Amsterdam",
        ".BR": "Europe/Brussels",
        ".LS": "Europe/Lisbon",
        ".MC": "Europe/Madrid",
        ".MI": "Europe/Rome",
        ".SW": "Europe/Zurich", ".VX": "Europe/Zurich",
        ".VI": "Europe/Vienna",
        ".ST": "Europe/Stockholm",
        ".OL": "Europe/Oslo",
        ".CO": "Europe/Copenhagen",
        ".HE": "Europe/Helsinki",
        ".IC": "Atlantic/Reykjavik",
        ".WA": "Europe/Warsaw",
        ".IS": "Europe/Istanbul",
        ".TA": "Asia/Jerusalem",
        ".JO": "Africa/Johannesburg",
        ".T": "Asia/Tokyo", ".JP": "Asia/Tokyo",
        ".HK": "Asia/Hong_Kong",
        ".SS": "Asia/Shanghai", ".SZ": "Asia/Shanghai",
        ".KS": "Asia/Seoul", ".KQ": "Asia/Seoul",
        ".TW": "Asia/Taipei", ".TWO": "Asia/Taipei",
        ".SI": "Asia/Singapore",
        ".KL": "Asia/Kuala_Lumpur",
        ".JK": "Asia/Jakarta",
        ".BK": "Asia/Bangkok",
        ".PS": "Asia/Manila",
        ".NS": "Asia/Kolkata", ".BO": "Asia/Kolkata",
        ".AX": "Australia/Sydney",
        ".NZ": "Pacific/Auckland"
    ]

    /// Zone for `ticker`, or nil when the symbol says nothing about where it
    /// trades (crypto, FX, an unrecognised suffix).
    ///
    /// Crypto and FX deliberately return nil rather than a venue zone: they
    /// trade continuously with no home exchange, so the viewer's own clock is
    /// the honest label for them.
    static func forTicker(_ ticker: String?) -> String? {
        guard let raw = ticker?.trimmingCharacters(in: .whitespaces).uppercased(), !raw.isEmpty else {
            return nil
        }
        if raw.contains("-USD") || raw.contains("-CAD") || raw.contains("-EUR")
            || raw.hasSuffix("=X") || raw.contains("USD=") {
            return nil
        }

        // Index symbols carry no exchange suffix to read — "^GSPTSE" is as
        // opaque as "^GSPC" — so they are listed outright. Without this they
        // all fall through to the unsuffixed default and every foreign index on
        // the Markets tab is labelled in New York's hours.
        if let indexZone = indexZones[raw] { return indexZone }

        let t = raw.hasPrefix("^") ? String(raw.dropFirst()) : raw
        guard let dotIndex = t.lastIndex(of: ".") else {
            return us   // AAPL, ES=F → US venues
        }
        let suffix = String(t[dotIndex...])
        return suffixZones[suffix]
    }

    /// The zone to render a quote's timestamp in: what the provider reported,
    /// then what the ticker implies, then nil for "use the viewer's clock".
    ///
    /// A provider zone is validated against the system's own database rather
    /// than trusted — an id this device doesn't know would silently resolve to
    /// GMT and print an hour that is simply wrong.
    static func resolve(providerZone: String?, ticker: String?) -> String? {
        if let reported = providerZone?.trimmingCharacters(in: .whitespaces),
           !reported.isEmpty,
           TimeZone(identifier: reported) != nil {
            return reported
        }
        return forTicker(ticker)
    }
}
