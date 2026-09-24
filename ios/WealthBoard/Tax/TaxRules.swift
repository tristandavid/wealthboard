import Foundation

// Ported from `tax/TaxRules.kt`.

/// Where the user files. Only the two the app models withholding for are
/// treated as such; everyone else gets account classification without invented
/// withholding rates.
enum Residency: String, Codable, CaseIterable, Identifiable {
    case canada = "CA"
    case unitedStates = "US"

    // Countries the app does NOT model withholding for, but which do have
    // tax-advantaged accounts worth classifying.
    //
    // The distinction matters. Withholding needs a full treaty network per
    // residency and is expensive to get right; account CLASSIFICATION needs
    // only the local product names, and delivers real value on its own — a
    // holding in an ISA or a NISA genuinely is not taxed, and saying so is
    // useful even when the app cannot price the foreign tax on it.
    case unitedKingdom = "GB"
    case australia = "AU"
    case newZealand = "NZ"
    case japan = "JP"
    case singapore = "SG"
    case philippines = "PH"
    case india = "IN"
    case southAfrica = "ZA"
    case france = "FR"
    case poland = "PL"

    case other = ""

    var id: String { rawValue }

    var code: String { rawValue }

    var label: String {
        switch self {
        case .canada: return "Canada"
        case .unitedStates: return "United States"
        case .unitedKingdom: return "United Kingdom"
        case .australia: return "Australia"
        case .newZealand: return "New Zealand"
        case .japan: return "Japan"
        case .singapore: return "Singapore"
        case .philippines: return "Philippines"
        case .india: return "India"
        case .southAfrica: return "South Africa"
        case .france: return "France"
        case .poland: return "Poland"
        case .other: return "Somewhere else"
        }
    }

    /// True when the app can put a number on tax withheld abroad for someone
    /// filing here. Only the two treaty networks actually modelled qualify.
    var modelsWithholding: Bool {
        self == .canada || self == .unitedStates
    }

    /// True when this country has tax-advantaged accounts the app can classify.
    var hasRegisteredAccounts: Bool { self != .other }

    static func from(code: String?) -> Residency {
        guard let code = code?.uppercased(), !code.isEmpty else { return .other }
        return Residency.allCases.first { $0.rawValue == code && !$0.rawValue.isEmpty } ?? .other
    }
}

/// What kind of income a distribution is, which is what decides its rate.
enum IncomeType {
    /// Dividend from a company resident in the user's own country.
    case domesticDividend
    /// Dividend from abroad. Taxed as ordinary income, and usually withheld at source.
    case foreignDividend
    /// Interest, and distributions taxed like it. No preferential rate anywhere.
    case interest
    /// Realised capital gain.
    case capitalGain
}

/// A warning worth showing against a holding.
///
/// `detail` is written for someone who does not know how tax works — the rule
/// is explained, not named. `estimatedAnnualCost` is the money at stake per
/// year where that can be estimated, because "you are losing 15%" lands
/// differently from "$66 a year".
struct TaxNote: Identifiable, Hashable {
    enum Severity { case info, warning }

    let title: String
    let detail: String
    var estimatedAnnualCost: Double?
    var severity: Severity = .info

    var id: String { title }
}

/// The tax rules this app models, and nothing beyond them.
///
/// Scope is deliberately narrow. This does NOT compute anyone's tax bill: it
/// has no brackets, no provincial or state rates, no surtaxes and no knowledge
/// of anybody's other income. What it does is answer two questions the app
/// already has the data to answer honestly:
///
/// 1. Is tax relevant to this account at all?
/// 2. Is this holding losing money to foreign withholding that a different
///    account would avoid?
///
/// The second is the one worth building. Withholding is deducted at source and
/// never appears on a statement as a line item, so it is invisible to the
/// person losing it — and unlike a marginal rate, the app can work it out from
/// facts it already holds: the listing's country, the account's treatment, and
/// the user's residency.
///
/// Everything here is an ESTIMATE, and treaty rates depend on the user having
/// filed the right residency paperwork with their broker.
enum TaxRules {

