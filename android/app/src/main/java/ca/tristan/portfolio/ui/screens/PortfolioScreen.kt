package ca.tristan.portfolio.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ca.tristan.portfolio.data.PortfolioSeries
import ca.tristan.portfolio.net.HistoryBar
import ca.tristan.portfolio.ui.components.WbDimens
import ca.tristan.portfolio.ui.components.StockAreaChart
import ca.tristan.portfolio.ui.AccountAllocation
import ca.tristan.portfolio.ui.DashboardState
import ca.tristan.portfolio.ui.PortfolioViewModel
import ca.tristan.portfolio.data.db.HoldingEntity
import ca.tristan.portfolio.ui.components.WbCard
import ca.tristan.portfolio.ui.format.Money
import ca.tristan.portfolio.ui.format.TickerFlag


// Indigo gradient shared with the Dividends tab's Passive Income Goal card,
// so the app's two headline cards read as a matched pair.
/** Range options for the Total Value chart — the same set the stock charts use. */
data class PortfolioRange(val label: String, val range: String, val interval: String)

private val PORTFOLIO_RANGES = listOf(
    PortfolioRange("1D",  "1d",  "30m"),
    PortfolioRange("1W",  "5d",  "1h"),
    PortfolioRange("1M",  "1mo", "1d"),
    PortfolioRange("6M",  "6mo", "1d"),
    PortfolioRange("YTD", "ytd", "1d"),
    PortfolioRange("1Y",  "1y",  "1d"),
    PortfolioRange("5Y",  "5y",  "1wk"),
    PortfolioRange("ALL", "max", "1mo")
)

private val GoalIndigoDark = Color(0xFF1E1B4B)
private val GoalIndigoMid  = Color(0xFF312E81)

// Brand palette for allocation pie slices and bars — cycles for as many holdings as exist.
private val AllocationColors = listOf(
    Color(0xFF0F2A43), Color(0xFFC9A227), Color(0xFF3D6E8C),
    Color(0xFF8C6A1F), Color(0xFF5A7A63), Color(0xFF7B4F8C), Color(0xFFB55A3A)
)

private fun allocationColorAt(index: Int): Color = AllocationColors[index % AllocationColors.size]

/**
 * "My Portfolio" tab: total portfolio value, day change, allocation by holdings
 * (with pie-chart drill-down), dividend summary, and the My Holdings list.
 * Settings have moved to the bottom navigation's hamburger tab.
 */
