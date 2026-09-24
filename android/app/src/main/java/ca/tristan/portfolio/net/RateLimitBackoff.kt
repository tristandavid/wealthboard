package ca.tristan.portfolio.net

import android.util.Log
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import kotlin.math.min
import kotlin.random.Random

/**
 * Rate-limit backoff for the Yahoo endpoints.
 *
 * Yahoo's finance API is undocumented and unsupported, which makes Yahoo's
 * tolerance a single point of failure for the whole app: quotes, history,
 * dividends and news all come from it. Without backoff, a throttle turns into
 * a stampede — every screen keeps retrying at full rate, and a temporary 429
 * escalates into a longer block.
 *
 * Installed as an interceptor on the shared [okhttp3.OkHttpClient] rather than
 * wrapped around each call, because the client has around a dozen separate
 * request sites and any one of them missed would keep hammering on its own.
 *
 * Two behaviours matter:
 *
 * **Fail fast while cooling down.** Once rate-limited, requests are rejected
 * locally without touching the network. Letting them through would be the
 * stampede this exists to prevent.
 *
 * **Escalate, then recover.** Each consecutive rejection roughly doubles the
 * cooldown up to a ceiling; one success clears it. Jitter is added so that a
 * device that hit the limit alongside others doesn't retry in lockstep with
 * them.
 */
object RateLimitBackoff : Interceptor {

    private const val TAG = "RateLimitBackoff"

    /** First cooldown after a rejection. Doubles from here. */
    private const val BASE_COOLDOWN_MS = 30_000L

    /** Ceiling, so a long outage can still recover within a session. */
    private const val MAX_COOLDOWN_MS = 10 * 60_000L

    @Volatile
    private var cooldownUntilMs = 0L

    @Volatile
    private var consecutiveRejections = 0

    /** True while requests are being refused locally. */
    val isCoolingDown: Boolean
        get() = System.currentTimeMillis() < cooldownUntilMs

    /** Milliseconds until requests resume, or 0 when not cooling down. */
    val cooldownRemainingMs: Long
        get() = (cooldownUntilMs - System.currentTimeMillis()).coerceAtLeast(0L)

    override fun intercept(chain: Interceptor.Chain): Response {
        val now = System.currentTimeMillis()
        if (now < cooldownUntilMs) {
            // Callers already treat an IOException as "no data" and fall back
            // to cached values, so this surfaces the same way a network drop
            // does — without spending the request.
            throw IOException(
                "Rate-limited; retrying in ${(cooldownUntilMs - now) / 1000}s"
            )
        }

        val response = chain.proceed(chain.request())

        when (response.code) {
            // 429 is an explicit throttle. 403 from Yahoo usually means the
            // crumb/cookie session expired rather than a permissions problem,
            // and retrying without re-authenticating just burns requests that
            // were never going to succeed — so both back off.
            429, 403 -> {
                val retryAfter = response.header("Retry-After")?.toLongOrNull()?.times(1000)
                val escalated = min(
                    BASE_COOLDOWN_MS shl consecutiveRejections.coerceAtMost(5),
                    MAX_COOLDOWN_MS
                )
                // Server's own advice wins when it gives any.
                val base = retryAfter ?: escalated
                val jitter = Random.nextLong(0, base / 4 + 1)
                cooldownUntilMs = System.currentTimeMillis() + base + jitter
                consecutiveRejections++
                Log.w(
                    TAG,
                    "HTTP ${response.code} — backing off ${(base + jitter) / 1000}s " +
                        "(rejection #$consecutiveRejections)"
                )
            }

            else -> {
                if (response.isSuccessful && consecutiveRejections > 0) {
                    Log.d(TAG, "Recovered after $consecutiveRejections rejection(s)")
                    consecutiveRejections = 0
                    cooldownUntilMs = 0L
                }
            }
        }

        return response
    }
}
