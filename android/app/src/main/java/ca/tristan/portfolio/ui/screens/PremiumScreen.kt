package ca.tristan.portfolio.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ca.tristan.portfolio.billing.PurchaseOutcome
import ca.tristan.portfolio.billing.SubscriptionPlan
import ca.tristan.portfolio.billing.Subscriptions
import ca.tristan.portfolio.ui.components.WealthBoardTopBar
import kotlinx.coroutines.launch

/**
 * The plan screen: what Free gives you, what Premium adds, and the two ways to
 * buy it. Mirrors `PremiumView` on iOS.
 *
 * Prices come from Play, never from the app. They are regional, they change
 * without a release, and Play requires the customer be shown the amount they
 * will actually be charged in their own currency — so a hardcoded "$6.99" is
 * wrong for most of the world and eventually wrong everywhere.
 * `SubscriptionPlan.fallbackPrice` appears only while the fetch is in flight or
 * has failed.
 */
@Composable
fun PremiumScreen(onBack: () -> Unit) {
    // Unwrapped from the composition's context rather than taken from a
    // `LocalActivity` composition local, which is not in every version of
    // activity-compose. Play's billing flow needs a real Activity to present
    // over — it cannot be launched from an application context.
    val context = LocalContext.current
    val activity = remember(context) {
        var candidate: android.content.Context? = context
        var found: android.app.Activity? = null
        while (candidate is android.content.ContextWrapper) {
            if (candidate is android.app.Activity) { found = candidate; break }
            candidate = candidate.baseContext
        }
        found
    }
    val scope = rememberCoroutineScope()

    val isPremium by Subscriptions.isPremium.collectAsStateWithLifecycle()
    val products by Subscriptions.products.collectAsStateWithLifecycle()
    val isConfigured by Subscriptions.isConfigured.collectAsStateWithLifecycle()

    var selected by remember { mutableStateOf(SubscriptionPlan.YEARLY) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) { Subscriptions.refresh() }

    val selectedTrial = products.firstOrNull { it.plan == selected }?.trialDescription

    Scaffold(
        topBar = {
            WealthBoardTopBar(
                title = "Premium",
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
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text(
                "WealthBoard Premium",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(6.dp))
            // The copy follows what the build actually does. It read "without
            // the ads" unconditionally, which was written for a build that had
            // an ad network; there isn't one, so the free tier carries no ads.
            Text(
                "Alerts, cloud sync, PDF and CSV reports, and unlimited accounts.",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(20.dp))

            if (isPremium) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            Icons.Filled.Verified,
                            contentDescription = null,
                            tint = ca.tristan.portfolio.ui.theme.GainGreen,
                            modifier = Modifier.size(24.dp)
                        )
                        Column {
                            Text("Premium is active", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                            Text(
                                "Manage or cancel it in the Play Store under Subscriptions.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                // What each tier GIVES rather than what it withholds: a "Free"
                // column listing four things it cannot do reads as a
                // punishment, and the free tier here is genuinely the whole app.
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(Modifier.padding(16.dp)) {
                        TierRow(
                            title = "Free",
                            detail = "Every screen, every holding, every chart. No ads, no limits.",
                            isCurrent = true
                        )
                        Divider(
                            modifier = Modifier.padding(vertical = 12.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                        TierRow(
                            title = "Premium",
                            detail = "Price and ex-dividend alerts, cloud backup and sync across your devices, PDF and CSV report exports, and unlimited accounts.",
                            isCurrent = false
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                SubscriptionPlan.values().forEach { plan ->
                    val live = products.firstOrNull { it.plan == plan }
                    PlanRow(
                        title = plan.title,
                        period = plan.period,
                        price = live?.displayPrice ?: plan.fallbackPrice,
                        trial = live?.trialDescription,
                        isSelected = selected == plan,
                        onClick = { selected = plan }
                    )
                    Spacer(Modifier.height(10.dp))
                }

                Spacer(Modifier.height(8.dp))

                Button(
                    onClick = {
                        val host = activity ?: return@Button
                        busy = true
                        message = null
                        scope.launch {
                            when (val outcome = Subscriptions.purchase(host, selected)) {
                                is PurchaseOutcome.Purchased -> {
                                    message = "You're on Premium. Thank you."
                                    isError = false
                                }
                                // Backing out is a decision, not a failure; an
                                // error in red under the button would imply
                                // something broke.
                                is PurchaseOutcome.Cancelled -> Unit
                                is PurchaseOutcome.Pending -> {
                                    message = "That purchase is waiting for approval. " +
                                        "Premium switches on as soon as it clears."
                                    isError = false
                                }
                                is PurchaseOutcome.Failed -> {
                                    message = outcome.message
                                    isError = true
                                }
                            }
                            busy = false
                        }
                    },
                    enabled = !busy && isConfigured && activity != null,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(25.dp)
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        // "Start free trial" only when the selected plan
                        // actually carries one for this account. A button
                        // promising a trial that charges immediately is the
                        // single worst thing a paywall can do.
                        Text(if (selectedTrial == null) "Subscribe" else "Start free trial")
                    }
                }

                TextButton(
                    onClick = {
                        busy = true
                        message = null
                        scope.launch {
                            val restored = Subscriptions.restore()
                            message = if (restored) "Premium restored."
                                      else "No previous subscription found on this Google account."
                            isError = !restored
                            busy = false
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Restore purchases") }

                if (!isConfigured) {
                    Spacer(Modifier.height(12.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                "Subscriptions aren't switched on yet",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 16.sp
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Everything in the app is available right now. " +
                                    "Premium arrives once the store is set up.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Play requires the trial's terms where the customer buys, not
                // only in the Console: how long it runs, what happens at the
                // end, and how to get out before being charged.
                val trialTerms = selectedTrial?.let {
                    "Your ${it.removeSuffix(" free")} trial is free. Unless you cancel " +
                        "at least 24 hours before it ends, it becomes a paid subscription " +
                        "automatically. "
                } ?: ""
                Text(
                    trialTerms +
                        "Subscriptions renew automatically until cancelled. You can cancel " +
                        "any time in the Play Store under Subscriptions; cancelling stops " +
                        "the next renewal and Premium runs to the end of the period you " +
                        "have paid for.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Terms of Use and Privacy Policy, on the purchase screen
                // itself rather than only in the Menu or the store listing.
                //
                // Play's subscription policy wants them reachable from where
                // the customer buys, and the iOS build needs the same under
                // guideline 3.1.2 — so both screens carry the same two links to
                // the same two published pages, and there is one copy of each
                // document to keep current.
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    LegalLink("Terms of Use", ca.tristan.portfolio.Legal.TERMS_OF_USE)
                    LegalLink("Privacy Policy", ca.tristan.portfolio.Legal.PRIVACY_POLICY)
                }
            }

            message?.let {
                Spacer(Modifier.height(14.dp))
                Text(
                    it,
                    fontSize = 12.sp,
                    color = if (isError) ca.tristan.portfolio.ui.theme.LossRed
                            else ca.tristan.portfolio.ui.theme.GainGreen
                )
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

/**
 * One tier, described rather than offered.
 *
 * These two rows were drawn with an empty radio circle and a filled check —
 * which is exactly what a radio group looks like, so "Free" read as an option
 * that could be chosen and then did nothing when tapped. It was never
 * selectable: this card says what each tier IS, and the only choice on the
 * screen is monthly against yearly below. The iconography now says so.
 */
@Composable
private fun TierRow(title: String, detail: String, isCurrent: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            Icons.Filled.Check,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp)
        )
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                if (isCurrent) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color.Transparent,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Text(
                            "CURRENT PLAN",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(3.dp))
            Text(
                detail,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PlanRow(
    title: String,
    period: String,
    price: String,
    trial: String?,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            if (isSelected) 2.dp else 1.dp,
            if (isSelected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = Modifier.fillMaxWidth().clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                if (isSelected) Icons.Filled.RadioButtonChecked
                else Icons.Filled.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (isSelected) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    // Only when Play says this account can actually have it.
                    if (trial != null) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = ca.tristan.portfolio.ui.theme.GainGreen
                        ) {
                            Text(
                                trial.uppercase(),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                            )
                        }
                    }
                }
                Text(
                    period,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(price, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        }
    }
}

/**
 * One underlined link out to a published legal page.
 *
 * Opens in the browser rather than an in-app WebView: the requirement on both
 * stores is a FUNCTIONAL link to the published document, and the published one
 * is what the store listings point at too — so a reviewer tapping this lands on
 * exactly the page they were given in the metadata.
 */
@Composable
private fun LegalLink(label: String, url: String) {
    val context = LocalContext.current
    Text(
        label,
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.primary,
        textDecoration = TextDecoration.Underline,
        modifier = Modifier.clickable {
            // A device with no browser at all would otherwise crash the screen
            // on a tap. Rare, but a crash on the purchase screen is the worst
            // place for one.
            runCatching {
                context.startActivity(
                    android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(url)
                    )
                )
            }
        }
    )
}
