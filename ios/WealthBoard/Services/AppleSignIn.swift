import AuthenticationServices
import CryptoKit
import Foundation
import UIKit

/// The Sign in with Apple half of federated sign-in.
///
/// Separate from `FirebaseAuthService` for the same reason `GoogleSignInFlow`
/// is: this part is pure AuthenticationServices and compiles with no Firebase
/// present, while exchanging the result for a Firebase credential needs the
/// SDK. Keeping them apart means the build without Firebase still compiles.
///
/// ## Why this exists at all
///
/// App Store guideline 4.8: an app offering any third-party sign-in must offer
/// Sign in with Apple too. The Google button on the sign-in screen puts this
/// build in scope, so shipping without this is a rejection.
///
/// ## The nonce
///
/// Apple returns a signed identity token. Firebase has to be able to prove
/// that token was minted for *this* sign-in attempt and not replayed from
/// another one, so a random nonce is generated here, its SHA-256 goes to Apple
/// inside the request, and the RAW value goes to Firebase alongside the token.
/// Firebase hashes the raw value and checks it matches the hash Apple signed
/// into the token. Sending the hashed value to Firebase — an easy mistake,
/// since both are "the nonce" — fails every time with an opaque error.
enum AppleSignInFlow {

    struct Result {
        /// Apple's signed identity token, as the string Firebase wants.
        let identityToken: String
        /// The RAW nonce, not the hash that went to Apple.
        let nonce: String
        /// Present on the FIRST sign-in only — see `displayName`.
        let email: String?
        let displayName: String?
    }

    enum Failure: LocalizedError {
        case cancelled
        case noIdentityToken

        var errorDescription: String? {
            switch self {
            case .cancelled:
                return "Sign in with Apple was cancelled."
            case .noIdentityToken:
                return "Apple didn't return a sign-in token. Please try again."
            }
        }
    }

    /// Runs the system sheet and returns what Apple gave back.
    ///
    /// Apple sends the name and email exactly ONCE, on the very first
    /// authorization for this app and Apple ID, and never again — not on the
    /// next sign-in, not after a reinstall. Whatever comes back here has to be
    /// stored now or it is gone, which is why the caller writes the display
    /// name onto the Firebase user immediately rather than reading it back
    /// later.
    @MainActor
    static func signIn() async throws -> Result {
        let rawNonce = randomNonce()

        let request = ASAuthorizationAppleIDProvider().createRequest()
        request.requestedScopes = [.fullName, .email]
        request.nonce = sha256(rawNonce)

        let authorization = try await Delegate.perform(request: request)

        guard let credential = authorization.credential as? ASAuthorizationAppleIDCredential,
              let tokenData = credential.identityToken,
              let token = String(data: tokenData, encoding: .utf8) else {
            throw Failure.noIdentityToken
        }

        var name: String?
        if let components = credential.fullName {
            let parts = [components.givenName, components.familyName].compactMap { $0 }
            let joined = parts.joined(separator: " ").trimmingCharacters(in: .whitespaces)
            name = joined.isEmpty ? nil : joined
        }

        return Result(
            identityToken: token,
            nonce: rawNonce,
            email: credential.email,
            displayName: name
        )
    }

    // MARK: - Running the sheet

    /// Bridges `ASAuthorizationController`'s delegate callbacks to async/await.
    ///
    /// Held in a static until the callback fires: the controller keeps only a
    /// weak reference to its delegate, so a locally-scoped one is deallocated
    /// the moment this function returns and the sheet then completes into
    /// nothing — the continuation never resumes and the sign-in hangs forever
    /// with no error.
    @MainActor
    private final class Delegate: NSObject,
                                  ASAuthorizationControllerDelegate,
                                  ASAuthorizationControllerPresentationContextProviding {

        private static var retained: Delegate?
        private var continuation: CheckedContinuation<ASAuthorization, Error>?

        static func perform(request: ASAuthorizationAppleIDRequest) async throws -> ASAuthorization {
            let delegate = Delegate()
            retained = delegate
            defer { retained = nil }

            return try await withCheckedThrowingContinuation { continuation in
                delegate.continuation = continuation
                let controller = ASAuthorizationController(authorizationRequests: [request])
                controller.delegate = delegate
                controller.presentationContextProvider = delegate
                controller.performRequests()
            }
        }

        func authorizationController(
            controller: ASAuthorizationController,
            didCompleteWithAuthorization authorization: ASAuthorization
        ) {
            continuation?.resume(returning: authorization)
            continuation = nil
        }

        func authorizationController(
            controller: ASAuthorizationController,
            didCompleteWithError error: Error
        ) {
            // A cancel is not a failure, and showing "the operation couldn't be
            // completed" to someone who deliberately tapped Cancel is noise.
            // Mapped here so the caller can swallow just this one case.
            if let authError = error as? ASAuthorizationError, authError.code == .canceled {
                continuation?.resume(throwing: Failure.cancelled)
            } else {
                continuation?.resume(throwing: error)
            }
            continuation = nil
        }

        func presentationAnchor(for controller: ASAuthorizationController) -> ASPresentationAnchor {
            let scene = UIApplication.shared.connectedScenes
                .compactMap { $0 as? UIWindowScene }
                .first { $0.activationState == .foregroundActive }
                ?? UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first

            return scene?.windows.first { $0.isKeyWindow }
                ?? scene?.windows.first
                ?? ASPresentationAnchor()
        }
    }

    // MARK: - Nonce

    /// A cryptographically random string, per Apple's own sample.
    private static func randomNonce(length: Int = 32) -> String {
        let charset = Array("0123456789ABCDEFGHIJKLMNOPQRSTUVXYZabcdefghijklmnopqrstuvwxyz-._")
        var result = ""
        var remaining = length

        while remaining > 0 {
            var random: UInt8 = 0
            let status = SecRandomCopyBytes(kSecRandomDefault, 1, &random)
            guard status == errSecSuccess else {
                // SecRandomCopyBytes failing means the system RNG is
                // unavailable, which is not a condition to paper over with a
                // weaker source — a predictable nonce defeats the whole point.
                fatalError("Unable to generate a secure nonce (SecRandomCopyBytes: \(status))")
            }
            if random < charset.count {
                result.append(charset[Int(random)])
                remaining -= 1
            }
        }
        return result
    }

    private static func sha256(_ input: String) -> String {
        SHA256.hash(data: Data(input.utf8))
            .map { String(format: "%02x", $0) }
            .joined()
    }
}
