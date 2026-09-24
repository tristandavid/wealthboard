package ca.tristan.portfolio.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ca.tristan.portfolio.ui.HoldingDetail
import ca.tristan.portfolio.ui.PortfolioViewModel
import ca.tristan.portfolio.ui.securityKey
import ca.tristan.portfolio.ui.components.DividendBarChart
import ca.tristan.portfolio.ui.components.DividendPeriod
import ca.tristan.portfolio.ui.components.KeyValueRow
import ca.tristan.portfolio.ui.components.buildDividendBars
import ca.tristan.portfolio.ui.components.RangeBar
import ca.tristan.portfolio.ui.components.SectionHeader
import ca.tristan.portfolio.ui.components.StatPair
import ca.tristan.portfolio.ui.components.StatusPill
import ca.tristan.portfolio.ui.components.TickerLogo
import ca.tristan.portfolio.ui.components.WbCard
import ca.tristan.portfolio.ui.components.WbDimens
import ca.tristan.portfolio.ui.components.WbDivider
import ca.tristan.portfolio.ui.components.WealthBoardTopBar
import ca.tristan.portfolio.ui.format.Money
import ca.tristan.portfolio.ui.format.TickerFlag
import ca.tristan.portfolio.ui.theme.GainGreen
import ca.tristan.portfolio.ui.theme.LossRed
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val dateFmt = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())

// Money and percentages come from the shared formatter now. This screen used
// the device locale, which renders a Canadian holding as "$112,112.50" and a
// US one as "US$326.57" — the same glyph meaning two currencies, on a screen
// whose whole job is telling you what a position is worth.
private fun pct(v: Double?, digits: Int = 2): String = Money.percent(v, digits)

private fun signedPct(v: Double?): String = Money.signedPercent(v)

/**
 * Flag for a ticker, so the header reads like the rest of the app.
 *
 * This screen used to carry its own eight-entry copy of this mapping, which
 * knew Toronto, London, Frankfurt, Paris, Tokyo, Hong Kong and Sydney and
 * defaulted everything else to 🇺🇸 — so a Stockholm listing like ERIC-A.ST
 * was labelled American while the card below it correctly said SEK. Every
 * screen now shares [TickerFlag]; the currency is passed as a last resort so
 * an unrecognised suffix still gets the right flag once a quote has landed.
 */
private fun holdingFlag(ticker: String?, currency: String?): String =
    ticker?.let { TickerFlag.forTicker(it, exchange = null, currency = currency) } ?: ""

/**
 * Holding detail: price header, position stats, dividend metrics, a
 * trailing-yield history chart and the recent/upcoming payout schedule.
 *
 * Laid out as labelled stat pairs inside the app's standard cards so it reads
 * the same way as the Dividends and Markets screens.
 */
