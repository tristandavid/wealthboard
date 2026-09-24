package ca.tristan.portfolio.data

import ca.tristan.portfolio.data.db.AccountEntity
import ca.tristan.portfolio.data.db.DividendPaymentEntity
import ca.tristan.portfolio.data.db.HoldingEntity
import ca.tristan.portfolio.data.db.HoldingType
import ca.tristan.portfolio.data.db.TaxTreatment
import ca.tristan.portfolio.data.db.TransactionEntity
import ca.tristan.portfolio.data.db.TransactionType
import ca.tristan.portfolio.data.db.WatchlistItemEntity

/**
 * Serialisation between Room entities and the plain maps Firestore stores.
 *
 * Kept apart from both the repository and FirebaseManager because it is the
 * part with the sharp edges, and those edges are all about types:
 *
 *  - Firestore has ONE numeric type on the way out. A Double written as 45.0
 *    can come back as a Long, and an Int always does. Reading `as Double` on a
 *    restored field is a ClassCastException waiting for the first whole-number
 *    price, so every numeric read goes through [num] instead of a cast.
 *  - Enums round-trip by `name`, and a name that no longer exists (a build
 *    that renamed a HoldingType) must not take the whole restore down with it —
 *    hence the defaulted `enumOrNull` reads.
 *
 * The maps are deliberately flat and field-named rather than positional, so a
 * backup written by this version still restores after a column is added.
 */
internal object CloudBackup {

    /** Bumped when the shape changes in a way a reader has to know about. */
    const val FORMAT_VERSION = 3

    // ── Writing ───────────────────────────────────────────────────────────

    fun accountToMap(a: AccountEntity): Map<String, Any?> = mapOf(
        "id" to a.id,
        "displayName" to a.displayName,
        "taxTreatment" to a.taxTreatment?.name
    )

    fun holdingToMap(h: HoldingEntity): Map<String, Any?> = mapOf(
        "id" to h.id,
        "accountId" to h.accountId,
        "name" to h.name,
        "ticker" to h.ticker,
        "type" to h.type.name,
        "units" to h.units,
        "manualPrice" to h.manualPrice,
        "currency" to h.currency,
        // lastKnownPrice/lastPriceAtMillis are deliberately NOT backed up:
        // they are a cache of a quote the app can re-fetch in seconds, and
        // restoring a stale one would show a price with a timestamp that lies.
        "costBasis" to h.costBasis,
        "createdAtMillis" to h.createdAtMillis
    )

    fun transactionToMap(t: TransactionEntity): Map<String, Any?> = mapOf(
        "id" to t.id,
        "holdingId" to t.holdingId,
        "type" to t.type.name,
        "atMillis" to t.atMillis,
        "shares" to t.shares,
        "pricePerShare" to t.pricePerShare,
        "currency" to t.currency,
        "note" to t.note,
        "sourceDividendId" to t.sourceDividendId
    )

    fun dividendToMap(d: DividendPaymentEntity): Map<String, Any?> = mapOf(
        "id" to d.id,
        "holdingId" to d.holdingId,
        "paidAtMillis" to d.paidAtMillis,
        "amount" to d.amount,
        "perUnit" to d.perUnit,
        "currency" to d.currency,
        "note" to d.note
    )

    fun watchlistToMap(w: WatchlistItemEntity): Map<String, Any?> = mapOf(
        "id" to w.id,
        "ticker" to w.ticker,
        "addedAtMillis" to w.addedAtMillis,
        "customName" to w.customName
        // Cached quote fields omitted for the same reason as above.
    )

    // ── Reading ───────────────────────────────────────────────────────────

    /**
     * The `list` array out of a synced document, as maps.
     *
     * Anything that isn't a map of string keys is dropped rather than crashing
     * the restore: a half-written document should cost the rows it damaged,
     * not the whole backup.
     */
    @Suppress("UNCHECKED_CAST")
    fun rows(doc: Map<String, Any?>?): List<Map<String, Any?>> {
        val raw = doc?.get("list") as? List<*> ?: return emptyList()
        return raw.mapNotNull { it as? Map<String, Any?> }
    }

