import Foundation

/// The two public legal documents, in one place.
///
/// Both stores need these as URLs rather than as in-app screens: App Store
/// guideline 3.1.2 requires functional links to Terms of Use and a Privacy
/// Policy *from the purchase screen itself*, and Play's listing requires the
/// privacy policy as a URL. The app still shows its own copy of the policy
/// offline — see `PrivacyPolicyView` — but a reviewer needs a link they can
/// open in a browser, and a customer part-way through a purchase should not
/// have to leave the flow to find one.
///
/// Kept as constants rather than typed at each call site because the same two
/// addresses appear on the paywall, in the Menu and in both store listings, and
/// a stale link in one of those is invisible until someone taps it.
enum Legal {
    static let privacyPolicy = URL(string: "https://wealthboardapp.com/privacy-policy.html")!
    static let termsOfUse = URL(string: "https://wealthboardapp.com/terms.html")!
    static let website = URL(string: "https://wealthboardapp.com")!
}
