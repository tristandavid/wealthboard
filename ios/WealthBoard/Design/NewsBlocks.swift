import SwiftUI

// The news feed's shared furniture, so the Markets tab and the full reader are
// the same feed with the same design rather than a reader in one place and a
// flat list of links in the other.
//
// Laid out like a news reader: a coloured heading per topic, one large lead
// story with artwork, then compact rows. Headlines come from several outlets
// and are grouped by subject rather than jumbled by timestamp, which is the
// only thing that makes a mixed feed skimmable.

/// Accent colour per section, echoing Apple News' coloured section headings.
func newsAccent(for category: NewsCategory) -> Color {
    switch category {
    case .top: return Color(red: 0.88, green: 0.14, blue: 0.37)
    case .markets: return Color(red: 0.12, green: 0.56, blue: 0.35)
    case .economy: return Color(red: 0.48, green: 0.31, blue: 0.82)
    case .companies: return Color(red: 0.06, green: 0.44, blue: 0.75)
    case .crypto: return Color(red: 0.85, green: 0.46, blue: 0.02)
    }
}

/// One topic's worth of the feed. A named type rather than a tuple because
/// `ForEach` needs something identifiable to iterate over.
struct NewsSection: Identifiable {
    let category: NewsCategory
    let items: [NewsItem]

    var id: String { category.rawValue }
    /// The story that gets the artwork and the large headline.
    var lead: NewsItem { items[0] }
    var rest: ArraySlice<NewsItem> { items.dropFirst() }
}

/// Articles grouped into sections, in the enum's editorial order, dropping any
/// section the feeds produced nothing for.
func newsSections(_ articles: [NewsItem]) -> [NewsSection] {
    NewsCategory.allCases.compactMap { category in
        let items = articles.filter { $0.category == category }
        return items.isEmpty ? nil : NewsSection(category: category, items: items)
    }
}

/// Relative age ("2h ago"), falling back to an absolute date past a day.
func newsAgeLabel(_ date: Date) -> String {
    let minutes = Int(Date().timeIntervalSince(date) / 60)
    switch minutes {
    case ..<1: return "Just now"
    case ..<60: return "\(minutes)m ago"
    case ..<1440: return "\(minutes / 60)h ago"
    default: return date.formatted(date: .abbreviated, time: .shortened)
    }
}

/// Coloured section title, e.g. a red "Top Stories".
struct NewsSectionHeading: View {
    let category: NewsCategory

    var body: some View {
        Text(category.label)
            .font(.system(size: 22, weight: .heavy))
            .foregroundStyle(newsAccent(for: category))
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, WbDimens.screenPadding)
            .padding(.top, 12)
            .padding(.bottom, 8)
    }
}

/// The lead story: 16:9 artwork above the publisher and headline.
///
/// No card around it. In a card, the artwork sat at the screen gutter but the
/// headline under it was inset by the card's own padding, so the lead story's
/// text started at a different place from every row beneath it and the section
/// read as two columns that had slipped. Everything in the feed — heading, lead
/// and rows — now shares one left edge.
struct LeadStoryCard: View {
    @Environment(\.colorScheme) private var scheme

    let article: NewsItem

    var body: some View {
        // Opens in the app's own browser rather than handing off to Safari: a
        // tap on a headline should not visibly leave WealthBoard.
        NavigationLink {
            InAppBrowserView(url: article.linkURL, title: article.publisher)
        } label: {
            VStack(alignment: .leading, spacing: 6) {
                if let imageURL = article.imageURL, let url = URL(string: imageURL) {
                    RemoteImage(url: url, referer: article.linkURL) {
                        Palette.surfaceVariant(scheme)
                    }
                    .frame(maxWidth: .infinity)
                    .aspectRatio(16.0 / 9.0, contentMode: .fit)
                    .clipped()
                    .clipShape(RoundedRectangle(cornerRadius: WbDimens.cardRadius, style: .continuous))
                    .padding(.bottom, 4)
                }

                Text(article.publisher.uppercased())
                    .font(.system(size: 11, weight: .bold))
                    .kerning(0.6)
                    .foregroundStyle(Palette.onSurfaceVariant(scheme))
                    .lineLimit(1)
                Text(article.title)
                    .font(.system(size: 19, weight: .bold))
                    .foregroundStyle(Palette.onSurface(scheme))
                    .multilineTextAlignment(.leading)
                    .lineLimit(3)
                Text(newsAgeLabel(article.publishedAt))
                    .font(.wbBodySmall)
                    .foregroundStyle(Palette.onSurfaceVariant(scheme))
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, WbDimens.screenPadding)
            .padding(.vertical, 6)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

/// Compact row: headline on the left, square thumbnail on the right.
struct CompactStoryRow: View {
    @Environment(\.colorScheme) private var scheme

    let article: NewsItem

    var body: some View {
        NavigationLink {
            InAppBrowserView(url: article.linkURL, title: article.publisher)
        } label: {
            HStack(alignment: .top, spacing: 12) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(article.publisher.uppercased())
                        .font(.system(size: 10, weight: .bold))
                        .kerning(0.5)
                        .foregroundStyle(Palette.onSurfaceVariant(scheme))
                        .lineLimit(1)
                    Text(article.title)
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(Palette.onSurface(scheme))
                        .multilineTextAlignment(.leading)
                        .lineLimit(3)
                    Text(newsAgeLabel(article.publishedAt))
                        .font(.system(size: 11))
                        .foregroundStyle(Palette.onSurfaceVariant(scheme))
                }
                // Takes the width the thumbnail doesn't, which is what keeps
                // every row starting at the same place.
                //
                // Without it the row was only as wide as its own text, and an
                // HStack narrower than its container gets CENTRED — so each
                // headline sat further right the shorter it was, and a column
                // of rows fanned out to the right of the lead story above them.
                .frame(maxWidth: .infinity, alignment: .leading)

                if let imageURL = article.imageURL, let url = URL(string: imageURL) {
                    RemoteImage(url: url, referer: article.linkURL) {
                        Palette.surfaceVariant(scheme)
                    }
                    .frame(width: 86, height: 86)
                    .clipped()
                    .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                }
            }
            .padding(.horizontal, WbDimens.screenPadding)
            .padding(.vertical, 11)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}
