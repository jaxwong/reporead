package com.reporead.android.reader

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.webkit.WebViewAssetLoader
import com.reporead.android.Screen
import com.reporead.android.core.network.ApiException
import com.reporead.android.core.network.LoadContent
import com.reporead.android.core.network.rememberLoad
import com.reporead.android.data.LibraryDao
import com.reporead.android.data.NoteRow
import com.reporead.android.data.ReadingRow
import com.reporead.android.sync.Sync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream

private const val ASSET_HOST = "appassets.androidplatform.net"
private const val ASSET_ORIGIN = "https://$ASSET_HOST"
private const val IMAGE_PREFIX = "/repo-image/"
private val IMAGE_TYPES = mapOf("png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "gif" to "image/gif",
    "webp" to "image/webp", "svg" to "image/svg+xml")
private const val SAVE_AFTER_SCROLL_MS = 700L
private const val READY_POLL_MS = 150L
private const val READY_POLL_LIMIT = 100

@Composable
fun ReaderScreen(sync: Sync, dao: LibraryDao, appScope: CoroutineScope, onFailure: (ApiException) -> Unit, screen: Screen.Reader) {
    var reload by remember { mutableIntStateOf(0) }
    val load by rememberLoad(screen.documentId to reload, onFailure) { sync.openNote(screen.documentId) }
    val bookmark by dao.bookmark(screen.documentId).collectAsState(null)
    var restoreNotice by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(screen.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            val opened = (load as? com.reporead.android.core.network.Load.Ready)?.value
            if (opened != null) {
                val marked = bookmark?.bookmarked == true
                TextButton(onClick = { appScope.launch { sync.setBookmark(opened.note, !marked) } }) {
                    Text(if (marked) "★ Bookmarked" else "☆ Bookmark")
                }
            }
        }
        LoadContent(load, onRetry = { reload++ }) { opened ->
            opened.staleReason?.let { Notice("Showing your saved copy. $it") }
            restoreNotice?.let { Notice(it) }
            val lifecycle = LocalLifecycleOwner.current.lifecycle
            val session = remember(opened.note) { ReaderSession(opened.note, sync, dao, appScope) { restoreNotice = it } }
            DisposableEffect(lifecycle, session) {
                // Process death can follow ON_STOP, so the position is captured whenever the reader leaves the screen.
                val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) session.capture() }
                lifecycle.addObserver(observer)
                onDispose { lifecycle.removeObserver(observer) }
            }
            AndroidView(
                factory = { context -> session.createView(context) },
                onRelease = { view -> session.release(view) },
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }
    }
}

@Composable
private fun Notice(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
}

/**
 * One displayed note version in an isolated WebView. Repository content gets no credentials, no JavaScript bridge, and
 * no network, file, or content access; native code reads and restores the position through evaluateJavascript only.
 */
private class ReaderSession(
    private val note: NoteRow,
    private val sync: Sync,
    private val dao: LibraryDao,
    private val scope: CoroutineScope,
    private val onRestoreNotice: (String?) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var view: WebView? = null
    /** Saving starts only after the saved position is restored, so the top of the page never overwrites it. */
    private var restored = false
    private val saveAfterScroll = Runnable { capture() }

    fun createView(context: Context): WebView {
        val assets = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context)).build()
        return WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.blockNetworkLoads = true
            settings.textZoom = (context.resources.configuration.fontScale * 100).toInt()
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

                // Runs on a WebView background thread, so the blocking image fetch is allowed here.
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                    val url = request.url
                    if (url.host == ASSET_HOST && url.path?.startsWith("/assets/") == true) {
                        assets.shouldInterceptRequest(url)?.let { return it }
                    }
                    if (url.host == ASSET_HOST && url.path?.startsWith(IMAGE_PREFIX) == true) {
                        val path = url.path!!.removePrefix(IMAGE_PREFIX)
                        val type = IMAGE_TYPES[path.substringAfterLast('.', "").lowercase()]
                        val bytes = type?.let { sync.image(note.documentId, note.blobSha, path) }
                        if (type != null && bytes != null) {
                            return WebResourceResponse(type, null, 200, "OK", mapOf("X-Content-Type-Options" to "nosniff"), ByteArrayInputStream(bytes))
                        }
                    }
                    return WebResourceResponse("text/plain", "UTF-8", 404, "Unavailable", emptyMap(),
                        ByteArrayInputStream("Unavailable in RepoRead reader".toByteArray()))
                }

                override fun onPageFinished(view: WebView, url: String) = awaitReady(view, 0)
            }
            setOnScrollChangeListener { _, _, _, _, _ ->
                main.removeCallbacks(saveAfterScroll)
                if (restored) main.postDelayed(saveAfterScroll, SAVE_AFTER_SCROLL_MS)
            }
            view = this
            loadDataWithBaseURL("$ASSET_ORIGIN/", note.html, "text/html", "UTF-8", null)
        }
    }

    private fun awaitReady(view: WebView, attempt: Int) {
        view.evaluateJavascript("document.body.dataset.state || ''") { encoded ->
            when (JSONTokener(encoded).nextValue()) {
                "ready" -> restore(view)
                "failed" -> Log.w("RepoRead", "Reader render failed; position not saved; documentId=${note.documentId}")
                else -> if (attempt < READY_POLL_LIMIT) main.postDelayed({ awaitReady(view, attempt + 1) }, READY_POLL_MS)
                    else Log.w("RepoRead", "Reader never became ready; position not saved; documentId=${note.documentId}")
            }
        }
    }

    private fun restore(view: WebView) {
        scope.launch {
            val saved = dao.reading(note.documentId)
            if (saved == null) {
                restored = true
                capture()
                return@launch
            }
            view.evaluateJavascript("window.reporead.restore(${saved.anchorJson}, ${saved.progressPercent})") { encoded ->
                val mode = JSONTokener(encoded).nextValue() as String
                onRestoreNotice(when {
                    mode == "section" -> "Resumed at the start of the section you were reading; the exact passage changed."
                    mode == "block" || mode == "percent" -> "Resumed near your last position; the exact passage could not be found."
                    saved.lastReadBlobSha != note.blobSha -> "This note changed since you last read it; resumed at the same passage."
                    else -> null
                })
                restored = true
                capture()
            }
        }
    }

    /** Saves the current position for the version on screen, then runs [then]. */
    fun capture(then: () -> Unit = {}) {
        main.removeCallbacks(saveAfterScroll)
        val webView = view
        if (webView == null || !restored) {
            then()
            return
        }
        webView.evaluateJavascript("JSON.stringify(window.reporead.position())") { encoded ->
            val position = JSONObject(JSONTokener(encoded).nextValue() as String)
            val row = ReadingRow(note.documentId, note.title, note.path, note.blobSha, position.getInt("progressPercent"),
                position.getJSONObject("anchor").toString(), System.currentTimeMillis(), pending = true)
            scope.launch { sync.saveReading(row) }
            then()
        }
    }

    /** Saves the final position before destroying the view; later lifecycle events no longer touch it. */
    fun release(webView: WebView) {
        capture { webView.destroy() }
        view = null
        restored = false
    }
}
