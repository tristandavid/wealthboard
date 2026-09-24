package ca.tristan.portfolio.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.draw.clip
import ca.tristan.portfolio.data.MarketCalendar
import ca.tristan.portfolio.data.MarketIndices
import ca.tristan.portfolio.net.MarketMover
import ca.tristan.portfolio.net.ExtendedQuote
import ca.tristan.portfolio.ui.PortfolioViewModel
import ca.tristan.portfolio.ui.components.WbDimens
import ca.tristan.portfolio.ui.components.TickerLogo
import ca.tristan.portfolio.ui.format.TradeTime
import ca.tristan.portfolio.ui.format.TickerFlag
import ca.tristan.portfolio.ui.theme.GainGreen
import ca.tristan.portfolio.ui.theme.LossRed
import androidx.compose.runtime.produceState
import java.text.DateFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale

private val closureDateFormat: DateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.CANADA)
// Time-only format for the "last traded at" stamp under each price.

// A ticker is crypto if it follows Yahoo's convention (e.g. BTC-USD, ETH-USD)
private fun isCrypto(ticker: String) = ticker.contains("-USD") || ticker.contains("-BTC") || ticker.contains("-ETH")
private fun isFuture(ticker: String) = ticker.endsWith("=F")
private fun isStock(ticker: String) = !isCrypto(ticker) && !isFuture(ticker)

/**
 * Dashboard sub-tabs.
 *
 * "Hot Stocks" replaces the old Top Gainers / Top Losers pair: two tabs that
 * each told half the story and neither of which answered "what is actually
 * moving today". One heat-ranked list built from most-actives plus both mover
 * screens does, and it frees a tab slot in the process.
 */
private enum class StocksTab(val label: String) {
    ACTIVE("Markets"),
    HOT("Hot Stocks"),
    HOLDINGS("My Holdings"),
    CRYPTO("Cryptocurrencies")
}

/**
 * Maps a Yahoo Finance ticker symbol to a country flag emoji.
 * Indices (^…) and crypto (-USD/-BTC/-ETH) get no flag.
 *
 * Delegates to [TickerFlag] rather than keeping a private copy: three screens
 * each grew their own version of this list, they drifted, and a ticker could
 * come out Swedish in search results and American on the row next to it.
 */
private fun flagForTicker(ticker: String, currency: String? = null): String =
    TickerFlag.forTicker(ticker, exchange = null, currency = currency)

/**
 * Row label for a watchlist ticker. Market indices get their real name — a
 * row reading "^GSPTSE" tells the user nothing, "S&P/TSX Composite" does.
 * Everything else keeps its ticker as the headline.
 */
private fun rowTitle(ticker: String): String =
    if (MarketIndices.isIndex(ticker)) MarketIndices.displayName(ticker) else ticker

/**
 * Secondary line under the title: for an index the raw symbol, for a stock the
 * company name Yahoo returned.
 */
private fun rowSubtitle(ticker: String, quoteName: String?): String? =
    if (MarketIndices.isIndex(ticker)) ticker else quoteName

/**
 * Maps a Yahoo Finance exchange name (e.g. "NasdaqGS", "Toronto") to a flag
 * emoji, via the shared [TickerFlag] mapping. Defaults to 🇺🇸.
 */
private fun flagForExchange(exchange: String?): String =
    TickerFlag.forTicker(ticker = "", exchange = exchange)

/**
 * "Markets": a market-closure banner, the indices and watchlist, and a Stocks
 * section with four sub-tabs (Active Stocks / Gainers / Losers /
 * Cryptocurrencies).
 *
 * Reached from Menu rather than the bottom bar, and no longer carries a news
 * feed: News is its own tab now. The feed used to sit below everything here, so
 * neither half of the screen was reachable without scrolling past the other.
 */
