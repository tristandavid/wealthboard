import Foundation

// The domain model, ported from `data/db/Entities.kt`.
//
// Room entities become plain `Codable` structs with a UUID identity. Everything
// in WealthBoard is entered by hand — there is no institution sign-in or page
// scraping — so the model is small and the store can be a single document.

// MARK: - Holding type

enum HoldingType: String, Codable, CaseIterable, Identifiable {
    case etf = "ETF"
    case stock = "STOCK"
    case segFund = "SEG_FUND"
    case mutualFund = "MUTUAL_FUND"
    case crypto = "CRYPTO"
    case cash = "CASH"
    case other = "OTHER"

    var id: String { rawValue }

    /// Clean label for display — the raw case name reads as a code constant,
    /// not something to show someone.
    var label: String {
        switch self {
        case .etf: return "ETF"
        case .stock: return "Stock"
        case .segFund: return "Seg Funds/Variable Annuities"
        case .mutualFund: return "Mutual Fund"
        case .crypto: return "Crypto"
        case .cash: return "Cash"
        case .other: return "Other"
        }
    }

    /// Maps a quote provider's instrument type onto a `HoldingType`.
    ///
    /// Providers answer in different vocabularies: Yahoo says "EQUITY" and
    /// "ETF", others use human labels like "Common Stock" and "ETP". Handling
    /// only one of those is how a stock ends up filed as an ETF.
    ///
    /// Returns nil for types this app has no bucket for (indices, futures, FX,
    /// options), so the caller leaves the user's own choice alone.
    static func fromQuoteType(_ raw: String?) -> HoldingType? {
        guard let raw else { return nil }
        let t = raw.trimmingCharacters(in: .whitespaces).uppercased().replacingOccurrences(of: "-", with: " ")
        if t.isEmpty { return nil }

        // Yahoo
        if t == "EQUITY" { return .stock }
        if t == "ETF" { return .etf }
        if t == "MUTUALFUND" { return .mutualFund }
        if t == "CRYPTOCURRENCY" { return .crypto }

        // Finnhub and other human-labelled sources
        if t.contains("ETP") || t.contains("ETF") || t.contains("EXCHANGE TRADED") { return .etf }
        if t.contains("MUTUAL FUND") || t.contains("OPEN END FUND") || t.contains("CLOSED END FUND")
            || (t.contains("FUND") && !t.contains("FUNDAMENTAL")) { return .mutualFund }
        if t.contains("CRYPTO") || t.contains("DIGITAL CURRENCY") { return .crypto }
        if t.contains("COMMON STOCK") || t.contains("ORDINARY SHARE") || t.contains("PREFERRED")
            || t.contains("ADR") || t.contains("GDR") || t.contains("REIT")
            || t.contains("EQUITY") || t == "STOCK" || t == "CS" { return .stock }
        return nil
    }
}

// MARK: - Tax treatment

/// How an account is treated for tax, which is the single fact that decides
/// whether any tax figure shown against a holding means anything at all.
///
/// Deliberately three buckets rather than a list of product names. "TFSA",
/// "Roth IRA", "ISA" and "FHSA" all behave the same way for the purposes this
/// app cares about, and a name list would need extending for every country.
enum TaxTreatment: String, Codable, CaseIterable, Identifiable {
    /// Ordinary brokerage/cash account. Dividends and gains are taxed as earned.
    case taxable = "TAXABLE"
    /// RRSP, Traditional IRA, 401(k): no tax now, taxed as income on withdrawal.
    case taxDeferred = "TAX_DEFERRED"
    /// TFSA, Roth IRA, FHSA: no domestic tax on growth or withdrawal.
    case taxFree = "TAX_FREE"

    var id: String { rawValue }

    var label: String {
        switch self {
        case .taxable: return "Taxable"
        case .taxDeferred: return "Tax-deferred"
        case .taxFree: return "Tax-free"
        }
    }

    /// Plain description of what happens to the money, with no country's
    /// product names in it — those live in `TaxRules.examples(for:)`, built from
    /// the same suggestion list the account chips use, so the two can't disagree.
    var detail: String {
        switch self {
        case .taxable: return "Taxed as you earn it"
        case .taxDeferred: return "No tax now, taxed when you withdraw"
        case .taxFree: return "No tax on growth or withdrawals"
        }
    }
}

// MARK: - Account

