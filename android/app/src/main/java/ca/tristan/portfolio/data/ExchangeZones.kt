package ca.tristan.portfolio.data

import java.util.TimeZone

/**
 * The IANA time zone a listing actually trades in, derived from its ticker.
 *
 * Yahoo's chart endpoint reports `meta.exchangeTimezoneName` and that is
 * always preferred — but it is not always there. The Dashboard's price refresh
 * runs through Finnhub for US symbols and through a batched `spark` call for
 * everything else, and neither payload carries a zone at all. Without a
 * fallback, those rows printed their last-trade time on the *device's* clock,
 * which is how the FTSE's 4:30 p.m. London close ended up rendered as
 * "11:30 a.m." for a reader in Toronto: a correct instant, labelled in a way
 * that reads as though London trades through the morning.
 *
 * The suffix groups mirror [ca.tristan.portfolio.ui.format.TickerFlag] exactly,
 * so a ticker that gets a flag also gets a zone.
 */
object ExchangeZones {

    /** Default for anything with no suffix — Yahoo's unsuffixed symbols are US. */
    private const val US = "America/New_York"


    /**
     * Zones for the index symbols the app lists, which have no suffix to read.
     * Suffixed indices (FTSEMIB.MI, 000001.SS, ^SET.BK …) resolve through the
     * suffix table below and are deliberately absent here.
     */
    private val INDEX_ZONES: Map<String, String> = mapOf(
        "^DJI"      to US,
        "^GSPC"     to US,
        "^IXIC"     to US,
        "^RUT"      to US,
        "^VIX"      to US,
        "^NYA"      to US,
        "^GSPTSE"   to "America/Toronto",
        "^MXX"      to "America/Mexico_City",
        "^BVSP"     to "America/Sao_Paulo",
        "^FTSE"     to "Europe/London",
        "^GDAXI"    to "Europe/Berlin",
        "^STOXX50E" to "Europe/Berlin",
        "^FCHI"     to "Europe/Paris",
        "^AEX"      to "Europe/Amsterdam",
        "^IBEX"     to "Europe/Madrid",
        "^SSMI"     to "Europe/Zurich",
        "^OMX"      to "Europe/Stockholm",
        "^OSEAX"    to "Europe/Oslo",
        "^OMXC25"   to "Europe/Copenhagen",
        "^N225"     to "Asia/Tokyo",
        "^HSI"      to "Asia/Hong_Kong",
        "^KS11"     to "Asia/Seoul",
        "^TWII"     to "Asia/Taipei",
        "^STI"      to "Asia/Singapore",
        "^KLSE"     to "Asia/Kuala_Lumpur",
        "^JKSE"     to "Asia/Jakarta",
        "^NSEI"     to "Asia/Kolkata",
        "^BSESN"    to "Asia/Kolkata",
        "^AXJO"     to "Australia/Sydney",
        "^AORD"     to "Australia/Sydney",
        "^NZ50"     to "Pacific/Auckland"
    )

    /**
     * Zone for [ticker], or null when the symbol says nothing about where it
     * trades (crypto, FX, an unrecognised suffix).
     *
     * Crypto and FX deliberately return null rather than a venue zone: they
     * trade continuously with no home exchange, so the viewer's own clock is
     * the honest label for them.
     */
    fun forTicker(ticker: String?): String? {
        val raw = ticker?.trim()?.uppercase() ?: return null
        if (raw.isEmpty()) return null
        if (raw.contains("-USD") || raw.contains("-CAD") || raw.contains("-EUR") ||
            raw.endsWith("=X") || raw.contains("USD=")
        ) return null

        // Index symbols carry no exchange suffix to read — "^GSPTSE" is as
        // opaque as "^GSPC" — so they are listed outright. Without this they
        // all fell through to the unsuffixed default and every foreign index
        // on the Markets tab was labelled in New York's hours.
        INDEX_ZONES[raw]?.let { return it }

        val t = raw.removePrefix("^")
        val dot = t.lastIndexOf('.')
        if (dot < 0) return US                       // AAPL, ES=F → US venues
        return when (t.substring(dot)) {
            ".TO", ".V", ".NE", ".CN", ".TSX" -> "America/Toronto"
            ".MX"                             -> "America/Mexico_City"
            ".SA"                             -> "America/Sao_Paulo"
            ".L"                              -> "Europe/London"
            ".IR"                             -> "Europe/Dublin"
            ".DE", ".F", ".BE", ".SG", ".HM",
            ".DU", ".MU", ".HA"               -> "Europe/Berlin"
            ".PA"                             -> "Europe/Paris"
            ".AS"                             -> "Europe/Amsterdam"
            ".BR"                             -> "Europe/Brussels"
            ".LS"                             -> "Europe/Lisbon"
            ".MC"                             -> "Europe/Madrid"
            ".MI"                             -> "Europe/Rome"
            ".SW", ".VX"                      -> "Europe/Zurich"
            ".VI"                             -> "Europe/Vienna"
            ".ST"                             -> "Europe/Stockholm"
            ".OL"                             -> "Europe/Oslo"
            ".CO"                             -> "Europe/Copenhagen"
            ".HE"                             -> "Europe/Helsinki"
            ".IC"                             -> "Atlantic/Reykjavik"
            ".WA"                             -> "Europe/Warsaw"
            ".IS"                             -> "Europe/Istanbul"
            ".TA"                             -> "Asia/Jerusalem"
            ".JO"                             -> "Africa/Johannesburg"
            ".T", ".JP"                       -> "Asia/Tokyo"
            ".HK"                             -> "Asia/Hong_Kong"
            ".SS", ".SZ"                      -> "Asia/Shanghai"
            ".KS", ".KQ"                      -> "Asia/Seoul"
            ".TW", ".TWO"                     -> "Asia/Taipei"
            ".SI"                             -> "Asia/Singapore"
            ".KL"                             -> "Asia/Kuala_Lumpur"
            ".JK"                             -> "Asia/Jakarta"
            ".BK"                             -> "Asia/Bangkok"
            ".PS"                             -> "Asia/Manila"
            ".NS", ".BO"                      -> "Asia/Kolkata"
            ".AX"                             -> "Australia/Sydney"
            ".NZ"                             -> "Pacific/Auckland"
            else                              -> null
        }
    }

    /**
     * The zone to render a quote's timestamp in: what the provider reported,
     * then what the ticker implies, then null for "use the viewer's clock".
     *
     * A provider zone is validated against the JVM's own database rather than
     * trusted — an id this device doesn't know would silently resolve to GMT
     * and print an hour that is simply wrong.
     */
    fun resolve(providerZone: String?, ticker: String?): String? {
        val reported = providerZone?.trim()?.takeIf { it.isNotEmpty() }
        if (reported != null && TimeZone.getAvailableIDs().contains(reported)) return reported
        return forTicker(ticker)
    }
}
