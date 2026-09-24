import SwiftUI

/// Price and dividend alerts — create, arm, disarm, delete.
///
/// Alerts are evaluated on every launch and, when iOS grants it, by a
/// background refresh. This screen never promises more than that: if
/// notifications are off the banner says so and offers the prompt, rather than
/// letting someone set six rules and wonder why they are silent.
struct AlertsView: View {
    @EnvironmentObject private var viewModel: PortfolioViewModel
    @Environment(\.colorScheme) private var scheme

    @ObservedObject private var subscription = SubscriptionSession.shared

    @State private var showCreate = false
    @State private var showPremium = false
    @State private var canNotify = true
    @State private var pendingDelete: PriceAlert?

    private var isPremium: Bool { subscription.isPremium }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                if !isPremium {
                    premiumCard
                } else if !canNotify {
                    notificationsOffCard
                }

                if viewModel.alerts.isEmpty {
                    emptyState
                } else {
                    ForEach(viewModel.alerts) { alert in
                        AlertRow(
                            alert: alert,
                            enabled: isPremium,
                            onToggle: { on in
                                Task { await viewModel.setAlertEnabled(alert.id, enabled: on) }
                            },
                            onDelete: { pendingDelete = alert }
                        )
                    }
                }

                Color.clear.frame(height: 30)
            }
            .padding(.horizontal, WbDimens.screenPadding)
            .padding(.top, 8)
        }
        .wbScreenBackground(scheme)
        .wbNavigationBar("Alerts")
        .toolbar {
            if isPremium {
                ToolbarItem(placement: .primaryAction) {
                    Button { showCreate = true } label: {
                        Image(systemName: "plus")
                    }
                }
            }
        }
        .task {
            subscription.sync()
            await viewModel.reloadAlerts()
            canNotify = await AlertNotifier.canPost()
        }
        .sheet(isPresented: $showCreate) {
            NewAlertSheet(
                suggestions: suggestions,
                onCreate: { ticker, kind, threshold in
                    Task {
                        await viewModel.addAlert(
                            ticker: ticker, kind: kind, threshold: threshold
                        )
                        // Asked at the moment the first alert is created,
                        // which is when the reason for it is self-evident,
                        // rather than at launch where it reads as an app
                        // demanding things.
                        if !canNotify {
                            _ = await AlertNotifier.requestPermission()
                            canNotify = await AlertNotifier.canPost()
                        }
                    }
                }
            )
            // Passed explicitly rather than relying on the sheet inheriting
            // it: the search field inside needs the view model, and an
            // environment object that silently fails to arrive is a crash at
            // presentation, not a compile error.
            .environmentObject(viewModel)
        }
        .sheet(isPresented: $showPremium) {
            NavigationStack { PremiumView() }
        }
        .alert("Delete this alert?", isPresented: Binding(
            get: { pendingDelete != nil },
            set: { if !$0 { pendingDelete = nil } }
        )) {
            Button("Delete", role: .destructive) {
                if let alert = pendingDelete {
                    Task { await viewModel.deleteAlert(alert.id) }
                }
                pendingDelete = nil
            }
            Button("Cancel", role: .cancel) { pendingDelete = nil }
        } message: {
            if let alert = pendingDelete {
                Text("\(alert.ticker) — \(alert.summary)")
            }
        }
    }

    // MARK: - Pieces

    /// Tickers worth suggesting: everything held or watched. Alerts are keyed
    /// by ticker, so a suggestion list is the difference between tapping a
    /// symbol and typing one correctly from memory.
    private var suggestions: [String] {
        let held = viewModel.holdings.compactMap { $0.ticker }
        let watched = viewModel.watchlist.map { $0.ticker }
        return Array(Set(held + watched)).map { $0.uppercased() }.sorted()
    }

    private var premiumCard: some View {
        WbCard {
            Text("Alerts are a Premium feature")
                .font(.wbBodyLarge)
                .fontWeight(.semibold)
                .foregroundStyle(Palette.onSurface(scheme))
                .padding(.bottom, 6)
            Text("Get told when a price reaches your target, when something moves sharply in a day, or before a holding goes ex-dividend — so you find out when it happens rather than the next time you open the app.")
                .font(.wbBodySmall)
                .foregroundStyle(Palette.onSurfaceVariant(scheme))
                .fixedSize(horizontal: false, vertical: true)
                .padding(.bottom, 14)

            Button { showPremium = true } label: {
                Text("See Premium")
                    .font(.wbBodyLarge)
                    .fontWeight(.semibold)
                    .foregroundStyle(Palette.accent(scheme))
                    .frame(maxWidth: .infinity)
                    .frame(height: 44)
                    .overlay(Capsule().stroke(Palette.accent(scheme), lineWidth: 1.5))
            }
            .buttonStyle(.plain)

            // Existing rules stay visible, read-only, for a lapsed subscriber:
            // they are the user's own configuration, and hiding them would
            // look like they had been deleted.
            if !viewModel.alerts.isEmpty {
                Text("Your saved alerts are kept, and start firing again when Premium is active.")
                    .font(.system(size: 11))
                    .foregroundStyle(Palette.onSurfaceVariant(scheme))
                    .padding(.top, 12)
            }
        }
    }

    private var notificationsOffCard: some View {
        WbCard {
            Text("Notifications are off")
                .font(.wbBodyLarge)
                .fontWeight(.semibold)
                .foregroundStyle(Brand.loss)
                .padding(.bottom, 6)
            Text("Alerts are checked when the app opens and, when iOS allows it, in the background — but nothing can reach you until notifications are allowed.")
                .font(.wbBodySmall)
                .foregroundStyle(Palette.onSurfaceVariant(scheme))
                .fixedSize(horizontal: false, vertical: true)
                .padding(.bottom, 14)

            Button {
                Task {
                    _ = await AlertNotifier.requestPermission()
                    canNotify = await AlertNotifier.canPost()
                }
            } label: {
                Text("Allow notifications")
                    .font(.wbBodyLarge)
                    .fontWeight(.semibold)
                    .foregroundStyle(Palette.accent(scheme))
                    .frame(maxWidth: .infinity)
                    .frame(height: 44)
                    .overlay(Capsule().stroke(Palette.accent(scheme), lineWidth: 1.5))
            }
            .buttonStyle(.plain)
        }
    }

    private var emptyState: some View {
        VStack(spacing: 10) {
            Image(systemName: "bell.badge")
                .font(.system(size: 34))
                .foregroundStyle(Palette.onSurfaceVariant(scheme))
            Text("No alerts yet")
                .font(.wbBodyLarge)
                .fontWeight(.medium)
                .foregroundStyle(Palette.onSurface(scheme))
            Text("Alerts are checked when you open the app, and in the background when iOS wakes it.")
                .font(.system(size: 12))
                .multilineTextAlignment(.center)
                .foregroundStyle(Palette.onSurfaceVariant(scheme))
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 50)
    }
}

