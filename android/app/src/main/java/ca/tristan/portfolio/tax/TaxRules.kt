package ca.tristan.portfolio.tax

import ca.tristan.portfolio.data.db.TaxTreatment

/**
 * Where the user files. Only the two the app models are listed; everyone else
 * is [OTHER], and every tax feature stays switched off for them rather than
 * showing Canadian rules to someone in Singapore.
 */
enum class Residency(val code: String, val label: String) {
    CANADA("CA", "Canada"),
    UNITED_STATES("US", "United States"),

    // Countries the app does NOT model withholding for, but which do have
    // tax-advantaged accounts worth classifying.
    //
    // The distinction matters. Withholding needs a full treaty network per
    // residency and is expensive to get right; account CLASSIFICATION needs
    // only the local product names, and delivers real value on its own — a
    // holding in an ISA or a NISA genuinely is not taxed, and saying so is
    // useful even when the app cannot price the foreign tax on it.
    //
    // So these residencies switch on the account model and the
    // taxed-now/later/never notes, and stay silent about withholding rather
    // than guessing at a rate.
    UNITED_KINGDOM("GB", "United Kingdom"),
    AUSTRALIA("AU", "Australia"),
    NEW_ZEALAND("NZ", "New Zealand"),
    JAPAN("JP", "Japan"),
    SINGAPORE("SG", "Singapore"),
    PHILIPPINES("PH", "Philippines"),
    INDIA("IN", "India"),
    SOUTH_AFRICA("ZA", "South Africa"),
    FRANCE("FR", "France"),
    POLAND("PL", "Poland"),

    OTHER("", "Somewhere else");

    /**
     * True when the app can put a number on tax withheld abroad for someone
     * filing here. Only the two treaty networks actually modelled qualify.
     */
    val modelsWithholding: Boolean
        get() = this == CANADA || this == UNITED_STATES

    /** True when this country has tax-advantaged accounts the app can classify. */
    val hasRegisteredAccounts: Boolean
        get() = this != OTHER

    companion object {
        fun fromCode(code: String?): Residency =
            values().firstOrNull { it.code == code?.uppercase() && it.code.isNotEmpty() } ?: OTHER
    }
}

/** What kind of income a distribution is, which is what decides its rate. */
enum class IncomeType {
    /** Dividend from a company resident in the user's own country. */
    DOMESTIC_DIVIDEND,

    /** Dividend from abroad. Taxed as ordinary income, and usually withheld at source. */
    FOREIGN_DIVIDEND,

    /** Interest, and distributions taxed like it. No preferential rate anywhere. */
    INTEREST,

    /** Realised capital gain. */
    CAPITAL_GAIN
}

/**
 * A warning worth showing against a holding.
 *
 * [detail] is written for someone who does not know how tax works — the rule
 * is explained, not named. [estimatedAnnualCost] is the money at stake per
 * year where that can be estimated, because "you are losing 15%" lands
 * differently from "$66 a year".
 */
data class TaxNote(
    val title: String,
    val detail: String,
    val estimatedAnnualCost: Double? = null,
    val severity: Severity = Severity.INFO
) {
    enum class Severity { INFO, WARNING }
}

/**
 * The tax rules this app models, and nothing beyond them.
 *
 * Scope is deliberately narrow. This does NOT compute anyone's tax bill: it
 * has no brackets, no provincial or state rates, no surtaxes and no knowledge
 * of anybody's other income. What it does is answer two questions the app
 * already has the data to answer honestly:
 *
 *   1. Is tax relevant to this account at all?
 *   2. Is this holding losing money to foreign withholding that a different
 *      account would avoid?
 *
 * The second is the one worth building. Withholding is deducted at source and
 * never appears on a statement as a line item, so it is invisible to the
 * person losing it — and unlike a marginal rate, the app can work it out from
 * facts it already holds: the listing's country, the account's treatment, and
 * the user's residency.
 *
 * Everything here is an ESTIMATE, and treaty rates depend on the user having
 * filed the right residency paperwork with their broker. Callers must present
 * it as information, never as advice or as a filed figure.
 */
