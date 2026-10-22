package com.reporead.android.reader

import android.content.Context
import android.webkit.WebView
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
import com.reporead.android.database.DocumentEntity
import com.reporead.android.database.RepoReadDao
import com.reporead.android.database.SavedPageEntity
import com.reporead.android.sync.PhoneSync
import com.reporead.android.ui.folderOf
import org.json.JSONObject

/** What the preview found on this phone for the linked note. */
private sealed interface Saved {
    data object Loading : Saved

    data object Missing : Saved

    data class Copy(val note: SavedPageEntity) : Saved
}

/**
 * A tapped link to one note: its title and folder and, from the copy saved on this phone, the start of the note or of
 * the linked [heading], with Open. Works offline; nothing is fetched to preview. The page is a separate isolated page
 * built like the reader's (no bridge, the same resources); nothing in it navigates.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LinkPreviewSheet(
    target: DocumentEntity,
    heading: String?,
    sync: PhoneSync,
    dao: RepoReadDao,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
) {
    val saved by
        produceState<Saved>(Saved.Loading, target.id) {
            value = dao.note(target.id)?.let { Saved.Copy(it) } ?: Saved.Missing
        }
    var notice by remember(target.id) { mutableStateOf<String?>(null) }
    var failed by remember(target.id) { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                target.title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val place =
                listOfNotNull(folderOf(target.path).ifEmpty { null }, heading?.let { "# $it" }).joinToString(" ")
            if (place.isNotEmpty())
                Text(
                    place,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            when (val copy = saved) {
                Saved.Loading -> Unit
                Saved.Missing ->
                    Text(
                        "Not saved on this phone yet, so there is nothing to preview. Open it to fetch it.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                is Saved.Copy -> {
                    val older =
                        if (copy.note.blobSha != target.blobSha) "The saved copy is an older version of this note."
                        else null
                    for (line in listOfNotNull(older, notice)) Text(line, style = MaterialTheme.typography.bodySmall)
                    if (!failed)
                        AndroidView(
                            factory = { context ->
                                previewView(context, sync, copy.note, heading, onNotice = { notice = it }) {
                                    failed = true
                                }
                            },
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

private fun previewView(
    context: Context,
    sync: PhoneSync,
    note: SavedPageEntity,
    heading: String?,
    onNotice: (String) -> Unit,
    onFailed: () -> Unit,
): WebView =
    IsolatedPage(context).apply {
        alpha = 0f
        show(
            sync.images,
            note,
            "Link preview",
            heading?.let { "window.reporead.showHeading(${JSONObject.quote(it)})" },
            onFailed = {
                onNotice("This note could not be previewed.")
                onFailed()
            },
        ) { found ->
            if (!found) onNotice("No heading “$heading” in the saved copy; showing the start.")
            alpha = 1f
        }
    }
