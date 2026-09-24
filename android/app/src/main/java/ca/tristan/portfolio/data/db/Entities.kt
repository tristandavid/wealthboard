package ca.tristan.portfolio.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class HoldingType {
    ETF, STOCK, SEG_FUND, MUTUAL_FUND, CRYPTO, CASH, OTHER;

    /**
     * Clean label for display — the raw enum name (SEG_FUND, MUTUAL_FUND)
     * reads as a code constant, not something to show someone. ETF is kept
     * fully capitalized as the acronym it is; seg funds are labelled as
     * "Seg Funds/Variable Annuities".
     */
    fun label(): String = when (this) {
        ETF -> "ETF"
        STOCK -> "Stock"
        SEG_FUND -> "Seg Funds/Variable Annuities"
        MUTUAL_FUND -> "Mutual Fund"
        CRYPTO -> "Crypto"
        CASH -> "Cash"
        OTHER -> "Other"
    }

    companion object {
        /**
         * Maps a quote provider's instrument type onto a [HoldingType].
         *
         * Three screens each had their own copy of this `when`, and all three
         * only understood Yahoo's vocabulary ("EQUITY", "ETF", …). Finnhub —
         * which is the primary search source — answers with human labels like
         * "Common Stock" and "ETP" instead, so every one of those fell through
         * to the `else` branch and a stock was filed as an ETF. That is why
         * AAPL showed "Holding Type: ETF".
         *
         * Returns null for types this app has no bucket for (indices, futures,
         * FX, options), so the caller leaves the user's own choice alone.
         */
        fun fromQuoteType(raw: String?): HoldingType? {
            val t = raw?.trim()?.uppercase()?.replace("-", " ") ?: return null
            return when {
                t.isEmpty() -> null
                // Yahoo
                t == "EQUITY" -> STOCK
                t == "ETF" -> ETF
                t == "MUTUALFUND" -> MUTUAL_FUND
                t == "CRYPTOCURRENCY" -> CRYPTO
                // Finnhub and other human-labelled sources
                t.contains("ETP") || t.contains("ETF") ||
                    t.contains("EXCHANGE TRADED") -> ETF
                t.contains("MUTUAL FUND") || t.contains("OPEN END FUND") ||
                    t.contains("CLOSED END FUND") ||
                    (t.contains("FUND") && !t.contains("FUNDAMENTAL")) -> MUTUAL_FUND
                t.contains("CRYPTO") || t.contains("DIGITAL CURRENCY") -> CRYPTO
                t.contains("COMMON STOCK") || t.contains("ORDINARY SHARE") ||
                    t.contains("PREFERRED") || t.contains("ADR") ||
                    t.contains("GDR") || t.contains("REIT") ||
                    t.contains("EQUITY") || t == "STOCK" || t == "CS" -> STOCK
                else -> null
            }
        }
    }
}

/**
 * A user-named bucket of holdings (e.g. "TFSA", "RRSP", "Non-registered").
 * Everything in WealthBoard is entered by hand — there is no institution
 * sign-in or page scraping — so an account is just organizational.
 */
/**
 * How an account is treated for tax, which is the single fact that decides
 * whether any tax figure shown against a holding means anything at all.
 *
 * Deliberately three buckets rather than a list of product names. "TFSA",
 * "Roth IRA", "ISA" and "FHSA" all behave the same way for the purposes this
 * app cares about, and a name list would need extending for every country;
 * these three do not.
 */
enum class TaxTreatment {
    /** Ordinary brokerage/cash account. Dividends and gains are taxed as earned. */
    TAXABLE,

    /** RRSP, Traditional IRA, 401(k): no tax now, taxed as income on withdrawal. */
    TAX_DEFERRED,

    /** TFSA, Roth IRA, FHSA: no domestic tax on growth or withdrawal. */
    TAX_FREE;

    fun label(): String = when (this) {
        TAXABLE -> "Taxable"
        TAX_DEFERRED -> "Tax-deferred"
        TAX_FREE -> "Tax-free"
    }