object TaxRules {

    /**
     * Where the user files, as implied by the names of their own accounts, or
     * null when the names say nothing (or say both).
     *
     * A far better signal than the phone's region, which is really the
     * LANGUAGE setting's region: a Canadian whose phone is in "English (United
     * States)" reads as a US resident, and was being told about Roth IRAs and
     * Letters of Exemption for a TFSA. A TFSA, RRSP or FHSA exists only in
     * Canada, and an IRA or 401(k) only in the US, so an account named for one
     * settles the question.
     */
    fun residencyFromAccountNames(names: List<String>): Residency? {
        var canada = false
        var us = false
        for (name in names) {
            val tokens = name.uppercase().split(Regex("[^A-Z0-9()]+")).filter { it.isNotEmpty() }.toSet()
            if (tokens.any { it in CANADIAN_ACCOUNT_WORDS }) canada = true
            if (tokens.any { it in US_ACCOUNT_WORDS }) us = true
        }
        return when {
            canada && !us -> Residency.CANADA
            us && !canada -> Residency.UNITED_STATES
            else -> null
        }
    }

    private val CANADIAN_ACCOUNT_WORDS = setOf(
        "TFSA", "RRSP", "FHSA", "RESP", "RRIF", "LIRA", "RDSP", "SPOUSAL",
        // French names: CELI (TFSA), REER (RRSP), CELIAPP (FHSA), FERR (RRIF).
        "CELI", "REER", "CELIAPP", "FERR"
    )

    private val US_ACCOUNT_WORDS = setOf(
        "IRA", "ROTH", "401K", "401(K)", "403B", "403(B)", "HSA"
    )

    /**
     * Treaty withholding on dividends, payer country → recipient country.
     *
     * These are the standard treaty rates for individuals who have certified
     * their residency with their broker (a W-8BEN for a Canadian holding US
     * stock, an W-9/NR301 equivalent the other way). WITHOUT that paperwork
     * the statutory rate applies instead — 30% out of the US, 25% out of
     * Canada — which is why the notes below say "if your broker has your
     * residency form on file" rather than stating the rate as a certainty.
     */
    private const val US_TREATY_RATE = 0.15
    private const val CA_TREATY_RATE = 0.15

    /**
     * Dividend withholding by the country that PAYS, for a Canadian or US
     * resident whose broker holds their residency paperwork.
     *
     * These are the treaty-reduced rates. Without the paperwork the statutory
     * rate applies instead and is materially higher — 26.375% out of Germany,
     * 30% out of Sweden — which is why the notes say "if your broker has your
     * residency form on file" rather than stating a rate as fact.
     *
     * A country absent from this map is treated as withholding nothing rather
     * than guessed at. Under-warning is the safer error: a warning the user
     * cannot verify is worse than silence.
     */
    private val TREATY_RATES: Map<String, Double> = mapOf(
        "US" to US_TREATY_RATE,
        "CA" to CA_TREATY_RATE,
        "DE" to 0.15,
        "SE" to 0.15,
        "FR" to 0.15,
        "JP" to 0.15,
        // The UK, Hong Kong and Singapore do not withhold tax on ordinary
        // dividends paid to non-residents.
        "GB" to 0.0,
        "HK" to 0.0,
        "SG" to 0.0
    )

    /** ISO country the listing trades in, derived from the Yahoo-style suffix. */
    fun countryOf(ticker: String?): String? {
        val t = ticker?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: return null
        if (t.startsWith("^")) return null
        if (t.contains("-USD") || t.contains("-BTC") || t.contains("-ETH")) return null
        val dot = t.lastIndexOf('.')
        // No suffix at all is a US listing in Yahoo's scheme.
        if (dot < 0) return "US"
        return when (t.substring(dot)) {
            ".TO", ".V", ".NE", ".CN", ".TSX" -> "CA"
            ".L" -> "GB"
            ".AX" -> "AU"
            ".T" -> "JP"
            ".HK" -> "HK"
            ".PS" -> "PH"
            ".SI" -> "SG"
            ".DE", ".F", ".BE" -> "DE"
            ".PA" -> "FR"
            else -> null
        }
    }

