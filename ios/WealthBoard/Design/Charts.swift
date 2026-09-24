import SwiftUI

// Every chart in the app is drawn by hand with `Path` and `Canvas` rather than
// through Swift Charts, for the same reason the Android build uses `Canvas`
// instead of a charting library: these are small, opinionated visuals whose
// exact geometry (bar headroom, label placement, axis rounding) is part of the
// design, and a general-purpose chart library fights that at every step.

// MARK: - Axis helpers

enum ChartAxis {

    /// Rounds a chart's maximum up to a readable 1/2/5 × 10^n step.
    /// Ported from `niceAxisMax` in DividendsScreen.kt.
    static func niceMax(_ v: Double) -> Double {
        guard v > 0 else { return 1.0 }
        let magnitude = pow(10.0, floor(log10(v)))
        let n = v / magnitude
        let step: Double
        if n <= 1.0 { step = 1.0 }
        else if n <= 2.0 { step = 2.0 }
        else if n <= 5.0 { step = 5.0 }
        else { step = 10.0 }
        return step * magnitude
    }

    /// Axis labels widened until they are all distinct — a compact form rounds
    /// a small scale to the same string on every gridline, which reads as a
    /// broken chart rather than a small one.
    static func labels(_ values: [Double]) -> [String] {
        for digits in 0...4 {
            let out = values.map { v -> String in
                guard v > 0 else { return "0" }
                let f = NumberFormatter()
                f.locale = Locale(identifier: "en_US_POSIX")
                f.numberStyle = .decimal
                f.minimumFractionDigits = digits
                f.maximumFractionDigits = digits
                return f.string(from: NSNumber(value: v)) ?? "0"
            }
            if Set(out).count == out.count { return out }
        }
        return values.map { Money.compact($0) }
    }
}

// MARK: - Mini sparkline

/// The 44×28 intraday trace in a Markets row. Two points minimum; anything
/// less draws nothing rather than a misleading flat line.
struct MiniSparkline: View {
    let data: [Double]
    let color: Color

    var body: some View {
        GeometryReader { geo in
            Path { path in
                guard data.count >= 2 else { return }
                let lo = data.min() ?? 0
                let hi = data.max() ?? 1
                let span = max(hi - lo, 0.0001)
                let stepX = geo.size.width / CGFloat(data.count - 1)
                for (i, value) in data.enumerated() {
                    let x = CGFloat(i) * stepX
                    let y = geo.size.height * (1 - CGFloat((value - lo) / span))
                    if i == 0 { path.move(to: CGPoint(x: x, y: y)) }
                    else { path.addLine(to: CGPoint(x: x, y: y)) }
                }
            }
            .stroke(color, style: StrokeStyle(lineWidth: 1.4, lineCap: .round, lineJoin: .round))
        }
    }
}

// MARK: - Area chart

/// One point on a value/price series.
struct ChartPoint: Identifiable {
    let date: Date
    let value: Double
    var id: TimeInterval { date.timeIntervalSince1970 }
}

/// The Apple-Stocks-style area chart used for portfolio value and for a single
/// holding's price. Draws a gradient fill under a stroked line, a faint grid,
/// and right-hand value labels — the same anatomy as `StockAreaChart.kt`.
struct AreaChart: View {
    @Environment(\.colorScheme) private var scheme

    let points: [ChartPoint]
    var lineColor: Color?
    var showAxisLabels: Bool = true
    /// Exchange zone the x labels should be drawn in, when the series is
    /// intraday and belongs to a foreign listing.
    var zoneId: String?
    var intraday: Bool = false

    /// The range chip this series was fetched for ("1D", "1W", "1M" …).
    ///
    /// The x axis cannot be worded from the data alone. "MMM d" is right for a
    /// month and wrong for five years, where every label reads "Sep 17" with no
    /// year to tell 2021 from 2026 — which is exactly how a correct series
    /// comes to look like a broken one. Android picks the format from the range
    /// for the same reason; this carries the range across so it can too.
    var rangeLabel: String?

    /// A horizontal reference the series is read against — yesterday's close on
    /// an intraday price chart.
    ///
    /// The dashed rule every finance app draws at the previous close. Without
    /// it "up today" is a judgement about where the line started, which on an
    /// intraday chart is the open, not the close it is actually measured from;
    /// the two differ by the overnight gap. The y range is widened to keep the
    /// rule on screen, because a reference line that has been scrolled out of
    /// the plot silently stops being a reference.
    var baseline: Double?

    /// Number of decimals the scrub readout prints. Nil follows the y axis,
    /// which is what a price chart wants; the portfolio chart passes 0 because
    /// cents on a six-figure total are noise.
    var readoutDecimals: Int?

    /// Index under the finger while scrubbing, nil when not touching.
    @State private var scrubIndex: Int?

    private var isUp: Bool {
        // Measured against the baseline when there is one, so the colour agrees
        // with the figure printed above the chart: an intraday series that
        // opened below yesterday's close and never recovered is a DOWN day even
        // though the line itself rose all afternoon.
        if let baseline, let last = points.last?.value { return last >= baseline }
        guard let first = points.first?.value, let last = points.last?.value else { return true }
        return last >= first
    }

