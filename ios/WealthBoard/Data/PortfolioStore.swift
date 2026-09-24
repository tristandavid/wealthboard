import Foundation

/// On-disk persistence for the portfolio document.
///
/// This replaces Room. The Android build needed a relational store because it
/// observed tables independently through DAOs; here the whole document is a few
/// kilobytes of hand-entered data that every screen reads at once, so a single
/// atomic JSON file is both simpler and harder to corrupt — there is no schema
/// to migrate and no partially-written state to reconcile.
///
/// Writes are debounced and atomic (`.atomic` writes to a temp file and renames),
/// so a crash mid-save leaves the previous good document in place rather than a
/// truncated one.
actor PortfolioStore {

    static let shared = PortfolioStore()

    private let filename = "portfolio.json"
    private var cached: PortfolioDocument?
    private var pendingSave: Task<Void, Never>?

    private var fileURL: URL {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        if !FileManager.default.fileExists(atPath: dir.path) {
            try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        }
        return dir.appendingPathComponent(filename)
    }

    private var encoder: JSONEncoder {
        let e = JSONEncoder()
        e.dateEncodingStrategy = .millisecondsSince1970
        e.outputFormatting = [.sortedKeys]
        return e
    }

    private var decoder: JSONDecoder {
        let d = JSONDecoder()
        d.dateDecodingStrategy = .millisecondsSince1970
        return d
    }

    // MARK: - Reading

    func load() -> PortfolioDocument {
        if let cached { return cached }

        // No file at all is a first launch: an empty document is the right
        // answer, and caching it is safe because there is nothing to lose.
        guard let data = try? Data(contentsOf: fileURL) else {
            let fresh = PortfolioDocument()
            cached = fresh
            return fresh
        }

        if let doc = try? decoder.decode(PortfolioDocument.self, from: data) {
            cached = doc
            return doc
        }

        // A file that EXISTS but will not decode is the dangerous case, and it
        // used to be handled identically to the one above: cache an empty
        // document and carry on. That is data loss with extra steps — the
        // empty document becomes `cached`, the next `scheduleSave()` writes it
        // over the only copy of a hand-entered portfolio, and the real data is
        // gone for good. One unreadable byte, one field added to a model
        // without tolerant decoding, and every holding the user ever typed is
        // replaced with nothing.
        //
        // So: do NOT cache, and set the tripwire that stops writes. The
        // in-memory document stays empty for this launch (there is nothing to
        // show), but the file on disk is left exactly as it is, which means a
        // later build — or a fixed decoder — can still read it. `loadFailed`
        // is what `writeNow()` checks before overwriting anything.
        loadFailed = true
        return PortfolioDocument()
    }

    /// True when the document on disk exists but could not be decoded.
    ///
    /// While this is set, nothing is written: see `writeNow()`. It is not
    /// reset by a later successful `mutate`, because a mutate operates on the
    /// empty document this returned — saving that is precisely the overwrite
    /// being prevented. Only `replace(with:)` (a deliberate restore) clears it.
    private(set) var loadFailed = false

    // MARK: - Writing

    /// Applies a change to the document and schedules a save.
    ///
    /// The mutation runs inside the actor, so two screens editing at once can't
    /// interleave a read-modify-write and lose one of the edits — which is the
    /// failure a plain "load, change, save" from the view model would allow.
    @discardableResult
    func mutate<T>(_ body: (inout PortfolioDocument) -> T) -> T {
        var doc = load()
        let result = body(&doc)
        cached = doc
        scheduleSave()
        return result
    }

    /// Replaces the whole document — used by restore-from-backup.
    ///
    /// The one operation allowed to clear `loadFailed`: a restore is the user
    /// deliberately saying "this is my data now", which is exactly the case
    /// where overwriting an unreadable file is correct rather than
    /// catastrophic.
    func replace(with doc: PortfolioDocument) {
        cached = doc
        loadFailed = false
        scheduleSave()
    }

    private func scheduleSave() {
        pendingSave?.cancel()
        pendingSave = Task { [weak self] in
            // Coalesce a burst of edits (a restore writes hundreds of rows)
            // into one write rather than one per row.
            try? await Task.sleep(nanoseconds: 300_000_000)
            guard !Task.isCancelled else { return }
            await self?.writeNow()
        }
    }

    /// Forces an immediate write — called when the app is backgrounded, where
    /// waiting out the debounce could mean losing the last edit.
    func flush() {
        pendingSave?.cancel()
        pendingSave = nil
        writeNow()
    }

    private func writeNow() {
        // The tripwire. A document that failed to decode is still sitting on
        // disk, intact; writing over it with whatever is in memory would
        // destroy the only copy. See `load()`.
        guard !loadFailed else { return }
        guard let cached else { return }
        guard let data = try? encoder.encode(cached) else { return }
        try? data.write(to: fileURL, options: [.atomic])
    }
}

// MARK: - Lightweight preferences
//
// The Android build kept a dozen scalar settings in SharedPreferences. The
// direct equivalent is UserDefaults; this wrapper exists so the keys live in
// one place instead of being spelled out at each call site, which is how two
// screens end up reading different keys for the same setting.

enum Prefs {
    private static let defaults = UserDefaults.standard

    enum Key {
        static let baseCurrency   = "base_currency"
        static let themeMode      = "theme_mode"
        static let homeCountry    = "home_country"
        static let dividendGoal   = "dividend_goal_monthly"
        static let residency      = "tax_residency"
        static let residencyAuto  = "tax_residency_auto"
        static let residencyFromAccounts = "tax_residency_from_accounts"
        static let marginalRate   = "tax_marginal_rate"
        static let preferredRate  = "tax_preferential_rate"
        static let dripEnabled    = "drip_enabled"
        static let fxPrefix       = "fx_"
        static let closurePrefix  = "closure_dismissed_"
        static let didSeedWatch   = "did_seed_watchlist"
    }

    static func string(_ key: String) -> String? { defaults.string(forKey: key) }
    static func set(_ value: String?, _ key: String) { defaults.set(value, forKey: key) }

    static func double(_ key: String, default fallback: Double) -> Double {
        defaults.object(forKey: key) == nil ? fallback : defaults.double(forKey: key)
    }
    static func set(_ value: Double, _ key: String) { defaults.set(value, forKey: key) }

    static func bool(_ key: String, default fallback: Bool = false) -> Bool {
        defaults.object(forKey: key) == nil ? fallback : defaults.bool(forKey: key)
    }
    static func set(_ value: Bool, _ key: String) { defaults.set(value, forKey: key) }

    static func allKeys(withPrefix prefix: String) -> [String] {
        defaults.dictionaryRepresentation().keys.filter { $0.hasPrefix(prefix) }
    }
}