    /** How a dividend from [ticker] is classified for a user living in [residency]. */
    fun incomeTypeFor(ticker: String?, residency: Residency): IncomeType {
        val country = countryOf(ticker) ?: return IncomeType.FOREIGN_DIVIDEND
        return if (country == residency.code) IncomeType.DOMESTIC_DIVIDEND
        else IncomeType.FOREIGN_DIVIDEND
    }

    /**
     * The withholding rate a foreign dividend loses at source, or 0.0 when
     * none applies.
     *
     * The asymmetry here is the entire point of the feature, and it surprises
     * almost everyone:
     *
     *  - A Canadian's US dividends are EXEMPT from US withholding inside an
     *    RRSP. The treaty recognises an RRSP as a pension.
     *  - The same dividends inside a TFSA are withheld at 15%, and because a
     *    TFSA produces no Canadian tax, there is nothing to claim a foreign
     *    tax credit against. The money is simply gone.
     *  - In a taxable account it is withheld too, but recoverable as a foreign
     *    tax credit, so the real cost is usually near zero.
     *
     * The US-resident case mirrors it: Canada exempts IRAs and 401(k)s from
     * withholding on Canadian dividends under the same treaty.
     */
    fun withholdingRate(
        ticker: String?,
        residency: Residency,
        treatment: TaxTreatment?
    ): Double {
        if (treatment == null) return 0.0
        // Only the Canada and US treaty networks are modelled. For every other
        // residency the honest answer is "this app does not know", and 0.0 is
        // how that is expressed — silence rather than an invented rate.
        if (!residency.modelsWithholding) return 0.0
        val country = countryOf(ticker) ?: return 0.0

        val home = if (residency == Residency.CANADA) "CA" else "US"
        // A domestic dividend is never withheld at source.
        if (country == home) return 0.0

        val rate = TREATY_RATES[country] ?: return 0.0
        if (rate <= 0.0) return 0.0

        // The pension exemption is bilateral and applies to ONE relationship:
        // the Canada–US treaty, which recognises an RRSP (and an IRA or 401(k)
        // the other way) as a pension and exempts it from withholding.
        //
        // It does NOT generalise. Germany, Sweden, France and Japan withhold on
        // dividends paid into an RRSP exactly as they do into a taxable
        // account — and because a registered account generates no Canadian tax,
        // there is nothing to claim a foreign tax credit against, so that
        // deduction is permanent. Treating "tax-deferred" as "exempt" across
        // the board would have told a Canadian holding German and Swedish
        // stocks in an RRSP that they were losing nothing, which is the
        // opposite of the truth.
        // The Canadian side is automatic: the treaty exempts an RRSP from US
        // withholding, and a broker with a W-8BEN on file applies it without
        // the user doing anything. A TFSA gets no such treatment.
        if (residency == Residency.CANADA && country == "US" &&
            treatment == TaxTreatment.TAX_DEFERRED
        ) return 0.0

        // The US side is NOT the mirror image, and modelling it as one was
        // wrong. Article XXI paragraph 2 exempts IRAs — and, as "arrangements
        // operated exclusively to administer or provide pension, retirement or
        // employee benefits", Roth IRAs and 401(k)s on the same footing — from
        // Canadian withholding. So the exemption covers BOTH registered types,
        // not just the tax-deferred one.
        //
        // But it is not automatic. The CRA requires a Letter of Exemption,
        // which means an application with the beneficial owner's details and a
        // notarised affidavit of residency. Most retail investors never file
        // one, and until they do the broker withholds as normal.
        //
        // So the rate stands. Returning 0 here would tell someone they are
        // keeping money that is in fact being deducted. The note explains that
        // the exemption exists and what it takes — see [exemptionNeedsFiling].
        return rate
    }

