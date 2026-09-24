import SwiftUI

// The two charts that only the holding detail screen draws: the fund's own
// per-unit distribution record by year, and the trailing-yield line with its
// five-year average. Ported from the private composables in `HoldingScreen.kt`.

// MARK: - Dividend history

/// Per-unit distributions by calendar year, as a bar chart.
///
/// Shows when a security started paying and how the annual distribution has
/// moved since. Bars are the fund's own record — per unit, not scaled by the
/// user's position — so the shape stays meaningful regardless of when the
/// holding was bought or how many units are held.
///
/// The chart scrolls horizontally once there are more years than fit on a
/// phone, and opens parked on the most recent years: that is the end of the
/// series people look at first, and a long history would otherwise start
/// scrolled to payments made a decade ago.
struct DividendHistoryChart: View {
    @Environment(\.colorScheme) private var scheme

    /// `isProjected` marks a forecast year — the fund's own record extended by
    /// `DividendForecast`, not an actual distribution — so it can be dimmed the
    /// same way a projected bar is everywhere else in the app.
    let yearly: [(year: Int, perUnit: Double, isProjected: Bool)]
    let currencyCode: String

    private let chartHeight: CGFloat = 150
    private let slotWidth: CGFloat = 52
    /// Headroom at the TOP of each bar for the printed value. Taking it off the
    /// bottom instead leaves unused space under the bars and stamps the tallest
    /// bar's label on top of the bar itself.
    private let labelRoom: CGFloat = 18

    private var maxValue: Double {
        max(yearly.map(\.perUnit).max() ?? 0, 0.0001)
    }

    var body: some View {
        if yearly.isEmpty {
            EmptyView()
        } else {
            VStack(alignment: .leading, spacing: 0) {
                yearOverYearPill
                scroller
                footer
            }
        }
    }

    /// Year-over-year change on the two most recent complete years, which is
    /// the one number this chart exists to make obvious.
    @ViewBuilder
    private var yearOverYearPill: some View {
        // Actual years only — a projection compared against another
        // projection, or against the real prior year, is not a "year-over-year
        // change" the fund has actually delivered.
        let actual = yearly.filter { !$0.isProjected }
        if actual.count >= 2 {
            let last = actual[actual.count - 1]
            let prev = actual[actual.count - 2]
            if prev.perUnit > 0 {
                let change = (last.perUnit - prev.perUnit) / prev.perUnit * 100
                StatusPill(
                    text: "\(last.year): \(Money.signedPercent(change)) vs \(prev.year)",
                    color: change >= 0 ? Brand.gain : Brand.loss
                )
                .padding(.bottom, 10)
            }
        }
    }

    private var scroller: some View {
        ScrollViewReader { proxy in
            ScrollView(.horizontal, showsIndicators: false) {
                VStack(alignment: .leading, spacing: 0) {
                    HStack(alignment: .bottom, spacing: 0) {
                        ForEach(yearly, id: \.year) { entry in
                            bar(entry.perUnit, isProjected: entry.isProjected)
                                .frame(width: slotWidth, height: chartHeight)
                        }
                    }

                    Rectangle()
                        .fill(Palette.onSurface(scheme).opacity(0.35))
                        .frame(width: slotWidth * CGFloat(yearly.count), height: 1)

                    HStack(spacing: 0) {
                        ForEach(yearly, id: \.year) { entry in
                            HStack(spacing: 3) {
                                Text(String(entry.year))
                                    .font(.system(size: 10))
                                    .foregroundStyle(Palette.onSurfaceVariant(scheme))
                                if entry.isProjected {
                                    Circle()
                                        .fill(Palette.onSurfaceVariant(scheme))
                                        .frame(width: 3, height: 3)
                                }
                            }
                            .frame(width: slotWidth)
                            .padding(.top, 6)
                            .id(entry.year)
                        }
                    }
                }
            }
            .onAppear {
                if let last = yearly.last?.year {
                    proxy.scrollTo(last, anchor: .trailing)
                }
            }
        }
    }

    private func bar(_ amount: Double, isProjected: Bool) -> some View {
        GeometryReader { geo in
            let baseline = geo.size.height
            let plotHeight = max(geo.size.height - labelRoom, 1)
            let barHeight = amount <= 0 ? 0 : max(amount / maxValue * plotHeight, 1)
            let barWidth = geo.size.width * 0.55

            ZStack(alignment: .bottom) {
                // Four gridlines behind the bar, matching the chart's scale.
                VStack(spacing: 0) {
                    ForEach(0..<5, id: \.self) { _ in
                        Rectangle()
                            .fill(Palette.onSurface(scheme).opacity(0.22))
                            .frame(height: 1)
                        Spacer(minLength: 0)
                    }
                }
                .frame(height: plotHeight)
                .offset(y: -(baseline - plotHeight) / 2)

                if amount > 0 {
                    VStack(spacing: 2) {
                        Text(Self.valueLabel(amount))
                            .font(.system(size: 10, weight: .semibold))
                            .foregroundStyle(Palette.onSurface(scheme))
                            .lineLimit(1)
                        Rectangle()
                            .fill(Palette.primary(scheme))
                            .frame(width: barWidth, height: barHeight)
                    }
                    .frame(maxHeight: .infinity, alignment: .bottom)
                    // Same dimming convention as every other projected bar in
                    // the app: a lower opacity, not a different color, so a
                    // forecast reads as "this chart, softened" rather than as
                    // an unrelated second series.
                    .opacity(isProjected ? 0.55 : 1)
                }
            }
            .frame(width: geo.size.width, height: geo.size.height, alignment: .bottom)
        }
    }