/// A user-named bucket of holdings (e.g. "TFSA", "RRSP", "Non-registered").
///
/// `taxTreatment` is OPTIONAL on purpose, and nil means "the user hasn't said".
/// A default of `.taxable` would have been easier, and wrong: it would put real
/// tax figures against a TFSA — where they are all zero — without anyone having
/// chosen that, and a wrong tax number shown confidently is worse than none.
struct Account: Identifiable, Codable, Hashable {
    var id: UUID = UUID()
    var displayName: String
    var taxTreatment: TaxTreatment?
}

// MARK: - Holding

struct Holding: Identifiable, Codable, Hashable {
    var id: UUID = UUID()
    var accountId: UUID
    var name: String
    /// nil for seg/mutual funds priced only by NAV snapshot.
    var ticker: String?
    var type: HoldingType
    var units: Double
    /// Manually typed price, used until a live quote replaces it, or for funds
    /// with no ticker.
    var manualPrice: Double?
    var currency: String = "CAD"
    var lastKnownPrice: Double?
    /// The previous session's close for `lastKnownPrice`, stored beside it.
    ///
    /// Persisted rather than held only in a fetched quote, because the day
    /// change is drawn from it. Kept in memory only, a refresh that failed for
    /// one holding dropped that holding out of the day-change sum while its
    /// full value stayed in the total — so the percentage was quietly measured
    /// against a bigger portfolio than the one it described. Stored, the last
    /// known close survives a bad minute and the figure stays consistent.
    ///
    /// Optional and absent from older saved documents, which decode to nil.
    var previousClose: Double?
    var lastPriceAt: Date?
    /// For yield-on-cost calculations.
    var costBasis: Double?
    /// When the holding was first added. Used to filter out auto-imported
    /// historical dividend payments that pre-date the user's record.
    var createdAt: Date = Date()

    /// The price this position is currently valued at, in its own currency.
    var effectivePrice: Double {
        lastKnownPrice ?? manualPrice ?? 0
    }

    /// Market value in the holding's own currency.
    var nativeValue: Double {
        effectivePrice * units
    }

    /// The key a holding is grouped under when the same security is held in
    /// more than one account: the ticker where there is one, and the name where
    /// there isn't.
    var securityKey: String {
        if let ticker, !ticker.isEmpty { return ticker.uppercased() }
        return name
    }

    var normalizedCurrency: String {
        let c = currency.trimmingCharacters(in: .whitespaces).uppercased()
        return c.isEmpty ? "CAD" : c
    }
}

// MARK: - Price snapshot

struct PriceSnapshot: Identifiable, Codable, Hashable {
    var id: UUID = UUID()
    var holdingId: UUID
    var at: Date
    var price: Double
}

// MARK: - Dividend payment

/// A recorded dividend/distribution payment for a holding.
struct DividendPayment: Identifiable, Codable, Hashable {
    var id: UUID = UUID()
    var holdingId: UUID
    var paidAt: Date
    /// Total cash received, in the holding's currency.
    var amount: Double
    var perUnit: Double?
    var currency: String = "CAD"
    var note: String?

    var normalizedCurrency: String {
        let c = currency.trimmingCharacters(in: .whitespaces).uppercased()
        return c.isEmpty ? "CAD" : c
    }
}

// MARK: - Watchlist

/// A ticker the user is watching on the Markets tab — separate from anything
/// actually owned. Seeded with common indices on first run; the user can add
/// or remove freely.
struct WatchlistItem: Identifiable, Codable, Hashable {
    var id: UUID = UUID()
    var ticker: String
    var addedAt: Date
    /// User-chosen display name overriding the fetched quote name.
    var customName: String?

    // Cached last quote, held so the Markets tab can paint real numbers the
    // instant the app opens instead of showing a column of spinners while the
    // network answers. Refreshed in the background; treated as display-only.
    var cachedPrice: Double?
    var cachedPreviousClose: Double?
    var cachedName: String?
    var cachedCurrency: String?
    var cachedAt: Date?
}

// MARK: - Transaction

/// What a recorded transaction did to a position.
enum TransactionType: String, Codable, CaseIterable, Identifiable {
    /// Units bought with new money. Increases units and cost basis.
    case buy = "BUY"
    /// Units sold. Decreases units, and reduces cost basis proportionally.
    case sell = "SELL"
    /// Units acquired by reinvesting a dividend. Increases units, and increases
    /// cost basis by the reinvested cash (that cash was taxable income, so it
    /// counts as money put into the position).
    case drip = "DRIP"