    /// Treaty withholding on dividends, by the country that PAYS, for a
    /// Canadian or US resident whose broker holds their residency paperwork.
    ///
    /// These are the treaty-reduced rates. WITHOUT the paperwork the statutory
    /// rate applies instead and is materially higher — 26.375% out of Germany,
    /// 30% out of Sweden — which is why the notes say "if your broker has your
    /// residency form on file" rather than stating a rate as fact.
    ///
    /// A country absent from this map is treated as withholding nothing rather
    /// than guessed at. Under-warning is the safer error: a warning the user
    /// cannot verify is worse than silence.
    private static let treatyRates: [String: Double] = [
        "US": 0.15,
        "CA": 0.15,
        "DE": 0.15,
        "SE": 0.15,
        "FR": 0.15,
        "JP": 0.15,
        // The UK, Hong Kong and Singapore do not withhold tax on ordinary
        // dividends paid to non-residents.
        "GB": 0.0,
        "HK": 0.0,
        "SG": 0.0
    ]

    /// ISO country the listing trades in, derived from the ticker suffix.
    static func country(of ticker: String?) -> String? {
        guard let t = ticker?.trimmingCharacters(in: .whitespaces).uppercased(), !t.isEmpty else {
            return nil
        }
        if t.hasPrefix("^") { return nil }
        if t.contains("-USD") || t.contains("-BTC") || t.contains("-ETH") { return nil }
        guard let dot = t.lastIndex(of: ".") else {
            return "US"   // No suffix at all is a US listing.
        }
        switch String(t[dot...]) {
        case ".TO", ".V", ".NE", ".CN", ".TSX": return "CA"
        case ".L": return "GB"
        case ".AX": return "AU"
        case ".T": return "JP"
        case ".HK": return "HK"
        case ".PS": return "PH"
        case ".SI": return "SG"
        case ".DE", ".F", ".BE": return "DE"
        case ".PA": return "FR"
        default: return nil
        }
    }

    /// How a dividend from `ticker` is classified for a user living in `residency`.
    static func incomeType(ticker: String?, residency: Residency) -> IncomeType {
        guard let country = country(of: ticker) else { return .foreignDividend }
        return country == residency.code ? .domesticDividend : .foreignDividend
    }

    /// The withholding rate a foreign dividend loses at source, or 0 when none
    /// applies.
    ///
    /// The asymmetry here is the entire point of the feature, and it surprises
    /// almost everyone:
    ///
    /// - A Canadian's US dividends are EXEMPT from US withholding inside an
    ///   RRSP. The treaty recognises an RRSP as a pension.
    /// - The same dividends inside a TFSA are withheld at 15%, and because a
    ///   TFSA produces no Canadian tax, there is nothing to claim a foreign tax
    ///   credit against. The money is simply gone.
    /// - In a taxable account it is withheld too, but recoverable as a foreign
    ///   tax credit, so the real cost is usually near zero.
    static func withholdingRate(
        ticker: String?,
        residency: Residency,
        treatment: TaxTreatment?
    ) -> Double {
        guard let treatment else { return 0 }
        // Only the Canada and US treaty networks are modelled. For every other
        // residency the honest answer is "this app does not know", and 0 is how
        // that is expressed — silence rather than an invented rate.
        guard residency.modelsWithholding else { return 0 }
        guard let country = country(of: ticker) else { return 0 }

        let home = residency == .canada ? "CA" : "US"
        // A domestic dividend is never withheld at source.
        if country == home { return 0 }

        guard let rate = treatyRates[country], rate > 0 else { return 0 }

        // The pension exemption is bilateral and applies to ONE relationship:
        // the Canada–US treaty, which recognises an RRSP (and an IRA or 401(k)
        // the other way) as a pension and exempts it from withholding.
        //
        // It does NOT generalise. Germany, Sweden, France and Japan withhold on
        // dividends paid into an RRSP exactly as they do into a taxable
        // account — and because a registered account generates no Canadian tax,
        // there is nothing to claim a foreign tax credit against, so that
        // deduction is permanent.
        //
        // The Canadian side is automatic: the treaty exempts an RRSP from US
        // withholding, and a broker with a W-8BEN on file applies it without
        // the user doing anything. A TFSA gets no such treatment.
        if residency == .canada, country == "US", treatment == .taxDeferred { return 0 }

        // The US side is NOT the mirror image. Article XXI paragraph 2 exempts
        // IRAs — and Roth IRAs and 401(k)s on the same footing — from Canadian
        // withholding, so the exemption covers BOTH registered types. But it is
        // not automatic: the CRA requires a Letter of Exemption, and most
        // retail investors never file one. So the rate stands; returning 0 here
        // would tell someone they are keeping money that is in fact being
        // deducted. See `exemptionNeedsFiling`.
        return rate
    }

