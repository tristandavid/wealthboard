package ca.tristan.portfolio.report

import ca.tristan.portfolio.ui.securityKey
import ca.tristan.portfolio.data.db.AccountEntity
import ca.tristan.portfolio.data.db.DividendPaymentEntity
import ca.tristan.portfolio.data.db.HoldingEntity
import ca.tristan.portfolio.data.db.TransactionEntity
import ca.tristan.portfolio.data.db.TransactionType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

private const val MILLIS_PER_DAY = 86_400_000L

/** Trading days in a year — the standard annualization factor for daily returns. */
private const val TRADING_DAYS_PER_YEAR = 252.0

/**
 * Risk-free rate used for the Sharpe ratio and Jensen's alpha.
 *
 * Hard-coded rather than fetched: there is no free, dependable short-rate feed
 * the app already talks to, and a wrong-but-stated assumption is better than a
 * silently stale one. The report prints the value it used so a reader can
 * adjust. Update this when short rates move materially.
 */
const val DEFAULT_RISK_FREE_RATE = 0.042

/** Whole days since the epoch — the alignment key for every daily series. */
private fun dayIndex(millis: Long): Long = Math.floorDiv(millis, MILLIS_PER_DAY)

private fun dayStartMillis(index: Long): Long = index * MILLIS_PER_DAY

/**
 * Builds a [PerformanceReport] from the hand-entered ledger plus historical
 * prices fetched for each tickered holding.
 *
 * The portfolio is valued as market value of every position **plus** dividend
 * cash that has not been reinvested. Treating uninvested distributions as part
 * of the portfolio is what makes this a total-return measurement: without it,
 * a cash dividend would look like the portfolio shrinking, and a DRIP would
 * look like an outside deposit.
 *
 * External flows are therefore buys (money in) and sells (money out) only.
 * DRIPs are internal — they move value from the cash bucket into units and
 * must not be counted as a contribution, or reinvesting would be scored as
 * skill rather than as the income it is.
 */
object PerformanceCalculator {

