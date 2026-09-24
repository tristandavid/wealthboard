package ca.tristan.portfolio.ui.format

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * One money formatter for the whole app.
 *
 * Every screen used to build its own [java.text.NumberFormat], and they did not
 * agree: the Portfolio and Account screens pinned Locale.CANADA, the Dividends
 * screen picked a locale per currency, the Holding screen used the device
 * locale, and the Reports screen printed "CAD 1,234.56". The visible symptom
 * was a Canadian holding rendering as "$112,112.50" while a US one rendered as
 * "US$326.57" — the same glyph meaning two different currencies on two screens,
 * with nothing to tell them apart.
 *
 * The rule here is that the symbol always names the currency unambiguously:
 * CAD is CA$, USD is US$, AUD is A$. A bare "$" is never printed, because a
 * portfolio that holds both has no way to read it.
 */
object Money {

    /**
     * Unambiguous symbol for an ISO 4217 code.
     *
     * Dollar-denominated currencies keep their country prefix even when the
     * device is in that country — the point is to distinguish them from each
     * other, not to match local typing convention.
     */
    fun symbol(code: String?): String = when (code?.uppercase()?.trim()) {
        null, "" -> "$"
        "CAD" -> "CA$"
        "USD" -> "US$"
        "AUD" -> "A$"
        "NZD" -> "NZ$"
        "HKD" -> "HK$"
        "SGD" -> "S$"
        "TWD" -> "NT$"
        "MXN" -> "MX$"
        "BRL" -> "R$"
        "EUR" -> "€"
        "GBP" -> "£"
        "JPY" -> "¥"
        "CNY", "CNH" -> "CN¥"
        "INR" -> "₹"
        "KRW" -> "₩"
        "CHF" -> "CHF "
        "SEK" -> "SEK "
        "NOK" -> "NOK "
        "DKK" -> "DKK "
        "ZAR" -> "R"
        else -> "${code.uppercase().trim()} "
    }

    /** Currencies quoted without decimal places. */
    private fun defaultDecimals(code: String?): Int =
        when (code?.uppercase()?.trim()) { "JPY", "KRW" -> 0; else -> 2 }

    private fun grouping(decimals: Int): DecimalFormat {
        val pattern = if (decimals <= 0) "#,##0" else "#,##0." + "0".repeat(decimals)
        // Symbols pinned to US so the decimal mark and group separator never
        // change under the device locale: a price list where some rows read
        // "1,234.56" and others "1.234,56" is unreadable, and these strings sit
        // beside tickers and ratios that are always formatted this way.
        return DecimalFormat(pattern, DecimalFormatSymbols(Locale.US))
    }

    /**
     * "CA$1,234.56", or "-CA$12.50" for a negative.
     *
     * The minus sign leads the symbol rather than sitting between symbol and
     * digits, which is how every finance app prints a loss.
     */
    fun format(amount: Double, code: String?, decimals: Int = defaultDecimals(code)): String {
        val sign = if (amount < 0) "-" else ""
        return sign + symbol(code) + grouping(decimals).format(Math.abs(amount))
    }

    /** Same, but always carrying an explicit + or - — for changes and returns. */
    fun signed(amount: Double, code: String?, decimals: Int = defaultDecimals(code)): String {
        val sign = if (amount > 0) "+" else if (amount < 0) "-" else ""
        return sign + symbol(code) + grouping(decimals).format(Math.abs(amount))
    }

    /**
     * Per-unit distributions, which are small enough that two decimals rounds
     * a real payment to "CA$0.00". Trailing zeros are trimmed so a clean
     * quarterly amount doesn't read as false precision.
     */
    fun perUnit(amount: Double, code: String?): String {
        val v = Math.abs(amount)
        // Four decimals for anything under ten, which is every realistic
        // per-unit distribution. Apple pays 0.2702 a share and two decimals
        // rounds that to 0.27 — a 0.07 % error on a figure the whole dividend
        // section is derived from.
        val digits = if (v < 10.0) 4 else 2
        var body = grouping(digits).format(v)
        // Trailing zeros come off, but never past two decimals: "0.3600" reads
        // better as "0.36", and "1.0000" has to stay "1.00", not "1".
        if (body.contains('.')) {
            val parts = body.split('.')
            val frac = parts[1].trimEnd('0').padEnd(2, '0')
            body = parts[0] + "." + frac
        }
        return (if (amount < 0) "-" else "") + symbol(code) + body
    }

    /**
     * Axis- and bar-label form: "1.88k", "535", "54.5".
     *
     * Chart labels sit in a 58dp slot, so a full "CA$1,884.20" cannot fit and
     * the currency is named once in the legend instead.
     */
    fun compact(amount: Double): String {
        val v = Math.abs(amount)
        val sign = if (amount < 0) "-" else ""
        val body = when {
            v >= 1_000_000 -> trim(String.format(Locale.US, "%.2f", v / 1_000_000)) + "M"
            v >= 1_000 -> trim(String.format(Locale.US, "%.2f", v / 1_000)) + "k"
            v >= 100 -> String.format(Locale.US, "%.0f", v)
            v >= 10 -> trim(String.format(Locale.US, "%.1f", v))
            v >= 1 -> trim(String.format(Locale.US, "%.2f", v))
            v > 0 -> trim(String.format(Locale.US, "%.4f", v))
            else -> "0"
        }
        return sign + body
    }

    private fun trim(s: String): String =
        if (s.contains('.')) s.trimEnd('0').trimEnd('.') else s

    /** Plain number with thousands separators — for share counts and volumes. */
    fun units(value: Double): String {
        val s = grouping(4).format(value)
        return if (s.contains('.')) s.trimEnd('0').trimEnd('.') else s
    }

    /** "12.34%" — the app's single percent format. */
    fun percent(value: Double?, decimals: Int = 2): String =
        if (value == null) "—" else String.format(Locale.US, "%.${decimals}f%%", value)

    /** "+12.34%" / "-1.10%". */
    fun signedPercent(value: Double?, decimals: Int = 2): String =
        if (value == null) "—"
        else String.format(Locale.US, "%s%.${decimals}f%%", if (value >= 0) "+" else "", value)
}
