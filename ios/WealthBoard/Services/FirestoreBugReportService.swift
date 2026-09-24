import Foundation

// The iOS half of the bug-report console.
//
// Android has written reports to Firestore since v1.3; iOS shipped with the
// protocol and never installed an implementation, so `Services.bugReports` was
// always the unconfigured stub. Two things followed from that, and both looked
// like broken buttons: Contact Support could only ever fall back to the mail
// composer, and the admin Bug Reports screen could only ever be empty.
//
// Written against the SAME collection and the SAME field names as
// `FirebaseManager.submitBugReport` on Android — /bug_reports, with subject,
// description, userEmail, uid, timestamp and status. Both apps report into one
// console; a second schema would mean a second console.

// FirebaseCore as well as the two SDKs actually used: `FirebaseApp` — the
// handle `isConfigured` checks — is declared there, not in FirebaseAuth or
// FirebaseFirestore. The same three-way guard `FirebaseAuthService` uses, for
// the same reason.
#if canImport(FirebaseFirestore) && canImport(FirebaseAuth) && canImport(FirebaseCore)
import FirebaseAuth
import FirebaseCore
import FirebaseFirestore

@MainActor
final class FirestoreBugReportService: BugReportService {

    private var store: Firestore { Firestore.firestore() }
    private let collection = "bug_reports"

    var isConfigured: Bool { FirebaseApp.app() != nil }

    func submit(subject: String, detail: String, replyTo: String) async throws {
        // Anonymous is allowed on purpose: someone who cannot sign in is
        // exactly the person most likely to need support, and refusing the
        // report would make the screen useless precisely when it matters.
        let uid = Auth.auth().currentUser?.uid ?? "anonymous"
        try await store.collection(collection).addDocument(data: [
            "subject": subject,
            "description": detail,
            "userEmail": replyTo,
            "uid": uid,
            "timestamp": Timestamp(date: Date()),
            "status": "open"
        ])
    }

    func loadAll() async throws -> [BugReport] {
        // The screen checking `isAdministrator` is a convenience, not the
        // boundary — the boundary is the Firestore security rule. This mirrors
        // Android's client-side check so the two behave alike, and neither is
        // what actually protects the data.
        guard Auth.auth().currentUser?.email?.caseInsensitiveCompare(Services.administratorEmail)
                == .orderedSame else { return [] }

        let snapshot = try await store.collection(collection)
            .order(by: "timestamp", descending: true)
            .getDocuments()

        return snapshot.documents.compactMap { document -> BugReport? in
            let data = document.data()
            // A report with no subject and no body is a write that went wrong,
            // not a report; showing it as a blank row in the console helps
            // nobody.
            let subject = data["subject"] as? String ?? ""
            let detail = data["description"] as? String ?? ""
            guard !subject.isEmpty || !detail.isEmpty else { return nil }

            let submitted: Date
            if let stamp = data["timestamp"] as? Timestamp {
                submitted = stamp.dateValue()
            } else {
                submitted = Date()
            }

            return BugReport(
                id: document.documentID,
                subject: subject,
                detail: detail,
                userEmail: data["userEmail"] as? String ?? "",
                submittedAt: submitted,
                status: data["status"] as? String ?? "open"
            )
        }
    }

    func setStatus(_ id: String, status: String) async throws {
        try await store.collection(collection).document(id).updateData(["status": status])
    }

    func delete(_ id: String) async throws {
        try await store.collection(collection).document(id).delete()
    }
}
#endif

extension Services {
    /// Installs the Firestore-backed reporter where the SDK is present.
    ///
    /// Called from `bootstrap()` alongside cloud sync, and again after a
    /// sign-in: `loadAll` is gated on the signed-in email, so the console has
    /// to be re-created once there is an account to check.
    static func bootstrapBugReports() {
        #if canImport(FirebaseFirestore) && canImport(FirebaseAuth) && canImport(FirebaseCore)
        bugReports = FirestoreBugReportService()
        #endif
    }
}
