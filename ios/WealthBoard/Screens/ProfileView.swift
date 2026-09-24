import SwiftUI

/// The signed-in account: who you are, what the account unlocks, and the way
/// out. Signed out, it hands straight over to `LoginView` rather than showing
/// an empty profile.
struct ProfileView: View {
    @Environment(\.colorScheme) private var scheme
    @Environment(\.dismiss) private var dismiss

    @State private var showSignOutConfirm = false
    @State private var showDeleteConfirm = false
    @State private var deleting = false
    @State private var deleteError: String?

    /// Watched rather than polled — the same publisher the Menu row uses, so
    /// signing in on this screen updates the row behind it too.
    @ObservedObject private var session = AuthSession.shared

    private var user: AuthUser? { session.user }

    var body: some View {
        Group {
            if let user {
                signedIn(user)
            } else {
                LoginView()
            }
        }
        .onAppear { session.refresh() }
        // Signed out, this hands straight over to LoginView, which titles
        // itself "Sign In" or "Create Account" — so the title is only set here
        // when there is actually an account to show.
        .navigationTitle(user != nil ? "My Account" : "")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func signedIn(_ user: AuthUser) -> some View {
        ScrollView {
            VStack(spacing: 12) {
                identityCard(user)
                // Cloud backup and the bug-report console used to sit here as
                // well, and both were the lesser of two copies: backup is the
                // same feature as Menu ▸ Backup & Restore, which also offers
                // the file export and which a signed-out user can reach, and
                // the console is Menu ▸ Bug Reports. This screen is now the one
                // thing only it can answer — who is signed in, and how to stop
                // being signed in.
                signOutButton
                deleteAccountButton

                if let deleteError {
                    Text(deleteError)
                        .font(.wbBodySmall)
                        .foregroundStyle(Brand.loss)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .fixedSize(horizontal: false, vertical: true)
                }

                Color.clear.frame(height: 24)
            }
            .padding(WbDimens.screenPadding)
        }
        .wbScreenBackground(scheme)
        .confirmationDialog(
            "Sign out?",
            isPresented: $showSignOutConfirm,
            titleVisibility: .visible
        ) {
            Button("Sign Out", role: .destructive) {
                Task {
                    await Services.auth.signOut()
                    session.refresh()
                    dismiss()
                }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Your portfolio stays on this device. Only cloud backup and restore stop working until you sign back in.")
        }
        .confirmationDialog(
            "Delete your account?",
            isPresented: $showDeleteConfirm,
            titleVisibility: .visible
        ) {
            Button("Delete account", role: .destructive) {
                Task { await deleteAccount() }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            // Says exactly what goes and what stays. "Delete account" is the
            // one action in the app nobody can undo, and a user who thinks it
            // also wipes the portfolio on their phone — or who thinks it
            // doesn't — has been misled either way.
            Text("This permanently deletes your account and your cloud backup. It can't be undone.\n\nThe portfolio on this device is yours and stays where it is. Export it from Backup & Restore first if you want a copy.")
        }
    }

    private func identityCard(_ user: AuthUser) -> some View {
        WbCard {
            HStack(spacing: 14) {
                ZStack {
                    Circle().fill(Palette.accent(scheme).opacity(0.18))
                    Image(systemName: "person.fill")
                        .font(.system(size: 22))
                        .foregroundStyle(Palette.accent(scheme))
                }
                .frame(width: 52, height: 52)

                VStack(alignment: .leading, spacing: 3) {
                    Text(user.displayName ?? user.email ?? "Signed in")
                        .font(.wbTitleMedium)
                        .foregroundStyle(Palette.onSurface(scheme))
                    if let email = user.email {
                        Text(email)
                            .font(.wbBodySmall)
                            .foregroundStyle(Palette.onSurfaceVariant(scheme))
                    }
                    StatusPill(
                        text: session.isAdministrator ? "Administrator" : "Member",
                        color: session.isAdministrator ? Brand.divIndigo : Brand.gain
                    )
                    .padding(.top, 2)
                }
                Spacer(minLength: 0)
            }
        }
    }

    /// Required to exist and to be reachable from inside the app: both stores
    /// treat an account you can create here but can only delete by emailing
    /// someone as a policy violation.
    private var deleteAccountButton: some View {
        Button {
            deleteError = nil
            showDeleteConfirm = true
        } label: {
            ZStack {
                Text("Delete Account")
                    .opacity(deleting ? 0 : 1)
                if deleting { ProgressView().tint(Brand.loss) }
            }
            .font(.wbBodyMedium)
            .fontWeight(.medium)
            .foregroundStyle(Brand.loss)
            .frame(maxWidth: .infinity)
            .frame(height: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(deleting)
    }

    private func deleteAccount() async {
        deleting = true
        deleteError = nil
        defer { deleting = false }

        do {
            try await Services.auth.deleteAccount()
            session.refresh()
            dismiss()
        } catch {
            deleteError = error.localizedDescription
        }
    }

    private var signOutButton: some View {
        Button {
            showSignOutConfirm = true
        } label: {
            Text("Sign Out")
                .font(.wbBodyLarge)
                .fontWeight(.semibold)
                .foregroundStyle(Brand.loss)
                .frame(maxWidth: .infinity)
                .frame(height: 48)
                .overlay(Capsule().stroke(Brand.loss.opacity(0.5), lineWidth: 1))
        }
        .buttonStyle(.plain)
    }
}
