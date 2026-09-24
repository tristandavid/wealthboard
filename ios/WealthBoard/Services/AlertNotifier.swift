import Foundation
import UserNotifications

/// Puts a fired alert in front of the user.
///
/// Separate from `AlertEngine` so the decision and the delivery can go wrong
/// independently: a notification that fails to post (permission revoked,
/// Focus mode, a scheduling error) must not stop the engine from latching the
/// alert, or the same alert would re-fire on every background pass for as long
/// as the condition held.
enum AlertNotifier {

    /// Identifiers are derived from the ALERT id rather than being unique per
    /// firing. Two firings of the same alert replace each other in Notification
    /// Centre rather than stacking: the second is the current truth about that
    /// security, and a column of near-identical rows for one ticker is how a
    /// user learns to swipe the whole app's notifications away.
    private static func identifier(for alertId: UUID) -> String {
        "wealthboard.alert.\(alertId.uuidString)"
    }

    /// Whether the app may actually post right now.
    ///
    /// `.provisional` counts: a quietly-delivered notification still reaches
    /// Notification Centre, and treating it as "no permission" would make the
    /// Alerts screen nag someone who has already been getting alerts.
    static func canPost() async -> Bool {
        let settings = await UNUserNotificationCenter.current().notificationSettings()
        switch settings.authorizationStatus {
        case .authorized, .provisional, .ephemeral: return true
        case .denied, .notDetermined: return false
        @unknown default: return false
        }
    }

    /// Where a posted notification would actually turn up.
    ///
    /// `canPost` answers a narrower question than it looks like it does: it
    /// reads `authorizationStatus` alone, which stays `.authorized` even when
    /// the user has switched off every place a notification could appear.
    /// Lock Screen, Notification Centre and Banners are three INDEPENDENT
    /// toggles under Settings → Notifications → WealthBoard, and with all
    /// three off the post succeeds, returns no error, and is shown nowhere —
    /// indistinguishable, from inside the app, from an alert that never fired.
    ///
    /// So this reports the destinations separately. Nothing gates on it; it
    /// exists so the developer row and the Alerts screen can tell the user
    /// which of the two very different problems they have.
    struct Deliverability {
        /// Permission itself — the thing `canPost` tests.
        let authorized: Bool
        /// Whether a notification would appear anywhere the user would see it.
        let hasVisibleDestination: Bool
        /// Human-readable account of what is switched off, empty when fine.
        let summary: String
    }

    static func deliverability() async -> Deliverability {
        let settings = await UNUserNotificationCenter.current().notificationSettings()

        let authorized: Bool
        switch settings.authorizationStatus {
        case .authorized, .provisional, .ephemeral: authorized = true
        default: authorized = false
        }

        guard authorized else {
            return Deliverability(
                authorized: false,
                hasVisibleDestination: false,
                summary: "Notifications are not permitted for WealthBoard."
            )
        }

        let banner = settings.alertSetting == .enabled
        let lockScreen = settings.lockScreenSetting == .enabled
        let notificationCentre = settings.notificationCenterSetting == .enabled
        let visible = banner || lockScreen || notificationCentre

        var off: [String] = []
        if !banner { off.append("Banners") }
        if !lockScreen { off.append("Lock Screen") }
        if !notificationCentre { off.append("Notification Centre") }

        let summary: String
        if visible {
            summary = off.isEmpty ? "" : "Off: " + off.joined(separator: ", ") + "."
        } else {
            // The state this type exists for.
            summary = "Allowed, but Banners, Lock Screen and Notification Centre "
                + "are ALL off — notifications are delivered nowhere visible."
        }

        return Deliverability(
            authorized: true,
            hasVisibleDestination: visible,
            summary: summary
        )
    }

    /// Asks, once. Returns what the user decided — or what was already decided,
    /// since iOS answers a second request with the existing state rather than
    /// prompting again.
    @discardableResult
    static func requestPermission() async -> Bool {
        let granted = try? await UNUserNotificationCenter.current()
            .requestAuthorization(options: [.alert, .sound, .badge])
        return granted ?? false
    }

    static func post(_ firing: AlertFiring) async {
        guard await canPost() else { return }

        let content = UNMutableNotificationContent()
        content.title = firing.title
        content.body = firing.body
        content.sound = .default

        // Time Sensitive, so a threshold the person explicitly asked to be
        // told about is not held back by a Focus mode or bundled into a
        // Scheduled Summary and read an hour after the price moved. This is
        // the case the level exists for: actionable, perishable, and asked
        // for by name.
        //
        // Requires the `com.apple.developer.usernotifications.time-sensitive`
        // entitlement, which — like Sign in with Apple in this app's
        // entitlements file — must be added through Xcode ▸ Signing &
        // Capabilities ▸ + Capability ▸ Time Sensitive Notifications so the
        // App ID carries it. WITHOUT it this line is simply ignored and the
        // notification is delivered at `.active`, so it is safe to ship
        // before the capability is registered; it just does nothing until
        // then, and the "Time Sensitive Notifications" row stays absent from
        // Settings ▸ Notifications ▸ WealthBoard.
        if #available(iOS 15.0, *) {
            content.interruptionLevel = .timeSensitive
        }
        // Grouped by ticker, so three rules on the same security collapse into
        // one stack in Notification Centre rather than three separate threads.
        content.threadIdentifier = "wealthboard.alerts.\(firing.ticker)"

        // nil trigger means "deliver now". This runs from a background task
        // that is already awake, so there is nothing to schedule against.
        let request = UNNotificationRequest(
            identifier: identifier(for: firing.alertId),
            content: content,
            trigger: nil
        )

        // Swallowed on purpose: this is called in a loop over firings, and one
        // notification failing to schedule must not stop the rest — nor fail
        // the background task that is also refreshing quotes.
        try? await UNUserNotificationCenter.current().add(request)
    }
}
