package ca.tristan.portfolio.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import ca.tristan.portfolio.BuildConfig
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * What the app should do about its own version.
 *
 * `Blocked` is the only state that stops the user, and it is deliberately hard
 * to reach: see [UpdateGate.check] for why a config that cannot be read never
 * produces it.
 */
sealed interface UpdateStatus {
    /** Nothing to say. */
    object UpToDate : UpdateStatus

    /** Newer build exists; the user may carry on. Shown once, dismissible. */
    data class Optional(val message: String?, val storeUrl: String?) : UpdateStatus

    /** This build may no longer be used. Full-screen, no way past it. */
    data class Blocked(val message: String?, val storeUrl: String?) : UpdateStatus
}

/**
 * Forces an update when a shipped build has to be retired.
 *
 * The version floor lives in Firestore rather than in the app, which is the
 * whole point: a build that is already on someone's phone is the build that
 * needs retiring, and it cannot be told to retire itself by shipping another
 * one — the people who would get that release are exactly the people who are
 * not the problem. One number changed in the console reaches every install.
 *
 * Reserved for the cases that actually warrant it: a version that corrupts
 * data, one that hammers a provider hard enough to get the whole app
 * rate-limited, a security fix. Forcing an update is taking someone's app away
 * until they act, and a build that merely has a nicer Reports screen has not
 * earned that.
 *
 * The document, at `config/app_version`:
 * ```
 * {
 *   "minimumBuildAndroid": 14,   // below this, Blocked
 *   "latestBuildAndroid":  15,   // below this, Optional
 *   "message":             "…",  // optional, shown on the screen
 *   "storeUrlAndroid":     "…"   // optional; defaults to this app's Play page
 * }
 * ```
 * It has to be readable WITHOUT a signed-in user, since most installs have no
 * account — see the security rule quoted in `check`.
 */
object UpdateGate {

    private const val PREFS_NAME = "update_gate"
    private const val KEY_MINIMUM = "minimum_build"
    private const val KEY_LATEST = "latest_build"
    private const val KEY_MESSAGE = "message"
    private const val KEY_STORE_URL = "store_url"
    private const val KEY_OPTIONAL_SEEN_FOR = "optional_seen_for"

    private const val COLLECTION = "config"
    private const val DOCUMENT = "app_version"

    /**
     * Reads the floor and decides.
     *
     * FAILS OPEN, always. Every failure path here — no network, Firestore
     * down, the document missing, a field of the wrong type, the Firebase SDK
     * throwing on a build with no config — returns [UpdateStatus.UpToDate].
     *
     * That is not defensiveness, it is the single most important property of
     * this feature. A gate that fails CLOSED turns any backend hiccup into
     * every user on every version being locked out of a portfolio the app
     * already has on disk and can display perfectly well offline. The failure
     * mode of forcing an update wrongly is far worse than the failure mode of
     * missing one.
     *
     * The Firestore rule this needs, since most installs are not signed in:
     * ```
     * match /config/{document} {
     *   allow read: if true;
     *   allow write: if false;   // console only
     * }
     * ```
     */
    suspend fun check(context: Context): UpdateStatus = withContext(Dispatchers.IO) {
        val current = BuildConfig.VERSION_CODE
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val fetched = runCatching {
            FirebaseFirestore.getInstance()
                .collection(COLLECTION)
                .document(DOCUMENT)
                .get()
                .await()
                .data
        }.getOrNull()

        if (fetched != null) {
            // Cached so the decision survives a later launch with no network.
            // A floor that has been read once is a fact about this build, and
            // going quiet the moment the phone is in a tunnel would make the
            // retirement trivially avoidable by turning off wifi.
            prefs.edit()
                .putLong(KEY_MINIMUM, longOf(fetched["minimumBuildAndroid"]) ?: 0L)
                .putLong(KEY_LATEST, longOf(fetched["latestBuildAndroid"]) ?: 0L)
                .putString(KEY_MESSAGE, fetched["message"] as? String)
                .putString(KEY_STORE_URL, fetched["storeUrlAndroid"] as? String)
                .apply()
        }

        // Nothing fetched and nothing cached means this app has never
        // successfully read a floor — so there is no floor, and the answer is
        // "carry on".
        val minimum = prefs.getLong(KEY_MINIMUM, 0L)
        val latest = prefs.getLong(KEY_LATEST, 0L)
        val message = prefs.getString(KEY_MESSAGE, null)
        val storeUrl = prefs.getString(KEY_STORE_URL, null)

        when {
            minimum > 0 && current < minimum -> UpdateStatus.Blocked(message, storeUrl)

            latest > 0 && current < latest -> {
                // Once per newer build, not once per launch. A nag that
                // returns every time the app opens is one people learn to
                // dismiss without reading, which is also how they dismiss the
                // one that matters.
                if (prefs.getLong(KEY_OPTIONAL_SEEN_FOR, 0L) >= latest) {
                    UpdateStatus.UpToDate
                } else {
                    UpdateStatus.Optional(message, storeUrl)
                }
            }

            else -> UpdateStatus.UpToDate
        }
    }

    /** Remembers that the optional prompt for the current [latest] was shown. */
    fun markOptionalSeen(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putLong(KEY_OPTIONAL_SEEN_FOR, prefs.getLong(KEY_LATEST, 0L))
            .apply()
    }

    /**
     * Opens this app's Play Store page.
     *
     * `market://` first so the Play app handles it directly; the https form is
     * the fallback for a device without Play (or with it disabled), where the
     * market scheme resolves to nothing and the button would otherwise do
     * visibly nothing at all.
     */
    fun openStore(context: Context, storeUrl: String?) {
        val package_ = context.packageName
        val candidates = listOfNotNull(
            storeUrl,
            "market://details?id=$package_",
            "https://play.google.com/store/apps/details?id=$package_"
        )
        for (url in candidates) {
            val opened = runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                true
            }.getOrDefault(false)
            if (opened) return
        }
    }

    /**
     * Firestore hands numbers back as Long, Double or String depending on how
     * they were typed into the console. Reading only Long meant a floor
     * entered as `15.0` — which the console does if you type it into a number
     * field — silently read as absent, and the gate never fired.
     */
    private fun longOf(value: Any?): Long? = when (value) {
        is Long -> value
        is Int -> value.toLong()
        is Double -> value.toLong()
        is String -> value.trim().toDoubleOrNull()?.toLong()
        else -> null
    }
}
