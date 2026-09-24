import SwiftUI

/// Logs a dividend the user actually received.
///
/// Entered as a total or per unit, whichever the statement shows: brokers print
/// one or the other, and making someone divide by their share count before they
/// can record a payment is how a log stops being kept.
struct AddDividendView: View {
    @EnvironmentObject private var viewModel: PortfolioViewModel
    @Environment(\.colorScheme) private var scheme
    @Environment(\.dismiss) private var dismiss

    let holding: Holding

    private enum Basis: String, CaseIterable, Identifiable {
        case total = "Total received"
        case perUnit = "Per unit"
        var id: String { rawValue }
    }

    @State private var basis: Basis = .total
    @State private var amountText = ""
    @State private var date = Date()
    @State private var note = ""
    @State private var reinvest = false
    @State private var showDripConfirm = false
    @State private var reinvestPriceText = ""

    private var currency: String { holding.normalizedCurrency }

    private var enteredAmount: Double { Double(amountText) ?? 0 }

    private var totalAmount: Double {
        basis == .total ? enteredAmount : enteredAmount * holding.units
    }

    private var perUnitAmount: Double? {
        if basis == .perUnit { return enteredAmount > 0 ? enteredAmount : nil }
        return holding.units > 0 ? enteredAmount / holding.units : nil
    }

    private var reinvestPrice: Double { Double(reinvestPriceText) ?? 0 }

    var body: some View {
        ScrollView {
            VStack(spacing: 14) {
                holdingCard
                amountCard
                reinvestCard
                submitButton
                Color.clear.frame(height: 30)
            }
            .padding(.horizontal, WbDimens.screenPadding)
            .padding(.top, 8)
        }
        .wbScreenBackground(scheme)
        .navigationTitle("Log a dividend")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(isPresented: $showDripConfirm, onDismiss: { dismiss() }) {
            DripConfirmSheet(
                tickerLabel: holding.ticker ?? holding.name,
                dividendAmount: totalAmount,
                currency: currency,
                suggestedPrice: viewModel.currentPrice(for: holding),
                onKeepAsCash: {},
                onConfirm: { shares, price in
                    Task {
                        await viewModel.addTransaction(
                            holdingId: holding.id,
                            type: .drip,
                            at: date,
                            shares: shares,
                            pricePerShare: price,
                            note: "Reinvested dividend"
                        )
                    }
                }
            )
        }
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("Cancel") { dismiss() }
            }
        }
    }

    private var holdingCard: some View {
        WbCard {
            HStack(spacing: 12) {
                TickerLogo(
                    logoURL: QuoteClient.logoURL(ticker: holding.ticker ?? holding.name, name: holding.name),
                    label: holding.ticker ?? holding.name,
                    size: 36
                )
                VStack(alignment: .leading, spacing: 2) {
                    Text(holding.name)
                        .font(.wbBodyLarge)
                        .fontWeight(.semibold)
                        .foregroundStyle(Palette.onSurface(scheme))
                        .lineLimit(1)
                    Text("\(Money.units(holding.units)) units · \(currency)")
                        .font(.wbBodySmall)
                        .foregroundStyle(Palette.onSurfaceVariant(scheme))
                }
                Spacer(minLength: 0)
            }
        }
    }

    private var amountCard: some View {
        WbCard {
            Picker("Basis", selection: $basis) {
                ForEach(Basis.allCases) { option in
                    Text(option.rawValue).tag(option)
                }
            }
            .pickerStyle(.segmented)
            .padding(.bottom, 14)

            // The app's one date control, shared with Add Transaction and with
            // the Android build.
            WheelDateField("Paid on", date: $date, maxDate: Date())
                .padding(.bottom, 12)

            HStack {
                Text(Money.symbol(currency))
                    .font(.wbTitleMedium)
                    .foregroundStyle(Palette.onSurfaceVariant(scheme))
                TextField(basis == .total ? "0.00" : "0.0000", text: $amountText)
                    .keyboardType(.decimalPad)
                    .font(.wbTitleLarge)
            }
            .padding(12)
            .background(Palette.surfaceVariant(scheme).opacity(0.35))
            .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))

            if enteredAmount > 0 {
                // Whichever half the user did not type is shown back to them,
                // so a slip of the decimal point is visible before it is saved.
                Text(basis == .total
                     ? "\(Money.perUnit(perUnitAmount ?? 0, currency)) per unit across \(Money.units(holding.units)) units"
                     : "\(Money.format(totalAmount, currency)) in total")
                    .font(.wbBodySmall)
                    .foregroundStyle(Palette.onSurfaceVariant(scheme))
                    .padding(.top, 8)
            }

            TextField("Note (optional)", text: $note)
                .font(.wbBodyMedium)
                .padding(12)
                .background(Palette.surfaceVariant(scheme).opacity(0.35))
                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                .padding(.top, 10)
        }
    }

    @ViewBuilder
    private var reinvestCard: some View {
        WbCard {
            Toggle(isOn: $reinvest) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Reinvested it")
                        .font(.wbBodyLarge)
                        .foregroundStyle(Palette.onSurface(scheme))
                    Text("Records a DRIP alongside the payment, so units and cost basis both move.")
                        .font(.wbBodySmall)
                        .foregroundStyle(Palette.onSurfaceVariant(scheme))
                }
            }
            .tint(Palette.accent(scheme))

            if reinvest {
                TextField("Price paid per share, \(currency)", text: $reinvestPriceText)
                    .keyboardType(.decimalPad)
                    .padding(12)
                    .background(Palette.surfaceVariant(scheme).opacity(0.35))
                    .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                    .padding(.top, 12)

                if reinvestPrice > 0, totalAmount > 0 {
                    KeyValueRow(
                        label: "Units bought",
                        value: Money.units(totalAmount / reinvestPrice)
                    )
                }
            }
        }
    }

    private var submitButton: some View {
        Button {
            Task { await submit() }
        } label: {
            Text("Save payment")
                .font(.wbBodyLarge)
                .fontWeight(.bold)
                .foregroundStyle(Palette.onAccent(scheme))
                .frame(maxWidth: .infinity)
                .frame(height: 50)
                .background(Palette.accent(scheme), in: Capsule())
        }
        .buttonStyle(.plain)
        .disabled(!canSubmit)
        .opacity(canSubmit ? 1 : 0.5)
    }

    private var canSubmit: Bool {
        totalAmount > 0 && (!reinvest || reinvestPrice > 0)
    }

    private func submit() async {
        guard canSubmit else { return }

        await saveDividend()

        if reinvest, reinvestPrice > 0 {
            // The reinvestment is its own ledger row: the dividend records what
            // was received, the DRIP records what it bought. Keeping them apart
            // is what lets the income charts and the cost basis both stay right.
            await viewModel.addTransaction(
                holdingId: holding.id,
                type: .drip,
                at: date,
                shares: totalAmount / reinvestPrice,
                pricePerShare: reinvestPrice,
                note: "Reinvested dividend"
            )
            dismiss()
        } else if viewModel.dripEnabled {
            // DRIP is on but this payment wasn't marked reinvested inline, so
            // ask. A broker's fill price and unit count are things only the
            // user knows, and inventing them at the market price is how a cost
            // basis quietly drifts.
            showDripConfirm = true
        } else {
            dismiss()
        }
    }

    private func saveDividend() async {
        await viewModel.addDividend(
            holdingId: holding.id,
            paidAt: date,
            amount: totalAmount,
            perUnit: perUnitAmount,
            currency: currency,
            note: note.nilIfEmpty
        )
    }
}
