package ca.tristan.portfolio.alerts

import ca.tristan.portfolio.data.PortfolioRepository
import ca.tristan.portfolio.data.db.AlertDao
import ca.tristan.portfolio.data.db.AlertEntity
import ca.tristan.portfolio.data.db.AlertKind
import ca.tristan.portfolio.net.YahooQuoteClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What one alert firing has to say.
 *
 * Carries the finished strings rather than the raw numbers, so the notifier is
 * a dumb pipe and the wording lives next to the rule that produced it.
 */
data class AlertFiring(
    val alertId: Long,
    val ticker: String,
    val title: String,
    val body: String,
    val value: Double
)

/**
 * Decides which alerts should fire right now.
 *
 * Deliberately has no Android dependencies beyond the data layer: evaluation
 * is the part worth being able to reason about (and, later, test) on its own,
 * and it is the part where a mistake is expensive — an alert that fires wrongly
 * teaches people to ignore alerts, and one that silently never fires is worse,
 * because nothing about the UI looks broken.
 */
object AlertEngine {

    /**
     * Per-ticker price data, however it was obtained.
     *
     * A small shim rather than passing [YahooQuoteClient.SparkQuote] around, so
     * the evaluator can be handed figures from anywhere — a batch response, a
     * cached row, or a test.
     */
    data class Priced(val price: Double, val previousClose: Double?)

    /**
     * Evaluates every enabled alert and returns the ones that just became true.
     *
     * Latching: an alert that is already `triggeredAtMillis != null` does not
     * fire again. It is cleared here, silently, the moment its condition goes
     * false — which is what re-arms it for the next genuine crossing. That
     * clearing is the whole reason this returns firings rather than just
     * writing notifications: the caller has to persist both outcomes.
     *
     * [kinds] limits the pass to the rule families worth evaluating right now
     * — see [AlertGate], which decides it. A kind left out is not evaluated at
     * all, which means it is neither fired NOR re-armed: skipping is silence
     * about a rule, never a verdict on it. Defaults to every kind, so a caller
     * that has no opinion (the developer row, a test) behaves as before.
     */
    suspend fun evaluate(
        dao: AlertDao,
        repository: PortfolioRepository,
        nowMillis: Long = System.currentTimeMillis(),
        kinds: Set<AlertKind> = ALL_KINDS
    ): List<AlertFiring> = withContext(Dispatchers.IO) {
        val alerts = runCatching { dao.enabled() }.getOrNull().orEmpty()
            .filter { it.kind in kinds }
        if (alerts.isEmpty()) return@withContext emptyList()

        val firings = mutableListOf<AlertFiring>()

        // ── Price-driven rules ────────────────────────────────────────────
        //
        // One batched request for every ticker any price rule mentions,
        // rather than a call per alert: someone watching five thresholds on
        // the same security should cost one request, not five.
        val priceKinds = setOf(
            AlertKind.PRICE_ABOVE, AlertKind.PRICE_BELOW, AlertKind.DAY_MOVE_PERCENT
        )
        val priceAlerts = alerts.filter { it.kind in priceKinds }
        val priced: Map<String, Priced> = if (priceAlerts.isEmpty()) {
            emptyMap()
        } else {
            val tickers = priceAlerts.map { it.ticker }.distinct()
            pricedFor(tickers)
        }

        for (alert in priceAlerts) {
            // A ticker the batch didn't answer for is UNKNOWN, not false. It
            // is left exactly as it was — neither fired nor re-armed — because
            // a failed fetch must never look like "the condition went away".
            val quote = priced[alert.ticker] ?: continue
            val outcome = evaluatePrice(alert, quote)
            applyOutcome(dao, alert, outcome, nowMillis, firings)
        }

        // ── Ex-dividend rules ─────────────────────────────────────────────
        //
        // One call per distinct ticker, and only for tickers that actually
        // have such an alert: this endpoint is per-symbol, so the cost is
        // bounded by how many calendar alerts the user set rather than by the
        // size of their portfolio.
        val exDivAlerts = alerts.filter { it.kind == AlertKind.EX_DIVIDEND_WITHIN_DAYS }
        if (exDivAlerts.isNotEmpty()) {
            // The SAME resolution the Dividends tab uses (payout calendar,
            // the exchange's declared feed, Yahoo), not Yahoo's quoteSummary
            // alone — which reports no announcements and, for many Canadian
            // listings, only the LAST ex-date. The alert and the card could
            // otherwise disagree about when a fund goes ex.
            val upcoming = exDivAlerts.map { it.ticker }.distinct().associateWith { ticker ->
                runCatching { repository.dividendRecordFor(ticker).first }.getOrNull()
            }
            for (alert in exDivAlerts) {
                val next = upcoming[alert.ticker] ?: continue
                val exDate = next.exDividendDateMillis ?: continue
                val outcome = evaluateExDividend(alert, exDate, nowMillis, next.isAnnounced)
                applyOutcome(dao, alert, outcome, nowMillis, firings)
            }
        }

        firings
    }

