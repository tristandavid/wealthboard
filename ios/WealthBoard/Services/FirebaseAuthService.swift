import Foundation

// Sign-in for iOS, wired the moment the Firebase SDK is present.
//
// The Android build signs in through Firebase. iOS could not, because the SDK
// is a package dependency that has to be added in Xcode — so the Sign In screen
// fell back to "accounts aren't switched on in this build", which reads as a
// broken button rather than a missing dependency.
//
// This file closes that gap ahead of time. It compiles to nothing at all
// without the SDK, and becomes the real implementation as soon as the package
// is added, with no other edit anywhere:
//
//   1. Xcode → File → Add Package Dependencies…
//      https://github.com/firebase/firebase-ios-sdk
//      → add FirebaseAuth and FirebaseFirestore
//   2. GoogleService-Info.plist is already in Resources/ — confirm it is a
//      member of the app target (Xcode ▸ Target ▸ Build Phases ▸ Copy Bundle
//      Resources).
//
// `Services.bootstrap()` runs at launch and installs whichever implementation
// is available, so the screens never test for the SDK themselves.

#if canImport(FirebaseAuth) && canImport(FirebaseCore)
import FirebaseAuth
import FirebaseCore

@MainActor
final class FirebaseAuthService: AuthService {
    /// Configured once, lazily, so a missing plist degrades to "unavailable"
    /// rather than trapping at launch.
    private let ready: Bool

    init() {
        if FirebaseApp.app() == nil, Bundle.main.url(
            forResource: "GoogleService-Info",
            withExtension: "plist"
        ) != nil {
            FirebaseApp.configure()
        }
        ready = FirebaseApp.app() != nil
    }

    var isConfigured: Bool { ready }

    var currentUser: AuthUser? {
        guard ready, let user = Auth.auth().currentUser else { return nil }
        return AuthUser(id: user.uid, email: user.email, displayName: user.displayName)
    }

    func signIn(email: String, password: String) async throws -> AuthUser {
        guard ready else { throw AuthError.notConfigured }
        let result = try await Auth.auth().signIn(withEmail: email, password: password)
        return AuthUser(id: result.user.uid, email: result.user.email, displayName: result.user.displayName)
    }

    func register(email: String, password: String) async throws -> AuthUser {
        guard ready else { throw AuthError.notConfigured }
        let result = try await Auth.auth().createUser(withEmail: email, password: password)
        return AuthUser(id: result.user.uid, email: result.user.email, displayName: result.user.displayName)
    }

    func signOut() async {
        guard ready else { return }
        try? Auth.auth().signOut()
    }

    /// Permanently deletes the signed-in account.
    ///
    /// The cloud copy goes FIRST, on purpose. Deleting the auth user revokes
    /// the credential the Firestore rules check, so a document deleted
    /// afterwards would be refused — and the portfolio would outlive the
    /// account that owned it, which is exactly what the deletion requirement
    /// exists to prevent. If the account delete then fails, the user is still
    /// signed in and can retry; the reverse order leaves data nobody can reach.
    func deleteAccount() async throws {
        guard ready, let user = Auth.auth().currentUser else {
            throw AuthError.notConfigured
        }

        // Best-effort: an account with nothing backed up has no document, and
        // "there was nothing to delete" must not stop the account deletion.
        try? await Services.cloud.deleteBackup()

        do {
            try await user.delete()
        } catch let error as NSError {
            // Firebase refuses to delete on a session older than a few minutes.
            // Translated rather than passed through, because the raw message
            // ("This operation is sensitive and requires recent
            // authentication") does not tell anyone what to do about it.
            if error.code == AuthErrorCode.requiresRecentLogin.rawValue {
                throw AuthError.requiresRecentLogin
            }
            throw error
        }
    }

    func sendPasswordReset(email: String) async throws {
        guard ready else { throw AuthError.notConfigured }
        try await Auth.auth().sendPasswordReset(withEmail: email)
    }

    // MARK: - Google

    /// True once the SDK is in the project and the plist carries a CLIENT_ID.
    ///
    /// Both halves are checked because either can be missing on its own: no
    /// package means no flow to run, and no CLIENT_ID means Google was never
    /// enabled as a provider for the iOS app in the Firebase console. Neither
    /// is detectable after the sheet opens, so both are checked before the
    /// button is drawn.
    var isGoogleAvailable: Bool { ready && GoogleSignInFlow.isConfigured }

