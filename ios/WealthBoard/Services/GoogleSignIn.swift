import Foundation
import UIKit

#if canImport(GoogleSignIn)
import GoogleSignIn
#endif

/// The non-Firebase half of Google sign-in: running Google's sheet and handing
/// back the tokens an auth backend needs.
///
/// Split so that this part does not
/// depend on Firebase, so the SDK-guarded file stays down to the credential
/// exchange.
///
/// ## Why this needs a package
///
/// Google's SDK does not ship with the system, so everything here is wrapped in
/// `canImport(GoogleSignIn)` and compiles to a set of "not configured" stubs
/// until the package is added. `isConfigured` then reports honestly and
/// `LoginView` hides the button rather than showing one that cannot work.
///
/// ## Two tokens, not one
///
/// The flow yields an ID token **and** an access token, and Firebase wants
/// both: the ID token proves who the user is, the access token is what would
/// authorise calls to other Google APIs on their behalf. The app makes no such
/// calls, but the credential is built with both because that is the shape
/// Firebase's constructor takes.
enum GoogleSignInFlow {

    struct Result {
        let idToken: String
        let accessToken: String
        let email: String?
        let displayName: String?
    }

    enum Failure: LocalizedError, Equatable {
        case cancelled
        case notConfigured
        case noIdentityToken
        case failed(String)

        var errorDescription: String? {
            switch self {
            case .cancelled:
                // Distinguished but never shown: backing out is a choice.
                return "Sign-in was cancelled."
            case .notConfigured:
                return "Google sign-in isn't set up in this build."
            case .noIdentityToken:
                return "Google didn't return a usable sign-in token. Try again."
            case .failed(let message):
                return message
            }
        }
    }

    /// The OAuth client for THIS app, out of the bundled Firebase plist.
    ///
    /// Read from the plist rather than taken from `FirebaseApp.options` so that
    /// this file needs no Firebase import — and read fresh rather than cached
    /// at launch, because a missing value is a configuration problem worth
    /// reporting at the moment someone taps the button.
    ///
    /// `CLIENT_ID` is absent from the plist when Google is not enabled as a
    /// sign-in provider for the iOS app in the Firebase console. If that is the
    /// case, enabling it and re-downloading `GoogleService-Info.plist` is the
    /// fix — there is nothing to change in code.
    static var clientID: String? {
        guard let url = Bundle.main.url(forResource: "GoogleService-Info", withExtension: "plist"),
              let plist = NSDictionary(contentsOf: url),
              let id = plist["CLIENT_ID"] as? String,
              !id.isEmpty else { return nil }
        return id
    }

    /// The custom URL scheme the OAuth redirect comes back on.
    ///
    /// Exposed so the setup can be checked rather than only documented: the
    /// scheme has to be in `Info.plist` under `CFBundleURLTypes`, and when it
    /// is missing the sheet opens and then never returns — which looks like a
    /// hang rather than a misconfiguration.
    static var reversedClientID: String? {
        guard let url = Bundle.main.url(forResource: "GoogleService-Info", withExtension: "plist"),
              let plist = NSDictionary(contentsOf: url) else { return nil }
        return plist["REVERSED_CLIENT_ID"] as? String
    }

    #if canImport(GoogleSignIn)

    static var isConfigured: Bool { clientID != nil }

    @MainActor
    static func signIn() async throws -> Result {
        guard let clientID else { throw Failure.notConfigured }
        guard let presenter = UIApplication.wbTopViewController() else {
            throw Failure.failed("Couldn't find a window to present sign-in from.")
        }

        // Set every time rather than once at launch. It is idempotent, and it
        // means the flow cannot run against a configuration that was never
        // installed because a bootstrap ordering changed.
        GIDSignIn.sharedInstance.configuration = GIDConfiguration(clientID: clientID)

        do {
            let result = try await GIDSignIn.sharedInstance.signIn(withPresenting: presenter)
            guard let idToken = result.user.idToken?.tokenString else {
                throw Failure.noIdentityToken
            }
            return Result(
                idToken: idToken,
                accessToken: result.user.accessToken.tokenString,
                email: result.user.profile?.email,
                displayName: result.user.profile?.name
            )
        } catch let failure as Failure {
            throw failure
        } catch {
            throw self.failure(from: error)
        }
    }

    /// Hands a redirect back to the SDK. Wired up in `WealthBoardApp`.
    @discardableResult
    static func handle(_ url: URL) -> Bool {
        GIDSignIn.sharedInstance.handle(url)
    }

    /// Maps Google's error onto ours so a cancel can be told apart.
    ///
    /// The SDK reports dismissal as an error, like Apple's does, and it is not
    /// one as far as the user is concerned.
    static func failure(from error: Error) -> Failure {
        let nsError = error as NSError
        if nsError.domain == kGIDSignInErrorDomain,
           nsError.code == GIDSignInError.canceled.rawValue {
            return .cancelled
        }
        return .failed(error.localizedDescription)
    }

    #else

    static var isConfigured: Bool { false }

    @MainActor
    static func signIn() async throws -> Result {
        throw Failure.notConfigured
    }

    @discardableResult
    static func handle(_ url: URL) -> Bool { false }

    static func failure(from error: Error) -> Failure {
        .failed(error.localizedDescription)
    }

    #endif
}

extension UIApplication {
    /// The view controller to present a sheet from.
    ///
    /// Google's SDK takes a presenter rather than finding one itself, and
    /// handing it the wrong one is how a sheet ends up attached to a window
    /// the user cannot see — or refuses to appear because something is already
    /// presented there. So: the foreground scene's key window, then down
    /// through whatever it has already presented.
    static func wbTopViewController() -> UIViewController? {
        let scenes = shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let scene = scenes.first { $0.activationState == .foregroundActive } ?? scenes.first
        var controller = scene?.keyWindow?.rootViewController
        while let presented = controller?.presentedViewController {
            controller = presented
        }
        return controller
    }
}