@Composable
fun MyPortfolioScreen(
    viewModel: PortfolioViewModel,
    onOpenHolding: (Long) -> Unit,
    onOpenDividends: () -> Unit,
    onAddHolding: () -> Unit,
    /** Opens one account's own screen from the summary below the holdings. */
    onOpenAccount: (Long) -> Unit = {}
) {
    val dashboard by viewModel.dashboard.collectAsStateWithLifecycle()

    // Collected here, in the composable body, NOT inside the LazyColumn.
    // A LazyColumn's content lambda is LazyListScope — an ordinary lambda, not
    // a @Composable one — so collectAsStateWithLifecycle and remember are
    // illegal inside it. State the list needs has to be read out here and
    // captured.
    val upcomingForTax by viewModel.upcomingDividends.collectAsStateWithLifecycle()
    val accountsForTax by viewModel.accounts.collectAsStateWithLifecycle()
    val taxDrag = remember(upcomingForTax, accountsForTax, dashboard.baseCurrency) {
        viewModel.portfolioTaxDrag()
    }

    val holdings  by viewModel.holdings.collectAsStateWithLifecycle()
    val quotes    by viewModel.quotes.collectAsStateWithLifecycle()

    // Portfolio value trend for the Total Value card. Built from each
    // holding's own price history so the longer ranges have real data from
    // day one, rather than only what the app has locally recorded.
    var valueRange by remember { mutableStateOf(PORTFOLIO_RANGES[2]) }   // 1M
    val valueSeries by produceState(PortfolioSeries(), valueRange, holdings.size) {
        value = viewModel.portfolioValueSeries(valueRange.range, valueRange.interval)
    }

    // The figure under the total, for the range the chips are on.
    //
    // It used to be the day change, full stop, whatever the chips said: tapping
    // 1M moved the chart and left the headline still talking about this
    // afternoon, so the two halves of the card described different periods and
    // only one of them was labelled.
    //
    // 1D keeps using the dashboard's own day change rather than the series —
    // that one is computed from each holding's previous close, the exact number
    // the exchange would quote, where the series would have to infer it from
    // whichever bar happened to open the session.
    val useDayChange = valueRange.label == "1D" || valueSeries.isEmpty
    val headlineAbs = if (useDayChange) dashboard.dayChangeAbsolute else valueSeries.gain
    val headlinePct = if (useDayChange) dashboard.dayChangePercent else valueSeries.gainPercent
    val headlinePeriod = when {
        useDayChange -> "today"
        valueSeries.coversRequestedRange -> "over ${valueRange.label}"
        // Three weeks of history under a chip marked 1Y is not a year, and
        // "over 1Y" there would be the same overstatement the percentage itself
        // was just fixed to stop making.
        else -> valueSeries.startMs?.let {
            "since " + java.text.SimpleDateFormat("MMM d", java.util.Locale.US)
                .format(java.util.Date(it))
        } ?: "over ${valueRange.label}"
    }
    var addMenuExpanded by remember { mutableStateOf(false) }
    var showAllocationPie by remember { mutableStateOf(false) }

    // Refresh live holding prices when the screen opens with stale data.
    // Staleness-gated, not composition-gated. This fires again every time the
    // screen re-enters composition — which returning to the tab does — so an
    // unconditional refresh here made the figures change on a tab switch. The
    // pull-to-refresh below still forces a fetch unconditionally.
    LaunchedEffect(Unit) { viewModel.refreshPortfolioIfStale() }

    val onRefreshPortfolio: () -> Unit = { viewModel.refreshPortfolio() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My Portfolio", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
                // Settings icon removed — accessible from the bottom nav ≡ tab.
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { onAddHolding() },
                containerColor = MaterialTheme.colorScheme.secondary,
                contentColor = MaterialTheme.colorScheme.onSecondary
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Add holding")
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            item {
                TotalValueCard(
                    total       = dashboard.totalValue,
                    changeAbs   = headlineAbs,
                    changePct   = headlinePct,
                    periodLabel = headlinePeriod,
                    baseCurrency = dashboard.baseCurrency,
                    unconverted = dashboard.unconvertedCurrencies,
                    onRefresh   = onRefreshPortfolio
                )
            }
            item {
                PortfolioTrendCard(
                    series        = valueSeries,
                    ranges        = PORTFOLIO_RANGES,
                    selected      = valueRange,
                    onRangeChange = { valueRange = it },
                    dayChangeAbs  = dashboard.dayChangeAbsolute,
                    dayChangePct  = dashboard.dayChangePercent
                )
            }
            item { Spacer(Modifier.height(12.dp)) }
            if (dashboard.allocations.isNotEmpty()) {
                item {
                    AllocationCard(
                        allocations = dashboard.allocations,
                        onTap = { showAllocationPie = true }
                    )
                }
                item { Spacer(Modifier.height(20.dp)) }
            }
            // Tax drag, above the holdings list.
            //
            // The per-holding notes answer "is this costing me anything?" one
            // position at a time. The action they imply — move it to a
            // sheltered account — is a portfolio decision, so the total is what
            // makes it worth acting on. Shown only when there is a real,
            // avoidable cost; silence is correct the rest of the time.
            if (taxDrag != null) {
                item {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(16.dp),
                        color = ca.tristan.portfolio.ui.theme.LossRed.copy(alpha = 0.12f)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                "Losing about ${Money.format(taxDrag.annualCost, taxDrag.currency)} a year to tax",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = ca.tristan.portfolio.ui.theme.LossRed
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "${taxDrag.holdingCount} holding${if (taxDrag.holdingCount == 1) "" else "s"} " +
                                    "in a registered account pay foreign dividends. The tax withheld " +
                                    "abroad can't be claimed back there, because the account owes no " +
                                    "domestic tax to credit it against. Open a holding to see which, " +
                                    "and whether a different account would avoid it.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Estimate only — see \"How tax works here\" in the Menu.",
                                style = MaterialTheme.typography.bodySmall,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }

            // "My Holdings" section — grouped by currency so CAD and USD
            // positions are easy to read separately at a glance.
            item {
                Text(
                    "My Holdings",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
                Spacer(Modifier.height(4.dp))
            }
            if (holdings.isEmpty()) {
                item {
                    Text(
                        "No holdings yet — tap + to add one and it will appear here.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp)
                    )
                }
            } else {
                // Group holdings by their recorded currency (CAD, USD, GBP, …),
                // sorted so CAD leads (home market), then alphabetically.
                val groups = holdings
                    .groupBy { it.currency.uppercase().ifBlank { "CAD" } }
                    .entries
                    .sortedWith(
                        compareBy<Map.Entry<String, List<HoldingEntity>>> {
                            if (it.key == "CAD") "" else it.key
                        }
                    )

                groups.forEach { (currency, groupHoldings) ->
                    // Compute the subtotal in the group's native currency.
                    val groupSubtotal = groupHoldings.sumOf { h ->
                        val price = if (!h.ticker.isNullOrBlank()) {
                            quotes[h.ticker]?.price ?: h.lastKnownPrice ?: h.manualPrice ?: 0.0
                        } else {
                            h.lastKnownPrice ?: h.manualPrice ?: 0.0
                        }
                        price * h.units
                    }
                    item(key = "header-$currency") {
                        CurrencySectionHeader(currency = currency, subtotal = groupSubtotal)
                    }
                    // One row per SECURITY, not per stored holding row.
                    //
                    // The same fund bought in a TFSA, an RRSP and an FHSA is
                    // three rows in the database — necessarily, since cost
                    // basis and tax treatment are per account. But the user
                    // owns ONE position in it, and listing "ISHARES CORE
                    // EQUITY ETF" three times reads as duplicate data rather
                    // than as an account split.
                    //
                    // So rows are merged on the security and the per-account
                    // detail moves inside, where it is an answer to "how is
                    // this split?" rather than noise in the main list.
                    val bySecurity = groupHoldings
                        .groupBy { h ->
                            h.ticker?.takeIf { it.isNotBlank() }?.uppercase() ?: h.name
                        }
                        .entries
                        .sortedByDescending { (_, rows) ->
                            rows.sumOf { h ->
                                val p = if (!h.ticker.isNullOrBlank()) {
                                    quotes[h.ticker]?.price ?: h.lastKnownPrice ?: h.manualPrice ?: 0.0
                                } else h.lastKnownPrice ?: h.manualPrice ?: 0.0
                                p * h.units
                            }
                        }

                    items(bySecurity, key = { it.key }) { (_, rows) ->
                        fun priceOf(h: HoldingEntity): Double =
                            if (!h.ticker.isNullOrBlank())
                                quotes[h.ticker]?.price ?: h.lastKnownPrice ?: h.manualPrice ?: 0.0
                            else h.lastKnownPrice ?: h.manualPrice ?: 0.0

                        MergedHoldingRow(
                            rows = rows,
                            valueOf = { h -> priceOf(h) * h.units },
                            onOpenHolding = onOpenHolding
                        )
                    }
                }
            }
            // Accounts summarised AFTER the holdings, not before them.
            //
            // This screen answers "what do I own?", and the holdings are that
            // answer; the account totals are a second cut of the same money.
            // Above the list they would push the holdings below the fold and
            // read as the main event.
            if (accountsForTax.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(20.dp))
                    Text(
                        "Accounts Summary",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 20.dp)
                    )
                    Spacer(Modifier.height(6.dp))
                }
                items(accountsForTax, key = { "acct-${it.id}" }) { account ->
                    val value = holdings
                        .filter { it.accountId == account.id }
                        .sumOf { h ->
                            val price = if (!h.ticker.isNullOrBlank()) {
                                quotes[h.ticker]?.price ?: h.lastKnownPrice ?: h.manualPrice ?: 0.0
                            } else {
                                h.lastKnownPrice ?: h.manualPrice ?: 0.0
                            }
                            viewModel.amountInBase(price * h.units, h.currency)
                        }
                    AccountSummaryRow(
                        name = account.displayName,
                        treatment = account.taxTreatment?.label(),
                        value = value,
                        currency = dashboard.baseCurrency,
                        onClick = { onOpenAccount(account.id) }
                    )
                }
            }

            item { Spacer(Modifier.height(WbDimens.ScrollBottomGap)) }
        }
    }

    // Allocation pie chart dialog (shown when user taps "Allocation by Holdings")
    if (showAllocationPie && dashboard.allocations.isNotEmpty()) {
        AllocationPieDialog(
            allocations = dashboard.allocations,
            baseCurrency = dashboard.baseCurrency,
            onDismiss = { showAllocationPie = false }
        )
    }
}

