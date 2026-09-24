package ca.tristan.portfolio.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ca.tristan.portfolio.alerts.AlertNotifier
import ca.tristan.portfolio.data.db.AlertEntity
import ca.tristan.portfolio.data.db.AlertKind
import ca.tristan.portfolio.ui.PortfolioViewModel
import ca.tristan.portfolio.ui.components.WealthBoardTopBar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Price and dividend alerts — create, arm, disarm, delete.
 *
 * Alerts are evaluated by the background quote refresh that already runs, so
 * this screen never promises anything the app cannot deliver: if notifications
 * are off at the system level the banner says so and offers the prompt, rather
 * than letting someone set six rules and wonder why they are silent.
 */
@Composable
fun AlertsScreen(
    viewModel: PortfolioViewModel,
    onBack: () -> Unit = {},
    onOpenPremium: () -> Unit = {}
) {
    val context = LocalContext.current
    val alerts by viewModel.alerts.collectAsStateWithLifecycle()
    val holdings by viewModel.holdings.collectAsStateWithLifecycle()
    val watchlist by viewModel.watchlist.collectAsStateWithLifecycle()
    val isPremium by ca.tristan.portfolio.billing.Subscriptions.isPremium
        .collectAsStateWithLifecycle()

    var showCreate by remember { mutableStateOf(false) }
    var canNotify by remember { mutableStateOf(AlertNotifier.canPost(context)) }
    var pendingDelete by remember { mutableStateOf<AlertEntity?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> canNotify = granted }

    // The channel has to exist before the first notification, and creating it
    // here rather than at app launch keeps the app's notification settings
    // clean for anyone who never opens this screen.
    LaunchedEffect(Unit) {
        AlertNotifier.ensureChannel(context)
        canNotify = AlertNotifier.canPost(context)
    }

    // Tickers worth suggesting: everything held or watched. Alerts are keyed
    // by ticker, so a suggestion list is the difference between tapping a
    // symbol and typing one correctly from memory.
    val suggestions = remember(holdings, watchlist) {
        (holdings.mapNotNull { it.ticker } + watchlist.map { it.ticker })
            .map { it.uppercase() }
            .distinct()
            .sorted()
    }

    Scaffold(
        topBar = {
            WealthBoardTopBar(
                title = "Alerts",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            if (isPremium) {
                ExtendedFloatingActionButton(
                    onClick = { showCreate = true },
                    icon = { Icon(Icons.Filled.NotificationsActive, contentDescription = null) },
                    text = { Text("New alert") }
                )
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item { Spacer(Modifier.height(4.dp)) }

            if (!isPremium) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                "Alerts are a Premium feature",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Get told when a price reaches your target, when something " +
                                    "moves sharply in a day, or before a holding goes " +
                                    "ex-dividend — so you find out when it happens rather " +
                                    "than the next time you open the app.",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = onOpenPremium) { Text("See Premium") }
                        }
                    }
                }

                // Existing rules stay visible, read-only, for a lapsed
                // subscriber: they are the user's own configuration, and
                // hiding them would look like they had been deleted.
                if (alerts.isNotEmpty()) {
                    item {
                        Text(
                            "Your saved alerts are kept, and start firing again when " +
                                "Premium is active.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else if (!canNotify) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                "Notifications are off",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Alerts are checked in the background, but nothing can " +
                                    "reach you until notifications are allowed.",
                                fontSize = 13.sp
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    // Pre-13 there is no runtime permission to
                                    // ask for: being off means it was turned
                                    // off in system settings, which only the
                                    // user can undo.
                                    canNotify = AlertNotifier.canPost(context)
                                }
                            }) { Text("Allow notifications") }
                        }
                    }
                }
            }

            if (alerts.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Filled.NotificationsActive,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "No alerts yet",
                            fontWeight = FontWeight.Medium,
                            fontSize = 15.sp
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Alerts are checked with the background price refresh, " +
                                "roughly every half hour while markets are open.",
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                items(alerts, key = { it.id }) { alert ->
                    AlertRow(
                        alert = alert,
                        enabled = isPremium,
                        onToggle = { viewModel.setAlertEnabled(alert.id, it) },
                        onDelete = { pendingDelete = alert }
                    )
                }
            }

            item { Spacer(Modifier.height(80.dp)) }
        }
    }

    if (showCreate) {
        NewAlertDialog(
            viewModel = viewModel,
            suggestions = suggestions,
            onDismiss = { showCreate = false },
            onCreate = { ticker, kind, threshold ->
                showCreate = false
                viewModel.addAlert(ticker, kind, threshold)
                // Asked at the moment the first alert is created, which is
                // when the reason for it is self-evident, rather than at
                // launch where it reads as an app demanding things.
                if (!canNotify && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        )
    }

    pendingDelete?.let { alert ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete this alert?") },
            text = { Text("${alert.ticker} — ${describe(alert)}") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteAlert(alert.id)
                    pendingDelete = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun AlertRow(
    alert: AlertEntity,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(alert.ticker, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Spacer(Modifier.height(2.dp))
                Text(
                    describe(alert),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // "Waiting" vs "fired" is the single most useful thing this
                // row can say: it tells the reader whether silence means
                // nothing has happened or that they already missed the
                // notification.
                alert.triggeredAtMillis?.let { at ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Fired ${timestamp(at)} — re-arms when the condition clears",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Switch(
                checked = alert.enabled,
                onCheckedChange = onToggle,
                enabled = enabled
            )
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Delete alert",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun NewAlertDialog(
    viewModel: PortfolioViewModel,
    suggestions: List<String>,
    onDismiss: () -> Unit,
    onCreate: (String, AlertKind, Double) -> Unit
) {
    var ticker by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(AlertKind.PRICE_ABOVE) }
    var threshold by remember { mutableStateOf("") }

    // Typed-but-not-yet-chosen. Kept separate from [ticker] so that picking a
    // result stops the search rather than re-running it on the symbol that was
    // just selected, which is what makes the result list flicker back open
    // the instant you tap something.
    var query by remember { mutableStateOf("") }
    val results by viewModel.searchResults.collectAsStateWithLifecycle()

    LaunchedEffect(query) {
        if (query.isBlank()) viewModel.clearSearchResults() else viewModel.searchQuotes(query)
    }
    // Leaving the dialog must not leave results behind for the next screen
    // that reads the same shared flow.
    DisposableEffect(Unit) { onDispose { viewModel.clearSearchResults() } }

    val parsed = threshold.replace(",", ".").toDoubleOrNull()
    val valid = ticker.isNotBlank() && parsed != null && parsed > 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New alert") },
        text = {
            Column {
                OutlinedTextField(
                    value = if (query.isBlank()) ticker else query,
                    onValueChange = {
                        query = it
                        // The typed text IS the ticker until a search result
                        // is chosen, so a symbol that the search cannot find —
                        // an index like ^HSI, a listing the endpoint misses —
                        // can still be entered by hand.
                        ticker = it.trim().uppercase()
                    },
                    label = { Text("Ticker or company name") },
                    placeholder = { Text("VDY.TO, Apple, ^HSI…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (results.isNotEmpty() && query.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    // Capped at six: this sits inside a dialog that also has to
                    // fit the rule picker and the threshold field on a phone.
                    results.take(6).forEach { result ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    ticker = result.symbol.uppercase()
                                    // Clearing the query both closes the list
                                    // and puts the chosen symbol back in the
                                    // field, via the value expression above.
                                    query = ""
                                    viewModel.clearSearchResults()
                                }
                                .padding(vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    result.symbol,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                val subtitle = listOfNotNull(
                                    result.name?.takeIf { it.isNotBlank() },
                                    result.exchange?.takeIf { it.isNotBlank() }
                                ).joinToString(" · ")
                                if (subtitle.isNotBlank()) {
                                    Text(
                                        subtitle,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                if (suggestions.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Held or watched",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    // Capped rather than scrolled: this is a shortcut, not a
                    // browser, and a long list here pushes the actual inputs
                    // off a small screen.
                    suggestions.take(12).chunked(3).forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            row.forEach { symbol ->
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (ticker == symbol)
                                        MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                    modifier = Modifier.weight(1f).clickable { ticker = symbol }
                                ) {
                                    Text(
                                        symbol,
                                        modifier = Modifier.padding(vertical = 7.dp),
                                        textAlign = TextAlign.Center,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        color = if (ticker == symbol)
                                            MaterialTheme.colorScheme.onPrimary
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                Text("Tell me when it…", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                Spacer(Modifier.height(6.dp))
                AlertKind.values().forEach { k ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { kind = k }
                            .padding(vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (kind == k) "●" else "○",
                            color = if (kind == k) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.size(10.dp))
                        Text(k.label(), fontSize = 13.sp)
                    }
                }

                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = threshold,
                    onValueChange = { threshold = it },
                    label = {
                        Text(
                            when (kind) {
                                AlertKind.PRICE_ABOVE, AlertKind.PRICE_BELOW -> "Price"
                                AlertKind.DAY_MOVE_PERCENT -> "Percent"
                                AlertKind.EX_DIVIDEND_WITHIN_DAYS -> "Days before"
                            }
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(10.dp))
                Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Spacer(Modifier.height(8.dp))
                Text(
                    "Checked with the background price refresh — roughly every half " +
                        "hour while markets are open, and not overnight, when prices " +
                        "cannot move.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (parsed != null) onCreate(ticker.trim(), kind, parsed) },
                enabled = valid
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ── Wording ───────────────────────────────────────────────────────────────

private fun describe(alert: AlertEntity): String = when (alert.kind) {
    AlertKind.PRICE_ABOVE -> "Rises to ${money(alert.threshold)} or above"
    AlertKind.PRICE_BELOW -> "Falls to ${money(alert.threshold)} or below"
    AlertKind.DAY_MOVE_PERCENT ->
        "Moves ${trim(alert.threshold)}% or more in a day, either way"
    AlertKind.EX_DIVIDEND_WITHIN_DAYS ->
        "Ex-dividend date is within ${trim(alert.threshold)} days"
}

private fun money(value: Double): String =
    String.format(Locale.getDefault(), "%,.2f", value)

/** Drops a pointless ".0" so "5 days" doesn't read as "5.0 days". */
private fun trim(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString()
    else String.format(Locale.getDefault(), "%.2f", value)

private fun timestamp(millis: Long): String =
    SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(millis))
