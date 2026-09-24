package ca.tristan.portfolio.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * The backup file format — the copy that needs no account and no network.
 *
 * ## Why this exists alongside cloud sync
 *
 * Everything in this app is typed in by hand. Losing it means re-entering
 * every holding, transaction and dividend, and the only safety net used to be
 * cloud backup, which was invisible unless you had signed in. A backup is not
 * a premium feature; it is what makes hand-entry survivable. So this path
 * works with no account, no network and no subscription, and it is the one
 * that still works when sign-in is down or the user simply does not want an
 * account.
 *
 * ## The shape
 *
 * Deliberately the SAME row maps [CloudBackup] already writes — field-named
 * and flat, so a file exported today still restores after a column is added,
 * and so there is one serialiser to keep correct rather than two.
 *
 * [FORMAT_VERSION] is recorded but not enforced on read: a file from a newer
 * build is attempted rather than refused, because the row readers already drop
 * what they do not understand row by row. Refusing outright would turn a
 * recoverable backup into a dead one.
 *
 * ## Crossing platforms
 *
 * A second copy of the same portfolio rides along under "interchange", in the
 * neutral shape [PortfolioInterchange] defines, and [decode] falls back to it
 * for a file this app did not write. That is what lets a backup exported on an
 * iPhone restore here, and one exported here restore there.
 *
 * It is a FALLBACK and not the primary read: a file written by this app is
 * still read from its own sections, by the code that has always read them. The
 * interchange block only comes into play for a file that would otherwise have
 * been refused outright.
 */
internal object LocalBackup {

    private const val MAGIC = "WealthBoard"

    /** The keys [PortfolioRepository.backupPayload] produces, in restore order. */
    val SECTIONS = listOf("accounts", "holdings", "transactions", "dividends", "watchlist")

    fun encode(
        payload: Map<String, List<Map<String, Any?>>>,
        interchange: Map<String, Any?>? = null
    ): String {
        val root = JSONObject()
        root.put("app", MAGIC)
        root.put("platform", "android")
        root.put("formatVersion", CloudBackup.FORMAT_VERSION)
        root.put("exportedAt", System.currentTimeMillis())
        // Optional so an export still works if building it failed — a file
        // that restores on this phone and not the other one beats no file.
        interchange?.let { root.put("interchange", toJson(it)) }
        for (section in SECTIONS) {
            val arr = JSONArray()
            payload[section].orEmpty().forEach { row ->
                val o = JSONObject()
                row.forEach { (k, v) ->
                    // JSONObject.put(String, null) removes the key, which is
                    // what we want: an absent field and a null one read back
                    // the same, and omitting them keeps the file readable.
                    if (v != null) o.put(k, v)
                }
                arr.put(o)
            }
            root.put(section, arr)
        }
        return root.toString(2)
    }

    /** Parsed sections, or null if this is not a WealthBoard backup. */
    fun decode(text: String): Map<String, List<Map<String, Any?>>>? {
        val root = try {
            JSONObject(text)
        } catch (_: Exception) {
            return null
        }
        // Checked so that picking the wrong file says so, instead of
        // "restored 0 holdings" after wiping the device.
        //
        // A file from the iOS build has no such marker, so before refusing we
        // look for the interchange block. Finding one means this IS a
        // WealthBoard backup, written by the other app.
        if (root.optString("app") != MAGIC) {
            val block = root.optJSONObject("interchange") ?: return null
            val decoded = PortfolioInterchange.decode(fromJson(block)) ?: return null
            if (decoded.summary.isEmpty) return null
            return mapOf(
                "accounts" to decoded.accounts,
                "holdings" to decoded.holdings,
                "transactions" to decoded.transactions,
                "dividends" to decoded.dividends,
                "watchlist" to decoded.watchlist
            )
        }

        val out = HashMap<String, List<Map<String, Any?>>>()
        for (section in SECTIONS) {
            val arr = root.optJSONArray(section) ?: JSONArray()
            val rows = ArrayList<Map<String, Any?>>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val row = HashMap<String, Any?>()
                for (key in o.keys()) {
                    val value = o.get(key)
                    row[key] = if (value == JSONObject.NULL) null else value
                }
                rows.add(row)
            }
            out[section] = rows
        }
        return out
    }

    // ── JSON <-> Map ──────────────────────────────────────────────────────
    //
    // Written out rather than leaning on JSONObject(Map), whose handling of
    // nested lists differs between the desktop org.json and Android's, and a
    // backup is not the place to find that out.

    private fun toJson(map: Map<String, Any?>): JSONObject {
        val o = JSONObject()
        for ((k, v) in map) {
            // A null put REMOVES the key, which is the behaviour we want:
            // absent and null read back the same.
            if (v != null) o.put(k, wrap(v))
        }
        return o
    }

    @Suppress("UNCHECKED_CAST")
    private fun wrap(value: Any): Any = when (value) {
        is Map<*, *> -> toJson(value as Map<String, Any?>)
        is List<*> -> JSONArray().apply {
            for (item in value) if (item != null) put(wrap(item))
        }
        else -> value
    }

    private fun fromJson(o: JSONObject): Map<String, Any?> {
        val out = HashMap<String, Any?>()
        for (key in o.keys()) out[key] = unwrap(o.get(key))
        return out
    }

    private fun unwrap(value: Any?): Any? = when {
        value == null || value == JSONObject.NULL -> null
        value is JSONObject -> fromJson(value)
        value is JSONArray -> (0 until value.length()).mapNotNull { unwrap(value.get(it)) }
        else -> value
    }

    /** A filename someone can tell apart in a folder of them. */
    fun suggestedFileName(): String {
        val stamp = java.text.SimpleDateFormat(
            "yyyy-MM-dd", java.util.Locale.US
        ).format(java.util.Date())
        return "WealthBoard-$stamp.json"
    }
}
