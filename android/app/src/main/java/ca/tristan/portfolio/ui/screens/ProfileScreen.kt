package ca.tristan.portfolio.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ca.tristan.portfolio.firebase.FirebaseManager
import ca.tristan.portfolio.ui.components.WbDimens
import ca.tristan.portfolio.ui.components.WealthBoardTopBar

/**
 * Profile / account management screen.
 * Shows the signed-in account and allows sign-out.
 * When not signed in, shows a prompt to go sign in.
 */
@Composable
fun ProfileScreen(
    onBack: () -> Unit,
    onSignedOut: () -> Unit,
    onSignIn: (() -> Unit)? = null
) {
    val user = FirebaseManager.currentUser
    val isAdmin = user?.email == FirebaseManager.ADMIN_EMAIL

    val scope = rememberCoroutineScope()
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var deleteError by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            WealthBoardTopBar(
                title = "My Account",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            // ── Account card ─────────────────────────────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = MaterialTheme.shapes.large
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.AccountCircle,
                        contentDescription = null,
                        modifier = Modifier.size(52.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Column(modifier = Modifier.padding(start = 14.dp)) {
                        if (user != null) {
                            Text(
                                user.email ?: "Unknown",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                            Text(
                                if (isAdmin) "Administrator" else "Member",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Text("Not signed in", fontWeight = FontWeight.Medium)
                            Text(
                                "Sign in to enable cloud sync",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            if (user != null) {
                // ── Sign out ─────────────────────────────────────────────────
                // The Cloud Sync block that sat here only wrote a metadata
                // document to prove connectivity — it never pushed portfolio
                // entities, so "Sync successful" overstated what had happened.
                // Real syncing lives on the Menu's Sync to Cloud row, which
                // calls PortfolioViewModel.syncPortfolioToCloud().
                Spacer(Modifier.height(WbDimens.ScrollBottomGap))
                Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        FirebaseManager.signOut()
                        onSignedOut()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) {
                    Icon(Icons.Filled.ExitToApp, contentDescription = null)
                    Text("  Sign Out")
                }

                Spacer(Modifier.height(8.dp))

                // Required to exist and to be reachable from inside the app:
                // Play treats an account you can create here but can only
                // delete by emailing someone as a policy violation.
                TextButton(
                    onClick = { showDeleteConfirm = true },
                    enabled = !deleting,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (deleting) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp),
                            color = MaterialTheme.colorScheme.error
                        )
                    } else {
                        Text("Delete Account", color = MaterialTheme.colorScheme.error)
                    }
                }

                deleteError?.let {
                    Text(
                        it,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
            }
        }

        if (showDeleteConfirm) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = false },
                title = { Text("Delete your account?") },
                // Says exactly what goes and what stays. "Delete account" is
                // the one action in the app nobody can undo, and a user who
                // thinks it also wipes the portfolio on their phone — or who
                // thinks it doesn't — has been misled either way.
                text = {
                    Text(
                        "This permanently deletes your account and your cloud backup. " +
                            "It can't be undone.\n\nThe portfolio on this device is yours " +
                            "and stays where it is. Export it from Backup & Restore first " +
                            "if you want a copy."
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        showDeleteConfirm = false
                        deleting = true
                        deleteError = null
                        scope.launch {
                            val error = FirebaseManager.deleteAccount()
                            deleting = false
                            when (error) {
                                null -> onSignedOut()
                                FirebaseManager.REQUIRES_RECENT_LOGIN ->
                                    deleteError = "For your security, please sign out and " +
                                        "sign back in, then delete your account."
                                else -> deleteError = error
                            }
                        }
                    }) {
                        Text("Delete account", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
                }
            )
        }
    }
}
