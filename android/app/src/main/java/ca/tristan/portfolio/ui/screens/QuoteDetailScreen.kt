package ca.tristan.portfolio.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ca.tristan.portfolio.data.MarketIndices
import ca.tristan.portfolio.net.HistoryBar
import ca.tristan.portfolio.ui.PortfolioViewModel
import ca.tristan.portfolio.ui.components.WbDimens
import ca.tristan.portfolio.ui.components.StockAreaChart
import ca.tristan.portfolio.ui.components.WealthBoardTopBar
import ca.tristan.portfolio.ui.format.TickerFlag
import ca.tristan.portfolio.ui.theme.GainGreen
import ca.tristan.portfolio.ui.theme.LossRed

/**
 * Country flag for a Yahoo ticker, derived from its exchange suffix and, for
 * unsuffixed symbols, the quote currency. Shown in the top-right of the app bar
 * so it's obvious at a glance which market (and currency) a quote trades on.
 */
private fun flagForTicker(ticker: String, currency: String?): String =
    TickerFlag.forTicker(ticker, exchange = null, currency = currency)

private data class RangeOption(val label: String, val range: String, val interval: String)

private val RANGE_OPTIONS = listOf(
    RangeOption("1D", "1d", "5m"),
    RangeOption("1W", "5d", "15m"),
    RangeOption("1M", "1mo", "1d"),
    RangeOption("6M", "6mo", "1d"),
    RangeOption("YTD", "ytd", "1d"),
    RangeOption("1Y", "1y", "1wk"),
    RangeOption("5Y", "5y", "1mo"),
    RangeOption("All", "max", "1mo")
)

/**
 * Quote detail screen: Apple-Stocks-style area chart with filled gradient,
 * y-axis price labels, x-axis time labels, and volume bars.
 *
 * The gain/loss badge reflects the selected time range — not just the day —
 * so switching from 1D to 1Y shows the one-year change.
 */