    private var resolvedColor: Color {
        lineColor ?? (isUp ? Brand.gain : Brand.loss)
    }

    var body: some View {
        if points.count < 2 {
            emptyState
        } else {
            chart
        }
    }

    private var emptyState: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 8)
                .fill(Palette.surfaceVariant(scheme).opacity(0.35))
            Text("Not enough price history yet")
                .font(.wbBodySmall)
                .foregroundStyle(Palette.onSurfaceVariant(scheme))
        }
    }

    private var chart: some View {
        let values = points.map(\.value)
        // The baseline is part of the extent, not an overlay on top of it. A
        // previous close outside the session's own high and low — a gap up or
        // down, which is precisely when the reference matters most — would
        // otherwise be drawn off the plot.
        // Spelled out rather than leaning on implicit member syntax: a
        // leading-dot member inside a prefix minus is one of the places Swift's
        // inference gives up.
        let lo = min(values.min() ?? 0, baseline ?? Double.greatestFiniteMagnitude)
        let hi = max(values.max() ?? 1, baseline ?? -Double.greatestFiniteMagnitude)
        // A little vertical breathing room, so the line never sits flush with
        // the frame edge and the highest point stays readable.
        let pad = max((hi - lo) * 0.08, hi == lo ? max(abs(hi) * 0.01, 0.5) : 0)
        let low = lo - pad
        let high = hi + pad
        let span = max(high - low, 0.0001)
        // Cents are signal on a $45 share and noise on a $68,000 portfolio.
        //
        // Fixed at 0 decimals, every gridline on a $45 chart printed "45" — four
        // identical labels, which reads as a broken axis rather than a narrow
        // one. The same rule the Android chart uses: whole dollars once the
        // scale is in the thousands, cents below it.
        let axisDecimals: Int = high >= 1000 ? 0 : 2
        let axisLabels = showAxisLabels
            ? (0..<4).map { Money.plain(high - (high - low) * Double($0) / 3, decimals: axisDecimals) }
            : []
        let widestLabel = axisLabels.map(\.count).max() ?? 0
        // The gutter is measured, not guessed.
        //
        // A fixed 58pt was sized for the widest figure it might ever hold, so a
        // chart labelled "134" left a strip of dead card between the plot and
        // the numbers; drawing the labels over the plot instead put them on top
        // of the line. The plot now ends exactly where the widest label starts.
        // 9pt digits run a shade over 5.5pt wide, plus the gap to the plot.
        let labelWidth: CGFloat = showAxisLabels
            ? min(max(CGFloat(widestLabel) * 5.8 + 10, 26), 64)
            : 0

        return GeometryReader { geo in
            let plotWidth = max(geo.size.width - labelWidth, 1)
            let plotHeight = max(geo.size.height - (showAxisLabels ? 18 : 0), 1)
            let point = { (i: Int) -> CGPoint in
                CGPoint(
                    x: plotWidth * CGFloat(i) / CGFloat(max(points.count - 1, 1)),
                    y: plotHeight * (1 - CGFloat((points[i].value - low) / span))
                )
            }

            ZStack(alignment: .topLeading) {
                // Grid
                Path { p in
                    for row in 0...3 {
                        let y = plotHeight * CGFloat(row) / 3
                        p.move(to: CGPoint(x: 0, y: y))
                        p.addLine(to: CGPoint(x: plotWidth, y: y))
                    }
                }
                // onSurface at 0.22, the same gridline tone StockAreaChart.kt
                // uses — `outline` reads noticeably cooler on the navy scheme.
                .stroke(Palette.onSurface(scheme).opacity(0.22), lineWidth: 0.6)

                // Gradient fill under the line
                Path { p in
                    p.move(to: CGPoint(x: 0, y: plotHeight))
                    for i in points.indices { p.addLine(to: point(i)) }
                    p.addLine(to: CGPoint(x: plotWidth, y: plotHeight))
                    p.closeSubpath()
                }
                .fill(
                    LinearGradient(
                        colors: [resolvedColor.opacity(0.32), resolvedColor.opacity(0)],
                        startPoint: .top,
                        endPoint: .bottom
                    )
                )

                // Previous close, under the line rather than over it: it is the
                // thing being measured against, not a second series.
                if let baseline {
                    let y = plotHeight * (1 - CGFloat((baseline - low) / span))
                    Path { p in
                        p.move(to: CGPoint(x: 0, y: y))
                        p.addLine(to: CGPoint(x: plotWidth, y: y))
                    }
                    .stroke(
                        Palette.onSurface(scheme).opacity(0.45),
                        style: StrokeStyle(lineWidth: 1, dash: [4, 3])
                    )
                }

                // The line itself
                Path { p in
                    for i in points.indices {
                        if i == 0 { p.move(to: point(i)) } else { p.addLine(to: point(i)) }
                    }
                }
                .stroke(resolvedColor, style: StrokeStyle(lineWidth: 1.8, lineCap: .round, lineJoin: .round))

                // Scrub crosshair — a vertical rule through the touched point,
                // a dot on the line, and the value and moment printed above.
                //
                // The readout goes INSIDE the plot rather than above the card,
                // because the chart is the only thing that knows which point is
                // under the finger, and lifting that out through a binding
                // would make every caller responsible for drawing it.
                if let index = scrubIndex, points.indices.contains(index) {
                    let position = point(index)
                    Path { p in
                        p.move(to: CGPoint(x: position.x, y: 0))
                        p.addLine(to: CGPoint(x: position.x, y: plotHeight))
                    }
                    .stroke(resolvedColor.opacity(0.55), lineWidth: 1)

                    Circle()
                        .fill(resolvedColor)
                        .frame(width: 10, height: 10)
                        .overlay(Circle().fill(.white).frame(width: 4, height: 4))
                        .position(x: position.x, y: position.y)

                    scrubReadout(index: index, decimals: axisDecimals)
                        .position(
                            // Clamped so the label cannot run off either edge:
                            // the point being read is often at one end of the
                            // plot, which is where a centred label would be
                            // half off-screen.
                            x: min(max(position.x, 62), max(plotWidth - 62, 62)),
                            y: 8
                        )
                }

                if showAxisLabels {
                    // Value axis, in its own gutter beside the plot.
                    VStack(alignment: .leading, spacing: 0) {
                        ForEach(Array(axisLabels.enumerated()), id: \.offset) { _, label in
                            Text(label)
                                .font(.system(size: 9))
                                .foregroundStyle(Palette.onSurfaceVariant(scheme))
                                .lineLimit(1)
                                .frame(height: plotHeight / 3, alignment: .top)
                        }
                    }
                    .frame(width: labelWidth, alignment: .leading)
                    .padding(.leading, 6)
                    .offset(x: plotWidth, y: -4)

                    // Time axis, each stamp sitting over the point it names.
                    //
                    // These used to be three strings in an HStack with spacers
                    // between them, which puts the middle one at the middle of
                    // the CARD and the last one hard against the gutter — near
                    // enough on an evenly spaced month, wrong the moment the
                    // series is not evenly spaced, and wrong about which bar it
                    // is naming either way. Placing each label at its own bar's
                    // x means the date under a point is that point's date.
                    ForEach(xTicks(plotWidth: plotWidth), id: \.id) { tick in
                        Text(tick.text)
                            .font(.system(size: 9))
                            .foregroundStyle(Palette.onSurfaceVariant(scheme))
                            .fixedSize()
                            .position(x: tick.x, y: plotHeight + 9)
                            // Hidden under the finger: the dates and the
                            // readout occupy the same band at the ends of the
                            // plot, and two overlapping labels is worse than
                            // one.
                            .opacity(scrubIndex == nil ? 1 : 0)
                    }
                }
            }
            // `minimumDistance: 0` so a tap reads a value without having to
            // drag first — checking one point is the common case, and Apple
            // Stocks behaves the same way.
            //
            // This does claim vertical drags over the plot, so the page cannot
            // be scrolled by starting the gesture on the chart. That is the
            // trade a finance chart makes — every one of them behaves this way
            // — and the card is 200pt of a scrolling screen, so there is plenty
            // of room either side to scroll from.
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { value in
                        scrubIndex = index(atX: value.location.x, plotWidth: plotWidth)
                    }
                    .onEnded { _ in scrubIndex = nil }
            )
        }
    }

    /// The bar nearest an x position inside the plot.
    private func index(atX x: CGFloat, plotWidth: CGFloat) -> Int? {
        guard points.count >= 2, plotWidth > 1 else { return nil }
        let fraction = min(max(x / plotWidth, 0), 1)
        let raw = (fraction * CGFloat(points.count - 1)).rounded()
        return min(max(Int(raw), 0), points.count - 1)
    }

    /// When the scrubbed point was, spelled out.
    ///
    /// Fuller than the axis: the axis says roughly when, this says exactly
    /// when, and on an intraday chart the date matters as much as the time once
    /// a range spans more than one session.
    ///
    /// A plain function rather than a `let` inside the ViewBuilder below: a
    /// result builder takes declarations, not the statements a DateFormatter
    /// needs to configure itself.
    private func scrubDateText(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale.current
        if let zoneId, let tz = TimeZone(identifier: zoneId) { formatter.timeZone = tz }
        formatter.dateFormat = intraday ? "MMM d, h:mm a" : "MMM d, yyyy"
        return formatter.string(from: date)
    }

    /// The value and moment under the finger.
    private func scrubReadout(index: Int, decimals: Int) -> some View {
        VStack(spacing: 1) {
            Text(scrubDateText(points[index].date))
                .font(.system(size: 9))
                .foregroundStyle(Palette.onSurfaceVariant(scheme))
            Text(Money.plain(points[index].value, decimals: readoutDecimals ?? decimals))
                .font(.system(size: 13, weight: .bold))
                .foregroundStyle(resolvedColor)
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 4)
        .background(
            RoundedRectangle(cornerRadius: 6, style: .continuous)
                .fill(Palette.surface(scheme).opacity(0.92))
        )
        .fixedSize()
    }

    /// One dated label on the time axis, with the x it belongs over.
    private struct XTick: Identifiable {
        let index: Int
        let text: String
        let x: CGFloat
        var id: Int { index }
    }

    /// Date format for the axis, chosen by the range being drawn.
    ///
    /// Ported from `xLabelFormat` in StockAreaChart.kt. Falls back to the
    /// intraday flag when a caller has no range to give — a portfolio chart
    /// drawn from a stored equity curve, for instance.
    private func axisFormat() -> String {
        switch rangeLabel {
        case "1D":                  return "h:mm a"
        case "1W":                  return "EEE"
        case "1M":                  return "MMM d"
        case "6M", "YTD", "1Y":     return "MMM"
        case "5Y", "ALL":           return "MMM yy"
        default:                    return intraday ? "h:mm a" : "MMM d"
        }
    }

    /// Up to five labels across the axis, dropped where they would collide.
    ///
    /// The first and last are kept whatever happens — they carry the span of
    /// the chart — and the ones between give way, because two dates printed on
    /// top of each other ("Sep 8Sep 12") is worse than three dates.
    private func xTicks(plotWidth: CGFloat) -> [XTick] {
        guard points.count >= 2, plotWidth > 1 else { return [] }

        let formatter = DateFormatter()
        formatter.locale = Locale.current
        if let zoneId, let tz = TimeZone(identifier: zoneId) { formatter.timeZone = tz }
        formatter.dateFormat = axisFormat()

        let steps = 4
        let lastIndex = points.count - 1
        var candidates: [XTick] = []
        var seenIndex = Set<Int>()

        // One step at a time, with a named type on every intermediate.
        //
        // Written as one expression this is a conversion chain
        // (Int→Double→CGFloat) feeding a range built from arithmetic, and that
        // is the shape that makes the type checker give up — which it did, in
        // this file's neighbour, with "unable to type-check this expression in
        // reasonable time".
        for step in 0...steps {
            let fraction: Double = Double(step) / Double(steps)
            let rawIndex: Double = (fraction * Double(lastIndex)).rounded()
            let index: Int = min(Int(rawIndex), lastIndex)
            guard seenIndex.insert(index).inserted else { continue }

            let text: String = formatter.string(from: points[index].date)
            // 9pt text runs a little over 5.4pt per character; close enough to
            // decide whether two labels touch.
            let width: CGFloat = CGFloat(text.count) * 5.4 + 2
            let half: CGFloat = width / 2
            let natural: CGFloat = plotWidth * CGFloat(index) / CGFloat(lastIndex)
            let upper: CGFloat = max(plotWidth - half, half)
            let x: CGFloat = min(max(natural, half), upper)
            candidates.append(XTick(index: index, text: text, x: x))
        }
        guard let first = candidates.first, let last = candidates.last else { return [] }
        if candidates.count == 1 { return candidates }

        func width(_ tick: XTick) -> CGFloat { CGFloat(tick.text.count) * 5.4 + 2 }
        let gap: CGFloat = 8

        var kept: [XTick] = [first]
        for tick in candidates.dropFirst().dropLast() {
            guard let previous = kept.last else { continue }
            // Same wording as the label before it says nothing new — a "MMM"
            // axis over six months prints "Sep" three times otherwise.
            if tick.text == previous.text { continue }
            if tick.x - width(tick) / 2 >= previous.x + width(previous) / 2 + gap {
                kept.append(tick)
            }
        }
        // The closing label wins any argument: drop whatever it would have
        // landed on, then place it.
        while kept.count > 1, let previous = kept.last,
              last.x - width(last) / 2 < previous.x + width(previous) / 2 + gap {
            kept.removeLast()
        }
        // Down to the opening label and still colliding: the plot is too narrow
        // for two dates, so it gets the closing one. Where the series ENDS is
        // the more useful of the two on a chart you cannot fit both on.
        if let opening = kept.first, kept.count == 1,
           last.x - width(last) / 2 < opening.x + width(opening) / 2 + gap {
            kept.removeAll()
        }
        if kept.last?.index != last.index, kept.last?.text != last.text {
            kept.append(last)
        }
        return kept
    }
}

