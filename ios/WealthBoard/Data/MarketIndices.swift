import Foundation

/// A stock-market index the Markets tab can show as a header row.
///
/// Quote providers return indices under opaque caret symbols ("^GSPTSE"), which
/// tells a user nothing — `displayName` is what actually gets rendered, with
/// `flag` beside it so the market is obvious at a glance.
struct MarketIndex: Identifiable, Hashable {
    let symbol: String        // "^GSPC"
    let displayName: String   // "S&P 500"
    let flag: String          // "🇺🇸"
    let countryCode: String   // ISO-3166 alpha-2, "US"

    var id: String { symbol }
}

/// Registry of the market indices the app knows how to label, plus the logic
/// for choosing which ones to show for a given user.
///
/// Ordering on the Markets tab is always: the three US benchmarks first, then
/// the user's home market (when it isn't the US), then the other majors.
enum MarketIndices {

    /// The three US benchmarks, always pinned to the top of the Markets tab.
    static let usIndices: [MarketIndex] = [
        MarketIndex(symbol: "^DJI",  displayName: "Dow Jones",        flag: "🇺🇸", countryCode: "US"),
        MarketIndex(symbol: "^GSPC", displayName: "S&P 500",          flag: "🇺🇸", countryCode: "US"),
        MarketIndex(symbol: "^IXIC", displayName: "NASDAQ Composite", flag: "🇺🇸", countryCode: "US")
    ]

    /// Primary index for each country the app can localise to.
    private static let countryIndices: [MarketIndex] = [
        MarketIndex(symbol: "^GSPTSE",    displayName: "S&P/TSX Composite", flag: "🇨🇦", countryCode: "CA"),
        MarketIndex(symbol: "^FTSE",      displayName: "FTSE 100",          flag: "🇬🇧", countryCode: "GB"),
        MarketIndex(symbol: "^GDAXI",     displayName: "DAX",               flag: "🇩🇪", countryCode: "DE"),
        MarketIndex(symbol: "^FCHI",      displayName: "CAC 40",            flag: "🇫🇷", countryCode: "FR"),
        MarketIndex(symbol: "^IBEX",      displayName: "IBEX 35",           flag: "🇪🇸", countryCode: "ES"),
        MarketIndex(symbol: "FTSEMIB.MI", displayName: "FTSE MIB",          flag: "🇮🇹", countryCode: "IT"),
        MarketIndex(symbol: "^AEX",       displayName: "AEX",               flag: "🇳🇱", countryCode: "NL"),
        MarketIndex(symbol: "^SSMI",      displayName: "SMI",               flag: "🇨🇭", countryCode: "CH"),
        MarketIndex(symbol: "^N225",      displayName: "Nikkei 225",        flag: "🇯🇵", countryCode: "JP"),
        MarketIndex(symbol: "^HSI",       displayName: "Hang Seng",         flag: "🇭🇰", countryCode: "HK"),
        MarketIndex(symbol: "000001.SS",  displayName: "SSE Composite",     flag: "🇨🇳", countryCode: "CN"),
        MarketIndex(symbol: "^AXJO",      displayName: "S&P/ASX 200",       flag: "🇦🇺", countryCode: "AU"),
        MarketIndex(symbol: "^NSEI",      displayName: "NIFTY 50",          flag: "🇮🇳", countryCode: "IN"),
        MarketIndex(symbol: "^KS11",      displayName: "KOSPI",             flag: "🇰🇷", countryCode: "KR"),
        MarketIndex(symbol: "^TWII",      displayName: "TAIEX",             flag: "🇹🇼", countryCode: "TW"),
        MarketIndex(symbol: "^STI",       displayName: "Straits Times",     flag: "🇸🇬", countryCode: "SG"),
        MarketIndex(symbol: "^BVSP",      displayName: "Bovespa",           flag: "🇧🇷", countryCode: "BR"),
        MarketIndex(symbol: "^MXX",       displayName: "IPC Mexico",        flag: "🇲🇽", countryCode: "MX"),
        MarketIndex(symbol: "^JKSE",      displayName: "IDX Composite",     flag: "🇮🇩", countryCode: "ID"),
        MarketIndex(symbol: "^KLSE",      displayName: "FTSE Malaysia",     flag: "🇲🇾", countryCode: "MY"),
        MarketIndex(symbol: "PSEI.PS",    displayName: "PSEi",              flag: "🇵🇭", countryCode: "PH"),
        MarketIndex(symbol: "^SET.BK",    displayName: "SET Index",         flag: "🇹🇭", countryCode: "TH"),
        MarketIndex(symbol: "^TA125.TA",  displayName: "TA-125",            flag: "🇮🇱", countryCode: "IL"),
        MarketIndex(symbol: "^J203.JO",   displayName: "JSE All Share",     flag: "🇿🇦", countryCode: "ZA"),
        MarketIndex(symbol: "^OMX",       displayName: "OMX Stockholm 30",  flag: "🇸🇪", countryCode: "SE"),
        MarketIndex(symbol: "^OSEAX",     displayName: "Oslo All Share",    flag: "🇳🇴", countryCode: "NO")
    ]