    /**
     * True when the withholding on this holding could be eliminated by filing,
     * rather than by moving the holding to a different account.
     *
     * The distinction matters for the advice given: "hold this somewhere else"
     * is useless when every registered account is treated alike, and the real
     * remedy is a form.
     */
    fun exemptionNeedsFiling(
        ticker: String?,
        residency: Residency,
        treatment: TaxTreatment?
    ): Boolean =
        residency == Residency.UNITED_STATES &&
            countryOf(ticker) == "CA" &&
            treatment != null &&
            treatment != TaxTreatment.TAXABLE

    /** True when the withholding on [ticker] can normally be reclaimed at filing time. */
    fun isRecoverable(treatment: TaxTreatment?): Boolean =
        treatment == TaxTreatment.TAXABLE

    /**
     * Notes to show against one holding.
     *
     * [annualDividendIncome] is the forward twelve-month income for the
     * position in its own currency, used only to put a number on the warning.
     * Pass null and the note still renders, without the figure.
     */
    fun notesFor(
        ticker: String?,
        residency: Residency,
        treatment: TaxTreatment?,
        annualDividendIncome: Double?
    ): List<TaxNote> {
        if (treatment == null) return emptyList()
        if (!residency.hasRegisteredAccounts) return emptyList()

        val notes = mutableListOf<TaxNote>()
        val rate = withholdingRate(ticker, residency, treatment)
        val foreignCountry = countryOf(ticker)
        val homeCountry = residency.code
        // Named from the country's own products where the app knows them, so a
        // UK reader sees "ISA" and "SIPP" rather than Canadian account names.
        val suggestions = suggestedAccounts(residency)
        val shelteredName = suggestions.firstOrNull { it.treatment == TaxTreatment.TAX_DEFERRED }
            ?.name ?: "a tax-deferred account"
        val freeName = suggestions.firstOrNull { it.treatment == TaxTreatment.TAX_FREE }
            ?.name ?: "a tax-free account"

        // Name the country that is actually deducting, rather than assuming it
        // is the one across the border. A Canadian holding Siemens Energy is
        // losing German tax, and being told about "US tax" would be nonsense.
        val payerName = countryName(foreignCountry)
        val pct = Math.round(rate * 100).toInt()
        val shelterAvoids =
            (residency == Residency.CANADA && foreignCountry == "US") ||
                (residency == Residency.UNITED_STATES && foreignCountry == "CA")

        if (rate > 0.0 && treatment != TaxTreatment.TAXABLE) {
            // Registered, and still being withheld: nothing to reclaim it
            // against, so the deduction is permanent.
            val accountName =
                if (treatment == TaxTreatment.TAX_FREE) freeName else shelteredName
            val needsFiling = exemptionNeedsFiling(ticker, residency, treatment)
            val remedy = when {
                // US resident, Canadian stock: every registered account
                // qualifies, but only once the paperwork is filed.
                needsFiling ->
                    " Canada does exempt US retirement accounts from this under the tax " +
                        "treaty, but not automatically — it requires a Letter of Exemption " +
                        "from the CRA, which your broker or custodian applies for. Until " +
                        "that is on file the tax is withheld as normal. Moving the holding " +
                        "to a different retirement account will not change it."
                // Canadian resident, US stock, currently in a TFSA: the
                // sheltered account genuinely avoids it, automatically.
                shelterAvoids && treatment == TaxTreatment.TAX_FREE ->
                    " Holding this in an $shelteredName instead would avoid it entirely, " +
                        "because the tax treaty exempts those accounts."
                shelterAvoids -> ""
                else -> {
                    // Written from the reader's own side. The exemption a
                    // Canadian has covers US dividends in an RRSP; the one a US
                    // resident has covers CANADIAN dividends in an IRA. Naming
                    // the wrong one — which the single hard-coded sentence here
                    // used to do for every US user — made the sentence false.
                    val exemptPayer = if (residency == Residency.CANADA) "US" else "Canadian"
                    " There is no account that avoids this one — the treaty exemption that " +
                        "covers $exemptPayer dividends in an $shelteredName does not extend " +
                        "to ${countryNoun(foreignCountry)}."
                }
            }

            notes += TaxNote(
                title = "$pct% is withheld and can't be recovered",
                detail = "$payerName companies deduct tax from dividends before they reach you. " +
                    "In a taxable account you could normally claim that back at tax time. In " +
                    "${article(accountName)} $accountName you can't — there's no tax owing to claim it against, so the " +
                    "deduction is permanent.$remedy",
                estimatedAnnualCost = annualDividendIncome?.times(rate),
                severity = TaxNote.Severity.WARNING
            )
        }

        if (rate > 0.0 && treatment == TaxTreatment.TAXABLE) {
            notes += TaxNote(
                title = "$pct% withheld at source, usually recoverable",
                detail = "$payerName tax is deducted before the dividend reaches you, but in " +
                    "a taxable account you can generally claim it back as a foreign tax credit " +
                    "when you file. Your broker reports the amount withheld.",
                severity = TaxNote.Severity.INFO
            )
        }

        if (residency.modelsWithholding &&
            foreignCountry == homeCountry && treatment == TaxTreatment.TAXABLE
        ) {
            val detail = if (residency == Residency.CANADA)
                "Dividends from Canadian companies get the dividend tax credit, which usually " +
                    "makes them the most lightly taxed income in a taxable account — often " +
                    "taxed less than interest or foreign dividends of the same size."
            else
                "Dividends from US companies are usually \"qualified\", which means a lower tax " +
                    "rate than interest — provided you've held the shares long enough, generally " +
                    "more than 60 days around the dividend date."
            notes += TaxNote(
                title = "Favourably taxed here",
                detail = detail,
                severity = TaxNote.Severity.INFO
            )
        }

        if (treatment == TaxTreatment.TAX_FREE && rate == 0.0) {
            notes += TaxNote(
                title = "No tax on this",
                detail = "Growth and dividends in ${article(freeName)} $freeName aren't taxed, and nothing needs " +
                    "reporting when you withdraw.",
                severity = TaxNote.Severity.INFO
            )
        }

        if (treatment == TaxTreatment.TAX_DEFERRED && rate <= 0.0) {
            notes += TaxNote(
                title = "Taxed when you withdraw, not now",
                detail = "Nothing in ${article(shelteredName)} $shelteredName is taxed while it stays there — dividends " +
                    "and gains both compound untouched. Withdrawals are taxed as regular income.",
                severity = TaxNote.Severity.INFO
            )
        }

        return notes
    }

