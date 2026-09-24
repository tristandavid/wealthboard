import SwiftUI

/// Add or edit a holding by hand.
///
/// Positions are normally built from transactions, so this is not the primary
/// way in — it exists for funds with no ticker, for correcting a position whose
/// history was never recorded, and for editing what an existing holding is
/// called or which account it sits in.
struct ManualHoldingView: View {
    @EnvironmentObject private var viewModel: PortfolioViewModel
    @Environment(\.colorScheme) private var scheme
    @Environment(\.dismiss) private var dismiss

    /// The holding being edited, or nil when adding a new one.
    var editing: UUID?
    /// Account a new holding should land in. Ignored when editing.
    var presetAccountId: UUID?

    @State private var name = ""
    @State private var ticker = ""
    @State private var type: HoldingType = .etf
    @State private var units = ""
    @State private var manualPrice = ""
    @State private var costBasis = ""
    @State private var selectedAccountId: UUID?
    @State private var preview: QuotePreview?
    @State private var pickedTicker: String?
    @State private var seeded = false
    @State private var saving = false

    @FocusState private var tickerFocused: Bool

    private var existing: Holding? { editing.flatMap { viewModel.holding($0) } }

    /// The suggestion list stays shut for as long as the field still holds a
    /// symbol the user has already resolved by tapping one.
    ///
    /// A one-shot boolean cleared inside the search task needs TWO taps to
    /// select anything whose symbol differs from what was typed: the tap
    /// changes the field, the task re-runs and clears the flag while the old
    /// results are still on screen, and the list reopens looking like nothing
    /// happened. Remembering the symbol rather than a flag fixes it.
    private var showSuggestions: Bool {
        !ticker.trimmingCharacters(in: .whitespaces).isEmpty
            && !viewModel.searchResults.isEmpty
            && ticker != pickedTicker
            && tickerFocused
    }

