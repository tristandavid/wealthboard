import Foundation
import BackgroundTasks

/// Runs the alert engine while the app is in the background.
///
/// iOS gives no equivalent of Android's periodic WorkManager job: a
/// `BGAppRefreshTask` is a *request* to be woken, and the system decides
/// whether and when to honour it based on how the person actually uses the
/// app. So this is best-effort by construction, and the Alerts screen says so
/// rather than implying a guaranteed half-hourly check.
///
/// The engine is also run on foreground activation, which is what makes the
/// feature dependable in practice: opening the app always evaluates, whatever
/// the scheduler decided overnight.
enum AlertBackgroundTask {

    /// Must match the `BGTaskSchedulerPermittedIdentifiers` entry in
    /// Info.plist exactly. A mismatch throws at registration on a debug build
    /// and silently never runs on a release one.
    static let identifier = "ca.tristan.wealthboard.alertrefresh"

    /// The earliest the system may wake us. A floor, not a schedule — iOS
    /// routinely waits much longer, and asking for a tighter one does not make
    /// it more likely, only more likely to be throttled.
    private static let minimumInterval: TimeInterval = 30 * 60

    /// Registered once, before the app finishes launching. Registering later
    /// than that is a hard error on iOS.
    static func register() {
        BGTaskScheduler.shared.register(
            forTaskWithIdentifier: identifier,
            using: nil
        ) { task in
            guard let refresh = task as? BGAppRefreshTask else {
                task.setTaskCompleted(success: false)
                return
            }
            handle(refresh)
        }
    }

    static func schedule() {
        let request = BGAppRefreshTaskRequest(identifier: identifier)
        request.earliestBeginDate = Date(timeIntervalSinceNow: minimumInterval)
        // Throws when the app is not entitled (the background mode missing
        // from Info.plist) or when too many requests are pending. Neither is
        // worth crashing over: the foreground pass still covers the user.
        try? BGTaskScheduler.shared.submit(request)
    }

    private static func handle(_ task: BGAppRefreshTask) {
        // Re-scheduled FIRST. A background task gets exactly one wake per
        // submitted request, so a path that returns early without submitting
        // the next one silently ends the feature until the app is next opened.
        schedule()

        let work = Task {
            await AlertRunner.run()
            task.setTaskCompleted(success: true)
        }

        // The system reclaims the task if we run long; cancelling here means
        // the in-flight network calls stop rather than the process being
        // killed outright, which is what earns future wakes.
        task.expirationHandler = { work.cancel() }
    }
}

/// Keeps evaluating alerts while the app is open.
///
/// Alerts used to be evaluated once when the app came to the foreground and
/// then never again until it left and came back — so a price that crossed a
/// target while the portfolio was open on screen produced nothing, and the
/// only way to get the notification was the developer "Check alerts now" row.
/// This re-runs the same pass on a timer for as long as the app is active.
/// `AlertGate` still decides what each pass evaluates, so outside market hours
/// a tick costs nothing.
@MainActor
enum AlertForegroundLoop {

    /// Two minutes: close enough to "live" for a threshold alert, far enough
    /// apart not to lean on the quote endpoint.
    private static let interval: UInt64 = 120

    private static var task: Task<Void, Never>?

    static func start() {
        guard task == nil else { return }
        task = Task { @MainActor in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: interval * 1_000_000_000)
                guard !Task.isCancelled else { break }
                await AlertRunner.run()
            }
        }
    }

    static func stop() {
        task?.cancel()
        task = nil
    }
}

/// One evaluation pass, from whichever context asked for it.
///
/// Shared by the background task and the foreground refresh so the two cannot
/// drift — the Premium check in particular, which must be identical or a
/// lapsed subscriber would get alerts from one path and not the other.
enum AlertRunner {

