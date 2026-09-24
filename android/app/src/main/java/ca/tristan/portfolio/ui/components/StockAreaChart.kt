package ca.tristan.portfolio.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ca.tristan.portfolio.net.HistoryBar
import ca.tristan.portfolio.ui.theme.GainGreen
import ca.tristan.portfolio.ui.theme.LossRed
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Maps an x pixel inside the plot to the nearest bar index.
 *
 * [leftPad] and [rightPad] are the SAME values the drawing pass uses. They were
 * previously approximated here as 2% and 16% of the width, which never matched
 * the insets actually drawn, so the crosshair sat on the wrong bar — a small
 * error mid-plot and a visible one near either edge.
 */
private fun indexForX(x: Float, width: Float, count: Int, leftPad: Float, rightPad: Float): Int {
    if (count <= 1 || width <= 0f) return 0
    val plotW = (width - leftPad - rightPad).coerceAtLeast(1f)
    val frac = ((x - leftPad) / plotW).coerceIn(0f, 1f)
    return Math.round(frac * (count - 1)).coerceIn(0, count - 1)
}

/**
 * X-axis formatter for a range.
 *
 * [timeZoneId] is the exchange's IANA zone. Intraday labels have to be drawn
 * in it rather than the device's: a London session runs 08:00–16:30 local, and
 * formatting those bars in the viewer's own zone printed the FTSE as trading
 * 3:00 AM–11:30 AM for a reader in Toronto. Exchange-local is what every
 * finance app plots, and it is the clock the day high/low figures below the
 * chart already refer to. Ranges of a day or longer stay in device time, where
 * a calendar date is a calendar date and shifting it only invites off-by-one
 * dates at the boundaries.
 */
private fun xLabelFormat(rangeLabel: String, timeZoneId: String?): SimpleDateFormat =
    when (rangeLabel) {
        "1D"  -> SimpleDateFormat("h:mm a", Locale.US).withZone(timeZoneId)
        "1W"  -> SimpleDateFormat("EEE", Locale.US).withZone(timeZoneId)
        "1M"  -> SimpleDateFormat("MMM d", Locale.US)
        "6M"  -> SimpleDateFormat("MMM", Locale.US)
        "YTD" -> SimpleDateFormat("MMM", Locale.US)
        "1Y"  -> SimpleDateFormat("MMM", Locale.US)
        else  -> SimpleDateFormat("MMM yy", Locale.US)
    }

private fun SimpleDateFormat.withZone(id: String?): SimpleDateFormat = apply {
    // An unrecognised id silently resolves to GMT, which is a worse answer than
    // the device zone, so only adopt one the platform actually knows.
    if (!id.isNullOrBlank() && TimeZone.getAvailableIDs().contains(id)) {
        timeZone = TimeZone.getTimeZone(id)
    }
}

/**
 * Short name for the zone the intraday axis is drawn in ("BST", "JST"), or
 * null when the exchange keeps the same clock as the device.
 *
 * Shown beside the chart so a reader is never left guessing whose times these
 * are once the axis stops matching their own watch.
 */
fun exchangeZoneLabel(timeZoneId: String?, atMillis: Long): String? {
    if (timeZoneId.isNullOrBlank()) return null
    if (!TimeZone.getAvailableIDs().contains(timeZoneId)) return null
    val exchange = TimeZone.getTimeZone(timeZoneId)
    if (exchange.getOffset(atMillis) == TimeZone.getDefault().getOffset(atMillis)) return null
    return exchange.getDisplayName(
        exchange.inDaylightTime(java.util.Date(atMillis)),
        TimeZone.SHORT,
        Locale.US
    )
}

/**
 * Apple-Stocks-inspired area chart — filled gradient under the price line,
 * y-axis price labels on the right, x-axis time labels at the bottom, and
 * translucent volume bars along the lower edge.
 *
 * [positive] controls the line/fill colour: green for a gain over the range,
 * red for a loss.
 */
