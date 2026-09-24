package ca.tristan.portfolio.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ca.tristan.portfolio.firebase.FirebaseManager
import ca.tristan.portfolio.ui.components.WealthBoardTopBar
import kotlinx.coroutines.launch

/**
 * Contact Support / Report a Bug screen.
 *
 * Users fill in a subject and a detailed description. The report is submitted
 * to Firestore at /bug_reports/{auto-id} and becomes visible to the admin
 * via [AdminReportsScreen].
 */
@Composable
fun BugReportScreen(onBack: () -> Unit) {
    val currentEmail = FirebaseManager.currentUser?.email ?: ""

    var email by remember { mutableStateOf(currentEmail) }
    var subject by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var showSuccess by remember { mutableStateOf(false) }

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            WealthBoardTopBar(
                title = "Contact Support",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .imePadding()
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                "Report a Bug or Contact Support",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Describe the issue you're experiencing. We'll review your report and get back to you.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(20.dp))

            // ── Email ────────────────────────────────────────────────────────
            OutlinedTextField(
                value = email,
                onValueChange = { email = it.trim() },
                label = { Text("Your email (for our reply)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(12.dp))

            // ── Subject ──────────────────────────────────────────────────────
            OutlinedTextField(
                value = subject,
                onValueChange = { subject = it },
                label = { Text("Subject") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("e.g. App crashes on the Holdings screen") }
            )

            Spacer(Modifier.height(12.dp))

            // ── Description ──────────────────────────────────────────────────
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("Description") },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp),
                maxLines = 10,
                placeholder = {
                    Text(
                        "Please describe the bug in detail:\n" +
                            "• What were you doing when it happened?\n" +
                            "• What did you expect to happen?\n" +
                            "• What actually happened?"
                    )
                }
            )

            Spacer(Modifier.height(24.dp))

            // ── Submit ───────────────────────────────────────────────────────
            Button(
                onClick = {
                    when {
                        email.isBlank() -> scope.launch {
                            snackbar.showSnackbar("Please enter your email.")
                        }
                        subject.isBlank() -> scope.launch {
                            snackbar.showSnackbar("Please enter a subject.")
                        }
                        description.isBlank() -> scope.launch {
                            snackbar.showSnackbar("Please describe the issue.")
                        }
                        else -> {
                            loading = true
                            scope.launch {
                                val error = FirebaseManager.submitBugReport(
                                    subject = subject,
                                    description = description,
                                    userEmail = email
                                )
                                loading = false
                                if (error == null) {
                                    showSuccess = true
                                } else {
                                    snackbar.showSnackbar("Failed to send: $error")
                                }
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !loading
            ) {
                if (loading) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                } else {
                    Text("Submit Report")
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "Your report will be reviewed by the WealthBoard team. Thank you for helping us improve the app.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    // ── Success dialog ───────────────────────────────────────────────────────
    if (showSuccess) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Report Sent ✓") },
            text = {
                Text(
                    "Thank you! Your report has been submitted successfully. " +
                        "We'll review it and follow up at ${email.ifBlank { "your email" }}."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showSuccess = false
                    onBack()
                }) {
                    Text("Done")
                }
            }
        )
    }
}
