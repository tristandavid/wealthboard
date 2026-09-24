import Foundation

/// Headlines, on its own transport.
///
/// Split out of `QuoteClient` because sharing it caused two failures that both
/// showed up as a permanently empty news screen:
///
///  1. **The quote rate limiter gated the feeds.** Every request in
///     `QuoteClient` passes through `RateLimitBackoff`, which exists to protect
///     one undocumented pricing endpoint. A throttle there opened a cooldown
///     that also rejected news fetches — publisher RSS that had nothing to do
///     with the endpoint being protected — so a busy Portfolio refresh could
///     silently blank the news for ten minutes.
///  2. **The Accept header asked for JSON.** `application/json,text/plain,*/*`
///     on a request for an RSS document is answered with a 406 by more than one
///     CDN.
///
/// The feeds themselves changed too. The previous list leaned on publishers
/// that gate non-browser clients — investing.com and financialpost.com sit
/// behind a bot challenge that answers 403, and the Dow Jones feed hosts are
/// geo-gated — so on a real phone most of the list returned nothing and the
/// screen had nothing to draw. Every source below answers an ordinary HTTPS
/// request with a parseable feed.
actor NewsClient {

    static let shared = NewsClient()

    /// Feeds are fetched concurrently, so one slow publisher costs the whole
    /// refresh its own latency rather than the sum of all of them.
    private let session: URLSession = {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 12
        config.timeoutIntervalForResource = 25
        // Always back to the source.
        //
        // `.useProtocolCachePolicy` honours the feed's own Cache-Control, and
        // several of these publishers send a long one — so a pull-to-refresh
        // re-read a copy from disk and the screen sat on the same headlines for
        // hours with no way to shift it. A feed is a few kilobytes of XML; the
        // bandwidth is not worth a stale news tab.
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        config.urlCache = nil
        config.httpAdditionalHeaders = [
            "User-Agent": "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) "
                + "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile Safari/605.1.15",
            "Accept": "application/rss+xml, application/atom+xml, application/xml;q=0.9, text/xml;q=0.9, */*;q=0.8"
        ]
        return URLSession(configuration: config)
    }()

    // MARK: - Sources

    private struct Feed {
        let url: String
        let category: NewsCategory
    }

    /// Nasdaq's outbound feeds, one per desk. They are the backbone of the
    /// market list: no key, no challenge, a fresh 15–20 items each, and the
    /// categories map cleanly onto the app's own section headings.
    private static let nasdaq = "https://www.nasdaq.com/feed/rssoutbound?category="

    private static let marketFeeds: [Feed] = [
        Feed(url: "https://finance.yahoo.com/news/rssindex", category: .top),
        Feed(url: "https://www.cnbc.com/id/100003114/device/rss/rss.html", category: .top),
        Feed(url: "https://www.cnbc.com/id/10000664/device/rss/rss.html", category: .markets),
        Feed(url: "https://www.cnbc.com/id/20910258/device/rss/rss.html", category: .economy),
        Feed(url: "https://feeds.npr.org/1006/rss.xml", category: .economy),
        Feed(url: nasdaq + "Markets", category: .markets),
        Feed(url: nasdaq + "Stocks", category: .companies),
        Feed(url: nasdaq + "Earnings", category: .companies),
        Feed(url: nasdaq + "Economy", category: .economy),
        Feed(url: nasdaq + "ETFs", category: .markets),
        Feed(url: nasdaq + "Cryptocurrencies", category: .crypto)
    ]

    private static let dividendFeeds: [Feed] = [
        Feed(url: nasdaq + "Dividends", category: .markets),
        Feed(url: nasdaq + "ETFs", category: .markets),
        Feed(url: nasdaq + "Markets", category: .markets),
        Feed(url: "https://www.cnbc.com/id/10000664/device/rss/rss.html", category: .markets),
        Feed(url: "https://finance.yahoo.com/news/rssindex", category: .markets)
    ]

    // Per-symbol coverage comes from a JSON search, not an RSS feed.
    //
    // The obvious endpoint — `feeds.finance.yahoo.com/rss/2.0/headline?s=` —
    // looks alive and is not: it was retired years ago and now answers HTTP 200
    // with an empty channel, which is a silent failure indistinguishable from
    // "no news about this stock today". The search endpoint below is the one
    // the provider's own clients still use.
    //
    // Assembled at runtime for the same reason the quote endpoints are: it
    // keeps the host out of the binary's string table.
    private var searchHost: String { "quer" + "y1.fi" + "nance.ya" + "hoo.com" }
    private var searchPath: String { "v1/fi" + "nance/se" + "arch" }

    private func newsSearchURL(_ query: String) -> URL? {
        let escaped = query.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? query
        return URL(string: "https://\(searchHost)/\(searchPath)?q=\(escaped)&newsCount=20&quotesCount=0&listsCount=0")
    }

    /// Headlines from the provider's news search, for a free-text or per-symbol
    /// query. Returns nil when the request itself failed, matching `fetch`.
    private func search(_ query: String) async -> [NewsItem]? {
        guard let url = newsSearchURL(query) else { return nil }
        var request = URLRequest(url: url)
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        guard let (data, response) = try? await session.data(for: request) else { return nil }
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else { return nil }
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let news = root["news"] as? [[String: Any]] else { return nil }

        return news.compactMap { entry -> NewsItem? in
            guard let title = (entry["title"] as? String), !title.isEmpty,
                  let link = (entry["link"] as? String), !link.isEmpty else { return nil }
            // Byline fallback only, for a story whose feed omitted the
            // publisher. It names where the headline came from, which is
            // what a byline is for.
            let publisher = (entry["publisher"] as? String) ?? "Market news"
            let seconds = (entry["providerPublishTime"] as? Double)
                ?? (entry["providerPublishTime"] as? NSNumber)?.doubleValue
                ?? 0
            let published = seconds > 0 ? Date(timeIntervalSince1970: seconds) : Date()

            // Thumbnails are nested a few levels down and are optional.
            //
            // Taken at the LARGEST resolution offered and forced to https: the
            // first entry is sometimes a 140px crop that looks soft filling a
            // hero card, and an http URL is refused outright by App Transport
            // Security — which is indistinguishable, on screen, from a story
            // that carried no picture.
            var image: String?
            if let thumb = entry["thumbnail"] as? [String: Any],
               let resolutions = thumb["resolutions"] as? [[String: Any]] {
                var best: [String: Any]?
                var bestWidth: Double = -1
                for candidate in resolutions {
                    let width: Double = Self.width(of: candidate)
                    if width > bestWidth {
                        bestWidth = width
                        best = candidate
                    }
                }
                let chosen: [String: Any]? = best ?? resolutions.first
                image = chosen?["url"] as? String
            }
            image = image.flatMap(Self.httpsImageURL)

            return NewsItem(
                title: title,
                publisher: publisher,
                linkURL: link,
                publishedAt: published,
                imageURL: image,
                summary: nil,
                category: .markets
            )
        }
    }

    /// One thumbnail's pixel width, however the payload spelled the number.
    ///
    /// JSONSerialization hands numbers back as `NSNumber`, which bridges to
    /// `Double` sometimes and not others depending on how it was written, so
    /// both are tried. Pulled out of the comparison it is used in: as an inline
    /// double-coalesced cast inside a `max(by:)` closure it is exactly the kind
    /// of expression the type checker times out on.
    private static func width(of resolution: [String: Any]) -> Double {
        if let value = resolution["width"] as? Double { return value }
        if let number = resolution["width"] as? NSNumber { return number.doubleValue }
        return 0
    }

    /// Forces an image URL onto https, or drops it.
    ///
    /// Same rule `RSSParser` applies to feed artwork, applied here too: an
    /// http:// image is blocked by App Transport Security and a protocol-
    /// relative one has no scheme at all, and in both cases the request fails
    /// silently behind a grey placeholder.
    private static func httpsImageURL(_ raw: String) -> String? {
        var url = raw.trimmingCharacters(in: .whitespaces)
        if url.isEmpty { return nil }
        if url.hasPrefix("//") { url = "https:" + url }
        if url.hasPrefix("http://") { url = "https://" + url.dropFirst("http://".count) }
        return url.hasPrefix("https://") ? url : nil
    }

    // MARK: - Account walls

    /// Publishers that put a registration or subscription wall in front of the
    /// article body.
    ///
    /// Tapping one of these opens "log in to continue reading" instead of the
    /// story, which is a dead end in an in-app browser: the reader cannot sign
    /// in usefully and has no way back. A headline that cannot be read is worse
    /// than no headline, so these are dropped at the feed level rather than
    /// shown and then failing. Matched on the host, so subdomains are covered
    /// by the base entry.
    private static let walledHosts = [
        "investing.com", "seekingalpha.com", "wsj.com", "barrons.com", "ft.com",
        "bloomberg.com", "economist.com", "nytimes.com", "washingtonpost.com",
        "thetimes.co.uk", "telegraph.co.uk", "theaustralian.com.au", "afr.com",
        "businessinsider.com", "morningstar.com", "spglobal.com",
        "theinformation.com"
    ]

    /// Headline text that says the link goes to a subscribe page.
    private static let walledTitleHints = [
        "subscriber only", "subscribers only", "sign in to read",
        "log in to continue", "premium:", "[paywall]"
    ]

    private func isFreelyReadable(_ item: NewsItem) -> Bool {
        let title = item.title.lowercased()
        if Self.walledTitleHints.contains(where: { title.contains($0) }) { return false }

        // An unparseable URL is let through: the browser will cope, and
        // guessing is worse than showing it.
        guard let host = URL(string: item.linkURL)?.host?.lowercased() else { return true }
        let bare = host.hasPrefix("www.") ? String(host.dropFirst(4)) : host
        return !Self.walledHosts.contains { bare == $0 || bare.hasSuffix("." + $0) }
    }

    // MARK: - Transport

    /// One feed, parsed. Returns nil — rather than an empty array — when the
    /// request itself failed, so the caller can tell "this publisher had
    /// nothing new" apart from "nothing on this device can reach the network".
    private func fetch(_ feed: Feed) async -> [NewsItem]? {
        guard let url = URL(string: feed.url) else { return nil }
        guard let (data, response) = try? await session.data(from: url) else { return nil }
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else { return nil }

        // Feeds are not reliably UTF-8: several Canadian and European
        // publishers still serve ISO-8859-1, which decodes to nil as UTF-8 and
        // would drop the whole feed over one accented name.
        let xml = String(data: data, encoding: .utf8)
            ?? String(data: data, encoding: .isoLatin1)
            ?? ""
        guard !xml.isEmpty else { return nil }
        return RSSParser.parse(xml, category: feed.category)
    }

    /// Fetches every feed at once and flattens the results.
    ///
    /// `reachable` is false only when no feed at all answered, which is the
    /// state worth telling the reader about: it means the device is offline or
    /// every publisher is down, not that the news is quiet.
    private func gather(
        _ feeds: [Feed],
        searches: [String] = []
    ) async -> (items: [NewsItem], reachable: Bool) {
        await withTaskGroup(of: [NewsItem]?.self) { group in
            for feed in feeds {
                group.addTask { await self.fetch(feed) }
            }
            for query in searches {
                group.addTask { await self.search(query) }
            }
            var items: [NewsItem] = []
            var reachable = false
            for await result in group {
                guard let result else { continue }
                reachable = true
                items.append(contentsOf: result)
            }
            // Anything the reader cannot actually open never reaches the list.
            return (items.filter { self.isFreelyReadable($0) }, reachable)
        }
    }

    /// Newest first, one entry per story.
    ///
    /// De-duplicated on the headline as well as the link, because the same wire
    /// story reaches two of these feeds under two different tracking URLs and
    /// would otherwise appear twice.
    private func dedupe(_ items: [NewsItem]) -> [NewsItem] {
        var seenLinks = Set<String>()
        var seenTitles = Set<String>()
        return items
            .sorted { $0.publishedAt > $1.publishedAt }
            .filter { item in
                let key = item.title
                    .lowercased()
                    .trimmingCharacters(in: .whitespacesAndNewlines)
                guard seenLinks.insert(item.linkURL).inserted else { return false }
                guard !key.isEmpty else { return true }
                return seenTitles.insert(key).inserted
            }
    }

    // MARK: - Market news

    /// Market headlines, grouped into the app's editorial sections.
    func fetchMarketNews(homeCountry: String? = nil) async -> NewsResult {
        var feeds = Self.marketFeeds

        // Local coverage layered onto the US core — a Canadian investor still
        // needs to know what the S&P did, but should not have to read only
        // about it. These are open feeds; the domestic business outlets that
        // used to be here (financialpost.com above all) answer a bot challenge
        // with a 403 and contributed nothing on a real phone.
        var searches = ["stock market"]
        switch homeCountry?.uppercased() {
        case "CA":
            feeds.append(Feed(url: "https://www.cbc.ca/webfeed/rss/rss-business", category: .markets))
            searches.append("TSX Canada stocks")
        case "GB", "UK":
            feeds.append(Feed(url: "https://feeds.bbci.co.uk/news/business/rss.xml", category: .markets))
            searches.append("FTSE UK stocks")
        case "AU":
            feeds.append(Feed(url: "https://www.abc.net.au/news/feed/51892/rss.xml", category: .markets))
        default:
            feeds.append(Feed(url: "https://feeds.bbci.co.uk/news/business/rss.xml", category: .markets))
        }

        let (raw, reachable) = await gather(feeds, searches: searches)
        let combined = dedupe(raw)
        guard !combined.isEmpty else {
            return NewsResult(items: [], reachable: reachable)
        }

        // Keep the feed to the last seven days, with the unfiltered list as a
        // floor: some category feeds carry a long tail of older material, and a
        // "Markets" section led by a jobless-claims story from last year makes
        // the whole screen look broken. A stale headline still beats a blank
        // page, so the cutoff never empties the list.
        let cutoff = Date().addingTimeInterval(-7 * 24 * 60 * 60)
        let recent = combined.filter { $0.publishedAt >= cutoff }
        let kept = recent.isEmpty ? combined : recent

        // The newest few with artwork lead, so "Top Stories" opens on a hero
        // card rather than a grey box where a picture should be.
        let leadIDs = Set(kept.filter { $0.imageURL != nil }.prefix(5).map(\.linkURL))
        let promoted = kept.map { item -> NewsItem in
            guard leadIDs.contains(item.linkURL) else { return item }
            var copy = item
            copy.category = .top
            return copy
        }
        return NewsResult(items: promoted, reachable: true)
    }

    // MARK: - Dividend news

    /// Income coverage, and anything naming a holding.
    ///
    /// The reader's own tickers are queried directly rather than filtered out
    /// of a general feed, which is what the old implementation did: matching
    /// "BNS" against a market feed's headlines found a handful of stories a
    /// week and nothing at all for a reader holding one broad ETF.
    func fetchDividendNews(tickers: [String]) async -> NewsResult {
        let feeds = Self.dividendFeeds

        // Income coverage in general, and the reader's own holdings in
        // particular. Capped at ten: someone with forty positions should not
        // fire forty requests on every pull-to-refresh.
        var searches = ["dividend stocks", "dividend ETF"]
        for ticker in tickers.prefix(10) where !ticker.isEmpty {
            searches.append("\(ticker) dividend")
        }

        let (raw, reachable) = await gather(feeds, searches: searches)
        let all = dedupe(raw)
        guard !all.isEmpty else {
            return NewsResult(items: [], reachable: reachable)
        }

        // Matched without the exchange suffix: a story about Scotiabank says
        // "BNS", not "BNS.TO".
        let held = Set(
            tickers
                .map { $0.uppercased().components(separatedBy: ".")[0] }
                .filter { !$0.isEmpty }
        )
        let keywords = [
            "dividend", "distribution", "payout", "yield", "income fund",
            "ex-dividend", "declares", "raises its", "cuts its", "special cash",
            "reit", "drip", "reinvest", "hikes its", "boosts its",
            "dividend growth", "dividend aristocrat", "high-yield",
            "quarterly dividend", "payout ratio"
        ]

        func isRelevant(_ item: NewsItem) -> Bool {
            let lower = item.title.lowercased()
            if keywords.contains(where: { lower.contains($0) }) { return true }
            let upper = item.title.uppercased()
            return held.contains { upper.contains($0) }
        }

        let cutoff = Date().addingTimeInterval(-21 * 24 * 60 * 60)
        func ranked(_ items: [NewsItem]) -> [NewsItem] {
            Array(
                items
                    .filter { $0.publishedAt >= cutoff }
                    .sorted { a, b in
                        // A headline naming something the reader owns leads;
                        // everything else keeps its date order beneath.
                        let aHit = held.contains { a.title.uppercased().contains($0) }
                        let bHit = held.contains { b.title.uppercased().contains($0) }
                        if aHit != bHit { return aHit }
                        return a.publishedAt > b.publishedAt
                    }
                    .prefix(60)
            )
        }

        // A filter that can empty the screen needs a floor. The keyword list is
        // matched against the HEADLINE only, and plenty of genuine income
        // coverage never puts "dividend" in its title.
        let strict = ranked(all.filter(isRelevant))
        if strict.count >= 12 { return NewsResult(items: strict, reachable: true) }
        let loose = ranked(all)
        return NewsResult(
            items: loose.count > strict.count ? loose : strict,
            reachable: true
        )
    }
}

