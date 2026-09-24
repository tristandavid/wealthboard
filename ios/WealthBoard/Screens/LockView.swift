import Combine
import SwiftUI

/// The gate in front of the app when the lock is on.
///
/// Biometric mode raises the system prompt as soon as the view appears and
/// offers a retry button if the person dismisses it; PIN mode shows the field
/// with the escalating lockout enforced.
struct LockView: View {
    let onUnlocked: () -> Void

    @State private var pin = ""
    @State private var error: String?
    @State private var lockoutRemaining: TimeInterval = 0

    private let tick = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    var body: some View {
        ZStack {
            LinearGradient(
                colors: [Brand.navy, Brand.navyDark],
                startPoint: .top,
                endPoint: .bottom
            )
            .ignoresSafeArea()

            VStack(spacing: 0) {
                Text("WealthBoard")
                    .font(.system(size: 28, weight: .bold))
                    .foregroundStyle(.white)
                Text("Locked")
                    .font(.wbBodyMedium)
                    .foregroundStyle(.white.opacity(0.7))
                    .padding(.top, 4)

                if let error {
                    Text(error)
                        .font(.wbBodySmall)
                        .foregroundStyle(Brand.lossOnDark)
                        .multilineTextAlignment(.center)
                        .padding(.top, 20)
                        .padding(.horizontal, 24)
                }

                if AppLock.mode == .pin {
                    pinEntry.padding(.top, 24)
                } else {
                    Button("Unlock") {
                        Task { await authenticate() }
                    }
                    .font(.wbBodyLarge)
                    .fontWeight(.semibold)
                    .foregroundStyle(Palette.onAccent(.light))
                    .padding(.horizontal, 28)
                    .padding(.vertical, 13)
                    .background(Brand.goldLight, in: Capsule())
                    .padding(.top, 24)
                }
            }
            .padding(24)
        }
        .task {
            lockoutRemaining = AppLock.lockoutRemaining
            if AppLock.mode == .biometric { await authenticate() }
        }
        .onReceive(tick) { _ in
            let remaining = AppLock.lockoutRemaining
            if remaining != lockoutRemaining { lockoutRemaining = remaining }
        }
    }

    private var pinEntry: some View {
        VStack(spacing: 12) {
            SecureField("Enter PIN", text: $pin)
                .keyboardType(.numberPad)
                .multilineTextAlignment(.center)
                .font(.system(size: 22, weight: .semibold, design: .rounded))
                .foregroundStyle(.white)
                .padding(.vertical, 12)
                .frame(width: 180)
                .background(.white.opacity(0.12), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                .onChange(of: pin) { value in
                    let digits = String(value.filter(\.isNumber).prefix(6))
                    if digits != value { pin = digits }
                }

            if lockoutRemaining > 0 {
                Text("Too many attempts. Try again in \(Int(lockoutRemaining))s.")
                    .font(.wbBodySmall)
                    .foregroundStyle(.white.opacity(0.8))
            } else {
                Button("Unlock") { submit() }
                    .font(.wbBodyLarge)
                    .fontWeight(.semibold)
                    .foregroundStyle(Palette.onAccent(.light))
                    .padding(.horizontal, 28)
                    .padding(.vertical, 13)
                    .background(Brand.goldLight, in: Capsule())
                    .disabled(pin.count < 4)
            }
        }
    }

    private func submit() {
        if AppLock.verify(pin: pin) {
            onUnlocked()
        } else {
            error = AppLock.lockoutRemaining > 0
                ? "Too many attempts."
                : "Incorrect PIN"
            pin = ""
            lockoutRemaining = AppLock.lockoutRemaining
        }
    }

    private func authenticate() async {
        error = nil
        switch await AppLock.authenticateWithBiometrics() {
        case .success:
            onUnlocked()
        case .failure(let failure):
            error = failure.localizedDescription
        }
    }
}