    /// True while a pass is in flight. The foreground loop, the scene-active
    /// hook and a background wake can all ask at once; two overlapping passes
    /// would evaluate the same unlatched rule twice and post it twice.
    @MainActor private static var isRunning = false

    @MainActor
    static func run() async {
        guard !isRunning else { return }
        isRunning = true
        defer { isRunning = false }

        // Premium-gated here rather than only on the screen that creates
        // alerts: someone whose subscription lapses keeps their saved rules —
        // deleting them would be destroying the user's own configuration over
        // a billing state — but stops being notified until they resubscribe.
        // The rules are waiting, intact, when they come back.
        //
        // The CACHED entitlement, not the live one. A background wake launches
        // the process without a scene, so nothing has called `refresh()` and
        // `SubscriptionSession.isPremium` is still its initial false however
        // long the user has been paying — reading it here would mean
        // background alerts never fire for anyone. `sync()` first so a
        // foreground run still picks up a purchase made moments ago.
        SubscriptionSession.shared.sync()
        let entitled = SubscriptionSession.shared.isPremium
            || SubscriptionSession.cachedIsPremium
        guard entitled else {
            // Reminders already handed to iOS would otherwise still arrive.
            await ExDividendReminders.cancelAll()
            return
        }

        guard await AlertNotifier.canPost() else { return }

        let repository = PortfolioRepository()
        let alerts = await repository.alerts()
        guard !alerts.isEmpty else {
            await ExDividendReminders.reschedule(alerts: [], upcoming: [:])
            return
        }

        // Which rule families are worth evaluating right now, decided from the
        // tickers the user's own rules mention — so an alert on a coin keeps
        // being checked overnight while an alert on an ETF does not, and an
        // ex-dividend rule is still evaluated on a Sunday. See AlertGate.
        let kinds = AlertGate.kinds(
            forTickers: alerts.filter { $0.enabled }.map { $0.ticker }
        )
        guard !kinds.isEmpty else { return }

        let (firings, changed, exDividends) = await AlertEngine.evaluate(
            alerts: alerts,
            repository: repository,
            kinds: kinds
        )

        // Marked only after the pass that actually evaluated them, so a day's
        // single check is not consumed by a pass that returned early.
        if kinds.contains(.exDividendWithinDays) {
            AlertGate.markExDividendChecked()
        }

        // State first, notifications second. If the process is suspended
        // between the two, an alert that was latched but not announced is a
        // missed notification; the other order would re-announce the same
        // firing on every pass forever.
        await repository.applyAlertState(changed)
        await post(firings, alerts: alerts)

        // Hand the next ex-dividend reminders to iOS while the dates are
        // fresh, from the latch state just written — see ExDividendReminders.
        if kinds.contains(.exDividendWithinDays) {
            await ExDividendReminders.reschedule(
                alerts: await repository.alerts(),
                upcoming: exDividends
            )
        }
    }

    /// Posts each firing — except an ex-dividend one iOS has already shown as
    /// a pre-scheduled reminder for the same date.
    @MainActor
    private static func post(_ firings: [AlertFiring], alerts: [PriceAlert]) async {
        let exDividendIds = Set(alerts.filter { $0.kind == .exDividendWithinDays }.map(\.id))
        for firing in firings {
            if exDividendIds.contains(firing.alertId) {
                let alreadyShown = ExDividendReminders.shouldSuppress(firing)
                ExDividendReminders.cancel(alertId: firing.alertId)
                if alreadyShown { continue }
            }
            await AlertNotifier.post(firing)
        }
    }

