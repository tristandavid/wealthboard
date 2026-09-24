import SwiftUI

// Shared building blocks for WealthBoard's screens, ported from
// `ui/components/WealthBoardUi.kt`. Screens compose these instead of
// hand-rolling cards and rows, which is what keeps the look uniform.

// MARK: - Card

/// The app's standard card. One radius, one elevation, one padding, everywhere.
struct WbCard<Content: View>: View {
    @Environment(\.colorScheme) private var scheme

    var padding: CGFloat = WbDimens.cardPadding
    @ViewBuilder var content: () -> Content

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            content()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(padding)
        .background(Palette.surface(scheme))
        .clipShape(RoundedRectangle(cornerRadius: WbDimens.cardRadius, style: .continuous))
        .shadow(color: .black.opacity(scheme == .dark ? 0.35 : 0.06), radius: 3, x: 0, y: 1)
    }
}

// MARK: - Section header

/// Section title, optionally with a trailing action (a "See all" link, a count).
/// Used above every list and card group so headings are visually identical.
struct SectionHeader<Trailing: View>: View {
    @Environment(\.colorScheme) private var scheme

    let title: String
    var subtitle: String? = nil
    @ViewBuilder var trailing: () -> Trailing

    var body: some View {
        HStack(alignment: .center) {
            VStack(alignment: .leading, spacing: 1) {
                Text(title)
                    .font(.wbTitleMedium)
                    .fontWeight(.bold)
                    .foregroundStyle(Palette.onSurface(scheme))
                if let subtitle {
                    Text(subtitle)
                        .font(.wbBodySmall)
                        .foregroundStyle(Palette.onSurfaceVariant(scheme))
                }
            }
            Spacer(minLength: 8)
            trailing()
        }
        .padding(.horizontal, WbDimens.screenPadding)
        .padding(.vertical, 8)
    }
}

extension SectionHeader where Trailing == EmptyView {
    init(_ title: String, subtitle: String? = nil) {
        self.init(title: title, subtitle: subtitle, trailing: { EmptyView() })
    }
}

// MARK: - Stats

/// A labelled metric, stacked label-over-value. This is the unit the holding
/// detail screen is built from — two per row gives the paired-stat grid.
struct StatCell: View {
    @Environment(\.colorScheme) private var scheme

    let label: String
    let value: String
    var valueColor: Color?
    var emphasis: Bool = false

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label)
                .font(.wbBodySmall)
                .foregroundStyle(Palette.onSurfaceVariant(scheme))
                .lineLimit(1)
            Text(value)
                .font(.system(size: emphasis ? 20 : 16, weight: emphasis ? .bold : .semibold))
                .foregroundStyle(valueColor ?? Palette.onSurface(scheme))
                .lineLimit(1)
                .minimumScaleFactor(0.7)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// Two `StatCell`s side by side, evenly split — the grid used in Position/Dividends.
struct StatPair: View {
    let leftLabel: String
    let leftValue: String
    var rightLabel: String?
    var rightValue: String?
    var leftColor: Color?
    var rightColor: Color?

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            StatCell(label: leftLabel, value: leftValue, valueColor: leftColor)
            if let rightLabel, let rightValue {
                StatCell(label: rightLabel, value: rightValue, valueColor: rightColor)
            } else {
                Color.clear.frame(maxWidth: .infinity, maxHeight: 1)
            }
        }
    }
}

/// Single-line label ⟷ value row, for dense key/value lists.
struct KeyValueRow: View {
    @Environment(\.colorScheme) private var scheme

    let label: String
    let value: String
    var valueColor: Color?

    var body: some View {
        HStack(alignment: .firstTextBaseline) {
            Text(label)
                .font(.wbBodyMedium)
                .foregroundStyle(Palette.onSurfaceVariant(scheme))
            Spacer(minLength: 12)
            Text(value)
                .font(.wbBodyMedium)
                .fontWeight(.semibold)
                .foregroundStyle(valueColor ?? Palette.onSurface(scheme))
                .multilineTextAlignment(.trailing)
        }
        .padding(.vertical, 9)
    }
}

/// Thin separator with the app's standard alpha.
struct WbDivider: View {
    @Environment(\.colorScheme) private var scheme
    var body: some View {
        Rectangle()
            .fill(Palette.outline(scheme).opacity(0.4))
            .frame(height: 1)
    }
}

// MARK: - Pills and chips

/// Small coloured status pill (Estimated / Announced / Fast / Negative …).
struct StatusPill: View {
    let text: String
    let color: Color

    var body: some View {
        HStack(spacing: 5) {
            Circle().fill(color).frame(width: 5, height: 5)
            Text(text)
                .font(.system(size: 11, weight: .medium))
                .foregroundStyle(color)
                .lineLimit(2)
        }
        .padding(.horizontal, 7)
        .padding(.vertical, 3)
        .background(color.opacity(0.12))
        .clipShape(RoundedRectangle(cornerRadius: 5, style: .continuous))
    }
}

