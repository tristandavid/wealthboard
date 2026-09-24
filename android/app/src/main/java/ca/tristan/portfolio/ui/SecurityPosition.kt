package ca.tristan.portfolio.ui

import ca.tristan.portfolio.data.db.AccountEntity
import ca.tristan.portfolio.data.db.HoldingEntity
import ca.tristan.portfolio.data.db.TaxTreatment

// The same fund bought in a TFSA, an RRSP and an FHSA is three rows in Room —
// necessarily, since cost basis and tax treatment are per account. But the user
// owns ONE position in it.
//
// These types are that position: the combined figures the app shows by default,
// and the per-account split it can break them back down into on request.

/** The key a holding is grouped under: the ticker where there is one. */
val HoldingEntity.securityKey: String
    get() = ticker?.takeIf { it.isNotBlank() }?.uppercase() ?: name

/** One account's slice of a position. */
data class AccountPosition(
    /** The underlying row, which is what an edit or a transaction acts on. */
    val holding: HoldingEntity,
    val account: AccountEntity?,
    val units: Double,
    val price: Double,
    val previousClose: Double?,
    val costBasis: Double?
) {
    val accountName: String get() = account?.displayName ?: "Unassigned"
    val treatment: TaxTreatment? get() = account?.taxTreatment
    val currency: String get() = holding.currency.uppercase().ifBlank { "CAD" }

    val marketValue: Double get() = price * units

    val averagePrice: Double?
        get() = costBasis?.takeIf { units > 0 }?.div(units)

    /** Today's move on this slice, from the quote's previous close. */
    val todaysReturn: Double?
        get() = previousClose?.let { (price - it) * units }

    val todaysReturnPct: Double?
        get() = previousClose?.takeIf { it > 0 }?.let { (price - it) / it * 100.0 }

    /**
     * Gain against what was paid. Null when no cost basis has been recorded —
     * an unrecorded cost is not a zero cost, and showing it as a 100% gain
     * would be a lie.
     */
    val totalReturn: Double?
        get() = costBasis?.let { marketValue - it }

    val totalReturnPct: Double?
        get() = costBasis?.takeIf { it > 0 }?.let { (marketValue - it) / it * 100.0 }
}

/** A whole position in one security, across every account it sits in. */
data class SecurityPosition(
    val key: String,
    /** Every stored row making up this position, largest slice first. */
    val slices: List<AccountPosition>
) {
    /**
     * The row the position is named and priced from. Largest slice, so a token
     * holding in a fourth account can't decide the display name.
     */
    val principal: HoldingEntity get() = slices.first().holding

    val name: String get() = principal.name
    val ticker: String? get() = principal.ticker
    val currency: String get() = principal.currency.uppercase().ifBlank { "CAD" }
    val price: Double get() = slices.first().price

    val isSplit: Boolean get() = slices.size > 1
    val accountCount: Int get() = slices.size

    val units: Double get() = slices.sumOf { it.units }
    val marketValue: Double get() = slices.sumOf { it.marketValue }

    /**
     * Summed only when every slice has one. A partial sum would read as the
     * whole position's cost and understate it.
     */
    val costBasis: Double?
        get() = if (slices.all { it.costBasis != null }) slices.sumOf { it.costBasis ?: 0.0 } else null

    val averagePrice: Double?
        get() = costBasis?.takeIf { units > 0 }?.div(units)

    val totalReturn: Double?
        get() = costBasis?.let { marketValue - it }

    val totalReturnPct: Double?
        get() = costBasis?.takeIf { it > 0 }?.let { (marketValue - it) / it * 100.0 }

    /**
     * The distinct tax treatments this position spans.
     *
     * More than one means no single tax answer applies to it, which is exactly
     * the case the per-account breakdown exists for.
     */
    val treatments: List<TaxTreatment?>
        get() = slices.map { it.treatment }.distinct()

    /** Share of the position sitting in one account, 0..1. */
    fun shareOf(slice: AccountPosition): Double =
        if (marketValue > 0) slice.marketValue / marketValue else 0.0
}
