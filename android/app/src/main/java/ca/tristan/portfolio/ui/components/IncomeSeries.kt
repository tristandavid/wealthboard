package ca.tristan.portfolio.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * One holding's contribution to a single bar.
 *
 * [key] is the stable identity used to pick a colour (the ticker, or the
 * holding name when there is no ticker) and [label] is what the legend shows.
 * Keeping them separate means a renamed holding doesn't change colour, and two
 * holdings that happen to share a display name still get their own slice.
 */
data class IncomeSlice(
    val key: String,
    val label: String,
    val amount: Double
)

/**
 * A bar made of per-holding slices rather than one flat total.
 *
 * The income charts used to draw a single block per month, which answered
 * "how much" but never "from what" — the question a dividend investor actually
 * asks of a month that jumped. Splitting the bar by holding answers both at
 * once, and the total is still just the sum.
 */
data class StackedIncomeBar(
    val label: String,
    val bucketKey: Int,
    val slices: List<IncomeSlice> = emptyList(),
    /** True for a bucket that hasn't happened yet — a forecast, not a record.
     *  `HistoryBarRow` dims these so a projected year is never mistaken for
     *  money actually received. */
    val isProjected: Boolean = false
) {
    val total: Double get() = slices.sumOf { it.amount }
}

/**
 * Colours for per-holding chart series.
 *
 * Assignment is by position in the SORTED list of every key on screen, not by
 * hash: a hash gives a holding the same colour forever but lets two of them
 * collide, and two identically-coloured slices in one stack is the one failure
 * a legend cannot explain. Sorting means the mapping is stable for as long as
 * the portfolio's membership is, and identical across every chart handed the
 * same key list — which is why both income charts are given the whole
 * portfolio's keys rather than only the ones with income in their own window.
 */
object SeriesPalette {

    /**
     * Twelve hues that stay distinguishable side by side and against both the
     * light and dark surfaces the charts sit on. Ordered so the first few —
     * the ones a small portfolio actually gets — are as far apart as possible.
     */
    private val PALETTE = listOf(
        Color(0xFF2E8FE0),  // blue
        Color(0xFF7A4FD1),  // violet
        Color(0xFF0F8B84),  // teal
        Color(0xFFE0803C),  // orange
        Color(0xFFD1457F),  // magenta
        Color(0xFF4CAF50),  // green
        Color(0xFFB4622E),  // sienna
        Color(0xFF00ACC1),  // cyan
        Color(0xFF8E7CC3),  // lavender
        Color(0xFFC0392B),  // red
        Color(0xFF5C7A29),  // olive
        Color(0xFF607D8B)   // slate
    )

    /** Colour map for [keys], stable for a given set of keys. */
    fun colorsFor(keys: Collection<String>): Map<String, Color> =
        keys.distinct().sorted().withIndex().associate { (i, key) ->
            key to PALETTE[i % PALETTE.size]
        }

    /** Fallback for a key that wasn't in the map the chart was built with. */
    val Unknown: Color = Color(0xFF9E9E9E)
}

/**
 * Legend for a per-holding chart: one swatch and label per series.
 *
 * Laid out in fixed rows of [perRow] rather than a scrolling strip — a legend
 * the reader has to drag sideways to finish reading is worse than one that
 * takes a second line. Each entry gets an equal share of its row so the
 * swatches line up into columns instead of drifting with label length.
 */
@Composable
fun IncomeSeriesLegend(
    entries: List<Pair<String, Color>>,
    modifier: Modifier = Modifier,
    perRow: Int = 3
) {
    if (entries.isEmpty()) return
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        entries.chunked(perRow).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { (label, color) ->
                    Row(
                        modifier = Modifier.weight(1f).padding(end = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .size(9.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(color)
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            label,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                // Pad a short final row so its swatches stay in the same
                // columns as the rows above instead of spreading out.
                repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