    /**
     * @param priceHistory fetches daily closes: (ticker, range, interval) -> [(millis, close)].
     *        Supplied by the caller so this object stays independent of the network layer.
     */
    suspend fun build(
        options: ReportOptions,
        accounts: List<AccountEntity>,
        allHoldings: List<HoldingEntity>,
        allTransactions: List<TransactionEntity>,
        allDividends: List<DividendPaymentEntity>,
        currency: String,
        nowMillis: Long = System.currentTimeMillis(),
        /**
         * Multiplier converting an amount in the given currency into [currency].
         * Defaults to 1.0, i.e. a single-currency portfolio.
         */
        fxRate: (String) -> Double = { 1.0 },
        priceHistory: suspend (String, String, String) -> List<Pair<Long, Double>>
    ): PerformanceReport = withContext(Dispatchers.Default) {

        val warnings = mutableListOf<String>()

        // ── Scope ────────────────────────────────────────────────────────────
        // Scoped to the SECURITY, not the stored row: picking XEQT.TO means the
        // whole position in it. Splitting the same fund across a TFSA and an
        // RRSP does not make it two investments.
        val holdingsRaw = if (options.holdingId != null) {
            val picked = allHoldings.firstOrNull { it.id == options.holdingId }
            if (picked == null) allHoldings
            else allHoldings.filter { it.securityKey == picked.securityKey }
        } else allHoldings

        val holdingIds = holdingsRaw.map { it.id }.toSet()

        // ── Normalise into the report currency ───────────────────────────────
        //
        // Every total below — portfolio value, contributions, the equity curve,
        // allocation weights, Sharpe, the benchmark comparison — is a sum across
        // holdings, and they were summed at face value: a US position's dollars
        // added straight to a Canadian one's. For a portfolio holding XEQT.TO
        // and AAPL that is not a number in any currency, and every percentage
        // derived from it was wrong alongside it.
        //
        // Converting the inputs once, here, means the arithmetic downstream is
        // untouched and automatically consistent. A rate is a constant
        // multiplier on prices and cash, so returns and risk come out the same
        // as they would have per-currency.
        //
        // The caveat, stated in the report: today's rate is applied to
        // historical values too, so a period's return is measured as if FX had
        // been flat. Measuring FX drift properly needs a historical rate series
        // this app does not carry, and a flat rate is far closer to right than
        // adding unconverted currencies together.
        fun rateFor(code: String?): Double {
            val c = code?.uppercase()?.trim()?.ifBlank { null } ?: return 1.0
            if (c.equals(currency, ignoreCase = true)) return 1.0
            val r = fxRate(c)
            return if (r.isNaN() || r <= 0.0) 1.0 else r
        }

        val foreignCurrencies = holdingsRaw
            .map { it.currency.uppercase().ifBlank { "CAD" } }
            .distinct()
            .filter { !it.equals(currency, ignoreCase = true) }

        if (foreignCurrencies.isNotEmpty()) {
            warnings += "Holdings in ${foreignCurrencies.joinToString(", ")} were converted to " +
                "$currency at today's exchange rate, including for past dates — this report " +
                "measures investment return, not currency movement."
        }

        val holdings = holdingsRaw.map { h ->
            val r = rateFor(h.currency)
            if (r == 1.0) h else h.copy(
                manualPrice = h.manualPrice?.times(r),
                lastKnownPrice = h.lastKnownPrice?.times(r),
                costBasis = h.costBasis?.times(r),
                currency = currency
            )
        }

        // Per-ticker rate, so fetched price series can be converted the same way.
        val rateByTicker = holdingsRaw
            .mapNotNull { h -> h.ticker?.trim()?.uppercase()?.ifBlank { null }?.let { it to rateFor(h.currency) } }
            .toMap()

        val recordedTransactions = allTransactions
            .filter { it.holdingId in holdingIds }
            .map { tx ->
                val r = rateFor(tx.currency)
                if (r == 1.0) tx else tx.copy(pricePerShare = tx.pricePerShare * r, currency = currency)
            }
            .sortedBy { it.atMillis }

        val dividends = allDividends
            .filter { it.holdingId in holdingIds }
            .map { d ->
                val r = rateFor(d.currency)
                if (r == 1.0) d else d.copy(
                    amount = d.amount * r,
                    perUnit = d.perUnit?.times(r),
                    currency = currency
                )
            }
            .sortedBy { it.paidAtMillis }

        // Inception comes from RECORDED trades only. Positions that were typed
        // in rather than traded carry a createdAtMillis of "whenever this was
        // entered into the app", which says nothing about when it was bought —
        // flooring the window with it would hand someone who set up the app
        // this morning a one-day report.
        val inception = recordedTransactions.minByOrNull { it.atMillis }?.atMillis

        val periodStart = options.period.startMillis(nowMillis, inception)
        val startDay = dayIndex(periodStart)
        val endDay = dayIndex(nowMillis)

        if (holdingsRaw.isEmpty()) {
            return@withContext emptyReport(options, periodStart, nowMillis, currency,
                listOf("No holdings found for the selected scope."))
        }

        // ── Reconcile the ledger against the holdings table ──────────────────
        //
        // The holding row is the position of record; the transaction table is
        // an audit trail of how it got there, and it is optional. A position
        // typed straight into the Add/Edit Holding screen has units and a cost
        // basis but no transactions at all.
        //
        // Rebuilding positions purely from transactions therefore starts every
        // such holding at zero units and keeps it there, which renders the
        // whole report — value, return, risk, allocation — as zeros for anyone
        // who entered their portfolio by hand. Where the replayed ledger falls
        // short of the holding's actual units, the difference is seeded as an
        // opening balance so the report reflects what is really held.
        //
        // The opening is dated one day BEFORE the window rather than at
        // createdAtMillis. Two reasons: the real purchase date is unknown, and
        // dating it inside the window would book the whole position as a fresh
        // contribution, flattening the return to nothing and drawing a chart
        // that sits at zero and then jumps. Treating it as capital already in
        // place measures exactly what the reader wants — how the things they
        // hold have performed — at the cost of assuming the quantity was held
        // throughout, which the report states plainly.
        val syntheticOpenings = mutableListOf<TransactionEntity>()
        val reconstructedNames = mutableListOf<String>()

        holdings.forEach { h ->
            val own = recordedTransactions.filter { it.holdingId == h.id }
            val ledgerUnits = own.sumOf { tx ->
                when (tx.type) {
                    TransactionType.BUY, TransactionType.DRIP -> tx.shares
                    TransactionType.SELL -> -tx.shares
                }
            }
            val missingUnits = h.units - ledgerUnits
            if (missingUnits <= 1e-6) return@forEach

            val ledgerCost = own.sumOf { tx ->
                when (tx.type) {
                    TransactionType.BUY, TransactionType.DRIP -> tx.shares * tx.pricePerShare
                    TransactionType.SELL -> 0.0
                }
            }
            // Prefer the entered cost basis. With none on file, fall back to the
            // current price: the position then shows no unrealized gain, which
            // is honest about not knowing what was paid rather than inventing a
            // profit or a loss out of nothing.
            val currentPrice = h.lastKnownPrice ?: h.manualPrice
            val totalCost = h.costBasis ?: currentPrice?.let { it * h.units }
            val openingCost = totalCost?.let { (it - ledgerCost).coerceAtLeast(0.0) }
            val pricePerShare = openingCost
                ?.takeIf { it > 0.0 }
                ?.let { it / missingUnits }
                ?: currentPrice
                ?: 0.0

            syntheticOpenings += TransactionEntity(
                // Negative id marks this as reconstructed rather than recorded,
                // keeping it out of the Activity tallies: the user did not place
                // this trade, it is a restated opening balance.
                id = -h.id,
                holdingId = h.id,
                type = TransactionType.BUY,
                atMillis = periodStart - MILLIS_PER_DAY,
                shares = missingUnits,
                pricePerShare = pricePerShare,
                currency = h.currency
            )
            reconstructedNames += (h.ticker ?: h.name)
        }

        if (reconstructedNames.isNotEmpty()) {
            warnings += "No buy/sell history is on file for " +
                "${reconstructedNames.joinToString(", ")}, so the opening position was taken " +
                "from the units and cost basis entered for each and assumed held at that " +
                "quantity for the whole period. Record transactions for these holdings to " +
                "measure contributions and timing exactly."
        }

        val transactions = (recordedTransactions + syntheticOpenings).sortedBy { it.atMillis }

        // ── Historical prices, one fetch per distinct ticker ──────────────────
        val tickers = holdings.mapNotNull { it.ticker?.trim()?.uppercase()?.ifBlank { null } }.distinct()
        val range = options.period.yahooRange()

        // Warnings are collected from the results rather than appended inside
        // the async blocks: `warnings` is a plain ArrayList and these run
        // concurrently, so writing to it from several of them at once could
        // drop a message or throw outright.
        val fetched: List<Pair<Pair<String, Map<Long, Double>>, String?>> = coroutineScope {
            tickers.map { t ->
                async {
                    val bars = runCatching { priceHistory(t, range, "1d") }.getOrElse { emptyList() }
                    val warning = if (bars.isEmpty())
                        "No price history for $t — held flat at its last known price." else null
                    // Converted on the way in, so the equity curve is a single
                    // currency rather than a mixture of whatever each listing
                    // happens to trade in.
                    val r = rateByTicker[t] ?: 1.0
                    (t to bars.associate { (ms, close) -> dayIndex(ms) to close * r }) to warning
                }
            }.awaitAll()
        }
        fetched.mapNotNull { it.second }.forEach { warnings += it }
        val priceSeries: Map<String, Map<Long, Double>> = fetched.associate { it.first }

        // Forward-filled close for a ticker on a given day, falling back to the
        // holding's stored price when the series has not started yet.
        fun priceOn(ticker: String?, day: Long, fallback: Double?): Double? {
            if (ticker.isNullOrBlank()) return fallback
            val series = priceSeries[ticker.uppercase()] ?: return fallback
            if (series.isEmpty()) return fallback
            series[day]?.let { return it }
            // Walk back to the most recent close on or before this day. Markets
            // close on weekends and holidays, so a gap here is the norm; a run
            // of more than a few days means the series has not started yet.
            var d = day - 1
            var guard = 0
            while (guard < 10) {
                series[d]?.let { return it }
                d--; guard++
            }
            val priorDay = series.keys.filter { it <= day }.maxOrNull()
            return if (priorDay != null) series[priorDay] else fallback
        }

        // ── Replay the ledger into per-day unit and cost-basis state ─────────
        // positionByDay[holdingId] = sorted list of (day, units, costBasis) after that day's activity.
        data class PositionState(var units: Double, var cost: Double)

        val stateByHolding = holdings.associate { it.id to PositionState(0.0, 0.0) }.toMutableMap()
        val realizedRows = mutableListOf<RealizedGainRow>()
        val realizedByHolding = mutableMapOf<Long, Double>()

        // Day -> external net flow (buys positive, sells negative)
        val flowByDay = mutableMapOf<Long, Double>()
        // Day -> per-holding snapshot of units, so the curve can be valued each day
        val unitsTimeline = mutableMapOf<Long, MutableMap<Long, Double>>()
        // Day -> cash added (dividends) / removed (DRIP cost)
        val cashDeltaByDay = mutableMapOf<Long, Double>()

        val holdingById = holdings.associateBy { it.id }
        val accountNameById = accounts.associate { it.id to it.displayName }

        // Dividends first so a DRIP on the same day has cash to consume.
        val dividendByDay = dividends.groupBy { dayIndex(it.paidAtMillis) }
        dividendByDay.forEach { (day, list) ->
            cashDeltaByDay[day] = (cashDeltaByDay[day] ?: 0.0) + list.sumOf { it.amount }
        }

        var activityBuys = 0; var activitySells = 0; var activityDrips = 0
        var totalInvested = 0.0; var totalWithdrawn = 0.0; var totalReinvested = 0.0

        for (tx in transactions) {
            val st = stateByHolding[tx.holdingId] ?: continue
            val day = dayIndex(tx.atMillis)
            val cash = tx.shares * tx.pricePerShare
            // Reconstructed opening balances (negative id) count towards the
            // position and its flows, but are not trades the user placed, so
            // they stay out of the Activity tallies.
            val inPeriod = day in startDay..endDay && tx.id > 0L

            when (tx.type) {
                TransactionType.BUY -> {
                    st.units += tx.shares
                    st.cost += cash
                    flowByDay[day] = (flowByDay[day] ?: 0.0) + cash
                    if (inPeriod) { activityBuys++; totalInvested += cash }
                }
                TransactionType.DRIP -> {
                    // Reinvested income: units up, cost up (the distribution was
                    // taxable money put back into the position), cash bucket down.
                    st.units += tx.shares
                    st.cost += cash
                    cashDeltaByDay[day] = (cashDeltaByDay[day] ?: 0.0) - cash
                    if (inPeriod) { activityDrips++; totalReinvested += cash }
                }
                TransactionType.SELL -> {
                    val avg = if (st.units > 0) st.cost / st.units else 0.0
                    val soldUnits = minOf(tx.shares, st.units)
                    val costOut = avg * soldUnits
                    val gain = soldUnits * (tx.pricePerShare - avg)
                    st.units -= soldUnits
                    st.cost -= costOut
                    if (st.units <= 1e-9) { st.units = 0.0; st.cost = 0.0 }
                    flowByDay[day] = (flowByDay[day] ?: 0.0) - cash
                    realizedByHolding[tx.holdingId] = (realizedByHolding[tx.holdingId] ?: 0.0) + gain
                    if (inPeriod) {
                        activitySells++; totalWithdrawn += cash
                        val h = holdingById[tx.holdingId]
                        realizedRows += RealizedGainRow(
                            holdingName = h?.name ?: "Unknown",
                            ticker = h?.ticker,
                            soldAtMillis = tx.atMillis,
                            shares = soldUnits,
                            salePrice = tx.pricePerShare,
                            averageCostAtSale = avg,
                            proceeds = soldUnits * tx.pricePerShare,
                            costOfUnitsSold = costOut,
                            realizedGain = gain,
                            realizedGainPercent = if (costOut > 0) gain / costOut else null,
                            currency = tx.currency
                        )
                    }
                }
            }
            unitsTimeline.getOrPut(day) { mutableMapOf() }[tx.holdingId] = st.units
        }

        // ── Daily equity curve ───────────────────────────────────────────────
        // Replay again day by day so each day's units are known, then value them.
        val txByDay = transactions.groupBy { dayIndex(it.atMillis) }
        val running = holdings.associate { it.id to 0.0 }.toMutableMap()
        var runningCash = 0.0

        // Wind the ledger forward to the day before the period starts so the
        // curve opens with the position the user actually held that morning.
        val firstLedgerDay = txByDay.keys.minOrNull() ?: startDay
        for (day in firstLedgerDay until startDay) {
            txByDay[day]?.forEach { tx ->
                val delta = when (tx.type) {
                    TransactionType.BUY, TransactionType.DRIP -> tx.shares
                    TransactionType.SELL -> -tx.shares
                }
                running[tx.holdingId] = ((running[tx.holdingId] ?: 0.0) + delta).coerceAtLeast(0.0)
            }
            runningCash += cashDeltaByDay[day] ?: 0.0
        }
        // Dividend cash accrued before the window is not part of this period's
        // return, so the curve starts the cash bucket flat.
        runningCash = 0.0

        val curve = mutableListOf<EquityPoint>()
        var prevValue = Double.NaN
        var index = 1.0

        for (day in startDay..endDay) {
            txByDay[day]?.forEach { tx ->
                val delta = when (tx.type) {
                    TransactionType.BUY, TransactionType.DRIP -> tx.shares
                    TransactionType.SELL -> -tx.shares
                }
                running[tx.holdingId] = ((running[tx.holdingId] ?: 0.0) + delta).coerceAtLeast(0.0)
            }
            runningCash += cashDeltaByDay[day] ?: 0.0

            var positions = 0.0
            for (h in holdings) {
                val u = running[h.id] ?: 0.0
                if (u <= 0.0) continue
                val fallback = h.lastKnownPrice ?: h.manualPrice
                val px = priceOn(h.ticker, day, fallback) ?: continue
                positions += u * px
            }
            val value = positions + runningCash
            val flow = flowByDay[day] ?: 0.0

            // Daily time-weighted return with flows treated as arriving at the
            // END of the day: a buy is recorded at that day's own price, so the
            // new money did not participate in the move from yesterday's close
            // and must be excluded from the day's earning base. Crediting it
            // (start-of-day) understates the return on days money came in.
            if (!prevValue.isNaN()) {
                val r = if (prevValue > 1e-9) (value - flow - prevValue) / prevValue else 0.0
                index *= (1.0 + r)
            }
            curve += EquityPoint(dayStartMillis(day), value, index, flow)
            prevValue = value
        }

        // Opening balance is the value the portfolio carried INTO the window,
        // so the first day's own buys are excluded — they are contributions,
        // not starting capital. Without this a position opened on day one is
        // counted twice (once in the opening value, once in contributions) and
        // the period's gain comes out short by that amount.
        val startValue = curve.firstOrNull()?.let { it.value - it.netFlow }?.coerceAtLeast(0.0) ?: 0.0
        val endValue = curve.lastOrNull()?.value ?: 0.0
        val periodDays = ((nowMillis - periodStart) / MILLIS_PER_DAY).toInt().coerceAtLeast(1)
        val years = periodDays / 365.25

        // ── Returns ──────────────────────────────────────────────────────────
        val twr = (curve.lastOrNull()?.returnIndex ?: 1.0) - 1.0
        val netContributions = curve.sumOf { it.netFlow }
        val dividendIncomeInPeriod = dividends
            .filter { dayIndex(it.paidAtMillis) in startDay..endDay }
            .sumOf { it.amount }
        val realizedInPeriod = realizedRows.sumOf { it.realizedGain }
        val totalGain = endValue - startValue - netContributions

        val mwr = computeXirr(
            buildCashFlows(startValue, periodStart, curve, endValue, nowMillis)
        )

        val annualized = if (years >= 1.0 && twr > -1.0) (1.0 + twr).pow(1.0 / years) - 1.0 else null
        val averageCapital = curve.map { it.value }.filter { it > 0 }.average().takeIf { !it.isNaN() } ?: 0.0
        val simpleReturn = if (averageCapital > 0) totalGain / averageCapital else 0.0

        val unrealizedNow = holdings.sumOf { h ->
            val st = stateByHolding[h.id] ?: return@sumOf 0.0
            val px = h.lastKnownPrice ?: h.manualPrice ?: 0.0
            st.units * px - st.cost
        }

        val returns = ReturnMetrics(
            timeWeightedReturn = twr,
            moneyWeightedReturn = mwr,
            simpleReturn = simpleReturn,
            annualizedReturn = annualized,
            startValue = startValue,
            endValue = endValue,
            netContributions = netContributions,
            totalGain = totalGain,
            dividendIncome = dividendIncomeInPeriod,
            realizedGain = realizedInPeriod,
            unrealizedGain = unrealizedNow,
            periodDays = periodDays
        )

        // ── Risk ─────────────────────────────────────────────────────────────
        val dailyReturns = curve.zipWithNext { a, b ->
            if (a.returnIndex > 1e-12) b.returnIndex / a.returnIndex - 1.0 else 0.0
        }
        val risk = computeRisk(curve, dailyReturns, annualized ?: twr, years)

        // ── Benchmark ────────────────────────────────────────────────────────
        val benchmark = options.benchmark?.let { bm ->
            val bars = runCatching { priceHistory(bm.ticker, range, "1d") }.getOrElse { emptyList() }
            if (bars.isEmpty()) {
                warnings += "Benchmark ${bm.ticker} could not be loaded — comparison omitted."
                null
            } else {
                buildBenchmark(bm, bars, curve, dailyReturns, twr, annualized, years, risk.riskFreeRateUsed)
            }
        }

        // ── Per-holding performance ──────────────────────────────────────────
        val dividendByHolding = dividends.groupBy { it.holdingId }
            .mapValues { (_, v) -> v.filter { dayIndex(it.paidAtMillis) in startDay..endDay }.sumOf { it.amount } }

        val rows = holdings.mapNotNull { h ->
            val st = stateByHolding[h.id] ?: return@mapNotNull null
            val realized = realizedByHolding[h.id] ?: 0.0
            val income = dividendByHolding[h.id] ?: 0.0
            if (st.units <= 0.0 && realized == 0.0 && income == 0.0) return@mapNotNull null

            val px = h.lastKnownPrice ?: h.manualPrice
            val mv = st.units * (px ?: 0.0)
            val unreal = mv - st.cost
            HoldingPerformance(
                holdingId = h.id,
                name = h.name,
                ticker = h.ticker,
                type = h.type,
                accountName = accountNameById[h.accountId] ?: "—",
                units = st.units,
                averageCost = if (st.units > 0) st.cost / st.units else null,
                currentPrice = px,
                marketValue = mv,
                costBasis = st.cost,
                unrealizedGain = unreal,
                unrealizedGainPercent = if (st.cost > 0) unreal / st.cost else null,
                realizedGain = realized,
                dividendIncome = income,
                totalGain = unreal + realized + income,
                weight = 0.0,
                contributionShare = null,
                currency = h.currency
            )
        }

        val totalMv = rows.sumOf { it.marketValue }
        val gainMagnitude = rows.sumOf { abs(it.totalGain) }
        val holdingRows = rows.map {
            it.copy(
                weight = if (totalMv > 0) it.marketValue / totalMv else 0.0,
                contributionShare = if (gainMagnitude > 0) it.totalGain / gainMagnitude else null
            )
        }.sortedByDescending { it.totalGain }

        // ── Income ───────────────────────────────────────────────────────────
        val income = buildIncome(dividends, transactions, holdingById, startDay, endDay, nowMillis,
            currentCost = holdingRows.sumOf { it.costBasis },
            currentValue = totalMv)

        // ── Allocation ───────────────────────────────────────────────────────
        val allocation = buildAllocation(holdingRows, totalMv)

        val activity = ActivitySummary(
            buyCount = activityBuys,
            sellCount = activitySells,
            dripCount = activityDrips,
            totalInvested = totalInvested,
            totalWithdrawn = totalWithdrawn,
            totalReinvested = totalReinvested
        )

        val untickered = holdings
            .filter { it.ticker.isNullOrBlank() && (stateByHolding[it.id]?.units ?: 0.0) > 0 }
            .map { it.name }

        PerformanceReport(
            generatedAtMillis = nowMillis,
            period = options.period,
            periodStartMillis = periodStart,
            periodEndMillis = nowMillis,
            currency = currency,
            returns = returns,
            risk = risk,
            benchmark = benchmark,
            equityCurve = curve,
            holdings = holdingRows,
            realizedGains = realizedRows.sortedByDescending { it.soldAtMillis },
            income = income,
            allocation = allocation,
            activity = activity,
            untickeredHoldingNames = untickered,
            warnings = warnings
        )
    }

