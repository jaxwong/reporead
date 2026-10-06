package com.reporead.readerspike

import android.os.Bundle
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import java.io.File

private const val ASSET_ORIGIN = "https://appassets.androidplatform.net"

class ReaderSpikeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme { ReaderSpikeScreen() }
        }
    }
}

private fun loadNote(view: WebView, fixture: Boolean): String {
    val html = if (fixture) {
        view.context.assets.open("fixture-note.html").bufferedReader().use { it.readText() }
    } else {
        val file = File(view.context.filesDir, "reader-proof.html")
        if (!file.exists()) {
            view.loadUrl("about:blank")
            return "No real note imported. Follow the spike README; no fixture was substituted."
        }
        file.readText(Charsets.UTF_8)
    }
    Log.i("ReaderProof", "load source=" + if (fixture) "synthetic-fixture" else "private-note-snapshot")
    view.loadDataWithBaseURL("$ASSET_ORIGIN/", html, "text/html", "UTF-8", null)
    return "Loading. Tap Status after the diagrams appear."
}

@Composable
private fun ReaderSpikeScreen() {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var result by remember { mutableStateOf("Loading imported snapshot.") }

    fun inspect(expression: String) {
        webView?.evaluateJavascript("window.readerProof ? JSON.stringify($expression) : null") { encoded ->
            val value = JSONTokener(encoded).nextValue()
            result = if (value == JSONObject.NULL) {
                "No selection yet, or the renderer is not ready."
            } else {
                JSONObject(value as String).toString(2)
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Stage 0 reader proof", style = MaterialTheme.typography.headlineSmall)
        Text("Local snapshot — not live backend delivery.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { webView?.let { result = loadNote(it, false) } }) { Text("Real note") }
            Button(onClick = { webView?.let { result = loadNote(it, true) } }) { Text("Test fixture") }
        }
        AndroidView(
            factory = { context ->
                val assetLoader = WebViewAssetLoader.Builder()
                    .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
                    .build()
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.blockNetworkLoads = true
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                            val local = if (request.url.toString().startsWith("$ASSET_ORIGIN/assets/")) {
                                assetLoader.shouldInterceptRequest(request.url)
                            } else null
                            return local ?: WebResourceResponse("text/plain", "UTF-8", 403, "Blocked",
                                emptyMap(), ByteArrayInputStream("Resource blocked by reader proof".toByteArray()))
                        }
                    }
                    webView = this
                    result = loadNote(this, false)
                }
            },
            modifier = Modifier.fillMaxWidth().weight(1f),
            onRelease = { view ->
                if (webView === view) webView = null
                view.destroy()
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = { inspect("window.readerProof.capture()") }) { Text("Capture") }
            Button(onClick = { inspect("window.readerProof.status") }) { Text("Status") }
            Button(onClick = { inspect("window.readerProof.runChecks()") }) { Text("Run checks") }
        }
        Text(result, modifier = Modifier.fillMaxWidth().height(170.dp).verticalScroll(rememberScrollState()))
    }
}
