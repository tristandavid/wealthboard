package ca.tristan.portfolio.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ca.tristan.portfolio.ui.PortfolioViewModel
import ca.tristan.portfolio.ui.format.Money

@Composable
fun AccountScreen(
    viewModel: PortfolioViewModel,
    accountId: Long,
    onOpenHolding: (Long) -> Unit,
    onAddHolding: () -> Unit,
    onBack: () -> Unit,
    onAccountDeleted: () -> Unit
) {
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val holdings by viewModel.holdings.collectAsStateWithLifecycle()
    val account = accounts.firstOrNull { it.id == accountId }
    val accountHoldings = holdings.filter { it.accountId == accountId }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val residency by viewModel.residency.collectAsStateWithLifecycle()
    val taxOn = residency != ca.tristan.portfolio.tax.Residency.OTHER

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(account?.displayName ?: "Account", fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = { showDeleteConfirm = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Remove this account")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddHolding,
                containerColor = MaterialTheme.colorScheme.secondary,
                contentColor = MaterialTheme.colorScheme.onSecondary
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Add holding")
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
        if (taxOn && account != null) {
            // Sits above the holdings rather than behind a settings screen:
            // an unclassified account produces NO tax notes anywhere, which
            // reads identically to "there's nothing to say about this account".
            // Asking here, where the account is already on screen, is the only
            // place the question makes sense.
            TaxTreatmentPicker(
                current = account.taxTreatment,
                residencyCode = residency.code,
                onPick = { viewModel.setAccountTaxTreatment(accountId, it) }
            )
        }
        if (accountHoldings.isEmpty()) {
            Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                Text(
                    "No holdings yet — tap + to add one.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(accountHoldings) { holding ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenHolding(holding.id) }
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(holding.name, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TypeBadge(holding.type.label())
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "${Money.units(holding.units)} units" +
                                        (holding.ticker?.let { " · $it" } ?: ""),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        val price = holding.lastKnownPrice ?: holding.manualPrice ?: 0.0
                        // The holding's own currency, not a hardcoded Canadian
                        // one: this row is what the position is worth on its
                        // own statement.
                        Text(
                            Money.format(
                                price * holding.units,
                                holding.currency.uppercase().ifBlank { "CAD" }
                            ),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                }
            }
        }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Remove this account?") },
            text = { Text("This removes it and every holding in it from the app.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    viewModel.deleteAccount(accountId)
                    onAccountDeleted()
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun TypeBadge(label: String) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
        )
    }
}

/**
 * Lets the user say how an account is taxed, in plain terms.
 *
 * The three choices are described by what happens to the money rather than by
 * their product names, because the names are the part people get wrong — a
 * TFSA is not a savings account and an RRSP is not a fund, and someone who
 * doesn't know that can still answer "is this taxed now, later, or never?".
 * Real product names appear underneath as examples, matched to the user's
 * country so a Canadian isn't offered "Roth IRA".
 */
@Composable
private fun TaxTreatmentPicker(
    current: ca.tristan.portfolio.data.db.TaxTreatment?,
    residencyCode: String,
    onPick: (ca.tristan.portfolio.data.db.TaxTreatment) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        shape = RoundedCornerShape(14.dp),
        color = if (current == null)
            MaterialTheme.colorScheme.secondary.copy(alpha = 0.14f)
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                if (current == null) "How is this account taxed?" else "Tax treatment",
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp
            )
            if (current == null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Pick one and the app can tell you when tax is quietly being " +
                        "deducted from your dividends. Not sure? See \"How tax works here\" " +
                        "in the Menu.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(10.dp))
            ca.tristan.portfolio.data.db.TaxTreatment.values().forEach { t ->
                val selected = t == current
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(t) }
                        .padding(vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (selected) "●" else "○",
                        color = if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            t.label(),
                            fontSize = 13.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                        )
                        Text(
                            ca.tristan.portfolio.tax.TaxRules.examplesFor(t, ca.tristan.portfolio.tax.Residency.fromCode(residencyCode)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
