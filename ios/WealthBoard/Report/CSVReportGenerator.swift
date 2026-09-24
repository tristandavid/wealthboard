import Foundation

/// The same report the PDF writes, as a spreadsheet.
///
/// Built from the identical `PerformanceReport` the screen and the PDF use, so
/// the three can never disagree — a CSV recomputed from the raw ledger would
/// be a second implementation of every return calculation, and the first quiet
/// rounding difference between them would be reported as a bug in whichever
/// one the reader trusted less.
///
/// One file with several labelled blocks rather than several files. A zip of
/// sheets is tidier in the abstract and worse in practice: it cannot be opened
/// by tapping it in Mail, and the people who ask for CSV are almost always
/// about to paste one region into a spreadsheet they already keep.
///
/// Mirrors `CsvReportGenerator` on Android, block for block and column for
/// column, so the same person exporting from either phone can paste both into
/// the same sheet.
enum CSVReportGenerator {

    static func write(
        _ report: PerformanceReport,
        options: ReportOptions,
        holdingLabel: String? = nil
    ) throws -> URL {
        let text = build(report, options: options, holdingLabel: holdingLabel)

        let stamp = DateFormatter()
        stamp.locale = Locale(identifier: "en_US_POSIX")
        stamp.dateFormat = "yyyy-MM-dd"

        let scope = holdingLabel
            .map { "-" + $0.components(separatedBy: CharacterSet.alphanumerics.inverted).joined() }
            ?? ""
        let name = "WealthBoard-report\(scope)-\(stamp.string(from: report.generatedAt)).csv"
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(name)

        // .utf8 with a BOM: Excel opens a plain UTF-8 CSV as the system legacy
        // encoding and turns every accented holding name into mojibake. The
        // BOM is what makes it read the file as Unicode.
        var data = Data([0xEF, 0xBB, 0xBF])
        data.append(Data(text.utf8))
        try data.write(to: url, options: .atomic)
        return url
    }

    // MARK: - Content

