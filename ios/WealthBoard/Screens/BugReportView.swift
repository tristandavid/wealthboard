import SwiftUI
import UIKit

/// Report a bug or contact support.
///
/// Submits through `Services.bugReports` when one is configured and falls back
/// to a pre-filled mail composer otherwise — the same report either way, so the
/// screen never becomes a dead end just because no backend is wired up.
struct BugReportView: View {
    @Environment(\.colorScheme) private var scheme
    @Environment(\.openURL) private var openURL
    @Environment(\.dismiss) private var dismiss

    @State private var subject = ""
    @State private var detail = ""
    @State private var email = ""
    @State private var error: String?
    @State private var sent = false
    @State private var busy = false

    private static let prompt = """
    • What were you doing when it happened?
    • What did you expect to happen?
    • What actually happened?
    """

    var body: some View {
        Group {
            if sent {
                confirmation
            } else {
                form
            }
        }
        .wbScreenBackground(scheme)
        .navigationTitle("Contact Support")
        .navigationBarTitleDisplayMode(.inline)
    }

    // MARK: - Form

    private var form: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                WbCard {
                    Text("Report a Bug or Contact Support")
                        .font(.wbTitleMedium)
                        .foregroundStyle(Palette.onSurface(scheme))
                        .padding(.bottom, 4)
                    Text("Describe the issue you're experiencing. We'll review your report and get back to you.")
                        .font(.wbBodySmall)
                        .foregroundStyle(Palette.onSurfaceVariant(scheme))
                        .fixedSize(horizontal: false, vertical: true)
                }

                WbCard {
                    Text("Subject")
                        .font(.wbBodySmall)
                        .foregroundStyle(Palette.onSurfaceVariant(scheme))
                    TextField("e.g. App crashes on the Holdings screen", text: $subject)
                        .padding(.bottom, 10)

                    WbDivider().padding(.bottom, 10)

                    Text("Your email (for our reply)")
                        .font(.wbBodySmall)
                        .foregroundStyle(Palette.onSurfaceVariant(scheme))
                    TextField("you@example.com", text: $email)
                        .keyboardType(.emailAddress)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                }

                WbCard {
                    Text("Description")
                        .font(.wbBodySmall)
                        .foregroundStyle(Palette.onSurfaceVariant(scheme))
                        .padding(.bottom, 6)

                    ZStack(alignment: .topLeading) {
                        if detail.isEmpty {
                            // A placeholder that asks the three questions is
                            // what turns "it doesn't work" into a report
                            // somebody can act on.
                            Text(Self.prompt)
                                .font(.wbBodySmall)
                                .foregroundStyle(Palette.onSurfaceVariant(scheme).opacity(0.7))
                                .padding(.top, 8)
                                .padding(.leading, 5)
                                .allowsHitTesting(false)
                        }
                        TextEditor(text: $detail)
                            .frame(height: 170)
                            .scrollContentBackground(.hidden)
                    }

                    Text("The app version below is what identifies the exact build, and it is what makes a report actionable.")
                        .font(.system(size: 11))
                        .foregroundStyle(Palette.onSurfaceVariant(scheme))
                        .padding(.top, 8)
                    Text(environmentStamp)
                        .font(.system(size: 11, design: .monospaced))
                        .foregroundStyle(Palette.onSurfaceVariant(scheme))
                }

                if let error {
                    Text(error)
                        .font(.wbBodySmall)
                        .foregroundStyle(Brand.loss)
                }

                Button {
                    Task { await submit() }
                } label: {
                    Text("Submit Report")
                        .font(.wbBodyLarge)
                        .fontWeight(.semibold)
                        .foregroundStyle(Palette.onAccent(scheme))
                        .frame(maxWidth: .infinity)
                        .frame(height: 48)
                        .background(Palette.accent(scheme), in: Capsule())
                }
                .buttonStyle(.plain)
                .disabled(busy)

                Color.clear.frame(height: 24)
            }
            .padding(WbDimens.screenPadding)
        }
    }

    // MARK: - Confirmation

    private var confirmation: some View {
        VStack(spacing: 14) {
            Text("Report Sent ✓")
                .font(.wbTitleLarge)
                .foregroundStyle(Brand.gain)
            Text("Thank you! Your report has been submitted successfully. We'll review it and follow up at \(email.trimmingCharacters(in: .whitespaces).isEmpty ? "the address you gave" : email).")
                .font(.wbBodyMedium)
                .foregroundStyle(Palette.onSurfaceVariant(scheme))
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)

            Button("Done") { dismiss() }
                .font(.wbBodyLarge)
                .fontWeight(.semibold)
                .foregroundStyle(Palette.onAccent(scheme))
                .padding(.horizontal, 32)
                .padding(.vertical, 13)
                .background(Palette.accent(scheme), in: Capsule())
                .buttonStyle(.plain)
        }
        .padding(32)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    // MARK: - Behaviour

    private var environmentStamp: String {
        let info = Bundle.main.infoDictionary
        let version = info?["CFBundleShortVersionString"] as? String ?? "1.0"
        let build = info?["CFBundleVersion"] as? String ?? "1"
        return "WealthBoard \(version) (\(build)) · \(UIDevice.current.systemName) \(UIDevice.current.systemVersion)"
    }

    private func submit() async {
        error = nil
        guard !subject.trimmingCharacters(in: .whitespaces).isEmpty else {
            error = "Please enter a subject."
            return
        }
        guard !detail.trimmingCharacters(in: .whitespaces).isEmpty else {
            error = "Please describe the issue."
            return
        }
        guard !email.trimmingCharacters(in: .whitespaces).isEmpty else {
            error = "Please enter your email."
            return
        }

        busy = true
        defer { busy = false }

        let body = "\(detail)\n\n---\n\(environmentStamp)"

        if Services.bugReports.isConfigured {
            do {
                try await Services.bugReports.submit(
                    subject: subject,
                    detail: body,
                    replyTo: email
                )
                sent = true
            } catch {
                self.error = "Failed to send: \(error.localizedDescription)"
            }
        } else {
            // No backend: hand the same report to the mail composer rather than
            // losing it.
            var components = URLComponents(string: "mailto:\(Services.administratorEmail)")
            components?.queryItems = [
                URLQueryItem(name: "subject", value: "WealthBoard: \(subject)"),
                URLQueryItem(name: "body", value: "\(body)\n\nReply to: \(email)")
            ]
            guard let url = components?.url else {
                self.error = "Couldn't open the mail composer."
                return
            }

            // Whether the mail app actually opened decides what this screen
            // says next.
            //
            // It used to call `openURL` and set `sent = true` on the next line
            // regardless. On a device with no mail account — and on every
            // simulator, where Mail is not installed at all — nothing happens
            // and iOS reports the failure to no one, so the screen showed a
            // cheerful confirmation for a report that had gone nowhere. That is
            // the whole of the "Contact Support doesn't work" bug: it wasn't
            // silent, it was lying.
            let opened = await withCheckedContinuation { continuation in
                openURL(url) { accepted in continuation.resume(returning: accepted) }
            }

            if opened {
                sent = true
            } else {
                // Not a dead end: the report is put on the clipboard so it can
                // be pasted wherever the user actually reads mail.
                UIPasteboard.general.string =
                    "To: \(Services.administratorEmail)\n"
                    + "Subject: WealthBoard: \(subject)\n\n"
                    + body
                    + "\n\nReply to: \(email)"
                self.error = "No mail app is set up on this device, so the report has been "
                    + "copied to your clipboard. Paste it into an email to "
                    + "\(Services.administratorEmail)."
            }
        }
    }
}