extension CGFloat {
    /// Clamps into a range, for label positions that must stay inside the plot.
    func clamped(to limits: ClosedRange<CGFloat>) -> CGFloat {
        Swift.min(Swift.max(self, limits.lowerBound), limits.upperBound)
    }
}

// MARK: - Per-holding income series
//
// Ported from `ui/components/IncomeSeries.kt`.

/// One holding's contribution to a single bar.
///
/// `key` is the stable identity used to pick a colour (the ticker, or the
/// holding name when there is no ticker) and `label` is what the legend shows.
/// Keeping them separate means a renamed holding doesn't change colour.
struct IncomeSlice: Identifiable, Hashable {
    let key: String
    let label: String
    let amount: Double
    var id: String { key }
}

/// A bar made of per-holding slices rather than one flat total. Splitting the
/// bar by holding answers "how much" and "from what" at once, and the total is
/// still just the sum.
struct StackedIncomeBar: Identifiable, Hashable {
    let label: String
    let bucketKey: Int
    var slices: [IncomeSlice] = []
    /// True for a bucket that hasn't happened yet — a forecast, not a record.
    /// `HistoryBarRow` dims these so a projected year is never mistaken for
    /// money actually received.
    var isProjected: Bool = false

    var total: Double { slices.reduce(0) { $0 + $1.amount } }
    var id: Int { bucketKey }
}