    fun accountFrom(m: Map<String, Any?>): AccountEntity? {
        val name = str(m["displayName"]) ?: return null
        return AccountEntity(
            id = 0,
            displayName = name,
            taxTreatment = enumOrNull<TaxTreatment>(str(m["taxTreatment"]))
        )
    }

    fun holdingFrom(m: Map<String, Any?>, accountId: Long): HoldingEntity? {
        val name = str(m["name"]) ?: return null
        val units = num(m["units"]) ?: return null
        return HoldingEntity(
            id = 0,
            accountId = accountId,
            name = name,
            ticker = str(m["ticker"]),
            type = enumOrNull<HoldingType>(str(m["type"])) ?: HoldingType.OTHER,
            units = units,
            manualPrice = num(m["manualPrice"]),
            currency = str(m["currency"]) ?: "CAD",
            costBasis = num(m["costBasis"]),
            createdAtMillis = num(m["createdAtMillis"])?.toLong() ?: System.currentTimeMillis()
        )
    }

    fun transactionFrom(m: Map<String, Any?>, holdingId: Long): TransactionEntity? {
        val shares = num(m["shares"]) ?: return null
        val price = num(m["pricePerShare"]) ?: return null
        val at = num(m["atMillis"])?.toLong() ?: return null
        return TransactionEntity(
            id = 0,
            holdingId = holdingId,
            type = enumOrNull<TransactionType>(str(m["type"])) ?: TransactionType.BUY,
            atMillis = at,
            shares = shares,
            pricePerShare = price,
            currency = str(m["currency"]) ?: "CAD",
            note = str(m["note"]),
            // Intentionally dropped: it points at a dividend row id from the
            // OLD database, and the restore renumbers everything. A wrong id
            // here would let a dividend be reinvested twice, or block a real
            // reinvestment, so null — "not from a dividend" — is the safe read.
            sourceDividendId = null
        )
    }

    fun dividendFrom(m: Map<String, Any?>, holdingId: Long): DividendPaymentEntity? {
        val amount = num(m["amount"]) ?: return null
        val paidAt = num(m["paidAtMillis"])?.toLong() ?: return null
        return DividendPaymentEntity(
            id = 0,
            holdingId = holdingId,
            paidAtMillis = paidAt,
            amount = amount,
            perUnit = num(m["perUnit"]),
            currency = str(m["currency"]) ?: "CAD",
            note = str(m["note"])
        )
    }

    fun watchlistFrom(m: Map<String, Any?>): WatchlistItemEntity? {
        val ticker = str(m["ticker"])?.uppercase() ?: return null
        return WatchlistItemEntity(
            id = 0,
            ticker = ticker,
            addedAtMillis = num(m["addedAtMillis"])?.toLong() ?: System.currentTimeMillis(),
            customName = str(m["customName"])
        )
    }

    /** The original row id, used to rebuild parent→child links after renumbering. */
    fun oldId(m: Map<String, Any?>): Long? = longOf(m["id"])

    /** A Firestore number as a Long, whatever type it came back as. */
    fun longOf(v: Any?): Long? = num(v)?.toLong()

    // ── Type coercion ─────────────────────────────────────────────────────

    /**
     * Any Firestore number as a Double.
     *
     * Firestore narrows on write: 45.0 comes back a Long, 45.29 a Double, and
     * which one you get depends on the value rather than the field. A direct
     * cast therefore works right up until someone owns a whole number of
     * shares at a whole-dollar price.
     */
    private fun num(v: Any?): Double? = when (v) {
        is Double -> v.takeIf { !it.isNaN() }
        is Long -> v.toDouble()
        is Int -> v.toDouble()
        is Float -> v.toDouble()
        is Number -> v.toDouble()
        is String -> v.toDoubleOrNull()
        else -> null
    }

    private fun str(v: Any?): String? = (v as? String)?.trim()?.takeIf { it.isNotEmpty() }

    private inline fun <reified T : Enum<T>> enumOrNull(name: String?): T? =
        name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() }
}
