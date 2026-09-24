package ca.tristan.portfolio.data

import ca.tristan.portfolio.data.db.AccountEntity
import ca.tristan.portfolio.data.db.DividendPaymentEntity
import ca.tristan.portfolio.data.db.HoldingEntity
import ca.tristan.portfolio.data.db.TransactionEntity
import ca.tristan.portfolio.data.db.WatchlistItemEntity

/**
 * The one portfolio format both apps can read. Kotlin half of the Swift
 * `PortfolioInterchange` — same schema, same keys, same forgiving decode.
 *
 * ## Why a third format rather than one app adopting the other's
 *
 * The two native shapes are not convertible. Room identifies every row by an
 * auto-incrementing Long; the iOS build identifies everything by UUID. Neither
 * can be produced from the other, so a backup written on one phone names rows
 * the other cannot resolve — which is why cloud sync appeared to work and then
 * restored nothing.
 *
 * This carries NATURAL keys instead: an account is its display name, a holding
 * is its account plus its security key (the ticker, or the name where there is
 * no ticker — the same key the portfolio screen already groups on). Each app
 * rebuilds its own identifiers on import. Nothing in the file depends on how
 * either app stores a row.
 *
 * ## Why it is written alongside the native backup, not instead of it
 *
 * A format that has never round-tripped against real data is not something to
 * put between someone and their only copy. Both apps keep writing their own
 * backup exactly as before and keep reading it first; this is written beside it
 * and read only when it is NEWER, so a fault here costs a cross-device restore
 * and never the local one. Remove that belt once it has been proven against a
 * real portfolio on both platforms — not before.
 *
 * ## Why decoding produces NATIVE rows rather than entities
 *
 * [decode] hands back maps in exactly the shape [CloudBackup] writes, with
 * synthesised ids standing in for the natural keys. That means the restore path
 * itself — `PortfolioRepository.restoreFromBackup`, with its wipe ordering, its
 * parent relinking and its cost-basis rebuild — is the SAME code that has
 * always run, not a second copy of it written for this format. A cross-platform
 * restore can only go wrong in the translation, which is this file, and never
 * in the part that touches the database.
 *
 * ## Decoding is deliberately forgiving
 *
 * A row that cannot be read is skipped and counted rather than failing the
 * whole restore. [ImportSummary] carries those counts to the screen, because
 * the failure this format must never have is the silent one — a restore that
 * reports success having quietly dropped half the holdings.
 */
internal object PortfolioInterchange {

    /**
     * Bumped only for a change an older reader could MISREAD. Adding a field an
     * old build ignores does not need it.
     */
    const val SCHEMA_VERSION = 1

    /** Sibling of "holdings"/"accounts"/… under users/{uid}/portfolio. */
    const val DOCUMENT_NAME = "interchange"

    // ── Writing ───────────────────────────────────────────────────────────

