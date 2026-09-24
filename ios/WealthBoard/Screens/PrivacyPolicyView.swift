import SwiftUI

/// The full privacy policy, matching the Android build section for section.
///
/// It replaced a four-paragraph summary that had drifted into being wrong: it
/// said "no account required and nothing is uploaded anywhere", which stopped
/// being true once sign-in, cloud backup and bug reports shipped. A policy that
/// under-describes what an app does is worse than no policy, and this one is
/// reviewed by the App Store.
///
/// Kept deliberately in step with `PrivacyPolicyScreen.kt` — the two platforms
/// are one product to a reader, and a policy that differs between them is a
/// policy nobody can rely on. Where the platforms genuinely differ (local
/// storage, the lock mechanism) the wording describes what THIS build does.
struct PrivacyPolicyView: View {
    @Environment(\.colorScheme) private var scheme

    private static let lastUpdated = "September 18, 2026"

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                Text("Privacy Policy")
                    .font(.system(size: 22, weight: .bold))
                    .foregroundStyle(Palette.onSurface(scheme))

                Text("Last updated: \(Self.lastUpdated)")
                    .font(.system(size: 13))
                    .foregroundStyle(Palette.onSurfaceVariant(scheme))
                    .padding(.top, 4)
                    .padding(.bottom, 20)

                section(
                    "1. Introduction",
                    """
                    Welcome to WealthBoard ("we", "us", or "our"). We respect your privacy and are \
                    committed to protecting the personal information you share with us. This Privacy \
                    Policy explains how we collect, use, and protect your data when you use the \
                    WealthBoard mobile application.
                    """
                )

                section(
                    "2. Information We Collect",
                    """
                    Account Information: When you register, we collect your email address and a \
                    securely hashed password via Firebase Authentication. If you sign in with \
                    Google, we receive the email address and basic profile information associated \
                    with that Google account. An account is optional — WealthBoard works without one.

                    Portfolio Data: Holdings, transactions, dividends, and watchlists you enter are \
                    stored locally on your device and, if Cloud Sync is enabled, in your private \
                    Firebase Firestore collection.

                    Market Data: WealthBoard fetches publicly available quotes, charts, dividend \
                    records and news headlines from public market data sources. Only the ticker \
                    symbols you are viewing or hold are sent. No personally identifiable information \
                    is included in these requests, and nothing about your position sizes, cost basis \
                    or totals ever leaves the device with them.

                    Bug Reports: If you submit a bug report or contact support, we collect the \
                    message you write, your account email, and the timestamp of submission. This \
                    information is used solely to respond to and resolve your report.

                    Subscriptions: WealthBoard Premium is purchased and billed entirely through \
                    the App Store. Apple processes your payment and tells the app only whether a \
                    subscription is currently active. We never receive or store your payment card, \
                    billing address, or any other payment details.

                    Usage Data: We do not collect analytics, crash logs, or any telemetry beyond \
                    what Firebase provides by default (see Firebase's own privacy policy).
                    """
                )

                section(
                    "3. How We Use Your Information",
                    """
                    • To provide and maintain the app's core portfolio tracking features.
                    • To sync your data across devices when Cloud Sync is enabled.
                    • To respond to bug reports and support requests you submit.
                    • To determine whether your Premium subscription is active.
                    • To improve the app based on aggregated, non-identifiable usage patterns.
                    """
                )

                section(
                    "4. Data Storage & Security",
                    """
                    All cloud data (portfolio, sync, bug reports) is stored in Google Firebase \
                    (Firestore and Authentication), which is protected by Google's security \
                    infrastructure. Data is encrypted in transit (TLS) and at rest. Your Firestore \
                    data is scoped to your own user account — other users cannot access it.

                    Data entered on-device only is written to a private file in the app's own \
                    container, protected by iOS app sandboxing and file-level data protection. If \
                    you enable the App Lock feature, additional Face ID, Touch ID or PIN protection \
                    is applied; a PIN is stored only as a hash in the device keychain, marked so it \
                    never travels to a backup or another device.
                    """
                )

                section(
                    "5. Data Sharing",
                    """
                    We do not sell, trade, or rent your personal information to third parties. We do \
                    not share your portfolio data with any external service except:

                    • Google Firebase (for authentication and cloud sync, as described above).
                    • Public market data sources (only your requested ticker symbols are sent; no \
                    personally identifiable information is included).
                    """
                )

                section(
                    "6. Data Retention",
                    """
                    Your account and cloud data are retained for as long as your account exists. If \
                    you delete your account from within the app, your Firestore data will be \
                    permanently deleted within 30 days. Local on-device data is removed when you \
                    uninstall the app.
                    """
                )

                section(
                    "7. Your Rights",
                    """
                    You have the right to:
                    • Access the personal data we hold about you.
                    • Request correction of inaccurate data.
                    • Delete your account and all associated cloud data, from within the app \
                    (Menu → My Account → Delete account), or by request.
                    • Export your portfolio data, as a backup file from Backup & Restore or as a \
                    PDF from the Reports screen.

                    To exercise any of these rights, contact us through the in-app "Contact Support" \
                    feature or email us directly.
                    """
                )

                section(
                    "8. Children's Privacy",
                    """
                    WealthBoard is not directed at children under the age of 13. We do not knowingly \
                    collect personal information from children. If you believe a child has provided \
                    us with personal information, please contact us and we will delete it promptly.
                    """
                )

                section(
                    "9. Third-Party Services",
                    """
                    WealthBoard uses the following third-party services, each with their own privacy \
                    policies:

                    • Google Firebase (Authentication, Firestore): https://firebase.google.com/support/privacy
                    • Apple App Store billing, for subscriptions.
                    • Third-party market data API (quotes, charts, symbol search): no account \
                    required.
                    • dividendhistory.org (dividend dates and amounts): public data, no account \
                    required.
                    • Publisher news feeds (headlines): public RSS, no account required.

                    Advertising: WealthBoard does not currently display advertising and contains no \
                    advertising SDK. No advertising identifier is collected or shared. If this \
                    changes in a future version, this policy will be updated before that version is \
                    released.
                    """
                )

                section(
                    "10. Changes to This Policy",
                    """
                    We may update this Privacy Policy from time to time. When we do, we will update \
                    the "Last updated" date at the top and, for material changes, notify you in-app. \
                    Continued use of WealthBoard after a policy update constitutes acceptance of the \
                    revised policy.
                    """
                )

                section(
                    "11. Contact Us",
                    """
                    If you have any questions about this Privacy Policy, please contact us through \
                    the in-app "Contact Support" option found in the Menu. We aim to respond to all \
                    inquiries within 48 hours.
                    """
                )

                Color.clear.frame(height: 32)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 20)
            .padding(.vertical, 16)
        }
        .wbScreenBackground(scheme)
        .navigationTitle("Privacy Policy")
        .navigationBarTitleDisplayMode(.inline)
    }

    @ViewBuilder
    private func section(_ title: String, _ body: String) -> some View {
        Text(title)
            .font(.system(size: 15, weight: .semibold))
            .foregroundStyle(Palette.accent(scheme))
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.bottom, 6)

        Text(body)
            .font(.system(size: 14))
            .lineSpacing(6)
            .foregroundStyle(Palette.onSurface(scheme))
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.bottom, 20)
    }
}