    var id: String { rawValue }

    var label: String {
        switch self {
        case .buy: return "Buy"
        case .sell: return "Sell"
        case .drip: return "DRIP"
        }
    }
}

/// One buy, sell or dividend reinvestment against a holding.
///
/// Holdings remain the position of record — this is the audit trail of how a
/// position got to its current size. Applying a transaction updates the
/// holding's units and cost basis; the row here is what lets the user review,
/// and undo, that change later.
struct PortfolioTransaction: Identifiable, Codable, Hashable {
    var id: UUID = UUID()
    var holdingId: UUID
    var type: TransactionType
    var at: Date
    var shares: Double
    var pricePerShare: Double
    var currency: String = "CAD"
    var note: String?
    /// For DRIP rows: the dividend payment this reinvestment came from, so a
    /// payment can't be reinvested twice. nil for ordinary buys and sells.
    var sourceDividendId: UUID?

    /// Total cash value of the transaction.
    var amount: Double { shares * pricePerShare }
}

// MARK: - The whole portfolio document

/// Everything the app stores locally, in one serialisable value.
///
/// A single document rather than a set of tables: the whole portfolio is a few
/// kilobytes of hand-entered data, every screen reads most of it at once, and
/// keeping it as one value means the view model never has to reconcile
/// partially-loaded state.
struct PortfolioDocument: Codable {
    var accounts: [Account] = []
    var holdings: [Holding] = []
    var dividends: [DividendPayment] = []
    var transactions: [PortfolioTransaction] = []
    var watchlist: [WatchlistItem] = []
    var priceSnapshots: [PriceSnapshot] = []
    var alerts: [PriceAlert] = []

    /// Bumped whenever the shape changes, so a future migration has something
    /// to branch on.
    var schemaVersion: Int = 2

    init() {}

    /// Decoded field by field, every one of them optional.
    ///
    /// The synthesized `init(from:)` throws `keyNotFound` for any non-optional
    /// property whose key is absent — a Swift default is a default for `init()`,
    /// not for decoding. So adding `alerts` to this struct would have made
    /// every document written by an earlier build fail to decode, and
    /// `PortfolioStore.load()` swallows a decode failure by returning a FRESH
    /// document: every user's entire hand-entered portfolio, silently replaced
    /// with an empty one on first launch after the update.
    ///
    /// Writing this out by hand is what makes the next added field a one-line
    /// change instead of that.
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        accounts = try c.decodeIfPresent([Account].self, forKey: .accounts) ?? []
        holdings = try c.decodeIfPresent([Holding].self, forKey: .holdings) ?? []
        dividends = try c.decodeIfPresent([DividendPayment].self, forKey: .dividends) ?? []
        transactions = try c.decodeIfPresent([PortfolioTransaction].self, forKey: .transactions) ?? []
        watchlist = try c.decodeIfPresent([WatchlistItem].self, forKey: .watchlist) ?? []
        priceSnapshots = try c.decodeIfPresent([PriceSnapshot].self, forKey: .priceSnapshots) ?? []
        alerts = try c.decodeIfPresent([PriceAlert].self, forKey: .alerts) ?? []
        schemaVersion = try c.decodeIfPresent(Int.self, forKey: .schemaVersion) ?? 1
    }
}

// MARK: - Alerts

/// What condition an alert watches for.
///
/// Mirrors `AlertKind` on Android, case for case, so a portfolio backed up on
/// one phone describes the same rules on the other.
enum AlertKind: String, Codable, CaseIterable, Identifiable {
    /// Fires when the last price rises to or above the threshold.
    case priceAbove = "PRICE_ABOVE"
    /// Fires when the last price falls to or below the threshold.
    case priceBelow = "PRICE_BELOW"
    /// Fires when the day's move, in either direction, reaches the threshold
    /// percent. Absolute on purpose: someone watching for a 5% day wants to
    /// hear about it whichever way it went, and two separate rules for up and
    /// down would be the same alert entered twice.
    case dayMovePercent = "DAY_MOVE_PERCENT"
    /// Fires when a holding's ex-dividend date is within the threshold number
    /// of days — the one alert about the calendar rather than the price, and
    /// the reason this app exists, since buying after the ex-date means
    /// waiting a whole cycle for the first payment.
    case exDividendWithinDays = "EX_DIVIDEND_WITHIN_DAYS"

