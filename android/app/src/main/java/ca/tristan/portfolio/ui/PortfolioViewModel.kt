package ca.tristan.portfolio.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ca.tristan.portfolio.data.PortfolioRepository
import ca.tristan.portfolio.data.PortfolioSeries
import ca.tristan.portfolio.data.QuotePreview
import ca.tristan.portfolio.data.db.AccountEntity
import ca.tristan.portfolio.data.db.AppDatabase
import ca.tristan.portfolio.data.db.DividendPaymentEntity
import ca.tristan.portfolio.data.db.HoldingEntity
import ca.tristan.portfolio.data.db.HoldingType
import ca.tristan.portfolio.data.db.WatchlistItemEntity
import ca.tristan.portfolio.net.HistoryBar
import ca.tristan.portfolio.net.MarketMover
import ca.tristan.portfolio.net.Quote
import ca.tristan.portfolio.net.SymbolSearchResult
import ca.tristan.portfolio.net.UpcomingDividend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * One slice of the allocation breakdown.
 *
 * This is per-HOLDING, despite the legacy name: the card is titled "Allocation
 * by Holdings" and grouping by account produced a single 100% slice for anyone
 * keeping everything in one account, which told the user nothing.
 * [holdings] keeps the old shape so existing call sites still compile.
 */
data class AccountAllocation(
    val account: AccountEntity,
    val value: Double,
    val holdings: List<HoldingEntity>,
    /** Label for this slice — the holding's ticker, or its name when untickered. */
    val label: String = account.displayName
)

/** Tracks progress of the dividend auto-import operation shown in DividendsScreen. */
sealed class ImportState {
    object Idle : ImportState()
    data class Running(val current: Int, val total: Int, val ticker: String) : ImportState()
    data class Done(val imported: Int, val skipped: Int) : ImportState()
    data class Error(val message: String) : ImportState()
}

data class DashboardState(
    val totalValue: Double = 0.0,
    val dayChangeAbsolute: Double = 0.0,
    val dayChangePercent: Double = 0.0,
    val allocations: List<AccountAllocation> = emptyList(),
    val dividendsThisMonth: Double = 0.0,
    val dividendsThisYear: Double = 0.0,
    val dividendsAllTime: Double = 0.0,
    /** Currency [totalValue], [dayChangeAbsolute] and the allocations are in. */
    val baseCurrency: String = "CAD",
    /**
     * Currencies held that could not be converted, because no rate has been
     * fetched yet. Their holdings are counted at face value, so the total is
     * understated or overstated until a rate lands — the UI says so rather
     * than presenting a number it can't stand behind.
     */
    val unconvertedCurrencies: List<String> = emptyList()
)

// Default tickers seeded into the Dashboard the first time the app runs.
// Indices/stocks go to the "Active Stocks" tab; tickers containing "-USD"
// go to the "Cryptocurrencies" tab. The user can add or remove any of these.
private val DEFAULT_TICKERS = listOf(
    // Stocks — Markets tab
    "AAPL", "MSFT", "NVDA", "TSLA",
    // Crypto — Cryptocurrencies tab
    "BTC-USD", "ETH-USD", "SOL-USD", "DOGE-USD"
)

/**
 * Seed list: the market indices for the user's region (US benchmarks first,
 * then their home market, then the other majors) followed by the default
 * stocks and crypto.
 */
private fun defaultWatchlist(homeCountry: String?): List<String> =
    ca.tristan.portfolio.data.MarketIndices.symbolsFor(homeCountry) + DEFAULT_TICKERS

data class QuoteRow(val ticker: String, val watchlistId: Long, val quote: Quote?, val loading: Boolean)

/**
 * A holding's next expected distribution, plus the record it was inferred from.
 *
 * [history] is the fund's own per-unit payment record, carried alongside the
 * projection because the forward charts need the seasonal shape — a quarterly
 * ETF that pays a token Q1 and a large Q4 cannot be projected from a single
 * per-payment number. It is already fetched to work out [info], so keeping it
 * costs nothing and saves every consumer a second round trip.
 */
data class UpcomingDividendRow(
    /** The largest slice — what the card opens, and what names the payment. */
    val holding: HoldingEntity,
    val info: UpcomingDividend,
    val history: List<Pair<Long, Double>> = emptyList(),
    /**
     * Every account's row for this security, largest first. A payment lands in
     * each of them, so the card shows one combined amount and breaks it back
     * down underneath.
     */
    val rows: List<HoldingEntity> = emptyList()
) {
    /** Units the payment is actually paid on, across every account. */
    val units: Double get() = if (rows.isEmpty()) holding.units else rows.sumOf { it.units }

    val isSplit: Boolean get() = rows.size > 1
}

/**
 * Assumed annual growth in distributions per unit, used by every forward
 * projection so the charts agree with each other.
 *
 * A flat assumption rather than a per-holding trend: a fund's own five-year
 * dividend CAGR is far too noisy to extrapolate a decade from — a single
 * special distribution can put it above 40 % — and compounding that produces
 * a projection nobody should plan against.
 */
// Superseded by DividendForecast.measuredGrowth, which reads each fund's own
// year-over-year growth from its payment record rather than assuming one rate
// for every holding. Passing null asks for that; DividendForecast keeps a 3%
// fallback for funds without enough history to measure.
private val DISTRIBUTION_GROWTH_RATE: Double? = null

class PortfolioViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.get(application)
    val repository = PortfolioRepository(db.accountDao(), db.holdingDao(), db.priceSnapshotDao(), db.dividendDao(), db.watchlistDao(), db.transactionDao(), db.alertDao())

    // ── Dividend Goal (persisted in SharedPreferences) ────────────────────────
    private val prefs = application.getSharedPreferences("wealthboard_prefs", android.content.Context.MODE_PRIVATE)

    // ── Base currency (persisted) ─────────────────────────────────────────────
    /**
     * The currency portfolio-wide totals are reported in.
     *
     * Defaults to the device's own currency so a Canadian user sees CAD and a
     * US user sees USD without configuring anything, falling back to CAD when
     * the locale has no sensible currency.
     */
    private fun deviceCurrency(): String = runCatching {
        java.util.Currency.getInstance(java.util.Locale.getDefault()).currencyCode
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: "CAD"

    private val _baseCurrency = MutableStateFlow(
        prefs.getString("base_currency", null)?.takeIf { it.isNotBlank() } ?: deviceCurrency()
    )
    val baseCurrency: StateFlow<String> = _baseCurrency

    fun setBaseCurrency(code: String) {
        val c = code.uppercase().takeIf { it.isNotBlank() } ?: return
        if (c == _baseCurrency.value) return
        prefs.edit().putString("base_currency", c).apply()
        _baseCurrency.value = c
        repository.baseCurrency = c
        // Re-price immediately: totals, the day change and the value chart are
        // all denominated in the currency that just changed.
        refreshPortfolio()
    }

    // ── Transactions & DRIP ──────────────────────────────────────────────────

    val transactions: StateFlow<List<ca.tristan.portfolio.data.db.TransactionEntity>> =
        repository.observeTransactions()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ── Appearance ───────────────────────────────────────────────────────────

    private val _themeMode = MutableStateFlow(
        ca.tristan.portfolio.ui.theme.ThemeMode.from(
            prefs.getString(ca.tristan.portfolio.ui.theme.ThemeMode.PREF_KEY, null)
        )
    )
    /** Collected by MainActivity, so a change repaints the app immediately. */
    val themeMode: StateFlow<ca.tristan.portfolio.ui.theme.ThemeMode> = _themeMode

    fun setThemeMode(mode: ca.tristan.portfolio.ui.theme.ThemeMode) {
        prefs.edit()
            .putString(ca.tristan.portfolio.ui.theme.ThemeMode.PREF_KEY, mode.name)
            .apply()
        _themeMode.value = mode
    }

    /** Whether dividends should prompt to be reinvested. Off by default. */
    private val _dripEnabled = MutableStateFlow(prefs.getBoolean("drip_enabled", false))
    val dripEnabled: StateFlow<Boolean> = _dripEnabled

    fun setDripEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("drip_enabled", enabled).apply()
        _dripEnabled.value = enabled
    }

    fun addTransaction(
        holdingId: Long,
        type: ca.tristan.portfolio.data.db.TransactionType,
        atMillis: Long,
        shares: Double,
        pricePerShare: Double,
        note: String? = null,
        sourceDividendId: Long? = null
    ) = viewModelScope.launch {
        repository.addTransaction(
            holdingId, type, atMillis, shares, pricePerShare, note, sourceDividendId
        )
        repository.refreshQuote(holdingId)
    }

    /**
     * Creates a brand-new holding at zero units, then records the opening
     * transaction against it — so units and cost basis are always produced by
     * the same code path rather than being set twice in two different ways.
     */
    fun createHoldingThenTransact(
        accountId: Long,
        ticker: String,
        name: String,
        type: ca.tristan.portfolio.data.db.HoldingType,
        txType: ca.tristan.portfolio.data.db.TransactionType,
        atMillis: Long,
        shares: Double,
        pricePerShare: Double
    ) = viewModelScope.launch {
        val id = repository.saveHolding(
            existingId = null,
            accountId = accountId,
            name = name,
            ticker = ticker,
            type = type,
            units = 0.0,
            manualPrice = null,
            currency = repository.currencyForTicker(ticker),
            costBasis = null
        )
        repository.addTransaction(id, txType, atMillis, shares, pricePerShare)
        repository.refreshQuote(id)
    }

    /**
     * Starts the same security in another account, then records the trade
     * against it.
     *
     * Buying XEQT in an FHSA when it is already held in a TFSA is a new row —
     * cost basis and tax treatment are per account — but it is not a new
     * security, so nothing about it should be re-derived or re-guessed. Name,
     * ticker, type and currency are copied from the row that already exists;
     * only the account and the units differ.
     */
    fun addSliceThenTransact(
        fromHoldingId: Long,
        accountId: Long,
        txType: ca.tristan.portfolio.data.db.TransactionType,
        atMillis: Long,
        shares: Double,
        pricePerShare: Double
    ) = viewModelScope.launch {
        val source = holdings.value.firstOrNull { it.id == fromHoldingId } ?: return@launch
        val id = repository.saveHolding(
            existingId = null,
            accountId = accountId,
            name = source.name,
            ticker = source.ticker,
            type = source.type,
            units = 0.0,
            manualPrice = null,
            currency = source.currency,
            costBasis = null
        )
        repository.addTransaction(id, txType, atMillis, shares, pricePerShare)
        repository.refreshQuote(id)
    }

    fun deleteTransaction(id: Long) = viewModelScope.launch {
        repository.deleteTransaction(id)
    }

    /** Live price for the Add Transaction form, so the user isn't guessing. */
    suspend fun priceForHolding(holdingId: Long): Double? {
        val h = holdings.value.firstOrNull { it.id == holdingId } ?: return null
        val ticker = h.ticker
        return if (!ticker.isNullOrBlank()) {
            repository.fetchQuote(ticker)?.price ?: h.lastKnownPrice ?: h.manualPrice
        } else h.lastKnownPrice ?: h.manualPrice
    }

    /** Live price for any ticker symbol — used for newly searched tickers on the Add Transaction form. */
    suspend fun fetchPriceForTicker(ticker: String): Double? =
        repository.fetchQuote(ticker)?.price

    /**
     * Full quote for a ticker the user is about to buy.
     *
     * The Add Transaction form needs more than the price: it has to label the
     * price field with the currency the listing actually trades in. It used to
     * assume CAD for anything not already held, so searching AAPL — a US
     * listing — offered "Price per share, CAD" under a market price quoted in
     * US dollars, and recorded the cost basis as though those were Canadian.
     */
    suspend fun quoteForTicker(ticker: String): Quote? =
        repository.fetchQuoteDetailed(ticker)

    /**
     * Best-effort logo for [ticker]; null when nothing can be resolved, in
     * which case the caller draws its initial avatar.
     *
     * Pass [name] whenever the caller already has the security's name — a
     * fund's issuer is readable straight off it, and that is the only path
     * that gives non-US listings a logo without a per-ticker request.
     */
    suspend fun logoUrlFor(ticker: String, name: String? = null): String? =
        repository.fetchLogo(ticker, name)

    /** Currency a listing trades in, inferred from its exchange suffix. */
    fun currencyForTicker(ticker: String?): String = repository.currencyForTicker(ticker)

    /**
     * [amount] of [from] expressed in the user's reporting currency, or null
     * when no rate has been fetched yet (in which case the UI shows nothing
     * rather than an unconverted number wearing the wrong symbol).
     */
    fun convertedToBase(amount: Double, from: String): Double? {
        val base = _baseCurrency.value
        if (from.equals(base, ignoreCase = true)) return null
        val rate = ca.tristan.portfolio.data.FxRates.rateOrNull(from, base) ?: return null
        return amount * rate
    }

    /** The rate used by [convertedToBase], for showing the user the arithmetic. */
    fun rateToBase(from: String): Double? {
        val base = _baseCurrency.value
        if (from.equals(base, ignoreCase = true)) return null
        return ca.tristan.portfolio.data.FxRates.rateOrNull(from, base)
    }

    /** Makes sure a rate exists for [currency] before the form tries to show one. */
    fun ensureRateFor(currency: String) = viewModelScope.launch {
        ca.tristan.portfolio.data.FxRates.refresh(listOf(currency), _baseCurrency.value)
        // Nudge recomposition: the rate cache is not itself observable, so the
        // form re-reads it when this flips.
        _fxTick.value = _fxTick.value + 1
    }

    /** Same as [ensureRateFor], for a whole set of currencies at once. */
    fun ensureRatesFor(currencies: Collection<String>) = viewModelScope.launch {
        ca.tristan.portfolio.data.FxRates.refresh(currencies, _baseCurrency.value)
        _fxTick.value = _fxTick.value + 1
    }

    /**
     * [amount] recorded in [from], expressed in the reporting currency.
     *
     * Unlike [convertedToBase], this never returns null: with no rate fetched
     * yet it hands back [amount] unconverted (see [ca.tristan.portfolio.data
     * .FxRates.convert]), which is what a running total across currencies
     * needs — a total that goes blank the instant one rate is missing is a
     * worse answer than one that is briefly a little off.
     */
    fun amountInBase(amount: Double, from: String): Double =
        ca.tristan.portfolio.data.FxRates.convert(
            amount, from.uppercase().ifBlank { "CAD" }, _baseCurrency.value
        )

    private val _fxTick = MutableStateFlow(0)
    /** Increments whenever a new FX rate lands, so forms can recompose. */
    val fxTick: StateFlow<Int> = _fxTick

    // ── Home market (Markets tab ordering) ───────────────────────────────────
    // Auto-detected from the device region unless the user picks one in
    // Settings. Stored as an ISO alpha-2 code; null/absent means "auto".
    private val _homeCountry = MutableStateFlow(
        prefs.getString("home_country", null)
            ?: ca.tristan.portfolio.data.MarketIndices.deviceCountry()
    )
    val homeCountry: StateFlow<String> = _homeCountry

    /** True when the home market is following the device region rather than a manual pick. */
    val isHomeCountryAuto: Boolean get() = prefs.getString("home_country", null) == null

    /** Sets the home market. Pass null to go back to auto-detection. */
    fun setHomeCountry(code: String?) {
        prefs.edit().apply {
            if (code == null) remove("home_country") else putString("home_country", code.uppercase())
        }.apply()
        _homeCountry.value = code?.uppercase()
            ?: ca.tristan.portfolio.data.MarketIndices.deviceCountry()
        // Make sure the newly-relevant index is actually in the watchlist.
        viewModelScope.launch {
            ca.tristan.portfolio.data.MarketIndices.symbolsFor(_homeCountry.value)
                .forEach { repository.addToWatchlist(it) }
            refreshWatchlistQuotes()
        }
    }

    /** Ordered indices for the Markets tab header block. */
    val marketIndices: StateFlow<List<ca.tristan.portfolio.data.MarketIndex>> =
        _homeCountry
            .map { ca.tristan.portfolio.data.MarketIndices.indexesFor(it) }
            .stateIn(
                viewModelScope,
                kotlinx.coroutines.flow.SharingStarted.Eagerly,
                ca.tristan.portfolio.data.MarketIndices.indexesFor(
                    prefs.getString("home_country", null)
                )
            )
    private val _dividendGoal = MutableStateFlow(prefs.getFloat("dividend_goal_monthly", 5000f).toDouble())
    val dividendGoal: StateFlow<Double> = _dividendGoal

    fun setDividendGoal(goal: Double) {
        prefs.edit().putFloat("dividend_goal_monthly", goal.toFloat()).apply()
        _dividendGoal.value = goal
    }

    val accounts: StateFlow<List<AccountEntity>> =
        repository.observeAccounts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val holdings: StateFlow<List<HoldingEntity>> =
        repository.observeHoldings().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ── Alerts ────────────────────────────────────────────────────────────

    val alerts: StateFlow<List<ca.tristan.portfolio.data.db.AlertEntity>> =
        repository.observeAlerts()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun addAlert(
        ticker: String,
        kind: ca.tristan.portfolio.data.db.AlertKind,
        threshold: Double
    ) = viewModelScope.launch {
        // Premium-gated, alongside the screen that offers it and the worker
        // that fires it. Three checks for one rule is not redundancy here:
        // each is the last line of defence for a different entry point.
        if (!ca.tristan.portfolio.billing.Subscriptions.isPremium.value) return@launch
        repository.addAlert(ticker, kind, threshold)
    }

    fun setAlertEnabled(id: Long, enabled: Boolean) = viewModelScope.launch {
        repository.setAlertEnabled(id, enabled)
    }

    fun deleteAlert(id: Long) = viewModelScope.launch {
        repository.deleteAlert(id)
    }

    /**
     * Runs an alert pass right now, and reports how many fired.
     *
     * Developer-only — the caller passes its own `BuildConfig.DEBUG`, same
     * shape as `Subscriptions.setDebugPremiumOverride`, so a release build has
     * no path to it.
     *
     * Worth having rather than waiting for the worker: `SyncWorker` runs on a
     * 30–45 minute flex window AND skips entirely when markets are shut, so
     * testing an alert on a Sunday evening means waiting until Monday to find
     * out whether it works. This bypasses both, evaluating the same engine the
     * worker uses so a pass here proves the real path.
     */
    fun debugRunAlertCheck(
        isDebugBuild: Boolean,
        onDone: (
            fired: Int,
            wasPremium: Boolean,
            delivery: ca.tristan.portfolio.alerts.AlertNotifier.Deliverability,
            detail: String
        ) -> Unit
    ) = viewModelScope.launch {
        if (!isDebugBuild) return@launch

        // Reported rather than guarded on, so the row can say WHY nothing
        // arrived. `post` drops silently without permission — and, separately,
        // when the channel itself is blocked — which made a readout of "1
        // alert fired — check your notifications" actively misleading in
        // exactly the case the user most needed a right answer.
        val delivery = ca.tristan.portfolio.alerts.AlertNotifier
            .deliverability(getApplication())

        val premium = ca.tristan.portfolio.billing.Subscriptions.isPremium.value
        if (!premium) {
            onDone(0, false, delivery, "")
            return@launch
        }
        // Explained BEFORE evaluating, so the readout describes the state that
        // produced the result rather than the latched state left behind by it.
        val detail = runCatching {
            ca.tristan.portfolio.alerts.AlertEngine.explain(db.alertDao(), repository)
        }.getOrDefault(emptyList()).joinToString("  •  ")

        val firings = ca.tristan.portfolio.alerts.AlertEngine.evaluate(
            db.alertDao(), repository
        )
        for (firing in firings) {
            ca.tristan.portfolio.alerts.AlertNotifier.post(getApplication(), firing)
        }
        onDone(firings.size, true, delivery, detail)
    }

    /**
     * Currencies the user actually holds, for the base-currency picker.
     *
     * Declared after [holdings] on purpose — it derives from that flow, and a
     * Kotlin property can't read one initialised further down the class.
     */
    val heldCurrencies: StateFlow<List<String>> = holdings
        .map { list ->
            list.map { it.currency.uppercase().ifBlank { "CAD" } }
                .distinct()
                .sorted()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val dividends: StateFlow<List<DividendPaymentEntity>> =
        repository.observeDividends().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val watchlist: StateFlow<List<WatchlistItemEntity>> =
        repository.observeWatchlist().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _quotes = MutableStateFlow<Map<String, Quote?>>(emptyMap())
    val quotes: StateFlow<Map<String, Quote?>> = _quotes

    // Mini 1D sparkline data per ticker (list of closing prices for the day).
    private val _sparklines = MutableStateFlow<Map<String, List<Float>>>(emptyMap())
    val sparklines: StateFlow<Map<String, List<Float>>> = _sparklines

    // Market news headlines fetched from Yahoo Finance.
    private val _marketNews = MutableStateFlow<List<ca.tristan.portfolio.net.NewsItem>>(emptyList())
    val marketNews: StateFlow<List<ca.tristan.portfolio.net.NewsItem>> = _marketNews

    private val _upcomingDividends = MutableStateFlow<List<UpcomingDividendRow>>(emptyList())
    val upcomingDividends: StateFlow<List<UpcomingDividendRow>> = _upcomingDividends

    // Import progress for dividend auto-import shown in DividendsScreen.
    private val _upcomingProgress = MutableStateFlow<ImportState>(ImportState.Idle)
    /** Progress of the upcoming-dividends fetch, for the spinner on that card. */
    val upcomingProgress: StateFlow<ImportState> = _upcomingProgress

    // Market-wide Top Gainers and Top Losers for the Market screen.
    private val _topGainers = MutableStateFlow<List<MarketMover>>(emptyList())
    val topGainers: StateFlow<List<MarketMover>> = _topGainers

    private val _topLosers = MutableStateFlow<List<MarketMover>>(emptyList())
    val topLosers: StateFlow<List<MarketMover>> = _topLosers

    /**
     * Today's hot stocks — the merged, heat-ranked list behind the Dashboard's
     * "Hot Stocks" tab. Whatever the market is actually trading today, whether
     * it is up or down.
     */
    private val _hotStocks = MutableStateFlow<List<MarketMover>>(emptyList())
    val hotStocks: StateFlow<List<MarketMover>> = _hotStocks

    // Mini sparklines for market mover rows (Top Gainers / Top Losers tabs).
    // Secondary quote per watchlist ticker: after-hours for stocks/ETFs, the
    // index future for cash indices. Populated after the main prices land, so
    // the list never waits on it.
    private val _extendedQuotes =
        MutableStateFlow<Map<String, ca.tristan.portfolio.net.ExtendedQuote>>(emptyMap())
    val extendedQuotes: StateFlow<Map<String, ca.tristan.portfolio.net.ExtendedQuote>> = _extendedQuotes

    private val _moverSparklines = MutableStateFlow<Map<String, List<Float>>>(emptyMap())
    val moverSparklines: StateFlow<Map<String, List<Float>>> = _moverSparklines

    // Sync progress for Nasdaq upcoming dividends.
    private val _syncProgress = MutableStateFlow<ImportState>(ImportState.Idle)
    val syncProgress: StateFlow<ImportState> = _syncProgress

    // Live portfolio day-change (sum of (price - prevClose) * units) together
    // with the market value it was measured across, updated on refresh.
    //
    // The pair travels together because the percentage is one divided by the
    // other. Holding only the amount meant it got divided by the whole
    // portfolio total, so a holding whose quote failed left the numerator
    // while staying in the denominator.
    private val _liveDayChange =
        MutableStateFlow(ca.tristan.portfolio.data.PortfolioRepository.DayChange(0.0, 0.0))

    init {
        // Seed the FX cache from disk and tell the repository which currency
        // to report in, before anything reads a portfolio total. A cold start
        // would otherwise sum unconverted for the first second or two, and the
        // headline number would visibly jump once the first rate arrived.
        ca.tristan.portfolio.data.FxRates.attach(prefs)
        ca.tristan.portfolio.data.DiskCache.attach(application.cacheDir)
        repository.baseCurrency = _baseCurrency.value

        viewModelScope.launch {
            repository.seedDefaultWatchlistIfEmpty(defaultWatchlist(_homeCountry.value))
            // Reconcile: people who installed before the world-markets list
            // existed only ever got the original seed, so top up anything
            // missing. addToWatchlist is a no-op for tickers already present.
            ca.tristan.portfolio.data.MarketIndices.symbolsFor(_homeCountry.value)
                .forEach { repository.addToWatchlist(it) }
            // Wait for Room to actually emit the seeded list before fetching —
            // reading watchlist.value straight away yields an empty list.
            // Bounded, so a user who has deleted every row doesn't leave this
            // coroutine suspended for the ViewModel's lifetime.
            val ready = kotlinx.coroutines.withTimeoutOrNull(5_000) {
                watchlist.first { it.isNotEmpty() }
            }
            if (!ready.isNullOrEmpty()) refreshWatchlistQuotes()
        }

        // Paint the Markets tab from the last cached quotes as soon as the
        // watchlist loads, then refresh over the network. Without this the
        // list shows a column of spinners on every cold start, because the
        // in-memory quote map begins empty and the network is the only source.
        viewModelScope.launch {
            watchlist.collect { items ->
                if (items.isEmpty()) return@collect
                val seeded = _quotes.value.toMutableMap()
                var added = false
                for (item in items) {
                    if (seeded[item.ticker] != null) continue          // live value wins
                    val price = item.cachedPrice ?: continue
                    seeded[item.ticker] = Quote(
                        price = price,
                        name = item.cachedName,
                        previousClose = item.cachedPreviousClose,
                        currency = item.cachedCurrency
                    )
                    added = true
                }
                if (added) _quotes.value = seeded
            }
        }
    }

    val dashboard: StateFlow<DashboardState> = combine(
        accounts, holdings, dividends, _liveDayChange, _baseCurrency
    ) { accts, holds, divs, dayChange, base ->
        // One slice per holding, largest first, so the pie actually shows the
        // portfolio's composition rather than one slice per account.
        // One slice per SECURITY, not per holding row.
        //
        // The same fund held in three accounts is three HoldingEntity rows —
        // it has to be, because cost basis and tax treatment differ per
        // account. But it is still one position in the portfolio, and slicing
        // the pie per row drew "XEQT.TO 64%, XEQT.TO 22%, XEQT.TO 13%", which
        // says nothing about diversification and looks broken.
        //
        // Grouped on the ticker where there is one, and on the name where there
        // isn't — two untickered holdings with the same name are the same thing
        // in different accounts, while two with different names are not.
        val allocations = holds
            .groupBy { h -> h.ticker?.takeIf { it.isNotBlank() }?.uppercase() ?: h.name }
            .map { (key, rows) ->
                val first = rows.first()
                val acct = accts.firstOrNull { it.id == first.accountId }
                    ?: AccountEntity(id = first.accountId, displayName = first.name)
                AccountAllocation(
                    account = acct,
                    value = rows.sumOf { repository.valueOfHolding(it) },
                    holdings = rows,
                    label = key
                )
            }
            .filter { it.value > 0 }
            .sortedByDescending { it.value }
        val total = allocations.sumOf { it.value }

        val cal = Calendar.getInstance()
        cal.set(Calendar.DAY_OF_MONTH, 1); cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0)
        val monthStart = cal.timeInMillis
        cal.set(Calendar.DAY_OF_YEAR, 1)
        val yearStart = cal.timeInMillis
        val now = System.currentTimeMillis()

        // Converted per payment before being added up. These totals sit on a
        // card labelled with the base currency, and a US distribution summed
        // into a Canadian total at face value is not money in either currency —
        // the same bug FxRates was added to fix for portfolio value, which the
        // dividend figures beside it had never been put through.
        fun inBase(d: ca.tristan.portfolio.data.db.DividendPaymentEntity): Double =
            ca.tristan.portfolio.data.FxRates.convert(
                d.amount, d.currency.uppercase().ifBlank { "CAD" }, base
            )

        val divsThisMonth = divs.filter { it.paidAtMillis in monthStart..now }.sumOf { inBase(it) }
        val divsThisYear = divs.filter { it.paidAtMillis in yearStart..now }.sumOf { inBase(it) }
        val divsAllTime = divs.sumOf { inBase(it) }

        // Currencies held that we have no rate for yet. Their holdings are in
        // the total at face value, so the figure is wrong until a rate lands —
        // the Total value card says so rather than quietly overstating things.
        val unconverted = holds
            .map { it.currency.uppercase().ifBlank { "CAD" } }
            .distinct()
            .filter { !ca.tristan.portfolio.data.FxRates.hasRate(it, base) }
            .sorted()

        DashboardState(
            totalValue = total,
            dayChangeAbsolute = dayChange.amount,
            dayChangePercent = if (dayChange.measuredValue > 0)
                (dayChange.amount / dayChange.measuredValue) * 100.0 else 0.0,
            allocations = allocations,
            baseCurrency = base,
            unconvertedCurrencies = unconverted,
            dividendsThisMonth = divsThisMonth,
            dividendsThisYear = divsThisYear,
            dividendsAllTime = divsAllTime
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DashboardState())

    fun deleteAccount(id: Long) = viewModelScope.launch { repository.deleteAccount(id) }
    fun deleteHolding(id: Long) = viewModelScope.launch { repository.deleteHolding(id) }

    /**
     * The account a new holding should default to, or null when there are none.
     *
     * This used to get-or-CREATE an account called "My holdings" with no tax
     * treatment set. That default was the problem: a holding filed into it
     * silently produced no tax information at all, which on screen is
     * indistinguishable from "there is no tax to report". Nothing is created
     * here now — the transaction form asks, and will not save until it has an
     * answer.
     */
    fun defaultAccount(): Long? = accounts.value.firstOrNull()?.id

    // ── Positions ────────────────────────────────────────────────────────
    //
    // Three rows in Room are one thing the user owns. Everything that lists
    // holdings goes through these, so the same fund never appears three times.

    /** Every stored row for one security, largest slice first. */
    fun slicesForSecurity(key: String): List<AccountPosition> {
        val q = quotes.value
        val accs = accounts.value
        return holdings.value
            .filter { it.securityKey == key }
            .map { row ->
                val quote = row.ticker?.let { q[it] }
                AccountPosition(
                    holding = row,
                    account = accs.firstOrNull { it.id == row.accountId },
                    units = row.units,
                    price = quote?.price ?: row.lastKnownPrice ?: row.manualPrice ?: 0.0,
                    previousClose = quote?.previousClose,
                    costBasis = row.costBasis
                )
            }
            .sortedByDescending { it.marketValue }
    }

    fun positionForSecurity(key: String): SecurityPosition? =
        slicesForSecurity(key).takeIf { it.isNotEmpty() }?.let { SecurityPosition(key, it) }

    fun positionFor(holding: HoldingEntity): SecurityPosition? =
        positionForSecurity(holding.securityKey)

    /** One entry per security the user owns, largest first. */
    fun positions(): List<SecurityPosition> =
        holdings.value
            .map { it.securityKey }
            .distinct()
            .mapNotNull { positionForSecurity(it) }
            .sortedByDescending { it.marketValue }

    fun createAccount(
        name: String,
        taxTreatment: ca.tristan.portfolio.data.db.TaxTreatment? = null,
        onDone: (Long) -> Unit = {}
    ) = viewModelScope.launch {
        // Premium-gated past the free limit — AccountPicker already refuses
        // at the chip, but this is the call every entry point goes through,
        // so the limit lives here too rather than only in whichever screen
        // remembered to check. A restore from backup deliberately does NOT
        // come through here: data someone already entered is theirs, and
        // silently dropping accounts on restore would be data loss dressed
        // up as a paywall.
        if (!ca.tristan.portfolio.billing.PremiumLimits.canCreateAccount(
                accounts.value.size,
                ca.tristan.portfolio.billing.Subscriptions.isPremium.value
            )
        ) {
            return@launch
        }
        val id = repository.upsertAccount(
            AccountEntity(displayName = name, taxTreatment = taxTreatment)
        )
        onDone(id)
    }

    /** Sets (or clears) how an existing account is taxed. */
    fun setAccountTaxTreatment(
        accountId: Long,
        treatment: ca.tristan.portfolio.data.db.TaxTreatment?
    ) = viewModelScope.launch {
        // A targeted UPDATE, NOT an upsert. Going through the REPLACE-based
        // upsert here deleted the account row and cascaded away every holding
        // in it — see AccountDao.upsert.
        repository.setAccountTaxTreatment(accountId, treatment)
    }

    // ── Tax ───────────────────────────────────────────────────────────────

    /**
     * Where the user files, which gates every tax feature.
     *
     * Seeded from the device region on first run, and labelled as such
     * wherever it is shown.
     *
     * The device region is weak evidence — an expat, an immigrant who never
     * changed the setting, or a phone bought abroad all report the wrong
     * country. It used to default to OTHER for that reason, which turned every
     * tax figure in the app off behind a question nobody had been asked. The
     * cost of that silence was higher than the cost of a visible wrong guess:
     * a blank tax screen looks like "nothing to report here", whereas a filled
     * one that says where its answer came from invites the correction.
     *
     * So: seeded, never silently. [isResidencyAuto] stays true until the user
     * picks one themselves, and the Taxes screen says so while it is.
     */
    private val _residency = MutableStateFlow(
        ca.tristan.portfolio.tax.Residency.fromCode(
            prefs.getString(KEY_RESIDENCY, null) ?: seedResidencyFromDevice()
        )
    )
    val residency: StateFlow<ca.tristan.portfolio.tax.Residency> = _residency

    private val _isResidencyAuto = MutableStateFlow(prefs.getBoolean(KEY_RESIDENCY_AUTO, false))
    /** True while residency is the device's answer rather than the user's. */
    val isResidencyAuto: StateFlow<Boolean> = _isResidencyAuto

    /**
     * Writes the device's region into prefs the first time, and returns it.
     *
     * Persisted rather than derived on every launch so that a user who travels,
     * or who changes their phone's region for unrelated reasons, does not find
     * their tax rules quietly changed underneath them.
     */
    private fun seedResidencyFromDevice(): String? {
        val guess = suggestedResidency() ?: return null
        prefs.edit()
            .putString(KEY_RESIDENCY, guess.code)
            .putBoolean(KEY_RESIDENCY_AUTO, true)
            .apply()
        return guess.code
    }

    fun setResidency(r: ca.tristan.portfolio.tax.Residency) {
        prefs.edit()
            .putString(KEY_RESIDENCY, r.code)
            // A deliberate pick is no longer the device's guess.
            .putBoolean(KEY_RESIDENCY_AUTO, false)
            .apply()
        _residency.value = r
        _isResidencyAuto.value = false
    }

    /** Puts residency back under the device's control. */
    fun resetResidencyToDevice() {
        val guess = suggestedResidency() ?: ca.tristan.portfolio.tax.Residency.OTHER
        prefs.edit()
            .putString(KEY_RESIDENCY, guess.code)
            .putBoolean(KEY_RESIDENCY_AUTO, suggestedResidency() != null)
            .apply()
        _residency.value = guess
        _isResidencyAuto.value = suggestedResidency() != null
    }

    // The two tax rates the user supplies. Stored as percentages; -1 means unset.
    private val _taxRates = MutableStateFlow(
        ca.tristan.portfolio.tax.TaxRules.UserRates(
            marginalPct = prefs.getFloat(KEY_MARGINAL_RATE, -1f).takeIf { it >= 0f }?.toDouble(),
            preferentialPct = prefs.getFloat(KEY_PREF_RATE, -1f).takeIf { it >= 0f }?.toDouble()
        )
    )
    val taxRates: StateFlow<ca.tristan.portfolio.tax.TaxRules.UserRates> = _taxRates

    fun setMarginalRate(pct: Double?) {
        prefs.edit().putFloat(KEY_MARGINAL_RATE, pct?.toFloat() ?: -1f).apply()
        _taxRates.value = _taxRates.value.copy(marginalPct = pct)
    }

    fun setPreferentialRate(pct: Double?) {
        prefs.edit().putFloat(KEY_PREF_RATE, pct?.toFloat() ?: -1f).apply()
        _taxRates.value = _taxRates.value.copy(preferentialPct = pct)
    }

    /** Estimated annual tax on a holding's dividends, or null when not estimable. */
    fun dividendTaxEstimateFor(
        holding: HoldingEntity,
        annualDividendIncome: Double?
    ): ca.tristan.portfolio.tax.TaxRules.DividendTaxEstimate? {
        val income = annualDividendIncome ?: return null
        val treatment = accounts.value.firstOrNull { it.id == holding.accountId }?.taxTreatment
        return ca.tristan.portfolio.tax.TaxRules.estimateDividendTax(
            ticker = holding.ticker,
            residency = _residency.value,
            treatment = treatment,
            annualDividendIncome = income,
            rates = _taxRates.value
        )
    }

    /** Estimated tax if this position were sold today, or null when it isn't taxable. */
    fun capitalGainsTaxEstimateFor(holding: HoldingEntity, unrealizedGain: Double?): Double? {
        val gain = unrealizedGain ?: return null
        val treatment = accounts.value.firstOrNull { it.id == holding.accountId }?.taxTreatment
        return ca.tristan.portfolio.tax.TaxRules.estimateCapitalGainsTax(
            residency = _residency.value,
            treatment = treatment,
            unrealizedGain = gain,
            rates = _taxRates.value
        )
    }

    /** True once the user has told the app where they file. */
    val taxFeaturesEnabled: StateFlow<Boolean> =
        residency.map { it != ca.tristan.portfolio.tax.Residency.OTHER }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /**
     * Accounts holding something, that the user hasn't classified yet.
     *
     * Surfaced so the app can prompt once rather than silently showing nothing:
     * an unclassified account produces no tax notes at all, which is
     * indistinguishable from "there is nothing to say about it".
     */
    val accountsMissingTaxTreatment: StateFlow<List<AccountEntity>> =
        combine(accounts, holdings) { accts, hs ->
            val used = hs.map { it.accountId }.toSet()
            accts.filter { it.taxTreatment == null && it.id in used }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Tax notes for one holding, or empty when the app has nothing honest to
     * say — residency unset, account unclassified, or no rule that applies.
     */
    /**
     * True when tax features are switched on but this holding's account has
     * not been classified, so the app has something to say and is only waiting
     * to be told where the holding lives.
     *
     * Without this the two required settings fail silently and in different
     * places: residency is in Settings, treatment is on the account screen, and
     * a user who sets the first never learns the second exists.
     */
    /**
     * The residency the device's region suggests, or null when it suggests
     * nothing this app models.
     *
     * A SUGGESTION, never an assumption. A phone's region is its language and
     * store settings, not where its owner files taxes — an expat, an immigrant
     * who never changed the setting, or a phone bought abroad would all be
     * silently given the wrong country's rules, and "silently" is the problem:
     * a wrong withholding warning looks exactly like a right one. So this is
     * offered for one-tap confirmation and nothing is applied until the user
     * taps it.
     */
    fun suggestedResidency(): ca.tristan.portfolio.tax.Residency? =
        when (java.util.Locale.getDefault().country.uppercase()) {
            "CA" -> ca.tristan.portfolio.tax.Residency.CANADA
            "US" -> ca.tristan.portfolio.tax.Residency.UNITED_STATES
            else -> null
        }

    /**
     * Portfolio-wide withholding that a different account would avoid.
     *
     * This is the number worth surfacing, because the per-holding notes answer
     * "is this costing me anything?" one holding at a time, while the action
     * they imply — move it to a sheltered account — is a portfolio decision.
     * Seeing "$420 a year across 3 holdings" is what makes it worth doing.
     *
     * Only counts avoidable loss: tax withheld inside a tax-free account, where
     * there is no domestic tax bill to claim a foreign tax credit against.
     * Withholding in a taxable account is normally recoverable at filing time,
     * so counting it here would invent a loss the user does not have.
     */
    data class TaxDrag(
        val annualCost: Double,
        val holdingCount: Int,
        val currency: String
    )

    fun portfolioTaxDrag(): TaxDrag? {
        val residency = _residency.value
        if (residency == ca.tristan.portfolio.tax.Residency.OTHER) return null

        val treatmentByAccount = accounts.value.associate { it.id to it.taxTreatment }
        var total = 0.0
        var count = 0

        // Per SLICE, not per card: the same fund loses withholding in the TFSA
        // and loses none of it in the RRSP, so a single treatment for the
        // merged position would be wrong whichever one it picked.
        for (row in _upcomingDividends.value) {
            for (holding in row.rows.ifEmpty { listOf(row.holding) }) {
            val treatment = treatmentByAccount[holding.accountId] ?: continue
            // Any registered account, not just the tax-free one. A Canadian
            // holding German or Swedish stock in an RRSP is losing withholding
            // permanently too — the treaty exemption that covers US dividends
            // there does not extend to anywhere else.
            if (ca.tristan.portfolio.tax.TaxRules.isRecoverable(treatment)) continue

            val rate = ca.tristan.portfolio.tax.TaxRules.withholdingRate(
                holding.ticker, residency, treatment
            )
            if (rate <= 0.0) continue

            val freq = (row.info.paymentFrequencyPerYear ?: 4).coerceAtLeast(1)
            val perPayment = row.info.perPaymentAmount
                ?: row.info.estimatedAnnualRate?.div(freq.toDouble())
                ?: continue
            val annualIncome = perPayment * holding.units * freq
            if (annualIncome <= 0.0) continue

            total += ca.tristan.portfolio.data.FxRates.convert(
                annualIncome * rate,
                holding.currency.uppercase().ifBlank { "CAD" },
                _baseCurrency.value
            )
            count++
            }
        }
        return if (count > 0) TaxDrag(total, count, _baseCurrency.value) else null
    }

    fun needsTaxTreatmentPrompt(holding: HoldingEntity): Boolean {
        if (_residency.value == ca.tristan.portfolio.tax.Residency.OTHER) return false
        return accounts.value.firstOrNull { it.id == holding.accountId }?.taxTreatment == null
    }

    fun taxNotesFor(
        holding: HoldingEntity,
        annualDividendIncome: Double?
    ): List<ca.tristan.portfolio.tax.TaxNote> {
        val treatment = accounts.value.firstOrNull { it.id == holding.accountId }?.taxTreatment
        return ca.tristan.portfolio.tax.TaxRules.notesFor(
            ticker = holding.ticker,
            residency = _residency.value,
            treatment = treatment,
            annualDividendIncome = annualDividendIncome
        )
    }

    fun saveHolding(
        existingId: Long?, accountId: Long, name: String, ticker: String?, type: HoldingType,
        units: Double, manualPrice: Double?, currency: String, costBasis: Double?, onDone: () -> Unit = {}
    ) = viewModelScope.launch {
        repository.saveHolding(existingId, accountId, name, ticker, type, units, manualPrice, currency, costBasis)
        onDone()
    }

    suspend fun previewQuote(ticker: String): QuotePreview = repository.previewQuote(ticker)

    fun addDividend(holdingId: Long, paidAtMillis: Long, amount: Double, perUnit: Double?, currency: String, note: String?, onDone: () -> Unit = {}) =
        viewModelScope.launch {
            repository.addDividendPayment(holdingId, paidAtMillis, amount, perUnit, currency, note)
            onDone()
        }

    fun deleteDividend(id: Long) = viewModelScope.launch { repository.deleteDividendPayment(id) }

    // ---- Portfolio: live holding prices ----

    /** Refreshes live prices for all holdings with tickers from Yahoo Finance
     *  and updates the portfolio day-change figure. */
    fun refreshPortfolio() = viewModelScope.launch {
        // An explicit pull means the user wants current data, so the chart
        // cache steps aside for this pass.
        ca.tristan.portfolio.net.YahooQuoteClient.invalidateBarCache()
        val dayChange = repository.refreshAllQuotesAndComputeDayChange()
        // Only accept a refresh that actually priced something. Assigning
        // unconditionally meant a throttled minute — every quote request
        // refused — reported the day as a flat 0.00%, replacing a correct
        // figure with a wrong one until the next pull happened to succeed.
        // That is the "correct, then wrong, then correct" flapping.
        if (dayChange.measuredValue > 0.0) _liveDayChange.value = dayChange
        // Seed the SHARED quote cache with the exact price/previousClose this
        // pass just used for the total above. Without this, a holding that
        // isn't also on the watchlist had no entry here at all until
        // `refreshHoldingQuotes()` (Markets → My Holdings) filled one in from
        // the spark endpoint — a different provider answering a different
        // call, which is exactly the split this whole cache exists to avoid.
        // Only fills in what this pass actually priced; never erases an
        // existing entry that answered better (e.g. full detail fields).
        if (dayChange.quotes.isNotEmpty()) {
            val merged = _quotes.value.toMutableMap()
            for ((ticker, q) in dayChange.quotes) {
                val existing = merged[ticker]
                merged[ticker] = if (existing == null) q else existing.copy(
                    price = q.price,
                    previousClose = q.previousClose ?: existing.previousClose,
                    currency = q.currency ?: existing.currency
                )
            }
            _quotes.value = merged
        }
        lastPortfolioRefreshAt = System.currentTimeMillis()
    }

    /** When [refreshPortfolio] last completed, for [refreshPortfolioIfStale]. */
    private var lastPortfolioRefreshAt: Long = 0L

    /**
     * Refreshes only if the prices on screen are old enough to be worth
     * replacing.
     *
     * The Portfolio screen used to call [refreshPortfolio] from
     * `LaunchedEffect(Unit)`, which re-fires every time the composable
     * re-enters composition — and returning to the tab does exactly that. So
     * switching tabs issued a fresh network round trip each time, and the
     * displayed figures visibly changed on a navigation the user did not think
     * of as a refresh at all.
     *
     * A window rather than a one-shot flag: the screen should still catch up
     * after the app has been in the background for a while, it just should not
     * refetch because someone looked at Dividends for four seconds. An explicit
     * pull-to-refresh bypasses this entirely and always fetches, which is what
     * makes the gesture mean something.
     */
    fun refreshPortfolioIfStale(maxAgeMillis: Long = PORTFOLIO_FRESHNESS_MILLIS) {
        val age = System.currentTimeMillis() - lastPortfolioRefreshAt
        if (lastPortfolioRefreshAt != 0L && age < maxAgeMillis) return
        refreshPortfolio()
    }

    suspend fun fetchHistoryBars(ticker: String, range: String, interval: String): List<HistoryBar> =
        repository.fetchHistoryBars(ticker, range, interval)

    // ---- Dashboard: Quotes watchlist ----

    /**
     * Loads prices + sparklines for every watchlist row.
     *
     * This used to run two sequential HTTP calls per ticker (quote, then
     * sparkline) — roughly 22 round-trips for an 11-row watchlist, which is
     * why the dashboard sat empty for several seconds on launch. Now a single
     * batched `spark` request covers every ticker at once, and only the
     * symbols that request misses fall back to individual calls, in parallel
     * rather than one at a time.
     */
    fun refreshWatchlistQuotes() = viewModelScope.launch {
        val tickers = watchlist.value.map { it.ticker }
        if (tickers.isEmpty()) return@launch

        val updatedQuotes     = _quotes.value.toMutableMap()
        val updatedSparklines = _sparklines.value.toMutableMap()

        // ── 1. One batched call for the whole list ────────────────────────
        val batch = withContext(Dispatchers.IO) {
            ca.tristan.portfolio.net.YahooQuoteClient.fetchSparkBatch(tickers, "1d", "30m")
        }
        // Tickers the PORTFOLIO prices, and prices from a different provider.
        //
        // `_quotes` is one map shared by every screen, and the portfolio rows
        // read `quotes[ticker]?.price ?: lastKnownPrice` — so whatever lands
        // here is what My Holdings and the totals display, in preference to the
        // value the refresh carefully stored in Room.
        //
        // refreshAllQuotesAndComputeDayChange fills that from the FinanceQuery
        // chain; this loop used to overwrite the same entries from the spark
        // endpoint, a different server with a different cache — and a chart
        // endpoint at that, whose price falls back to the last 30-minute bar
        // close when the payload carries no `meta`. For a held ticker that was
        // also on the watchlist, opening the Markets tab rewrote the
        // portfolio's price, and going back showed different numbers after a
        // navigation the user never thought of as a refresh.
        //
        // The sparkline is still taken from spark for every row — that is what
        // the chart column is made of, and no other endpoint provides it. Only
        // the PRICE is left alone, and only where the portfolio has already
        // established one.
        val heldTickers = holdings.value.mapNotNull { it.ticker?.uppercase() }.toSet()

        for ((symbol, spark) in batch) {
            val existing = updatedQuotes[symbol]

            // A held ticker with no quote yet still takes this one: a row with
            // a slightly different price beats a row with none, and the next
            // portfolio refresh corrects it.
            val pricedByPortfolio = symbol.uppercase() in heldTickers && existing != null

            if (!pricedByPortfolio) updatedQuotes[symbol] = Quote(
                price         = spark.price,
                // The spark payload carries no company name; keep whatever a
                // previous full quote resolved so rows don't lose their label.
                name          = existing?.name,
                previousClose = spark.previousClose,
                currency      = existing?.currency,
                // Read straight off this same batch rather than waiting on the
                // slower per-ticker backfill below, which isn't equally
                // reliable for every symbol (see [SparkQuote.marketTimeMillis]).
                marketTimeMillis = spark.marketTimeMillis ?: existing?.marketTimeMillis,
                // Without this the row has a timestamp but no venue to label
                // it in, and falls back to the reader's own clock — which is
                // what printed the FTSE's London close as a Toronto morning.
                exchangeTimezone = spark.exchangeTimezone ?: existing?.exchangeTimezone
            )
            if (spark.closes.size >= 2) updatedSparklines[symbol] = spark.closes
        }
        // Publish the batch straight away — the list fills in before the
        // slower per-ticker detail calls below have finished.
        _quotes.value     = updatedQuotes
        _sparklines.value = updatedSparklines

        // Write through to the cache so the next cold start paints instantly.
        //
        // Caches what is actually DISPLAYED, not the raw spark figure: for a
        // held ticker the price above was deliberately left to the portfolio's
        // provider, and caching spark's number anyway would put it back on
        // screen at the next cold start — the same discrepancy, just deferred
        // to launch instead of a tab switch.
        for ((symbol, spark) in batch) {
            val shown = updatedQuotes[symbol]
            repository.cacheWatchlistQuote(
                symbol,
                shown?.price ?: spark.price,
                shown?.previousClose ?: spark.previousClose,
                shown?.name, shown?.currency
            )
        }

        // ── 2. Anything the batch missed, fetched in parallel ─────────────
        val missing = tickers.filter { it !in batch }
        if (missing.isNotEmpty()) {
            val fetched = withContext(Dispatchers.IO) {
                coroutineScope {
                    missing.map { t -> async { t to repository.fetchQuote(t) } }.awaitAll()
                }
            }
            for ((t, q) in fetched) {
                updatedQuotes[t] = q
                if (q != null) {
                    repository.cacheWatchlistQuote(t, q.price, q.previousClose, q.name, q.currency)
                }
            }
            _quotes.value = updatedQuotes.toMutableMap()
        }

        // ── 3. Fill in names, currency and last-trade time ────────────────
        // The batched spark payload carries none of those, so any row that got
        // its price from the batch still needs one full quote to complete it.
        val needsDetail = batch.keys.filter {
            val q = updatedQuotes[it]
            q?.name == null || q.marketTimeMillis == null || q.exchangeTimezone == null
        }
        if (needsDetail.isNotEmpty()) {
            val detailed = withContext(Dispatchers.IO) {
                coroutineScope {
                    needsDetail.map { t -> async { t to repository.fetchQuote(t) } }.awaitAll()
                }
            }
            for ((t, q) in detailed) if (q != null) {
                updatedQuotes[t] = q
                repository.cacheWatchlistQuote(t, q.price, q.previousClose, q.name, q.currency)
            }
            _quotes.value = updatedQuotes.toMutableMap()
        }

        // ── 3b. Sparklines the batch didn't supply ────────────────────────
        // The spark endpoint is the only source of the mini chart, and when it
        // answers for a symbol without a usable close[] series — or doesn't
        // answer at all — step 2 backfills the PRICE and nothing notices the
        // chart column stayed empty. That is why the Markets rows showed live
        // prices and times beside a blank chart cell.
        //
        // The chart endpoint carries the same intraday series and is known
        // good (it is what draws the full-size chart on the detail screen), so
        // any row still missing a series asks it directly. Capped and run in
        // parallel: this is decoration, and it must not turn a long watchlist
        // into a burst of requests.
        val needsSpark = tickers.filter { (updatedSparklines[it]?.size ?: 0) < 2 }.take(12)
        if (needsSpark.isNotEmpty()) {
            val series = withContext(Dispatchers.IO) {
                coroutineScope {
                    needsSpark.map { t ->
                        async {
                            t to runCatching {
                                repository.fetchHistory(t, "1d", "30m")
                            }.getOrDefault(emptyList())
                        }
                    }.awaitAll()
                }
            }
            var changed = false
            for ((t, points) in series) {
                if (points.size >= 2) {
                    updatedSparklines[t] = points.map { it.second.toFloat() }
                    changed = true
                }
            }
            if (changed) _sparklines.value = updatedSparklines.toMutableMap()
        }

        // ── 4. Extended-hours / futures lines ─────────────────────────────
        // Deliberately last: prices are already on screen by now, and these
        // secondary lines fill in underneath rather than holding up the list.
        refreshExtendedQuotes(tickers)
    }

    /**
     * Loads the second line under each row: the index future for cash indices,
     * after-hours trading for everything else.
     *
     * All the index futures come back in one batched call. The per-ticker
     * extended-hours lookups need the chart endpoint (spark carries no
     * pre/post data), so they run in parallel and only for symbols that can
     * actually have an extended session.
     */
    /**
     * Extended data for ONE symbol, fetched on demand.
     *
     * Used by the quote detail screen, where the user has explicitly opened a
     * ticker — so a single request for that symbol is clearly wanted, and costs
     * nothing while they are only scrolling the list. Returns the index future
     * for a cash index, or the pre/post-market print for anything else.
     */
    suspend fun fetchExtendedFor(ticker: String): ca.tristan.portfolio.net.ExtendedQuote? =
        withContext(Dispatchers.IO) {
            val client = ca.tristan.portfolio.net.YahooQuoteClient
            val upper = ticker.uppercase()
            if (upper.contains("-USD")) return@withContext null

            val future = client.futureFor(upper)
            if (future != null) {
                val (symbol, label) = future
                val spark = client.fetchSparkBatch(listOf(symbol), "1d", "30m")[symbol]
                    ?: return@withContext null
                val prev = spark.previousClose ?: return@withContext null
                val change = spark.price - prev
                return@withContext ca.tristan.portfolio.net.ExtendedQuote(
                    label = label,
                    price = spark.price,
                    change = change,
                    changePercent = if (prev != 0.0) change / prev * 100.0 else 0.0,
                    atMillis = System.currentTimeMillis(),
                    symbol = symbol,
                    zoneId = spark.exchangeTimezone
                )
            }
            // A cash index with no future we track prints nothing after the
            // close, so asking only spends a request to learn that.
            if (upper.startsWith("^")) return@withContext null
            runCatching { client.fetchExtendedQuote(upper) }.getOrNull()
        }

    private suspend fun refreshExtendedQuotes(tickers: List<String>) {
        val client = ca.tristan.portfolio.net.YahooQuoteClient

        // Futures and after-hours are NOT the same thing, and gating them the
        // same way was wrong.
        //
        // After-hours during a live session is meaningless — there is no
        // after-hours print while the market is trading, and showing one
        // repeats the price above it with a staler number. Futures are the
        // opposite: they trade nearly around the clock and lead the cash index,
        // which is exactly why people watch them intraday.
        //
        // The cost decides it. Futures are ONE batched call covering every
        // index, whatever the watchlist size. After-hours is up to 25 separate
        // per-ticker calls on every refresh. Practically all of the saving is
        // in the second, so the second is what stays gated.
        val marketOpen = ca.tristan.portfolio.data.MarketCalendar.isMarketOpen()

        val result = _extendedQuotes.value.toMutableMap()
        // Drop after-hours rows left over from before the open, so a stale
        // overnight print doesn't sit under a live price all morning. Future
        // rows are keyed by their index and stay.
        if (marketOpen) result.keys.retainAll { client.hasFuture(it) }

        val indices = tickers.filter { client.hasFuture(it) }
        // Only symbols that actually have an extended session:
        //  - crypto trades 24/7, so "after hours" is meaningless
        //  - a cash index with no future we track (^GSPTSE, ^KS11 …) simply
        //    stops printing at the close; querying it just burns a request
        // Capped so a large watchlist can't fan out into dozens of calls.
        val others = if (marketOpen) emptyList() else tickers
            .filter { !client.hasFuture(it) && !it.contains("-USD") && !it.startsWith("^") }
            .take(25)

        withContext(Dispatchers.IO) {
            // Index futures — one call covers all of them.
            if (indices.isNotEmpty()) {
                val futures = client.fetchSparkBatch(client.futureSymbols(), "1d", "30m")
                for (index in indices) {
                    val (symbol, label) = client.futureFor(index) ?: continue
                    val spark = futures[symbol] ?: continue
                    val prev = spark.previousClose ?: continue
                    val change = spark.price - prev
                    result[index] = ca.tristan.portfolio.net.ExtendedQuote(
                        label = label,
                        price = spark.price,
                        change = change,
                        changePercent = if (prev != 0.0) change / prev * 100.0 else 0.0,
                        atMillis = System.currentTimeMillis(),
                        // The future's own symbol and zone, not the cash
                        // index's: "E-mini YM" is quoted on its own venue's
                        // clock, and that is what the timestamp beside it means.
                        symbol = symbol,
                        zoneId = spark.exchangeTimezone
                    )
                }
            }

            // After-hours for the rest, in parallel.
            if (others.isNotEmpty()) {
                coroutineScope {
                    others.map { t -> async { t to client.fetchExtendedQuote(t) } }.awaitAll()
                }.forEach { (t, ext) -> if (ext != null) result[t] = ext }
            }
        }
        _extendedQuotes.value = result
    }

    /**
     * Prices for held tickers that aren't on the watchlist, so the Dashboard's
     * My Holdings tab shows live values rather than the last stored price.
     */
    fun refreshHoldingQuotes() = viewModelScope.launch {
        val held = holdings.value.mapNotNull { it.ticker }.distinct()
        val onWatchlist = watchlist.value.map { it.ticker }.toSet()
        val missing = held.filter { it !in onWatchlist }
        if (missing.isEmpty()) return@launch

        val batch = withContext(Dispatchers.IO) {
            ca.tristan.portfolio.net.YahooQuoteClient.fetchSparkBatch(missing, "1d", "30m")
        }
        val q = _quotes.value.toMutableMap()
        val sp = _sparklines.value.toMutableMap()
        for ((symbol, spark) in batch) {
            // A held ticker not on the watchlist still gets its price and
            // previousClose from `refreshAllQuotesAndComputeDayChange()` —
            // the same authoritative FinanceQuery-first call the Portfolio
            // total's day-change percentage is computed from — the moment
            // that pass has run. Overwriting them here from spark, a
            // different provider answering at a different moment, is exactly
            // how the Holding Detail screen and the Portfolio total ended up
            // printing two different percentages for one holding. Spark stays
            // in use only to fill in whatever that pass hasn't covered yet
            // (a brand-new holding, or before the first refresh) and to keep
            // the fields it alone carries — the mini chart, market time.
            val existing = q[symbol]
            val alreadyPriced = existing?.previousClose != null
            q[symbol] = Quote(
                price = if (alreadyPriced) existing!!.price else spark.price,
                name = existing?.name,
                previousClose = if (alreadyPriced) existing!!.previousClose else spark.previousClose,
                currency = existing?.currency,
                marketTimeMillis = spark.marketTimeMillis ?: existing?.marketTimeMillis,
                exchangeTimezone = spark.exchangeTimezone ?: existing?.exchangeTimezone
            )
            if (spark.closes.size >= 2) sp[symbol] = spark.closes
        }
        _quotes.value = q
        _sparklines.value = sp
    }

    fun refreshMarketNews() = viewModelScope.launch {
        // Local outlets for the reader's own market, layered onto the US feeds.
        val fetched = repository.fetchMarketNews(_homeCountry.value)
        // A refresh that came back empty leaves what is on screen alone — it
        // should improve the feed or do nothing, never take headlines away.
        if (fetched.isNotEmpty()) {
            _marketNews.value = fetched
            withContext(Dispatchers.IO) {
                ca.tristan.portfolio.data.DiskCache.saveNews(dividend = false, items = fetched)
            }
        }
    }

    // ── Dividend news (Menu → Dividend News) ─────────────────────────────────
    private val _dividendNews = MutableStateFlow<List<ca.tristan.portfolio.net.NewsItem>>(emptyList())
    val dividendNews: StateFlow<List<ca.tristan.portfolio.net.NewsItem>> = _dividendNews

    /** Dividend headlines, seeded with the user's own tickers so declarations
     *  for what they actually hold surface first. */
    fun refreshDividendNews() = viewModelScope.launch {
        val tickers = holdings.value.mapNotNull { it.ticker }.distinct()
        val fetched = repository.fetchDividendNews(tickers)
        if (fetched.isNotEmpty()) {
            _dividendNews.value = fetched
            withContext(Dispatchers.IO) {
                ca.tristan.portfolio.data.DiskCache.saveNews(dividend = true, items = fetched)
            }
        }
    }

    /**
     * Paints the last fetched headlines so the News tab opens on content
     * rather than a spinner, then the refresh above replaces them.
     *
     * Headlines are worth keeping between launches — they are still readable
     * an hour later — and re-fetching a dozen feeds before anything appears
     * was the slowest empty state in the app.
     */
    fun loadCachedNews() = viewModelScope.launch {
        val (market, dividend) = withContext(Dispatchers.IO) {
            ca.tristan.portfolio.data.DiskCache.loadNews(dividend = false) to
                ca.tristan.portfolio.data.DiskCache.loadNews(dividend = true)
        }
        if (_marketNews.value.isEmpty() && market != null) _marketNews.value = market
        if (_dividendNews.value.isEmpty() && dividend != null) _dividendNews.value = dividend
    }

    /** Re-scrapes the payout calendars, ignoring the cache — what a deliberate
     *  pull-to-refresh on the Dividends tab should mean. */
    fun refreshUpcomingDividendsForced() = viewModelScope.launch {
        ca.tristan.portfolio.net.YahooQuoteClient.invalidateDividendCalendar()
        refreshUpcomingDividends().join()
    }

    /**
     * Refreshes the Dashboard's Hot Stocks list, then fetches intraday
     * sparklines for each name so the Chart column is populated.
     *
     * The merge across three screeners happens inside the client. The gainer
     * and loser flows are kept as derived views of the same result so nothing
     * that reads them breaks, without costing extra requests.
     */
    fun refreshMarketMovers() = viewModelScope.launch {
        // One call: fetchHotStocks already merges most-actives, gainers and
        // losers internally, so fetching the gainer and loser lists again here
        // would repeat three network requests for data already in hand. The
        // gainer/loser flows are derived from the merged result instead.
        val hot = withContext(Dispatchers.IO) {
            ca.tristan.portfolio.net.YahooQuoteClient.fetchHotStocks()
        }
        if (hot.isEmpty()) return@launch

        _hotStocks.value  = hot
        _topGainers.value = hot.filter { it.changePct > 0 }.sortedByDescending { it.changePct }
        _topLosers.value  = hot.filter { it.changePct < 0 }.sortedBy { it.changePct }

        // Sparklines for every name in one batched call rather than dozens of
        // sequential per-ticker requests.
        val allTickers = hot.map { it.ticker }.distinct()
        val sparklines = _moverSparklines.value.toMutableMap()
        val batch = withContext(Dispatchers.IO) {
            ca.tristan.portfolio.net.YahooQuoteClient.fetchSparkBatch(allTickers, "1d", "30m")
        }
        // The batch endpoint answers with symbols spelled its own way and drops
        // any it cannot serve, so match case-insensitively and note what came
        // back rather than assuming the keys line up with what was asked for.
        val filled = mutableSetOf<String>()
        for ((symbol, spark) in batch) {
            if (spark.closes.size < 2) continue
            val ticker = allTickers.firstOrNull { it.equals(symbol, ignoreCase = true) } ?: symbol
            sparklines[ticker] = spark.closes
            filled += ticker
        }

        // Anything the batch missed falls back to the per-ticker chart endpoint
        // — the same one the quote detail screen uses, which is reliable. The
        // Chart column sat empty for every Hot Stocks row whenever the spark
        // endpoint refused the request, and one silent failure left it that way
        // until the app was restarted.
        val missing = allTickers.filterNot { it in filled }
        if (missing.isNotEmpty()) {
            val recovered = withContext(Dispatchers.IO) {
                coroutineScope {
                    // Capped concurrency: a burst of 25 parallel requests is a
                    // good way to get rate-limited into an empty chart column.
                    missing.chunked(6).flatMap { chunk ->
                        chunk.map { t ->
                            async {
                                t to runCatching {
                                    ca.tristan.portfolio.net.YahooQuoteClient
                                        .fetchHistory(t, "1d", "30m")
                                        .map { it.second.toFloat() }
                                }.getOrDefault(emptyList())
                            }
                        }.awaitAll()
                    }
                }
            }
            for ((ticker, closes) in recovered) {
                if (closes.size >= 2) sparklines[ticker] = closes
            }
        }

        _moverSparklines.value = sparklines
    }

    /**
     * Ensures [ticker] is in the watchlist, then delivers its watchlist entry ID
     * via [onReady] so the caller can navigate to its QuoteDetailScreen.
     * If the ticker is already in the watchlist the existing ID is returned immediately.
     */
    fun addMoverToWatchlistAndOpen(ticker: String, onReady: (Long) -> Unit) =
        viewModelScope.launch {
            val existing = watchlist.value.firstOrNull { it.ticker == ticker.trim().uppercase() }
            if (existing != null) { onReady(existing.id); return@launch }
            repository.addToWatchlist(ticker)
            refreshWatchlistQuotes()
            val entry = repository.getWatchlistItemByTicker(ticker)
            if (entry != null) onReady(entry.id)
        }

    fun addQuote(ticker: String, onResult: (Boolean) -> Unit = {}) = viewModelScope.launch {
        val added = repository.addToWatchlist(ticker)
        if (added) refreshWatchlistQuotes()
        onResult(added)
    }

    fun removeQuote(id: Long) = viewModelScope.launch { repository.removeFromWatchlist(id) }

    fun renameQuote(id: Long, name: String?) = viewModelScope.launch { repository.renameWatchlistItem(id, name) }

    suspend fun fetchHistory(ticker: String, range: String, interval: String): List<Pair<Long, Double>> =
        repository.fetchHistory(ticker, range, interval)

    private val _searchResults = MutableStateFlow<List<SymbolSearchResult>>(emptyList())
    val searchResults: StateFlow<List<SymbolSearchResult>> = _searchResults

    private var searchJob: kotlinx.coroutines.Job? = null

    fun searchQuotes(query: String) {
        searchJob?.cancel()
        if (query.isBlank()) {
            _searchResults.value = emptyList()
            return
        }
        searchJob = viewModelScope.launch {
            kotlinx.coroutines.delay(300)
            _searchResults.value = repository.searchSymbols(query)
        }
    }

    fun clearSearchResults() {
        searchJob?.cancel()
        _searchResults.value = emptyList()
    }

    // ---- Dividends tab: upcoming dividends across owned holdings ----

    /**
     * Fetches upcoming dividend info for each tickered holding.
     *
     * Primary source: dividendhistory.org — one HTTP call per ticker returns
     * both the confirmed history and the next 1-2 projected entries, giving
     * more reliable Ex-Date / Pay-Date than Yahoo Finance's calendarEvents.
     *
     * Fallback: Yahoo Finance quoteSummary (calendarEvents + summaryDetail)
     * is used when dividendhistory.org returns nothing for a ticker.
     */
    fun refreshUpcomingDividends() = viewModelScope.launch {
        // One entry per SECURITY, not per stored row. A fund held in three
        // accounts pays one distribution on the combined unit count, and
        // fetching the same ticker three times to build three identical cards
        // was both wrong on screen and three times the network.
        val securities = positions().filter { !it.ticker.isNullOrBlank() }
        val rows = mutableListOf<UpcomingDividendRow>()
        for (position in securities) {
            // The payment record is now kept, not just consumed: the forward
            // charts project from the fund's actual seasonal shape, and this is
            // the only place it gets fetched.
            val resolved = withContext(Dispatchers.IO) { resolveUpcoming(position.ticker!!) }
            val info = resolved.first
            if (info != null) {
                rows.add(
                    UpcomingDividendRow(
                        holding = position.principal,
                        info = info,
                        history = resolved.second,
                        rows = position.slices.map { it.holding }
                    )
                )
            }
        }
        // Only replace the list when the refresh produced something. Assigning
        // unconditionally let one throttled minute erase cards that were
        // already on screen and correct — a refresh should improve what is
        // shown or leave it alone, never take data away.
        if (rows.isNotEmpty()) {
            _upcomingDividends.value = rows.sortedBy { it.info.exDividendDateMillis ?: Long.MAX_VALUE }
        }
    }

    /**
     * Next expected distribution for [ticker], plus the per-unit record it came
     * from, as (info, history).
     *
     * Extracted so the Dividends tab and the holding detail screen resolve the
     * next payment exactly the same way. They used to disagree: the detail
     * screen fetched its own, while its payout *chart* read a cached list the
     * screen never populated — so a holding could show "Nov 9 — US$27.02
     * upcoming" in its schedule and an entirely empty chart directly above it.
     *
     * Sources in order of trust: dividendhistory.org (confirmed dates and
     * declared amounts), Yahoo's calendarEvents, then Yahoo's raw distribution
     * events run through the same estimator.
     */
    /**
     * The distribution record every income figure is bucketed on, in PAY dates.
     *
     * [yahooEvents] is Yahoo's events block — ex-dates only, but reaching back
     * to the security's first ever distribution. [orgEntries] is the payout
     * page — ex AND pay dates, but only a recent slice. Taking either one whole
     * means giving up its counterpart's strength, so this takes the reach from
     * one and the dates from the other.
     *
     * Falls back to ex-dates unchanged when no pay date exists anywhere, which
     * is the honest answer rather than a fabricated offset.
     */
    private fun payDatedHistory(
        yahooEvents: List<Pair<Long, Double>>,
        orgEntries: List<ca.tristan.portfolio.net.DividendHistoryOrgEntry>
    ): List<Pair<Long, Double>> {
        val dayMs = 24L * 60 * 60 * 1000
        val confirmedOrg = orgEntries.filter { !it.isEstimated && it.amountPerShare > 0 }

        // The fund's own ex→pay gap, from the rows carrying both dates.
        // Median rather than mean: one row with a mis-parsed date should not
        // shift every projected payment.
        val gaps = confirmedOrg
            .mapNotNull { entry -> entry.payDateMs?.let { it - entry.exDateMs } }
            .filter { it >= 0 && it <= 90 * dayMs }
            .sorted()
        val medianGap = if (gaps.isEmpty()) null else gaps[gaps.size / 2]

        // No pay date anywhere — nothing to re-date with. Keep the old
        // longest-record rule and stay on ex-dates.
        if (medianGap == null) {
            val orgAsPairs = confirmedOrg.map { it.exDateMs to it.amountPerShare }
            return (if (yahooEvents.size >= orgAsPairs.size) yahooEvents else orgAsPairs)
                .sortedBy { it.first }
        }

        // The two feeds can disagree by a day on the same distribution, so
        // matching is by proximity rather than equality.
        val tolerance = 3 * dayMs

        val redated = yahooEvents.map { (exMs, amount) ->
            val matched = confirmedOrg
                .filter { kotlin.math.abs(it.exDateMs - exMs) <= tolerance }
                .minByOrNull { kotlin.math.abs(it.exDateMs - exMs) }
                ?.payDateMs
            (matched ?: (exMs + medianGap)) to amount
        }

        // Distributions the org page knows about and Yahoo's record missed —
        // deduplicated against what is already in, so an overlap cannot bill
        // one payment twice.
        val extras = confirmedOrg
            .map { (it.payDateMs ?: (it.exDateMs + medianGap)) to it.amountPerShare }
            .filter { candidate ->
                redated.none { kotlin.math.abs(it.first - candidate.first) <= tolerance }
            }

        return (redated + extras).sortedBy { it.first }
    }

    private suspend fun resolveUpcoming(
        ticker: String
    ): Pair<UpcomingDividend?, List<Pair<Long, Double>>> = withContext(Dispatchers.IO) {
        val yahooEvents = runCatching {
            ca.tristan.portfolio.net.YahooQuoteClient.fetchHistoricalDividendEvents(ticker)
        }.getOrDefault(emptyList())

        val orgEntries = runCatching {
            ca.tristan.portfolio.net.YahooQuoteClient.fetchDividendHistoryOrg(ticker)
        }.getOrDefault(emptyList())

        // Yahoo's reach, the org feed's dates — not one or the other.
        //
        // This used to pick whichever list was LONGER and take its dates with
        // it, which quietly decided something it had no business deciding:
        // Yahoo's events block carries ex-dates only, the org feed carries pay
        // dates, and for a fund like XEQT the two records are the same length,
        // so the tie-break chose the date TYPE. Android landed on ex-dates and
        // iOS, whose tie-break runs the other way, landed on pay dates — which
        // is why one build put XEQT's year-end distribution in December and the
        // other put it in January. Same fund, same engine, opposite answers.
        //
        // Pay dates are the correct ones here. Every consumer of this list is
        // about money arriving — the "Monthly Income" bars, the trailing
        // twelve-month total, the forward projection — and money arrives on the
        // pay date. Nobody can spend an ex-dividend date.
        //
        // So: keep Yahoo's record, which reaches back to the security's first
        // ever payment where the org feed only returns a recent page, and
        // re-date it from the org feed. An exact match supplies a real pay
        // date; anything older than the org page falls back to the fund's own
        // median ex→pay gap, which is the same trick the upcoming-payment
        // estimator already uses rather than a guess at a fixed offset.
        val history = payDatedHistory(yahooEvents, orgEntries)

        // Sources in descending order of trust, evaluated lazily so a source
        // is only paid for when the ones above it came up short.
        //
        // Each candidate is checked for a *usable* answer rather than merely a
        // non-null one. An elvis chain took the first non-null result, which
        // let Yahoo's quoteSummary — happy to return an object carrying an
        // annual rate and nothing else — shadow the historical-events estimate
        // underneath it, and a payment with no date and no per-payment amount
        // is not something any screen can draw.
        // `suspend` because repository.upcomingDividendFor is — a plain
        // function type would not accept it.
        val candidates: List<suspend () -> ca.tristan.portfolio.net.UpcomingDividend?> = listOf(
            { orgEntries.takeIf { it.isNotEmpty() }
                ?.let { ca.tristan.portfolio.net.YahooQuoteClient.inferUpcomingFromHistoryOrg(it) } },
            { repository.upcomingDividendFor(ticker) },
            {
                yahooEvents.takeIf { it.size >= 2 }?.let { events ->
                    ca.tristan.portfolio.net.YahooQuoteClient.inferUpcomingFromHistoryOrg(
                        events.map {
                            ca.tristan.portfolio.net.DividendHistoryOrgEntry(
                                exDateMs = it.first,
                                payDateMs = null,
                                amountPerShare = it.second,
                                isEstimated = false
                            )
                        }.sortedByDescending { it.exDateMs }
                    )
                }
            }
        )

        fun usable(d: ca.tristan.portfolio.net.UpcomingDividend?) =
            d != null && d.exDividendDateMillis != null && d.perPaymentAmount != null

        var best: ca.tristan.portfolio.net.UpcomingDividend? = null
        for (candidate in candidates) {
            val value = runCatching { candidate() }.getOrNull() ?: continue
            // Keep the first answer as a floor, so a partial result is still
            // better than nothing if every source turns out to be partial.
            if (best == null) best = value
            if (usable(value)) { best = value; break }
        }
        best to history
    }

    /** Portfolio value over a Yahoo range, for the Total Value chart's selector. */
    suspend fun portfolioValueSeries(range: String, interval: String): PortfolioSeries =
        repository.portfolioValueSeries(range, interval)

    /** Portfolio value over the last [days] days, for the Total Value chart. */
    suspend fun portfolioValueHistory(days: Int = 90): List<Pair<Long, Double>> =
        repository.portfolioValueHistory(days)

    // ── Dividend income series (bar charts) ──────────────────────────────────

    /**
     * Received-vs-projected dividend cash flows for the whole portfolio, ready
     * to bucket into chart bars.
     *
     * "Received" comes from the payments the user has actually logged; the
     * projection extends each holding's next expected payment forward at its
     * own cadence for [monthsAhead] months. Both are in the holding's own
     * currency — no FX conversion, matching the rest of the app.
     *
     * @param currency when non-null, only holdings recorded in that currency
     *        contribute. Summing a USD distribution into a CAD total produces
     *        a number that is not money in any currency, so the Dividends
     *        screen asks for one currency at a time.
     */
    fun dividendCashFlows(
        // Eleven years, matching the YEAR chart's forward span. At the old 18
        // months the year chart had to extrapolate almost every forward bar
        // from a run rate; now the projection itself covers them, seasonal
        // shape and all.
        monthsAhead: Int = 132,
        currency: String? = null
    ): Pair<List<Pair<Long, Double>>, List<Pair<Long, Double>>> {
        val wanted = currency?.uppercase()
        fun matches(h: ca.tristan.portfolio.data.db.HoldingEntity) =
            wanted == null || h.currency.uppercase().ifBlank { "CAD" } == wanted

        val allowedIds = holdings.value.filter { matches(it) }.map { it.id }.toSet()
        val now = System.currentTimeMillis()

        // Past and future halves of the logged payments — see
        // dividendCashFlowsInBase for why a "received" payment can be dated in
        // the future and must not be drawn as money already banked.
        val loggedAll = dividends.value
            .filter { wanted == null || it.holdingId in allowedIds }
        val received = loggedAll
            .filter { it.paidAtMillis <= now }
            .map { it.paidAtMillis to it.amount }
        val futureLogged = loggedAll
            .filter { it.paidAtMillis > now }
            .map { it.paidAtMillis to it.amount }
        val loggedByHolding = loggedAll.groupBy { it.holdingId }

        val horizon = Calendar.getInstance().apply {
            timeInMillis = now
            add(Calendar.MONTH, monthsAhead)
        }.timeInMillis

        val projected = _upcomingDividends.value
            .filter { matches(it.holding) }
            .flatMap { row ->
                val logged = loggedByHolding[row.holding.id].orEmpty()
                val perYear = (row.info.paymentFrequencyPerYear ?: 4).coerceAtLeast(1)
                val windowMs = (365.0 / perYear / 2.0 * 24 * 60 * 60 * 1000).toLong()
                projectFor(row, now, horizon).filterNot { (ts, _) ->
                    logged.any { kotlin.math.abs(it.paidAtMillis - ts) <= windowMs }
                }
            }

        return received to (projected + futureLogged)
    }

    /**
     * One holding's projected cash payments between [from] and [until].
     *
     * Shared by the portfolio chart, the Dividends tab's forward view and the
     * holding detail chart, so the three can no longer disagree about what the
     * next year of income looks like — which they did, because each had its own
     * copy of the schedule walk.
     */
    private fun projectFor(
        row: UpcomingDividendRow,
        from: Long,
        until: Long
    ): List<Pair<Long, Double>> {
        if (row.units <= 0.0) return emptyList()
        return ca.tristan.portfolio.data.DividendForecast.project(
            history = row.history,
            upcoming = row.info,
            fromMillis = from,
            untilMillis = until,
            growthRate = DISTRIBUTION_GROWTH_RATE
        ).map { it.atMillis to it.perUnit * row.units }
    }

    /**
     * Received and projected dividend cash for the whole portfolio, every
     * amount converted into the reporting currency.
     *
     * [dividendCashFlows] filters to one currency instead, which is right for
     * the Dividends tab (where the user picks which currency to look at) and
     * wrong for the Portfolio tab, whose every other figure is a converted
     * total. Converting rather than filtering means the income chart there
     * covers the whole portfolio.
     */
    fun dividendCashFlowsInBase(
        monthsAhead: Int = 132
    ): Pair<List<Pair<Long, Double>>, List<Pair<Long, Double>>> {
        val base = _baseCurrency.value
        fun conv(amount: Double, from: String) =
            ca.tristan.portfolio.data.FxRates.convert(
                amount, from.uppercase().ifBlank { "CAD" }, base
            )

        val holdingCurrency = holdings.value.associate { it.id to it.currency }
        val now = System.currentTimeMillis()

        // Split logged payments on the date, not on which table they came from.
        //
        // The dividend auto-import writes announced-but-unpaid dividends into
        // the user's records, so "received" can contain money that has not
        // arrived. Drawing that green — as income already banked — is what put
        // a BNS dividend payable on 27 October into September's Received
        // Income. A payment dated after today is an expectation wherever it is
        // stored, so it is treated as one.
        val loggedAll = dividends.value
        val received = loggedAll
            .filter { it.paidAtMillis <= now }
            .map { it.paidAtMillis to conv(it.amount, it.currency) }
        val futureLogged = loggedAll
            .filter { it.paidAtMillis > now }
            .map { it.paidAtMillis to conv(it.amount, it.currency) }

        val horizon = Calendar.getInstance().apply {
            timeInMillis = now
            add(Calendar.MONTH, monthsAhead)
        }.timeInMillis

        // Projecting only from TODAY made the current year's bar on the Yearly
        // Income chart read as a collapse: with nothing logged as received, a
        // quarterly payer's year showed just whatever payment was still ahead
        // of today — one quarter out of four — sitting next to full years on
        // either side. Nothing about the fund's income actually dropped; the
        // other three quarters were simply never asked for.
        //
        // So when nothing has been received yet THIS calendar year, the
        // projection starts at January 1st instead of today. DividendForecast
        // then builds its seasonal template from the cycle before that (the
        // fund's own real record) and projects every quarter of the current
        // year from it — the same "fall back to the forecast" rule the goal
        // card already uses when nothing is logged. Whichever quarter is
        // actually announced still gets substituted with the real figure by
        // DividendForecast's own applyAnnouncedPayment step.
        val receivedThisYear = received.any {
            Calendar.getInstance().apply { timeInMillis = it.first }
                .get(Calendar.YEAR) == Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.YEAR)
        }
        val projectionStart = if (!receivedThisYear) {
            Calendar.getInstance().apply {
                timeInMillis = now
                set(Calendar.MONTH, Calendar.JANUARY)
                set(Calendar.DAY_OF_MONTH, 1)
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0);      set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        } else now

        // Logged payments grouped by holding, so a projection can be checked
        // against the payments that holding already has on record.
        val loggedByHolding = loggedAll.groupBy { it.holdingId }

        val projected = _upcomingDividends.value.flatMap { row ->
            val cur = holdingCurrency[row.holding.id] ?: row.holding.currency
            val logged = loggedByHolding[row.holding.id].orEmpty()

            // Half a payment interval: the widest gap at which two dates can
            // only be the same payment, never two consecutive ones. Derived
            // from the holding's own frequency because a fixed window that is
            // safe for a quarterly payer would delete a monthly payer's next
            // real payment.
            val perYear = (row.info.paymentFrequencyPerYear ?: 4).coerceAtLeast(1)
            val windowMs = (365.0 / perYear / 2.0 * 24 * 60 * 60 * 1000).toLong()

            projectFor(row, projectionStart, horizon)
                // Drop anything the user already has a payment recorded for:
                // the forecast and the auto-import both know about the same
                // announced dividend, and without this the chart adds it twice.
                .filterNot { (ts, _) ->
                    logged.any { kotlin.math.abs(it.paidAtMillis - ts) <= windowMs }
                }
                .map { (ts, amt) -> ts to conv(amt, cur) }
        }
        return received to (projected + futureLogged)
    }

    /**
     * Per-month projected income for the next [months] months, keyed by
     * (year * 100 + zero-based month), for the Dividends tab's FWD chart.
     */
    fun projectedIncomeByMonth(
        months: Int = 12,
        currency: String? = null
    ): Map<Int, Double> {
        val wanted = currency?.uppercase()
        val now = System.currentTimeMillis()
        // One extra month of horizon: the final bar covers a whole calendar
        // month and a payment late in it would otherwise be clipped.
        val horizon = Calendar.getInstance().apply {
            timeInMillis = now
            add(Calendar.MONTH, months + 1)
        }.timeInMillis

        val out = HashMap<Int, Double>()
        val cal = Calendar.getInstance()
        for (row in _upcomingDividends.value) {
            if (wanted != null &&
                row.holding.currency.uppercase().ifBlank { "CAD" } != wanted
            ) continue
            for ((ts, amount) in projectFor(row, now, horizon)) {
                cal.timeInMillis = ts
                val key = cal.get(Calendar.YEAR) * 100 + cal.get(Calendar.MONTH)
                out[key] = (out[key] ?: 0.0) + amount
            }
        }
        return out
    }

    /**
     * Same as [projectedIncomeByMonth], but every holding contributes —
     * converted into the reporting currency — instead of filtering to a
     * single one. What the Dividends tab's FWD chart uses now: a forward
     * income projection that stops at the portfolio's currency boundary
     * isn't the portfolio's forward income.
     */
    fun projectedIncomeByMonthInBase(months: Int = 12): Map<Int, Double> =
        projectedIncomeByMonthAndHoldingInBase(months)
            .mapValues { (_, byHolding) -> byHolding.values.sum() }

    /**
     * The same forward projection, split by holding: month bucket → holding id
     * → amount, all in the reporting currency.
     *
     * The FWD chart stacks one segment per holding, so it needs the breakdown
     * rather than the month's total; [projectedIncomeByMonthInBase] is now just
     * this summed, which keeps the stacked bars and any total computed from
     * them arithmetically identical by construction rather than by hoping two
     * separate loops stay in step.
     */
    fun projectedIncomeByMonthAndHoldingInBase(months: Int = 12): Map<Int, Map<Long, Double>> {
        val now = System.currentTimeMillis()
        val horizon = Calendar.getInstance().apply {
            timeInMillis = now
            add(Calendar.MONTH, months + 1)
        }.timeInMillis

        val out = HashMap<Int, HashMap<Long, Double>>()
        val cal = Calendar.getInstance()
        for (row in _upcomingDividends.value) {
            val cur = row.holding.currency
            for ((ts, amount) in projectFor(row, now, horizon)) {
                cal.timeInMillis = ts
                val key = cal.get(Calendar.YEAR) * 100 + cal.get(Calendar.MONTH)
                val bucket = out.getOrPut(key) { HashMap() }
                bucket[row.holding.id] =
                    (bucket[row.holding.id] ?: 0.0) + amountInBase(amount, cur)
            }
        }
        return out
    }

    /**
     * Projected income for the next [years] calendar years, bucketed by year
     * and split per holding — what the projected rows on "Historical Income ▸
     * Yearly" extend into, so a fund's future distributions read as the same
     * seasonal, per-holding stack as its past ones rather than a flat guess
     * bolted onto the end of a real chart.
     */
    fun projectedIncomeByYearAndHoldingInBase(years: Int): Map<Int, Map<Long, Double>> {
        val now = System.currentTimeMillis()
        val horizon = Calendar.getInstance().apply {
            timeInMillis = now
            add(Calendar.YEAR, years)
        }.timeInMillis

        val out = HashMap<Int, HashMap<Long, Double>>()
        val cal = Calendar.getInstance()
        for (row in _upcomingDividends.value) {
            val cur = row.holding.currency
            for ((ts, amount) in projectFor(row, now, horizon)) {
                cal.timeInMillis = ts
                val year = cal.get(Calendar.YEAR)
                val bucket = out.getOrPut(year) { HashMap() }
                bucket[row.holding.id] =
                    (bucket[row.holding.id] ?: 0.0) + amountInBase(amount, cur)
            }
        }
        return out
    }

    // ── Holding detail (rich stats for the holding screen) ───────────────────

    /**
     * Assembles the full stat sheet for one holding: live quote, position
     * maths, dividend metrics and the trailing-yield series.
     *
     * Network work happens on IO and the result is returned in one shot so the
     * screen renders in a single recomposition instead of flickering through
     * partial states.
     */
    suspend fun loadHoldingDetail(holdingId: Long): HoldingDetail? = withContext(Dispatchers.IO) {
        val holding = holdings.value.firstOrNull { it.id == holdingId } ?: return@withContext null
        val ticker  = holding.ticker
        val now     = System.currentTimeMillis()

        // Every figure below covers the WHOLE position — all accounts holding
        // this security, not just the row that was tapped. Three rows in Room
        // are one thing the user owns, and the top of this screen has to agree
        // with the total the Portfolio list printed.
        val siblings = holdings.value.filter { it.securityKey == holding.securityKey }
        val positionUnits = siblings.sumOf { it.units }
        // Summed only when every slice has one: a partial sum would read as the
        // whole position's cost and understate it.
        val positionCost =
            if (siblings.all { it.costBasis != null }) siblings.sumOf { it.costBasis ?: 0.0 } else null

        // fetchQuoteDetailed, not fetchQuote: the fast path answers from
        // whichever provider replies first, and Finnhub carries no 52-week
        // range — which is why the 52-week band was missing on US holdings
        // and present on Canadian ones.
        val fetched = ticker?.let { repository.fetchQuoteDetailed(it) }

        // Price and previous close come from the SAME cache the Portfolio
        // list reads (`_quotes`), not from this screen's own live answer to
        // the same question.
        //
        // This used to take fetched.price/previousClose directly. Two live
        // calls to the same undocumented endpoint, minutes apart, do not
        // always agree — previousClose in particular has been seen to differ
        // between two calls for the very same closed session — so the
        // Portfolio screen and this one could print two different day-change
        // percentages for one holding, off the same closing price. Only the
        // stat fields the cache does not carry (52-week range, volume, logo,
        // instrument type) are taken from the fresh call.
        val cached = ticker?.let { quotes.value[it] }
        val quote = when {
            cached == null -> fetched
            fetched == null -> cached
            else -> fetched.copy(
                price = cached.price,
                previousClose = cached.previousClose ?: fetched.previousClose,
                currency = cached.currency ?: fetched.currency
            )
        }
        val price = quote?.price ?: holding.lastKnownPrice ?: holding.manualPrice ?: 0.0

        // Repair a misfiled holding from the quote we already have. A position
        // created before the search results were classified correctly can be
        // sitting on the wrong type — AAPL stored as an ETF — and opening its
        // detail screen is where that gets noticed, so it is also where it gets
        // fixed, rather than leaving the user to delete and re-add it.
        if (quote != null) repository.applyQuoteMetadata(holdingId, quote)

        // The SAME payment record the Dividends tab shows for this
        // security, not a second live answer to the same question.
        //
        // This used to call resolveUpcoming(ticker) fresh every time the
        // screen opened, independent of `_upcomingDividends` — the list
        // refreshUpcomingDividends() populates and the Dividends tab reads.
        // Both went through the same merge logic (dividendhistory.org, Yahoo
        // fallback) but as two SEPARATE live calls, which a feed that has
        // already been seen to disagree with itself between calls (see the
        // price merge above) can still answer differently for — so a
        // holding's own detail page could show a different next-payment
        // date, or a different monthly shape, than the tab built to
        // summarise it. Falls back to a fresh fetch only when the cache has
        // not covered this security yet — a holding just added, before the
        // next background refresh.
        val cachedRow = _upcomingDividends.value.firstOrNull { it.holding.securityKey == holding.securityKey }
        val upcoming: UpcomingDividend?
        val rawEvents: List<Pair<Long, Double>>
        if (cachedRow != null) {
            upcoming = cachedRow.info
            rawEvents = cachedRow.history
        } else {
            val resolved = ticker?.let { resolveUpcoming(it) }
            upcoming = resolved?.first
            rawEvents = resolved?.second ?: emptyList()
        }
        // Newest-first for the change/growth helpers below.
        val events = rawEvents.sortedByDescending { it.first }

        // ── Position ──
        // The listing's own currency, preferring the live quote over the
        // stored value, and the rates needed to express anything in it.
        // Fetched up front because both the dividend total below and the
        // portfolio weight further down convert against it — and a conversion
        // that runs before its rate has landed silently returns the amount
        // unchanged, which is the failure mode that made a SEK position read
        // as though it were priced in Canadian dollars.
        val holdingCurrency = quote?.currency?.uppercase() ?: holding.currency

        // Payments read straight from the DB, NOT from the `dividends`
        // StateFlow. That flow is stateIn(WhileSubscribed), and the only
        // screen that collects it is the Dividends tab — reached from the
        // holdings list, `.value` is still the initial empty list, so every
        // figure derived from it would quietly read zero.
        // Payments logged against ANY account's slice — the received total on
        // this screen is the position's, like every other figure on it.
        val logged = siblings.flatMap { repository.observeDividendsForHolding(it.id).first() }

        ca.tristan.portfolio.data.FxRates.refresh(
            holdings.value.map { it.currency } +
                logged.map { it.currency } +
                holdingCurrency,
            _baseCurrency.value
        )

        val marketValue = price * positionUnits
        val avgCost     = positionCost?.takeIf { positionUnits > 0 }?.div(positionUnits)
        val contributions = positionCost
        val priceReturn = contributions?.let { marketValue - it }
        val priceReturnPct = contributions?.takeIf { it > 0 }?.let { priceReturn!! / it * 100.0 }

        // Dividends received, expressed in the holding's OWN currency — which
        // is what every other figure on the detail screen is in, and what the
        // screen's single converter expects.
        //
        // dividendTotalForHolding() is a bare SQL SUM with no idea what
        // currency each row is in, so a payment logged in CAD against a
        // Stockholm listing was being added to a kronor price return as
        // though the two were the same unit. That lands in Total Return,
        // which is the number people actually judge a position by.
        // Payments actually received. A dividend the auto-import recorded but
        // that has not been paid yet is not a return — counting it would put
        // money into Total Return weeks before it exists.
        val received = logged.filter { it.paidAtMillis <= now }.sumOf {
            ca.tristan.portfolio.data.FxRates.convert(
                it.amount, it.currency.uppercase().ifBlank { holdingCurrency }, holdingCurrency
            )
        }
        val totalReturn = priceReturn?.plus(received)
        val totalReturnPct = contributions?.takeIf { it > 0 }?.let { totalReturn!! / it * 100.0 }

        // Both sides of this division have to be in the same currency.
        //
        // `marketValue` above is in the holding's own currency (the whole
        // detail screen is, and converts once at render time), while
        // valueOfHolding() deliberately converts into the reporting currency
        // because it exists to be summed across the portfolio. Dividing one by
        // the other compared, for a Stockholm listing in a CAD portfolio,
        // kronor against dollars: 9,500 SEK over an 88,000 CAD portfolio read
        // as 10.7% for a position actually worth about 1.6% of it — and the
        // error scaled with how weak the holding's currency was, so the
        // further a holding sat from the reporting currency the more wrong its
        // weight looked. (Rates for every currency involved — this holding's
        // and every other holding's, since the total sums all of them — were
        // fetched up front alongside `holdingCurrency`.)
        val marketValueInBase = ca.tristan.portfolio.data.FxRates.convert(
            marketValue, holdingCurrency, _baseCurrency.value
        )
        val portfolioTotal = holdings.value.sumOf { repository.valueOfHolding(it) }
        // The position's weight, not one slice's.
        val weight = if (portfolioTotal > 0) marketValueInBase / portfolioTotal * 100.0 else null

        // ── Dividends ──
        val ttm = HoldingDetailMath.trailingTwelveMonths(events, now)
        val freq = HoldingDetailMath.inferFrequency(events) ?: upcoming?.paymentFrequencyPerYear
        val yieldTtm = if (price > 0 && ttm > 0) ttm / price * 100.0 else null
        val yieldOnCost = if (avgCost != null && avgCost > 0 && ttm > 0) ttm / avgCost * 100.0 else null
        val recentChange = HoldingDetailMath.mostRecentChangePct(events)

        // Trailing distribution per unit — the same twelve months the TTM yield
        // is computed over, so the two figures on screen agree. It used to be
        // the next payment multiplied by the frequency, which is wrong for any
        // seasonal payer: XEQT's small Q3 × 4 reported CA$0.40 a unit against a
        // 1.60 % yield on a CA$44 price, a figure that implies CA$0.72.
        val annualPerUnit = when {
            ttm > 0 -> ttm
            else -> upcoming?.estimatedAnnualRate
        }

        // Forward income is projected payment by payment instead of annualising
        // one of them, so a lumpy distribution lands in the right months and
        // sums to the right year.
        val forwardPerUnit = ca.tristan.portfolio.data.DividendForecast.forwardAnnualPerUnit(
            history = events,
            upcoming = upcoming,
            nowMillis = now,
            growthRate = DISTRIBUTION_GROWTH_RATE
        ) ?: annualPerUnit

        val horizon = Calendar.getInstance().apply {
            timeInMillis = now
            add(Calendar.YEAR, 11)
        }.timeInMillis
        // Half a payment interval: the widest window in which two dates can
        // only be the SAME payment, never two consecutive ones.
        //
        // A fixed window can't work here, because the safe distance depends on
        // how often the holding pays. ±20 days would correctly collapse a
        // quarterly payer's duplicates (90 days apart) and would wrongly delete
        // a monthly payer's next real payment (30 days apart). Deriving it from
        // the frequency makes it right for both.
        val paymentsPerYear = upcoming?.paymentFrequencyPerYear ?: 4
        val dedupeWindowMs =
            (365.0 / paymentsPerYear.coerceAtLeast(1) / 2.0 * 24 * 60 * 60 * 1000).toLong()

        /** True when [ts] falls on a payment the user already has on record. */
        fun alreadyLogged(ts: Long): Boolean =
            logged.any { kotlin.math.abs(it.paidAtMillis - ts) <= dedupeWindowMs }

        val projectedFlows = if (positionUnits > 0) {
            ca.tristan.portfolio.data.DividendForecast.project(
                history = events,
                upcoming = upcoming,
                fromMillis = now,
                untilMillis = horizon,
                growthRate = DISTRIBUTION_GROWTH_RATE
            )
                .map { it.atMillis to it.perUnit * positionUnits }
                // Drop anything the user already has a payment recorded for.
                //
                // The two sources overlap: the forecast projects forward from
                // the fund's own distribution history, while the dividend
                // auto-import writes announced payments into the user's
                // records — including ones not yet paid. A single announced
                // quarterly dividend therefore arrived twice, once as
                // "Received" and once as "Estimated", and the monthly chart
                // faithfully added them. That is how a BNS holding paying
                // $1,140 a quarter drew a $2,280 October bar.
                .filterNot { alreadyLogged(it.first) }
        } else emptyList()

        // Payout schedule = the next expected payment (forecast automatically)
        // plus payments the user actually has on record.
        //
        // It deliberately does NOT list the fund's own distribution history.
        // Those are payments the fund made, not payments this user received —
        // buying XEQT today does not mean you were paid its June distribution.
        // Showing them made the schedule read like income that had been banked.
        // Anything before the holding existed has to be entered by hand under
        // Dividends Received.
        //
        // (`logged` is loaded once near the top of this function — the
        // position maths needs it too, for the dividend half of Total Return.)
        val payouts = buildList {
            upcoming?.let { u ->
                val amt = u.perPaymentAmount
                // Same overlap as the chart above, and the same fix: if the
                // user already has this payment on record, listing the
                // forecast beside it shows one dividend as two.
                if (amt != null && u.exDividendDateMillis != null &&
                    !alreadyLogged(u.exDividendDateMillis)
                ) {
                    add(
                        PayoutRow(
                            exDateMs = u.exDividendDateMillis,
                            // The date the cash actually lands, where the
                            // calendar published one. Never derived from the
                            // ex-date by adding a guessed number of days.
                            payDateMs = u.payDateMillis,
                            amountPerUnit = amt,
                            totalForPosition = amt * positionUnits,
                            isUpcoming = true
                        )
                    )
                }
            }
            logged.sortedByDescending { it.paidAtMillis }.take(24).forEach { p ->
                add(
                    PayoutRow(
                        // A logged payment records the day the money arrived.
                        // There is no ex-date to know for it, and inventing one
                        // would be worse than leaving it out.
                        exDateMs = null,
                        payDateMs = p.paidAtMillis,
                        amountPerUnit = p.perUnit
                            ?: if (positionUnits > 0) p.amount / positionUnits else 0.0,
                        totalForPosition = p.amount,
                        isUpcoming = false
                    )
                )
            }
        }.sortedByDescending { it.displayDateMs ?: 0L }

        // Trailing-yield series over 10 years of weekly closes. A longer
        // lookback gives a steadier reference band, and this only ever
        // returns as much as the provider actually has — a fund younger than
        // ten years just gets its whole history, the same graceful
        // degradation the 5-year window always had.
        val bars = ticker?.let {
            runCatching { repository.fetchHistory(it, "10y", "1wk") }.getOrDefault(emptyList())
        } ?: emptyList()
        val yieldHistory = HoldingDetailMath.buildYieldHistory(bars, events)
        val tenYearAvgYield = yieldHistory.takeIf { it.isNotEmpty() }?.map { it.yieldPercent }?.average()

        HoldingDetail(
            holding = holding,
            slices = siblings.sortedByDescending { it.units },
            quote = quote,
            upcoming = upcoming,
            payouts = payouts,
            yieldHistory = yieldHistory,
            // Oldest-first, so the history chart reads left-to-right in time.
            dividendEvents = events.sortedBy { it.first },
            marketValue = marketValue,
            averageCost = avgCost,
            totalContributions = contributions,
            priceReturn = priceReturn,
            priceReturnPct = priceReturnPct,
            totalReturn = totalReturn,
            totalReturnPct = totalReturnPct,
            portfolioWeightPct = weight,
            trailingAnnualPerUnit = annualPerUnit,
            yieldTtmPct = yieldTtm,
            yieldOnCostPct = yieldOnCost,
            divGrowth1YPct = HoldingDetailMath.dividendCagr(events, 1, now),
            divGrowth5YPct = HoldingDetailMath.dividendCagr(events, 5, now),
            divChangeRecentPct = recentChange?.first,
            divChangeRecentLabel = recentChange?.second?.let {
                java.text.SimpleDateFormat("MMM ''yy", java.util.Locale.getDefault())
                    .format(java.util.Date(it))
            },
            divChangeVs1YPct = HoldingDetailMath.changeVsYearAgoPct(events),
            frequencyPerYear = freq,
            allTimeReceived = received,
            estimatedAnnualIncome = forwardPerUnit?.times(positionUnits),
            tenYearAvgYieldPct = tenYearAvgYield,
            // Converted into the holding's own currency, exactly as
            // `allTimeReceived` above is: these feed the detail screen's
            // income chart, which labels its bars in that currency. A payment
            // logged in CAD against a Stockholm listing was otherwise drawn
            // as though it were kronor — the same mismatch as the total, just
            // one field further down.
            // Split on the date, not on where the row came from.
            //
            // The dividend auto-import records announced-but-unpaid dividends,
            // so a "received" payment can sit in the future — and the chart
            // then coloured money that has not arrived as income already
            // banked. A payment dated after today is an expectation whatever
            // table it lives in, so it is drawn as estimated.
            receivedFlows = logged
                .filter { it.paidAtMillis <= now }
                .map {
                    it.paidAtMillis to ca.tristan.portfolio.data.FxRates.convert(
                        it.amount, it.currency.uppercase().ifBlank { holdingCurrency }, holdingCurrency
                    )
                },
            projectedFlows = projectedFlows + logged
                .filter { it.paidAtMillis > now }
                .map {
                    it.paidAtMillis to ca.tristan.portfolio.data.FxRates.convert(
                        it.amount, it.currency.uppercase().ifBlank { holdingCurrency }, holdingCurrency
                    )
                }
        )
    }

    // ── Dividend calendar sync ────────────────────────────────────────────────

    /**
     * Scans the Nasdaq dividend calendar for the next 30 days and records any
     * payment that has ALREADY been paid on a holding the user owns.
     *
     * Deliberately narrow, and invoked by hand rather than on screen open. The
     * history auto-import that used to run here wrote every past distribution
     * for every ticker into Dividends Received on the assumption the user had
     * held the whole position for the whole history — an income record that
     * could not be reconciled against a statement, and that took manual row
     * deletion to undo.
     */
    fun syncUpcomingFromNasdaqCalendar() = viewModelScope.launch {
        val tickeredHoldings = holdings.value.filter { !it.ticker.isNullOrBlank() }
        if (tickeredHoldings.isEmpty()) {
            _syncProgress.value = ImportState.Done(0, 0)
            return@launch
        }
        val ownedSymbols = tickeredHoldings.associateBy { it.ticker!! }
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        var imported = 0
        var skipped  = 0
        val cal = Calendar.getInstance()
        // Scan today + next 29 days (30 days total)
        for (dayOffset in 0..29) {
            val dateStr = sdf.format(cal.time)
            _syncProgress.value = ImportState.Running(dayOffset + 1, 30, dateStr)
            val rows = withContext(Dispatchers.IO) {
                ca.tristan.portfolio.net.YahooQuoteClient.fetchNasdaqDividendCalendar(dateStr)
            }
            for (row in rows) {
                val holding = ownedSymbols[row.symbol] ?: continue
                val payMs = row.payDateMs ?: row.exDateMs ?: continue
                // Same rule as the history import: a payment that has not
                // happened is not income received. This scan looks 30 days
                // AHEAD, so without this guard it exists only to write future
                // dividends into the received table — which the forecast
                // already covers, correctly, without pretending the money has
                // arrived.
                if (payMs > System.currentTimeMillis()) { skipped++; continue }
                val amount = (row.amountPerShare ?: continue) * holding.units
                val existing = dividends.value.filter { it.holdingId == holding.id }
                val duplicate = existing.any { ex ->
                    kotlin.math.abs(ex.paidAtMillis - payMs) < 7L * 24 * 3600 * 1000
                }
                if (duplicate) { skipped++; continue }
                repository.addDividendPayment(
                    holdingId    = holding.id,
                    paidAtMillis = payMs,
                    amount       = amount,
                    perUnit      = row.amountPerShare,
                    currency     = holding.currency,
                    note         = "Nasdaq calendar"
                )
                imported++
            }
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        _syncProgress.value = ImportState.Done(imported, skipped)
    }

    /** Dismiss the sync result banner so it returns to Idle. */
    fun dismissSyncResult() { _syncProgress.value = ImportState.Idle }

    // ── Performance reports ──────────────────────────────────────────────────

    private val _reportOptions = MutableStateFlow(ca.tristan.portfolio.report.ReportOptions())
    val reportOptions: StateFlow<ca.tristan.portfolio.report.ReportOptions> = _reportOptions

    private val _reportState = MutableStateFlow<ReportUiState>(ReportUiState.Idle)
    val reportState: StateFlow<ReportUiState> = _reportState

    fun updateReportOptions(
        transform: (ca.tristan.portfolio.report.ReportOptions) -> ca.tristan.portfolio.report.ReportOptions
    ) {
        _reportOptions.value = transform(_reportOptions.value)
        // Any option change invalidates a computed report — leaving the previous
        // numbers on screen under new settings would misrepresent them.
        if (_reportState.value is ReportUiState.Ready) _reportState.value = ReportUiState.Idle
    }

    /**
     * Computes the performance report for the current options.
     *
     * Price history is fetched once per ticker, so this touches the network and
     * can take a few seconds on a large portfolio; the screen shows a running
     * state while it works.
     */
    fun generateReport() = viewModelScope.launch {
        _reportState.value = ReportUiState.Running
        try {
            val opts = _reportOptions.value
            val holds = holdings.value
            if (holds.isEmpty()) {
                _reportState.value = ReportUiState.Error(
                    "Add at least one holding before generating a report."
                )
                return@launch
            }
            // Report in the user's chosen reporting currency — the same one
            // the Portfolio tab's headline total uses. It used to be whichever
            // currency the most holdings happened to be in, which with two
            // holdings is a coin toss and can change the whole report's units
            // when a position is added.
            val currency = _baseCurrency.value

            // Rates fetched before anything is summed. Every figure in the
            // report is a cross-holding total, and they were previously added
            // at face value across currencies.
            ca.tristan.portfolio.data.FxRates.refresh(holds.map { it.currency }, currency)

            val report = ca.tristan.portfolio.report.PerformanceCalculator.build(
                options = opts,
                accounts = accounts.value,
                allHoldings = holds,
                allTransactions = transactions.value,
                allDividends = dividends.value,
                currency = currency,
                fxRate = { code ->
                    ca.tristan.portfolio.data.FxRates.rateOrNull(code, currency) ?: 1.0
                }
            ) { ticker, range, interval ->
                repository.fetchHistory(ticker, range, interval)
            }
            _reportState.value = ReportUiState.Ready(report)
        } catch (t: Throwable) {
            _reportState.value = ReportUiState.Error(
                t.message ?: "Could not build the report. Check your connection and try again."
            )
        }
    }

    /**
     * Writes the computed report to a PDF in the cache directory and hands the
     * file back so the caller can open the system share sheet for it.
     */
    fun exportReportPdf(
        context: android.content.Context,
        onDone: (java.io.File?) -> Unit
    ) = viewModelScope.launch {
        // Premium-gated — see the matching check on the Reports screen's
        // Export button. Checked again here so this call isn't only as safe
        // as whichever screen happens to invoke it.
        if (!ca.tristan.portfolio.billing.Subscriptions.isPremium.value) {
            onDone(null)
            return@launch
        }
        val ready = _reportState.value as? ReportUiState.Ready
        if (ready == null) { onDone(null); return@launch }
        val opts = _reportOptions.value
        val label = opts.holdingId?.let { id ->
            holdings.value.firstOrNull { it.id == id }?.let { it.ticker ?: it.name }
        }
        val file = withContext(Dispatchers.IO) {
            runCatching {
                ca.tristan.portfolio.report.PdfReportGenerator.generate(
                    context, ready.report, opts, label
                )
            }.getOrNull()
        }
        onDone(file)
    }

    /**
     * The same report as a spreadsheet.
     *
     * Premium-gated alongside the PDF: both are the "take the report away with
     * you" feature, and gating one while leaving the other open would make the
     * paywall a formality.
     */
    fun exportReportCsv(
        context: android.content.Context,
        onDone: (java.io.File?) -> Unit
    ) = viewModelScope.launch {
        if (!ca.tristan.portfolio.billing.Subscriptions.isPremium.value) {
            onDone(null)
            return@launch
        }
        val ready = _reportState.value as? ReportUiState.Ready
        if (ready == null) { onDone(null); return@launch }
        val opts = _reportOptions.value
        val label = opts.holdingId?.let { id ->
            holdings.value.firstOrNull { it.id == id }?.let { it.ticker ?: it.name }
        }
        val file = withContext(Dispatchers.IO) {
            runCatching {
                ca.tristan.portfolio.report.CsvReportGenerator.generate(
                    context, ready.report, opts, label
                )
            }.getOrNull()
        }
        onDone(file)
    }

    fun clearReport() { _reportState.value = ReportUiState.Idle }

    // ── Cloud Sync (manual, triggered after watching a rewarded ad) ───────────

    /**
     * Pushes the current portfolio snapshot to Firestore for the signed-in user.
     * Called from the Menu screen after the user watches a rewarded ad.
     */
    // ── Cloud backup & restore ────────────────────────────────────────────

    /** What the Menu's cloud rows are currently doing. */
    private val _cloudState = MutableStateFlow<CloudState>(CloudState.Idle)
    val cloudState: StateFlow<CloudState> = _cloudState

    fun dismissCloudState() { _cloudState.value = CloudState.Idle }

    /**
     * Uploads the whole portfolio.
     *
     * Previously this sent holdings and transactions only, and with a reduced
     * set of fields — no currency, no cost basis, no accounts at all. A restore
     * from that would have rebuilt a portfolio that looked right and valued
     * wrong, which is worse than no restore. Every table the user can edit now
     * goes up, whole.
     */
    fun syncPortfolioToCloud() = viewModelScope.launch {
        if (ca.tristan.portfolio.firebase.FirebaseManager.currentUser == null) {
            _cloudState.value = CloudState.Error("Sign in first — a backup is stored against your account.")
            return@launch
        }
        // Premium-gated. The Menu screen already hides this behind the
        // paywall, but that's a UI convenience — someone could still reach
        // this call directly (a stale screen, a future entry point), so the
        // entitlement is checked again here rather than trusted from above.
        if (!ca.tristan.portfolio.billing.Subscriptions.isPremium.value) {
            _cloudState.value = CloudState.Error("Cloud backup is a Premium feature.")
            return@launch
        }
        _cloudState.value = CloudState.Working("Backing up…")
        try {
            val payload = repository.backupPayload()
            val at = System.currentTimeMillis()
            for ((key, rows) in payload) {
                ca.tristan.portfolio.firebase.FirebaseManager.syncToCloud(
                    key,
                    mapOf(
                        "list" to rows,
                        "syncedAt" to at,
                        "formatVersion" to ca.tristan.portfolio.data.CloudBackup.FORMAT_VERSION
                    )
                )
            }
            // And the cross-platform copy, beside the native one.
            //
            // Best-effort on purpose: the native backup above is what this
            // device restores from, and it has already succeeded by the time
            // we get here. A failure to write the interchange copy costs a
            // restore on the OTHER platform, which is worth reporting but is
            // not worth turning a successful backup into a failed one.
            val interchangeWritten = runCatching {
                ca.tristan.portfolio.firebase.FirebaseManager.syncToCloud(
                    ca.tristan.portfolio.data.PortfolioInterchange.DOCUMENT_NAME,
                    // Same instant as the native docs' "syncedAt" above (`at`),
                    // not a fresh timestamp — see interchangePayload's doc
                    // comment for why that matters on restore.
                    repository.interchangePayload(writtenAt = at)
                )
            }.isSuccess

            prefs.edit().putLong(KEY_LAST_BACKUP_AT, at).apply()
            _lastBackupAt.value = at
            val holdings = payload["holdings"]?.size ?: 0
            _cloudState.value = CloudState.Done(
                buildString {
                    append("Backed up $holdings holding(s) and everything attached to them.")
                    if (!interchangeWritten) {
                        append("\n\nThe copy your iPhone reads couldn't be written. ")
                        append("This device will still restore normally; try the backup again ")
                        append("if you want it available on iOS.")
                    }
                }
            )
        } catch (e: Exception) {
            _cloudState.value = CloudState.Error("Backup failed: ${e.message ?: "unknown error"}")
        }
    }

    private val _lastBackupAt = MutableStateFlow(prefs.getLong(KEY_LAST_BACKUP_AT, 0L))
    val lastBackupAt: StateFlow<Long> = _lastBackupAt

    /** Holdings currently on this device — the number the restore warning quotes. */
    suspend fun localHoldingCount(): Int = repository.localHoldingCount()

    /**
     * Pulls the cloud backup back down, replacing what is on this device.
     *
     * Destructive by design — see PortfolioRepository.restoreFromBackup for why
     * merging is not offered. The caller is responsible for confirming with the
     * user first; this does not ask.
     */
    fun restorePortfolioFromCloud() = viewModelScope.launch {
        if (ca.tristan.portfolio.firebase.FirebaseManager.currentUser == null) {
            _cloudState.value = CloudState.Error("Sign in first — the backup is stored against your account.")
            return@launch
        }
        // Premium-gated — see the matching check in syncPortfolioToCloud().
        if (!ca.tristan.portfolio.billing.Subscriptions.isPremium.value) {
            _cloudState.value = CloudState.Error("Cloud backup is a Premium feature.")
            return@launch
        }
        _cloudState.value = CloudState.Working("Restoring…")
        try {
            val fm = ca.tristan.portfolio.firebase.FirebaseManager
            val cb = ca.tristan.portfolio.data.CloudBackup
            val ix = ca.tristan.portfolio.data.PortfolioInterchange

            val nativeHoldingsDoc = fm.readFromCloud("holdings")
            var accounts = cb.rows(fm.readFromCloud("accounts"))
            var holdings = cb.rows(nativeHoldingsDoc)
            var transactions = cb.rows(fm.readFromCloud("transactions"))
            var dividends = cb.rows(fm.readFromCloud("dividends"))
            var watchlist = cb.rows(fm.readFromCloud("watchlist"))

            // The cross-platform copy, which the iOS build writes beside its
            // own backup and this one writes beside the native rows above.
            //
            // Preferred ONLY when it is strictly newer than the native backup.
            // Both are written in the same breath by whichever app made them,
            // so "newer" is really "the last backup came from the other phone".
            // Ties and unknown timestamps go to the native rows, because those
            // are the ones that have been restoring correctly for a year and
            // this format has not been proven on real data yet.
            val interchangeDoc = fm.readFromCloud(ix.DOCUMENT_NAME)
            val interchange = ix.decode(interchangeDoc)
            val nativeAt = cb.longOf(nativeHoldingsDoc["syncedAt"]) ?: 0L
            val interchangeAt = ix.writtenAt(interchangeDoc) ?: 0L
            val useInterchange = interchange != null &&
                !interchange.summary.isEmpty &&
                (holdings.isEmpty() || interchangeAt > nativeAt)

            var crossPlatformNote: String? = null
            if (useInterchange && interchange != null) {
                accounts = interchange.accounts
                holdings = interchange.holdings
                transactions = interchange.transactions
                dividends = interchange.dividends
                watchlist = interchange.watchlist
                crossPlatformNote = buildString {
                    append("This came from your ")
                    append(if (interchange.writtenBy == "ios") "iPhone" else "other device")
                    append(" — it was the more recent backup.")
                    if (interchange.summary.skipped > 0) {
                        append("\n\n${interchange.summary.skipped} row(s) in it couldn't be read ")
                        append("and were skipped. Check your holdings against your records.")
                    }
                }
            }

            // Nothing up there. Stop BEFORE the wipe — restoring an empty
            // backup over a real portfolio would delete everything and report
            // success, which is the single worst thing this feature could do.
            if (holdings.isEmpty() && watchlist.isEmpty()) {
                _cloudState.value = CloudState.Error(
                    "No backup found for this account. Nothing was changed."
                )
                return@launch
            }

            val result = repository.restoreFromBackup(
                accounts, holdings, transactions, dividends, watchlist
            )
            _cloudState.value = CloudState.Done(
                buildString {
                    append("Restored ${result.holdings} holding(s), ${result.transactions} transaction(s), ")
                    append("${result.dividends} dividend(s) and ${result.watchlist} watchlist item(s).")
                    crossPlatformNote?.let { append("\n\n$it") }
                    if (result.rebuiltCostBasis > 0) {
                        append("\n\nCost basis for ${result.rebuiltCostBasis} holding(s) was rebuilt from ")
                        append("their transaction history — the backup didn't carry it. Check the average ")
                        append("cost on those holdings against your records.")
                    }
                }
            )
            refreshWatchlistQuotes()
        } catch (e: Exception) {
            _cloudState.value = CloudState.Error("Restore failed: ${e.message ?: "unknown error"}")
        }
    }

    // ── Backup to a file — no account, no network ─────────────────────────

    /**
     * Writes the whole portfolio to a document the user picked.
     *
     * Shares [CloudState] with the cloud rows on purpose: from the user's side
     * "backing up" is one activity with two destinations, and one state means
     * one progress indicator and one result dialog rather than two sets that
     * can both be on screen.
     */
    fun exportBackupTo(uri: android.net.Uri) = viewModelScope.launch {
        _cloudState.value = CloudState.Working("Exporting…")
        try {
            val payload = repository.backupPayload()
            // The cross-platform copy rides along in the same file, so a backup
            // exported here restores on the iPhone. Best-effort: a file that
            // restores on this phone and not the other one beats no file.
            val interchange = runCatching { repository.interchangePayload() }.getOrNull()
            val text = ca.tristan.portfolio.data.LocalBackup.encode(payload, interchange)
            withContext(Dispatchers.IO) {
                val resolver = getApplication<Application>().contentResolver
                // "wt" truncates. Plain "w" leaves any trailing bytes of a
                // longer previous file in place, which produces a file that is
                // valid JSON followed by garbage — and fails to parse on the
                // day it is needed.
                resolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) }
                    ?: throw IllegalStateException("Couldn't open that location for writing.")
            }
            val holdings = payload["holdings"]?.size ?: 0
            _cloudState.value = CloudState.Done(
                "Exported $holdings holding(s) and everything attached to them. " +
                    "Keep the file somewhere you'll still have if this phone doesn't come back."
            )
        } catch (e: Exception) {
            _cloudState.value = CloudState.Error("Export failed: ${e.message ?: "unknown error"}")
        }
    }

    /**
     * Reads a backup file WITHOUT applying it, so the caller can confirm first.
     *
     * Restoring replaces everything, so the counts in the confirmation have to
     * come from the file itself rather than from a promise about it.
     */
    suspend fun readBackupFile(uri: android.net.Uri): Map<String, List<Map<String, Any?>>>? =
        withContext(Dispatchers.IO) {
            try {
                val text = getApplication<Application>().contentResolver
                    .openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                    ?: return@withContext null
                ca.tristan.portfolio.data.LocalBackup.decode(text)
            } catch (_: Exception) {
                null
            }
        }

    /**
     * Applies a file already read by [readBackupFile]. Destructive — the caller
     * confirms; this does not ask.
     */
    fun restorePortfolioFromFile(
        sections: Map<String, List<Map<String, Any?>>>
    ) = viewModelScope.launch {
        _cloudState.value = CloudState.Working("Restoring…")
        try {
            val holdings = sections["holdings"].orEmpty()
            val watchlist = sections["watchlist"].orEmpty()

            // Same guard as the cloud path: an empty backup applied over a real
            // portfolio would delete everything and report success.
            if (holdings.isEmpty() && watchlist.isEmpty()) {
                _cloudState.value = CloudState.Error(
                    "That file has no holdings or watchlist in it. Nothing was changed."
                )
                return@launch
            }

            val result = repository.restoreFromBackup(
                sections["accounts"].orEmpty(),
                holdings,
                sections["transactions"].orEmpty(),
                sections["dividends"].orEmpty(),
                watchlist
            )
            _cloudState.value = CloudState.Done(
                buildString {
                    append("Restored ${result.holdings} holding(s), ${result.transactions} transaction(s), ")
                    append("${result.dividends} dividend(s) and ${result.watchlist} watchlist item(s).")
                    if (result.rebuiltCostBasis > 0) {
                        append("\n\nCost basis for ${result.rebuiltCostBasis} holding(s) was rebuilt from ")
                        append("their transaction history — the backup didn't carry it. Check the average ")
                        append("cost on those holdings against your records.")
                    }
                }
            )
            refreshWatchlistQuotes()
        } catch (e: Exception) {
            _cloudState.value = CloudState.Error("Restore failed: ${e.message ?: "unknown error"}")
        }
    }

}

