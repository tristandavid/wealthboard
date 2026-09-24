import SwiftUI
import UIKit

/// A remote image that actually loads on a news CDN.
///
/// `AsyncImage` goes through `URLSession.shared`, which sends URLSession's own
/// user agent and no referer. A newspaper's image CDN treats that as a hotlink
/// and answers 403 or a 1×1 placeholder, so the artwork silently never arrived
/// and every story drew the grey box behind it — which looks exactly like a
/// feed that carries no pictures. Requesting as a browser does, with the
/// article's own host as the referer, is what makes those hosts serve it.
///
/// It also keeps decoded images in memory. `AsyncImage` reloads on every
/// appearance, so scrolling a feed back up re-fetched artwork that was on
/// screen a second earlier.
struct RemoteImage<Placeholder: View>: View {
    let url: URL?
    /// The page the picture belongs to. Sent as the referer, which is the part
    /// a hotlink check actually looks at.
    var referer: String?
    @ViewBuilder var placeholder: () -> Placeholder

    @State private var image: UIImage?
    @State private var failed = false

    var body: some View {
        Group {
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
            } else {
                placeholder()
            }
        }
        // Keyed on the url so a recycled row in a lazy stack re-fetches for its
        // new story instead of keeping the previous one's picture.
        .task(id: url) {
            guard let url, image == nil, !failed else { return }
            if let cached = await ImageLoader.shared.image(for: url, referer: referer) {
                image = cached
            } else {
                failed = true
            }
        }
    }
}

/// Fetches and caches images for `RemoteImage`.
actor ImageLoader {
    static let shared = ImageLoader()

    private let session: URLSession = {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 15
        config.requestCachePolicy = .returnCacheDataElseLoad
        // 64 MB on disk: artwork is the only thing in here and a feed's worth
        // of thumbnails is a few megabytes.
        config.urlCache = URLCache(
            memoryCapacity: 16 * 1024 * 1024,
            diskCapacity: 64 * 1024 * 1024,
            diskPath: "WealthBoardImages"
        )
        config.httpAdditionalHeaders = [
            "User-Agent": "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) "
                + "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile Safari/605.1.15",
            "Accept": "image/avif,image/webp,image/apng,image/*,*/*;q=0.8"
        ]
        return URLSession(configuration: config)
    }()

    private var cache: [URL: UIImage] = [:]
    /// Decoded images are large; this is a count, not a byte budget, because a
    /// feed is a bounded number of thumbnails rather than an open-ended gallery.
    private let limit = 120

    /// In-flight requests, so eight rows appearing at once for the same picture
    /// make one request rather than eight.
    private var inFlight: [URL: Task<UIImage?, Never>] = [:]

    func image(for url: URL, referer: String?) async -> UIImage? {
        if let hit = cache[url] { return hit }
        if let running = inFlight[url] { return await running.value }

        let task = Task<UIImage?, Never> { [session] in
            var request = URLRequest(url: url)
            if let referer, let host = URL(string: referer)?.host {
                request.setValue("https://\(host)/", forHTTPHeaderField: "Referer")
            }
            guard let (data, response) = try? await session.data(for: request) else { return nil }
            let status = (response as? HTTPURLResponse)?.statusCode ?? 200
            guard (200..<300).contains(status) else { return nil }
            // A tracking pixel is a valid image and not a picture. Anything
            // this small is a spacer, and drawing it stretched across a hero
            // card is worse than drawing nothing.
            guard let decoded = UIImage(data: data),
                  decoded.size.width >= 32, decoded.size.height >= 32 else { return nil }
            return decoded
        }
        inFlight[url] = task
        let result = await task.value
        inFlight[url] = nil

        if let result {
            if cache.count >= limit { cache.removeAll() }
            cache[url] = result
        }
        return result
    }
}