/** One line of the Accounts Summary: name, how it is taxed, what is in it. */
@Composable
private fun AccountSummaryRow(
    name: String,
    treatment: String?,
    value: Double,
    currency: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 3.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(name, fontWeight = FontWeight.Medium)
                Text(
                    // An unset treatment is called out rather than left blank:
                    // it silently switches off every tax figure for everything
                    // in the account.
                    treatment ?: "Tax type not set",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (treatment == null) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(Money.format(value, currency), fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(6.dp))
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun TotalValueCard(
    total: Double,
    changeAbs: Double,
    changePct: Double,
    /** Worded for the sentence it lands in: "today", "over 1M", "since Sep 8". */
    periodLabel: String,
    baseCurrency: String,
    unconverted: List<String>,
    onRefresh: () -> Unit
) {
    // Formatted in the base currency, not a hardcoded locale — this number is
    // the sum of every holding after FX conversion, so labelling it CA$ when
    // the user reports in USD would be a lie about what they're looking at.
    fun money(v: Double) = Money.format(v, baseCurrency)
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        // Indigo gradient matching the Dividends tab's Passive Income Goal
        // card, so the two headline cards in the app read as a matched pair.
        Column(
            modifier = Modifier
                // fillMaxWidth is load-bearing: the chart that used to sit in
                // this column was the widest child and stretched the gradient
                // across the card. With it gone the column wrapped to the width
                // of the text and the gradient painted only the left portion,
                // leaving a bare rectangle beside it.
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(listOf(GoalIndigoDark, GoalIndigoMid))
                )
                .padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Total value",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White.copy(alpha = 0.75f)
                )
                // Naming the currency matters once holdings span more than one:
                // without it there is no way to tell whether this number is
                // Canadian or US dollars.
                Text(
                    baseCurrency.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.6f)
                )
            }
            Text(
                money(total),
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White
            )
            Spacer(Modifier.height(4.dp))
            val positive = changeAbs >= 0
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = (if (positive) ca.tristan.portfolio.ui.theme.GainGreen else ca.tristan.portfolio.ui.theme.LossRed).copy(alpha = 0.18f),
                modifier = Modifier.clickable { onRefresh() }
            ) {
                Text(
                    // signed() carries the sign itself, so a loss can't come
                    // out as "--CA$12.50" the way a hand-prefixed sign could.
                    "${Money.signed(changeAbs, baseCurrency)}  " +
                        "(${Money.signedPercent(changePct)}) $periodLabel  ↻",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (positive) Color(0xFF6FE3A8) else Color(0xFFE58A82),
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }

            // Only shown when a rate is genuinely missing. Those holdings are
            // counted at face value, which overstates or understates the total,
            // and the user deserves to know before acting on the number.
            if (unconverted.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "${unconverted.joinToString(", ")} not converted — no exchange " +
                        "rate yet. Pull to refresh once you're online.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFE58A82)
                )
            }

            // The value trend used to be drawn inside this card. It now lives
            // in its own card below, where it gets full width, a real axis and
            // the same range selector as a stock's chart — an unlabelled
            // sparkline squeezed under the headline number could not be read
            // against any particular period.
        }
    }
}