/** What the Menu's backup and restore rows are doing right now. */
sealed class CloudState {
    object Idle : CloudState()
    data class Working(val message: String) : CloudState()
    data class Done(val message: String) : CloudState()
    data class Error(val message: String) : CloudState()
}

/**
 * How long a portfolio price refresh stays "fresh enough" for a screen that
 * merely re-appeared.
 *
 * Two minutes: long enough that tab-hopping costs nothing, short enough that
 * coming back to the app after a coffee still shows current figures. A pull to
 * refresh ignores it.
 */
private const val PORTFOLIO_FRESHNESS_MILLIS = 2L * 60L * 1000L

private const val KEY_LAST_BACKUP_AT = "last_cloud_backup_at"
private const val KEY_RESIDENCY = "tax_residency"
private const val KEY_RESIDENCY_AUTO = "tax_residency_auto"
private const val KEY_MARGINAL_RATE = "tax_marginal_rate_pct"
private const val KEY_PREF_RATE = "tax_preferential_rate_pct"

/** Lifecycle of the Reports tab. */
sealed class ReportUiState {
    object Idle : ReportUiState()
    object Running : ReportUiState()
    data class Ready(val report: ca.tristan.portfolio.report.PerformanceReport) : ReportUiState()
    data class Error(val message: String) : ReportUiState()
}