    /**
     * A plain-language account of what each enabled alert evaluates to right
     * now, for the developer row on the Menu.
     *
     * Exists because "nothing fired" is the least useful thing a test can
     * report: it covers a symbol the provider does not return, an
     * already-latched rule, a threshold that simply has not been reached, and
     * a genuine bug, and the first three are not problems at all. This prints
     * the fetched price next to the target so the difference is visible.
     *
     * Read-only — it never latches or notifies.
     */
    suspend fun explain(
        dao: AlertDao,
        repository: PortfolioRepository,
        nowMillis: Long = System.currentTimeMillis()
    ): List<String> = withContext(Dispatchers.IO) {
        val alerts = runCatching { dao.enabled() }.getOrNull().orEmpty()
        if (alerts.isEmpty()) return@withContext listOf("No enabled alerts.")

        val priceKinds = setOf(
            AlertKind.PRICE_ABOVE, AlertKind.PRICE_BELOW, AlertKind.DAY_MOVE_PERCENT
        )
        val tickers = alerts.filter { it.kind in priceKinds }.map { it.ticker }.distinct()
        val priced = if (tickers.isEmpty()) emptyMap() else pricedFor(tickers)

        alerts.map { alert ->
            val latched = alert.triggeredAtMillis != null
            when {
                alert.kind == AlertKind.EX_DIVIDEND_WITHIN_DAYS -> {
                    val exDate = runCatching {
                        repository.dividendRecordFor(alert.ticker).first?.exDividendDateMillis
                    }.getOrNull()
                    if (exDate == null) "${alert.ticker}: no ex-dividend date published"
                    else {
                        val days = calendarDaysBetween(nowMillis, exDate)
                        "${alert.ticker}: ex-dividend in $days day(s), window " +
                            "${alert.threshold.toInt()}" +
                            if (latched) " — already fired" else ""
                    }
                }

                priced[alert.ticker] == null ->
                    "${alert.ticker}: NO QUOTE returned — the symbol may be wrong, " +
                        "or the request failed"

                else -> {
                    val quote = priced[alert.ticker]!!
                    val verdict = evaluatePrice(alert, quote)
                    buildString {
                        append("${alert.ticker}: ${money(quote.price)} vs target ")
                        append(money(alert.threshold))
                        append(if (verdict.isTrue) " — CONDITION MET" else " — not met")
                        if (latched) append(" (already fired; re-arms when it clears)")
                    }
                }
            }
        }
    }