    fun encode(
        accounts: List<AccountEntity>,
        holdings: List<HoldingEntity>,
        transactions: List<TransactionEntity>,
        dividends: List<DividendPaymentEntity>,
        watchlist: List<WatchlistItemEntity>,
        baseCurrency: String,
        // Defaults to "now" for callers that don't care (the local file
        // export, where restore always tries the native shape first and this
        // timestamp is never compared against anything). Cloud backup passes
        // its own timestamp explicitly instead — see the call site for why.
        writtenAt: Long = System.currentTimeMillis()
    ): Map<String, Any?> {
        // Native ids resolved to natural keys once, up front.
        //
        // The keys have to be UNIQUE, and a display name is not. Two accounts
        // both called "TFSA", or the same ticker held twice in one account, are
        // things the app allows and a reader keyed on names cannot tell apart —
        // it would merge them, and merging two positions into one silently
        // loses a holding. So a repeat is given a suffix here, at the only
        // point that can still see which row is which.
        val accountName = HashMap<Long, String>()
        val usedAccountNames = HashSet<String>()
        for (a in accounts) {
            // A blank name would decode to null and take every holding under
            // it down with it.
            val base = a.displayName.trim().ifEmpty { "Account" }
            var name = base
            // Visible on purpose: the account really is called something
            // slightly different after a restore, and a renamed account is a
            // far better outcome than a missing one.
            var attempt = 2
            while (!usedAccountNames.add(name)) {
                name = "$base ($attempt)"
                attempt++
            }
            accountName[a.id] = name
        }

        val holdingKey = HashMap<Long, Pair<String, String>>()
        val usedSecurityKeys = HashSet<String>()
        for (h in holdings) {
            val account = accountName[h.accountId] ?: "Account"
            val base = securityKey(h)
            var security = base
            // NOT visible: `security` is only a key here — the holding carries
            // its own name and ticker — so the suffix never reaches a screen.
            var attempt = 2
            while (!usedSecurityKeys.add(key(account, security))) {
                security = "$base#$attempt"
                attempt++
            }
            holdingKey[h.id] = account to security
        }

        val accountRows = accounts.map { a ->
            val row = HashMap<String, Any?>()
            row["name"] = accountName[a.id] ?: "Account"
            a.taxTreatment?.let { row["taxTreatment"] = it.name }
            row
        }

        val holdingRows = holdings.map { h ->
            val hKey = holdingKey[h.id] ?: ("Account" to securityKey(h))
            val row = HashMap<String, Any?>()
            row["account"] = hKey.first
            row["name"] = h.name
            row["security"] = hKey.second
            row["type"] = h.type.name
            row["units"] = h.units
            row["currency"] = h.currency.trim().uppercase().ifEmpty { "CAD" }
            row["createdAt"] = h.createdAtMillis
            h.ticker?.takeIf { it.isNotBlank() }?.let { row["ticker"] = it }
            h.manualPrice?.let { row["manualPrice"] = it }
            h.costBasis?.let { row["costBasis"] = it }
            row
        }

        // A transaction or dividend whose holding is gone has nothing to attach
        // to on the other side, so it is dropped here rather than written as an
        // orphan the reader would have to skip.
        val transactionRows = transactions.mapNotNull { t ->
            val key = holdingKey[t.holdingId] ?: return@mapNotNull null
            val row = HashMap<String, Any?>()
            row["account"] = key.first
            row["security"] = key.second
            row["type"] = t.type.name
            row["at"] = t.atMillis
            row["shares"] = t.shares
            row["pricePerShare"] = t.pricePerShare
            row["currency"] = t.currency.trim().uppercase().ifEmpty { "CAD" }
            t.note?.takeIf { it.isNotBlank() }?.let { row["note"] = it }
            row
        }

        val dividendRows = dividends.mapNotNull { d ->
            val key = holdingKey[d.holdingId] ?: return@mapNotNull null
            val row = HashMap<String, Any?>()
            row["account"] = key.first
            row["security"] = key.second
            row["paidAt"] = d.paidAtMillis
            row["amount"] = d.amount
            row["currency"] = d.currency.trim().uppercase().ifEmpty { "CAD" }
            d.perUnit?.let { row["perUnit"] = it }
            d.note?.takeIf { it.isNotBlank() }?.let { row["note"] = it }
            row
        }

        val watchlistRows = watchlist.map { w ->
            val row = HashMap<String, Any?>()
            row["ticker"] = w.ticker
            row["addedAt"] = w.addedAtMillis
            w.customName?.takeIf { it.isNotBlank() }?.let { row["customName"] = it }
            row
        }

        // Price snapshots and cached quotes are deliberately left out. They are
        // a local cache the app rebuilds from the network in seconds, they are
        // by far the largest part of the document, and a restore that carried
        // them across would show a price with a timestamp that lies.
        //
        // baseCurrency travels for information only — neither app applies it on
        // import. Silently switching someone's display currency because they
        // restored from their other phone is a surprise, not a feature.
        return mapOf(
            "schema" to SCHEMA_VERSION,
            "writtenAt" to writtenAt,
            "writtenBy" to "android",
            "baseCurrency" to baseCurrency,
            "accounts" to accountRows,
            "holdings" to holdingRows,
            "transactions" to transactionRows,
            "dividends" to dividendRows,
            "watchlist" to watchlistRows
        )
    }