/// Colours for per-holding chart series.
///
/// Assignment is by position in the SORTED list of every key on screen, not by
/// hash: a hash gives a holding the same colour forever but lets two of them
/// collide, and two identically-coloured slices in one stack is the one failure
/// a legend cannot explain.
enum SeriesPalette {

    /// Twelve hues that stay distinguishable side by side and against both the
    /// light and dark surfaces the charts sit on, ordered so the first few are
    /// as far apart as possible.
    private static let palette: [Color] = [
        Color(hex: 0x2E8FE0),  // blue
        Color(hex: 0x7A4FD1),  // violet
        Color(hex: 0x0F8B84),  // teal
        Color(hex: 0xE0803C),  // orange
        Color(hex: 0xD1457F),  // magenta
        Color(hex: 0x4CAF50),  // green
        Color(hex: 0xB4622E),  // sienna
        Color(hex: 0x00ACC1),  // cyan
        Color(hex: 0x8E7CC3),  // lavender
        Color(hex: 0xC0392B),  // red
        Color(hex: 0x5C7A29),  // olive
        Color(hex: 0x607D8B)   // slate
    ]

    /// Colour map for `keys`, stable for a given set of keys.
    static func colors(for keys: some Collection<String>) -> [String: Color] {
        let sorted = Array(Set(keys)).sorted()
        var out: [String: Color] = [:]
        for (i, key) in sorted.enumerated() {
            out[key] = palette[i % palette.count]
        }
        return out
    }