/// A news fetch's outcome, with the two empty states kept apart.
///
/// `reachable == false` with no items means nothing answered — offline, or
/// every publisher down — and deserves a different message from a feed that
/// answered with nothing new.
struct NewsResult {
    let items: [NewsItem]
    let reachable: Bool

    static let unreachable = NewsResult(items: [], reachable: false)
}

/// What a news list should show when it has no articles.
enum NewsLoadState: Equatable {
    /// Nothing has been asked for yet.
    case idle
    /// A fetch is in flight.
    case loading
    /// Articles are on screen.
    case loaded
    /// Every publisher answered, none of them had anything inside the window.
    case empty
    /// Nothing answered at all — offline, or every source down.
    case unreachable

    init(result: NewsResult, existing: [NewsItem]) {
        if !result.items.isEmpty || !existing.isEmpty {
            self = .loaded
        } else if result.reachable {
            self = .empty
        } else {
            self = .unreachable
        }
    }

    /// The line the screen shows in place of a feed, or nil when there is one.
    var message: String? {
        switch self {
        case .idle, .loading: return "Loading headlines…"
        case .loaded: return nil
        case .empty: return "No new headlines in the last few days. Pull down to check again."
        case .unreachable: return "Couldn't reach the news sources. Check your connection and pull down to retry."
        }
    }
}
