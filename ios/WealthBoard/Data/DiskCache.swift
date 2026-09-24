import Foundation

/// A small on-disk cache for things that are expensive to fetch and cheap to
/// keep: news headlines, scraped dividend calendars.
///
/// ## Why not the portfolio document
///
/// `PortfolioStore` holds what the user typed — accounts, holdings,
/// transactions, dividends. It is a few kilobytes, it is written atomically,
/// and it is re-saved on every edit. Putting a hundred kilobytes of news
/// headlines in it would mean rewriting all of that every time someone changed
/// a unit count, and would mix data the user would be upset to lose with data
/// that can be thrown away at any moment.
///
/// So each cache gets its own file, in the caches directory rather than
/// application support. That is the honest location: iOS is free to delete it
/// under storage pressure, which is exactly the right outcome for something
/// that can be re-fetched. Nothing here is load-bearing.
///
/// ## Staleness is the caller's decision
///
/// `load` hands back the value AND its age, rather than deciding for itself
/// whether the value is too old. A cold launch wants to paint whatever it has,
/// however old; a pull-to-refresh wants to skip the cache entirely; a
/// background refresh wants to know if it should bother. One store, three
/// policies, so the age travels with the value and the caller picks.
actor DiskCache {

    /// One cached value and when it was written.
    struct Entry<Value: Codable>: Codable {
        let value: Value
        let storedAt: Date

        var age: TimeInterval { Date().timeIntervalSince(storedAt) }

        /// True when the entry is older than `ttl`. A caller that does not
        /// care about age simply ignores this.
        func isStale(after ttl: TimeInterval) -> Bool { age > ttl }
    }

    static let shared = DiskCache()

    /// Named caches, so callers refer to a file by a value rather than a
    /// string literal in three places.
    enum Key: String {
        case marketNews = "market-news"
        case dividendNews = "dividend-news"
        case dividendCalendars = "dividend-calendars"
    }

    private var directory: URL {
        let base = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        let dir = base.appendingPathComponent("WealthBoardCache", isDirectory: true)
        if !FileManager.default.fileExists(atPath: dir.path) {
            try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        }
        return dir
    }

    private func url(for key: Key) -> URL {
        directory.appendingPathComponent("\(key.rawValue).json")
    }

    private var encoder: JSONEncoder {
        let e = JSONEncoder()
        e.dateEncodingStrategy = .millisecondsSince1970
        return e
    }

    private var decoder: JSONDecoder {
        let d = JSONDecoder()
        d.dateDecodingStrategy = .millisecondsSince1970
        return d
    }

    // MARK: - Reading and writing

    /// The cached value and its age, or nil when there is nothing usable.
    ///
    /// A decode failure is treated as a miss rather than an error: the shape on
    /// disk was written by an older build, and a cache that cannot be read is
    /// simply a cache that is empty.
    func load<Value: Codable>(_ key: Key, as type: Value.Type) -> Entry<Value>? {
        guard let data = try? Data(contentsOf: url(for: key)) else { return nil }
        return try? decoder.decode(Entry<Value>.self, from: data)
    }

    /// Writes atomically, so a crash mid-save leaves the previous good file
    /// rather than a truncated one.
    func save<Value: Codable>(_ key: Key, _ value: Value) {
        guard let data = try? encoder.encode(Entry(value: value, storedAt: Date())) else { return }
        try? data.write(to: url(for: key), options: .atomic)
    }

    func clear(_ key: Key) {
        try? FileManager.default.removeItem(at: url(for: key))
    }

    /// Everything, for a "clear cached data" action or a sign-out.
    func clearAll() {
        for key in [Key.marketNews, .dividendNews, .dividendCalendars] { clear(key) }
    }
}
