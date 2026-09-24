import SwiftUI

/// Records a BUY, SELL or DRIP against a holding.
///
/// This replaces typing a position size directly: a portfolio built from
/// transactions can show how it got where it is, and gives cost basis a real
/// provenance instead of a number the user had to work out themselves. An
/// unknown ticker creates the holding on the fly, so the first buy of something
/// new is still a single step.
struct AddTransactionView: View {
    @EnvironmentObject private var viewModel: PortfolioViewModel
    @Environment(\.colorScheme) private var scheme
    @Environment(\.dismiss) private var dismiss

    var presetHoldingId: UUID?

    @State private var type: TransactionType = .buy
    /// What is being traded is a SECURITY; which account it lands in is chosen
    /// separately, below. They used to be one choice — pick a stored row and
    /// the account came with it — which is why the same fund appeared once per
    /// account in the picker, and why buying it in a fourth account looked
    /// impossible.
    @State private var selectedSecurityKey: String?
    @State private var selectedAccountId: UUID?
    @State private var tickerQuery = ""
    /// The query the user has already resolved by tapping a suggestion. A value
    /// rather than a flag: a one-shot boolean cleared by the search effect made
    /// selection take two taps for any result whose symbol differed from what
    /// was typed — the list reopened the instant it closed.
    @State private var pickedTicker: String?
    @State private var resolvedName: String?
    /// Deliberately nil until something tells us what this is. Defaulting to
    /// ETF meant a ticker typed without picking a suggestion was filed as an
    /// ETF regardless, which is how a stock ended up labelled "ETF".
    @State private var resolvedType: HoldingType?
    @State private var resolvedCurrency: String?

    @State private var sharesText = ""
    @State private var priceText = ""
    @State private var date = Date()
    @State private var livePrice: Double?
    @State private var isFetchingPrice = false
    @State private var error: String?
    @State private var showNewAccount = false

    /// Every account's slice of the chosen security, largest first.
    private var securitySlices: [Holding] {
        guard let key = selectedSecurityKey else { return [] }
        return viewModel.holdings
            .filter { $0.securityKey == key }
            .sorted { $0.units > $1.units }
    }

    /// The security itself, for everything that is true of it whatever account
    /// it sits in: its name, its currency, its price.
    private var selectedHolding: Holding? { securitySlices.first }

    /// The row this entry will actually be recorded against — nil when the
    /// chosen account holds none of it yet, which for a buy means "start it
    /// here" rather than "nothing to do".
    private var targetHolding: Holding? {
        securitySlices.first { $0.accountId == selectedAccountId }
    }

    /// Units of the chosen security in one account. Zero is a real answer, and
    /// is shown as one.
    private func units(in accountId: UUID) -> Double {
        securitySlices.first { $0.accountId == accountId }?.units ?? 0
    }

    private var shares: Double { Double(sharesText) ?? 0 }
    private var price: Double { Double(priceText) ?? 0 }

    private var currency: String {
        selectedHolding?.normalizedCurrency
            ?? resolvedCurrency
            ?? viewModel.currency(forTicker: tickerQuery.nilIfEmpty)
    }

