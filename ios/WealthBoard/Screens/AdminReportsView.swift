import SwiftUI

/// The bug-report console, reachable only from an administrator's profile.
///
/// The screen check is a convenience, not a boundary — the service enforces who
/// may read these. It is here so the row does not appear for everyone else.
struct AdminReportsView: View {
    @Environment(\.colorScheme) private var scheme

    @State private var reports: [BugReport] = []
    @State private var loading = false
    @State private var error: String?
    @State private var pendingDelete: BugReport?

    var body: some View {
        Group {
            if !Services.bugReports.isConfigured {
                EmptyNote(text: "Bug reports aren't set up in this build. Reports submitted from Contact Support go out by email instead, so there is nothing to list here.")
                    .padding(WbDimens.screenPadding)
                    .frame(maxHeight: .infinity, alignment: .top)
            } else if loading && reports.isEmpty {
                ProgressView()
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if reports.isEmpty {
                EmptyNote(text: "No bug reports yet")
                    .padding(WbDimens.screenPadding)
                    .frame(maxHeight: .infinity, alignment: .top)
            } else {
                list
            }
        }
        .wbScreenBackground(scheme)
        .navigationTitle("Bug Reports")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Button {
                    Task { await load() }
                } label: {
                    Image(systemName: "arrow.clockwise")
                }
                .accessibilityLabel("Refresh")
            }
        }
        .task { await load() }
        .alert(
            "Delete this report?",
            isPresented: Binding(
                get: { pendingDelete != nil },
                set: { if !$0 { pendingDelete = nil } }
            ),
            presenting: pendingDelete
        ) { report in
            Button("Delete", role: .destructive) {
                Task { await delete(report) }
            }
            Button("Cancel", role: .cancel) { pendingDelete = nil }
        } message: { _ in
            Text("This can't be undone.")
        }
    }

    private var list: some View {
        ScrollView {
            LazyVStack(spacing: 12) {
                Text("\(reports.count) \(reports.count == 1 ? "report" : "reports")")
                    .font(.wbBodySmall)
                    .foregroundStyle(Palette.onSurfaceVariant(scheme))
                    .frame(maxWidth: .infinity, alignment: .leading)

                if let error {
                    Text(error)
                        .font(.wbBodySmall)
                        .foregroundStyle(Brand.loss)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }

                ForEach(reports) { report in
                    card(report)
                }

                Color.clear.frame(height: 24)
            }
            .padding(WbDimens.screenPadding)
        }
        .refreshable { await load() }
    }

    private func card(_ report: BugReport) -> some View {
        WbCard {
            HStack(alignment: .top) {
                Text(report.subject.isEmpty ? "(no subject)" : report.subject)
                    .font(.wbBodyLarge)
                    .fontWeight(.semibold)
                    .foregroundStyle(Palette.onSurface(scheme))
                Spacer(minLength: 8)
                StatusPill(
                    text: report.isResolved ? "Resolved" : "Open",
                    color: report.isResolved ? Brand.gain : Brand.divAmber
                )
            }
            .padding(.bottom, 4)

            Text("From: \(report.userEmail.isEmpty ? "unknown" : report.userEmail)")
                .font(.wbBodySmall)
                .foregroundStyle(Palette.onSurfaceVariant(scheme))
            Text(Self.timestamp.string(from: report.submittedAt))
                .font(.wbBodySmall)
                .foregroundStyle(Palette.onSurfaceVariant(scheme))
                .padding(.bottom, 8)

            Text(report.detail)
                .font(.wbBodyMedium)
                .foregroundStyle(Palette.onSurface(scheme))
                .fixedSize(horizontal: false, vertical: true)
                .padding(.bottom, 12)

            HStack(spacing: 12) {
                if !report.isResolved {
                    Button("Mark Resolved") {
                        Task { await setStatus(report, "resolved") }
                    }
                    .font(.wbBodySmall)
                    .fontWeight(.medium)
                    .foregroundStyle(Brand.gain)
                } else {
                    Button("Reopen") {
                        Task { await setStatus(report, "open") }
                    }
                    .font(.wbBodySmall)
                    .fontWeight(.medium)
                    .foregroundStyle(Brand.divAmber)
                }

                Spacer()

                Button("Delete") { pendingDelete = report }
                    .font(.wbBodySmall)
                    .fontWeight(.medium)
                    .foregroundStyle(Brand.loss)
            }
            .buttonStyle(.plain)
        }
    }

    // MARK: - Behaviour

    private func load() async {
        guard Services.bugReports.isConfigured else { return }
        loading = true
        defer { loading = false }
        do {
            reports = try await Services.bugReports.loadAll()
            error = nil
        } catch {
            self.error = error.localizedDescription
        }
    }

    private func setStatus(_ report: BugReport, _ status: String) async {
        do {
            try await Services.bugReports.setStatus(report.id, status: status)
            await load()
        } catch {
            self.error = error.localizedDescription
        }
    }

    private func delete(_ report: BugReport) async {
        pendingDelete = nil
        do {
            try await Services.bugReports.delete(report.id)
            await load()
        } catch {
            self.error = error.localizedDescription
        }
    }

    private static let timestamp: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "MMM d, yyyy • h:mm a"
        return f
    }()
}
