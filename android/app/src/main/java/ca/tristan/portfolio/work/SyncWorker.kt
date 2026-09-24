package ca.tristan.portfolio.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ca.tristan.portfolio.alerts.AlertEngine
import ca.tristan.portfolio.alerts.AlertGate
import ca.tristan.portfolio.alerts.AlertNotifier
import ca.tristan.portfolio.data.MarketCalendar
import ca.tristan.portfolio.data.PortfolioRepository
import ca.tristan.portfolio.data.db.AlertKind
import ca.tristan.portfolio.data.db.AppDatabase
import ca.tristan.portfolio.data.db.HoldingType
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Background refresh of live ETF/stock quote prices for holdings the user
 * has entered manually, so values don't sit stale between app opens.
 *
 * Uses a flex window plus shuffled spacing rather than a fixed-interval
 * alarm, so requests don't arrive at a suspiciously regular cadence.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val db = AppDatabase.get(applicationContext)
            val repo = PortfolioRepository(db.accountDao(), db.holdingDao(), db.priceSnapshotDao(), db.dividendDao(), db.watchlistDao(), db.transactionDao(), db.alertDao())

            // The quote refresh and the alert pass are gated SEPARATELY.
            //
            // They used to share one early return, on the reasoning that a
            // rule is a function of a price that cannot have changed or of an
            // ex-dividend date that does not move hour to hour. The first half
            // holds. The second does not: "ex-dividend within 3 days" becomes
            // true because TODAY advanced, not because the date moved, so
            // bundling it with the refresh meant nothing was evaluated between
            // Friday's close and Monday's open — and an ex-date on the Monday
            // was announced on the Monday, after the last chance to buy in.
            //
            // So the refresh keeps its own gate, and the alert pass decides
            // per rule family. See AlertGate.
            if (shouldSyncNow(repo)) {
                repo.refreshAllQuotes()
            }

            evaluateAlerts(db, repo)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    /**
     * Fires any alert whose condition just became true.
     *
     * Premium-gated here rather than only on the screen that creates alerts:
     * someone whose subscription lapses keeps their saved rules — deleting
     * them would be destroying the user's own configuration over a billing
     * state — but stops being notified until they resubscribe. The rules are
     * waiting, intact, when they come back.
     *
     * Failures are swallowed on purpose. Letting an alert problem return
     * Result.retry() would re-run the whole worker — including the quote
     * refresh, which may well have already succeeded — for a feature that is
     * not the reason this worker exists.
     *
     * Runs whether or not the refresh above did: an ex-dividend rule has to be
     * evaluated on days the market never opens, and AlertGate is what decides
     * which families are live on this pass.
     */
    private suspend fun evaluateAlerts(
        db: AppDatabase,
        repo: PortfolioRepository
    ) {
        if (!ca.tristan.portfolio.billing.Subscriptions.cachedIsPremium(applicationContext)) return
        runCatching {
            val dao = db.alertDao()
            val enabled = dao.enabled()
            if (enabled.isEmpty()) return@runCatching

            // Which families are worth evaluating right now, decided from the
            // tickers the user's own rules mention — so an alert on a coin
            // keeps being checked overnight while an alert on an ETF does not.
            val kinds = AlertGate.kindsFor(applicationContext, enabled.map { it.ticker })
            if (kinds.isEmpty()) return@runCatching

            val firings = AlertEngine.evaluate(dao, repo, kinds = kinds)

            // Marked only after the pass that actually evaluated them, so a
            // day's single check is not consumed by a pass that threw.
            if (AlertKind.EX_DIVIDEND_WITHIN_DAYS in kinds) {
                AlertGate.markExDividendChecked(applicationContext)
            }

            for (firing in firings) {
                AlertNotifier.post(applicationContext, firing)
            }
        }
    }

    /**
     * Whether a refresh is worth making network calls for.
     *
     * Markets trade roughly 32 hours of the 168 in a week, so polling around
     * the clock spends about four fifths of its requests re-fetching prices
     * that cannot have changed. Against an undocumented endpoint whose
     * tolerance is the app's single point of failure, those are requests worth
     * not making.
     *
     * Crypto is the exception: it trades continuously, so a portfolio holding
     * any keeps syncing whatever the exchanges are doing.
     */
    private suspend fun shouldSyncNow(repo: PortfolioRepository): Boolean {
        if (MarketCalendar.isMarketOpen()) return true

        val holdsCrypto = runCatching {
            repo.observeHoldings().first().any { holding ->
                holding.type == HoldingType.CRYPTO ||
                    holding.ticker?.uppercase()?.let { t ->
                        t.contains("-USD") || t.contains("-BTC") || t.contains("-ETH")
                    } == true
            }
        }.getOrDefault(false)

        return holdsCrypto
    }

    companion object {
        private const val WORK_NAME = "quote_refresh"

        fun schedule(context: Context) {
            // 30-45 min flex window, jittered spacing rather than a fixed period
            val jitterMinutes = (0..10).random()
            val request = PeriodicWorkRequestBuilder<SyncWorker>(
                30L + jitterMinutes, TimeUnit.MINUTES,
                15L, TimeUnit.MINUTES
            ).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request
            )
        }
    }
}
