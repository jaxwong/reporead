package com.reporead.android.reader

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.reporead.android.data.DocumentRow
import com.reporead.android.data.LibraryDao
import com.reporead.android.data.NoteRow
import com.reporead.android.sync.Sync
import com.reporead.android.ui.folderOf
import org.json.JSONObject
import org.json.JSONTokener

/** What the preview found on this phone for the linked note. */
private sealed interface Saved {
    data object Loading : Saved
    data object Missing : Saved
    data class Copy(val note: NoteRow) : Saved
}

/**
 * A tapped link to one note: its title and folder and, from the copy saved on this phone, the start of the note or of
 * the linked [heading], with Open. Works offline; nothing is fetched to preview. The page is a separate isolated page
 * built like the reader's (no bridge, the same resources); nothing in it navigates.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LinkPreviewSheet(target: DocumentRow, heading: String?, sync: Sync, dao: LibraryDao, onOpen: () -> Unit, onDismiss: () -> Unit) {
    val saved by produceState<Saved>(Saved.Loading, target.id) { value = dao.note(target.id)?.let { Saved.Copy(it) } ?: Saved.Missing }
    var notice by remember(target.id) { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(target.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val place = listOfNotNull(folderOf(target.path).ifEmpty { null }, heading?.let { "# $it" }).joinToString(" ")
            if (place.isNotEmpty()) Text(place, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            when (val copy = saved) {
                Saved.Loading -> Unit
                Saved.Missing -> Text("Not saved on this phone yet, so there is nothing to preview. Open it to fetch it.",
                    style = MaterialTheme.typography.bodyMedium)
                is Saved.Copy -> {
                    if (copy.note.blobSha != target.blobSha) notice = "The saved copy is an older version of this note."
                    notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    AndroidView(
                        factory = { context -> previewView(context, sync, copy.note, heading) { notice = it } },
                        onRelease = { it.destroy() },
                        modifier = Modifier.fillMaxWidth().height(320.dp),
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Close") }
                Button(onClick = onOpen) { Text("Open") }
            }
        }
    }
}

private fun previewView(context: android.content.Context, sync: Sync, note: NoteRow, heading: String?, onNotice: (String) -> Unit): WebView {
    val assets = readerAssets(context)
    val main = Handler(Looper.getMainLooper())
    return WebView(context).apply {
        isolateReaderPage()
        // Hidden until it shows the linked heading, so the top of the note does not flash first.
        alpha = 0f
        webViewClient = object : WebViewClient() {
            // Links are followed from the reader, not from a preview.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) = readerResource(assets, sync, note, request.url)

            override fun onPageFinished(view: WebView, url: String) = awaitRendered(view, main) { state, _ ->
                if (state != "ready") {
                    Log.w("RepoRead", "Link preview did not render; documentId=${note.documentId} state=$state")
                    onNotice("This note could not be previewed.")
                    return@awaitRendered
                }
                if (heading == null) {
                    view.alpha = 1f
                    return@awaitRendered
                }
                view.evaluateJavascript("window.reporead.showHeading(${JSONObject.quote(heading)})") { encoded ->
                    if (JSONTokener(encoded).nextValue() != true) onNotice("No heading “$heading” in the saved copy; showing the start.")
                    view.alpha = 1f
                }
            }
        }
        loadDataWithBaseURL("$ASSET_ORIGIN/", note.html, "text/html", "UTF-8", null)
    }
}
