package ca.tristan.portfolio.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Percent
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.StarOutline
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import ca.tristan.portfolio.BuildConfig
import ca.tristan.portfolio.firebase.FirebaseManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ca.tristan.portfolio.ui.CloudState
import ca.tristan.portfolio.ui.PortfolioViewModel
import kotlinx.coroutines.launch
import ca.tristan.portfolio.ui.components.WbDimens
import ca.tristan.portfolio.ui.components.WealthBoardTopBar

/**
 * "Menu" tab — the hamburger ≡ destination in the bottom nav.
 *
 * Rows run: account, the markets dashboard, backup & restore, taxes,
 * Privacy Policy, Contact Support, the admin-only Bug Reports section,
 * and Settings last.
 */
@Composable
fun MenuScreen(
    viewModel: PortfolioViewModel,
    onOpenSettings: () -> Unit,
    onOpenMarkets: () -> Unit,
    onOpenPrivacyPolicy: () -> Unit = {},
    onOpenTaxHelp: () -> Unit = {},
    onOpenTaxSettings: () -> Unit = {},
    onOpenBugReport: () -> Unit = {},
    onOpenProfile: () -> Unit = {},
    onOpenAdminReports: () -> Unit = {},
    onOpenPremium: () -> Unit = {},
    onOpenAlerts: () -> Unit = {}
) {
    val isPremium by ca.tristan.portfolio.billing.Subscriptions.isPremium
        .collectAsStateWithLifecycle()
    val isAdmin = FirebaseManager.currentUser?.email == FirebaseManager.ADMIN_EMAIL
    val isSignedIn = FirebaseManager.currentUser != null
    val scope = rememberCoroutineScope()
    var showRestoreConfirm by remember { mutableStateOf(false) }
    var showCloudChooser by remember { mutableStateOf(false) }
    var showBackupChooser by remember { mutableStateOf(false) }
    var localHoldings by remember { mutableStateOf(-1) }
    // Debug-only readout for the "Check alerts now" row below.
    var debugAlertResult by remember { mutableStateOf<String?>(null) }
    val alerts by viewModel.alerts.collectAsStateWithLifecycle()
    val cloudState by viewModel.cloudState.collectAsStateWithLifecycle()
    val lastBackupAt by viewModel.lastBackupAt.collectAsStateWithLifecycle()

    // A file that has been read and understood, waiting on confirmation —
    // restoring replaces everything, so it is never applied on the strength of
    // the file picker alone.
    var pendingFileRestore by remember {
        mutableStateOf<Map<String, List<Map<String, Any?>>>?>(null)
    }
    var fileError by remember { mutableStateOf<String?>(null) }

    // System file picker, both directions. The Storage Access Framework means
    // no storage permission and no fixed folder: the file goes wherever the
    // user keeps things — Drive, Files, an SD card — which is the point, since
    // a backup stored only on the device it is backing up is not a backup.
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> if (uri != null) viewModel.exportBackupTo(uri) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val sections = viewModel.readBackupFile(uri)
                if (sections == null) {
                    fileError = "That doesn't look like a WealthBoard backup."
                } else {
                    localHoldings = viewModel.localHoldingCount()
                    pendingFileRestore = sections
                }
            }
        }
    }

    Scaffold(
        topBar = {
            WealthBoardTopBar(title = "Menu")
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize()
            ) {

                // ── Account / Profile ─────────────────────────────────────────
                item {
                    MenuNavRow(
                        label = if (FirebaseManager.currentUser != null) "My Account" else "Sign In / Register",
                        icon = Icons.Filled.AccountCircle,
                        onClick = onOpenProfile
                    )
                    Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }

                // ── Premium ───────────────────────────────────────────────────
                //
                // Directly under the account row: the two things a signed-in
                // user is most likely to be looking for are who they are and
                // what they pay.
                item {
                    MenuNavRow(
                        label = if (isPremium) "Premium" else "Go Premium",
                        icon = if (isPremium) Icons.Filled.Verified else Icons.Filled.StarOutline,
                        subtitle = premiumSubtitle(isPremium),
                        onClick = onOpenPremium
                    )
                    Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }

                // ── Alerts ────────────────────────────────────────────────────
                //
                // Under Premium rather than buried near Settings: it is the
                // feature most likely to be the reason someone subscribed, and
                // a rule nobody can find is a rule nobody sets.
                item {
                    MenuNavRow(
                        label = "Alerts",
                        icon = Icons.Filled.NotificationsActive,
                        subtitle = alertsSubtitle(alerts.size, isPremium),
                        onClick = onOpenAlerts
                    )
                    Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }

                // ── Markets row ───────────────────────────────────────────────
                //
                // The markets dashboard lives here now that News has the tab it
                // used to share. Indices, movers, the watchlist and symbol
                // search are worth keeping — they are just not a
                // several-times-a-day screen the way the feed is.
                item {
                    MenuNavRow(
                        label = "Markets & Watchlist",
                        icon = Icons.Filled.ShowChart,
                        onClick = onOpenMarkets
                    )
                    Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }

                // ── Backup & Restore — always, signed in or not ───────────────
                //
                // This row used to appear only when signed in, which made the
                // app's only safety net invisible to everyone who had not made
                // an account. The portfolio is hand-entered: losing it means
                // retyping every holding, transaction and dividend. Backup is
                // not a premium feature, it is what makes hand-entry
                // survivable — so the file export needs no account, no network
                // and no ad, and cloud sync sits behind it as the convenience
                // for people who do sign in.
                item {
                    MenuNavRow(
                        label = "Backup & Restore",
                        icon = Icons.Filled.Cloud,
                        subtitle = if (lastBackupAt > 0L)
                            "Last cloud backup " + java.text.SimpleDateFormat(
                                "MMM d, yyyy  h:mm a", java.util.Locale.getDefault()
                            ).format(java.util.Date(lastBackupAt))
                        else "Export to a file, or sign in to sync",
                        onClick = { showBackupChooser = true }
                    )
                    Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }

                // ── Taxes ─────────────────────────────────────────────────────
                item {
                    MenuNavRow(
                        label = "Taxes",
                        icon = Icons.Filled.Percent,
                        subtitle = "Residency, account types and your tax rates",
                        onClick = onOpenTaxSettings
                    )
                    Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }

                // ── Privacy Policy ────────────────────────────────────────────
                item {
                    MenuNavRow(
                        label = "Privacy Policy",
                        icon = Icons.Filled.Policy,
                        onClick = onOpenPrivacyPolicy
                    )
                    Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }

                // ── Contact Support / Bug Report ──────────────────────────────
                item {
                    MenuNavRow(
                        label = "Contact Support",
                        icon = Icons.Filled.BugReport,
                        onClick = onOpenBugReport
                    )
                    Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }

                // ── Admin: Bug Reports (visible only to admin account) ────────
                if (isAdmin) {
                    item {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "  ADMIN",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 20.dp)
                        )
                        MenuNavRow(
                            label = "Bug Reports",
                            icon = Icons.Filled.AdminPanelSettings,
                            onClick = onOpenAdminReports
                        )
                        Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    }
                }

                // ── Developer: simulate the Premium gate (debug builds only) ──
                //
                // BuildConfig.DEBUG, not the admin email check above: this is
                // for testing the locked/unlocked UI on any debug install,
                // not just the developer's own signed-in account, and
                // Subscriptions.setDebugPremiumOverride() refuses to do
                // anything unless this exact flag says it's a debug build —
                // so there's no way for this row's tap to reach anything in
                // a release build even if it were somehow left visible.
                if (BuildConfig.DEBUG) {
                    item {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "  DEVELOPER",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.padding(horizontal = 20.dp)
                        )
                        val override = ca.tristan.portfolio.billing.Subscriptions.debugPremiumOverrideOrNull()
                        MenuNavRow(
                            label = "Simulate Premium: " + when (override) {
                                true -> "Unlocked"
                                false -> "Locked"
                                null -> "Off (real Play state)"
                            },
                            icon = Icons.Filled.BugReport,
                            subtitle = "Tap to cycle — forces the paywall gates without a real purchase. " +
                                if (isPremium) "Currently: unlocked." else "Currently: locked.",
                            onClick = {
                                val next = when (override) {
                                    null -> false
                                    false -> true
                                    true -> null
                                }
                                ca.tristan.portfolio.billing.Subscriptions
                                    .setDebugPremiumOverride(BuildConfig.DEBUG, next)
                            }
                        )
                        Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        MenuNavRow(
                            label = "Check alerts now (debug)",
                            icon = Icons.Filled.NotificationsActive,
                            subtitle = debugAlertResult
                                ?: "Debug builds only. Alerts already run by themselves — " +
                                "every 2 minutes while the app is open and every 15 minutes " +
                                "in the background. This forces one pass now, ignoring market hours.",
                            onClick = {
                                debugAlertResult = "Checking…"
                                viewModel.debugRunAlertCheck(BuildConfig.DEBUG) { fired, wasPremium, delivery, detail ->
                                    val headline = when {
                                        !wasPremium ->
                                            "Skipped — not Premium. Use Simulate Premium above."
                                        fired == 0 -> "Checked. Nothing fired."
                                        // Posting succeeds without permission
                                        // and delivers nothing, so the old
                                        // "check your notifications" was a
                                        // wrong steer in exactly the case the
                                        // user most needed a right one.
                                        !delivery.authorized ->
                                            "$fired fired, but notifications are NOT permitted — " +
                                                "none were delivered. Open Alerts to grant it."
                                        // Permission granted is NOT the same
                                        // as "will be seen": a blocked channel
                                        // drops the post silently while the
                                        // app-level switch stays on.
                                        !delivery.hasVisibleDestination ->
                                            "$fired fired and posted, but nowhere visible. " +
                                                delivery.summary
                                        // Do Not Disturb is invisible to the
                                        // app: the post succeeds and Android
                                        // silences it. From the outside that
                                        // looks identical to never firing.
                                        fired == 1 ->
                                            "1 alert fired and posted. If nothing appeared, " +
                                                "check the notification shade and Do Not Disturb."
                                        else ->
                                            "$fired alerts fired and posted. If nothing appeared, " +
                                                "check the notification shade and Do Not Disturb."
                                    }
                                    debugAlertResult =
                                        if (detail.isBlank()) headline else "$headline  $detail"
                                }
                            }
                        )
                        Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    }
                }

                // ── Settings row ──────────────────────────────────────────────
                // Last in the list, below the admin section. Settings is a
                // destination people go looking for deliberately rather than one
                // they browse into, so it doesn't need to sit near the top.
                item {
                    if (isAdmin) Spacer(Modifier.height(8.dp))
                    MenuNavRow(
                        label = "Settings",
                        icon = Icons.Filled.Settings,
                        onClick = onOpenSettings
                    )
                    Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }

                // Clearance for the pinned version stamp below, so it never
                // covers the Settings row once the menu is long enough to scroll.
                item { Spacer(Modifier.height(WbDimens.ScrollBottomGap)) }
            }

            // ── Version stamp ─────────────────────────────────────────────
            // Pinned to the bottom of the screen rather than added as a list
            // item, so it sits in the same place whether or not the menu
            // scrolls. VERSION_NAME is the part people quote back to you;
            // VERSION_CODE is what identifies the exact build in Play, which
            // is what makes a bug report actionable.
            Text(
                "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = 14.dp),
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    // ── Backup & Restore chooser ──────────────────────────────────────────────
    //
    // File first, cloud second, and that order is deliberate: the file path is
    // the one that works with no account, offline, and when the sign-in service
    // is having a bad day.
    if (showBackupChooser) {
        AlertDialog(
            onDismissRequest = { showBackupChooser = false },
            title = { Text("Backup & Restore") },
            text = {
                Column {
                    Text(
                        "Save everything you've entered — accounts, holdings, transactions " +
                            "and dividends — to a file you keep. No account needed, and it " +
                            "works offline.",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = {
                        showBackupChooser = false
                        exportLauncher.launch(
                            ca.tristan.portfolio.data.LocalBackup.suggestedFileName()
                        )
                    }) { Text("Export to a file") }
                    TextButton(onClick = {
                        showBackupChooser = false
                        // "*/*" rather than a JSON filter: plenty of providers
                        // hand back .json as octet-stream and would grey out
                        // the user's own backup. The file is validated when it
                        // is read, so filtering here would only cost access.
                        importLauncher.launch(arrayOf("*/*"))
                    }) { Text("Restore from a file") }

                    Divider(
                        modifier = Modifier.padding(vertical = 6.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )

                    if (isSignedIn) {
                        Text(
                            "Or keep a copy against your account, so it survives losing " +
                                "this phone.",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(4.dp))
                        TextButton(onClick = {
                            showBackupChooser = false
                            // Premium-gated. Cloud backup used to sit behind a
                            // rewarded ad; that gate was removed outright when
                            // there was no ad network left to serve one,
                            // rather than replaced, which quietly left this
                            // free for everyone even though the Premium
                            // paywall keeps advertising it as a subscriber
                            // perk. The file export above stays free — it
                            // needs no account and is the safety net for
                            // anyone who hasn't paid or signed in — only the
                            // account-linked cloud copy is Premium.
                            if (isPremium) {
                                showCloudChooser = true
                            } else {
                                onOpenPremium()
                            }
                        }) { Text("Cloud backup & restore") }
                    } else {
                        Text(
                            "Signing in adds a copy that syncs across your devices. The " +
                                "file export above works without one.",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(4.dp))
                        TextButton(onClick = {
                            showBackupChooser = false
                            onOpenProfile()
                        }) { Text("Sign in") }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showBackupChooser = false }) { Text("Close") }
            }
        )
    }

    // ── Restore from a file: confirmation ─────────────────────────────────────
    // Same weight as the cloud confirmation, because it does the same thing,
    // and the counts come from the file that was actually read.
    pendingFileRestore?.let { sections ->
        AlertDialog(
            onDismissRequest = { pendingFileRestore = null },
            title = { Text("Replace everything?") },
            text = {
                Text(
                    buildString {
                        append("This file holds ${sections["holdings"]?.size ?: 0} holding(s), ")
                        append("${sections["transactions"]?.size ?: 0} transaction(s) and ")
                        append("${sections["dividends"]?.size ?: 0} dividend payment(s).")
                        if (localHoldings > 0) {
                            append("\n\nYour $localHoldings holding(s) on this device, and their ")
                            append("transactions and dividends, will be deleted first. ")
                            append("This cannot be undone.")
                        }
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingFileRestore = null
                    viewModel.restorePortfolioFromFile(sections)
                }) { Text("Replace my data") }
            },
            dismissButton = {
                TextButton(onClick = { pendingFileRestore = null }) { Text("Cancel") }
            }
        )
    }

    fileError?.let { text ->
        AlertDialog(
            onDismissRequest = { fileError = null },
            title = { Text("Can't read that file") },
            text = { Text(text) },
            confirmButton = {
                TextButton(onClick = { fileError = null }) { Text("OK") }
            }
        )
    }

    // ── Back up or restore ────────────────────────────────────────────────────
    if (showCloudChooser) {
        AlertDialog(
            onDismissRequest = { showCloudChooser = false },
            title = { Text("Cloud backup") },
            text = {
                Text(
                    if (lastBackupAt > 0L)
                        "Back up this device's portfolio, or replace it with your last backup."
                    else
                        "Back up this device's portfolio so you can restore it later."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showCloudChooser = false
                    viewModel.syncPortfolioToCloud()
                }) { Text("Back up now") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showCloudChooser = false
                    scope.launch {
                        localHoldings = viewModel.localHoldingCount()
                        showRestoreConfirm = true
                    }
                }) { Text("Restore") }
            }
        )
    }

    // ── Restore confirmation ──────────────────────────────────────────────────
    // Restore replaces the local portfolio outright, so the count of what is
    // about to be destroyed is stated plainly and the confirm button says what
    // it does rather than "OK".
    if (showRestoreConfirm) {
        AlertDialog(
            onDismissRequest = { showRestoreConfirm = false },
            title = { Text("Restore from cloud?") },
            text = {
                Text(
                    if (localHoldings > 0)
                        "This replaces everything on this device with your last cloud backup. " +
                            "Your $localHoldings holding(s) here, and their transactions and " +
                            "dividends, will be deleted first. This cannot be undone."
                    else
                        "This copies your last cloud backup onto this device."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showRestoreConfirm = false
                    viewModel.restorePortfolioFromCloud()
                }) { Text("Replace my data") }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreConfirm = false }) { Text("Cancel") }
            }
        )
    }

    // ── Backup / restore result ───────────────────────────────────────────────
    // A silent best-effort write was the old behaviour, and it is precisely why
    // the feature could not be trusted: it looked identical whether it worked
    // or failed. Every outcome now says so.
    when (val state = cloudState) {
        is CloudState.Working -> AlertDialog(
            onDismissRequest = { },
            title = { Text(state.message) },
            text = { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) },
            confirmButton = { }
        )
        is CloudState.Done -> AlertDialog(
            onDismissRequest = { viewModel.dismissCloudState() },
            title = { Text("Done") },
            text = { Text(state.message) },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissCloudState() }) { Text("OK") }
            }
        )
        is CloudState.Error -> AlertDialog(
            onDismissRequest = { viewModel.dismissCloudState() },
            title = { Text("Didn't work") },
            text = { Text(state.message) },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissCloudState() }) { Text("OK") }
            }
        )
        CloudState.Idle -> Unit
    }
}

/**
 * Describes Premium by what this build actually gives, so the Menu row and the
 * paywall cannot promise different things.
 *
 * No ad network is wired up (see the removal note in MainActivity.kt), so
 * there is no "remove ads" branch here — Premium is cloud sync and PDF
 * reports, full stop.
 */
private fun alertsSubtitle(count: Int, isPremium: Boolean): String = when {
    !isPremium -> "Price targets and ex-dividend reminders — Premium"
    count == 0 -> "Set a price target or ex-dividend reminder"
    count == 1 -> "1 alert set"
    else -> "$count alerts set"
}

private fun premiumSubtitle(isPremium: Boolean): String =
    if (isPremium) "Active — alerts, cloud sync, exports, unlimited accounts"
    else "Alerts, cloud sync, exports and unlimited accounts"

@Composable
private fun MenuNavRow(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    subtitle: String? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Column {
                Text(label, fontWeight = FontWeight.Medium, fontSize = 15.sp)
                if (subtitle != null) {
                    Text(
                        subtitle,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
