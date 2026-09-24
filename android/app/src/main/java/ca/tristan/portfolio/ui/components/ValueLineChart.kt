package ca.tristan.portfolio.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * Line chart for a value series (portfolio or single holding), e.g. the 1D
 * range on the home screen.
 *
 * v-scale fix: auto-scaling the y-axis to the day's tight observed min/max
 * with minimal padding visually blows a genuine ~0.1% wobble up to fill the
 * whole chart height, which looked "not accurate" even though the header's
 * real move (e.g. +0.09%) was correctly tiny. This gives the y-axis a floor
 * of 1% of the series' representative value, centered on the data's
 * midpoint, so small real moves no longer look exaggerated. Nothing is
 * hidden — the exact values are still what's plotted; only the chart's
 * vertical scale changes, and no stored data changes.
 */
@Composable
fun ValueLineChart(points: List<Double>, modifier: Modifier = Modifier) {
    val lineColor = MaterialTheme.colorScheme.primary
    Canvas(modifier = modifier
        .fillMaxWidth()
        .height(120.dp)) {
        if (points.size < 2) return@Canvas
        val observedMin = points.min()
        val observedMax = points.max()
        val midpoint = (observedMin + observedMax) / 2.0
        val representative = points.map { abs(it) }.maxOrNull() ?: 1.0
        val floorRange = representative * 0.01 // 1% floor
        val observedRange = observedMax - observedMin
        val halfRange = (maxOf(observedRange, floorRange)) / 2.0
        val yMin = midpoint - halfRange
        val yMax = midpoint + halfRange
        val yRange = (yMax - yMin).let { if (it == 0.0) 1.0 else it }

        val stepX = size.width / (points.size - 1)
        val path = androidx.compose.ui.graphics.Path()
        points.forEachIndexed { index, value ->
            val x = index * stepX
            val normalized = ((value - yMin) / yRange).toFloat()
            val y = size.height - (normalized * size.height)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path = path, color = lineColor, style = Stroke(width = 4f))
    }
}