    private var showSuggestions: Bool {
        !tickerQuery.isEmpty && !viewModel.searchResults.isEmpty && tickerQuery != pickedTicker
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 14) {
                typePicker
                holdingPicker
                if selectedHolding == nil { tickerField }
                detailsCard
                oversellWarning
                // Always on screen, and now the only place the destination is
                // decided — for a held security as much as a new one.
                accountPicker
                if let error {
                    Text(error)
                        .font(.wbBodySmall)
                        .foregroundStyle(Brand.loss)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                submitButton
                Color.clear.frame(height: 30)
            }
            .padding(.horizontal, WbDimens.screenPadding)
            .padding(.top, 8)
        }
        .wbScreenBackground(scheme)
        .navigationTitle("Add Transaction")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("Cancel") { dismiss() }
            }
        }
        .task {
            // Arriving from a holding: adopt both its security and its account,
            // so the form opens on the row the user tapped.
            if let preset = presetHoldingId.flatMap({ viewModel.holding($0) }) {
                selectedSecurityKey = preset.securityKey
                selectedAccountId = preset.accountId
            }
            // Only ever preselects an account the user actually made. With none
            // yet, the picker stays empty and Add stays disabled — an account
            // decides how everything in it is taxed, so it is not a field to
            // fill in on someone's behalf.
            if selectedAccountId == nil {
                selectedAccountId = viewModel.accounts.first?.id
            }
        }
        // Keyed on the security, not the account's row: the price of XEQT is
        // the price of XEQT whichever account it sits in.
        .task(id: selectedHolding?.id) { await loadPriceForSelection() }
        .task(id: tickerQuery) { await resolveTyped() }
        .sheet(isPresented: $showNewAccount) {
            NewAccountSheet { id in selectedAccountId = id }
        }
    }

    // MARK: - Sections

    private var typePicker: some View {
        Picker("Type", selection: $type) {
            ForEach(TransactionType.allCases) { option in
                Text(option.label).tag(option)
            }
        }
        .pickerStyle(.segmented)
    }

    @ViewBuilder
    private var holdingPicker: some View {
        WbCard {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Holding")
                        .font(.wbTitleMedium)
                        .foregroundStyle(Palette.onSurface(scheme))
                    Text("Tap to choose, or search a new ticker below")
                        .font(.wbBodySmall)
                        .foregroundStyle(Palette.onSurfaceVariant(scheme))
                }
                Spacer()
                // One entry per security, not one per stored row.
                //
                // The same fund bought in three accounts is three rows in the
                // store, because cost basis and tax treatment are per account —
                // but it is one thing the user owns, and listing it three times
                // over with nothing but a unit count to tell the entries apart
                // made the menu unanswerable.
                //
                // Which account the trade belongs to is asked once, by the
                // account chips below, where every account is visible —
                // including the ones this security is not in yet.
                Menu {
                    Button("Search a new ticker instead") { selectedSecurityKey = nil }
                    ForEach(viewModel.positions) { position in
                        Button(positionLabel(position)) {
                            selectedSecurityKey = position.key
                            // Land on an account that actually holds it, unless
                            // the current choice already does.
                            if !position.slices.contains(where: { $0.holding.accountId == selectedAccountId }) {
                                selectedAccountId = position.slices[0].holding.accountId
                            }
                        }
                    }
                } label: {
                    Text(selectedHolding.map { $0.ticker ?? $0.name } ?? "Select…")
                        .font(.wbBodyLarge)
                        .fontWeight(.semibold)
                        .foregroundStyle(Palette.accent(scheme))
                }
                .disabled(viewModel.holdings.isEmpty)
            }
        }
    }

    /// "XEQT.TO — iShares Core Equity ETF · 1,498.86 units · 3 accounts"
    private func positionLabel(_ position: SecurityPosition) -> String {
        var label = "\(position.ticker ?? position.name) — \(position.name)"
        label += " · \(Money.units(position.units)) units"
        if position.slices.count > 1 {
            label += " · \(position.slices.count) accounts"
        }
        return label
    }

    @ViewBuilder
    private var tickerField: some View {
        VStack(alignment: .leading, spacing: 0) {
            TextField("Search a new ticker", text: $tickerQuery)
                .textInputAutocapitalization(.characters)
                .autocorrectionDisabled()
                .font(.wbBodyLarge)
                .padding(14)
                .background(Palette.surface(scheme))
                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: 10, style: .continuous)
                        .stroke(Palette.outline(scheme), lineWidth: 1)
                )

            if showSuggestions {
                VStack(spacing: 0) {
                    ForEach(viewModel.searchResults.prefix(6)) { result in
                        Button {
                            pick(result)
                        } label: {
                            HStack(spacing: 8) {
                                Text(TickerFlag.forTicker(result.symbol, exchange: result.exchange))
                                VStack(alignment: .leading, spacing: 1) {
                                    Text(result.symbol)
                                        .font(.wbBodyMedium)
                                        .fontWeight(.semibold)
                                        .foregroundStyle(Palette.onSurface(scheme))
                                    if let name = result.name {
                                        Text(name)
                                            .font(.wbBodySmall)
                                            .foregroundStyle(Palette.onSurfaceVariant(scheme))
                                            .lineLimit(1)
                                    }
                                }
                                Spacer()
                            }
                            .padding(.horizontal, 14)
                            .padding(.vertical, 10)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        WbDivider()
                    }
                }
                .background(Palette.surface(scheme))
                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                .padding(.top, 4)
            }

            if let resolvedName, selectedHolding == nil, !tickerQuery.isEmpty {
                Text(resolvedName + (resolvedType.map { " · \($0.label)" } ?? ""))
                    .font(.wbBodySmall)
                    .foregroundStyle(Palette.onSurfaceVariant(scheme))
                    .padding(.top, 6)
            }
        }
    }

    private var detailsCard: some View {
        WbCard {
            // The system wheel picker in a sheet, rather than the inline
            // calendar grid: a trade is dated by a day you already know, so
            // spinning to it beats hunting for it in a month you have to
            // navigate to first.
            WheelDateField("Date", date: $date, maxDate: Date())
                .padding(.bottom, 12)

            WbDivider().padding(.bottom, 12)

            TextField("Number of shares", text: $sharesText)
                .keyboardType(.decimalPad)
                .padding(12)
                .background(Palette.surfaceVariant(scheme).opacity(0.35))
                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                .padding(.bottom, 10)

            TextField("Price per share, \(currency)", text: $priceText)
                .keyboardType(.decimalPad)
                .padding(12)
                .background(Palette.surfaceVariant(scheme).opacity(0.35))
                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))

            HStack(spacing: 6) {
                if isFetchingPrice {
                    ProgressView().controlSize(.mini)
                    Text("Checking the market…")
                        .font(.wbBodySmall)
                        .foregroundStyle(Palette.onSurfaceVariant(scheme))
                } else if let livePrice {
                    Button {
                        priceText = String(format: "%.2f", livePrice)
                    } label: {
                        Text("Live now: \(Money.format(livePrice, currency)) — tap to use")
                            .font(.wbBodySmall)
                            .foregroundStyle(Palette.accent(scheme))
                    }
                    .buttonStyle(.plain)
                }
                Spacer(minLength: 0)
            }
            .padding(.top, 8)

            if shares > 0, price > 0 {
                WbDivider().padding(.vertical, 12)
                KeyValueRow(
                    label: type == .sell ? "Proceeds" : "Total cost",
                    value: Money.format(shares * price, currency)
                )
            }
        }
    }

    @ViewBuilder
    private var accountPicker: some View {
        // Only consulted when this transaction creates a NEW holding. Selling
        // or reinvesting an existing one must not move it between accounts —
        // that would change its tax treatment as a side effect of a trade.
        WbCard {
            Text("Account")
                .font(.wbTitleMedium)
                .foregroundStyle(Palette.onSurface(scheme))

            // Every account, with what it already holds of this security —
            // zero included, because an account holding none of it is still a
            // place you can buy it, and that used to be reachable only by
            // creating an account that already existed.
            //
            // Recording a trade still cannot MOVE a position between accounts:
            // each keeps its own row, its own cost basis and its own tax
            // treatment. Choosing a different one here records the trade
            // there; it does not relocate anything.
            if let label = securityLabel {
                Text(type == .buy
                     ? "Where this buy lands. The figure is what you already hold of \(label) there."
                     : "Which slice of \(label) this is out of.")
                    .font(.wbBodySmall)
                    .foregroundStyle(Palette.onSurfaceVariant(scheme))
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 2)
            }

            if viewModel.accounts.isEmpty {
                Text("Make one first — whether an account is taxable, tax-free or tax-deferred is what decides every tax figure the app shows for what's in it.")
                    .font(.wbBodySmall)
                    .foregroundStyle(Brand.divAmber)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 6)
            }

            // Equal columns that fill the card, wrapping onto as many lines as
            // it takes rather than scrolling sideways.
            //
            // This was a horizontal ScrollView, which put "+ New" off the right
            // edge the moment there were four accounts — and nothing indicated
            // there was anything to scroll to, so the only way to add an
            // account from here was to swipe a row that did not look
            // scrollable. Wrapping fixed that but left the last line ragged
            // against the left edge; see `AccountChipGrid`.
            LazyVGrid(columns: AccountChipGrid.columns, spacing: 8) {
                Group {
                    ForEach(viewModel.accounts) { account in
                        accountChip(account)
                    }

                    Button {
                        showNewAccount = true
                    } label: {
                        Text("+ New")
                            .font(.system(size: 11, weight: .medium))
                            .foregroundStyle(Palette.accent(scheme))
                            .padding(.horizontal, 8)
                            .padding(.vertical, 8)
                            .frame(maxWidth: .infinity, minHeight: 44)
                            .background(
                                RoundedRectangle(cornerRadius: 8, style: .continuous)
                                    .fill(Palette.accent(scheme).opacity(0.18))
                            )
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.top, 10)
        }
    }

    /// Against the SLICE being sold, not the whole position: holding 1,500
    /// units across three accounts does not mean 1,500 can be sold out of the
    /// TFSA.
    @ViewBuilder
    private var oversellWarning: some View {
        if type == .sell, let security = selectedHolding, shares > (targetHolding?.units ?? 0) {
            Text("You only hold \(Money.units(targetHolding?.units ?? 0)) units of \(security.ticker ?? security.name) in that account.")
                .font(.wbBodySmall)
                .foregroundStyle(Brand.loss)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    /// The ticker (or name) of whatever is being traded, once there is one.
    private var securityLabel: String? {
        selectedHolding.map { $0.ticker ?? $0.name }
    }

    /// You cannot sell or reinvest what is not there. Those accounts stay
    /// visible, dimmed, rather than vanishing — a disappearing account reads
    /// as a bug.
    private func isSelectable(_ account: Account) -> Bool {
        if type == .buy || selectedHolding == nil { return true }
        return units(in: account.id) > 0
    }

    @ViewBuilder
    private func accountChip(_ account: Account) -> some View {
        let selected = account.id == selectedAccountId
        let enabled = isSelectable(account)
        // Annotated: a bare `nil` in a ternary has no contextual type to
        // infer from, which the compiler rejects rather than guessing.
        let held: Double? = selectedHolding == nil ? nil : units(in: account.id)

        Button {
            selectedAccountId = account.id
        } label: {
            VStack(spacing: 2) {
                Text(account.displayName)
                    .font(.system(size: 12, weight: selected ? .bold : .medium))
                // The tax type is shown on the chip so the choice being made
                // here is visibly the one that drives the tax notes.
                Text(account.taxTreatment?.label ?? "Tax type not set")
                    .font(.system(size: 9))
                    .opacity(0.8)
                if let held {
                    Text(Money.units(held))
                        .font(.system(size: 13, weight: .bold))
                        .foregroundStyle(
                            selected ? Palette.onAccent(scheme)
                                     : (held > 0 ? Palette.accent(scheme)
                                                 : Palette.onSurfaceVariant(scheme).opacity(0.6))
                        )
                        .padding(.top, 1)
                }
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
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.42)
    }

    private var submitButton: some View {
        Button {
            Task { await submit() }
        } label: {
            Text("Add \(type.label)")
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

    /// An account is always required now: it is where the trade lands, whether
    /// the security is already held there or not.
    private var canSubmit: Bool {
        guard shares > 0, price > 0 else { return false }
        if targetHolding != nil { return true }
        guard selectedAccountId != nil else { return false }
        // An account holding none of it can still be bought into; it cannot be
        // sold out of.
        return selectedHolding == nil || type == .buy
    }

    // MARK: - Behaviour

    private func pick(_ result: SymbolSearchResult) {
        tickerQuery = result.symbol
        pickedTicker = result.symbol
        resolvedName = result.name
        resolvedType = HoldingType.fromQuoteType(result.quoteType)
        viewModel.clearSearch()
        Task { await fetchLive(for: result.symbol) }
    }

    /// A ticker can be typed in full and submitted without ever touching the
    /// suggestion list, which would otherwise leave the currency at its default
    /// and the type unresolved. So anything that looks like a complete symbol
    /// is resolved either way.
    private func resolveTyped() async {
        guard selectedHolding == nil else { return }
        guard tickerQuery != pickedTicker else { return }

        // The field has moved off the picked symbol, so that pick is spent —
        // forgetting it here means retyping the same symbol later still
        // searches instead of silently showing nothing.
        pickedTicker = nil
        livePrice = nil
        resolvedCurrency = nil
        resolvedType = nil
        viewModel.search(tickerQuery)

        let candidate = tickerQuery.trimmingCharacters(in: .whitespaces).uppercased()
        guard candidate.count >= 2 else { return }
        try? await Task.sleep(nanoseconds: 700_000_000)
        guard candidate == tickerQuery.trimmingCharacters(in: .whitespaces).uppercased() else { return }
        await fetchLive(for: candidate)
    }

    /// The quote settles three things at once: the price, the currency the
    /// listing trades in, and what kind of instrument it is.
    private func fetchLive(for ticker: String) async {
        isFetchingPrice = true
        defer { isFetchingPrice = false }

        guard let quote = await viewModel.quote(forTicker: ticker) else { return }
        livePrice = quote.price
        resolvedCurrency = quote.currency?.uppercased()
        // The quote's own classification beats the search result's, and beats
        // any guess: this is the exchange telling us what the symbol is.
        if let type = HoldingType.fromQuoteType(quote.instrumentType) { resolvedType = type }
        if resolvedName == nil { resolvedName = quote.name }
        if priceText.isEmpty { priceText = String(format: "%.2f", quote.price) }
    }

    private func loadPriceForSelection() async {
        guard let holding = selectedHolding, let ticker = holding.ticker else { return }
        livePrice = nil
        isFetchingPrice = true
        let quote = await viewModel.quote(forTicker: ticker)
        isFetchingPrice = false
        livePrice = quote?.price
        if priceText.isEmpty, let price = quote?.price {
            priceText = String(format: "%.2f", price)
        }
    }

    private func submit() async {
        error = nil
        guard shares > 0, price > 0 else {
            error = "Enter how many shares and what you paid per share."
            return
        }

        if let holding = targetHolding {
            await viewModel.addTransaction(
                holdingId: holding.id,
                type: type,
                at: date,
                shares: shares,
                pricePerShare: price
            )
            dismiss()
            return
        }

        // A held security, in an account that has none of it yet: for a buy
        // that is a new slice of the same position, and nothing about the
        // security is re-derived — name, ticker, type and currency are copied
        // from the slice that already exists.
        if let security = selectedHolding {
            guard type == .buy else {
                error = "You don't hold \(security.ticker ?? security.name) in that account."
                return
            }
            guard let accountId = selectedAccountId else {
                error = "Choose an account first — it decides how this holding is taxed."
                return
            }
            await viewModel.createHoldingThenTransact(
                accountId: accountId,
                name: security.name,
                ticker: security.ticker ?? "",
                type: security.type,
                currency: security.normalizedCurrency,
                transactionType: .buy,
                at: date,
                shares: shares,
                pricePerShare: price
            )
            dismiss()
            return
        }

        let ticker = tickerQuery.trimmingCharacters(in: .whitespaces).uppercased()
        guard !ticker.isEmpty else {
            error = "Choose a holding, or search for a ticker to start a new one."
            return
        }
        guard type == .buy else {
            // Selling something not yet in the portfolio would create a
            // position and immediately empty it, leaving a holding with a
            // history the user never had.
            error = "You can only record a buy for a ticker that isn't in your portfolio yet."
            return
        }

        guard let accountId = selectedAccountId else {
            error = "Choose an account first — it decides how this holding is taxed."
            return
        }
        await viewModel.createHoldingThenTransact(
            accountId: accountId,
            name: resolvedName ?? ticker,
            ticker: ticker,
            type: resolvedType ?? .stock,
            currency: resolvedCurrency ?? viewModel.currency(forTicker: ticker),
            transactionType: .buy,
            at: date,
            shares: shares,
            pricePerShare: price
        )
        dismiss()
    }
}

// MARK: - New account

/// Creating an account asks for its tax treatment here, at creation, because it
/// is a property of the account rather than of one entry. Setting it
/// per-transaction would let the most recent trade silently reclassify every
/// holding in the account.
struct NewAccountSheet: View {
    @EnvironmentObject private var viewModel: PortfolioViewModel
    @Environment(\.colorScheme) private var scheme
    @Environment(\.dismiss) private var dismiss

    var onCreate: (UUID) -> Void

    @State private var name = ""
    @State private var treatment: TaxTreatment?
    // Only reachable if something bypassed the picker's own gate (the chip
    // that opens this sheet already refuses to at the limit) — but "can't
    // happen" still needs a message the day it does, rather than a sheet
    // that just closes with no account created and no explanation.
    @State private var showAccountLimit = false
    @State private var showPremium = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    // One-tap suggestions naming the real products, because a
                    // user who does not know the jargon cannot reliably pick
                    // "tax-free" for a Roth IRA — and a wrong pick silently
                    // changes every tax figure for everything in the account.
                    let suggestions = TaxRules.suggestedAccounts(viewModel.residency)
                    if !suggestions.isEmpty {
                        Text("COMMON WHERE YOU FILE")
                            .font(.system(size: 11, weight: .semibold))
                            .foregroundStyle(Palette.onSurfaceVariant(scheme))
                        FlowChips(items: suggestions.map(\.name)) { picked in
                            name = picked
                            treatment = suggestions.first { $0.name == picked }?.treatment
                        }
                    }

                    TextField(TaxRules.accountNameHint(viewModel.residency), text: $name)
                        .font(.wbBodyLarge)
                        .padding(14)
                        .background(Palette.surface(scheme))
                        .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))

                    Text("HOW IS IT TAXED?")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(Palette.onSurfaceVariant(scheme))

                    VStack(spacing: 0) {
                        ForEach(TaxTreatment.allCases) { option in
                            Button {
                                treatment = option
                            } label: {
                                HStack(alignment: .top, spacing: 10) {
                                    Image(systemName: treatment == option ? "largecircle.fill.circle" : "circle")
                                        .foregroundStyle(Palette.accent(scheme))
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(option.label)
                                            .font(.wbBodyLarge)
                                            .foregroundStyle(Palette.onSurface(scheme))
                                        Text(option.detail)
                                            .font(.wbBodySmall)
                                            .foregroundStyle(Palette.onSurfaceVariant(scheme))
                                        Text(TaxRules.examples(for: option, residency: viewModel.residency))
                                            .font(.system(size: 11))
                                            .foregroundStyle(Palette.accent(scheme))
                                    }
                                    Spacer(minLength: 0)
                                }
                                .padding(12)
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                            if option != TaxTreatment.allCases.last { WbDivider() }
                        }
                    }
                    .background(Palette.surface(scheme))
                    .clipShape(RoundedRectangle(cornerRadius: WbDimens.cardRadius, style: .continuous))

                    Text("You can leave this unset — the app then says nothing about tax for this account rather than guessing.")
                        .font(.system(size: 11))
                        .foregroundStyle(Palette.onSurfaceVariant(scheme))
                }
                .padding(WbDimens.screenPadding)
            }
            .wbScreenBackground(scheme)
            .navigationTitle("New account")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Create") {
                        Task {
                            // nil means the free account limit refused it.
                            // The picker gates the chip that opens this sheet,
                            // so this is a fallback for whatever bypassed that
                            // gate — a limit re-checked at save time because
                            // the count changed while the sheet was open, say.
                            // It used to just dismiss here with nothing said,
                            // which reads as the tap doing nothing at all.
                            if let id = await viewModel.createAccount(
                                name: name.trimmingCharacters(in: .whitespaces),
                                treatment: treatment
                            ) {
                                onCreate(id)
                                dismiss()
                            } else {
                                showAccountLimit = true
                            }
                        }
                    }
                    .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
            .sheet(isPresented: $showPremium) {
                NavigationStack { PremiumView() }
            }
            .alert("Account limit reached", isPresented: $showAccountLimit) {
                Button("See Premium") { showPremium = true }
                Button("Not now", role: .cancel) { dismiss() }
            } message: {
                Text("Free installs keep up to \(PremiumLimits.freeAccountLimit) accounts — enough for one taxable account and one registered one. Premium removes the limit, so every account you actually hold can be tracked with its own tax treatment.\n\nNothing already entered is affected: your existing accounts, holdings and history stay exactly as they are.")
            }
        }
    }
}

/// A wrapping row of tappable chips. SwiftUI has no flow layout before iOS 16's
/// `Layout` protocol, and this keeps the chip rows readable at any width
/// without one.
struct FlowChips: View {
    @Environment(\.colorScheme) private var scheme

    let items: [String]
    let onTap: (String) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ForEach(rows(), id: \.self) { row in
                HStack(spacing: 8) {
                    ForEach(row, id: \.self) { item in
                        Button {
                            onTap(item)
                        } label: {
                            Text(item)
                                .font(.system(size: 12, weight: .medium))
                                .foregroundStyle(Palette.accent(scheme))
                                .padding(.horizontal, 12)
                                .padding(.vertical, 7)
                                .background(
                                    Capsule().fill(Palette.accent(scheme).opacity(0.15))
                                )
                        }
                        .buttonStyle(.plain)
                    }
                    Spacer(minLength: 0)
                }
            }
        }
    }

    /// Three per row is a conservative fit for the longest product names at the
    /// narrowest supported width; measuring properly would need a custom
    /// `Layout` for no visible gain here.
    private func rows() -> [[String]] {
        stride(from: 0, to: items.count, by: 3).map {
            Array(items[$0..<min($0 + 3, items.count)])
        }
    }
}
