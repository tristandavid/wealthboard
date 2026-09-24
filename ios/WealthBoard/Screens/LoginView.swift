import AuthenticationServices
import SwiftUI

/// Sign in or register, with password reset, and Apple and Google as the
/// federated options.
///
/// Laid out to match `LoginScreen.kt` on the Android side, screen element for
/// screen element: the branded title, the Google button, the "or" rule, two
/// outlined fields with leading icons, the primary action, and the register and
/// reset links beneath it. The two builds sign into the same Firebase project,
/// and someone moving between a phone and an iPad should not have to re-learn
/// where anything is.
///
/// Both federated buttons are here because App Store guideline 4.8 requires an
/// app offering any third-party sign-in to offer Sign in with Apple as well.
/// Apple's goes FIRST, which is also what the guideline asks for — its option
/// must be presented at least as prominently as the others, and on iOS it is
/// the one most people already have.
///
/// The whole screen adapts to `Services.auth.isConfigured`: with no backend it
/// says so plainly instead of showing a form that can only ever fail. A form
/// that always fails reads as a broken app; a sentence saying accounts aren't
/// switched on reads as a deliberate choice, which it is.
struct LoginView: View {
    @Environment(\.colorScheme) private var scheme
    @Environment(\.dismiss) private var dismiss

    /// Called once there is a signed-in user, so the caller can pop back.
    var onAuthenticated: (() -> Void)?

    @State private var isRegistering = false
    @State private var email = ""
    @State private var password = ""
    @State private var showPassword = false
    @State private var message: String?
    @State private var isError = true
    @State private var busy = false

    @FocusState private var focus: Field?
    private enum Field { case email, password }

    var body: some View {
        ScrollView {
            VStack(spacing: 0) {
                brand

                if Services.auth.isConfigured {
                    appleButton
                    googleButton
                    orRule
                    fields
                    primaryAction
                    links
                } else {
                    unavailableNote
                }

                if let message {
                    Text(message)
                        .font(.wbBodySmall)
                        .foregroundStyle(isError ? Brand.loss : Brand.gain)
                        .multilineTextAlignment(.center)
                        .frame(maxWidth: .infinity)
                        .padding(.top, 14)
                }

                Button("Continue without an account") { dismiss() }
                    .font(.wbBodyMedium)
                    .fontWeight(.medium)
                    .foregroundStyle(Palette.onSurfaceVariant(scheme))
                    .padding(.top, 34)

                Color.clear.frame(height: 32)
            }
            // 28pt rather than the app's usual 16: the Android screen uses the
            // same wider gutter, and a sign-in form is one column of controls
            // with nothing beside it to justify running full width.
            .padding(.horizontal, 28)
        }
        .wbScreenBackground(scheme)
        .navigationTitle(isRegistering ? "Create Account" : "Sign In")
        .navigationBarTitleDisplayMode(.inline)
    }

    // MARK: - Pieces

    private var brand: some View {
        VStack(spacing: 6) {
            Text("💰 WealthBoard")
                .font(.system(size: 28, weight: .bold))
                .foregroundStyle(Palette.accent(scheme))
            Text(isRegistering ? "Create your account" : "Sign in to continue")
                .font(.system(size: 15))
                .foregroundStyle(Palette.onSurfaceVariant(scheme))
        }
        .padding(.top, 40)
        .padding(.bottom, 32)
    }

