package ca.tristan.portfolio.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import ca.tristan.portfolio.security.AppLock
import ca.tristan.portfolio.ui.screens.LockScreen
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.NavType
import ca.tristan.portfolio.BuildConfig
import ca.tristan.portfolio.firebase.FirebaseManager
import ca.tristan.portfolio.ui.screens.AccountBreakdownScreen
import ca.tristan.portfolio.ui.screens.AccountScreen
import ca.tristan.portfolio.ui.screens.AddDividendScreen
import ca.tristan.portfolio.ui.screens.AddTransactionScreen
import ca.tristan.portfolio.ui.screens.AdminReportsScreen
import ca.tristan.portfolio.ui.screens.AlertsScreen
import ca.tristan.portfolio.ui.screens.BugReportScreen
import ca.tristan.portfolio.ui.screens.TransactionHistoryScreen
import ca.tristan.portfolio.ui.screens.DividendsScreen
import ca.tristan.portfolio.ui.screens.ForcedUpdateScreen
import ca.tristan.portfolio.ui.screens.HoldingScreen
import ca.tristan.portfolio.ui.screens.InAppBrowserScreen
import ca.tristan.portfolio.ui.screens.LoginScreen
import ca.tristan.portfolio.ui.screens.ManualHoldingScreen
import ca.tristan.portfolio.ui.screens.MarketScreen
import ca.tristan.portfolio.ui.screens.MenuScreen
import ca.tristan.portfolio.ui.screens.NewsScreen
import ca.tristan.portfolio.ui.screens.MyPortfolioScreen
import ca.tristan.portfolio.ui.screens.PrivacyPolicyScreen
import ca.tristan.portfolio.ui.screens.ProfileScreen
import ca.tristan.portfolio.ui.screens.QuoteDetailScreen
import ca.tristan.portfolio.ui.screens.ReportsScreen
import ca.tristan.portfolio.ui.screens.SettingsScreen
import java.net.URLDecoder
import java.net.URLEncoder

object Routes {
    const val DASHBOARD = "dashboard"
    const val PORTFOLIO = "portfolio"
    const val ACCOUNT = "account/{accountId}"
    const val PREMIUM = "premium"
    const val ALERTS = "alerts"
    const val HOLDING = "holding/{holdingId}"
    const val ADD_HOLDING = "add-holding/{accountId}"
    const val EDIT_HOLDING = "edit-holding/{holdingId}"
    const val DIVIDENDS = "dividends"
    const val REPORTS = "reports"
    const val ADD_DIVIDEND = "add-dividend/{holdingId}"
    const val SETTINGS = "settings"
    const val QUOTE_DETAIL = "quote-detail/{watchlistId}"
    const val MENU = "menu"
    const val NEWS = "news"
    const val TRANSACTIONS = "transactions"
    const val ADD_TRANSACTION =
        "add-transaction?accountId={accountId}&holdingId={holdingId}"
    fun addTransaction(accountId: Long?, holdingId: Long? = null): String =
        "add-transaction?accountId=${accountId ?: -1L}&holdingId=${holdingId ?: -1L}"
    const val NEWS_ARTICLE = "news-article/{encodedUrl}"
    const val ACCOUNT_BREAKDOWN = "account-breakdown/{securityKey}"

    // ── New screens ───────────────────────────────────────────────────────
    const val PRIVACY_POLICY = "privacy-policy"
    const val TAX_HELP = "tax-help"
    const val TAX_SETTINGS = "tax-settings"
    const val LOGIN = "login"
    const val PROFILE = "profile"
    const val BUG_REPORT = "bug-report"
    const val ADMIN_REPORTS = "admin-reports"

    // The 5 content tabs shown with the bottom navigation bar.
    //
    // News took the Markets slot rather than being added as a sixth tab: five
    // is what a phone's navigation bar fits without the labels wrapping onto a
    // second line and clipping the outer items. The Markets dashboard is not
    // gone — it moved under Menu, which is where a screen people open
    // occasionally belongs, while the feed they open daily got the tab.
    val TOP_LEVEL = listOf(PORTFOLIO, DIVIDENDS, REPORTS, NEWS, MENU)