/**
 * Portfolio value over time, with the same range selector a single stock gets.
 *
 * Separate from the headline card so the line has room to be read: the ranges
 * are the ones from the quote detail screen (1D … ALL), so switching between a
 * holding's chart and the whole portfolio's feels like the same control.
 */
@Composable
private fun PortfolioTrendCard(
    series: PortfolioSeries,
    ranges: List<PortfolioRange>,
    selected: PortfolioRange,
    onRangeChange: (PortfolioRange) -> Unit,
    dayChangeAbs: Double,
    dayChangePct: Double
) {
    val history = series.points
    // On 1D the badge is TODAY's change — measured from each holding's
    // previous close, the same figure the Total value card prints above it.
    //
    // It used to be the distance the intraday line travelled, which starts at
    // the session's first bar, not at yesterday's close. On a day that gaps
    // down at the open the two disagree badly: -1.08% "today" on one card and
    // -0.48% "1D" on the next, for the same portfolio on the same day.
    val isOneDay = selected.label == "1D"
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Portfolio value",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (isOneDay || !series.isEmpty) {
                    // The return the HOLDINGS produced, not the distance the
                    // line travelled. A month containing a deposit moves the
                    // line by the size of the deposit, and printing that as a
                    // percentage announced a 55% month on a market that moved
                    // about two.
                    val pct = if (isOneDay) dayChangePct else series.gainPercent
                    val up = (if (isOneDay) dayChangeAbs else series.gain) >= 0
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${if (up) "+" else ""}${String.format("%.2f", pct)}%  ${selected.label}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false,
                        color = if (up) ca.tristan.portfolio.ui.theme.GainGreen
                                else ca.tristan.portfolio.ui.theme.LossRed
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // Range selector, above the chart rather than below it.
            //
            // Underneath, the control that decides what the chart shows sat
            // below the thing it changes, and on a tall card it could be off
            // the bottom of the screen while the chart was in full view. The
            // iOS build puts it here for the same reason.
            //
            // Each chip takes an equal share of the row rather than sizing to
            // its own text. Intrinsically-sized chips needed more width than
            // the card has for all eight ranges, so the row scrolled and "5Y"
            // and "ALL" sat outside the card's right edge, reading as a layout
            // break rather than as something to swipe.
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                ranges.forEach { r ->
                    val isSelected = r.label == selected.label
                    Surface(
                        shape = RoundedCornerShape(7.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        border = if (isSelected) null
                                 else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier
                            .weight(1f)
                            .height(28.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .clickable { onRangeChange(r) }
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Text(
                                r.label,
                                fontSize = 10.sp,
                                maxLines = 1,
                                softWrap = false,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            if (history.size >= 2) {
                // The same component the stock detail screen draws, rather than
                // a bare sparkline: it brings the value labels down the side and
                // the dates along the bottom, so a reader can tell what the line
                // is worth and when — an axis-free curve only shows shape. It
                // also makes the portfolio chart and a holding's chart read as
                // the same control, which is what the range chips already imply.
                StockAreaChart(
                    bars = history.map { (ts, value) ->
                        HistoryBar(timestampMs = ts, close = value, volume = null)
                    },
                    // Coloured by the same change the badge prints, so a day
                    // that is down overall is never drawn green because it
                    // recovered a little after the open.
                    positive = if (isOneDay) dayChangeAbs >= 0
                               else history.last().second >= history.first().second,
                    rangeLabel = selected.label,
                    // Holdings can span several exchanges, so there is no single
                    // market clock to use — device time is the honest choice.
                    exchangeTimeZoneId = null,
                    showGrid = true,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxWidth().height(150.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        // Two causes read the same to a user: the tickers have
                        // no bars for this range, or the range mostly pre-dates
                        // the holdings. A shorter range is the fix for both.
                        "Not enough history for this range yet \u2014 try a shorter one.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Says what the line actually covers when it is short of its chip.
            //
            // A three-week-old portfolio under a chip marked 1Y draws three
            // weeks, and the dates along the bottom then contradict the chip
            // with nothing to reconcile them.
            if (!series.coversRequestedRange && series.startMs != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "History starts " + java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.US)
                        .format(java.util.Date(series.startMs!!)) +
                        " — the whole range isn't held yet.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun DividendSummaryCard(dashboard: DashboardState, onClick: () -> Unit) {
    // Labelled with the currency these totals are actually expressed in. They
    // were formatted as Canadian dollars unconditionally, which was wrong the
    // moment anyone reported in anything else.
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Dividend income",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.secondary
            )
            Spacer(Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                DividendStat("This month", dashboard.dividendsThisMonth, dashboard.baseCurrency)
                DividendStat("This year", dashboard.dividendsThisYear, dashboard.baseCurrency)
                DividendStat("All time", dashboard.dividendsAllTime, dashboard.baseCurrency)
            }
        }
    }
}

@Composable
private fun DividendStat(label: String, value: Double, currency: String) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(Money.format(value, currency), fontWeight = FontWeight.SemiBold)
    }
}

/**
 * "Allocation by Holdings" card — tapping it opens a pie-chart dialog.
 * Shows the same name/percent progress bars as before, now labelled correctly.
 */
@Composable
private fun AllocationCard(allocations: List<AccountAllocation>, onTap: () -> Unit) {
    val total = allocations.sumOf { it.value }.coerceAtLeast(0.01)
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).clickable(onClick = onTap),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Allocation by Holdings",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.secondary
                )
                Text(
                    "View chart →",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.7f)
                )
            }
            Spacer(Modifier.height(10.dp))
            allocations.forEachIndexed { index, allocation ->
                val fraction = (allocation.value / total).toFloat().coerceIn(0f, 1f)
                val color = allocationColorAt(index)
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(allocation.label, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${String.format("%.1f", fraction * 100)}%",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                        color = color,
                        trackColor = color.copy(alpha = 0.15f),
                        strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
                    )
                }
            }
        }
    }
}