@Composable
fun MarketScreen(
    viewModel: PortfolioViewModel,
    onOpenQuote: (Long) -> Unit,
    /**
     * Opens a POSITION rather than a quote, for the My Holdings tab.
     *
     * Defaulted so existing call sites keep compiling; a host that does not
     * supply it leaves that tab behaving as it did.
     */
    onOpenHolding: (Long) -> Unit = {},
    /** Back to Menu, which is where this screen is reached from now. */
    onBack: () -> Unit = {}
) {
    val watchlist       by viewModel.watchlist.collectAsStateWithLifecycle()
    val quotes          by viewModel.quotes.collectAsStateWithLifecycle()
    val sparklines      by viewModel.sparklines.collectAsStateWithLifecycle()
    val moverSparklines by viewModel.moverSparklines.collectAsStateWithLifecycle()
    val hotStocks       by viewModel.hotStocks.collectAsStateWithLifecycle()
    val marketIndices   by viewModel.marketIndices.collectAsStateWithLifecycle()
    val extendedQuotes  by viewModel.extendedQuotes.collectAsStateWithLifecycle()
    val myHoldings      by viewModel.holdings.collectAsStateWithLifecycle()
    var showAddDialog    by remember { mutableStateOf(false) }
    var showSearchDialog by remember { mutableStateOf(false) }
    var selectedTab      by remember { mutableStateOf(StocksTab.ACTIVE) }

    // Keyed on the watchlist size, not Unit. The watchlist is loaded from Room
    // asynchronously, so on a cold start the first composition often happens
    // while it is still empty — refreshWatchlistQuotes() would then find no
    // tickers, return immediately, and never run again, leaving every row
    // spinning forever. Re-running when the list arrives fixes that.
    LaunchedEffect(watchlist.size) {
        if (watchlist.isNotEmpty()) viewModel.refreshWatchlistQuotes()
    }
    // Held tickers aren't necessarily on the watchlist, so the My Holdings tab
    // needs its own price fetch to avoid showing stale cached values.
    LaunchedEffect(myHoldings.size) {
        if (myHoldings.isNotEmpty()) viewModel.refreshHoldingQuotes()
    }
    LaunchedEffect(Unit) {
        viewModel.refreshMarketMovers()
    }

    val country = remember { Locale.getDefault().country }   // ISO-3166, or ""

    val today = remember { LocalDate.now(ZoneId.systemDefault()) }

    // The reader's own market plus the US — see MarketCalendar.closuresFor.
    // The old filter matched on the label text ("US" in it.market) and sent
    // every other country down an else branch that showed BOTH North American
    // markets, which is the one combination a reader outside North America is
    // least likely to want.
    val todaysClosures = remember(today, country) {
        MarketCalendar.closuresFor(today, country)
    }

    val nextClosure: Pair<LocalDate, ca.tristan.portfolio.data.MarketClosure>? =
        remember(today, country) { MarketCalendar.nextClosureAfter(today, country) }

    // Monday holidays → warn 3 days ahead; all others → 1 day ahead.
    val showNextBanner = nextClosure?.let { (date, _) ->
        val daysAway = ChronoUnit.DAYS.between(today, date)
        val threshold = if (date.dayOfWeek == DayOfWeek.MONDAY) 3L else 1L
        daysAway in 1..threshold
    } ?: false

    // Dismissal, remembered per closure rather than per session.
    //
    // The banner is a standing notice, not an event: without persistence it
    // reappears on every launch for the three days before a Monday holiday,
    // and a notice that cannot be acknowledged stops being read. Keyed by date
    // and market so dismissing Thanksgiving says nothing about Christmas, and
    // so the key for a past holiday is simply never asked about again.
    val context = LocalContext.current
    val closurePrefs = remember {
        context.getSharedPreferences("wealthboard_prefs", android.content.Context.MODE_PRIVATE)
    }
    fun dismissKey(date: LocalDate, closure: ca.tristan.portfolio.data.MarketClosure) =
        "closure_dismissed_${date}_${closure.countryCode}"

    var dismissedKeys by remember { mutableStateOf(setOf<String>()) }
    fun isDismissed(key: String) =
        key in dismissedKeys || closurePrefs.getBoolean(key, false)
    fun dismiss(key: String) {
        closurePrefs.edit().putBoolean(key, true).apply()
        dismissedKeys = dismissedKeys + key
    }

    Scaffold(
        topBar = {
            ca.tristan.portfolio.ui.components.WealthBoardTopBar(
                title = "Markets",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = androidx.compose.ui.graphics.Color.White
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showSearchDialog = true }) {
                        Icon(
                            Icons.Filled.Search,
                            contentDescription = "Search ticker",
                            tint = androidx.compose.ui.graphics.Color.White
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = MaterialTheme.colorScheme.secondary,
                contentColor = MaterialTheme.colorScheme.onSecondary
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Add a ticker")
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        // Filter out futures; classify the rest into stocks/crypto
        // Indices lead the Markets tab in registry order (US benchmarks, then
        // the user's home market, then the other majors); individual stocks
        // follow in watchlist order.
        // One row per SECURITY on the My Holdings tab. This is a quote list:
        // the price of XEQT.TO is the same number whether it is held in one
        // account or three, so printing it three times says nothing — it just
        // buries the rest of the list.
        //
        // Derived here rather than inside the LazyColumn below: that block is a
        // LazyListScope, not a composable one, so `remember` is not callable in
        // it.
        val heldSecurities = remember(myHoldings, quotes) { viewModel.positions() }

        val indexOrder = marketIndices.map { it.symbol }
        val stockItems = watchlist
            .filter { isStock(it.ticker) }
            .sortedWith(
                compareBy<ca.tristan.portfolio.data.db.WatchlistItemEntity> {
                    if (MarketIndices.isIndex(it.ticker)) 0 else 1
                }.thenBy { entry ->
                    indexOrder.indexOf(entry.ticker).let { i -> if (i < 0) Int.MAX_VALUE else i }
                }
            )
        val cryptoItems = watchlist.filter { isCrypto(it.ticker) }

        // ACTIVE / CRYPTO tabs still use the user's watchlist
        val watchlistTabItems = when (selectedTab) {
            StocksTab.ACTIVE -> stockItems
            StocksTab.CRYPTO -> cryptoItems
            else -> emptyList()
        }

        // Hot Stocks uses market-wide screener data
        val moverTabItems: List<MarketMover> = when (selectedTab) {
            StocksTab.HOT -> hotStocks
            else          -> emptyList()
        }
        val isMoversTab = selectedTab == StocksTab.HOT
        val isHoldingsTab = selectedTab == StocksTab.HOLDINGS

        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Market closure banner
            item {
                val todayKey = todaysClosures.firstOrNull()?.let { dismissKey(today, it) }
                if (todaysClosures.isNotEmpty() && todayKey != null && !isDismissed(todayKey)) {
                    ClosureBanner(
                        text = "Closed today: " + todaysClosures.joinToString(" · ") { "${it.market} (${it.holidayName})" },
                        onDismiss = { dismiss(todayKey) }
                    )
                } else if (showNextBanner && nextClosure != null) {
                    val (date, closure) = nextClosure
                    val key = dismissKey(date, closure)
                    if (!isDismissed(key)) {
                        ClosureBanner(
                            text = "${closure.market} closed ${closureDateFormat.format(
                                Date.from(date.atStartOfDay(ZoneId.systemDefault()).toInstant())
                            )} for ${closure.holidayName}",
                            onDismiss = { dismiss(key) }
                        )
                    }
                }
            }

            // Sub-tab row
            item {
                ScrollableTabRow(
                    selectedTabIndex = StocksTab.values().indexOf(selectedTab),
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.secondary,
                    edgePadding = 16.dp
                ) {
                    StocksTab.values().forEach { tab ->
                        Tab(
                            selected = selectedTab == tab,
                            onClick = { selectedTab = tab },
                            text = {
                                Text(
                                    tab.label,
                                    fontSize = 12.sp,
                                    fontWeight = if (selectedTab == tab) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        )
                    }
                }
            }

            // Column header
            item { StockTableHeader() }

            // Rows — market movers tabs
            if (isMoversTab) {
                if (moverTabItems.isEmpty()) {
                    item {
                        Text(
                            "Finding today's hot stocks…",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                        )
                    }
                } else {
                    items(moverTabItems, key = { it.ticker }) { mover ->
                        MarketMoverTableRow(
                            mover     = mover,
                            sparkline = moverSparklines[mover.ticker] ?: emptyList(),
                            onClick   = { viewModel.addMoverToWatchlistAndOpen(mover.ticker, onOpenQuote) }
                        )
                        Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    }
                }
            } else if (isHoldingsTab) {
                // The user's own positions, priced live, so the Dashboard can
                // answer "how are MY holdings doing" without leaving it.
                //
                // This branch must be tested BEFORE the watchlist-empty check
                // below: watchlistTabItems is empty by construction on this tab
                // (the `when` above maps only ACTIVE and CRYPTO), so an
                // empty-first ordering swallowed every holding and showed
                // "No tickers yet — tap + to add one" to users who had plenty.
                if (myHoldings.isEmpty()) {
                    item {
                        Text(
                            "No holdings yet — add one from My Portfolio.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                        )
                    }
                } else {
                    items(heldSecurities, key = { "s-${it.key}" }) { position ->
                        val h = position.principal
                        val t = position.ticker
                        val quote = t?.let { quotes[it] }
                        StockTableRow(
                            ticker = t ?: position.name,
                            flag = t?.let { flagForTicker(it, quote?.currency ?: position.currency) } ?: "",
                            displayName = buildString {
                                append(position.name)
                                append(" · ")
                                append(String.format("%,.4f", position.units))
                                append(" units")
                                if (position.isSplit) {
                                    append(" · ")
                                    append("${position.accountCount} accounts")
                                }
                            },
                            price = quote?.price ?: h.lastKnownPrice ?: h.manualPrice,
                            previousClose = quote?.previousClose,
                            loading = false,
                            sparkline = t?.let { sparklines[it] } ?: emptyList(),
                            marketTimeMillis = quote?.marketTimeMillis,
                            quoteSymbol = t,
                            exchangeTimezone = quote?.exchangeTimezone,
                            extended = t?.let { extendedQuotes[it] },
                            onClick = {
                                // The POSITION, not the quote.
                                //
                                // This tab used to open the ticker's quote view
                                // on the reasoning that the tab is a quote
                                // list. But every row here is something the
                                // reader owns, and the quote screen is the one
                                // screen that cannot say what they own of it —
                                // no units, no market value, no cost. Tapping
                                // your own holding and landing somewhere that
                                // does not mention you is the wrong answer, so
                                // these rows now go where the iOS build's do:
                                // the holding's own screen.
                                onOpenHolding(h.id)
                            }
                        )
                        Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    }
                }
            } else {
                // Markets / Cryptocurrencies tabs — the user's watchlist.
                if (watchlistTabItems.isEmpty()) {
                    item {
                        val hint = when (selectedTab) {
                            StocksTab.CRYPTO -> "No crypto yet — tap + and add a ticker like BTC-USD."
                            else -> "No tickers yet — tap + to add one."
                        }
                        Text(
                            hint,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                        )
                    }
                } else {
                    items(watchlistTabItems, key = { it.id }) { entry ->
                        val quote = quotes[entry.ticker]
                        val spark = sparklines[entry.ticker] ?: emptyList()
                        StockTableRow(
                            // Indices show their real name ("Dow Jones") with
                            // the raw symbol demoted to the subtitle line.
                            ticker = entry.customName ?: rowTitle(entry.ticker),
                            flag = flagForTicker(entry.ticker, quote?.currency),
                            displayName = rowSubtitle(entry.ticker, quote?.name),
                            price = quote?.price,
                            previousClose = quote?.previousClose,
                            // Only spin when there is genuinely nothing to show
                            // — a cached price counts, so the list renders real
                            // numbers immediately on a cold start.
                            loading = quotes[entry.ticker]?.price == null &&
                                      entry.cachedPrice == null,
                            sparkline = spark,
                            // Only ever the real last-trade time from the quote.
                            // cachedAtMillis is when WE wrote the cache, which
                            // is a different thing and would read as a lie
                            // overnight, so it is deliberately not used here.
                            marketTimeMillis = quote?.marketTimeMillis,
                            quoteSymbol = entry.ticker,
                            exchangeTimezone = quote?.exchangeTimezone,
                            extended = extendedQuotes[entry.ticker],
                            onClick = { onOpenQuote(entry.id) }
                        )
                        Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    }
                }
            }

            item { Spacer(Modifier.height(WbDimens.ScrollBottomGap)) }
        }
    }

    if (showAddDialog) {
        AddQuoteDialog(
            viewModel = viewModel,
            onDismiss = {
                showAddDialog = false
                viewModel.clearSearchResults()
            }
        )
    }

    if (showSearchDialog) {
        SearchQuoteDialog(
            viewModel = viewModel,
            onDismiss = {
                showSearchDialog = false
                viewModel.clearSearchResults()
            },
            onOpenQuote = onOpenQuote
        )
    }
}

@Composable
private fun StockTableHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "Market / Name",
            style = MaterialTheme.typography.labelLarge,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1.8f)
        )
        Text(
            "Chart",
            style = MaterialTheme.typography.labelLarge,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(44.dp),
            textAlign = TextAlign.Center
        )
        Text(
            "Last Price",
            style = MaterialTheme.typography.labelLarge,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            // Wider than the change column now: the as-of line beneath each
            // price carries a zone, and sometimes a date ("Sep 11, 3:30 PM
            // JST") for a market whose session closed on the far side of the
            // reader's midnight.
            modifier = Modifier.weight(1.7f),
            textAlign = TextAlign.End
        )
        Text(
            "Change",
            style = MaterialTheme.typography.labelLarge,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1.3f),
            textAlign = TextAlign.End
        )
    }
    Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
}