    /**
     * Prices for [tickers], from the SAME source the portfolio is priced from.
     *
     * This used to call `fetchSparkBatch` directly, which bypasses the
     * FinanceQuery -> Finnhub -> Yahoo chain that every other price in the app
     * goes through. An alert could therefore fire on a number the user could
     * not see anywhere in the app — or, worse, sit silent while the portfolio
     * showed a price past the threshold. The rule and the row now agree
     * because they read the same provider.
     *
     * Spark stays as the fallback for symbols the quote batch does not answer
     * for, so coverage is no worse than before.
     */
    private fun pricedFor(tickers: List<String>): Map<String, Priced> {
        val quotes = runCatching { YahooQuoteClient.fetchQuotes(tickers) }
            .getOrDefault(emptyMap())

        // Keyed back to the CALLER's spelling: fetchQuotes uppercases its keys,
        // and the alert's own ticker string is what every lookup downstream
        // uses. Returning the provider's spelling would leave every alert on a
        // lower-case ticker unpriced — and an unpriced ticker reads as UNKNOWN,
        // so the rule would never fire and never re-arm.
        val out = mutableMapOf<String, Priced>()
        for (ticker in tickers) {
            val quote = quotes[ticker.trim().uppercase()] ?: continue
            out[ticker] = Priced(quote.price, quote.previousClose)
        }

        val missing = tickers.filterNot { out.containsKey(it) }
        if (missing.isNotEmpty()) {
            val batch = runCatching { YahooQuoteClient.fetchSparkBatch(missing) }
                .getOrDefault(emptyMap())
            // Matched case-insensitively for the same reason as above: the
            // spark endpoint answers with symbols spelled its own way.
            for (ticker in missing) {
                val spark = batch[ticker]
                    ?: batch.entries.firstOrNull { it.key.equals(ticker, ignoreCase = true) }?.value
                    ?: continue
                out[ticker] = Priced(spark.price, spark.previousClose)
            }
        }
        return out
    }

    // ── Rules ─────────────────────────────────────────────────────────────

    /** True when the condition holds, plus the value to report. */
    private data class Outcome(val isTrue: Boolean, val value: Double, val body: String)

    private fun evaluatePrice(alert: AlertEntity, quote: Priced): Outcome =
        when (alert.kind) {
            AlertKind.PRICE_ABOVE -> Outcome(
                isTrue = quote.price >= alert.threshold,
                value = quote.price,
                body = "${alert.ticker} is at ${money(quote.price)}, at or above your " +
                    "${money(alert.threshold)} target."
            )

            AlertKind.PRICE_BELOW -> Outcome(
                isTrue = quote.price <= alert.threshold,
                value = quote.price,
                body = "${alert.ticker} is at ${money(quote.price)}, at or below your " +
                    "${money(alert.threshold)} target."
            )

            AlertKind.DAY_MOVE_PERCENT -> {
                // No previous close means no day move to speak of — not a
                // move of zero. Reporting 0% would re-arm an alert that was
                // legitimately latched, so this reads as "condition false,
                // value unknown" and the guard below keeps it from clearing.
                val prev = quote.previousClose
                if (prev == null || prev <= 0.0) {
                    Outcome(false, quote.price, "")
                } else {
                    val movePercent = (quote.price - prev) / prev * 100.0
                    val direction = if (movePercent >= 0) "up" else "down"
                    Outcome(
                        isTrue = kotlin.math.abs(movePercent) >= alert.threshold,
                        value = movePercent,
                        body = "${alert.ticker} is $direction " +
                            "${percent(kotlin.math.abs(movePercent))} today, at " +
                            "${money(quote.price)}."
                    )
                }
            }

            else -> Outcome(false, quote.price, "")
        }

