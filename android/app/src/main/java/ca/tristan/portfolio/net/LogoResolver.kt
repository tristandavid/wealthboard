package ca.tristan.portfolio.net

/**
 * Resolves a logo image for a ticker, worldwide.
 *
 * The app's original source was Finnhub's `/stock/profile2`, whose free tier
 * profiles **US listings only**, with Yahoo's `assetProfile.website` behind it
 * for everything else. Both were wrong for this job:
 *
 *  - Finnhub spent a *metered* request — from the same free-tier budget that
 *    quotes and search depend on — to decorate a row. A scrolling list could
 *    burn the day's allowance on icons and leave prices unable to load.
 *  - The Yahoo path was gated behind a cookie/crumb session and returned 401
 *    for every symbol in every market, so in practice it resolved nothing.
 *
 * What remained working was the one path that made no request at all: matching
 * a fund's issuer from its own name. That is why Canadian and US *funds* showed
 * a mark and individual companies everywhere showed a letter.
 *
 * Resolution order now, neither step metered:
 *
 *  1. **Issuer match on the security's name** — no network call. Funds are named
 *     after their issuer ("VANGUARD ALL EQUITY ETF PORTFOLIO"), and an issuer has
 *     exactly one website, so the name alone identifies the mark. Preferred over
 *     the symbol lookup because fund tickers are arbitrary while names are not.
 *  2. **Keyless logo CDN, by symbol** — a URL is constructed, not requested. The
 *     image loader fetches it while drawing the row, the CDN caches it, and a
 *     symbol it has never heard of 404s into the initial avatar.
 *
 * Every outcome is cached, misses included, so a ticker with no logo does not
 * re-resolve every time a row carrying it is composed.
 */
object LogoResolver {

    /**
     * Symbol → logo CDN.
     *
     * Keyless and unmetered, which is the reason for choosing it: a logo is
     * decoration, and it should not be able to exhaust a quote provider's quota
     * or block a row from drawing.
     *
     * ATTRIBUTION IS A CONDITION OF THE FREE TIER. A visible credit linking to
     * elbstream.com must be shown at no less than 12pt. [SettingsScreen] renders
     * it under About — do not remove it without moving to the paid tier, or the
     * app is using the service outside its licence.
     */
    private const val LOGO_CDN = "https://api.elbstream.com/logos/symbol/"

    /**
     * Forces a raster response.
     *
     * The service defaults to SVG, and Coil does NOT decode SVG without the
     * separate coil-svg artifact — so every logo request succeeded, returned a
     * valid image, and then failed to decode into the initial-letter fallback.
     * From the outside that is indistinguishable from "no logo exists", which
     * is exactly how it looked: US and international rows stayed on letters
     * while Canadian funds (resolved by issuer domain, a .ico) drew fine.
     */
    private const val LOGO_FORMAT = "?format=png"

    /** Human-readable credit and link, rendered by the About section. */
    const val ATTRIBUTION_TEXT = "Company logos by elbstream.com"
    const val ATTRIBUTION_URL = "https://elbstream.com"

    /** ticker → logo URL, or null for "asked, and there isn't one". */
    private val cache = HashMap<String, String?>()

    /**
     * Domains, not image URLs, so the icon service can be swapped in one place.
     * Matched as substrings against the security's own name, longest first, so
     * "BMO" can't win over a name that also contains a more specific issuer.
     */
    private val ISSUER_DOMAINS: List<Pair<String, String>> = listOf(
        // Canada
        "VANGUARD"            to "vanguard.com",
        "ISHARES"             to "ishares.com",
        "BLACKROCK"           to "blackrock.com",
        "BMO"                 to "bmo.com",
        "HORIZONS"            to "horizonsetfs.com",
        "GLOBAL X"            to "globalx.ca",
        "PURPOSE"             to "purposeinvest.com",
        "HAMILTON"            to "hamiltonetfs.com",
        "HARVEST"             to "harvestportfolios.com",
        "EVOLVE"              to "evolveetfs.com",
        "MACKENZIE"           to "mackenzieinvestments.com",
        "DYNAMIC"             to "dynamic.ca",
        "FIRST TRUST"         to "firsttrust.com",
        "CI "                 to "ci.com",
        "TD "                 to "td.com",
        "RBC"                 to "rbc.com",
        "SCOTIA"              to "scotiabank.com",
        "MANULIFE"            to "manulife.ca",
        // US
        "SPDR"                to "ssga.com",
        "STATE STREET"        to "ssga.com",
        "FIDELITY"            to "fidelity.com",
        "INVESCO"             to "invesco.com",
        "SCHWAB"              to "schwab.com",
        "JPMORGAN"            to "jpmorgan.com",
        "J.P. MORGAN"         to "jpmorgan.com",
        "PIMCO"               to "pimco.com",
        "WISDOMTREE"          to "wisdomtree.com",
        "VANECK"              to "vaneck.com",
        "ARK "                to "ark-funds.com",
        "DIREXION"            to "direxion.com",
        "PROSHARES"           to "proshares.com",
        "FRANKLIN"            to "franklintempleton.com",
        "GOLDMAN"             to "gsam.com",
        "T. ROWE"             to "troweprice.com",
        // Europe / global
        "XTRACKERS"           to "xtrackers.com",
        "AMUNDI"              to "amundi.com",
        "LYXOR"               to "lyxor.com",
        "UBS"                 to "ubs.com",
        "HSBC"                to "hsbc.com",
        "DEKA"                to "deka.de",
        "BNP PARIBAS"         to "bnpparibas.com",
        "LEGAL & GENERAL"     to "lgim.com",
        "L&G "                to "lgim.com",
        "VANGUARD FTSE"       to "vanguard.com",
        // Asia-Pacific. Sparse before this: an Asian fund matched nothing and,
        // with the quoteSummary path dead, fell straight to a letter avatar.
        "NIKKO"               to "nikkoam.com",
        "NOMURA"              to "nomura-asset.co.jp",
        "DAIWA"               to "daiwa-am.co.jp",
        "MIRAE"               to "miraeasset.com",
        "SAMSUNG"             to "samsungfunds.com",
        "CHINAAMC"            to "chinaamc.com",
        "E FUND"              to "efunds.com.cn",
        "TRACKER FUND"        to "trackerfund.com.hk",
        "NIPPON INDIA"        to "nipponindiaim.com",
        "SBI "                to "sbimf.com",
        "BETASHARES"          to "betashares.com.au",
        "VANECK AUSTRALIA"    to "vaneck.com.au",
        // Philippines — the PSE's index funds are issued by the banks
        // themselves, so the issuer match is what will cover them.
        "BPI "                to "bpi.com.ph",
        "BDO "                to "bdo.com.ph",
        "SUN LIFE"            to "sunlife.com",
        "PHILEQUITY"          to "philequity.net",
        "ATRAM"               to "atram.com.ph",
        "FIRST METRO"         to "firstmetro.com.ph"
    ).sortedByDescending { it.first.length }