    private static func build(
        _ report: PerformanceReport,
        options: ReportOptions,
        holdingLabel: String?
    ) -> String {
        var out = ""

        let date = DateFormatter()
        date.dateStyle = .medium
        date.timeStyle = .none

        out += line("WealthBoard performance report", "")
        out += line("Scope", holdingLabel ?? "All holdings")
        out += line("Period", report.period.label)
        out += line("From", date.string(from: report.periodStart))
        out += line("To", date.string(from: report.periodEnd))
        out += line("Reporting currency", report.currency)
        out += line("Generated", date.string(from: report.generatedAt))
        out += "\r\n"

        let r = report.returns
        out += line("Returns", "Value")
        out += line("Time-weighted return", percent(r.timeWeightedReturn))
        out += line("Money-weighted return (IRR)", r.moneyWeightedReturn.map(percent) ?? "n/a")
        out += line("Simple return", percent(r.simpleReturn))
        out += line("Annualized return", r.annualizedReturn.map(percent) ?? "n/a")
        out += line("Start value", number(r.startValue))
        out += line("End value", number(r.endValue))
        out += line("Net contributions", number(r.netContributions))
        out += line("Total gain", number(r.totalGain))
        out += line("Dividend income", number(r.dividendIncome))
        out += line("Realized gain", number(r.realizedGain))
        out += line("Unrealized gain", number(r.unrealizedGain))
        out += "\r\n"

        if options.includeRiskMetrics {
            let k = report.risk
            out += line("Risk", "Value")
            out += line("Annualized volatility", k.annualizedVolatility.map(percent) ?? "n/a")
            out += line("Sharpe ratio", k.sharpeRatio.map(number) ?? "n/a")
            out += line("Max drawdown", k.maxDrawdown.map(percent) ?? "n/a")
            out += line("Drawdown recovered", k.drawdownRecovered ? "Yes" : "No")
            out += line("Positive months", "\(k.positiveMonths) of \(k.totalMonths)")
            out += line("Risk-free rate used", percent(k.riskFreeRateUsed))
            out += "\r\n"
        }

        if let b = report.benchmark {
            out += line("Benchmark", b.benchmark.displayName)
            out += line("Benchmark return", percent(b.benchmarkReturn))
            out += line("Portfolio return", percent(b.portfolioReturn))
            out += line("Excess return", percent(b.excessReturn))
            out += line("Beta", b.beta.map(number) ?? "n/a")
            out += line("Alpha", b.alpha.map(percent) ?? "n/a")
            out += line("Correlation", b.correlation.map(number) ?? "n/a")
            out += "\r\n"
        }

        if options.includeHoldingsTable && !report.holdings.isEmpty {
            out += line(
                "Holding", "Ticker", "Type", "Account", "Units", "Average cost",
                "Current price", "Market value", "Cost basis", "Unrealized gain",
                "Unrealized gain %", "Realized gain", "Dividend income", "Total gain",
                "Weight %", "Currency"
            )
            for h in report.holdings {
                out += line(
                    h.name,
                    h.ticker ?? "",
                    h.type.label,
                    h.accountName,
                    number(h.units),
                    h.averageCost.map(number) ?? "",
                    h.currentPrice.map(number) ?? "",
                    number(h.marketValue),
                    number(h.costBasis),
                    number(h.unrealizedGain),
                    h.unrealizedGainPercent.map(percent) ?? "",
                    number(h.realizedGain),
                    number(h.dividendIncome),
                    number(h.totalGain),
                    percent(h.weight),
                    h.currency
                )
            }
            out += "\r\n"
        }

        if options.includeRealizedGains && !report.realizedGains.isEmpty {
            out += line(
                "Sold", "Holding", "Ticker", "Shares", "Sale price",
                "Average cost at sale", "Proceeds", "Cost of units sold",
                "Realized gain", "Realized gain %", "Currency"
            )
            for g in report.realizedGains {
                out += line(
                    date.string(from: g.soldAt),
                    g.holdingName,
                    g.ticker ?? "",
                    number(g.shares),
                    number(g.salePrice),
                    number(g.averageCostAtSale),
                    number(g.proceeds),
                    number(g.costOfUnitsSold),
                    number(g.realizedGain),
                    g.realizedGainPercent.map(percent) ?? "",
                    g.currency
                )
            }
            out += "\r\n"
        }

        if options.includeIncome {
            let i = report.income
            out += line("Dividend income", "Value")
            out += line("Total in period", number(i.totalInPeriod))
            out += line("Trailing 12 months", number(i.trailing12Months))
            out += line("Yield on cost", i.yieldOnCost.map(percent) ?? "n/a")
            out += line("Current yield", i.currentYield.map(percent) ?? "n/a")
            out += line("Reinvested", number(i.reinvestedAmount))
            out += line("Taken as cash", number(i.cashAmount))
            out += line("Payments", "\(i.paymentCount)")
            out += "\r\n"

            if !i.byMonth.isEmpty {
                out += line("Income month", "Amount")
                for entry in i.byMonth { out += line(entry.label, number(entry.amount)) }
                out += "\r\n"
            }
            if !i.byHolding.isEmpty {
                out += line("Income by holding", "Amount")
                for entry in i.byHolding { out += line(entry.label, number(entry.amount)) }
                out += "\r\n"
            }
        }

        if options.includeAllocation {
            let a = report.allocation
            if !a.byType.isEmpty {
                out += line("Allocation by type", "Value", "Percent")
                for s in a.byType { out += line(s.label, number(s.value), percent(s.percent)) }
                out += "\r\n"
            }
            if !a.byAccount.isEmpty {
                out += line("Allocation by account", "Value", "Percent")
                for s in a.byAccount { out += line(s.label, number(s.value), percent(s.percent)) }
                out += "\r\n"
            }
        }

        if options.includeTransactionActivity {
            let act = report.activity
            out += line("Activity", "Value")
            out += line("Buys", "\(act.buyCount)")
            out += line("Sells", "\(act.sellCount)")
            out += line("Dividend reinvestments", "\(act.dripCount)")
            out += line("Total invested", number(act.totalInvested))
            out += line("Total withdrawn", number(act.totalWithdrawn))
            out += line("Total reinvested", number(act.totalReinvested))
            out += "\r\n"
        }

        if !report.warnings.isEmpty {
            out += line("Data notes")
            for w in report.warnings { out += line(w) }
            out += "\r\n"
        }

        out += line("Informational only, not investment advice. Figures are built from data you entered and may differ from your official statements.")
        return out
    }

    // MARK: - Writing

    /// CRLF, not LF: Excel on Windows treats a lone LF inside a quoted field
    /// as part of the value and the file opens with rows merged.
    private static func line(_ cells: String...) -> String {
        cells.map(escape).joined(separator: ",") + "\r\n"
    }

    /// RFC 4180 quoting. A holding named `Vanguard S&P 500, Acc` is the case
    /// that matters — unquoted, it silently becomes two columns and shifts
    /// every figure in the row one place left.
    private static func escape(_ value: String) -> String {
        guard value.contains(where: { $0 == "," || $0 == "\"" || $0 == "\n" || $0 == "\r" })
        else { return value }
        return "\"" + value.replacingOccurrences(of: "\"", with: "\"\"") + "\""
    }

    // MARK: - Formatting
    //
    // `String(format:)` with no locale formats POSIX-style, decimal point and
    // no grouping, whatever the phone's region is — and deliberately so: a
    // spreadsheet
    // reading "1.234,56" from a French phone as text rather than as a number
    // is the single most common way an export like this arrives useless. The
    // labels stay localised because they are read, not calculated with.

    private static func number(_ value: Double) -> String {
        guard value.isFinite else { return "" }
        return String(format: "%.2f", value)
    }

    /// As a percentage NUMBER (12.34), not a string with a % sign, so it sums.
    private static func percent(_ fraction: Double) -> String {
        guard fraction.isFinite else { return "" }
        return String(format: "%.2f", fraction * 100.0)
    }
}
