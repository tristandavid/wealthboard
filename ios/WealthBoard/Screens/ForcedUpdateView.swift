import SwiftUI

/// Shown instead of the app when this build has been retired.
///
/// Deliberately a dead end: no navigation, no dismiss, no "later". A soft
/// version of this screen is worse than none, because the whole reason a build
/// gets retired is that continuing to use it does damage — and an escape hatch
/// means the people most likely to keep using it are the ones who found it.
///
/// What it still does say is that the user's data is fine and local. "Update
/// required" with nothing else reads, to someone with a hand-entered
/// portfolio, like their data is being held hostage.
struct ForcedUpdateView: View {
    let message: String?
    let storeURL: String?

    @Environment(\.colorScheme) private var scheme

    var body: some View {
        ZStack {
            Palette.background(scheme).ignoresSafeArea()

            VStack(spacing: 0) {
                Image(systemName: "arrow.down.circle.fill")
                    .font(.system(size: 52))
                    .foregroundStyle(Palette.accent(scheme))
                    .padding(.bottom, 20)

                Text("Update required")
                    .font(.system(size: 22, weight: .bold))
                    .foregroundStyle(Palette.onSurface(scheme))
                    .padding(.bottom, 10)

                Text(message?.isEmpty == false
                     ? message!
                     : "This version of WealthBoard can no longer be used. Updating takes a moment and fixes it.")
                    .font(.wbBodyMedium)
                    .multilineTextAlignment(.center)
                    .foregroundStyle(Palette.onSurfaceVariant(scheme))
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.bottom, 16)

                Text("Everything you've entered is stored on this device and is not affected by updating.")
                    .font(.system(size: 12))
                    .multilineTextAlignment(.center)
                    .foregroundStyle(Palette.onSurfaceVariant(scheme))
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.bottom, 28)

                Button {
                    UpdateGate.openStore(storeURL)
                } label: {
                    Text("Update now")
                        .font(.wbBodyLarge)
                        .fontWeight(.semibold)
                        .foregroundStyle(Palette.onAccent(scheme))
                        .frame(maxWidth: .infinity)
                        .frame(height: 50)
                        .background(Capsule().fill(Palette.accent(scheme)))
                }
                .buttonStyle(.plain)

                // The build number, because the first thing a support
                // conversation needs is which version is actually installed.
                Text("Installed: build \(UpdateGate.currentBuild)")
                    .font(.system(size: 11))
                    .foregroundStyle(Palette.onSurfaceVariant(scheme))
                    .padding(.top, 20)
            }
            .padding(.horizontal, 32)
        }
        // No interactive dismiss, no swipe-back, nothing behind it to reach.
        .interactiveDismissDisabled(true)
    }
}
