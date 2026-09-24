package ca.tristan.portfolio.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ca.tristan.portfolio.data.db.AccountEntity
import ca.tristan.portfolio.data.db.TaxTreatment
import ca.tristan.portfolio.ui.format.Money

/**
 * Chooses which account a holding or transaction belongs to.
 *
 * Accounts existed in the data model from the start but were never surfaced:
 * both entry screens fell back to `accounts.firstOrNull()`, so every holding
 * landed in one auto-created bucket. That is fine until tax treatment matters,
 * at which point it becomes impossible to say "XEQT is in my RRSP and AAPL is
 * in my TFSA" — one setting covered the whole portfolio and was therefore
 * wrong for most of it.
 *
 * Tax type is asked at account CREATION, not here, because it is a property of
 * the account rather than of this entry. Setting it per-transaction would let
 * the most recent trade silently reclassify every holding in the account.
 */
@Composable
fun AccountPicker(
    accounts: List<AccountEntity>,
    /** Nullable: with no accounts yet there is nothing selected, and nothing
     *  is invented to fill the gap. */
    selectedId: Long?,
    residencyCode: String,
    onSelect: (Long) -> Unit,
    onCreate: (name: String, treatment: TaxTreatment?) -> Unit,
    onChangeTreatment: (accountId: Long, treatment: TaxTreatment) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Units of the security this entry is about, per account — the whole point
     * of showing every account rather than only the ones already holding it.
     * Null where the screen is not about one security (creating a holding by
     * hand, say), in which case no unit line is drawn.
     *
     * A map rather than a lambda on purpose. As `((Long) -> Double)?` this was
     * a nullable function-typed parameter WITH a default, and passing it as
     * `if (x == null) null else { id -> … }` made the Compose compiler's
     * default-argument machinery bind the call's arguments to the wrong
     * parameter slots — the screen crashed on selecting a holding with
     * `ClassCastException: String cannot be cast to Void`. Plain data has no
     * such ambiguity.
     */
    unitsByAccount: Map<Long, Double>? = null,
    /** Accounts that cannot take this entry — nothing to sell out of them. */
    disabledAccountIds: Set<Long> = emptySet(),
    /** Shown under the heading, e.g. "Units are what you hold of XEQT.TO". */
    hint: String? = null,
    /**
     * Opens the paywall. Called instead of the create dialog once a free
     * install is at [ca.tristan.portfolio.billing.PremiumLimits.FREE_ACCOUNT_LIMIT].
     *
     * Defaulted so a screen that has no way to navigate still compiles; the
     * limit dialog then explains where Premium lives rather than offering a
     * button that goes nowhere.
     */
    onUpgrade: (() -> Unit)? = null
) {
    var showCreate by remember { mutableStateOf(false) }
    var showAccountLimit by remember { mutableStateOf(false) }
    var editingTreatmentFor by remember { mutableStateOf<AccountEntity?>(null) }

    val isPremium by ca.tristan.portfolio.billing.Subscriptions.isPremium
        .collectAsStateWithLifecycle()
    val canCreate = ca.tristan.portfolio.billing.PremiumLimits
        .canCreateAccount(accounts.size, isPremium)

    Column(modifier = modifier.fillMaxWidth()) {
        Text("Account", fontWeight = FontWeight.Medium, fontSize = 14.sp)
        if (hint != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                hint,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(6.dp))

        // Every account, three to a row.
        //
        // It used to show three and hide the rest behind a scrolling list
        // under them, which read as "these are your accounts" and made the
        // + New button look like the only way to reach anything else. Every
        // account is a chip now, and the row wraps.
        val rows = (accounts + listOf(null)).chunked(3)
        rows.forEachIndexed { rowIndex, row ->
            if (rowIndex > 0) Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                row.forEach { account ->
                    if (account == null) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.18f),
                            modifier = Modifier.weight(1f).clickable {
                                // Still tappable at the limit, and deliberately:
                                // a greyed-out chip explains nothing, whereas
                                // the dialog below says what the limit is and
                                // what lifts it.
                                if (canCreate) showCreate = true else showAccountLimit = true
                            }
                        ) {
                            Column(
                                modifier = Modifier.padding(vertical = 8.dp, horizontal = 6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    if (canCreate) "+ New" else "★ New",
                                    textAlign = TextAlign.Center,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                                Text(
                                    "account",
                                    textAlign = TextAlign.Center,
                                    fontSize = 9.sp,
                                    color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.75f)
                                )
                            }
                        }
                    } else {
                        val selected = account.id == selectedId
                        val enabled = account.id !in disabledAccountIds
                        // Present-but-zero and absent are different things: the
                        // map says "this screen is about a security", the value
                        // says how much of it is here.
                        val units = unitsByAccount?.let { it[account.id] ?: 0.0 }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier
                                .weight(1f)
                                .alpha(if (enabled) 1f else 0.42f)
                                .clickable(enabled = enabled) { onSelect(account.id) }
                        ) {
                            Column(
                                modifier = Modifier.padding(vertical = 8.dp, horizontal = 6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    account.displayName,
                                    textAlign = TextAlign.Center,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (selected) MaterialTheme.colorScheme.onPrimary
                                            else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                // The tax type is shown on the chip so the choice being
                                // made here is visibly the one that drives the tax
                                // notes, rather than an unexplained label.
                                Text(
                                    account.taxTreatment?.label() ?: "Tax type not set",
                                    textAlign = TextAlign.Center,
                                    fontSize = 9.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = if (selected) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.75f)
                                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                                // What is already there. Zero is printed, not
                                // omitted: an account with none of this holding
                                // is a place you can still buy into, and
                                // leaving it blank made those accounts look
                                // unavailable.
                                if (units != null) {
                                    Spacer(Modifier.height(3.dp))
                                    Text(
                                        Money.units(units),
                                        textAlign = TextAlign.Center,
                                        fontSize = 13.sp,
                                        maxLines = 1,
                                        fontWeight = FontWeight.Bold,
                                        color = when {
                                            selected -> MaterialTheme.colorScheme.onPrimary
                                            units > 0 -> MaterialTheme.colorScheme.secondary
                                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                                                .copy(alpha = 0.6f)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
                // Keeps a short last row the same chip width as a full one.
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }

    // The selected account's tax type, changeable in place.
    //
    // It is set once when the account is created, which is the right moment to
    // ask — but a wrong choice there used to be permanent once the Accounts
    // list was removed from the Taxes screen, and a wrong choice silently
    // changes every tax figure for everything in the account. One line here
    // keeps it correctable without a separate settings screen.
    val selected = accounts.firstOrNull { it.id == selectedId }
    if (selected != null) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { editingTreatmentFor = selected }
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                selected.taxTreatment?.let { "Taxed: ${it.label()}" } ?: "Tax type not set",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Text(
                if (selected.taxTreatment == null) "Set" else "Change",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }

    editingTreatmentFor?.let { account ->
        AlertDialog(
            onDismissRequest = { editingTreatmentFor = null },
            title = { Text("How is ${account.displayName} taxed?") },
            text = {
                Column {
                    TaxTreatment.values().forEach { t ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onChangeTreatment(account.id, t)
                                    editingTreatmentFor = null
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (account.taxTreatment == t) "●" else "○",
                                color = if (account.taxTreatment == t)
                                    MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(t.label(), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                Text(
                                    ca.tristan.portfolio.tax.TaxRules.examplesFor(t, ca.tristan.portfolio.tax.Residency.fromCode(residencyCode)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { editingTreatmentFor = null }) { Text("Cancel") }
            }
        )
    }

    if (showAccountLimit) {
        val limit = ca.tristan.portfolio.billing.PremiumLimits.FREE_ACCOUNT_LIMIT
        AlertDialog(
            onDismissRequest = { showAccountLimit = false },
            title = { Text("Account limit reached") },
            text = {
                Text(
                    "Free installs keep up to $limit accounts — enough for one " +
                        "taxable account and one registered one. Premium removes " +
                        "the limit, so every account you actually hold can be " +
                        "tracked with its own tax treatment.\n\n" +
                        "Nothing already entered is affected: your existing " +
                        "accounts, holdings and history stay exactly as they are."
                )
            },
            confirmButton = {
                if (onUpgrade != null) {
                    TextButton(onClick = {
                        showAccountLimit = false
                        onUpgrade()
                    }) { Text("See Premium") }
                } else {
                    TextButton(onClick = { showAccountLimit = false }) { Text("OK") }
                }
            },
            dismissButton = if (onUpgrade != null) {
                { TextButton(onClick = { showAccountLimit = false }) { Text("Not now") } }
            } else null
        )
    }

    if (showCreate) {
        NewAccountDialog(
            residencyCode = residencyCode,
            onDismiss = { showCreate = false },
            onCreate = { name, treatment ->
                showCreate = false
                onCreate(name, treatment)
            }
        )
    }
}

@Composable
private fun NewAccountDialog(
    residencyCode: String,
    onDismiss: () -> Unit,
    onCreate: (String, TaxTreatment?) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var treatment by remember { mutableStateOf<TaxTreatment?>(null) }

    val residency = ca.tristan.portfolio.tax.Residency.fromCode(residencyCode)
    val suggestions = ca.tristan.portfolio.tax.TaxRules.suggestedAccounts(residency)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New account") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                // One tap fills the name AND the tax treatment.
                //
                // Someone who does not know the jargon cannot reliably decide
                // that a Roth IRA is "tax-free", and getting it wrong silently
                // changes every tax figure for everything in the account. Naming
                // the real product removes the guess.
                if (suggestions.isNotEmpty()) {
                    Text("Common types", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    suggestions.chunked(2).forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            row.forEach { suggestion ->
                                val picked = name == suggestion.name &&
                                    treatment == suggestion.treatment
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (picked) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.weight(1f).clickable {
                                        name = suggestion.name
                                        treatment = suggestion.treatment
                                    }
                                ) {
                                    Text(
                                        suggestion.name,
                                        modifier = Modifier.padding(vertical = 9.dp),
                                        textAlign = TextAlign.Center,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        fontWeight = if (picked) FontWeight.Bold else FontWeight.Medium,
                                        color = if (picked) MaterialTheme.colorScheme.onPrimary
                                                else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    placeholder = {
                        Text(ca.tristan.portfolio.tax.TaxRules.accountNameHint(residency))
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(14.dp))
                Text("How is it taxed?", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                Spacer(Modifier.height(2.dp))
                Text(
                    "Asked once, here, because it belongs to the account rather than to " +
                        "any one trade. You can change it later from this picker.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                TaxTreatment.values().forEach { t ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { treatment = t }
                            .padding(vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (treatment == t) "●" else "○",
                            color = if (treatment == t) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(t.label(), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text(
                                ca.tristan.portfolio.tax.TaxRules.examplesFor(t, ca.tristan.portfolio.tax.Residency.fromCode(residencyCode)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name.trim(), treatment) },
                enabled = name.isNotBlank()
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
