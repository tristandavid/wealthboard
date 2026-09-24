import SwiftUI
import UniformTypeIdentifiers

/// Backup and restore, in one screen, with the no-account option first.
///
/// The Menu only showed a "Cloud Backup & Restore" row when signed in, which
/// meant the app's only safety net was invisible to anyone who had not made an
/// account — and the portfolio is hand-entered, so losing it means retyping
/// every holding, transaction and dividend. Backup is not a premium feature;
/// it is the thing that makes hand-entry survivable.
///
/// So the file export leads. It needs nothing: no account, no network, no
/// subscription. It writes the same document the cloud sync uploads, and it is
/// the one path that still works when the sign-in service is down, the account
/// is locked out, or the user simply does not want one. Cloud sync sits
/// underneath as the convenience for people who do sign in.
struct BackupView: View {
    @EnvironmentObject private var viewModel: PortfolioViewModel
    @Environment(\.colorScheme) private var scheme

    @State private var exportDocument: BackupFile?
    @State private var showImporter = false
    // Carries the note as well as the document, so a copy that came from the
    // Android phone says so in the confirmation — BEFORE the replace, not after
    // it, which is when it is still information the user can act on.
    @State private var pendingRestore: CloudRestore?
    @State private var message: String?
    @State private var isWorking = false

    /// `Services.auth` is a plain reference, not an observable object, so
    /// signing in changes nothing SwiftUI is watching and this screen would go
    /// on offering "Sign in" to an account that is already signed in. The
    /// session is what publishes it; this screen used to keep a counter of its
    /// own for the same job, which worked here and nowhere else.
    @ObservedObject private var session = AuthSession.shared

    /// Cloud backup is Premium-gated — see `subscription.isPremium` below.
    /// The file export above stays free for everyone, signed in or not.
    @ObservedObject private var subscription = SubscriptionSession.shared

    private var isSignedIn: Bool { session.isSignedIn }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                SectionHeader("To a file")
                    .padding(.horizontal, -WbDimens.screenPadding)

                WbCard {
                    Text("Writes everything you have entered — accounts, holdings, transactions and dividends — to a single file you keep wherever you like. No account needed, and it works offline.")
                        .font(.wbBodySmall)
                        .foregroundStyle(Palette.onSurfaceVariant(scheme))
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.bottom, 14)

                    Button {
                        Task { await prepareExport() }
                    } label: {
                        actionLabel("Export to a file", systemImage: "square.and.arrow.up", filled: true)
                    }
                    .buttonStyle(.plain)
                    .disabled(isWorking)

                    Color.clear.frame(height: 10)

