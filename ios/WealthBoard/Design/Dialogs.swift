import SwiftUI

// The two modal decisions the app asks for: how a dividend was reinvested, and
// whether to watch an ad for a gated feature. Ported from `DripDialog.kt` and
// `PremiumGateDialog.kt`.

// MARK: - DRIP

/// Confirms how a received dividend was reinvested before it changes a position.
///
/// A broker's DRIP fills at a price the app can't know — often a
/// volume-weighted average, sometimes at a discount, and usually in fractional
/// units. Rather than silently inventing units at the current market price,
/// this asks for the price and unit count actually filled, pre-filling a
/// sensible estimate to correct. "Keep as cash" leaves the payment recorded as
/// income and the position untouched, which is the right answer when the
/// dividend was paid out rather than reinvested.
struct DripConfirmSheet: View {
    @Environment(\.colorScheme) private var scheme
    @Environment(\.dismiss) private var dismiss

    let tickerLabel: String
    let dividendAmount: Double
    let currency: String
    let suggestedPrice: Double?

    let onKeepAsCash: () -> Void
    let onConfirm: (_ shares: Double, _ pricePerShare: Double) -> Void

    @State private var priceText = ""
    @State private var sharesText = ""

    private var price: Double { Double(priceText) ?? 0 }

    /// Units default to the whole payment divided by the price; the user can
    /// override when their broker only bought whole shares.
    private var derivedShares: Double {
        price > 0 ? dividendAmount / price : 0
    }

    private var shares: Double {
        Double(sharesText) ?? derivedShares
    }

    private var used: Double { shares * price }
    private var leftover: Double { dividendAmount - used }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    Text("\(Money.format(dividendAmount, currency)) received. Confirm the price and units your broker actually filled.")
                        .font(.wbBodyMedium)
                        .foregroundStyle(Palette.onSurface(scheme))
                        .fixedSize(horizontal: false, vertical: true)

                    WbCard {
                        Text("Price per share, \(currency)")
                            .font(.wbBodySmall)
                            .foregroundStyle(Palette.onSurfaceVariant(scheme))
                        TextField("0.00", text: $priceText)
                            .keyboardType(.decimalPad)
                            .padding(.bottom, 10)

                        WbDivider().padding(.bottom, 10)

                        Text("Units acquired")
                            .font(.wbBodySmall)
                            .foregroundStyle(Palette.onSurfaceVariant(scheme))
                        TextField(
                            derivedShares > 0 ? Money.units(derivedShares) : "0",
                            text: $sharesText
                        )
                        .keyboardType(.decimalPad)
                    }

                    if price > 0, shares > 0 {
                        VStack(alignment: .leading, spacing: 4) {
                            Text("Reinvesting \(Money.format(used, currency))")
                                .font(.wbBodySmall)
                                .foregroundStyle(Palette.onSurface(scheme))

                            if leftover > 0.005 {
                                Text("\(Money.format(leftover, currency)) left over — stays recorded as cash income.")
                                    .font(.wbBodySmall)
                                    .foregroundStyle(Palette.onSurfaceVariant(scheme))
                                    .fixedSize(horizontal: false, vertical: true)
                            } else if leftover < -0.005 {
                                Text("That's more than the dividend paid. Check the numbers.")
                                    .font(.wbBodySmall)
                                    .fontWeight(.medium)
                                    .foregroundStyle(Brand.loss)
                            }
                        }
                    }

                    Button {
                        onConfirm(shares, price)
                        dismiss()
                    } label: {
                        Text("Add to holding")
                            .font(.wbBodyLarge)
                            .fontWeight(.semibold)
                            .foregroundStyle(Palette.onAccent(scheme))
                            .frame(maxWidth: .infinity)
                            .frame(height: 48)
                            .background(
                                (price > 0 && shares > 0)
                                    ? Palette.accent(scheme)
                                    : Palette.surfaceVariant(scheme),
                                in: Capsule()
                            )
                    }
                    .buttonStyle(.plain)
                    .disabled(price <= 0 || shares <= 0)

                    Button("Keep as cash") {
                        onKeepAsCash()
                        dismiss()
                    }
                    .font(.wbBodyMedium)
                    .foregroundStyle(Palette.onSurfaceVariant(scheme))
                    .frame(maxWidth: .infinity)
                }
                .padding(WbDimens.screenPadding)
            }
            .wbScreenBackground(scheme)
            .navigationTitle("Reinvest \(tickerLabel) dividend")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
        .onAppear {
            if priceText.isEmpty, let suggestedPrice {
                priceText = String(format: "%.2f", suggestedPrice)
            }
        }
    }
}

