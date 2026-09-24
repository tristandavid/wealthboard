package ca.tristan.portfolio.ui.format

import ca.tristan.portfolio.data.ExchangeZones
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * "As of" labels for a quote's last-trade timestamp.
 *
 * A price is struck on the exchange's clock, not the reader's. Rendering that
 * instant in the device's zone is arithmetically correct and reads as nonsense:
 * the FTSE's 4:30 p.m. London close came out as "11:30 a.m." in Toronto, the
 * Nikkei's 3:30 p.m. close as "2:30 a.m.", and a user reasonably asks why the
 * London market seems to stop trading before lunch. Every finance app labels a
 * quote in exchange-local time for this reason.
 *
 * So the label is built in the listing's own zone and *names* that zone, which
 * is also what makes it honest: "4:30 p.m. BST" is unambiguous in a way that
 * either bare time is not. When the timestamp isn't from today in that zone —
 * a market that closed before the reader's day began, or a stale weekend
 * quote — the date is shown too, so a Friday close can't masquerade as a live
 * Sunday price.
 */
object TradeTime {

    /** SimpleDateFormat is not thread-safe; one instance per zone, reused. */
    private val timeFormats = HashMap<String, SimpleDateFormat>()
    private val dateTimeFormats = HashMap<String, SimpleDateFormat>()

    private fun zoneOf(id: String?): TimeZone =
        if (id.isNullOrBlank()) TimeZone.getDefault() else TimeZone.getTimeZone(id)

    private fun timeFormat(zone: TimeZone): SimpleDateFormat =
        timeFormats.getOrPut(zone.id) {
            SimpleDateFormat("h:mm a", Locale.getDefault()).apply { timeZone = zone }
        }

    private fun dateTimeFormat(zone: TimeZone): SimpleDateFormat =
        dateTimeFormats.getOrPut(zone.id) {
            SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).apply { timeZone = zone }
        }

    /**
     * Short name for [zone] at [atMillis] — "EDT", "BST", "JST", or a
     * "GMT+05:30" style offset for zones the JVM has no abbreviation for.
     *
     * Whether the instant fell in daylight time matters: a July close in
     * London is BST and a January one GMT, and printing the wrong one of those
     * against a correct time is a subtler error than printing no zone at all.
     */
    fun zoneLabel(zoneId: String?, atMillis: Long): String {
        val zone = zoneOf(zoneId)
        val inDst = zone.inDaylightTime(Date(atMillis))
        val name = zone.getDisplayName(inDst, TimeZone.SHORT, Locale.US)
        // getDisplayName falls back to "GMT+09:00" for zones with no
        // abbreviation, which is already a fine label. Anything else long or
        // wordy ("Japan Standard Time") is not, so it is trimmed to initials.
        return when {
            name.startsWith("GMT") -> name
            name.length <= 5       -> name
            else -> name.split(' ').mapNotNull { it.firstOrNull()?.uppercaseChar() }
                .joinToString("").ifEmpty { name }
        }
    }

    private fun isSameDay(atMillis: Long, nowMillis: Long, zone: TimeZone): Boolean {
        val a = Calendar.getInstance(zone).apply { timeInMillis = atMillis }
        val b = Calendar.getInstance(zone).apply { timeInMillis = nowMillis }
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
            a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    }

    /**
     * The row label for a quote struck at [atMillis].
     *
     * [providerZone] is `meta.exchangeTimezoneName` when the provider sent one;
     * [ticker] is used to work out the venue when it didn't (the batched spark
     * call and Finnhub both omit it). Falls back to the device's own clock only
     * when neither says anything — crypto and FX, which have no home exchange.
     *
     * @param showZone false for tight layouts (a secondary extended-hours line)
     *                 where the zone is already established by the row above.
     */
    fun label(
        atMillis: Long,
        providerZone: String? = null,
        ticker: String? = null,
        nowMillis: Long = System.currentTimeMillis(),
        showZone: Boolean = true
    ): String {
        val zoneId = ExchangeZones.resolve(providerZone, ticker)
        val zone = zoneOf(zoneId)
        val sameDay = isSameDay(atMillis, nowMillis, zone)
        val base = if (sameDay) timeFormat(zone).format(Date(atMillis))
                   else dateTimeFormat(zone).format(Date(atMillis))
        // No zone id resolved means the device's own clock is what was used,
        // and naming it would be worse than saying nothing: it would suggest
        // the exchange trades on the reader's hours.
        if (!showZone || zoneId == null) return base
        return "$base ${zoneLabel(zoneId, atMillis)}"
    }
}
