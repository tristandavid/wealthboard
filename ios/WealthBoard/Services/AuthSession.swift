import Combine
import Foundation

/// The signed-in user, as something SwiftUI can watch.
///
/// `Services.auth` is a protocol object held in a static, which is the right
/// shape for a service and the wrong one for a view: reading
/// `Services.auth.currentUser` inside a `body` gives the answer at the moment
/// that body ran and nothing ever invalidates it. That is the whole of the
/// "already signed in, still says Sign In / Register" bug — the Menu row asked
/// once, when the tab was first built, and was never told the answer had
/// changed.
///
/// This publishes it instead. Screens observe the session; anything that ends a
/// sign-in, a registration or a sign-out calls `refresh()`, and every screen
/// watching updates at once.
///
/// Deliberately a cache over the service rather than a second source of truth:
/// `refresh()` re-reads `Services.auth`, so the service stays the authority and
/// this cannot drift from it.
@MainActor
final class AuthSession: ObservableObject {
    static let shared = AuthSession()

    @Published private(set) var user: AuthUser?

    private init() {
        user = Services.auth.currentUser
    }

    var isSignedIn: Bool { user != nil }

    /// The one account that sees the bug-report console.
    var isAdministrator: Bool {
        user?.email?.caseInsensitiveCompare(Services.administratorEmail) == .orderedSame
    }

    /// What the account row should say.
    var menuLabel: String { isSignedIn ? "My Account" : "Sign In / Register" }

    /// Who is signed in, for the row's second line — the display name where
    /// there is one, the email otherwise, and a prompt when there is neither.
    var menuSubtitle: String? {
        guard let user else { return "Sign in to sync and back up" }
        return user.displayName ?? user.email
    }

    /// Re-reads the service.
    ///
    /// Called after any sign-in, registration or sign-out, and on the Menu
    /// appearing — the latter catches the case nothing in the app initiates,
    /// where a persisted Firebase session is restored a moment after launch.
    func refresh() {
        let latest = Services.auth.currentUser
        // Assigned only on a real change: @Published fires on every write, and
        // an every-appear write would re-render the menu for nothing.
        if latest != user { user = latest }
    }
}
