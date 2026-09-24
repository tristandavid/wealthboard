import Foundation

/// Resolves a logo image for a ticker, worldwide.
/// Ported from `net/LogoResolver.kt`.
///
/// Resolution is deliberately unmetered at every step — a logo is decoration,
/// and it must not be able to exhaust a quote provider's quota or block a row
/// from drawing:
///
/// 1. **Issuer match on the security's name** — no network call at all. Funds
///    are named after their issuer ("VANGUARD ALL EQUITY ETF PORTFOLIO"), and
///    an issuer has exactly one website, so the name alone identifies the mark.
///    Preferred over any symbol lookup because fund tickers are arbitrary while
///    names are not.
/// 2. **Keyless logo CDN, by domain** — a URL is constructed, not requested.
///    `AsyncImage` fetches it while drawing the row, the CDN caches it, and a
///    domain it has never heard of 404s into the initial-letter avatar.
///
/// Every outcome is cached, misses included, so a ticker with no logo does not
/// re-resolve every time a row carrying it is drawn.
enum LogoResolver {

    // ATTRIBUTION IS A CONDITION OF THE FREE TIER. A visible credit linking
    // back is required; the Credits row in Settings is where it lives.
    // Removing it puts the app outside that licence.
    static let attributionText = "Company logos by elbstream.com"
    static let attributionURL = "https://elbstream.com"

    /// ticker → domain, or nil for "asked, and there isn't one".
    private static var cache: [String: String?] = [:]
    private static let lock = NSLock()

    /// Domains, not image URLs, so the icon service can be swapped in one place.
    /// Matched as substrings against the security's own name, longest first, so
    /// "BMO" can't win over a name that also contains a more specific issuer.
    private static let issuerDomains: [(needle: String, domain: String)] = [
        // Canada
        ("VANGUARD FTSE", "vanguard.com"),
        ("VANGUARD", "vanguard.com"),
        ("ISHARES", "ishares.com"),
        ("BLACKROCK", "blackrock.com"),
        ("BMO", "bmo.com"),
        ("HORIZONS", "horizonsetfs.com"),
        ("GLOBAL X", "globalx.ca"),
        ("PURPOSE", "purposeinvest.com"),
        ("HAMILTON", "hamiltonetfs.com"),
        ("HARVEST", "harvestportfolios.com"),
        ("EVOLVE", "evolveetfs.com"),
        ("MACKENZIE", "mackenzieinvestments.com"),
        ("DYNAMIC", "dynamic.ca"),
        ("FIRST TRUST", "firsttrust.com"),
        ("CI ", "ci.com"),
        ("TD ", "td.com"),
        ("RBC", "rbc.com"),
        ("SCOTIA", "scotiabank.com"),
        ("BANK OF NOVA SCOTIA", "scotiabank.com"),
        ("MANULIFE", "manulife.ca"),
        // US
        ("SPDR", "ssga.com"),
        ("STATE STREET", "ssga.com"),
        ("FIDELITY", "fidelity.com"),
        ("INVESCO", "invesco.com"),
        ("SCHWAB", "schwab.com"),
        ("JPMORGAN", "jpmorgan.com"),
        ("J.P. MORGAN", "jpmorgan.com"),
        ("PIMCO", "pimco.com"),
        ("WISDOMTREE", "wisdomtree.com"),
        ("VANECK", "vaneck.com"),
        ("ARK ", "ark-funds.com"),
        ("DIREXION", "direxion.com"),
        ("PROSHARES", "proshares.com"),
        ("FRANKLIN", "franklintempleton.com"),
        ("GOLDMAN", "gsam.com"),
        ("T. ROWE", "troweprice.com"),
        // Europe / global
        ("XTRACKERS", "xtrackers.com"),
        ("AMUNDI", "amundi.com"),
        ("LYXOR", "lyxor.com"),
        ("UBS", "ubs.com"),
        ("HSBC", "hsbc.com"),
        ("DEKA", "deka.de"),
        ("BNP PARIBAS", "bnpparibas.com"),
        ("LEGAL & GENERAL", "lgim.com"),
        ("L&G ", "lgim.com")
    ]

