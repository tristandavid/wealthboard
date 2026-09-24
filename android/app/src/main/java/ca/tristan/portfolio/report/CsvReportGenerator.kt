package ca.tristan.portfolio.report

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The same report the PDF writes, as a spreadsheet.
 *
 * Built from the identical [PerformanceReport] the screen and the PDF use, so
 * the three can never disagree — a CSV recomputed from the raw ledger would be
 * a second implementation of every return calculation, and the first quiet
 * rounding difference between them would be reported as a bug in whichever one
 * the reader trusted less.
 *
 * One file with several labelled blocks rather than several files. A zip of
 * sheets is tidier in the abstract and worse in practice: it cannot be opened
 * by tapping it in a mail app, and the people who ask for CSV are almost
 * always about to paste one region into a spreadsheet they already keep.
 */
object CsvReportGenerator {

    /**
     * Excel's separator sniffing gives up on a file whose first line is not a
     * header row, and opens the whole thing in column A. Every block therefore
     * carries its own header line, and the title block below is two columns
     * wide rather than one.
     */
    private const val SEPARATOR = ","

    fun generate(
        context: Context,
        report: PerformanceReport,
        options: ReportOptions,
        holdingLabel: String? = null
    ): File {
        val text = buildString { writeReport(report, options, holdingLabel) }
        val stamp = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(report.generatedAtMillis))
        val scope = holdingLabel?.let { "-" + it.replace(Regex("[^A-Za-z0-9]"), "") } ?: ""
        val file = File(context.cacheDir, "WealthBoard-report$scope-$stamp.csv")
        file.writeText(text)
        return file
    }

    private fun StringBuilder.writeReport(
        report: PerformanceReport,
        options: ReportOptions,
        holdingLabel: String?
    ) {
        val date = SimpleDateFormat("d MMM yyyy", Locale.getDefault())

        row("WealthBoard performance report", "")
        row("Scope", holdingLabel ?: "All holdings")
        row("Period", report.period.label)
        row("From", date.format(Date(report.periodStartMillis)))
        row("To", date.format(Date(report.periodEndMillis)))
        row("Reporting currency", report.currency)
        row("Generated", date.format(Date(report.generatedAtMillis)))
        blank()

        val r = report.returns
        row("Returns", "Value")
        row("Time-weighted return", percent(r.timeWeightedReturn))
        row("Money-weighted return (IRR)", r.moneyWeightedReturn?.let { percent(it) } ?: "n/a")
        row("Simple return", percent(r.simpleReturn))
        row("Annualized return", r.annualizedReturn?.let { percent(it) } ?: "n/a")
        row("Start value", number(r.startValue))
        row("End value", number(r.endValue))
        row("Net contributions", number(r.netContributions))
        row("Total gain", number(r.totalGain))
        row("Dividend income", number(r.dividendIncome))
        row("Realized gain", number(r.realizedGain))
        row("Unrealized gain", number(r.unrealizedGain))
        blank()

        if (options.includeRiskMetrics) {
            val k = report.risk
            row("Risk", "Value")
            row("Annualized volatility", k.annualizedVolatility?.let { percent(it) } ?: "n/a")
            row("Sharpe ratio", k.sharpeRatio?.let { number(it) } ?: "n/a")
            row("Max drawdown", k.maxDrawdown?.let { percent(it) } ?: "n/a")
            row("Drawdown recovered", if (k.drawdownRecovered) "Yes" else "No")
            row("Positive months", "${k.positiveMonths} of ${k.totalMonths}")
            row("Risk-free rate used", percent(k.riskFreeRateUsed))
            blank()
        }

        report.benchmark?.let { b ->
            row("Benchmark", b.benchmark.displayName)
            row("Benchmark return", percent(b.benchmarkReturn))
            row("Portfolio return", percent(b.portfolioReturn))
            row("Excess return", percent(b.excessReturn))
            row("Beta", b.beta?.let { number(it) } ?: "n/a")
            row("Alpha", b.alpha?.let { percent(it) } ?: "n/a")
            row("Correlation", b.correlation?.let { number(it) } ?: "n/a")
            blank()
        }

        if (options.includeHoldingsTable && report.holdings.isNotEmpty()) {
            line(
                "Holding", "Ticker", "Type", "Account", "Units", "Average cost",
                "Current price", "Market value", "Cost basis", "Unrealized gain",
                "Unrealized gain %", "Realized gain", "Dividend income", "Total gain",
                "Weight %", "Currency"
            )
            for (h in report.holdings) {
                line(
                    h.name,
                    h.ticker ?: "",
                    h.type.label(),
                    h.accountName,
                    number(h.units),
                    h.averageCost?.let { number(it) } ?: "",
                    h.currentPrice?.let { number(it) } ?: "",
                    number(h.marketValue),
                    number(h.costBasis),
                    number(h.unrealizedGain),
                    h.unrealizedGainPercent?.let { percent(it) } ?: "",
                    number(h.realizedGain),
                    number(h.dividendIncome),
                    number(h.totalGain),
                    percent(h.weight),
                    h.currency
                )
            }
            blank()
        }

        if (options.includeRealizedGains && report.realizedGains.isNotEmpty()) {
            line(
                "Sold", "Holding", "Ticker", "Shares", "Sale price",
                "Average cost at sale", "Proceeds", "Cost of units sold",
                "Realized gain", "Realized gain %", "Currency"
            )
            for (g in report.realizedGains) {
                line(
                    date.format(Date(g.soldAtMillis)),
                    g.holdingName,
                    g.ticker ?: "",
                    number(g.shares),
                    number(g.salePrice),
                    number(g.averageCostAtSale),
                    number(g.proceeds),
                    number(g.costOfUnitsSold),
                    number(g.realizedGain),
                    g.realizedGainPercent?.let { percent(it) } ?: "",
                    g.currency
                )
            }
            blank()
        }

        if (options.includeIncome) {
            val i = report.income
            row("Dividend income", "Value")
            row("Total in period", number(i.totalInPeriod))
            row("Trailing 12 months", number(i.trailing12Months))
            row("Yield on cost", i.yieldOnCost?.let { percent(it) } ?: "n/a")
            row("Current yield", i.currentYield?.let { percent(it) } ?: "n/a")
            row("Reinvested", number(i.reinvestedAmount))
            row("Taken as cash", number(i.cashAmount))
            row("Payments", i.paymentCount.toString())
            blank()

            if (i.byMonth.isNotEmpty()) {
                val month = SimpleDateFormat("MMM yyyy", Locale.getDefault())
                line("Income month", "Amount")
                for ((at, amount) in i.byMonth) line(month.format(Date(at)), number(amount))
                blank()
            }

            if (i.byHolding.isNotEmpty()) {
                line("Income by holding", "Amount")
                for ((name, amount) in i.byHolding) line(name, number(amount))
                blank()
            }
        }

        val a = report.allocation
        if (a.byType.isNotEmpty()) {
            line("Allocation by type", "Value", "Percent")
            for (s in a.byType) line(s.label, number(s.value), percent(s.percent))
            blank()
        }
        if (a.byAccount.isNotEmpty()) {
            line("Allocation by account", "Value", "Percent")
            for (s in a.byAccount) line(s.label, number(s.value), percent(s.percent))
            blank()
        }

        val act = report.activity
        row("Activity", "Value")
        row("Buys", act.buyCount.toString())
        row("Sells", act.sellCount.toString())
        row("Dividend reinvestments", act.dripCount.toString())
        row("Total invested", number(act.totalInvested))
        row("Total withdrawn", number(act.totalWithdrawn))
        row("Total reinvested", number(act.totalReinvested))
        blank()

        if (report.warnings.isNotEmpty()) {
            line("Data notes")
            for (w in report.warnings) line(w)
            blank()
        }

        line("Informational only, not investment advice. Figures are built from data you entered and may differ from your official statements.")
    }

    // ── Writing ───────────────────────────────────────────────────────────

    private fun StringBuilder.row(label: String, value: String) = line(label, value)

    private fun StringBuilder.blank() = append("\r\n")

    /**
     * CRLF, not LF: Excel on Windows treats a lone LF inside a quoted field as
     * part of the value and the file opens with rows merged.
     */
    private fun StringBuilder.line(vararg cells: String) {
        append(cells.joinToString(SEPARATOR) { escape(it) })
        append("\r\n")
    }

    /**
     * RFC 4180 quoting. A holding named `Vanguard S&P 500, Acc` is the case
     * that matters — unquoted, it silently becomes two columns and shifts every
     * figure in the row one place left.
     */
    private fun escape(value: String): String {
        val needsQuotes = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        if (!needsQuotes) return value
        return "\"" + value.replace("\"", "\"\"") + "\""
    }

    // ── Formatting ────────────────────────────────────────────────────────
    //
    // Locale.US for the numbers, always, and deliberately: a spreadsheet
    // reading "1.234,56" from a French locale as text rather than a number is
    // the single most common way an export like this arrives useless. The
    // labels stay localised because they are read, not calculated with.

    private fun number(value: Double): String =
        if (value.isNaN() || value.isInfinite()) "" else String.format(Locale.US, "%.2f", value)

    /** As a percentage NUMBER (12.34), not a string with a % sign, so it sums. */
    private fun percent(fraction: Double): String =
        if (fraction.isNaN() || fraction.isInfinite()) ""
        else String.format(Locale.US, "%.2f", fraction * 100.0)
}
