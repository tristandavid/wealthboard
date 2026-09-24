import SwiftUI

/// Where the user files, how each account is treated, and the two rates the
/// estimates need.
///
/// Every tax figure in the app is switched off until residency is set, and
/// switched off per account until that account's treatment is set. Both gaps
/// are shown here rather than left to look like "no tax applies".
struct TaxSettingsView: View {
    @EnvironmentObject private var viewModel: PortfolioViewModel
    @Environment(\.colorScheme) private var scheme

    @State private var marginalText = ""
    @State private var preferentialText = ""

    var body: some View {
        ScrollView {
            VStack(spacing: 14) {
                helpLink
                residencyCard
                if viewModel.residency != .other {
                    accountsCard
                    ratesCard
                    explainerCard
                }
                disclaimerCard
                Color.clear.frame(height: 30)
            }
            .padding(.horizontal, WbDimens.screenPadding)
            .padding(.top, 8)
        }
        .wbScreenBackground(scheme)
        .navigationTitle("Taxes")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            marginalText = viewModel.taxRates.marginalPct.map { String(format: "%.1f", $0) } ?? ""
            preferentialText = viewModel.taxRates.preferentialPct.map { String(format: "%.1f", $0) } ?? ""
        }
    }

    /// The plain-language guide, at the top of the screen it explains.
    ///
    /// It used to be a second tax entry in the Menu, which asked the reader to
    /// choose between "How tax works here" and "Taxes" before knowing what
    /// either did. Here it is an offer attached to the thing being configured.
    private var helpLink: some View {
        NavigationLink {
            TaxHelpView()
        } label: {
            WbCard {
                HStack {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("New to this? Read how tax works here")
                            .font(.wbBodyLarge)
                            .fontWeight(.semibold)
                            .foregroundStyle(Palette.onSurface(scheme))
                        Text("Plain-English explanation of the settings below and what the app can tell you once they're filled in.")
                            .font(.wbBodySmall)
                            .foregroundStyle(Palette.onSurfaceVariant(scheme))
                            .fixedSize(horizontal: false, vertical: true)
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

    // MARK: - Residency

    private var residencyCard: some View {
        WbCard {
            Text("Where you file")
                .font(.wbTitleMedium)
                .foregroundStyle(Palette.onSurface(scheme))
                .padding(.bottom, 4)

            Text("This decides which dividends count as foreign, and whether the app can put a number on tax withheld abroad.")
                .font(.wbBodySmall)
                .foregroundStyle(Palette.onSurfaceVariant(scheme))
                .padding(.bottom, 12)

            Menu {
                ForEach(Residency.allCases) { option in
                    Button(option.label) { viewModel.residency = option }
                }
            } label: {
                HStack {
                    Text(viewModel.residency.label)
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

            // Where the current answer came from, shown only while it is still
            // the app's guess. Choosing one above clears the flag and the note
            // goes with it — an answer the user gave needs no provenance.
            if viewModel.isResidencyAuto {
                VStack(alignment: .leading, spacing: 2) {
                    Text(viewModel.isResidencyFromAccounts
                         ? "Set from your account types (\(viewModel.residency.label))."
                         : "Set from your device's region (\(viewModel.residency.label)).")
                        .font(.wbBodySmall)
                        .fontWeight(.medium)
                        .foregroundStyle(Palette.onSurface(scheme))
                    Text("Change it above if you file somewhere else.")
                        .font(.system(size: 11))
                        .foregroundStyle(Palette.onSurfaceVariant(scheme))
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(12)
                .background(
                    Palette.accent(scheme).opacity(0.14),
                    in: RoundedRectangle(cornerRadius: 10, style: .continuous)
                )
                .padding(.top, 10)
            } else if let suggested = viewModel.suggestedResidency(),
                      suggested != viewModel.residency {
                // The way back, for someone who changed it by mistake or moved.
                Button {
                    viewModel.resetResidencyToDevice()
                } label: {
                    Text("Use my device's region (\(suggested.label))")
                        .font(.wbBodySmall)
                        .foregroundStyle(Palette.accent(scheme))
                }
                .buttonStyle(.plain)
                .padding(.top, 10)
            }

            if !viewModel.residency.modelsWithholding, viewModel.residency != .other {
                Text("The app can classify your accounts and say what's taxed when, but it doesn't model \(viewModel.residency.label)'s treaty rates — so it stays silent about foreign withholding rather than inventing a figure.")
                    .font(.system(size: 11))
                    .foregroundStyle(Palette.onSurfaceVariant(scheme))
                    .padding(.top, 10)
            }
        }
    }

    // MARK: - Accounts

    private var accountsCard: some View {
        WbCard {
            Text("Your accounts")
                .font(.wbTitleMedium)
                .foregroundStyle(Palette.onSurface(scheme))
                .padding(.bottom, 4)

            Text("An account with no tax type set disables every tax figure for everything inside it.")
                .font(.wbBodySmall)
                .foregroundStyle(Palette.onSurfaceVariant(scheme))
                .padding(.bottom, 12)

            if viewModel.accounts.isEmpty {
                Text("No accounts yet. One is created the first time you record a transaction.")
                    .font(.wbBodySmall)
                    .foregroundStyle(Palette.onSurfaceVariant(scheme))
            } else {
                ForEach(viewModel.accounts) { account in
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(account.displayName)
                                .font(.wbBodyLarge)
                                .foregroundStyle(Palette.onSurface(scheme))
                            Text(account.taxTreatment?.detail ?? "Tax type not set")
                                .font(.wbBodySmall)
                                .foregroundStyle(
                                    account.taxTreatment == nil
                                        ? Brand.divAmber
                                        : Palette.onSurfaceVariant(scheme)
                                )
                        }
                        Spacer()
                        Menu {
                            ForEach(TaxTreatment.allCases) { option in
                                Button("\(option.label) — \(TaxRules.examples(for: option, residency: viewModel.residency))") {
                                    Task { await viewModel.setAccountTaxTreatment(account.id, option) }
                                }
                            }
                            Divider()
                            Button("Not set", role: .destructive) {
                                Task { await viewModel.setAccountTaxTreatment(account.id, nil) }
                            }
                        } label: {
                            Text(account.taxTreatment?.label ?? "Set")
                                .font(.wbBodySmall)
                                .fontWeight(.semibold)
                                .foregroundStyle(Palette.accent(scheme))
                                .padding(.horizontal, 12)
                                .padding(.vertical, 6)
                                .background(
                                    Capsule().fill(Palette.accent(scheme).opacity(0.15))
                                )
                        }
                    }
                    .padding(.vertical, 8)
                    if account.id != viewModel.accounts.last?.id { WbDivider() }
                }
            }
        }
    }

    // MARK: - Rates

    private var ratesCard: some View {
        WbCard {
            Text("Your rates")
                .font(.wbTitleMedium)
                .foregroundStyle(Palette.onSurface(scheme))
                .padding(.bottom, 4)

            // Asked for rather than derived. Computing them would mean shipping
            // federal AND provincial/state brackets, knowing the user's total
            // income from every other source, and re-checking all of it every
            // year — a confident number that is quietly wrong is worse than no
            // number, and going stale is a certainty rather than a risk.
            Text("Optional. Without them the app still warns about tax withheld abroad — it just can't estimate what you'd owe at home.")
                .font(.wbBodySmall)
                .foregroundStyle(Palette.onSurfaceVariant(scheme))
                .padding(.bottom, 12)

            rateField(
                label: TaxRules.marginalRateLabel(viewModel.residency),
                hint: "The rate on your next dollar of ordinary income. Foreign dividends and interest are taxed at this.",
                text: $marginalText
            ) { value in
                viewModel.taxRates = TaxRules.UserRates(
                    marginalPct: value,
                    preferentialPct: viewModel.taxRates.preferentialPct
                )
            }

            WbDivider().padding(.vertical, 12)

            rateField(
                label: TaxRules.preferentialRateLabel(viewModel.residency),
                hint: TaxRules.preferentialRateHint(viewModel.residency),
                text: $preferentialText
            ) { value in
                viewModel.taxRates = TaxRules.UserRates(
                    marginalPct: viewModel.taxRates.marginalPct,
                    preferentialPct: value
                )
            }
        }
    }

    private func rateField(
        label: String,
        hint: String,
        text: Binding<String>,
        onCommit: @escaping (Double?) -> Void
    ) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label)
                .font(.wbBodyLarge)
                .foregroundStyle(Palette.onSurface(scheme))

            HStack {
                TextField("—", text: text)
                    .keyboardType(.decimalPad)
                    .font(.wbTitleMedium)
                    .onChange(of: text.wrappedValue) { newValue in
                        onCommit(Double(newValue))
                    }
                Text("%")
                    .font(.wbTitleMedium)
                    .foregroundStyle(Palette.onSurfaceVariant(scheme))
            }
            .padding(12)
            .background(Palette.surfaceVariant(scheme).opacity(0.35))
            .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))

            Text(hint)
                .font(.system(size: 11))
                .foregroundStyle(Palette.onSurfaceVariant(scheme))
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    // MARK: - Explainer

    private var explainerCard: some View {
        WbCard {
            Text("How tax works here")
                .font(.wbTitleMedium)
                .foregroundStyle(Palette.onSurface(scheme))
                .padding(.bottom, 10)

            explainer(
                "Withholding is invisible, which is why it's worth showing",
                "A foreign company deducts tax before the dividend reaches you. It never appears on a statement as a line item, so the money is gone without anyone seeing it go."
            )
            explainer(
                "A sheltered account doesn't always help",
                "In a taxable account that deduction is usually reclaimable as a foreign tax credit. In a tax-free account there's no domestic tax to credit it against, so the same deduction becomes permanent."
            )
            explainer(
                "And sometimes it helps enormously",
                "The Canada–US treaty exempts an RRSP from US withholding entirely. A TFSA holding the same US stock is withheld at 15% and can't claim any of it back. That difference is what these notes exist to surface."
            )
            explainer(
                "Everything here is an estimate",
                "Treaty rates assume your broker holds your residency paperwork. Without it the statutory rate applies instead, which is materially higher."
            )
        }
    }

    private func explainer(_ title: String, _ body: String) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(title)
                .font(.wbBodyLarge)
                .fontWeight(.semibold)
                .foregroundStyle(Palette.onSurface(scheme))
            Text(body)
                .font(.wbBodySmall)
                .foregroundStyle(Palette.onSurfaceVariant(scheme))
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.bottom, 12)
    }

    private var disclaimerCard: some View {
        Text(TaxRules.disclaimer)
            .font(.system(size: 11))
            .foregroundStyle(Palette.onSurfaceVariant(scheme))
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(14)
            .background(Palette.surfaceVariant(scheme).opacity(0.4))
            .clipShape(RoundedRectangle(cornerRadius: WbDimens.cardRadius, style: .continuous))
    }
}
