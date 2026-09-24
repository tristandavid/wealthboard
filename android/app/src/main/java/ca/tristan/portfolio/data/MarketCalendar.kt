package ca.tristan.portfolio.data

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * One exchange closure on a given date.
 *
 * [countryCode] is what callers filter on — matching on [market] text was fine
 * when there were two markets whose labels happened to contain "US" and
 * "Canadian", but it does not survive a third.
 */
data class MarketClosure(
    val market: String,
    val holidayName: String,
    val countryCode: String
)

/**
 * A locally-computed market holiday calendar for the Dashboard's closures
 * banner — NOT fetched live (there's no free, reliable "is the market open"
 * API this app calls), so it's a best-effort calendar of the well-known,
 * uncontroversial holidays each exchange observes, computed algorithmically
 * (nth-weekday-of-month rules, and the Anonymous Gregorian algorithm for
 * Easter/Good Friday) so it stays correct for any year rather than being a
 * list of hardcoded dates that goes stale. Half-day early closes are not
 * modeled, only full-day closures.
 */
object MarketCalendar {

    /**
     * Exchange timezone for both markets this app follows. The TSX keeps the
     * same hours as the US exchanges, so one clock covers both.
     */
    private val EXCHANGE_ZONE: ZoneId = ZoneId.of("America/New_York")

    private val OPEN_TIME: LocalTime = LocalTime.of(9, 30)
    private val CLOSE_TIME: LocalTime = LocalTime.of(16, 0)

    /**
     * Whether the North American exchanges are trading right now.
     *
     * Weekends and full-day holidays are excluded; half-day early closes are
     * not modelled, so the last couple of hours of a half-day will read as
     * open. That errs toward fetching slightly too often rather than missing a
     * real session, which is the right way round to be wrong.
     */
    fun isMarketOpen(nowMs: Long = System.currentTimeMillis()): Boolean {
        val local = Instant.ofEpochMilli(nowMs).atZone(EXCHANGE_ZONE)
        val date = local.toLocalDate()

        if (date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY) return false
        // North America only, deliberately. This gates the background quote
        // refresh, which runs on New York hours — a London or Manila holiday
        // must not stop it, and once the calendar covers those markets a plain
        // closuresFor() check would do exactly that.
        //
        // Closed only when BOTH are. This used to treat either market's
        // holiday as a full closure, so on US Thanksgiving — TSX trading
        // normally — no Canadian holding was re-priced and no price alert on
        // one was evaluated, and every Canadian holiday did the same to US
        // listings.
        if (bothNorthAmericanMarketsClosed(date)) return false

        val time = local.toLocalTime()
        return !time.isBefore(OPEN_TIME) && time.isBefore(CLOSE_TIME)
    }

    /**
     * How long until the next session opens, for logging and for deciding
     * whether a background pass is worth running at all.
     */
    fun millisUntilNextOpen(nowMs: Long = System.currentTimeMillis()): Long {
        if (isMarketOpen(nowMs)) return 0L
        val local = Instant.ofEpochMilli(nowMs).atZone(EXCHANGE_ZONE)
        var candidate = local.toLocalDate()
        // Same day still counts when the bell hasn't rung yet.
        if (!local.toLocalTime().isBefore(OPEN_TIME)) candidate = candidate.plusDays(1)
        repeat(10) {
            val isWeekend = candidate.dayOfWeek == DayOfWeek.SATURDAY ||
                candidate.dayOfWeek == DayOfWeek.SUNDAY
            if (!isWeekend && !bothNorthAmericanMarketsClosed(candidate)) {
                val open = candidate.atTime(OPEN_TIME).atZone(EXCHANGE_ZONE).toInstant().toEpochMilli()
                if (open > nowMs) return open - nowMs
            }
            candidate = candidate.plusDays(1)
        }
        return 0L
    }

    /**
     * Every market this calendar knows that is closed on [date].
     *
     * Callers almost always want [closuresFor] with a country code instead —
     * an unfiltered list mixes markets the reader has no interest in.
     */
    fun closuresFor(date: LocalDate): List<MarketClosure> =
        MARKETS.mapNotNull { (code, market) ->
            market.holidays(date.year)[date]?.let { MarketClosure(market.label, it, code) }
        }

