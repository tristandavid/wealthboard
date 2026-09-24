package ca.tristan.portfolio.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ca.tristan.portfolio.ui.format.Money
import java.util.Calendar
import java.util.Locale

/** How dividend history is bucketed along the x-axis. */
enum class DividendPeriod(val label: String) {
    /** Summed per calendar month. */
    MONTH("MONTH"),
    /** Summed per calendar year, including forward projections. */
    YEAR("YEAR")
}

/**
 * One bar. [received] is money actually banked, [estimated] is a projection,
 * and [drip] is the extra a projection gains from reinvesting — they stack in
 * that order, so a bar is never double-counted.
 */
data class DividendBar(
    val label: String,
    val received: Double = 0.0,
    val estimated: Double = 0.0,
    val drip: Double = 0.0
) {
    val total: Double get() = received + estimated + drip
}

/** Series colours, kept here so the chart and its legend can't drift apart. */
object DividendChartColors {
    val Received = Color(0xFF0F8B84)   // teal — money actually received
    val Estimated = Color(0xFFD8DBDD)  // pale grey — forecast
    val Drip = Color(0xFF2E8FE0)       // blue — extra from reinvestment
}

/**
 * Dividend income over time, as a grouped bar chart with a DAY / MONTH / YEAR
 * selector.
 *
 * Bars stack received income under forecast income so past and future read on
 * one continuous axis: the teal portion is what actually landed, the pale
 * portion is projected, and the blue cap is the additional income a DRIP would
 * generate. Values are printed above each bar because the exact figure matters
 * more here than the shape does — this is a planning tool, not a trend line.
 *
 * The plot scrolls horizontally when there are more bars than fit, so a
 * ten-year projection stays readable on a phone.
 */
