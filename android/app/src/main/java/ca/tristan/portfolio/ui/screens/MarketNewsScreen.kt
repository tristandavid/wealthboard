package ca.tristan.portfolio.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import ca.tristan.portfolio.net.NewsCategory
import ca.tristan.portfolio.net.NewsItem
import ca.tristan.portfolio.ui.PortfolioViewModel
import ca.tristan.portfolio.ui.components.WbDimens
import ca.tristan.portfolio.ui.components.WbDivider
import ca.tristan.portfolio.ui.components.WealthBoardTopBar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val newsTimeFmt = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())

/** Accent colour per section, echoing Apple News' coloured section headings. */
internal fun accentFor(category: NewsCategory): Color = when (category) {
    NewsCategory.TOP       -> Color(0xFFE0245E)
    NewsCategory.MARKETS   -> Color(0xFF1E8E5A)
    NewsCategory.ECONOMY   -> Color(0xFF7A4FD1)
    NewsCategory.COMPANIES -> Color(0xFF0F6FBF)
    NewsCategory.CANADA    -> Color(0xFFC0392B)
    NewsCategory.CRYPTO    -> Color(0xFFD97706)
}

/** Relative age ("2h ago"), falling back to an absolute timestamp past a day. */
internal fun ageLabel(millis: Long): String {
    val diff = System.currentTimeMillis() - millis
    val mins = diff / 60_000
    return when {
        mins < 1    -> "Just now"
        mins < 60   -> "${mins}m ago"
        mins < 1440 -> "${mins / 60}h ago"
        else        -> newsTimeFmt.format(Date(millis))
    }
}

/**
 * Market News, laid out like a news reader rather than a list of links:
 * a coloured section heading per topic, one large lead story with artwork,
 * then compact rows with thumbnails. Headlines come from several outlets
 * (CNBC, MarketWatch, Yahoo Finance, Investing.com, Financial Post, BBC),
 * grouped by subject rather than jumbled by timestamp.
 */
@Composable
fun MarketNewsScreen(
    viewModel: PortfolioViewModel,
    onBack: () -> Unit,
    onOpenArticle: (String) -> Unit,
    /**
     * True when this feed is drawn inside the News tab, which supplies its own
     * title bar and tab row. The screen then renders its body alone — a second
     * app bar stacked under the tab's would push the first headline off screen.
     */
    embedded: Boolean = false
) {
    val marketNews by viewModel.marketNews.collectAsStateWithLifecycle()
    var refreshing by remember { mutableStateOf(false) }

    // join(), not fire-and-forget: refreshMarketNews() returns a Job the
    // instant it is called, so `refreshing` used to flip back to false before
    // a single feed had answered. The empty state then showed "No headlines
    // right now — pull refresh to try again" during the seconds the fetch was
    // actually running, which reads as a feed that came back empty rather
    // than one still loading.
    LaunchedEffect(Unit) {
        // Cached headlines first, so the feed opens on content instead of a
        // spinner; the refresh then replaces them.
        viewModel.loadCachedNews().join()
        refreshing = marketNews.isEmpty()
        viewModel.refreshMarketNews().join()
        refreshing = false
    }

    // Group into sections, keeping the enum's editorial order and dropping
    // any section the feeds produced nothing for.
    val sections = remember(marketNews) {
        NewsCategory.values()
            .map { cat -> cat to marketNews.filter { it.category == cat } }
            .filter { it.second.isNotEmpty() }
    }

    Scaffold(
        topBar = {
            if (!embedded) {
                WealthBoardTopBar(
                    title = "Market News",
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            refreshing = true
                            viewModel.refreshMarketNews()
                            refreshing = false
                        }) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") }
                    }
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->

        if (marketNews.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                if (refreshing) CircularProgressIndicator()
                else Text(
                    "No headlines right now — pull refresh to try again.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                top = 8.dp, bottom = WbDimens.ScrollBottomGap
            )
        ) {
            sections.forEach { (category, items) ->
                item(key = "hdr-${category.name}") {
                    NewsSectionHeading(category)
                }

                // Lead story: full-width artwork, like the top of an Apple News section
                val lead = items.first()
                item(key = "lead-${lead.linkUrl}") {
                    LeadStoryCard(lead) { onOpenArticle(lead.linkUrl) }
                }

                // Everything the section has, not the first six.
                //
                // This cap was the real reason the feed read as thin: the
                // fetch was returning plenty, but a category holding forty
                // headlines rendered seven of them and silently dropped the
                // rest, so adding sources upstream changed nothing on screen.
                // A LazyColumn only composes what's visible, so there is no
                // cost to handing it the whole list — the 40-item ceiling is
                // just a guard against one runaway feed dominating the page.
                items.drop(1).take(40).forEach { article ->
                    item(key = article.linkUrl) {
                        CompactStoryRow(article) { onOpenArticle(article.linkUrl) }
                    }
                }

                item(key = "gap-${category.name}") {
                    Spacer(Modifier.height(6.dp))
                    WbDivider(Modifier.padding(horizontal = WbDimens.ScreenPadding))
                    Spacer(Modifier.height(10.dp))
                }
            }
        }
    }
}

/** Coloured section title, e.g. a red "Top Stories". */
@Composable
internal fun NewsSectionHeading(category: NewsCategory) {
    Text(
        category.label,
        fontSize = 22.sp,
        fontWeight = FontWeight.ExtraBold,
        color = accentFor(category),
        modifier = Modifier.padding(
            start = WbDimens.ScreenPadding,
            end = WbDimens.ScreenPadding,
            top = 12.dp,
            bottom = 8.dp
        )
    )
}

/**
 * The lead story: 16:9 artwork above the publisher and headline.
 *
 * No card around it. In a card, the artwork sat at the screen gutter but the
 * headline under it was inset by the card's own padding, so the lead story's
 * text started at a different place from every row beneath it. Heading, lead
 * and rows now share one left edge.
 */
@Composable
internal fun LeadStoryCard(article: NewsItem, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = WbDimens.ScreenPadding, vertical = 6.dp)
    ) {
        if (article.imageUrl != null) {
            AsyncImage(
                model = article.imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
            Spacer(Modifier.height(10.dp))
        }
        Column {
            Text(
                article.publisher.uppercase(),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.6.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(5.dp))
            Text(
                article.title,
                fontSize = 19.sp,
                lineHeight = 24.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(6.dp))
            Text(
                ageLabel(article.publishedAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Compact row: headline on the left, square thumbnail on the right. */
@Composable
internal fun CompactStoryRow(article: NewsItem, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = WbDimens.ScreenPadding, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                article.publisher.uppercase(),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Text(
                article.title,
                fontSize = 15.sp,
                lineHeight = 19.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Text(
                ageLabel(article.publishedAt),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (article.imageUrl != null) {
            AsyncImage(
                model = article.imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(86.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
        }
    }
}
