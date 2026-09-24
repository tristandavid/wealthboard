import SwiftUI

/// How one position splits across the accounts holding it.
///
/// The figures the holding screen shows are the whole position, because that is
/// what the user owns. This is where those figures come apart again: what each
/// account paid, what it is worth now, and how it is taxed — which is the one
/// thing that genuinely differs between a TFSA slice and an RRSP slice of the
/// same fund.
///
/// Each card opens the holding row behind it, so editing the RRSP slice or
/// recording a sell against it is still one tap away.
struct AccountBreakdownView: View {
    @EnvironmentObject private var viewModel: PortfolioViewModel
    @Environment(\.colorScheme) private var scheme

    let securityKey: String

    private var position: SecurityPosition? {
        viewModel.position(forSecurity: securityKey)
    }

    var body: some View {
        ScrollView {
            if let position {
                VStack(spacing: 14) {
                    header(position)
                    ForEach(position.slices) { slice in
                        NavigationLink {
                            HoldingDetailView(holdingId: slice.holding.id)
                        } label: {
                            card(slice, in: position)
                        }
                        .buttonStyle(.plain)
                    }
                    Color.clear.frame(height: 24)
                }
                .padding(WbDimens.screenPadding)
            } else {
                EmptyNote(text: "This position is no longer in your portfolio.")
                    .padding(WbDimens.screenPadding)
            }
        }
        .wbScreenBackground(scheme)
        .navigationTitle("Breakdown by account")
        .navigationBarTitleDisplayMode(.large)
    }

    // MARK: - Header

    private func header(_ position: SecurityPosition) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(position.name)
                .font(.wbTitleMedium)
                .foregroundStyle(Palette.onSurface(scheme))
            Text("Total value of shares \(Money.format(position.marketValue, position.currency))")
                .font(.wbBodyMedium)
                .foregroundStyle(Palette.onSurfaceVariant(scheme))
            Text("\(Money.units(position.units)) units across \(position.accountCount) \(position.accountCount == 1 ? "account" : "accounts")")
                .font(.wbBodySmall)
                .foregroundStyle(Palette.onSurfaceVariant(scheme))
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    // MARK: - One account

    private func card(_ slice: AccountPosition, in position: SecurityPosition) -> some View {
        WbCard {
            HStack(alignment: .firstTextBaseline) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(slice.accountName)
                        .font(.wbTitleMedium)
                        .foregroundStyle(Palette.onSurface(scheme))
                        .lineLimit(2)
                    Text("\(Money.units(slice.units)) shares")
                        .font(.wbBodySmall)
                        .foregroundStyle(Palette.onSurfaceVariant(scheme))
                }
                Spacer(minLength: 8)
                Text(Money.format(slice.marketValue, slice.currency))
                    .font(.wbTitleMedium)
                    .fontWeight(.bold)
                    .foregroundStyle(Palette.onSurface(scheme))
            }
            .padding(.bottom, 10)

            WbDivider().padding(.bottom, 4)

            KeyValueRow(
                label: "Book cost",
                value: slice.costBasis.map { Money.format($0, slice.currency) } ?? "Not recorded"
            )
            KeyValueRow(
                label: "Average price",
                value: slice.averagePrice.map { Money.format($0, slice.currency) } ?? "—"
            )
            KeyValueRow(
                label: "% of position",
                value: Money.percent(position.share(of: slice) * 100, decimals: 1)
            )
            KeyValueRow(
                label: "Today's return",
                value: slice.todaysReturn.map {
                    "\(Money.signed($0, slice.currency))  (\(Money.signedPercent(slice.todaysReturnPercent)))"
                } ?? "—",
                valueColor: slice.todaysReturn.map { Palette.change($0) }
            )
            KeyValueRow(
                label: "Total return",
                value: slice.totalReturn.map {
                    "\(Money.signed($0, slice.currency))  (\(Money.signedPercent(slice.totalReturnPercent)))"
                } ?? "—",
                valueColor: slice.totalReturn.map { Palette.change($0) }
            )

            // The one figure that genuinely differs between two slices of the
            // same fund, and the reason this screen is worth having.
            WbDivider().padding(.vertical, 6)
            HStack {
                Text("Taxed")
                    .font(.wbBodyMedium)
                    .foregroundStyle(Palette.onSurfaceVariant(scheme))
                Spacer()
                if let treatment = slice.treatment {
                    StatusPill(text: treatment.label, color: Palette.accent(scheme))
                } else {
                    StatusPill(text: "Not set", color: Brand.divAmber)
                }
            }
        }
    }
}