    /**
     * The country as a NOUN, for sentences that name the place rather than
     * describe its companies.
     *
     * [countryName] returns an adjective — "German companies deduct tax" — and
     * reusing it where a noun belongs produced "does not extend to German".
     */
    /**
     * "a" or "an" for an account name.
     *
     * The names are acronyms read aloud letter by letter — R-R-S-P, I-R-A — so
     * the article follows the SOUND, not the spelling. "a RRSP" is what a
     * naive template produces and what a reader notices immediately.
     */
    private fun article(name: String): String {
        val first = name.trim().substringBefore(' ')
        if (first.isEmpty()) return "a"
        // An acronym is spelled out, so the article follows the letter's sound:
        // "an RRSP" (ar), "an IRA" (eye), but "a TFSA" (tee).
        val isAcronym = first.length > 1 && first.all { it.isUpperCase() || !it.isLetter() }
        return if (isAcronym) {
            if (first[0] in "AEFHILMNORSX") "an" else "a"
        } else {
            // An ordinary word follows its opening sound: "a Roth IRA".
            if (first[0].uppercaseChar() in "AEIOU") "an" else "a"
        }
    }

    private fun countryNoun(code: String?): String = when (code) {
        "US" -> "the United States"; "CA" -> "Canada"; "DE" -> "Germany"
        "SE" -> "Sweden"; "FR" -> "France"; "JP" -> "Japan"
        "GB" -> "the United Kingdom"; "HK" -> "Hong Kong"; "SG" -> "Singapore"
        else -> "that country"
    }

    private fun countryName(code: String?): String = when (code) {
        "US" -> "US"; "CA" -> "Canadian"; "DE" -> "German"; "SE" -> "Swedish"
        "FR" -> "French"; "JP" -> "Japanese"; "GB" -> "UK"; "HK" -> "Hong Kong"
        "SG" -> "Singapore"; else -> "Foreign"
    }