    // ── Cash flows for XIRR ──────────────────────────────────────────────────

    private fun buildCashFlows(
        startValue: Double,
        startMillis: Long,
        curve: List<EquityPoint>,
        endValue: Double,
        endMillis: Long
    ): List<Pair<Long, Double>> {
        val flows = mutableListOf<Pair<Long, Double>>()
        // Opening balance is money already committed at the start of the window.
        if (startValue > 0) flows += startMillis to -startValue
        curve.forEach { p ->
            if (abs(p.netFlow) > 1e-9) flows += p.atMillis to -p.netFlow
        }
        if (endValue > 0) flows += endMillis to endValue
        return flows
    }

    /**
     * Annualized money-weighted return, solved by bisection.
     *
     * Bisection rather than Newton-Raphson: irregular hand-entered flows can
     * produce a derivative near zero and send Newton off to infinity, and a
     * report that prints a wrong number is worse than one that prints none.
     */
    fun computeXirr(flows: List<Pair<Long, Double>>): Double? {
        if (flows.size < 2) return null
        if (flows.none { it.second > 0 } || flows.none { it.second < 0 }) return null

        val t0 = flows.minOf { it.first }
        fun npv(rate: Double): Double = flows.sumOf { (ms, amt) ->
            val years = (ms - t0).toDouble() / (365.25 * MILLIS_PER_DAY)
            amt / (1.0 + rate).pow(years)
        }

        var low = -0.9999
        var high = 10.0
        var fLow = npv(low)
        var fHigh = npv(high)
        if (fLow * fHigh > 0) return null

        repeat(200) {
            val mid = (low + high) / 2.0
            val fMid = npv(mid)
            if (abs(fMid) < 1e-9) return mid
            if (fLow * fMid < 0) { high = mid; fHigh = fMid } else { low = mid; fLow = fMid }
        }
        return (low + high) / 2.0
    }