    /// True when the withholding on this holding could be eliminated by filing,
    /// rather than by moving the holding to a different account.
    ///
    /// The distinction matters for the advice given: "hold this somewhere else"
    /// is useless when every registered account is treated alike, and the real
    /// remedy is a form.
    static func exemptionNeedsFiling(
        ticker: String?,
        residency: Residency,
        treatment: TaxTreatment?
    ) -> Bool {
        residency == .unitedStates
            && country(of: ticker) == "CA"
            && treatment != nil
            && treatment != .taxable
    }

    /// True when the withholding on `ticker` can normally be reclaimed at
    /// filing time.
    static func isRecoverable(_ treatment: TaxTreatment?) -> Bool {
        treatment == .taxable
    }

    // MARK: - Notes

    /// Notes to show against one holding.
    ///
    /// `annualDividendIncome` is the forward twelve-month income for the
    /// position in its own currency, used only to put a number on the warning.
    /// Pass nil and the note still renders, without the figure.
    static func notes(
        ticker: String?,
        residency: Residency,
        treatment: TaxTreatment?,
        annualDividendIncome: Double?
    ) -> [TaxNote] {
        guard let treatment else { return [] }
        guard residency.hasRegisteredAccounts else { return [] }

        var notes: [TaxNote] = []
        let rate = withholdingRate(ticker: ticker, residency: residency, treatment: treatment)
        let foreignCountry = country(of: ticker)
        let homeCountry = residency.code

        // Named from the country's own products where the app knows them, so a
        // UK reader sees "ISA" and "SIPP" rather than Canadian account names.
        let suggestions = suggestedAccounts(residency)
        let shelteredName = suggestions.first { $0.treatment == .taxDeferred }?.name
            ?? "a tax-deferred account"
        let freeName = suggestions.first { $0.treatment == .taxFree }?.name
            ?? "a tax-free account"

        // Name the country that is actually deducting, rather than assuming it
        // is the one across the border. A Canadian holding Siemens Energy is
        // losing German tax, and being told about "US tax" would be nonsense.
        let payerName = countryAdjective(foreignCountry)
        let pct = Int((rate * 100).rounded())
        let shelterAvoids = (residency == .canada && foreignCountry == "US")
            || (residency == .unitedStates && foreignCountry == "CA")

        if rate > 0, treatment != .taxable {
            // Registered, and still being withheld: nothing to reclaim it
            // against, so the deduction is permanent.
            let accountName = treatment == .taxFree ? freeName : shelteredName
            let needsFiling = exemptionNeedsFiling(ticker: ticker, residency: residency, treatment: treatment)

            let remedy: String
            if needsFiling {
                remedy = " Canada does exempt US retirement accounts from this under the tax "
                    + "treaty, but not automatically — it requires a Letter of Exemption from "
                    + "the CRA, which your broker or custodian applies for. Until that is on "
                    + "file the tax is withheld as normal. Moving the holding to a different "
                    + "retirement account will not change it."
            } else if shelterAvoids && treatment == .taxFree {
                remedy = " Holding this in \(article(shelteredName)) \(shelteredName) instead would "
                    + "avoid it entirely, because the tax treaty exempts those accounts."
            } else if shelterAvoids {
                remedy = ""
            } else {
                // Written from the reader's own side. The exemption a Canadian
                // has covers US dividends in an RRSP; the one a US resident has
                // covers CANADIAN dividends in an IRA. Naming the wrong one
                // makes the sentence false.
                let exemptPayer = residency == .canada ? "US" : "Canadian"
                remedy = " There is no account that avoids this one — the treaty exemption that "
                    + "covers \(exemptPayer) dividends in \(article(shelteredName)) \(shelteredName) "
                    + "does not extend to \(countryNoun(foreignCountry))."
            }

            notes.append(TaxNote(
                title: "\(pct)% is withheld and can't be recovered",
                detail: "\(payerName) companies deduct tax from dividends before they reach you. "
                    + "In a taxable account you could normally claim that back at tax time. In "
                    + "\(article(accountName)) \(accountName) you can't — there's no tax owing to "
                    + "claim it against, so the deduction is permanent.\(remedy)",
                estimatedAnnualCost: annualDividendIncome.map { $0 * rate },
                severity: .warning
            ))
        }

        if rate > 0, treatment == .taxable {
            notes.append(TaxNote(
                title: "\(pct)% withheld at source, usually recoverable",
                detail: "\(payerName) tax is deducted before the dividend reaches you, but in a "
                    + "taxable account you can generally claim it back as a foreign tax credit "
                    + "when you file. Your broker reports the amount withheld.",
                severity: .info
            ))
        }

        if residency.modelsWithholding, foreignCountry == homeCountry, treatment == .taxable {
            let detail = residency == .canada
                ? "Dividends from Canadian companies get the dividend tax credit, which usually "
                    + "makes them the most lightly taxed income in a taxable account — often taxed "
                    + "less than interest or foreign dividends of the same size."
                : "Dividends from US companies are usually \"qualified\", which means a lower tax "
                    + "rate than interest — provided you've held the shares long enough, generally "
                    + "more than 60 days around the dividend date."
            notes.append(TaxNote(title: "Favourably taxed here", detail: detail, severity: .info))
        }

        if treatment == .taxFree, rate == 0 {
            notes.append(TaxNote(
                title: "No tax on this",
                detail: "Growth and dividends in \(article(freeName)) \(freeName) aren't taxed, and "
                    + "nothing needs reporting when you withdraw.",
                severity: .info
            ))
        }

        if treatment == .taxDeferred, rate <= 0 {
            notes.append(TaxNote(
                title: "Taxed when you withdraw, not now",
                detail: "Nothing in \(article(shelteredName)) \(shelteredName) is taxed while it "
                    + "stays there — dividends and gains both compound untouched. Withdrawals are "
                    + "taxed as regular income.",
                severity: .info
            ))
        }

        return notes
    }

