package ca.tristan.portfolio.ui.screens

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ca.tristan.portfolio.ui.components.WealthBoardTopBar

@Composable
fun PrivacyPolicyScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            WealthBoardTopBar(
                title = "Privacy Policy",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            item {
                Text(
                    "Privacy Policy",
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Last updated: September 18, 2026",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(20.dp))

                PolicySection(
                    title = "1. Introduction",
                    body = "Welcome to WealthBoard (\"we\", \"us\", or \"our\"). We respect your privacy and are committed to protecting the personal information you share with us. This Privacy Policy explains how we collect, use, and protect your data when you use the WealthBoard mobile application."
                )

                PolicySection(
                    title = "2. Information We Collect",
                    body = "Account Information: When you register, we collect your email address and a securely hashed password via Firebase Authentication. If you sign in with Google, we receive the email address and basic profile information associated with that Google account. An account is optional — WealthBoard works without one.\n\n" +
                        "Portfolio Data: Holdings, transactions, dividends, and watchlists you enter are stored locally on your device and, if Cloud Sync is enabled, in your private Firebase Firestore collection.\n\n" +
                        "Market Data: WealthBoard fetches publicly available quotes, charts, dividend records and news headlines from public market data sources. Only the ticker symbols you are viewing or hold are sent. No personally identifiable information is included in these requests, and nothing about your position sizes, cost basis or totals ever leaves the device with them.\n\n" +
                        "Bug Reports: If you submit a bug report or contact support, we collect the message you write, your account email, and the timestamp of submission. This information is used solely to respond to and resolve your report.\n\n" +
                        "Subscriptions: WealthBoard Premium is purchased and billed entirely through Google Play. Google processes your payment and tells the app only whether a subscription is currently active. We never receive or store your payment card, billing address, or any other payment details.\n\n" +
                        "Usage Data: We do not collect analytics, crash logs, or any telemetry beyond what Firebase provides by default (see Firebase's own privacy policy)."
                )

                PolicySection(
                    title = "3. How We Use Your Information",
                    body = "• To provide and maintain the app's core portfolio tracking features.\n" +
                        "• To sync your data across devices when Cloud Sync is enabled.\n" +
                        "• To respond to bug reports and support requests you submit.\n• To determine whether your Premium subscription is active.\n" +
                        "• To improve the app based on aggregated, non-identifiable usage patterns."
                )

                PolicySection(
                    title = "4. Data Storage & Security",
                    body = "All cloud data (portfolio, sync, bug reports) is stored in Google Firebase (Firestore and Authentication), which is protected by Google's security infrastructure. Data is encrypted in transit (TLS) and at rest. Your Firestore data is scoped to your own user account — other users cannot access it.\n\n" +
                        "Data entered on-device only is stored in a local Room (SQLite) database protected by the Android security model. If you enable the App Lock feature, additional biometric or PIN protection is applied."
                )

                PolicySection(
                    title = "5. Data Sharing",
                    body = "We do not sell, trade, or rent your personal information to third parties. We do not share your portfolio data with any external service except:\n\n" +
                        "• Google Firebase (for authentication and cloud sync, as described above).\n" +
                        "• Public market data sources (only your requested ticker symbols are sent; no personally identifiable information is included)."
                )

                PolicySection(
                    title = "6. Data Retention",
                    body = "Your account and cloud data are retained for as long as your account exists. If you delete your account from within the app, your Firestore data will be permanently deleted within 30 days. Local on-device data is removed when you uninstall the app."
                )

                PolicySection(
                    title = "7. Your Rights",
                    body = "You have the right to:\n" +
                        "• Access the personal data we hold about you.\n" +
                        "• Request correction of inaccurate data.\n" +
                        "• Delete your account and all associated cloud data, from within the app (Menu → My Account → Delete account), or by request.\n" +
                        "• Export your portfolio data, as a backup file from Backup & Restore or as a PDF from the Reports screen.\n\n" +
                        "To exercise any of these rights, contact us through the in-app \"Contact Support\" feature or email us directly."
                )

                PolicySection(
                    title = "8. Children's Privacy",
                    body = "WealthBoard is not directed at children under the age of 13. We do not knowingly collect personal information from children. If you believe a child has provided us with personal information, please contact us and we will delete it promptly."
                )

                PolicySection(
                    title = "9. Third-Party Services",
                    body = "WealthBoard uses the following third-party services, each with their own privacy policies:\n\n" +
                        "• Google Firebase (Authentication, Firestore): https://firebase.google.com/support/privacy\n• Google Play billing, for subscriptions.\n" +
                        "• Third-party market data API (quotes, charts, symbol search): no account required.\n" +
                        "• dividendhistory.org (dividend dates and amounts): public data, no account required.\n" +
                        "• Publisher news feeds (headlines): public RSS, no account required.\n\nAdvertising: WealthBoard does not currently display advertising and contains no advertising SDK. No advertising identifier is collected or shared. If this changes in a future version, this policy will be updated before that version is released."
                )

                PolicySection(
                    title = "10. Changes to This Policy",
                    body = "We may update this Privacy Policy from time to time. When we do, we will update the \"Last updated\" date at the top and, for material changes, notify you in-app. Continued use of WealthBoard after a policy update constitutes acceptance of the revised policy."
                )

                PolicySection(
                    title = "11. Contact Us",
                    body = "If you have any questions about this Privacy Policy, please contact us through the in-app \"Contact Support\" option found in the Menu. We aim to respond to all inquiries within 48 hours."
                )

                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun PolicySection(title: String, body: String) {
    Text(
        title,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        color = MaterialTheme.colorScheme.primary
    )
    Spacer(Modifier.height(6.dp))
    Text(
        body,
        fontSize = 14.sp,
        lineHeight = 22.sp,
        color = MaterialTheme.colorScheme.onSurface
    )
    Spacer(Modifier.height(20.dp))
}
