import SwiftUI

/// The two feeds behind the News tab.
enum NewsFeedTab: String, CaseIterable, Identifiable {
    case market, dividend

    var id: String { rawValue }

    var label: String {
        switch self {
        case .market: return "Market"
        case .dividend: return "Dividends"
        }
    }
}

/// "News": market headlines and dividend headlines, as a tab of their own.
///
/// News used to be a block at the bottom of the Dashboard, under the indices
/// and the movers table, with the full readers filed away under Menu. That put
/// the thing people open several times a day below a fold they had to scroll
/// past, and split one feature across two places. It is a destination now, with
/// the two feeds behind a segmented control rather than a pair of chips.
struct NewsView: View {
    @EnvironmentObject private var viewModel: PortfolioViewModel
    @Environment(\.colorScheme) private var scheme

    @State private var tab: NewsFeedTab = .market

    var body: some View {
        VStack(spacing: 0) {
            picker

            TabView(selection: $tab) {
                ForEach(NewsFeedTab.allCases) { feed in
                    NewsFeedList(feed: feed).tag(feed)
                }
            }
            .tabViewStyle(.page(indexDisplayMode: .never))
        }
        .wbScreenBackground(scheme)
        .wbNavigationBar("News")
        .task {
            // Cached headlines first, so the tab opens on content rather than
            // a spinner — then the network, ALWAYS.
            //
            // The refresh used to be conditional on the list being empty, which
            // meant the cache won every time it had anything in it: once a
            // reader had opened the tab once, the only way to see a new
            // headline was to pull down, and the feed otherwise sat on
            // yesterday's stories indefinitely. The cache is here to avoid a
            // spinner, not to decide what the news is.
            await viewModel.loadCachedNews()
            await viewModel.refreshMarketNews()
        }
        .task(id: tab) {
            if tab == .dividend { await viewModel.refreshDividendNews() }
        }
    }

    /// A real segmented control rather than two capsule chips: this is a choice
    /// between two views of one screen, which is exactly what a segmented
    /// control means, and it stays put while the feed beneath it scrolls.
    private var picker: some View {
        Picker("Feed", selection: $tab) {
            ForEach(NewsFeedTab.allCases) { feed in
                Text(feed.label).tag(feed)
            }
        }
        .pickerStyle(.segmented)
        .padding(.horizontal, WbDimens.screenPadding)
        .padding(.top, 10)
        .padding(.bottom, 10)
        .background(Palette.background(scheme))
    }
}

/// One feed's worth of articles, with its own pull-to-refresh.
private struct NewsFeedList: View {
    @EnvironmentObject private var viewModel: PortfolioViewModel
    @Environment(\.colorScheme) private var scheme

    let feed: NewsFeedTab

    private var articles: [NewsItem] {
        feed == .market ? viewModel.marketNews : viewModel.dividendNews
    }

    private var state: NewsLoadState {
        feed == .market ? viewModel.marketNewsState : viewModel.dividendNewsState
    }

    var body: some View {
        ScrollView {
            LazyVStack(spacing: 0) {
                if articles.isEmpty {
                    placeholder
                } else if feed == .dividend {
                    // Dividend headlines come from one kind of source and have
                    // no topics to group into, so they stay a flat list — but a
                    // flat list of the SAME rows the market feed uses, not a
                    // second row design that lines up differently.
                    ForEach(articles) { article in
                        CompactStoryRow(article: article)
                        WbDivider().padding(.horizontal, WbDimens.screenPadding)
                    }
                } else {
                    ForEach(newsSections(articles)) { section in
                        NewsSectionHeading(category: section.category)
                        LeadStoryCard(article: section.lead)
                        ForEach(section.rest) { article in
                            CompactStoryRow(article: article)
                        }
                        WbDivider()
                            .padding(.horizontal, WbDimens.screenPadding)
                            .padding(.top, 10)
                    }
                }
                Color.clear.frame(height: 30)
            }
        }
        .refreshable { await load() }
    }

    /// The empty state says which empty this is.
    ///
    /// "Pull down to refresh" is useless advice to someone whose device cannot
    /// reach the sources at all, and it was the only thing the old screen said
    /// in either case.
    @ViewBuilder
    private var placeholder: some View {
        VStack(spacing: 12) {
            if state == .loading || state == .idle {
                ProgressView()
                    .controlSize(.regular)
                    .padding(.top, 40)
            }
            Text(state.message ?? "")
                .font(.wbBodyMedium)
                .multilineTextAlignment(.center)
                .foregroundStyle(Palette.onSurfaceVariant(scheme))

            if state == .unreachable || state == .empty {
                Button {
                    Task { await load() }
                } label: {
                    Text("Try again")
                        .font(.wbBodyMedium)
                        .fontWeight(.semibold)
                        .foregroundStyle(Palette.onAccent(scheme))
                        .padding(.horizontal, 20)
                        .padding(.vertical, 9)
                        .background(
                            Capsule().fill(Palette.accent(scheme))
                        )
                }
                .buttonStyle(.plain)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.horizontal, WbDimens.screenPadding)
        .padding(.top, state == .loading || state == .idle ? 0 : 60)
    }

    private func load() async {
        switch feed {
        case .market: await viewModel.refreshMarketNews()
        case .dividend: await viewModel.refreshDividendNews()
        }
    }
}