    /** The same key the portfolio screen groups on: ticker, else name. */
    private fun securityKey(h: HoldingEntity): String {
        val ticker = h.ticker?.trim()
        return if (!ticker.isNullOrEmpty()) ticker.uppercase() else h.name
    }

    // ── Reading ───────────────────────────────────────────────────────────

    /**
     * What a restore actually brought in, and what it could not.
     *
     * Shown to the user. A restore that says "done" having dropped rows is the
     * one outcome this format exists to prevent.
     */
    data class ImportSummary(
        val accounts: Int = 0,
        val holdings: Int = 0,
        val transactions: Int = 0,
        val dividends: Int = 0,
        val watchlist: Int = 0,
        val skipped: Int = 0
    ) {
        val isEmpty: Boolean
            get() = accounts + holdings + transactions + dividends + watchlist == 0
    }

    /**
     * A decoded payload, in the native row shape `restoreFromBackup` consumes.
     */
    data class Import(
        val accounts: List<Map<String, Any?>>,
        val holdings: List<Map<String, Any?>>,
        val transactions: List<Map<String, Any?>>,
        val dividends: List<Map<String, Any?>>,
        val watchlist: List<Map<String, Any?>>,
        val summary: ImportSummary,
        /** Which app wrote it — "ios", "android", or null on an older writer. */
        val writtenBy: String?
    )

    /**
     * Translates an interchange payload into native rows with synthesised ids.
     *
     * Returns null only when the payload is not this format at all. A payload
     * that IS this format but partly unreadable comes back with whatever could
     * be read and a count of what could not.
     */
    fun decode(raw: Map<String, Any?>?): Import? {
        val doc = raw ?: return null
        val schema = intOf(doc["schema"]) ?: return null
        if (schema < 1) return null

        var skipped = 0

        // Accounts first: everything else is keyed on their names. The ids here
        // are invented for this restore only — restoreFromBackup renumbers
        // everything again on insert, so they need to be unique and nothing
        // more.
        val accountId = HashMap<String, Long>()
        val accountRows = ArrayList<Map<String, Any?>>()
        for (row in rows(doc["accounts"])) {
            val name = str(row["name"])
            if (name == null) { skipped++; continue }
            if (accountId.containsKey(name)) continue
            val id = (accountId.size + 1).toLong()
            accountId[name] = id
            accountRows.add(
                mapOf(
                    "id" to id,
                    "displayName" to name,
                    "taxTreatment" to str(row["taxTreatment"])
                )
            )
        }

        val holdingId = HashMap<String, Long>()
        val holdingRows = ArrayList<Map<String, Any?>>()
        for (row in rows(doc["holdings"])) {
            val accountName = str(row["account"])
            val owner = accountName?.let { accountId[it] }
            val name = str(row["name"])
            val units = num(row["units"])
            if (owner == null || accountName == null || name == null || units == null) {
                skipped++; continue
            }

            val ticker = str(row["ticker"])
            val security = str(row["security"]) ?: ticker?.uppercase() ?: name
            val id = (holdingId.size + 1).toLong()
            holdingId[key(accountName, security)] = id

            val out = HashMap<String, Any?>()
            out["id"] = id
            out["accountId"] = owner
            out["name"] = name
            out["ticker"] = ticker
            out["type"] = str(row["type"])
            out["units"] = units
            out["manualPrice"] = num(row["manualPrice"])
            out["currency"] = str(row["currency"]) ?: "CAD"
            out["costBasis"] = num(row["costBasis"])
            out["createdAtMillis"] = longOf(row["createdAt"]) ?: System.currentTimeMillis()
            holdingRows.add(out)
        }

        val transactionRows = ArrayList<Map<String, Any?>>()
        for (row in rows(doc["transactions"])) {
            val accountName = str(row["account"])
            val security = str(row["security"])
            val owner = if (accountName != null && security != null) {
                holdingId[key(accountName, security)]
            } else null
            val shares = num(row["shares"])
            val price = num(row["pricePerShare"])
            val at = longOf(row["at"])
            if (owner == null || shares == null || price == null || at == null) {
                skipped++; continue
            }
            transactionRows.add(
                mapOf(
                    "id" to 0L,
                    "holdingId" to owner,
                    "type" to str(row["type"]),
                    "atMillis" to at,
                    "shares" to shares,
                    "pricePerShare" to price,
                    "currency" to (str(row["currency"]) ?: "CAD"),
                    "note" to str(row["note"]),
                    "sourceDividendId" to null
                )
            )
        }

        val dividendRows = ArrayList<Map<String, Any?>>()
        for (row in rows(doc["dividends"])) {
            val accountName = str(row["account"])
            val security = str(row["security"])
            val owner = if (accountName != null && security != null) {
                holdingId[key(accountName, security)]
            } else null
            val amount = num(row["amount"])
            val paidAt = longOf(row["paidAt"])
            if (owner == null || amount == null || paidAt == null) { skipped++; continue }
            dividendRows.add(
                mapOf(
                    "id" to 0L,
                    "holdingId" to owner,
                    "paidAtMillis" to paidAt,
                    "amount" to amount,
                    "perUnit" to num(row["perUnit"]),
                    "currency" to (str(row["currency"]) ?: "CAD"),
                    "note" to str(row["note"])
                )
            )
        }

        val watchlistRows = ArrayList<Map<String, Any?>>()
        for (row in rows(doc["watchlist"])) {
            val ticker = str(row["ticker"])
            if (ticker == null) { skipped++; continue }
            watchlistRows.add(
                mapOf(
                    "id" to 0L,
                    "ticker" to ticker.uppercase(),
                    "addedAtMillis" to (longOf(row["addedAt"]) ?: System.currentTimeMillis()),
                    "customName" to str(row["customName"])
                )
            )
        }

        return Import(
            accounts = accountRows,
            holdings = holdingRows,
            transactions = transactionRows,
            dividends = dividendRows,
            watchlist = watchlistRows,
            summary = ImportSummary(
                accounts = accountRows.size,
                holdings = holdingRows.size,
                transactions = transactionRows.size,
                dividends = dividendRows.size,
                watchlist = watchlistRows.size,
                skipped = skipped
            ),
            writtenBy = str(doc["writtenBy"])
        )
    }

