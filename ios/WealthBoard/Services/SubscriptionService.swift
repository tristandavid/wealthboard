import Foundation
#if canImport(StoreKit)
import StoreKit
#endif

// MARK: - Plans

/// What the app sells.
///
/// The identifiers are the contract with the stores, and nothing else in the
/// app should spell them: they have to match App Store Connect and the Play
/// Console character for character, and a typo here fails silently as "no
/// products found" rather than as an error anyone can read.
enum SubscriptionPlan: String, CaseIterable, Identifiable {
    case monthly = "ca.tristan.wealthboard.premium.monthly"
    case yearly  = "ca.tristan.wealthboard.premium.yearly"

    var id: String { rawValue }

    var title: String {
        switch self {
        case .monthly: return "Monthly"
        case .yearly:  return "Yearly"
        }
    }

    /// Shown only until the store answers with the real, localised price.
    ///
    /// Never the number the user is charged. Prices are regional, they change
    /// without an app update, and both stores require the price the customer
    /// will actually pay in their own currency — so the live `displayPrice`
    /// always wins and this is the placeholder behind a slow network.
    var fallbackPrice: String {
        switch self {
        case .monthly: return "$4.99"
        case .yearly:  return "$49.99"
        }
    }

    var period: String {
        switch self {
        case .monthly: return "per month"
        case .yearly:  return "per year"
        }
    }
}

/// One purchasable plan as the store describes it.
struct SubscriptionProduct: Identifiable, Equatable {
    let plan: SubscriptionPlan
    /// Localised and currency-correct, straight from the store.
    let displayPrice: String

    /// The introductory offer, worded for display — "7 days free" — or nil
    /// when there isn't one or this customer cannot have it.
    ///
    /// Read from the store rather than written here, for the same reason the
    /// price is: the trial's length lives in App Store Connect and can be
    /// changed without a release, and eligibility is per Apple ID. Someone who
    /// has already used the trial is not offered it again, and promising them
    /// one would be a straightforward lie at the point of payment.
    var trialDescription: String?

    var id: String { plan.rawValue }
}

enum PurchaseOutcome: Equatable {
    case purchased
    case cancelled
    case pending
    case failed(String)
}

// MARK: - Service

/// Whether this install has Premium, and how to buy it.
///
/// Behind a protocol for the same reason auth and sync are: the screens that
/// use it are real screens with real states, and a build with no store
/// configured should still run rather than show a paywall that can only fail.
@MainActor
protocol SubscriptionService: AnyObject {
    /// True once the store is reachable and products have been loaded.
    var isConfigured: Bool { get }

    /// The entitlement everything else keys on.
    var isPremium: Bool { get }

    /// Plans with live prices, empty until `refresh()` has run.
    var products: [SubscriptionProduct] { get }

    func refresh() async
    func purchase(_ plan: SubscriptionPlan) async -> PurchaseOutcome
    /// Apple requires a visible restore action; Play restores silently but the
    /// button does no harm there.
    func restore() async -> Bool
}

/// The default when no products exist yet.
///
/// `isPremium` is false and `isConfigured` is false, which is what makes the
/// paywall explain itself instead of offering buttons that cannot work.
@MainActor
final class UnconfiguredSubscriptionService: SubscriptionService {
    var isConfigured: Bool { false }
    var isPremium: Bool { false }
    var products: [SubscriptionProduct] { [] }

    func refresh() async {}
    func purchase(_ plan: SubscriptionPlan) async -> PurchaseOutcome {
        .failed("Subscriptions aren't set up in this build yet.")
    }
    func restore() async -> Bool { false }
}

// MARK: - StoreKit 2

#if canImport(StoreKit)

/// The real implementation.
///
/// Unlike Firebase this needs no package: StoreKit ships with the system, so
/// this compiles into every build. What it still needs is the products
/// themselves, created in App Store Connect under the identifiers in
/// `SubscriptionPlan` — until they exist, `Product.products(for:)` returns an
/// empty array, `isConfigured` stays false and the paywall says so.
@available(iOS 15.0, *)
@MainActor
final class StoreKitSubscriptionService: SubscriptionService {

    private var storeProducts: [SubscriptionPlan: Product] = [:]
    private(set) var isPremium: Bool = false
    private var updatesTask: Task<Void, Never>?

    var isConfigured: Bool { !storeProducts.isEmpty }

    /// Built during `refresh()` rather than computed here: eligibility for an
    /// introductory offer is an async call, and a computed property a SwiftUI
    /// body reads cannot await.
    private(set) var products: [SubscriptionProduct] = []