/// The two-state toggle used on the Dividends cards (MTD/YTD, TTM/FWD,
/// Monthly/Yearly). Ported from `DivToggleChips`.
struct ToggleChips: View {
    @Environment(\.colorScheme) private var scheme

    let left: String
    let right: String
    @Binding var selection: String

    var body: some View {
        HStack(spacing: 4) {
            chip(left)
            chip(right)
        }
    }

    @ViewBuilder
    private func chip(_ title: String) -> some View {
        let selected = selection == title
        Button {
            selection = title
        } label: {
            Text(title)
                .font(.system(size: 11, weight: selected ? .bold : .medium))
                .foregroundStyle(selected ? Palette.onAccent(scheme) : Palette.onSurfaceVariant(scheme))
                .padding(.horizontal, 10)
                .padding(.vertical, 5)
                .background(
                    selected
                        ? Palette.accent(scheme)
                        : Palette.surfaceVariant(scheme).opacity(0.5)
                )
                .clipShape(Capsule())
        }
        .buttonStyle(.plain)
    }
}

/// Horizontally scrolling single-select chip row — the Reports period and
/// benchmark pickers, and the chart range selector.
struct ChipRow<T: Hashable>: View {
    @Environment(\.colorScheme) private var scheme

    let items: [T]
    let title: (T) -> String
    @Binding var selection: T

    /// The whole row fits the width; it does not scroll.
    ///
    /// Scrolled, the last chips sat off the right edge with nothing to say they
    /// were there — on the quote screen that hid 5Y and ALL entirely. Sharing
    /// the width means every range is visible and reachable at a glance, which
    /// is the entire job of a range selector.
    var body: some View {
        HStack(spacing: 5) {
            ForEach(items, id: \.self) { item in
                let selected = item == selection
                Button {
                    selection = item
                } label: {
                    Text(title(item))
                        .font(.system(size: 12, weight: selected ? .semibold : .regular))
                        .lineLimit(1)
                        .minimumScaleFactor(0.8)
                        .foregroundStyle(selected ? Palette.onAccent(scheme) : Palette.onSurfaceVariant(scheme))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 8)
                        .background(
                            Capsule().fill(selected ? Palette.accent(scheme) : Color.clear)
                        )
                        .overlay(
                            Capsule().stroke(
                                selected ? Color.clear : Palette.outline(scheme),
                                lineWidth: 1
                            )
                        )
                        .contentShape(Capsule())
                }
                .buttonStyle(.plain)
            }
        }
        .frame(maxWidth: .infinity)
    }
}

// MARK: - Logo avatar

/// Small fixed palette an avatar's colour is picked from, keyed by its label.
private let avatarPalette: [Color] = [
    Color(hex: 0x1F6FEB), Color(hex: 0x8957E5), Color(hex: 0xCF222E), Color(hex: 0x1A7F37),
    Color(hex: 0xBF8700), Color(hex: 0x0969DA), Color(hex: 0xBC4C00), Color(hex: 0x6E40C9)
]

private func avatarColor(for seed: String) -> Color {
    // A stable, platform-independent hash — Swift's own `hashValue` is seeded
    // per process, so the same ticker would change colour between launches.
    var h: UInt32 = 2166136261
    for byte in seed.uppercased().utf8 {
        h = (h ^ UInt32(byte)) &* 16777619
    }
    return avatarPalette[Int(h % UInt32(avatarPalette.count))]
}

/// Company/ticker logo used everywhere a quote, holding or search result shows
/// one. Falls back to a colour-coded initial when `logoURL` is nil or fails to
/// load, which is the common case: most Canadian and foreign listings, indices
/// and crypto never have a real image.
struct TickerLogo: View {
    let logoURL: String?
    let label: String
    var size: CGFloat = 40

    private var initial: String {
        let cleaned = label.drop(while: { $0 == "^" })
        if let letter = cleaned.first(where: { $0.isLetter }) {
            return String(letter).uppercased()
        }
        return "•"
    }

    var body: some View {
        let tint = avatarColor(for: label)
        ZStack {
            Circle().fill(tint.opacity(0.14))
            if let logoURL, let url = URL(string: logoURL) {
                AsyncImage(url: url) { phase in
                    switch phase {
                    case .success(let image):
                        image.resizable().scaledToFit().clipShape(Circle())
                    default:
                        fallback(tint)
                    }
                }
                .frame(width: size * 0.66, height: size * 0.66)
            } else {
                fallback(tint)
            }
        }
        .frame(width: size, height: size)
    }

