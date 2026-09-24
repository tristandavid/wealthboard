package ca.tristan.portfolio.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ca.tristan.portfolio.data.db.HoldingType
import ca.tristan.portfolio.data.db.TransactionType
import ca.tristan.portfolio.ui.PortfolioViewModel
import ca.tristan.portfolio.ui.securityKey
import ca.tristan.portfolio.ui.components.TickerLogo
import ca.tristan.portfolio.ui.components.WbCard
import ca.tristan.portfolio.ui.components.WbDateField
import ca.tristan.portfolio.ui.components.WbDimens
import ca.tristan.portfolio.ui.components.WealthBoardTopBar
import ca.tristan.portfolio.ui.format.Money
import ca.tristan.portfolio.ui.format.TickerFlag
import ca.tristan.portfolio.ui.theme.GainGreen
import ca.tristan.portfolio.ui.theme.LossRed


/**
 * Records a BUY, SELL or DRIP against a holding.
 *
 * This replaces typing a position size directly: a portfolio built from
 * transactions can show how it got where it is, and gives cost basis a real
 * provenance instead of a number the user had to work out themselves. An
 * unknown ticker creates the holding on the fly, so the first buy of something
 * new is still a single step.
 */
@Composable
fun AddTransactionScreen(
    viewModel: PortfolioViewModel,
    accountId: Long?,
    presetHoldingId: Long? = null,
    onDone: () -> Unit,
    /** Opens the paywall when the free account limit is hit in the picker. */
    onOpenPremium: () -> Unit = {}
) {
    val holdings by viewModel.holdings.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val residency by viewModel.residency.collectAsStateWithLifecycle()
    // Only consulted when this transaction creates a NEW holding. Selling or
    // reinvesting an existing one must not move it between accounts — that
    // would change its tax treatment as a side effect of recording a trade.
    // Nullable on purpose. It used to fall back to 0L, which is not a real
    // account id — the row then landed against whatever the repository invented,
    // with no tax treatment set, and a holding with no treatment reports no tax
    // at all. Nothing is chosen on the user's behalf now.
    var selectedAccountId by remember(accountId, accounts) {
        mutableStateOf(accountId ?: accounts.firstOrNull()?.id)
    }
    val searchResults by viewModel.searchResults.collectAsStateWithLifecycle()
    val baseCurrency by viewModel.baseCurrency.collectAsStateWithLifecycle()

    var type by remember { mutableStateOf(TransactionType.BUY) }
    // What is being traded is a SECURITY; which account it lands in is chosen
    // separately, below. They used to be one choice — pick a stored row and the
    // account came with it — which is why the same fund appeared three times in
    // the picker and why buying it in a fourth account looked impossible.
    var selectedSecurityKey by remember(presetHoldingId) { mutableStateOf<String?>(null) }
    var tickerQuery by remember { mutableStateOf("") }
    // The query the user has already resolved by tapping a suggestion. See the
    // showSuggestions comment below for why this is a value and not a flag.
    var pickedTicker by remember { mutableStateOf<String?>(null) }
    var newTickerName by remember { mutableStateOf<String?>(null) }
    // Deliberately null until something tells us what this is. It used to
    // default to ETF, so a ticker typed without picking a suggestion — or
    // picked from a provider whose type labels we didn't recognise — was filed
    // as an ETF regardless. That is why AAPL showed "Holding Type: ETF".
    var newTickerType by remember { mutableStateOf<HoldingType?>(null) }
    var newTickerCurrency by remember { mutableStateOf<String?>(null) }

    var sharesText by remember { mutableStateOf("") }
    var priceText by remember { mutableStateOf("") }
    var dateMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    var showHoldingPicker by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // Live market price shown as a read-only hint below the price field
    var livePrice by remember { mutableStateOf<Double?>(null) }
    var livePriceFetching by remember { mutableStateOf(false) }
    // Tracks the last ticker explicitly chosen from the search dropdown
    var selectedNewTicker by remember { mutableStateOf<String?>(null) }

    // Arriving from a holding: adopt both its security and its account, so the
    // form opens on the row the user tapped.
    LaunchedEffect(presetHoldingId, holdings) {
        val preset = presetHoldingId?.let { id -> holdings.firstOrNull { it.id == id } }
            ?: return@LaunchedEffect
        if (selectedSecurityKey == null) {
            selectedSecurityKey = preset.securityKey
            selectedAccountId = preset.accountId
        }
    }

    // Every account's slice of the chosen security, and the row this entry will
    // actually be recorded against — null when the chosen account holds none of
    // it yet, which for a buy means "start it here".
    val securitySlices = holdings.filter { it.securityKey == selectedSecurityKey }
    val selected = securitySlices.maxByOrNull { it.units }
    val targetHolding = securitySlices.firstOrNull { it.accountId == selectedAccountId }
    // The list stays shut for as long as the field still holds the symbol the
    // user picked. The previous one-shot boolean cleared itself inside
    // LaunchedEffect(tickerQuery), which made selection take two taps for any
    // result whose symbol differed from what was typed: the first tap changed
    // the query, which re-ran the effect, which cleared the flag while
    // searchResults still held the old matches — so the list reopened the
    // instant it closed. Typing a US symbol in full and tapping the identical
    // row left the key unchanged and worked first time, which is why this only
    // ever bit on Canadian and international tickers (type "VEQT", the match
    // is "VEQT.TO").
    val showSuggestions =
        tickerQuery.isNotBlank() && searchResults.isNotEmpty() && tickerQuery != pickedTicker

    LaunchedEffect(tickerQuery) {
        // A query the user just chose from the list is already resolved — the
        // click handler set the name, type and selected ticker itself, and
        // re-running this would wipe them.
        if (tickerQuery == pickedTicker) return@LaunchedEffect
        // The field has moved off the picked symbol, so that pick is spent —
        // forgetting it here means retyping the same symbol later still
        // searches instead of silently showing nothing.
        pickedTicker = null
        // User is typing a new search — clear any stale price hint
        livePrice = null
        livePriceFetching = false
        newTickerCurrency = null
        newTickerType = null
        viewModel.searchQuotes(tickerQuery)
    }

    // A ticker can be typed in full and submitted without ever touching the
    // suggestion list, which used to leave the currency at its CAD default and
    // the type at its ETF default. Resolve anything that looks like a complete
    // symbol, so the form is right either way.
    LaunchedEffect(tickerQuery, selected) {
        if (selected != null) return@LaunchedEffect
        val t = tickerQuery.trim()
        if (t.length < 2 || t == selectedNewTicker) return@LaunchedEffect
        kotlinx.coroutines.delay(600)
        if (t == tickerQuery.trim()) selectedNewTicker = t
    }

    // When an existing holding is chosen, fetch its live price for reference.
    // Keyed on the security rather than the account's row: the price of XEQT is
    // the price of XEQT whichever account it sits in.
    LaunchedEffect(selected?.id) {
        val id = selected?.id ?: return@LaunchedEffect
        livePrice = null
        selectedNewTicker = null
        livePriceFetching = true
        val fetched = viewModel.priceForHolding(id)
        livePriceFetching = false
        livePrice = fetched
        if (priceText.isBlank()) {
            fetched?.let { priceText = String.format("%.2f", it) }
        }
    }

    // When a new ticker is chosen — from the dropdown or just typed — look it
    // up. The quote settles three things at once: the price, the currency the
    // listing trades in, and what kind of instrument it is.
    LaunchedEffect(selectedNewTicker) {
        val ticker = selectedNewTicker ?: return@LaunchedEffect
        livePrice = null
        livePriceFetching = true
        val quote = viewModel.quoteForTicker(ticker)
        livePriceFetching = false
        livePrice = quote?.price
        quote?.currency?.uppercase()?.let { newTickerCurrency = it }
        // The quote's own classification beats the search result's, and beats
        // any guess: this is the exchange telling us what the symbol is.
        HoldingType.fromQuoteType(quote?.instrumentType)?.let { newTickerType = it }
        if (quote?.name != null && newTickerName == null) newTickerName = quote.name
    }

    val shares = sharesText.toDoubleOrNull() ?: 0.0
    val price = priceText.toDoubleOrNull() ?: 0.0
    val total = shares * price

    // The currency of the thing being bought, in order of confidence: the
    // holding's own stored currency, the currency the quote reported, then the
    // exchange suffix on the ticker. Falling back to "CAD" for anything not
    // already held was what labelled a US listing's price field CAD.
    val currency = selected?.currency?.uppercase()
        ?: newTickerCurrency
        ?: tickerQuery.takeIf { it.isNotBlank() }?.let { viewModel.currencyForTicker(it) }
        ?: baseCurrency

    // Recomposes when a rate lands; the FX cache itself isn't observable.
    val fxTick by viewModel.fxTick.collectAsStateWithLifecycle()
    LaunchedEffect(currency) { viewModel.ensureRateFor(currency) }

    val fxRate = remember(currency, fxTick, baseCurrency) { viewModel.rateToBase(currency) }

    /** "≈ CA$451.28" for an amount quoted in the listing's own currency. */
    fun convertedLabel(amount: Double): String? {
        if (fxRate == null || amount <= 0.0) return null
        return "≈ ${Money.format(amount * fxRate, baseCurrency)}"
    }

    Scaffold(
        topBar = {
            WealthBoardTopBar(
                title = "Add Transaction",
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
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
                .padding(WbDimens.ScreenPadding)
        ) {

            // ── BUY / SELL / DRIP ─────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(3.dp)
            ) {
                TransactionType.values().forEach { t ->
                    val on = t == type
                    val tint = when (t) {
                        TransactionType.BUY  -> GainGreen
                        TransactionType.SELL -> LossRed
                        TransactionType.DRIP -> MaterialTheme.colorScheme.primary
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (on) tint else Color.Transparent)
                            .clickable { type = t }
                            .padding(vertical = 11.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            t.label().uppercase(),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (on) Color.White
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── Holding ───────────────────────────────────────────────────
            WbCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showHoldingPicker = true },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Holding", modifier = Modifier.weight(1f),
                        fontWeight = FontWeight.Medium)
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            selected?.ticker ?: selected?.name ?: "Select…",
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            selected?.name ?: "Tap to choose or search a new ticker",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Searching a ticker not yet held creates the holding on first buy.
            if (selected == null) {
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = tickerQuery,
                    onValueChange = { tickerQuery = it.uppercase() },
                    label = { Text("Or search a new ticker") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (showSuggestions) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Column(
                            modifier = Modifier
                                .heightIn(max = 220.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            searchResults.take(10).forEach { r ->
                                val logoUrl by produceState<String?>(null, r.symbol) {
                                    value = viewModel.logoUrlFor(r.symbol, r.name)
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            tickerQuery = r.symbol
                                            selectedNewTicker = r.symbol
                                            newTickerName = r.name ?: r.symbol
                                            // One shared mapping that understands
                                            // both providers' vocabularies, and
                                            // leaves the type unset rather than
                                            // guessing "ETF" for anything it does
                                            // not recognise.
                                            newTickerType = HoldingType.fromQuoteType(r.quoteType)
                                            newTickerCurrency = null
                                            pickedTicker = r.symbol
                                        }
                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    TickerLogo(logoUrl = logoUrl, label = r.symbol, size = 32.dp)
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(r.symbol, fontWeight = FontWeight.SemiBold)
                                            val flag = TickerFlag.forTicker(r.symbol, r.exchange)
                                            if (flag.isNotEmpty()) Text(flag, fontSize = 14.sp)
                                        }
                                        Text(
                                            listOfNotNull(r.name, r.exchange).joinToString(" · "),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── Date / shares / price ─────────────────────────────────────
            WbCard {
                // The app's one date control, shared with Add Dividend and
                // with the iOS build.
                WbDateField(
                    label = "Date",
                    millis = dateMillis,
                    onChange = { dateMillis = it },
                    maxMillis = System.currentTimeMillis()
                )
                Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = sharesText,
                    onValueChange = { sharesText = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Number of shares") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Decimal
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = priceText,
                    onValueChange = { priceText = it.filter { c -> c.isDigit() || c == '.' } },
                    // Named for the currency the listing actually trades in, so
                    // the number typed here means what the user thinks it means.
                    label = { Text("Price per share, $currency") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Decimal
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                // Grayed market-price reference hint, in the listing's currency
                // with the reporting-currency equivalent underneath it.
                when {
                    livePriceFetching -> {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Fetching market price…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                    livePrice != null -> {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Market price: ${Money.format(livePrice!!, currency)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                        convertedLabel(livePrice!!)?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
                // What the price just typed is worth in the reporting currency,
                // so a US buy can be sanity-checked against a Canadian budget
                // without leaving the form.
                if (price > 0 && fxRate != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${Money.format(price, currency)} ${convertedLabel(price)} " +
                            "· 1 $currency = ${String.format("%.4f", fxRate)} $baseCurrency",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (total > 0) {
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                        Text("Total", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Column(horizontalAlignment = Alignment.End) {
                            Text(Money.format(total, currency), fontWeight = FontWeight.Bold)
                            convertedLabel(total)?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // Against the SLICE being sold, not the whole position: holding
            // 1,500 units across three accounts does not mean 1,500 can be sold
            // out of the TFSA.
            val sellable = targetHolding?.units ?: 0.0
            if (type == TransactionType.SELL && selected != null && shares > sellable) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "You only hold ${String.format("%,.4f", sellable)} units of " +
                        "${selected.ticker ?: selected.name} in that account.",
                    color = LossRed,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            // The account row is ALWAYS on screen.
            //
            // It used to appear only once a new ticker had been typed, which
            // made it effectively invisible: the Portfolio "+" lands here, and
            // someone looking for the setting saw an empty ticker field and no
            // account row at all. A control that exists only after an unrelated
            // field is filled in is a control nobody finds.
            //
            // And it is now where the trade's destination is decided, for a
            // held security as much as a new one. Every account is listed with
            // what it already holds of this security — zero included, because
            // an account holding none of it is still a place you can buy it,
            // and that was previously reachable only by creating an account
            // that already existed.
            //
            // Recording a trade still cannot MOVE a position between accounts:
            // each account keeps its own row, its own cost basis and its own
            // tax treatment. Choosing a different one here records the trade
            // there; it does not relocate anything.
            //
            // Hidden entirely where the user's country has no tax-advantaged
            // accounts to sort holdings into. In that case every account is
            // taxable, so the picker offers a choice with no consequence — and
            // asking someone to classify something their tax system does not
            // distinguish is worse than not asking.
            val showAccountRow = residency.hasRegisteredAccounts
            if (showAccountRow) {
                Spacer(Modifier.height(16.dp))
                if (accounts.isEmpty()) {
                    Text(
                        "Make an account first — whether it's taxable, tax-free or " +
                            "tax-deferred is what decides every tax figure the app shows " +
                            "for what's in it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
                val securityLabel = selected?.ticker ?: selected?.name

                // Worked out here as plain values rather than passed as
                // lambdas: see the note on AccountPicker's parameters for the
                // crash that cost.
                val unitsByAccount: Map<Long, Double>? =
                    if (securityLabel == null) null
                    else accounts.associate { account ->
                        account.id to (
                            securitySlices.firstOrNull { it.accountId == account.id }?.units ?: 0.0
                            )
                    }

                // You cannot sell or reinvest what is not there. Those accounts
                // stay visible, dimmed, rather than vanishing — a disappearing
                // account reads as a bug.
                val disabledAccountIds: Set<Long> =
                    if (securityLabel == null || type == TransactionType.BUY) emptySet()
                    else accounts
                        .filter { account ->
                            securitySlices.none { it.accountId == account.id && it.units > 0 }
                        }
                        .map { it.id }
                        .toSet()

                val accountHint: String? = when {
                    securityLabel == null -> null
                    type == TransactionType.BUY ->
                        "Where this buy lands. The figure is what you already hold of $securityLabel there."
                    else -> "Which slice of $securityLabel this is out of."
                }

                ca.tristan.portfolio.ui.components.AccountPicker(
                    accounts = accounts,
                    selectedId = selectedAccountId,
                    residencyCode = residency.code,
                    onSelect = { selectedAccountId = it },
                    onCreate = { newName, treatment ->
                        viewModel.createAccount(newName, treatment) { newId ->
                            selectedAccountId = newId
                        }
                    },
                    onChangeTreatment = { id, t -> viewModel.setAccountTaxTreatment(id, t) },
                    unitsByAccount = unitsByAccount,
                    disabledAccountIds = disabledAccountIds,
                    hint = accountHint,
                    onUpgrade = onOpenPremium
                )
            }

            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = LossRed, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    error = null
                    if (shares <= 0.0) { error = "Enter the number of shares."; return@Button }
                    if (price <= 0.0)  { error = "Enter a price per share."; return@Button }

                    val existing = targetHolding
                    val security = selected
                    if (existing != null) {
                        viewModel.addTransaction(existing.id, type, dateMillis, shares, price)
                        onDone()
                    } else if (security != null) {
                        // A held security, in an account that has none of it
                        // yet. For a buy that is a new slice of the same
                        // position; for anything else there is nothing to act
                        // on and saying so beats a silent no-op.
                        if (type != TransactionType.BUY) {
                            error = "You don't hold ${security.ticker ?: security.name} in that account."
                            return@Button
                        }
                        val targetAccount = selectedAccountId
                        if (targetAccount == null) {
                            error = "Choose an account first — it decides how this holding is taxed."
                            return@Button
                        }
                        viewModel.addSliceThenTransact(
                            fromHoldingId = security.id,
                            accountId = targetAccount,
                            txType = type,
                            atMillis = dateMillis,
                            shares = shares,
                            pricePerShare = price
                        )
                        onDone()
                    } else if (tickerQuery.isNotBlank()) {
                        if (type != TransactionType.BUY) {
                            error = "Pick an existing holding to sell or reinvest."
                            return@Button
                        }
                        // First buy of something new: create the position at
                        // zero and let the transaction fill it, so units and
                        // cost basis come from one code path.
                        val targetAccount = selectedAccountId
                        if (targetAccount == null) {
                            error = "Choose an account first — it decides how this holding is taxed."
                            return@Button
                        }
                        viewModel.createHoldingThenTransact(
                            accountId = targetAccount,
                            ticker = tickerQuery,
                            name = newTickerName ?: tickerQuery,
                            // Falls back to STOCK rather than ETF when nothing
                            // resolved the type: a plain equity is the far more
                            // common hand-typed ticker, and a refreshed quote
                            // corrects either way on the next price update.
                            type = newTickerType ?: HoldingType.STOCK,
                            txType = type,
                            atMillis = dateMillis,
                            shares = shares,
                            pricePerShare = price
                        )
                        onDone()
                    } else {
                        error = "Choose a holding or search a ticker."
                    }
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(26.dp)
            ) {
                Text("ADD ${type.label().uppercase()}", fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showHoldingPicker) {
        // One row per security, not one per stored holding.
        //
        // The same fund bought in three accounts is three rows in the database,
        // because cost basis and tax treatment are per account — but it is one
        // thing the user owns, and listing it three times over with nothing but
        // a unit count to tell the entries apart made the picker unanswerable.
        //
        // Which account the trade belongs to is asked once, by the account
        // chips on the form, where every account is visible — including the
        // ones this security is not in yet.
        val grouped = remember(holdings) {
            holdings
                .groupBy { it.securityKey }
                .map { (key, rows) -> key to rows.sortedByDescending { it.units } }
                .sortedByDescending { (_, rows) -> rows.sumOf { it.units } }
        }

        AlertDialog(
            onDismissRequest = { showHoldingPicker = false },
            title = { Text("Choose holding") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    if (grouped.isEmpty()) {
                        Text(
                            "No holdings yet — search a ticker to record your first buy.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } else {
                        // The way back out of a selection: without it, choosing
                        // a holding by mistake hid the ticker search for good.
                        Text(
                            "Search a new ticker instead",
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedSecurityKey = null
                                    priceText = ""
                                    showHoldingPicker = false
                                }
                                .padding(vertical = 11.dp)
                        )
                        Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    }
                    grouped.forEach { (key, rows) ->
                        val principal = rows.first()
                        val totalUnits = rows.sumOf { it.units }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedSecurityKey = key
                                    priceText = ""
                                    // Land on an account that actually holds
                                    // it, unless the current choice already
                                    // does — otherwise the form opens claiming
                                    // a slice the user never picked.
                                    if (rows.none { it.accountId == selectedAccountId }) {
                                        selectedAccountId = principal.accountId
                                    }
                                    showHoldingPicker = false
                                }
                                .padding(vertical = 11.dp)
                        ) {
                            Text(
                                principal.ticker ?: principal.name,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "${principal.name} · ${String.format("%,.4f", totalUnits)} units" +
                                    if (rows.size > 1) " · ${rows.size} accounts" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showHoldingPicker = false }) { Text("Close") }
            }
        )
    }
}
