package ca.tristan.portfolio.ui.screens

import android.content.Intent
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ca.tristan.portfolio.ui.securityKey
import ca.tristan.portfolio.report.Benchmark
import ca.tristan.portfolio.report.PerformanceReport
import ca.tristan.portfolio.report.ReportPeriod
import ca.tristan.portfolio.ui.PortfolioViewModel
import ca.tristan.portfolio.ui.format.Money
import ca.tristan.portfolio.ui.ReportUiState
import ca.tristan.portfolio.ui.components.WealthBoardTopBar
import ca.tristan.portfolio.ui.theme.GainGreen
import ca.tristan.portfolio.ui.theme.LossRed
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Reports" tab — builds a full performance report from the transaction ledger
 * and exports it as a PDF.
 *
 * The on-screen summary and the PDF are rendered from the same computed
 * [PerformanceReport], so what the user sees here is exactly what they send.
 */
@Composable
fun ReportsScreen(viewModel: PortfolioViewModel, onOpenPremium: () -> Unit = {}) {
    val context = LocalContext.current
    val state by viewModel.reportState.collectAsStateWithLifecycle()
    val options by viewModel.reportOptions.collectAsStateWithLifecycle()
    val holdings by viewModel.holdings.collectAsStateWithLifecycle()
    val isPremium by ca.tristan.portfolio.billing.Subscriptions.isPremium
        .collectAsStateWithLifecycle()

    var exporting by remember { mutableStateOf(false) }
    var exportingCsv by remember { mutableStateOf(false) }
    var exportError by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = { WealthBoardTopBar(title = "Reports") },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { Spacer(Modifier.height(4.dp)) }

            // ── Period ───────────────────────────────────────────────────
            item {
                SectionLabel("Reporting period")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ReportPeriod.values().forEach { p ->
                        FilterChip(
                            selected = options.period == p,
                            onClick = { viewModel.updateReportOptions { it.copy(period = p) } },
                            label = { Text(p.label, fontSize = 13.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                            )
                        )
                    }
                }
            }

            // ── Scope ────────────────────────────────────────────────────
            item {
                SectionLabel("Holdings")
                var expanded by remember { mutableStateOf(false) }
                val label = options.holdingId
                    ?.let { id -> holdings.firstOrNull { it.id == id }?.let { h -> h.ticker ?: h.name } }
                    ?: "All holdings"
                Box(Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { expanded = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(label, modifier = Modifier.weight(1f))
                        Icon(Icons.Filled.ExpandMore, contentDescription = null)
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        DropdownMenuItem(
                            text = { Text("All holdings") },
                            onClick = {
                                viewModel.updateReportOptions { it.copy(holdingId = null) }
                                expanded = false
                            }
                        )
                        // One entry per SECURITY. The same fund in three
                        // accounts is three stored rows, and listing it three
                        // times gave the reader three identical-looking choices
                        // that each produced a partial report. Picking one now
                        // scopes the report to the whole position.
                        holdings
                            .groupBy { it.securityKey }
                            .map { (_, rows) -> rows.maxByOrNull { it.units } ?: rows.first() }
                            .sortedByDescending { it.units }
                            .forEach { h ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(h.ticker ?: h.name, fontWeight = FontWeight.Medium)
                                            Text(
                                                h.name,
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    },
                                    onClick = {
                                        viewModel.updateReportOptions { it.copy(holdingId = h.id) }
                                        expanded = false
                                    }
                                )
                            }
                    }
                }
            }

            // ── Benchmark ────────────────────────────────────────────────
            item {
                SectionLabel("Compare against")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Benchmark.PRESETS.forEach { b ->
                        FilterChip(
                            selected = options.benchmark?.ticker == b.ticker,
                            onClick = {
                                viewModel.updateReportOptions {
                                    it.copy(benchmark = if (it.benchmark?.ticker == b.ticker) null else b)
                                }
                            },
                            label = { Text(b.ticker, fontSize = 13.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                            )
                        )
                    }
                    FilterChip(
                        selected = options.benchmark == null,
                        onClick = { viewModel.updateReportOptions { it.copy(benchmark = null) } },
                        label = { Text("None", fontSize = 13.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                        )
                    )
                }
            }

            // ── Sections ─────────────────────────────────────────────────
            item {
                SectionLabel("Include in the PDF")
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(vertical = 4.dp)) {
                        ToggleRow("Risk metrics", "Volatility, Sharpe, drawdown, beta", options.includeRiskMetrics) { v ->
                            viewModel.updateReportOptions { it.copy(includeRiskMetrics = v) }
                        }
                        Divider(Modifier.padding(horizontal = 16.dp))
                        ToggleRow("Holdings table", "Per-position cost, value and gain", options.includeHoldingsTable) { v ->
                            viewModel.updateReportOptions { it.copy(includeHoldingsTable = v) }
                        }
                        Divider(Modifier.padding(horizontal = 16.dp))
                        ToggleRow("Realized gains", "Closed positions — useful at tax time", options.includeRealizedGains) { v ->
                            viewModel.updateReportOptions { it.copy(includeRealizedGains = v) }
                        }
                        Divider(Modifier.padding(horizontal = 16.dp))
                        ToggleRow("Dividend income", "Income by month and by holding", options.includeIncome) { v ->
                            viewModel.updateReportOptions { it.copy(includeIncome = v) }
                        }
                        Divider(Modifier.padding(horizontal = 16.dp))
                        ToggleRow("Allocation", "Weights by type, account and position", options.includeAllocation) { v ->
                            viewModel.updateReportOptions { it.copy(includeAllocation = v) }
                        }
                        Divider(Modifier.padding(horizontal = 16.dp))
                        ToggleRow("Activity", "Buys, sells and reinvestments", options.includeTransactionActivity) { v ->
                            viewModel.updateReportOptions { it.copy(includeTransactionActivity = v) }
                        }
                    }
                }
            }

            // ── Action ───────────────────────────────────────────────────
            item {
                Button(
                    // Still no gate here, and deliberately: this builds and
                    // shows the report on-screen, which used to sit behind a
                    // rewarded ad that became a dead end once there was no ad
                    // network to serve one. It's the PDF export below —
                    // what the Premium paywall actually promises — that's
                    // gated now, not the free on-screen report.
                    onClick = { viewModel.generateReport() },
                    enabled = state !is ReportUiState.Running,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    if (state is ReportUiState.Running) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("Building report…")
                    } else {
                        Icon(Icons.Filled.Assessment, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (state is ReportUiState.Ready) "Rebuild report" else "Generate report")
                    }
                }
            }

            // ── Results ──────────────────────────────────────────────────
            when (val s = state) {
                is ReportUiState.Error -> item {
                    Card(colors = CardDefaults.cardColors(containerColor = LossRed.copy(alpha = 0.10f))) {
                        Text(
                            s.message,
                            modifier = Modifier.padding(14.dp),
                            color = LossRed,
                            fontSize = 13.sp
                        )
                    }
                }

                is ReportUiState.Idle -> item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                "What you'll get",
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "A multi-page PDF covering time-weighted and money-weighted returns, " +
                                    "a benchmark comparison, risk metrics, every position's contribution to your gain, " +
                                    "realized gains, dividend income and allocation — built from the transactions you've recorded.",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                is ReportUiState.Running -> Unit

                is ReportUiState.Ready -> {
                    val r = s.report
                    item { ReportSummaryCard(r) }
                    item { ReturnsCard(r) }
                    r.benchmark?.let { item { BenchmarkCard(r) } }
                    if (options.includeRiskMetrics) item { RiskCard(r) }
                    if (options.includeHoldingsTable && r.holdings.isNotEmpty()) item { TopHoldingsCard(r) }

                    if (r.warnings.isNotEmpty()) item {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                            Column(Modifier.padding(14.dp)) {
                                Text("Data notes", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                Spacer(Modifier.height(4.dp))
                                r.warnings.forEach {
                                    Text("• $it", fontSize = 12.sp)
                                }
                            }
                        }
                    }

                    item {
                        Button(
                            onClick = {
                                // Premium-gated — see PremiumScreen's copy
                                // ("Cloud backup, sync and PDF reports.").
                                // Building and viewing the report above stays
                                // free; only the PDF export/share is a
                                // subscriber perk.
                                if (!isPremium) {
                                    onOpenPremium()
                                    return@Button
                                }
                                exporting = true
                                exportError = null
                                viewModel.exportReportPdf(context) { file ->
                                    exporting = false
                                    if (file == null) {
                                        exportError = "Could not create the PDF."
                                    } else {
                                        runCatching {
                                            val uri = FileProvider.getUriForFile(
                                                context,
                                                "${context.packageName}.fileprovider",
                                                file
                                            )
                                            val share = Intent(Intent.ACTION_SEND).apply {
                                                type = "application/pdf"
                                                putExtra(Intent.EXTRA_STREAM, uri)
                                                putExtra(
                                                    Intent.EXTRA_SUBJECT,
                                                    "WealthBoard performance report — ${r.period.label}"
                                                )
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }
                                            context.startActivity(
                                                Intent.createChooser(share, "Share report")
                                            )
                                        }.onFailure {
                                            exportError = "Could not open the share sheet."
                                        }
                                    }
                                }
                            },
                            enabled = !exporting,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondary,
                                contentColor = MaterialTheme.colorScheme.onSecondary
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                        ) {
                            if (exporting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onSecondary
                                )
                                Spacer(Modifier.width(10.dp))
                                Text("Preparing PDF…")
                            } else {
                                Icon(Icons.Filled.PictureAsPdf, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Export as PDF")
                            }
                        }
                    }

                    item {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            // Gated with the PDF: both are "take the report
                            // away with you", and gating one while leaving the
                            // other open would make the paywall a formality.
                            onClick = {
                                if (!isPremium) {
                                    onOpenPremium()
                                    return@OutlinedButton
                                }
                                exportingCsv = true
                                exportError = null
                                viewModel.exportReportCsv(context) { file ->
                                    exportingCsv = false
                                    if (file == null) {
                                        exportError = "Could not create the spreadsheet."
                                    } else {
                                        runCatching {
                                            val uri = FileProvider.getUriForFile(
                                                context,
                                                "${context.packageName}.fileprovider",
                                                file
                                            )
                                            val share = Intent(Intent.ACTION_SEND).apply {
                                                type = "text/csv"
                                                putExtra(Intent.EXTRA_STREAM, uri)
                                                putExtra(
                                                    Intent.EXTRA_SUBJECT,
                                                    "WealthBoard performance report — ${r.period.label}"
                                                )
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }
                                            context.startActivity(
                                                Intent.createChooser(share, "Share spreadsheet")
                                            )
                                        }.onFailure {
                                            exportError = "Could not open the share sheet."
                                        }
                                    }
                                }
                            },
                            enabled = !exportingCsv,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                        ) {
                            if (exportingCsv) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(Modifier.width(10.dp))
                                Text("Preparing spreadsheet…")
                            } else {
                                Icon(
                                    Icons.Filled.TableChart,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("Export as CSV")
                            }
                        }
                    }

                    exportError?.let { msg ->
                        item { Text(msg, color = LossRed, fontSize = 12.sp) }
                    }

                    item {
                        Text(
                            "Informational only, not investment advice. Figures are built from data you entered and may differ from your official statements.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }

}

// ── Result cards ─────────────────────────────────────────────────────────────

@Composable
private fun ReportSummaryCard(r: PerformanceReport) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "${r.period.label} · ${dateStr(r.periodStartMillis)} — ${dateStr(r.periodEndMillis)}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth()) {
                MetricTile("Value", money(r.returns.endValue, r.currency), Modifier.weight(1f))
                MetricTile(
                    "Total gain",
                    signedMoney(r.returns.totalGain, r.currency),
                    Modifier.weight(1f),
                    tint = gainColor(r.returns.totalGain)
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth()) {
                MetricTile(
                    "Time-weighted",
                    pct(r.returns.timeWeightedReturn),
                    Modifier.weight(1f),
                    tint = gainColor(r.returns.timeWeightedReturn)
                )
                MetricTile(
                    "Money-weighted",
                    r.returns.moneyWeightedReturn?.let { pct(it) } ?: "—",
                    Modifier.weight(1f),
                    tint = gainColor(r.returns.moneyWeightedReturn ?: 0.0)
                )
            }
        }
    }
}