    /// "a" or "an" for an account name.
    ///
    /// The names are acronyms read aloud letter by letter — R-R-S-P, I-R-A — so
    /// the article follows the SOUND, not the spelling. "a RRSP" is what a naive
    /// template produces and what a reader notices immediately.
    private static func article(_ name: String) -> String {
        let first = name.trimmingCharacters(in: .whitespaces)
            .split(separator: " ").first.map(String.init) ?? ""
        guard let initial = first.first else { return "a" }

        let isAcronym = first.count > 1 && first.allSatisfy { $0.isUppercase || !$0.isLetter }
        if isAcronym {
            return "AEFHILMNORSX".contains(initial) ? "an" : "a"
        }
        return "AEIOU".contains(Character(initial.uppercased())) ? "an" : "a"
    }

    /// The country as a NOUN, for sentences that name the place rather than
    /// describe its companies.
    private static func countryNoun(_ code: String?) -> String {
        switch code {
        case "US": return "the United States"
        case "CA": return "Canada"
        case "DE": return "Germany"
        case "SE": return "Sweden"
        case "FR": return "France"
        case "JP": return "Japan"
        case "GB": return "the United Kingdom"
        case "HK": return "Hong Kong"
        case "SG": return "Singapore"
        default: return "that country"
        }
    }