    // ── Risk ─────────────────────────────────────────────────────────────────

    private fun computeRisk(
        curve: List<EquityPoint>,
        dailyReturns: List<Double>,
        annualizedReturn: Double,
        years: Double
    ): RiskMetrics {
        val rf = DEFAULT_RISK_FREE_RATE

        val vol = if (dailyReturns.size >= 5) stdev(dailyReturns) * sqrt(TRADING_DAYS_PER_YEAR) else null
        val sharpe = if (vol != null && vol > 1e-9) (annualizedReturn - rf) / vol else null

        // Drawdown is measured on the return index, not raw value: a deposit
        // lifts the value curve and would otherwise hide a real decline.
        var peak = Double.NEGATIVE_INFINITY
        var peakMs: Long? = null
        var worst = 0.0
        var worstPeak: Long? = null
        var worstTrough: Long? = null
        curve.forEach { p ->
            if (p.returnIndex > peak) { peak = p.returnIndex; peakMs = p.atMillis }
            if (peak > 0) {
                val dd = p.returnIndex / peak - 1.0
                if (dd < worst) { worst = dd; worstPeak = peakMs; worstTrough = p.atMillis }
            }
        }
        val recovered = worstTrough?.let { trough ->
            val peakValue = curve.firstOrNull { it.atMillis == worstPeak }?.returnIndex
            peakValue != null && curve.any { it.atMillis > trough && it.returnIndex >= peakValue }
        } ?: false

        // Monthly buckets: compound each month's daily returns, then convert the
        // accumulated (1+r) product back into a plain return.
        val monthProducts = mutableMapOf<Long, Double>()
        curve.zipWithNext().forEach { (a, b) ->
            val key = monthKey(b.atMillis)
            val r = if (a.returnIndex > 1e-12) b.returnIndex / a.returnIndex - 1.0 else 0.0
            monthProducts[key] = (monthProducts[key] ?: 1.0) * (1.0 + r)
        }
        val monthlyReturns = monthProducts
            .map { (k, product) -> k to (product - 1.0) }
            .sortedBy { it.first }

        return RiskMetrics(
            annualizedVolatility = vol,
            sharpeRatio = sharpe,
            maxDrawdown = if (worst < 0) worst else null,
            maxDrawdownPeakMillis = worstPeak,
            maxDrawdownTroughMillis = worstTrough,
            drawdownRecovered = recovered,
            bestMonth = monthlyReturns.maxByOrNull { it.second },
            worstMonth = monthlyReturns.minByOrNull { it.second },
            positiveMonths = monthlyReturns.count { it.second > 0 },
            totalMonths = monthlyReturns.size,
            riskFreeRateUsed = rf
        )
    }

