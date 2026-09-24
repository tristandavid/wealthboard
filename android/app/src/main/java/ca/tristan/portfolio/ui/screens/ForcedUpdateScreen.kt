package ca.tristan.portfolio.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ca.tristan.portfolio.BuildConfig
import ca.tristan.portfolio.update.UpdateGate

/**
 * Shown instead of the app when this build has been retired.
 *
 * Deliberately a dead end: no back, no dismiss, no "later". A soft version of
 * this screen is worse than none, because the whole reason a build gets
 * retired is that continuing to use it does damage — and an escape hatch means
 * the people most likely to keep using it are the ones who found the hatch.
 *
 * What it still does say is that the user's data is fine and local. "Update
 * required" with nothing else reads, to someone with a hand-entered portfolio,
 * like their data is being held hostage.
 */
@Composable
fun ForcedUpdateScreen(message: String?, storeUrl: String?) {
    val context = LocalContext.current

    // Swallows the system back gesture. Without this, back pops this screen
    // and lands the user in the app the gate just took away.
    BackHandler(enabled = true) { }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Filled.SystemUpdate,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(Modifier.height(20.dp))

            Text(
                "Update required",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(Modifier.height(10.dp))

            Text(
                message?.takeIf { it.isNotBlank() }
                    ?: "This version of WealthBoard can no longer be used. " +
                    "Updating takes a moment and fixes it.",
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(16.dp))

            Text(
                "Everything you've entered is stored on this device and is not " +
                    "affected by updating.",
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(28.dp))

            Button(
                onClick = { UpdateGate.openStore(context, storeUrl) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Update now") }

            Spacer(Modifier.height(20.dp))

            // The build number, because the first thing a support conversation
            // needs is which version is actually installed.
            Text(
                "Installed: v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
