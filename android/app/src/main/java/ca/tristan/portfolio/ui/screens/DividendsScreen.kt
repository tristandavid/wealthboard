package ca.tristan.portfolio.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ca.tristan.portfolio.data.DividendForecast
import ca.tristan.portfolio.data.db.AccountEntity
import ca.tristan.portfolio.data.db.HoldingEntity
import ca.tristan.portfolio.ui.ImportState
import ca.tristan.portfolio.ui.securityKey
import ca.tristan.portfolio.ui.PortfolioViewModel
import ca.tristan.portfolio.ui.components.DividendBarChart
import ca.tristan.portfolio.ui.components.DividendPeriod
import ca.tristan.portfolio.ui.components.IncomeSeriesLegend
import ca.tristan.portfolio.ui.components.IncomeSlice
import ca.tristan.portfolio.ui.components.SeriesPalette
import ca.tristan.portfolio.ui.components.StackedIncomeBar
import ca.tristan.portfolio.ui.components.WbCard
import ca.tristan.portfolio.ui.components.WbDimens
import ca.tristan.portfolio.ui.components.WealthBoardTopBar
import ca.tristan.portfolio.ui.components.buildDividendBars
import ca.tristan.portfolio.ui.format.Money
import ca.tristan.portfolio.ui.theme.GainGreen
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// ── Design tokens ─────────────────────────────────────────────────────────────
private val DivIndigo      = Color(0xFF6366F1)
private val DivIndigoLight = Color(0xFF818CF8)
private val DivGreen       = Color(0xFF16A34A)
private val DivGreenBg     = Color(0xFF14532D)
private val DivAmber       = Color(0xFFD97706)
private val DivRed         = Color(0xFFEF4444)
private val DivGoalBg1     = Color(0xFF1E1B4B)
private val DivGoalBg2     = Color(0xFF312E81)
private val DivBarActual   = Color(0xFF6366F1)
private val DivBarFwd      = Color(0xFF22D3EE)

private val divDateFmt: DateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM)

// ── Helpers ───────────────────────────────────────────────────────────────────

// Money formatting moved to the shared [Money] object. The per-currency
// locale trick here produced a bare "$" for CAD under Locale.CANADA, which is
// indistinguishable from a US dollar on a screen that can show both.


// Monthly Income chart geometry. Kept as named constants because the bar
// height and the value label's offset have to be derived from the same
// numbers — a label positioned against a different height than the bar it
// belongs to drifts away from its bar as the value changes.
private const val PLOT_HEIGHT_DP = 132        // axis column + bar area + month label
private const val BAR_AREA_DP = 116f          // what the bars themselves get
private const val BAR_HEADROOM = 0.82f        // fraction of the bar area bars may fill

/** Rounds a chart's maximum up to a readable 1/2/5 × 10^n step. */
private fun niceAxisMax(v: Double): Double {
    if (v <= 0) return 1.0
    val mag = Math.pow(10.0, Math.floor(Math.log10(v)))
    val n = v / mag
    val step = when {
        n <= 1.0 -> 1.0
        n <= 2.0 -> 2.0
        n <= 5.0 -> 5.0
        else -> 10.0
    }
    return step * mag
}

/**
 * Axis labels widened until they are all distinct — a compact form rounds a
 * small scale to the same string on every gridline, which reads as a broken
 * chart rather than a small one.
 */
private fun incomeAxisLabels(values: List<Double>): List<String> {
    for (digits in 0..4) {
        val out = values.map { v ->
            if (v <= 0.0) "0" else String.format(Locale.US, "%,.${digits}f", v)
        }
        if (out.distinct().size == out.size) return out
    }
    return values.map { ca.tristan.portfolio.ui.format.Money.compact(it) }
}

private fun daysUntil(ms: Long): Long =
    ((ms - System.currentTimeMillis()) / (1000L * 60 * 60 * 24)).coerceAtLeast(0L)

// ═════════════════════════════════════════════════════════════════════════════
// Main screen
// ═════════════════════════════════════════════════════════════════════════════

