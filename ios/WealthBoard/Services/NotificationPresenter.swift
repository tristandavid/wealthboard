import Foundation
import UserNotifications

/// Shows alert notifications even while the app is open.
///
/// iOS does NOT display a banner for a notification that arrives while the
/// app is in the foreground unless the app says it should — without a
/// delegate the system delivers it silently to Notification Centre and the
/// user sees nothing at all.
///
/// That is exactly the case that matters here. `AlertRunner.run()` fires on
/// launch and on every foreground activation, so the most likely moment for
/// an alert to be produced is while the person is looking at the app. Without
/// this, every one of those notifications was posted successfully and shown
/// to nobody — the feature appearing completely dead while behaving exactly
/// as written.
///
/// Installed once, from `WealthBoardApp.init()`. The delegate has to be set
/// before the app finishes launching, for the same reason
/// `BGTaskScheduler.register` does.
final class NotificationPresenter: NSObject, UNUserNotificationCenterDelegate {

    static let shared = NotificationPresenter()

    private override init() { super.init() }

    func install() {
        UNUserNotificationCenter.current().delegate = self
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        // `.banner` and `.list` rather than only `.banner`: the banner is the
        // part the user sees now, the list entry is what they find later if
        // they were not looking at the screen at that second. A price alert
        // that vanishes after four seconds and leaves no trace is worse than
        // one that arrives quietly.
        //
        // `.sound` too — this is a threshold the person explicitly asked to be
        // told about, which is the definition of worth interrupting for.
        completionHandler([.banner, .list, .sound])
    }
}
