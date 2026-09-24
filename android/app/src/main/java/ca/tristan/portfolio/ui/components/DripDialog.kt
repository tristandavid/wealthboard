package ca.tristan.portfolio.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ca.tristan.portfolio.ui.theme.LossRed

/**
 * Confirms how a received dividend was reinvested before it changes a position.
 *
 * A broker's DRIP fills at a price the app can't know — often a volume-weighted
 * average, sometimes with a discount, and usually in fractional units. Rather
 * than silently inventing units at the current market price, this asks the user
 * for the price and unit count actually filled, pre-filling a sensible estimate
 * they can correct. Choosing "Keep as cash" leaves the payment recorded as
 * income and the position untouched, which is the right answer when the
 * dividend was paid out rather than reinvested.
 */
@Composable
fun DripConfirmDialog(
    tickerLabel: String,
    dividendAmount: Double,
    currency: String,
    suggestedPrice: Double?,
    onDismiss: () -> Unit,
    onKeepAsCash: () -> Unit,
    onConfirm: (shares: Double, pricePerShare: Double) -> Unit
) {
    var priceText by remember(suggestedPrice) {
        mutableStateOf(suggestedPrice?.let { String.format("%.2f", it) } ?: "")
    }
    val price = priceText.toDoubleOrNull() ?: 0.0
    // Units default to the whole payment divided by the price; the user can
    // override when their broker only bought whole shares.
    var sharesText by remember { mutableStateOf("") }
    val derivedShares = if (price > 0) dividendAmount / price else 0.0
    val shares = sharesText.toDoubleOrNull() ?: derivedShares
    val used = shares * price
    val leftover = dividendAmount - used

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reinvest $tickerLabel dividend") },
        text = {
            Column {
                Text(
                    "${String.format("%,.2f", dividendAmount)} $currency received. " +
                        "Confirm the price and units your broker actually filled.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = priceText,
                    onValueChange = { priceText = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Price per share, $currency") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = sharesText,
                    onValueChange = { sharesText = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Units acquired") },
                    placeholder = {
                        Text(
                            if (derivedShares > 0) String.format("%.4f", derivedShares) else "0"
                        )
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                if (price > 0 && shares > 0) {
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth()) {
                        Text(
                            "Reinvesting ${String.format("%,.2f", used)} $currency",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (leftover > 0.005) {
                        Text(
                            "${String.format("%,.2f", leftover)} $currency left over — " +
                                "stays recorded as cash income.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else if (leftover < -0.005) {
                        Text(
                            "That's more than the dividend paid. Check the numbers.",
                            style = MaterialTheme.typography.bodySmall,
                            color = LossRed,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = price > 0 && shares > 0,
                onClick = { onConfirm(shares, price) }
            ) { Text("Add to holding") }
        },
        dismissButton = {
            TextButton(onClick = onKeepAsCash) { Text("Keep as cash") }
        }
    )
}