@Composable
private fun ReturnsCard(r: PerformanceReport) {
    CardBlock("Returns") {
        Metric("Time-weighted return", pct(r.returns.timeWeightedReturn), gainColor(r.returns.timeWeightedReturn))
        Metric("Money-weighted (XIRR)", r.returns.moneyWeightedReturn?.let { pct(it) } ?: "—",
            gainColor(r.returns.moneyWeightedReturn ?: 0.0))
        r.returns.annualizedReturn?.let {
            Metric("Annualized", pct(it), gainColor(it))
        }
        Metric("Opening value", money(r.returns.startValue, r.currency))
        Metric("Net contributions", signedMoney(r.returns.netContributions, r.currency))
        Metric("From price change", signedMoney(r.returns.unrealizedGain, r.currency), gainColor(r.returns.unrealizedGain))
        Metric("From sales", signedMoney(r.returns.realizedGain, r.currency), gainColor(r.returns.realizedGain))
        Metric("From dividends", signedMoney(r.returns.dividendIncome, r.currency), gainColor(r.returns.dividendIncome))
    }
}

@Composable
private fun BenchmarkCard(r: PerformanceReport) {
    val b = r.benchmark ?: return
    CardBlock("vs ${b.benchmark.displayName}") {
        Metric("Your portfolio", pct(b.portfolioReturn), gainColor(b.portfolioReturn))
        Metric(b.benchmark.ticker, pct(b.benchmarkReturn), gainColor(b.benchmarkReturn))
        Metric("Excess return", pct(b.excessReturn), gainColor(b.excessReturn))
        b.alpha?.let { Metric("Alpha (annualized)", pct(it), gainColor(it)) }
        b.beta?.let { Metric("Beta", fmt2(it)) }
    }
}