    /// Fallback for a key that wasn't in the map the chart was built with.
    static let unknown = Color(hex: 0x9E9E9E)
}

/// Legend for a per-holding chart: one swatch and label per series, laid out in
/// fixed rows rather than a scrolling strip — a legend the reader has to drag
/// sideways to finish reading is worse than one that takes a second line.
struct IncomeSeriesLegend: View {
    @Environment(\.colorScheme) private var scheme

    let entries: [(String, Color)]
    var perRow: Int = 3

    var body: some View {
        if entries.isEmpty {
            EmptyView()
        } else {
            VStack(alignment: .leading, spacing: 5) {
                ForEach(Array(chunks().enumerated()), id: \.offset) { _, row in
                    HStack(spacing: 0) {
                        ForEach(Array(row.enumerated()), id: \.offset) { _, entry in
                            HStack(spacing: 5) {
                                RoundedRectangle(cornerRadius: 2)
                                    .fill(entry.1)
                                    .frame(width: 9, height: 9)
                                Text(entry.0)
                                    .font(.system(size: 10, weight: .medium))
                                    .foregroundStyle(Palette.onSurfaceVariant(scheme))
                                    .lineLimit(1)
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.trailing, 6)
                        }
                        // Pad a short final row so its swatches stay in the
                        // same columns as the rows above.
                        if row.count < perRow {
                            ForEach(0..<(perRow - row.count), id: \.self) { _ in
                                Color.clear.frame(maxWidth: .infinity, maxHeight: 1)
                            }
                        }
                    }
                }
            }
        }
    }

    private func chunks() -> [[(String, Color)]] {
        stride(from: 0, to: entries.count, by: perRow).map {
            Array(entries[$0..<min($0 + perRow, entries.count)])
        }
    }
}

// MARK: - Stacked column chart (Monthly Income)

/// The twelve-column income chart on the Dividends tab. Bars stack by holding,
/// carry a value label above them, and respond to a tap.
///
/// The geometry constants mirror the Android build: the bar area and the label
/// offset are derived from the same numbers, because a label positioned against
/// a different height than the bar it belongs to drifts away as the value changes.
struct StackedColumnChart: View {
    @Environment(\.colorScheme) private var scheme

    let bars: [StackedIncomeBar]
    let seriesColors: [String: Color]
    var fallbackColor: Color = Brand.divBarActual
    @Binding var selectedIndex: Int?

    private let plotHeight: CGFloat = 150
    private let headroom: CGFloat = 0.82

