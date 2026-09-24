import SwiftUI

/// Every buy, sell and DRIP recorded against the portfolio, newest first and
/// grouped by month.
///
/// Deleting a row reverses its effect on the position, so a mistyped entry can
/// be corrected rather than left to skew cost basis forever.
struct TransactionHistoryView: View {
    @EnvironmentObject private var viewModel: PortfolioViewModel
    @Environment(\.colorScheme) private var scheme

    @State private var pendingDelete: PortfolioTransaction?

    /// Newest first, grouped by calendar month for readable section headers.
    private var months: [(key: String, rows: [PortfolioTransaction])] {
        let sorted = viewModel.transactions.sorted { $0.at > $1.at }
        var order: [String] = []
        var buckets: [String: [PortfolioTransaction]] = [:]
        for tx in sorted {
            let key = Self.monthFormatter.string(from: tx.at)
            if buckets[key] == nil {
                buckets[key] = []
                order.append(key)
            }
            buckets[key]?.append(tx)
        }
        return order.map { ($0, buckets[$0] ?? []) }
    }

    var body: some View {
        Group {
            if viewModel.transactions.isEmpty {
                VStack {
                    Text("No transactions yet. Buys, sells and DRIPs you record will appear here.")
                        .font(.wbBodyMedium)
                        .foregroundStyle(Palette.onSurfaceVariant(scheme))
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 32)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(Palette.background(scheme).ignoresSafeArea())
            } else {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 0) {
                        ForEach(months, id: \.key) { month in
                            Text(month.key)
                                .font(.system(size: 13, weight: .bold))
                                .foregroundStyle(Palette.onSurfaceVariant(scheme))
                                .padding(.horizontal, WbDimens.screenPadding)
                                .padding(.top, 16)
                                .padding(.bottom, 6)

                            ForEach(month.rows) { tx in
                                TransactionRow(
                                    transaction: tx,
                                    label: label(for: tx),
                                    onDelete: { pendingDelete = tx }
                                )
                                WbDivider().padding(.horizontal, WbDimens.screenPadding)
                            }
                        }
                        Color.clear.frame(height: 28)
                    }
                }
                .wbScreenBackground(scheme)
            }
        }
        .navigationTitle("Transaction History")
        .navigationBarTitleDisplayMode(.inline)
        .alert(
            "Delete transaction?",
            isPresented: Binding(
                get: { pendingDelete != nil },
                set: { if !$0 { pendingDelete = nil } }
            ),
            presenting: pendingDelete
        ) { tx in
            Button("Delete", role: .destructive) {
                Task { await viewModel.deleteTransaction(tx.id) }
                pendingDelete = nil
            }
            Button("Cancel", role: .cancel) { pendingDelete = nil }
        } message: { tx in
            let direction = tx.type == .sell ? "added back to" : "removed from"
            Text("This reverses its effect on your position — \(Money.units(tx.shares)) units will be \(direction) the holding, and the cost basis adjusted.")
        }
    }

    private func label(for tx: PortfolioTransaction) -> String {
        guard let holding = viewModel.holdings.first(where: { $0.id == tx.holdingId }) else {
            return "Unknown holding"
        }
        if let ticker = holding.ticker, !ticker.isEmpty { return ticker }
        return holding.name
    }

    private static let monthFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "MMMM yyyy"
        return f
    }()
}

// MARK: - Row

/// One transaction. Shared with the holding detail screen's Activity section,
/// so a buy reads identically wherever it is shown.
struct TransactionRow: View {
    @Environment(\.colorScheme) private var scheme

    let transaction: PortfolioTransaction
    let label: String
    var onDelete: (() -> Void)?

    private var tint: Color {
        switch transaction.type {
        case .buy: return Brand.gain
        case .sell: return Brand.loss
        case .drip: return Brand.divIndigo
        }
    }

    var body: some View {
        HStack(spacing: 12) {
            Text(transaction.type.label.uppercased())
                .font(.system(size: 10, weight: .bold))
                .foregroundStyle(tint)
                .padding(.horizontal, 8)
                .padding(.vertical, 4)
                .background(tint.opacity(0.14))
                .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))

            VStack(alignment: .leading, spacing: 2) {
                Text(label)
                    .font(.wbBodyLarge)
                    .fontWeight(.semibold)
                    .foregroundStyle(Palette.onSurface(scheme))
                    .lineLimit(1)
                Text("\(Money.units(transaction.shares)) @ \(Money.plain(transaction.pricePerShare)) · \(Self.dateFormatter.string(from: transaction.at))")
                    .font(.wbBodySmall)
                    .foregroundStyle(Palette.onSurfaceVariant(scheme))
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            VStack(alignment: .trailing, spacing: 2) {
                Text((transaction.type == .sell ? "-" : "+") + Money.plain(transaction.amount))
                    .font(.wbBodyLarge)
                    .fontWeight(.bold)
                    .foregroundStyle(tint)
                Text(transaction.currency)
                    .font(.wbBodySmall)
                    .foregroundStyle(Palette.onSurfaceVariant(scheme))
            }

            if let onDelete {
                Button(action: onDelete) {
                    Image(systemName: "trash")
                        .font(.system(size: 15))
                        .foregroundStyle(Palette.onSurfaceVariant(scheme))
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Delete transaction")
            }
        }
        .padding(.horizontal, WbDimens.screenPadding)
        .padding(.vertical, 11)
    }

    private static let dateFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "MMM d, yyyy"
        return f
    }()
}