    /**
     * Closures on [date] for the markets a reader in [countryCode] cares about:
     * their own, plus the US.
     *
     * The US is included for everyone because it sets the tone for every other
     * market and nearly every portfolio holds something priced there. The
     * reverse is not true, which is why a US reader gets only the US — showing
     * a Frankfurt closure to someone who holds none of it is noise.
     *
     * An unknown or blank country falls back to the US alone rather than to
     * everything. The previous behaviour — "not US and not CA, so show both" —
     * meant a device set to PH saw exactly the two markets it was least likely
     * to care about.
     */
    fun closuresFor(date: LocalDate, countryCode: String?): List<MarketClosure> {
        val home = countryCode?.uppercase()?.takeIf { MARKETS.containsKey(it) }
        val wanted = if (home == null || home == "US") listOf("US") else listOf(home, "US")
        return wanted.mapNotNull { code ->
            MARKETS[code]?.let { m -> m.holidays(date.year)[date]?.let { MarketClosure(m.label, it, code) } }
        }
    }

    /** Whether this calendar has holiday data for [countryCode] at all. */
    fun covers(countryCode: String?): Boolean =
        countryCode?.uppercase()?.let { MARKETS.containsKey(it) } == true

    private fun bothNorthAmericanMarketsClosed(date: LocalDate): Boolean =
        northAmericanClosures(date).map { it.countryCode }.toSet().containsAll(listOf("US", "CA"))

    private fun northAmericanClosures(date: LocalDate): List<MarketClosure> =
        listOf("US", "CA").mapNotNull { code ->
            MARKETS[code]?.let { m -> m.holidays(date.year)[date]?.let { MarketClosure(m.label, it, code) } }
        }

    /** Next upcoming closure strictly after [date], searching up to ~14 months ahead. */
    fun nextClosureAfter(date: LocalDate, countryCode: String? = null): Pair<LocalDate, MarketClosure>? {
        var d = date.plusDays(1)
        val limit = date.plusMonths(14)
        while (d.isBefore(limit)) {
            val hits = closuresFor(d, countryCode)
            if (hits.isNotEmpty()) return d to hits.first()
            d = d.plusDays(1)
        }
        return null
    }

    // ---- market registry ----

    private class Market(val label: String, val holidays: (Int) -> Map<LocalDate, String>)

    private val MARKETS: Map<String, Market> = linkedMapOf(
        "US" to Market("US stock markets") { usHolidays(it) },
        "CA" to Market("Canadian stock markets (TSX)") { caHolidays(it) },
        "GB" to Market("London Stock Exchange") { gbHolidays(it) },
        "DE" to Market("Frankfurt / XETRA") { deHolidays(it) },
        "AU" to Market("Australian Securities Exchange") { auHolidays(it) },
        "PH" to Market("Philippine Stock Exchange") { phHolidays(it) },
        "JP" to Market("Tokyo Stock Exchange") { jpHolidays(it) }
    )

    // ---- US (NYSE/NASDAQ) ----

    private fun usHolidays(year: Int): Map<LocalDate, String> {
        val map = LinkedHashMap<LocalDate, String>()
        map[observedWeekday(LocalDate.of(year, 1, 1))] = "New Year's Day"
        map[nthWeekday(year, 1, DayOfWeek.MONDAY, 3)] = "Martin Luther King Jr. Day"
        map[nthWeekday(year, 2, DayOfWeek.MONDAY, 3)] = "Washington's Birthday"
        map[easterSunday(year).minusDays(2)] = "Good Friday"
        map[lastWeekday(year, 5, DayOfWeek.MONDAY)] = "Memorial Day"
        map[observedWeekday(LocalDate.of(year, 6, 19))] = "Juneteenth"
        map[observedWeekday(LocalDate.of(year, 7, 4))] = "Independence Day"
        map[nthWeekday(year, 9, DayOfWeek.MONDAY, 1)] = "Labor Day"
        map[nthWeekday(year, 11, DayOfWeek.THURSDAY, 4)] = "Thanksgiving Day"
        map[observedWeekday(LocalDate.of(year, 12, 25))] = "Christmas Day"
        return map
    }

    // ---- Canada (TSX) — the well-known national/Ontario-observed ones ----