    private fun monthKey(millis: Long): Long {
        val c = Calendar.getInstance(TimeZone.getDefault()).apply {
            timeInMillis = millis
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        return c.timeInMillis
    }

    private fun stdev(xs: List<Double>): Double {
        if (xs.size < 2) return 0.0
        val m = xs.average()
        return sqrt(xs.sumOf { (it - m) * (it - m) } / (xs.size - 1))
    }

    // ── Benchmark ────────────────────────────────────────────────────────────

    private fun buildBenchmark(
        bm: Benchmark,
        bars: List<Pair<Long, Double>>,
        curve: List<EquityPoint>,
        portfolioDaily: List<Double>,
        portfolioTwr: Double,
        portfolioAnnualized: Double?,
        years: Double,
        rf: Double
    ): BenchmarkComparison {
        val byDay = bars.associate { dayIndex(it.first) to it.second }
        val sortedDays = byDay.keys.sorted()

        fun closeOn(day: Long): Double? {
            byDay[day]?.let { return it }
            val prior = sortedDays.lastOrNull { it <= day } ?: return null
            return byDay[prior]
        }

        val aligned = curve.mapNotNull { p ->
            val d = dayIndex(p.atMillis)
            closeOn(d)?.let { p.atMillis to it }
        }
        if (aligned.size < 2) {
            return BenchmarkComparison(bm, 0.0, portfolioTwr, portfolioTwr, null, null, null, emptyList())
        }

        val base = aligned.first().second
        val bmIndex = aligned.map { (ms, close) ->
            EquityPoint(ms, close, if (base > 0) close / base else 1.0, 0.0)
        }
        val bmReturn = (bmIndex.last().returnIndex) - 1.0
        val bmDaily = bmIndex.zipWithNext { a, b ->
            if (a.returnIndex > 1e-12) b.returnIndex / a.returnIndex - 1.0 else 0.0
        }

        val n = minOf(portfolioDaily.size, bmDaily.size)
        var beta: Double? = null
        var corr: Double? = null
        if (n >= 5) {
            val p = portfolioDaily.takeLast(n)
            val b = bmDaily.takeLast(n)
            val pm = p.average(); val bmm = b.average()
            var cov = 0.0; var varB = 0.0; var varP = 0.0
            for (i in 0 until n) {
                val dp = p[i] - pm; val db = b[i] - bmm
                cov += dp * db; varB += db * db; varP += dp * dp
            }
            cov /= (n - 1); varB /= (n - 1); varP /= (n - 1)
            if (varB > 1e-12) beta = cov / varB
            if (varB > 1e-12 && varP > 1e-12) corr = cov / sqrt(varB * varP)
        }

        val bmAnnualized = if (years >= 1.0 && bmReturn > -1.0) (1.0 + bmReturn).pow(1.0 / years) - 1.0 else bmReturn
        val portAnn = portfolioAnnualized ?: portfolioTwr
        val alpha = beta?.let { portAnn - (rf + it * (bmAnnualized - rf)) }

        return BenchmarkComparison(
            benchmark = bm,
            benchmarkReturn = bmReturn,
            portfolioReturn = portfolioTwr,
            excessReturn = portfolioTwr - bmReturn,
            beta = beta,
            alpha = alpha,
            correlation = corr,
            benchmarkIndex = bmIndex
        )
    }

    // ── Income ───────────────────────────────────────────────────────────────

    private fun buildIncome(
        dividends: List<DividendPaymentEntity>,
        transactions: List<TransactionEntity>,
        holdingById: Map<Long, HoldingEntity>,
        startDay: Long,
        endDay: Long,
        nowMillis: Long,
        currentCost: Double,
        currentValue: Double
    ): IncomeSummary {
        val inPeriod = dividends.filter { dayIndex(it.paidAtMillis) in startDay..endDay }
        val ttmCutoff = nowMillis - (365L * MILLIS_PER_DAY)
        val ttm = dividends.filter { it.paidAtMillis >= ttmCutoff }.sumOf { it.amount }

        val byMonth = inPeriod.groupBy { monthKey(it.paidAtMillis) }
            .map { (k, v) -> k to v.sumOf { it.amount } }
            .sortedBy { it.first }

        val byHolding = inPeriod.groupBy { it.holdingId }
            .map { (id, v) -> (holdingById[id]?.let { h -> h.ticker ?: h.name } ?: "Unknown") to v.sumOf { it.amount } }
            .sortedByDescending { it.second }

        val reinvestedIds = transactions.filter { it.sourceDividendId != null }.mapNotNull { it.sourceDividendId }.toSet()
        val reinvested = inPeriod.filter { it.id in reinvestedIds }.sumOf { it.amount }

        return IncomeSummary(
            totalInPeriod = inPeriod.sumOf { it.amount },
            trailing12Months = ttm,
            byMonth = byMonth,
            byHolding = byHolding,
            yieldOnCost = if (currentCost > 0) ttm / currentCost else null,
            currentYield = if (currentValue > 0) ttm / currentValue else null,
            reinvestedAmount = reinvested,
            cashAmount = inPeriod.sumOf { it.amount } - reinvested,
            paymentCount = inPeriod.size
        )
    }

    // ── Allocation ───────────────────────────────────────────────────────────

    private fun buildAllocation(rows: List<HoldingPerformance>, totalMv: Double): AllocationSummary {
        fun slices(pairs: List<Pair<String, Double>>): List<AllocationSlice> =
            pairs.filter { it.second > 0 }
                .sortedByDescending { it.second }
                .map { (label, v) -> AllocationSlice(label, v, if (totalMv > 0) v / totalMv else 0.0) }

        val byType = slices(rows.groupBy { it.type.label() }.map { (k, v) -> k to v.sumOf { it.marketValue } })
        val byAccount = slices(rows.groupBy { it.accountName }.map { (k, v) -> k to v.sumOf { it.marketValue } })
        val byHolding = slices(rows.map { (it.ticker ?: it.name) to it.marketValue })

        val largest = byHolding.firstOrNull()
        val hhi = if (totalMv > 0) rows.sumOf { val w = it.marketValue / totalMv; w * w } else 0.0

        return AllocationSummary(
            byType = byType,
            byAccount = byAccount,
            byHolding = byHolding,
            largestPositionWeight = largest?.percent ?: 0.0,
            largestPositionName = largest?.label,
            concentrationIndex = hhi
        )
    }

    // ── Empty state ──────────────────────────────────────────────────────────

    private fun emptyReport(
        options: ReportOptions,
        start: Long,
        end: Long,
        currency: String,
        warnings: List<String>
    ) = PerformanceReport(
        generatedAtMillis = end,
        period = options.period,
        periodStartMillis = start,
        periodEndMillis = end,
        currency = currency,
        returns = ReturnMetrics(0.0, null, 0.0, null, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1),
        risk = RiskMetrics(null, null, null, null, null, false, null, null, 0, 0, DEFAULT_RISK_FREE_RATE),
        benchmark = null,
        equityCurve = emptyList(),
        holdings = emptyList(),
        realizedGains = emptyList(),
        income = IncomeSummary(0.0, 0.0, emptyList(), emptyList(), null, null, 0.0, 0.0, 0),
        allocation = AllocationSummary(emptyList(), emptyList(), emptyList(), 0.0, null, 0.0),
        activity = ActivitySummary(0, 0, 0, 0.0, 0.0, 0.0),
        untickeredHoldingNames = emptyList(),
        warnings = warnings
    )
}