    init() {
        // A subscription can change outside the app: a renewal, a refund, a
        // cancellation, or a purchase made on another device. Listening for
        // the whole life of the process is Apple's documented requirement —
        // without it a refunded subscription keeps its entitlement until the
        // next cold start.
        updatesTask = Task { [weak self] in
            for await update in Transaction.updates {
                guard let self else { return }
                if case .verified(let transaction) = update {
                    await transaction.finish()
                }
                await self.refreshEntitlement()
            }
        }
    }

    deinit { updatesTask?.cancel() }

    func refresh() async {
        let identifiers = SubscriptionPlan.allCases.map(\.rawValue)
        // `try?` rather than a throw: a paywall that cannot reach the store
        // should say it is unavailable, not crash the Menu.
        let fetched = (try? await Product.products(for: identifiers)) ?? []
        var mapped: [SubscriptionPlan: Product] = [:]
        for product in fetched {
            guard let plan = SubscriptionPlan(rawValue: product.id) else { continue }
            mapped[plan] = product
        }
        storeProducts = mapped

        // Ordered by the enum rather than by whatever the store returned, so
        // the paywall does not reorder itself between launches.
        var described: [SubscriptionProduct] = []
        for plan in SubscriptionPlan.allCases {
            guard let product = mapped[plan] else { continue }
            described.append(
                SubscriptionProduct(
                    plan: plan,
                    displayPrice: product.displayPrice,
                    trialDescription: await Self.trialDescription(for: product)
                )
            )
        }
        products = described

        await refreshEntitlement()
    }

    /// How this product's introductory offer should read, if the customer can
    /// actually have it.
    ///
    /// Two conditions, both required. The product must carry a free-trial
    /// offer, and this Apple ID must still be eligible for it — a subscription
    /// group's introductory offer can be used once, so a returning customer
    /// sees the plain price, which is what they will be charged.
    private static func trialDescription(for product: Product) async -> String? {
        guard let subscription = product.subscription,
              let offer = subscription.introductoryOffer,
              offer.paymentMode == .freeTrial else { return nil }
        guard await subscription.isEligibleForIntroOffer else { return nil }

        let count = offer.period.value
        let unit: String
        switch offer.period.unit {
        case .day:   unit = count == 1 ? "day" : "days"
        case .week:  unit = count == 1 ? "week" : "weeks"
        case .month: unit = count == 1 ? "month" : "months"
        case .year:  unit = count == 1 ? "year" : "years"
        @unknown default: return nil
        }
        return "\(count) \(unit) free"
    }

    func purchase(_ plan: SubscriptionPlan) async -> PurchaseOutcome {
        guard let product = storeProducts[plan] else {
            return .failed("That plan isn't available right now.")
        }
        do {
            switch try await product.purchase() {
            case .success(let verification):
                guard case .verified(let transaction) = verification else {
                    // An unverified transaction is one StoreKit could not
                    // cryptographically vouch for. Treating it as a purchase is
                    // how a jailbroken device gets Premium for nothing.
                    return .failed("That purchase couldn't be verified.")
                }
                await transaction.finish()
                await refreshEntitlement()
                return .purchased
            case .userCancelled:
                // Backing out is a decision, not a failure, and the screen
                // should say nothing at all.
                return .cancelled
            case .pending:
                // Ask to Buy, or a payment method needing approval. The
                // entitlement arrives later through Transaction.updates.
                return .pending
            @unknown default:
                return .failed("That purchase didn't complete.")
            }
        } catch {
            return .failed(error.localizedDescription)
        }
    }

    func restore() async -> Bool {
        try? await AppStore.sync()
        await refreshEntitlement()
        return isPremium
    }

    /// Recomputes the entitlement from what StoreKit currently vouches for.
    ///
    /// `currentEntitlements` already excludes expired and revoked
    /// subscriptions, so this is the whole check — there is no receipt to parse
    /// and no expiry date to compare by hand.
    private func refreshEntitlement() async {
        var entitled = false
        for await result in Transaction.currentEntitlements {
            guard case .verified(let transaction) = result else { continue }
            if SubscriptionPlan(rawValue: transaction.productID) != nil {
                entitled = true
                break
            }
        }
        isPremium = entitled
    }
}

#endif

// MARK: - Observable wrapper

/// The entitlement, as something SwiftUI can watch.
///
/// Same problem `AuthSession` solves, same solution: `Services.subscriptions`
/// is a protocol object in a static, so reading `isPremium` inside a `body`
/// gives the answer at the moment that body ran and nothing invalidates it. A
/// purchase would then leave the ad gate and the Menu row showing the old
/// state until the next cold start.
@MainActor
final class SubscriptionSession: ObservableObject {
    static let shared = SubscriptionSession()