    /// The country as an ADJECTIVE — "German companies deduct tax".
    private static func countryAdjective(_ code: String?) -> String {
        switch code {
        case "US": return "US"
        case "CA": return "Canadian"
        case "DE": return "German"
        case "SE": return "Swedish"
        case "FR": return "French"
        case "JP": return "Japanese"
        case "GB": return "UK"
        case "HK": return "Hong Kong"
        case "SG": return "Singapore"
        default: return "Foreign"
        }
    }

    // MARK: - Estimates

    /// The two rates the user supplies, as percentages. nil means "not told".
    ///
    /// Asked for rather than derived. Computing them would mean shipping
    /// federal AND provincial/state brackets for 13 provinces and 50 states,
    /// knowing the user's total income from every other source, and re-checking
    /// all of it every year. Getting that subtly wrong produces a confident
    /// number that is quietly incorrect, which is worse than no number.
    struct UserRates: Equatable {
        var marginalPct: Double?
        var preferentialPct: Double?

        var hasAny: Bool { marginalPct != nil || preferentialPct != nil }
    }

    /// Estimated annual tax on one holding's dividend income.
    ///
    /// Three separate deductions, and they do not all apply at once:
    /// foreign withholding taken at source; domestic tax on the income, which a
    /// registered account does not pay; and nothing else — this is not a tax
    /// return. In a taxable account the withholding is normally reclaimed as a
    /// foreign tax credit, so counting both it AND full domestic tax would
    /// double-count the same dollar.
    struct DividendTaxEstimate {
        let grossIncome: Double
        let withheld: Double
        let domesticTax: Double
        let incomeType: IncomeType

        var totalTax: Double { withheld + domesticTax }
        var afterTax: Double { max(grossIncome - totalTax, 0) }
        var effectiveRatePct: Double {
            grossIncome > 0 ? totalTax / grossIncome * 100 : 0
        }
    }

    static func estimateDividendTax(
        ticker: String?,
        residency: Residency,
        treatment: TaxTreatment?,
        annualDividendIncome: Double,
        rates: UserRates
    ) -> DividendTaxEstimate? {
        guard residency != .other, let treatment else { return nil }
        guard annualDividendIncome > 0 else { return nil }

        let type = incomeType(ticker: ticker, residency: residency)
        let withholding = withholdingRate(ticker: ticker, residency: residency, treatment: treatment)

        let domesticRatePct: Double
        if treatment != .taxable {
            // Registered accounts pay no domestic tax on the income. A
            // tax-deferred account pays on WITHDRAWAL, which is a different
            // event at a different rate and is not estimated here.
            domesticRatePct = 0
        } else if type == .domesticDividend {
            // A domestic dividend gets the preferential treatment — the
            // dividend tax credit in Canada, the qualified rate in the US — but
            // only if the user has told us what that rate is.
            guard let pct = rates.preferentialPct ?? rates.marginalPct else { return nil }
            domesticRatePct = pct
        } else {
            // Foreign dividends are ordinary income in both countries.
            guard let pct = rates.marginalPct else { return nil }
            domesticRatePct = pct
        }

        let withheld = annualDividendIncome * withholding
        let domestic: Double
        if treatment == .taxable {
            let gross = annualDividendIncome * (domesticRatePct / 100)
            // Foreign tax credit: the withholding already paid offsets the
            // domestic bill rather than adding to it.
            domestic = max(gross - withheld, 0)
        } else {
            domestic = 0
        }

        return DividendTaxEstimate(
            grossIncome: annualDividendIncome,
            withheld: withheld,
            domesticTax: domestic,
            incomeType: type
        )
    }