    /**
     * The two rates the user supplies, as percentages. Null means "not told".
     *
     * Asked for rather than derived. Computing them would mean shipping federal
     * AND provincial/state brackets for 13 provinces and 50 states, knowing the
     * user's total income from every other source, and re-checking all of it
     * every year. Getting that subtly wrong produces a confident number that is
     * quietly incorrect, which is worse than no number — and going stale is a
     * certainty, not a risk. A rate the user looked up once is both more
     * accurate and honestly theirs.
     */
    data class UserRates(
        val marginalPct: Double?,
        val preferentialPct: Double?
    ) {
        val hasAny: Boolean get() = marginalPct != null || preferentialPct != null
    }

    /**
     * Estimated annual tax on one holding's dividend income.
     *
     * Three separate deductions, and they do not all apply at once:
     *
     *  - Foreign withholding, taken at source before the money arrives.
     *  - Domestic tax on the income, which a registered account does not pay.
     *  - Nothing else. This is not a tax return.
     *
     * In a taxable account the withholding is normally reclaimed as a foreign
     * tax credit, so counting both it AND full domestic tax would double-count
     * the same dollar. The credit is assumed to offset the withholding exactly,
     * which is the usual case and slightly optimistic at the margins.
     */
    data class DividendTaxEstimate(
        val grossIncome: Double,
        val withheld: Double,
        val domesticTax: Double,
        val incomeType: IncomeType
    ) {
        val totalTax: Double get() = withheld + domesticTax
        val afterTax: Double get() = (grossIncome - totalTax).coerceAtLeast(0.0)
        val effectiveRatePct: Double
            get() = if (grossIncome > 0) totalTax / grossIncome * 100.0 else 0.0
    }

    fun estimateDividendTax(
        ticker: String?,
        residency: Residency,
        treatment: TaxTreatment?,
        annualDividendIncome: Double,
        rates: UserRates
    ): DividendTaxEstimate? {
        if (residency == Residency.OTHER || treatment == null) return null
        if (annualDividendIncome <= 0.0) return null

        val incomeType = incomeTypeFor(ticker, residency)
        val withholdingRate = withholdingRate(ticker, residency, treatment)

        val domesticRatePct = when {
            // Registered accounts pay no domestic tax on the income. A
            // tax-deferred account pays on WITHDRAWAL, which is a different
            // event at a different rate and is not estimated here.
            treatment != TaxTreatment.TAXABLE -> 0.0
            // A domestic dividend gets the preferential treatment — the
            // dividend tax credit in Canada, the qualified rate in the US —
            // but only if the user has told us what that rate is.
            incomeType == IncomeType.DOMESTIC_DIVIDEND ->
                rates.preferentialPct ?: rates.marginalPct ?: return null
            // Foreign dividends are ordinary income in both countries.
            else -> rates.marginalPct ?: return null
        }

        val withheld = annualDividendIncome * withholdingRate
        val domestic = if (treatment == TaxTreatment.TAXABLE) {
            val gross = annualDividendIncome * (domesticRatePct / 100.0)
            // Foreign tax credit: the withholding already paid offsets the
            // domestic bill rather than adding to it.
            (gross - withheld).coerceAtLeast(0.0)
        } else 0.0

        return DividendTaxEstimate(
            grossIncome = annualDividendIncome,
            withheld = withheld,
            domesticTax = domestic,
            incomeType = incomeType
        )
    }

