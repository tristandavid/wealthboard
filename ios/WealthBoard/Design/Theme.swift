import SwiftUI

// MARK: - Brand palette
//
// Ported from `ui/theme/Theme.kt`. Deep navy + warm gold, matching the app
// icon. A finance app reads as more trustworthy in a restrained, low-chroma
// navy/ivory scheme than in a stock system-blue one, so this replaces the
// system accent everywhere.

extension Color {
    init(hex: UInt32, alpha: Double = 1.0) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xFF) / 255.0,
            green: Double((hex >> 8) & 0xFF) / 255.0,
            blue: Double(hex & 0xFF) / 255.0,
            opacity: alpha
        )
    }
}

enum Brand {
    static let navy       = Color(hex: 0x0F2A43)
    static let navyDark   = Color(hex: 0x0A1D2F)
    static let navyLight  = Color(hex: 0x1D3F5F)
    static let gold       = Color(hex: 0xC9A227)
    static let goldLight  = Color(hex: 0xE4C766)
    static let ivory      = Color(hex: 0xF4F1E8)

    /// Semantic colours for gains/losses, used anywhere a value needs a
    /// positive/negative read at a glance.
    static let gain = Color(hex: 0x1E8E5A)
    static let loss = Color(hex: 0xC0392B)

    /// Brighter variants for use on the dark gradient headline cards, where the
    /// standard gain/loss greens sit too close to the background to read.
    static let gainOnDark = Color(hex: 0x6FE3A8)
    static let lossOnDark = Color(hex: 0xE58A82)

    /// Indigo gradient shared by the two headline cards (Total Value on
    /// Portfolio, Passive Income Goal on Dividends), so they read as a pair.
    static let goalIndigoDark = Color(hex: 0x1E1B4B)
    static let goalIndigoMid  = Color(hex: 0x312E81)

    /// Dividends-tab accents.
    static let divIndigo      = Color(hex: 0x6366F1)
    static let divIndigoLight = Color(hex: 0x818CF8)
    static let divIndigoPale  = Color(hex: 0xC4B5FD)
    static let divGreen       = Color(hex: 0x16A34A)
    static let divAmber       = Color(hex: 0xD97706)
    static let divBarActual   = Color(hex: 0x6366F1)
    static let divBarForward  = Color(hex: 0x22D3EE)

    /// Allocation pie / bar palette — cycles for as many holdings as exist.
    static let allocation: [Color] = [
        Color(hex: 0x0F2A43), Color(hex: 0xC9A227), Color(hex: 0x3D6E8C),
        Color(hex: 0x8C6A1F), Color(hex: 0x5A7A63), Color(hex: 0x7B4F8C),
        Color(hex: 0xB55A3A)
    ]

    static func allocationColor(at index: Int) -> Color {
        allocation[((index % allocation.count) + allocation.count) % allocation.count]
    }
}

// MARK: - Semantic surfaces
//
// SwiftUI has no Material colour-role system, so the roles the Compose theme
// defined explicitly are re-declared here as dynamic colours that resolve
// against the active colour scheme. Everything the app paints with goes through
// these, which is what keeps light and dark consistent.

enum Palette {

    static func background(_ scheme: ColorScheme) -> Color {
        scheme == .dark ? Brand.navyDark : Color(hex: 0xFAFAF7)
    }

    static func surface(_ scheme: ColorScheme) -> Color {
        // Android's WbCard paints `colorScheme.surface`, which in the dark
        // scheme is BrandNavy itself — not a step up the surfaceContainer
        // ladder. Matching it exactly keeps a card the same colour on both
        // platforms.
        scheme == .dark ? Brand.navy : .white
    }

    /// Slightly raised surface, for a card sitting on another card.
    static func surfaceHigh(_ scheme: ColorScheme) -> Color {
        scheme == .dark ? Color(hex: 0x163150) : Color(hex: 0xF2EFE6)
    }

    static func surfaceVariant(_ scheme: ColorScheme) -> Color {
        scheme == .dark ? Brand.navyLight : Color(hex: 0xE9E6DD)
    }

    static func onSurface(_ scheme: ColorScheme) -> Color {
        scheme == .dark ? Color(hex: 0xE8E9EB) : Color(hex: 0x1A1C1E)
    }