// MARK: - Row

private struct AlertRow: View {
    let alert: PriceAlert
    let enabled: Bool
    let onToggle: (Bool) -> Void
    let onDelete: () -> Void

    @Environment(\.colorScheme) private var scheme

    var body: some View {
        WbCard {
            HStack(alignment: .center, spacing: 10) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(alert.ticker)
                        .font(.wbBodyLarge)
                        .fontWeight(.bold)
                        .foregroundStyle(Palette.onSurface(scheme))
                    Text(alert.summary)
                        .font(.wbBodySmall)
                        .foregroundStyle(Palette.onSurfaceVariant(scheme))
                        .fixedSize(horizontal: false, vertical: true)

                    // "Waiting" vs "fired" is the single most useful thing
                    // this row can say: it tells the reader whether silence
                    // means nothing has happened or that they already missed
                    // the notification.
                    if let at = alert.triggeredAt {
                        Text("Fired \(at.formatted(date: .abbreviated, time: .shortened)) — re-arms when the condition clears")
                            .font(.system(size: 11))
                            .foregroundStyle(Palette.accent(scheme))
                            .padding(.top, 2)
                    }
                }

                Spacer()

                Toggle("", isOn: Binding(
                    get: { alert.enabled },
                    set: { onToggle($0) }
                ))
                .labelsHidden()
                .disabled(!enabled)

                Button(action: onDelete) {
                    Image(systemName: "trash")
                        .foregroundStyle(Palette.onSurfaceVariant(scheme))
                }
                .buttonStyle(.plain)
            }
        }
    }
}

// MARK: - Create

private struct NewAlertSheet: View {
    let suggestions: [String]
    let onCreate: (String, AlertKind, Double) -> Void

    @EnvironmentObject private var viewModel: PortfolioViewModel
    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var scheme

    @State private var ticker = ""
    @State private var kind: AlertKind = .priceAbove
    @State private var threshold = ""

    /// Typed-but-not-yet-chosen. Kept separate from `ticker` so picking a
    /// result stops the search rather than re-running it on the symbol just
    /// selected, which is what makes the result list flicker back open the
    /// instant you tap something.
    @State private var query = ""

    private var parsed: Double? {
        Double(threshold.replacingOccurrences(of: ",", with: "."))
    }

