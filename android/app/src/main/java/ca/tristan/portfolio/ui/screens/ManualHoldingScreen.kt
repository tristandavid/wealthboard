package ca.tristan.portfolio.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ca.tristan.portfolio.data.QuotePreview
import ca.tristan.portfolio.data.db.HoldingType
import ca.tristan.portfolio.ui.PortfolioViewModel
import kotlinx.coroutines.delay

// Maps a provider's search quoteType onto our own HoldingType, for autofill
// when a ticker suggestion is picked. The mapping now lives on HoldingType
// itself, because three screens each had their own copy and all three only
// understood Yahoo's spelling — Finnhub's "Common Stock" matched none of them.
private fun holdingTypeForQuoteType(quoteType: String?): HoldingType? =
    HoldingType.fromQuoteType(quoteType)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualHoldingScreen(
    viewModel: PortfolioViewModel,
    accountId: Long?,
    editingHoldingId: Long?,
    onDone: () -> Unit,
    /** Opens the paywall when the free account limit is hit in the picker. */
    onOpenPremium: () -> Unit = {}
) {
    val holdings by viewModel.holdings.collectAsStateWithLifecycle()
    val existing = editingHoldingId?.let { id -> holdings.firstOrNull { it.id == id } }
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val residency by viewModel.residency.collectAsStateWithLifecycle()

    // Which account this holding belongs to.
    //
    // Editing keeps the holding where it is; a new holding defaults to whatever
    // the caller passed, then to the first account. The state is seeded from
    // those rather than forced, so a user who never touches the picker gets
    // exactly the old behaviour.
    var selectedAccountId by remember(existing, accountId, accounts) {
        mutableStateOf(
            // Nullable: 0L is not a real account, and a holding filed against
            // one reports no tax at all.
            existing?.accountId ?: accountId ?: accounts.firstOrNull()?.id
        )
    }

    var name by remember(existing) { mutableStateOf(existing?.name ?: "") }
    var ticker by remember(existing) { mutableStateOf(existing?.ticker ?: "") }
    var type by remember(existing) { mutableStateOf(existing?.type ?: HoldingType.ETF) }
    var units by remember(existing) { mutableStateOf(existing?.units?.toString() ?: "") }
    var manualPrice by remember(existing) { mutableStateOf(existing?.manualPrice?.toString() ?: "") }
    var costBasis by remember(existing) { mutableStateOf(existing?.costBasis?.toString() ?: "") }
    var typeMenuExpanded by remember { mutableStateOf(false) }

    var preview by remember { mutableStateOf<QuotePreview?>(null) }
    val focusManager = LocalFocusManager.current

    // Search-as-you-type ticker suggestions (stocks, ETFs, indices, futures,
    // forex, crypto — everything the same search backing the Dashboard's Add
    // a quote dialog returns).
    //
    // `pickedTicker` remembers which exact query the user has already resolved
    // by tapping a suggestion, and the list stays shut for as long as the
    // field still holds it. It replaces a one-shot boolean that cleared itself
    // inside LaunchedEffect(ticker), which needed TWO taps to select anything
    // whose symbol differed from what was typed:
    //
    //   tap → ticker becomes "VEQT.TO", flag set, list hides
    //        → the ticker key changed, so the effect re-ran and cleared the
    //          flag, while searchResults still held the matches for "VEQT"
    //        → list reopened immediately, looking like the tap did nothing
    //   tap again → ticker is unchanged this time, so the effect never runs,
    //               the flag survives and the list finally stays shut.
    //
    // Typing a US symbol in full ("AAPL") and tapping the identical result
    // left the key unchanged and worked first time, which is exactly why this
    // only ever showed up on Canadian and international tickers, where the
    // match carries an exchange suffix the user did not type.
    val tickerResults by viewModel.searchResults.collectAsStateWithLifecycle()
    var pickedTicker by remember { mutableStateOf<String?>(null) }
    val showSuggestions =
        ticker.isNotBlank() && tickerResults.isNotEmpty() && ticker != pickedTicker

    LaunchedEffect(ticker) {
        // Nothing to search for a query the user just chose from this list.
        if (ticker == pickedTicker) return@LaunchedEffect
        // The field has moved off the picked symbol, so that pick is spent.
        pickedTicker = null
        viewModel.searchQuotes(ticker)
    }

    // Debounced (400ms) live Yahoo lookup as soon as a ticker is typed for an
    // ETF/stock/crypto, so there's on-screen confirmation before saving
    // rather than only finding out the price was wired up correctly after
    // the fact.
    LaunchedEffect(ticker, type) {
        if (ticker.isBlank() || (type != HoldingType.ETF && type != HoldingType.STOCK && type != HoldingType.CRYPTO)) {
            preview = null
            return@LaunchedEffect
        }
        delay(400)
        preview = viewModel.previewQuote(ticker)
    }

    Scaffold(
        topBar = {
            ca.tristan.portfolio.ui.components.WealthBoardTopBar(
                title = if (existing != null) "Edit holding" else "Add holding",
                navigationIcon = {
                    IconButton(onClick = onDone) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            // Name is auto-filled when the user picks a ticker from the autocomplete dropdown.
            // No manual Name field — the ticker search is the primary entry point.

            // Ticker field + inline suggestion list.
            //
            // This is deliberately NOT a DropdownMenu / ExposedDropdownMenu:
            // those render in a Popup window, which on this screen kept getting
            // positioned over the top app bar instead of under the field. Laying
            // the list out inline in the Column guarantees it always sits
            // directly beneath the ticker box and scrolls with the form.
            OutlinedTextField(
                value = ticker,
                onValueChange = { ticker = it.uppercase() },
                label = { Text("Ticker (optional for funds)") },
                singleLine = true,
                shape = RoundedCornerShape(
                    topStart = 4.dp, topEnd = 4.dp,
                    bottomStart = if (showSuggestions) 0.dp else 4.dp,
                    bottomEnd   = if (showSuggestions) 0.dp else 4.dp
                ),
                modifier = Modifier.fillMaxWidth()
            )

            if (showSuggestions) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    tonalElevation = 3.dp,
                    shadowElevation = 4.dp,
                    shape = RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    // Cap the height so a long result list can't push the Save
                    // button off screen; the list scrolls inside the cap.
                    // A plain scrolling Column (not LazyColumn) — the list is
                    // capped at 12 items, and a lazy list nested in a Column
                    // measures badly.
                    Column(
                        modifier = Modifier
                            .heightIn(max = 240.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        tickerResults.take(12).forEach { result ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        ticker = result.symbol
                                        if (name.isBlank()) result.name?.let { name = it }
                                        holdingTypeForQuoteType(result.quoteType)?.let { type = it }
                                        pickedTicker = result.symbol
                                        focusManager.clearFocus()
                                    }
                                    .padding(horizontal = 14.dp, vertical = 10.dp)
                            ) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(result.symbol, fontWeight = FontWeight.SemiBold)
                                        result.quoteType?.let {
                                            Text(
                                                "  $it",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    val subtitle = listOfNotNull(result.name, result.exchange).joinToString(" · ")
                                    if (subtitle.isNotBlank()) {
                                        Text(
                                            subtitle,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                            Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        }
                    }
                }
            }

            preview?.let { p ->
                Spacer(Modifier.height(4.dp))
                if (p.found && p.price != null) {
                    Text("Live now: \$${String.format("%.2f", p.price)}" + (p.name?.let { " · $it" } ?: "") + " — leave Price blank to keep tracking this automatically")
                } else {
                    Text("Couldn't find that ticker")
                }
            }

            Spacer(Modifier.height(8.dp))
            ExposedDropdownMenuBox(expanded = typeMenuExpanded, onExpandedChange = { typeMenuExpanded = it }) {
                OutlinedTextField(
                    value = type.label(),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Type") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeMenuExpanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor()
                )
                DropdownMenu(expanded = typeMenuExpanded, onDismissRequest = { typeMenuExpanded = false }) {
                    HoldingType.values().forEach { t ->
                        DropdownMenuItem(text = { Text(t.label()) }, onClick = { type = t; typeMenuExpanded = false })
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = units, onValueChange = { units = it }, label = { Text("Units / shares") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = manualPrice, onValueChange = { manualPrice = it }, label = { Text("Price (leave blank for live/NAV)") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = costBasis, onValueChange = { costBasis = it }, label = { Text("Cost basis (optional, for yield on cost)") }, modifier = Modifier.fillMaxWidth())

            // Same rule as Add Transaction: no registered accounts in this
            // country means no meaningful choice to present.
            if (residency.hasRegisteredAccounts) {
            Spacer(Modifier.height(16.dp))
            ca.tristan.portfolio.ui.components.AccountPicker(
                accounts = accounts,
                selectedId = selectedAccountId,
                residencyCode = residency.code,
                onSelect = { selectedAccountId = it },
                onCreate = { newName, treatment ->
                    // Select the new account as soon as it exists, so the
                    // holding being entered lands in the one just created
                    // rather than silently staying in the old default.
                    viewModel.createAccount(newName, treatment) { newId ->
                        selectedAccountId = newId
                    }
                },
                onChangeTreatment = { id, t -> viewModel.setAccountTaxTreatment(id, t) },
                onUpgrade = onOpenPremium
            )
            }

            Spacer(Modifier.height(16.dp))
            Button(
                enabled = selectedAccountId != null,
                onClick = {
                    val targetAccount = selectedAccountId ?: return@Button
                    viewModel.saveHolding(
                        existingId = editingHoldingId,
                        accountId = targetAccount,
                        name = name,
                        ticker = ticker.ifBlank { null },
                        type = type,
                        units = units.toDoubleOrNull() ?: 0.0,
                        manualPrice = manualPrice.toDoubleOrNull(),
                        // Derived from the ticker's exchange rather than
                        // defaulted to CAD, so US listings save as USD.
                        currency = viewModel.repository.currencyForTicker(
                            ticker.ifBlank { null }, "CAD"
                        ),
                        costBasis = costBasis.toDoubleOrNull(),
                        onDone = onDone
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (existing != null) "Save changes" else "Save holding")
            }
        }
    }
}
