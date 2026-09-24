import SwiftUI

@main
struct WealthBoardApp: App {
    @StateObject private var viewModel = PortfolioViewModel()
    @Environment(\.scenePhase) private var scenePhase

    /// Seeded from the stored setting so a locked app opens locked rather than
    /// flashing the portfolio for a frame first.
    @State private var unlocked = !AppLock.isEnabled
    /// Covers the window while the app is not in the foreground, which is what
    /// the app switcher captures for its card.
    @State private var obscured = false

    /// Starts at `.upToDate` and stays there until Firestore answers, so a
    /// cold launch shows the app immediately rather than a spinner. See
    /// `UpdateGate.check` — it fails open by design.
    @State private var updateStatus: UpdateStatus = .upToDate
    @State private var showOptionalUpdate = false

    /// Shown once per launch, on top of everything else below — see the last
    /// branch of the ZStack in `body`. Dismissed by SplashView itself once
    /// its entrance animation finishes.
    @State private var showSplash = true

    init() {
        // Installs the real account service when its SDK is present in the
        // project, and leaves the stubs in place when it is not.
        Services.bootstrap()

        // Registered here and nowhere else: BGTaskScheduler requires every
        // identifier to be registered before the app finishes launching, and
        // registering from a view's .task or .onAppear is too late — it
        // throws, and the alert refresh then never runs at all.
        AlertBackgroundTask.register()

        // Must be set before the app finishes launching, same as the task
        // registration above. Without it, an alert produced while the app is
        // open is delivered silently and never shown — which is most alerts,
        // since the engine runs on launch and on every foreground.
        NotificationPresenter.shared.install()
    }

    var body: some Scene {
        WindowGroup {
            ZStack {
                // Checked before the lock, and rendered before it: a retired
                // build is retired whether or not the user can get past their
                // own Face ID prompt, and making them authenticate first only
                // to be told the app is unusable is a pointless step.
                if case .blocked(let message, let storeURL) = updateStatus {
                    ForcedUpdateView(message: message, storeURL: storeURL)
                } else if unlocked {
                    RootTabView()
                        .environmentObject(viewModel)
                        .task {
                            await viewModel.bootstrap()

                            // Ask StoreKit what the entitlement actually is,
                            // every launch. It used to be asked from exactly
                            // one place — the Premium screen — so for anyone
                            // who never opened the paywall the entitlement
                            // stayed at its default false, and the alert pass
                            // below returned immediately every single time.
                            await SubscriptionSession.shared.refresh()
                            // Evaluated on every launch, not only when iOS
                            // decides to wake the background task. A
                            // BGAppRefreshTask is a request, not a schedule —
                            // the system may honour it hours late or not at
                            // all — so opening the app is what makes alerts
                            // dependable rather than best-effort.
                            await AlertRunner.ensureNotificationPermission()
                            await AlertRunner.run()
                        }
                } else {
                    LockView { unlocked = true }
                }

                // iOS has no equivalent of Android's FLAG_SECURE, so the
                // screenshot setting is honoured by painting over the window
                // when the app leaves the foreground. A screenshot taken while
                // the app is in front is still the user's own business.
                if obscured && AppLock.isEnabled && !AppLock.allowScreenshots {
                    PrivacyCover()
                        .transition(.opacity)
                }

                // Drawn last so it sits on top of the lock screen, the
                // forced-update screen, and everything else — a cold launch
                // should show the brand mark before any of those, not after.
                if showSplash {
                    SplashView {
                        withAnimation(.easeOut(duration: 0.3)) {
                            showSplash = false
                        }
                    }
                    .transition(.opacity)
                    .zIndex(10)
                }
            }
            // On the ZStack, NOT on RootTabView: that view does not exist
            // while the app is locked, so a check attached to it would never
            // run for anyone with the lock enabled — exactly the "retired
            // build keeps running" case this is here to prevent.
            .task {
                updateStatus = await UpdateGate.check()
                if case .optional = updateStatus, unlocked {
                    showOptionalUpdate = true
                }
            }
            // Held back until the app is actually open. A soft prompt over a
            // Face ID screen is noise at the one moment the user is trying to
            // do something else.
            .onChange(of: unlocked) { isUnlocked in
                if isUnlocked, case .optional = updateStatus {
                    showOptionalUpdate = true
                }
            }
            .preferredColorScheme(viewModel.themeMode.colorScheme)
            .tint(Brand.gold)
            // The OAuth redirect coming back from Google.
            //
            // Without this the sheet opens, the user signs in, and nothing
            // happens — which reads as a hang rather than as a missing
            // handler. Harmless when the SDK is absent: the call compiles to a
            // stub that returns false. The other half is the URL scheme in
            // Info.plist; see `GoogleSignInFlow.reversedClientID`.
            .onOpenURL { url in
                GoogleSignInFlow.handle(url)
            }
            // The soft prompt. An alert over the app rather than instead of
            // it: there is nothing wrong with the build they have, so it must
            // stay usable behind the dialog and one dismissal must be the end
            // of it.
            .alert("Update available", isPresented: $showOptionalUpdate) {
                Button("Update") {
                    UpdateGate.markOptionalSeen()
                    if case .optional(_, let storeURL) = updateStatus {
                        UpdateGate.openStore(storeURL)
                    }
                    updateStatus = .upToDate
                }
                Button("Not now", role: .cancel) {
                    UpdateGate.markOptionalSeen()
                    updateStatus = .upToDate
                }
            } message: {
                if case .optional(let message, _) = updateStatus {
                    Text(message?.isEmpty == false
                         ? message!
                         : "A newer version of WealthBoard is on the App Store.")
                }
            }
        }
        .onChange(of: scenePhase) { phase in
            obscured = phase != .active

            // The store debounces writes to coalesce a burst of edits; leaving
            // the foreground is the one moment where waiting it out could mean
            // losing the last one, so flush explicitly.
            if phase != .active {
                Task { await viewModel.flush() }
            }

            // Re-lock on return from the background. A lock that only applies
            // at cold launch protects nothing on a phone that is rarely
            // restarted.
            if phase == .background && AppLock.isEnabled {
                unlocked = false
            }

            // Asked for on the way out, which is the only moment the request
            // means anything: a task submitted while the app is in front is
            // just as valid, but this is where the app knows it is about to
            // stop being able to check prices itself.
            if phase == .background {
                AlertBackgroundTask.schedule()
            }

            // And evaluated on the way back in, before the user has had time
            // to look at the portfolio and wonder why nothing told them.
            if phase == .active {
                Task {
                    // Before the pass, so a device that has alerts but has
                    // never been asked gets the prompt rather than silently
                    // dropping every firing.
                    await AlertRunner.ensureNotificationPermission()
                    await AlertRunner.run()
                }
            }
        }
    }
}

/// The plain branded cover shown in the app switcher when the lock is on.
private struct PrivacyCover: View {
    var body: some View {
        ZStack {
            LinearGradient(
                colors: [Brand.navy, Brand.navyDark],
                startPoint: .top,
                endPoint: .bottom
            )
            .ignoresSafeArea()

            Text("WealthBoard")
                .font(.system(size: 24, weight: .bold))
                .foregroundStyle(.white.opacity(0.9))
        }
    }
}
