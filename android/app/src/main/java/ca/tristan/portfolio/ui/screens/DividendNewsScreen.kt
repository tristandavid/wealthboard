package ca.tristan.portfolio.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ca.tristan.portfolio.ui.PortfolioViewModel
import ca.tristan.portfolio.ui.components.WbDimens
import ca.tristan.portfolio.ui.components.WbDivider
import ca.tristan.portfolio.ui.components.WealthBoardTopBar
import ca.tristan.portfolio.ui.theme.GainGreen

/**
 * Dividend News: declarations, raises, cuts and income-investing coverage,
 * with stories about the user's own holdings pulled to the top.
 *
 * Uses the same card layout as Market News so the two feeds read as one
 * product; what differs is the source mix and the keyword filter behind it.
 */
@Composable
fun DividendNewsScreen(
    viewModel: PortfolioViewModel,
    onBack: () -> Unit,
    onOpenArticle: (String) -> Unit,
    /** See the same parameter on [MarketNewsScreen]. */
    embedded: Boolean = false
) {
    val news     by viewModel.dividendNews.collectAsStateWithLifecycle()
    val holdings by viewModel.holdings.collectAsStateWithLifecycle()
    var refreshing by remember { mutableStateOf(false) }

    // join() so the spinner covers the actual fetch — see the same note in
    // MarketNewsScreen.
    LaunchedEffect(holdings.size) {
        viewModel.loadCachedNews().join()
        refreshing = news.isEmpty()
        viewModel.refreshDividendNews().join()
        refreshing = false
    }

    // Split into "about your holdings" and everything else, so a declaration
    // for something actually owned isn't buried under generic income coverage.
    val heldSymbols = remember(holdings) {
        holdings.mapNotNull { it.ticker?.uppercase()?.substringBefore('.') }
            .filter { it.isNotBlank() }
            .toSet()
    }
    val mine = remember(news, heldSymbols) {
        news.filter { item -> heldSymbols.any { it in item.title.uppercase() } }
    }
    val rest = remember(news, mine) { news - mine.toSet() }

    Scaffold(
        topBar = {
            if (!embedded) {
                WealthBoardTopBar(
                    title = "Dividend News",
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            refreshing = true
                            viewModel.refreshDividendNews()
                            refreshing = false
                        }) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") }
                    }
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->

        if (news.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                if (refreshing) CircularProgressIndicator()
                else Text(
                    "No dividend headlines right now — tap refresh to try again.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 32.dp)
                )
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(top = 8.dp, bottom = WbDimens.ScrollBottomGap)
        ) {
            if (mine.isNotEmpty()) {
                item(key = "hdr-mine") {
                    SectionTitle("Your Holdings", GainGreen)
                }
                item(key = "lead-mine") {
                    LeadStoryCard(mine.first()) { onOpenArticle(mine.first().linkUrl) }
                }
                // Uncapped for the same reason the Markets feed is: a
                // take(8) here threw away holdings-specific coverage that had
                // already been fetched and filtered, which is exactly the
                // coverage this section exists to surface.
                mine.drop(1).forEach { a ->
                    item(key = a.linkUrl) {
                        CompactStoryRow(a) { onOpenArticle(a.linkUrl) }
                    }
                }
                item(key = "gap-mine") {
                    Spacer(Modifier.height(6.dp))
                    WbDivider(Modifier.padding(horizontal = WbDimens.ScreenPadding))
                    Spacer(Modifier.height(10.dp))
                }
            }

            if (rest.isNotEmpty()) {
                item(key = "hdr-rest") {
                    SectionTitle("Dividends & Income", MaterialTheme.colorScheme.primary)
                }
                item(key = "lead-rest") {
                    LeadStoryCard(rest.first()) { onOpenArticle(rest.first().linkUrl) }
                }
                val restTail = rest.drop(1)
                restTail.forEach { a ->
                    item(key = a.linkUrl) {
                        CompactStoryRow(a) { onOpenArticle(a.linkUrl) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text,
        fontSize = 22.sp,
        fontWeight = FontWeight.ExtraBold,
        color = color,
        modifier = Modifier.padding(
            start = WbDimens.ScreenPadding,
            end = WbDimens.ScreenPadding,
            top = 12.dp,
            bottom = 8.dp
        )
    )
}