    /// Asks for notification permission if, and only if, there are alerts that
    /// would otherwise go undelivered.
    ///
    /// Permission used to be requested solely from the Alerts screen, on the
    /// reasoning that the person creating an alert is the person to ask. That
    /// misses the case where the rules arrived some other way — a restored
    /// backup, most obviously — leaving a device with live alerts, no
    /// authorization, and a `post` that drops every one of them in silence.
    ///
    /// Foreground only: `requestAuthorization` needs a running app, and asking
    /// during a background wake would either do nothing or surface a prompt
    /// with no context around it. Called from the scene-active path.
    ///
    /// Asks only when the status is `.notDetermined` — iOS answers a repeat
    /// request with the existing decision rather than re-prompting, so a user
    /// who said no is not nagged, they are left alone until they change it in
    /// Settings.
    @MainActor
    static func ensureNotificationPermission() async {
        let repository = PortfolioRepository()
        let alerts = await repository.alerts()
        guard alerts.contains(where: { $0.enabled }) else { return }
        guard await AlertNotifier.canPost() == false else { return }
        await AlertNotifier.requestPermission()
    }

    #if DEBUG
    /// What a developer-row pass found. Every field exists because its absence
    /// once made a real problem look like a non-problem.
    struct DebugOutcome {
        /// Alerts that fired on this pass.
        let fired: Int
        /// Whether the Premium gate let the pass run at all.
        let wasPremium: Bool
        /// How many enabled rules there were to evaluate. Zero is the case the
        /// old readout could not express: it reported "nothing fired", which
        /// reads as "your thresholds were not met" and actually meant "there
        /// was nothing on this device to check". Those need different fixes.
        let enabledAlerts: Int
        /// Whether notifications may actually be delivered. `post` drops
        /// silently without permission, so a pass could report firings that
        /// the user would never see — the one readout worse than no readout.
        let canNotify: Bool
        /// Whether a delivered notification would appear anywhere the user
        /// looks. Permission being granted is NOT the same question: with
        /// Banners, Lock Screen and Notification Centre all switched off, the
        /// post succeeds and is shown nowhere.
        let hasVisibleDestination: Bool
        /// What, if anything, is switched off — shown verbatim in the row.
        let deliverySummary: String
    }

    /// Runs a pass right now and reports what happened, for the Menu's
    /// developer row.
    ///
    /// Worth having rather than waiting for `BGAppRefreshTask`: iOS decides
    /// when to honour one, and in the simulator it effectively never does —
    /// so without this, testing an alert means either a real device and a lot
    /// of patience, or poking the scheduler from the Xcode debug console.
    ///
    /// Runs the SAME engine and notifier the real path uses, so a pass here
    /// proves the real path rather than a parallel one. It deliberately does
    /// NOT apply `AlertGate`: the point of the row is to test a rule now
    /// rather than to wait for the session it belongs to, and Android's
    /// equivalent row says the same in its subtitle.
    @MainActor
    static func debugRun() async -> DebugOutcome {
        SubscriptionSession.shared.sync()
        let entitled = SubscriptionSession.shared.isPremium
            || SubscriptionSession.cachedIsPremium

        // Reported rather than guarded on, so the row can say WHY nothing
        // arrived. `run()` returns early here; this does not, because
        // "evaluated fine, but iOS will not deliver it" is the single most
        // useful thing this row can tell you.
        let delivery = await AlertNotifier.deliverability()

        guard entitled else {
            return DebugOutcome(
                fired: 0,
                wasPremium: false,
                enabledAlerts: 0,
                canNotify: delivery.authorized,
                hasVisibleDestination: delivery.hasVisibleDestination,
                deliverySummary: delivery.summary
            )
        }

        let repository = PortfolioRepository()
        let alerts = await repository.alerts()
        let enabledCount = alerts.filter { $0.enabled }.count

        let (firings, changed, _) = await AlertEngine.evaluate(
            alerts: alerts,
            repository: repository
        )
        await repository.applyAlertState(changed)
        await post(firings, alerts: alerts)
        return DebugOutcome(
            fired: firings.count,
            wasPremium: true,
            enabledAlerts: enabledCount,
            canNotify: delivery.authorized,
            hasVisibleDestination: delivery.hasVisibleDestination,
            deliverySummary: delivery.summary
        )
    }
    #endif
}