    private static let byCountry: [String: MarketIndex] = {
        var out: [String: MarketIndex] = [:]
        for index in countryIndices { out[index.countryCode] = index }
        return out
    }()

    /// Majors shown after the US block and the home market.
    private static let majorOrder = ["GB", "DE", "JP", "HK", "AU"]

    /// Every index the app can label, keyed by symbol.
    static let bySymbol: [String: MarketIndex] = {
        var out: [String: MarketIndex] = [:]
        for index in usIndices + countryIndices { out[index.symbol] = index }
        return out
    }()

    /// True when `symbol` is one of the market indices this screen pins on top.
    static func isIndex(_ symbol: String) -> Bool {
        bySymbol[symbol.uppercased()] != nil || bySymbol[symbol] != nil
    }

    /// Friendly name for `symbol`, or the raw symbol when it isn't a known index.
    static func displayName(for symbol: String) -> String {
        bySymbol[symbol]?.displayName ?? symbol
    }

    /// Flag for `symbol`, or empty when it isn't a known index.
    static func flag(for symbol: String) -> String {
        bySymbol[symbol]?.flag ?? ""
    }

    /// The index for an ISO country code, if the app knows one.
    static func forCountry(_ code: String?) -> MarketIndex? {
        guard let code else { return nil }
        return byCountry[code.uppercased()]
    }

    /// The device's region, used to pick a home market when the user hasn't
    /// chosen one in Settings. Returns an ISO alpha-2 code, defaulting to "US".
    static func deviceCountry() -> String {
        if #available(iOS 16.0, *) {
            if let region = Locale.current.region?.identifier, !region.isEmpty { return region }
        }
        return "US"
    }

    /// Builds the ordered index list for the Markets tab: the three US
    /// benchmarks, then the home market (if not US), then majors.
    static func indexes(homeCountry: String?) -> [MarketIndex] {
        let home = (homeCountry ?? deviceCountry()).uppercased()
        var result = usIndices

        if home != "US", let homeIndex = forCountry(home) {
            result.append(homeIndex)
        }

        for code in majorOrder {
            if code == home { continue }
            if let index = byCountry[code], !result.contains(index) {
                result.append(index)
            }
        }
        return result
    }

    /// Symbols for `indexes(homeCountry:)`, for seeding the watchlist.
    static func symbols(homeCountry: String?) -> [String] {
        indexes(homeCountry: homeCountry).map(\.symbol)
    }

    /// Country codes the Settings picker offers, with the label to show.
    static func selectableCountries() -> [(code: String, label: String)] {
        var out: [(code: String, label: String)] = [("US", "United States 🇺🇸")]
        for index in countryIndices where index.countryCode != "US" {
            out.append((index.countryCode, "\(countryName(index.countryCode)) \(index.flag)"))
        }
        return out.sorted { $0.label < $1.label }
    }

    private static func countryName(_ code: String) -> String {
        Locale.current.localizedString(forRegionCode: code) ?? code
    }
}
