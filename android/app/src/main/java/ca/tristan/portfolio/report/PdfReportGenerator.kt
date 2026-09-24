package ca.tristan.portfolio.report

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * Renders a [PerformanceReport] to a multi-page A4 PDF.
 *
 * Drawn directly with [PdfDocument] and Canvas rather than through a PDF
 * library: this needs no third-party dependency (iText is AGPL, which does not
 * suit a closed-source app), works with no network, and keeps the document
 * visually identical to the app's navy/gold identity.
 */
object PdfReportGenerator {

    // A4 at 72dpi, the unit PdfDocument works in.
    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val MARGIN = 40f
    private const val CONTENT_W = PAGE_W - MARGIN * 2

    private const val NAVY = 0xFF0F2A43.toInt()
    private const val NAVY_LIGHT = 0xFF1D3F5F.toInt()
    private const val GOLD = 0xFFC9A227.toInt()
    private const val GREEN = 0xFF1E8E5A.toInt()
    private const val RED = 0xFFC0392B.toInt()
    private const val INK = 0xFF1A1C1E.toInt()
    private const val MUTED = 0xFF6B7280.toInt()
    private const val HAIRLINE = 0xFFD8D5CC.toInt()
    private const val ZEBRA = 0xFFF7F5EF.toInt()

    private val SANS = Typeface.create("sans-serif", Typeface.NORMAL)
    private val SANS_BOLD = Typeface.create("sans-serif", Typeface.BOLD)
    private val SANS_MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    /**
     * Writes the report into the app's cache and returns the file.
     * The caller shares it through a FileProvider.
     */
    fun generate(
        context: Context,
        report: PerformanceReport,
        options: ReportOptions,
        accountLabel: String?
    ): File {
        val doc = PdfDocument()
        val w = Writer(doc, report)

        // Returns and the growth curve are the report — they are not optional.
        // Everything below them is included only if the user asked for it AND
        // there is something to show.
        w.startPage()
        w.coverHeader(accountLabel)
        w.summaryTiles()
        w.equityChart()
        w.returnsSection()

        if (report.benchmark != null) w.benchmarkSection()
        if (options.includeRiskMetrics &&
            (report.risk.annualizedVolatility != null || report.risk.maxDrawdown != null)
        ) w.riskSection()
        if (options.includeHoldingsTable && report.holdings.isNotEmpty()) w.holdingsSection()
        if (options.includeRealizedGains && report.realizedGains.isNotEmpty()) w.realizedSection()
        if (options.includeIncome &&
            (report.income.paymentCount > 0 || report.income.trailing12Months > 0)
        ) w.incomeSection()
        if (options.includeAllocation && report.allocation.byHolding.isNotEmpty()) w.allocationSection()
        if (options.includeTransactionActivity) w.activitySection()
        w.notesAndDisclaimer()

        w.finish()

        val dir = File(context.cacheDir, "reports").apply { mkdirs() }
        // Stable, human-readable filename — this is what the user sees in the
        // share sheet and in whatever app they save it to.
        val stamp = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(report.generatedAtMillis))
        val scope = accountLabel?.replace(Regex("[^A-Za-z0-9]+"), "-")?.trim('-')?.take(24)
        val name = buildString {
            append("WealthBoard-Performance-")
            if (!scope.isNullOrBlank()) append("$scope-")
            append(stamp).append(".pdf")
        }
        val file = File(dir, name)
        FileOutputStream(file).use { doc.writeTo(it) }
        doc.close()
        return file
    }

    // ─────────────────────────────────────────────────────────────────────────

    private class Writer(val doc: PdfDocument, val report: PerformanceReport) {

        private var page: PdfDocument.Page? = null
        private var canvas: Canvas? = null
        private var pageNumber = 0
        private var y = MARGIN

        private val cur = report.currency

        private val p = Paint(Paint.ANTI_ALIAS_FLAG)

        private fun text(size: Float, color: Int, face: Typeface = SANS): Paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = size; this.color = color; typeface = face
            }

        // ── Page lifecycle ───────────────────────────────────────────────────

        fun startPage() {
            finishPage()
            pageNumber++
            val info = PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNumber).create()
            page = doc.startPage(info)
            canvas = page!!.canvas
            y = MARGIN
            if (pageNumber > 1) runningHeader()
        }

        private fun finishPage() {
            page?.let {
                footer()
                doc.finishPage(it)
            }
            page = null; canvas = null
        }

        fun finish() = finishPage()

        private fun runningHeader() {
            val c = canvas ?: return
            c.drawText("WealthBoard — Performance Report", MARGIN, MARGIN - 12f, text(8f, MUTED))
            c.drawText(
                periodLabel(),
                PAGE_W - MARGIN - text(8f, MUTED).measureText(periodLabel()),
                MARGIN - 12f,
                text(8f, MUTED)
            )
            p.color = HAIRLINE; p.strokeWidth = 0.5f
            c.drawLine(MARGIN, MARGIN - 6f, PAGE_W - MARGIN, MARGIN - 6f, p)
            y = MARGIN + 8f
        }

        private fun footer() {
            val c = canvas ?: return
            val f = text(7.5f, MUTED)
            val label = "Page $pageNumber"
            c.drawText(label, PAGE_W - MARGIN - f.measureText(label), PAGE_H - 24f, f)
            c.drawText(
                "Generated by WealthBoard on ${fullDate(report.generatedAtMillis)} · Informational only, not investment advice",
                MARGIN, PAGE_H - 24f, f
            )
        }

        /** Starts a new page when [needed] points will not fit above the footer. */
        private fun ensure(needed: Float) {
            if (y + needed > PAGE_H - 44f) startPage()
        }

        // ── Primitives ───────────────────────────────────────────────────────

        private fun gap(h: Float) { y += h }

        fun sectionTitle(title: String, subtitle: String? = null) {
            ensure(46f)
            val c = canvas ?: return
            gap(10f)
            p.color = GOLD
            c.drawRect(MARGIN, y - 1f, MARGIN + 26f, y + 2.5f, p)
            gap(16f)
            c.drawText(title, MARGIN, y, text(13.5f, NAVY, SANS_BOLD))
            gap(if (subtitle != null) 12f else 6f)
            if (subtitle != null) {
                c.drawText(subtitle, MARGIN, y, text(8.5f, MUTED))
                gap(8f)
            }
        }

        fun paragraph(s: String, size: Float = 8.5f, color: Int = MUTED) {
            val paint = text(size, color)
            val lines = wrap(s, paint, CONTENT_W)
            ensure(lines.size * (size + 3.5f))
            val c = canvas ?: return
            lines.forEach {
                gap(size + 3.5f)
                c.drawText(it, MARGIN, y, paint)
            }
            gap(3f)
        }

        private fun wrap(s: String, paint: Paint, width: Float): List<String> {
            val out = mutableListOf<String>()
            var line = StringBuilder()
            s.split(" ").forEach { word ->
                val candidate = if (line.isEmpty()) word else "$line $word"
                if (paint.measureText(candidate) <= width) {
                    line = StringBuilder(candidate)
                } else {
                    if (line.isNotEmpty()) out += line.toString()
                    line = StringBuilder(word)
                }
            }
            if (line.isNotEmpty()) out += line.toString()
            return out
        }

        private fun ellipsize(s: String, paint: Paint, width: Float): String {
            if (paint.measureText(s) <= width) return s
            var t = s
            while (t.isNotEmpty() && paint.measureText("$t…") > width) t = t.dropLast(1)
            return "$t…"
        }

        // ── Cover ────────────────────────────────────────────────────────────

        fun coverHeader(accountLabel: String?) {
            val c = canvas ?: return
            p.color = NAVY
            c.drawRect(0f, 0f, PAGE_W.toFloat(), 104f, p)
            p.color = GOLD
            c.drawRect(0f, 104f, PAGE_W.toFloat(), 107f, p)

            // Icon mark: three ascending bars, echoing the launcher icon.
            p.color = GOLD
            c.drawRect(MARGIN, 56f, MARGIN + 6f, 74f, p)
            c.drawRect(MARGIN + 9f, 46f, MARGIN + 15f, 74f, p)
            p.color = 0xFFF4F1E8.toInt()
            c.drawRect(MARGIN + 18f, 34f, MARGIN + 24f, 74f, p)

            c.drawText("WealthBoard", MARGIN + 36f, 50f, text(19f, Color.WHITE, SANS_BOLD))
            c.drawText("Portfolio Performance Report", MARGIN + 36f, 66f, text(10f, 0xFFC8D4E0.toInt()))
            val scope = accountLabel ?: "All accounts"
            c.drawText(scope, MARGIN + 36f, 80f, text(8.5f, GOLD))

            val right = text(9f, Color.WHITE, SANS_MEDIUM)
            val pl = periodLabel()
            c.drawText(pl, PAGE_W - MARGIN - right.measureText(pl), 50f, right)
            val sub = text(8f, 0xFFC8D4E0.toInt())
            val range = "${shortDate(report.periodStartMillis)} — ${shortDate(report.periodEndMillis)}"
            c.drawText(range, PAGE_W - MARGIN - sub.measureText(range), 64f, sub)
            val gen = "Generated ${shortDate(report.generatedAtMillis)}"
            c.drawText(gen, PAGE_W - MARGIN - sub.measureText(gen), 78f, sub)

            y = 128f
        }

        /** The four headline numbers, in a boxed row. */
        fun summaryTiles() {
            val c = canvas ?: return
            val r = report.returns
            val tiles = listOf(
                Triple("Portfolio Value", money(r.endValue), NAVY),
                Triple("Total Gain/Loss", signedMoney(r.totalGain), gainColor(r.totalGain)),
                Triple("Time-Weighted Return", pct(r.timeWeightedReturn), gainColor(r.timeWeightedReturn)),
                Triple(
                    if (r.annualizedReturn != null) "Annualized (TWR)" else "Money-Weighted (XIRR)",
                    r.annualizedReturn?.let { pct(it) } ?: r.moneyWeightedReturn?.let { pct(it) } ?: "—",
                    gainColor(r.annualizedReturn ?: r.moneyWeightedReturn ?: 0.0)
                )
            )
            val gapW = 8f
            val tw = (CONTENT_W - gapW * 3) / 4f
            val th = 52f
            tiles.forEachIndexed { i, (label, value, color) ->
                val x = MARGIN + i * (tw + gapW)
                p.color = ZEBRA
                c.drawRoundRect(RectF(x, y, x + tw, y + th), 5f, 5f, p)
                p.color = HAIRLINE; p.style = Paint.Style.STROKE; p.strokeWidth = 0.6f
                c.drawRoundRect(RectF(x, y, x + tw, y + th), 5f, 5f, p)
                p.style = Paint.Style.FILL
                val lp = text(7f, MUTED, SANS_MEDIUM)
                c.drawText(ellipsize(label.uppercase(), lp, tw - 16f), x + 8f, y + 16f, lp)
                val vp = text(13f, color, SANS_BOLD)
                c.drawText(ellipsize(value, vp, tw - 16f), x + 8f, y + 36f, vp)
            }
            y += th + 6f
        }

        // ── Equity curve ─────────────────────────────────────────────────────

        fun equityChart() {
            if (report.equityCurve.size < 2) return
            sectionTitle(
                "Growth of Capital",
                "Indexed to 100 at the start of the period. Deposits and withdrawals are removed, so the line shows investment performance only."
            )
            val h = 150f
            ensure(h + 24f)
            val c = canvas ?: return
            val top = y + 4f
            val bottom = top + h
            val left = MARGIN + 34f
            val right = PAGE_W - MARGIN

            val port = report.equityCurve.map { it.returnIndex * 100.0 }
            val bench = report.benchmark?.benchmarkIndex?.map { it.returnIndex * 100.0 } ?: emptyList()

            var lo = port.min(); var hi = port.max()
            if (bench.size >= 2) { lo = minOf(lo, bench.min()); hi = maxOf(hi, bench.max()) }
            val pad = max((hi - lo) * 0.12, 1.0)
            lo -= pad; hi += pad
            if (hi - lo < 1e-6) { hi = lo + 1 }

            fun yFor(v: Double) = (bottom - ((v - lo) / (hi - lo)) * h).toFloat()

            // Gridlines + axis labels
            p.color = HAIRLINE; p.strokeWidth = 0.5f; p.style = Paint.Style.FILL
            val gp = text(6.5f, MUTED)
            for (i in 0..4) {
                val v = lo + (hi - lo) * i / 4.0
                val gy = yFor(v)
                p.color = HAIRLINE
                c.drawLine(left, gy, right, gy, p)
                val lbl = String.format(Locale.getDefault(), "%.0f", v)
                c.drawText(lbl, MARGIN, gy + 2.5f, gp)
            }
            // Baseline at 100 — the break-even line.
            if (100.0 in lo..hi) {
                p.color = 0xFF9AA3AE.toInt(); p.strokeWidth = 0.9f
                c.drawLine(left, yFor(100.0), right, yFor(100.0), p)
            }

            fun path(series: List<Double>): Path {
                val path = Path()
                series.forEachIndexed { i, v ->
                    val x = left + (right - left) * (i.toFloat() / (series.size - 1).coerceAtLeast(1))
                    if (i == 0) path.moveTo(x, yFor(v)) else path.lineTo(x, yFor(v))
                }
                return path
            }

            if (bench.size >= 2) {
                p.color = 0xFF9AA3AE.toInt(); p.style = Paint.Style.STROKE
                p.strokeWidth = 1.1f
                p.pathEffect = android.graphics.DashPathEffect(floatArrayOf(4f, 3f), 0f)
                c.drawPath(path(bench), p)
                p.pathEffect = null
            }

            // Fill under the portfolio line, then the line itself.
            val fill = Path(path(port))
            fill.lineTo(right, bottom); fill.lineTo(left, bottom); fill.close()
            p.style = Paint.Style.FILL
            p.color = if (report.returns.timeWeightedReturn >= 0) 0x1A1E8E5A else 0x1AC0392B
            c.drawPath(fill, p)

            p.style = Paint.Style.STROKE; p.strokeWidth = 1.6f
            p.color = if (report.returns.timeWeightedReturn >= 0) GREEN else RED
            c.drawPath(path(port), p)
            p.style = Paint.Style.FILL; p.pathEffect = null

            // Frame + date bounds
            p.color = HAIRLINE; p.style = Paint.Style.STROKE; p.strokeWidth = 0.6f
            c.drawRect(left, top, right, bottom, p)
            p.style = Paint.Style.FILL
            c.drawText(shortDate(report.periodStartMillis), left, bottom + 11f, gp)
            val endLbl = shortDate(report.periodEndMillis)
            c.drawText(endLbl, right - gp.measureText(endLbl), bottom + 11f, gp)

            // Legend
            var lx = left + 4f
            val ly = top + 12f
            p.color = if (report.returns.timeWeightedReturn >= 0) GREEN else RED
            c.drawRect(lx, ly - 4f, lx + 10f, ly - 2.5f, p)
            val leg = text(7f, INK)
            c.drawText("Your portfolio", lx + 14f, ly, leg)
            lx += 14f + leg.measureText("Your portfolio") + 12f
            report.benchmark?.let {
                p.color = 0xFF9AA3AE.toInt()
                c.drawRect(lx, ly - 4f, lx + 10f, ly - 2.5f, p)
                c.drawText(it.benchmark.displayName, lx + 14f, ly, leg)
            }

            y = bottom + 18f
        }

        // ── Tables ───────────────────────────────────────────────────────────

        /**
         * @param cols (title, weight, rightAligned)
         * @param rows cell text; [colorFor] may tint a cell by row/column.
         */
        fun table(
            cols: List<Triple<String, Float, Boolean>>,
            rows: List<List<String>>,
            colorFor: ((Int, Int) -> Int?)? = null,
            rowHeight: Float = 15f
        ) {
            if (rows.isEmpty()) return
            val totalWeight = cols.sumOf { it.second.toDouble() }.toFloat()
            val widths = cols.map { CONTENT_W * it.second / totalWeight }

            fun header() {
                ensure(rowHeight + 6f)
                val c = canvas ?: return
                p.color = NAVY
                c.drawRect(MARGIN, y, MARGIN + CONTENT_W, y + rowHeight + 2f, p)
                var x = MARGIN
                cols.forEachIndexed { i, (title, _, right) ->
                    val hp = text(7f, Color.WHITE, SANS_BOLD)
                    val t = ellipsize(title.uppercase(), hp, widths[i] - 8f)
                    val tx = if (right) x + widths[i] - 4f - hp.measureText(t) else x + 4f
                    c.drawText(t, tx, y + rowHeight - 3f, hp)
                    x += widths[i]
                }
                y += rowHeight + 2f
            }

            header()
            rows.forEachIndexed { ri, row ->
                if (y + rowHeight > PAGE_H - 44f) { startPage(); header() }
                val c = canvas ?: return
                if (ri % 2 == 1) {
                    p.color = ZEBRA
                    c.drawRect(MARGIN, y, MARGIN + CONTENT_W, y + rowHeight, p)
                }
                var x = MARGIN
                row.forEachIndexed { ci, cell ->
                    if (ci >= cols.size) return@forEachIndexed
                    val col = colorFor?.invoke(ri, ci) ?: INK
                    val face = if (ci == 0) SANS_MEDIUM else SANS
                    val cp = text(7.6f, col, face)
                    val t = ellipsize(cell, cp, widths[ci] - 8f)
                    val tx = if (cols[ci].third) x + widths[ci] - 4f - cp.measureText(t) else x + 4f
                    c.drawText(t, tx, y + rowHeight - 4.5f, cp)
                    x += widths[ci]
                }
                p.color = HAIRLINE; p.strokeWidth = 0.4f
                c.drawLine(MARGIN, y + rowHeight, MARGIN + CONTENT_W, y + rowHeight, p)
                y += rowHeight
            }
            y += 4f
        }

        /** Two-column label/value list, used for the metric blocks. */
        fun metricRows(pairs: List<Triple<String, String, Int>>) {
            val c = canvas ?: return
            val colW = CONTENT_W / 2f
            var i = 0
            while (i < pairs.size) {
                ensure(16f)
                val cc = canvas ?: return
                for (col in 0..1) {
                    val idx = i + col
                    if (idx >= pairs.size) break
                    val (label, value, color) = pairs[idx]
                    val x = MARGIN + col * colW
                    val lp = text(8f, MUTED)
                    cc.drawText(ellipsize(label, lp, colW * 0.62f), x, y + 10f, lp)
                    val vp = text(8.5f, color, SANS_BOLD)
                    cc.drawText(value, x + colW - 8f - vp.measureText(value), y + 10f, vp)
                    p.color = HAIRLINE; p.strokeWidth = 0.4f
                    cc.drawLine(x, y + 14f, x + colW - 8f, y + 14f, p)
                }
                y += 16f
                i += 2
            }
            y += 4f
        }

        // ── Sections ─────────────────────────────────────────────────────────

        fun returnsSection() {
            val r = report.returns
            sectionTitle(
                "Returns",
                "Time-weighted return measures the investments; money-weighted return measures what you earned given when you added money."
            )
            metricRows(listOf(
                Triple("Time-weighted return (TWR)", pct(r.timeWeightedReturn), gainColor(r.timeWeightedReturn)),
                Triple("Money-weighted return (XIRR, p.a.)", r.moneyWeightedReturn?.let { pct(it) } ?: "—",
                    gainColor(r.moneyWeightedReturn ?: 0.0)),
                Triple("Annualized return", r.annualizedReturn?.let { pct(it) } ?: "n/a (period < 1 yr)",
                    if (r.annualizedReturn != null) gainColor(r.annualizedReturn) else MUTED),
                Triple("Simple return on average capital", pct(r.simpleReturn), gainColor(r.simpleReturn)),
                Triple("Opening value", money(r.startValue), INK),
                Triple("Closing value", money(r.endValue), INK),
                Triple("Net contributions", signedMoney(r.netContributions), INK),
                Triple("Total gain/loss", signedMoney(r.totalGain), gainColor(r.totalGain)),
                Triple("— from price change (unrealized)", signedMoney(r.unrealizedGain), gainColor(r.unrealizedGain)),
                Triple("— from sales (realized)", signedMoney(r.realizedGain), gainColor(r.realizedGain)),
                Triple("— from dividends", signedMoney(r.dividendIncome), gainColor(r.dividendIncome)),
                Triple("Period length", "${r.periodDays} days", INK)
            ))
        }

        fun benchmarkSection() {
            val b = report.benchmark ?: return
            sectionTitle("Benchmark Comparison", "Your time-weighted return against ${b.benchmark.displayName} over the same window.")
            metricRows(listOf(
                Triple("Your portfolio (TWR)", pct(b.portfolioReturn), gainColor(b.portfolioReturn)),
                Triple(b.benchmark.displayName, pct(b.benchmarkReturn), gainColor(b.benchmarkReturn)),
                Triple("Excess return vs benchmark", pct(b.excessReturn), gainColor(b.excessReturn)),
                Triple("Alpha (annualized)", b.alpha?.let { pct(it) } ?: "—", gainColor(b.alpha ?: 0.0)),
                Triple("Beta", b.beta?.let { fmt2(it) } ?: "—", INK),
                Triple("Correlation", b.correlation?.let { fmt2(it) } ?: "—", INK)
            ))
            val verdict = when {
                b.excessReturn > 0.0001 -> "You outperformed the benchmark by ${pct(b.excessReturn)} over this period."
                b.excessReturn < -0.0001 -> "You trailed the benchmark by ${pct(abs(b.excessReturn))} over this period."
                else -> "You matched the benchmark over this period."
            }
            val betaNote = b.beta?.let {
                when {
                    it > 1.15 -> " A beta of ${fmt2(it)} means the portfolio has moved more sharply than the benchmark in both directions."
                    it < 0.85 -> " A beta of ${fmt2(it)} means the portfolio has moved less sharply than the benchmark."
                    else -> " A beta of ${fmt2(it)} means the portfolio has tracked the benchmark closely."
                }
            } ?: ""
            paragraph(verdict + betaNote)
        }

        fun riskSection() {
            val rk = report.risk
            sectionTitle("Risk", "How much the portfolio moved around to produce the return above.")
            metricRows(listOf(
                Triple("Annualized volatility", rk.annualizedVolatility?.let { pct(it) } ?: "—", INK),
                Triple("Sharpe ratio", rk.sharpeRatio?.let { fmt2(it) } ?: "—",
                    if ((rk.sharpeRatio ?: 0.0) >= 1.0) GREEN else INK),
                Triple("Maximum drawdown", rk.maxDrawdown?.let { pct(it) } ?: "—", if (rk.maxDrawdown != null) RED else INK),
                Triple("Drawdown recovered", if (rk.maxDrawdown == null) "—" else if (rk.drawdownRecovered) "Yes" else "Not yet",
                    if (rk.maxDrawdown != null && !rk.drawdownRecovered) RED else INK),
                Triple("Best month", rk.bestMonth?.let { "${monthName(it.first)}  ${pct(it.second)}" } ?: "—", GREEN),
                Triple("Worst month", rk.worstMonth?.let { "${monthName(it.first)}  ${pct(it.second)}" } ?: "—", RED),
                Triple("Positive months", if (rk.totalMonths > 0) "${rk.positiveMonths} of ${rk.totalMonths}" else "—", INK),
                Triple("Risk-free rate assumed", pct(rk.riskFreeRateUsed), MUTED)
            ))
            rk.maxDrawdown?.let { dd ->
                val from = rk.maxDrawdownPeakMillis?.let { shortDate(it) }
                val to = rk.maxDrawdownTroughMillis?.let { shortDate(it) }
                if (from != null && to != null) {
                    paragraph("The largest peak-to-trough decline was ${pct(dd)}, from $from to $to. " +
                        (if (rk.drawdownRecovered) "The portfolio has since returned to its previous high."
                        else "The portfolio has not yet regained that high."))
                }
            }
            paragraph("Sharpe ratio divides return above the risk-free rate by volatility — higher is better, and above 1.0 is generally considered strong. Volatility is the annualized standard deviation of daily returns.")
        }

        fun holdingsSection() {
            sectionTitle("Holdings Performance", "Sorted by total contribution to your gain — the positions that mattered most appear first.")
            val rows = report.holdings.map { h ->
                listOf(
                    h.ticker ?: h.name,
                    fmtUnits(h.units),
                    h.averageCost?.let { money(it) } ?: "—",
                    h.currentPrice?.let { money(it) } ?: "—",
                    money(h.marketValue),
                    pct(h.weight),
                    signedMoney(h.unrealizedGain),
                    h.unrealizedGainPercent?.let { pct(it) } ?: "—",
                    signedMoney(h.totalGain)
                )
            }
            table(
                cols = listOf(
                    Triple("Holding", 1.5f, false),
                    Triple("Units", 1.0f, true),
                    Triple("Avg Cost", 1.1f, true),
                    Triple("Price", 1.1f, true),
                    Triple("Value", 1.3f, true),
                    Triple("Weight", 0.9f, true),
                    Triple("Unrealized", 1.3f, true),
                    Triple("Unreal %", 1.0f, true),
                    Triple("Total Gain", 1.3f, true)
                ),
                rows = rows,
                colorFor = { ri, ci ->
                    when (ci) {
                        6, 7 -> gainColor(report.holdings[ri].unrealizedGain)
                        8 -> gainColor(report.holdings[ri].totalGain)
                        else -> null
                    }
                }
            )
            val best = report.holdings.firstOrNull()
            val worst = report.holdings.lastOrNull()
            if (best != null && worst != null && best.holdingId != worst.holdingId) {
                paragraph("Best contributor: ${best.ticker ?: best.name} at ${signedMoney(best.totalGain)}. " +
                    "Weakest: ${worst.ticker ?: worst.name} at ${signedMoney(worst.totalGain)}. " +
                    "Total gain includes price change, realized sales and dividends received.")
            }
        }

        fun realizedSection() {
            sectionTitle("Realized Gains", "Positions sold during the period. Cost is average cost at the time of sale.")
            val rows = report.realizedGains.map { r ->
                listOf(
                    shortDate(r.soldAtMillis),
                    r.ticker ?: r.holdingName,
                    fmtUnits(r.shares),
                    money(r.salePrice),
                    money(r.averageCostAtSale),
                    money(r.proceeds),
                    signedMoney(r.realizedGain),
                    r.realizedGainPercent?.let { pct(it) } ?: "—"
                )
            }
            table(
                cols = listOf(
                    Triple("Date", 1.2f, false),
                    Triple("Holding", 1.5f, false),
                    Triple("Units", 1.0f, true),
                    Triple("Sale Price", 1.2f, true),
                    Triple("Avg Cost", 1.2f, true),
                    Triple("Proceeds", 1.3f, true),
                    Triple("Realized", 1.3f, true),
                    Triple("Return", 1.0f, true)
                ),
                rows = rows,
                colorFor = { ri, ci ->
                    if (ci == 6 || ci == 7) gainColor(report.realizedGains[ri].realizedGain) else null
                }
            )
            val total = report.realizedGains.sumOf { it.realizedGain }
            paragraph("Total realized ${if (total >= 0) "gain" else "loss"} for the period: ${signedMoney(total)}. " +
                "This figure may be relevant for tax reporting; confirm the treatment that applies in your jurisdiction.")
        }

        fun incomeSection() {
            val inc = report.income
            sectionTitle("Dividend Income", "Distributions recorded during the period, and the trailing-twelve-month yield they imply.")
            metricRows(listOf(
                Triple("Income this period", money(inc.totalInPeriod), GREEN),
                Triple("Trailing 12 months", money(inc.trailing12Months), GREEN),
                Triple("Yield on cost (TTM)", inc.yieldOnCost?.let { pct(it) } ?: "—", INK),
                Triple("Current yield (TTM)", inc.currentYield?.let { pct(it) } ?: "—", INK),
                Triple("Reinvested (DRIP)", money(inc.reinvestedAmount), INK),
                Triple("Taken as cash", money(inc.cashAmount), INK),
                Triple("Payments received", inc.paymentCount.toString(), INK),
                Triple("Average payment", if (inc.paymentCount > 0) money(inc.totalInPeriod / inc.paymentCount) else "—", INK)
            ))

            if (inc.byMonth.size >= 2) monthlyIncomeChart(inc.byMonth)

            if (inc.byHolding.isNotEmpty()) {
                val top = inc.byHolding.take(15)
                val totalInc = inc.byHolding.sumOf { it.second }
                table(
                    cols = listOf(
                        Triple("Holding", 3f, false),
                        Triple("Income", 1.4f, true),
                        Triple("Share of Income", 1.4f, true)
                    ),
                    rows = top.map { (name, amt) ->
                        listOf(name, money(amt), if (totalInc > 0) pct(amt / totalInc) else "—")
                    }
                )
            }
        }

        private fun monthlyIncomeChart(byMonth: List<Pair<Long, Double>>) {
            val h = 92f
            ensure(h + 24f)
            val c = canvas ?: return
            gap(6f)
            val top = y
            val bottom = top + h
            val maxV = byMonth.maxOf { it.second }.coerceAtLeast(0.01)
            // Cap the bar count so a long window stays legible.
            val items = if (byMonth.size > 24) byMonth.takeLast(24) else byMonth
            val slot = CONTENT_W / items.size
            val barW = (slot * 0.62f).coerceAtMost(22f)

            p.color = HAIRLINE; p.strokeWidth = 0.5f
            c.drawLine(MARGIN, bottom, MARGIN + CONTENT_W, bottom, p)

            items.forEachIndexed { i, (ms, amt) ->
                val bh = ((amt / maxV) * (h - 14f)).toFloat()
                val x = MARGIN + i * slot + (slot - barW) / 2f
                p.color = GOLD
                c.drawRoundRect(RectF(x, bottom - bh, x + barW, bottom), 2f, 2f, p)
                // Label every month when there is room, otherwise every other.
                if (items.size <= 14 || i % 2 == 0) {
                    val lp = text(6f, MUTED)
                    val lbl = monthShort(ms)
                    c.drawText(lbl, x + barW / 2f - lp.measureText(lbl) / 2f, bottom + 8f, lp)
                }
            }
            val mp = text(6.5f, MUTED)
            c.drawText("Peak month: ${money(maxV)}", MARGIN, top + 7f, mp)
            y = bottom + 16f
        }

        fun allocationSection() {
            val a = report.allocation
            sectionTitle("Allocation", "Where the portfolio's value sits today.")

            if (a.byType.isNotEmpty()) {
                paragraph("By asset type", 9f, NAVY)
                allocationBars(a.byType)
            }
            if (a.byAccount.size > 1) {
                paragraph("By account", 9f, NAVY)
                allocationBars(a.byAccount)
            }
            paragraph("Largest positions", 9f, NAVY)
            allocationBars(a.byHolding.take(10))

            val concentrationNote = when {
                a.largestPositionWeight >= 0.30 ->
                    "${a.largestPositionName} is ${pct(a.largestPositionWeight)} of the portfolio. A single position above 30% means the portfolio's result depends heavily on that one holding."
                a.largestPositionWeight >= 0.20 ->
                    "${a.largestPositionName} is ${pct(a.largestPositionWeight)} of the portfolio — worth keeping an eye on as a concentration."
                else ->
                    "No single position exceeds 20% of the portfolio."
            }
            paragraph(concentrationNote + " Concentration index (Herfindahl) is ${fmt2(a.concentrationIndex)}, where 1.00 would be a single holding and lower values indicate a more even spread.")
        }

        private fun allocationBars(slices: List<AllocationSlice>) {
            if (slices.isEmpty()) return
            val rowH = 14f
            slices.forEach { s ->
                ensure(rowH)
                val c = canvas ?: return
                val labelW = CONTENT_W * 0.30f
                val barMax = CONTENT_W * 0.50f
                val lp = text(7.6f, INK, SANS_MEDIUM)
                c.drawText(ellipsize(s.label, lp, labelW - 6f), MARGIN, y + 9.5f, lp)

                val bx = MARGIN + labelW
                p.color = 0xFFECE9E0.toInt()
                c.drawRoundRect(RectF(bx, y + 2.5f, bx + barMax, y + 10.5f), 2f, 2f, p)
                p.color = NAVY_LIGHT
                val w = (barMax * s.percent).toFloat().coerceAtLeast(1.5f)
                c.drawRoundRect(RectF(bx, y + 2.5f, bx + w, y + 10.5f), 2f, 2f, p)

                val vp = text(7.6f, INK)
                val vt = "${pct(s.percent)}   ${money(s.value)}"
                c.drawText(vt, MARGIN + CONTENT_W - vp.measureText(vt), y + 9.5f, vp)
                y += rowH
            }
            y += 6f
        }

        fun activitySection() {
            val a = report.activity
            sectionTitle("Activity", "Transactions recorded inside the reporting window.")
            metricRows(listOf(
                Triple("Buys", a.buyCount.toString(), INK),
                Triple("Sells", a.sellCount.toString(), INK),
                Triple("Dividend reinvestments", a.dripCount.toString(), INK),
                Triple("Total invested", money(a.totalInvested), INK),
                Triple("Total withdrawn", money(a.totalWithdrawn), INK),
                Triple("Total reinvested", money(a.totalReinvested), INK)
            ))
        }

        fun notesAndDisclaimer() {
            sectionTitle("Notes & Methodology")

            paragraph("Returns. Time-weighted return (TWR) links daily returns and removes the effect of when money was added or taken out, which makes it the figure to compare against a benchmark. Money-weighted return (XIRR) solves for the annualized rate that makes your actual cash flows balance, so it reflects your own timing. The two differ whenever contributions were uneven.")
            paragraph("Valuation. Positions with a ticker are valued using daily closing prices. Dividends are treated as part of return: cash distributions are held in the portfolio until reinvested, and reinvestments (DRIP) are internal transfers rather than new contributions. Buys and sells are the only external flows.")
            paragraph("Cost basis. Realized gains use the average-cost method across all units of a holding.")

            if (report.untickeredHoldingNames.isNotEmpty()) {
                paragraph("Estimated positions. The following have no ticker and were held at their entered price for the whole period, so their contribution to return is an estimate rather than a market valuation: ${report.untickeredHoldingNames.joinToString(", ")}.")
            }
            if (report.warnings.isNotEmpty()) {
                paragraph("Data notes. " + report.warnings.joinToString(" "))
            }

            ensure(70f)
            val c = canvas ?: return
            gap(8f)
            p.color = ZEBRA
            c.drawRoundRect(RectF(MARGIN, y, MARGIN + CONTENT_W, y + 58f), 4f, 4f, p)
            p.color = GOLD
            c.drawRect(MARGIN, y, MARGIN + 2.5f, y + 58f, p)
            val dp = text(7f, MUTED)
            val disclaimer = "This report is generated from holdings and transactions you entered yourself and from third-party market data, and is provided for informational purposes only. It is not investment, tax or financial advice, and it is not a statement from a broker, custodian or financial institution. Figures may differ from your official account statements. Past performance does not predict future results. Verify all values against your statements before relying on them."
            var ty = y + 13f
            wrap(disclaimer, dp, CONTENT_W - 20f).forEach {
                c.drawText(it, MARGIN + 12f, ty, dp)
                ty += 9.5f
            }
            y += 64f
        }

        // ── Formatting ───────────────────────────────────────────────────────

        // Through the shared formatter, so an exported PDF and the report on
        // screen print the same figure the same way. This printed "CAD 1,234.56"
        // where the app prints "CA$1,234.56".
        private fun money(v: Double): String =
            ca.tristan.portfolio.ui.format.Money.format(v, cur)

        private fun signedMoney(v: Double): String =
            ca.tristan.portfolio.ui.format.Money.signed(v, cur)

        private fun pct(v: Double): String =
            ca.tristan.portfolio.ui.format.Money.signedPercent(v * 100)

        private fun fmt2(v: Double): String = String.format(Locale.US, "%.2f", v)

        private fun fmtUnits(v: Double): String =
            ca.tristan.portfolio.ui.format.Money.units(v)

        private fun gainColor(v: Double) = when {
            v > 0 -> GREEN
            v < 0 -> RED
            else -> INK
        }

        private fun periodLabel() = report.period.label

        private fun shortDate(ms: Long) =
            SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(ms))

        private fun fullDate(ms: Long) =
            SimpleDateFormat("d MMMM yyyy 'at' HH:mm", Locale.getDefault()).format(Date(ms))

        private fun monthName(ms: Long) =
            SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date(ms))

        private fun monthShort(ms: Long) =
            SimpleDateFormat("MMM", Locale.getDefault()).format(Date(ms))
    }
}