@Composable
fun QuoteDetailScreen(
    viewModel: PortfolioViewModel,
    watchlistId: Long,
    onBack: () -> Unit,
    onRemoved: () -> Unit
) {
    val watchlist by viewModel.watchlist.collectAsStateWithLifecycle()
    val quotes    by viewModel.quotes.collectAsStateWithLifecycle()
    val item      = watchlist.firstOrNull { it.id == watchlistId }
    val ticker    = item?.ticker
    val listQuote = ticker?.let { quotes[it] }

    // The Markets list is painted from a batched spark fetch, which carries a
    // price and little else — no 52-week range, no volume. That is fine for a
    // row in a list and not fine for a detail screen whose Key Stats card was
    // showing "—" for half its rows. Top it up with a full quote once here.
    val detailQuote by produceState<ca.tristan.portfolio.net.Quote?>(null, ticker) {
        value = ticker?.let { viewModel.quoteForTicker(it) }
    }
    val quote = when {
        detailQuote == null -> listQuote
        listQuote == null -> detailQuote
        else -> detailQuote!!.copy(
            // The list's price is the more recently refreshed of the two.
            price = listQuote!!.price,
            previousClose = listQuote!!.previousClose ?: detailQuote!!.previousClose,
            name = listQuote!!.name ?: detailQuote!!.name
        )
    }

    var menuExpanded     by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var selectedRange    by remember { mutableStateOf(RANGE_OPTIONS.first()) }
    var chartBars        by remember { mutableStateOf<List<HistoryBar>>(emptyList()) }
    var loadingChart     by remember { mutableStateOf(false) }
    // Bar under the finger while scrubbing the chart; the header shows this
    // instead of the live price so the readout follows the crosshair.
    var scrubbed         by remember { mutableStateOf<HistoryBar?>(null) }

    LaunchedEffect(ticker, selectedRange) {
        if (ticker == null) return@LaunchedEffect
        loadingChart = true
        chartBars    = viewModel.fetchHistoryBars(ticker, selectedRange.range, selectedRange.interval)
        loadingChart = false
    }

    // Range-aware gain/loss: first bar → last bar (or current price if only one bar)
    val firstPrice    = chartBars.firstOrNull()?.close
    val lastPrice     = chartBars.lastOrNull()?.close ?: quote?.price
    val rangeChange   = if (firstPrice != null && lastPrice != null) lastPrice - firstPrice else null
    val rangeChangePct = if (firstPrice != null && firstPrice != 0.0 && rangeChange != null)
        (rangeChange / firstPrice) * 100.0 else null
    val positive      = (rangeChange ?: 0.0) >= 0

    val currency = quote?.currency ?: "USD"

    Scaffold(
        topBar = {
            WealthBoardTopBar(
                // Indices show their real name here too, so the detail screen
                // header matches the Markets list rather than showing "^GSPTSE".
                title = item?.customName
                    ?: ticker?.let { MarketIndices.displayName(it) }
                    ?: "Quote",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Market flag, top-right
                    ticker?.let {
                        Text(
                            flagForTicker(it, quote?.currency),
                            fontSize = 20.sp,
                            modifier = Modifier.padding(end = 4.dp)
                        )
                    }
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More options")
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(
                            text    = { Text("Rename") },
                            onClick = { menuExpanded = false; showRenameDialog = true }
                        )
                        DropdownMenuItem(
                            text    = { Text("Remove from watchlist") },
                            onClick = {
                                menuExpanded = false
                                item?.let { viewModel.removeQuote(it.id) }
                                onRemoved()
                            }
                        )
                    }
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->

        if (item == null || ticker == null) {
            Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
                Text("Quote not found.")
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {

            // ── Price header ─────────────────────────────────────────────
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ca.tristan.portfolio.ui.components.TickerLogo(
                    logoUrl = quote?.logoUrl,
                    label = ticker,
                    size = 44.dp
                )
                Column {
                if (item.customName != null) {
                    Text(ticker, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                quote?.name?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(6.dp))

                if (quote == null) {
                    Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    // Currency label
                    Text(
                        currency,
                        style     = MaterialTheme.typography.labelSmall,
                        color     = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        String.format("%.2f", scrubbed?.close ?: quote.price),
                        style     = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold
                    )
                    // While scrubbing, show that point's timestamp in place of
                    // the range badge so the number on screen is unambiguous.
                    scrubbed?.let { bar ->
                        Text(
                            java.text.SimpleDateFormat("MMM d, yyyy  h:mm a", java.util.Locale.getDefault())
                                .format(java.util.Date(bar.timestampMs)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Range change badge
                    if (scrubbed == null && rangeChange != null && rangeChangePct != null) {
                        val sign  = if (positive) "+" else ""
                        val color = if (positive) GainGreen else LossRed
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = color.copy(alpha = 0.15f),
                            modifier = Modifier.padding(top = 4.dp)
                        ) {
                            Text(
                                "$sign${String.format("%.2f", rangeChange)}  " +
                                        "($sign${String.format("%.2f", rangeChangePct)}%)  ${selectedRange.label}",
                                style     = MaterialTheme.typography.bodyMedium,
                                color     = color,
                                fontWeight = FontWeight.Medium,
                                modifier  = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    // Last trade time, directly under the change badge.
                    //
                    // Without it a stale close is indistinguishable from a live
                    // quote: the Markets list carries this on every row, and
                    // dropping it on the one screen someone opens to look
                    // closely was the wrong way round. Rendered in the
                    // exchange's own zone (the same rule the chart's axis
                    // uses), so a PSE or ASX close doesn't read as a local
                    // morning.
                    if (scrubbed == null) {
                        quote?.marketTimeMillis?.let { ts ->
                            Text(
                                ca.tristan.portfolio.ui.format.TradeTime.label(
                                    atMillis = ts,
                                    providerZone = quote?.exchangeTimezone,
                                    ticker = ticker
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
                }
            }

            // ── Range selector chips ──────────────────────────────────────
            // Equal-weight chips in a non-scrolling row, matching the control
            // on My Portfolio exactly.
            //
            // This used to be a horizontally-scrolling row of intrinsically
            // sized chips. Eight of them need more width than a phone has, so
            // the row overflowed at BOTH ends: "1D" was clipped off the left
            // edge and "All" off the right, with nothing to indicate either was
            // scrollable. It read as a broken layout, and the first chip — the
            // default selection — was the one you couldn't see.
            //
            // Dividing the available width instead means every range is always
            // visible and tappable, and the row lines up with the chart card's
            // 12.dp inset below it rather than sitting on its own margin.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RANGE_OPTIONS.forEach { option ->
                    val isSelected = option == selectedRange
                    Surface(
                        shape   = RoundedCornerShape(7.dp),
                        color   = if (isSelected) MaterialTheme.colorScheme.primary
                                  else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        border  = if (isSelected) null
                                  else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier
                            .weight(1f)
                            .height(28.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .clickable { selectedRange = option }
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Text(
                                option.label,
                                fontSize   = 10.sp,
                                maxLines   = 1,
                                softWrap   = false,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color      = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                             else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // ── Chart ─────────────────────────────────────────────────────
            // Boxed in its own card with a matching inset on both sides, so the
            // plot no longer bleeds off the left edge of the screen. Gridlines
            // are drawn by StockAreaChart.
            Card(
                modifier  = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                shape     = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(0.dp),
                colors    = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                border    = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
            ) {
                when {
                    loadingChart -> {
                        Box(
                            modifier          = Modifier.fillMaxWidth().height(240.dp),
                            contentAlignment  = Alignment.Center
                        ) { CircularProgressIndicator(modifier = Modifier.size(32.dp)) }
                    }
                    chartBars.size >= 2 -> {
                        StockAreaChart(
                            bars       = chartBars,
                            positive   = positive,
                            rangeLabel = selectedRange.label,
                            // Intraday times belong to the market's clock, not
                            // the reader's — otherwise the FTSE reads as opening
                            // at 3 AM to someone in Toronto.
                            exchangeTimeZoneId = quote?.exchangeTimezone,
                            // Yesterday's close on 1D — the price the day's
                            // move is actually quoted from, and which the open
                            // can sit either side of after an overnight gap. On
                            // every longer range it is the period's own opening
                            // close: "previous close" means nothing there, but
                            // "where this range started" means a great deal,
                            // and once a chart has been panned up by a rally
                            // the line's own starting height is hard to find by
                            // eye.
                            baseline = if (selectedRange.label == "1D") {
                                quote?.previousClose ?: chartBars.firstOrNull()?.close
                            } else {
                                chartBars.firstOrNull()?.close
                            },
                            showGrid   = true,
                            onScrub    = { scrubbed = it },
                            modifier   = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                        )
                        // The zone caption that used to sit here is gone. The
                        // last-trade line under the price already names the
                        // clock ("Sept 11, 4:46 p.m. EDT" / "12:17 p.m.
                        // GMT+10:00"), so repeating it under the chart said the
                        // same thing twice — and only on foreign listings,
                        // which made those screens look different from US ones
                        // for no reason the reader could see.
                    }
                    else -> {
                        Box(
                            modifier          = Modifier.fillMaxWidth().height(240.dp),
                            contentAlignment  = Alignment.Center
                        ) {
                            Text(
                                "No chart data for this range.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // ── Key stats ─────────────────────────────────────────────────
            if (quote != null) {
                Spacer(Modifier.height(16.dp))
                Card(
                    modifier  = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    shape     = RoundedCornerShape(16.dp),
                    elevation = CardDefaults.cardElevation(1.dp)
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        StatRow("Currency",         currency)
                        Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        StatRow("Prev close",       quote.previousClose?.let { String.format("%.2f", it) })
                        Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        StatRow("Open",             quote.open?.let { String.format("%.2f", it) })
                        Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        StatRow("Day high",         quote.dayHigh?.let { String.format("%.2f", it) })
                        Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        StatRow("Day low",          quote.dayLow?.let { String.format("%.2f", it) })
                        Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        StatRow("52-wk high",       quote.fiftyTwoWeekHigh?.let { String.format("%.2f", it) })
                        Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        StatRow("52-wk low",        quote.fiftyTwoWeekLow?.let { String.format("%.2f", it) })
                        Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        StatRow("Volume",           quote.volume?.let { String.format("%,d", it) })
                        Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        StatRow("Avg vol (3mo)",    quote.avgVolume3Month?.let { String.format("%,d", it) })
                    }
                }
            }

            // ── Futures / extended hours ──────────────────────────────────
            // Fetched on open, for this one symbol only.
            //
            // The Markets list deliberately stops requesting after-hours prints
            // during the session, because 25 per-ticker calls an hour buy a
            // number that cannot exist while the market is trading. That saving
            // shouldn't cost the user the data entirely — opening a ticker is an
            // explicit ask, and one request for the symbol on screen is cheap.
            val extended by produceState<ca.tristan.portfolio.net.ExtendedQuote?>(
                initialValue = null,
                key1 = ticker
            ) {
                value = ticker?.let { viewModel.fetchExtendedFor(it) }
            }
            extended?.let { ext ->
                Spacer(Modifier.height(16.dp))
                Text(
                    ext.label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
                Spacer(Modifier.height(6.dp))
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        val up = ext.change >= 0
                        val tint = if (up) GainGreen else LossRed
                        Text(
                            String.format("%.2f", ext.price),
                            fontWeight = FontWeight.Bold,
                            fontSize = 22.sp
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "${if (up) "+" else ""}${String.format("%.2f", ext.change)} " +
                                "(${if (up) "+" else ""}${String.format("%.2f", ext.changePercent)}%)",
                            color = tint,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            ca.tristan.portfolio.ui.format.TradeTime.label(
                                atMillis = ext.atMillis,
                                providerZone = ext.zoneId,
                                ticker = ext.symbol
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(Modifier.height(WbDimens.ScrollBottomGap))
        }

        if (showRenameDialog) {
            var name by remember { mutableStateOf(item.customName ?: "") }
            AlertDialog(
                onDismissRequest = { showRenameDialog = false },
                title            = { Text("Rename quote") },
                text             = {
                    OutlinedTextField(
                        value         = name,
                        onValueChange = { name = it },
                        label         = { Text("Display name") },
                        singleLine    = true
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.renameQuote(item.id, name.ifBlank { null })
                        showRenameDialog = false
                    }) { Text("Save") }
                },
                dismissButton = {
                    TextButton(onClick = { showRenameDialog = false }) { Text("Cancel") }
                }
            )
        }
    }
}

@Composable
private fun StatRow(label: String, value: String?) {
    // Label and value share one size. The value used to inherit the 16sp
    // default while the label was 14sp, which left the two columns visibly
    // mismatched and let long index figures crowd the row.
    Row(
        modifier              = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically
    ) {
        Text(
            label,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
        Text(
            value ?: "—",
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            textAlign = TextAlign.End
        )
    }
}
