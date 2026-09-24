package ca.tristan.portfolio.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ca.tristan.portfolio.firebase.FirebaseManager
import ca.tristan.portfolio.ui.components.WealthBoardTopBar
import com.google.firebase.Timestamp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Admin-only screen showing all submitted bug reports.
 *
 * Accessible only when signed in as [FirebaseManager.ADMIN_EMAIL].
 * Shows each report's subject, email, description, timestamp, and status.
 * The admin can mark reports as resolved.
 */
@Composable
fun AdminReportsScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var reports by remember { mutableStateOf<List<Map<String, Any?>>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    val sdf = remember { SimpleDateFormat("MMM d, yyyy • h:mm a", Locale.getDefault()) }

    fun load() {
        loading = true
        scope.launch {
            reports = FirebaseManager.loadBugReports()
            loading = false
        }
    }

    LaunchedEffect(Unit) { load() }

    Scaffold(
        topBar = {
            WealthBoardTopBar(
                title = "Bug Reports",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { load() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        when {
            loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.material3.CircularProgressIndicator()
                }
            }
            reports.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Filled.BugReport,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        Text(
                            "No bug reports yet",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "${reports.size} report${if (reports.size == 1) "" else "s"}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(4.dp))
                    }

                    items(reports, key = { it["id"] as? String ?: "" }) { report ->
                        val id = report["id"] as? String ?: ""
                        val subject = report["subject"] as? String ?: "(no subject)"
                        val description = report["description"] as? String ?: ""
                        val userEmail = report["userEmail"] as? String ?: "unknown"
                        val status = report["status"] as? String ?: "open"
                        val timestamp = report["timestamp"]

                        val dateStr = when (timestamp) {
                            is Timestamp -> sdf.format(Date(timestamp.toDate().time))
                            is Long -> sdf.format(Date(timestamp))
                            else -> ""
                        }

                        val isResolved = status == "resolved"

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isResolved)
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                else
                                    MaterialTheme.colorScheme.surface
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = if (isResolved) 0.dp else 2.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                // Status badge + subject
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Text(
                                        subject,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 14.sp,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Box(
                                        modifier = Modifier
                                            .background(
                                                color = if (isResolved)
                                                    MaterialTheme.colorScheme.surfaceTint.copy(alpha = 0.15f)
                                                else
                                                    MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
                                                shape = RoundedCornerShape(4.dp)
                                            )
                                            .padding(horizontal = 8.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            status.uppercase(),
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isResolved)
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            else
                                                MaterialTheme.colorScheme.error
                                        )
                                    }
                                }

                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "From: $userEmail",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                if (dateStr.isNotBlank()) {
                                    Text(
                                        dateStr,
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                if (description.isNotBlank()) {
                                    Spacer(Modifier.height(10.dp))
                                    Divider(
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                                    )
                                    Spacer(Modifier.height(10.dp))
                                    Text(
                                        description,
                                        fontSize = 13.sp,
                                        lineHeight = 19.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }

                                if (id.isNotBlank()) {
                                    Spacer(Modifier.height(12.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End
                                    ) {
                                        if (!isResolved) {
                                            TextButton(
                                                onClick = {
                                                    scope.launch {
                                                        FirebaseManager.updateBugReportStatus(id, "resolved")
                                                        reports = reports.map { r ->
                                                            if (r["id"] == id) {
                                                                r.toMutableMap().also { it["status"] = "resolved" }
                                                            } else r
                                                        }
                                                    }
                                                }
                                            ) {
                                                Icon(
                                                    Icons.Filled.CheckCircle,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary
                                                )
                                                Text(
                                                    "  Mark Resolved",
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }
                                        // Delete button — only shown on resolved reports
                                        if (isResolved) {
                                            TextButton(
                                                onClick = {
                                                    scope.launch {
                                                        FirebaseManager.deleteBugReport(id)
                                                        // Remove from local list immediately
                                                        reports = reports.filter { r -> r["id"] != id }
                                                    }
                                                }
                                            ) {
                                                Icon(
                                                    Icons.Filled.Delete,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.error
                                                )
                                                Text(
                                                    "  Delete",
                                                    color = MaterialTheme.colorScheme.error
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
    }
}