    func signInWithGoogle() async throws -> AuthUser {
        guard ready else { throw AuthError.notConfigured }

        let result = try await GoogleSignInFlow.signIn()
        let credential = GoogleAuthProvider.credential(
            withIDToken: result.idToken,
            accessToken: result.accessToken
        )
        let authResult = try await Auth.auth().signIn(with: credential)
        let user = authResult.user

        // Unlike Apple, Google returns the profile on every sign-in, so this is
        // a backfill rather than a one-and-only chance: it matters for an
        // account first created some other way and left without a name.
        if user.displayName?.isEmpty ?? true, let name = result.displayName {
            let change = user.createProfileChangeRequest()
            change.displayName = name
            try? await change.commitChanges()
        }

        return AuthUser(
            id: user.uid,
            email: user.email ?? result.email,
            displayName: user.displayName ?? result.displayName
        )
    }

    // MARK: - Apple

    /// Sign in with Apple ships with the OS, so there is no SDK to check for —
    /// only whether Firebase itself is ready.
    ///
    /// What CAN be missing is server-side: the Apple provider has to be enabled
    /// for this project in the Firebase console, and the App ID has to carry
    /// the Sign in with Apple capability. Neither is visible from here, and
    /// both fail at the exchange rather than before the sheet, so there is
    /// nothing useful to gate the button on.
    var isAppleAvailable: Bool { ready }

    func signInWithApple() async throws -> AuthUser {
        guard ready else { throw AuthError.notConfigured }

        let result = try await AppleSignInFlow.signIn()

        // The RAW nonce, not the hash that went to Apple — Firebase hashes this
        // itself and compares against what Apple signed into the token.
        //
        // `appleCredential(withIDToken:rawNonce:fullName:)` rather than the
        // `credential(withProviderID: "apple.com", ...)` form the Firebase docs
        // still show: this one has been present since Firebase 9 and is not
        // deprecated in 10, 11 or 12, whereas the provider-ID form is
        // deprecated from 11 and compiles with a warning. If the SDK in this
        // project predates 9, swap this call for:
        //
        //     OAuthProvider.credential(withProviderID: "apple.com",
        //                              idToken: result.identityToken,
        //                              rawNonce: result.nonce)
        let credential = OAuthProvider.appleCredential(
            withIDToken: result.identityToken,
            rawNonce: result.nonce,
            fullName: nil
        )
        let authResult = try await Auth.auth().signIn(with: credential)
        let user = authResult.user

        // Apple hands over the name ONCE, on the first authorization for this
        // Apple ID, and never again — not on the next sign-in, not after a
        // reinstall. So it is written through now or lost permanently, which
        // is the opposite of the Google path above where it can be backfilled
        // any time.
        if user.displayName?.isEmpty ?? true, let name = result.displayName {
            let change = user.createProfileChangeRequest()
            change.displayName = name
            try? await change.commitChanges()
        }

        // `user.email` is nil when someone chooses Apple's private relay and
        // Firebase has not been given the relay address, so Apple's own value
        // is the fallback rather than the other way round.
        return AuthUser(
            id: user.uid,
            email: user.email ?? result.email,
            displayName: user.displayName ?? result.displayName
        )
    }

}
#endif

extension Services {
    /// Installs the real services where the SDK is present, and leaves the
    /// unconfigured stubs in place where it is not. Called once at launch.
    static func bootstrap() {
        #if canImport(FirebaseAuth) && canImport(FirebaseCore)
        let firebase = FirebaseAuthService()
        if firebase.isConfigured {
            auth = firebase
            // Sync and the bug-report console ride on the same SDK and the
            // same account, so they are installed together rather than left for
            // a screen to notice.
            bootstrapCloudSync()
            bootstrapBugReports()
        }
        #endif

        // StoreKit needs no package and no configuration check — it ships with
        // the system, so the real implementation is installed unconditionally.
        // Whether there is anything to SELL is a separate question, answered by
        // `isConfigured` once the products have been fetched: with none created
        // in App Store Connect yet, the paywall says so rather than offering
        // buttons that cannot work.
        if #available(iOS 15.0, *) {
            subscriptions = StoreKitSubscriptionService()
        }
    }
}