    /// Estimated tax if a position were sold today at `unrealizedGain` profit.
    /// nil in a registered account, where a sale is not a taxable event at all.
    static func estimateCapitalGainsTax(
        residency: Residency,
        treatment: TaxTreatment?,
        unrealizedGain: Double,
        rates: UserRates
    ) -> Double? {
        guard residency != .other else { return nil }
        guard treatment == .taxable else { return nil }
        guard unrealizedGain > 0 else { return nil }

        switch residency {
        case .canada:
            // Canada includes half of a capital gain in income. The increase to
            // two-thirds was proposed and then cancelled in March 2025, so 50%
            // stands.
            guard let rate = rates.marginalPct else { return nil }
            return unrealizedGain * 0.5 * (rate / 100)
        case .unitedStates:
            // Assumes the position has been held over a year, so the long-term
            // rate applies. A shorter hold is taxed as ordinary income, which
            // the app cannot know without a purchase date on every lot.
            guard let rate = rates.preferentialPct ?? rates.marginalPct else { return nil }
            return unrealizedGain * (rate / 100)
        default:
            // No inclusion rate or preferential regime modelled elsewhere.
            return nil
        }
    }

    // MARK: - Account suggestions

    /// Common account types for a country, with the tax treatment each one has.
    ///
    /// Offered as one-tap suggestions when creating an account. The reason is
    /// not convenience: a user who does not know the jargon cannot reliably
    /// pick "tax-free" for a Roth IRA, and a wrong pick here silently changes
    /// every tax figure for everything in that account.
    struct AccountSuggestion: Identifiable, Hashable {
        let name: String
        let treatment: TaxTreatment
        var id: String { name }
    }

    static func suggestedAccounts(_ residency: Residency) -> [AccountSuggestion] {
        switch residency {
        case .unitedStates:
            return [
                AccountSuggestion(name: "Brokerage", treatment: .taxable),
                AccountSuggestion(name: "Roth IRA", treatment: .taxFree),
                AccountSuggestion(name: "Traditional IRA", treatment: .taxDeferred),
                AccountSuggestion(name: "401(k)", treatment: .taxDeferred),
                AccountSuggestion(name: "Roth 401(k)", treatment: .taxFree)
            ]
        case .canada:
            return [
                AccountSuggestion(name: "Non-registered", treatment: .taxable),
                AccountSuggestion(name: "TFSA", treatment: .taxFree),
                AccountSuggestion(name: "RRSP", treatment: .taxDeferred),
                AccountSuggestion(name: "FHSA", treatment: .taxFree),
                AccountSuggestion(name: "RESP", treatment: .taxDeferred),
                AccountSuggestion(name: "RRIF", treatment: .taxDeferred)
            ]
        case .unitedKingdom:
            return [
                AccountSuggestion(name: "General account", treatment: .taxable),
                AccountSuggestion(name: "Stocks & Shares ISA", treatment: .taxFree),
                AccountSuggestion(name: "Lifetime ISA", treatment: .taxFree),
                AccountSuggestion(name: "SIPP", treatment: .taxDeferred),
                AccountSuggestion(name: "Workplace pension", treatment: .taxDeferred)
            ]
        case .australia:
            return [
                AccountSuggestion(name: "Brokerage", treatment: .taxable),
                // Super is concessionally taxed rather than untaxed — 15% on
                // earnings in accumulation, tax-free in pension phase. Filed as
                // tax-deferred because that is the closer of the three, and the
                // note says "taxed when you withdraw" rather than "not taxed".
                AccountSuggestion(name: "Superannuation", treatment: .taxDeferred)
            ]
        case .newZealand:
            return [
                AccountSuggestion(name: "Brokerage", treatment: .taxable),
                AccountSuggestion(name: "KiwiSaver", treatment: .taxDeferred)
            ]
        case .japan:
            return [
                AccountSuggestion(name: "Taxable account", treatment: .taxable),
                AccountSuggestion(name: "NISA", treatment: .taxFree),
                AccountSuggestion(name: "iDeCo", treatment: .taxDeferred)
            ]
        case .singapore:
            return [
                AccountSuggestion(name: "Brokerage", treatment: .taxable),
                AccountSuggestion(name: "SRS", treatment: .taxDeferred)
            ]
        case .philippines:
            return [
                AccountSuggestion(name: "Brokerage", treatment: .taxable),
                AccountSuggestion(name: "PERA", treatment: .taxFree)
            ]
        case .india:
            return [
                AccountSuggestion(name: "Demat account", treatment: .taxable),
                AccountSuggestion(name: "PPF", treatment: .taxFree),
                AccountSuggestion(name: "NPS", treatment: .taxDeferred),
                AccountSuggestion(name: "EPF", treatment: .taxDeferred)
            ]
        case .southAfrica:
            return [
                AccountSuggestion(name: "Brokerage", treatment: .taxable),
                AccountSuggestion(name: "Tax-Free Savings Account", treatment: .taxFree),
                AccountSuggestion(name: "Retirement Annuity", treatment: .taxDeferred)
            ]
        case .france:
            return [
                AccountSuggestion(name: "Compte-titres", treatment: .taxable),
                AccountSuggestion(name: "PEA", treatment: .taxFree),
                AccountSuggestion(name: "PER", treatment: .taxDeferred)
            ]
        case .poland:
            return [
                AccountSuggestion(name: "Brokerage", treatment: .taxable),
                AccountSuggestion(name: "IKE", treatment: .taxFree),
                AccountSuggestion(name: "IKZE", treatment: .taxDeferred)
            ]
        case .other:
            return []
        }
    }