    var body: some View {
        let maxTotal = bars.map(\.total).max() ?? 0
        let axisMax = ChartAxis.niceMax(maxTotal)
        let axisValues = [axisMax, axisMax * 0.75, axisMax * 0.5, axisMax * 0.25, 0]
        let axisLabels = ChartAxis.labels(axisValues)

        HStack(alignment: .bottom, spacing: 6) {
            // Value axis
            VStack(alignment: .trailing, spacing: 0) {
                ForEach(Array(axisLabels.enumerated()), id: \.offset) { _, label in
                    Text(label)
                        .font(.system(size: 9))
                        .foregroundStyle(Palette.onSurfaceVariant(scheme))
                        .frame(height: plotHeight / CGFloat(max(axisLabels.count - 1, 1)),
                               alignment: .top)
                }
            }
            .frame(width: 38, height: plotHeight + 6, alignment: .trailing)
            .padding(.bottom, 16)

            // Columns
            HStack(alignment: .bottom, spacing: 3) {
                ForEach(Array(bars.enumerated()), id: \.element.id) { index, bar in
                    column(bar, index: index, axisMax: axisMax)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    @ViewBuilder
    private func column(_ bar: StackedIncomeBar, index: Int, axisMax: Double) -> some View {
        let fraction = axisMax > 0 ? min(bar.total / axisMax, 1.0) : 0
        let height = plotHeight * CGFloat(fraction) * headroom
        let selected = selectedIndex == index

        VStack(spacing: 2) {
            // The spacer goes FIRST so the label rides on top of its own bar.
            //
            // With the label first and the spacer after it, every value was
            // pinned to the top of the plot instead — a row of numbers along
            // the ceiling, one per column but nowhere near the column it
            // described, which is unreadable the moment two bars differ in
            // height.
            Spacer(minLength: 0)

            Text(bar.total > 0 ? Money.compact(bar.total) : "")
                .font(.system(size: 8.5, weight: .semibold))
                .foregroundStyle(Palette.onSurface(scheme))
                .lineLimit(1)
                .minimumScaleFactor(0.6)
                .frame(height: 11)

            VStack(spacing: 0) {
                ForEach(bar.slices) { slice in
                    let share = bar.total > 0 ? slice.amount / bar.total : 0
                    Rectangle()
                        .fill(seriesColors[slice.key] ?? SeriesPalette.unknown)
                        .frame(height: max(height * CGFloat(share), share > 0 ? 1 : 0))
                }
                if bar.slices.isEmpty {
                    Rectangle().fill(fallbackColor).frame(height: height)
                }
            }
            .frame(height: max(height, 1))
            .clipShape(RoundedRectangle(cornerRadius: 3, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 3)
                    .stroke(selected ? Palette.onSurface(scheme) : .clear, lineWidth: 1.5)
            )
            .opacity(selectedIndex == nil || selected ? 1 : 0.45)

            Text(bar.label)
                .font(.system(size: 9))
                .foregroundStyle(Palette.onSurfaceVariant(scheme))
                .lineLimit(1)
                .minimumScaleFactor(0.6)
                .frame(height: 12)
        }
        .frame(maxWidth: .infinity)
        .frame(height: plotHeight + 25)
        .contentShape(Rectangle())
        .onTapGesture {
            selectedIndex = selected ? nil : index
        }
    }
}

// MARK: - Horizontal stacked bar (Historical Income rows)

/// One row of the Historical Income list: a label, a proportional stacked bar
/// and the amount. The bar is the row's share of the largest row; within it
/// each holding takes its share of *this* row, so the segment boundaries line
/// up with the legend without the row's own length changing.
struct HistoryBarRow: View {
    @Environment(\.colorScheme) private var scheme

    let label: String
    let bar: StackedIncomeBar
    let fraction: Double
    let currencyCode: String
    let seriesColors: [String: Color]
    let fallbackColor: Color

    var body: some View {
        HStack(spacing: 8) {
            HStack(spacing: 3) {
                Text(label)
                    .font(.system(size: 12, weight: .medium))
                    .foregroundStyle(Palette.onSurfaceVariant(scheme))
                    .lineLimit(1)
                if bar.isProjected {
                    // A dot rather than a text suffix: "2029e" or "(proj.)"
                    // crowds a 44pt column at accessibility text sizes, and the
                    // dimmed bar below already carries the same meaning — this
                    // is only for someone scanning the label column alone.
                    Circle()
                        .fill(Palette.onSurfaceVariant(scheme))
                        .frame(width: 3, height: 3)
                }
            }
            .frame(width: 44, alignment: .leading)

            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    Capsule().fill(Palette.surfaceVariant(scheme))
                    HStack(spacing: 0) {
                        ForEach(bar.slices) { slice in
                            let share = bar.total > 0 ? slice.amount / bar.total : 0
                            Rectangle()
                                .fill(seriesColors[slice.key] ?? SeriesPalette.unknown)
                                .frame(width: geo.size.width * CGFloat(fraction) * CGFloat(share))
                        }
                        if bar.slices.isEmpty && fraction > 0 {
                            Rectangle()
                                .fill(fallbackColor)
                                .frame(width: geo.size.width * CGFloat(fraction))
                        }
                    }
                    .clipShape(Capsule())
                }
            }
            .frame(height: 7)
            // The received/estimated split above already uses opacity for
            // "this isn't money in hand yet" (see DividendChartColors); dimming
            // a projected row the same way keeps the two charts speaking one
            // visual language instead of two.
            .opacity(bar.isProjected ? 0.55 : 1)

            Text(bar.total > 0 ? Money.format(bar.total, currencyCode) : "—")
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(bar.total > 0 ? Palette.onSurface(scheme) : Palette.onSurfaceVariant(scheme))
                .frame(width: 80, alignment: .trailing)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
                .opacity(bar.isProjected ? 0.75 : 1)
        }
        .padding(.vertical, 4)
    }
}

// MARK: - Received / estimated bar chart

/// The period selector on the Dividends Received chart.
enum DividendPeriod: String, CaseIterable, Identifiable {
    /// Summed per calendar month.
    case month = "MONTH"
    /// Summed per calendar year, including forward projections.
    case year = "YEAR"

    var id: String { rawValue }
    var label: String { rawValue }
}

/// One bar. `received` is money actually banked, `estimated` is a projection,
/// and `drip` is the extra a projection gains from reinvesting — they stack in
/// that order, so a bar is never double-counted.
struct DividendBar: Identifiable, Hashable {
    let label: String
    var received: Double = 0
    var estimated: Double = 0
    var drip: Double = 0

    var total: Double { received + estimated + drip }
    var id: String { label }
}

/// Series colours, kept here so the chart and its legend can't drift apart.
enum DividendChartColors {
    static let received  = Color(hex: 0x0F8B84)   // teal — money actually received
    static let estimated = Color(hex: 0xD8DBDD)   // pale grey — forecast
    static let drip      = Color(hex: 0x2E8FE0)   // blue — extra from reinvestment
}

/// Dividend income over time, as a stacked bar chart with a MONTH / YEAR
/// selector. Received income sits under forecast income so past and future read
/// on one continuous axis.
struct DividendBarChart: View {
    @Environment(\.colorScheme) private var scheme

    let bars: [DividendBar]
    @Binding var period: DividendPeriod
    let currencyCode: String
    let title: String
    var showDrip: Bool = false

    private let plotHeight: CGFloat = 210

    var body: some View {
        let axisMax = ChartAxis.niceMax(bars.map(\.total).max() ?? 0)
        let axisValues = [axisMax, axisMax * 0.75, axisMax * 0.5, axisMax * 0.25, 0]
        let axisLabels = ChartAxis.labels(axisValues)

        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("\(title), \(Money.symbol(currencyCode).trimmingCharacters(in: .whitespaces))")
                    .font(.wbTitleMedium)
                    .foregroundStyle(Palette.onSurface(scheme))
                Spacer()
                ToggleChips(
                    left: DividendPeriod.month.label,
                    right: DividendPeriod.year.label,
                    selection: Binding(
                        get: { period.label },
                        set: { period = $0 == DividendPeriod.year.label ? .year : .month }
                    )
                )
            }

            HStack(alignment: .bottom, spacing: 6) {
                VStack(alignment: .trailing, spacing: 0) {
                    ForEach(Array(axisLabels.enumerated()), id: \.offset) { _, label in
                        Text(label)
                            .font(.system(size: 9))
                            .foregroundStyle(Palette.onSurfaceVariant(scheme))
                            .frame(height: plotHeight / CGFloat(max(axisLabels.count - 1, 1)),
                                   alignment: .top)
                    }
                }
                .frame(width: 40, height: plotHeight + 6, alignment: .trailing)
                .padding(.bottom, 16)

                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(alignment: .bottom, spacing: 8) {
                        ForEach(bars) { bar in
                            column(bar, axisMax: axisMax)
                        }
                    }
                }
            }

            legend
        }
    }

    @ViewBuilder
    private func column(_ bar: DividendBar, axisMax: Double) -> some View {
        let scale = axisMax > 0 ? plotHeight * 0.86 / CGFloat(axisMax) : 0

        VStack(spacing: 3) {
            // Spacer first, so the figure sits on the bar it belongs to rather
            // than in a row of numbers along the top of the plot.
            Spacer(minLength: 0)

            Text(bar.total > 0 ? Money.compact(bar.total) : "0")
                .font(.system(size: 9, weight: .semibold))
                .foregroundStyle(Palette.onSurface(scheme))
                .lineLimit(1)

            VStack(spacing: 0) {
                if showDrip && bar.drip > 0 {
                    Rectangle().fill(DividendChartColors.drip)
                        .frame(height: max(CGFloat(bar.drip) * scale, 1))
                }
                if bar.estimated > 0 {
                    Rectangle().fill(DividendChartColors.estimated)
                        .frame(height: max(CGFloat(bar.estimated) * scale, 1))
                }
                if bar.received > 0 {
                    Rectangle().fill(DividendChartColors.received)
                        .frame(height: max(CGFloat(bar.received) * scale, 1))
                }
                if bar.total <= 0 {
                    Rectangle().fill(Palette.outline(scheme).opacity(0.4)).frame(height: 1)
                }
            }
            .frame(width: 26)
            .clipShape(RoundedRectangle(cornerRadius: 3, style: .continuous))

            Text(bar.label)
                .font(.system(size: 9))
                .foregroundStyle(Palette.onSurfaceVariant(scheme))
                .lineLimit(1)
                .frame(height: 12)
        }
        .frame(height: plotHeight + 28)
    }

    private var legend: some View {
        HStack(spacing: 14) {
            swatch(DividendChartColors.received, "Received, \(currencyCode)")
            swatch(DividendChartColors.estimated, "Estimated, \(currencyCode)")
            if showDrip {
                swatch(DividendChartColors.drip, "With DRIP")
            }
            Spacer(minLength: 0)
        }
    }

    private func swatch(_ color: Color, _ label: String) -> some View {
        HStack(spacing: 5) {
            RoundedRectangle(cornerRadius: 2).fill(color).frame(width: 10, height: 10)
            Text(label)
                .font(.system(size: 10))
                .foregroundStyle(Palette.onSurfaceVariant(scheme))
                .lineLimit(1)
        }
    }
}