                    Button {
                        showImporter = true
                    } label: {
                        actionLabel("Restore from a file", systemImage: "square.and.arrow.down", filled: false)
                    }
                    .buttonStyle(.plain)
                    .disabled(isWorking)
                }

                SectionHeader("To the cloud")
                    .padding(.horizontal, -WbDimens.screenPadding)
                    .padding(.top, 8)

                WbCard {
                    if isSignedIn && subscription.isPremium {
                        Text(
                            Services.cloud.lastBackupAt.map {
                                "Last backed up " + $0.formatted(date: .abbreviated, time: .shortened)
                            } ?? "Signed in, but nothing has been backed up yet."
                        )
                        .font(.wbBodySmall)
                        .foregroundStyle(Palette.onSurfaceVariant(scheme))
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.bottom, 14)

                        Button {
                            Task { await backUpToCloud() }
                        } label: {
                            actionLabel("Back up now", systemImage: "icloud.and.arrow.up", filled: false)
                        }
                        .buttonStyle(.plain)
                        .disabled(isWorking)

                        Color.clear.frame(height: 10)

                        Button {
                            Task { await restoreFromCloud() }
                        } label: {
                            actionLabel("Restore from cloud", systemImage: "icloud.and.arrow.down", filled: false)
                        }
                        .buttonStyle(.plain)
                        .disabled(isWorking)
                    } else if isSignedIn {
                        // Signed in, but not subscribed: the account-linked
                        // copy is the Premium perk itself, not something that
                        // needs signing in twice over.
                        Text("Keep a copy against your account, so it survives losing this phone. Cloud backup is a Premium feature — the file export above works without a subscription.")
                            .font(.wbBodySmall)
                            .foregroundStyle(Palette.onSurfaceVariant(scheme))
                            .fixedSize(horizontal: false, vertical: true)
                            .padding(.bottom, 14)

                        NavigationLink { PremiumView() } label: {
                            actionLabel("Go Premium", systemImage: "star.circle", filled: false)
                        }
                        .buttonStyle(.plain)
                    } else {
                        // Says what signing in adds, rather than blocking the
                        // screen on it. The file export above already covers
                        // the need; this is the convenience on top.
                        Text("Sign in to keep a copy that syncs across your devices and survives losing this phone. The file export above works without an account.")
                            .font(.wbBodySmall)
                            .foregroundStyle(Palette.onSurfaceVariant(scheme))
                            .fixedSize(horizontal: false, vertical: true)
                            .padding(.bottom, 14)

                        NavigationLink {
                            LoginView(onAuthenticated: {
                                // Sync is installed against the signed-in
                                // account, so it has to be re-bootstrapped
                                // here rather than only at launch. The session
                                // itself is refreshed by LoginView.
                                Services.bootstrapCloudSync()
                            })
                        } label: {
                            actionLabel("Sign in", systemImage: "person.crop.circle", filled: false)
                        }
                        .buttonStyle(.plain)
                    }
                }

                if let message {
                    Text(message)
                        .font(.wbBodySmall)
                        .foregroundStyle(Palette.onSurfaceVariant(scheme))
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.top, 14)
                }

                Color.clear.frame(height: 30)
            }
            .padding(.horizontal, WbDimens.screenPadding)
            .padding(.top, 8)
        }
        .wbScreenBackground(scheme)
        .navigationTitle("Backup & Restore")
        .navigationBarTitleDisplayMode(.inline)
        .fileExporter(
            isPresented: Binding(
                get: { exportDocument != nil },
                set: { if !$0 { exportDocument = nil } }
            ),
            document: exportDocument,
            contentType: .json,
            defaultFilename: BackupFile.suggestedName()
        ) { result in
            switch result {
            case .success: message = "Exported. Keep it somewhere you'll still have if this phone doesn't come back."
            case .failure(let error): message = "Export failed: \(error.localizedDescription)"
            }
            exportDocument = nil
        }
        .fileImporter(
            isPresented: $showImporter,
            allowedContentTypes: [.json],
            allowsMultipleSelection: false
        ) { result in
            Task { await loadImport(result) }
        }
        // Restoring REPLACES everything, so it asks first and says what will
        // happen in numbers rather than in the abstract.
        .alert("Replace everything?", isPresented: Binding(
            get: { pendingRestore != nil },
            set: { if !$0 { pendingRestore = nil } }
        )) {
            Button("Cancel", role: .cancel) { pendingRestore = nil }
            Button("Replace", role: .destructive) {
                // Captured synchronously, on the tap itself, rather than
                // re-read inside commitRestore(). SwiftUI clears
                // `pendingRestore` (via the isPresented binding's set above)
                // as part of dismissing this alert, and that write can land
                // before the Task body below gets its first chance to run —
                // so commitRestore reading `pendingRestore` itself saw nil
                // and silently returned, doing nothing. Tapping Replace
                // looked like it worked (the alert closed) but never restored
                // anything, with no error to explain why.
                if let pending = pendingRestore {
                    Task { await commitRestore(pending) }
                }
            }
        } message: {
            if let pendingRestore {
                Text(restoreWarning(for: pendingRestore))
            }
        }
    }

    /// Built outside the view body: as one concatenated expression with three
    /// interpolations and an optional map, the type checker gave up on it.
    private func restoreWarning(for restore: CloudRestore) -> String {
        let document = restore.document
        let holdings: Int = document.holdings.count
        let transactions: Int = document.transactions.count
        let dividends: Int = document.dividends.count

        var text = "This backup holds \(holdings) holdings, "
        text += "\(transactions) transactions and "
        text += "\(dividends) dividend payments. "
        text += "Restoring discards what is on this device now and cannot be undone."
        if let note = restore.note {
            text += "\n\n" + note
        }
        return text
    }

    @ViewBuilder
    private func actionLabel(_ title: String, systemImage: String, filled: Bool) -> some View {
        HStack(spacing: 8) {
            Image(systemName: systemImage)
            Text(title).fontWeight(.semibold)
        }
        .font(.wbBodyMedium)
        .foregroundStyle(filled ? Palette.onAccent(scheme) : Palette.accent(scheme))
        .frame(maxWidth: .infinity)
        .padding(.vertical, 12)
        .background(
            RoundedRectangle(cornerRadius: 10, style: .continuous)
                .fill(filled ? Palette.accent(scheme) : Palette.accent(scheme).opacity(0.14))
        )
    }

    // MARK: - Actions

    private func prepareExport() async {
        isWorking = true
        defer { isWorking = false }
        let document = await viewModel.backupPayload()
        guard let file = BackupFile(document: document) else {
            message = "Couldn't prepare the backup."
            return
        }
        exportDocument = file
    }

    private func loadImport(_ result: Result<[URL], Error>) async {
        switch result {
        case .failure(let error):
            message = "Couldn't open that file: \(error.localizedDescription)"
        case .success(let urls):
            guard let url = urls.first else { return }
            // The picker hands back a security-scoped URL; without the
            // begin/stop pair the read fails for anything outside the app's
            // own container, which is most of where people keep backups.
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }

            guard let data = try? Data(contentsOf: url) else {
                message = "Couldn't read that file."
                return
            }
            guard let restore = BackupFile.decode(data) else {
                message = "That doesn't look like a WealthBoard backup."
                return
            }
            pendingRestore = restore
        }
    }

    private func commitRestore(_ pending: CloudRestore) async {
        isWorking = true
        defer { isWorking = false }
        await viewModel.restore(from: pending.document)
        let count = pending.document.holdings.count
        message = "Restored \(count) holding\(count == 1 ? "" : "s")."
            + (pending.note.map { " " + $0 } ?? "")
    }

    private func backUpToCloud() async {
        // Premium-gated — see the matching check that hides these buttons
        // above. Checked again here so this path isn't only as safe as the
        // view that happens to call it.
        guard subscription.isPremium else {
            message = "Cloud backup is a Premium feature."
            return
        }
        isWorking = true
        defer { isWorking = false }
        let document = await viewModel.backupPayload()
        do {
            try await Services.cloud.backup(document)
            message = "Backed up."
        } catch {
            message = error.localizedDescription
        }
    }

    private func restoreFromCloud() async {
        // Premium-gated — see backUpToCloud() above.
        guard subscription.isPremium else {
            message = "Cloud backup is a Premium feature."
            return
        }
        isWorking = true
        defer { isWorking = false }
        do {
            // `restore()` throws `.noBackup` rather than returning nil, so the
            // error message is already the right one to show.
            pendingRestore = try await Services.cloud.restore()
        } catch {
            message = error.localizedDescription
        }
    }
}

