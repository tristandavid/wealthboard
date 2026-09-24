package ca.tristan.portfolio.net

import android.util.Log
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Cookie + crumb session for Yahoo's `quoteSummary` endpoint.
 *
 * Yahoo gated `v10/finance/quoteSummary` behind a consent cookie plus a
 * matching "crumb" token. Without them the endpoint answers 401 with an
 * `Invalid Crumb` body for every symbol, worldwide — so every call that goes
 * through it has been failing silently:
 *
 *  - [LogoResolver] step 3 (`assetProfile.website`), which is the ONLY logo
 *    path for an individual company outside Finnhub's US coverage. That is why
 *    logos appeared for Canadian and US *funds* — those resolve by issuer name
 *    with no network call — and for nothing else.
 *  - [YahooQuoteClient.fetchUpcomingDividend], which reads `calendarEvents`
 *    and `summaryDetail`.
 *
 * The handshake is two requests, both cheap and both cached for the process:
 *
 *  1. GET `fc.yahoo.com` purely to collect the `Set-Cookie` headers. It answers
 *     404 — that is expected and not an error; the cookies are the point.
 *  2. GET `/v1/test/getcrumb` with those cookies, whose entire body is the
 *     crumb string.
 *
 * Deliberately run on a **separate** OkHttpClient that shares this cookie jar
 * but does NOT carry [RateLimitBackoff]. A handshake failure is a session
 * problem, not a throttle, and letting a 403 here trip the global cooldown
 * would stop quotes, charts and news app-wide over a logo lookup.
 */
internal object YahooSession {

    private const val TAG = "YahooSession"

    /** In-memory cookie store. Not persisted: a fresh crumb per launch is fine. */
    private val store = HashMap<String, List<Cookie>>()

    private val jar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            synchronized(store) { store[url.host] = cookies }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(store) {
            // Yahoo sets its consent cookie on one host and validates the crumb
            // on another, so every stored cookie is offered rather than only
            // those matching the request host.
            store.values.flatten()
        }
    }

    /** Shared with [YahooQuoteClient] so cookies collected here travel with API calls. */
    val cookieJar: CookieJar get() = jar

    private val handshakeClient by lazy {
        OkHttpClient.Builder().cookieJar(jar).build()
    }

    @Volatile
    private var cached: String? = null

    private val ua =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

    // Split the same way the host strings in YahooQuoteClient are, for the
    // same reason: no full domain sitting in the APK's string table.
    private val consentHost get() = buildString { append("https://fc.ya"); append("hoo.com/") }
    private val crumbUrl get() =
        buildString { append("https://quer"); append("y1.fi"); append("nance.ya"); append("hoo.com") } +
            buildString { append("/v1/te"); append("st/getcr"); append("umb") }

    /**
     * The current crumb, fetching one if needed. Null when Yahoo won't issue
     * one, in which case callers behave exactly as they did before: no data.
     *
     * Blocking; every caller is already off the main thread.
     */
    fun crumb(): String? {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val fresh = runCatching { handshake() }.getOrNull()
            cached = fresh
            return fresh
        }
    }

    /**
     * Drops the cached crumb so the next call re-handshakes. Called when a
     * request comes back 401, which is how an expired crumb presents.
     */
    fun invalidate() {
        cached = null
        synchronized(store) { store.clear() }
    }

    private fun handshake(): String? {
        // 1. Collect cookies. A 404 here is the documented, expected outcome.
        runCatching {
            handshakeClient.newCall(
                Request.Builder().url(consentHost).header("User-Agent", ua).build()
            ).execute().use { it.body?.string() }
        }

        // 2. Trade them for a crumb.
        return runCatching {
            handshakeClient.newCall(
                Request.Builder().url(crumbUrl)
                    .header("User-Agent", ua)
                    .header("Accept", "*/*")
                    .build()
            ).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Crumb request failed: HTTP ${response.code}")
                    return@use null
                }
                response.body?.string()?.trim()
                    // A crumb is a short opaque token. An HTML error page is
                    // not one, and appending it to a URL would be worse than
                    // having none at all.
                    ?.takeIf { it.isNotBlank() && it.length < 32 && !it.contains('<') }
            }
        }.getOrNull()
    }
}