    /**
     * Plain description of what happens to the money, with no country's product
     * names in it.
     *
     * The country-specific examples live in TaxRules.examplesFor(), built from
     * the same suggestion list the account chips use, so the two can never
     * disagree. This function used to carry an `if (residency == "US") … else
     * Canadian` branch, which meant a UK user picking from ISA and SIPP chips
     * read "RRSP, RRIF, LIRA" underneath the radio buttons. Hardcoding two
     * countries into an enum that twelve countries now use was the mistake;
     * this describes the BEHAVIOUR instead, which is true everywhere.
     */
    fun description(): String = when (this) {
        TAXABLE -> "Taxed as you earn it"
        TAX_DEFERRED -> "No tax now, taxed when you withdraw"
        TAX_FREE -> "No tax on growth or withdrawals"
    }
}

/**
 * A user-named bucket of holdings (e.g. "TFSA", "RRSP", "Non-registered").
 *
 * [taxTreatment] is NULLABLE on purpose, and null means "the user hasn't said".
 * A default of TAXABLE would have been easier, and wrong: it would put real
 * tax figures against a TFSA — where they are all zero — without anyone having
 * chosen that, and a wrong tax number shown confidently is worse than no
 * number. Null makes the gap visible so the app can ask instead of assume.
 *
 * (It also keeps the schema migration honest: a NOT NULL column added by
 * ALTER TABLE needs a SQL DEFAULT, which Room then flags as a mismatch against
 * the entity's Kotlin default. Nullable sidesteps that entirely.)
 */
@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val displayName: String,
    val taxTreatment: TaxTreatment? = null
)

