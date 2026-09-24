package ca.tristan.portfolio.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the app sells.
 *
 * The ids are the contract with Play, and nothing else should spell them: they
 * have to match the Play Console character for character, and a typo fails
 * silently as "no products found" rather than as an error anyone can read.
 * They deliberately match the iOS product ids so both stores describe the same
 * thing.
 */
enum class SubscriptionPlan(val productId: String, val title: String, val period: String) {
    MONTHLY("ca.tristan.wealthboard.premium.monthly", "Monthly", "per month"),
    YEARLY("ca.tristan.wealthboard.premium.yearly", "Yearly", "per year");

    /**
     * Shown only until Play answers with the real, localised price. Never the
     * number the user is charged: prices are regional, they change without an
     * app update, and Play requires the customer be shown what they will
     * actually pay in their own currency.
     */
    val fallbackPrice: String
        get() = if (this == MONTHLY) "$4.99" else "$49.99"
}

/** One purchasable plan as Play describes it. */
data class SubscriptionProduct(
    val plan: SubscriptionPlan,
    /** Localised and currency-correct, straight from Play. */
    val displayPrice: String,
    /**
     * The introductory offer, worded for display — "7 days free" — or null when
     * there is none or this account cannot have it.
     *
     * Read from Play rather than written here: the trial's length lives in the
     * Play Console and can change without a release, and eligibility is per
     * Google account. Play only returns offers the account qualifies for, so a
     * returning customer simply gets no badge — which is correct, because they
     * will be charged immediately.
     */
    val trialDescription: String? = null,
    /** Identifies the offer to launch; Play requires it at purchase. */
    val offerToken: String? = null
)

sealed interface PurchaseOutcome {
    object Purchased : PurchaseOutcome
    object Cancelled : PurchaseOutcome
    object Pending : PurchaseOutcome
    data class Failed(val message: String) : PurchaseOutcome
}

/**
 * Whether this install has Premium, and how to buy it.
 *
 * Mirrors `SubscriptionService` on iOS. Screens read [isPremium] and
 * [products]; whether Play is reachable is [isConfigured], which is what makes
 * the paywall explain itself rather than offer buttons that cannot work.
 */
object Subscriptions : PurchasesUpdatedListener {

    private const val TRIAL_PRICE_ZERO = 0L

    private const val PREFS_NAME = "billing"
    private const val KEY_CACHED_PREMIUM = "is_premium"
    private const val KEY_DEBUG_OVERRIDE = "debug_premium_override"

    private var billing: BillingClient? = null
    private var appContext: Context? = null
    private var connecting: CompletableDeferred<Boolean>? = null
    private var details: Map<SubscriptionPlan, ProductDetails> = emptyMap()

    /** What Play actually reports. [isPremium] is this, unless [debugOverride] is set. */
    private val _isPremiumReal = MutableStateFlow(false)

    /**
     * Developer-only override for exercising the locked/unlocked UI without a
     * real purchase — set only through [setDebugPremiumOverride], which
     * refuses to do anything unless the caller proves this is a debug build.
     * Persisted in a debug build only — see [setDebugPremiumOverride] for why
     * a memory-only override made the alert path untestable.
     */
    private var debugOverride: Boolean? = null
    /** Whether [debugOverride] has been read back from disk this process. */
    private var debugOverrideLoaded = false

    private val _isPremium = MutableStateFlow(false)
    val isPremium: StateFlow<Boolean> = _isPremium.asStateFlow()

    private fun recomputeIsPremium() {
        _isPremium.value = debugOverride ?: _isPremiumReal.value
    }

