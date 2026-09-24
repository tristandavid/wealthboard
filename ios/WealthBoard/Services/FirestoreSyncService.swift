import Foundation

// Cloud backup for iOS, wired the moment the Firestore SDK is present.
//
// Same arrangement as `FirebaseAuthService`: compiles to nothing without the
// package, becomes the real implementation as soon as it is added, and
// `Services.bootstrap()` installs whichever is available. See that file's
// header for the Xcode steps.

#if canImport(FirebaseFirestore) && canImport(FirebaseAuth)
import FirebaseAuth
import FirebaseFirestore

/// Stores one document per account, at `backups/{uid}`.
///
/// One document rather than a collection per table, because the portfolio is a
/// few kilobytes and is only ever read or written whole. A per-entity layout
/// would buy partial sync nobody asked for and cost a merge strategy, and the
/// merge is where a sync of hand-entered data goes wrong — two devices both
/// right, one silently winning.
///
/// The payload is the SAME JSON the file export writes, so a cloud backup and a
/// file backup are interchangeable and there is one format to keep working.
@MainActor
final class FirestoreSyncService: CloudSyncService {

    private let lastBackupKey = "cloudLastBackupAt"

    var isConfigured: Bool { Auth.auth().currentUser != nil }

    var lastBackupAt: Date? {
        let seconds = UserDefaults.standard.double(forKey: lastBackupKey)
        return seconds > 0 ? Date(timeIntervalSince1970: seconds) : nil
    }

    private var encoder: JSONEncoder {
        let e = JSONEncoder()
        e.dateEncodingStrategy = .millisecondsSince1970
        return e
    }

    private var decoder: JSONDecoder {
        let d = JSONDecoder()
        d.dateDecodingStrategy = .millisecondsSince1970
        return d
    }

    func backup(_ document: PortfolioDocument) async throws {
        guard let uid = Auth.auth().currentUser?.uid else {
            throw CloudSyncError.notConfigured
        }
        let data = try encoder.encode(document)
        guard let json = String(data: data, encoding: .utf8) else {
            throw CloudSyncError.notConfigured
        }

        // One instant, stamped on BOTH documents below. Two separate `Date()`
        // calls (one per write) used to mean the interchange copy's timestamp
        // was always a few milliseconds later than the native one's — since
        // it is always written second — so `restore()`'s "prefer whichever is
        // newer" comparison read every single same-device backup as "the
        // interchange copy is newer" and silently restored the lossier
        // natural-key reconstruction instead of the exact native document.
        let backupTimestamp = Date()

        // Stored as a JSON STRING in one field, not as a nested Firestore map.
        //
        // Firestore has no array-of-arrays and a 20-level nesting limit, and it
        // coerces types on the way back — so a faithful round-trip of a Codable
        // document through a map means writing a second serialiser and keeping
        // it in step with the first. A string is the same bytes out as in.
        try await Firestore.firestore()
            .collection("users")
            .document(uid)
            .collection("portfolio")
            .document("document")
            .setData([
                "payload": json,
                "schemaVersion": document.schemaVersion,
                "updatedAt": FieldValue.serverTimestamp(),
                "writtenAt": Int64(backupTimestamp.timeIntervalSince1970 * 1000),
                // Written for the user's own benefit when they look at what is
                // stored: a count they can sanity-check against the app.
                "holdingCount": document.holdings.count
            ])

        // The cross-platform copy, written BESIDE the native one above — see
        // `PortfolioInterchange` for why it is beside and not instead.
        //
        // Best-effort on purpose. The native document is what this device
        // restores from and it has already landed by the time we get here; a
        // failure to write this one costs a restore on Android, which is worth
        // reporting but is not worth turning a good backup into a failed one.
        do {
            try await Firestore.firestore()
                .collection("users")
                .document(uid)
                .collection("portfolio")
                .document(PortfolioInterchange.documentName)
                .setData(
                    PortfolioInterchange.encode(
                        document,
                        // Read from Prefs rather than taken as a parameter:
                        // this is the same value the view model persists, and
                        // threading it through `CloudSyncService.backup` would
                        // change a protocol four call sites implement for one
                        // informational field neither app applies on import.
                        baseCurrency: Prefs.string(Prefs.Key.baseCurrency) ?? "CAD",
                        // Same instant as the native document above, not a
                        // fresh one — see backupTimestamp's comment.
                        writtenAt: backupTimestamp
                    )
                )
        } catch {
            UserDefaults.standard.set(backupTimestamp.timeIntervalSince1970, forKey: lastBackupKey)
            throw CloudSyncError.crossPlatformCopyFailed
        }

        UserDefaults.standard.set(backupTimestamp.timeIntervalSince1970, forKey: lastBackupKey)
    }

