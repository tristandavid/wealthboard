package ca.tristan.portfolio.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ca.tristan.portfolio.ui.AccountPosition
import ca.tristan.portfolio.ui.PortfolioViewModel
import ca.tristan.portfolio.ui.SecurityPosition
import ca.tristan.portfolio.ui.components.KeyValueRow
import ca.tristan.portfolio.ui.components.StatusPill
import ca.tristan.portfolio.ui.components.WbCard
import ca.tristan.portfolio.ui.components.WbDimens
import ca.tristan.portfolio.ui.components.WbDivider
import ca.tristan.portfolio.ui.components.WealthBoardTopBar
import ca.tristan.portfolio.ui.format.Money
import ca.tristan.portfolio.ui.theme.GainGreen
import ca.tristan.portfolio.ui.theme.LossRed

/**
 * How one position splits across the accounts holding it.
 *
 * The figures the holding screen shows are the whole position, because that is
 * what the user owns. This is where those figures come apart again: what each
 * account paid, what it is worth now, and how it is taxed — which is the one
 * thing that genuinely differs between a TFSA slice and an RRSP slice of the
 * same fund.
 *
 * Each card opens the holding row behind it, so editing the RRSP slice or
 * recording a sell against it is still one tap away.
 */
@Composable
fun AccountBreakdownScreen(
    viewModel: PortfolioViewModel,
    securityKey: String,
    onBack: () -> Unit,
    onOpenHolding: (Long) -> Unit
) {
    // Collected so the screen recomposes when a price, a holding or an account
    // changes underneath it — the position is derived from all three, and
    // keying the derivation on them is what ties the two together.
    val holdings by viewModel.holdings.collectAsStateWithLifecycle()
    val quotes by viewModel.quotes.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()

    val position = remember(holdings, quotes, accounts, securityKey) {
        viewModel.positionForSecurity(securityKey)
    }

    Scaffold(
        topBar = {
            WealthBoardTopBar(
                title = "Breakdown by account",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        if (position == null) {
            Column(Modifier.fillMaxSize().padding(padding).padding(20.dp)) {
                Text(
                    "This position is no longer in your portfolio.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return@Scaffold
        }

        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            item { BreakdownHeader(position) }
            items(position.slices, key = { it.holding.id }) { slice ->
                AccountSliceCard(
                    slice = slice,
                    position = position,
                    onClick = { onOpenHolding(slice.holding.id) }
                )
            }
            item { Spacer(Modifier.height(WbDimens.ScrollBottomGap)) }
        }
    }
}

@Composable
private fun BreakdownHeader(position: SecurityPosition) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = WbDimens.ScreenPadding, vertical = 14.dp)
    ) {
        Text(
            position.name,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Total value of shares ${Money.format(position.marketValue, position.currency)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "${Money.units(position.units)} units across ${position.accountCount} " +
                if (position.accountCount == 1) "account" else "accounts",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AccountSliceCard(
    slice: AccountPosition,
    position: SecurityPosition,
    onClick: () -> Unit
) {
    val currency = slice.currency
    fun money(v: Double) = Money.format(v, currency)

    WbCard(
        modifier = Modifier
            .padding(horizontal = WbDimens.ScreenPadding, vertical = 6.dp),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text(
                    slice.accountName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${Money.units(slice.units)} shares",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                money(slice.marketValue),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(Modifier.height(10.dp))
        WbDivider()
        Spacer(Modifier.height(4.dp))

        KeyValueRow(
            "Book cost",
            slice.costBasis?.let { money(it) } ?: "Not recorded"
        )
        KeyValueRow(
            "Average price",
            slice.averagePrice?.let { money(it) } ?: "—"
        )
        KeyValueRow(
            "% of position",
            Money.percent(position.shareOf(slice) * 100.0, 1)
        )
        KeyValueRow(
            "Today's return",
            slice.todaysReturn?.let {
                "${Money.signed(it, currency)}  (${Money.signedPercent(slice.todaysReturnPct)})"
            } ?: "—",
            valueColor = slice.todaysReturn?.let { if (it >= 0) GainGreen else LossRed }
                ?: MaterialTheme.colorScheme.onSurface
        )
        KeyValueRow(
            "Total return",
            slice.totalReturn?.let {
                "${Money.signed(it, currency)}  (${Money.signedPercent(slice.totalReturnPct)})"
            } ?: "—",
            valueColor = slice.totalReturn?.let { if (it >= 0) GainGreen else LossRed }
                ?: MaterialTheme.colorScheme.onSurface
        )

        // The one figure that genuinely differs between two slices of the same
        // fund, and the reason this screen is worth having.
        Spacer(Modifier.height(6.dp))
        WbDivider()
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Taxed",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val treatment = slice.treatment
            if (treatment != null) {
                StatusPill(treatment.label(), MaterialTheme.colorScheme.secondary)
            } else {
                StatusPill("Not set", MaterialTheme.colorScheme.error)
            }
        }
    }
}