    /// Real product names for a treatment, in the reader's own country.
    ///
    /// Derived from `suggestedAccounts` rather than written out separately, so
    /// the chips at the top of the New Account sheet and the descriptions
    /// beside the radio buttons below them can never disagree.
    static func examples(for treatment: TaxTreatment, residency: Residency) -> String {
        let named = suggestedAccounts(residency)
            .filter { $0.treatment == treatment }
            .map(\.name)
            .joined(separator: ", ")
        return named.isEmpty ? treatment.detail : named
    }

    /// Placeholder text for the account-name field, in the user's own vocabulary.
    static func accountNameHint(_ residency: Residency) -> String {
        // The tax-advantaged ones first: a placeholder reading "General
        // account…" teaches nothing, while "Stocks & Shares ISA, SIPP…" tells a
        // UK user immediately that the app speaks their vocabulary.
        let named = suggestedAccounts(residency)
            .sorted { a, b in
                (a.treatment == .taxable ? 1 : 0) < (b.treatment == .taxable ? 1 : 0)
            }
            .prefix(2)
            .map(\.name)
            .joined(separator: ", ")
        return named.isEmpty ? "Name this account…" : "\(named)…"
    }

    /// What the two rate fields should be called for this residency.
    static func marginalRateLabel(_ residency: Residency) -> String {
        residency == .canada ? "Your marginal tax rate" : "Your marginal (ordinary income) rate"
    }

    static func preferentialRateLabel(_ residency: Residency) -> String {
        residency == .canada
            ? "Your eligible-dividend rate"
            : "Your qualified-dividend / long-term gains rate"
    }

    static func preferentialRateHint(_ residency: Residency) -> String {
        residency == .canada
            ? "The rate you pay on dividends from Canadian companies, after the dividend tax "
                + "credit. It is usually well below your marginal rate, and can be negative at "
                + "low incomes. Your province publishes a table of these."
            : "Usually 0%, 15% or 20% depending on your income. It applies to qualified "
                + "dividends and to gains on anything held more than a year."
    }

    /// Shown on every screen carrying a tax figure. Not optional.
    static let disclaimer =
        "These are general estimates, not tax advice. Rules depend on your own circumstances "
        + "and on the residency paperwork your broker holds. Check with a tax professional "
        + "before acting on anything here."
}
