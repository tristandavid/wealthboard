import Foundation

/// The one portfolio format both apps can read.
///
/// ## Why a third format rather than one app adopting the other's
///
/// The two native shapes are not convertible. iOS identifies everything by
/// `UUID`; Android's Room entities use auto-incrementing `Long`s. Neither can
/// be produced from the other, so a backup written on one phone names rows the
/// other cannot resolve — which is why cloud sync appeared to work and then
/// restored nothing.
///
/// This carries NATURAL keys instead: an account is its display name, a holding
/// is its account plus its security key (the ticker, or the name where there is
/// no ticker — the same key the portfolio screen already groups on). Each app
/// rebuilds its own identifiers on import. Nothing in the file depends on how
/// either app stores a row.
///
/// ## Why it is written alongside the native backup, not instead of it
///
/// A format that has never round-tripped against real data is not something to
/// put between someone and their only copy. Both apps keep writing their own
/// backup exactly as before and keep reading it first; this is written beside
/// it and read only when it is NEWER, so a fault here costs a cross-device
/// restore and never the local one. Remove that belt once it has been proven
/// against a real portfolio on both platforms — not before.
///
/// ## Decoding is deliberately forgiving
///
/// A row that cannot be read is skipped and counted rather than failing the
/// whole restore. `ImportSummary` carries those counts to the screen, because
/// the failure this format must never have is the silent one — a restore that
/// reports success having quietly dropped half the holdings.
enum PortfolioInterchange {

    /// Bumped only for a change that an older reader could misread. Adding a
    /// field an old build ignores does not need it.
    static let schemaVersion = 1

    static let documentName = "interchange"

    // MARK: - Encoding

    /// The portfolio as a dictionary ready for Firestore or a JSON file.
    ///
    /// `writtenAt` defaults to "now" for a caller that doesn't care (the file
    /// export, where restore always tries the native shape first and this
    /// timestamp is never compared against anything). Cloud backup passes its
    /// own timestamp explicitly instead — see FirestoreSyncService.backup for
    /// why: this used to stamp its own fresh `Date()` internally, a few
    /// milliseconds AFTER the native document's timestamp since it is always
    /// written second in the same backup call, which made restore read every
    /// same-device backup as "the interchange copy is newer" and silently
    /// prefer the lossier natural-key reconstruction (fresh UUIDs, no
    /// per-holding notes/order/etc.) over the exact native document.
    static func encode(_ document: PortfolioDocument, baseCurrency: String, writtenAt: Date = Date()) -> [String: Any] {
        // Native identifiers resolved to natural keys once, up front.
        //
        // The keys have to be UNIQUE, and a display name is not. Two accounts
        // both called "TFSA", or the same ticker held twice in one account, are
        // things the app allows and a reader keyed on names cannot tell apart —
        // it would merge them, and merging two positions into one silently
        // loses a holding. So a repeat is given a suffix here, at the only
        // point that can still see which row is which.
        var accountName: [UUID: String] = [:]
        var usedAccountNames: Set<String> = []
        for account in document.accounts {
            // A blank name would decode to nil and take every holding under it
            // down with it.
            let base = account.displayName.trimmingCharacters(in: .whitespacesAndNewlines)
            var name = base.isEmpty ? "Account" : base
            // Visible on purpose: the account really is called something
            // slightly different after a restore, and a renamed account is a
            // far better outcome than a missing one.
            var attempt = 2
            while usedAccountNames.contains(name) {
                name = "\(base.isEmpty ? "Account" : base) (\(attempt))"
                attempt += 1
            }
            usedAccountNames.insert(name)
            accountName[account.id] = name
        }

        var holdingKey: [UUID: (account: String, security: String)] = [:]
        var usedSecurityKeys: Set<String> = []
        for holding in document.holdings {
            let account = accountName[holding.accountId] ?? "Account"
            let base = holding.securityKey
            var security = base
            // NOT visible: `security` is only a key here — the holding carries
            // its own name and ticker — so the suffix never reaches a screen.
            var attempt = 2
            while usedSecurityKeys.contains(key(account, security)) {
                security = "\(base)#\(attempt)"
                attempt += 1
            }
            usedSecurityKeys.insert(key(account, security))
            holdingKey[holding.id] = (account, security)
        }

        var accounts: [[String: Any]] = []
        for account in document.accounts {
            var row: [String: Any] = ["name": accountName[account.id] ?? "Account"]
            if let treatment = account.taxTreatment { row["taxTreatment"] = treatment.rawValue }
            accounts.append(row)
        }

        var holdings: [[String: Any]] = []
        for holding in document.holdings {
            let key = holdingKey[holding.id] ?? ("Account", holding.securityKey)
            var row: [String: Any] = [
                "account": key.account,
                "name": holding.name,
                "security": key.security,
                "type": holding.type.rawValue,
                "units": holding.units,
                "currency": holding.normalizedCurrency,
                "createdAt": millis(holding.createdAt)
            ]
            if let ticker = holding.ticker { row["ticker"] = ticker }
            if let manualPrice = holding.manualPrice { row["manualPrice"] = manualPrice }
            if let costBasis = holding.costBasis { row["costBasis"] = costBasis }
            holdings.append(row)
        }

        var transactions: [[String: Any]] = []
        for transaction in document.transactions {
            guard let key = holdingKey[transaction.holdingId] else { continue }
            var row: [String: Any] = [
                "account": key.account,
                "security": key.security,
                "type": transaction.type.rawValue,
                "at": millis(transaction.at),
                "shares": transaction.shares,
                "pricePerShare": transaction.pricePerShare,
                "currency": transaction.currency
            ]
            if let note = transaction.note { row["note"] = note }
            transactions.append(row)
        }

        var dividends: [[String: Any]] = []
        for dividend in document.dividends {
            guard let key = holdingKey[dividend.holdingId] else { continue }
            var row: [String: Any] = [
                "account": key.account,
                "security": key.security,
                "paidAt": millis(dividend.paidAt),
                "amount": dividend.amount,
                "currency": dividend.normalizedCurrency
            ]
            if let perUnit = dividend.perUnit { row["perUnit"] = perUnit }
            if let note = dividend.note { row["note"] = note }
            dividends.append(row)
        }

        var watchlist: [[String: Any]] = []
        for item in document.watchlist {
            var row: [String: Any] = ["ticker": item.ticker, "addedAt": millis(item.addedAt)]
            if let name = item.customName { row["customName"] = name }
            watchlist.append(row)
        }

        // Price snapshots are deliberately left out. They are a local cache the
        // app rebuilds from the network, they are by far the largest part of
        // the document, and a restore that carries them across adds nothing a
        // refresh would not.
        return [
            "schema": schemaVersion,
            "writtenAt": millis(writtenAt),
            "writtenBy": "ios",
            "baseCurrency": baseCurrency,
            "accounts": accounts,
            "holdings": holdings,
            "transactions": transactions,
            "dividends": dividends,
            "watchlist": watchlist
        ]
    }