    fun account(id: Long) = "account/$id"
    fun holding(id: Long) = "holding/$id"
    fun addHolding(accountId: Long) = "add-holding/$accountId"
    fun editHolding(id: Long) = "edit-holding/$id"
    fun addDividend(holdingId: Long) = "add-dividend/$holdingId"
    fun quoteDetail(watchlistId: Long) = "quote-detail/$watchlistId"
    fun newsArticle(url: String) = "news-article/${URLEncoder.encode(url, "UTF-8")}"
    fun accountBreakdown(securityKey: String) =
        "account-breakdown/${URLEncoder.encode(securityKey, "UTF-8")}"
}

private data class TabItem(
    val route: String,
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    /** If true, navigating to this tab goes directly via navigate() without saveState/restoreState. */
    val directNav: Boolean = false
)

// Labels are deliberately one short word each. With five tabs a phone gives
// each item roughly 70dp, and a two-word label ("My Portfolio") wraps onto a
// second line, which pushes the row taller and clips the tabs at either end.
private val TAB_ITEMS = listOf(
    TabItem(Routes.PORTFOLIO, "Portfolio", Icons.Filled.AccountBalanceWallet),
    TabItem(Routes.DIVIDENDS, "Dividends", Icons.Filled.Payments),
    TabItem(Routes.REPORTS, "Reports", Icons.Filled.Assessment),
    TabItem(Routes.NEWS, "News", Icons.Filled.Article),
    // Menu tab — hamburger icon; saved-state tab like the other content tabs.
    TabItem(Routes.MENU, "Menu", Icons.Filled.Menu)
)

