package ca.tristan.portfolio.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import ca.tristan.portfolio.R
import ca.tristan.portfolio.firebase.FirebaseManager
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.launch

/**
 * Login / Register screen using Firebase Authentication.
 * Supports email/password and Google Sign-In.
 *
 * Sign in with Apple was removed at the owner's request. Note for later: App
 * Store review requires an app offering third-party sign-in to offer Apple's as
 * well, so the iOS build shipping alongside this one has the same exposure
 * under guideline 4.8 while the Google button is visible.
 * Accessible from the Menu tab; the app no longer blocks on sign-in at launch.
 */
@Composable
fun LoginScreen(
    activity: FragmentActivity,
    onAuthenticated: () -> Unit
) {
    var isRegisterMode by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    // Google's widget picks its own light/dark treatment, so it has to be told
    // which one to use. Taken from the actual background rather than from
    // `isSystemInDarkTheme()`, because the app has its own light/dark/system
    // setting and the system answer is wrong whenever the user has overridden
    // it — a light Google button on a dark card is the visible symptom.
    val isDarkBackground = MaterialTheme.colorScheme.background.luminance() < 0.5f

    // ── Google Sign-In launcher ──────────────────────────────────────────────
    val googleSignInClient = remember {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(activity.getString(R.string.default_web_client_id))
            .requestEmail()
            .build()
        GoogleSignIn.getClient(activity, gso)
    }

    val googleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)
            val idToken = account.idToken
            if (idToken == null) {
                scope.launch { snackbar.showSnackbar("Google sign-in failed: no token received.") }
                return@rememberLauncherForActivityResult
            }
            loading = true
            scope.launch {
                val error = FirebaseManager.signInWithGoogle(idToken)
                loading = false
                if (error == null) {
                    onAuthenticated()
                } else {
                    snackbar.showSnackbar(error)
                }
            }
        } catch (e: ApiException) {
            if (e.statusCode != 12501) { // 12501 = user cancelled
                scope.launch { snackbar.showSnackbar("Google sign-in failed: ${e.message}") }
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 28.dp)
                .imePadding()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(48.dp))

            // ── App logo / title ─────────────────────────────────────────────
            Text(
                "💰 WealthBoard",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (isRegisterMode) "Create your account" else "Sign in to continue",
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(32.dp))

            // ── Google Sign-In button ────────────────────────────────────────
            //
            // Hand-built rather than Play Services' own `SignInButton` widget:
            // that widget only ever renders as a near-square-cornered
            // rectangle, which reads as dated next to a modern pill-shaped
            // design. Google's branding guidelines allow a fully custom shape
            // as long as the real multi-colour "G" mark, the standard wordmark
            // text, and sufficient contrast are kept — all three hold here.
            // The mark is `ic_google_logo`, the unmodified four-colour asset,
            // on a white disc so it stays legible against both the black and
            // white button backgrounds below.
            Button(
                onClick = {
                    // Sign out first so the account picker always shows.
                    googleSignInClient.signOut()
                    googleLauncher.launch(googleSignInClient.signInIntent)
                },
                enabled = !loading,
                shape = RoundedCornerShape(percent = 50),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isDarkBackground) Color.White else Color.Black,
                    contentColor = if (isDarkBackground) Color.Black else Color.White
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .background(Color.White, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_google_logo),
                        contentDescription = null,
                        modifier = Modifier.size(17.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    "Continue with Google",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp
                )
            }

            Spacer(Modifier.height(20.dp))

            // ── Divider ──────────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Divider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    "  or  ",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Divider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
            }

            Spacer(Modifier.height(20.dp))

            // ── Email ────────────────────────────────────────────────────────
            OutlinedTextField(
                value = email,
                onValueChange = { email = it.trim() },
                label = { Text("Email") },
                leadingIcon = { Icon(Icons.Filled.Email, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Next
                ),
                keyboardActions = KeyboardActions(
                    onNext = { focusManager.moveFocus(FocusDirection.Down) }
                )
            )

            Spacer(Modifier.height(12.dp))

            // ── Password ─────────────────────────────────────────────────────
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null) },
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (passwordVisible) "Hide password" else "Show password"
                        )
                    }
                },
                visualTransformation = if (passwordVisible) VisualTransformation.None
                                       else PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = { focusManager.clearFocus() }
                )
            )

            Spacer(Modifier.height(24.dp))

            // ── Primary action button ────────────────────────────────────────
            Box(modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = {
                        if (email.isBlank() || password.isBlank()) {
                            scope.launch { snackbar.showSnackbar("Please enter your email and password.") }
                            return@Button
                        }
                        loading = true
                        scope.launch {
                            val error = if (isRegisterMode) {
                                if (password.length < 6) {
                                    "Password must be at least 6 characters."
                                } else {
                                    FirebaseManager.register(email, password)
                                }
                            } else {
                                FirebaseManager.signIn(email, password)
                            }
                            loading = false
                            if (error == null) {
                                onAuthenticated()
                            } else {
                                snackbar.showSnackbar(error)
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
                        Text(if (isRegisterMode) "Create Account" else "Sign In")
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── Toggle register / login ──────────────────────────────────────
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    if (isRegisterMode) "Already have an account?" else "Don't have an account?",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp
                )
                TextButton(onClick = {
                    isRegisterMode = !isRegisterMode
                    password = ""
                }) {
                    Text(if (isRegisterMode) "Sign In" else "Register")
                }
            }

            // ── Forgot password ──────────────────────────────────────────────
            if (!isRegisterMode) {
                TextButton(
                    onClick = {
                        if (email.isBlank()) {
                            scope.launch { snackbar.showSnackbar("Enter your email above first.") }
                            return@TextButton
                        }
                        loading = true
                        scope.launch {
                            val err = FirebaseManager.sendPasswordReset(email)
                            loading = false
                            if (err == null) {
                                snackbar.showSnackbar("Reset link sent — check your email.")
                            } else {
                                snackbar.showSnackbar(err)
                            }
                        }
                    }
                ) {
                    Text("Forgot password?")
                }
            }

            Spacer(Modifier.height(32.dp))

            // ── Use without account ──────────────────────────────────────────
            TextButton(onClick = onAuthenticated) {
                Text(
                    "Continue without an account",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