    @Published private(set) var isPremium: Bool = false
    @Published private(set) var products: [SubscriptionProduct] = []
    @Published private(set) var isConfigured: Bool = false

    /// Last known entitlement, on disk.
    ///
    /// A `BGAppRefreshTask` wake launches the process WITHOUT the scene: the
    /// WindowGroup's `.task` never runs, nothing calls `refresh()`, and
    /// `StoreKitSubscriptionService.isPremium` is therefore still its initial
    /// `false` however long the user has been paying. Background alerts would
    /// silently never fire — the worst shape a bug can take, because nothing
    /// looks broken, the feature simply never happens.
    ///
    /// So the entitlement is cached the moment StoreKit reports it, and
    /// background code reads `cachedIsPremium` instead of waiting on a
    /// StoreKit round-trip it may not get time for. It is a cache of an
    /// entitlement, not the entitlement: a lapsed subscription keeps it true
    /// until the next foreground refresh corrects it. That is the right way
    /// round — briefly honouring a subscription that just ended is a far
    /// smaller wrong than briefly refusing one that is still paid for.
    ///
    /// Mirrors `Subscriptions.cachedIsPremium` on Android.
    private static let cacheKey = "wealthboard.isPremium"

    static var cachedIsPremium: Bool {
        UserDefaults.standard.bool(forKey: cacheKey)
    }

    private func cache(_ entitled: Bool) {
        UserDefaults.standard.set(entitled, forKey: Self.cacheKey)
    }

    #if DEBUG
    /// Developer-only override for exercising the locked/unlocked UI without
    /// a real (even sandboxed) purchase. Only compiled into debug builds at
    /// all — `#if DEBUG` removes this property, its storage and
    /// `setDebugPremiumOverride` from a release binary, so there is no runtime
    /// flag to trick and no code path a release build could reach.
    ///
    /// PERSISTED, deliberately. Held only in memory it reset on every launch,
    /// which made it useless for the thing it exists to test: alerts are
    /// evaluated at launch, so the override was always nil at exactly the
    /// moment the entitlement was checked, and every alert was silently
    /// skipped as unentitled. Testing a background feature requires the
    /// simulated state to outlive the process.
    private static let debugOverrideKey = "wealthboard.debug.premiumOverride"

    @Published private(set) var debugOverride: Bool? = {
        let defaults = UserDefaults.standard
        guard defaults.object(forKey: debugOverrideKey) != nil else { return nil }
        return defaults.bool(forKey: debugOverrideKey)
    }()

    func setDebugPremiumOverride(_ value: Bool?) {
        debugOverride = value
        if let value {
            UserDefaults.standard.set(value, forKey: Self.debugOverrideKey)
        } else {
            UserDefaults.standard.removeObject(forKey: Self.debugOverrideKey)
        }
        sync()
    }
    #endif

    private init() {}

    /// Loads products and re-reads the entitlement. Cheap enough to call on
    /// the paywall appearing and after any purchase or restore.
    func refresh() async {
        await Services.subscriptions.refresh()
        sync()
    }

    /// Re-reads without touching the network — for a screen appearing after a
    /// purchase made somewhere else in the app.
    func sync() {
        let service = Services.subscriptions
        #if DEBUG
        let effectivePremium = debugOverride ?? service.isPremium
        #else
        let effectivePremium = service.isPremium
        #endif
        if isPremium != effectivePremium { isPremium = effectivePremium }

        // The REAL entitlement is cached, never the debug override: a
        // simulated unlock must not leak out of a debug build into a value
        // background code would go on trusting.
        //
        // And ONLY once the store has actually been reached. `isPremium`
        // starts false and stays false until `refresh()` queries StoreKit, so
        // caching it unconditionally meant every `sync()` — which the Menu
        // does on appear — wrote "not subscribed" over the cache before
        // anything had asked. The background alert pass then read that false
        // and returned immediately. `isConfigured` is true only when products
        // came back, which is the proof that the answer is real rather than
        // the default.
        if service.isConfigured {
            cache(service.isPremium)
        }

        if isConfigured != service.isConfigured { isConfigured = service.isConfigured }
        if products != service.products { products = service.products }
    }

    func purchase(_ plan: SubscriptionPlan) async -> PurchaseOutcome {
        let outcome = await Services.subscriptions.purchase(plan)
        sync()
        return outcome
    }

    func restore() async -> Bool {
        let restored = await Services.subscriptions.restore()
        sync()
        return restored
    }
}
