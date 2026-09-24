package ca.tristan.portfolio.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ca.tristan.portfolio.ui.PortfolioViewModel

/**
 * Manual dividend entry for a holding. Detection from provider pages is a
 * possible future addition (DividendPaymentEntity.source already has room
 * for "detected"), but v1 is manual entry only, matching the scope Tristan
 * asked for: track payments received, with totals.
 */
@Composable
fun AddDividendScreen(
    viewModel: PortfolioViewModel,
    holdingId: Long,
    onDone: () -> Unit
) {
    val holdings by viewModel.holdings.collectAsStateWithLifecycle()
    val holding = holdings.firstOrNull { it.id == holdingId }

    var amount by remember { mutableStateOf("") }
    var perUnit by remember { mutableStateOf("") }
    // Millis, not a typed string. The date used to be an OutlinedTextField
    // expecting "yyyy-MM-dd", which silently fell back to today whenever the
    // parse failed — a mistyped date became the wrong date with nothing said.
    var dateMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    var note by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            ca.tristan.portfolio.ui.components.WealthBoardTopBar(
                title = "Record dividend" + (holding?.let { " · ${it.name}" } ?: ""),
                navigationIcon = {
                    IconButton(onClick = onDone) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
                }
            )
        },
        containerColor = androidx.compose.material3.MaterialTheme.colorScheme.background
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            OutlinedTextField(value = amount, onValueChange = { amount = it }, label = { Text("Total amount received") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = perUnit, onValueChange = { perUnit = it }, label = { Text("Per unit (optional)") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            // The app's one date control, shared with Add Transaction and with
            // the iOS build.
            ca.tristan.portfolio.ui.components.WbDateField(
                label = "Date paid",
                millis = dateMillis,
                onChange = { dateMillis = it },
                maxMillis = System.currentTimeMillis()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("Note (optional)") }, modifier = Modifier.fillMaxWidth())

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    val amt = amount.toDoubleOrNull() ?: return@Button
                    val millis = dateMillis
                    viewModel.addDividend(
                        holdingId = holdingId,
                        paidAtMillis = millis,
                        amount = amt,
                        perUnit = perUnit.toDoubleOrNull(),
                        currency = holding?.currency ?: "CAD",
                        note = note.ifBlank { null },
                        onDone = onDone
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save")
            }
        }
    }
}