@Composable
private fun StockTableRow(
    ticker: String,
    flag: String = "",
    displayName: String?,
    price: Double?,
    previousClose: Double?,
    loading: Boolean,
    sparkline: List<Float>,
    marketTimeMillis: Long? = null,
    /**
     * The raw Yahoo symbol behind this row. [ticker] is the *display* title
     * and may be a friendly name ("Dow Jones") or a user's rename, neither of
     * which says which exchange the price came from — so the timestamp needs
     * the real symbol to work out the venue's clock.
     */
    quoteSymbol: String? = null,
    /** meta.exchangeTimezoneName when the provider sent one. */
    exchangeTimezone: String? = null,
    extended: ExtendedQuote? = null,
    onClick: () -> Unit
) {
    val change = if (price != null && previousClose != null) price - previousClose else null
    val changePct = if (change != null && previousClose != null && previousClose != 0.0) (change / previousClose) * 100.0 else null
    val positive = (change ?: 0.0) >= 0
    val changeColor = if (positive) GainGreen else LossRed

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Ticker + name column
            Column(modifier = Modifier.weight(1.8f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (flag.isNotEmpty()) {
                        Text(flag, fontSize = 14.sp)
                    }
                    Text(
                        ticker,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (!displayName.isNullOrBlank()) {
                    Text(
                        displayName,
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Mini sparkline
            Box(modifier = Modifier.width(44.dp).height(28.dp)) {
                if (sparkline.size >= 2) {
                    MiniSparkline(data = sparkline, color = changeColor)
                }
            }

            // Price + the time that price was last struck. Without the
            // timestamp a stale close is indistinguishable from a live quote.
            Column(
                modifier = Modifier.weight(1.7f),
                horizontalAlignment = Alignment.End
            ) {
                when {
                    loading -> CircularProgressIndicator(
                        modifier = Modifier.size(14.dp), strokeWidth = 2.dp
                    )
                    price == null -> Text(
                        "—",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    else -> Text(
                        String.format("%,.2f", price),
                        textAlign = TextAlign.End,
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp
                    )
                }
                if (marketTimeMillis != null && price != null) {
                    Text(
                        // Exchange-local, and named: see [TradeTime].
                        TradeTime.label(
                            atMillis = marketTimeMillis,
                            providerZone = exchangeTimezone,
                            ticker = quoteSymbol
                        ),
                        fontSize = 9.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.End
                    )
                }
            }

            // Change column
            if (changePct != null && change != null) {
                val sign = if (positive) "+" else ""
                Column(
                    modifier = Modifier.weight(1.3f),
                    horizontalAlignment = Alignment.End
                ) {
                    Text(
                        "$sign${String.format("%,.2f", change)}",
                        fontSize = 12.sp,
                        color = changeColor,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        "($sign${String.format("%.2f", changePct)}%)",
                        fontSize = 11.sp,
                        color = changeColor
                    )
                }
            } else {
                Spacer(Modifier.weight(1.3f))
            }
        }

        // Secondary line: the index future for a cash index, after-hours
        // trading for a stock or ETF. Only drawn when there is something
        // newer than the regular close to report.
        if (extended != null) {
            val extPositive = extended.change >= 0
            val extColor = if (extPositive) GainGreen else LossRed
            val extSign = if (extPositive) "+" else ""
            Row(
                modifier = Modifier.padding(top = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${extended.label}: ",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    String.format("%,.2f", extended.price),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    "  $extSign${String.format("%,.2f", extended.change)} " +
                        "($extSign${String.format("%.2f", extended.changePercent)}%)",
                    fontSize = 11.sp,
                    color = extColor
                )
                Text(
                    " · ${TradeTime.label(
                        atMillis = extended.atMillis,
                        providerZone = extended.zoneId,
                        ticker = extended.symbol ?: quoteSymbol
                    )}",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun MarketMoverTableRow(
    mover: MarketMover,
    sparkline: List<Float> = emptyList(),
    onClick: () -> Unit = {}
) {
    val changePct  = mover.changePct
    val positive   = changePct >= 0
    val changeColor = if (positive) GainGreen else LossRed
    val flag       = flagForExchange(mover.exchange)
    val sign       = if (positive) "+" else ""
    val change     = mover.price - mover.previousClose

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1.8f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (flag.isNotEmpty()) Text(flag, fontSize = 14.sp)
                Text(
                    mover.ticker,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (!mover.name.isNullOrBlank()) {
                Text(
                    mover.name,
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Sparkline chart (44dp wide, 28dp tall) — shown when intraday data is available
        Box(modifier = Modifier.width(44.dp).height(28.dp)) {
            if (sparkline.size >= 2) {
                MiniSparkline(data = sparkline, color = changeColor)
            }
        }

        // Price + the time it was struck. Hot Stocks had no timestamp at all,
        // so an overnight list of yesterday's movers was indistinguishable
        // from a live one — the same gap the watchlist rows already close.
        Column(
            modifier = Modifier.weight(1.7f),
            horizontalAlignment = Alignment.End
        ) {
            Text(
                String.format("%.2f", mover.price),
                textAlign = TextAlign.End,
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp
            )
            mover.marketTimeMillis?.let { at ->
                Text(
                    TradeTime.label(
                        atMillis = at,
                        providerZone = mover.exchangeTimezone,
                        ticker = mover.ticker
                    ),
                    fontSize = 9.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End
                )
            }
        }

        Column(
            modifier = Modifier.weight(1.3f),
            horizontalAlignment = Alignment.End
        ) {
            Text(
                "$sign${String.format("%.2f", change)}",
                fontSize = 12.sp,
                color = changeColor,
                fontWeight = FontWeight.Medium
            )
            Text(
                "($sign${String.format("%.2f", changePct)}%)",
                fontSize = 11.sp,
                color = changeColor
            )
        }
    }
}

/**
 * Tiny canvas sparkline — draws a stroke path through normalised data points.
 * Colour matches the day's overall gain/loss for the ticker.
 */
@Composable
private fun MiniSparkline(data: List<Float>, color: Color) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val minVal = data.min()
        val maxVal = data.max()
        val range = (maxVal - minVal).coerceAtLeast(0.001f)
        val path = Path()
        data.forEachIndexed { i, v ->
            val x = (i.toFloat() / (data.size - 1)) * size.width
            val y = size.height - ((v - minVal) / range) * size.height
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = 1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}


/**
 * Search-only dialog — lets the user look up any ticker and open its quote
 * detail screen to preview it, WITHOUT permanently adding it to the Markets tab.
 * If the ticker isn't already on the watchlist it is added temporarily so
 * QuoteDetailScreen has an ID to work with; the user can remove it from there.
 */
@Composable
private fun SearchQuoteDialog(
    viewModel: PortfolioViewModel,
    onDismiss: () -> Unit,
    onOpenQuote: (Long) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val results by viewModel.searchResults.collectAsStateWithLifecycle()

    LaunchedEffect(query) { viewModel.searchQuotes(query) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Search a ticker") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Ticker, company, index…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                if (results.isNotEmpty()) {
                    LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
                        items(results, key = { it.symbol }) { result ->
                            SearchResultRow(
                                viewModel = viewModel,
                                symbol = result.symbol,
                                name = result.name,
                                exchange = result.exchange,
                                quoteType = result.quoteType,
                                onClick = {
                                    onDismiss()
                                    viewModel.addMoverToWatchlistAndOpen(result.symbol, onOpenQuote)
                                }
                            )
                            Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        }
                    }
                } else if (query.isNotBlank()) {
                    Text(
                        "No matches yet",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

/**
 * One search-result row, shared by [SearchQuoteDialog] and [AddQuoteDialog]
 * so both dialogs — and the ticker search on Add Transaction — show a ticker
 * suggestion the same way: logo, flag and name, not just bare text.
 */
@Composable
private fun SearchResultRow(
    viewModel: PortfolioViewModel,
    symbol: String,
    name: String?,
    exchange: String?,
    quoteType: String?,
    onClick: () -> Unit
) {
    val logoUrl by produceState<String?>(null, symbol) {
        // The name goes with it: a fund's issuer is readable from its name
        // alone, which is what gets non-US listings a mark instead of a letter.
        value = viewModel.logoUrlFor(symbol, name)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        TickerLogo(logoUrl = logoUrl, label = symbol, size = 32.dp)
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(symbol, fontWeight = FontWeight.SemiBold)
                val flag = TickerFlag.forTicker(symbol, exchange)
                if (flag.isNotEmpty()) Text(flag, fontSize = 14.sp)
                quoteType?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val subtitle = listOfNotNull(name, exchange).joinToString(" · ")
            if (subtitle.isNotBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun AddQuoteDialog(viewModel: PortfolioViewModel, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val results by viewModel.searchResults.collectAsStateWithLifecycle()

    LaunchedEffect(query) { viewModel.searchQuotes(query) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a ticker") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Ticker, company, index…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                if (results.isNotEmpty()) {
                    LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
                        items(results, key = { it.symbol }) { result ->
                            SearchResultRow(
                                viewModel = viewModel,
                                symbol = result.symbol,
                                name = result.name,
                                exchange = result.exchange,
                                quoteType = result.quoteType,
                                onClick = {
                                    viewModel.addQuote(result.symbol) {}
                                    onDismiss()
                                }
                            )
                            Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        }
                    }
                } else if (query.isNotBlank()) {
                    Text(
                        "No matches yet",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}


@Composable
private fun ClosureBanner(text: String, onDismiss: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f)
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
                fontWeight = FontWeight.Medium
            )
            IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Dismiss",
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