    /**
     * When this payload was written, for deciding whether it is newer than the
     * native backup beside it. Null when absent or unreadable, which the caller
     * treats as "older" — native wins ties and unknowns.
     */
    fun writtenAt(raw: Map<String, Any?>?): Long? = longOf(raw?.get("writtenAt"))

    // ── Type coercion ─────────────────────────────────────────────────────
    //
    // Firestore narrows numbers on write: 45.0 comes back a Long, 45.29 a
    // Double, and which one you get depends on the VALUE rather than the field.
    // Every numeric read therefore goes through these rather than a cast that
    // works right up until someone owns a whole number of shares.

    private fun key(account: String, security: String): String =
        account + "\u001F" + security.uppercase()

    @Suppress("UNCHECKED_CAST")
    private fun rows(v: Any?): List<Map<String, Any?>> {
        val raw = v as? List<*> ?: return emptyList()
        return raw.mapNotNull { it as? Map<String, Any?> }
    }

    private fun str(v: Any?): String? = (v as? String)?.trim()?.takeIf { it.isNotEmpty() }

    private fun num(v: Any?): Double? = when (v) {
        is Double -> v.takeIf { !it.isNaN() }
        is Long -> v.toDouble()
        is Int -> v.toDouble()
        is Float -> v.toDouble()
        is Number -> v.toDouble()
        is String -> v.toDoubleOrNull()
        else -> null
    }

    private fun longOf(v: Any?): Long? = num(v)?.takeIf { it > 0 }?.toLong()

    private fun intOf(v: Any?): Int? = num(v)?.toInt()
}