/// Builds the received/estimated bars for a period.
/// Ported from `buildDividendBars` in DividendBarChart.kt.
func buildDividendBars(
    received: [(Date, Double)],
    projected: [(Date, Double)],
    period: DividendPeriod,
    dripRate: Double = 0,
    growthRate: Double = 0,
    yearsAhead: Int = 10,
    now: Date = Date()
) -> [DividendBar] {
    var calendar = Calendar(identifier: .gregorian)
    calendar.timeZone = .current

    func year(_ d: Date) -> Int { calendar.component(.year, from: d) }
    func month(_ d: Date) -> Int { calendar.component(.month, from: d) }

    switch period {
    case .month:
        // Six months back, this month, and six months forward. The window used
        // to be the twelve months ENDING today, which made this chart
        // structurally incapable of showing an upcoming payment — for a holding
        // with nothing logged yet every bar was zero while the payout schedule
        // directly below listed a payment due next month.
        let f = DateFormatter()
        f.locale = Locale.current
        f.dateFormat = "MMM ''yy"

        guard let start = calendar.date(byAdding: .month, value: -6, to: now) else { return [] }
        var startOfMonth = calendar.dateComponents([.year, .month], from: start)
        startOfMonth.day = 1
        guard let anchor = calendar.date(from: startOfMonth) else { return [] }

        return (0..<13).compactMap { i -> DividendBar? in
            guard let bucket = calendar.date(byAdding: .month, value: i, to: anchor) else { return nil }
            let y = year(bucket), m = month(bucket)
            func sum(_ src: [(Date, Double)]) -> Double {
                src.filter { year($0.0) == y && month($0.0) == m }.reduce(0) { $0 + $1.1 }
            }
            return DividendBar(label: f.string(from: bucket), received: sum(received), estimated: sum(projected))
        }

    case .year:
        let thisYear = year(now)
        let earliestReceived = received.map { year($0.0) }.min() ?? thisYear
        let firstYear = min(earliestReceived, thisYear)

        var projByYear: [Int: Double] = [:]
        for (date, amount) in projected {
            projByYear[year(date), default: 0] += amount
        }

        // Baseline for any year the projection doesn't reach. The run rate is
        // one YEAR of income, so take the first complete projected year, or
        // fall back to what was actually received this year — summing every
        // projected payment would read a decade of income as a single year's.
        let firstFullYear = projByYear.keys.filter { $0 > thisYear }.min()
        let runRate: Double = {
            if let firstFullYear, let v = projByYear[firstFullYear], v > 0 { return v }
            return received.filter { year($0.0) == thisYear }.reduce(0) { $0 + $1.1 }
        }()

        return (firstYear...(thisYear + yearsAhead)).map { y -> DividendBar in
            let rec = received.filter { year($0.0) == y }.reduce(0) { $0 + $1.1 }
            let proj = projByYear[y] ?? 0
            if y < thisYear {
                return DividendBar(label: String(y), received: rec)
            } else if y == thisYear {
                return DividendBar(label: String(y), received: rec, estimated: proj)
            } else {
                let n = Double(y - thisYear)
                // Prefer the projection's own figure — it already carries the
                // fund's seasonal shape and the growth assumption. Extrapolate
                // only past its horizon.
                let base = proj > 0 ? proj : runRate * pow(1 + growthRate, n)
                // With a DRIP the unit count compounds on top of that, at
                // roughly the yield: every payment buys more units, and those
                // units are paid the following period.
                let withDrip = base * pow(1 + dripRate, n)
                return DividendBar(label: String(y), estimated: base, drip: max(withDrip - base, 0))
            }
        }
    }
}

