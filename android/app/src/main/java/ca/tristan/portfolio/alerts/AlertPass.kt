package ca.tristan.portfolio.alerts

import android.content.Context
import ca.tristan.portfolio.billing.Subscriptions
import ca.tristan.portfolio.data.PortfolioRepository
import ca.tristan.portfolio.data.db.AlertKind
import ca.tristan.portfolio.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/**
 * One alert evaluation pass, shared by the background worker and the app's
 * own foreground loop so the two cannot drift — and cannot overlap.
 *
 * The pass used to be written out twice, once in SyncWorker and once in a
 * launch-only LaunchedEffect. Beyond the duplication, nothing stopped the two
 * running at the same moment: both would read the same unlatched rule, both
 * would fire it, and the user got the same notification twice. A pass that
 * finds another already in flight now simply returns.
 */
object AlertPass {

    private val mutex = Mutex()

    /**
     * Evaluates whichever rule families [AlertGate] says are live and posts
     * what fired. Swallows failures: an alert problem must never fail the
     * caller (the worker's quote refresh, or the UI).
     */
    suspend fun run(context: Context) {
        if (!mutex.tryLock()) return
        try {
            withContext(Dispatchers.IO) { runLocked(context) }
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun runLocked(context: Context) {
        val app = context.applicationContext

        // Premium-gated here rather than only on the screen that creates
        // alerts: a lapsed subscriber keeps their saved rules, but stops
        // being notified until they resubscribe. The CACHED entitlement,
        // because a worker's process may never have shown a screen.
        if (!Subscriptions.cachedIsPremium(app)) return
        if (!AlertNotifier.canPost(app)) return

        runCatching {
            val db = AppDatabase.get(app)
            val repo = PortfolioRepository(
                db.accountDao(), db.holdingDao(), db.priceSnapshotDao(),
                db.dividendDao(), db.watchlistDao(), db.transactionDao(), db.alertDao()
            )
            val dao = db.alertDao()
            val enabled = dao.enabled()
            if (enabled.isEmpty()) return@runCatching

            // Which families are worth evaluating right now, decided from
            // the tickers the user's own rules mention — so an alert on a
            // coin keeps being checked overnight while an alert on an ETF
            // does not.
            val kinds = AlertGate.kindsFor(app, enabled.map { it.ticker })
            if (kinds.isEmpty()) return@runCatching

            val firings = AlertEngine.evaluate(dao, repo, kinds = kinds)

            // Marked only after the pass that actually evaluated them, so
            // a check is not consumed by a pass that threw.
            if (AlertKind.EX_DIVIDEND_WITHIN_DAYS in kinds) {
                AlertGate.markExDividendChecked(app)
            }

            for (firing in firings) {
                AlertNotifier.post(app, firing)
            }
        }
    }
}
