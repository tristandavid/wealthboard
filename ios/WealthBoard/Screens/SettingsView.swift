import SwiftUI

/// Appearance, reporting currency, home market, app lock, dividend
/// reinvestment, transaction history and credits.
struct SettingsView: View {
    @EnvironmentObject private var viewModel: PortfolioViewModel
    @Environment(\.colorScheme) private var scheme
    @Environment(\.openURL) private var openURL

    @State private var lockEnabled = AppLock.isEnabled
    @State private var lockMode = AppLock.mode
    @State private var allowScreenshots = AppLock.allowScreenshots
    @State private var showPinSetup = false
    @State private var lockError: String?

    /// The currencies offered outright. Anything else the user holds is added
    /// below, so a portfolio in a currency this list forgot can still report in it.
    private let commonCurrencies = [
        "CAD", "USD", "EUR", "GBP", "AUD", "NZD", "JPY", "CHF",
        "SEK", "NOK", "DKK", "SGD", "HKD", "INR", "MXN", "BRL", "ZAR"
    ]

    private var currencyOptions: [String] {
        Array(Set(commonCurrencies + viewModel.heldCurrencies)).sorted()
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 14) {
                appearanceCard
                currencyCard
                homeMarketCard
                appLockCard
                dripCard
                transactionsCard
                aboutCard
                creditsCard
                Color.clear.frame(height: 30)
            }
            .padding(.horizontal, WbDimens.screenPadding)
            .padding(.top, 8)
        }
        .wbScreenBackground(scheme)
        .navigationTitle("Settings")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(isPresented: $showPinSetup) {
            PinSetupSheet { pin in
                AppLock.enablePin(pin)
                lockEnabled = true
                lockMode = .pin
            }
        }
    }

    // MARK: - App lock

    private var appLockCard: some View {
        WbCard {
            Text("App lock")
                .font(.wbTitleMedium)
                .foregroundStyle(Palette.onSurface(scheme))
                .padding(.bottom, 4)

            Text("Ask for \(AppLock.biometryName) before the app opens. Your portfolio never leaves the device, so this is about who can pick your phone up rather than about the network.")
                .font(.wbBodySmall)
                .foregroundStyle(Palette.onSurfaceVariant(scheme))
                .fixedSize(horizontal: false, vertical: true)
                .padding(.bottom, 12)

            Toggle(isOn: Binding(
                get: { lockEnabled },
                set: { setLockEnabled($0) }
            )) {
                Text("Require \(AppLock.biometryName) to open")
                    .font(.wbBodyMedium)
                    .foregroundStyle(Palette.onSurface(scheme))
            }
            .tint(Palette.accent(scheme))

            if let lockError {
                Text(lockError)
                    .font(.wbBodySmall)
                    .foregroundStyle(Brand.loss)
                    .padding(.top, 6)
            }

            if lockEnabled {
                WbDivider().padding(.vertical, 10)

                Toggle(isOn: Binding(
                    get: { lockMode == .pin },
                    set: { usePin in
                        if usePin {
                            showPinSetup = true
                        } else if AppLock.biometricsAvailable {
                            AppLock.enableBiometric()
                            lockMode = .biometric
                        }
                    }
                )) {
                    VStack(alignment: .leading, spacing: 1) {
                        Text("Use a 6-digit PIN instead")
                            .font(.wbBodyMedium)
                            .foregroundStyle(Palette.onSurface(scheme))
                        Text("A separate PIN, hashed and stored in the keychain. Useful when several people share a device passcode.")
                            .font(.wbBodySmall)
                            .foregroundStyle(Palette.onSurfaceVariant(scheme))
                    }
                }
                .tint(Palette.accent(scheme))

                WbDivider().padding(.vertical, 10)

                Toggle(isOn: Binding(
                    get: { allowScreenshots },
                    set: {
                        allowScreenshots = $0
                        AppLock.allowScreenshots = $0
                    }
                )) {
                    VStack(alignment: .leading, spacing: 1) {
                        Text("Allow screenshots")
                            .font(.wbBodyMedium)
                            .foregroundStyle(Palette.onSurface(scheme))
                        Text("Off, the app switcher shows a blank card instead of your balances.")
                            .font(.wbBodySmall)
                            .foregroundStyle(Palette.onSurfaceVariant(scheme))
                    }
                }
                .tint(Palette.accent(scheme))
            }
        }
    }

    private func setLockEnabled(_ enabled: Bool) {
        lockError = nil
        guard enabled else {
            AppLock.disable()
            lockEnabled = false
            return
        }
        if AppLock.biometricsAvailable {
            AppLock.enableBiometric()
            lockEnabled = true
            lockMode = .biometric
        } else {
            // A lock that cannot be opened is worse than no lock, so fall
            // straight through to the PIN rather than enabling a biometric
            // check this device can't perform.
            showPinSetup = true
        }
    }

    // MARK: - DRIP

    private var dripCard: some View {
        WbCard {
            Text("Dividend reinvestment (DRIP)")
                .font(.wbTitleMedium)
                .foregroundStyle(Palette.onSurface(scheme))
                .padding(.bottom, 4)

            Text("When on, each dividend you receive offers to be reinvested. You confirm the actual fill price and units before anything is added to a holding — nothing changes your position automatically.")
                .font(.wbBodySmall)
                .foregroundStyle(Palette.onSurfaceVariant(scheme))
                .fixedSize(horizontal: false, vertical: true)
                .padding(.bottom, 12)

            Toggle(isOn: $viewModel.dripEnabled) {
                Text("Offer DRIP on received dividends")
                    .font(.wbBodyMedium)
                    .foregroundStyle(Palette.onSurface(scheme))
            }
            .tint(Palette.accent(scheme))
        }
    }

    // MARK: - Transactions

    private var transactionsCard: some View {
        NavigationLink {
            TransactionHistoryView()
        } label: {
            WbCard {
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("View transaction history")
                            .font(.wbBodyLarge)
                            .fontWeight(.semibold)
                            .foregroundStyle(Palette.onSurface(scheme))
                        Text("Every buy, sell and DRIP recorded against your holdings.")
                            .font(.wbBodySmall)
                            .foregroundStyle(Palette.onSurfaceVariant(scheme))
                    }
                    Spacer(minLength: 8)
                    Image(systemName: "chevron.right")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(Palette.onSurfaceVariant(scheme).opacity(0.6))
                }
            }
        }
        .buttonStyle(.plain)
    }

    // MARK: - Credits

    /// Required, not decorative: the logo CDN's free tier is licensed on
    /// condition of a visible credit linking back. Removing this puts the app
    /// outside that licence.
    private var creditsCard: some View {
        WbCard {
            Text("Credits")
                .font(.wbTitleMedium)
                .foregroundStyle(Palette.onSurface(scheme))
                .padding(.bottom, 6)

            Button {
                if let url = URL(string: LogoResolver.attributionURL) { openURL(url) }
            } label: {
                Text(LogoResolver.attributionText)
                    .font(.wbBodySmall)
                    .foregroundStyle(Palette.accent(scheme))
            }
            .buttonStyle(.plain)
        }
    }

    private var appearanceCard: some View {
        WbCard {
            Text("Appearance")
                .font(.wbTitleMedium)
                .foregroundStyle(Palette.onSurface(scheme))
                .padding(.bottom, 10)

            ForEach(ThemeMode.allCases) { mode in
                Button {
                    viewModel.themeMode = mode
                } label: {
                    HStack(alignment: .top, spacing: 10) {
                        Image(systemName: viewModel.themeMode == mode ? "largecircle.fill.circle" : "circle")
                            .foregroundStyle(Palette.accent(scheme))
                        VStack(alignment: .leading, spacing: 1) {
                            Text(mode.label)
                                .font(.wbBodyLarge)
                                .foregroundStyle(Palette.onSurface(scheme))
                            Text(mode.detail)
                                .font(.wbBodySmall)
                                .foregroundStyle(Palette.onSurfaceVariant(scheme))
                        }
                        Spacer(minLength: 0)
                    }
                    .padding(.vertical, 8)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
        }
    }

    private var currencyCard: some View {
        WbCard {
            Text("Reporting currency")
                .font(.wbTitleMedium)
                .foregroundStyle(Palette.onSurface(scheme))
                .padding(.bottom, 4)

            // Per-holding screens still show a position in its own currency —
            // that's the number on the statement. This is what anything summing
            // across holdings is expressed in.
            Text("Portfolio totals, allocation and dividend income are converted into this. Individual positions keep their own currency.")
                .font(.wbBodySmall)
                .foregroundStyle(Palette.onSurfaceVariant(scheme))
                .padding(.bottom, 12)

            Menu {
                ForEach(currencyOptions, id: \.self) { code in
                    Button("\(TickerFlag.forCurrency(code)) \(code)") {
                        viewModel.baseCurrency = code
                    }
                }
            } label: {
                HStack {
                    Text("\(TickerFlag.forCurrency(viewModel.baseCurrency)) \(viewModel.baseCurrency)")
                        .font(.wbBodyLarge)
                        .fontWeight(.semibold)
                        .foregroundStyle(Palette.accent(scheme))
                    Spacer()
                    Image(systemName: "chevron.up.chevron.down")
                        .font(.system(size: 12))
                        .foregroundStyle(Palette.onSurfaceVariant(scheme))
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 12)
                .overlay(
                    RoundedRectangle(cornerRadius: 10, style: .continuous)
                        .stroke(Palette.outline(scheme), lineWidth: 1)
                )
            }

            let held = viewModel.heldCurrencies.filter {
                $0.caseInsensitiveCompare(viewModel.baseCurrency) != .orderedSame
            }
            Text(held.isEmpty
                ? "Single-currency portfolio"
                : "You hold positions in \(held.joined(separator: ", "))")
                .font(.wbBodySmall)
                .foregroundStyle(Palette.onSurfaceVariant(scheme))
                .padding(.top, 8)

            let unconverted = viewModel.dashboard.unconvertedCurrencies
            if !unconverted.isEmpty {
                Text("No exchange rate yet for \(unconverted.joined(separator: ", ")). Those positions are counted at face value until one arrives.")
                    .font(.wbBodySmall)
                    .foregroundStyle(Brand.loss)
                    .padding(.top, 10)
            }
        }
    }

    private var homeMarketCard: some View {
        WbCard {
            Text("Home market")
                .font(.wbTitleMedium)
                .foregroundStyle(Palette.onSurface(scheme))
                .padding(.bottom, 4)

            Text("Pins your own index to the Markets tab, after the three US benchmarks, and picks which market holiday closures you're told about.")
                .font(.wbBodySmall)
                .foregroundStyle(Palette.onSurfaceVariant(scheme))
                .padding(.bottom, 12)

            Menu {
                Button("Reset to auto-detect") { viewModel.homeCountry = nil }
                ForEach(MarketIndices.selectableCountries(), id: \.code) { entry in
                    Button(entry.label) { viewModel.homeCountry = entry.code }
                }
            } label: {
                HStack {
                    Text(homeMarketLabel)
                        .font(.wbBodyLarge)
                        .fontWeight(.semibold)
                        .foregroundStyle(Palette.accent(scheme))
                    Spacer()
                    Image(systemName: "chevron.up.chevron.down")
                        .font(.system(size: 12))
                        .foregroundStyle(Palette.onSurfaceVariant(scheme))
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 12)
                .overlay(
                    RoundedRectangle(cornerRadius: 10, style: .continuous)
                        .stroke(Palette.outline(scheme), lineWidth: 1)
                )
            }
        }
    }

    private var homeMarketLabel: String {
        guard let code = viewModel.homeCountry else {
            return "Follow my device (\(MarketIndices.deviceCountry()))"
        }
        return MarketIndices.selectableCountries().first { $0.code == code }?.label ?? code
    }

    private var aboutCard: some View {
        WbCard {
            Text("About")
                .font(.wbTitleMedium)
                .foregroundStyle(Palette.onSurface(scheme))
                .padding(.bottom, 8)

            Text("WealthBoard tracks a hand-entered portfolio: what you hold, what it's worth, what it pays and what tax is quietly coming off it. Prices come from a Finance Query server and dividend history from dividendhistory.org; nothing about your positions is sent with them.")
                .font(.wbBodySmall)
                .foregroundStyle(Palette.onSurfaceVariant(scheme))
                .padding(.bottom, 10)

            KeyValueRow(label: "Version", value: versionString)
        }
    }

    private var versionString: String {
        let info = Bundle.main.infoDictionary
        let version = info?["CFBundleShortVersionString"] as? String ?? "1.0"
        let build = info?["CFBundleVersion"] as? String ?? "1"
        return "\(version) (\(build))"
    }
}