    /**
     * Icon URL for a domain.
     *
     * This service answers 404 for a domain it has no icon for, which is what
     * makes it the right choice here: the image load simply fails and the
     * caller's initial avatar is drawn. A service that returns a generic globe
     * placeholder instead would replace a clean, distinctive letter mark with
     * an identical grey globe on every unmatched row.
     */
    private fun iconFor(domain: String): String =
        "https://icons.duckduckgo.com/ip3/${domain.lowercase().removePrefix("www.")}.ico"

    /** Strips scheme, "www." and any path from an `assetProfile.website` value. */
    internal fun domainOf(website: String?): String? {
        val raw = website?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return raw
            .substringAfter("://")
            .substringBefore('/')
            .substringBefore('?')
            .removePrefix("www.")
            .lowercase()
            .takeIf { it.contains('.') && !it.contains(' ') }
    }

    /** The issuer domain implied by a security's [name], or null. */
    internal fun issuerDomainFor(name: String?): String? {
        val n = name?.uppercase()?.takeIf { it.isNotBlank() } ?: return null
        return ISSUER_DOMAINS.firstOrNull { n.contains(it.first) }?.second
    }

    /**
     * True for symbols that have no logo by nature — an index, a future, a
     * currency pair. Asking about these only spends requests to learn nothing.
     */
    internal fun hasNoLogo(ticker: String): Boolean {
        val t = ticker.uppercase()
        return t.startsWith("^") || t.endsWith("=F") || t.endsWith("=X")
    }

    /**
     * Best-effort logo for [ticker]. [name] is the security's own name when the
     * caller already has it (search results and quotes both do) — it is what
     * makes step 2 work without a network call, so passing it is worth doing.
     *
     * Blocking; callers already run this off the main thread.
     */
    fun logoFor(ticker: String, name: String? = null): String? {
        val key = ticker.trim().uppercase()
        if (key.isEmpty()) return null
        synchronized(cache) { if (cache.containsKey(key)) return cache[key] }

        val resolved = resolve(key, name)
        synchronized(cache) { cache[key] = resolved }
        return resolved
    }

    private fun resolve(ticker: String, name: String?): String? {
        if (hasNoLogo(ticker)) return null

        // 1. Issuer, straight from the name — no request, and more reliable
        //    than a symbol lookup for funds, whose tickers are arbitrary but
        //    whose names always carry the issuer.
        issuerDomainFor(name)?.let { return iconFor(it) }

        // 2. Symbol lookup. Returns a URL without asking anyone whether it
        //    resolves: the image loader fetches it, and a miss 404s into the
        //    caller's initial avatar exactly as a null would have.
        return symbolIconFor(ticker)
    }

    /**
     * Logo URL for a ticker symbol.
     *
     * Deliberately builds a URL rather than calling an API. Nothing here costs
     * a request from the app's own budget — the image loader fetches it as part
     * of drawing the row, the CDN caches it, and a symbol with no logo simply
     * 404s and falls through to the initial avatar. That is the whole point of
     * this over the previous Finnhub + Yahoo pair: those spent a metered
     * request, and a blocking one, to answer a question about an icon.
     *
     * The symbol is passed WHOLE, exchange suffix included.
     *
     * An earlier version stripped the suffix, on the assumption the service
     * keyed on a base symbol. It does not — it keys on the full Yahoo-style
     * symbol, and the two are different rows in its index:
     *
     *     Z74.SI   -> logo          Z74   -> 404
     *     XEQT.TO  -> logo          XEQT  -> 404
     *
     * So stripping guaranteed a miss for every listing that HAS a suffix,
     * which is every non-US listing there is. US symbols carry no suffix, so
     * stripping was a no-op for them and they kept working; Canadian funds
     * resolve by issuer name above and never reach this path. That is exactly
     * the pattern that showed up on screen — US fine, Canada fine, everything
     * international on a letter avatar — and it was this one line.
     */
    private fun symbolIconFor(ticker: String): String? {
        val symbol = ticker.trim().uppercase()
            .takeIf {
                it.isNotEmpty() &&
                    it.all { c -> c.isLetterOrDigit() || c == '.' || c == '-' }
            }
            ?: return null
        return "$LOGO_CDN$symbol$LOGO_FORMAT"
    }

}
