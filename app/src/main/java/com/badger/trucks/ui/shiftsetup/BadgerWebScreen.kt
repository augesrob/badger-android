package com.badger.trucks.ui.shiftsetup

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Embeds badger.augesrob.net pages inside the app for features that are
 * best kept as web-only (Route Sheet, Cheat Sheet).
 *
 * Shares the same session cookie as the system browser — users who are
 * already logged in on the web app will land directly on the page.
 */
@Composable
fun BadgerWebScreen(url: String) {
    var loading by remember { mutableStateOf(true) }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled  = true
                    settings.databaseEnabled    = true
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, pageUrl: String?) {
                            loading = false
                        }
                    }
                    loadUrl(url)
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color(0xFF3B82F6),
                strokeWidth = 3.dp
            )
        }
    }
}
