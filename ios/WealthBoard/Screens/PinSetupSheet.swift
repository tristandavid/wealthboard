import SwiftUI

/// Sets a six-digit app PIN, entered twice.
///
/// Confirmation is not ceremony: the PIN is hashed on save, so a typo in a
/// single-entry field would lock the owner out of their own app with nothing to
/// compare against and no way back in short of deleting it.
struct PinSetupSheet: View {
    @Environment(\.colorScheme) private var scheme
    @Environment(\.dismiss) private var dismiss

    let onSave: (String) -> Void

    @State private var pin = ""
    @State private var confirmation = ""
    @State private var error: String?

    private var canSave: Bool {
        pin.count == 6 && confirmation.count == 6
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    Text("Pick a six-digit PIN. It's hashed before it's stored, so there is no way to recover it — only to turn the lock off from inside the app while it is unlocked.")
                        .font(.wbBodySmall)
                        .foregroundStyle(Palette.onSurfaceVariant(scheme))
                        .fixedSize(horizontal: false, vertical: true)

                    WbCard {
                        Text("New PIN")
                            .font(.wbBodySmall)
                            .foregroundStyle(Palette.onSurfaceVariant(scheme))
                        SecureField("••••••", text: $pin)
                            .keyboardType(.numberPad)
                            .onChange(of: pin) { value in
                                let digits = String(value.filter(\.isNumber).prefix(6))
                                if digits != value { pin = digits }
                            }
                            .padding(.bottom, 10)

                        WbDivider().padding(.bottom, 10)

                        Text("Confirm PIN")
                            .font(.wbBodySmall)
                            .foregroundStyle(Palette.onSurfaceVariant(scheme))
                        SecureField("••••••", text: $confirmation)
                            .keyboardType(.numberPad)
                            .onChange(of: confirmation) { value in
                                let digits = String(value.filter(\.isNumber).prefix(6))
                                if digits != value { confirmation = digits }
                            }
                    }

                    if let error {
                        Text(error)
                            .font(.wbBodySmall)
                            .foregroundStyle(Brand.loss)
                    }

                    Button {
                        save()
                    } label: {
                        Text("Save PIN")
                            .font(.wbBodyLarge)
                            .fontWeight(.semibold)
                            .foregroundStyle(Palette.onAccent(scheme))
                            .frame(maxWidth: .infinity)
                            .frame(height: 48)
                            .background(
                                canSave ? Palette.accent(scheme) : Palette.surfaceVariant(scheme),
                                in: Capsule()
                            )
                    }
                    .buttonStyle(.plain)
                    .disabled(!canSave)
                }
                .padding(WbDimens.screenPadding)
            }
            .wbScreenBackground(scheme)
            .navigationTitle("Set a PIN")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
    }

    private func save() {
        guard pin.count == 6 else {
            error = "The PIN needs to be six digits."
            return
        }
        guard pin == confirmation else {
            error = "Those two don't match."
            return
        }
        onSave(pin)
        dismiss()
    }
}
