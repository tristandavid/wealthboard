import Foundation
import UIKit

#if canImport(FirebaseFirestore)
import FirebaseFirestore
#endif

/// What the app should do about its own version.
///
/// `blocked` is the only state that stops the user, and it is deliberately
/// hard to reach: see `UpdateGate.check` for why a config that cannot be read
/// never produces it.
enum UpdateStatus: Equatable {
    /// Nothing to say.
    case upToDate
    /// Newer build exists; the user may carry on. Shown once, dismissible.
    case optional(message: String?, storeURL: String?)
    /// This build may no longer be used. Full-screen, no way past it.
    case blocked(message: String?, storeURL: String?)
}

/// Forces an update when a shipped build has to be retired.
///
/// The version floor lives in Firestore rather than in the app, which is the
/// whole point: a build that is already on someone's phone is the build that
/// needs retiring, and it cannot be told to retire itself by shipping another
/// one — the people who would get that release are exactly the people who are
/// not the problem. One number changed in the console reaches every install.
///
/// This matters more on iOS than on Android, because a fix has to clear App
/// Review before anyone can install it. The floor can be raised the moment the
/// replacement is approved, not when it was written.
///
/// Reserved for the cases that actually warrant it: a version that corrupts
/// data, one that hammers a provider hard enough to get the whole app
/// rate-limited, a security fix. Forcing an update is taking someone's app away
/// until they act, and a build that merely has a nicer Reports screen has not
/// earned that.
///
/// The document, at `config/app_version`, is shared with Android and carries
/// both platforms' numbers:
/// ```
/// {
///   "minimumBuildIOS": 14,   // below this, blocked
///   "latestBuildIOS":  15,   // below this, optional
///   "message":         "…",  // optional, shown on the screen
///   "storeUrlIOS":     "…"   // optional; see openStore
/// }
/// ```
/// It has to be readable WITHOUT a signed-in user, since most installs have no
/// account — see the security rule quoted in `check`.
enum UpdateGate {

    private static let minimumKey = "wealthboard.update.minimumBuild"
    private static let latestKey = "wealthboard.update.latestBuild"
    private static let messageKey = "wealthboard.update.message"
    private static let storeURLKey = "wealthboard.update.storeURL"
    private static let optionalSeenKey = "wealthboard.update.optionalSeenFor"

    /// This build, as an integer. `CFBundleVersion` is the one that increments
    /// every submission; `CFBundleShortVersionString` ("1.10.0") is marketing
    /// and can repeat.
    static var currentBuild: Int {
        Int(Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "") ?? 0
    }

    /// Reads the floor and decides.
    ///
    /// FAILS OPEN, always. Every failure path here — no network, Firestore
    /// down, the document missing, a field of the wrong type, a build with no
    /// Firebase SDK linked at all — returns `.upToDate`.
    ///
    /// That is not defensiveness, it is the single most important property of
    /// this feature. A gate that fails CLOSED turns any backend hiccup into
    /// every user on every version being locked out of a portfolio the app
    /// already has on disk and can display perfectly well offline. The failure
    /// mode of forcing an update wrongly is far worse than the failure mode of
    /// missing one.
    ///
    /// The Firestore rule this needs, since most installs are not signed in:
    /// ```
    /// match /config/{document} {
    ///   allow read: if true;
    ///   allow write: if false;   // console only
    /// }
    /// ```
    static func check() async -> UpdateStatus {
        let defaults = UserDefaults.standard
        let current = currentBuild

        if let fetched = await fetchConfig() {
            // Cached so the decision survives a later launch with no network.
            // A floor that has been read once is a fact about this build, and
            // going quiet the moment the phone is in a tunnel would make the
            // retirement trivially avoidable by turning off wifi.
            defaults.set(intOf(fetched["minimumBuildIOS"]) ?? 0, forKey: minimumKey)
            defaults.set(intOf(fetched["latestBuildIOS"]) ?? 0, forKey: latestKey)
            defaults.set(fetched["message"] as? String, forKey: messageKey)
            defaults.set(fetched["storeUrlIOS"] as? String, forKey: storeURLKey)
        }

        // Nothing fetched and nothing cached means this app has never
        // successfully read a floor — so there is no floor, and the answer is
        // "carry on".
        let minimum = defaults.integer(forKey: minimumKey)
        let latest = defaults.integer(forKey: latestKey)
        let message = defaults.string(forKey: messageKey)
        let storeURL = defaults.string(forKey: storeURLKey)

        if minimum > 0 && current < minimum {
            return .blocked(message: message, storeURL: storeURL)
        }

        if latest > 0 && current < latest {
            // Once per newer build, not once per launch. A nag that returns
            // every time the app opens is one people learn to dismiss without
            // reading, which is also how they dismiss the one that matters.
            if defaults.integer(forKey: optionalSeenKey) >= latest {
                return .upToDate
            }
            return .optional(message: message, storeURL: storeURL)
        }

        return .upToDate
    }

    /// Remembers that the optional prompt for the current `latest` was shown.
    static func markOptionalSeen() {
        let defaults = UserDefaults.standard
        defaults.set(defaults.integer(forKey: latestKey), forKey: optionalSeenKey)
    }

    /// Opens this app's App Store page.
    ///
    /// Falls back to a search rather than a hardcoded numeric Apple ID, which
    /// this app does not know until its first App Store listing exists — and a
    /// wrong id is a button that opens someone else's app. Set `storeUrlIOS`
    /// in the config document to the real product URL once it is live, and
    /// this path stops being used.
    @MainActor
    static func openStore(_ storeURL: String?) {
        let candidates = [
            storeURL,
            "itms-apps://apps.apple.com/app/id\(Bundle.main.bundleIdentifier ?? "")",
            "https://apps.apple.com/search?term=WealthBoard"
        ].compactMap { $0 }

        for candidate in candidates {
            guard let url = URL(string: candidate),
                  UIApplication.shared.canOpenURL(url) else { continue }
            UIApplication.shared.open(url)
            return
        }
    }

    // MARK: - Reading

    private static func fetchConfig() async -> [String: Any]? {
        #if canImport(FirebaseFirestore)
        do {
            let snapshot = try await Firestore.firestore()
                .collection("config")
                .document("app_version")
                .getDocument()
            return snapshot.data()
        } catch {
            // Swallowed deliberately — see the fail-open note on `check`.
            return nil
        }
        #else
        // No Firebase in this build: there is no floor to read, so there is no
        // floor. Returning nil lands on the same "carry on" path as a failed
        // fetch.
        return nil
        #endif
    }

    /// Firestore hands numbers back as Int, Double or String depending on how
    /// they were typed into the console. Reading only Int meant a floor
    /// entered as `15.0` — which the console does if you type it into a number
    /// field — silently read as absent, and the gate never fired.
    private static func intOf(_ value: Any?) -> Int? {
        switch value {
        case let int as Int: return int
        case let double as Double: return Int(double)
        case let number as NSNumber: return number.intValue
        case let string as String:
            return Double(string.trimmingCharacters(in: .whitespaces)).map(Int.init)
        default: return nil
        }
    }
}
