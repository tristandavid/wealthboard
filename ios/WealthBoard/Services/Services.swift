import Foundation

// The three third-party services the Android build depends on — Firebase Auth,
// Firestore sync and AdMob — are declared here as protocols with local no-op
// implementations.
//
// The reason to define them now rather than later: the screens that use them
// (Menu's sign-in row, cloud backup and restore, the premium gate on Reports)
// are real screens with real states, and writing them against a protocol means
// adding the real SDK later is a matter of writing one conforming type and
// changing one line in `Services`. Writing them against nothing would mean
// rebuilding those flows from scratch when the SDK arrives.
//
// Nothing here talks to a network, and the app has no external package
// dependencies as a result — it builds and runs from a fresh clone.

// MARK: - Auth

struct AuthUser: Equatable {
    let id: String
    let email: String?
    let displayName: String?
}

enum AuthError: LocalizedError {
    case notConfigured
    case invalidCredentials
    case network
    /// Deleting an account is a sensitive operation, and the provider refuses
    /// it on a session that has been open a while. The user has to prove who
    /// they are again first.
    case requiresRecentLogin

    var errorDescription: String? {
        switch self {
        case .notConfigured:
            return "Sign-in isn't set up in this build yet."
        case .invalidCredentials:
            return "That email and password didn't match an account."
        case .network:
            return "Couldn't reach the sign-in service. Check your connection."
        case .requiresRecentLogin:
            return "For your security, please sign out and sign back in, then delete your account."
        }
    }
}

@MainActor
protocol AuthService: AnyObject {
    /// The signed-in user, or nil. Read directly by the Menu row, so it must be
    /// cheap and synchronous.
    var currentUser: AuthUser? { get }

    /// True when a real backend is wired up. The UI uses this to decide between
    /// showing the sign-in flow and explaining that it isn't available yet —
    /// which is more honest than a sign-in form that always fails.
    var isConfigured: Bool { get }

    func signIn(email: String, password: String) async throws -> AuthUser
    func register(email: String, password: String) async throws -> AuthUser
    func signOut() async

    /// Permanently deletes the signed-in account.
    ///
    /// Required to exist and to be reachable from inside the app: both stores
    /// treat an account you can create in the app but only delete by emailing
    /// someone as a policy violation. Throws `.requiresRecentLogin` when the
    /// session is too old for the provider to accept the request.
    ///
    /// Deletes the ACCOUNT. The portfolio on the device is the user's own data
    /// and is left alone — see `ProfileView`, which says so before asking.
    func deleteAccount() async throws

    /// Sends a password-reset email. Separate from `signIn` because it is the
    /// one flow that has to work for someone who cannot sign in.
    func sendPasswordReset(email: String) async throws

    /// Federated sign-in. Each flag is false with no SDK behind it, so the
    /// button is hidden rather than shown as a control that always fails.
    ///
    /// Both options are offered because App Store guideline 4.8 requires an app
    /// offering any third-party sign-in to offer Sign in with Apple as well.
    /// Removing Apple's while keeping Google's is a rejection, so the two
    /// belong together.
    var isGoogleAvailable: Bool { get }
    func signInWithGoogle() async throws -> AuthUser

    var isAppleAvailable: Bool { get }
    func signInWithApple() async throws -> AuthUser
}

extension AuthService {
    /// The one account that sees the bug-report console. Matching on email
    /// rather than a role claim keeps this to one constant while there is
    /// exactly one administrator.
    var isAdministrator: Bool {
        currentUser?.email?.caseInsensitiveCompare(Services.administratorEmail) == .orderedSame
    }
}

/// The no-op implementation shipped by default.
@MainActor
final class UnconfiguredAuthService: AuthService {
    var currentUser: AuthUser? { nil }
    var isConfigured: Bool { false }

    func signIn(email: String, password: String) async throws -> AuthUser {
        throw AuthError.notConfigured
    }

    func register(email: String, password: String) async throws -> AuthUser {
        throw AuthError.notConfigured
    }

    func signOut() async {}

    func deleteAccount() async throws {
        throw AuthError.notConfigured
    }

    func sendPasswordReset(email: String) async throws {
        throw AuthError.notConfigured
    }

    var isGoogleAvailable: Bool { false }

    func signInWithGoogle() async throws -> AuthUser {
        throw AuthError.notConfigured
    }

    var isAppleAvailable: Bool { false }

    func signInWithApple() async throws -> AuthUser {
        throw AuthError.notConfigured
    }
}

// MARK: - Cloud sync

enum CloudState: Equatable {
    case idle
    case working(String)
    case done(String)
    case failed(String)
}

@MainActor
protocol CloudSyncService: AnyObject {
    var isConfigured: Bool { get }
    /// When the last successful backup finished, or nil if never.
    var lastBackupAt: Date? { get }

    func backup(_ document: PortfolioDocument) async throws
    func restore() async throws -> CloudRestore

    /// Removes this account's cloud copy.
    ///
    /// Called as part of deleting an account: leaving a portfolio behind in the
    /// cloud after the account that owned it is gone is precisely the thing the
    /// deletion requirement exists to prevent.
    func deleteBackup() async throws
}

/// A restored portfolio, plus anything the user should be told about where it
/// came from.
///
/// The note exists for one case: the copy that came down was written by the
/// Android build rather than this one. That is worth saying out loud — it is
/// the difference between "restored 5 holdings" and "restored 5 holdings from
/// your Android phone, and 2 rows in it couldn't be read" — and a restore that
/// quietly drops rows is the failure cross-platform sync must never have.
struct CloudRestore {
    var document: PortfolioDocument
    var note: String?
}