@Composable
fun PortfolioApp(viewModel: PortfolioViewModel, activity: FragmentActivity) {
    val context = LocalContext.current
    var unlocked by remember { mutableStateOf(!AppLock.isEnabled(context)) }

    // ── Version gate ──────────────────────────────────────────────────────
    //
    // Checked once per process, before the lock, and rendered BEFORE it: a
    // retired build is retired whether or not the user can get past their own
    // biometric prompt, and making them authenticate first only to be told the
    // app is unusable is a pointless step.
    //
    // Starts at UpToDate and stays there until the network answers, so a cold
    // launch shows the app immediately rather than a spinner. See
    // UpdateGate.check — it fails open by design.
    var updateStatus by remember {
        mutableStateOf<ca.tristan.portfolio.update.UpdateStatus>(
            ca.tristan.portfolio.update.UpdateStatus.UpToDate
        )
    }
    LaunchedEffect(Unit) {
        updateStatus = ca.tristan.portfolio.update.UpdateGate.check(context)
    }

    // ── Alerts, on open ───────────────────────────────────────────────────
    //
    // Evaluated here as well as in SyncWorker, to match iOS and because the
    // worker alone made the feature look broken: it runs on a 30-45 minute
    // flex window AND skips entirely whenever markets are shut, so an alert
    // set on a Sunday evening produced nothing at all until Monday's open and
    // read as "alerts don't work".
    //
    // Premium and notification permission are both checked inside the engine
    // path, so this is a no-op for anyone who has neither.
    LaunchedEffect(Unit) {
        ca.tristan.portfolio.billing.Subscriptions
            .loadDebugOverride(BuildConfig.DEBUG, context)
        if (ca.tristan.portfolio.billing.Subscriptions.cachedIsPremium(context)) {
            runCatching {
                val db = ca.tristan.portfolio.data.db.AppDatabase.get(context)
                val repo = ca.tristan.portfolio.data.PortfolioRepository(
                    db.accountDao(), db.holdingDao(), db.priceSnapshotDao(),
                    db.dividendDao(), db.watchlistDao(), db.transactionDao(), db.alertDao()
                )
                val dao = db.alertDao()
                val enabled = dao.enabled()
                if (enabled.isNotEmpty()) {
                    // Gated exactly as the worker is. This pass runs on every
                    // launch, so leaving it ungated meant the one path the
                    // user triggers most often was also the one making the
                    // most pointless requests — re-reading prices that cannot
                    // have moved, every time the app was opened at night.
                    val kinds = ca.tristan.portfolio.alerts.AlertGate
                        .kindsFor(context, enabled.map { it.ticker })
                    if (kinds.isNotEmpty()) {
                        val firings = ca.tristan.portfolio.alerts.AlertEngine
                            .evaluate(dao, repo, kinds = kinds)
                        if (ca.tristan.portfolio.data.db.AlertKind.EX_DIVIDEND_WITHIN_DAYS in kinds) {
                            ca.tristan.portfolio.alerts.AlertGate.markExDividendChecked(context)
                        }
                        for (firing in firings) {
                            ca.tristan.portfolio.alerts.AlertNotifier.post(context, firing)
                        }
                    }
                }
            }
        }
    }

    // ── Notification permission ───────────────────────────────────────────
    //
    // Asked for here, not only on the Alerts screen. Requesting it solely
    // where alerts are created assumed the person creating the rule is the
    // person to ask — which misses the case where the rules arrived some
    // other way, a restored backup most obviously, leaving a device with live
    // alerts, no permission, and a post() that drops every one of them in
    // silence.
    //
    // Only when there is something to deliver, and only once: the launcher
    // returns the existing decision without re-prompting, so someone who
    // declined is left alone rather than nagged on every launch.
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Either way the engine keeps working; only delivery differs. */ }

    var notificationPermissionAsked by rememberSaveable { mutableStateOf(false) }
    val enabledAlerts by viewModel.alerts.collectAsStateWithLifecycle()

    LaunchedEffect(enabledAlerts.isNotEmpty(), notificationPermissionAsked) {
        if (notificationPermissionAsked) return@LaunchedEffect
        if (enabledAlerts.none { it.enabled }) return@LaunchedEffect
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
            return@LaunchedEffect
        }
        if (ca.tristan.portfolio.alerts.AlertNotifier.canPost(context)) return@LaunchedEffect
        notificationPermissionAsked = true
        notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    (updateStatus as? ca.tristan.portfolio.update.UpdateStatus.Blocked)?.let { blocked ->
        ForcedUpdateScreen(message = blocked.message, storeUrl = blocked.storeUrl)
        return
    }

    if (!unlocked) {
        LockScreen(activity = activity, onUnlocked = { unlocked = true })
        return
    }

    val navController: NavHostController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // Show the bottom bar for the 4 content tabs.
    val showBottomBar = currentRoute in Routes.TOP_LEVEL

    // The soft prompt. Over the app rather than instead of it: there is
    // nothing wrong with the build they have, so it must stay usable behind
    // the dialog and one dismissal must be the end of it.
    (updateStatus as? ca.tristan.portfolio.update.UpdateStatus.Optional)?.let { optional ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = {
                ca.tristan.portfolio.update.UpdateGate.markOptionalSeen(context)
                updateStatus = ca.tristan.portfolio.update.UpdateStatus.UpToDate
            },
            title = { Text("Update available") },
            text = {
                Text(
                    optional.message?.takeIf { it.isNotBlank() }
                        ?: "A newer version of WealthBoard is on the Play Store."
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    ca.tristan.portfolio.update.UpdateGate.markOptionalSeen(context)
                    updateStatus = ca.tristan.portfolio.update.UpdateStatus.UpToDate
                    ca.tristan.portfolio.update.UpdateGate.openStore(context, optional.storeUrl)
                }) { Text("Update") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = {
                    ca.tristan.portfolio.update.UpdateGate.markOptionalSeen(context)
                    updateStatus = ca.tristan.portfolio.update.UpdateStatus.UpToDate
                }) { Text("Not now") }
            }
        )
    }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    TAB_ITEMS.forEach { tab ->
                        val selected = if (tab.directNav) {
                            currentRoute == tab.route
                        } else {
                            backStackEntry?.destination?.hierarchy?.any { it.route == tab.route } == true
                        }
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                if (tab.directNav) {
                                    // Settings: simple navigate with back-stack support so pressing
                                    // back in Settings returns to the current content tab.
                                    navController.navigate(tab.route) { launchSingleTop = true }
                                } else {
                                    navController.navigate(tab.route) {
                                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            icon = {
                                Icon(
                                    tab.icon,
                                    contentDescription = tab.label,
                                    modifier = Modifier.size(22.dp)
                                )
                            },
                            label = {
                                // softWrap = false keeps every label on one
                                // line; without it the row grows to two lines
                                // and the outer tabs get clipped.
                                Text(
                                    tab.label,
                                    fontSize = 10.sp,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Visible
                                )
                            },
                            alwaysShowLabel = true
                        )
                    }
                }
            }
        }
    ) { scaffoldPadding ->
        // Reserve the bottom bar's space AND tell everything inside that it has
        // been reserved.
        //
        // `padding` alone was not enough, and the bug it left was subtle. Every
        // screen below is its own Scaffold, and a Scaffold reads the window
        // insets to work out its own content padding. `Modifier.padding` moves
        // a child without consuming anything, so each inner Scaffold still saw
        // the full system-navigation inset and subtracted it AGAIN — inside a
        // region that had already excluded it.
        //
        // The result was not a harmless margin. It shortened the scrolling
        // VIEWPORT, so the list was clipped mid-row with a dead strip beneath
        // it, visible whether or not the list was scrolled to the end. (A
        // trailing `contentPadding` would only ever show up at the bottom of
        // the content; this showed up everywhere, which is what gave it away.)
        //
        // `consumeWindowInsets` marks those insets as handled, so the nested
        // Scaffolds add nothing and their content fills the space right up to
        // the bar.
        val bottomInset = if (showBottomBar) scaffoldPadding.calculateBottomPadding() else 0.dp
        NavHost(
            navController = navController,
            startDestination = Routes.PORTFOLIO,
            modifier = Modifier
                .padding(bottom = bottomInset)
                .consumeWindowInsets(PaddingValues(bottom = bottomInset))
        ) {
            composable(Routes.DASHBOARD) {
                MarketScreen(
                    viewModel      = viewModel,
                    onBack         = { navController.popBackStack() },
                    onOpenQuote    = { navController.navigate(Routes.quoteDetail(it)) },
                    // A My Holdings row opens the POSITION, not the quote. The
                    // reader tapping a row on that tab owns the thing; the one
                    // question they cannot answer from the row is what it is
                    // worth to them, and only the holding screen answers it.
                    onOpenHolding  = { navController.navigate(Routes.holding(it)) }
                )
            }
            composable(Routes.NEWS) {
                NewsScreen(
                    viewModel     = viewModel,
                    onOpenArticle = { navController.navigate(Routes.newsArticle(it)) }
                )
            }
            composable(
                Routes.QUOTE_DETAIL,
                arguments = listOf(navArgument("watchlistId") { type = NavType.LongType })
            ) { backStackEntry ->
                val watchlistId = backStackEntry.arguments?.getLong("watchlistId") ?: 0L
                QuoteDetailScreen(
                    viewModel = viewModel,
                    watchlistId = watchlistId,
                    onBack = { navController.popBackStack() },
                    onRemoved = { navController.popBackStack() }
                )
            }
            composable(Routes.PORTFOLIO) {
                MyPortfolioScreen(
                    viewModel = viewModel,
                    onOpenHolding = { navController.navigate(Routes.holding(it)) },
                    onOpenAccount = { navController.navigate(Routes.account(it)) },
                    onOpenDividends = {
                        // Navigate to Dividends, keeping Portfolio on the back stack.
                        // This means pressing back (or the back arrow we add) returns here.
                        navController.navigate(Routes.DIVIDENDS)
                    },
                    onAddHolding = {
                        // Positions are built from transactions now, so the
                        // primary "+" records a buy rather than typing a
                        // position size straight in. The old Add-holding form
                        // is still reachable for editing an existing holding.
                        // No account is invented here any more: the form opens
                        // on whichever account exists, or on none, and refuses
                        // to save until one is chosen.
                        navController.navigate(Routes.addTransaction(viewModel.defaultAccount()))
                    }
                )
            }
            composable(
                Routes.ACCOUNT,
                arguments = listOf(navArgument("accountId") { type = NavType.LongType })
            ) { backStackEntry ->
                val accountId = backStackEntry.arguments?.getLong("accountId") ?: 0L
                AccountScreen(
                    viewModel = viewModel,
                    accountId = accountId,
                    onOpenHolding = { navController.navigate(Routes.holding(it)) },
                    onAddHolding = { navController.navigate(Routes.addTransaction(accountId)) },
                    onBack = { navController.popBackStack() },
                    onAccountDeleted = { navController.popBackStack(Routes.PORTFOLIO, false) }
                )
            }
            composable(
                Routes.HOLDING,
                arguments = listOf(navArgument("holdingId") { type = NavType.LongType })
            ) { backStackEntry ->
                val holdingId = backStackEntry.arguments?.getLong("holdingId") ?: 0L
                HoldingScreen(
                    viewModel = viewModel,
                    holdingId = holdingId,
                    onBack = { navController.popBackStack() },
                    onAddDividend = { navController.navigate(Routes.addDividend(holdingId)) },
                    onEditHolding = { navController.navigate(Routes.editHolding(holdingId)) },
                    onDeleted = { navController.popBackStack() },
                    onOpenBreakdown = { key ->
                        navController.navigate(Routes.accountBreakdown(key))
                    }
                )
            }
            composable(
                Routes.ADD_HOLDING,
                arguments = listOf(navArgument("accountId") { type = NavType.LongType })
            ) { backStackEntry ->
                val accountId = backStackEntry.arguments?.getLong("accountId") ?: 0L
                ManualHoldingScreen(
                    viewModel = viewModel,
                    accountId = accountId,
                    editingHoldingId = null,
                    onDone = { navController.popBackStack() },
                    onOpenPremium = { navController.navigate(Routes.PREMIUM) }
                )
            }
            composable(
                Routes.EDIT_HOLDING,
                arguments = listOf(navArgument("holdingId") { type = NavType.LongType })
            ) { backStackEntry ->
                val holdingId = backStackEntry.arguments?.getLong("holdingId") ?: 0L
                ManualHoldingScreen(
                    viewModel = viewModel,
                    accountId = null,
                    editingHoldingId = holdingId,
                    onDone = { navController.popBackStack() },
                    onOpenPremium = { navController.navigate(Routes.PREMIUM) }
                )
            }
            composable(
                Routes.ACCOUNT_BREAKDOWN,
                arguments = listOf(navArgument("securityKey") { type = NavType.StringType })
            ) { backStackEntry ->
                val encoded = backStackEntry.arguments?.getString("securityKey") ?: ""
                AccountBreakdownScreen(
                    viewModel = viewModel,
                    securityKey = URLDecoder.decode(encoded, "UTF-8"),
                    onBack = { navController.popBackStack() },
                    onOpenHolding = { navController.navigate(Routes.holding(it)) }
                )
            }
            composable(Routes.DIVIDENDS) {
                // Determine if there's a Portfolio entry in the back stack — if so,
                // show a back arrow so the user can return to My Portfolio directly.
                val previousRoute = navController.previousBackStackEntry?.destination?.route
                val showBack = previousRoute == Routes.PORTFOLIO

                DividendsScreen(
                    viewModel     = viewModel,
                    onOpenHolding = { navController.navigate(Routes.holding(it)) },
                    onAddDividend = { navController.navigate(Routes.addDividend(it)) },
                    onBack        = if (showBack) ({ navController.popBackStack() }) else null
                )
            }
            composable(
                Routes.ADD_DIVIDEND,
                arguments = listOf(navArgument("holdingId") { type = NavType.LongType })
            ) { backStackEntry ->
                val holdingId = backStackEntry.arguments?.getLong("holdingId") ?: 0L
                AddDividendScreen(
                    viewModel = viewModel,
                    holdingId = holdingId,
                    onDone = { navController.popBackStack() }
                )
            }
            composable(Routes.REPORTS) {
                ReportsScreen(
                    viewModel = viewModel,
                    onOpenPremium = { navController.navigate(Routes.PREMIUM) }
                )
            }
            composable(Routes.MENU) {
                MenuScreen(
                    viewModel          = viewModel,
                    onOpenSettings     = { navController.navigate(Routes.SETTINGS) },
                    onOpenMarkets      = { navController.navigate(Routes.DASHBOARD) },
                    onOpenPrivacyPolicy = { navController.navigate(Routes.PRIVACY_POLICY) },
                    onOpenTaxHelp = { navController.navigate(Routes.TAX_HELP) },
                    onOpenTaxSettings = { navController.navigate(Routes.TAX_SETTINGS) },
                    onOpenBugReport    = { navController.navigate(Routes.BUG_REPORT) },
                    // Route to login if not signed in, profile if already signed in.
                    onOpenProfile      = {
                        if (FirebaseManager.currentUser != null) {
                            navController.navigate(Routes.PROFILE)
                        } else {
                            navController.navigate(Routes.LOGIN)
                        }
                    },
                    onOpenAdminReports = { navController.navigate(Routes.ADMIN_REPORTS) },
                    onOpenPremium      = { navController.navigate(Routes.PREMIUM) },
                    onOpenAlerts       = { navController.navigate(Routes.ALERTS) }
                )
            }
            composable(
                Routes.NEWS_ARTICLE,
                arguments = listOf(navArgument("encodedUrl") { type = NavType.StringType })
            ) { backStackEntry ->
                val encodedUrl = backStackEntry.arguments?.getString("encodedUrl") ?: ""
                val url = URLDecoder.decode(encodedUrl, "UTF-8")
                InAppBrowserScreen(url = url, onBack = { navController.popBackStack() })
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    viewModel = viewModel,
                    activity = activity,
                    onBack = { navController.popBackStack() },
                    onOpenTransactions = { navController.navigate(Routes.TRANSACTIONS) }
                )
            }
            composable(Routes.TRANSACTIONS) {
                TransactionHistoryScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() }
                )
            }
            composable(
                Routes.ADD_TRANSACTION,
                arguments = listOf(
                    navArgument("accountId") { type = NavType.LongType; defaultValue = -1L },
                    navArgument("holdingId") { type = NavType.LongType; defaultValue = -1L }
                )
            ) { entry ->
                val acct = entry.arguments?.getLong("accountId")?.takeIf { it > 0 }
                // Opened from a holding: the form starts on that security and
                // that account rather than on an empty picker.
                val preset = entry.arguments?.getLong("holdingId")?.takeIf { it > 0 }
                AddTransactionScreen(
                    viewModel = viewModel,
                    accountId = acct,
                    presetHoldingId = preset,
                    onDone = { navController.popBackStack() },
                    onOpenPremium = { navController.navigate(Routes.PREMIUM) }
                )
            }

            // ── Privacy Policy ────────────────────────────────────────────
            composable(Routes.PRIVACY_POLICY) {
                PrivacyPolicyScreen(onBack = { navController.popBackStack() })
            }

            // ── Taxes ─────────────────────────────────────────────────────
            composable(Routes.TAX_SETTINGS) {
                ca.tristan.portfolio.ui.screens.TaxSettingsScreen(
                    viewModel = viewModel,
                    onOpenHelp = { navController.navigate(Routes.TAX_HELP) },
                    onBack = { navController.popBackStack() }
                )
            }

            // ── How tax works ─────────────────────────────────────────────
            composable(Routes.TAX_HELP) {
                ca.tristan.portfolio.ui.screens.TaxHelpScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() }
                )
            }

            // ── Login ─────────────────────────────────────────────────────
            composable(Routes.LOGIN) {
                LoginScreen(
                    activity = activity,
                    onAuthenticated = { navController.popBackStack() }
                )
            }

            // ── Alerts ────────────────────────────────────────────────────
            composable(Routes.ALERTS) {
                AlertsScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() },
                    onOpenPremium = { navController.navigate(Routes.PREMIUM) }
                )
            }

            // ── Premium / subscription ────────────────────────────────────
            composable(Routes.PREMIUM) {
                ca.tristan.portfolio.ui.screens.PremiumScreen(
                    onBack = { navController.popBackStack() }
                )
            }

            // ── Profile / Account ─────────────────────────────────────────
            composable(Routes.PROFILE) {
                ProfileScreen(
                    onBack = { navController.popBackStack() },
                    onSignedOut = {
                        // Pop back to menu; auth gate will show login on next open
                        navController.popBackStack()
                    }
                )
            }

            // ── Bug Report / Contact Support ──────────────────────────────
            composable(Routes.BUG_REPORT) {
                BugReportScreen(onBack = { navController.popBackStack() })
            }

            // ── Admin: Bug Reports (admin account only) ───────────────────
            composable(Routes.ADMIN_REPORTS) {
                AdminReportsScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
