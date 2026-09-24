import SwiftUI

/// A date row that opens the system wheel picker — month, day and year on three
/// spinning columns — in a bottom sheet, with Cancel and Confirm above it.
///
/// This is `UIDatePicker` in its `.wheel` mode, so the scrolling, the momentum,
/// the haptics and the locale-driven column order are the system's, not a
/// reimplementation of them. Only the sheet around it is ours.
///
/// Cancel and Confirm are the reason the sheet edits a DRAFT rather than the
/// binding directly: a wheel commits its value the instant it stops spinning,
/// so without a draft there would be nothing left for Cancel to undo — the date
/// would already have changed behind the sheet.
struct WheelDateField: View {
    @Environment(\.colorScheme) private var scheme

    let label: String
    @Binding var date: Date
    /// Latest date the wheel will offer. Recording a trade in the future is
    /// usually a typo, so callers generally pass `Date()`.
    ///
    /// A plain `Date?` rather than a range: a stored property needs a concrete
    /// type, and `...Date()` is a `PartialRangeThrough<Date>`, which is not a
    /// `ClosedRange`. The bound is turned into a range where the picker is
    /// built, which is the one place that needs it.
    var maxDate: Date?

    @State private var isShowing = false
    @State private var draft = Date()

    init(_ label: String, date: Binding<Date>, maxDate: Date? = nil) {
        self.label = label
        self._date = date
        self.maxDate = maxDate
    }

    var body: some View {
        Button {
            draft = date
            isShowing = true
        } label: {
            HStack {
                Text(label)
                    .font(.wbBodyLarge)
                    .foregroundStyle(Palette.onSurface(scheme))
                Spacer(minLength: 8)
                Text(date.formatted(date: .abbreviated, time: .omitted))
                    .font(.wbBodyLarge)
                    .foregroundStyle(Palette.accent(scheme))
                    .padding(.horizontal, 11)
                    .padding(.vertical, 6)
                    .background(
                        Palette.onSurfaceVariant(scheme).opacity(0.16),
                        in: RoundedRectangle(cornerRadius: 7, style: .continuous)
                    )
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
        .accessibilityValue(date.formatted(date: .complete, time: .omitted))
        .sheet(isPresented: $isShowing) {
            WheelDateSheet(
                label: label,
                draft: $draft,
                maxDate: maxDate,
                onConfirm: {
                    date = draft
                    isShowing = false
                },
                onCancel: { isShowing = false }
            )
        }
    }
}

/// The sheet itself: the two actions on one row, the wheel under them.
private struct WheelDateSheet: View {
    @Environment(\.colorScheme) private var scheme

    let label: String
    @Binding var draft: Date
    let maxDate: Date?
    let onConfirm: () -> Void
    let onCancel: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Button("Cancel", action: onCancel)
                    .font(.wbBodyLarge)
                    .foregroundStyle(Palette.onSurface(scheme))
                Spacer()
                Button("Confirm", action: onConfirm)
                    .font(.wbBodyLarge)
                    .fontWeight(.semibold)
                    .foregroundStyle(Brand.gain)
            }
            .buttonStyle(.plain)
            .padding(.horizontal, 20)
            .padding(.top, 18)
            .padding(.bottom, 4)

            picker
                .labelsHidden()
                .datePickerStyle(.wheel)
                .frame(maxWidth: .infinity)

            Spacer(minLength: 0)
        }
        .background(Palette.surface(scheme).ignoresSafeArea())
        // Tall enough for the wheel's full five visible rows; any shorter and
        // the system clips the outer rows rather than compressing them.
        .presentationDetents([.height(340)])
        .presentationDragIndicator(.visible)
    }

    /// Bounded and unbounded are separate calls because `DatePicker` takes the
    /// bound as part of its initialiser, not as a modifier.
    @ViewBuilder
    private var picker: some View {
        if let maxDate {
            DatePicker(label, selection: $draft, in: ...maxDate, displayedComponents: .date)
        } else {
            DatePicker(label, selection: $draft, displayedComponents: .date)
        }
    }
}