/// The exported file.
///
/// Plain JSON, and deliberately the SAME shape the app stores and the cloud
/// sync uploads — one format, so a file exported today restores into a build
/// from next year, and a cloud backup and a file backup are interchangeable.
/// A bespoke export format would be a second thing to keep in step.
///
/// A second copy of the same portfolio rides along under `"interchange"`, in
/// the neutral shape `PortfolioInterchange` defines. That is what lets a file
/// exported here restore on the Android build, and one exported there restore
/// here. It is an EXTRA key on the same document: an older build decoding this
/// file ignores it, and this build still reads its own shape first.
struct BackupFile: FileDocument {
    static var readableContentTypes: [UTType] { [.json] }

    let data: Data

    init?(document: PortfolioDocument) {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .millisecondsSince1970
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        guard let encoded = try? encoder.encode(document) else { return nil }

        // Added by re-serialising rather than by making PortfolioDocument
        // carry the field: the document is the app's own model and has no
        // business holding a second encoding of itself.
        //
        // Every step is best-effort, and the plain encoding is what ships if
        // any of it fails. A file that restores on this phone and not the
        // other one beats no file.
        guard var object = (try? JSONSerialization.jsonObject(with: encoded)) as? [String: Any] else {
            data = encoded
            return
        }
        object["interchange"] = PortfolioInterchange.encode(
            document,
            baseCurrency: Prefs.string(Prefs.Key.baseCurrency) ?? "CAD"
        )
        guard let merged = try? JSONSerialization.data(
            withJSONObject: object,
            options: [.prettyPrinted, .sortedKeys]
        ) else {
            data = encoded
            return
        }
        data = merged
    }

    init(configuration: ReadConfiguration) throws {
        data = configuration.file.regularFileContents ?? Data()
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: data)
    }

    /// The portfolio in the file, and a note if it came from the other app.
    ///
    /// This app's own shape is tried FIRST and always wins, so nothing about
    /// reading a file this build wrote has changed. The interchange block is
    /// the fallback for a file that would otherwise have been refused — which,
    /// before this, is exactly what an Android export was.
    static func decode(_ data: Data) -> CloudRestore? {
        let object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]

        // An Android export carries an "app" marker this app never writes.
        // Checked BEFORE the native decode rather than relying on that decode
        // to fail: a file with accounts and nothing else would decode into a
        // half-empty document instead of throwing, and a restore that succeeds
        // with the wrong content is worse than one that refuses.
        let isForeign = (object?["app"] as? String) == "WealthBoard"

        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .millisecondsSince1970
        if !isForeign, let document = try? decoder.decode(PortfolioDocument.self, from: data) {
            return CloudRestore(document: document, note: nil)
        }

        guard let object,
              let block = object["interchange"] as? [String: Any],
              let decoded = PortfolioInterchange.decode(block),
              !decoded.summary.isEmpty else { return nil }

        var note = "This file was exported from the Android app."
        if decoded.summary.skipped > 0 {
            note += " \(decoded.summary.skipped) row"
                + (decoded.summary.skipped == 1 ? "" : "s")
                + " in it couldn't be read and "
                + (decoded.summary.skipped == 1 ? "was" : "were")
                + " skipped."
        }
        return CloudRestore(document: decoded.document, note: note)
    }

    /// Dated, because the first thing anyone wants from a folder of backups is
    /// to know which one is the newest.
    static func suggestedName() -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd"
        return "WealthBoard-\(formatter.string(from: Date()))"
    }
}
