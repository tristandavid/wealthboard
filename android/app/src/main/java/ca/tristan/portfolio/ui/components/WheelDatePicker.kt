package ca.tristan.portfolio.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ca.tristan.portfolio.ui.theme.GainGreen
import java.text.DateFormatSymbols
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * The app's one date control, on both platforms: a tappable row that opens a
 * three-column wheel — month, day, year — in a bottom sheet, with Cancel and
 * Confirm above it.
 *
 * A wheel rather than the Material calendar grid, and the same wheel iOS gets,
 * because the two apps should not ask for a date in two different ways. The
 * calendar grid is the better control for *finding* a date ("the second Tuesday
 * of next month"); a trade or a dividend is dated by a day you already know,
 * and spinning to it beats navigating to the month first.
 *
 * Cancel and Confirm are the reason the sheet edits its own copy of the date:
 * a wheel commits as soon as it stops spinning, so without that there would be
 * nothing left for Cancel to undo.
 */
@Composable
fun WbDateField(
    label: String,
    millis: Long,
    onChange: (Long) -> Unit,
    modifier: Modifier = Modifier,
    /** Upper bound, usually "now" — a trade dated in the future is a typo. */
    maxMillis: Long? = null
) {
    var showing by remember { mutableStateOf(false) }
    val formatter = remember { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { showing = true }
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
        Text(
            formatter.format(Date(millis)),
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier
                .clip(RoundedCornerShape(7.dp))
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.16f))
                .padding(horizontal = 11.dp, vertical = 6.dp)
        )
    }

    if (showing) {
        WheelDateSheet(
            initialMillis = millis,
            maxMillis = maxMillis,
            onCancel = { showing = false },
            onConfirm = {
                onChange(it)
                showing = false
            }
        )
    }
}

@Composable
private fun WheelDateSheet(
    initialMillis: Long,
    maxMillis: Long?,
    onCancel: () -> Unit,
    onConfirm: (Long) -> Unit
) {
    val sheetState = rememberModalBottomSheetState()

    val start = remember(initialMillis) {
        Calendar.getInstance().apply { timeInMillis = initialMillis }
    }
    var year by remember { mutableStateOf(start.get(Calendar.YEAR)) }
    var month by remember { mutableStateOf(start.get(Calendar.MONTH)) }
    var day by remember { mutableStateOf(start.get(Calendar.DAY_OF_MONTH)) }

    val lastYear = remember(maxMillis) {
        val cal = Calendar.getInstance()
        maxMillis?.let { cal.timeInMillis = it }
        cal.get(Calendar.YEAR)
    }
    val years = remember(lastYear) { (1970..lastYear).toList() }

    // Locale month names. `months` carries a trailing empty entry on some
    // locales for the 13th month slot, which would render as a blank row.
    val monthNames = remember {
        DateFormatSymbols(Locale.getDefault()).months.filter { it.isNotBlank() }
    }

    val daysInMonth = remember(year, month) {
        Calendar.getInstance().apply {
            clear()
            set(year, month, 1)
        }.getActualMaximum(Calendar.DAY_OF_MONTH)
    }
    // Jan 31 → February has to land somewhere real.
    LaunchedEffect(daysInMonth) {
        if (day > daysInMonth) day = daysInMonth
    }
    val dayNames = remember(daysInMonth) { (1..daysInMonth).map { it.toString() } }

    ModalBottomSheet(
        onDismissRequest = onCancel,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onCancel) {
                Text("Cancel", fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurface)
            }
            TextButton(onClick = {
                val picked = Calendar.getInstance().apply {
                    clear()
                    set(year, month, day.coerceAtMost(daysInMonth))
                }.timeInMillis
                onConfirm(maxMillis?.let { minOf(picked, it) } ?: picked)
            }) {
                Text("Confirm", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = GainGreen)
            }
        }

        Box(modifier = Modifier.fillMaxWidth()) {
            // The selection band, drawn behind the columns so all three share it.
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp)
                    .height(ITEM_HEIGHT)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.10f))
            )

            Row(modifier = Modifier.fillMaxWidth()) {
                WheelColumn(
                    items = monthNames,
                    selectedIndex = month,
                    onSelected = { month = it },
                    modifier = Modifier.weight(1.4f)
                )
                WheelColumn(
                    items = dayNames,
                    selectedIndex = (day - 1).coerceIn(0, dayNames.lastIndex),
                    onSelected = { day = it + 1 },
                    modifier = Modifier.weight(0.8f)
                )
                WheelColumn(
                    items = years.map { it.toString() },
                    selectedIndex = years.indexOf(year).coerceAtLeast(0),
                    onSelected = { year = years[it] },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Spacer(Modifier.height(20.dp))
    }
}

private val ITEM_HEIGHT = 44.dp
private const val VISIBLE_ROWS = 5

/**
 * One spinning column.
 *
 * Snapping is the system's (`rememberSnapFlingBehavior`), so the fling and the
 * settle feel like every other wheel on the device rather than like a list that
 * happens to stop in the right place.
 */
@Composable
private fun WheelColumn(
    items: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (items.isEmpty()) return

    val state = rememberLazyListState(initialFirstVisibleItemIndex = selectedIndex)
    val itemPx = with(LocalDensity.current) { ITEM_HEIGHT.toPx() }

    // Which row is currently under the band. Derived rather than stored so it
    // tracks the scroll continuously and the fading follows the finger.
    val centre by remember {
        derivedStateOf {
            state.firstVisibleItemIndex +
                if (state.firstVisibleItemScrollOffset > itemPx / 2) 1 else 0
        }
    }

    // Pulled back into line when the selection changes from outside — picking
    // February with the day wheel sitting on the 30th has to move that wheel.
    LaunchedEffect(selectedIndex, items.size) {
        if (!state.isScrollInProgress && state.firstVisibleItemIndex != selectedIndex) {
            state.scrollToItem(selectedIndex.coerceIn(0, items.lastIndex))
        }
    }

    // Reported only once the wheel has settled. Reporting mid-fling would
    // rebuild the day column under the user's finger every row it passed.
    LaunchedEffect(state, items.size) {
        snapshotFlow { state.isScrollInProgress }.collect { scrolling ->
            if (!scrolling) {
                val landed = centre.coerceIn(0, items.lastIndex)
                if (landed != selectedIndex) onSelected(landed)
            }
        }
    }

    LazyColumn(
        state = state,
        modifier = modifier.height(ITEM_HEIGHT * VISIBLE_ROWS),
        contentPadding = PaddingValues(vertical = ITEM_HEIGHT * (VISIBLE_ROWS / 2)),
        flingBehavior = rememberSnapFlingBehavior(state),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        itemsIndexed(items) { index, label ->
            val distance = abs(index - centre)
            Box(
                modifier = Modifier.fillMaxWidth().height(ITEM_HEIGHT),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    fontSize = 21.sp,
                    textAlign = TextAlign.Center,
                    color = if (distance == 0) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(
                            alpha = when (distance) {
                                1 -> 0.75f
                                2 -> 0.45f
                                else -> 0.25f
                            }
                        )
                    }
                )
            }
        }
    }
}
