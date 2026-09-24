import SwiftUI

/// One account: how it is taxed, what is held in it, and what that comes to.
///
/// The tax picker sits above the holdings rather than behind a settings screen.
/// An unclassified account produces NO tax notes anywhere, which reads
/// identically to "there's nothing to say about this account" — so the question
/// is asked where the account is already on screen.
struct AccountView: View {
    @EnvironmentObject private var viewModel: PortfolioViewModel
    @Environment(\.colorScheme) private var scheme
    @Environment(\.dismiss) private var dismiss

    let accountId: UUID

    @State private var showDeleteConfirm = false
    @State private var showAddHolding = false

    private var account: Account? {
        viewModel.accounts.first { $0.id == accountId }
    }

    private var holdings: [Holding] {
        viewModel.holdings.filter { $0.accountId == accountId }
    }

    private var taxEnabled: Bool { viewModel.residency != .other }

    var body: some View {
        ScrollView {
            VStack(spacing: 12) {
                summaryCard
                if taxEnabled, let account {
                    treatmentCard(account)
                }
                holdingsSection
                Color.clear.frame(height: 30)
            }
            .padding(.horizontal, WbDimens.screenPadding)
            .padding(.top, 8)
        }
        .wbScreenBackground(scheme)
        .navigationTitle(account?.displayName ?? "Account")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Menu {
                    Button("Add holding") { showAddHolding = true }
                    Divider()
                    Button("Remove this account", role: .destructive) { showDeleteConfirm = true }
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
            }
        }
        .sheet(isPresented: $showAddHolding) {
            NavigationStack { ManualHoldingView(presetAccountId: accountId) }
        }
        .confirmationDialog(
            "Remove this account?",
            isPresented: $showDeleteConfirm,
            titleVisibility: .visible
        ) {
            Button("Remove", role: .destructive) {
                Task {
                    await viewModel.deleteAccount(accountId)
                    dismiss()
                }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("This removes it and every holding in it from the app.")
        }
    }

    // MARK: - Summary

    private var summaryCard: some View {
        let total = holdings.reduce(0.0) { $0 + viewModel.value(of: $1) }

        return WbCard {
            Text("Account value")
                .font(.wbBodySmall)
                .foregroundStyle(Palette.onSurfaceVariant(scheme))
            Text(Money.format(total, viewModel.baseCurrency))
                .font(.wbHeadline)
                .foregroundStyle(Palette.onSurface(scheme))
                .padding(.bottom, 4)
            Text("\(holdings.count) \(holdings.count == 1 ? "holding" : "holdings")")
                .font(.wbBodySmall)
                .foregroundStyle(Palette.onSurfaceVariant(scheme))
        }
    }

    // MARK: - Tax treatment

    /// The three choices are described by what happens to the money rather than
    /// by their product names, because the names are the part people get wrong
    /// — a TFSA is not a savings account and an RRSP is not a fund, and someone
    /// who doesn't know that can still answer "is this taxed now, later, or
    /// never?".
    private func treatmentCard(_ account: Account) -> some View {
        WbCard {
            Text("How is this account taxed?")
                .font(.wbTitleMedium)
                .foregroundStyle(Palette.onSurface(scheme))
                .padding(.bottom, 4)

            Text("Leave this unset and the app shows no tax information for anything held here, rather than guessing.")
                .font(.wbBodySmall)
                .foregroundStyle(Palette.onSurfaceVariant(scheme))
                .fixedSize(horizontal: false, vertical: true)
                .padding(.bottom, 10)

            ForEach(TaxTreatment.allCases) { treatment in
                Button {
                    Task { await viewModel.setAccountTaxTreatment(accountId, treatment) }
                } label: {
                    HStack(alignment: .top, spacing: 10) {
                        Image(systemName: account.taxTreatment == treatment
                            ? "largecircle.fill.circle"
                            : "circle")
                            .foregroundStyle(
                                account.taxTreatment == treatment
                                    ? Palette.accent(scheme)
                                    : Palette.onSurfaceVariant(scheme)
                            )
                            .padding(.top, 1)
                        VStack(alignment: .leading, spacing: 1) {
                            Text(treatment.label)
                                .font(.system(size: 13, weight: .medium))
                                .foregroundStyle(Palette.onSurface(scheme))
                            Text(TaxRules.examples(for: treatment, residency: viewModel.residency))
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

    // MARK: - Holdings

    @ViewBuilder
    private var holdingsSection: some View {
        if holdings.isEmpty {
            EmptyNote(text: "No holdings yet — add one from the menu above.")
        } else {
            WbCard(padding: 0) {
                ForEach(Array(holdings.enumerated()), id: \.element.id) { index, holding in
                    if index > 0 { WbDivider() }
                    NavigationLink {
                        HoldingDetailView(holdingId: holding.id)
                    } label: {
                        holdingRow(holding)
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }

    private func holdingRow(_ holding: Holding) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 4) {
                Text(holding.name)
                    .font(.wbBodyLarge)
                    .fontWeight(.semibold)
                    .foregroundStyle(Palette.onSurface(scheme))
                    .lineLimit(1)
                HStack(spacing: 6) {
                    TypeBadge(label: holding.type.label)
                    Text("\(Money.units(holding.units)) units"
                        + (holding.ticker.map { " · \($0)" } ?? ""))
                        .font(.wbBodySmall)
                        .foregroundStyle(Palette.onSurfaceVariant(scheme))
                        .lineLimit(1)
                }
            }
            Spacer(minLength: 8)
            // The holding's own currency, not a hardcoded Canadian one: this
            // row is what the position is worth on its own statement.
            Text(Money.format(holding.nativeValue, holding.normalizedCurrency))
                .font(.wbBodyLarge)
                .fontWeight(.semibold)
                .foregroundStyle(Palette.onSurface(scheme))
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 14)
        .contentShape(Rectangle())
    }
}

// MARK: - Type badge

/// Small tinted label for a holding's instrument type.
struct TypeBadge: View {
    @Environment(\.colorScheme) private var scheme
    let label: String

    var body: some View {
        Text(label)
            .font(.wbBodySmall)
            .fontWeight(.medium)
            .foregroundStyle(Palette.accent(scheme))
            .padding(.horizontal, 6)
            .padding(.vertical, 1)
            .background(Palette.accent(scheme).opacity(0.15))
            .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
    }
}
