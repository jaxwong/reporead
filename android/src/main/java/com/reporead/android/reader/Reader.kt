package com.reporead.android.reader

import android.content.ActivityNotFoundException
import android.content.Intent
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import com.reporead.android.Screen
import com.reporead.android.core.network.Api
import com.reporead.android.core.network.ApiException
import com.reporead.android.core.network.LoadContent
import com.reporead.android.core.network.contract
import com.reporead.android.core.network.rememberLoad
import java.io.ByteArrayInputStream

private const val ASSET_HOST = "appassets.androidplatform.net"
private const val ASSET_ORIGIN = "https://$ASSET_HOST"

private data class Note(val path: String, val html: String)

@Composable
fun ReaderScreen(api: Api, onFailure: (ApiException) -> Unit, screen: Screen.Reader) {
    var reload by remember { mutableIntStateOf(0) }
    val load by rememberLoad(screen.documentId to reload, onFailure) {
        val json = api.get("/api/documents/${screen.documentId}/content")
        contract { Note(json.getString("path"), json.getString("html")) }
    }
    Column(Modifier.fillMaxSize()) {
        Text(screen.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
        LoadContent(load, onRetry = { reload++ }) { note ->
            AndroidView(
                factory = { context -> isolatedWebView(context) },
                update = { view ->
                    if (view.tag != note) {
                        view.tag = note
                        view.loadDataWithBaseURL("$ASSET_ORIGIN/", note.html, "text/html", "UTF-8", null)
                    }
                },
                onRelease = { it.destroy() },
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }
    }
}

/**
 * Renders backend-sanitized HTML with app-bundled reader assets only. Repository content gets no credentials,
 * no native JavaScript bridge, and no network, file, or content access. A tapped http(s) link opens the browser.
 */
private fun isolatedWebView(context: android.content.Context): WebView {
    val assets = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context)).build()
    return WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.blockNetworkLoads = true
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                if (request.hasGesture() && (url.scheme == "https" || url.scheme == "http") && url.host != ASSET_HOST) {
                    try {
                        view.context.startActivity(Intent(Intent.ACTION_VIEW, url).addCategory(Intent.CATEGORY_BROWSABLE))
                    } catch (error: ActivityNotFoundException) {
                        Log.w("RepoRead", "No browser for an external note link")
                    }
                }
                // Links between notes are not supported yet; the reader never navigates away from the note.
                return true
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                val local = if (request.url.toString().startsWith("$ASSET_ORIGIN/assets/")) assets.shouldInterceptRequest(request.url) else null
                return local ?: WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(),
                    ByteArrayInputStream("Blocked by RepoRead reader".toByteArray()))
            }
        }
    }
}
