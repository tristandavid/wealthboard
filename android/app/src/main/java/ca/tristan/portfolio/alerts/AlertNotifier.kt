package ca.tristan.portfolio.alerts

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Puts a fired alert in front of the user.
 *
 * Separate from [AlertEngine] so the decision and the delivery can go wrong
 * independently: a notification that fails to post (permission revoked,
 * channel blocked) must not stop the engine from latching the alert, or the
 * same alert would re-fire on every single background pass for as long as the
 * condition held.
 */
object AlertNotifier {

    private const val CHANNEL_ID = "wealthboard_alerts"

    /**
     * Notification ids are derived from the ALERT id rather than incremented.
     *
     * Two firings of the same alert should replace each other in the shade
     * rather than stack: the second one is the current truth about that
     * security, and a column of near-identical rows for one ticker is how a
     * user learns to swipe the whole app's notifications away.
     *
     * Offset so these cannot collide with any other notification the app might
     * post later from an unrelated feature.
     */
    private const val ID_BASE = 40_000

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Price and dividend alerts",
            // HIGH, not DEFAULT: the user explicitly asked to be told about
            // this specific thing at this specific number. An alert that
            // arrives silently in the shade is one they find hours later,
            // which for a price threshold is the same as not sending it.
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Fires when a price target, daily move or ex-dividend date you set is reached."
        }
        context.getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(channel)
    }

    /** Whether the app may actually post. False on 13+ until the user agrees. */
    fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Where a posted notification would actually turn up.
     *
     * [canPost] answers a narrower question than it looks like it does: it
     * tests the app-level permission alone. A CHANNEL can be blocked
     * independently — long-press a notification, "turn off notifications for
     * this category", and the app-level switch stays on while this app's
     * alerts are silently dropped by the system. The post succeeds, throws
     * nothing, and is shown nowhere.
     *
     * So this reports the two separately. Nothing gates on it; it exists so
     * the developer row can tell the user which of two very different problems
     * they have.
     *
     * Mirrors `AlertNotifier.deliverability()` on iOS.
     */
    data class Deliverability(
        /** App-level permission — the thing [canPost] tests. */
        val authorized: Boolean,
        /** Whether the alerts channel itself is allowed to show anything. */
        val hasVisibleDestination: Boolean,
        /** Human-readable account of what is switched off, empty when fine. */
        val summary: String
    )

    fun deliverability(context: Context): Deliverability {
        if (!canPost(context)) {
            return Deliverability(
                authorized = false,
                hasVisibleDestination = false,
                summary = "Notifications are not permitted for WealthBoard."
            )
        }

        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = manager?.getNotificationChannel(CHANNEL_ID)

        // A channel that does not exist yet is not blocked — it is created on
        // the first post, at the importance this app asks for. Absent means
        // "fine", not "broken".
        val blocked = channel != null &&
            channel.importance == NotificationManager.IMPORTANCE_NONE

        return Deliverability(
            authorized = true,
            hasVisibleDestination = !blocked,
            summary = if (blocked) {
                "Allowed, but the Alerts notification category is turned off — " +
                    "notifications are delivered nowhere visible."
            } else {
                ""
            }
        )
    }

    fun post(context: Context, firing: AlertFiring) {
        if (!canPost(context)) return
        ensureChannel(context)

        // Opens the app rather than deep-linking to the Alerts screen: the
        // thing someone wants after "VDY hit $40" is the holding and the
        // market, not the rule that told them.
        val launch = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?.apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP }

        val pending = launch?.let {
            PendingIntent.getActivity(
                context,
                firing.alertId.toInt(),
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(ca.tristan.portfolio.R.drawable.ic_notification)
            .setContentTitle(firing.title)
            .setContentText(firing.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(firing.body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .apply { if (pending != null) setContentIntent(pending) }
            .build()

        // Wrapped: the permission can be revoked between canPost() and here,
        // and a SecurityException thrown out of a background worker would fail
        // the whole refresh pass, taking the quote sync down with it.
        runCatching {
            NotificationManagerCompat.from(context)
                .notify(ID_BASE + firing.alertId.toInt(), notification)
        }
    }
}