    private func fallback(_ tint: Color) -> some View {
        Text(initial)
            .font(.system(size: size * 0.42, weight: .bold))
            .foregroundStyle(tint)
    }
}

// MARK: - Range bar

/// Horizontal position indicator for a value inside a low→high band, used for
/// the 52-week range. Renders a gradient track with a marker at `fraction`.
struct RangeBar: View {
    @Environment(\.colorScheme) private var scheme
    let fraction: Double

    var body: some View {
        let clamped = min(max(fraction, 0), 1)
        HStack(spacing: 6) {
            Text("L")
                .font(.wbLabelSmall)
                .foregroundStyle(Palette.onSurfaceVariant(scheme))
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    RoundedRectangle(cornerRadius: 4)
                        .fill(
                            LinearGradient(
                                colors: [
                                    Brand.gain.opacity(0.55),
                                    Brand.goldLight,
                                    Brand.loss.opacity(0.65)
                                ],
                                startPoint: .leading,
                                endPoint: .trailing
                            )
                        )
                        .frame(height: 8)
                    RoundedRectangle(cornerRadius: 2)
                        .fill(Palette.onSurface(scheme))
                        .frame(width: 3, height: 14)
                        .offset(x: max(0, geo.size.width * clamped - 1.5))
                }
                .frame(height: 14)
            }
            .frame(height: 14)
            Text("H")
                .font(.wbLabelSmall)
                .foregroundStyle(Palette.onSurfaceVariant(scheme))
        }
    }
}

// MARK: - Progress bar

/// The flat, rounded progress track used by the goal card and allocation rows.
struct WbProgressBar: View {
    let fraction: Double
    var height: CGFloat = 6
    var fill: AnyShapeStyle
    var track: Color

    init(fraction: Double, height: CGFloat = 6, fill: Color, track: Color) {
        self.fraction = fraction
        self.height = height
        self.fill = AnyShapeStyle(fill)
        self.track = track
    }

    init(fraction: Double, height: CGFloat = 6, gradient: LinearGradient, track: Color) {
        self.fraction = fraction
        self.height = height
        self.fill = AnyShapeStyle(gradient)
        self.track = track
    }

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                Capsule().fill(track)
                Capsule()
                    .fill(fill)
                    .frame(width: max(0, geo.size.width * min(max(fraction, 0), 1)))
            }
        }
        .frame(height: height)
    }
}

// MARK: - Screen chrome

/// The gold navigation bar the whole app wears, with the large bold title the
/// Android top bar uses. Applied by each tab's root view.
struct WbNavigationBar: ViewModifier {
    @Environment(\.colorScheme) private var scheme
    let title: String

    func body(content: Content) -> some View {
        content
            // A real navigation title, centred.
            //
            // It was a leading toolbar item, to put the screen's name on the
            // left the way the Android bar does. That worked until the system
            // began giving every toolbar item a capsule background and button
            // metrics: "My Portfolio" was then sized as a bar button, which is
            // why a circle containing "M…" appeared where the title should be.
            // Nothing can be styled back out of that — as far as the bar is
            // concerned the item is a button.
            //
            // `.large` is not the answer either: it puts the name in a band
            // that scrolls away and then reappears centred and shrunken, which
            // is two different headers for one screen. So: `.inline`, centred,
            // the way the quote screen has always drawn it. The two apps now
            // disagree about where a screen's name sits, which is a far smaller
            // price than a title that cannot be read.
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbarBackground(Palette.topBar(scheme), for: .navigationBar)
            .toolbarBackground(.visible, for: .navigationBar)
            // The bar inverts with the scheme — navy in light, pale gold in
            // dark — so the title and buttons have to invert with it. Pinning
            // this to .light put dark-brown text on the navy bar.
            .toolbarColorScheme(scheme == .dark ? .light : .dark, for: .navigationBar)
    }
}

extension View {
    func wbNavigationBar(_ title: String) -> some View {
        modifier(WbNavigationBar(title: title))
    }

    /// Fills the screen behind a scroll view with the app background, which
    /// SwiftUI otherwise leaves as the system grouped colour.
    func wbScreenBackground(_ scheme: ColorScheme) -> some View {
        self
            .scrollContentBackground(.hidden)
            .background(Palette.background(scheme).ignoresSafeArea())
    }
}

/// Small helper for the "empty state" copy every list shows before there is
/// anything in it.
struct EmptyNote: View {
    @Environment(\.colorScheme) private var scheme
    let text: String

    var body: some View {
        Text(text)
            .font(.wbBodySmall)
            .foregroundStyle(Palette.onSurfaceVariant(scheme))
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(WbDimens.cardPadding)
            .background(Palette.surfaceVariant(scheme).opacity(0.5))
            .clipShape(RoundedRectangle(cornerRadius: WbDimens.cardRadius, style: .continuous))
    }
}
