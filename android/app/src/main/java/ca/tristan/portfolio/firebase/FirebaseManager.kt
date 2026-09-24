package ca.tristan.portfolio.firebase

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/**
 * Central Firebase helper for WealthBoard.
 *
 * Provides:
 *  - Authentication (email/password)
 *  - A [currentUserFlow] that emits whenever sign-in state changes
 *  - Firestore cloud sync for the user's portfolio data
 *  - Bug report submission (stored under /bug_reports)
 */
object FirebaseManager {

    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val db: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    // ── Admin email — only this account can see all bug reports ──────────────
    const val ADMIN_EMAIL = "tristandavid20@gmail.com"

    // ── Auth ──────────────────────────────────────────────────────────────────

    val currentUser: FirebaseUser? get() = auth.currentUser

    /** A Flow that emits the current FirebaseUser (or null) on every auth-state change. */
    val currentUserFlow: Flow<FirebaseUser?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { trySend(it.currentUser) }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }

    /** Sign in with email and password. Returns null on success, or an error message. */
    suspend fun signIn(email: String, password: String): String? {
        return try {
            auth.signInWithEmailAndPassword(email, password).await()
            null
        } catch (e: Exception) {
            e.message ?: "Sign-in failed"
        }
    }

    /** Register a new account. Returns null on success, or an error message. */
    suspend fun register(email: String, password: String): String? {
        return try {
            auth.createUserWithEmailAndPassword(email, password).await()
            null
        } catch (e: Exception) {
            e.message ?: "Registration failed"
        }
    }

    /** Send a password reset email. Returns null on success, or an error message. */
    suspend fun sendPasswordReset(email: String): String? {
        return try {
            auth.sendPasswordResetEmail(email).await()
            null
        } catch (e: Exception) {
            e.message ?: "Could not send reset email"
        }
    }

    fun signOut() = auth.signOut()

    /**
     * Returned by [deleteAccount] when the session is too old for Firebase to
     * accept a deletion. Identity-compared by the caller.
     */
    const val REQUIRES_RECENT_LOGIN = "wb:requires-recent-login"

    /**
     * Permanently deletes the signed-in account and its cloud copy.
     * Returns null on success, [REQUIRES_RECENT_LOGIN], or an error message.
     *
     * Required to exist and to be reachable from inside the app: Play treats an
     * account you can create in the app but can only delete by emailing someone
     * as a policy violation, and the App Store says the same.
     *
     * The cloud data goes FIRST, on purpose. Deleting the auth user revokes the
     * credential the Firestore rules check, so documents deleted afterwards
     * would be refused — and the portfolio would outlive the account that owned
     * it, which is exactly what the requirement exists to prevent. If the
     * account delete then fails, the user is still signed in and can retry; the
     * reverse order leaves data nobody can reach.
     *
     * Deletes the ACCOUNT. The portfolio in the local database is the user's own
     * data and is left alone — the confirmation dialog says so before asking.
     */
    suspend fun deleteAccount(): String? {
        val user = currentUser ?: return "You're not signed in."
        return try {
            // Best-effort: an account that never synced has no documents, and
            // "there was nothing to delete" must not stop the deletion.
            runCatching {
                // "document" is the iOS build's single-JSON backup and
                // "interchange" the cross-platform copy either app may have
                // written; both live in this same subtree. Deleting an account
                // has to take the whole of the user's cloud data with it,
                // whichever app wrote it.
                for (key in listOf(
                    "holdings", "transactions", "dividends", "accounts", "watchlist",
                    "document", "interchange"
                )) {
                    userCollection().collection("portfolio").document(key).delete().await()
                }
                userCollection().delete().await()
            }

            user.delete().await()
            null
        } catch (e: Exception) {
            // Firebase refuses to delete on a session older than a few minutes.
            // Translated rather than passed through, because the raw message
            // does not tell anyone what to do about it.
            if (e is FirebaseAuthRecentLoginRequiredException) REQUIRES_RECENT_LOGIN
            else e.message ?: "Couldn't delete the account."
        }
    }

    /**
     * Sign in with a Google ID token obtained from GoogleSignIn.
     * Returns null on success, or an error message.
     */
    suspend fun signInWithGoogle(idToken: String): String? {
        return try {
            val credential = GoogleAuthProvider.getCredential(idToken, null)
            auth.signInWithCredential(credential).await()
            null
        } catch (e: Exception) {
            e.message ?: "Google sign-in failed"
        }
    }

    // ── Cloud Sync — portfolio data ───────────────────────────────────────────

    /**
     * Returns the Firestore document path for this user's portfolio root.
     * Structure: users/{uid}/portfolio/{document}
     */
    private fun userCollection() = db.collection("users").document(currentUser!!.uid)

    /**
     * Writes a map of serializable portfolio data to Firestore for the signed-in user.
     * Uses merge so partial updates don't wipe other fields.
     *
     * @param key  sub-document key (e.g. "holdings", "transactions", "dividends")
     * @param data serializable Map<String, Any?> representation of the data
     */
    suspend fun syncToCloud(key: String, data: Map<String, Any?>) {
        if (currentUser == null) return
        try {
            userCollection()
                .collection("portfolio")
                .document(key)
                .set(data, SetOptions.merge())
                .await()
        } catch (_: Exception) { /* best-effort — local data is authoritative */ }
    }

    /**
     * Reads a sub-document from the user's cloud portfolio.
     * Returns an empty map if not found or on error.
     */
    suspend fun readFromCloud(key: String): Map<String, Any?> {
        if (currentUser == null) return emptyMap()
        return try {
            userCollection()
                .collection("portfolio")
                .document(key)
                .get()
                .await()
                .data ?: emptyMap()
        } catch (_: Exception) { emptyMap() }
    }

    // ── Bug Reports ───────────────────────────────────────────────────────────

    /**
     * Submits a bug report to Firestore at /bug_reports/{auto-id}.
     * Returns null on success, or an error message.
     */
    suspend fun submitBugReport(
        subject: String,
        description: String,
        userEmail: String
    ): String? {
        return try {
            val report = mapOf(
                "subject"      to subject,
                "description"  to description,
                "userEmail"    to userEmail,
                "uid"          to (currentUser?.uid ?: "anonymous"),
                "timestamp"    to com.google.firebase.Timestamp.now(),
                "status"       to "open"
            )
            db.collection("bug_reports").add(report).await()
            null
        } catch (e: Exception) {
            e.message ?: "Failed to submit report"
        }
    }

    /**
     * Loads all bug reports — admin only.
     * Returns list of maps ordered by timestamp descending.
     */
    suspend fun loadBugReports(): List<Map<String, Any?>> {
        if (currentUser?.email != ADMIN_EMAIL) return emptyList()
        return try {
            db.collection("bug_reports")
                .orderBy("timestamp", com.google.firebase.firestore.Query.Direction.DESCENDING)
                .get()
                .await()
                .documents
                .map { doc ->
                    doc.data?.toMutableMap()?.also { it["id"] = doc.id } ?: emptyMap()
                }
        } catch (_: Exception) { emptyList() }
    }

    /**
     * Updates the status of a bug report (e.g. "open" → "resolved").
     */
    suspend fun updateBugReportStatus(reportId: String, status: String) {
        try {
            db.collection("bug_reports")
                .document(reportId)
                .update("status", status)
                .await()
        } catch (_: Exception) { }
    }

    /**
     * Permanently deletes a bug report document — admin only.
     * Intended for resolved reports the admin wants to clean up.
     */
    suspend fun deleteBugReport(reportId: String) {
        if (currentUser?.email != ADMIN_EMAIL) return
        try {
            db.collection("bug_reports")
                .document(reportId)
                .delete()
                .await()
        } catch (_: Exception) { }
    }
}
