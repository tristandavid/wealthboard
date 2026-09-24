import Foundation

/// Turns a publisher's RSS feed into `NewsItem`s.
///
/// Built on `XMLParser` rather than a regex over the markup: feed XML is
/// inconsistent enough (CDATA, entity escapes, namespaced media elements) that
/// pattern-matching it produces headlines with `&amp;#39;` in them.
///
/// Thumbnails come from whichever of the three conventional elements the feed
/// uses — `media:thumbnail`, `media:content` or `enclosure` — and fall back to
/// the first image in the description HTML, which is what several feeds do
/// instead of declaring one.
enum RSSParser {

    static func parse(_ xml: String, category: NewsCategory) -> [NewsItem] {
        guard let data = xml.data(using: .utf8) else { return [] }
        let delegate = FeedDelegate(category: category)
        let parser = XMLParser(data: data)
        parser.delegate = delegate
        parser.shouldProcessNamespaces = false
        guard parser.parse() else { return delegate.items }
        return delegate.items
    }

    // MARK: - Delegate

    private final class FeedDelegate: NSObject, XMLParserDelegate {
        let category: NewsCategory
        var items: [NewsItem] = []

        private var inItem = false
        private var element = ""
        private var buffer = ""

        private var title = ""
        private var link = ""
        private var pubDate = ""
        private var itemDescription = ""
        private var publisher = ""
        private var imageURL: String?
        private var feedTitle = ""

        init(category: NewsCategory) {
            self.category = category
        }

        func parser(
            _ parser: XMLParser,
            didStartElement elementName: String,
            namespaceURI: String?,
            qualifiedName qName: String?,
            attributes attributeDict: [String: String] = [:]
        ) {
            element = elementName
            buffer = ""

            if elementName == "item" || elementName == "entry" {
                inItem = true
                title = ""; link = ""; pubDate = ""; itemDescription = ""
                publisher = ""; imageURL = nil
                return
            }

            guard inItem else { return }

            // Thumbnails are declared as attributes, not text.
            switch elementName {
            case "media:thumbnail", "media:content":
                // `media:content` carries whatever the item has — a story with
                // video attaches an .mp4 here, and taking it as the thumbnail
                // left the row with a URL that can never decode into a picture
                // and so with no picture at all. A declared type is trusted;
                // an undeclared one is judged by its extension.
                if imageURL == nil, let url = attributeDict["url"] {
                    let type = (attributeDict["type"] ?? "").lowercased()
                    let medium = (attributeDict["medium"] ?? "").lowercased()
                    let looksLikeImage = type.hasPrefix("image")
                        || medium == "image"
                        || (type.isEmpty && medium.isEmpty && Self.hasImageExtension(url))
                    if looksLikeImage { imageURL = url }
                }
            case "enclosure":
                if imageURL == nil,
                   let url = attributeDict["url"],
                   (attributeDict["type"] ?? "").hasPrefix("image") {
                    imageURL = url
                }
            case "link":
                // Atom puts the link in an href attribute rather than in text.
                if link.isEmpty, let href = attributeDict["href"] { link = href }
            default:
                break
            }
        }

        func parser(_ parser: XMLParser, foundCharacters string: String) {
            buffer += string
        }

        func parser(_ parser: XMLParser, foundCDATA CDATABlock: Data) {
            buffer += String(data: CDATABlock, encoding: .utf8) ?? ""
        }

        func parser(
            _ parser: XMLParser,
            didEndElement elementName: String,
            namespaceURI: String?,
            qualifiedName qName: String?
        ) {
            let text = buffer.trimmingCharacters(in: .whitespacesAndNewlines)
            buffer = ""

            guard inItem else {
                // The channel's own title names the publisher, which most items
                // don't carry themselves.
                if elementName == "title", feedTitle.isEmpty { feedTitle = text }
                return
            }

            switch elementName {
            case "title": if title.isEmpty { title = text }
            case "link": if link.isEmpty { link = text }
            case "pubDate", "published", "updated", "dc:date": if pubDate.isEmpty { pubDate = text }
            case "description", "summary", "content:encoded":
                if itemDescription.isEmpty { itemDescription = text }
            case "source", "dc:creator": if publisher.isEmpty { publisher = text }
            case "item", "entry":
                inItem = false
                appendItem()
            default:
                break
            }
        }