    func restore() async throws -> CloudRestore {
        guard let uid = Auth.auth().currentUser?.uid else {
            throw CloudSyncError.notConfigured
        }
        let portfolio = Firestore.firestore()
            .collection("users")
            .document(uid)
            .collection("portfolio")

        let snapshot = try await portfolio.document("document").getDocument()

        // The native copy: what this app wrote, in its own shape.
        var native: PortfolioDocument?
        var nativeAt: Date?
        if snapshot.exists,
           let json = snapshot.get("payload") as? String,
           let data = json.data(using: .utf8),
           let decoded = try? decoder.decode(PortfolioDocument.self, from: data) {
            native = decoded
            if let ms = snapshot.get("writtenAt") as? NSNumber {
                nativeAt = Date(timeIntervalSince1970: ms.doubleValue / 1000)
            } else if let stamp = snapshot.get("updatedAt") as? Timestamp {
                // Backups written before `writtenAt` existed still carry the
                // server timestamp, which is what the comparison needs.
                nativeAt = stamp.dateValue()
            }
        }

        // The cross-platform copy, which either app may have written.
        //
        // Preferred ONLY when it is strictly newer than the native document.
        // Both are written in the same breath by whichever app made them, so
        // "newer" really means "the last backup came from the Android phone".
        // Ties and unknown timestamps go to the native copy, because that is
        // the one that has been restoring correctly and this format has not
        // been proven against real data yet.
        var interchange: (document: PortfolioDocument, summary: PortfolioInterchange.ImportSummary)?
        var interchangeAt: Date?
        if let raw = try? await portfolio.document(PortfolioInterchange.documentName)
            .getDocument().data() {
            interchange = PortfolioInterchange.decode(raw)
            interchangeAt = PortfolioInterchange.writtenAt(raw)
        }

        let preferInterchange: Bool = {
            guard let interchange, !interchange.summary.isEmpty else { return false }
            // No usable native copy at all (missing, or failed to decode):
            // the interchange copy is all there is.
            guard native != nil else { return true }
            // A native copy exists but one of the two timestamps can't be
            // read — this used to fall through to "prefer interchange" here,
            // which contradicted the comment above it (unknown timestamps are
            // supposed to go to native, the one that has been restoring
            // correctly). Now it actually does.
            guard let nativeAt, let interchangeAt else { return false }
            return interchangeAt > nativeAt
        }()

        if preferInterchange, let interchange {
            var note = "This came from your Android phone — it was the more recent backup."
            if interchange.summary.skipped > 0 {
                note += "\n\n\(interchange.summary.skipped) row"
                    + (interchange.summary.skipped == 1 ? "" : "s")
                    + " in it couldn't be read and "
                    + (interchange.summary.skipped == 1 ? "was" : "were")
                    + " skipped. Check your holdings against your records."
            }
            return CloudRestore(document: interchange.document, note: note)
        }

        guard let native else { throw CloudSyncError.noBackup }
        return CloudRestore(document: native, note: nil)
    }

    func deleteBackup() async throws {
        guard let uid = Auth.auth().currentUser?.uid else {
            throw CloudSyncError.notConfigured
        }
        let portfolio = Firestore.firestore()
            .collection("users")
            .document(uid)
            .collection("portfolio")

        try await portfolio.document("document").delete()
        // The cross-platform copy is the user's data too. Leaving it behind
        // when the account is deleted is exactly what the deletion requirement
        // exists to prevent.
        try? await portfolio.document(PortfolioInterchange.documentName).delete()

        // The local stamp goes with it: a "last backed up" date pointing at a
        // document that no longer exists would offer a restore that can only
        // fail.
        UserDefaults.standard.removeObject(forKey: lastBackupKey)
    }
}
#endif

extension Services {
    /// Installs the Firestore-backed sync when the SDK is present.
    ///
    /// Separate from `bootstrap()`'s auth step because sync depends on being
    /// signed in, so it is also re-run after a sign-in rather than only at
    /// launch.
    static func bootstrapCloudSync() {
        #if canImport(FirebaseFirestore) && canImport(FirebaseAuth)
        cloud = FirestoreSyncService()
        #endif
    }
}