    private var valid: Bool {
        !ticker.trimmingCharacters(in: .whitespaces).isEmpty
            && (parsed ?? 0) > 0
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    WbCard {
                        Text("Ticker")
                            .font(.system(size: 14, weight: .medium))
                            .foregroundStyle(Palette.onSurface(scheme))
                            .padding(.bottom, 8)

                        TextField("VDY.TO, Apple, ^HSI…", text: Binding(
                            get: { query.isEmpty ? ticker : query },
                            set: { newValue in
                                query = newValue
                                // The typed text IS the ticker until a result
                                // is chosen, so a symbol the search cannot
                                // find — an index like ^HSI, a listing the
                                // endpoint misses — can still be typed by hand.
                                ticker = newValue.trimmingCharacters(in: .whitespaces).uppercased()
                            }
                        ))
                            .autocorrectionDisabled()
                            .font(.wbBodyLarge)
                            .padding(.horizontal, 14)
                            .padding(.vertical, 12)
                            .background(
                                RoundedRectangle(cornerRadius: 10, style: .continuous)
                                    .fill(Palette.surfaceVariant(scheme).opacity(0.5))
                            )
                            .onChange(of: query) { newValue in
                                if newValue.isEmpty {
                                    viewModel.clearSearch()
                                } else {
                                    viewModel.search(newValue)
                                }
                            }

                        if !viewModel.searchResults.isEmpty && !query.isEmpty {
                            // Capped at six: this sheet also has to fit the
                            // rule picker and the threshold field on a phone.
                            ForEach(viewModel.searchResults.prefix(6)) { result in
                                Button {
                                    ticker = result.symbol.uppercased()
                                    // Clearing the query both closes the list
                                    // and puts the chosen symbol back in the
                                    // field, via the binding above.
                                    query = ""
                                    viewModel.clearSearch()
                                } label: {
                                    VStack(alignment: .leading, spacing: 1) {
                                        Text(result.symbol)
                                            .font(.wbBodyMedium)
                                            .fontWeight(.medium)
                                            .foregroundStyle(Palette.onSurface(scheme))
                                        let subtitle = [result.name, result.exchange]
                                            .compactMap { $0 }
                                            .filter { !$0.isEmpty }
                                            .joined(separator: " · ")
                                        if !subtitle.isEmpty {
                                            Text(subtitle)
                                                .font(.system(size: 11))
                                                .lineLimit(1)
                                                .foregroundStyle(Palette.onSurfaceVariant(scheme))
                                        }
                                    }
                                    .frame(maxWidth: .infinity, alignment: .leading)
                                    .contentShape(Rectangle())
                                    .padding(.vertical, 7)
                                }
                                .buttonStyle(.plain)
                            }
                        }

                        if !suggestions.isEmpty {
                            Text("Held or watched")
                                .font(.system(size: 11))
                                .foregroundStyle(Palette.onSurfaceVariant(scheme))
                                .padding(.top, 12)
                                .padding(.bottom, 6)

                            // Capped rather than scrolled: this is a shortcut,
                            // not a browser, and a long list here pushes the
                            // actual inputs off a small screen.
                            FlowChips(items: Array(suggestions.prefix(12))) { symbol in
                                ticker = symbol
                            }
                        }
                    }

                    WbCard {
                        Text("Tell me when it…")
                            .font(.system(size: 14, weight: .medium))
                            .foregroundStyle(Palette.onSurface(scheme))
                            .padding(.bottom, 8)

                        ForEach(AlertKind.allCases) { option in
                            Button { kind = option } label: {
                                HStack(spacing: 10) {
                                    Image(systemName: kind == option
                                          ? "largecircle.fill.circle" : "circle")
                                        .foregroundStyle(kind == option
                                                         ? Palette.accent(scheme)
                                                         : Palette.onSurfaceVariant(scheme))
                                    Text(option.label)
                                        .font(.wbBodyMedium)
                                        .foregroundStyle(Palette.onSurface(scheme))
                                    Spacer()
                                }
                                .contentShape(Rectangle())
                                .padding(.vertical, 7)
                            }
                            .buttonStyle(.plain)
                        }
                    }

                    WbCard {
                        Text(kind.thresholdLabel)
                            .font(.system(size: 14, weight: .medium))
                            .foregroundStyle(Palette.onSurface(scheme))
                            .padding(.bottom, 8)

                        TextField("0.00", text: $threshold)
                            .keyboardType(.decimalPad)
                            .font(.wbBodyLarge)
                            .padding(.horizontal, 14)
                            .padding(.vertical, 12)
                            .background(
                                RoundedRectangle(cornerRadius: 10, style: .continuous)
                                    .fill(Palette.surfaceVariant(scheme).opacity(0.5))
                            )

                        Text("Checked when you open the app, and in the background when iOS wakes it — which it decides based on how you use the app, so background checks are best-effort rather than a fixed schedule.")
                            .font(.system(size: 11))
                            .foregroundStyle(Palette.onSurfaceVariant(scheme))
                            .fixedSize(horizontal: false, vertical: true)
                            .padding(.top, 12)
                    }

                    Color.clear.frame(height: 20)
                }
                .padding(WbDimens.screenPadding)
            }
            .wbScreenBackground(scheme)
            .navigationTitle("New alert")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") {
                        viewModel.clearSearch()
                        dismiss()
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Create") {
                        if let value = parsed {
                            onCreate(ticker.trimmingCharacters(in: .whitespaces), kind, value)
                        }
                        viewModel.clearSearch()
                        dismiss()
                    }
                    .disabled(!valid)
                }
            }
        }
    }
}