    /// Sign in with Apple, first among the federated options.
    ///
    /// `SignInWithAppleButton` rather than a hand-built one: Apple's Human
    /// Interface Guidelines require the supplied button, with its own mark,
    /// corner radius and localized title, and review does check. The closure
    /// form is unused here — the request and the nonce are built inside
    /// `AppleSignInFlow` so the credential exchange and the nonce that proves
    /// it cannot drift apart — so this is a plain button wearing Apple's
    /// chrome.
    @ViewBuilder
    private var appleButton: some View {
        if Services.auth.isAppleAvailable {
            SignInWithAppleButton(.signIn) { _ in
                // Intentionally empty: the real request is made by
                // AppleSignInFlow, which owns the nonce.
            } onCompletion: { _ in }
                .signInWithAppleButtonStyle(scheme == .dark ? .white : .black)
                .frame(height: 50)
                // Full capsule rather than Apple's default slight rounding, to
                // read as one pair with the Google button below — both pill
                // shaped, both flipping black/white with the scheme.
                .clipShape(Capsule())
                .allowsHitTesting(false)
                .overlay {
                    Button {
                        Task { await signInWithApple() }
                    } label: {
                        Color.clear.contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .disabled(busy)
                }
                .accessibilityRepresentation {
                    Button("Sign in with Apple") {
                        Task { await signInWithApple() }
                    }
                }
                .padding(.bottom, 10)
        }
    }

    /// Google, above the form rather than below it.
    ///
    /// One tap against six fields' worth of typing: the federated option is
    /// what most people will use, and putting it under the password field makes
    /// them fill the form before discovering they needn't have. The Android
    /// screen leads with it for the same reason.
    ///
    /// Built by hand rather than from `GoogleSignInButton` (the SDK's own
    /// widget): that widget only ever renders as a slightly-rounded rectangle,
    /// which read as dated next to Apple's capsule above it. Google's branding
    /// guidelines allow a fully custom shape as long as the real multi-colour
    /// "G" mark, the standard wordmark text, and sufficient contrast are kept —
    /// all three hold here, so this stays compliant while matching the rest of
    /// the screen. The mark itself is `GoogleLogo`, the unmodified four-colour
    /// asset, on a white disc so it stays legible on both the black and white
    /// button backgrounds below.
    @ViewBuilder
    private var googleButton: some View {
        if Services.auth.isGoogleAvailable {
            Button {
                Task { await signInWithGoogle() }
            } label: {
                HStack(spacing: 10) {
                    ZStack {
                        Circle()
                            .fill(.white)
                            .frame(width: 26, height: 26)
                        Image("GoogleLogo")
                            .resizable()
                            .scaledToFit()
                            .frame(width: 17, height: 17)
                    }
                    Text("Continue with Google")
                        .font(.wbBodyLarge)
                        .fontWeight(.semibold)
                        .foregroundStyle(scheme == .dark ? .black : .white)
                }
                .frame(maxWidth: .infinity)
                .frame(height: 50)
                .background(scheme == .dark ? Color.white : Color.black, in: Capsule())
            }
            .buttonStyle(.plain)
            .disabled(busy)
        }
    }

    @ViewBuilder
    private var orRule: some View {
        if Services.auth.isGoogleAvailable {
            HStack(spacing: 12) {
                Rectangle()
                    .fill(Palette.outline(scheme))
                    .frame(height: 1)
                Text("or")
                    .font(.system(size: 13))
                    .foregroundStyle(Palette.onSurfaceVariant(scheme))
                Rectangle()
                    .fill(Palette.outline(scheme))
                    .frame(height: 1)
            }
            .padding(.vertical, 20)
        }
    }

    private var fields: some View {
        VStack(spacing: 12) {
            OutlinedField(
                systemImage: "envelope.fill",
                placeholder: "Email"
            ) {
                TextField("Email", text: $email)
                    .keyboardType(.emailAddress)
                    .textContentType(.emailAddress)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .submitLabel(.next)
                    .focused($focus, equals: .email)
                    .onSubmit { focus = .password }
            }

            OutlinedField(
                systemImage: "lock.fill",
                placeholder: "Password",
                trailing: {
                    Button {
                        showPassword.toggle()
                    } label: {
                        Image(systemName: showPassword ? "eye.slash.fill" : "eye.fill")
                            .foregroundStyle(Palette.onSurfaceVariant(scheme))
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(showPassword ? "Hide password" : "Show password")
                }
            ) {
                Group {
                    if showPassword {
                        TextField("Password", text: $password)
                    } else {
                        SecureField("Password", text: $password)
                    }
                }
                .textContentType(isRegistering ? .newPassword : .password)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .submitLabel(.done)
                .focused($focus, equals: .password)
                .onSubmit { Task { await submit() } }
            }
        }
    }

    private var primaryAction: some View {
        Button {
            Task { await submit() }
        } label: {
            ZStack {
                // The spinner replaces the label in place rather than resizing
                // the button, so the layout doesn't jump on every attempt.
                Text(isRegistering ? "Create Account" : "Sign In")
                    .opacity(busy ? 0 : 1)
                if busy {
                    ProgressView()
                        .tint(Palette.onAccent(scheme))
                }
            }
            .font(.wbBodyLarge)
            .fontWeight(.semibold)
            .foregroundStyle(Palette.onAccent(scheme))
            .frame(maxWidth: .infinity)
            .frame(height: 50)
            .background(Palette.accent(scheme), in: Capsule())
        }
        .buttonStyle(.plain)
        .disabled(busy)
        .padding(.top, 24)
    }

    private var links: some View {
        VStack(spacing: 4) {
            HStack(spacing: 6) {
                Text(isRegistering ? "Already have an account?" : "Don't have an account?")
                    .font(.system(size: 14))
                    .foregroundStyle(Palette.onSurfaceVariant(scheme))
                Button(isRegistering ? "Sign In" : "Register") {
                    isRegistering.toggle()
                    password = ""
                    message = nil
                }
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(Palette.accent(scheme))
                .buttonStyle(.plain)
            }

            if !isRegistering {
                Button("Forgot password?") {
                    Task { await resetPassword() }
                }
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(Palette.accent(scheme))
                .buttonStyle(.plain)
                .padding(.top, 10)
            }
        }
        .padding(.top, 16)
    }

    private var unavailableNote: some View {
        WbCard {
            Text("Accounts aren't switched on in this build")
                .font(.wbTitleMedium)
                .foregroundStyle(Palette.onSurface(scheme))
                .padding(.bottom, 6)
            Text("Everything you record is stored on this device, and nothing leaves it. Signing in and cloud backup arrive once an account service is connected.")
                .font(.wbBodySmall)
                .foregroundStyle(Palette.onSurfaceVariant(scheme))
                .fixedSize(horizontal: false, vertical: true)

            // Debug only — see `Services.authSetupHint`. Without this the
            // screen states the symptom and leaves the cause to guesswork,
            // and the cause is a one-line fix in Xcode.
            #if DEBUG
            WbDivider()
                .padding(.vertical, 10)
            Text("Setup")
                .font(.system(size: 10, weight: .bold))
                .foregroundStyle(Brand.divAmber)
                .padding(.bottom, 4)
            Text(Services.authSetupHint)
                .font(.system(size: 11))
                .foregroundStyle(Palette.onSurfaceVariant(scheme))
                .fixedSize(horizontal: false, vertical: true)
                .textSelection(.enabled)
            #endif
        }
    }

    // MARK: - Actions

    private func submit() async {
        let address = email.trimmingCharacters(in: .whitespaces)
        guard !address.isEmpty, !password.isEmpty else {
            fail("Please enter your email and password.")
            return
        }
        if isRegistering, password.count < 6 {
            fail("Password must be at least 6 characters.")
            return
        }

        busy = true
        defer { busy = false }
        message = nil
        do {
            _ = isRegistering
                ? try await Services.auth.register(email: address, password: password)
                : try await Services.auth.signIn(email: address, password: password)
            finish()
        } catch {
            fail(error.localizedDescription)
        }
    }

    private func resetPassword() async {
        let address = email.trimmingCharacters(in: .whitespaces)
        guard !address.isEmpty else {
            fail("Enter your email above first.")
            return
        }
        busy = true
        defer { busy = false }
        do {
            try await Services.auth.sendPasswordReset(email: address)
            message = "Reset link sent — check your email."
            isError = false
        } catch {
            fail(error.localizedDescription)
        }
    }

    private func signInWithGoogle() async {
        busy = true
        message = nil
        defer { busy = false }
        do {
            _ = try await Services.auth.signInWithGoogle()
            finish()
        } catch GoogleSignInFlow.Failure.cancelled {
            // Dismissing the sheet is a decision, and saying "cancelled" in red
            // underneath implies something broke.
        } catch {
            fail(error.localizedDescription)
        }
    }

    private func signInWithApple() async {
        busy = true
        message = nil
        defer { busy = false }
        do {
            _ = try await Services.auth.signInWithApple()
            finish()
        } catch AppleSignInFlow.Failure.cancelled {
            // Dismissing the sheet is a decision, not an error — same as Google.
        } catch {
            fail(error.localizedDescription)
        }
    }

    /// Publishes the new session before leaving.
    ///
    /// `AuthSession.refresh()` is what the Menu row and the profile screen are
    /// watching; without it they keep the answer they had when they were built,
    /// which is how a signed-in user ends up looking at "Sign In / Register".
    private func finish() {
        AuthSession.shared.refresh()
        // The bug-report console gates its listing on the signed-in email, so
        // it has to be re-created once there is an account to check.
        Services.bootstrapBugReports()
        onAuthenticated?()
        dismiss()
    }

    private func fail(_ text: String) {
        message = text
        isError = true
    }
}

// MARK: - Field

/// An outlined field with a leading icon and an optional trailing control —
/// Material's `OutlinedTextField`, which is what the Android screen uses, has
/// no counterpart in SwiftUI.
private struct OutlinedField<Content: View, Trailing: View>: View {
    @Environment(\.colorScheme) private var scheme

    let systemImage: String
    /// Used as the accessibility label: the field inside carries the visible
    /// placeholder, and VoiceOver reads the icon as nothing at all without it.
    let placeholder: String
    let trailing: () -> Trailing
    let content: () -> Content

    init(
        systemImage: String,
        placeholder: String,
        @ViewBuilder trailing: @escaping () -> Trailing = { EmptyView() },
        @ViewBuilder content: @escaping () -> Content
    ) {
        self.systemImage = systemImage
        self.placeholder = placeholder
        self.trailing = trailing
        self.content = content
    }

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: systemImage)
                .font(.system(size: 17))
                .foregroundStyle(Palette.onSurfaceVariant(scheme))
                .frame(width: 22)
            content()
                .font(.wbBodyLarge)
                .foregroundStyle(Palette.onSurface(scheme))
            trailing()
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel(placeholder)
        .padding(.horizontal, 14)
        .frame(height: 56)
        .overlay(
            RoundedRectangle(cornerRadius: 8, style: .continuous)
                .stroke(Palette.outline(scheme), lineWidth: 1)
        )
    }
}
