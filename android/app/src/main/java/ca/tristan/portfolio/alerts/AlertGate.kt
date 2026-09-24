package ca.tristan.portfolio.alerts

import android.content.Context
import ca.tristan.portfolio.data.MarketCalendar
import ca.tristan.portfolio.data.db.AlertKind
import java.time.Instant
import java.time.ZoneId

/**
 * Decides which alert rules are worth evaluating on a given pass.
 *
 * Exists because the two families of rule have nothing in common about when
 * their answer can change, and gating them together gets one of them wrong
 * whichever way you choose:
 *
 *  - A PRICE rule is a function of a quote. Markets trade roughly 32 of the
 *    168 hours in a week, so evaluating one outside a session spends a request
 *    against an undocumented endpoint to re-read a number that cannot have
 *    moved.
 *  - An EX-DIVIDEND rule is a function of the CALENDAR. "Within 3 days"
 *    becomes true because today advanced, not because the ex-date changed — so
 *    it has to be evaluated on days the market never opens. Gating it on
 *    market hours meant nothing was evaluated between Friday's close and
 *    Monday's open, and an ex-date falling on the Monday was announced on the
 *    Monday: after the last chance to buy in, which is the entire thing the
 *    rule exists to prevent.
 *
 * So: price rules follow the session, ex-dividend rules follow the date.
 *
 * Mirrors `AlertGate` on iOS decision for decision.
 */
object AlertGate {

    private const val PREFS_NAME = "alert_gate"
    private const val KEY_LAST_EX_DIVIDEND_DAY = "last_ex_dividend_day"
    private const val KEY_LAST_EX_DIVIDEND_AT = "last_ex_dividend_at"

    /**
     * How long one ex-dividend check stands. Funds announce during the day, so
     * once per day meant a distribution declared after the morning's pass was
     * not seen until the next day — for a short window, after the ex-date.
     */
    private const val EX_DIVIDEND_RECHECK_MS = 3L * 60 * 60 * 1000

    /**
     * Exchange clock, matching [MarketCalendar]. The "day" an ex-dividend
     * check belongs to is a New York day, not the device's: a user in Manila
     * would otherwise get two checks on one North American date and none on
     * the next.
     */
    private val EXCHANGE_ZONE: ZoneId = ZoneId.of("America/New_York")

    /**
     * Whether a ticker trades around the clock.
     *
     * Decided from the TICKER, not from whether the user holds any crypto.
     * Alerts are keyed by ticker precisely so they can watch something that is
     * not held — a watchlist row, or a coin being waited on before buying —
     * and the holdings-based test this replaces meant an alert on BTC-USD that
     * the user had not bought yet was evaluated only during North American
     * equity hours, which is close to the least useful window crypto has.
     *
     * Suffix-matched rather than substring-matched: "-USD" appears at the end
     * of every crypto pair this provider returns, but a substring test would
     * also catch a hypothetical "USD-HEDGED" equity listing.
     */
    fun isCryptoTicker(ticker: String): Boolean {
        val symbol = ticker.trim().uppercase()
        return CRYPTO_QUOTE_SUFFIXES.any { symbol.endsWith(it) }
    }

    private val CRYPTO_QUOTE_SUFFIXES = listOf(
        "-USD", "-USDT", "-USDC", "-CAD", "-EUR", "-GBP", "-BTC", "-ETH"
    )

    /**
     * The rule kinds this pass should evaluate, given the tickers the user's
     * enabled alerts mention.
     *
     * An empty result means the pass has nothing to do and should make no
     * network calls at all.
     */
    fun kindsFor(
        context: Context,
        tickers: Collection<String>,
        nowMillis: Long = System.currentTimeMillis()
    ): Set<AlertKind> {
        val kinds = mutableSetOf<AlertKind>()

        // Price rules: only while something they watch is actually trading.
        val marketOpen = MarketCalendar.isMarketOpen(nowMillis)
        if (marketOpen || tickers.any { isCryptoTicker(it) }) {
            kinds += AlertKind.PRICE_ABOVE
            kinds += AlertKind.PRICE_BELOW
            kinds += AlertKind.DAY_MOVE_PERCENT
        }

        // Ex-dividend rules: on the first pass of each exchange day, and again
        // every few hours after it. The day matters because "within N days"
        // turns true at midnight; the re-check matters because the DATE can
        // change during the day — a fund announcing its distribution, or the
        // calendar catching up — and waiting for tomorrow can land after the
        // ex-date itself.
        if (exDividendDueToday(context, nowMillis)) {
            kinds += AlertKind.EX_DIVIDEND_WITHIN_DAYS
        }

        return kinds
    }

    /** Whether an ex-dividend check is due: none yet today, or the last one is over three hours old. */
    fun exDividendDueToday(
        context: Context,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_LAST_EX_DIVIDEND_DAY, 0) != exchangeDay(nowMillis)) return true
        val lastAt = prefs.getLong(KEY_LAST_EX_DIVIDEND_AT, 0L)
        return lastAt <= 0L || nowMillis - lastAt >= EX_DIVIDEND_RECHECK_MS
    }

    /**
     * Records that today's ex-dividend check has run.
     *
     * Called only after a pass that actually evaluated those rules, so a pass
     * that was skipped for any other reason — not Premium, no alerts, a thrown
     * fetch — does not consume the day's single check.
     */
    fun markExDividendChecked(
        context: Context,
        nowMillis: Long = System.currentTimeMillis()
    ) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_LAST_EX_DIVIDEND_DAY, exchangeDay(nowMillis))
            .putLong(KEY_LAST_EX_DIVIDEND_AT, nowMillis)
            .apply()
    }

    /** The exchange-local date as `yyyyMMdd`, which compares and stores as one int. */
    private fun exchangeDay(nowMillis: Long): Int {
        val date = Instant.ofEpochMilli(nowMillis).atZone(EXCHANGE_ZONE).toLocalDate()
        return date.year * 10_000 + date.monthValue * 100 + date.dayOfMonth
    }
}