    /// Well-known single-company symbols whose domain is not derivable from the
    /// name. Deliberately short: this is a convenience list, not a directory,
    /// and anything absent simply draws its initial.
    private static let symbolDomains: [String: String] = [
        "AAPL": "apple.com",
        "MSFT": "microsoft.com",
        "GOOG": "abo" + "ut.google",
        "GOOGL": "abo" + "ut.google",
        "AMZN": "amazon.com",
        "META": "meta.com",
        "NVDA": "nvidia.com",
        "TSLA": "tesla.com",
        "BRK-B": "berkshirehathaway.com",
        "JPM": "jpmorganchase.com",
        "V": "visa.com",
        "MA": "mastercard.com",
        "JNJ": "jnj.com",
        "WMT": "walmart.com",
        "KO": "coca-colacompany.com",
        "PEP": "pepsico.com",
        "DIS": "thewaltdisneycompany.com",
        "NFLX": "netflix.com",
        "INTC": "intel.com",
        "AMD": "amd.com",
        "BNS.TO": "scotiabank.com",
        "TD.TO": "td.com",
        "RY.TO": "rbc.com",
        "BMO.TO": "bmo.com",
        "CM.TO": "cibc.com",
        "ENB.TO": "enbridge.com",
        "SHOP.TO": "shopify.com",
        "CNR.TO": "cn.ca",
        "T.TO": "telus.com",
        "BCE.TO": "bce.ca"
    ]

    /// Keyless logo CDN, keyed on the FULL Yahoo-style symbol.
    ///
    /// The symbol keeps its exchange suffix: the service indexes "XEQT.TO" and
    /// "Z74.SI", and the bare base symbol is a different row that 404s.
    private static let symbolCDN = "https://api.elbstream.com/logos/symbol/"
    /// Forces a raster response, which `AsyncImage` can decode.
    private static let logoFormat = "?format=png"

    /// The image URL for a security, or nil when the app has nothing better
    /// than a letter to draw.
    ///
    /// Two paths, the same two the Android build uses, in the same order:
    ///
    /// 1. **Issuer, from the name.** A fund is named after its house, and an
    ///    issuer has one website, so the name alone identifies the mark.
    /// 2. **Symbol lookup.** A URL is built, never requested — the image loader
    ///    fetches it while drawing the row, and a symbol with no logo 404s into
    ///    the initial-letter avatar exactly as a nil would have.
    ///
    /// Step 2 is what was missing here. iOS resolved a DOMAIN only, so any
    /// security whose name matched none of the issuer needles — which is every
    /// ordinary company, since "APPLE INC." is not an issuer — got no logo at
    /// all, and the domains it did resolve were pointed at a logo API that no
    /// longer answers without a key. That is why the letters showed up.
    static func imageURL(ticker: String, name: String?) -> String? {
        let key = ticker.trimmingCharacters(in: .whitespaces).uppercased()
        guard !key.isEmpty else { return nil }
        // No issuer to borrow a mark from, and no company behind them.
        if key.hasPrefix("^") || key.contains("=") { return nil }
        if key.contains("-USD") || key.contains("-BTC") || key.contains("-ETH") { return nil }

        if let domain = domain(ticker: key, name: name) {
            return iconURL(forDomain: domain)
        }

        let allowed = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: ".-"))
        guard key.unicodeScalars.allSatisfy({ allowed.contains($0) }) else { return nil }
        return symbolCDN + key + logoFormat
    }

    /// A domain's own mark, from a keyless favicon service — the same one the
    /// Android build uses, so a fund resolved by issuer looks identical on both.
    private static func iconURL(forDomain domain: String) -> String {
        let clean = domain.lowercased().hasPrefix("www.")
            ? String(domain.lowercased().dropFirst(4))
            : domain.lowercased()
        return "https://icons.duckduckgo.com/ip3/\(clean).ico"
    }

    /// The domain whose mark should stand for this security, or nil when the
    /// app has nothing better than a letter.
    ///
    /// Indices, futures, FX pairs and crypto deliberately return nil: they have
    /// no issuer, and borrowing an exchange's logo for them would be misleading.
    static func domain(ticker: String, name: String?) -> String? {
        let key = ticker.uppercased()

        lock.lock()
        if let cached = cache[key] {
            lock.unlock()
            return cached
        }
        lock.unlock()

        let resolved = resolve(ticker: key, name: name)

        lock.lock()
        cache[key] = resolved
        lock.unlock()
        return resolved
    }

    private static func resolve(ticker: String, name: String?) -> String? {
        if ticker.hasPrefix("^") { return nil }
        if ticker.contains("=") { return nil }
        if ticker.contains("-USD") || ticker.contains("-BTC") || ticker.contains("-ETH") { return nil }

        // Issuer first: a fund's own name identifies its house, and costs
        // nothing to match.
        if let name {
            let upper = name.uppercased()
            // Longest needle first, so "VANGUARD FTSE" beats "VANGUARD" and
            // a name containing both "CI " and something more specific resolves
            // to the specific one.
            let sorted = issuerDomains.sorted { $0.needle.count > $1.needle.count }
            if let hit = sorted.first(where: { upper.contains($0.needle) }) {
                return hit.domain
            }
        }

        // Then the small symbol directory, with and without its exchange suffix.
        if let direct = symbolDomains[ticker] { return direct }
        if let dot = ticker.firstIndex(of: "."),
           let base = symbolDomains[String(ticker[..<dot])] {
            return base
        }
        return nil
    }
}