    private var footer: some View {
        // The closing figure is the last ACTUAL year, not a projected one —
        // "2025 total" should never quietly turn into a forecast because five
        // more, dimmed bars were appended after it.
        let lastActual = yearly.last { !$0.isProjected }
        return HStack {
            Text("First paid \(String(yearly.first?.year ?? 0))")
            Spacer()
            if let lastActual {
                Text("\(String(lastActual.year)) total \(Money.perUnit(lastActual.perUnit, currencyCode)) / unit")
            }
        }
        .font(.wbBodySmall)
        .foregroundStyle(Palette.onSurfaceVariant(scheme))
        .padding(.top, 10)
    }

    /// Per-unit distributions are small numbers, so this prints more precision
    /// than a money format would give — "0.34" beats "$0".
    private static func valueLabel(_ amount: Double) -> String {
        if amount >= 1 { return String(format: "%.2f", amount) }
        var s = String(format: "%.3f", amount)
        while s.hasSuffix("0") { s.removeLast() }
        if s.hasSuffix(".") { s.removeLast() }
        return s
    }
}

// MARK: - Yield history

/// Trailing-yield line with a dashed average reference, mirroring how dividend
/// trackers show whether a holding is cheap or expensive versus its own history.
struct YieldHistoryChart: View {
    @Environment(\.colorScheme) private var scheme

    let points: [YieldPoint]
    var averagePercent: Double?

    /// Left gutter for the axis labels. Without one the chart draws gridlines
    /// with no scale at all — the shape is readable, the actual yield at any
    /// point is not.
    private let leftGutter: CGFloat = 46

    var body: some View {
        GeometryReader { geo in
            let values = points.map(\.yieldPercent)
            let rawMin = values.min() ?? 0
            let rawMax = values.max() ?? 1
            // Pad the band slightly so the line never rides flush against the
            // top or bottom edge of the plot.
            let pad = max((rawMax - rawMin) * 0.08, 0.02)
            let minV = max(rawMin - pad, 0)
            let maxV = rawMax + pad
            let range = (maxV - minV) < 0.01 ? 1.0 : (maxV - minV)

            let plotWidth = max(geo.size.width - leftGutter, 1)
            let height = geo.size.height

            // Closures, not local `func`s. A function declaration inside a
            // ViewBuilder closure makes the compiler read its `return` as a
            // return from the builder itself — "Cannot use explicit 'return'
            // statement in the body of result builder 'ViewBuilder'", and then
            // the whole GeometryReader fails to infer its Content.
            let x: (Int) -> CGFloat = { i in
                points.count > 1
                    ? leftGutter + CGFloat(i) / CGFloat(points.count - 1) * plotWidth
                    : leftGutter
            }
            let y: (Double) -> CGFloat = { v in
                height - CGFloat((v - minV) / range) * height
            }

            ZStack(alignment: .topLeading) {
                // Gridlines, each labelled with the yield it represents.
                ForEach(0..<5, id: \.self) { step in
                    let gy = height * CGFloat(step) / 4
                    let value = maxV - range * Double(step) / 4

                    Path { p in
                        p.move(to: CGPoint(x: leftGutter, y: gy))
                        p.addLine(to: CGPoint(x: geo.size.width, y: gy))
                    }
                    .stroke(Palette.onSurface(scheme).opacity(0.22), lineWidth: 0.8)

                    Text(String(format: "%.2f%%", value))
                        .font(.system(size: 10))
                        .foregroundStyle(Palette.onSurfaceVariant(scheme))
                        .frame(width: leftGutter - 6, alignment: .trailing)
                        .offset(x: 0, y: min(max(gy - 6, 0), height - 12))
                }

                // Five-year average reference, dashed.
                if let averagePercent, averagePercent >= minV, averagePercent <= maxV {
                    Path { p in
                        p.move(to: CGPoint(x: leftGutter, y: y(averagePercent)))
                        p.addLine(to: CGPoint(x: geo.size.width, y: y(averagePercent)))
                    }
                    .stroke(
                        Palette.onSurfaceVariant(scheme),
                        style: StrokeStyle(lineWidth: 1.2, dash: [6, 5])
                    )
                }

                if points.count >= 2 {
                    Path { p in
                        for (i, point) in points.enumerated() {
                            let pt = CGPoint(x: x(i), y: y(point.yieldPercent))
                            if i == 0 { p.move(to: pt) } else { p.addLine(to: pt) }
                        }
                    }
                    .stroke(
                        Palette.primary(scheme),
                        style: StrokeStyle(lineWidth: 2.2, lineCap: .round, lineJoin: .round)
                    )

                    // Marker on the latest point.
                    Circle()
                        .fill(Palette.primary(scheme))
                        .frame(width: 7, height: 7)
                        .position(
                            x: x(points.count - 1),
                            y: y(points[points.count - 1].yieldPercent)
                        )
                }
            }
        }
    }
}
