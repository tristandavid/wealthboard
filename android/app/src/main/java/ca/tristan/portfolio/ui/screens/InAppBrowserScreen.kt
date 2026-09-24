package ca.tristan.portfolio.ui.screens

import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import ca.tristan.portfolio.ui.components.WealthBoardTopBar

/**
 * A minimal in-app browser using Android WebView.
 * Used to display news articles without leaving the app.
 */
@Composable
fun InAppBrowserScreen(url: String, title: String = "News", onBack: () -> Unit) {
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
            factory = { context ->
                WebView(context).apply {
                    webViewClient = object : WebViewClient() {
                        // Stay within the app — intercept all navigations
                        override fun shouldOverrideUrlLoading(
                            view: WebView, request: WebResourceRequest
                        ): Boolean {
                            view.loadUrl(request.url.toString())
                            return true
                        }
                    }
                    settings.javaScriptEnabled  = true
                    settings.domStorageEnabled  = true
                    settings.setSupportZoom(true)
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    loadUrl(url)
                }
            },
            update = { webView ->
                // Re-load only if the URL actually changed (e.g. recomposition)
                if (webView.url != url && url.isNotBlank()) webView.loadUrl(url)
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        )
    }
}
