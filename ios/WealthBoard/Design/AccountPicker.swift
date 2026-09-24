import SwiftUI

/// The account chip row, shared by every screen that has to put a holding
/// somewhere: add/edit holding, and the transaction form when it is creating a
/// new position.
///
/// The tax treatment is printed on the chip itself, and tapping the row below
/// it changes that treatment in place. The choice is not decoration — it drives
/// every tax figure for everything in the account — and burying it in a
/// separate settings screen is how it ends up never being set at all.
struct AccountPicker: View {
    @EnvironmentObject private var viewModel: PortfolioViewModel
    @Environment(\.colorScheme) private var scheme

    @Binding var selectedId: UUID?

    @State private var showNewAccount = false
    @State private var showAccountLimit = false
    @State private var showPremium = false
    @State private var editingTreatmentFor: Account?

    /// Past `PremiumLimits.freeAccountLimit`, creating another account is a
    /// Premium feature — see `canCreateAccount` below.
    @ObservedObject private var subscription = SubscriptionSession.shared

    private var selectedAccount: Account? {
        viewModel.accounts.first { $0.id == selectedId }
    }

    private var canCreateAccount: Bool {
        PremiumLimits.canCreateAccount(
            currentCount: viewModel.accounts.count,
            isPremium: subscription.isPremium
        )
    }

