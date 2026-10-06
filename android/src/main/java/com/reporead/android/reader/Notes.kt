package com.reporead.android.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.reporead.android.core.network.ApiException
import com.reporead.android.core.network.describe
import com.reporead.android.data.AnnotationRow
import com.reporead.android.data.LibraryDao
import com.reporead.android.sync.Sync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** An edit the server refused because another edit landed first: the user picks which text to keep. */
private data class Conflict(val current: AnnotationRow, val mine: String?)

private fun status(row: AnnotationRow, displayedBlobSha: String, notShown: Set<String>): String? = when {
    row.rejection != null -> "Not saved to the server: ${row.rejection}"
    row.pending -> "Saved on this phone; waiting to sync"
    row.orphanedIn(displayedBlobSha) -> "The note changed and this passage could not be found reliably. Originally: …${row.prefixText}[${row.exactText}]${row.suffixText}…"
    row.drawn.blobSha != displayedBlobSha -> "Placed in another version of this note; not shown in the text"
    row.mutationId in notShown -> "Its text was not found in this version; not shown"
    row.drawn.exactText != row.exactText -> "Followed an edit of the note; now “${row.drawn.exactText}”"
    else -> null
}

@Composable
internal fun NotesPanel(annotations: List<AnnotationRow>, displayedBlobSha: String, notShown: Set<String>, sync: Sync, dao: LibraryDao,
                        scope: CoroutineScope, onFailure: (ApiException) -> Unit, onReveal: (AnnotationRow) -> Unit,
                        onMessage: (String?) -> Unit, onReattach: (AnnotationRow) -> Unit, modifier: Modifier) {
    var editing by remember { mutableStateOf<AnnotationRow?>(null) }
    var conflict by remember { mutableStateOf<Conflict?>(null) }

    // Editing and deleting acknowledged highlights need the server; offline they fail visibly and change nothing.
    fun save(row: AnnotationRow, note: String?, expectedVersion: Int) = scope.launch {
        try {
            sync.editAnnotation(row, note, expectedVersion)
            onMessage(null)
        } catch (error: ApiException) {
            onFailure(error)
            if (error.code == "ANNOTATION_CONFLICT") {
                try {
                    sync.refreshAnnotations(row.documentId)
                    dao.annotation(row.mutationId)?.let { conflict = Conflict(it, note) } ?: onMessage("This highlight was deleted elsewhere.")
                } catch (refresh: ApiException) {
                    onMessage("This note was changed elsewhere, and the latest version could not be loaded. ${refresh.describe()}")
                }
            } else {
                onMessage("Edit not saved. ${error.describe()}")
            }
        }
    }

    Column(modifier) {
        HorizontalDivider()
        if (annotations.isEmpty()) {
            Text("No highlights yet. Select text and choose Highlight or Add note.", Modifier.padding(16.dp))
        }
        LazyColumn {
            items(annotations, key = { it.mutationId }) { row ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("“${row.exactText}”", style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    row.note?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                    status(row, displayedBlobSha, notShown)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Row {
                        val shown = row.drawn.blobSha == displayedBlobSha && row.mutationId !in notShown
                        if (shown) TextButton(onClick = { onReveal(row) }) { Text("Show") }
                        // Online: the server verifies the new selection against the version it was made on.
                        if (row.serverId != null && !shown) TextButton(onClick = { onReattach(row) }) { Text("Reattach") }
                        if (row.serverId != null) TextButton(onClick = { editing = row }) { Text("Edit note") }
                        TextButton(onClick = {
                            scope.launch {
                                try {
                                    sync.deleteAnnotation(row)
                                    onMessage(null)
                                } catch (error: ApiException) {
                                    onFailure(error)
                                    onMessage(if (error.code == "ANNOTATION_CONFLICT") "This highlight was changed elsewhere; sync and review it before deleting."
                                        else "Not deleted. ${error.describe()}")
                                }
                            }
                        }) { Text("Delete") }
                    }
                }
                HorizontalDivider()
            }
        }
    }

    editing?.let { row ->
        NoteDialog(title = "Edit note", quote = row.exactText, initial = row.note ?: "", onDismiss = { editing = null }, onSave = { text ->
            editing = null
            save(row, text.ifBlank { null }, row.version)
        })
    }
    conflict?.let { (current, mine) ->
        AlertDialog(
            onDismissRequest = { conflict = null },
            title = { Text("Changed elsewhere") },
            text = {
                Column {
                    Text("Current note: ${current.note ?: "(none)"}")
                    Text("Your edit: ${mine ?: "(none)"}", Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = { TextButton(onClick = { conflict = null; save(current, mine, current.version) }) { Text("Keep mine") } },
            dismissButton = { TextButton(onClick = { conflict = null }) { Text("Keep current") } },
        )
    }
}

@Composable
internal fun NoteDialog(title: String, quote: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text("“$quote”", maxLines = 4, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(text, { if (it.length <= 10_000) text = it }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text("Note") })
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