    // MARK: - Decoding

    /// What a restore actually brought in, and what it could not.
    ///
    /// Shown to the user. A restore that says "done" having dropped rows is
    /// the one outcome this format exists to prevent.
    struct ImportSummary: Equatable {
        var accounts = 0
        var holdings = 0
        var transactions = 0
        var dividends = 0
        var watchlist = 0
        var skipped = 0

        var isEmpty: Bool { accounts + holdings + transactions + dividends + watchlist == 0 }

        var describedForUser: String {
            var parts: [String] = []
            if accounts > 0 { parts.append("\(accounts) account\(accounts == 1 ? "" : "s")") }
            if holdings > 0 { parts.append("\(holdings) holding\(holdings == 1 ? "" : "s")") }
            if transactions > 0 { parts.append("\(transactions) transaction\(transactions == 1 ? "" : "s")") }
            if dividends > 0 { parts.append("\(dividends) dividend\(dividends == 1 ? "" : "s")") }
            if watchlist > 0 { parts.append("\(watchlist) watchlist item\(watchlist == 1 ? "" : "s")") }
            let restored = parts.isEmpty ? "Nothing" : parts.joined(separator: ", ")
            guard skipped > 0 else { return "Restored \(restored)." }
            return "Restored \(restored). \(skipped) row\(skipped == 1 ? "" : "s") couldn't be read and were skipped."
        }
    }

