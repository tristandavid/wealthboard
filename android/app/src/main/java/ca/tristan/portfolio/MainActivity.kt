package ca.tristan.portfolio

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import ca.tristan.portfolio.security.AppLock
import ca.tristan.portfolio.ui.PortfolioApp
import ca.tristan.portfolio.ui.PortfolioViewModel
import ca.tristan.portfolio.ui.screens.SplashScreen
import ca.tristan.portfolio.ui.theme.ThemeMode
import ca.tristan.portfolio.ui.theme.WealthBoardTheme
import ca.tristan.portfolio.billing.Subscriptions
import ca.tristan.portfolio.work.SyncWorker
import kotlinx.coroutines.launch

// FragmentActivity (not ComponentActivity) because androidx.biometric's
// BiometricPrompt needs a FragmentActivity host for the app lock.
class MainActivity : FragmentActivity() {

    private val viewModel: PortfolioViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must be called before super.onCreate(): this is what swaps the
        // activity's theme from Theme.WealthBoard.Splash (set in the
        // manifest, so it's already showing at process start) back to
        // Theme.WealthBoard, and it has to happen before the window's
        // content view is touched. The OS splash it manages exits as soon as
        // the first frame is drawn below, handing off to the animated
        // Compose SplashScreen for the rest of the launch sequence.
        installSplashScreen()

        // Called before super.onCreate so the window is configured before any
        // view work happens.
        //
        // At targetSdk 36 edge-to-edge is enforced with no opt-out: the app
        // draws behind the status and navigation bars whether it asks to or
        // not. Opting in explicitly makes that a decision rather than
        // something inherited, and gives us a single place to change it.
        // Material3's Scaffold, TopAppBar and NavigationBar all apply their
        // own window insets, so the app's chrome should still sit clear of
        // the system bars.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        applySecureFlag()
        SyncWorker.schedule(applicationContext)

        // Connects Play Billing for the Premium subscription. Safe to call
        // before the products exist: with nothing set up in the Play Console
        // the client simply reports none, and the paywall says so rather than
        // offering buttons that cannot work.
        Subscriptions.start(this)

        // And actually ASK Play what the entitlement is, every launch.
        //
        // start() only builds the billing client; refresh() is what queries
        // purchases and writes the entitlement cache that background work
        // reads. It used to be called from exactly one place — the Premium
        // screen's LaunchedEffect — which meant the cache stayed false for
        // anyone who never opened the paywall, and SyncWorker's alert pass
        // therefore returned early every single time. Alerts could not fire
        // at all for a subscriber who had not recently visited that one
        // screen, and nothing about it looked broken.
        lifecycleScope.launch { runCatching { Subscriptions.refresh() } }

        // No ad network is wired up — see the removal note in ads/ (deleted)
        // and app/build.gradle.kts: the AdMob account behind this app was
        // disabled for invalid activity and Google's appeal decision is
        // final, so AdMob, and the UMP consent flow that existed only to
        // support it, were removed rather than left dead in the tree.

        setContent {
            // Collected rather than read once, so choosing a theme in Settings
            // repaints the app straight away instead of on next launch.
            val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
            val dark = when (themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            WealthBoardTheme(darkTheme = dark) {
                Surface(modifier = Modifier, color = MaterialTheme.colorScheme.background) {
                    // Shown once per process, on top of everything else,
                    // then swapped out for the real content — see
                    // SplashScreen.kt for the animation itself.
                    var showSplash by remember { mutableStateOf(true) }
                    if (showSplash) {
                        SplashScreen(onFinished = { showSplash = false })
                    } else {
                        PortfolioApp(viewModel = viewModel, activity = this)
                    }
                }
            }
        }
    }

    fun applySecureFlag() {
        if (AppLock.isEnabled(this) && !AppLock.allowScreenshots(this)) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}