enum CloudSyncError: LocalizedError {
    case notConfigured
    case noBackup
    /// The backup itself landed; only the copy the Android build reads did not.
    ///
    /// A distinct case rather than a generic failure because the outcome is
    /// genuinely different: this device can still restore, and telling someone
    /// their backup failed when it did not would have them retry — or worse,
    /// distrust a backup that is fine.
    case crossPlatformCopyFailed

    var errorDescription: String? {
        switch self {
        case .notConfigured:
            return "Cloud backup isn't set up in this build yet."
        case .noBackup:
            return "There's no backup on this account to restore from."
        case .crossPlatformCopyFailed:
            return "Backed up, but the copy your Android phone reads couldn't be written. "
                + "This device will still restore normally — try again if you want it on Android too."
        }
    }
}

@MainActor
final class UnconfiguredCloudSyncService: CloudSyncService {
    var isConfigured: Bool { false }
    var lastBackupAt: Date? { nil }

    func backup(_ document: PortfolioDocument) async throws {
        throw CloudSyncError.notConfigured
    }

    func restore() async throws -> CloudRestore {
        throw CloudSyncError.notConfigured
    }

    func deleteBackup() async throws {
        throw CloudSyncError.notConfigured
    }
}

// MARK: - Ads and the premium gate

/// The Android build gates cloud sync and PDF reports behind a rewarded ad.
/// The gate is modelled here so those screens keep their shape, but with no ad
/// SDK present `isAvailable` is false and the gate lets everything through
/// rather than blocking a feature behind a video that can never play.
@MainActor
protocol AdService: AnyObject {
    var isAvailable: Bool { get }
    /// Returns true when the reward was earned. With no SDK this returns true
    /// immediately, so the feature is simply not gated.
    func showRewardedAd() async -> Bool
}

@MainActor
final class NoAdsService: AdService {
    var isAvailable: Bool { false }
    func showRewardedAd() async -> Bool { true }
}

// MARK: - Bug reports

/// One submitted report, as the admin console lists them.
struct BugReport: Identifiable, Hashable {
    let id: String
    var subject: String
    var detail: String
    var userEmail: String
    var submittedAt: Date
    /// "open" or "resolved".
    var status: String

    var isResolved: Bool { status == "resolved" }
}

enum BugReportError: LocalizedError {
    case notConfigured

    var errorDescription: String? {
        "Bug reporting isn't set up in this build yet — use the email option instead."
    }
}

@MainActor
protocol BugReportService: AnyObject {
    var isConfigured: Bool { get }

    func submit(subject: String, detail: String, replyTo: String) async throws

    // Admin-only. Implementations are expected to enforce that themselves; the
    // screen checking `isAdministrator` is a UI convenience, not the boundary.
    func loadAll() async throws -> [BugReport]
    func setStatus(_ id: String, status: String) async throws
    func delete(_ id: String) async throws
}

@MainActor
final class UnconfiguredBugReportService: BugReportService {
    var isConfigured: Bool { false }

    func submit(subject: String, detail: String, replyTo: String) async throws {
        throw BugReportError.notConfigured
    }

    func loadAll() async throws -> [BugReport] {
        throw BugReportError.notConfigured
    }

    func setStatus(_ id: String, status: String) async throws {
        throw BugReportError.notConfigured
    }

    func delete(_ id: String) async throws {
        throw BugReportError.notConfigured
    }
}

// MARK: - Container

/// One place the app reaches for its services, so swapping in a real
/// implementation is a single edit rather than a search through the screens.
@MainActor
enum Services {
    static var auth: AuthService = UnconfiguredAuthService()
    static var cloud: CloudSyncService = UnconfiguredCloudSyncService()
    static var ads: AdService = NoAdsService()
    static var bugReports: BugReportService = UnconfiguredBugReportService()
    static var subscriptions: SubscriptionService = UnconfiguredSubscriptionService()

    /// True when this install should see ads.
    ///
    /// One place, because the answer is the product decision rather than a
    /// property of the ad SDK: the free tier carries ads, Premium does not, and
    /// a build with no ad network configured shows none either. Screens ask
    /// this rather than `ads.isAvailable` so that "has the user paid" and "is
    /// there an ad to show" cannot drift apart.
    static var showsAds: Bool {
        ads.isAvailable && !subscriptions.isPremium
    }

    /// Mirrors `FirebaseManager.ADMIN_EMAIL` on the Android side.
    static let administratorEmail = "tristandavid20@gmail.com"

    /// Why sign-in is unavailable, in terms of what to do about it.
    ///
    /// `isConfigured == false` has three different causes and the same
    /// symptom, which made "accounts aren't switched on in this build" a dead
    /// end — it says the state without saying which of the three it is. This
    /// names it. Shown only in DEBUG: it is a note to whoever is building the
    /// app, and shipping build instructions to a user would be worse than
    /// saying nothing.
    static var authSetupHint: String {
        #if canImport(FirebaseAuth) && canImport(FirebaseCore)
        if Bundle.main.url(forResource: "GoogleService-Info", withExtension: "plist") == nil {
            return """
            Firebase is linked, but GoogleService-Info.plist isn't in the app \
            bundle. Select it in Xcode and tick WealthBoard under Target \
            Membership (File inspector, right-hand panel).
            """
        }
        return """
        Firebase is linked and the plist is bundled, but FirebaseApp didn't \
        start. Check the plist's BUNDLE_ID is ca.tristan.wealthboard.
        """
        #else
        return """
        The Firebase packages aren't in the project yet — that's all this is. \
        Xcode ▸ File ▸ Add Package Dependencies… ▸ \
        https://github.com/firebase/firebase-ios-sdk ▸ add FirebaseAuth and \
        FirebaseFirestore to the WealthBoard target.
        """
        #endif
    }
}
