package ca.tristan.portfolio.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ca.tristan.portfolio.data.db.TransactionEntity
import ca.tristan.portfolio.data.db.TransactionType
import ca.tristan.portfolio.ui.PortfolioViewModel
import ca.tristan.portfolio.ui.components.WbDimens
import ca.tristan.portfolio.ui.components.WbDivider
import ca.tristan.portfolio.ui.components.WealthBoardTopBar
import ca.tristan.portfolio.ui.theme.GainGreen
import ca.tristan.portfolio.ui.theme.LossRed
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val histDateFmt = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
private val histMonthFmt = SimpleDateFormat("MMMM yyyy", Locale.getDefault())

/**
 * Every buy, sell and DRIP recorded against the portfolio, newest first and
 * grouped by month.
 *
 * Deleting a row reverses its effect on the position, so a mistyped entry can
 * be corrected rather than left to skew cost basis forever.
 */
@Composable
fun TransactionHistoryScreen(
    viewModel: PortfolioViewModel,
    onBack: () -> Unit
) {
    val transactions by viewModel.transactions.collectAsStateWithLifecycle()
    val holdings     by viewModel.holdings.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<TransactionEntity?>(null) }

    // Group by calendar month for readable section headers.
    val grouped = remember(transactions) {
        transactions.groupBy { histMonthFmt.format(Date(it.atMillis)) }
    }

    Scaffold(
        topBar = {
            WealthBoardTopBar(
                title = "Transaction History",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->

        if (transactions.isEmpty()) {
            Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "No transactions yet. Buys, sells and DRIPs you record will appear here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 32.dp)
                )
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = WbDimens.ScrollBottomGap)
        ) {
            grouped.forEach { (month, rows) ->
                item(key = "hdr-$month") {
                    Text(
                        month,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(
                            start = WbDimens.ScreenPadding,
                            end = WbDimens.ScreenPadding,
                            top = 16.dp, bottom = 6.dp
                        )
                    )
                }
                rows.forEach { tx ->
                    item(key = tx.id) {
                        val holding = holdings.firstOrNull { it.id == tx.holdingId }
                        TransactionRow(
                            tx = tx,
                            label = holding?.ticker ?: holding?.name ?: "Unknown holding",
                            onDelete = { pendingDelete = tx }
                        )
                        WbDivider(Modifier.padding(horizontal = WbDimens.ScreenPadding))
                    }
                }
            }
        }
    }

    pendingDelete?.let { tx ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete transaction?") },
            text = {
                Text(
                    "This reverses its effect on your position — " +
                        "${String.format("%,.4f", tx.shares)} units will be " +
                        (if (tx.type == TransactionType.SELL) "added back to" else "removed from") +
                        " the holding, and the cost basis adjusted."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteTransaction(tx.id)
                    pendingDelete = null
                }) { Text("Delete", color = LossRed) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun TransactionRow(
    tx: TransactionEntity,
    label: String,
    onDelete: () -> Unit
) {
    val tint = when (tx.type) {
        TransactionType.BUY  -> GainGreen
        TransactionType.SELL -> LossRed
        TransactionType.DRIP -> MaterialTheme.colorScheme.primary
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = WbDimens.ScreenPadding, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(tint.copy(alpha = 0.14f))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                tx.type.label().uppercase(),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = tint
            )
        }
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, fontWeight = FontWeight.SemiBold, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            Text(
                "${String.format("%,.4f", tx.shares)} @ " +
                    "${String.format("%,.2f", tx.pricePerShare)} · " +
                    histDateFmt.format(Date(tx.atMillis)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                (if (tx.type == TransactionType.SELL) "-" else "+") +
                    String.format("%,.2f", tx.amount),
                fontWeight = FontWeight.Bold,
                color = tint
            )
            Text(
                tx.currency,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = "Delete transaction",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
