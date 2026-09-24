import SwiftUI
import UIKit

/// The Menu tab: account, news, taxes, privacy, support and settings, with the
/// build stamp pinned to the bottom.
struct MenuView: View {
    @EnvironmentObject private var viewModel: PortfolioViewModel
    @Environment(\.colorScheme) private var scheme

    /// Watched, not read once. See `AuthSession` for why this row used to go on
    /// saying "Sign In / Register" to someone who was already signed in.
    @ObservedObject private var session = AuthSession.shared
    @ObservedObject private var subscription = SubscriptionSession.shared

    #if DEBUG
    /// Readout for the developer-only "Check alerts now" row.
    @State private var debugAlertResult: String?
    #endif

    var body: some View {
        ZStack(alignment: .bottom) {
            ScrollView {
                // Three groups rather than one flat stack: ViewBuilder takes at
                // most ten children, and a menu of this length has well over
                // twenty once every divider is counted.
                LazyVStack(spacing: 0) {
                    accountAndNews
                    taxAndPrivacy
                    supportAndSettings

                    // Clearance for the pinned version stamp below.
                    Color.clear.frame(height: 56)
                }
            }

            // Pinned rather than added as a list item, so it sits in the same
            // place whether or not the menu scrolls.
            Text(buildStamp)
                .font(.system(size: 11))
                .foregroundStyle(Palette.onSurfaceVariant(scheme))
                .padding(.bottom, 14)
        }
        .wbScreenBackground(scheme)
        .wbNavigationBar("Menu")
        // Catches the session nothing in the app initiated: a persisted
        // sign-in restored a moment after launch, or a sign-out that happened
        // on another screen.
        .onAppear {
            session.refresh()
            subscription.sync()
        }
    }

    // MARK: - Sections

    @ViewBuilder
    private var accountAndNews: some View {
        NavigationLink { ProfileView() } label: {
            MenuRow(
                label: session.menuLabel,
                systemImage: "person.crop.circle",
                subtitle: session.menuSubtitle
            )
        }
        .buttonStyle(.plain)
        WbDivider()

        // The markets dashboard lives here now that News has its tab. Indices,
        // movers, the watchlist and symbol search are worth keeping — they are
        // just not a several-times-a-day screen the way the feed is.
        // Directly under the account row: the two things a signed-in user is
        // most likely to be looking for are who they are and what they pay.
        NavigationLink { PremiumView() } label: {
            MenuRow(
                label: subscription.isPremium ? "Premium" : "Go Premium",
                systemImage: subscription.isPremium ? "checkmark.seal.fill" : "star.circle",
                subtitle: premiumSubtitle
            )
        }
        .buttonStyle(.plain)
        WbDivider()

        // Alerts, directly under Premium rather than buried near Settings: it
        // is the feature most likely to be the reason someone subscribed, and
        // a rule nobody can find is a rule nobody sets.
        NavigationLink { AlertsView() } label: {
            MenuRow(
                label: "Alerts",
                systemImage: "bell.badge",
                subtitle: alertsSubtitle
            )
        }
        .buttonStyle(.plain)
        WbDivider()

        NavigationLink { MarketsView() } label: {
            MenuRow(label: "Markets & Watchlist", systemImage: "chart.line.uptrend.xyaxis")
        }
        .buttonStyle(.plain)
        WbDivider()

        // Always shown, signed in or not.
        //
        // This row used to appear only when signed in, which made the app's
        // only safety net invisible to anyone without an account — for a
        // hand-entered portfolio, that is the one feature nobody should have to
        // discover. The screen behind it leads with a file export that needs no
        // account at all, and offers cloud sync to those who do sign in.
        NavigationLink { BackupView() } label: {
            MenuRow(
                label: "Backup & Restore",
                systemImage: "arrow.down.doc",
                subtitle: Services.cloud.lastBackupAt.map {
                    "Last cloud backup " + $0.formatted(date: .abbreviated, time: .shortened)
                } ?? "Export to a file, or sign in to sync"
            )
        }
        .buttonStyle(.plain)
        WbDivider()
    }

    @ViewBuilder
    private var taxAndPrivacy: some View {
        // The plain-language guide lives inside Taxes now, at the top of the
        // screen it explains. Two tax entries side by side in the menu made the
        // reader choose between them before knowing what either was.
        NavigationLink { TaxSettingsView() } label: {
            MenuRow(
                label: "Taxes",
                systemImage: "percent",
                subtitle: "Residency, account types and your tax rates"
            )
        }
        .buttonStyle(.plain)
        WbDivider()

        NavigationLink { PrivacyPolicyView() } label: {
            MenuRow(label: "Privacy Policy", systemImage: "checkmark.shield")
        }
        .buttonStyle(.plain)
        WbDivider()
    }