    /**
     * Last known entitlement, on disk.
     *
     * Background work runs in a process that may never have shown a screen, so
     * `isPremium` there is whatever this object was constructed with — false —
     * however long the user has been paying. A worker reading that would
     * silently stop honouring a live subscription, which is the worst shape a
     * bug can take: nothing looks broken, the feature simply never happens.
     *
     * So the entitlement is cached the moment Play reports it, and background
     * code reads [cachedIsPremium] instead of connecting to Play on every
     * pass. It is a cache of a billing answer, not the billing answer: a
     * lapsed subscription keeps it true until the next foreground refresh
     * corrects it. That is the right way round — briefly honouring a
     * subscription that just ended is a far smaller wrong than briefly
     * refusing one that is still paid for.
     */
    private fun cacheEntitlement(entitled: Boolean) {
        val context = appContext ?: return
        runCatching {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_CACHED_PREMIUM, entitled)
                .apply()
        }
    }

    /** For background work. See [cacheEntitlement]. */
    fun cachedIsPremium(context: Context): Boolean {
        return runCatching {
            val prefs = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            // The debug override wins, and is read from DISK rather than from
            // the in-memory field: this is called from a background worker
            // whose process may never have shown a screen, so the field is
            // null there however the developer set the toggle.
            debugOverride?.let { return@runCatching it }
            if (prefs.contains(KEY_DEBUG_OVERRIDE)) {
                return@runCatching prefs.getBoolean(KEY_DEBUG_OVERRIDE, false)
            }
            prefs.getBoolean(KEY_CACHED_PREMIUM, false)
        }.getOrDefault(false)
    }

    /**
     * Forces [isPremium] to [value] (or, with `null`, releases the override
     * and goes back to Play's real answer) so the creator can see the app as
     * a locked or an unlocked user on demand.
     *
     * [isDebugBuild] must be the caller's own `BuildConfig.DEBUG` — this
     * object doesn't read BuildConfig itself, so there is no code path here
     * that a release build can reach, whatever it passes.
     */
    fun setDebugPremiumOverride(isDebugBuild: Boolean, value: Boolean?) {
        if (!isDebugBuild) return
        debugOverride = value
        debugOverrideLoaded = true
        // PERSISTED, deliberately. Held only in memory it reset on every
        // process start, which made it useless for the thing it exists to
        // test: the alert worker runs in a freshly launched process, so the
        // override was always null at exactly the moment the entitlement was
        // checked, and every alert was silently skipped as unentitled.
        val context = appContext
        if (context != null) {
            runCatching {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                if (value == null) prefs.edit().remove(KEY_DEBUG_OVERRIDE).apply()
                else prefs.edit().putBoolean(KEY_DEBUG_OVERRIDE, value).apply()
            }
        }
        recomputeIsPremium()
    }

    /**
     * Restores a persisted debug override, once per process.
     *
     * Only ever called with a real BuildConfig.DEBUG from the caller, so a
     * release build never reads the key even if one somehow existed on disk.
     */
    fun loadDebugOverride(isDebugBuild: Boolean, context: Context) {
        if (!isDebugBuild || debugOverrideLoaded) return
        debugOverrideLoaded = true
        runCatching {
            val prefs = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (prefs.contains(KEY_DEBUG_OVERRIDE)) {
                debugOverride = prefs.getBoolean(KEY_DEBUG_OVERRIDE, false)
            }
        }
        recomputeIsPremium()
    }

    /** The active override, if any — so debug UI can show it's not the real state. */
    fun debugPremiumOverrideOrNull(): Boolean? = debugOverride

    private val _products = MutableStateFlow<List<SubscriptionProduct>>(emptyList())
    val products: StateFlow<List<SubscriptionProduct>> = _products.asStateFlow()

    private val _isConfigured = MutableStateFlow(false)
    val isConfigured: StateFlow<Boolean> = _isConfigured.asStateFlow()

    /** Resolves a purchase flow started by [purchase]. */
    private var pending: CompletableDeferred<PurchaseOutcome>? = null

    fun start(context: Context) {
        appContext = context.applicationContext
        if (billing != null) return
        billing = BillingClient.newBuilder(context.applicationContext)
            .setListener(this)
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder()
                    .enableOneTimeProducts()
                    .build()
            )
            .build()
    }

    /**
     * Connects if needed. Play's client drops its connection on its own
     * schedule, so every path re-checks rather than assuming the connection
     * made at startup is still live.
     */
    private suspend fun ensureConnected(): Boolean {
        val client = billing ?: return false
        if (client.isReady) return true

        connecting?.let { return it.await() }
        val gate = CompletableDeferred<Boolean>()
        connecting = gate
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (!gate.isCompleted) {
                    gate.complete(result.responseCode == BillingClient.BillingResponseCode.OK)
                }
                connecting = null
            }
            override fun onBillingServiceDisconnected() {
                if (!gate.isCompleted) gate.complete(false)
                connecting = null
            }
        })
        return gate.await()
    }

    /** Loads products and re-reads the entitlement. */
    suspend fun refresh() {
        if (!ensureConnected()) {
            _isConfigured.value = false
            return
        }
        val client = billing ?: return

        val query = QueryProductDetailsParams.newBuilder()
            .setProductList(
                SubscriptionPlan.values().map { plan ->
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(plan.productId)
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build()
                }
            )
            .build()

        // Billing 8.0 changed this callback's second argument from a plain
        // List<ProductDetails> to a QueryProductDetailsResult wrapper (which
        // also carries any ids Play couldn't find) — unwrap it here.
        val fetched = CompletableDeferred<List<ProductDetails>>()
        client.queryProductDetailsAsync(query) { _, result -> fetched.complete(result.productDetailsList) }
        val list = fetched.await()

        val mapped = HashMap<SubscriptionPlan, ProductDetails>()
        for (detail in list) {
            val plan = SubscriptionPlan.values().firstOrNull { it.productId == detail.productId }
            if (plan != null) mapped[plan] = detail
        }
        details = mapped

        // Ordered by the enum rather than by whatever Play returned, so the
        // paywall does not reorder itself between launches.
        _products.value = SubscriptionPlan.values().mapNotNull { plan ->
            mapped[plan]?.let { describe(plan, it) }
        }
        _isConfigured.value = mapped.isNotEmpty()

        refreshEntitlement()
    }

    /**
     * Turns Play's offer structure into a price and a trial line.
     *
     * A subscription's pricing is a list of offers, each a list of phases. A
     * free trial is a leading phase priced at zero; the phase after it carries
     * the real recurring price. Reading only the first phase would print
     * "Free" as the plan's price — the kind of mistake that survives review and
     * then gets reported as a billing bug.
     */
    private fun describe(plan: SubscriptionPlan, detail: ProductDetails): SubscriptionProduct {
        val offers = detail.subscriptionOfferDetails
        // Prefer the offer carrying a trial when Play returns more than one
        // this account qualifies for.
        val offer = offers?.maxByOrNull { candidate ->
            candidate.pricingPhases.pricingPhaseList.count { it.priceAmountMicros == TRIAL_PRICE_ZERO }
        } ?: offers?.firstOrNull()

        val phases = offer?.pricingPhases?.pricingPhaseList.orEmpty()
        val trialPhase = phases.firstOrNull { it.priceAmountMicros == TRIAL_PRICE_ZERO }
        val paidPhase = phases.firstOrNull { it.priceAmountMicros > TRIAL_PRICE_ZERO }

        return SubscriptionProduct(
            plan = plan,
            displayPrice = paidPhase?.formattedPrice ?: plan.fallbackPrice,
            trialDescription = trialPhase?.billingPeriod?.let { describePeriod(it) },
            offerToken = offer?.offerToken
        )
    }

    /**
     * ISO-8601 period ("P7D", "P1W", "P1M") as something readable. Play states
     * trial lengths in ISO durations; printing one raw would put "P7D free" in
     * front of a customer.
     */
    private fun describePeriod(iso: String): String? {
        val match = Regex("^P(\\d+)([DWMY])$").find(iso.uppercase()) ?: return null
        val count = match.groupValues[1].toIntOrNull() ?: return null
        val unit = when (match.groupValues[2]) {
            "D" -> if (count == 1) "day" else "days"
            "W" -> if (count == 1) "week" else "weeks"
            "M" -> if (count == 1) "month" else "months"
            "Y" -> if (count == 1) "year" else "years"
            else -> return null
        }
        return "$count $unit free"
    }

    suspend fun purchase(activity: Activity, plan: SubscriptionPlan): PurchaseOutcome {
        if (!ensureConnected()) return PurchaseOutcome.Failed("Couldn't reach Google Play.")
        val client = billing ?: return PurchaseOutcome.Failed("Billing isn't available.")
        val detail = details[plan]
            ?: return PurchaseOutcome.Failed("That plan isn't available right now.")
        val token = _products.value.firstOrNull { it.plan == plan }?.offerToken
            ?: return PurchaseOutcome.Failed("That plan isn't available right now.")

        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(detail)
                        .setOfferToken(token)
                        .build()
                )
            )
            .build()

        val gate = CompletableDeferred<PurchaseOutcome>()
        pending = gate
        val launch = client.launchBillingFlow(activity, params)
        if (launch.responseCode != BillingClient.BillingResponseCode.OK) {
            pending = null
            return PurchaseOutcome.Failed("Couldn't open the purchase screen.")
        }
        return gate.await()
    }

    /**
     * Play restores automatically on query, so "restore" is a re-read rather
     * than a separate transaction. The button still earns its place: someone
     * who has reinstalled wants to be told it worked.
     */
    suspend fun restore(): Boolean {
        if (!ensureConnected()) return false
        refreshEntitlement()
        return _isPremium.value
    }

    private suspend fun refreshEntitlement() {
        val client = billing ?: return
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()

        val fetched = CompletableDeferred<List<Purchase>>()
        client.queryPurchasesAsync(params) { _, purchases -> fetched.complete(purchases) }
        val purchases = fetched.await()

        // PURCHASED only. A PENDING purchase is one awaiting payment — a cash
        // top-up, a parent's approval — and granting the entitlement on it
        // hands out Premium for money that may never arrive.
        val entitled = purchases.any { purchase ->
            purchase.purchaseState == Purchase.PurchaseState.PURCHASED &&
                purchase.products.any { id ->
                    SubscriptionPlan.values().any { it.productId == id }
                }
        }
        _isPremiumReal.value = entitled
        cacheEntitlement(entitled)
        recomputeIsPremium()

        // Play refunds anything not acknowledged within three days, so this is
        // not housekeeping — an unacknowledged purchase is money handed back.
        for (purchase in purchases) {
            if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) continue
            if (purchase.isAcknowledged) continue
            val ack = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build()
            client.acknowledgePurchase(ack) { }
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        val outcome: PurchaseOutcome = when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                val anyPending = purchases?.any {
                    it.purchaseState == Purchase.PurchaseState.PENDING
                } == true
                if (anyPending) PurchaseOutcome.Pending else PurchaseOutcome.Purchased
            }
            // Backing out is a decision, not a failure; the screen says nothing.
            BillingClient.BillingResponseCode.USER_CANCELED -> PurchaseOutcome.Cancelled
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> PurchaseOutcome.Purchased
            else -> PurchaseOutcome.Failed(
                result.debugMessage.ifBlank { "That purchase didn't complete." }
            )
        }
        pending?.complete(outcome)
        pending = null

        if (outcome is PurchaseOutcome.Purchased) {
            _isPremiumReal.value = true
            cacheEntitlement(true)
            recomputeIsPremium()
        }
    }
}
