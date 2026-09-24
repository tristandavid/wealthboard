package ca.tristan.portfolio.ui.screens

import android.content.Intent
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import ca.tristan.portfolio.ui.components.WealthBoardTopBar

/**
 * A minimal in-app browser using Android WebView.
 * Used to display news articles without leaving the app.
 */
@Composable
fun InAppBrowserScreen(url: String, title: String = "News", onBack: () -> Unit) {
    val context = LocalContext.current
    // Plain holders, not Compose state: they are written from the view
    // factory and update block, which run during composition.
    val holder = remember { BrowserHolder() }
    var canGoBack by remember { mutableStateOf(false) }

    // System back walks the article's own history first, the way a browser
    // does, and only leaves the screen once there is nothing to go back to.
    BackHandler(enabled = canGoBack) { holder.webView?.goBack() }

    Scaffold(
        topBar = {
            WealthBoardTopBar(
                title = title,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    webViewClient = object : WebViewClient() {
                        // Web pages load here, in place — returning false lets
                        // the WebView follow the navigation itself.
                        //
                        // This used to call loadUrl() for EVERY navigation and
                        // return true. That re-issued each redirect as a fresh
                        // top-level load and pulled ad and consent iframes'
                        // navigations up into the main page, so some articles
                        // bounced between addresses and kept reloading.
                        override fun shouldOverrideUrlLoading(
                            view: WebView, request: WebResourceRequest
                        ): Boolean {
                            val scheme = request.url.scheme?.lowercase()
                            if (scheme == "http" || scheme == "https") return false
                            // mailto:, tel:, intent:, market: … belong to other
                            // apps; a WebView cannot open them.
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, request.url)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                            return true
                        }

                        override fun doUpdateVisitedHistory(
                            view: WebView, url: String?, isReload: Boolean
                        ) {
                            canGoBack = view.canGoBack()
                        }
                    }
                    settings.javaScriptEnabled  = true
                    settings.domStorageEnabled  = true
                    settings.setSupportZoom(true)
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    holder.requestedUrl = url
                    loadUrl(url)
                    holder.webView = this
                }
            },
            update = { view ->
                // Load only when the CALLER asked for a different page. The
                // old check compared against view.url — where the page ended
                // up after redirects — which never equals the link it was
                // opened with on a redirecting site, so every recomposition
                // restarted the article.
                if (url.isNotBlank() && url != holder.requestedUrl) {
                    holder.requestedUrl = url
                    view.loadUrl(url)
                }
            },
            onRelease = { view ->
                holder.webView = null
                view.stopLoading()
                view.destroy()
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        )
    }
}

/** The WebView and the last URL it was asked to load — see InAppBrowserScreen. */
private class BrowserHolder {
    var webView: WebView? = null
    /** The last URL this screen was ASKED to show, not where redirects took it. */
    var requestedUrl: String? = null
}
