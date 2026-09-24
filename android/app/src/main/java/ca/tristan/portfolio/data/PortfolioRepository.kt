package ca.tristan.portfolio.data

import ca.tristan.portfolio.data.db.AccountDao
import ca.tristan.portfolio.data.db.AlertDao
import ca.tristan.portfolio.data.db.AlertEntity
import ca.tristan.portfolio.data.db.AlertKind
import ca.tristan.portfolio.data.db.AccountEntity
import ca.tristan.portfolio.data.db.DividendDao
import ca.tristan.portfolio.data.db.DividendPaymentEntity
import ca.tristan.portfolio.data.db.HoldingDao
import ca.tristan.portfolio.data.db.HoldingEntity
import ca.tristan.portfolio.data.db.HoldingType
import ca.tristan.portfolio.data.db.PriceSnapshotDao
import ca.tristan.portfolio.data.db.PriceSnapshotEntity
import ca.tristan.portfolio.data.db.TransactionDao
import ca.tristan.portfolio.data.db.TransactionEntity
import ca.tristan.portfolio.data.db.TaxTreatment
import ca.tristan.portfolio.data.db.TransactionType
import ca.tristan.portfolio.data.db.WatchlistDao
import ca.tristan.portfolio.data.db.WatchlistItemEntity
import ca.tristan.portfolio.net.HistoryBar
import ca.tristan.portfolio.net.Quote
import ca.tristan.portfolio.net.SymbolSearchResult
import ca.tristan.portfolio.net.UpcomingDividend
import ca.tristan.portfolio.net.YahooQuoteClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

data class QuotePreview(val price: Double?, val name: String?, val found: Boolean)

/**
 * A portfolio value line, together with what it took to get there.
 *
 * The points on their own cannot answer "how did I do over this period?". A
 * line that climbs from 43,000 to 68,000 has climbed either because the market
 * did well or because the user paid 25,000 into it, and nothing in the shape of
 * the line distinguishes the two. The money that moved in and out travels with
 * the series so the figure printed above the chart can subtract it.
 */
data class PortfolioSeries(
    val points: List<Pair<Long, Double>> = emptyList(),
    /**
     * Money paid in less money taken out, inside the window the points cover,
     * in the reporting currency. Flows before the opening point are already
     * priced into it and are not counted here.
     */
    val netContributions: Double = 0.0,
    /**
     * False when the line starts later than the range asked for — a portfolio
     * three weeks old has no year to draw, and the card says so rather than
     * labelling three weeks "1Y".
     */
    val coversRequestedRange: Boolean = true
) {
    val isEmpty: Boolean get() = points.size < 2
    val startMs: Long? get() = points.firstOrNull()?.first
    val startValue: Double get() = points.firstOrNull()?.second ?: 0.0
    val endValue: Double get() = points.lastOrNull()?.second ?: 0.0

    /**
     * What the portfolio actually made over the window.
     *
     * End minus start calls a deposit a gain, which is how a screen comes to
     * report +55% in a month the market moved two. Netting the flows out leaves
     * the part the holdings earned.
     */
    val gain: Double get() = endValue - startValue - netContributions

    /**
     * [gain] against what was actually at risk to earn it.
     *
     * The denominator is the opening value plus the money added, not the
     * opening value alone: measuring a whole month's gain against a starting
     * balance of nearly nothing turns a modest return into a meaningless
     * multiple. A simple approximation of a money-weighted return, not a true
     * one — the daily-weighted version lives in PerformanceCalculator.
     */
    val gainPercent: Double
        get() {
            val invested = startValue + netContributions
            return if (invested > 0.01) gain / invested * 100 else 0.0
        }
}