    /// Rebuilds a document, minting fresh identifiers and relinking by key.
    ///
    /// Returns nil only when the payload is not this format at all. A payload
    /// that IS this format but partly unreadable comes back with whatever could
    /// be read and a count of what could not.
    static func decode(_ raw: [String: Any]) -> (document: PortfolioDocument, summary: ImportSummary)? {
        guard let schema = intValue(raw["schema"]), schema >= 1 else { return nil }

        var document = PortfolioDocument()
        var summary = ImportSummary()

        // Accounts first: everything else is keyed on their names.
        var accountId: [String: UUID] = [:]
        for row in rows(raw["accounts"]) {
            guard let name = stringValue(row["name"]), !name.isEmpty else { summary.skipped += 1; continue }
            if accountId[name] != nil { continue }
            let account = Account(
                displayName: name,
                taxTreatment: stringValue(row["taxTreatment"]).flatMap(TaxTreatment.init(rawValue:))
            )
            accountId[name] = account.id
            document.accounts.append(account)
            summary.accounts += 1
        }

        var holdingId: [String: UUID] = [:]
        for row in rows(raw["holdings"]) {
            guard let accountName = stringValue(row["account"]),
                  let owner = accountId[accountName],
                  let name = stringValue(row["name"]),
                  let units = doubleValue(row["units"]) else { summary.skipped += 1; continue }

            let ticker = stringValue(row["ticker"])
            let security = stringValue(row["security"])
                ?? ticker?.uppercased()
                ?? name
            let holding = Holding(
                accountId: owner,
                name: name,
                ticker: ticker,
                type: stringValue(row["type"]).flatMap(HoldingType.init(rawValue:)) ?? .other,
                units: units,
                manualPrice: doubleValue(row["manualPrice"]),
                currency: stringValue(row["currency"]) ?? "CAD",
                costBasis: doubleValue(row["costBasis"]),
                createdAt: date(row["createdAt"]) ?? Date()
            )
            holdingId[key(accountName, security)] = holding.id
            document.holdings.append(holding)
            summary.holdings += 1
        }

        for row in rows(raw["transactions"]) {
            guard let accountName = stringValue(row["account"]),
                  let security = stringValue(row["security"]),
                  let owner = holdingId[key(accountName, security)],
                  let shares = doubleValue(row["shares"]),
                  let price = doubleValue(row["pricePerShare"]),
                  let at = date(row["at"]) else { summary.skipped += 1; continue }

            document.transactions.append(
                PortfolioTransaction(
                    holdingId: owner,
                    type: stringValue(row["type"]).flatMap(TransactionType.init(rawValue:)) ?? .buy,
                    at: at,
                    shares: shares,
                    pricePerShare: price,
                    currency: stringValue(row["currency"]) ?? "CAD",
                    note: stringValue(row["note"])
                )
            )
            summary.transactions += 1
        }

        for row in rows(raw["dividends"]) {
            guard let accountName = stringValue(row["account"]),
                  let security = stringValue(row["security"]),
                  let owner = holdingId[key(accountName, security)],
                  let amount = doubleValue(row["amount"]),
                  let paidAt = date(row["paidAt"]) else { summary.skipped += 1; continue }

            document.dividends.append(
                DividendPayment(
                    holdingId: owner,
                    paidAt: paidAt,
                    amount: amount,
                    perUnit: doubleValue(row["perUnit"]),
                    currency: stringValue(row["currency"]) ?? "CAD",
                    note: stringValue(row["note"])
                )
            )
            summary.dividends += 1
        }

        for row in rows(raw["watchlist"]) {
            guard let ticker = stringValue(row["ticker"]), !ticker.isEmpty else { summary.skipped += 1; continue }
            document.watchlist.append(
                WatchlistItem(
                    ticker: ticker,
                    addedAt: date(row["addedAt"]) ?? Date(),
                    customName: stringValue(row["customName"])
                )
            )
            summary.watchlist += 1
        }

        return (document, summary)
    }

    /// When this payload was written, for deciding whether it is newer than the
    /// native backup beside it.
    static func writtenAt(_ raw: [String: Any]) -> Date? {
        date(raw["writtenAt"])
    }

    // MARK: - Reading loosely typed values
    //
    // Firestore hands numbers back as NSNumber and a JSON file hands them back
    // as Double or Int depending on how they were written, so every read goes
    // through these rather than a cast that works in one path and not the other.

    private static func key(_ account: String, _ security: String) -> String {
        account + "\u{1F}" + security.uppercased()
    }

    private static func millis(_ date: Date) -> Int64 {
        Int64(date.timeIntervalSince1970 * 1000)
    }

    private static func rows(_ value: Any?) -> [[String: Any]] {
        (value as? [[String: Any]]) ?? []
    }

    private static func stringValue(_ value: Any?) -> String? {
        guard let text = value as? String else { return nil }
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }

    private static func doubleValue(_ value: Any?) -> Double? {
        if let number = value as? Double { return number }
        if let number = value as? Int { return Double(number) }
        if let number = value as? NSNumber { return number.doubleValue }
        if let text = value as? String { return Double(text) }
        return nil
    }

    private static func intValue(_ value: Any?) -> Int? {
        if let number = value as? Int { return number }
        if let number = value as? NSNumber { return number.intValue }
        if let text = value as? String { return Int(text) }
        return nil
    }

    private static func date(_ value: Any?) -> Date? {
        guard let ms = doubleValue(value), ms > 0 else { return nil }
        return Date(timeIntervalSince1970: ms / 1000)
    }
}
