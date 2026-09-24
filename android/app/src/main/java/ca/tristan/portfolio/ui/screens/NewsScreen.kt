package ca.tristan.portfolio.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import ca.tristan.portfolio.ui.PortfolioViewModel
import ca.tristan.portfolio.ui.components.WealthBoardTopBar

/**
 * News: market headlines and dividend headlines, as a tab of its own.
 *
 * News used to be a block at the bottom of the Markets dashboard, under the
 * indices and the movers table, with the full readers filed away under Menu.
 * That put the thing people open several times a day below a fold they had to
 * scroll past, and split one feature across two places — three, counting the
 * chips on the dashboard that switched between the same two feeds.
 *
 * The two feeds are the two halves of one screen, which is what a tab row
 * means, so that is what they get. The reader screens themselves are unchanged;
 * they are drawn here with their own app bars suppressed.
 */
@Composable
fun NewsScreen(
    viewModel: PortfolioViewModel,
    onOpenArticle: (String) -> Unit
) {
    // rememberSaveable, so switching tabs and coming back does not silently
    // reset the reader to Market when they had left it on Dividends.
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val titles = remember { listOf("Market", "Dividends") }

    Scaffold(
        topBar = {
            WealthBoardTopBar(
                title = "News",
                actions = {
                    IconButton(onClick = {
                        if (selected == 0) viewModel.refreshMarketNews()
                        else viewModel.refreshDividendNews()
                    }) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") }
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TabRow(
                selectedTabIndex = selected,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary
            ) {
                titles.forEachIndexed { index, title ->
                    Tab(
                        selected = selected == index,
                        onClick = { selected = index },
                        text = {
                            Text(
                                title,
                                fontSize = 13.sp,
                                fontWeight = if (selected == index) FontWeight.Bold
                                             else FontWeight.Medium
                            )
                        }
                    )
                }
            }

            // Each feed keeps its own fetch-on-first-show and its own empty
            // state; `embedded` is what stops it drawing a second app bar under
            // the tab row.
            when (selected) {
                0 -> MarketNewsScreen(
                    viewModel = viewModel,
                    onBack = {},
                    onOpenArticle = onOpenArticle,
                    embedded = true
                )
                else -> DividendNewsScreen(
                    viewModel = viewModel,
                    onBack = {},
                    onOpenArticle = onOpenArticle,
                    embedded = true
                )
            }
        }
    }
}
