import UIKit
import PDFKit

/// Renders a `PerformanceReport` as a multi-page PDF.
/// Ported from `report/PdfReportGenerator.kt`.
///
/// Drawn with Core Graphics rather than built from HTML: the report is a
/// fixed-layout document with tables whose columns have to line up across page
/// breaks, and a web view's pagination gives no control over where those breaks
/// land.
enum PDFReportGenerator {

    // US Letter at 72 dpi, the size every desktop PDF reader assumes.
    private static let pageSize = CGSize(width: 612, height: 792)
    private static let margin: CGFloat = 40
    private static var contentWidth: CGFloat { pageSize.width - margin * 2 }

    private enum PDFColors {
        static let navy = UIColor(red: 0.059, green: 0.165, blue: 0.263, alpha: 1)
        static let gold = UIColor(red: 0.788, green: 0.635, blue: 0.153, alpha: 1)
        static let text = UIColor(white: 0.1, alpha: 1)
        static let muted = UIColor(white: 0.45, alpha: 1)
        static let rule = UIColor(white: 0.85, alpha: 1)
        static let gain = UIColor(red: 0.118, green: 0.557, blue: 0.353, alpha: 1)
        static let loss = UIColor(red: 0.753, green: 0.224, blue: 0.169, alpha: 1)
    }

    private enum Fonts {
        static let title = UIFont.systemFont(ofSize: 22, weight: .bold)
        static let heading = UIFont.systemFont(ofSize: 13, weight: .bold)
        static let body = UIFont.systemFont(ofSize: 10)
        static let bodyBold = UIFont.systemFont(ofSize: 10, weight: .semibold)
        static let small = UIFont.systemFont(ofSize: 8)
        static let metric = UIFont.systemFont(ofSize: 17, weight: .bold)
    }

    /// Writes the report to a temporary file and returns its URL, ready to hand
    /// to a share sheet.
    static func write(_ report: PerformanceReport, options: ReportOptions) throws -> URL {
        let renderer = UIGraphicsPDFRenderer(
            bounds: CGRect(origin: .zero, size: pageSize),
            format: metadataFormat(report)
        )

        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent(filename(for: report))

        try renderer.writePDF(to: url) { context in
            var cursor = Cursor(context: context)
            cursor.beginPage()

            drawHeader(report, cursor: &cursor)
            drawSummary(report, cursor: &cursor)
            drawReturns(report, cursor: &cursor)
            drawEquityCurve(report, cursor: &cursor)

            if let benchmark = report.benchmark {
                drawBenchmark(benchmark, report: report, cursor: &cursor)
            }
            if options.includeRiskMetrics {
                drawRisk(report, cursor: &cursor)
            }
            if options.includeHoldingsTable, !report.holdings.isEmpty {
                drawHoldings(report, cursor: &cursor)
            }
            if options.includeRealizedGains, !report.realizedGains.isEmpty {
                drawRealizedGains(report, cursor: &cursor)
            }
            if options.includeIncome, report.income.paymentCount > 0 {
                drawIncome(report, cursor: &cursor)
            }
            if options.includeAllocation, !report.allocation.byHolding.isEmpty {
                drawAllocation(report, cursor: &cursor)
            }
            if options.includeTransactionActivity {
                drawActivity(report, cursor: &cursor)
            }

            drawFooter(report, cursor: &cursor)
        }

        return url
    }

    private static func metadataFormat(_ report: PerformanceReport) -> UIGraphicsPDFRendererFormat {
        let format = UIGraphicsPDFRendererFormat()
        format.documentInfo = [
            kCGPDFContextTitle as String: "WealthBoard performance report",
            kCGPDFContextCreator as String: "WealthBoard",
            kCGPDFContextSubject as String: report.period.label
        ]
        return format
    }