    @ViewBuilder
    private var supportAndSettings: some View {
        NavigationLink { BugReportView() } label: {
            MenuRow(label: "Contact Support", systemImage: "ant")
        }
        .buttonStyle(.plain)
        WbDivider()

        if session.isAdministrator {
            NavigationLink { AdminReportsView() } label: {
                MenuRow(label: "Bug Reports", systemImage: "tray.full")
            }
            .buttonStyle(.plain)
            WbDivider()
        }

        #if DEBUG
        // Developer-only: lets the creator see the app as a locked or an
        // unlocked user on demand, without a real (or even sandboxed)
        // purchase. `#if DEBUG` compiles this whole row out of a release
        // build, so there's nothing here for TestFlight or App Store
        // builds to expose.
        Button {
            let next: Bool?
            switch subscription.debugOverride {
            case .none: next = false
            case .some(false): next = true
            case .some(true): next = nil
            }
            subscription.setDebugPremiumOverride(next)
        } label: {
            MenuRow(
                label: "Simulate Premium: " + {
                    switch subscription.debugOverride {
                    case .some(true): return "Unlocked"
                    case .some(false): return "Locked"
                    case .none: return "Off (real StoreKit state)"
                    }
                }(),
                systemImage: "ladybug",
                subtitle: "Tap to cycle — forces the paywall gates. Currently: " +
                    (subscription.isPremium ? "unlocked." : "locked.")
            )
        }
        .buttonStyle(.plain)
        WbDivider()

        Button {
            debugAlertResult = "Checking…"
            Task {
                let outcome = await AlertRunner.debugRun()
                debugAlertResult = {
                    if !outcome.wasPremium {
                        return "Skipped — not Premium. Use Simulate Premium above."
                    }

                    // Distinguished from "nothing fired" on purpose. The two
                    // read the same to a user and need opposite fixes: this
                    // one means the rules live on another device.
                    if outcome.enabledAlerts == 0 {
                        return "No enabled alerts on this device — nothing to check. "
                            + "Alerts are stored per device; add one here, or restore a backup."
                    }

                    let scope = outcome.enabledAlerts == 1
                        ? "1 enabled alert" : "\(outcome.enabledAlerts) enabled alerts"

                    if outcome.fired == 0 {
                        return "Checked \(scope). Nothing fired — no condition is newly true "
                            + "(already-fired ones stay latched until they clear)."
                    }

                    let fired = outcome.fired == 1 ? "1 alert fired" : "\(outcome.fired) alerts fired"

                    // Posting succeeds without permission and delivers
                    // nothing, so saying "check your notifications" here was
                    // actively misleading.
                    guard outcome.canNotify else {
                        return "\(fired), but notifications are NOT permitted — none were "
                            + "delivered. Settings → WealthBoard → Notifications."
                    }

                    // Permission granted is NOT the same as "will be seen".
                    // With Banners, Lock Screen and Notification Centre all
                    // off, the post succeeds and appears nowhere at all —
                    // which from the outside is identical to never firing.
                    guard outcome.hasVisibleDestination else {
                        return "\(fired) and posted, but nowhere visible. "
                            + outcome.deliverySummary
                            + " Settings → Notifications → WealthBoard."
                    }

                    // Focus and the ring/silent switch stay invisible to the
                    // app — authorizationStatus does not move for either — so
                    // this is as precise as the row can honestly be.
                    let caveat = outcome.deliverySummary.isEmpty
                        ? ""
                        : " " + outcome.deliverySummary
                    return "\(fired) and posted.\(caveat) If nothing appeared, swipe down for "
                        + "Notification Centre, then check Focus and Scheduled Summary."
                }()
            }
        } label: {
            MenuRow(
                label: "Check alerts now (debug)",
                systemImage: "bell.badge.waveform",
                subtitle: debugAlertResult
                    ?? "Debug builds only. Alerts already run by themselves — every 2 minutes while the app is open, and in the background when iOS allows. This forces one pass now, ignoring market hours."
            )
        }
        .buttonStyle(.plain)
        WbDivider()
        #endif

        // Settings is a destination people go looking for deliberately rather
        // than one they browse into, so it sits last rather than near the top.
        NavigationLink { SettingsView() } label: {
            MenuRow(label: "Settings", systemImage: "gearshape")
        }
        .buttonStyle(.plain)
        WbDivider()
    }

    private var alertsSubtitle: String {
        if !subscription.isPremium {
            return "Price targets and ex-dividend reminders — Premium"
        }
        switch viewModel.alerts.count {
        case 0: return "Set a price target or ex-dividend reminder"
        case 1: return "1 alert set"
        case let n: return "\(n) alerts set"
        }
    }

    /// Describes Premium by what this build actually gives, so the Menu row
    /// and the paywall cannot promise different things.
    private var premiumSubtitle: String {
        if subscription.isPremium {
            return Services.ads.isAvailable
                ? "Active — no ads, alerts, cloud sync, exports, unlimited accounts"
                : "Active — alerts, cloud sync, exports, unlimited accounts"
        }
        return Services.ads.isAvailable
            ? "Remove ads, alerts, cloud sync, exports, unlimited accounts"
            : "Alerts, cloud sync, exports and unlimited accounts"
    }

    private var buildStamp: String {
        let info = Bundle.main.infoDictionary
        let version = info?["CFBundleShortVersionString"] as? String ?? "1.0"
        let build = info?["CFBundleVersion"] as? String ?? "1"
        return "v\(version) (\(build))"
    }

}

// MARK: - Row

private struct MenuRow: View {
    @Environment(\.colorScheme) private var scheme

    let label: String
    let systemImage: String
    var subtitle: String?

    var body: some View {
        HStack(spacing: 14) {
            Image(systemName: systemImage)
                .font(.system(size: 18))
                .foregroundStyle(Palette.accent(scheme))
                .frame(width: 28)

            VStack(alignment: .leading, spacing: 2) {
                Text(label)
                    .font(.wbBodyLarge)
                    .fontWeight(.medium)
                    .foregroundStyle(Palette.onSurface(scheme))
                if let subtitle {
                    Text(subtitle)
                        .font(.wbBodySmall)
                        .foregroundStyle(Palette.onSurfaceVariant(scheme))
                }
            }

            Spacer()

            Image(systemName: "chevron.right")
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(Palette.onSurfaceVariant(scheme).opacity(0.6))
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 16)
        .contentShape(Rectangle())
    }
}