@Composable
fun StockAreaChart(
    bars: List<HistoryBar>,
    positive: Boolean,
    rangeLabel: String,
    /** Exchange IANA zone, so intraday times read in the market's own clock. */
    exchangeTimeZoneId: String? = null,
    /**
     * A horizontal reference the series is read against — yesterday's close on
     * an intraday chart, the period's opening close on a longer one.
     *
     * The dashed rule every finance app draws. Without it "up today" is a
     * judgement about where the line started, which on an intraday chart is the
     * open, not the close the move is actually quoted from; the two differ by
     * the overnight gap. The price range is widened to keep the rule on screen,
     * because a reference line scrolled out of the plot silently stops being a
     * reference.
     */
    baseline: Double? = null,
    showGrid: Boolean = true,
    /** Called with the bar under the finger while scrubbing, null on release. */
    onScrub: ((HistoryBar?) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    if (bars.size < 2) return

    // Index under the finger while the user drags across the plot; null when
    // not touching. Drives the crosshair and the price readout in the header.
    var scrubIndex by remember(bars) { mutableStateOf<Int?>(null) }

    val textMeasurer   = rememberTextMeasurer()
    val lineColor      = if (positive) GainGreen else LossRed
    val labelColor     = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
    // 0.08 alpha was effectively invisible against the dark surface — the
    // gridlines were there but did no work, so the plot read as a line floating
    // in empty space with no reference to measure it against. 0.22 is still
    // clearly subordinate to the price line (0.55+) and to the axis labels
    // (0.45), so it reads as structure rather than as competing data.
    val gridColor      = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f)
    // Hoisted out here with the others: a Canvas block is a DrawScope, not a
    // composable one, and MaterialTheme cannot be read from inside it.
    val baselineColor  = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
    val baselineDash   = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
        floatArrayOf(10f, 8f), 0f
    )
    val dateFormat     = remember(rangeLabel, exchangeTimeZoneId) {
        xLabelFormat(rangeLabel, exchangeTimeZoneId)
    }
    val labelStyle     = TextStyle(fontSize = 9.sp, color = labelColor)

    // ── Plot geometry, computed once ──────────────────────────────────────
    // Shared by the drawing pass and by scrub hit-testing. They used to be
    // worked out separately — the draw used fixed dp insets while indexForX
    // guessed at 2% and 16% of the width — so the crosshair landed on the
    // wrong bar, increasingly so towards the edges. One source of truth fixes
    // that and lets the gutter size itself to the labels.
    val prices     = bars.map { it.close }
    // The baseline is part of the extent, not an overlay on top of it. A
    // previous close outside the session's own high and low — a gap up or down,
    // which is precisely when the reference matters most — would otherwise be
    // drawn off the plot.
    val priceMin   = minOf(prices.min(), baseline ?: Double.MAX_VALUE)
    val priceMax   = maxOf(prices.max(), baseline ?: -Double.MAX_VALUE)
    val priceRange = (priceMax - priceMin).let { if (it < 0.001) 1.0 else it }
    val ySteps     = 4

    // Cents are signal on a $61 share and noise on an $89,000 portfolio, where
    // they only widen the label enough to crowd the plot.
    val yLabelText: (Double) -> String = { p ->
        if (priceMax >= 1000) String.format(Locale.US, "%,.0f", p)
        else String.format(Locale.US, "%.2f", p)
    }

    val density = LocalDensity.current
    val widestYLabel = (0..ySteps).maxOf { step ->
        val p = priceMin + (step.toFloat() / ySteps) * priceRange
        textMeasurer.measure(yLabelText(p), labelStyle).size.width.toFloat()
    }
    // The gutter is measured, not guessed. A fixed 56dp was sized for the
    // widest thing it might ever hold, so a chart labelled "91,559" left a
    // strip of dead card between the plot and the numbers.
    val rightPadPx = with(density) {
        (widestYLabel + 10.dp.toPx()).coerceIn(24.dp.toPx(), 72.dp.toPx())
    }
    // Left inset only as wide as the stroke and the opening label need, so the
    // plot starts close to the card edge.
    val leftPadPx = with(density) { 6.dp.toPx() }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(240.dp)
            .pointerInput(bars) {
                // Press-and-drag scrubbing, the way Apple Stocks behaves:
                // touch anywhere on the plot to read the price at that moment.
                detectDragGesturesAfterLongPress(
                    onDragStart = { offset ->
                        scrubIndex = indexForX(offset.x, size.width.toFloat(), bars.size, leftPadPx, rightPadPx)
                            .also { i -> onScrub?.invoke(bars.getOrNull(i)) }
                    },
                    onDrag = { change, _ ->
                        scrubIndex = indexForX(change.position.x, size.width.toFloat(), bars.size, leftPadPx, rightPadPx)
                            .also { i -> onScrub?.invoke(bars.getOrNull(i)) }
                    },
                    onDragEnd = { scrubIndex = null; onScrub?.invoke(null) },
                    onDragCancel = { scrubIndex = null; onScrub?.invoke(null) }
                )
            }
            .pointerInput(bars) {
                // A simple tap also parks the crosshair, so a quick check
                // doesn't require holding down.
                detectTapGestures(
                    onPress = { offset ->
                        scrubIndex = indexForX(offset.x, size.width.toFloat(), bars.size, leftPadPx, rightPadPx)
                            .also { i -> onScrub?.invoke(bars.getOrNull(i)) }
                        tryAwaitRelease()
                        scrubIndex = null
                        onScrub?.invoke(null)
                    }
                )
            }
    ) {

        // ── Layout ────────────────────────────────────────────────────────
        // Insets come from the geometry computed above the Canvas, so the
        // drawing and the scrub hit-test agree on where the plot actually is.
        val rightPad    = rightPadPx
        val leftPad     = leftPadPx
        val topPad      = 10.dp.toPx()
        val xLabelH     = 18.dp.toPx()
        // The volume band is reserved only when there is volume to draw. The
        // portfolio chart has none, and holding the space open left an empty
        // strip under the dates that read as a broken layout.
        //
        // Zero counts as none. Yahoo is inconsistent about index volume: the
        // US and Canadian benchmarks (^GSPC, ^IXIC, ^DJI, ^GSPTSE) carry real
        // turnover, while ^HSI and ^AXJO come back with nothing — sometimes as
        // nulls, sometimes as an array of zeros. A null-only test reserves the
        // band for the zeros case and then draws bars of no height into it,
        // which looks like a rendering fault rather than absent data.
        val hasVolume   = bars.any { (it.volume ?: 0L) > 0L }
        val volH        = if (hasVolume) size.height * 0.15f else 0f
        val priceH      = size.height - topPad - xLabelH - volH - 6.dp.toPx()
        val chartW      = size.width - rightPad - leftPad

        fun priceToY(p: Double) =
            (topPad + priceH - ((p - priceMin) / priceRange * priceH)).toFloat()

        fun idxToX(i: Int) =
            leftPad + (i.toFloat() / (bars.size - 1)) * chartW

        // ── Build price path ──────────────────────────────────────────────
        val linePath = Path()
        val fillPath = Path()
        bars.forEachIndexed { i, bar ->
            val x = idxToX(i)
            val y = priceToY(bar.close)
            if (i == 0) {
                linePath.moveTo(x, y)
                fillPath.moveTo(x, topPad + priceH)
                fillPath.lineTo(x, y)
            } else {
                linePath.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }
        fillPath.lineTo(idxToX(bars.lastIndex), topPad + priceH)
        fillPath.close()

        // Gradient fill
        drawPath(
            path  = fillPath,
            brush = Brush.verticalGradient(
                colors   = listOf(lineColor.copy(alpha = 0.32f), lineColor.copy(alpha = 0.0f)),
                startY   = topPad,
                endY     = topPad + priceH
            )
        )

        // Reference line, under the price line rather than over it: it is the
        // thing being measured against, not a second series.
        if (baseline != null) {
            val baselineY = priceToY(baseline)
            drawLine(
                color = baselineColor,
                start = Offset(leftPad, baselineY),
                end   = Offset(leftPad + chartW, baselineY),
                strokeWidth = 1.2f,
                pathEffect = baselineDash
            )
        }

        // Price line
        drawPath(
            path  = linePath,
            color = lineColor,
            style = Stroke(width = 2.4f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        // ── Y-axis grid + labels (right) ──────────────────────────────────
        for (step in 0..ySteps) {
            val frac  = step.toFloat() / ySteps
            val price = priceMin + frac * priceRange
            val y     = priceToY(price)

            // Horizontal grid line
            if (showGrid) {
                drawLine(
                    color       = gridColor,
                    start       = Offset(leftPad, y),
                    end         = Offset(leftPad + chartW, y),
                    strokeWidth = 0.7f
                )
            }

            val label   = yLabelText(price)
            val measured = textMeasurer.measure(label, labelStyle)
            drawText(
                textMeasurer = textMeasurer,
                text         = label,
                topLeft      = Offset(
                    leftPad + chartW + 4.dp.toPx(),
                    y - measured.size.height / 2f
                ),
                style = labelStyle
            )
        }

        // ── Vertical grid lines, aligned to the x-axis labels ─────────────
        if (showGrid) {
            val vSteps = 4
            for (step in 0..vSteps) {
                val x = leftPad + (step.toFloat() / vSteps) * chartW
                drawLine(
                    color       = gridColor,
                    start       = Offset(x, topPad),
                    end         = Offset(x, topPad + priceH),
                    strokeWidth = 0.7f
                )
            }
        }

        // ── X-axis labels (bottom of price area) ─────────────────────────
        //
        // Positions are measured and collision-checked rather than trusted.
        // Five evenly-spaced labels fit a wide chart, but on a narrow one a
        // long format like "10:00 AM" is wider than the gap between steps, and
        // drawing them blind printed the first two on top of each other
        // ("9:3010:00 AM"). The first and last are always kept — they carry the
        // range — and the ones between are dropped whenever they would touch.
        val xSteps    = 4
        val xLabelY   = topPad + priceH + 3.dp.toPx()
        val minGap    = 8.dp.toPx()

        val xLabels = (0..xSteps).map { step ->
            val barIdx = (step.toFloat() / xSteps * (bars.size - 1)).toInt()
                .coerceIn(0, bars.lastIndex)
            val text = dateFormat.format(Date(bars[barIdx].timestampMs))
            val w = textMeasurer.measure(text, labelStyle).size.width.toFloat()
            val x = (idxToX(barIdx) - w / 2f)
                .coerceIn(leftPad, (leftPad + chartW - w).coerceAtLeast(leftPad))
            Triple(text, x, w)
        }

        val keptLabels = mutableListOf<Triple<String, Float, Float>>()
        xLabels.forEachIndexed { i, lab ->
            when {
                i == 0 -> keptLabels += lab
                i == xLabels.lastIndex -> {
                    // The closing label wins any argument: drop whatever it
                    // would have collided with, then place it.
                    while (keptLabels.size > 1 &&
                        keptLabels.last().let { it.second + it.third + minGap } > lab.second
                    ) keptLabels.removeAt(keptLabels.lastIndex)
                    if (keptLabels.size == 1 &&
                        keptLabels[0].let { it.second + it.third + minGap } > lab.second
                    ) {
                        // Only room for one: keep the range's end.
                        keptLabels.clear()
                    }
                    keptLabels += lab
                }
                else -> {
                    val prev = keptLabels.last()
                    if (lab.second >= prev.second + prev.third + minGap) keptLabels += lab
                }
            }
        }

        keptLabels.forEach { (text, x, _) ->
            drawText(
                textMeasurer = textMeasurer,
                text         = text,
                topLeft      = Offset(x, xLabelY),
                style        = labelStyle
            )
        }

        // ── Volume bars ───────────────────────────────────────────────────
        val maxVol   = bars.mapNotNull { it.volume }.maxOrNull() ?: 1L
        val volBaseY = size.height - 2.dp.toPx()
        val barW     = (chartW / bars.size * 0.7f).coerceAtLeast(1.5f).coerceAtMost(leftPad * 2f)

        bars.forEachIndexed { i, bar ->
            val vol = bar.volume ?: return@forEachIndexed
            val x   = idxToX(i)
            val barH = (vol.toFloat() / maxVol * (volH - 2.dp.toPx())).coerceAtLeast(1f)
            drawRect(
                color   = lineColor.copy(alpha = 0.35f),
                topLeft = Offset(x - barW / 2, volBaseY - barH),
                size    = Size(barW, barH)
            )
        }

        // ── Scrub crosshair ───────────────────────────────────────────────
        // A vertical rule through the touched point, a dot on the line, and
        // the price printed at the top — the readout Apple Stocks shows while
        // you drag along the chart.
        val si = scrubIndex
        if (si != null && si in bars.indices) {
            val bar = bars[si]
            val cx = idxToX(si)
            val cy = priceToY(bar.close)

            drawLine(
                color = lineColor.copy(alpha = 0.55f),
                start = Offset(cx, topPad),
                end   = Offset(cx, topPad + priceH),
                strokeWidth = 1.2f
            )
            drawCircle(color = lineColor, radius = 5.dp.toPx(), center = Offset(cx, cy))
            drawCircle(
                color = androidx.compose.ui.graphics.Color.White,
                radius = 2.2.dp.toPx(),
                center = Offset(cx, cy)
            )

            // Price label, clamped so it never runs off either edge.
            val priceText = String.format("%.2f", bar.close)
            val priceStyle = TextStyle(
                fontSize = 12.sp,
                color = lineColor,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
            )
            val measured = textMeasurer.measure(priceText, priceStyle)
            val labelX = (cx - measured.size.width / 2f)
                .coerceIn(0f, (chartW - measured.size.width).coerceAtLeast(0f))
            drawText(
                textMeasurer = textMeasurer,
                text = priceText,
                topLeft = Offset(labelX, 0f),
                style = priceStyle
            )

            // Timestamp under the plot, in the x-label band.
            val timeText = dateFormat.format(Date(bar.timestampMs))
            val tMeasured = textMeasurer.measure(timeText, labelStyle)
            val tX = (cx - tMeasured.size.width / 2f)
                .coerceIn(leftPad, (leftPad + chartW - tMeasured.size.width).coerceAtLeast(leftPad))
            drawText(
                textMeasurer = textMeasurer,
                text = timeText,
                topLeft = Offset(tX, topPad + priceH + 3.dp.toPx()),
                style = labelStyle
            )
        }
    }
}
