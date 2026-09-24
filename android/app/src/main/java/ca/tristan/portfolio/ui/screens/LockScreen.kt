package ca.tristan.portfolio.ui.screens

import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import ca.tristan.portfolio.security.AppLock
import ca.tristan.portfolio.ui.theme.BrandNavy
import ca.tristan.portfolio.ui.theme.BrandNavyDark

@Composable
fun LockScreen(activity: FragmentActivity, onUnlocked: () -> Unit) {
    val context = LocalContext.current
    val mode = remember { AppLock.mode(context) }
    var pinAttempt by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    if (mode == AppLock.Mode.BIOMETRIC) {
        LaunchedEffect(Unit) {
            val executor = ContextCompat.getMainExecutor(context)
            val prompt = BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onUnlocked()
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    error = errString.toString()
                }
            })
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock WealthBoard")
                .setAllowedAuthenticators(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK or androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                .build()
            prompt.authenticate(info)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(BrandNavy, BrandNavyDark)))
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "WealthBoard",
            style = MaterialTheme.typography.headlineMedium,
            color = androidx.compose.ui.graphics.Color.White,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Locked",
            color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.7f)
        )
        Spacer(Modifier.height(24.dp))
        error?.let {
            Text(it, color = androidx.compose.ui.graphics.Color(0xFFE58A82))
            Spacer(Modifier.height(8.dp))
        }
        if (mode == AppLock.Mode.PIN) {
            OutlinedTextField(
                value = pinAttempt,
                onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) pinAttempt = it },
                label = { Text("Enter PIN") }
            )
            Spacer(Modifier.height(12.dp))
            val remaining = AppLock.lockoutRemainingMillis(context)
            if (remaining > 0) {
                Text(
                    "Too many attempts. Try again in ${remaining / 1000}s.",
                    color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.8f)
                )
            } else {
                Button(
                    onClick = {
                        if (AppLock.verifyPin(context, pinAttempt)) {
                            onUnlocked()
                        } else {
                            error = "Incorrect PIN"
                            pinAttempt = ""
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary,
                        contentColor = MaterialTheme.colorScheme.onSecondary
                    )
                ) { Text("Unlock") }
            }
        }
    }
}
