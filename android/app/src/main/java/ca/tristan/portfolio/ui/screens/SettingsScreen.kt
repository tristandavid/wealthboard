package ca.tristan.portfolio.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.Surface
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ca.tristan.portfolio.data.MarketIndices
import ca.tristan.portfolio.security.AppLock
import ca.tristan.portfolio.ui.PortfolioViewModel

@Composable
fun SettingsScreen(
    viewModel: PortfolioViewModel,
    activity: FragmentActivity,
    onBack: () -> Unit,
    onOpenTransactions: () -> Unit = {}
) {
    val context = LocalContext.current
    var lockEnabled by remember { mutableStateOf(AppLock.isEnabled(context)) }
    var allowScreenshots by remember { mutableStateOf(AppLock.allowScreenshots(context)) }
    var pinInput by remember { mutableStateOf("") }
    var showPinField by remember { mutableStateOf(false) }

    val homeCountry by viewModel.homeCountry.collectAsStateWithLifecycle()
    var showMarketPicker by remember { mutableStateOf(false) }
    val dripEnabled by viewModel.dripEnabled.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()

    val baseCurrency by viewModel.baseCurrency.collectAsStateWithLifecycle()
    val heldCurrencies by viewModel.heldCurrencies.collectAsStateWithLifecycle()
    var showCurrencyPicker by remember { mutableStateOf(false) }

    // The currencies actually held, plus the majors, so someone can report in a
    // currency they don't yet hold — a Canadian holding only US stocks may still
    // want the total in CAD.
    val currencyOptions = remember(heldCurrencies, baseCurrency) {
        (heldCurrencies + baseCurrency.uppercase() +
            listOf("CAD", "USD", "EUR", "GBP", "AUD", "JPY"))
            .map { it.uppercase() }
            .filter { it.isNotBlank() }
            .distinct()
            .sortedWith(compareBy<String> { if (it in heldCurrencies) 0 else 1 }.thenBy { it })
    }

    Scaffold(
        topBar = {
            ca.tristan.portfolio.ui.components.WealthBoardTopBar(
                title = "Settings",
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
                }
            )
        },
        containerColor = androidx.compose.material3.MaterialTheme.colorScheme.background
    ) { padding ->
        // verticalScroll is load-bearing. This was a plain fillMaxSize Column,
        // so every section past the first screenful — Data, and anything added
        // below it — was laid out but clipped, with no way to scroll to it.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text("App lock", fontWeight = FontWeight.Bold)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Require biometric or PIN to open")
                Switch(checked = lockEnabled, onCheckedChange = { checked ->
                    lockEnabled = checked
                    if (checked) {
                        AppLock.enableBiometric(context)
                    } else {
                        AppLock.disable(context)
                    }
                    activity.applySecureFlagIfPossible()
                })
            }

            if (lockEnabled) {
                Spacer(Modifier.height(8.dp))
                Button(onClick = { showPinField = !showPinField }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (showPinField) "Cancel" else "Use a 6-digit PIN instead of biometric")
                }
                if (showPinField) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = pinInput,
                        onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) pinInput = it },
                        label = { Text("6-digit PIN") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            if (pinInput.length == 6) {
                                AppLock.enablePin(context, pinInput)
                                showPinField = false
                                pinInput = ""
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Save PIN") }
                }

                Spacer(Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Allow screenshots")
                    Switch(checked = allowScreenshots, onCheckedChange = { checked ->
                        allowScreenshots = checked
                        AppLock.setAllowScreenshots(context, checked)
                        activity.applySecureFlagIfPossible()
                    })
                }
            }

            // ── Appearance ────────────────────────────────────────────────
            Spacer(Modifier.height(24.dp))
            Text("Appearance", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Pick a theme, or follow whatever your device is set to.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            ca.tristan.portfolio.ui.theme.ThemeMode.values().forEach { mode ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // The whole row is the target, not just the button —
                        // a 20dp radio is a poor thing to ask a thumb to hit.
                        .clickable { viewModel.setThemeMode(mode) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = themeMode == mode,
                        onClick = { viewModel.setThemeMode(mode) }
                    )
                    Spacer(Modifier.width(4.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(mode.label, fontWeight = FontWeight.Medium)
                        Text(
                            mode.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // ── Reporting currency ────────────────────────────────────────
            Spacer(Modifier.height(24.dp))
            Text("Reporting currency", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Your total value, day change and allocation percentages are " +
                    "converted into this currency at the current exchange rate. " +
                    "Individual holdings still show in the currency they trade in.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(baseCurrency.uppercase(), fontWeight = FontWeight.Medium)
                    Text(
                        if (heldCurrencies.size > 1)
                            "You hold ${heldCurrencies.joinToString(", ")}"
                        else "Single-currency portfolio",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Button(onClick = { showCurrencyPicker = true }) { Text("Change") }
            }

            // ── Home market ───────────────────────────────────────────────
            Spacer(Modifier.height(24.dp))
            Text("Home market", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Which country's index appears on the Markets tab after the US " +
                    "benchmarks. Detected from your device region unless you pick one.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    val idx = MarketIndices.forCountry(homeCountry)
                    Text(
                        idx?.let { "${it.flag}  ${it.displayName}" } ?: "🇺🇸  US markets only",
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        if (viewModel.isHomeCountryAuto) "Auto-detected ($homeCountry)"
                        else "Chosen manually ($homeCountry)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Button(onClick = { showMarketPicker = true }) { Text("Change") }
            }
            if (!viewModel.isHomeCountryAuto) {
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = { viewModel.setHomeCountry(null) }) {
                    Text("Reset to auto-detect")
                }
            }

            // ── Dividend reinvestment ─────────────────────────────────────
            Spacer(Modifier.height(24.dp))
            Text("Dividend reinvestment (DRIP)", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                "When on, each dividend you receive offers to be reinvested. " +
                    "You confirm the actual fill price and units before anything " +
                    "is added to a holding — nothing changes your position " +
                    "automatically.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Offer DRIP on received dividends")
                Switch(
                    checked = dripEnabled,
                    onCheckedChange = { viewModel.setDripEnabled(it) }
                )
            }

            // ── Transaction history ───────────────────────────────────────
            Spacer(Modifier.height(24.dp))
            Text("Transactions", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Every buy, sell and DRIP recorded against your holdings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = onOpenTransactions, modifier = Modifier.fillMaxWidth()) {
                Text("View transaction history")
            }

            Spacer(Modifier.height(24.dp))
            Text("Data", fontWeight = FontWeight.Bold)
            Text("Every holding is entered by hand — there's no institution sign-in. ETFs and stocks with a ticker are priced from public market data and refresh automatically in the background; other holding types use the price you enter.")

            Spacer(Modifier.height(24.dp))
            Text("Credits", fontWeight = FontWeight.Bold)
            // Required, not decorative: the logo CDN's free tier is licensed on
            // condition of a visible credit of at least 12sp linking back. See
            // LogoResolver.ATTRIBUTION_TEXT. Removing this puts the app outside
            // that licence.
            Text(
                ca.tristan.portfolio.net.LogoResolver.ATTRIBUTION_TEXT,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable {
                    runCatching {
                        activity.startActivity(
                            android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(
                                    ca.tristan.portfolio.net.LogoResolver.ATTRIBUTION_URL
                                )
                            )
                        )
                    }
                }
            )

            // Clearance so the last paragraph clears the system navigation bar
            // instead of ending flush against it.
            Spacer(Modifier.height(32.dp))
        }
    }

    if (showMarketPicker) {
        AlertDialog(
            onDismissRequest = { showMarketPicker = false },
            title = { Text("Home market") },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(MarketIndices.selectableCountries()) { (code, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.setHomeCountry(code)
                                    showMarketPicker = false
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(label, modifier = Modifier.weight(1f))
                            if (code == homeCountry) {
                                Text("✓", fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showMarketPicker = false }) { Text("Close") }
            }
        )
    }

    if (showCurrencyPicker) {
        AlertDialog(
            onDismissRequest = { showCurrencyPicker = false },
            title = { Text("Reporting currency") },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(currencyOptions) { code ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.setBaseCurrency(code)
                                    showCurrencyPicker = false
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(code, fontWeight = FontWeight.Medium)
                                if (code in heldCurrencies) {
                                    Text(
                                        "You hold positions in this currency",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            if (code.equals(baseCurrency, ignoreCase = true)) {
                                Text("✓", fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCurrencyPicker = false }) { Text("Close") }
            }
        )
    }
}

// MainActivity exposes applySecureFlag(); called here via reflection-free
// extension so Settings doesn't need to know MainActivity's concrete type
// beyond FragmentActivity in most cases. In this app MainActivity IS the
// FragmentActivity passed in, so this just calls straight through.
private fun FragmentActivity.applySecureFlagIfPossible() {
    (this as? ca.tristan.portfolio.MainActivity)?.applySecureFlag()
}