class PortfolioRepository(
    private val accountDao: AccountDao,
    private val holdingDao: HoldingDao,
    private val priceSnapshotDao: PriceSnapshotDao,
    private val dividendDao: DividendDao,
    private val watchlistDao: WatchlistDao,
    private val transactionDao: TransactionDao,
    private val alertDao: AlertDao
) {

    // ── Alerts ────────────────────────────────────────────────────────────

    fun observeAlerts(): kotlinx.coroutines.flow.Flow<List<AlertEntity>> =
        alertDao.observeAll()

    suspend fun addAlert(
        ticker: String,
        kind: AlertKind,
        threshold: Double
    ): Long = withContext(Dispatchers.IO) {
        alertDao.insert(
            AlertEntity(
                // Uppercased and trimmed here rather than trusted from the
                // text field: the quote endpoint is case-sensitive about
                // symbols, and "vdy.to " would simply never match anything —
                // an alert that looks saved and can never fire.
                ticker = ticker.trim().uppercase(),
                kind = kind,
                threshold = threshold,
                createdAtMillis = System.currentTimeMillis()
            )
        )
    }

    suspend fun setAlertEnabled(id: Long, enabled: Boolean) = withContext(Dispatchers.IO) {
        alertDao.setEnabled(id, enabled)
        // Re-arming on re-enable, deliberately. A rule switched off while its
        // condition was true would otherwise come back already latched and
        // stay silent through the next genuine crossing.
        if (enabled) alertDao.markTriggered(id, null, null)
    }

    suspend fun deleteAlert(id: Long) = withContext(Dispatchers.IO) { alertDao.delete(id) }
    /**
     * Currency every portfolio-level total is expressed in.
     *
     * Per-holding screens still show a position in its own currency — that's
     * the number on the statement — but anything that adds two holdings
     * together has to pick one currency and convert, or it is adding US
     * dollars to Canadian ones. Set from the ViewModel, which owns the
     * user's saved preference.
     */
    @Volatile
    var baseCurrency: String = "CAD"

    /** Fetches any FX rate needed to value [holdings] in [baseCurrency]. */
    private suspend fun ensureRatesFor(holdings: List<HoldingEntity>) {
        FxRates.refresh(holdings.map { it.currency }, baseCurrency)
    }

    fun observeAccounts(): Flow<List<AccountEntity>> = accountDao.observeAll()
    fun observeHoldings(): Flow<List<HoldingEntity>> = holdingDao.observeAll()
    fun observeDividends(): Flow<List<DividendPaymentEntity>> = dividendDao.observeAll()

    fun observePortfolio(): Flow<Pair<List<AccountEntity>, List<HoldingEntity>>> =
        combine(observeAccounts(), observeHoldings()) { accounts, holdings -> accounts to holdings }

    /** Creates a NEW account. See [AccountDao.upsert] before reusing this to edit one. */
    suspend fun upsertAccount(account: AccountEntity) = accountDao.upsert(account)

    /** Changes an existing account's tax treatment without touching its holdings. */
    suspend fun setAccountTaxTreatment(accountId: Long, treatment: TaxTreatment?) =
        withContext(Dispatchers.IO) { accountDao.setTaxTreatment(accountId, treatment?.name) }

    suspend fun deleteAccount(id: Long) = withContext(Dispatchers.IO) {
        accountDao.delete(id) // cascades to holdings via ForeignKey.CASCADE
    }

    suspend fun deleteHolding(id: Long) = withContext(Dispatchers.IO) { holdingDao.delete(id) }

    /**
     * Save a holding, either creating a new one (existingId == null) or
     * updating one in place (e.g. "bought more XEQT") without deleting/recreating.
     * Fetches a live quote immediately after saving for ETF/stock holdings with a
     * ticker so it doesn't sit at $0 until the next background poll.
     */
    suspend fun saveHolding(
        existingId: Long?,
        accountId: Long,
        name: String,
        ticker: String?,
        type: HoldingType,
        units: Double,
        manualPrice: Double?,
        currency: String,
        costBasis: Double?
    ): Long = withContext(Dispatchers.IO) {
        val entity = HoldingEntity(
            id = existingId ?: 0,
            accountId = accountId,
            name = name,
            ticker = ticker?.ifBlank { null },
            type = type,
            units = units,
            manualPrice = manualPrice,
            currency = currency,
            costBasis = costBasis
        )
        val id = if (existingId != null) {
            holdingDao.update(entity)
            existingId
        } else {
            holdingDao.upsert(entity)
        }
        if (!ticker.isNullOrBlank() && (type == HoldingType.ETF || type == HoldingType.STOCK || type == HoldingType.CRYPTO)) {
            // refreshQuote also adopts the exchange's real currency, so a US
            // listing stops being labelled CAD just because that's the default.
            refreshQuote(id)
        }
        id
    }

    // Debounced live lookup for the Add/Edit Holding screen, shown as
    // "Live now: $X.XX · <name>" before the user saves. Doesn't touch the DB.
    suspend fun previewQuote(ticker: String): QuotePreview = withContext(Dispatchers.IO) {
        if (ticker.isBlank()) return@withContext QuotePreview(null, null, false)
        val quote = YahooQuoteClient.fetch(ticker.trim().uppercase())
        if (quote == null) QuotePreview(null, null, false) else QuotePreview(quote.price, quote.name, true)
    }

    suspend fun refreshQuote(holdingId: Long) = withContext(Dispatchers.IO) {
        val holding = holdingDao.getById(holdingId) ?: return@withContext
        val ticker = holding.ticker ?: return@withContext
        val quote = YahooQuoteClient.fetch(ticker) ?: return@withContext
        val now = System.currentTimeMillis()
        holdingDao.update(
            holding.copy(
                lastKnownPrice = quote.price,
                lastPriceAtMillis = now,
                // Yahoo knows what the listing actually trades in; the stored
                // value is only a default until a quote tells us otherwise.
                currency = quote.currency?.uppercase() ?: holding.currency,
                // Same argument for the instrument type. A holding created
                // before the search results were classified correctly can be
                // sitting on the wrong type (a stock filed as an ETF); the
                // provider knows better, so let the refresh repair it rather
                // than making the user delete and re-add the position.
                type = correctedType(holding.type, quote.instrumentType)
            )
        )
        priceSnapshotDao.insert(PriceSnapshotEntity(holdingId = holdingId, atMillis = now, price = quote.price))
    }

    /**
     * Writes back the classification and currency a freshly-fetched [quote]
     * implies for a holding, without spending another request.
     *
     * The detail screen already has a full quote in hand, and a holding filed
     * under the wrong type is most likely to be noticed there — so opening the
     * screen is what repairs it, rather than waiting for a background refresh.
     */
    suspend fun applyQuoteMetadata(holdingId: Long, quote: Quote) = withContext(Dispatchers.IO) {
        val holding = holdingDao.getById(holdingId) ?: return@withContext
        val type = correctedType(holding.type, quote.instrumentType)
        val currency = quote.currency?.uppercase() ?: holding.currency
        if (type == holding.type && currency == holding.currency) return@withContext
        holdingDao.update(holding.copy(type = type, currency = currency))
    }

    /**
     * The holding type a quote implies, or the stored one when the quote says
     * nothing useful.
     *
     * Only the market-priced types are ever overwritten. Seg funds, cash and
     * "other" are the user's own classification of something the market does
     * not label, so a provider's guess must not replace them.
     */
    private fun correctedType(current: HoldingType, instrumentType: String?): HoldingType {
        if (current == HoldingType.SEG_FUND || current == HoldingType.CASH ||
            current == HoldingType.OTHER
        ) return current
        return HoldingType.fromQuoteType(instrumentType) ?: current
    }

    suspend fun refreshAllQuotes() = withContext(Dispatchers.IO) {
        val list = holdingDao.observeAll().first()
        for (h in list) {
            if (h.ticker != null) refreshQuote(h.id)
        }
    }

    /**
     * Refreshes quotes for all holdings and returns the total portfolio day
     * change in local units (sum of (price - prevClose) * units).
     */
    /**
     * Refreshes every held ticker and reports today's move.
     *
     * Returns the move AND the market value it was measured across, because
     * the two have to be divided by each other. Returning only the move meant
     * the caller divided it by the WHOLE portfolio total — so any holding
     * whose quote failed dropped out of the numerator while its full value
     * stayed in the denominator, and the percentage came out quietly wrong
     * until the next successful refresh.
     */
    /**
     * Today's move, and the base it is measured against.
     *
     * [measuredValue] is the PREVIOUS CLOSE value of the holdings that could
     * be priced — the denominator of the percentage — not their value now.
     */
    data class DayChange(
        val amount: Double,
        val measuredValue: Double,
        // The exact price/previousClose this pass priced each ticker at — the
        // SAME authoritative figures the day-change sum above was computed
        // from. Callers write this into the shared `_quotes` cache so the
        // Holding Detail screen (and anything else reading that cache) agrees
        // with the Portfolio total instead of asking its own, separate
        // question of the same undocumented endpoint.
        val quotes: Map<String, Quote> = emptyMap()
    )

    suspend fun refreshAllQuotesAndComputeDayChange(): DayChange = withContext(Dispatchers.IO) {
        val list = holdingDao.observeAll().first().filter { it.ticker != null }
        if (list.isEmpty()) return@withContext DayChange(0.0, 0.0)

        // One batched request for every held ticker instead of a sequential
        // per-holding call, which is what made the portfolio slow to settle.
        val tickers = list.mapNotNull { it.ticker }.distinct()

        // ONE authoritative price source, and it is the same one every other
        // write to lastKnownPrice uses.
        //
        // This used to call fetchSparkBatch, which goes straight to Yahoo's
        // spark endpoint and skips the FinanceQuery -> Finnhub -> Yahoo chain
        // that `fetch` (and therefore the background worker, and every
        // post-transaction refresh) goes through. Two providers wrote the same
        // field from two different caches, so the displayed value changed
        // depending on which path happened to run last — a pull-to-refresh
        // showed Yahoo's number, the next worker pass replaced it with
        // FinanceQuery's, and the portfolio appeared to drift on its own.
        //
        // Spark is a CHART endpoint being read for a price: when its payload
        // carries no `meta`, the price falls back to the last 30-minute bar
        // close, which is a third number again. It stays in use below purely
        // as a fallback for symbols the quote batch did not answer for.
        val quotes = runCatching { YahooQuoteClient.fetchQuotes(tickers) }
            .getOrDefault(emptyMap())

        // fetchQuotes keys its map by the UPPERCASED symbol, while a holding's
        // stored ticker keeps whatever spelling it was entered with. Looking
        // up the raw string missed every lower-case ticker, which would have
        // sent it silently down the spark fallback — the exact split this
        // change exists to remove.
        fun quoteFor(ticker: String) = quotes[ticker.trim().uppercase()]

        val missing = tickers.filter { quoteFor(it) == null }
        val batch = if (missing.isEmpty()) {
            emptyMap()
        } else {
            runCatching { YahooQuoteClient.fetchSparkBatch(missing, "1d", "30m") }
                .getOrDefault(emptyMap())
        }

        // Rates first: the day change below is a sum across holdings, so it
        // needs every currency expressible in the base one before it starts.
        ensureRatesFor(list)

        var dayChange = 0.0
        var measuredValue = 0.0
        val now = System.currentTimeMillis()
        // Whatever this pass actually priced each ticker at — handed back so
        // the caller can seed the shared `_quotes` cache with the SAME numbers
        // the day-change sum below used. Populated once per ticker, in
        // whichever branch below resolved it.
        val resolvedQuotes = mutableMapOf<String, Quote>()
        for (h in list) {
            val ticker = h.ticker ?: continue

            val price: Double
            val prevClose: Double?
            // Null when the figure came from spark, which carries no currency.
            val quotedCurrency: String?

            val quoted = quoteFor(ticker)
            val spark = if (quoted == null) batch[ticker] else null
            when {
                quoted != null -> {
                    price = quoted.price
                    prevClose = quoted.previousClose
                    quotedCurrency = quoted.currency
                    resolvedQuotes[ticker] = quoted
                }
                spark != null -> {
                    price = spark.price
                    prevClose = spark.previousClose
                    quotedCurrency = null
                    resolvedQuotes[ticker] = Quote(
                        price = spark.price,
                        name = null,
                        previousClose = spark.previousClose,
                        marketTimeMillis = spark.marketTimeMillis,
                        exchangeTimezone = spark.exchangeTimezone
                    )
                }
                else -> {
                    // Last resort, same chain as everywhere else.
                    val single = YahooQuoteClient.fetch(ticker) ?: continue
                    price = single.price
                    prevClose = single.previousClose
                    quotedCurrency = single.currency
                    resolvedQuotes[ticker] = single
                }
            }

            // The provider knows what the listing actually trades in; the
            // suffix-inferred currency is only a default for when it does not
            // say. Matches `refreshQuote`, which has always trusted the quote
            // over the stored value — the two disagreeing was itself a way for
            // a converted total to move without any price moving.
            val currency = quotedCurrency?.uppercase()
                ?: currencyForTicker(ticker, h.currency)
            holdingDao.update(
                h.copy(
                    lastKnownPrice = price,
                    lastPriceAtMillis = now,
                    currency = currency
                )
            )
            priceSnapshotDao.insert(PriceSnapshotEntity(holdingId = h.id, atMillis = now, price = price))
            // Converted per holding before being added in — a US position that
            // moved $100 did not move the Canadian-dollar total by $100.
            if (prevClose != null) {
                dayChange += FxRates.convert(
                    (price - prevClose) * h.units, currency, baseCurrency
                )
                // The PREVIOUS CLOSE value, not today's.
                //
                // A day change percentage is the move divided by what the
                // position was worth before the move — that is what every
                // quote screen means by "+1.41% today", and what this app's
                // own security detail screen shows. Dividing by the CURRENT
                // value instead puts the gain in its own denominator, so a
                // rising portfolio always reported slightly less than the
                // holding it was made of: 1.39% against the detail screen's
                // 1.41% for a single-ETF portfolio that must agree exactly.
                //
                // Still only what was actually measured, so a partial refresh
                // reports an honest percentage of the part it could price.
                measuredValue += FxRates.convert(prevClose * h.units, currency, baseCurrency)
            }
        }
        DayChange(dayChange, measuredValue, resolvedQuotes)
    }

    /**
     * Portfolio market value over time, reconstructed from the price snapshots
     * recorded on each refresh.
     *
     * Snapshots land at slightly different instants per holding, so prices are
     * carried forward into daily buckets: for each day, every holding is valued
     * at its most recent snapshot on or before that day. Days before a holding's
     * first snapshot simply exclude it, which is the honest treatment — the
     * position genuinely wasn't being tracked yet.
     */
    suspend fun portfolioValueHistory(days: Int = 90): List<Pair<Long, Double>> =
        withContext(Dispatchers.IO) {
            val holdings = holdingDao.observeAll().first()
            if (holdings.isEmpty()) return@withContext emptyList()
            ensureRatesFor(holdings)

            val dayMs = 24L * 60 * 60 * 1000
            val since = System.currentTimeMillis() - days * dayMs

            // holdingId -> its snapshots, oldest first
            val byHolding = holdings.associate { h ->
                h.id to priceSnapshotDao.history(h.id, since).sortedBy { it.atMillis }
            }
            if (byHolding.values.all { it.isEmpty() }) return@withContext emptyList()

            val firstTs = byHolding.values.mapNotNull { it.firstOrNull()?.atMillis }.minOrNull()
                ?: return@withContext emptyList()

            val startDay = firstTs / dayMs
            val endDay = System.currentTimeMillis() / dayMs

            (startDay..endDay).mapNotNull { day ->
                val cutoff = day * dayMs + dayMs - 1
                var total = 0.0
                var any = false
                for (h in holdings) {
                    val snap = byHolding[h.id]?.lastOrNull { it.atMillis <= cutoff }
                    val price = snap?.price ?: continue
                    total += FxRates.convert(price * h.units, h.currency, baseCurrency)
                    any = true
                }
                if (any) (day * dayMs) to total else null
            }
        }

    /**
     * Currency a listing trades in, from its Yahoo exchange suffix.
     *
     * Holdings default to CAD, which mislabels every US listing (JEPQ, AAPL…)
     * as Canadian until a full quote happens to correct it. The suffix is
     * deterministic and needs no network call, so it's the reliable default;
     * [refreshQuote] still overrides it with Yahoo's own value when it runs.
     */
    fun currencyForTicker(ticker: String?, fallback: String = "CAD"): String {
        val t = ticker?.uppercase() ?: return fallback
        return when {
            t.endsWith(".TO") || t.endsWith(".V") || t.endsWith(".NE") ||
                t.endsWith(".CN") || t.endsWith(".TSX")        -> "CAD"
            t.endsWith(".L")                                   -> "GBP"
            t.endsWith(".DE") || t.endsWith(".F") || t.endsWith(".BE") ||
                t.endsWith(".PA") || t.endsWith(".AS") || t.endsWith(".MI") ||
                t.endsWith(".MC") || t.endsWith(".HE") || t.endsWith(".IR") ||
                t.endsWith(".LS") || t.endsWith(".VI") || t.endsWith(".BR") -> "EUR"
            t.endsWith(".SW")                                  -> "CHF"
            // Nordics: each keeps its own krona/krone. Missing these is what
            // left a Stockholm listing (ERIC-A.ST) labelled CAD until a full
            // Yahoo quote happened to correct it — and any figure computed
            // from it in the meantime was kronor being counted as dollars.
            t.endsWith(".ST")                                  -> "SEK"
            t.endsWith(".OL")                                  -> "NOK"
            t.endsWith(".CO")                                  -> "DKK"
            t.endsWith(".IC")                                  -> "ISK"
            t.endsWith(".T")                                   -> "JPY"
            t.endsWith(".HK")                                  -> "HKD"
            t.endsWith(".SS") || t.endsWith(".SZ")             -> "CNY"
            t.endsWith(".AX")                                  -> "AUD"
            t.endsWith(".NZ")                                  -> "NZD"
            t.endsWith(".NS") || t.endsWith(".BO")             -> "INR"
            t.endsWith(".SA")                                  -> "BRL"
            t.endsWith(".MX")                                  -> "MXN"
            t.endsWith(".KS") || t.endsWith(".KQ")             -> "KRW"
            t.endsWith(".TW") || t.endsWith(".TWO")            -> "TWD"
            t.endsWith(".SI")                                  -> "SGD"
            t.endsWith(".JK")                                  -> "IDR"
            t.endsWith(".BK")                                  -> "THB"
            t.endsWith(".IS")                                  -> "TRY"
            t.endsWith(".TA")                                  -> "ILS"
            t.endsWith(".WA")                                  -> "PLN"
            t.endsWith(".JO")                                  -> "ZAR"
            t.contains("-USD")                                 -> "USD"
            // No suffix on Yahoo means a US listing.
            !t.contains(".")                                   -> "USD"
            else                                               -> fallback
        }
    }

    /**
     * Portfolio market value over time, one point per bar in the requested
     * range, valued at the units actually held at each instant.
     *
     * Price history comes from each holding's own ticker rather than from
     * locally recorded snapshots — those only start the day the app is
     * installed, so a 1Y or 5Y view built from them would be almost empty.
     * Unit counts come from the holding's recorded transactions, replayed into
     * a step function, so the series begins when the user first owned
     * something: a position bought three months ago is absent from the first
     * nine months of a 1Y chart instead of being back-projected onto prices
     * nobody was exposed to.
     *
     * Each holding is evaluated on a shared timeline: at every timestamp any
     * holding has a bar for, a position contributes its most recent price at
     * or before that instant, multiplied by the units held at that instant.
     * A holding not yet owned contributes nothing, so the step up when it is
     * bought is a real one — under the old "today's units, full history" model
     * that step had to be suppressed by starting the chart only once every
     * position had a price, because it read as a gain that never happened.
     *
     * The obvious shortcut — bucket every bar by calendar day and add up what
     * lands in each bucket — is wrong for any intraday interval, and was: on a
     * 1D/30m range every bar falls in the same day, collapsing the whole series
     * to a single point ("not enough price history"), and on 1W/1h it summed
     * roughly seven hourly prices per position per day, inflating the portfolio
     * to several times its real value. Bucketing only looked right at daily
     * intervals, where one bar per day made the sum a no-op.
     */
    suspend fun portfolioValueSeries(range: String, interval: String): PortfolioSeries =
        withContext(Dispatchers.IO) {
            // One read for the whole log rather than a query per holding: the
            // chart already waits on a network round-trip per position.
            val txByHolding = transactionDao.observeAll().first().groupBy { it.holdingId }

            val allHoldings = holdingDao.observeAll().first()

            // A position sold down to zero still counts toward what the
            // portfolio was worth before it was sold, so it stays in the series
            // as long as the log says it was once held. Selling keeps the
            // holding row at zero units rather than deleting it, which is what
            // makes that recoverable.
            val holdings = allHoldings
                .filter { !it.ticker.isNullOrBlank() }
                .filter { it.units > 0 || !txByHolding[it.id].isNullOrEmpty() }
            if (holdings.isEmpty()) return@withContext PortfolioSeries()
            ensureRatesFor(holdings)

            // One request per SECURITY, fetched in parallel.
            //
            // Per HOLDING it was three identical requests for a fund held in a
            // TFSA, an FHSA and an RRSP — the same price history, three times,
            // on every range change. The rows still get their own unit
            // schedules (cost basis and transactions are per account); they
            // just share the bars. Keyed on the trimmed, uppercased ticker, so
            // "XEQT.TO" and "xeqt.to " are one security here as they are
            // everywhere else.
            val barsByTicker: Map<String, List<Pair<Long, Double>>> = coroutineScope {
                holdings.map { it.ticker!!.trim().uppercase() }.distinct().map { symbol ->
                    async {
                        symbol to runCatching {
                            YahooQuoteClient.fetchHistory(symbol, range, interval)
                        }.getOrDefault(emptyList())
                            .filter { it.second > 0 }
                            .sortedBy { it.first }
                    }
                }.awaitAll().toMap()
            }

            val fetched: List<Triple<HoldingEntity, List<Pair<Long, Double>>, UnitSchedule>> =
                holdings.map { h ->
                    val bars = barsByTicker[h.ticker!!.trim().uppercase()].orEmpty()
                    Triple(h, bars, unitScheduleFor(h, txByHolding[h.id].orEmpty(), bars))
                }

            val series = fetched.filter { it.second.isNotEmpty() }
            if (series.isEmpty()) return@withContext PortfolioSeries()

            // Everything the timeline cannot price, carried as a flat line.
            //
            // Two groups end up here, and dropping either is what made the
            // chart's Y axis disagree with the total on the card above it:
            //
            //  - Hand-priced holdings (seg funds, anything without a ticker).
            //    They were excluded from this method entirely, so a portfolio
            //    that was half segregated funds drew a line worth half of it.
            //  - Tickered holdings whose history request came back empty — a
            //    throttled endpoint, a symbol the provider does not chart. They
            //    were silently filtered out, taking their whole market value
            //    with them and leaving nothing on screen to explain the gap.
            //
            // Neither has a shape to contribute, but both have a VALUE, and a
            // flat contribution is far closer to the truth than zero.
            val flatValue =
                allHoldings.filter { it.ticker.isNullOrBlank() }.sumOf { valueOfHolding(it) } +
                    fetched.filter { it.second.isEmpty() }.sumOf { valueOfHolding(it.first) }

            // Start when the user first held anything, but never before the
            // earliest price we actually have.
            val ownedFrom = series.minOf { it.third.ownedFrom }
            val firstBar = series.minOf { it.second.first().first }
            val startTs = maxOf(ownedFrom, firstBar)

            val rawTimeline = series
                .flatMap { (_, bars, _) -> bars.map { it.first } }
                .filter { it >= startTs }
                .distinct()
                .sorted()

            // One point per calendar day, on any range whose bars are daily or
            // coarser.
            //
            // The timeline is the UNION of every security's bars, and the chart
            // sources do not agree on what time of day a daily bar is stamped —
            // midnight UTC against the opening bell — so one TSX holding and
            // one NYSE holding could put two points on the axis for the same
            // Tuesday: a sawtooth in the line, and a date under the middle of
            // the chart that belonged to neither bar.
            val intradayBars = interval.endsWith("m") || interval.endsWith("h")
            val timeline: List<Long> = if (intradayBars) rawTimeline else {
                val calendar = java.util.Calendar.getInstance()
                val latestPerDay = LinkedHashMap<Long, Long>()
                for (stamp in rawTimeline) {
                    calendar.timeInMillis = stamp
                    calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
                    calendar.set(java.util.Calendar.MINUTE, 0)
                    calendar.set(java.util.Calendar.SECOND, 0)
                    calendar.set(java.util.Calendar.MILLISECOND, 0)
                    val day = calendar.timeInMillis
                    val existing = latestPerDay[day]
                    if (existing == null || stamp > existing) latestPerDay[day] = stamp
                }
                latestPerDay.values.sorted()
            }
            if (timeline.size < 2) return@withContext PortfolioSeries()

            // Walk the shared timeline once, advancing a price cursor and a
            // units cursor per holding, so this stays linear rather than
            // re-scanning every series per point.
            val priceCursor = IntArray(series.size)
            val lastPrice = DoubleArray(series.size)
            val unitCursor = IntArray(series.size)
            val heldUnits = DoubleArray(series.size) { series[it].third.openingUnits }

            var points = timeline.map { ts ->
                var total = flatValue
                series.forEachIndexed { i, (holding, bars, schedule) ->
                    var p = priceCursor[i]
                    while (p < bars.size && bars[p].first <= ts) {
                        lastPrice[i] = bars[p].second
                        p++
                    }
                    priceCursor[i] = p

                    var u = unitCursor[i]
                    while (u < schedule.steps.size && schedule.steps[u].first <= ts) {
                        heldUnits[i] = schedule.steps[u].second
                        u++
                    }
                    unitCursor[i] = u

                    // Nothing held, or nothing priced yet, contributes nothing
                    // rather than a negative or a zero-price point.
                    if (heldUnits[i] > 0.0 && lastPrice[i] > 0.0) {
                        // Today's rate is applied across the whole series. A true
                        // historical reconstruction would need the rate on each
                        // date; this keeps the line's shape driven by the assets
                        // rather than by FX drift, which is the honest default for
                        // "what is my portfolio worth over time".
                        total += FxRates.convert(
                            lastPrice[i] * heldUnits[i], holding.currency, baseCurrency
                        )
                    }
                }
                ts to total
            }.toMutableList()

            // The last point is the portfolio as it stands RIGHT NOW.
            //
            // Everything above is built from closing bars, and the newest bar
            // can be up to a whole interval stale — half an hour on the 1D
            // chart, a full session on the longer ones. The card above this
            // chart shows the live total, so leaving the series to end on a
            // stale bar puts two different numbers for the same thing on one
            // screen, and the axis labels get read as wrong because next to the
            // headline figure they are.
            val liveTotal = allHoldings.sumOf { valueOfHolding(it) }
            var endsOnLiveTotal = false
            if (liveTotal > 0.0 && points.isNotEmpty()) {
                points[points.lastIndex] = points.last().first to liveTotal
                endsOnLiveTotal = true
            }

            // A weekend point that repeats Friday's number is a forward fill,
            // not a session.
            //
            // Some providers pad daily series out to the calendar, which puts a
            // Saturday on a chart of the Toronto exchange and lets the axis
            // label a Sunday as the middle of the week. Only the unchanged ones
            // go: a crypto holding really does move over a weekend, and that
            // point stays.
            if (!intradayBars && points.size > 2) {
                val calendar = java.util.Calendar.getInstance()
                val filtered = mutableListOf(points.first())
                for (index in 1 until points.size) {
                    val ts = points[index].first
                    val value = points[index].second
                    calendar.timeInMillis = ts
                    val day = calendar.get(java.util.Calendar.DAY_OF_WEEK)
                    val isWeekend = day == java.util.Calendar.SATURDAY ||
                        day == java.util.Calendar.SUNDAY
                    val unchanged = kotlin.math.abs(value - filtered.last().second) < 0.01
                    // The closing point always survives — it is the portfolio
                    // as it stands now, whatever day it falls on.
                    if (isWeekend && unchanged && index != points.lastIndex) continue
                    filtered.add(ts to value)
                }
                points = filtered
            }
            if (points.size < 2) return@withContext PortfolioSeries()

            // Money in and out WITHIN the drawn window, read off the UNIT
            // SCHEDULE rather than the transaction log.
            //
            // The log was the obvious source and the wrong one. A holding typed
            // straight in — a name, a ticker and a unit count — has no
            // transactions at all, yet its schedule still steps from zero to
            // its full size on the day it was added, so the line jumps by the
            // whole position with nothing in the log to explain it, and that
            // jump was being reported as growth.
            //
            // The schedule is the honest source because it is what the line is
            // drawn from: every step in it moves the line, whether a
            // transaction accounts for it or not.
            val windowStart = points.first().first
            // Up to NOW when the line ends on the live total, not up to the
            // last bar: the closing value holds every unit owned at this
            // moment, so a buy entered this evening is already in it while the
            // newest daily bar is stamped this morning.
            val windowEnd = if (endsOnLiveTotal) {
                maxOf(points.last().first, System.currentTimeMillis())
            } else {
                points.last().first
            }

            var netContributions = 0.0
            for ((holding, bars, schedule) in series) {
                // Where the position stood as the window opened. Steps at or
                // before that moment are already priced into the opening value,
                // and counting them would subtract the position twice.
                var units = schedule.openingUnits
                for (step in schedule.steps) {
                    if (step.first <= windowStart) units = step.second
                }
                for (step in schedule.steps) {
                    if (step.first <= windowStart || step.first > windowEnd) continue
                    val delta = step.second - units
                    units = step.second
                    // A millionth of a share is a rounding artefact, not a
                    // deposit.
                    if (kotlin.math.abs(delta) <= 1e-6) continue
                    val price = priceAt(bars, step.first)
                    if (price <= 0.0) continue
                    netContributions += FxRates.convert(
                        delta * price, holding.currency, baseCurrency
                    )
                }
            }

            // Hand-priced and unchartable holdings are deliberately NOT counted
            // here. They ride the window as a flat line at today's value, so one
            // added mid-window never produced a jump for a contribution to
            // explain; subtracting it would invent a loss its own size.

            PortfolioSeries(
                points = points,
                netContributions = netContributions,
                // `startTs` is where the clipping put the line and `firstBar`
                // is the oldest price we were given for the range that was
                // asked for. When the first is later, the chart shows less than
                // its own chip claims and the card above it has to say so.
                coversRequestedRange = startTs <= firstBar
            )
        }

    /**
     * The last close at or before [atMs], or the earliest one there is.
     *
     * A step's flow has to be valued at the price that applied when it
     * happened, not at today's: a buy made a month ago put in what the shares
     * cost a month ago, and valuing it at the current price would fold this
     * month's move into the deposit and back out of the gain.
     */
    private fun priceAt(bars: List<Pair<Long, Double>>, atMs: Long): Double {
        var result = bars.firstOrNull()?.second ?: 0.0
        for (bar in bars) {
            if (bar.first > atMs) break
            result = bar.second
        }
        return result
    }

    /**
     * How many units of a holding were held over time.
     *
     * [openingUnits] is in force from [ownedFrom] until the first entry in
     * [steps]; each step then gives the unit count from its own timestamp
     * onward. [ownedFrom] is what pulls the chart's start forward to the day
     * the user actually began holding.
     */
    private data class UnitSchedule(
        val ownedFrom: Long,
        val openingUnits: Double,
        val steps: List<Pair<Long, Double>>
    )

    /**
     * Replays a holding's transactions into a unit-count timeline.
     *
     * The holding row, not the transaction log, is the position of record, so
     * the replay is reconciled against [holding].units:
     *
     *  - No transactions at all — a position typed in directly — is a constant
     *    unit count from the day it was added to the app, and zero before it.
     *    That is what makes a portfolio started today draw from today rather
     *    than across a year of prices the user was never exposed to.
     *  - Transactions that net to the current position start the holding at
     *    its first buy, with zero units before that.
     *  - Transactions that net to LESS than the position leave a remainder
     *    that was evidently held before recording began; it becomes an opening
     *    balance carried from the start of the available price history.
     *  - Transactions that net to MORE than the position mean the log and the
     *    row disagree. Rather than draw a shape the data doesn't support, that
     *    holding falls back to its current unit count across the whole range.
     */
    private fun unitScheduleFor(
        holding: HoldingEntity,
        transactions: List<TransactionEntity>,
        bars: List<Pair<Long, Double>>
    ): UnitSchedule {
        val historyStart = bars.firstOrNull()?.first ?: 0L

        if (transactions.isEmpty()) {
            // The step, rather than an opening balance, is what keeps the
            // holding out of the chart before the date it was added.
            return UnitSchedule(
                ownedFrom = holding.createdAtMillis,
                openingUnits = 0.0,
                steps = listOf(holding.createdAtMillis to holding.units)
            )
        }

        val ordered = transactions.sortedWith(compareBy({ it.atMillis }, { it.id }))
        var running = 0.0
        val steps = ArrayList<Pair<Long, Double>>(ordered.size)
        for (tx in ordered) {
            running += when (tx.type) {
                TransactionType.BUY, TransactionType.DRIP -> tx.shares
                TransactionType.SELL -> -tx.shares
            }
            steps.add(tx.atMillis to running)
        }

        // Tolerance rather than equality: fractional DRIP units don't round-trip
        // through a Double exactly, and a millionth of a share is not a
        // difference anyone is charting.
        val unexplained = holding.units - running
        return when {
            unexplained > 1e-6 -> UnitSchedule(
                ownedFrom = historyStart,
                openingUnits = unexplained,
                steps = steps.map { (ts, units) -> ts to (units + unexplained) }
            )
            unexplained < -1e-6 -> UnitSchedule(
                ownedFrom = historyStart,
                openingUnits = holding.units,
                steps = emptyList()
            )
            else -> UnitSchedule(
                ownedFrom = ordered.first().atMillis,
                openingUnits = 0.0,
                steps = steps
            )
        }
    }

    /**
     * A holding's market value converted into [baseCurrency], for anything that
     * sums across the portfolio — totals, allocation weights, portfolio weight.
     */
    fun valueOfHolding(h: HoldingEntity): Double =
        FxRates.convert(nativeValueOfHolding(h), h.currency, baseCurrency)

    /**
     * The same value left in the holding's own currency, for per-holding
     * screens where converting would misreport what the position is worth
     * on its own statement.
     */
    fun nativeValueOfHolding(h: HoldingEntity): Double {
        val price = h.lastKnownPrice ?: h.manualPrice ?: 0.0
        return price * h.units
    }

    // ---- Dividend tracking (payments actually received) ----

    suspend fun addDividendPayment(
        holdingId: Long,
        paidAtMillis: Long,
        amount: Double,
        perUnit: Double?,
        currency: String,
        note: String?
    ) = withContext(Dispatchers.IO) {
        dividendDao.upsert(
            DividendPaymentEntity(
                holdingId = holdingId,
                paidAtMillis = paidAtMillis,
                amount = amount,
                perUnit = perUnit,
                currency = currency,
                note = note
            )
        )
    }

    suspend fun deleteDividendPayment(id: Long) = withContext(Dispatchers.IO) { dividendDao.deleteById(id) }

    fun observeDividendsForHolding(holdingId: Long): Flow<List<DividendPaymentEntity>> =
        dividendDao.observeForHolding(holdingId)

    suspend fun dividendTotalForHolding(holdingId: Long): Double =
        withContext(Dispatchers.IO) { dividendDao.totalForHolding(holdingId) }

    suspend fun dividendTotalBetween(startMillis: Long, endMillis: Long): Double =
        withContext(Dispatchers.IO) { dividendDao.totalBetween(startMillis, endMillis) }

    // ---- Upcoming dividends (estimated from Yahoo, not something recorded) ----

    suspend fun upcomingDividendFor(ticker: String): UpcomingDividend? =
        withContext(Dispatchers.IO) { YahooQuoteClient.fetchUpcomingDividend(ticker) }

    // ---- Transactions (buy / sell / DRIP) ----

    fun observeTransactions(): Flow<List<TransactionEntity>> = transactionDao.observeAll()

    fun observeTransactionsForHolding(holdingId: Long): Flow<List<TransactionEntity>> =
        transactionDao.observeForHolding(holdingId)

    /**
     * Records a transaction and applies it to the holding's position.
     *
     * Cost-basis treatment, which is the part worth being explicit about:
     *  - BUY  adds the cash spent to the basis.
     *  - SELL removes basis *proportionally* to the units sold, so the average
     *    cost per remaining unit is unchanged — selling doesn't make the rest
     *    of the position look cheaper or dearer than it was.
     *  - DRIP adds the reinvested cash to the basis. The dividend was taxable
     *    income, so treating it as money put into the position is what keeps
     *    yield-on-cost and total-return honest.
     *
     * Returns the new transaction's id.
     */
    suspend fun addTransaction(
        holdingId: Long,
        type: TransactionType,
        atMillis: Long,
        shares: Double,
        pricePerShare: Double,
        note: String? = null,
        sourceDividendId: Long? = null
    ): Long = withContext(Dispatchers.IO) {
        val holding = holdingDao.getById(holdingId)
            ?: return@withContext -1L
        if (shares <= 0.0) return@withContext -1L

        val cash = shares * pricePerShare
        val oldUnits = holding.units
        val oldBasis = holding.costBasis ?: 0.0

        val (newUnits, newBasis) = when (type) {
            TransactionType.BUY  -> (oldUnits + shares) to (oldBasis + cash)
            TransactionType.DRIP -> (oldUnits + shares) to (oldBasis + cash)
            TransactionType.SELL -> {
                val sold = shares.coerceAtMost(oldUnits)
                val remaining = (oldUnits - sold).coerceAtLeast(0.0)
                val basisOut = if (oldUnits > 0) oldBasis * (sold / oldUnits) else 0.0
                remaining to (oldBasis - basisOut).coerceAtLeast(0.0)
            }
        }

        holdingDao.update(
            holding.copy(
                units = newUnits,
                costBasis = if (newBasis > 0) newBasis else null
            )
        )

        transactionDao.insert(
            TransactionEntity(
                holdingId = holdingId,
                type = type,
                atMillis = atMillis,
                shares = shares,
                pricePerShare = pricePerShare,
                currency = holding.currency,
                note = note,
                sourceDividendId = sourceDividendId
            )
        )
    }

    /**
     * Removes a transaction and reverses its effect on the position, so a
     * mistyped entry can be undone rather than left to distort the basis.
     */
    suspend fun deleteTransaction(id: Long) = withContext(Dispatchers.IO) {
        val tx = transactionDao.getById(id) ?: return@withContext
        val holding = holdingDao.getById(tx.holdingId)
        if (holding != null) {
            val cash = tx.shares * tx.pricePerShare
            val oldBasis = holding.costBasis ?: 0.0
            val reverted = when (tx.type) {
                TransactionType.BUY, TransactionType.DRIP ->
                    (holding.units - tx.shares).coerceAtLeast(0.0) to
                        (oldBasis - cash).coerceAtLeast(0.0)
                TransactionType.SELL -> {
                    val restored = holding.units + tx.shares
                    // Re-add basis at the average the position carried when sold.
                    val perUnit = if (holding.units > 0) oldBasis / holding.units
                                  else tx.pricePerShare
                    restored to (oldBasis + perUnit * tx.shares)
                }
            }
            holdingDao.update(
                holding.copy(
                    units = reverted.first,
                    costBasis = reverted.second.takeIf { it > 0 }
                )
            )
        }
        transactionDao.deleteById(id)
    }

    /** True when this dividend payment has already been reinvested. */
    suspend fun isDividendReinvested(dividendId: Long): Boolean =
        withContext(Dispatchers.IO) { transactionDao.countForDividend(dividendId) > 0 }

    // ---- Watchlist (Dashboard's Quotes list — separate from owned holdings) ----

    fun observeWatchlist(): Flow<List<WatchlistItemEntity>> = watchlistDao.observeAll()

    suspend fun seedDefaultWatchlistIfEmpty(defaults: List<String>) = withContext(Dispatchers.IO) {
        if (watchlistDao.count() == 0) {
            val now = System.currentTimeMillis()
            watchlistDao.insertAll(defaults.mapIndexed { i, ticker ->
                WatchlistItemEntity(ticker = ticker, addedAtMillis = now + i)
            })
        }
    }

    suspend fun addToWatchlist(ticker: String): Boolean = withContext(Dispatchers.IO) {
        val clean = ticker.trim().uppercase()
        if (clean.isBlank()) return@withContext false
        if (watchlistDao.countForTicker(clean) > 0) return@withContext false
        watchlistDao.insert(WatchlistItemEntity(ticker = clean, addedAtMillis = System.currentTimeMillis()))
        true
    }

    suspend fun removeFromWatchlist(id: Long) = withContext(Dispatchers.IO) { watchlistDao.delete(id) }

    suspend fun getWatchlistItem(id: Long): WatchlistItemEntity? = withContext(Dispatchers.IO) { watchlistDao.getById(id) }

    /** Persists a fetched quote onto its watchlist row for instant next-launch paint. */
    suspend fun cacheWatchlistQuote(
        ticker: String,
        price: Double?,
        previousClose: Double?,
        name: String?,
        currency: String?
    ) = withContext(Dispatchers.IO) {
        watchlistDao.cacheQuote(
            ticker.trim().uppercase(), price, previousClose, name, currency,
            System.currentTimeMillis()
        )
    }

    suspend fun getWatchlistItemByTicker(ticker: String): WatchlistItemEntity? =
        withContext(Dispatchers.IO) { watchlistDao.getByTicker(ticker.trim().uppercase()) }

    suspend fun renameWatchlistItem(id: Long, name: String?) = withContext(Dispatchers.IO) {
        watchlistDao.updateCustomName(id, name?.trim()?.ifBlank { null })
    }

    suspend fun fetchQuote(ticker: String): Quote? = withContext(Dispatchers.IO) { YahooQuoteClient.fetch(ticker) }

    /**
     * A quote with every stat field populated, for the detail screens.
     *
     * [fetchQuote] answers from whichever provider replies first, and Finnhub —
     * the primary one — returns no 52-week range and no instrument type. The
     * visible effect was a US holding showing no 52-week band while a Canadian
     * one did, purely because the Canadian ticker fell through to Yahoo. This
     * tops the gaps up from Yahoo, at the cost of one extra request on a screen
     * the user opened deliberately.
     */
    suspend fun fetchQuoteDetailed(ticker: String): Quote? = withContext(Dispatchers.IO) {
        /**
         * Tops up the logo on a quote whose provider had none — Finnhub's
         * profiles are US-only, so a foreign listing's detail header showed a
         * letter avatar even where a mark was perfectly resolvable from the
         * security's name. Cached, so this is free after the first lookup.
         */
        fun withLogo(q: Quote): Quote =
            if (q.logoUrl != null) q
            else q.copy(logoUrl = YahooQuoteClient.fetchLogo(ticker, q.name))

        val primary = YahooQuoteClient.fetch(ticker)
            ?: return@withContext YahooQuoteClient.fetchFromYahoo(ticker)?.let(::withLogo)
        if (primary.fiftyTwoWeekHigh != null && primary.fiftyTwoWeekLow != null &&
            primary.instrumentType != null
        ) return@withContext withLogo(primary)

        val yahoo = YahooQuoteClient.fetchFromYahoo(ticker)
            ?: return@withContext withLogo(primary)
        // The live price stays the primary provider's — it is the fresher of
        // the two. Only the fields Finnhub never supplies are filled in.
        primary.copy(
            name = primary.name ?: yahoo.name,
            currency = primary.currency ?: yahoo.currency,
            fiftyTwoWeekHigh = primary.fiftyTwoWeekHigh ?: yahoo.fiftyTwoWeekHigh,
            fiftyTwoWeekLow = primary.fiftyTwoWeekLow ?: yahoo.fiftyTwoWeekLow,
            avgVolume3Month = primary.avgVolume3Month ?: yahoo.avgVolume3Month,
            volume = primary.volume ?: yahoo.volume,
            open = primary.open ?: yahoo.open,
            dayHigh = primary.dayHigh ?: yahoo.dayHigh,
            dayLow = primary.dayLow ?: yahoo.dayLow,
            previousClose = primary.previousClose ?: yahoo.previousClose,
            exchangeTimezone = primary.exchangeTimezone ?: yahoo.exchangeTimezone,
            instrumentType = primary.instrumentType ?: yahoo.instrumentType
        ).let(::withLogo)
    }

    suspend fun fetchHistory(ticker: String, range: String, interval: String): List<Pair<Long, Double>> =
        withContext(Dispatchers.IO) { YahooQuoteClient.fetchHistory(ticker, range, interval) }

    suspend fun fetchHistoryBars(ticker: String, range: String, interval: String): List<HistoryBar> =
        withContext(Dispatchers.IO) { YahooQuoteClient.fetchHistoryBars(ticker, range, interval) }

    suspend fun searchSymbols(query: String): List<SymbolSearchResult> =
        withContext(Dispatchers.IO) { YahooQuoteClient.searchSymbols(query) }

    /** Best-effort company logo for a search-result row or list item. See [Quote.logoUrl]. */
    suspend fun fetchLogo(ticker: String, name: String? = null): String? =
        withContext(Dispatchers.IO) { YahooQuoteClient.fetchLogo(ticker, name) }

    /** [homeCountry] is an ISO alpha-2 code; local outlets are layered onto the US core. */
    suspend fun fetchMarketNews(homeCountry: String? = null): List<ca.tristan.portfolio.net.NewsItem> =
        withContext(Dispatchers.IO) { YahooQuoteClient.fetchMarketNews(homeCountry) }

    suspend fun fetchHotStocks(): List<ca.tristan.portfolio.net.MarketMover> =
        withContext(Dispatchers.IO) { YahooQuoteClient.fetchHotStocks() }

    suspend fun fetchDividendNews(tickers: List<String>): List<ca.tristan.portfolio.net.NewsItem> =
        withContext(Dispatchers.IO) { YahooQuoteClient.fetchDividendNews(tickers) }
    // ── Cloud backup / restore ────────────────────────────────────────────

    /** Everything worth backing up, read once. */
    suspend fun backupPayload(): Map<String, List<Map<String, Any?>>> =
        withContext(Dispatchers.IO) {
            mapOf(
                "accounts" to accountDao.observeAll().first().map(CloudBackup::accountToMap),
                "holdings" to holdingDao.observeAll().first().map(CloudBackup::holdingToMap),
                "transactions" to transactionDao.observeAll().first().map(CloudBackup::transactionToMap),
                "dividends" to dividendDao.observeAll().first().map(CloudBackup::dividendToMap),
                "watchlist" to watchlistDao.observeAll().first().map(CloudBackup::watchlistToMap)
            )
        }

    /**
     * The same portfolio in the cross-platform shape, read in the same pass.
     *
     * Written BESIDE [backupPayload], never instead of it — see
     * [PortfolioInterchange] for why the native backup stays authoritative
     * until this format has been proven against a real portfolio on both
     * platforms.
     */
    suspend fun interchangePayload(
        // Cloud backup passes the SAME instant it stamped on the native
        // documents. Left as "now" otherwise (the file export caller), which
        // is never compared against anything.
        //
        // Without this, this function stamps its own fresh
        // System.currentTimeMillis() — a few milliseconds AFTER the native
        // docs' timestamp, since it is always written second in the same
        // backup pass. On restore that reads as "the interchange copy is
        // newer", so a plain same-device backup-then-restore would silently
        // prefer the lossier natural-key reconstruction (fresh UUIDs, no
        // per-holding notes/order/etc.) over the exact native document —
        // every single time, not just when an actual Android/iOS handoff
        // happened.
        writtenAt: Long = System.currentTimeMillis()
    ): Map<String, Any?> =
        withContext(Dispatchers.IO) {
            PortfolioInterchange.encode(
                accounts = accountDao.observeAll().first(),
                holdings = holdingDao.observeAll().first(),
                transactions = transactionDao.observeAll().first(),
                dividends = dividendDao.observeAll().first(),
                watchlist = watchlistDao.observeAll().first(),
                baseCurrency = baseCurrency,
                writtenAt = writtenAt
            )
        }

    /** How many holdings exist locally — what the restore confirmation warns about. */
    suspend fun localHoldingCount(): Int =
        withContext(Dispatchers.IO) { holdingDao.observeAll().first().size }

    data class RestoreResult(
        val accounts: Int,
        val holdings: Int,
        val transactions: Int,
        val dividends: Int,
        val watchlist: Int,
        /** Holdings whose cost basis had to be replayed from their transactions. */
        val rebuiltCostBasis: Int = 0
    ) {
        val isEmpty: Boolean get() = accounts + holdings + transactions + dividends + watchlist == 0
    }

    /**
     * Replaces this device's portfolio with a cloud backup.
     *
     * REPLACE, not merge, and deliberately so. Merging financial records means
     * deciding whether a buy of 10 shares in the backup is the same event as a
     * buy of 10 shares locally, and getting that wrong either double-counts a
     * position or silently drops a real trade. There is no field that settles
     * it — the ids are renumbered on every restore, and two genuine purchases
     * of the same size on the same day are indistinguishable from one. So the
     * user is asked, and the answer is all-or-nothing.
     *
     * Ids are the other reason this is not a straight insert. Every primary
     * key is autoGenerate, so restored rows get NEW ids, and every parent link
     * in the backup (holding→account, transaction→holding, dividend→holding)
     * points at an id from the old database. Each level is therefore inserted
     * first and its old→new mapping kept, so children can be re-pointed. A
     * child whose parent is missing from the backup is skipped rather than
     * attached to whatever holding happens to hold that id now — the foreign
     * keys cascade on delete, and a mis-pointed row would be deleted with the
     * wrong parent later.
     */
    suspend fun restoreFromBackup(
        accounts: List<Map<String, Any?>>,
        holdings: List<Map<String, Any?>>,
        transactions: List<Map<String, Any?>>,
        dividends: List<Map<String, Any?>>,
        watchlist: List<Map<String, Any?>>
    ): RestoreResult = withContext(Dispatchers.IO) {
        // Wipe first. Deleting accounts cascades to holdings, and holdings
        // cascade to transactions, dividends and price snapshots, so this one
        // loop clears the portfolio without needing a DAO per table.
        accountDao.observeAll().first().forEach { accountDao.delete(it.id) }
        watchlistDao.observeAll().first().forEach { watchlistDao.delete(it.id) }

        // 1. Accounts.
        val accountIds = HashMap<Long, Long>()
        var accountCount = 0
        for (row in accounts) {
            val entity = CloudBackup.accountFrom(row) ?: continue
            val newId = accountDao.upsert(entity)
            CloudBackup.oldId(row)?.let { accountIds[it] = newId }
            accountCount++
        }
        // A backup written before accounts were included, or one whose account
        // rows were all malformed, would otherwise drop every holding on the
        // floor for want of a parent.
        val fallbackAccountId = accountIds.values.firstOrNull()
            ?: accountDao.upsert(AccountEntity(id = 0, displayName = "Restored"))

        // 2. Holdings.
        val holdingIds = HashMap<Long, Long>()
        var holdingCount = 0
        for (row in holdings) {
            val accountId = CloudBackup.longOf(row["accountId"])?.let { accountIds[it] } ?: fallbackAccountId
            val entity = CloudBackup.holdingFrom(row, accountId) ?: continue
            val newId = holdingDao.upsert(entity)
            CloudBackup.oldId(row)?.let { holdingIds[it] = newId }
            holdingCount++
        }

        // 3. Transactions and dividends, re-pointed at the new holding ids.
        var txCount = 0
        for (row in transactions) {
            val oldHoldingId = CloudBackup.longOf(row["holdingId"]) ?: continue
            val holdingId = holdingIds[oldHoldingId] ?: continue
            val entity = CloudBackup.transactionFrom(row, holdingId) ?: continue
            transactionDao.insert(entity)
            txCount++
        }

        var divCount = 0
        for (row in dividends) {
            val oldHoldingId = CloudBackup.longOf(row["holdingId"]) ?: continue
            val holdingId = holdingIds[oldHoldingId] ?: continue
            val entity = CloudBackup.dividendFrom(row, holdingId) ?: continue
            dividendDao.upsert(entity)
            divCount++
        }

        var rebuiltBasis = 0
        // 3b. Rebuild any cost basis the backup didn't carry.
        //
        // costBasis is what Average Cost, Total Contributions, Price Return,
        // Total Return and Yield on Cost are ALL derived from — lose it and
        // five fields on the holding screen go to "—" at once, which is
        // exactly what a restore from an older backup produced.
        //
        // It does not have to be lost, because it is derivable: transactions
        // are the audit trail of how the position was built, and replaying
        // them applies the same arithmetic addTransaction used the first time.
        // Units are NOT recomputed — the backup's unit count is authoritative,
        // and a holding whose opening position pre-dates its transaction log
        // would otherwise be silently rewritten to a smaller one.
        for ((_, newHoldingId) in holdingIds) {
            val holding = holdingDao.getById(newHoldingId) ?: continue
            if (holding.costBasis != null) continue
            val rows = transactionDao.observeForHolding(newHoldingId).first()
            if (rows.isEmpty()) continue

            var units = 0.0
            var basis = 0.0
            for (tx in rows.sortedBy { it.atMillis }) {
                val cash = tx.shares * tx.pricePerShare
                when (tx.type) {
                    TransactionType.BUY, TransactionType.DRIP -> {
                        units += tx.shares
                        basis += cash
                    }
                    TransactionType.SELL -> {
                        val sold = tx.shares.coerceAtMost(units)
                        val basisOut = if (units > 0) basis * (sold / units) else 0.0
                        units = (units - sold).coerceAtLeast(0.0)
                        basis = (basis - basisOut).coerceAtLeast(0.0)
                    }
                }
            }
            if (basis > 0) {
                holdingDao.update(holding.copy(costBasis = basis))
                rebuiltBasis++
            }
        }

        // 4. Watchlist — no parent, so a straight insert.
        var watchCount = 0
        for (row in watchlist) {
            val entity = CloudBackup.watchlistFrom(row) ?: continue
            watchlistDao.insert(entity)
            watchCount++
        }

        RestoreResult(accountCount, holdingCount, txCount, divCount, watchCount, rebuiltBasis)
    }

}