@Composable
private fun RiskCard(r: PerformanceReport) {
    CardBlock("Risk") {
        r.risk.annualizedVolatility?.let { Metric("Volatility (annualized)", pct(it)) }
        r.risk.sharpeRatio?.let { Metric("Sharpe ratio", fmt2(it), if (it >= 1.0) GainGreen else null) }
        r.risk.maxDrawdown?.let { Metric("Max drawdown", pct(it), LossRed) }
        if (r.risk.totalMonths > 0) {
            Metric("Positive months", "${r.risk.positiveMonths} of ${r.risk.totalMonths}")
        }
        r.risk.bestMonth?.let { Metric("Best month", "${monthStr(it.first)}  ${pct(it.second)}", GainGreen) }
        r.risk.worstMonth?.let { Metric("Worst month", "${monthStr(it.first)}  ${pct(it.second)}", LossRed) }
    }
}

@Composable
private fun TopHoldingsCard(r: PerformanceReport) {
    CardBlock("Contribution to gain") {
        r.holdings.take(6).forEach { h ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(h.ticker ?: h.name, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Text(
                        "${pct(h.weight)} of portfolio",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    signedMoney(h.totalGain, h.currency),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = gainColor(h.totalGain)
                )
            }
        }
        if (r.holdings.size > 6) {
            Text(
                "The full table of ${r.holdings.size} positions is in the PDF.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

// ── Small building blocks ────────────────────────────────────────────────────

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 6.dp)
    )
}

@Composable
private fun CardBlock(title: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun Metric(label: String, value: String, tint: Color? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = tint ?: MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun MetricTile(label: String, value: String, modifier: Modifier = Modifier, tint: Color? = null) {
    Column(modifier) {
        Text(
            label.uppercase(),
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(2.dp))
        Text(
            value,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = tint ?: MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

// ── Formatting ───────────────────────────────────────────────────────────────

// Through the shared formatter, like every other screen. This one printed
// "CAD 1,234.56" — the ISO code and a space — where the rest of the app prints
// a symbol, so the same figure looked different depending on where you read it.
private fun money(v: Double, cur: String) = Money.format(v, cur)

private fun signedMoney(v: Double, cur: String) = Money.signed(v, cur)

private fun pct(v: Double) = Money.signedPercent(v * 100)

private fun fmt2(v: Double) = String.format(Locale.getDefault(), "%.2f", v)

private fun gainColor(v: Double): Color = when {
    v > 0 -> GainGreen
    v < 0 -> LossRed
    else -> Color.Unspecified
}

private fun dateStr(ms: Long) =
    SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(ms))

private fun monthStr(ms: Long) =
    SimpleDateFormat("MMM yyyy", Locale.getDefault()).format(Date(ms))