    static func onSurfaceVariant(_ scheme: ColorScheme) -> Color {
        scheme == .dark ? Color(hex: 0xB9C2CC) : Color(hex: 0x48454A)
    }

    static func outline(_ scheme: ColorScheme) -> Color {
        scheme == .dark ? Color(hex: 0x33506D) : Color(hex: 0xD8D5CC)
    }

    /// The accent used for chips, links, filled buttons and the tab bar
    /// selection. Gold reads well on navy; on the light scheme the darker gold
    /// keeps contrast. Android's `secondary`.
    static func accent(_ scheme: ColorScheme) -> Color {
        scheme == .dark ? Brand.goldLight : Brand.gold
    }

    /// Text and icons sitting on an `accent` fill. The same warm near-black in
    /// both schemes, because the fill is gold in both.
    static func onAccent(_ scheme: ColorScheme) -> Color {
        Color(hex: 0x3A2E00)
    }

    /// Android's `primary` role: navy in light, pale gold in dark.
    ///
    /// This is what the navigation bar and the portfolio value line are painted
    /// with over there, and the two schemes deliberately invert — navy reads as
    /// the brand colour against an ivory page, and would disappear against a
    /// navy one.
    static func primary(_ scheme: ColorScheme) -> Color {
        scheme == .dark ? Brand.goldLight : Brand.navy
    }

    static func onPrimary(_ scheme: ColorScheme) -> Color {
        scheme == .dark ? Color(hex: 0x3A2E00) : .white
    }

    /// The navigation bar fill — `primary`, as on Android.
    static func topBar(_ scheme: ColorScheme) -> Color {
        primary(scheme)
    }

    static func onTopBar(_ scheme: ColorScheme) -> Color {
        onPrimary(scheme)
    }

    /// Colour for a signed value: green when non-negative, red when negative.
    static func change(_ value: Double) -> Color {
        value >= 0 ? Brand.gain : Brand.loss
    }
}

// MARK: - Dimensions

enum WbDimens {
    /// Standard horizontal inset from the screen edge.
    static let screenPadding: CGFloat = 16
    /// Inner padding for card content.
    static let cardPadding: CGFloat = 16
    /// Gap between stacked cards / sections.
    static let sectionGap: CGFloat = 18
    /// Gap between rows inside a card.
    static let rowGap: CGFloat = 12
    /// Corner radius used by every card in the app.
    static let cardRadius: CGFloat = 16
}

// MARK: - Typography
//
// Mirrors `WealthBoardTypography` in Theme.kt. Sizes are given in points rather
// than named text styles so the app's density matches the Android build; the
// weights are the same.

extension Font {
    static let wbHeadline    = Font.system(size: 30, weight: .bold)
    static let wbTitleLarge  = Font.system(size: 22, weight: .semibold)
    static let wbTitleMedium = Font.system(size: 17, weight: .semibold)
    static let wbLabelLarge  = Font.system(size: 13, weight: .medium)
    static let wbLabelSmall  = Font.system(size: 11, weight: .medium)
    static let wbBodyLarge   = Font.system(size: 16)
    static let wbBodyMedium  = Font.system(size: 14)
    static let wbBodySmall   = Font.system(size: 12)
}

// MARK: - Appearance preference

/// Which colour scheme the app paints in. `system` follows the device setting;
/// the other two pin it regardless of what the device is doing.
enum ThemeMode: String, CaseIterable, Identifiable {
    case light, dark, system

    var id: String { rawValue }

    var label: String {
        switch self {
        case .light: return "Light"
        case .dark: return "Dark"
        case .system: return "System default"
        }
    }

    var detail: String {
        switch self {
        case .light: return "Always use the light theme"
        case .dark: return "Always use the dark theme"
        case .system: return "Follow your device's setting"
        }
    }

    /// nil means "follow the device", which is what SwiftUI's
    /// `.preferredColorScheme` expects for the system case.
    var colorScheme: ColorScheme? {
        switch self {
        case .light: return .light
        case .dark: return .dark
        case .system: return nil
        }
    }

    static func from(_ stored: String?) -> ThemeMode {
        guard let stored, let mode = ThemeMode(rawValue: stored) else { return .system }
        return mode
    }
}