    /**
     * Estimated tax if a position were sold today at [unrealizedGain] profit.
     *
     * Null in a registered account, where a sale is not a taxable event at all.
     */
    fun estimateCapitalGainsTax(
        residency: Residency,
        treatment: TaxTreatment?,
        unrealizedGain: Double,
        rates: UserRates
    ): Double? {
        if (residency == Residency.OTHER) return null
        if (treatment != TaxTreatment.TAXABLE) return null
        if (unrealizedGain <= 0.0) return null

        return when (residency) {
            // Canada includes half of a capital gain in income. The increase to
            // two-thirds was proposed and then cancelled in March 2025, so 50%
            // stands.
            Residency.CANADA -> {
                val rate = rates.marginalPct ?: return null
                unrealizedGain * 0.5 * (rate / 100.0)
            }
            // Assumes the position has been held over a year, so the long-term
            // rate applies. A shorter hold is taxed as ordinary income, which
            // the app cannot know without a purchase date on every lot.
            Residency.UNITED_STATES -> {
                val rate = rates.preferentialPct ?: rates.marginalPct ?: return null
                unrealizedGain * (rate / 100.0)
            }
            // No inclusion rate or preferential regime modelled elsewhere.
            else -> null
        }
    }

    /**
     * Common account types for a country, with the tax treatment each one has.
     *
     * Offered as one-tap suggestions when creating an account. The reason is
     * not convenience: a user who does not know the jargon cannot reliably pick
     * "tax-free" for a Roth IRA, and a wrong pick here silently changes every
     * tax figure for everything in that account. Naming the real product and
     * setting the treatment for them removes the guess.
     *
     * The list is deliberately short and uncontroversial. Anything unusual gets
     * typed by hand, where the user is choosing the treatment consciously.
     */
    data class AccountSuggestion(val name: String, val treatment: TaxTreatment)

    fun suggestedAccounts(residency: Residency): List<AccountSuggestion> = when (residency) {
        Residency.UNITED_STATES -> listOf(
            AccountSuggestion("Brokerage", TaxTreatment.TAXABLE),
            AccountSuggestion("Roth IRA", TaxTreatment.TAX_FREE),
            AccountSuggestion("Traditional IRA", TaxTreatment.TAX_DEFERRED),
            AccountSuggestion("401(k)", TaxTreatment.TAX_DEFERRED),
            AccountSuggestion("Roth 401(k)", TaxTreatment.TAX_FREE)
        )
        Residency.CANADA -> listOf(
            AccountSuggestion("Non-registered", TaxTreatment.TAXABLE),
            AccountSuggestion("TFSA", TaxTreatment.TAX_FREE),
            AccountSuggestion("RRSP", TaxTreatment.TAX_DEFERRED),
            AccountSuggestion("FHSA", TaxTreatment.TAX_FREE),
            AccountSuggestion("RESP", TaxTreatment.TAX_DEFERRED),
            AccountSuggestion("RRIF", TaxTreatment.TAX_DEFERRED)
        )
        Residency.UNITED_KINGDOM -> listOf(
            AccountSuggestion("General account", TaxTreatment.TAXABLE),
            AccountSuggestion("Stocks & Shares ISA", TaxTreatment.TAX_FREE),
            AccountSuggestion("Lifetime ISA", TaxTreatment.TAX_FREE),
            AccountSuggestion("SIPP", TaxTreatment.TAX_DEFERRED),
            AccountSuggestion("Workplace pension", TaxTreatment.TAX_DEFERRED)
        )
        Residency.AUSTRALIA -> listOf(
            AccountSuggestion("Brokerage", TaxTreatment.TAXABLE),
            // Super is concessionally taxed rather than untaxed — 15% on
            // earnings in accumulation, tax-free in pension phase. Filed as
            // tax-deferred because that is the closer of the three, and the
            // note says "taxed when you withdraw" rather than "not taxed".
            AccountSuggestion("Superannuation", TaxTreatment.TAX_DEFERRED)
        )
        Residency.NEW_ZEALAND -> listOf(
            AccountSuggestion("Brokerage", TaxTreatment.TAXABLE),
            AccountSuggestion("KiwiSaver", TaxTreatment.TAX_DEFERRED)
        )
        Residency.JAPAN -> listOf(
            AccountSuggestion("Taxable account", TaxTreatment.TAXABLE),
            AccountSuggestion("NISA", TaxTreatment.TAX_FREE),
            AccountSuggestion("iDeCo", TaxTreatment.TAX_DEFERRED)
        )
        Residency.SINGAPORE -> listOf(
            AccountSuggestion("Brokerage", TaxTreatment.TAXABLE),
            AccountSuggestion("SRS", TaxTreatment.TAX_DEFERRED)
        )
        Residency.PHILIPPINES -> listOf(
            AccountSuggestion("Brokerage", TaxTreatment.TAXABLE),
            AccountSuggestion("PERA", TaxTreatment.TAX_FREE)
        )
        Residency.INDIA -> listOf(
            AccountSuggestion("Demat account", TaxTreatment.TAXABLE),
            AccountSuggestion("PPF", TaxTreatment.TAX_FREE),
            AccountSuggestion("NPS", TaxTreatment.TAX_DEFERRED),
            AccountSuggestion("EPF", TaxTreatment.TAX_DEFERRED)
        )
        Residency.SOUTH_AFRICA -> listOf(
            AccountSuggestion("Brokerage", TaxTreatment.TAXABLE),
            AccountSuggestion("Tax-Free Savings Account", TaxTreatment.TAX_FREE),
            AccountSuggestion("Retirement Annuity", TaxTreatment.TAX_DEFERRED)
        )
        Residency.FRANCE -> listOf(
            AccountSuggestion("Compte-titres", TaxTreatment.TAXABLE),
            AccountSuggestion("PEA", TaxTreatment.TAX_FREE),
            AccountSuggestion("PER", TaxTreatment.TAX_DEFERRED)
        )
        Residency.POLAND -> listOf(
            AccountSuggestion("Brokerage", TaxTreatment.TAXABLE),
            AccountSuggestion("IKE", TaxTreatment.TAX_FREE),
            AccountSuggestion("IKZE", TaxTreatment.TAX_DEFERRED)
        )
        Residency.OTHER -> emptyList()
    }