@Composable
fun DividendBarChart(
    bars: List<DividendBar>,
    period: DividendPeriod,
    onPeriodChange: (DividendPeriod) -> Unit,
    modifier: Modifier = Modifier,
    currencyCode: String = "CAD",
    title: String? = null,
    showDrip: Boolean = true,
    chartHeight: androidx.compose.ui.unit.Dp = 210.dp
) {
    Column(modifier = modifier.fillMaxWidth()) {

        // ── Title + period selector ───────────────────────────────────────
        // The heading carries the currency, because the bar labels are
        // compacted ("1.88k") and have no room to repeat it on every bar.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (title != null) {
                Text(
                    "$title, ${Money.symbol(currencyCode)}",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            DividendPeriod.values().forEach { p ->
                val selected = p == period
                Box(
                    modifier = Modifier
                        .padding(start = 6.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (selected) DividendChartColors.Received
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                        .clickable { onPeriodChange(p) }
                        .padding(horizontal = 16.dp, vertical = 7.dp)
                ) {
                    Text(
                        p.label,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (selected) Color.White
                                else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        if (bars.isEmpty()) {
            Box(
                Modifier.fillMaxWidth().height(chartHeight),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "No dividend history yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return@Column
        }

        val density = LocalDensity.current
        val maxValue = bars.maxOf { it.total }.coerceAtLeast(0.01)
        val axisMax = niceCeiling(maxValue)
        // 0.12 was faint enough that the bars floated with no scale behind
        // them. Matched to StockAreaChart's 0.22 so every chart in the app
        // reads with the same weight of grid.
        val axisColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f)
        val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
        val valueColor = MaterialTheme.colorScheme.onSurface

        Row(Modifier.fillMaxWidth()) {

            // ── Y-axis labels ─────────────────────────────────────────────
            Column(
                modifier = Modifier.width(42.dp).height(chartHeight),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.End
            ) {
                // Rendered as a set so a small axis can't print "0.01" four
                // times over: at a max of a few cents the old formatter
                // rounded every gridline to the same string, which read as a
                // broken chart rather than a small one.
                val steps = (4 downTo 0).map { axisMax * it / 4.0 }
                val labels = axisLabels(steps)
                labels.forEach { label ->
                    Text(
                        label,
                        fontSize = 10.sp,
                        color = labelColor,
                        textAlign = TextAlign.End
                    )
                }
            }

            Spacer(Modifier.width(6.dp))

            // ── Bars ──────────────────────────────────────────────────────
            // Give every bar a fixed slot so labels stay legible, and let the
            // whole plot scroll when the series is longer than the screen.
            val slotWidth = 58.dp
            val scrollState = rememberScrollState()

            // Park the viewport on the first bar that actually has a value.
            // A 12-month window is usually mostly empty for a quarterly payer,
            // and opening on a run of zero bars looks like the chart is broken.
            val firstDataIndex = bars.indexOfFirst { it.total > 0 }
            LaunchedEffect(bars, period) {
                if (firstDataIndex > 0) {
                    // Leave one slot of context to the left of the first bar.
                    val target = ((firstDataIndex - 1) * slotWidthPx(slotWidth, density))
                        .coerceAtLeast(0)
                    scrollState.scrollTo(target.coerceAtMost(scrollState.maxValue))
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(scrollState)
            ) {
                Column {
                    Row(
                        modifier = Modifier.height(chartHeight),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        bars.forEach { bar ->
                            BarColumn(
                                bar = bar,
                                axisMax = axisMax,
                                gridColor = axisColor,
                                valueColor = valueColor,
                                showDrip = showDrip,
                                modifier = Modifier.width(slotWidth).height(chartHeight)
                            )
                        }
                    }
                    // X-axis rule + labels
                    Box(
                        Modifier
                            .width(slotWidth * bars.size)
                            .height(1.dp)
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f))
                    )
                    Row {
                        bars.forEach { bar ->
                            Text(
                                bar.label,
                                modifier = Modifier
                                    .width(slotWidth)
                                    .padding(top = 6.dp),
                                fontSize = 10.sp,
                                color = labelColor,
                                textAlign = TextAlign.Center,
                                maxLines = 2
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // ── Legend ────────────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val cur = Money.symbol(currencyCode).trim()
            if (showDrip) {
                LegendSwatch(DividendChartColors.Drip, "Est. w/DRIP, $cur")
                Spacer(Modifier.width(12.dp))
            }
            LegendSwatch(DividendChartColors.Received, "Received, $cur")
            Spacer(Modifier.width(12.dp))
            LegendSwatch(DividendChartColors.Estimated, "Estimated, $cur")
        }
    }
}

/** One bar: stacked segments with the total printed just above the bar top. */
@Composable
private fun BarColumn(
    bar: DividendBar,
    axisMax: Double,
    gridColor: Color,
    valueColor: Color,
    showDrip: Boolean,
    modifier: Modifier = Modifier
) {
    val measurer = rememberTextMeasurer()
    val valueStyle = TextStyle(
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        color = valueColor
    )

    Canvas(modifier = modifier) {
        // Reserved for the value text — at the TOP, above the tallest bar,
        // which is where that text is drawn. Taking it off the bottom instead
        // (the old `plotH = height - labelRoom`, with bars standing on plotH)
        // left dead space under the axis and none over it, so a bar at the
        // axis maximum ran to the very top of the canvas and its own label
        // was clamped down on top of it.
        val labelRoom = 16.dp.toPx()
        val baseline  = size.height
        val plotH     = (size.height - labelRoom).coerceAtLeast(1f)

        // Gridlines, matching the y-axis label steps
        for (s in 0..4) {
            val gy = baseline - plotH * s / 4f
            drawLine(gridColor, Offset(0f, gy), Offset(size.width, gy), strokeWidth = 1f)
        }

        val barW = size.width * 0.52f
        val left = (size.width - barW) / 2f
        fun px(v: Double) = ((v / axisMax) * plotH).toFloat()

        // Segments stack upward from the axis: received, then estimated, then DRIP.
        var yCursor = baseline
        fun segment(value: Double, color: Color) {
            if (value <= 0.0) return
            val bh = px(value)
            drawRect(color, topLeft = Offset(left, yCursor - bh), size = Size(barW, bh))
            yCursor -= bh
        }
        segment(bar.received, DividendChartColors.Received)
        segment(bar.estimated, DividendChartColors.Estimated)
        if (showDrip) segment(bar.drip, DividendChartColors.Drip)

        // Total, centred just above whatever the bar reached.
        val text = compactMoney(bar.total)
        val measured = measurer.measure(text, valueStyle)
        val topY = (yCursor - measured.size.height - 2.dp.toPx())
            .coerceAtLeast(0f)
        drawText(
            textMeasurer = measurer,
            text = text,
            topLeft = Offset((size.width - measured.size.width) / 2f, topY),
            style = valueStyle
        )
    }
}

@Composable
private fun LegendSwatch(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Spacer(Modifier.width(5.dp))
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Slot width in pixels, for positioning the horizontal scroll viewport. */
private fun slotWidthPx(slot: androidx.compose.ui.unit.Dp, density: androidx.compose.ui.unit.Density): Int =
    with(density) { slot.roundToPx() }

/** Rounds an axis maximum up to a readable step (1/2/5 × 10^n). */
private fun niceCeiling(v: Double): Double {
    if (v <= 0) return 1.0
    val mag = Math.pow(10.0, Math.floor(Math.log10(v)))
    val n = v / mag
    val step = when {
        n <= 1.0 -> 1.0
        n <= 2.0 -> 2.0
        n <= 5.0 -> 5.0
        else     -> 10.0
    }
    return step * mag
}

/** "1.88k" / "535" / "54.5" — short enough to sit above a narrow bar. */
internal fun compactMoney(v: Double): String = Money.compact(v)

/**
 * Labels for a set of axis gridlines, widened until they are distinct.
 *
 * [Money.compact] is tuned for bar labels, where two significant figures is
 * plenty. An axis is different: it has to show five *different* numbers, and on
 * a per-unit scale of a few cents the compact form collapsed all of them to
 * "0.01". This walks the precision up until the labels separate.
 */
private fun axisLabels(values: List<Double>): List<String> {
    val nonZero = values.filter { it > 0.0 }
    if (nonZero.isEmpty()) return values.map { "0" }
    for (digits in 0..5) {
        val out = values.map { v ->
            if (v <= 0.0) "0" else String.format(Locale.US, "%,.${digits}f", v)
        }
        if (out.distinct().size == out.size) return out
    }
    return values.map { Money.compact(it) }
}

/**
 * Buckets payments into [DividendBar]s for the requested [period].
 *
 * @param received   (timestamp, amount) pairs for money already banked
 * @param projected  (timestamp, amount) pairs for forecast payments
 * @param dripRate   the holding's dividend YIELD as a decimal. Reinvesting a
 *                   payment buys more units, so the unit count compounds at
 *                   roughly the yield each year — that, not dividend growth,
 *                   is what a DRIP adds. 0.0 disables the DRIP series.
 * @param growthRate annual growth in the distribution per unit, as a decimal.
 *                   Applied to the baseline projection, because a fund raising
 *                   its distribution pays more whether or not you reinvest.
 */
fun buildDividendBars(
    received: List<Pair<Long, Double>>,
    projected: List<Pair<Long, Double>>,
    period: DividendPeriod,
    dripRate: Double = 0.0,
    growthRate: Double = 0.0,
    yearsAhead: Int = 10
): List<DividendBar> {
    val cal = Calendar.getInstance()
    fun field(ts: Long, f: Int): Int { cal.timeInMillis = ts; return cal.get(f) }
    fun monthLabel(ts: Long): String {
        cal.timeInMillis = ts
        val m = java.text.SimpleDateFormat("MMM ''yy", Locale.getDefault())
        return m.format(cal.time)
    }
    return when (period) {
        DividendPeriod.MONTH -> {
            // Six months back, this month, and six months forward.
            //
            // The window used to be the twelve months ENDING today, which made
            // this chart structurally incapable of showing an upcoming payment
            // — the section is titled "Recent and Upcoming Dividends", and for
            // a holding with nothing logged yet every single bar was zero while
            // the payout schedule directly below it listed a payment due in
            // November. Centring the window on today shows both halves.
            val now = System.currentTimeMillis()
            val start = Calendar.getInstance().apply {
                timeInMillis = now
                add(Calendar.MONTH, -6)
                set(Calendar.DAY_OF_MONTH, 1)
            }
            (0 until 13).map { i ->
                val bucket = (start.clone() as Calendar).apply { add(Calendar.MONTH, i) }
                val y = bucket.get(Calendar.YEAR); val m = bucket.get(Calendar.MONTH)
                fun sum(src: List<Pair<Long, Double>>) = src
                    .filter { field(it.first, Calendar.YEAR) == y && field(it.first, Calendar.MONTH) == m }
                    .sumOf { it.second }
                DividendBar(
                    label = monthLabel(bucket.timeInMillis),
                    received = sum(received),
                    estimated = sum(projected)
                )
            }
        }

        DividendPeriod.YEAR -> {
            val thisYear = field(System.currentTimeMillis(), Calendar.YEAR)
            val firstYear = (received.minOfOrNull { field(it.first, Calendar.YEAR) }
                ?: thisYear).coerceAtMost(thisYear)

            val projByYear = projected
                .groupBy { field(it.first, Calendar.YEAR) }
                .mapValues { (_, v) -> v.sumOf { it.second } }

            // Baseline for any year the projection doesn't reach.
            //
            // This used to be `projected.sumOf { ... }` — the total of every
            // projected payment, whatever span they covered. That was already
            // ~1.5x too high when the projection ran 18 months, and once the
            // horizon was extended it would have read a decade of payments as
            // a single year's income and drawn a chart an order of magnitude
            // wrong. The run rate is one YEAR of income, so take the first
            // complete projected year, or fall back to the trailing twelve
            // months actually received.
            val firstFullYear = projByYear.keys.filter { it > thisYear }.minOrNull()
            val runRate = firstFullYear?.let { projByYear[it] }?.takeIf { it > 0 }
                ?: received.filter { field(it.first, Calendar.YEAR) == thisYear }.sumOf { it.second }

            (firstYear..(thisYear + yearsAhead)).map { y ->
                val rec = received.filter { field(it.first, Calendar.YEAR) == y }.sumOf { it.second }
                val proj = projByYear[y] ?: 0.0
                when {
                    y < thisYear -> DividendBar(y.toString(), received = rec)
                    y == thisYear -> DividendBar(y.toString(), received = rec, estimated = proj)
                    else -> {
                        val n = (y - thisYear).toDouble()
                        // Prefer the projection's own figure for the year —
                        // it already carries the fund's seasonal shape and the
                        // growth assumption. Extrapolate only past its horizon.
                        val base = if (proj > 0.0) proj
                                   else runRate * Math.pow(1.0 + growthRate, n)
                        // With a DRIP the unit count compounds on top of that,
                        // at roughly the yield — every payment buys more units,
                        // and those units are paid the following period.
                        val withDrip = base * Math.pow(1.0 + dripRate, n)
                        DividendBar(
                            label = y.toString(),
                            estimated = base,
                            drip = (withDrip - base).coerceAtLeast(0.0)
                        )
                    }
                }
            }
        }
    }
}
