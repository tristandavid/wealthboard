package ca.tristan.portfolio

/**
 * The two public legal documents, in one place.
 *
 * Both stores need these as URLs rather than as in-app screens: Play's listing
 * requires the privacy policy as a link, its subscription policy wants the
 * terms reachable from the purchase screen, and the iOS build needs the same
 * pair under App Store guideline 3.1.2. The app still carries its own copy of
 * the policy offline — see [ca.tristan.portfolio.ui.screens.PrivacyPolicyScreen]
 * — but a reviewer needs something they can open in a browser.
 *
 * Constants rather than strings typed at each call site because the same two
 * addresses appear on the paywall, in the Menu and in both store listings, and
 * a stale link in one of those is invisible until someone taps it.
 */
object Legal {
    const val WEBSITE = "https://wealthboardapp.com"
    const val PRIVACY_POLICY = "https://wealthboardapp.com/privacy-policy.html"
    const val TERMS_OF_USE = "https://wealthboardapp.com/terms.html"
}
