import Foundation
import UserNotifications

/// Ex-dividend reminders handed to iOS IN ADVANCE, so they arrive whether or
/// not the app gets to run.
///
/// The alert engine can only notify when something runs it — the app being
/// opened, or a `BGAppRefreshTask` iOS may honour hours late or never (it
/// never does for an app the user has swiped away, and rarely for one
/// installed from Xcode). For a price rule nothing better is possible without
/// a server: the price is only known by asking. An ex-dividend rule is
/// different — its answer is a DATE that is known days ahead — so once a pass
/// has seen the date, the reminder is scheduled with the system as a calendar
/// notification and iOS delivers it on the morning the window opens, app
/// running or not.
///
/// The engine's own "within the window" firing still happens when a pass runs
/// inside the window; `shouldSuppress` keeps the two from announcing the same
/// ex-date twice.
enum ExDividendReminders {

    private static let prefix = "wealthboard.exdiv."
    private static let recordKey = "wealthboard.exdiv.scheduled"

    /// Local time the reminder is delivered on the first day of the window.
    private static let deliveryHour = 9

    private static func identifier(for alertId: UUID) -> String {
        prefix + alertId.uuidString
    }

    /// What was handed to iOS for each alert: which ex-date, and when it fires.
    private struct Record: Codable {
        let exDay: Int
        let fireAt: Date
    }

    private static func loadRecords() -> [String: Record] {
        guard let data = UserDefaults.standard.data(forKey: recordKey),
              let decoded = try? JSONDecoder().decode([String: Record].self, from: data) else { return [:] }
        return decoded
    }

    private static func saveRecords(_ records: [String: Record]) {
        guard let data = try? JSONEncoder().encode(records) else { return }
        UserDefaults.standard.set(data, forKey: recordKey)
    }

    private static func dayKey(_ date: Date) -> Int {
        let c = Calendar.current.dateComponents([.year, .month, .day], from: date)
        return (c.year ?? 0) * 10_000 + (c.month ?? 1) * 100 + (c.day ?? 1)
    }

    /// Re-plans every ex-dividend reminder from the next ex-dates just seen.
    ///
    /// `upcoming` maps a ticker to its next distribution. Alerts that are gone,
    /// disabled, or whose window has already opened lose their pending
    /// reminder; the rest get one at `deliveryHour` on the window's first day.
    static func reschedule(alerts: [PriceAlert], upcoming: [String: UpcomingDividend], now: Date = Date()) async {
        let center = UNUserNotificationCenter.current()
        var records = loadRecords()
        let calendar = Calendar.current
        let startOfToday = calendar.startOfDay(for: now)

        let wanted = alerts.filter { $0.enabled && $0.kind == .exDividendWithinDays }
        let wantedIds = Set(wanted.map { identifier(for: $0.id) })

        // Reminders for alerts that no longer exist, or were switched off.
        let pending = await center.pendingNotificationRequests()
        let stale = pending.map(\.identifier).filter { $0.hasPrefix(prefix) && !wantedIds.contains($0) }
        if !stale.isEmpty { center.removePendingNotificationRequests(withIdentifiers: stale) }
        records = records.filter { wantedIds.contains($0.key) }

        for alert in wanted {
            let id = identifier(for: alert.id)
            // Latched means the engine has already announced this window.
            if alert.triggeredAt != nil {
                center.removePendingNotificationRequests(withIdentifiers: [id])
                continue
            }
            guard let next = upcoming[alert.ticker], let exDate = next.exDividendDate else { continue }
            let exDay = calendar.startOfDay(for: exDate)
            guard exDay >= startOfToday else { continue }

            let windowDays = max(Int(alert.threshold.rounded(.down)), 0)
            guard let windowStart = calendar.date(byAdding: .day, value: -windowDays, to: exDay),
                  let fireAt = calendar.date(
                      bySettingHour: deliveryHour, minute: 0, second: 0, of: max(windowStart, startOfToday)
                  ) else { continue }

            // Already in the window and past today's delivery hour: the engine
            // pass that is running right now is the notification.
            guard fireAt > now else { continue }

            // Unchanged plan — leave the pending request alone.
            if let existing = records[id], existing.exDay == dayKey(exDay), existing.fireAt == fireAt,
               pending.contains(where: { $0.identifier == id }) {
                continue
            }

            let days = calendar.dateComponents([.day], from: calendar.startOfDay(for: fireAt), to: exDay).day ?? 0
            let content = UNMutableNotificationContent()
            content.title = "\(alert.ticker) ex-dividend coming up"
            let when = days <= 0 ? "today" : (days == 1 ? "tomorrow" : "in \(days) days")
            let expected = next.isAnnounced ? "" : " (expected — not yet announced by the fund)"
            content.body = days <= 0
                ? "\(alert.ticker) goes ex-dividend today — units bought from today on don't receive this payment.\(expected)"
                : "\(alert.ticker) goes ex-dividend \(when) — buy before then to receive this payment.\(expected)"
            content.sound = .default
            content.threadIdentifier = "wealthboard.alerts.\(alert.ticker)"
            if #available(iOS 15.0, *) {
                content.interruptionLevel = .timeSensitive
            }

            let components = calendar.dateComponents([.year, .month, .day, .hour, .minute], from: fireAt)
            let request = UNNotificationRequest(
                identifier: id,
                content: content,
                trigger: UNCalendarNotificationTrigger(dateMatching: components, repeats: false)
            )
            do {
                try await center.add(request)
                records[id] = Record(exDay: dayKey(exDay), fireAt: fireAt)
            } catch {
                records[id] = nil
            }
        }
        saveRecords(records)
    }

    /// Whether the engine's firing for this alert repeats a reminder iOS has
    /// already delivered for the same ex-date. The engine still LATCHES the
    /// alert either way; this only decides whether to post a second banner.
    static func shouldSuppress(_ firing: AlertFiring, now: Date = Date()) -> Bool {
        guard let record = loadRecords()[identifier(for: firing.alertId)] else { return false }
        let calendar = Calendar.current
        guard let exDate = calendar.date(
            byAdding: .day, value: Int(firing.value), to: calendar.startOfDay(for: now)
        ) else { return false }
        return record.exDay == dayKey(exDate) && record.fireAt <= now
    }

    /// Drops the pending reminder for an alert the engine has just announced.
    static func cancel(alertId: UUID) {
        UNUserNotificationCenter.current()
            .removePendingNotificationRequests(withIdentifiers: [identifier(for: alertId)])
    }

    /// Everything off — for when alerts stop being entitled.
    static func cancelAll() async {
        let center = UNUserNotificationCenter.current()
        let ids = await center.pendingNotificationRequests().map(\.identifier).filter { $0.hasPrefix(prefix) }
        if !ids.isEmpty { center.removePendingNotificationRequests(withIdentifiers: ids) }
        UserDefaults.standard.removeObject(forKey: recordKey)
    }
}