// MARK: - Donut

/// One slice of the allocation donut.
struct DonutSlice: Identifiable, Hashable {
    let label: String
    let value: Double
    let color: Color
    var id: String { label }
}

/// The allocation chart shown when the user taps "Allocation by Holdings".
/// Drawn with arcs — no third-party charting library needed.
struct DonutChart: View {
    let slices: [DonutSlice]
    var diameter: CGFloat = 200
    var holeRatio: CGFloat = 0.5

    var body: some View {
        let total = max(slices.reduce(0) { $0 + $1.value }, 0.01)

        Canvas { context, size in
            let d = min(size.width, size.height)
            let rect = CGRect(
                x: (size.width - d) / 2,
                y: (size.height - d) / 2,
                width: d,
                height: d
            )
            let center = CGPoint(x: rect.midX, y: rect.midY)
            // Degrees as plain numbers, converted at the call: `Angle` has no
            // arithmetic operators, so accumulating the sweep has to happen
            // before it becomes one.
            var startDegrees = -90.0

            for slice in slices {
                let sweepDegrees = slice.value / total * 360
                var path = Path()
                path.move(to: center)
                path.addArc(
                    center: center,
                    radius: d / 2,
                    startAngle: .degrees(startDegrees),
                    endAngle: .degrees(startDegrees + sweepDegrees),
                    clockwise: false
                )
                path.closeSubpath()
                context.fill(path, with: .color(slice.color))
                // Thin separator so adjacent slices of similar hue stay apart.
                context.stroke(path, with: .color(.white.opacity(0.6)), lineWidth: 1)
                startDegrees += sweepDegrees
            }

            // Centre hole for a donut look.
            let holeD = d * holeRatio
            let hole = Path(
                ellipseIn: CGRect(
                    x: center.x - holeD / 2,
                    y: center.y - holeD / 2,
                    width: holeD,
                    height: holeD
                )
            )
            context.fill(hole, with: .color(.white.opacity(0.92)))
        }
        .frame(width: diameter, height: diameter)
    }
}