@Composable
fun HoldingScreen(
    viewModel: PortfolioViewModel,
    holdingId: Long,
    onBack: () -> Unit,
    onAddDividend: () -> Unit,
    onDeleted: () -> Unit,
    onOpenBreakdown: (String) -> Unit = {},
    /** Opens the holding's own edit form. */
    onEditHolding: () -> Unit = {}
) {
    val holdings by viewModel.holdings.collectAsStateWithLifecycle()
    val accountsForTax by viewModel.accounts.collectAsStateWithLifecycle()
    val holding = holdings.firstOrNull { it.id == holdingId }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val detail by produceState<HoldingDetail?>(initialValue = null, holdingId, holdings.size) {
        value = viewModel.loadHoldingDetail(holdingId)
    }

    var chartPeriod by remember { mutableStateOf(DividendPeriod.MONTH) }

    // ── Currency toggle ──────────────────────────────────────────────────
    // Every figure below is computed in the holding's own trading currency
    // (nativeCurrency). For a holding that trades somewhere other than the
    // user's reporting currency — AAPL held by a CAD-default user, say — the
    // screen now opens converted into the user's default currency, since
    // that's the number that lines up with the rest of the portfolio, with a
    // one-tap switch back to the original currency the position actually
    // trades in.
    val baseCurrency by viewModel.baseCurrency.collectAsStateWithLifecycle()
    val fxTick by viewModel.fxTick.collectAsStateWithLifecycle()
    val nativeCurrency = detail?.quote?.currency?.uppercase() ?: holding?.currency ?: baseCurrency
    val needsToggle = holding != null && !nativeCurrency.equals(baseCurrency, ignoreCase = true)
    var viewInBase by remember(holdingId) { mutableStateOf(true) }
    LaunchedEffect(nativeCurrency) { viewModel.ensureRateFor(nativeCurrency) }

    if (holding == null) {
        Scaffold(
            topBar = {
                WealthBoardTopBar(
                    title = "Holding",
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
            }
        ) { p ->
            Box(Modifier.fillMaxSize().padding(p), contentAlignment = Alignment.Center) {
                Text("Holding not found.")
            }
        }
        return
    }

    // Yahoo's currency wins over the stored one — it's authoritative and
    // corrects holdings saved before the exchange was known.
    val displayCurrency = nativeCurrency
    val flag  = holdingFlag(holding.ticker, nativeCurrency)

    // Currency actually shown right now: the user's default when the toggle
    // is on (or there's nothing to toggle), the holding's own otherwise.
    //
    // `wantsBase` is the request; `fxRate` is whether it can actually be
    // honoured. A currency Yahoo has no pair for (or one whose rate simply
    // hasn't landed yet) leaves fxRate null, and in that case the figures stay
    // in — and are labelled in — the currency they are actually in. Labelling
    // an unconverted SEK figure "CA$" is the one outcome worse than not
    // converting it at all: it is not a rounding error, it is a number that
    // reads roughly 7x too large with no hint that anything went wrong.
    val wantsBase = needsToggle && viewInBase
    val fxRate = if (wantsBase) viewModel.rateToBase(nativeCurrency) else null
    val activeCurrency = if (wantsBase && fxRate != null) baseCurrency else nativeCurrency

    // Local shorthand so every figure on the screen goes through one
    // converter+formatter. Depends on `fxTick` (collected above) so the
    // whole screen recomposes — and this picks up the real rate — the
    // moment a requested FX rate actually lands.
    fun money(v: Double): String {
        val converted = if (fxRate != null) v * fxRate else v
        return Money.format(converted, activeCurrency)
    }

    /**
     * Same conversion as [money], for the few figures that need the number
     * rather than the formatted string (per-unit amounts, which use their own
     * higher-precision formatter).
     */
    fun perUnitInActive(v: Double): Double = if (fxRate != null) v * fxRate else v

    Scaffold(
        topBar = {
            WealthBoardTopBar(
                title = listOf(flag, holding.ticker ?: holding.name)
                    .filter { it.isNotBlank() }.joinToString("  "),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // One overflow menu rather than a row of unlabelled icons.
                    //
                    // Two glyphs — a banknote and a bin — asked the reader to
                    // guess, and left no room for the two actions that were
                    // missing entirely: recording a trade against this holding,
                    // and editing it. Named items say what each one does, and
                    // the destructive one sits apart at the bottom.
                    var menuOpen by remember { mutableStateOf(false) }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More")
                    }
                    // No "Record a transaction" here. Buys and sells are
                    // recorded from the Portfolio "+", where the account is
                    // part of the entry; offering it from one account's holding
                    // as well made the same trade reachable two ways.
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Record dividend") },
                            onClick = { menuOpen = false; onAddDividend() }
                        )
                        DropdownMenuItem(
                            text = { Text("Edit holding") },
                            onClick = { menuOpen = false; onEditHolding() }
                        )
                        Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "Delete holding",
                                    color = MaterialTheme.colorScheme.error
                                )
                            },
                            onClick = { menuOpen = false; showDeleteConfirm = true }
                        )
                    }
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            // ── Header: logo, name, price, as-of, currency toggle ───────────
            //
            // The name side is weighted and the toggle is not: an unweighted
            // Row measures at its content's full width, so a long company name
            // ("Ericsson, Telefonab. L M ser. A") took the entire header and
            // laid the toggle out past the right edge of the screen, where it
            // was simply invisible — the toggle looked like it had never been
            // added. Weighting the name side makes it yield instead.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = WbDimens.ScreenPadding, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    TickerLogo(
                        logoUrl = detail?.quote?.logoUrl,
                        label = holding.ticker ?: holding.name,
                        size = 44.dp
                    )
                    Column {
                        Text(
                            holding.name,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        val price = detail?.quote?.price ?: holding.lastKnownPrice ?: holding.manualPrice
                        Text(
                            if (price != null) money(price) else "—",
                            fontSize = 30.sp,
                            fontWeight = FontWeight.Bold
                        )
                        holding.lastPriceAtMillis?.let {
                            Text(
                                "As of ${dateFmt.format(Date(it))}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                // Currency toggle — only shown when there's actually something
                // to switch between. Every money figure on this screen reads
                // `activeCurrency`, so flipping this instantly re-renders the
                // whole page in the other currency.
                //
                // The highlighted chip is `activeCurrency`, not the requested
                // one: while a rate is still in flight the figures really are
                // in the native currency, and a toggle claiming otherwise
                // would be the same lie as the CA$-labelled SEK it replaces.
                if (needsToggle) {
                    CurrencyToggle(
                        nativeCurrency = nativeCurrency,
                        baseCurrency = baseCurrency,
                        viewInBase = activeCurrency.equals(baseCurrency, ignoreCase = true),
                        onChange = { viewInBase = it },
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }

            if (detail == null) {
                Box(
                    Modifier.fillMaxWidth().height(120.dp),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator(Modifier.height(28.dp)) }
            }

            val d = detail

            // ── Overview: type, 52-week band ──────────────────────────────
            WbCard(Modifier.padding(horizontal = WbDimens.ScreenPadding, vertical = 4.dp)) {
                StatPair(
                    // label(), not the raw enum name: the constant spelling
                    // prints "SEG FUND" where the rest of the app says
                    // "Seg Funds/Variable Annuities".
                    "Holding Type", holding.type.label(),
                    "Currency", displayCurrency
                )
                if (d?.fiftyTwoWeekFraction != null) {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "52-week High/Low",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    RangeBar(d.fiftyTwoWeekFraction!!)
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                        Text(
                            d.quote?.fiftyTwoWeekLow?.let { money(it) } ?: "—",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            d.quote?.fiftyTwoWeekHigh?.let { money(it) } ?: "—",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // ── Position ──────────────────────────────────────────────────
            SectionHeader("Position")
            WbCard(Modifier.padding(horizontal = WbDimens.ScreenPadding)) {
                StatPair(
                    "Shares", Money.units(d?.slices?.sumOf { it.units } ?: holding.units),
                    "Market Value", money(d?.marketValue ?: 0.0)
                )
                Spacer(Modifier.height(14.dp))
                StatPair(
                    "Average Cost", d?.averageCost?.let { money(it) } ?: "—",
                    "Portfolio Weight", pct(d?.portfolioWeightPct, 1)
                )
                Spacer(Modifier.height(14.dp))
                StatPair(
                    "Total Contributions", d?.totalContributions?.let { money(it) } ?: "—",
                    "Dividends Received", money(d?.allTimeReceived ?: 0.0)
                )
                Spacer(Modifier.height(14.dp))
                WbDivider()
                Spacer(Modifier.height(12.dp))

                // Returns get their own emphasis — they're what the screen is for.
                ReturnLine(
                    "Price Return",
                    d?.priceReturn?.let { money(it) },
                    d?.priceReturnPct
                )
                Spacer(Modifier.height(10.dp))
                ReturnLine(
                    "Total Return (incl. dividends)",
                    d?.totalReturn?.let { money(it) },
                    d?.totalReturnPct
                )

                // The way back down to the per-account split. Everything above
                // is the combined position, which is what the user owns; this
                // is where it comes apart again. Shown only when the security
                // really is held in more than one account — a single-account
                // position has nothing to break down.
                if (d != null && d.isSplit) {
                    Spacer(Modifier.height(12.dp))
                    WbDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenBreakdown(holding.securityKey) }
                            .padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Breakdown by account",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp
                            )
                            Text(
                                "Held in ${d.slices.size} accounts" +
                                    if (d.slices.map { h ->
                                            accountsForTax.firstOrNull { it.id == h.accountId }?.taxTreatment
                                        }.distinct().size > 1
                                    ) " with different tax treatments" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text("›", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // ── Tax ───────────────────────────────────────────────────────
            // Placed directly above Dividends because that is what it is about:
            // withholding is deducted before the money arrives and never shows
            // up as a line on a statement, so the yield figures below are gross
            // of a cost the reader cannot otherwise see.
            // Asked right here, on the screen the answer affects.
            //
            // The picker used to live on AccountScreen — which, it turns out,
            // nothing in the app navigates to. Routes.account() is defined and
            // never called, so that screen is unreachable and the setting was
            // impossible to find. Accounts are not surfaced anywhere else
            // either, so the holding is the only place the question can
            // sensibly be put to the user.
            // One block per account, because tax is the one thing that
            // genuinely differs between two slices of the same fund: the TFSA
            // slice keeps nothing it loses to withholding, the RRSP slice
            // loses nothing at all. A single-account position renders exactly
            // as it always did.
            val taxSlices = d?.slices?.takeIf { it.isNotEmpty() } ?: listOfNotNull(d?.holding)
            taxSlices.forEach { slice ->
            val sliceAccount = accountsForTax.firstOrNull { it.id == slice.accountId }
            val sliceLabel =
                if (d?.isSplit == true && sliceAccount != null) " in ${sliceAccount.displayName}" else ""

            val taxNotes = viewModel.taxNotesFor(
                holding = slice,
                annualDividendIncome = d?.trailingAnnualPerUnit?.times(slice.units)
            )
            val needsTaxSetup = viewModel.needsTaxTreatmentPrompt(slice)
            if (needsTaxSetup) {
                val accountId = slice.accountId
                SectionHeader("Tax$sliceLabel")
                WbCard(Modifier.padding(horizontal = WbDimens.ScreenPadding)) {
                    Text(
                        "How is ${sliceAccount?.displayName ?: "this account"} taxed?",
                        fontWeight = FontWeight.SemiBold, fontSize = 14.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Pick one and the app can tell you when tax is quietly being " +
                            "deducted from your dividends. Not sure? See \"How tax works " +
                            "here\" in the Menu.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    val residencyCode = viewModel.residency.collectAsStateWithLifecycle().value.code
                    ca.tristan.portfolio.data.db.TaxTreatment.values().forEach { t ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.setAccountTaxTreatment(accountId, t) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("○", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(t.label(), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                Text(
                                    ca.tristan.portfolio.tax.TaxRules.examplesFor(t, ca.tristan.portfolio.tax.Residency.fromCode(residencyCode)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
            // Estimated figures, shown only when the user has supplied a rate.
            //
            // Scaled to THIS slice's units: the trailing per-unit distribution
            // is a property of the fund, so the slice's share of the income is
            // simply its own unit count.
            val annualDivIncome = d?.trailingAnnualPerUnit?.times(slice.units)
            val divTax = viewModel.dividendTaxEstimateFor(slice, annualDivIncome)
            // The unrealized gain on THIS slice, not on the whole position —
            // the other accounts' gains are not taxable here, and two of them
            // may not be taxable anywhere.
            val sliceGain = slice.costBasis?.let { cost ->
                (d?.quote?.price ?: slice.lastKnownPrice ?: slice.manualPrice ?: 0.0) * slice.units - cost
            }
            val gainsTax = viewModel.capitalGainsTaxEstimateFor(slice, sliceGain)

            if (taxNotes.isNotEmpty() || divTax != null || gainsTax != null) {
                SectionHeader("Tax$sliceLabel")
                WbCard(Modifier.padding(horizontal = WbDimens.ScreenPadding)) {
                    if (divTax != null && divTax.totalTax > 0.0) {
                        StatPair(
                            "Dividend income (est.)", money(divTax.grossIncome),
                            "After tax", money(divTax.afterTax)
                        )
                        Spacer(Modifier.height(10.dp))
                        StatPair(
                            "Tax on dividends", money(divTax.totalTax),
                            "Effective rate", String.format("%.1f%%", divTax.effectiveRatePct)
                        )
                        if (divTax.withheld > 0.0 && divTax.domesticTax > 0.0) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "${money(divTax.withheld)} withheld abroad, " +
                                    "${money(divTax.domesticTax)} payable at home",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        WbDivider()
                        Spacer(Modifier.height(12.dp))
                    }
                    if (gainsTax != null && gainsTax > 0.0) {
                        StatPair(
                            "If you sold today", money(sliceGain ?: 0.0),
                            "Estimated tax", money(gainsTax)
                        )
                        Spacer(Modifier.height(12.dp))
                        WbDivider()
                        Spacer(Modifier.height(12.dp))
                    }
                    taxNotes.forEachIndexed { index, note ->
                        if (index > 0) {
                            Spacer(Modifier.height(12.dp))
                            WbDivider()
                            Spacer(Modifier.height(12.dp))
                        }
                        Row(verticalAlignment = Alignment.Top) {
                            Text(
                                if (note.severity == ca.tristan.portfolio.tax.TaxNote.Severity.WARNING)
                                    "⚠" else "ℹ",
                                fontSize = 14.sp,
                                color = if (note.severity == ca.tristan.portfolio.tax.TaxNote.Severity.WARNING)
                                    LossRed else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(note.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                note.estimatedAnnualCost?.let { cost ->
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        "About ${money(cost)} a year at the current payout",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = LossRed
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    note.detail,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        ca.tristan.portfolio.tax.TaxRules.DISCLAIMER,
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            }

            // ── Dividends ─────────────────────────────────────────────────
            SectionHeader("Dividends")
            WbCard(Modifier.padding(horizontal = WbDimens.ScreenPadding)) {
                StatPair(
                    "Yield (TTM)", pct(d?.yieldTtmPct),
                    "Yield on Cost", pct(d?.yieldOnCostPct)
                )
                Spacer(Modifier.height(14.dp))
                GrowthPair(
                    "Div Growth, 1 Year", d?.divGrowth1YPct, d,
                    "Div Growth, 5 Years", d?.divGrowth5YPct
                )
                Spacer(Modifier.height(14.dp))
                GrowthPair(
                    "Div Change, Recent" + (d?.divChangeRecentLabel?.let { " ($it)" } ?: ""),
                    d?.divChangeRecentPct, d,
                    "Div Change, vs 1Y Ago", d?.divChangeVs1YPct
                )
                Spacer(Modifier.height(14.dp))
                StatPair(
                    "Frequency", d?.frequencyLabel ?: "—",
                    // Named for the window it covers. It is the same twelve
                    // months as Yield (TTM) directly above, so the two can be
                    // checked against each other; it used to be the next
                    // payment × frequency, which for a seasonal payer
                    // contradicted the yield printed beside it.
                    "Annual / Unit (TTM)",
                    // Converted like everything else on this card. Printing
                    // this one in the listing's own currency put "US$1.06"
                    // directly above "CA$0.00 received" and "CA$302.65
                    // estimated" — three figures on one card, two of them in
                    // a different currency from the third, with nothing
                    // saying so.
                    d?.trailingAnnualPerUnit
                        ?.let { Money.perUnit(perUnitInActive(it), activeCurrency) } ?: "—"
                )
                Spacer(Modifier.height(14.dp))
                WbDivider()
                KeyValueRow(
                    "All-Time Dividends Received",
                    money(d?.allTimeReceived ?: 0.0)
                )
                KeyValueRow(
                    "Est. Annual Income (next 12m)",
                    d?.estimatedAnnualIncome?.let { money(it) } ?: "—"
                )
            }

            // ── Dividend history ──────────────────────────────────────────
            // The fund's own per-unit distribution record, by year. Sits above
            // the yield chart because "has this thing ever paid, and is the
            // payment growing" is the question you ask before "is the yield
            // high or low versus its own history".
            // Keyed on the rate as well as the events: the toggle restates
            // the whole screen, and a per-unit history left in the listing's
            // currency would be the one chart on the page still in kronor
            // while its own axis label said CA$.
            val dividendHistoryProjectionYears = 5
            val annualDivs = remember(d?.dividendEvents, d?.upcoming, fxRate, activeCurrency) {
                val events = d?.dividendEvents ?: emptyList()
                val actual = ca.tristan.portfolio.ui.HoldingDetailMath.annualDividends(events)
                    .map { (year, perUnit) -> Triple(year, perUnitInActive(perUnit), false) }

                // Extends the fund's own record with `dividendHistoryProjectionYears`
                // years projected from that same record. Uses DividendForecast
                // directly on the fund's own events (not d.projectedFlows, which
                // is already multiplied by position size) so these bars stay PER
                // UNIT, matching the actual years beside them.
                val projected: List<Triple<Int, Double, Boolean>> = if (events.isEmpty()) {
                    emptyList()
                } else {
                    val cal = Calendar.getInstance()
                    val now = System.currentTimeMillis()
                    val thisYear = cal.apply { timeInMillis = now }.get(Calendar.YEAR)
                    // annualDividends drops the current year while it's still in
                    // progress, so the projection picks up right where that left
                    // off — starting AT this year, not after it — keeping the
                    // bars one unbroken run of calendar years with no gap at "now".
                    val startYear = if (actual.any { it.first == thisYear }) thisYear + 1 else thisYear
                    val endYear = startYear + dividendHistoryProjectionYears - 1
                    val until = Calendar.getInstance().apply {
                        set(endYear + 1, Calendar.JANUARY, 1, 0, 0, 0)
                        set(Calendar.MILLISECOND, 0)
                    }.timeInMillis

                    // The current year is never in `actual` (annualDividends drops
                    // it while still in progress), so its bar has to be built
                    // here: what's already been paid this year, plus a projection
                    // for the rest of it. Projecting from `now` alone would miss
                    // real payments made earlier in the year, so start from Jan 1
                    // unless something has already been received this year — in
                    // which case starting at `now` avoids double-counting that
                    // real payment.
                    val receivedThisYearTotal = events
                        .filter { cal.apply { timeInMillis = it.first }.get(Calendar.YEAR) == thisYear }
                        .sumOf { it.second }
                    val receivedThisYear = receivedThisYearTotal > 0
                    val projectionStart = if (startYear == thisYear && !receivedThisYear) {
                        Calendar.getInstance().apply {
                            set(thisYear, Calendar.JANUARY, 1, 0, 0, 0)
                            set(Calendar.MILLISECOND, 0)
                        }.timeInMillis
                    } else now

                    val payments = ca.tristan.portfolio.data.DividendForecast.project(
                        history = events,
                        upcoming = d?.upcoming,
                        fromMillis = projectionStart,
                        untilMillis = until,
                        growthRate = null
                    )
                    val byYear = HashMap<Int, Double>()
                    if (startYear == thisYear) byYear[thisYear] = receivedThisYearTotal
                    for (payment in payments) {
                        val y = cal.apply { timeInMillis = payment.atMillis }.get(Calendar.YEAR)
                        if (y in startYear..endYear) byYear[y] = (byYear[y] ?: 0.0) + payment.perUnit
                    }
                    (startYear..endYear).map { y ->
                        Triple(y, perUnitInActive(byYear[y] ?: 0.0), true)
                    }
                }

                actual + projected
            }
            if (annualDivs.isNotEmpty()) {
                SectionHeader(
                    "Dividend History",
                    subtitle = "Distributions per unit — first paid ${annualDivs.first().first}"
                )
                WbCard(Modifier.padding(horizontal = WbDimens.ScreenPadding)) {
                    DividendHistoryChart(
                        yearly = annualDivs,
                        currencyCode = activeCurrency,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // ── Historical yield ──────────────────────────────────────────
            if (d != null && d.yieldHistory.size >= 5) {
                SectionHeader(
                    "Historical Dividend Yield",
                    subtitle = d.tenYearAvgYieldPct?.let {
                        "10-year average: ${pct(it)}"
                    }
                )
                WbCard(Modifier.padding(horizontal = WbDimens.ScreenPadding)) {
                    val current = d.yieldTtmPct
                    val avg = d.tenYearAvgYieldPct
                    if (current != null && avg != null && avg > 0) {
                        val delta = (current - avg) / avg * 100.0
                        StatusPill(
                            text = if (delta < -5)
                                "Yield ${pct(-delta, 1)} below its 10-year average"
                            else if (delta > 5)
                                "Yield ${pct(delta, 1)} above its 10-year average"
                            else "Yield in line with its 10-year average",
                            color = if (delta < -5) LossRed
                                    else if (delta > 5) GainGreen
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                    YieldHistoryChart(
                        points = d.yieldHistory,
                        averagePct = d.tenYearAvgYieldPct,
                        modifier = Modifier.fillMaxWidth().height(160.dp)
                    )
                    Spacer(Modifier.height(6.dp))
                    // Indented by the chart's y-axis gutter so the start date
                    // sits under the start of the line, not under the labels.
                    Row(
                        Modifier.fillMaxWidth().padding(start = 46.dp),
                        Arrangement.SpaceBetween
                    ) {
                        Text(
                            d.yieldHistory.firstOrNull()?.let { dateFmt.format(Date(it.atMs)) } ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            d.yieldHistory.lastOrNull()?.let { dateFmt.format(Date(it.atMs)) } ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // ── Payout schedule ───────────────────────────────────────────
            if (d != null && (d.receivedFlows.isNotEmpty() || d.projectedFlows.isNotEmpty())) {
                SectionHeader(
                    "Recent and Upcoming Dividends",
                    subtitle = "Received vs projected income from this holding"
                )
                WbCard(Modifier.padding(horizontal = WbDimens.ScreenPadding)) {
                    DividendBarChart(
                        bars = buildDividendBars(
                            // Both series converted with the same rate the
                            // rest of the screen uses, so the chart's axis
                            // agrees with the figures above it. The flows
                            // arrive in the holding's own currency.
                            received  = d.receivedFlows
                                .map { it.first to perUnitInActive(it.second) },
                            projected = d.projectedFlows
                                .map { it.first to perUnitInActive(it.second) },
                            period    = chartPeriod,
                            // Reinvestment compounds the unit count at the
                            // holding's own yield. This used to be fed the
                            // 5-year dividend CAGR, which is null for plenty
                            // of ETFs — the DRIP series then resolved to zero
                            // and the forward bars drew perfectly flat.
                            dripRate   = ((d?.yieldTtmPct ?: 0.0) / 100.0)
                                .coerceIn(0.0, 0.25)
                            // No growthRate here (defaults to 0.0): `projected`
                            // already covers the chart's whole horizon via
                            // `d.projectedFlows`, which is built with
                            // DividendForecast's own measured, damped growth
                            // (DISTRIBUTION_GROWTH_RATE = null). This parameter
                            // only extrapolates PAST that window, and passing
                            // the 5-year CAGR badge here too — floored at 0%,
                            // never damped — would double up on a rate this
                            // chart no longer needs.
                        ),
                        period = chartPeriod,
                        onPeriodChange = { chartPeriod = it },
                        currencyCode = activeCurrency,
                        title = if (chartPeriod == DividendPeriod.YEAR)
                            "Yearly Income" else "Monthly Income",
                        showDrip = chartPeriod == DividendPeriod.YEAR
                    )
                }
            }

            if (d != null && d.payouts.isNotEmpty()) {
                SectionHeader(
                    "Dividend Payout Schedule",
                    subtitle = "Dates are payment dates. Upcoming is forecast; past entries come from Dividends Received"
                )
                WbCard(
                    Modifier.padding(horizontal = WbDimens.ScreenPadding),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = WbDimens.CardPadding, vertical = 4.dp
                    )
                ) {
                    // Make the "why is there no history here" question answer
                    // itself, rather than looking like missing data.
                    if (d.payouts.none { !it.isUpcoming }) {
                        Text(
                            "No past payments recorded for this holding. " +
                                "Dividends paid before you added it aren't assumed — " +
                                "log them under Dividends Received if you got them.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 10.dp)
                        )
                    }
                    d.payouts.forEachIndexed { i, row ->
                        if (i > 0) WbDivider()
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                // The PAY date — this list is a payout
                                // schedule, so the date on it is the day the
                                // money lands. It used to print the ex-date,
                                // which for a Canadian ETF is most of a week
                                // earlier: XEQT's September 2026 distribution
                                // goes ex on the 24th and pays on the 29th.
                                Text(
                                    row.displayDateMs?.let { dateFmt.format(Date(it)) } ?: "—",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                // When the calendar published no pay date, the
                                // ex-date stands in — and says so, rather than
                                // being passed off as the payment date.
                                if (row.isExDateFallback) {
                                    Text(
                                        "ex-dividend date",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else if (row.isUpcoming && row.exDateMs != null) {
                                    Text(
                                        "ex-div ${dateFmt.format(Date(row.exDateMs))}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (row.isUpcoming) {
                                    Spacer(Modifier.height(3.dp))
                                    // Says whether the figure is the fund's own
                                    // declaration or the app's forecast —
                                    // "Upcoming" alone read the same for both.
                                    if (row.isAnnounced) StatusPill("Upcoming · Announced", GainGreen)
                                    else StatusPill("Upcoming · Estimated", Color(0xFFD97706))
                                }
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    money(row.totalForPosition),
                                    fontWeight = FontWeight.SemiBold
                                )
                                // Converted alongside the total above it. Left
                                // in the native currency it read as a
                                // per-unit figure that, multiplied by the
                                // unit count printed on the same card, did
                                // not come to the total directly above it.
                                Text(
                                    "${Money.perUnit(perUnitInActive(row.amountPerUnit), activeCurrency)} / unit",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // The Record dividend / Edit holding button pair that sat here has
            // moved into the top bar. Edit already had an icon up there, so the
            // pair was a second copy of the same control eating a full row at
            // the foot of every scroll; recording a dividend now has its own
            // icon alongside it.
            Spacer(Modifier.height(WbDimens.ScrollBottomGap))
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete holding?") },
            text = { Text("${holding.name} and its recorded dividends will be removed.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    viewModel.deleteHolding(holdingId)
                    onDeleted()
                }) { Text("Delete", color = LossRed) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

/**
 * Two-way pill switch between the user's default currency and the currency
 * this holding actually trades in. Only shown when the two differ — a CAD
 * holding for a CAD-default user has nothing to switch between.
 */
@Composable
private fun CurrencyToggle(
    nativeCurrency: String,
    baseCurrency: String,
    viewInBase: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(3.dp)
    ) {
        CurrencyToggleChip(baseCurrency, selected = viewInBase, onClick = { onChange(true) })
        CurrencyToggleChip(nativeCurrency, selected = !viewInBase, onClick = { onChange(false) })
    }
}

@Composable
private fun CurrencyToggleChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 6.dp)
    ) {
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = if (selected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Money + percent on one line, coloured by sign. */
@Composable
private fun ReturnLine(label: String, amount: String?, pctValue: Double?) {
    val color = when {
        pctValue == null -> MaterialTheme.colorScheme.onSurface
        pctValue >= 0    -> GainGreen
        else             -> LossRed
    }
    Column {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                amount ?: "—",
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                color = color
            )
            if (pctValue != null) {
                Spacer(Modifier.height(0.dp))
                Text(
                    "  (${signedPct(pctValue)})",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = color
                )
            }
        }
    }
}

/** Two growth metrics with their qualitative tags underneath. */
@Composable
private fun GrowthPair(
    leftLabel: String,
    leftPct: Double?,
    detail: HoldingDetail?,
    rightLabel: String,
    rightPct: Double?
) {
    Row(Modifier.fillMaxWidth()) {
        GrowthCell(leftLabel, leftPct, detail, Modifier.weight(1f))
        Spacer(Modifier.height(0.dp))
        GrowthCell(rightLabel, rightPct, detail, Modifier.weight(1f))
    }
}

@Composable
private fun GrowthCell(
    label: String,
    value: Double?,
    detail: HoldingDetail?,
    modifier: Modifier = Modifier
) {
    val color = when {
        value == null -> MaterialTheme.colorScheme.onSurface
        value >= 0    -> GainGreen
        else          -> LossRed
    }
    Column(modifier) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2
        )
        Spacer(Modifier.height(2.dp))
        Text(
            if (value == null) "—" else signedPct(value),
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = color
        )
        detail?.growthTag(value)?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = color)
        }
    }
}

/**
 * Per-unit distributions by calendar year, as a bar chart.
 *
 * Shows when a security started paying and how the annual distribution has
 * moved since. Bars are the fund's own record — per unit, not scaled by the
 * user's position — so the shape stays meaningful regardless of when the
 * holding was bought or how many units are held.
 *
 * The chart scrolls horizontally once there are more years than fit on a phone,
 * and opens parked on the most recent years.
 */
@Composable
private fun DividendHistoryChart(
    /** (year, perUnit, isProjected) — isProjected marks a forecast year, the
     *  fund's own record extended by DividendForecast, not an actual
     *  distribution, so it can be dimmed the same way a projected bar is
     *  everywhere else in the app. */
    yearly: List<Triple<Int, Double, Boolean>>,
    currencyCode: String,
    modifier: Modifier = Modifier
) {
    if (yearly.isEmpty()) return

    val barColor   = MaterialTheme.colorScheme.primary
    val gridColor  = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val valueColor = MaterialTheme.colorScheme.onSurface

    val maxV = yearly.maxOf { it.second }.coerceAtLeast(0.0001)
    val chartHeight = 150.dp
    val slotWidth   = 52.dp
    val scrollState = rememberScrollState()

    // Open on the most recent years — that's the end of the series people
    // look at first, and a long history would otherwise start scrolled to
    // payments made a decade ago.
    //
    // Keyed on maxValue as well as the series: on first composition the scroll
    // range is still 0 because layout hasn't run, so a one-shot scroll would
    // land nowhere. Re-running once the range is known fixes that.
    LaunchedEffect(yearly.size, scrollState.maxValue) {
        if (scrollState.maxValue > 0) scrollState.scrollTo(scrollState.maxValue)
    }

    Column(modifier) {
        // Year-over-year change on the two most recent complete ACTUAL years —
        // a projection compared against another projection, or against the
        // real prior year, is not a change the fund has actually delivered.
        val actual = yearly.filter { !it.third }
        if (actual.size >= 2) {
            val last = actual[actual.lastIndex].second
            val prev = actual[actual.lastIndex - 1].second
            if (prev > 0) {
                val chg = (last - prev) / prev * 100.0
                StatusPill(
                    text = "${actual.last().first}: ${signedPct(chg)} vs ${actual[actual.lastIndex - 1].first}",
                    color = if (chg >= 0) GainGreen else LossRed
                )
                Spacer(Modifier.height(10.dp))
            }
        }

        Row(Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .weight(1f)
                    .horizontalScroll(scrollState)
            ) {
                Column {
                    Row(
                        modifier = Modifier.height(chartHeight),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        yearly.forEach { (_, amount, isProjected) ->
                            DividendHistoryBar(
                                amount = amount,
                                maxValue = maxV,
                                barColor = barColor,
                                gridColor = gridColor,
                                valueColor = valueColor,
                                isProjected = isProjected,
                                modifier = Modifier.width(slotWidth).height(chartHeight)
                            )
                        }
                    }
                    Box(
                        Modifier
                            .width(slotWidth * yearly.size)
                            .height(1.dp)
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f))
                    )
                    Row {
                        yearly.forEach { (year, _, isProjected) ->
                            Row(
                                modifier = Modifier.width(slotWidth).padding(top = 6.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    year.toString(),
                                    fontSize = 10.sp,
                                    color = labelColor,
                                    maxLines = 1
                                )
                                if (isProjected) {
                                    Spacer(Modifier.width(3.dp))
                                    Box(
                                        Modifier
                                            .size(3.dp)
                                            .background(labelColor, shape = androidx.compose.foundation.shape.CircleShape)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        // The closing figure is the last ACTUAL year, not a projected one —
        // "2025 total" should never quietly turn into a forecast because five
        // more, dimmed bars were appended after it.
        val lastActual = actual.lastOrNull()
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
            Text(
                "First paid ${yearly.first().first}",
                style = MaterialTheme.typography.bodySmall,
                color = labelColor
            )
            if (lastActual != null) {
                Text(
                    "${lastActual.first} total " +
                        "${Money.perUnit(lastActual.second, currencyCode)} / unit",
                    style = MaterialTheme.typography.bodySmall,
                    color = labelColor
                )
            }
        }
    }
}

/** One year's bar, with the per-unit amount printed above it. */
@Composable
private fun DividendHistoryBar(
    amount: Double,
    maxValue: Double,
    barColor: Color,
    gridColor: Color,
    valueColor: Color,
    isProjected: Boolean = false,
    modifier: Modifier = Modifier
) {
    val measurer = rememberTextMeasurer()
    // Same dimming convention as every other projected bar in the app: a
    // lower opacity, not a different color, so a forecast reads as "this
    // chart, softened" rather than as an unrelated second series.
    val effectiveBarColor = if (isProjected) barColor.copy(alpha = 0.55f) else barColor
    val effectiveValueColor = if (isProjected) valueColor.copy(alpha = 0.75f) else valueColor
    val valueStyle = TextStyle(
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        color = effectiveValueColor
    )

    Canvas(modifier) {
        // Headroom for the printed value belongs at the TOP of the canvas,
        // above the tallest bar — which is where the value is drawn.
        //
        // It used to be subtracted from the bottom instead (`plotH = height -
        // labelRoom`, with bars sitting on `plotH`), which left 15dp of unused
        // space under the bars and none above them. The tallest bar therefore
        // reached the very top of the canvas, its label was pushed to a
        // negative y, and the coerceAtLeast(0f) that caught that stamped the
        // text at y=0 — directly on top of the bar. On AAPL that rendered the
        // 2025 figure as dark text on the dark bar (invisible) and clipped
        // 2024's against its own bar top.
        val labelRoom = 18.dp.toPx()
        val baseline  = size.height
        val plotH     = (size.height - labelRoom).coerceAtLeast(1f)

        for (s in 0..4) {
            val gy = baseline - plotH * s / 4f
            drawLine(gridColor, Offset(0f, gy), Offset(size.width, gy), strokeWidth = 1f)
        }

        if (amount <= 0.0) return@Canvas

        val barW = size.width * 0.55f
        val left = (size.width - barW) / 2f
        val bh = ((amount / maxValue) * plotH).toFloat().coerceAtLeast(1f)
        drawRect(
            effectiveBarColor,
            topLeft = Offset(left, baseline - bh),
            size = androidx.compose.ui.geometry.Size(barW, bh)
        )

        // Per-unit distributions are small numbers, so print more precision
        // than a money format would give ("0.34" beats "$0").
        val text = if (amount >= 1.0) String.format("%.2f", amount)
                   else String.format("%.3f", amount).trimEnd('0').trimEnd('.')
        val measured = measurer.measure(text, valueStyle)
        drawText(
            textMeasurer = measurer,
            text = text,
            topLeft = Offset(
                (size.width - measured.size.width) / 2f,
                (baseline - bh - measured.size.height - 2.dp.toPx()).coerceAtLeast(0f)
            ),
            style = valueStyle
        )
    }
}

/**
 * Trailing-yield line with a dashed average reference, mirroring how dividend
 * trackers show whether a holding is cheap or expensive versus its own history.
 */
@Composable
private fun YieldHistoryChart(
    points: List<ca.tristan.portfolio.ui.YieldPoint>,
    averagePct: Double?,
    modifier: Modifier = Modifier
) {
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f)
    val avgColor  = MaterialTheme.colorScheme.onSurfaceVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    val measurer = rememberTextMeasurer()
    val axisStyle = TextStyle(fontSize = 10.sp, color = labelColor)

    Canvas(modifier) {
        if (points.size < 2) return@Canvas
        val values = points.map { it.yieldPercent }
        val rawMin = values.min()
        val rawMax = values.max()
        // Pad the band slightly so the line never rides flush against the
        // top or bottom edge of the plot.
        val pad = ((rawMax - rawMin) * 0.08).coerceAtLeast(0.02)
        val minV = (rawMin - pad).coerceAtLeast(0.0)
        val maxV = rawMax + pad
        val range = (maxV - minV).let { if (it < 0.01) 1.0 else it }

        // Left gutter sized to the widest axis label, so the percentages have
        // somewhere to sit instead of being clipped off the canvas. Without
        // this the chart drew gridlines with no scale at all — the shape was
        // readable but the actual yield at any point was not.
        val sample = measurer.measure(String.format("%.2f%%", maxV), axisStyle)
        val leftPad = sample.size.width + 8.dp.toPx()
        val chartW = (size.width - leftPad).coerceAtLeast(1f)
        fun x(i: Int) = leftPad + i.toFloat() / (points.size - 1) * chartW
        fun y(v: Double) = (size.height - ((v - minV) / range * size.height)).toFloat()

        // Horizontal gridlines, each labelled with the yield it represents.
        for (s in 0..4) {
            val gy = size.height * s / 4f
            drawLine(gridColor, Offset(leftPad, gy), Offset(size.width, gy), strokeWidth = 0.8f)

            // s == 0 is the top of the plot, so the value counts down.
            val v = maxV - (range * s / 4.0)
            val label = measurer.measure(String.format("%.2f%%", v), axisStyle)
            // Nudge the first and last labels inward to keep them on canvas.
            val ly = (gy - label.size.height / 2f)
                .coerceIn(0f, size.height - label.size.height)
            drawText(
                textMeasurer = measurer,
                text = String.format("%.2f%%", v),
                topLeft = Offset(leftPad - label.size.width - 6.dp.toPx(), ly),
                style = axisStyle
            )
        }

        // 5-year average reference (dashed)
        if (averagePct != null && averagePct in minV..maxV) {
            val ay = y(averagePct)
            var dx = leftPad
            while (dx < size.width) {
                drawLine(
                    avgColor,
                    Offset(dx, ay),
                    Offset(minOf(dx + 6.dp.toPx(), size.width), ay),
                    strokeWidth = 1.2f
                )
                dx += 11.dp.toPx()
            }
        }

        val path = Path()
        points.forEachIndexed { i, p ->
            val px = x(i); val py = y(p.yieldPercent)
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        drawPath(
            path,
            color = lineColor,
            style = Stroke(width = 2.2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        // Marker on the latest point
        drawCircle(
            color = lineColor,
            radius = 3.5.dp.toPx(),
            center = Offset(x(points.lastIndex), y(values.last()))
        )
    }
}