@Entity(
    tableName = "holdings",
    foreignKeys = [ForeignKey(
        entity = AccountEntity::class,
        parentColumns = ["id"],
        childColumns = ["accountId"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class HoldingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val name: String,
    val ticker: String? = null, // null for seg/mutual funds priced only by NAV snapshot
    val type: HoldingType,
    val units: Double,
    // Manually typed price, used until a live quote replaces it, or for funds with no ticker
    val manualPrice: Double? = null,
    val currency: String = "CAD",
    val lastKnownPrice: Double? = null,
    val lastPriceAtMillis: Long? = null,
    val costBasis: Double? = null, // for yield-on-cost calculations
    // When the holding was first added to the app (epoch millis). Used to filter
    // out auto-imported historical dividend payments that pre-date the user's record.
    val createdAtMillis: Long = System.currentTimeMillis()
)

@Entity(tableName = "price_snapshots")
data class PriceSnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val holdingId: Long,
    val atMillis: Long,
    val price: Double
)

/** A recorded dividend/distribution payment for a holding, entered by hand. */
@Entity(
    tableName = "dividend_payments",
    foreignKeys = [ForeignKey(
        entity = HoldingEntity::class,
        parentColumns = ["id"],
        childColumns = ["holdingId"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class DividendPaymentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val holdingId: Long,
    val paidAtMillis: Long,
    val amount: Double, // total cash received, in the holding's currency
    val perUnit: Double? = null,
    val currency: String = "CAD",
    val note: String? = null
)

/**
 * A ticker the user is watching on the Dashboard's Quotes list — separate
 * from anything actually owned (a HoldingEntity). Seeded with a few common
 * indices/tickers on first run; the user can add or remove freely.
 */
@Entity(tableName = "watchlist_items")
data class WatchlistItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ticker: String,
    val addedAtMillis: Long,
    // User-chosen display name overriding the fetched quote name, set from
    // the quote detail screen's "Rename" menu action.
    val customName: String? = null,

    // ── Cached last quote ────────────────────────────────────────────────
    // Held so the Markets tab can paint real numbers the instant the app
    // opens instead of showing a row of spinners while the network answers.
    // Refreshed in the background on every load; treated as display-only.
    val cachedPrice: Double? = null,
    val cachedPreviousClose: Double? = null,
    val cachedName: String? = null,
    val cachedCurrency: String? = null,
    val cachedAtMillis: Long? = null
)

/** What a recorded transaction did to a position. */
enum class TransactionType {
    /** Units bought with new money. Increases units and cost basis. */
    BUY,
    /** Units sold. Decreases units, and reduces cost basis proportionally. */
    SELL,
    /**
     * Units acquired by reinvesting a dividend. Increases units, and increases
     * cost basis by the reinvested cash (that cash was taxable income, so it
     * counts as money put into the position).
     */
    DRIP;

    fun label(): String = when (this) {
        BUY -> "Buy"; SELL -> "Sell"; DRIP -> "DRIP"
    }
}

/**
 * One buy, sell or dividend reinvestment against a holding.
 *
 * Holdings remain the position of record — this table is the audit trail of
 * how a position got to its current size. Applying a transaction updates the
 * holding's units and cost basis; the row here is what lets the user review,
 * and undo, that change later.
 */
@Entity(
    tableName = "transactions",
    foreignKeys = [ForeignKey(
        entity = HoldingEntity::class,
        parentColumns = ["id"],
        childColumns = ["holdingId"],
        onDelete = ForeignKey.CASCADE
    )],
    // Declared explicitly so the hand-written migration and Room's expected
    // schema agree. Room treats any index named "index_*" as one it owns, so
    // an index created by a migration but absent here fails validation.
    indices = [Index("holdingId")]
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val holdingId: Long,
    val type: TransactionType,
    val atMillis: Long,
    val shares: Double,
    val pricePerShare: Double,
    val currency: String = "CAD",
    val note: String? = null,
    /**
     * For DRIP rows: the dividend payment this reinvestment came from, so a
     * payment can't be reinvested twice. Null for ordinary buys and sells.
     */
    val sourceDividendId: Long? = null
) {
    /** Total cash value of the transaction. */
    val amount: Double get() = shares * pricePerShare
}

// ── Alerts ────────────────────────────────────────────────────────────────

/** What condition an alert watches for. */
enum class AlertKind {
    /** Fires when the last price rises to or above the threshold. */
    PRICE_ABOVE,

    /** Fires when the last price falls to or below the threshold. */
    PRICE_BELOW,

    /**
     * Fires when the day's move, in either direction, reaches the threshold
     * percent. Absolute on purpose: someone watching for a 5% day wants to
     * hear about it whichever way it went, and two separate rules for up and
     * down would be the same alert entered twice.
     */
    DAY_MOVE_PERCENT,

    /**
     * Fires when a holding's ex-dividend date is within the threshold number
     * of days. The one alert that is about the calendar rather than the price
     * — and the reason this app exists, since buying after the ex-date means
     * waiting a whole cycle for the first payment.
     */
    EX_DIVIDEND_WITHIN_DAYS;

    fun label(): String = when (this) {
        PRICE_ABOVE -> "Price rises to"
        PRICE_BELOW -> "Price falls to"
        DAY_MOVE_PERCENT -> "Moves in a day by"
        EX_DIVIDEND_WITHIN_DAYS -> "Ex-dividend within"
    }

    /** What the threshold means, for labelling the input and the notification. */
    fun unit(): String = when (this) {
        PRICE_ABOVE, PRICE_BELOW -> "price"
        DAY_MOVE_PERCENT -> "%"
        EX_DIVIDEND_WITHIN_DAYS -> "days"
    }
}

/**
 * One thing the user asked to be told about.
 *
 * Keyed by TICKER rather than by holding id, deliberately. The same security
 * can sit in three accounts as three holding rows, and an alert keyed to one
 * of them would go quiet the moment that particular row was sold — which is
 * not what "tell me when VDY hits $40" means. It also lets an alert watch
 * something on the watchlist that is not held at all, which is most of the
 * reason to want one.
 */
@Entity(tableName = "alerts")
data class AlertEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ticker: String,
    val kind: AlertKind,
    val threshold: Double,
    val enabled: Boolean = true,
    val createdAtMillis: Long,
    /**
     * Set while the condition is CURRENTLY true, cleared when it goes false
     * again — the latch that stops a price sitting above its target from
     * firing the same notification every half hour for a week.
     *
     * Storing "when it last fired" and suppressing for a fixed window was the
     * obvious alternative and is worse: pick an hour and a genuine second
     * crossing goes unreported, pick a day and the alert is useless for
     * anything intraday. Re-arming on the condition going false is what a
     * person means by "tell me when it crosses".
     */
    val triggeredAtMillis: Long? = null,
    /** The value that tripped it, kept so the list can say what happened. */
    val lastValue: Double? = null
)
