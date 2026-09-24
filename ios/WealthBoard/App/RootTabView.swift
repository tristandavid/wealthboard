import SwiftUI
import UIKit

/// The five content tabs, matching the Android bottom navigation bar.
///
/// Labels are deliberately one short word each. With five tabs a phone gives
/// each item roughly 70pt, and a two-word label ("My Portfolio") wraps onto a
/// second line, which pushes the row taller and clips the tabs at either end.
///
/// News took the Markets slot rather than being added as a sixth tab. Six is
/// one too many for a phone: iOS collapses the overflow into a "More" list,
/// which buries whatever lands in it. The Markets dashboard — indices, movers,
/// watchlist, symbol search — is not gone; it moved under Menu, which is where
/// a screen people open occasionally belongs, while the news feed they open
/// daily got the tab.
enum AppTab: String, CaseIterable, Identifiable {
    case portfolio, dividends, reports, news, menu

    var id: String { rawValue }

    var label: String {
        switch self {
        case .portfolio: return "Portfolio"
        case .dividends: return "Dividends"
        case .reports: return "Reports"
        case .news: return "News"
        case .menu: return "Menu"
        }
    }

    var systemImage: String {
        switch self {
        case .portfolio: return "wallet.pass.fill"
        case .dividends: return "banknote.fill"
        case .reports: return "chart.bar.fill"
        case .news: return "newspaper.fill"
        case .menu: return "line.3.horizontal"
        }
    }
}

struct RootTabView: View {
    @EnvironmentObject private var viewModel: PortfolioViewModel
    @Environment(\.colorScheme) private var scheme
    @State private var selection: AppTab = .portfolio

    var body: some View {
        TabView(selection: $selection) {
            ForEach(AppTab.allCases) { tab in
                NavigationStack {
                    screen(for: tab)
                }
                .tabItem {
                    Label(tab.label, systemImage: tab.systemImage)
                }
                .tag(tab)
            }
        }
        .onAppear { styleTabBar() }
        .onChange(of: scheme) { _ in styleTabBar() }
    }

    @ViewBuilder
    private func screen(for tab: AppTab) -> some View {
        switch tab {
        case .portfolio: PortfolioView()
        case .dividends: DividendsView()
        case .reports: ReportsView()
        case .news: NewsView()
        case .menu: MenuView()
        }
    }

    /// SwiftUI's tab bar goes translucent over scrolled content and picks up
    /// the system grouped background, which reads as a different app from the
    /// navy surfaces above it. Pinning the appearance keeps it on the app's own
    /// palette in both schemes.
    private func styleTabBar() {
        let appearance = UITabBarAppearance()
        appearance.configureWithOpaqueBackground()
        appearance.backgroundColor = UIColor(Palette.surface(scheme))

        let item = appearance.stackedLayoutAppearance
        let selected = UIColor(Palette.accent(scheme))
        let normal = UIColor(Palette.onSurfaceVariant(scheme))

        item.selected.iconColor = selected
        item.selected.titleTextAttributes = [.foregroundColor: selected]
        item.normal.iconColor = normal
        item.normal.titleTextAttributes = [.foregroundColor: normal]

        UITabBar.appearance().standardAppearance = appearance
        UITabBar.appearance().scrollEdgeAppearance = appearance
    }
}