        private func appendItem() {
            let cleanTitle = decodeEntities(stripHTML(title))
            guard !cleanTitle.isEmpty, !link.isEmpty else { return }

            let summary = decodeEntities(stripHTML(itemDescription))
            let image = imageURL ?? firstImage(in: itemDescription)

            items.append(NewsItem(
                title: cleanTitle,
                publisher: publisher.isEmpty ? feedTitle : publisher,
                linkURL: link,
                publishedAt: parseDate(pubDate) ?? Date(),
                imageURL: image.flatMap(normalizeImageURL),
                summary: summary.isEmpty ? nil : String(summary.prefix(280)),
                category: category
            ))
        }

        // MARK: - Text cleaning

        private func stripHTML(_ raw: String) -> String {
            var out = ""
            var inTag = false
            for ch in raw {
                if ch == "<" { inTag = true; continue }
                if ch == ">" { inTag = false; out.append(" "); continue }
                if !inTag { out.append(ch) }
            }
            return out
                .replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
                .trimmingCharacters(in: .whitespacesAndNewlines)
        }

        /// Decodes the handful of entities that survive `XMLParser` because they
        /// were double-escaped inside a CDATA block.
        private func decodeEntities(_ raw: String) -> String {
            var out = raw
            let map = [
                "&amp;": "&", "&lt;": "<", "&gt;": ">", "&quot;": "\"",
                "&#39;": "'", "&apos;": "'", "&nbsp;": " ", "&#8217;": "\u{2019}",
                "&#8216;": "\u{2018}", "&#8220;": "\u{201C}", "&#8221;": "\u{201D}",
                "&#8211;": "–", "&#8212;": "—"
            ]
            for (entity, replacement) in map {
                out = out.replacingOccurrences(of: entity, with: replacement)
            }
            return out
        }

        /// Whether a URL's path ends in something a decoder will recognise.
        /// Query strings are common on CDN URLs, so the extension is looked for
        /// before the "?" rather than at the end of the string.
        static func hasImageExtension(_ url: String) -> Bool {
            var path: String = url
            if let mark = path.firstIndex(of: "?") { path = String(path[path.startIndex..<mark]) }
            let lower: String = path.lowercased()
            let extensions: [String] = [".jpg", ".jpeg", ".png", ".gif", ".webp", ".avif", ".heic"]
            for candidate in extensions where lower.hasSuffix(candidate) { return true }
            return false
        }

        private func firstImage(in html: String) -> String? {
            guard let range = html.range(of: "<img[^>]+src=[\"']([^\"']+)[\"']",
                                         options: .regularExpression) else { return nil }
            let fragment = String(html[range])
            guard let srcRange = fragment.range(of: "[\"'][^\"']+[\"']$",
                                                options: .regularExpression) else { return nil }
            return String(fragment[srcRange]).trimmingCharacters(in: CharacterSet(charactersIn: "\"'"))
        }

        /// Upgrades protocol-relative and bare-http image URLs, which iOS will
        /// otherwise refuse to load under App Transport Security.
        private func normalizeImageURL(_ raw: String) -> String? {
            var url = raw.trimmingCharacters(in: .whitespaces)
            if url.isEmpty { return nil }
            if url.hasPrefix("//") { url = "https:" + url }
            if url.hasPrefix("http://") { url = "https://" + url.dropFirst("http://".count) }
            return url.hasPrefix("https://") ? url : nil
        }

        // MARK: - Dates

        private static let formatters: [DateFormatter] = {
            let patterns = [
                "EEE, dd MMM yyyy HH:mm:ss Z",
                "EEE, dd MMM yyyy HH:mm:ss zzz",
                "yyyy-MM-dd'T'HH:mm:ssZ",
                "yyyy-MM-dd'T'HH:mm:ss.SSSZ",
                "yyyy-MM-dd HH:mm:ss"
            ]
            return patterns.map { pattern in
                let f = DateFormatter()
                f.locale = Locale(identifier: "en_US_POSIX")
                f.timeZone = TimeZone(identifier: "UTC")
                f.dateFormat = pattern
                return f
            }
        }()

        private func parseDate(_ raw: String) -> Date? {
            let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !trimmed.isEmpty else { return nil }
            for formatter in FeedDelegate.formatters {
                if let date = formatter.date(from: trimmed) { return date }
            }
            return nil
        }
    }
}
