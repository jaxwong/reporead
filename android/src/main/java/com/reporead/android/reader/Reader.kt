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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.webkit.WebViewAssetLoader
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.IconButton
import com.reporead.android.R
import com.reporead.android.Screen
import com.reporead.android.ui.AppBar
import com.reporead.android.ui.AppIcon
import com.reporead.android.core.network.ApiException
import com.reporead.android.core.network.LoadContent
import com.reporead.android.core.network.rememberLoad
import com.reporead.android.data.LibraryDao
import com.reporead.android.core.network.Load
import com.reporead.android.core.network.describe
import com.reporead.android.data.AnnotationRow
import com.reporead.android.data.NoteRow
import com.reporead.android.data.ReadingRow
import com.reporead.android.sync.Changes
import com.reporead.android.sync.Sync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONArray
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
fun ReaderScreen(sync: Sync, dao: LibraryDao, appScope: CoroutineScope, signedIn: Boolean, onFailure: (ApiException) -> Unit,
                 screen: Screen.Reader, onBack: () -> Unit) {
    var reload by remember { mutableIntStateOf(0) }
    /*
     * The version last read when the note was opened (null: never read). Captured once, before the reader saves the
     * displayed version as read, and kept across activity recreation; reopening the note captures it again.
     */
    var since by rememberSaveable(screen.documentId) { mutableStateOf<String?>(null) }
    var sinceCaptured by rememberSaveable(screen.documentId) { mutableStateOf(false) }
    val load by rememberLoad(screen.documentId to reload, onFailure) {
        if (!sinceCaptured) {
            since = dao.reading(screen.documentId)?.lastReadBlobSha
            sinceCaptured = true
        }
        sync.openNote(screen.documentId)
    }
    val displayed = (load as? Load.Ready)?.value?.note?.blobSha
    var changesReload by remember { mutableIntStateOf(0) }
    var changesExpanded by rememberSaveable(screen.documentId) { mutableStateOf(true) }
    // Null when there is nothing to compare: never read, or reading the same version again.
    val changes by produceState<Load<Changes>?>(null, since, displayed, signedIn, changesReload) {
        val from = since
        if (from == null || displayed == null || from == displayed || !signedIn) {
            value = null
            return@produceState
        }
        value = Load.Loading
        value = try {
            Load.Ready(sync.changes(screen.documentId, from, displayed))
        } catch (error: ApiException) {
            onFailure(error)
            Load.Failed(error)
        }
    }
    val bookmark by dao.bookmark(screen.documentId).collectAsState(null)
    val annotations by dao.annotations(screen.documentId).collectAsState(emptyList())
    var restoreNotice by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var notesOpen by remember { mutableStateOf(false) }
    var newSelection by remember { mutableStateOf<JSONObject?>(null) }
    var notShown by remember { mutableStateOf(emptySet<String>()) }
    /** The highlight the user is placing by selecting its passage; the selection menu then offers only Reattach here. */
    var reattaching by remember { mutableStateOf<AnnotationRow?>(null) }

    if (signedIn) {
        LaunchedEffect(screen.documentId) {
            try {
                sync.refreshAnnotations(screen.documentId)
            } catch (error: ApiException) {
                onFailure(error)
                message = "Showing highlights saved on this phone. ${error.describe()}"
            }
        }
    }
    val create: (JSONObject, String?) -> Unit = { selection, note ->
        appScope.launch {
            sync.createAnnotation(selection, screen.documentId, note)
            message = try {
                sync.pushAnnotations()
                null
            } catch (error: ApiException) {
                onFailure(error)
                "Highlight saved on this phone; it will sync later. ${error.describe()}"
            }
        }
    }

    val reattach: (AnnotationRow, JSONObject) -> Unit = { row, selection ->
        appScope.launch {
            message = try {
                sync.reattachAnnotation(row, selection)
                reattaching = null
                null
            } catch (error: ApiException) {
                onFailure(error)
                if (error.code == "ANNOTATION_CONFLICT") "This highlight was changed elsewhere; sync, reopen the note, and try again."
                else "Not reattached. ${error.describe()}"
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        AppBar(screen.title, onBack = onBack, actions = {
            val opened = (load as? Load.Ready)?.value
            if (opened != null) {
                IconButton(onClick = { notesOpen = !notesOpen }) {
                    BadgedBox(badge = { if (annotations.isNotEmpty()) Badge { Text("${annotations.size}") } }) {
                        AppIcon(R.drawable.ic_notes, if (notesOpen) "Hide highlights and notes" else "Highlights and notes")
                    }
                }
                val marked = bookmark?.bookmarked == true
                IconButton(onClick = { appScope.launch { sync.setBookmark(opened.note, !marked) } }) {
                    AppIcon(if (marked) R.drawable.ic_bookmark else R.drawable.ic_bookmark_border, if (marked) "Remove bookmark" else "Bookmark")
                }
            }
        })
        LoadContent(load, onRetry = { reload++ }) { opened ->
            opened.staleReason?.let { Notice("Showing your saved copy. $it") }
            restoreNotice?.let { Notice(it) }
            message?.let { Notice(it) }
            if (since != null && since != opened.note.blobSha && !signedIn) Notice("This note changed since you last read it. Sign in to see what changed.")
            reattaching?.let { row ->
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Select the passage for “${row.exactText}”, then choose Reattach here.", style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = { reattaching = null }) { Text("Cancel") }
                }
            }
            val lifecycle = LocalLifecycleOwner.current.lifecycle
            val session = remember(opened.note) {
                ReaderSession(opened.note, sync, dao, appScope,
                    onRestoreNotice = { restoreNotice = it },
                    onNotShown = { notShown = it },
                    reattaching = { reattaching != null },
                    onSelection = { selection, action ->
                        val placing = reattaching
                        when {
                            selection == null -> message = "Select some text first."
                            selection.has("error") -> message = selection.getString("error")
                            action == SelectionAction.REATTACH && placing != null -> reattach(placing, selection)
                            action == SelectionAction.ADD_NOTE -> newSelection = selection
                            else -> create(selection, null)
                        }
                    })
            }
            DisposableEffect(lifecycle, session) {
                // Process death can follow ON_STOP, so the position is captured whenever the reader leaves the screen.
                val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) session.capture() }
                lifecycle.addObserver(observer)
                onDispose { lifecycle.removeObserver(observer) }
            }
            LaunchedEffect(session, annotations) { session.showHighlights(annotations) }
            changes?.let { comparison ->
                ChangesPanel(comparison, changesExpanded, onToggle = { changesExpanded = !changesExpanded }, onRetry = { changesReload++ },
                    onOpen = { section ->
                        changesExpanded = false
                        session.showBlock(section.blockId) { shown ->
                            if (!shown) message = "That section isn't in the version on screen; reopen the note."
                        }
                    })
            }
            AndroidView(
                factory = { context -> session.createView(context) },
                onRelease = { view -> session.release(view) },
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
            if (notesOpen) {
                NotesPanel(annotations, opened.note.blobSha, notShown, sync, dao, appScope, onFailure,
                    onReveal = { session.reveal(it.mutationId) }, onMessage = { message = it },
                    onReattach = { row ->
                        reattaching = row
                        notesOpen = false
                        message = null
                    },
                    modifier = Modifier.fillMaxWidth().weight(0.7f))
            }
        }
    }
    newSelection?.let { selection ->
        NoteDialog(title = "Add a note", quote = selection.getString("exactText"), initial = "",
            onDismiss = { newSelection = null }, onSave = { note ->
                newSelection = null
                create(selection, note.ifBlank { null })
            })
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
    private val onNotShown: (Set<String>) -> Unit,
    /** Whether a highlight is being reattached, which changes the selection menu. */
    private val reattaching: () -> Boolean,
    /** The captured selection (null when empty, {error} when it spans blocks) and the chosen action. */
    private val onSelection: (JSONObject?, SelectionAction) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var view: WebView? = null
    /** Rendering finished, so highlights and the scripts' functions can be used. */
    private var ready = false
    private var highlights: List<AnnotationRow> = emptyList()
    /** Saving starts only after the saved position is restored, so the top of the page never overwrites it. */
    private var restored = false
    private val saveAfterScroll = Runnable { capture() }

    fun createView(context: Context): WebView {
        val assets = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context)).build()
        return ReaderWebView(context, reattaching) { action, finish ->
            captureSelection { selection ->
                finish()
                onSelection(selection, action)
            }
        }.apply {
            // Until the page paints its own background, the app's surface shows through instead of a white flash.
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
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
                "ready" -> {
                    Log.i("RepoRead", "Reader ready; documentId=${note.documentId} viewHeight=${view.height} attempt=$attempt")
                    ready = true
                    applyHighlights()
                    restore(view)
                }
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
                Log.i("RepoRead", "Reader restored; documentId=${note.documentId} mode=$mode savedPercent=${saved.progressPercent} " +
                    "savedBlock=${JSONObject(saved.anchorJson).getInt("blockIndex")} viewHeight=${view.height}")
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
            Log.i("RepoRead", "Reading position saved; documentId=${note.documentId} percent=${position.getInt("progressPercent")} " +
                "block=${position.getJSONObject("anchor").getInt("blockIndex")} viewHeight=${webView.height}")
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
        ready = false
    }

    /** Draws each highlight whose current location is in the displayed version; others are listed, not drawn. */
    fun showHighlights(rows: List<AnnotationRow>) {
        highlights = rows
        applyHighlights()
    }

    private fun applyHighlights() {
        val webView = view ?: return
        if (!ready) return
        val payload = JSONArray(highlights.filter { it.drawn.blobSha == note.blobSha }.map {
            val passage = it.drawn
            JSONObject().put("key", it.mutationId).put("blockId", passage.blockId).put("startOffset", passage.startOffset)
                .put("endOffset", passage.endOffset).put("exactText", passage.exactText)
        })
        webView.evaluateJavascript("JSON.stringify(window.reporead.highlight($payload))") { encoded ->
            val missing = JSONArray(JSONTokener(encoded).nextValue() as String)
            onNotShown(List(missing.length()) { missing.getString(it) }.toSet())
        }
    }

    /** Scrolls to a changed section's heading block in this version, or the top for null; reports whether it was found. */
    fun showBlock(blockId: String?, then: (Boolean) -> Unit) {
        val webView = view
        if (webView == null || !ready) {
            then(false)
            return
        }
        webView.evaluateJavascript("window.reporead.showBlock(${if (blockId == null) "null" else JSONObject.quote(blockId)})") { encoded ->
            val shown = JSONTokener(encoded).nextValue() == true
            if (!shown) Log.w("RepoRead", "Changed section not found; documentId=${note.documentId} blobSha=${note.blobSha} blockId=$blockId")
            then(shown)
        }
    }

    fun reveal(key: String) {
        val webView = view ?: return
        if (ready) webView.evaluateJavascript("window.reporead.reveal(${JSONObject.quote(key)})", null)
    }

    private fun captureSelection(then: (JSONObject?) -> Unit) {
        val webView = view
        if (webView == null || !ready) {
            then(null)
            return
        }
        webView.evaluateJavascript("JSON.stringify(window.reporead.capture())") { encoded ->
            val value = JSONTokener(encoded).nextValue()
            then(if (value is String && value != "null") JSONObject(value) else null)
        }
    }
}