    private fun caHolidays(year: Int): Map<LocalDate, String> {
        val map = LinkedHashMap<LocalDate, String>()
        map[observedWeekday(LocalDate.of(year, 1, 1))] = "New Year's Day"
        map[nthWeekday(year, 2, DayOfWeek.MONDAY, 3)] = "Family Day"
        map[easterSunday(year).minusDays(2)] = "Good Friday"
        // Victoria Day: Monday on or before May 24
        map[LocalDate.of(year, 5, 24).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))] = "Victoria Day"
        map[observedWeekday(LocalDate.of(year, 7, 1))] = "Canada Day"
        map[nthWeekday(year, 9, DayOfWeek.MONDAY, 1)] = "Labour Day"
        map[nthWeekday(year, 10, DayOfWeek.MONDAY, 2)] = "Thanksgiving"
        map[observedWeekday(LocalDate.of(year, 12, 25))] = "Christmas Day"
        map[observedWeekday(LocalDate.of(year, 12, 26))] = "Boxing Day"
        return map
    }

    // ---- United Kingdom (LSE) ----

    private fun gbHolidays(year: Int): Map<LocalDate, String> {
        val map = LinkedHashMap<LocalDate, String>()
        map[substituteForward(LocalDate.of(year, 1, 1))] = "New Year's Day"
        val easter = easterSunday(year)
        map[easter.minusDays(2)] = "Good Friday"
        map[easter.plusDays(1)] = "Easter Monday"
        map[nthWeekday(year, 5, DayOfWeek.MONDAY, 1)] = "Early May bank holiday"
        map[lastWeekday(year, 5, DayOfWeek.MONDAY)] = "Spring bank holiday"
        map[lastWeekday(year, 8, DayOfWeek.MONDAY)] = "Summer bank holiday"
        // Christmas and Boxing Day are substituted FORWARD in the UK, never
        // back: a Saturday Christmas moves to the following Monday, pushing
        // Boxing Day to the Tuesday.
        val christmas = substituteForward(LocalDate.of(year, 12, 25))
        map[christmas] = "Christmas Day"
        var boxing = substituteForward(LocalDate.of(year, 12, 26))
        if (boxing == christmas) boxing = boxing.plusDays(1)
        map[boxing] = "Boxing Day"
        return map
    }

    // ---- Germany (Frankfurt / XETRA) ----

    private fun deHolidays(year: Int): Map<LocalDate, String> {
        val map = LinkedHashMap<LocalDate, String>()
        val easter = easterSunday(year)
        map[LocalDate.of(year, 1, 1)] = "New Year's Day"
        map[easter.minusDays(2)] = "Good Friday"
        map[easter.plusDays(1)] = "Easter Monday"
        map[LocalDate.of(year, 5, 1)] = "Labour Day"
        map[easter.plusDays(50)] = "Whit Monday"
        // XETRA closes the last two trading days of the year outright; these
        // are full closures, not the half-days noted elsewhere in this file.
        map[LocalDate.of(year, 12, 24)] = "Christmas Eve"
        map[LocalDate.of(year, 12, 25)] = "Christmas Day"
        map[LocalDate.of(year, 12, 26)] = "St. Stephen's Day"
        map[LocalDate.of(year, 12, 31)] = "New Year's Eve"
        return map
    }

    // ---- Australia (ASX) ----

    private fun auHolidays(year: Int): Map<LocalDate, String> {
        val map = LinkedHashMap<LocalDate, String>()
        val easter = easterSunday(year)
        map[substituteForward(LocalDate.of(year, 1, 1))] = "New Year's Day"
        map[substituteForward(LocalDate.of(year, 1, 26))] = "Australia Day"
        map[easter.minusDays(2)] = "Good Friday"
        map[easter.plusDays(1)] = "Easter Monday"
        map[LocalDate.of(year, 4, 25)] = "ANZAC Day"
        map[nthWeekday(year, 6, DayOfWeek.MONDAY, 2)] = "King's Birthday"
        val christmas = substituteForward(LocalDate.of(year, 12, 25))
        map[christmas] = "Christmas Day"
        var boxing = substituteForward(LocalDate.of(year, 12, 26))
        if (boxing == christmas) boxing = boxing.plusDays(1)
        map[boxing] = "Boxing Day"
        return map
    }

    // ---- Philippines (PSE) ----

    /**
     * The PSE's regular, date-certain closures.
     *
     * Deliberately omits the two Eid holidays (Eid'l Fitr and Eid'l Adha),
     * which follow the Islamic lunar calendar and are proclaimed each year
     * rather than falling on a fixed date — and the "special non-working days"
     * the President declares annually, which are not predictable at all. Those
     * days will read as open. Erring toward open is the right way round: the
     * banner stays silent rather than announcing a closure that isn't real.
     */
    private fun phHolidays(year: Int): Map<LocalDate, String> {
        val map = LinkedHashMap<LocalDate, String>()
        val easter = easterSunday(year)
        map[LocalDate.of(year, 1, 1)] = "New Year's Day"
        map[easter.minusDays(3)] = "Maundy Thursday"
        map[easter.minusDays(2)] = "Good Friday"
        map[LocalDate.of(year, 4, 9)] = "Araw ng Kagitingan"
        map[LocalDate.of(year, 5, 1)] = "Labor Day"
        map[LocalDate.of(year, 6, 12)] = "Independence Day"
        map[lastWeekday(year, 8, DayOfWeek.MONDAY)] = "National Heroes Day"
        map[LocalDate.of(year, 11, 1)] = "All Saints' Day"
        map[LocalDate.of(year, 11, 30)] = "Bonifacio Day"
        map[LocalDate.of(year, 12, 25)] = "Christmas Day"
        map[LocalDate.of(year, 12, 30)] = "Rizal Day"
        map[LocalDate.of(year, 12, 31)] = "Last day of the year"
        return map
    }

    // ---- Japan (TSE) ----

    /**
     * Japan's fixed and nth-weekday national holidays.
     *
     * The two equinox holidays (Vernal, around 20 March; Autumnal, around 23
     * September) are set astronomically and formally announced only the
     * February before, so they are approximated here from the standard
     * 20th/21st and 22nd/23rd rule. A year where the announcement differs will
     * be one day out on those two dates alone.
     *
     * Japan's "substitute holiday" rule (a Sunday holiday moves to the
     * following Monday) is applied; the New Year bank holidays are fixed.
     */
    private fun jpHolidays(year: Int): Map<LocalDate, String> {
        val map = LinkedHashMap<LocalDate, String>()
        fun put(date: LocalDate, name: String) {
            val d = if (date.dayOfWeek == DayOfWeek.SUNDAY) date.plusDays(1) else date
            map[d] = name
        }
        map[LocalDate.of(year, 1, 1)] = "New Year's Day"
        map[LocalDate.of(year, 1, 2)] = "New Year holiday"
        map[LocalDate.of(year, 1, 3)] = "New Year holiday"
        map[nthWeekday(year, 1, DayOfWeek.MONDAY, 2)] = "Coming of Age Day"
        put(LocalDate.of(year, 2, 11), "National Foundation Day")
        put(LocalDate.of(year, 2, 23), "Emperor's Birthday")
        put(LocalDate.of(year, 3, if (year % 4 == 0) 20 else 21), "Vernal Equinox Day")
        put(LocalDate.of(year, 4, 29), "Showa Day")
        put(LocalDate.of(year, 5, 3), "Constitution Memorial Day")
        put(LocalDate.of(year, 5, 4), "Greenery Day")
        put(LocalDate.of(year, 5, 5), "Children's Day")
        map[nthWeekday(year, 7, DayOfWeek.MONDAY, 3)] = "Marine Day"
        put(LocalDate.of(year, 8, 11), "Mountain Day")
        map[nthWeekday(year, 9, DayOfWeek.MONDAY, 3)] = "Respect for the Aged Day"
        put(LocalDate.of(year, 9, if (year % 4 == 0) 22 else 23), "Autumnal Equinox Day")
        map[nthWeekday(year, 10, DayOfWeek.MONDAY, 2)] = "Sports Day"
        put(LocalDate.of(year, 11, 3), "Culture Day")
        put(LocalDate.of(year, 11, 23), "Labour Thanksgiving Day")
        map[LocalDate.of(year, 12, 31)] = "Year-end holiday"
        return map
    }

    // ---- date helpers ----

    private fun nthWeekday(year: Int, month: Int, weekday: DayOfWeek, n: Int): LocalDate =
        LocalDate.of(year, month, 1).with(TemporalAdjusters.dayOfWeekInMonth(n, weekday))

    private fun lastWeekday(year: Int, month: Int, weekday: DayOfWeek): LocalDate =
        LocalDate.of(year, month, 1).with(TemporalAdjusters.lastInMonth(weekday))

    // A fixed date observed on the nearest weekday when it falls on a
    // weekend (Saturday -> observed Friday before; Sunday -> observed Monday after).
    // Some markets never move a holiday backwards: a weekend date is always
    // observed on the following Monday (UK, Australia), unlike the US rule
    // above where a Saturday moves to the Friday before.
    private fun substituteForward(date: LocalDate): LocalDate = when (date.dayOfWeek) {
        DayOfWeek.SATURDAY -> date.plusDays(2)
        DayOfWeek.SUNDAY -> date.plusDays(1)
        else -> date
    }

    private fun observedWeekday(date: LocalDate): LocalDate = when (date.dayOfWeek) {
        DayOfWeek.SATURDAY -> date.minusDays(1)
        DayOfWeek.SUNDAY -> date.plusDays(1)
        else -> date
    }

    // Anonymous Gregorian algorithm (Meeus/Jones/Butcher) for the date of Easter Sunday.
    private fun easterSunday(year: Int): LocalDate {
        val a = year % 19
        val b = year / 100
        val c = year % 100
        val d = b / 4
        val e = b % 4
        val f = (b + 8) / 25
        val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4
        val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31
        val day = ((h + l - 7 * m + 114) % 31) + 1
        return LocalDate.of(year, month, day)
    }
}
