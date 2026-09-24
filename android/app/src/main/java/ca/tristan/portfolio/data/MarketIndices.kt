package ca.tristan.portfolio.data

import java.util.Locale

/**
 * A stock-market index the Markets tab can show as a header row.
 *
 * Yahoo returns indices under opaque caret symbols ("^GSPTSE"), which tells a
 * user nothing — [displayName] is what actually gets rendered, with [flag]
 * beside it so the market is obvious at a glance.
 */
data class MarketIndex(
    val symbol: String,        // Yahoo ticker, e.g. "^GSPC"
    val displayName: String,   // "S&P 500"
    val flag: String,          // "🇺🇸"
    val countryCode: String    // ISO-3166 alpha-2, "US"
)

/**
 * Registry of the market indices the app knows how to label, plus the logic
 * for choosing which ones to show for a given user.
 *
 * Ordering on the Markets tab is always: the three US benchmarks first, then
 * the user's home market (when it isn't the US), then the other majors.
 */
object MarketIndices {

    /** The three US benchmarks, always pinned to the top of the Markets tab. */
    val US_INDICES = listOf(
        MarketIndex("^DJI",  "Dow Jones",       "🇺🇸", "US"),
        MarketIndex("^GSPC", "S&P 500",         "🇺🇸", "US"),
        MarketIndex("^IXIC", "NASDAQ Composite", "🇺🇸", "US")
    )

    /** Primary index for each country the app can localise to. */
    private val BY_COUNTRY: Map<String, MarketIndex> = listOf(
        MarketIndex("^GSPTSE",     "S&P/TSX Composite", "🇨🇦", "CA"),
        MarketIndex("^FTSE",       "FTSE 100",          "🇬🇧", "GB"),
        MarketIndex("^GDAXI",      "DAX",               "🇩🇪", "DE"),
        MarketIndex("^FCHI",       "CAC 40",            "🇫🇷", "FR"),
        MarketIndex("^IBEX",       "IBEX 35",           "🇪🇸", "ES"),
        MarketIndex("FTSEMIB.MI",  "FTSE MIB",          "🇮🇹", "IT"),
        MarketIndex("^AEX",        "AEX",               "🇳🇱", "NL"),
        MarketIndex("^SSMI",       "SMI",               "🇨🇭", "CH"),
        MarketIndex("^N225",       "Nikkei 225",        "🇯🇵", "JP"),
        MarketIndex("^HSI",        "Hang Seng",         "🇭🇰", "HK"),
        MarketIndex("000001.SS",   "SSE Composite",     "🇨🇳", "CN"),
        MarketIndex("^AXJO",       "S&P/ASX 200",       "🇦🇺", "AU"),
        MarketIndex("^NSEI",       "NIFTY 50",          "🇮🇳", "IN"),
        MarketIndex("^KS11",       "KOSPI",             "🇰🇷", "KR"),
        MarketIndex("^TWII",       "TAIEX",             "🇹🇼", "TW"),
        MarketIndex("^STI",        "Straits Times",     "🇸🇬", "SG"),
        MarketIndex("^BVSP",       "Bovespa",           "🇧🇷", "BR"),
        MarketIndex("^MXX",        "IPC Mexico",        "🇲🇽", "MX"),
        MarketIndex("^JKSE",       "IDX Composite",     "🇮🇩", "ID"),
        MarketIndex("^KLSE",       "FTSE Malaysia",     "🇲🇾", "MY"),
        MarketIndex("PSEI.PS",     "PSEi",              "🇵🇭", "PH"),
        MarketIndex("^SET.BK",     "SET Index",         "🇹🇭", "TH"),
        MarketIndex("^TA125.TA",   "TA-125",            "🇮🇱", "IL"),
        MarketIndex("^J203.JO",    "JSE All Share",     "🇿🇦", "ZA"),
        MarketIndex("^OMX",        "OMX Stockholm 30",  "🇸🇪", "SE"),
        MarketIndex("^OSEAX",      "Oslo All Share",    "🇳🇴", "NO")
    ).associateBy { it.countryCode }

    /** Majors shown after the US block and the home market. */
    private val MAJOR_ORDER = listOf("GB", "DE", "JP", "HK", "AU")

    /** Every index the app can label, keyed by Yahoo symbol. */
    val BY_SYMBOL: Map<String, MarketIndex> =
        (US_INDICES + BY_COUNTRY.values).associateBy { it.symbol }

    /** True when [symbol] is one of the market indices this screen pins on top. */
    fun isIndex(symbol: String): Boolean = BY_SYMBOL.containsKey(symbol)

    /** Friendly name for [symbol], or the raw symbol when it isn't a known index. */
    fun displayName(symbol: String): String = BY_SYMBOL[symbol]?.displayName ?: symbol

    /** Flag for [symbol], or empty when it isn't a known index. */
    fun flag(symbol: String): String = BY_SYMBOL[symbol]?.flag ?: ""

    /** The index for an ISO country code, if the app knows one. */
    fun forCountry(countryCode: String?): MarketIndex? =
        countryCode?.uppercase()?.let { BY_COUNTRY[it] }

    /**
     * The device's region, used to pick a home market when the user hasn't
     * chosen one in Settings. Returns an ISO alpha-2 code, defaulting to "US".
     */
    fun deviceCountry(): String =
        Locale.getDefault().country.takeIf { it.isNotBlank() } ?: "US"

    /**
     * Builds the ordered index list for the Markets tab:
     * the three US benchmarks, then the home market (if not US), then majors.
     *
     * @param homeCountry ISO code chosen in Settings, or null to auto-detect.
     */
    fun indexesFor(homeCountry: String?): List<MarketIndex> {
        val home = (homeCountry ?: deviceCountry()).uppercase()
        val result = US_INDICES.toMutableList()

        if (home != "US") forCountry(home)?.let(result::add)

        for (code in MAJOR_ORDER) {
            if (code == home) continue
            BY_COUNTRY[code]?.let { if (it !in result) result.add(it) }
        }
        return result
    }

    /** Symbols for [indexesFor], for seeding the watchlist / batch fetches. */
    fun symbolsFor(homeCountry: String?): List<String> =
        indexesFor(homeCountry).map { it.symbol }

    /** Country codes the Settings picker offers, with the label to show. */
    fun selectableCountries(): List<Pair<String, String>> =
        (listOf("US" to "United States 🇺🇸") +
            BY_COUNTRY.values.map { it.countryCode to "${countryName(it.countryCode)} ${it.flag}" })
            .distinctBy { it.first }
            .sortedBy { it.second }

    private fun countryName(code: String): String =
        Locale("", code).getDisplayCountry(Locale.getDefault()).ifBlank { code }
}
