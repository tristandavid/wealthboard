package ca.tristan.portfolio.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import ca.tristan.portfolio.ui.theme.GainGreen
import ca.tristan.portfolio.ui.theme.LossRed

/**
 * Shared building blocks for WealthBoard's screens.
 *
 * Every screen used to roll its own card shapes, paddings and section titles,
 * so the app looked like several apps stitched together. These are the single
 * source of truth for that chrome — screens compose these instead of
 * hand-rolling Cards and Rows, which is what keeps the look uniform.
 */
object WbDimens {
    /** Standard horizontal inset from the screen edge. */
    val ScreenPadding = 16.dp
    /** Inner padding for card content. */
    val CardPadding = 16.dp
    /** Gap between stacked cards / sections. */
    val SectionGap = 18.dp
    /** Gap between rows inside a card. */
    val RowGap = 12.dp
    /** Corner radius used by every card in the app. */
    val CardRadius = 16.dp

    /**
     * Breathing room under the last item of a scrolling screen.
     *
     * Deliberately small. Screens used to end with 88.dp of it, which was a
     * hand-measured stand-in for the height of the bottom navigation bar — from
     * before `PortfolioApp` inset the NavHost by the bar's ACTUAL height. Once
     * it did, the two stacked: content stopped roughly 88dp short of the bar
     * and left a dark band with nothing in it, and on screens with no bottom
     * bar at all (holding detail, quote detail) the whole 88dp was dead space.
     *
     * The bar is the navigation host's problem. This is just the gap that stops
     * the last row sitting flush against it.
     */
    val ScrollBottomGap = 24.dp
}

/** Colour for a signed value: green when non-negative, red when negative. */
@Composable
fun changeColor(value: Double): Color = if (value >= 0) GainGreen else LossRed

/**
 * Section title, optionally with a trailing action (a "See all" link, a count).
 * Used above every list and card group so headings are visually identical.
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = WbDimens.ScreenPadding, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        trailing?.invoke()
    }
}

/** The app's standard card. One radius, one elevation, one padding, everywhere. */
@Composable
fun WbCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: androidx.compose.foundation.layout.PaddingValues =
        androidx.compose.foundation.layout.PaddingValues(WbDimens.CardPadding),
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = RoundedCornerShape(WbDimens.CardRadius),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(contentPadding), content = content)
    }
}

/**
 * A labelled metric, stacked label-over-value. This is the unit the holding
 * detail screen is built from — two per row gives the paired-stat grid.
 */
@Composable
fun StatCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    emphasis: Boolean = false
) {
    Column(modifier = modifier) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(2.dp))
        Text(
            value,
            fontSize = if (emphasis) 20.sp else 16.sp,
            fontWeight = if (emphasis) FontWeight.Bold else FontWeight.SemiBold,
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** Two [StatCell]s side by side, evenly split — the grid used in Position/Dividends. */
@Composable
fun StatPair(
    leftLabel: String,
    leftValue: String,
    rightLabel: String?,
    rightValue: String?,
    modifier: Modifier = Modifier,
    leftColor: Color = MaterialTheme.colorScheme.onSurface,
    rightColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Row(modifier = modifier.fillMaxWidth()) {
        StatCell(leftLabel, leftValue, Modifier.weight(1f), leftColor)
        Spacer(Modifier.size(12.dp))
        if (rightLabel != null && rightValue != null) {
            StatCell(rightLabel, rightValue, Modifier.weight(1f), rightColor)
        } else {
            Spacer(Modifier.weight(1f))
        }
    }
}

/** Single-line label ⟷ value row, for dense key/value lists. */
@Composable
fun KeyValueRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = valueColor
        )
    }
}

/** Thin separator with the app's standard alpha. */
@Composable
fun WbDivider(modifier: Modifier = Modifier) {
    Divider(
        modifier = modifier,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
    )
}

/** Small coloured status pill (Estimated / Announced / Fast / Negative …). */
@Composable
fun StatusPill(
    text: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(5.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Box(Modifier.size(5.dp).background(color, CircleShape))
        Text(text, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

/** Small fixed palette an avatar's colour is picked from, keyed by its label. */
private val AVATAR_PALETTE = listOf(
    Color(0xFF1F6FEB), Color(0xFF8957E5), Color(0xFFCF222E), Color(0xFF1A7F37),
    Color(0xFFBF8700), Color(0xFF0969DA), Color(0xFFBC4C00), Color(0xFF6E40C9)
)

private fun avatarColorFor(seed: String): Color {
    val index = (seed.uppercase().hashCode() and 0x7fffffff) % AVATAR_PALETTE.size
    return AVATAR_PALETTE[index]
}

/**
 * Company/ticker logo used everywhere a quote, holding or search result shows
 * one — the quote detail header, holding detail, search suggestions.
 *
 * Falls back to a colour-coded initial when [logoUrl] is null or fails to
 * load, which is the common case: the free-tier logo source
 * ([ca.tristan.portfolio.net.Quote.logoUrl]) only covers Finnhub-profiled
 * tickers, so an index, most Canadian/foreign listings, and crypto never have
 * a real image. The fallback means every row still gets a consistent,
 * recognisable icon rather than blank space where some rows have a logo and
 * others don't.
 */
@Composable
fun TickerLogo(
    logoUrl: String?,
    label: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp
) {
    val initial = label.trimStart('^').firstOrNull { it.isLetter() }
        ?.uppercaseChar()?.toString() ?: "•"
    val tint = avatarColorFor(label)
    val fallback: @Composable () -> Unit = {
        Text(
            initial,
            color = tint,
            fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.42f).sp
        )
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center
    ) {
        if (logoUrl.isNullOrBlank()) {
            fallback()
        } else {
            SubcomposeAsyncImage(
                model = logoUrl,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(size * 0.66f).clip(CircleShape),
                loading = { fallback() },
                error = { fallback() }
            )
        }
    }
}

/**
 * Horizontal position indicator for a value inside a low→high band, used for
 * the 52-week range. Renders a gradient track with a marker at [fraction].
 */
@Composable
fun RangeBar(
    fraction: Float,
    modifier: Modifier = Modifier
) {
    val clamped = fraction.coerceIn(0f, 1f)
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("L", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.size(6.dp))
        Box(modifier = Modifier.weight(1f).height(8.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        androidx.compose.ui.graphics.Brush.horizontalGradient(
                            listOf(GainGreen.copy(alpha = 0.55f),
                                   Color(0xFFE4C766),
                                   LossRed.copy(alpha = 0.65f))
                        )
                    )
            )
            // Marker
            Box(
                modifier = Modifier
                    .fillMaxWidth(clamped.coerceAtLeast(0.02f))
                    .height(8.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Box(
                    Modifier
                        .size(width = 3.dp, height = 14.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.onSurface)
                )
            }
        }
        Spacer(Modifier.size(6.dp))
        Text("H", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
