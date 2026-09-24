import SwiftUI

/// The plan screen: what Free gives you, what Premium adds, and the two ways to
/// buy it.
///
/// Prices come from the store, never from the app. They are regional, they
/// change without a release, and both stores require the customer to be shown
/// the amount they will actually be charged in their own currency — so a
/// hardcoded "$6.99" is wrong for most of the world and eventually wrong
/// everywhere. `SubscriptionPlan.fallbackPrice` appears only while the fetch is
/// in flight or has failed.
struct PremiumView: View {
    @Environment(\.colorScheme) private var scheme
    @Environment(\.dismiss) private var dismiss

    @ObservedObject private var session = SubscriptionSession.shared

    @State private var selected: SubscriptionPlan = .yearly
    @State private var busy = false
    @State private var message: String?
    @State private var isError = true

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                header

                if session.isPremium {
                    activeCard
                } else {
                    comparison
                    plans
                    buyButton
                    restoreButton
                    if !session.isConfigured { unavailableNote }
                    smallPrint
                    legalLinks
                }

                if let message {
                    Text(message)
                        .font(.wbBodySmall)
                        .foregroundStyle(isError ? Brand.loss : Brand.gain)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.top, 14)
                }

                Color.clear.frame(height: 32)
            }
            .padding(WbDimens.screenPadding)
        }
        .wbScreenBackground(scheme)
        .navigationTitle("Premium")
        .navigationBarTitleDisplayMode(.inline)
        .task { await session.refresh() }
    }

    // MARK: - Pieces

    private var header: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("WealthBoard Premium")
                .font(.system(size: 24, weight: .bold))
                .foregroundStyle(Palette.onSurface(scheme))
            Text(Services.ads.isAvailable
                 ? "Keep the whole app, without the ads."
                 : "Alerts, cloud sync, PDF and CSV reports, and unlimited accounts.")
                .font(.wbBodyMedium)
                .foregroundStyle(Palette.onSurfaceVariant(scheme))
        }
        .padding(.bottom, 20)
    }

    private var activeCard: some View {
        WbCard {
            HStack(spacing: 10) {
                Image(systemName: "checkmark.seal.fill")
                    .font(.system(size: 22))
                    .foregroundStyle(Brand.gain)
                VStack(alignment: .leading, spacing: 2) {
                    Text("Premium is active")
                        .font(.wbTitleMedium)
                        .foregroundStyle(Palette.onSurface(scheme))
                    Text("Manage or cancel it in your Apple account settings.")
                        .font(.wbBodySmall)
                        .foregroundStyle(Palette.onSurfaceVariant(scheme))
                }
                Spacer(minLength: 0)
            }
        }
    }

    /// What each tier actually gets, side by side.
    ///
    /// Written as what is GIVEN rather than what is withheld: "Free" listing
    /// four things it cannot do reads as a punishment, and the free tier here
    /// is genuinely the whole app.
    private var comparison: some View {
        WbCard {
            // The copy follows what the build actually does.
            //
            // It read "with ads" against "no ads" unconditionally, which was
            // written for a build that had an ad network. There isn't one, so
            // the free tier carried no ads and the paywall advertised some
            // anyway — a promise the app doesn't keep, in the one place a
            // reviewer reads word for word before approving a purchase.
            // `Services.ads.isAvailable` is the honest test, and the copy goes
            // back to mentioning ads by itself the day a network is wired in.
            //
            // This used to say "no limits" unconditionally — true of ads,
            // false of accounts. `PremiumLimits.freeAccountLimit` caps a free
            // install at 2 accounts no matter what this screen claims, so the
            // one place a person reads about the free tier was contradicting
            // the wall they'd actually hit, and looked like a bug on either
            // side of that contradiction.
            tierRow(
                title: "Free",
                detail: Services.ads.isAvailable
                    ? "Every screen, every holding, every chart, up to \(PremiumLimits.freeAccountLimit) accounts — with ads."
                    : "Every screen, every holding, every chart, up to \(PremiumLimits.freeAccountLimit) accounts. No ads.",
                isCurrent: true
            )
            WbDivider().padding(.vertical, 12)
            tierRow(
                title: "Premium",
                detail: Services.ads.isAvailable
                    ? "No ads, price and ex-dividend alerts, cloud sync, PDF and CSV report exports, and unlimited accounts."
                    : "Price and ex-dividend alerts, cloud backup and sync across your devices, PDF and CSV report exports, and unlimited accounts.",
                isCurrent: false
            )
        }
    }

    /// One tier, described rather than offered.
    ///
    /// These two rows were drawn with an empty circle and a filled checkmark —
    /// which is exactly what a radio group looks like, so "Free" read as an
    /// option that could be chosen and then did nothing when tapped. It was
    /// never selectable: this card says what each tier IS, and the only choice
    /// on the screen is monthly against yearly below. The iconography now says
    /// so — a tick against everything both tiers give you, and a "Current plan"
    /// badge on the one you are on — so nothing here invites a tap it cannot
    /// answer.
    private func tierRow(
        title: String,
        detail: String,
        isCurrent: Bool
    ) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: "checkmark")
                .font(.system(size: 13, weight: .bold))
                .foregroundStyle(Palette.accent(scheme))
                .padding(.top, 3)
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 8) {
                    Text(title)
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(Palette.onSurface(scheme))
                    if isCurrent {
                        Text("CURRENT PLAN")
                            .font(.system(size: 9, weight: .bold))
                            .foregroundStyle(Palette.onSurfaceVariant(scheme))
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .background(
                                Capsule().stroke(Palette.outline(scheme), lineWidth: 1)
                            )
                    }
                }
                Text(detail)
                    .font(.wbBodySmall)
                    .foregroundStyle(Palette.onSurfaceVariant(scheme))
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 0)
        }
    }

    private var plans: some View {
        VStack(spacing: 10) {
            ForEach(SubscriptionPlan.allCases) { plan in
                planRow(plan)
            }
        }
        .padding(.top, 16)
    }

    private func planRow(_ plan: SubscriptionPlan) -> some View {
        let live = session.products.first { $0.plan == plan }
        let isSelected = selected == plan

        return Button {
            selected = plan
        } label: {
            HStack(spacing: 12) {
                Image(systemName: isSelected ? "largecircle.fill.circle" : "circle")
                    .font(.system(size: 20))
                    .foregroundStyle(isSelected ? Palette.accent(scheme) : Palette.onSurfaceVariant(scheme))

                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 6) {
                        Text(plan.title)
                            .font(.system(size: 15, weight: .semibold))
                            .foregroundStyle(Palette.onSurface(scheme))
                        // Only when the store says this customer can actually
                        // have it — see `trialDescription`.
                        if let trial = live?.trialDescription {
                            Text(trial.uppercased())
                                .font(.system(size: 10, weight: .bold))
                                .foregroundStyle(Palette.onAccent(scheme))
                                .padding(.horizontal, 7)
                                .padding(.vertical, 3)
                                .background(Brand.gain, in: Capsule())
                        }
                    }
                    Text(plan.period)
                        .font(.wbBodySmall)
                        .foregroundStyle(Palette.onSurfaceVariant(scheme))
                }

                Spacer(minLength: 8)

                Text(live?.displayPrice ?? plan.fallbackPrice)
                    .font(.system(size: 17, weight: .bold))
                    .foregroundStyle(Palette.onSurface(scheme))
            }
            .padding(14)
            .background(
                RoundedRectangle(cornerRadius: WbDimens.cardRadius, style: .continuous)
                    .fill(Palette.surface(scheme))
            )
            .overlay(
                RoundedRectangle(cornerRadius: WbDimens.cardRadius, style: .continuous)
                    .stroke(
                        isSelected ? Palette.accent(scheme) : Palette.outline(scheme),
                        lineWidth: isSelected ? 2 : 1
                    )
            )
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    /// The selected plan's trial, when the store offers this customer one.
    private var selectedTrial: String? {
        session.products.first { $0.plan == selected }?.trialDescription
    }

    private var buyButton: some View {
        Button {
            Task { await buy() }
        } label: {
            ZStack {
                // "Start free trial" only when the selected plan actually
                // carries one for this customer. A button promising a trial
                // that charges immediately is the single worst thing a paywall
                // can do.
                Text(selectedTrial == nil ? "Subscribe" : "Start free trial")
                    .opacity(busy ? 0 : 1)
                if busy { ProgressView().tint(Palette.onAccent(scheme)) }
            }
            .font(.wbBodyLarge)
            .fontWeight(.semibold)
            .foregroundStyle(Palette.onAccent(scheme))
            .frame(maxWidth: .infinity)
            .frame(height: 50)
            .background(Palette.accent(scheme), in: Capsule())
        }
        .buttonStyle(.plain)
        .disabled(busy || !session.isConfigured)
        .opacity(session.isConfigured ? 1 : 0.5)
        .padding(.top, 18)
    }

    /// Apple requires a restore action to be visible, not buried in support.
    private var restoreButton: some View {
        Button("Restore purchases") {
            Task { await restore() }
        }
        .font(.wbBodySmall)
        .fontWeight(.medium)
        .foregroundStyle(Palette.accent(scheme))
        .frame(maxWidth: .infinity)
        .padding(.top, 12)
        .disabled(busy)
    }

    private var unavailableNote: some View {
        WbCard {
            Text("Subscriptions aren't switched on yet")
                .font(.wbTitleMedium)
                .foregroundStyle(Palette.onSurface(scheme))
                .padding(.bottom, 6)
            Text("Everything in the app is available right now. Premium arrives once the store is set up.")
                .font(.wbBodySmall)
                .foregroundStyle(Palette.onSurfaceVariant(scheme))
                .fixedSize(horizontal: false, vertical: true)

            // Debug only: the cause is a one-off setup step, and shipping it to
            // a user would be worse than saying nothing.
            #if DEBUG
            WbDivider().padding(.vertical, 10)
            Text("Setup")
                .font(.system(size: 10, weight: .bold))
                .foregroundStyle(Brand.divAmber)
                .padding(.bottom, 4)
            Text("""
            No products came back from StoreKit. Create two auto-renewing \
            subscriptions in App Store Connect with exactly these product IDs, \
            in one subscription group:

            \(SubscriptionPlan.monthly.rawValue)
            \(SubscriptionPlan.yearly.rawValue)

            For the simulator, add a StoreKit configuration file instead \
            (File ▸ New ▸ StoreKit Configuration File) and select it in the \
            scheme's Run ▸ Options.
            """)
                .font(.system(size: 11))
                .foregroundStyle(Palette.onSurfaceVariant(scheme))
                .fixedSize(horizontal: false, vertical: true)
                .textSelection(.enabled)
            #endif
        }
        .padding(.top, 16)
    }

    private var smallPrint: some View {
        // Apple requires the trial's terms to be stated where the customer
        // buys, not only in App Store Connect: how long it runs, what happens
        // at the end, and how to get out before being charged.
        let trialTerms = selectedTrial.map {
            "Your \($0.replacingOccurrences(of: " free", with: "")) trial is free. "
                + "Unless you cancel at least 24 hours before it ends, it "
                + "becomes a paid subscription automatically. "
        } ?? ""

        return Text(trialTerms + """
        Subscriptions renew automatically until cancelled. You can cancel any \
        time from your Apple account settings; cancelling stops the next \
        renewal and Premium runs to the end of the period you have paid for.
        """)
            .font(.system(size: 11))
            .foregroundStyle(Palette.onSurfaceVariant(scheme))
            .fixedSize(horizontal: false, vertical: true)
            .padding(.top, 16)
    }

    /// Terms of Use and Privacy Policy, on the purchase screen itself.
    ///
    /// Guideline 3.1.2 requires both to be linked from where the subscription
    /// is bought — not only from a settings menu, and not only in App Store
    /// Connect's metadata. A reviewer taps these. They open in Safari rather
    /// than in an in-app sheet because the requirement is a *functional link*
    /// to the published document, and the published one is what the store
    /// listing also points at, so there is a single copy to keep current.
    private var legalLinks: some View {
        HStack(spacing: 18) {
            Link("Terms of Use", destination: Legal.termsOfUse)
            Link("Privacy Policy", destination: Legal.privacyPolicy)
            Spacer(minLength: 0)
        }
        .font(.system(size: 12))
        .foregroundStyle(Palette.accent(scheme))
        .padding(.top, 12)
    }

    // MARK: - Actions

    private func buy() async {
        busy = true
        message = nil
        defer { busy = false }

        switch await session.purchase(selected) {
        case .purchased:
            message = "You're on Premium. Thank you."
            isError = false
        case .cancelled:
            // Say nothing: backing out of the sheet is a decision, and an
            // error in red under the button implies something broke.
            break
        case .pending:
            message = "That purchase is waiting for approval. Premium switches on as soon as it clears."
            isError = false
        case .failed(let text):
            message = text
            isError = true
        }
    }

    private func restore() async {
        busy = true
        message = nil
        defer { busy = false }

        if await session.restore() {
            message = "Premium restored."
            isError = false
        } else {
            message = "No previous subscription found on this Apple account."
            isError = true
        }
    }
}