    private fun evaluateExDividend(
        alert: AlertEntity,
        exDateMillis: Long,
        nowMillis: Long,
        announced: Boolean = true
    ): Outcome {
        // Counted in CALENDAR days. The old fractional count — milliseconds
        // left over 86.4 million — went negative at 00:01 on the ex-date,
        // because ex-dates are stored at local midnight, so the "goes
        // ex-dividend today" notification could never actually be sent.
        val days = calendarDaysBetween(nowMillis, exDateMillis)
        // Past dates are not "zero days away", they are gone: the window is
        // closed and re-opening it would notify someone about a date they
        // already missed, every half hour, until the provider posts the next
        // one.
        val withinWindow = days >= 0 && days <= alert.threshold
        // A projected date is a forecast, and the notification says so.
        val suffix = if (announced) "" else " (expected — not yet announced by the fund)"
        return Outcome(
            isTrue = withinWindow,
            value = days.toDouble(),
            body = when {
                days <= 0 -> "${alert.ticker} goes ex-dividend today — units bought from " +
                    "today on don't receive this payment.$suffix"
                days == 1 -> "${alert.ticker} goes ex-dividend tomorrow — buy by today's " +
                    "close to receive this payment.$suffix"
                else -> "${alert.ticker} goes ex-dividend in $days days.$suffix"
            }
        )
    }

    /** Whole local calendar days from [fromMillis]'s date to [toMillis]'s date. */
    private fun calendarDaysBetween(fromMillis: Long, toMillis: Long): Int {
        val zone = java.time.ZoneId.systemDefault()
        val from = java.time.Instant.ofEpochMilli(fromMillis).atZone(zone).toLocalDate()
        val to = java.time.Instant.ofEpochMilli(toMillis).atZone(zone).toLocalDate()
        return java.time.temporal.ChronoUnit.DAYS.between(from, to).toInt()
    }

    // ── Latching ──────────────────────────────────────────────────────────

    private suspend fun applyOutcome(
        dao: AlertDao,
        alert: AlertEntity,
        outcome: Outcome,
        nowMillis: Long,
        firings: MutableList<AlertFiring>
    ) {
        val alreadyLatched = alert.triggeredAtMillis != null

        if (outcome.isTrue) {
            if (alreadyLatched) return
            dao.markTriggered(alert.id, nowMillis, outcome.value)
            firings += AlertFiring(
                alertId = alert.id,
                ticker = alert.ticker,
                title = title(alert),
                body = outcome.body,
                value = outcome.value
            )
        } else if (alreadyLatched && outcome.body.isNotEmpty()) {
            // Re-arm. The empty-body check is what keeps an "unknown" result
            // (no previous close, no ex-date) from being mistaken for the
            // condition having genuinely gone away.
            dao.markTriggered(alert.id, null, outcome.value)
        }
    }

    private fun title(alert: AlertEntity): String = when (alert.kind) {
        AlertKind.PRICE_ABOVE -> "${alert.ticker} hit ${money(alert.threshold)}"
        AlertKind.PRICE_BELOW -> "${alert.ticker} fell to ${money(alert.threshold)}"
        AlertKind.DAY_MOVE_PERCENT -> "${alert.ticker} moved ${trimmed(alert.threshold)}%"
        AlertKind.EX_DIVIDEND_WITHIN_DAYS -> "${alert.ticker} ex-dividend coming up"
    }

    // ── Formatting ────────────────────────────────────────────────────────

    /** Every rule family — the default for a caller that wants the lot. */
    val ALL_KINDS: Set<AlertKind> = AlertKind.entries.toSet()

    private const val MILLIS_PER_DAY = 24.0 * 60.0 * 60.0 * 1000.0

    private fun money(value: Double): String =
        String.format(java.util.Locale.getDefault(), "%,.2f", value)

    private fun percent(value: Double): String =
        String.format(java.util.Locale.getDefault(), "%.2f%%", value)

    /**
     * Drops a pointless ".0" so a 5% rule reads as "moved 5%", not "5.00%".
     *
     * Used for the user's own THRESHOLD, never for a measured value: "up
     * 5.23% today" is information, "moved 5%" is the rule being quoted back.
     * iOS formats the same two cases the same way — see `PriceAlert.trimmed`.
     */
    private fun trimmed(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString()
        else String.format(java.util.Locale.getDefault(), "%.2f", value)
}
