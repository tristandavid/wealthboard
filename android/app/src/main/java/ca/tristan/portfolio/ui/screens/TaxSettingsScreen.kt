package ca.tristan.portfolio.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ca.tristan.portfolio.ui.PortfolioViewModel
import ca.tristan.portfolio.ui.components.WbDimens
import ca.tristan.portfolio.ui.components.WealthBoardTopBar

/**
 * Everything tax-related, in one place.
 *
 * These three settings were spread through the general Settings screen, where
 * they read as unrelated toggles among biometrics, currency and theme. They are
 * not unrelated: residency gates the feature, account treatment decides whether
 * any figure means anything, and the rates decide whether an estimate can be
 * produced at all. A user who sets one and not the others sees nothing and has
 * no way to tell why — which is exactly what happened. Grouped here, the
 * dependency between them is visible, and the screen can say plainly what is
 * still missing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaxSettingsScreen(
    viewModel: PortfolioViewModel,
    onOpenHelp: () -> Unit,
    onBack: () -> Unit
) {
    val residency by viewModel.residency.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val taxRates by viewModel.taxRates.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            WealthBoardTopBar(
                title = "Taxes",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Spacer(Modifier.height(12.dp))

            // What the app does and does not do, before any field is shown.
            Surface(
                modifier = Modifier.fillMaxWidth().clickable { onOpenHelp() },
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.14f)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        "New to this? Read how tax works here →",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Plain-English explanation of the three settings below and what " +
                            "the app can tell you once they're filled in.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

    // What is still missing, stated once.
    //
    // The three settings below depend on each other, and a half-configured
    // state produces silence everywhere else in the app. Saying which piece is
    // absent is the difference between "there's nothing to report" and "you
    // haven't finished setting this up".
    val unsetAccounts = accounts.count { it.taxTreatment == null }
    val missing = buildList {
        if (residency == ca.tristan.portfolio.tax.Residency.OTHER) add("your tax residency")
        if (unsetAccounts > 0) add(
            "how $unsetAccounts account${if (unsetAccounts == 1) "" else "s"} " +
                "${if (unsetAccounts == 1) "is" else "are"} taxed — open a holding in " +
                "${if (unsetAccounts == 1) "it" else "one"} to set that"
        )
        if (!taxRates.hasAny) add("at least one tax rate (optional, for estimates)")
    }
    if (missing.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        Text(
            "Still needed: " + missing.joinToString("; ") + ".",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    // ── Tax residency ─────────────────────────────────────────────
    // Gates every tax feature in the app, and is seeded from the device
    // region on first run rather than left blank. A device set to en-CA
    // is weak evidence of where someone files — but a blank tax screen
    // reads as "nothing to report here", which is worse than a filled
    // one that says where its answer came from. Hence the note below:
    // seeded, never silently.
    Spacer(Modifier.height(24.dp))
    Text("Tax residency", fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(4.dp))
    Text(
        "Where you file your taxes. Used to flag tax that's being deducted " +
            "from your dividends — nothing is calculated or reported anywhere. " +
            "Leave this unset and the app shows no tax information at all.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(8.dp))
    // Where the current answer came from, shown only while it is still the
    // app's guess. Picking one from the dropdown clears the flag and the
    // note goes with it — an answer the user gave needs no provenance.
    val isAuto by viewModel.isResidencyAuto.collectAsStateWithLifecycle()
    val fromAccounts by viewModel.residencyFromAccounts.collectAsStateWithLifecycle()
    val deviceGuess = remember { viewModel.suggestedResidency() }
    if (isAuto) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.14f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    if (fromAccounts) "Set from your account types (${residency.label})."
                    else "Set from your phone's region (${residency.label}).",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "Change it below if you file somewhere else.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    } else if (deviceGuess != null && deviceGuess != residency) {
        // The way back, for someone who changed it by mistake or moved back.
        TextButton(
            onClick = { viewModel.resetResidencyToDevice() },
            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)
        ) {
            Text(
                "Use my phone's region (${deviceGuess.label})",
                style = MaterialTheme.typography.bodySmall
            )
        }
        Spacer(Modifier.height(4.dp))
    }

    // A dropdown.
    //
    // Three countries fitted as chips, thirteen did not, and a thirteen-row
    // list pushed the rate fields off the bottom of the screen. A dropdown
    // shows the current answer in one line and the options only when asked,
    // which is what a settings field with many values wants to be.
    var residencyMenuOpen by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = residencyMenuOpen,
        onExpandedChange = { residencyMenuOpen = !residencyMenuOpen }
    ) {
        OutlinedTextField(
            value = residency.label,
            onValueChange = {},
            readOnly = true,
            label = { Text("I file taxes in") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = residencyMenuOpen) },
            modifier = Modifier.fillMaxWidth().menuAnchor()
        )
        ExposedDropdownMenu(
            expanded = residencyMenuOpen,
            onDismissRequest = { residencyMenuOpen = false }
        ) {
            ca.tristan.portfolio.tax.Residency.values().forEach { r ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(r.label)
                            // Says plainly what each choice turns on, so a UK
                            // user is not left wondering why they get account
                            // types but no withholding figures.
                            if (r != ca.tristan.portfolio.tax.Residency.OTHER) {
                                Text(
                                    if (r.modelsWithholding) "Full tax details"
                                    else "Account types only",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    onClick = {
                        viewModel.setResidency(r)
                        residencyMenuOpen = false
                    }
                )
            }
        }
    }
    Spacer(Modifier.height(8.dp))

    if (residency != ca.tristan.portfolio.tax.Residency.OTHER) {
        Spacer(Modifier.height(8.dp))
        Text(
            ca.tristan.portfolio.tax.TaxRules.DISCLAIMER,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    // ── Tax rates ─────────────────────────────────────────────────
    // Asked for, not computed. See TaxRules.UserRates for why.
    if (residency != ca.tristan.portfolio.tax.Residency.OTHER) {
        Spacer(Modifier.height(24.dp))
        Text("Your tax rates", fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "Optional. Enter these and the app can estimate the tax on your dividend " +
                "income and on gains if you sold. Leave them blank and it stays with " +
                "what it can know for certain — what's withheld at source.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))

        RateField(
            label = ca.tristan.portfolio.tax.TaxRules.marginalRateLabel(residency),
            hint = "The rate on your next dollar of ordinary income. Your tax return " +
                "or a marginal-rate table for your province or state will give it.",
            value = taxRates.marginalPct,
            onChange = { viewModel.setMarginalRate(it) }
        )
        Spacer(Modifier.height(12.dp))
        RateField(
            label = ca.tristan.portfolio.tax.TaxRules.preferentialRateLabel(residency),
            hint = ca.tristan.portfolio.tax.TaxRules.preferentialRateHint(residency),
            value = taxRates.preferentialPct,
            onChange = { viewModel.setPreferentialRate(it) }
        )
    }


            Spacer(Modifier.height(WbDimens.ScrollBottomGap))
        }
    }
}

/**
 * A percentage entry field that accepts being emptied.
 *
 * Blank is a meaningful value here — "I haven't told you" — and is different
 * from 0%, which would be a claim that the user pays no tax. Clearing the field
 * therefore clears the setting rather than storing a zero.
 */
@Composable
private fun RateField(
    label: String,
    hint: String,
    value: Double?,
    onChange: (Double?) -> Unit
) {
    var text by remember(value) {
        mutableStateOf(value?.let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() } ?: "")
    }
    Column {
        Text(label, fontWeight = FontWeight.Medium, fontSize = 14.sp)
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { raw ->
                val cleaned = raw.filter { it.isDigit() || it == '.' }.take(5)
                text = cleaned
                onChange(cleaned.toDoubleOrNull()?.takeIf { it in 0.0..100.0 })
            },
            singleLine = true,
            suffix = { Text("%") },
            placeholder = { Text("Not set") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(4.dp))
        Text(
            hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