@Composable
fun DividendsScreen(
    viewModel: PortfolioViewModel,
    onOpenHolding: (Long) -> Unit,
    onAddDividend: (Long) -> Unit = {},
    onBack: (() -> Unit)? = null
) {
    val dividends      by viewModel.dividends.collectAsStateWithLifecycle()
    val holdings       by viewModel.holdings.collectAsStateWithLifecycle()
    // Named beside each slice of a split payment, so "paid into" reads as the
    // account names the user chose rather than as row ids.
    val accounts       by viewModel.accounts.collectAsStateWithLifecycle()
    val upcoming       by viewModel.upcomingDividends.collectAsStateWithLifecycle()
    val goal           by viewModel.dividendGoal.collectAsStateWithLifecycle()
    val upcomingProgress by viewModel.upcomingProgress.collectAsStateWithLifecycle()
    val baseCurrency   by viewModel.baseCurrency.collectAsStateWithLifecycle()
    val fxTick         by viewModel.fxTick.collectAsStateWithLifecycle()

    // ── Currency handling ────────────────────────────────────────────────
    // Every card total on this screen (goal progress, received income, the
    // monthly/yearly charts) now sums EVERY holding, each payment converted
    // into the user's default currency first — a screen that only ever
    // showed a third of the portfolio's income because it happened to be
    // filed under a different currency was answering the wrong question.
    // Individual line items (a specific upcoming or received payment) still
    // show the amount in the currency it actually is/was paid in — that's
    // the real number for that one payment — with the converted equivalent
    // printed underneath so it can still be compared against everything else.
    fun inBase(amount: Double, currency: String) = viewModel.amountInBase(amount, currency)

    val presentCurrencies = remember(holdings, dividends) {
        (holdings.map { it.currency.uppercase().ifBlank { "CAD" } } +
            dividends.map { it.currency.uppercase().ifBlank { "CAD" } })
            .distinct()
    }
    LaunchedEffect(presentCurrencies, baseCurrency) {
        if (presentCurrencies.isNotEmpty()) viewModel.ensureRatesFor(presentCurrencies)
    }

    // Bar-chart state. Recomputed whenever logged payments or the upcoming
    // projections change, so the chart and the list below never disagree.
    var chartPeriod by remember { mutableStateOf(DividendPeriod.MONTH) }
    val cashFlows = remember(dividends, upcoming, baseCurrency, fxTick) {
        viewModel.dividendCashFlowsInBase()
    }

    // Auto-refresh upcoming; auto-import payments once per ViewModel lifetime.
    val holdingsReady by remember(holdings) { derivedStateOf { holdings.isNotEmpty() } }
    // Only the forecast is refreshed here.
    //
    // This used to also auto-import dividend history: every past distribution
    // for every ticker, written into Dividends Received as though the user had
    // been paid it. That is only true if they held the whole position for the
    // whole history — and it produced an income record nobody could reconcile
    // against a statement, which undoing meant deleting rows by hand. Payments
    // are logged from a real statement now, by hand, from "+ Log past".
    LaunchedEffect(holdingsReady) {
        if (holdingsReady) {
            viewModel.refreshUpcomingDividends()
        }
    }

    // Time boundaries
    val now = remember { System.currentTimeMillis() }
    val ttmStart = now - 365L * 24 * 3600 * 1000
    val monthStart = remember {
        Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0);      set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
    val currentYr = remember { Calendar.getInstance().get(Calendar.YEAR) }

    // Metrics — every payment converted to the reporting currency before
    // being added up, so a portfolio split across currencies gets one real
    // total rather than three numbers pretending to be one.
    // Every window below is closed at BOTH ends.
    //
    // They used to be open at the top — "paid on or after the start of the
    // month", with no upper bound — which is correct only while no payment is
    // dated in the future. The dividend auto-import records announced-but-
    // unpaid dividends, so that assumption broke: a BNS dividend payable on
    // 27 October satisfied ">= 1 September" and was reported as money received
    // month-to-date, six weeks before it existed. It inflated the trailing
    // total and therefore the monthly average and goal progress too.
    //
    // "Received" has to mean received.
    val ttmTotal by remember(dividends, baseCurrency, fxTick) {
        derivedStateOf {
            dividends.filter { it.paidAtMillis in ttmStart..now }
                .sumOf { inBase(it.amount, it.currency) }
        }
    }
    val mtdAmount by remember(dividends, baseCurrency, fxTick) {
        derivedStateOf {
            dividends.filter { it.paidAtMillis in monthStart..now }
                .sumOf { inBase(it.amount, it.currency) }
        }
    }
    val ytdAmount by remember(dividends, currentYr, baseCurrency, fxTick) {
        derivedStateOf {
            dividends.filter { d ->
                d.paidAtMillis <= now &&
                    Calendar.getInstance().also { it.timeInMillis = d.paidAtMillis }
                        .get(Calendar.YEAR) == currentYr
            }.sumOf { inBase(it.amount, it.currency) }
        }
    }
    // Trailing average, and a forward one to fall back on.
    //
    // A goal measured only against the last twelve months reads zero for every
    // new user, and stays there until a real payment lands — which for a
    // quarterly payer can be three months of a card saying 0%. That is
    // accurate and useless: the portfolio has a perfectly good expected income,
    // it just has not been collected yet.
    //
    // So when nothing has actually been received, the card falls back to what
    // the holdings are projected to pay and says which basis it is using. It
    // never silently mixes the two.
    val trailingMonthlyAvg = ttmTotal / 12.0
    val forwardMonthlyAvg = remember(upcoming, holdings, baseCurrency, fxTick) {
        val byMonth = viewModel.projectedIncomeByMonthInBase(12)
        if (byMonth.isEmpty()) 0.0 else byMonth.values.sum() / 12.0
    }
    val usingForecast = trailingMonthlyAvg <= 0.0 && forwardMonthlyAvg > 0.0
    val monthlyAvg = if (usingForecast) forwardMonthlyAvg else trailingMonthlyAvg
    val goalProgress = if (goal > 0) (monthlyAvg / goal).coerceIn(0.0, 1.0).toFloat() else 0f

    // Blended forward yield across the WHOLE portfolio — the rate at which
    // reinvested distributions buy new units. Value and forward income are
    // both converted to the reporting currency before dividing, so mixing
    // currencies doesn't skew the ratio.
    //
    // Built from the upcoming payment schedule, NOT from logged payments.
    // Keying it on trailing logged income meant a portfolio with nothing
    // logged yet — the normal state for a new user, and the state this app
    // starts in — computed a yield of exactly zero. The DRIP series then had
    // zero height on every bar, so the forward projection showed no
    // reinvestment at all while still printing a DRIP swatch in the legend.
    //
    // The year is projected payment by payment rather than by annualising the
    // NEXT payment, which is wrong for any seasonal payer: XEQT's small Q3
    // times four reads as 0.408 a unit when the fund actually distributes
    // about 0.71, so the DRIP rate came out barely half what it should be and
    // the yearly chart drifted DOWNWARD — reinvestment too small to offset a
    // negative measured distribution growth. Same mistake DividendForecast was
    // written to avoid, and the same one HoldingScreen's detail math already
    // fixed; this was the last place still making it.
    val portfolioYield = remember(holdings, upcoming, baseCurrency, fxTick) {
        val value = holdings.sumOf { h ->
            val price = h.lastKnownPrice ?: h.manualPrice ?: 0.0
            inBase(price * h.units, h.currency)
        }
        val now = System.currentTimeMillis()
        val forwardAnnualIncome = upcoming.sumOf { row ->
            val freq = row.info.paymentFrequencyPerYear ?: 4
            // Falls back to the old flat annualisation only when there is too
            // little history to project a year — a rate that is roughly right
            // beats no DRIP series at all.
            val perUnit = DividendForecast.forwardAnnualPerUnit(
                history = row.history,
                upcoming = row.info,
                nowMillis = now
            )
                ?: row.info.estimatedAnnualRate
                ?: row.info.perPaymentAmount?.times(freq.toDouble())
                ?: 0.0
            inBase(perUnit * row.units, row.holding.currency)
        }
        if (value > 0 && forwardAnnualIncome > 0)
            (forwardAnnualIncome / value).coerceIn(0.0, 0.25)
        else 0.0
    }

    // ── Per-holding series ───────────────────────────────────────────────
    // Every income chart on this tab is stacked by holding, so they all need
    // the same two things: a stable key per holding and a colour map shared
    // across charts. The map is built from the WHOLE portfolio rather than
    // from whichever holdings happen to have income inside one chart's window
    // — otherwise a holding could be blue on the TTM chart and violet on the
    // yearly one purely because a third holding had no payments last year.
    val seriesKeyById = remember(holdings) {
        holdings.associate { h ->
            h.id to (h.ticker?.takeIf { it.isNotBlank() } ?: h.name)
        }
    }
    val seriesColors = remember(seriesKeyById) {
        SeriesPalette.colorsFor(seriesKeyById.values)
    }
    fun sliceFor(holdingId: Long, amount: Double): IncomeSlice {
        val key = seriesKeyById[holdingId] ?: "—"
        return IncomeSlice(key = key, label = key, amount = amount)
    }

    /** Collapses per-holding amounts into slices, biggest first. */
    fun slicesOf(byHolding: Map<Long, Double>): List<IncomeSlice> =
        byHolding.filter { it.value > 0.0 }
            .map { (id, amt) -> sliceFor(id, amt) }
            .sortedByDescending { it.amount }

    // Monthly bar data (last 12 months TTM)
    val monthlyBarsTTM by remember(dividends, holdings, baseCurrency, fxTick) {
        derivedStateOf {
            buildList {
                for (i in 11 downTo 0) {
                    val c = Calendar.getInstance().apply { add(Calendar.MONTH, -i) }
                    val y = c.get(Calendar.YEAR); val m = c.get(Calendar.MONTH)
                    val lbl = c.getDisplayName(Calendar.MONTH, Calendar.SHORT, Locale.getDefault()) ?: ""
                    val byHolding = dividends.filter { d ->
                        // Received means received — an announced-but-unpaid
                        // payment is not history, however it got into the table.
                        d.paidAtMillis <= now &&
                            Calendar.getInstance().also { it.timeInMillis = d.paidAtMillis }
                                .let { it.get(Calendar.YEAR) == y && it.get(Calendar.MONTH) == m }
                    }.groupBy { it.holdingId }
                        .mapValues { (_, ds) -> ds.sumOf { inBase(it.amount, it.currency) } }
                    add(
                        StackedIncomeBar(
                            label = lbl,
                            bucketKey = y * 100 + m,
                            slices = slicesOf(byHolding)
                        )
                    )
                }
            }
        }
    }
    // Forward: the next 12 months, projected from each holding's own seasonal
    // payment record by the shared forecaster.
    //
    // Two bugs lived here. The first divided an annual rate by twelve and drew
    // twelve identical bars. The second — the one that survived the first fix —
    // repeated a single per-payment estimate at a fixed cadence, so a quarterly
    // ETF still drew four bars of exactly equal height. Real distributions are
    // seasonal: XEQT pays a token Q1 and a large Q4, and a forward view exists
    // precisely to show which months are heavy. The projection also stepped by
    // 365/frequency days, which drifts payments out of their months over a long
    // horizon. Both now live in DividendForecast, shared with the holding
    // detail chart and the portfolio chart so all three agree.
    val monthlyBarsFWD by remember(upcoming, holdings, baseCurrency, fxTick) {
        derivedStateOf {
            val byMonth = viewModel.projectedIncomeByMonthAndHoldingInBase(months = 12)
            buildList {
                for (i in 1..12) {
                    val c = Calendar.getInstance().apply { add(Calendar.MONTH, i) }
                    val key = c.get(Calendar.YEAR) * 100 + c.get(Calendar.MONTH)
                    val lbl = c.getDisplayName(Calendar.MONTH, Calendar.SHORT, Locale.getDefault()) ?: ""
                    add(
                        StackedIncomeBar(
                            label = lbl,
                            bucketKey = key,
                            slices = slicesOf(byMonth[key].orEmpty())
                        )
                    )
                }
            }
        }
    }

    // Yearly history — most-recent first
    val yearlyHistory by remember(dividends, holdings, baseCurrency, fxTick) {
        derivedStateOf {
            (currentYr downTo currentYr - 4).map { yr ->
                val byHolding = dividends.filter { d ->
                    d.paidAtMillis <= now &&
                        Calendar.getInstance().also { it.timeInMillis = d.paidAtMillis }
                            .get(Calendar.YEAR) == yr
                }.groupBy { it.holdingId }
                    .mapValues { (_, ds) -> ds.sumOf { inBase(it.amount, it.currency) } }
                StackedIncomeBar(
                    label = yr.toString(),
                    bucketKey = yr,
                    slices = slicesOf(byHolding)
                )
            }
        }
    }

    // Payments visible: filter out auto-imported payments that pre-date the holding's creation
    val visibleDividends by remember(dividends, holdings) {
        derivedStateOf {
            dividends.filter { payment ->
                val h = holdings.firstOrNull { it.id == payment.holdingId }
                h == null || payment.paidAtMillis >= h.createdAtMillis
            }
        }
    }

    // UI state
    var showGoalDialog       by remember { mutableStateOf(false) }
    var showLogPaymentPicker by remember { mutableStateOf(false) }
    var incomeToggle         by remember { mutableStateOf("MTD") }
    var monthlyToggle        by remember { mutableStateOf("TTM") }
    var historyToggle        by remember { mutableStateOf("Monthly") }
    var selectedBarIndex     by remember { mutableStateOf<Int?>(null) }

    if (showGoalDialog) {
        GoalDialog(
            currentGoal = goal,
            currency    = baseCurrency,
            onConfirm   = { v -> viewModel.setDividendGoal(v); showGoalDialog = false },
            onDismiss   = { showGoalDialog = false }
        )
    }

    if (showLogPaymentPicker) {
        HoldingPickerDialog(
            holdings  = holdings.filter { !it.ticker.isNullOrBlank() },
            accounts  = accounts,
            onPick    = { id -> showLogPaymentPicker = false; onAddDividend(id) },
            onDismiss = { showLogPaymentPicker = false }
        )
    }

    Scaffold(
        topBar = {
            WealthBoardTopBar(
                title = "Dividends",
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Filled.ArrowBack, contentDescription = "Back",
                                tint = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                },
                actions = {
                    // The only way to force a re-scrape of the payout
                    // calendars, which are cached for twelve hours. Without it
                    // a newly declared distribution could take half a day to
                    // appear, and Android has no pull-to-refresh gesture to
                    // hang this on the way iOS does.
                    IconButton(onClick = { viewModel.refreshUpcomingDividendsForced() }) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = "Refresh dividends",
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { scaffoldPadding ->

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(scaffoldPadding),
            contentPadding = PaddingValues(bottom = WbDimens.ScrollBottomGap)
        ) {

            // ── 1. Passive Income Goal ─────────────────────────────────────────
            // Every total below is the whole portfolio, converted to
            // baseCurrency — see the "Currency handling" note above.
            item {
                PassiveIncomeGoalCard(
                    monthlyAvg  = monthlyAvg,
                    goal        = goal,
                    progress    = goalProgress,
                    currency    = baseCurrency,
                    isForecast  = usingForecast,
                    onSetGoal   = { showGoalDialog = true }
                )
            }

            // ── 2. Received Income ─────────────────────────────────────────────
            item {
                ReceivedIncomeCard(
                    toggle      = incomeToggle,
                    onToggle    = { incomeToggle = it },
                    amount      = if (incomeToggle == "MTD") mtdAmount else ytdAmount,
                    currencyCode = baseCurrency
                )
            }

            // ── 3. Monthly Income (clickable bars) ─────────────────────────────
            item {
                val bars = if (monthlyToggle == "TTM") monthlyBarsTTM else monthlyBarsFWD
                LaunchedEffect(monthlyToggle) { selectedBarIndex = null }
                MonthlyIncomeCard(
                    toggle        = monthlyToggle,
                    onToggle      = { monthlyToggle = it },
                    bars          = bars,
                    isForward     = monthlyToggle == "FWD",
                    selectedIndex = selectedBarIndex,
                    onBarSelected = { i -> selectedBarIndex = if (selectedBarIndex == i) null else i },
                    currencyCode  = baseCurrency,
                    seriesColors  = seriesColors
                )
            }

            // ── 4. Historical breakdown (per month / per year) ─────────────────
            item {
                DividendHistoryCard(
                    toggle      = historyToggle,
                    onToggle    = { historyToggle = it },
                    monthlyBars = monthlyBarsTTM,
                    yearlyData  = yearlyHistory,
                    currencyCode = baseCurrency,
                    seriesColors = seriesColors
                )
            }

            item { Spacer(Modifier.height(12.dp)) }

            // ── 5. Upcoming Dividends ──────────────────────────────────────────
            item {
                Text(
                    "Upcoming Dividends",
                    style      = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier   = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                )
            }
            item {
                if (upcoming.isEmpty()) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        shape    = RoundedCornerShape(16.dp),
                        color    = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ) {
                        Text(
                            "No upcoming dividend data found. Make sure your holdings have ticker symbols set.",
                            style    = MaterialTheme.typography.bodySmall,
                            color    = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                } else {
                    // A swipeable deck rather than a stack. One card per
                    // holding, stacked vertically, pushed everything below it
                    // — the received-payments chart and the whole payment log
                    // — several screens down for anyone holding more than a
                    // handful of dividend payers. Horizontally, the section
                    // costs one card's height no matter how many there are.
                    //
                    // Page width is the screen minus the content padding, so a
                    // sliver of the next card stays visible: that edge is the
                    // only affordance saying the deck can be swiped at all.
                    val pagerState = rememberPagerState(pageCount = { upcoming.size })

                    // Every card in the deck is the same height, and the height
                    // is the tallest card's.
                    //
                    // A floor per card was not enough: a position split across
                    // three accounts draws three extra rows, so the card beside
                    // it ended up visibly shorter and the deck changed height
                    // as you swiped. Deriving one height from the widest split
                    // in the deck makes every page measure the same on the
                    // first frame — no growing, no resize mid-swipe.
                    val fontScale = LocalDensity.current.fontScale.coerceIn(1f, 1.6f)
                    val deckHeight = remember(upcoming, fontScale) {
                        val widestSplit = upcoming.maxOfOrNull { it.rows.size } ?: 1
                        // Base card, plus the "Paid into" heading and one row
                        // per account when anything in the deck is split.
                        val splitExtra =
                            if (widestSplit > 1) 26 + widestSplit * 24 else 0
                        ((188 + splitExtra) * fontScale).dp
                    }

                    Column(modifier = Modifier.fillMaxWidth()) {
                        HorizontalPager(
                            state          = pagerState,
                            contentPadding = PaddingValues(horizontal = 24.dp),
                            pageSpacing    = 12.dp,
                            modifier       = Modifier.fillMaxWidth(),
                            key            = { upcoming[it].holding.id }
                        ) { page ->
                            val row = upcoming[page]
                            // Prefer the directly-estimated (or declared) per-payment
                            // amount. Only fall back to annual ÷ frequency when the
                            // source gave us nothing better — that even split badly
                            // over-states ETFs with lumpy quarterly distributions.
                            val freq            = row.info.paymentFrequencyPerYear ?: 4
                            val perUnit         = row.info.perPaymentAmount
                                ?: row.info.estimatedAnnualRate?.div(freq.toDouble())
                            val estimatedAmount = perUnit?.times(row.units)
                            val nativeCurrency  = row.holding.currency.uppercase().ifBlank { "CAD" }
                            UpcomingDividendCard(
                                holdingName      = row.holding.name,
                                ticker           = row.holding.ticker ?: "",
                                exDateMs         = row.info.exDividendDateMillis,
                                payDateMs        = row.info.payDateMillis,
                                estimatedAmount  = estimatedAmount,
                                units            = row.units,
                                perUnit          = perUnit,
                                frequencyPerYear = freq,
                                isAnnounced      = row.info.isAnnounced,
                                basisLabel       = row.info.basisLabel,
                                // The holding's own currency — the real amount
                                // this payment will actually be in — with the
                                // base-currency equivalent shown underneath
                                // whenever the two differ.
                                currencyCode     = nativeCurrency,
                                convertedAmount  = estimatedAmount?.let { inBase(it, nativeCurrency) }
                                    ?.takeIf { !nativeCurrency.equals(baseCurrency, ignoreCase = true) },
                                baseCurrencyCode = baseCurrency,
                                accountSplit     = row.rows.map { slice ->
                                    val acct = accounts.firstOrNull { it.id == slice.accountId }
                                    Triple(
                                        acct?.displayName ?: "Unassigned",
                                        acct?.taxTreatment?.label(),
                                        slice.units
                                    )
                                },
                                onClick          = { onOpenHolding(row.holding.id) },
                                // One height for the whole deck — see where it
                                // is computed, above.
                                modifier         = Modifier.height(deckHeight)
                            )
                        }

                        if (upcoming.size > 1) {
                            Spacer(Modifier.height(10.dp))
                            Row(
                                modifier              = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment     = Alignment.CenterVertically
                            ) {
                                // Capped: past a dozen holdings the dots stop
                                // being countable and a "3 / 18" counter is
                                // the more useful readout.
                                if (upcoming.size <= 12) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        repeat(upcoming.size) { i ->
                                            val active = i == pagerState.currentPage
                                            Box(
                                                modifier = Modifier
                                                    .size(if (active) 7.dp else 5.dp)
                                                    .background(
                                                        if (active) DivIndigo
                                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                                            .copy(alpha = 0.35f),
                                                        CircleShape
                                                    )
                                            )
                                        }
                                    }
                                } else {
                                    Text(
                                        "${pagerState.currentPage + 1} / ${upcoming.size}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(20.dp)) }

            // ── 6. Dividends Received — bar chart + payment log ────────────────
            // The chart is fed by the same numbers as the list below it: teal
            // is money actually logged, pale is the forecast that the Upcoming
            // section above is projecting.
            item {
                // Wrapped in a Column: a LazyColumn item lays its children out
                // on top of each other without one.
                Column {
                    WbCard(
                        Modifier.padding(horizontal = WbDimens.ScreenPadding, vertical = 4.dp)
                    ) {
                        DividendBarChart(
                            bars = buildDividendBars(
                                received  = cashFlows.first,
                                projected = cashFlows.second,
                                period    = chartPeriod,
                                // Reinvesting compounds units at the portfolio's
                                // own blended yield rather than a flat guess.
                                dripRate  = portfolioYield,
                                growthRate = 0.03
                            ),
                            period = chartPeriod,
                            onPeriodChange = { chartPeriod = it },
                            currencyCode = baseCurrency,
                            title = if (chartPeriod == DividendPeriod.YEAR)
                                "Yearly Income" else "Monthly Income",
                            // No yield means no reinvestment to draw. Showing
                            // the DRIP swatch anyway promised a series that
                            // wasn't there.
                            showDrip = chartPeriod == DividendPeriod.YEAR &&
                                       portfolioYield > 0.0
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    Text(
                        "Dividends Received",
                        style      = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (upcomingProgress is ImportState.Running) {
                        Row(
                            verticalAlignment     = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier    = Modifier.size(13.dp),
                                strokeWidth = 2.dp,
                                color       = DivIndigo
                            )
                            Text(
                                "Loading history…",
                                style = MaterialTheme.typography.labelSmall,
                                color = DivIndigo
                            )
                        }
                    } else {
                        Row(
                            verticalAlignment     = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                "${visibleDividends.size} payment${if (visibleDividends.size != 1) "s" else ""}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            TextButton(
                                onClick = { showLogPaymentPicker = true },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    "+ Log past",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = DivIndigo
                                )
                            }
                        }
                    }
                }
            }
            item {
                if (visibleDividends.isEmpty() && upcomingProgress !is ImportState.Running) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        shape    = RoundedCornerShape(16.dp),
                        color    = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "No payments logged yet. Received dividends appear here and in the chart above.",
                                style    = MaterialTheme.typography.bodySmall,
                                color    = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(8.dp))
                            TextButton(
                                onClick = { showLogPaymentPicker = true },
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text("+ Log a past payment manually", color = DivIndigo,
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                } else if (visibleDividends.isNotEmpty()) {
                    Card(
                        modifier  = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        shape     = RoundedCornerShape(16.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                    ) {
                        visibleDividends.forEachIndexed { idx, payment ->
                            val h = holdings.firstOrNull { it.id == payment.holdingId }
                            val holdingName = h?.name ?: "Unknown"
                            val ticker      = h?.ticker
                            // A row dated in the future is money that has not
                            // arrived. Earlier builds imported announced
                            // dividends into this table, so those rows still
                            // exist in people's data; they are drawn as
                            // expected rather than received, and stop being
                            // marked so on the day they are actually paid.
                            val isExpected  = payment.paidAtMillis > now
                            val rowColor    = if (isExpected)
                                MaterialTheme.colorScheme.onSurfaceVariant else DivGreen

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onOpenHolding(payment.holdingId) }
                                    .padding(start = 16.dp, end = 4.dp,
                                             top = 12.dp, bottom = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Avatar circle with initial
                                Box(
                                    modifier         = Modifier
                                        .size(38.dp)
                                        .background(rowColor.copy(alpha = 0.14f), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        holdingName.take(1).uppercase(),
                                        color      = rowColor,
                                        fontWeight = FontWeight.Bold,
                                        fontSize   = 15.sp
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        holdingName,
                                        fontWeight = FontWeight.Medium,
                                        fontSize   = 14.sp,
                                        maxLines   = 1,
                                        overflow   = TextOverflow.Ellipsis
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp),
                                        verticalAlignment = Alignment.CenterVertically) {
                                        if (!ticker.isNullOrBlank()) {
                                            Text(
                                                ticker,
                                                style      = MaterialTheme.typography.labelSmall,
                                                color      = DivIndigo,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            Text("·",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Text(
                                            (if (isExpected) "Expected " else "") +
                                                divDateFmt.format(Date(payment.paidAtMillis)),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    // The payment's own recorded currency — the
                                    // real amount that arrived — with the
                                    // base-currency equivalent underneath
                                    // whenever the two differ, so it can still
                                    // be compared against everything else on
                                    // this screen.
                                    Text(
                                        Money.format(payment.amount, payment.currency),
                                        fontWeight = FontWeight.Bold,
                                        color      = rowColor,
                                        fontSize   = 15.sp
                                    )
                                    val nativeCur = payment.currency.uppercase().ifBlank { "CAD" }
                                    if (!nativeCur.equals(baseCurrency, ignoreCase = true)) {
                                        Text(
                                            "≈ ${Money.format(inBase(payment.amount, nativeCur), baseCurrency)}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                IconButton(onClick = { viewModel.deleteDividend(payment.id) }) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = "Delete",
                                        tint     = MaterialTheme.colorScheme.onSurfaceVariant
                                                       .copy(alpha = 0.45f),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            if (idx < visibleDividends.lastIndex)
                                Divider(color = MaterialTheme.colorScheme.outlineVariant
                                    .copy(alpha = 0.3f))
                        }
                    }
                }
            }
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════════
// Sub-composables
// ═════════════════════════════════════════════════════════════════════════════

// ── Passive Income Goal ───────────────────────────────────────────────────────

@Composable
private fun PassiveIncomeGoalCard(
    monthlyAvg: Double,
    goal: Double,
    progress: Float,
    currency: String,
    isForecast: Boolean,
    onSetGoal: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .background(
                brush = Brush.linearGradient(listOf(DivGoalBg1, DivGoalBg2)),
                shape = RoundedCornerShape(16.dp)
            )
            .padding(horizontal = 20.dp, vertical = 18.dp)
    ) {
        Column {
            // Title row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Text(
                    "Passive Income Goal",
                    color      = Color.White.copy(alpha = 0.75f),
                    fontWeight = FontWeight.SemiBold,
                    fontSize   = 14.sp
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White.copy(alpha = 0.12f))
                        .clickable { onSetGoal() }
                        .padding(horizontal = 12.dp, vertical = 5.dp)
                ) {
                    Text(
                        "Edit Goal",
                        color      = Color.White.copy(alpha = 0.9f),
                        fontSize   = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(Modifier.height(18.dp))

            // One figure leads, the goal reads as its context, and the ring
            // sits apart from both.
            //
            // The three used to share a row as equals — a big number, a second
            // slightly smaller number and a percentage — with two 10sp captions
            // above them. Nothing said which was the answer and which was the
            // target, and on a narrow phone the captions wrapped into the
            // numbers underneath.
            Row(
                modifier          = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (isForecast) "Projected monthly income" else "Monthly average income",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        Money.format(monthlyAvg, currency),
                        color      = Color.White,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize   = 30.sp,
                        maxLines   = 1
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        if (goal > 0) "of ${Money.format(goal, currency)} a month"
                        else "No monthly goal set yet",
                        color    = Color.White.copy(alpha = 0.6f),
                        fontSize = 12.sp,
                        maxLines = 1
                    )
                }
                Spacer(Modifier.width(12.dp))
                // Ring rather than a filled disc: it shows the same percentage
                // and the same progress the bar below does, which is what makes
                // it worth the space.
                Box(
                    modifier         = Modifier.size(58.dp),
                    contentAlignment = Alignment.Center
                ) {
                    // Drawn rather than composed from CircularProgressIndicator:
                    // the indicator's API changed shape across Material3
                    // versions, and a ring is four lines of Canvas.
                    val ringStroke = with(LocalDensity.current) { 5.dp.toPx() }
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val inset = ringStroke / 2
                        val arcSize = androidx.compose.ui.geometry.Size(
                            size.width - ringStroke,
                            size.height - ringStroke
                        )
                        drawArc(
                            color = Color.White.copy(alpha = 0.15f),
                            startAngle = -90f,
                            sweepAngle = 360f,
                            useCenter = false,
                            topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                            size = arcSize,
                            style = Stroke(width = ringStroke, cap = StrokeCap.Round)
                        )
                        drawArc(
                            color = Color(0xFFC4B5FD),
                            startAngle = -90f,
                            sweepAngle = 360f * progress.coerceIn(0f, 1f),
                            useCenter = false,
                            topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                            size = arcSize,
                            style = Stroke(width = ringStroke, cap = StrokeCap.Round)
                        )
                    }
                    Text(
                        if (goal > 0) "${(progress * 100).toInt()}%" else "—",
                        color      = Color.White,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize   = 14.sp,
                        textAlign  = TextAlign.Center
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            // Progress bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.White.copy(alpha = 0.15f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            Brush.horizontalGradient(
                                listOf(DivIndigoLight, Color(0xFFC4B5FD))
                            )
                        )
                )
            }

            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // One line, left to right, rather than two captions fighting
                // for the same row.
                when {
                    goal <= 0 -> Spacer(Modifier.width(1.dp))
                    progress >= 1f ->
                        Text("🎯 Goal reached", color = Color(0xFFC4B5FD),
                            fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    else ->
                        Text(
                            "${Money.format(goal - monthlyAvg, currency)} to go",
                            color      = Color.White.copy(alpha = 0.75f),
                            fontSize   = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                }
                Text(
                    if (isForecast) "Projected — nothing received yet" else "Received to date",
                    color    = Color.White.copy(alpha = 0.4f),
                    fontSize = 10.sp,
                    maxLines = 1
                )
            }
        }
    }
}

// ── Received Income ───────────────────────────────────────────────────────────

@Composable
private fun ReceivedIncomeCard(
    toggle: String,
    onToggle: (String) -> Unit,
    amount: Double,
    currencyCode: String
) {
    Card(
        modifier  = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        shape     = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Text(
                    "Received Income",
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                DivToggleChips(left = "MTD", right = "YTD",
                    selected = toggle, onSelect = onToggle)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                Money.format(amount, currencyCode),
                fontWeight = FontWeight.ExtraBold,
                fontSize   = 30.sp,
                color      = DivGreen
            )
            Text(
                if (toggle == "MTD") "Month to date" else "Year to date",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ── Monthly Income bar chart ──────────────────────────────────────────────────

@Composable
private fun MonthlyIncomeCard(
    toggle: String,
    onToggle: (String) -> Unit,
    bars: List<StackedIncomeBar>,
    isForward: Boolean,
    selectedIndex: Int?,
    onBarSelected: (Int) -> Unit,
    currencyCode: String,
    seriesColors: Map<String, Color>
) {
    val maxVal   = bars.maxOfOrNull { it.total }?.takeIf { it > 0 } ?: 1.0
    // Accent for the chrome around the plot (the selected-bar strip, the
    // highlighted month label). The bars themselves are per-holding now, so
    // this is no longer the colour of anything inside the chart.
    val barColor = if (isForward) DivBarFwd else DivBarActual

    Card(
        modifier  = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        shape     = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // The chips share a row with the TITLE ONLY, and the subtitle runs
            // underneath at full card width.
            //
            // Previously the title and subtitle were one column beside the
            // chips, centred against it. The FWD subtitle is longer than the
            // TTM one, so switching to FWD wrapped it to two lines, grew the
            // column, and slid the chips down half a line — the control moved
            // out from under the finger that had just tapped it. Pinning them
            // to the title keeps them still whatever the subtitle does, and
            // giving the subtitle the full width means it usually no longer
            // needs to wrap at all.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                // weight(1f) on the title, none on the chips: Compose measures
                // unweighted children first, so the chips get their full
                // intrinsic width and the heading takes what is left. Without
                // it the title claimed the row and the toggle was squeezed
                // until its labels broke apart.
                Text(
                    "Monthly Income",
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                DivToggleChips(left = "TTM", right = "FWD",
                    selected = toggle, onSelect = onToggle)
            }
            Text(
                if (isForward) "Projected income · from each fund's own payment record"
                else "Tap a bar to see the amount",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Selected bar detail strip
            if (selectedIndex != null && selectedIndex < bars.size) {
                val selectedBar = bars[selectedIndex]
                val lbl = selectedBar.label
                val amt = selectedBar.total
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(barColor.copy(alpha = 0.1f))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    Text(lbl, fontWeight = FontWeight.SemiBold, color = barColor, fontSize = 14.sp)
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            Money.format(amt, currencyCode),
                            fontWeight = FontWeight.ExtraBold,
                            color      = barColor,
                            fontSize   = 17.sp
                        )
                        if (isForward) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(DivAmber.copy(alpha = 0.15f))
                                    .padding(horizontal = 5.dp, vertical = 2.dp)
                            ) {
                                Text("est.", fontSize = 10.sp, color = DivAmber,
                                    fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
                // What that month is actually made of. The stack already shows
                // the split visually; this is the same split with the figures,
                // which is what someone who tapped a specific month wants.
                if (selectedBar.slices.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    selectedBar.slices.forEach { slice ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier
                                        .size(8.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(
                                            seriesColors[slice.key] ?: SeriesPalette.Unknown
                                        )
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    slice.label,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                            Text(
                                Money.format(slice.amount, currencyCode),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // An all-zero series still draws twelve month labels under an empty
            // plot, which reads as the chart having failed rather than as there
            // being nothing to plot. Say which it is, and point at the toggle
            // that does have something to show.
            if (bars.none { it.total > 0.0 }) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(110.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (isForward)
                            "No upcoming payments found for your holdings yet."
                        else
                            "No dividends recorded in the last 12 months. " +
                                "Tap FWD for projected income, or record a payment from a holding.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                }
            } else {
            // Bar chart — each bar is individually clickable.
            //
            // Laid out like the income chart further down the screen: a labelled
            // y-axis on the left, the value printed above each bar, and the
            // month underneath. The bars alone made you tap one to learn what
            // any of them were worth, which is a poor trade for a chart whose
            // whole purpose is comparing months.
            val axisMax = niceAxisMax(maxVal)
            Row(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.width(40.dp).height(PLOT_HEIGHT_DP.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.End
                ) {
                    incomeAxisLabels((4 downTo 0).map { axisMax * it / 4.0 }).forEach {
                        Text(
                            it,
                            fontSize = 8.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
                Spacer(Modifier.width(6.dp))
                Row(
                    modifier = Modifier.weight(1f).height(PLOT_HEIGHT_DP.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    bars.forEachIndexed { i, bar ->
                        val value = bar.total
                        val frac = if (axisMax > 0) (value / axisMax).toFloat().coerceIn(0f, 1f) else 0f
                        val isSelected = selectedIndex == i
                        val alpha = if (selectedIndex == null || isSelected) 1f else 0.45f

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clickable { onBarSelected(i) },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Bottom
                        ) {
                            Box(
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                contentAlignment = Alignment.BottomCenter
                            ) {
                                // Bars fill BAR_HEADROOM of the plot, never
                                // all of it, so the value label above a
                                // full-height bar always has somewhere to sit
                                // instead of being clipped off the top.
                                val barFrac = (frac * BAR_HEADROOM)
                                    .coerceAtLeast(if (value > 0) 0.02f else 0f)
                                // One segment per holding, stacked largest at
                                // the bottom. Each segment is sized as its
                                // share of the bar's own total, so the stack
                                // adds up to exactly the height a single
                                // block would have had.
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth(if (isSelected) 0.85f else 0.7f)
                                        .fillMaxHeight(barFrac)
                                        .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)),
                                    verticalArrangement = Arrangement.Bottom
                                ) {
                                    // Top-down in the layout means reversed:
                                    // the smallest slice is drawn first so the
                                    // largest ends up sitting on the axis.
                                    bar.slices.reversed().forEach { slice ->
                                        val share =
                                            if (value > 0) (slice.amount / value).toFloat() else 0f
                                        if (share <= 0f) return@forEach
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .weight(share)
                                                .background(
                                                    (seriesColors[slice.key] ?: SeriesPalette.Unknown)
                                                        .copy(alpha = alpha)
                                                )
                                        )
                                    }
                                    // Nothing attributable (a payment logged
                                    // against a deleted holding) still has to
                                    // occupy the bar, or the total above it
                                    // would not match its height.
                                    val attributed = bar.slices.sumOf { it.amount }
                                    val remainder = (value - attributed).coerceAtLeast(0.0)
                                    if (value > 0 && remainder > 0) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .weight((remainder / value).toFloat())
                                                .background(barColor.copy(alpha = alpha))
                                        )
                                    }
                                }
                                if (value > 0) {
                                    Text(
                                        Money.compact(value),
                                        fontSize = 8.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                                        maxLines = 1,
                                        modifier = Modifier
                                            .align(Alignment.BottomCenter)
                                            .padding(
                                                bottom = (barFrac * BAR_AREA_DP).dp + 2.dp
                                            )
                                    )
                                }
                            }
                            Spacer(Modifier.height(3.dp))
                            Text(
                                bar.label,
                                fontSize   = 8.sp,
                                color      = if (isSelected) barColor
                                             else MaterialTheme.colorScheme.onSurfaceVariant
                                                      .copy(alpha = 0.7f),
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                textAlign  = TextAlign.Center,
                                maxLines   = 1
                            )
                        }
                    }
                }
            }

            // Legend: only the holdings that actually appear in this window,
            // so a twenty-holding portfolio doesn't print twenty swatches
            // under a chart showing income from three of them.
            val present = bars.flatMap { it.slices }
                .groupBy { it.key }
                .mapValues { (_, s) -> s.sumOf { it.amount } }
                .entries.sortedByDescending { it.value }
            if (present.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                IncomeSeriesLegend(
                    entries = present.map { (key, _) ->
                        key to (seriesColors[key] ?: SeriesPalette.Unknown)
                    }
                )
            }
            }
        }
    }
}

// ── Historical breakdown ──────────────────────────────────────────────────────

@Composable
private fun DividendHistoryCard(
    toggle: String,
    onToggle: (String) -> Unit,
    monthlyBars: List<StackedIncomeBar>,
    yearlyData: List<StackedIncomeBar>,
    currencyCode: String,
    seriesColors: Map<String, Color>
) {
    Card(
        modifier  = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        shape     = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Text(
                    "Historical Income",
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                DivToggleChips(left = "Monthly", right = "Yearly",
                    selected = toggle, onSelect = onToggle)
            }
            Spacer(Modifier.height(14.dp))

            val shown = if (toggle == "Monthly") monthlyBars else yearlyData
            val fallback = if (toggle == "Monthly") DivBarActual else DivIndigo
            val maxAmt = shown.maxOfOrNull { it.total }?.takeIf { it > 0 } ?: 1.0
            shown.forEach { bar ->
                HistoryBarRow(
                    label        = bar.label,
                    bar          = bar,
                    fraction     = (bar.total / maxAmt).toFloat().coerceIn(0f, 1f),
                    currencyCode = currencyCode,
                    seriesColors = seriesColors,
                    fallbackColor = fallback
                )
            }

            // Same legend treatment as the monthly chart, limited to the
            // holdings that contributed something in this view.
            val present = shown.flatMap { it.slices }
                .groupBy { it.key }
                .mapValues { (_, s) -> s.sumOf { it.amount } }
                .entries.sortedByDescending { it.value }
            if (present.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                IncomeSeriesLegend(
                    entries = present.map { (key, _) ->
                        key to (seriesColors[key] ?: SeriesPalette.Unknown)
                    }
                )
            }
        }
    }
}

@Composable
private fun HistoryBarRow(
    label: String,
    bar: StackedIncomeBar,
    fraction: Float,
    currencyCode: String,
    seriesColors: Map<String, Color>,
    fallbackColor: Color
) {
    val amount = bar.total
    Row(
        modifier          = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.width(44.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label,
                fontSize   = 12.sp,
                color      = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
                maxLines   = 1
            )
            if (bar.isProjected) {
                Spacer(Modifier.width(3.dp))
                // A dot rather than a text suffix: "2029e" crowds a 44dp
                // column at larger font scales, and the dimmed bar below
                // already carries the same meaning — this is only for someone
                // scanning the label column alone.
                Box(
                    modifier = Modifier
                        .size(3.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant)
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .height(7.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .alpha(if (bar.isProjected) 0.55f else 1f)
        ) {
            if (fraction > 0f) {
                // The bar is the row's share of the largest row; within it,
                // each holding takes its share of THIS row — so the segment
                // boundaries line up with the legend and the figure on the
                // right without the row's own length changing.
                Row(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(4.dp))
                ) {
                    var drawn = 0.0
                    bar.slices.forEach { slice ->
                        val share = if (amount > 0) (slice.amount / amount).toFloat() else 0f
                        if (share <= 0f) return@forEach
                        drawn += slice.amount
                        Box(
                            modifier = Modifier
                                .weight(share)
                                .fillMaxHeight()
                                .background(seriesColors[slice.key] ?: SeriesPalette.Unknown)
                        )
                    }
                    val remainder = (amount - drawn).coerceAtLeast(0.0)
                    if (remainder > 0 && amount > 0) {
                        Box(
                            modifier = Modifier
                                .weight((remainder / amount).toFloat())
                                .fillMaxHeight()
                                .background(fallbackColor)
                        )
                    }
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            if (amount > 0) Money.format(amount, currencyCode) else "—",
            modifier   = Modifier.width(76.dp).alpha(if (bar.isProjected) 0.75f else 1f),
            fontSize   = 12.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign  = TextAlign.End,
            color      = if (amount > 0) MaterialTheme.colorScheme.onSurface
                         else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ── Upcoming Dividend card ────────────────────────────────────────────────────

@Composable
private fun UpcomingDividendCard(
    holdingName: String,
    ticker: String,
    exDateMs: Long?,
    payDateMs: Long?,
    estimatedAmount: Double?,
    units: Double,
    perUnit: Double?,
    frequencyPerYear: Int = 4,
    isAnnounced: Boolean = false,
    basisLabel: String? = null,
    currencyCode: String,
    /** Estimated amount converted into [baseCurrencyCode], or null when
     *  [currencyCode] already IS the base currency — nothing to show then. */
    convertedAmount: Double? = null,
    baseCurrencyCode: String? = null,
    /**
     * Each account's slice, as (account label, tax treatment label, units).
     * Empty or single means there is nothing to break down and the split
     * section is not drawn.
     */
    accountSplit: List<Triple<String, String?, Double>> = emptyList(),
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        modifier  = modifier.fillMaxWidth(),
        shape     = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(14.dp)
        ) {
            // Header: name + ticker + estimated amount
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        holdingName,
                        fontWeight = FontWeight.SemiBold,
                        fontSize   = 14.sp,
                        maxLines   = 1,
                        overflow   = TextOverflow.Ellipsis
                    )
                    if (ticker.isNotBlank()) {
                        Text(
                            ticker,
                            color      = DivIndigo,
                            fontSize   = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                if (estimatedAmount != null && estimatedAmount > 0) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            Money.format(estimatedAmount, currencyCode),
                            fontWeight = FontWeight.ExtraBold,
                            fontSize   = 19.sp,
                            color      = DivAmber
                        )
                        // The same payment, converted — shown whenever this
                        // holding's currency isn't the user's default, so a
                        // USD payment on a CAD-default portfolio still reads
                        // as a comparable number at a glance.
                        if (convertedAmount != null && baseCurrencyCode != null) {
                            Text(
                                "≈ ${Money.format(convertedAmount, baseCurrencyCode)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (perUnit != null) {
                            Text(
                                "${String.format("%.4f", perUnit)} × " +
                                "${String.format("%.2f", units)} shares",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Announced (green) once the fund declares the distribution,
            // otherwise Estimated (amber) with how the figure was derived.
            Spacer(Modifier.height(6.dp))
            val badgeColor = if (isAnnounced) GainGreen else DivAmber
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(5.dp))
                    .background(badgeColor.copy(alpha = 0.1f))
                    .padding(horizontal = 7.dp, vertical = 3.dp),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Box(modifier = Modifier.size(5.dp).background(badgeColor, CircleShape))
                Text(
                    run {
                        val freqLabel = when (frequencyPerYear) {
                            12 -> "monthly"; 4 -> "quarterly"; 2 -> "semi-annual"; 1 -> "annual"
                            else -> "×$frequencyPerYear/yr"
                        }
                        if (isAnnounced) "Announced · declared by fund ($freqLabel)"
                        // "Next payment", not "Estimated".
                        //
                        // This card and the forward chart used to be labelled
                        // "Estimated" and "Projected" — two words that mean the
                        // same thing in English, attached to two quantities
                        // that are not the same thing at all: one payment
                        // against a year of them. Naming what each figure IS
                        // costs nothing and removes the question.
                        else "Next payment · ${basisLabel ?: "recent payments"} ($freqLabel)"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = badgeColor
                )
            }

            Spacer(Modifier.height(12.dp))
            Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
            Spacer(Modifier.height(12.dp))

            // Date blocks: Ex-Dividend + Payment
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                DateInfoBlock(
                    modifier = Modifier.weight(1f),
                    label    = "Ex-Dividend Date",
                    dateMs   = exDateMs,
                    dotColor = DivIndigo
                )
                DateInfoBlock(
                    modifier = Modifier.weight(1f),
                    label    = "Payment Date",
                    dateMs   = payDateMs,
                    dotColor = DivGreen
                )
            }

            // Where the payment actually lands.
            //
            // One distribution is paid into each account holding the fund, and
            // which account matters: the same payment is kept whole in one and
            // quietly docked 15% in another. The card shows the combined figure
            // because that is what arrives; this says where.
            if (accountSplit.size > 1 && perUnit != null) {
                Spacer(Modifier.height(10.dp))
                Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                Spacer(Modifier.height(8.dp))
                Text(
                    "Paid into",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                accountSplit.forEach { (accountName, treatmentLabel, sliceUnits) ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            accountName,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (treatmentLabel != null) {
                            Text(
                                treatmentLabel,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        Text(
                            Money.format(perUnit * sliceUnits, currencyCode),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = DivAmber
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DateInfoBlock(
    modifier: Modifier,
    label: String,
    dateMs: Long?,
    dotColor: Color
) {
    Row(
        modifier          = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier         = Modifier
                .size(34.dp)
                .background(dotColor.copy(alpha = 0.12f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.CalendarToday,
                contentDescription = null,
                tint     = dotColor,
                modifier = Modifier.size(16.dp)
            )
        }
        Column {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (dateMs != null) {
                Text(
                    divDateFmt.format(Date(dateMs)),
                    fontWeight = FontWeight.SemiBold,
                    fontSize   = 12.sp
                )
                val days = daysUntil(dateMs)
                Text(
                    if (days == 0L) "Today" else "in $days day${if (days != 1L) "s" else ""}",
                    style = MaterialTheme.typography.labelSmall,
                    color = dotColor,
                    fontWeight = FontWeight.Medium
                )
            } else {
                Text(
                    "Not yet announced",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ── Shared primitives ─────────────────────────────────────────────────────────

@Composable
private fun DivToggleChips(
    left: String,
    right: String,
    selected: String,
    onSelect: (String) -> Unit
) {
    val selBg   = MaterialTheme.colorScheme.primary
    val unselBg = MaterialTheme.colorScheme.surfaceVariant
    val selFg   = MaterialTheme.colorScheme.onPrimary
    val unselFg = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier          = Modifier.background(unselBg, RoundedCornerShape(20.dp)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        listOf(left, right).forEach { option ->
            val isSel = option == selected
            Box(
                modifier = Modifier
                    .clickable { onSelect(option) }
                    .background(
                        if (isSel) selBg else Color.Transparent,
                        RoundedCornerShape(20.dp)
                    )
                    .padding(horizontal = 11.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    option,
                    fontSize   = 12.sp,
                    // A three-letter chip label must never wrap. When the header
                    // text beside it grew longer, the row squeezed these chips
                    // until "TTM" broke across two lines as "TT / M" and "FWD"
                    // was pushed off the card entirely.
                    maxLines   = 1,
                    softWrap   = false,
                    color      = if (isSel) selFg else unselFg,
                    fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }
    }
}

// ── Holding picker for logging a past payment ─────────────────────────────────

@Composable
private fun HoldingPickerDialog(
    holdings: List<HoldingEntity>,
    accounts: List<AccountEntity>,
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    // One entry per SECURITY, not per stored row.
    //
    // The same fund held in a TFSA, an FHSA and an RRSP is three rows in the
    // database — necessarily, since cost basis and tax treatment are per
    // account — and this dialog listed all three under the identical name.
    // Three indistinguishable lines, no way to tell which account each meant,
    // and a payment logged against whichever one happened to be tapped.
    //
    // Grouped, a holding is one line. Where it genuinely does sit in more than
    // one account, the accounts are listed underneath it as the choice being
    // made, which is what the three rows were failing to ask.
    val groups = holdings
        .groupBy { it.securityKey }
        .entries
        .sortedByDescending { (_, rows) -> rows.sumOf { it.units } }

    fun accountName(h: HoldingEntity): String =
        accounts.firstOrNull { it.id == h.accountId }?.displayName ?: "Unassigned"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose a holding") },
        text  = {
            if (holdings.isEmpty()) {
                Text(
                    "No tickered holdings found. Add a holding with a ticker symbol first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(groups) { (_, rows) ->
                        val principal = rows.maxByOrNull { it.units } ?: rows.first()
                        val single    = rows.size == 1

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (single) Modifier.clickable { onPick(principal.id) }
                                    else Modifier
                                )
                                .padding(vertical = 10.dp, horizontal = 4.dp)
                        ) {
                            Text(principal.name, fontWeight = FontWeight.Medium, fontSize = 14.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (!principal.ticker.isNullOrBlank()) {
                                Text(principal.ticker, color = DivIndigo, fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold)
                            }

                            if (!single) {
                                Spacer(Modifier.height(6.dp))
                                rows.sortedByDescending { it.units }.forEach { row ->
                                    Text(
                                        accountName(row),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { onPick(row.id) }
                                            .padding(start = 12.dp, top = 6.dp, bottom = 6.dp)
                                    )
                                }
                            }
                        }
                        Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun GoalDialog(
    currentGoal: Double,
    currency: String,
    onConfirm: (Double) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember {
        mutableStateOf(if (currentGoal > 0) currentGoal.toInt().toString() else "")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Monthly Income Goal") },
        text  = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Set your monthly dividend income target in $currency.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value           = text,
                    onValueChange   = { text = it.filter { c -> c.isDigit() || c == '.' } },
                    label           = { Text("Monthly goal ($currency)") },
                    singleLine      = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.toDoubleOrNull() ?: 0.0) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