    private static func filename(for report: PerformanceReport) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd"
        return "WealthBoard-\(f.string(from: report.generatedAt)).pdf"
    }

    // MARK: - Layout cursor

    /// Tracks the write position and starts a new page when a block won't fit.
    ///
    /// A struct passed `inout` rather than a class: every draw function needs
    /// to move it, and making that explicit at each call site is what keeps
    /// two sections from silently drawing over each other.
    private struct Cursor {
        let context: UIGraphicsPDFRendererContext
        var y: CGFloat = PDFReportGenerator.margin
        var page = 0

        mutating func beginPage() {
            context.beginPage()
            page += 1
            y = PDFReportGenerator.margin
        }

        /// Ensures `height` points are available, breaking the page if not.
        mutating func require(_ height: CGFloat) {
            // Reserve a strip at the foot of the page so a block never lands
            // flush against the bottom edge.
            let limit = PDFReportGenerator.pageSize.height - PDFReportGenerator.margin - 24
            if y + height > limit {
                beginPage()
            }
        }

        mutating func advance(_ amount: CGFloat) { y += amount }
    }

    // MARK: - Primitives

    private static func draw(
        _ text: String,
        at point: CGPoint,
        font: UIFont,
        color: UIColor = PDFColors.text,
        alignment: NSTextAlignment = .left,
        width: CGFloat? = nil
    ) {
        let paragraph = NSMutableParagraphStyle()
        paragraph.alignment = alignment
        paragraph.lineBreakMode = .byTruncatingTail

        let attributes: [NSAttributedString.Key: Any] = [
            .font: font,
            .foregroundColor: color,
            .paragraphStyle: paragraph
        ]

        if let width {
            (text as NSString).draw(
                with: CGRect(x: point.x, y: point.y, width: width, height: font.lineHeight * 3),
                options: [.usesLineFragmentOrigin],
                attributes: attributes,
                context: nil
            )
        } else {
            (text as NSString).draw(at: point, withAttributes: attributes)
        }
    }

    /// Returns the height the wrapped text occupied, so the caller can advance.
    @discardableResult
    private static func drawWrapped(
        _ text: String,
        x: CGFloat,
        y: CGFloat,
        width: CGFloat,
        font: UIFont,
        color: UIColor = PDFColors.text
    ) -> CGFloat {
        let paragraph = NSMutableParagraphStyle()
        paragraph.lineBreakMode = .byWordWrapping
        let attributes: [NSAttributedString.Key: Any] = [
            .font: font,
            .foregroundColor: color,
            .paragraphStyle: paragraph
        ]
        let bounding = (text as NSString).boundingRect(
            with: CGSize(width: width, height: .greatestFiniteMagnitude),
            options: [.usesLineFragmentOrigin, .usesFontLeading],
            attributes: attributes,
            context: nil
        )
        (text as NSString).draw(
            with: CGRect(x: x, y: y, width: width, height: ceil(bounding.height)),
            options: [.usesLineFragmentOrigin, .usesFontLeading],
            attributes: attributes,
            context: nil
        )
        return ceil(bounding.height)
    }

    private static func rule(at y: CGFloat, color: UIColor = PDFColors.rule) {
        let path = UIBezierPath()
        path.move(to: CGPoint(x: margin, y: y))
        path.addLine(to: CGPoint(x: pageSize.width - margin, y: y))
        color.setStroke()
        path.lineWidth = 0.5
        path.stroke()
    }

    private static func sectionHeading(_ title: String, cursor: inout Cursor) {
        cursor.require(40)
        draw(title.uppercased(), at: CGPoint(x: margin, y: cursor.y), font: Fonts.heading, color: PDFColors.navy)
        cursor.advance(16)
        rule(at: cursor.y)
        cursor.advance(10)
    }

    private static func money(_ value: Double, _ currency: String) -> String {
        Money.format(value, currency)
    }

    private static func percent(_ value: Double?) -> String {
        guard let value else { return "—" }
        return Money.signedPercent(value * 100)
    }

    private static func tint(_ value: Double) -> UIColor {
        value >= 0 ? PDFColors.gain : PDFColors.loss
    }

    // MARK: - Sections

    private static func drawHeader(_ report: PerformanceReport, cursor: inout Cursor) {
        // A gold rule under a navy wordmark, matching the app's own chrome, so
        // the document is recognisably from the same product.
        draw("WealthBoard", at: CGPoint(x: margin, y: cursor.y), font: Fonts.title, color: PDFColors.navy)
        cursor.advance(28)

        let dates = DateFormatter()
        dates.dateStyle = .medium
        draw(
            "Performance report · \(report.period.label) · "
            + "\(dates.string(from: report.periodStart)) – \(dates.string(from: report.periodEnd))",
            at: CGPoint(x: margin, y: cursor.y),
            font: Fonts.body,
            color: PDFColors.muted
        )
        cursor.advance(14)
        draw(
            "All figures in \(report.currency). Generated \(dates.string(from: report.generatedAt)).",
            at: CGPoint(x: margin, y: cursor.y),
            font: Fonts.small,
            color: PDFColors.muted
        )
        cursor.advance(16)

        PDFColors.gold.setFill()
        UIBezierPath(rect: CGRect(x: margin, y: cursor.y, width: contentWidth, height: 2)).fill()
        cursor.advance(18)

        for warning in report.warnings {
            let height = drawWrapped(
                "• " + warning,
                x: margin, y: cursor.y, width: contentWidth,
                font: Fonts.small, color: PDFColors.muted
            )
            cursor.advance(height + 4)
        }
        if !report.warnings.isEmpty { cursor.advance(6) }
    }

    private static func drawSummary(_ report: PerformanceReport, cursor: inout Cursor) {
        sectionHeading("Summary", cursor: &cursor)
        cursor.require(70)

        let tiles: [(String, String, UIColor)] = [
            ("Ending value", money(report.returns.endValue, report.currency), PDFColors.text),
            ("Total gain", money(report.returns.totalGain, report.currency), tint(report.returns.totalGain)),
            ("Time-weighted", percent(report.returns.timeWeightedReturn), tint(report.returns.timeWeightedReturn)),
            ("Money-weighted", percent(report.returns.moneyWeightedReturn), tint(report.returns.moneyWeightedReturn ?? 0))
        ]

        let tileWidth = contentWidth / CGFloat(tiles.count)
        for (index, tile) in tiles.enumerated() {
            let x = margin + CGFloat(index) * tileWidth
            draw(tile.0, at: CGPoint(x: x, y: cursor.y), font: Fonts.small, color: PDFColors.muted)
            draw(tile.1, at: CGPoint(x: x, y: cursor.y + 12), font: Fonts.metric, color: tile.2)
        }
        cursor.advance(48)
    }

    private static func drawReturns(_ report: PerformanceReport, cursor: inout Cursor) {
        sectionHeading("Returns", cursor: &cursor)
        let r = report.returns

        let rows: [(String, String)] = [
            ("Starting value", money(r.startValue, report.currency)),
            ("Net contributions", money(r.netContributions, report.currency)),
            ("Ending value", money(r.endValue, report.currency)),
            ("Total gain", money(r.totalGain, report.currency)),
            ("Unrealized gain", money(r.unrealizedGain, report.currency)),
            ("Realized gain", money(r.realizedGain, report.currency)),
            ("Dividend income", money(r.dividendIncome, report.currency)),
            ("Time-weighted return", percent(r.timeWeightedReturn)),
            ("Money-weighted return (XIRR)", percent(r.moneyWeightedReturn)),
            ("Annualized", percent(r.annualizedReturn)),
            ("Simple return on average capital", percent(r.simpleReturn))
        ]
        drawKeyValues(rows, cursor: &cursor)

        let height = drawWrapped(
            "Time-weighted return strips out the timing of your deposits, so it is what to "
            + "compare against an index. Money-weighted return is what you personally earned, "
            + "given when you actually put money in.",
            x: margin, y: cursor.y, width: contentWidth,
            font: Fonts.small, color: PDFColors.muted
        )
        cursor.advance(height + 12)
    }

    private static func drawKeyValues(_ rows: [(String, String)], cursor: inout Cursor) {
        for row in rows {
            cursor.require(16)
            draw(row.0, at: CGPoint(x: margin, y: cursor.y), font: Fonts.body, color: PDFColors.muted)
            draw(
                row.1,
                at: CGPoint(x: margin, y: cursor.y),
                font: Fonts.bodyBold,
                alignment: .right,
                width: contentWidth
            )
            cursor.advance(15)
        }
        cursor.advance(8)
    }

    /// The equity curve, drawn as a filled line over the same window the
    /// figures above describe.
    private static func drawEquityCurve(_ report: PerformanceReport, cursor: inout Cursor) {
        let points = report.equityCurve.filter { $0.value > 0 }
        guard points.count >= 2 else { return }

        sectionHeading("Portfolio value", cursor: &cursor)
        let chartHeight: CGFloat = 140
        cursor.require(chartHeight + 20)

        let values = points.map(\.value)
        let low = values.min() ?? 0
        let high = values.max() ?? 1
        let span = max(high - low, 0.0001)
        let top = cursor.y
        let bottom = top + chartHeight

        func position(_ index: Int) -> CGPoint {
            let x = margin + contentWidth * CGFloat(index) / CGFloat(points.count - 1)
            let y = bottom - chartHeight * CGFloat((points[index].value - low) / span)
            return CGPoint(x: x, y: y)
        }

        // Fill
        let fill = UIBezierPath()
        fill.move(to: CGPoint(x: margin, y: bottom))
        for i in points.indices { fill.addLine(to: position(i)) }
        fill.addLine(to: CGPoint(x: margin + contentWidth, y: bottom))
        fill.close()
        PDFColors.navy.withAlphaComponent(0.10).setFill()
        fill.fill()

        // Line
        let line = UIBezierPath()
        line.move(to: position(0))
        for i in points.indices.dropFirst() { line.addLine(to: position(i)) }
        PDFColors.navy.setStroke()
        line.lineWidth = 1.2
        line.stroke()

        // Axis labels
        draw(money(high, report.currency), at: CGPoint(x: margin, y: top - 2), font: Fonts.small, color: PDFColors.muted)
        draw(
            money(low, report.currency),
            at: CGPoint(x: margin, y: bottom - 8),
            font: Fonts.small,
            color: PDFColors.muted,
            alignment: .right,
            width: contentWidth
        )
        cursor.advance(chartHeight + 16)
    }

    private static func drawBenchmark(
        _ comparison: BenchmarkComparison,
        report: PerformanceReport,
        cursor: inout Cursor
    ) {
        sectionHeading("Benchmark: \(comparison.benchmark.displayName)", cursor: &cursor)
        drawKeyValues([
            ("Portfolio return", percent(comparison.portfolioReturn)),
            ("Benchmark return", percent(comparison.benchmarkReturn)),
            ("Excess return", percent(comparison.excessReturn)),
            ("Beta", comparison.beta.map { String(format: "%.2f", $0) } ?? "—"),
            ("Alpha (annualized)", percent(comparison.alpha)),
            ("Correlation", comparison.correlation.map { String(format: "%.2f", $0) } ?? "—")
        ], cursor: &cursor)
    }

    private static func drawRisk(_ report: PerformanceReport, cursor: inout Cursor) {
        sectionHeading("Risk", cursor: &cursor)
        let risk = report.risk

        let months = DateFormatter()
        months.dateFormat = "MMM yyyy"

        drawKeyValues([
            ("Annualized volatility", percent(risk.annualizedVolatility)),
            ("Sharpe ratio", risk.sharpeRatio.map { String(format: "%.2f", $0) } ?? "—"),
            ("Maximum drawdown", percent(risk.maxDrawdown)),
            ("Recovered from drawdown", risk.maxDrawdown == nil ? "—" : (risk.drawdownRecovered ? "Yes" : "Not yet")),
            ("Best month", risk.bestMonth.map { "\(months.string(from: $0.date))  \(percent($0.value))" } ?? "—"),
            ("Worst month", risk.worstMonth.map { "\(months.string(from: $0.date))  \(percent($0.value))" } ?? "—"),
            ("Positive months", risk.totalMonths > 0 ? "\(risk.positiveMonths) of \(risk.totalMonths)" : "—"),
            ("Risk-free rate assumed", Money.percent(risk.riskFreeRateUsed * 100))
        ], cursor: &cursor)
    }

    private static func drawHoldings(_ report: PerformanceReport, cursor: inout Cursor) {
        sectionHeading("Holdings", cursor: &cursor)

        let columns: [CGFloat] = [0.26, 0.11, 0.13, 0.16, 0.17, 0.17]
        let headers = ["Holding", "Units", "Avg cost", "Value", "Unrealized", "Total gain"]
        drawTableHeader(headers, widths: columns, cursor: &cursor)

        for row in report.holdings {
            cursor.require(16)
            let cells = [
                row.ticker ?? row.name,
                Money.units(row.units),
                row.averageCost.map { money($0, report.currency) } ?? "—",
                money(row.marketValue, report.currency),
                money(row.unrealizedGain, report.currency),
                money(row.totalGain, report.currency)
            ]
            drawTableRow(
                cells,
                widths: columns,
                cursor: &cursor,
                tints: [nil, nil, nil, nil, tint(row.unrealizedGain), tint(row.totalGain)]
            )
        }
        cursor.advance(10)

        if !report.untickeredHoldingNames.isEmpty {
            let height = drawWrapped(
                "Held flat at their entered price (no market data): "
                + report.untickeredHoldingNames.joined(separator: ", ") + ".",
                x: margin, y: cursor.y, width: contentWidth,
                font: Fonts.small, color: PDFColors.muted
            )
            cursor.advance(height + 10)
        }
    }

    private static func drawRealizedGains(_ report: PerformanceReport, cursor: inout Cursor) {
        sectionHeading("Realized gains", cursor: &cursor)

        let columns: [CGFloat] = [0.24, 0.16, 0.12, 0.16, 0.16, 0.16]
        drawTableHeader(
            ["Holding", "Sold", "Units", "Price", "Cost", "Gain"],
            widths: columns,
            cursor: &cursor
        )

        let dates = DateFormatter()
        dates.dateStyle = .short

        for row in report.realizedGains {
            cursor.require(16)
            drawTableRow([
                row.ticker ?? row.holdingName,
                dates.string(from: row.soldAt),
                Money.units(row.shares),
                money(row.salePrice, row.currency),
                money(row.costOfUnitsSold, row.currency),
                money(row.realizedGain, row.currency)
            ], widths: columns, cursor: &cursor, tints: [nil, nil, nil, nil, nil, tint(row.realizedGain)])
        }

        let total = report.realizedGains.reduce(0) { $0 + $1.realizedGain }
        cursor.advance(4)
        rule(at: cursor.y)
        cursor.advance(6)
        draw("Total realized", at: CGPoint(x: margin, y: cursor.y), font: Fonts.bodyBold)
        draw(
            money(total, report.currency),
            at: CGPoint(x: margin, y: cursor.y),
            font: Fonts.bodyBold,
            color: tint(total),
            alignment: .right,
            width: contentWidth
        )
        cursor.advance(20)
    }

    private static func drawIncome(_ report: PerformanceReport, cursor: inout Cursor) {
        sectionHeading("Dividend income", cursor: &cursor)
        let income = report.income

        drawKeyValues([
            ("Income in period", money(income.totalInPeriod, report.currency)),
            ("Trailing 12 months", money(income.trailing12Months, report.currency)),
            ("Payments", "\(income.paymentCount)"),
            ("Reinvested", money(income.reinvestedAmount, report.currency)),
            ("Taken as cash", money(income.cashAmount, report.currency)),
            ("Yield on cost", income.yieldOnCost.map { Money.percent($0 * 100) } ?? "—"),
            ("Current yield", income.currentYield.map { Money.percent($0 * 100) } ?? "—")
        ], cursor: &cursor)

        if !income.byHolding.isEmpty {
            draw("By holding", at: CGPoint(x: margin, y: cursor.y), font: Fonts.bodyBold)
            cursor.advance(16)
            for entry in income.byHolding {
                cursor.require(14)
                draw(entry.label, at: CGPoint(x: margin, y: cursor.y), font: Fonts.body, color: PDFColors.muted)
                draw(
                    money(entry.amount, report.currency),
                    at: CGPoint(x: margin, y: cursor.y),
                    font: Fonts.body,
                    alignment: .right,
                    width: contentWidth
                )
                cursor.advance(13)
            }
            cursor.advance(10)
        }
    }

    private static func drawAllocation(_ report: PerformanceReport, cursor: inout Cursor) {
        sectionHeading("Allocation", cursor: &cursor)

        func slices(_ title: String, _ list: [AllocationSlice]) {
            guard !list.isEmpty else { return }
            cursor.require(30)
            draw(title, at: CGPoint(x: margin, y: cursor.y), font: Fonts.bodyBold)
            cursor.advance(16)
            for slice in list {
                cursor.require(16)
                draw(slice.label, at: CGPoint(x: margin, y: cursor.y), font: Fonts.body, color: PDFColors.muted)

                // A bar as well as a number: the point of an allocation table
                // is the shape of the portfolio, and a column of percentages
                // makes that harder to see than it needs to be.
                let barWidth = contentWidth * 0.34
                let barX = margin + contentWidth - barWidth - 62
                PDFColors.rule.setFill()
                UIBezierPath(
                    roundedRect: CGRect(x: barX, y: cursor.y + 2, width: barWidth, height: 6),
                    cornerRadius: 3
                ).fill()
                PDFColors.navy.setFill()
                UIBezierPath(
                    roundedRect: CGRect(
                        x: barX,
                        y: cursor.y + 2,
                        width: max(barWidth * CGFloat(slice.percent), 1),
                        height: 6
                    ),
                    cornerRadius: 3
                ).fill()

                draw(
                    Money.percent(slice.percent * 100, decimals: 1),
                    at: CGPoint(x: margin, y: cursor.y),
                    font: Fonts.bodyBold,
                    alignment: .right,
                    width: contentWidth
                )
                cursor.advance(15)
            }
            cursor.advance(8)
        }

        slices("By position", report.allocation.byHolding)
        slices("By type", report.allocation.byType)
        slices("By account", report.allocation.byAccount)

        if let largest = report.allocation.largestPositionName {
            let height = drawWrapped(
                "Largest position: \(largest) at \(Money.percent(report.allocation.largestPositionWeight * 100, decimals: 1)). "
                + "Concentration index \(String(format: "%.2f", report.allocation.concentrationIndex)) "
                + "(1.00 is everything in one holding).",
                x: margin, y: cursor.y, width: contentWidth,
                font: Fonts.small, color: PDFColors.muted
            )
            cursor.advance(height + 10)
        }
    }

    private static func drawActivity(_ report: PerformanceReport, cursor: inout Cursor) {
        sectionHeading("Activity", cursor: &cursor)
        let activity = report.activity
        drawKeyValues([
            ("Buys", "\(activity.buyCount)"),
            ("Sells", "\(activity.sellCount)"),
            ("Reinvestments", "\(activity.dripCount)"),
            ("Total invested", money(activity.totalInvested, report.currency)),
            ("Total withdrawn", money(activity.totalWithdrawn, report.currency)),
            ("Total reinvested", money(activity.totalReinvested, report.currency))
        ], cursor: &cursor)
    }

    private static func drawFooter(_ report: PerformanceReport, cursor: inout Cursor) {
        cursor.require(40)
        rule(at: cursor.y)
        cursor.advance(8)
        drawWrapped(
            "Built from the transactions you recorded in WealthBoard. Figures are estimates for "
            + "your own review, not a broker statement and not tax advice.",
            x: margin, y: cursor.y, width: contentWidth,
            font: Fonts.small, color: PDFColors.muted
        )
    }

    // MARK: - Tables

    private static func drawTableHeader(_ headers: [String], widths: [CGFloat], cursor: inout Cursor) {
        cursor.require(24)
        var x = margin
        for (index, header) in headers.enumerated() {
            let width = contentWidth * widths[index]
            draw(
                header,
                at: CGPoint(x: x, y: cursor.y),
                font: Fonts.small,
                color: PDFColors.muted,
                alignment: index == 0 ? .left : .right,
                width: width
            )
            x += width
        }
        cursor.advance(13)
        rule(at: cursor.y)
        cursor.advance(6)
    }

    private static func drawTableRow(
        _ cells: [String],
        widths: [CGFloat],
        cursor: inout Cursor,
        tints: [UIColor?] = []
    ) {
        var x = margin
        for (index, cell) in cells.enumerated() {
            let width = contentWidth * widths[index]
            draw(
                cell,
                at: CGPoint(x: x, y: cursor.y),
                font: index == 0 ? Fonts.bodyBold : Fonts.body,
                color: (index < tints.count ? tints[index] : nil) ?? PDFColors.text,
                alignment: index == 0 ? .left : .right,
                width: width
            )
            x += width
        }
        cursor.advance(14)
    }
}