    /**
     * Real product names for a treatment, in the reader's own country.
     *
     * Derived from [suggestedAccounts] rather than written out separately, so
     * the chips at the top of the New Account dialog and the descriptions
     * beside the radio buttons below them can never disagree — which they did:
     * the chips read ISA and SIPP while the radios read RRSP and TFSA.
     *
     * Falls back to a behaviour description where the country has no known
     * products, which is also what a reader in an unsupported country needs.
     */
    fun examplesFor(treatment: TaxTreatment, residency: Residency): String {
        val named = suggestedAccounts(residency)
            .filter { it.treatment == treatment }
            .joinToString(", ") { it.name }
        return if (named.isBlank()) treatment.description() else named
    }

    /** Placeholder text for the account-name field, in the user's own vocabulary. */
    fun accountNameHint(residency: Residency): String {
        // The tax-advantaged ones first: a placeholder reading "General
        // account…" teaches nothing, while "Stocks & Shares ISA, SIPP…" tells
        // a UK user immediately that the app speaks their vocabulary.
        val named = suggestedAccounts(residency)
            .sortedBy { it.treatment == TaxTreatment.TAXABLE }
            .take(2)
            .joinToString(", ") { it.name }
        return if (named.isEmpty()) "Name this account…" else "$named…"
    }

    /** What the two rate fields should be called for this residency. */
    fun marginalRateLabel(residency: Residency): String = when (residency) {
        Residency.CANADA -> "Your marginal tax rate"
        else -> "Your marginal (ordinary income) rate"
    }

    fun preferentialRateLabel(residency: Residency): String = when (residency) {
        Residency.CANADA -> "Your eligible-dividend rate"
        else -> "Your qualified-dividend / long-term gains rate"
    }

    fun preferentialRateHint(residency: Residency): String = when (residency) {
        Residency.CANADA ->
            "The rate you pay on dividends from Canadian companies, after the dividend " +
                "tax credit. It is usually well below your marginal rate, and can be " +
                "negative at low incomes. Your province publishes a table of these."
        else ->
            "Usually 0%, 15% or 20% depending on your income. It applies to qualified " +
                "dividends and to gains on anything held more than a year."
    }

    /** Shown on every screen carrying a tax figure. Not optional. */
    const val DISCLAIMER =
        "These are general estimates, not tax advice. Rules depend on your own " +
            "circumstances and on the residency paperwork your broker holds. " +
            "Check with a tax professional before acting on anything here."
}