/**
 * Pie-chart dialog shown when the user taps "Allocation by Holdings".
 * Drawn with Canvas arcs — no third-party charting library needed.
 */
@Composable
private fun AllocationPieDialog(
    allocations: List<AccountAllocation>,
    baseCurrency: String,
    onDismiss: () -> Unit
) {
    val total = allocations.sumOf { it.value }.coerceAtLeast(0.01)
    // Allocation values arrive already converted into the base currency —
    // they have to be, or slices from different currencies wouldn't be
    // comparable and the percentages would be meaningless.
    fun money(v: Double) = Money.format(v, baseCurrency)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Allocation by Holdings") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // Pie chart
                Canvas(modifier = Modifier.size(200.dp).padding(8.dp)) {
                    val diameter = size.minDimension
                    val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
                    var startAngle = -90f
                    // Draw segments
                    allocations.forEachIndexed { i, alloc ->
                        val sweep = ((alloc.value / total) * 360.0).toFloat()
                        drawArc(
                            color = allocationColorAt(i),
                            startAngle = startAngle,
                            sweepAngle = sweep,
                            useCenter = true,
                            topLeft = topLeft,
                            size = Size(diameter, diameter)
                        )
                        // Thin separator line
                        drawArc(
                            color = Color.White.copy(alpha = 0.6f),
                            startAngle = startAngle,
                            sweepAngle = 0.8f,
                            useCenter = true,
                            topLeft = topLeft,
                            size = Size(diameter, diameter)
                        )
                        startAngle += sweep
                    }
                    // Center hole for a donut look
                    drawCircle(
                        color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.9f),
                        radius = diameter * 0.28f,
                        center = Offset(size.width / 2f, size.height / 2f)
                    )
                }
                Spacer(Modifier.height(12.dp))
                // Legend
                allocations.forEachIndexed { i, alloc ->
                    val pct = (alloc.value / total) * 100.0
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .background(allocationColorAt(i), CircleShape)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            alloc.label,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            "${String.format("%.1f", pct)}%",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            money(alloc.value),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

/** Flag emoji for an ISO 4217 currency code, or "" when it isn't recognised. */
private fun flagForCurrency(currency: String): String = TickerFlag.forCurrency(currency)

/**
 * Sticky section header for each currency bucket inside "My Holdings".
 * Shows the flag, currency code, and a subtotal in that currency's own units.
 */
@Composable
private fun CurrencySectionHeader(currency: String, subtotal: Double) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val flag = flagForCurrency(currency)
            if (flag.isNotEmpty()) Text(flag, fontSize = 16.sp)
            Text(
                currency.uppercase(),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.secondary
            )
        }
        Text(
            Money.format(subtotal, currency),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * One security, priced and sized as the single position the user owns.
 *
 * The per-account split used to hang off this row behind a "Show split"
 * toggle. It moved to the holding screen — the same place Wealthsimple puts
 * it — because a list is for scanning what you own, and a three-line expansion
 * inside a scannable list is neither the summary nor the detail.
 */
@Composable
private fun MergedHoldingRow(
    rows: List<HoldingEntity>,
    valueOf: (HoldingEntity) -> Double,
    onOpenHolding: (Long) -> Unit
) {
    val first = rows.first()
    val totalUnits = rows.sumOf { it.units }
    val totalValue = rows.sumOf(valueOf)
    val currency = first.currency.uppercase().ifBlank { "CAD" }

    // Opens the largest slice. Every figure on that screen covers the whole
    // position anyway, so which row it is bound to only decides what an edit
    // acts on — and the largest is the likeliest.
    val principal = rows.maxByOrNull(valueOf) ?: first

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenHolding(principal.id) }
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                first.name,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            val subtitle = buildString {
                first.ticker?.let { append(it); append(" · ") }
                append(first.type.label())
                append(" · ")
                append(Money.units(totalUnits))
                append(" units")
                if (rows.size > 1) {
                    append(" · ")
                    append("${rows.size} accounts")
                }
            }
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(Money.format(totalValue, currency), fontWeight = FontWeight.SemiBold)
    }
    Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
}

/**
 * A single holding row, used where no per-account merge applies.
 *
 * Kept alongside [MergedHoldingRow] rather than deleted: it is the simpler
 * shape for any list that is already scoped to one account.
 */
@Composable
private fun IndividualHoldingRow(holding: HoldingEntity, value: Double, onClick: () -> Unit) {
    // Formatted in the holding's own currency. [value] here is deliberately the
    // unconverted figure — this row is what the position is worth on its own
    // statement, so printing a US holding as CA$ would misreport it.
    fun money(v: Double) = Money.format(v, holding.currency.uppercase().ifBlank { "CAD" })
    Column(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text(holding.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                val subtitle = buildString {
                    holding.ticker?.let { append(it); append(" · ") }
                    append(holding.type.label())
                    append(" · ")
                    append(Money.units(holding.units))
                    append(" units")
                }
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(money(value), fontWeight = FontWeight.SemiBold)
        }
    }
    Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
}
