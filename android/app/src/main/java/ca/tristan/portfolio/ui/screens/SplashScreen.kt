package ca.tristan.portfolio.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Brand colours, matched to colors.xml / the launcher icon rather than
// re-reading resources, since this screen is drawn before the rest of the
// app's theme has anything to hand it.
private val BrandNavy = Color(0xFF0F2A43)
private val BrandNavyDark = Color(0xFF0A1D2F)
private val BrandGoldLight = Color(0xFFE4C766)
private val BrandIvory = Color(0xFFF4F1E8)

/**
 * Launch splash: the three-bar "rising trend" mark animates in — bars grow
 * up from the baseline, the trend line draws itself across them, the
 * arrowhead pops in — then the wordmark fades up underneath. Same 108x108
 * coordinate space as ic_launcher_foreground.xml, so this is the same mark
 * as the launcher icon, just animated instead of static.
 *
 * Shown once, briefly, right after the OS splash (installSplashScreen() in
 * MainActivity) hands off — see the showSplash gate in MainActivity's
 * setContent. [onFinished] is called after the sequence completes so the
 * caller can swap it out for the real app content.
 */
@Composable
fun SplashScreen(onFinished: () -> Unit) {
    val bar1 = remember { Animatable(0f) }
    val bar2 = remember { Animatable(0f) }
    val bar3 = remember { Animatable(0f) }
    val lineProgress = remember { Animatable(0f) }
    val arrowAlpha = remember { Animatable(0f) }
    val textAlpha = remember { Animatable(0f) }
    val textOffset = remember { Animatable(18f) }

    LaunchedEffect(Unit) {
        launch {
            bar1.animateTo(
                1f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
            )
        }
        delay(90)
        launch {
            bar2.animateTo(
                1f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
            )
        }
        delay(90)
        launch {
            bar3.animateTo(
                1f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
            )
        }

        delay(260)
        lineProgress.animateTo(1f, animationSpec = tween(450, easing = LinearOutSlowInEasing))
        arrowAlpha.animateTo(1f, animationSpec = tween(200))

        delay(120)
        launch { textAlpha.animateTo(1f, animationSpec = tween(400)) }
        textOffset.animateTo(0f, animationSpec = tween(400))

        delay(700)
        onFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(BrandNavy, BrandNavyDark))),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BrandMark(
                bar1 = bar1.value,
                bar2 = bar2.value,
                bar3 = bar3.value,
                lineProgress = lineProgress.value,
                arrowAlpha = arrowAlpha.value,
                modifier = Modifier.size(132.dp)
            )
            Spacer(Modifier.height(22.dp))
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.graphicsLayer {
                    alpha = textAlpha.value
                    translationY = textOffset.value
                }
            ) {
                Text(
                    text = "WealthBoard",
                    color = BrandIvory,
                    fontSize = 27.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Dividend Tracker",
                    color = BrandGoldLight,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

/**
 * The three ascending bars + rising trend line + arrowhead, drawn directly
 * (not via the vector drawable) so each piece can be animated independently.
 * Coordinates are lifted 1:1 from ic_launcher_foreground.xml's 108x108
 * viewport.
 */
@Composable
private fun BrandMark(
    bar1: Float,
    bar2: Float,
    bar3: Float,
    lineProgress: Float,
    arrowAlpha: Float,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val scale = size.width / 108f

        fun p(x: Float, y: Float) = Offset(x * scale, y * scale)

        // Bar 1 (shortest, gold): x 34.22..45.68, bottom 77.71, top 64.61
        drawBar(x0 = 34.22f, x1 = 45.68f, bottom = 77.71f, top = 64.61f, progress = bar1, color = BrandGoldLight, scale = scale)
        // Bar 2 (ivory): x 48.14..59.61, bottom 77.71, top 53.14
        drawBar(x0 = 48.14f, x1 = 59.61f, bottom = 77.71f, top = 53.14f, progress = bar2, color = BrandIvory, scale = scale)
        // Bar 3 (tallest, gold): x 62.06..73.53, bottom 77.71, top 40.04
        drawBar(x0 = 62.06f, x1 = 73.53f, bottom = 77.71f, top = 40.04f, progress = bar3, color = BrandGoldLight, scale = scale)

        // Rising trend line, drawn progressively across its four points.
        val points = listOf(
            p(31.76f, 49.86f),
            p(49.78f, 38.4f),
            p(59.61f, 44.95f),
            p(75.99f, 30.21f)
        )
        if (lineProgress > 0f) {
            val path = androidx.compose.ui.graphics.Path()
            path.moveTo(points[0].x, points[0].y)
            for (point in points.drop(1)) path.lineTo(point.x, point.y)

            val measure = androidx.compose.ui.graphics.PathMeasure()
            measure.setPath(path, false)
            val trimmed = androidx.compose.ui.graphics.Path()
            measure.getSegment(0f, measure.length * lineProgress, trimmed, true)

            drawPath(
                path = trimmed,
                color = BrandIvory,
                style = Stroke(width = 2.78f * scale, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }

        // Arrowhead at the line's tip.
        if (arrowAlpha > 0f) {
            val arrow = androidx.compose.ui.graphics.Path().apply {
                moveTo(p(75.99f, 30.21f).x, p(75.99f, 30.21f).y)
                lineTo(p(67.8f, 31.44f).x, p(67.8f, 31.44f).y)
                lineTo(p(74.51f, 37.58f).x, p(74.51f, 37.58f).y)
                close()
            }
            drawPath(path = arrow, color = BrandIvory.copy(alpha = arrowAlpha))
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBar(
    x0: Float,
    x1: Float,
    bottom: Float,
    top: Float,
    progress: Float,
    color: Color,
    scale: Float
) {
    if (progress <= 0f) return
    val fullHeight = (bottom - top) * scale
    val height = fullHeight * progress
    val left = x0 * scale
    val width = (x1 - x0) * scale
    val bottomY = bottom * scale
    drawRoundRect(
        color = color,
        topLeft = Offset(left, bottomY - height),
        size = androidx.compose.ui.geometry.Size(width, height),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(0f, 0f)
    )
}