    private var canSave: Bool {
        let named = !name.trimmingCharacters(in: .whitespaces).isEmpty
            || !ticker.trimmingCharacters(in: .whitespaces).isEmpty
        // An account is not optional: it carries the tax treatment, and a
        // holding filed without one reports no tax at all.
        return named && selectedAccountId != nil
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 12) {
                tickerCard
                detailsCard
                if viewModel.residency.hasRegisteredAccounts {
                    // Same rule as the transaction form: no registered accounts
                    // in this country means no meaningful choice to present.
                    AccountPicker(selectedId: $selectedAccountId)
                }
                saveButton
                Color.clear.frame(height: 24)
            }
            .padding(WbDimens.screenPadding)
        }
        .wbScreenBackground(scheme)
        .navigationTitle(existing != nil ? "Edit holding" : "Add holding")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("Cancel") { dismiss() }
            }
        }
        .task { seed() }
        .task(id: ticker) { await searchAndPreview() }
    }

    // MARK: - Ticker

    private var tickerCard: some View {
        WbCard {
            TextField("Ticker (optional for funds)", text: $ticker)
                .textInputAutocapitalization(.characters)
                .autocorrectionDisabled()
                .focused($tickerFocused)
                .onChange(of: ticker) { newValue in
                    let upper = newValue.uppercased()
                    if upper != newValue { ticker = upper }
                }

            if showSuggestions {
                WbDivider().padding(.vertical, 8)
                // Capped so a long result list can't push the Save button off
                // screen; the list scrolls inside the cap.
                ScrollView {
                    VStack(spacing: 0) {
                        ForEach(viewModel.searchResults.prefix(12)) { result in
                            Button {
                                select(result)
                            } label: {
                                VStack(alignment: .leading, spacing: 2) {
                                    HStack(spacing: 6) {
                                        Text(result.symbol)
                                            .font(.wbBodyMedium)
                                            .fontWeight(.semibold)
                                            .foregroundStyle(Palette.onSurface(scheme))
                                        if let quoteType = result.quoteType {
                                            Text(quoteType)
                                                .font(.wbBodySmall)
                                                .foregroundStyle(Palette.onSurfaceVariant(scheme))
                                        }
                                    }
                                    let subtitle = [result.name, result.exchange]
                                        .compactMap { $0 }
                                        .joined(separator: " · ")
                                    if !subtitle.isEmpty {
                                        Text(subtitle)
                                            .font(.wbBodySmall)
                                            .foregroundStyle(Palette.onSurfaceVariant(scheme))
                                            .lineLimit(1)
                                    }
                                }
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .padding(.vertical, 10)
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                            WbDivider()
                        }
                    }
                }
                .frame(maxHeight: 240)
            }

            if let preview {
                Text(preview.found && preview.price != nil
                    ? "Live now: \(Money.plain(preview.price ?? 0))"
                        + (preview.name.map { " · \($0)" } ?? "")
                        + " — leave Price blank to keep tracking this automatically"
                    : "Couldn't find that ticker")
                    .font(.wbBodySmall)
                    .foregroundStyle(
                        preview.found ? Palette.onSurfaceVariant(scheme) : Brand.divAmber
                    )
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 8)
            }
        }
    }

    // MARK: - Details

    private var detailsCard: some View {
        WbCard {
            TextField("Name", text: $name)
                .padding(.bottom, 10)
            WbDivider().padding(.bottom, 10)

            HStack {
                Text("Type")
                    .font(.wbBodyMedium)
                    .foregroundStyle(Palette.onSurfaceVariant(scheme))
                Spacer()
                Picker("Type", selection: $type) {
                    ForEach(HoldingType.allCases) { option in
                        Text(option.label).tag(option)
                    }
                }
                .pickerStyle(.menu)
                .tint(Palette.accent(scheme))
            }
            .padding(.bottom, 10)
            WbDivider().padding(.bottom, 10)

            TextField("Units / shares", text: $units)
                .keyboardType(.decimalPad)
                .padding(.bottom, 10)
            WbDivider().padding(.bottom, 10)

            TextField("Price (leave blank for live/NAV)", text: $manualPrice)
                .keyboardType(.decimalPad)
                .padding(.bottom, 10)
            WbDivider().padding(.bottom, 10)

            TextField("Cost basis (optional, for yield on cost)", text: $costBasis)
                .keyboardType(.decimalPad)
        }
    }

    private var saveButton: some View {
        Button {
            Task { await save() }
        } label: {
            Text(existing != nil ? "Save changes" : "Save holding")
                .font(.wbBodyLarge)
                .fontWeight(.semibold)
                .foregroundStyle(Palette.onAccent(scheme))
                .frame(maxWidth: .infinity)
                .frame(height: 50)
                .background(
                    canSave ? Palette.accent(scheme) : Palette.surfaceVariant(scheme),
                    in: Capsule()
                )
        }
        .buttonStyle(.plain)
        .disabled(!canSave || saving)
    }

    // MARK: - Behaviour

    private func seed() {
        guard !seeded else { return }
        seeded = true
        if let existing {
            name = existing.name
            ticker = existing.ticker ?? ""
            pickedTicker = existing.ticker
            type = existing.type
            units = existing.units == 0 ? "" : Money.plain(existing.units, decimals: 4)
            manualPrice = existing.manualPrice.map { Money.plain($0) } ?? ""
            costBasis = existing.costBasis.map { Money.plain($0) } ?? ""
            selectedAccountId = existing.accountId
        } else {
            selectedAccountId = presetAccountId ?? viewModel.accounts.first?.id
        }
    }

    private func select(_ result: SymbolSearchResult) {
        ticker = result.symbol
        pickedTicker = result.symbol
        if name.trimmingCharacters(in: .whitespaces).isEmpty, let resultName = result.name {
            name = resultName
        }
        if let mapped = HoldingType.fromQuoteType(result.quoteType) {
            type = mapped
        }
        tickerFocused = false
        viewModel.clearSearch()
    }

    /// Debounced search plus a live quote lookup, so there is on-screen
    /// confirmation that the ticker resolves before anything is saved — rather
    /// than only finding out after the fact that the price was never wired up.
    private func searchAndPreview() async {
        let query = ticker.trimmingCharacters(in: .whitespaces)
        guard !query.isEmpty else {
            preview = nil
            viewModel.clearSearch()
            return
        }
        if query != pickedTicker {
            pickedTicker = nil
            viewModel.search(query)
        }

        try? await Task.sleep(nanoseconds: 400_000_000)
        guard !Task.isCancelled else { return }
        guard type == .etf || type == .stock || type == .crypto else {
            preview = nil
            return
        }
        preview = await viewModel.previewQuote(query)
    }

    private func save() async {
        saving = true
        defer { saving = false }

        guard let accountId = selectedAccountId else {
            saving = false
            return
        }
        let symbol = ticker.trimmingCharacters(in: .whitespaces).nilIfEmpty
        let resolvedName = name.trimmingCharacters(in: .whitespaces).nilIfEmpty
            ?? preview?.name
            ?? symbol
            ?? "Holding"

        await viewModel.saveHolding(
            existingId: editing,
            accountId: accountId,
            name: resolvedName,
            ticker: symbol,
            type: type,
            units: Double(units.replacingOccurrences(of: ",", with: "")) ?? 0,
            manualPrice: Double(manualPrice.replacingOccurrences(of: ",", with: "")),
            // Derived from the ticker's exchange rather than defaulted to CAD,
            // so a US listing saves as USD.
            currency: viewModel.currency(forTicker: symbol),
            costBasis: Double(costBasis.replacingOccurrences(of: ",", with: ""))
        )
        dismiss()
    }
}