    var body: some View {
        WbCard {
            Text("Account")
                .font(.system(size: 14, weight: .medium))
                .foregroundStyle(Palette.onSurface(scheme))
                .padding(.bottom, 10)

            chips

            if let account = selectedAccount {
                WbDivider().padding(.vertical, 10)
                Button {
                    editingTreatmentFor = account
                } label: {
                    HStack {
                        VStack(alignment: .leading, spacing: 1) {
                            Text("Tax treatment")
                                .font(.wbBodySmall)
                                .foregroundStyle(Palette.onSurfaceVariant(scheme))
                            Text(account.taxTreatment?.label ?? "Not set")
                                .font(.wbBodyMedium)
                                .fontWeight(.semibold)
                                .foregroundStyle(
                                    account.taxTreatment == nil
                                        ? Brand.divAmber
                                        : Palette.onSurface(scheme)
                                )
                        }
                        Spacer()
                        Text("Change")
                            .font(.wbBodySmall)
                            .fontWeight(.medium)
                            .foregroundStyle(Palette.accent(scheme))
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
        }
        .sheet(isPresented: $showNewAccount) {
            NewAccountSheet { id in selectedId = id }
        }
        .sheet(item: $editingTreatmentFor) { account in
            TreatmentSheet(account: account)
        }
        .sheet(isPresented: $showPremium) {
            NavigationStack { PremiumView() }
        }
        .alert("Account limit reached", isPresented: $showAccountLimit) {
            Button("See Premium") { showPremium = true }
            Button("Not now", role: .cancel) { }
        } message: {
            Text("Free installs keep up to \(PremiumLimits.freeAccountLimit) accounts — enough for one taxable account and one registered one. Premium removes the limit, so every account you actually hold can be tracked with its own tax treatment.\n\nNothing already entered is affected: your existing accounts, holdings and history stay exactly as they are.")
        }
    }

    /// The chips WRAP rather than scroll sideways, in equal columns.
    ///
    /// They were a horizontal `ScrollView`, which hid whatever didn't fit —
    /// and what didn't fit was "+ New", the one chip a first-time user needs
    /// most. A row you have to discover by swiping is a row most people never
    /// find, and there was nothing on screen to suggest more existed off the
    /// right edge.
    ///
    /// Wrapping came next, each chip its natural width, and that left the last
    /// line short: "Non-registered" and "+ New" sat against the left edge with
    /// a third of the card empty beside them, which reads as a layout that has
    /// come apart rather than as a row that happened to end. Equal columns fill
    /// the card on every line, at the cost of a little air inside the narrow
    /// chips — a trade worth making for a control of three to six fixed
    /// options that is read as a block.
    private var chips: some View {
        LazyVGrid(columns: AccountChipGrid.columns, spacing: 8) {
            Group {
                ForEach(viewModel.accounts) { account in
                    let selected = account.id == selectedId
                    Button {
                        selectedId = account.id
                    } label: {
                        VStack(spacing: 2) {
                            Text(account.displayName)
                                .font(.system(size: 12, weight: selected ? .bold : .medium))
                            Text(account.taxTreatment?.label ?? "Tax type not set")
                                .font(.system(size: 9))
                                .opacity(0.8)
                        }
                        .lineLimit(1)
                        .minimumScaleFactor(0.75)
                        .foregroundStyle(selected ? Palette.onAccent(scheme) : Palette.onSurfaceVariant(scheme))
                        .padding(.horizontal, 8)
                        .padding(.vertical, 8)
                        .frame(maxWidth: .infinity)
                        .background(
                            RoundedRectangle(cornerRadius: 8, style: .continuous)
                                .fill(selected ? Palette.accent(scheme) : Palette.surfaceVariant(scheme).opacity(0.5))
                        )
                    }
                    .buttonStyle(.plain)
                }

                Button {
                    // Still tappable at the limit, and deliberately: a
                    // disabled chip explains nothing, whereas the alert
                    // below says what the limit is and what lifts it.
                    if canCreateAccount { showNewAccount = true } else { showAccountLimit = true }
                } label: {
                    Text(canCreateAccount ? "+ New" : "★ New")
                        .font(.system(size: 11, weight: .medium))
                        .foregroundStyle(Palette.accent(scheme))
                        .padding(.horizontal, 8)
                        .padding(.vertical, 8)
                        .frame(maxWidth: .infinity, minHeight: 38)
                        .background(
                            RoundedRectangle(cornerRadius: 8, style: .continuous)
                                .fill(Palette.accent(scheme).opacity(0.18))
                        )
                }
                .buttonStyle(.plain)
            }
        }
    }
}

// MARK: - Chip grid

/// The column definition the account chips are laid out on, in one place so the
/// picker and the transaction form cannot drift apart.
///
/// Three columns rather than two or four: two makes a four-account user scroll
/// for something that fits on one screen, four squeezes "Non-registered" past
/// the point where shrinking the label saves it.
enum AccountChipGrid {
    static let columns = [
        GridItem(.flexible(), spacing: 8),
        GridItem(.flexible(), spacing: 8),
        GridItem(.flexible(), spacing: 8)
    ]
}

// MARK: - Wrapping layout

/// Lays children out left to right, wrapping onto a new line when the next one
/// would not fit.
///
/// SwiftUI has no built-in flow layout — `HStack` overflows, `LazyVGrid` forces
/// equal columns — so this is the small `Layout` that does it. Written once
/// here because the account chips are not the only place the app needs it.
struct WrappingHStack: Layout {
    var spacing: CGFloat = 8
    var lineSpacing: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let maxWidth = proposal.width ?? .infinity
        let rows = layout(subviews: subviews, maxWidth: maxWidth)
        let height = rows.reduce(0) { $0 + $1.height } +
            lineSpacing * CGFloat(max(rows.count - 1, 0))
        let width = rows.map(\.width).max() ?? 0
        return CGSize(width: min(width, maxWidth), height: height)
    }

    func placeSubviews(
        in bounds: CGRect,
        proposal: ProposedViewSize,
        subviews: Subviews,
        cache: inout ()
    ) {
        let rows = layout(subviews: subviews, maxWidth: bounds.width)
        var y = bounds.minY
        for row in rows {
            var x = bounds.minX
            for index in row.indices {
                let size = subviews[index].sizeThatFits(.unspecified)
                subviews[index].place(
                    at: CGPoint(x: x, y: y + (row.height - size.height) / 2),
                    proposal: ProposedViewSize(size)
                )
                x += size.width + spacing
            }
            y += row.height + lineSpacing
        }
    }

    private struct Row {
        var indices: [Int] = []
        var width: CGFloat = 0
        var height: CGFloat = 0
    }

    private func layout(subviews: Subviews, maxWidth: CGFloat) -> [Row] {
        var rows: [Row] = []
        var current = Row()

        for index in subviews.indices {
            let size = subviews[index].sizeThatFits(.unspecified)
            let needed = current.indices.isEmpty ? size.width : current.width + spacing + size.width

            // A chip wider than the whole line still gets its own line rather
            // than being dropped.
            if needed > maxWidth, !current.indices.isEmpty {
                rows.append(current)
                current = Row()
                current.indices = [index]
                current.width = size.width
                current.height = size.height
            } else {
                current.indices.append(index)
                current.width = needed
                current.height = max(current.height, size.height)
            }
        }
        if !current.indices.isEmpty { rows.append(current) }
        return rows
    }
}

// MARK: - Treatment sheet

/// Changes one account's tax treatment. Presented as a sheet rather than an
/// alert because each option carries the real product names for the reader's
/// own country, and those do not fit an alert's body.
struct TreatmentSheet: View {
    @EnvironmentObject private var viewModel: PortfolioViewModel
    @Environment(\.colorScheme) private var scheme
    @Environment(\.dismiss) private var dismiss

    let account: Account

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    Text("This is what decides whether any tax figure shown against a holding in this account means anything. Leave it unset and the app says nothing about tax here rather than guessing.")
                        .font(.wbBodySmall)
                        .foregroundStyle(Palette.onSurfaceVariant(scheme))
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.bottom, 14)

                    ForEach(TaxTreatment.allCases) { treatment in
                        Button {
                            Task {
                                await viewModel.setAccountTaxTreatment(account.id, treatment)
                                dismiss()
                            }
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
                            .padding(.vertical, 10)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                    }

                    Button("Leave it unset") {
                        Task {
                            await viewModel.setAccountTaxTreatment(account.id, nil)
                            dismiss()
                        }
                    }
                    .font(.wbBodySmall)
                    .foregroundStyle(Palette.onSurfaceVariant(scheme))
                    .padding(.top, 10)
                }
                .padding(WbDimens.screenPadding)
            }
            .wbScreenBackground(scheme)
            .navigationTitle("How is \(account.displayName) taxed?")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
    }
}