    var id: String { rawValue }

    var label: String {
        switch self {
        case .priceAbove: return "Price rises to"
        case .priceBelow: return "Price falls to"
        case .dayMovePercent: return "Moves in a day by"
        case .exDividendWithinDays: return "Ex-dividend within"
        }
    }

    /// What the threshold field is asking for.
    var thresholdLabel: String {
        switch self {
        case .priceAbove, .priceBelow: return "Price"
        case .dayMovePercent: return "Percent"
        case .exDividendWithinDays: return "Days before"
        }
    }
}

/// One thing the user asked to be told about.
///
/// Keyed by TICKER rather than by holding id, deliberately. The same security
/// can sit in three accounts as three holding rows, and an alert keyed to one
/// of them would go quiet the moment that particular row was sold — which is
/// not what "tell me when VDY hits $40" means. It also lets an alert watch
/// something on the watchlist that is not held at all, which is most of the
/// reason to want one.
struct PriceAlert: Identifiable, Codable, Hashable {
    var id: UUID = UUID()
    var ticker: String
    var kind: AlertKind
    var threshold: Double
    var enabled: Bool = true
    var createdAt: Date = Date()

    /// Set while the condition is CURRENTLY true, cleared when it goes false
    /// again — the latch that stops a price sitting above its target from
    /// firing the same notification every background pass for a week.
    ///
    /// Storing "when it last fired" and suppressing for a fixed window was the
    /// obvious alternative and is worse: pick an hour and a genuine second
    /// crossing goes unreported, pick a day and the alert is useless for
    /// anything intraday. Re-arming on the condition going false is what a
    /// person means by "tell me when it crosses".
    var triggeredAt: Date?

    /// The value that tripped it, kept so the list can say what happened.
    var lastValue: Double?

    /// Same hand-written decoding as `PortfolioDocument`, and for the same
    /// reason: these rows are inside a document that must survive the next
    /// field being added to this struct.
    init(
        id: UUID = UUID(),
        ticker: String,
        kind: AlertKind,
        threshold: Double,
        enabled: Bool = true,
        createdAt: Date = Date(),
        triggeredAt: Date? = nil,
        lastValue: Double? = nil
    ) {
        self.id = id
        self.ticker = ticker
        self.kind = kind
        self.threshold = threshold
        self.enabled = enabled
        self.createdAt = createdAt
        self.triggeredAt = triggeredAt
        self.lastValue = lastValue
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decodeIfPresent(UUID.self, forKey: .id) ?? UUID()
        ticker = try c.decodeIfPresent(String.self, forKey: .ticker) ?? ""
        kind = try c.decodeIfPresent(AlertKind.self, forKey: .kind) ?? .priceAbove
        threshold = try c.decodeIfPresent(Double.self, forKey: .threshold) ?? 0
        enabled = try c.decodeIfPresent(Bool.self, forKey: .enabled) ?? true
        createdAt = try c.decodeIfPresent(Date.self, forKey: .createdAt) ?? Date()
        triggeredAt = try c.decodeIfPresent(Date.self, forKey: .triggeredAt)
        lastValue = try c.decodeIfPresent(Double.self, forKey: .lastValue)
    }

    /// How this rule reads in a list.
    var summary: String {
        switch kind {
        case .priceAbove:
            return "Rises to \(Self.money(threshold)) or above"
        case .priceBelow:
            return "Falls to \(Self.money(threshold)) or below"
        case .dayMovePercent:
            return "Moves \(Self.trimmed(threshold))% or more in a day, either way"
        case .exDividendWithinDays:
            return "Ex-dividend date is within \(Self.trimmed(threshold)) days"
        }
    }

    /// Grouped to the reader's locale. `String(format:)` has no thousands
    /// flag, so a NumberFormatter is the only way to get "1,234.56" rather
    /// than "1234.56" for a price someone is comparing against their broker.
    static func money(_ value: Double) -> String {
        let f = NumberFormatter()
        f.numberStyle = .decimal
        f.minimumFractionDigits = 2
        f.maximumFractionDigits = 2
        return f.string(from: NSNumber(value: value)) ?? String(format: "%.2f", value)
    }

    /// Drops a pointless ".0" so "5 days" doesn't read as "5.0 days".
    static func trimmed(_ value: Double) -> String {
        value == value.rounded() && abs(value) < 1e9
            ? String(Int(value))
            : String(format: "%.2f", value)
    }
}
