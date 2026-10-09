package com.reporead.android.reader

import android.util.Log
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.reporead.android.core.network.ApiException
import com.reporead.android.core.network.describe
import com.reporead.android.R
import com.reporead.android.data.AnnotationRow
import com.reporead.android.data.DocumentRow
import com.reporead.android.data.LibraryDao
import com.reporead.android.sync.Sync
import com.reporead.android.ui.EntryRow
import com.reporead.android.ui.folderOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Saved notes that link to the open note, out of [saved] of the repository's [listed] notes saved on this phone (fewer
 * means the list may be missing some). [listed] is 0 when the note is in no saved list.
 */
private data class Backlinks(val notes: List<DocumentRow>, val saved: Int, val listed: Int)

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
internal fun NotesPanel(documentId: Long, annotations: List<AnnotationRow>, displayedBlobSha: String, notShown: Set<String>, sync: Sync,
                        dao: LibraryDao, scope: CoroutineScope, onFailure: (ApiException) -> Unit, onReveal: (AnnotationRow) -> Unit,
                        onMessage: (String?) -> Unit, onMakeQuestion: (AnnotationRow) -> Unit, onReattach: (AnnotationRow) -> Unit, onOpenNote: (DocumentRow) -> Unit, modifier: Modifier) {
    var editing by remember { mutableStateOf<AnnotationRow?>(null) }
    var conflict by remember { mutableStateOf<Conflict?>(null) }
    /** The highlight or card the user asked to delete, until they confirm. */
    var deleting by remember { mutableStateOf<AnnotationRow?>(null) }
    // Computed on the phone from the saved copies' links, so it works offline and covers only notes saved here.
    val backlinks by produceState<Backlinks?>(null, documentId) {
        val note = dao.document(documentId)
        if (note == null) {
            value = Backlinks(emptyList(), 0, 0)
            return@produceState
        }
        val documents = dao.documentsOnce(note.repositoryId)
        val pages = dao.pagesWithNoteLinks(note.repositoryId)
        val started = System.currentTimeMillis()
        val found = withContext(Dispatchers.Default) { linkedFrom(note, documents, pages) }
        Log.i("RepoRead", "Linked from found; documentId=$documentId pagesWithLinks=${pages.size} found=${found.size} ms=${System.currentTimeMillis() - started}")
        value = Backlinks(found, dao.savedNoteCount(note.repositoryId), documents.size)
    }

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
                    Text("“${if (row.type == "CARD") row.drawn.exactText else row.exactText}”", style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    row.question?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                    if (row.type == "CARD" && row.checkedBlobSha != displayedBlobSha) Text("Check this card", style = MaterialTheme.typography.labelLarge)
                    row.note?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                    status(row, displayedBlobSha, notShown)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    val shown = row.drawn.blobSha == displayedBlobSha && row.mutationId !in notShown
                    Row {
                        if (shown) TextButton(onClick = { onReveal(row) }) { Text("Show") }
                        if (shown && row.type == "HIGHLIGHT") TextButton(onClick = { onMakeQuestion(row) }) { Text("Make a question") }
                        if (shown && row.type == "CARD" && row.status != "ORPHANED" && row.serverId != null && row.checkedBlobSha != displayedBlobSha) {
                            TextButton(onClick = {
                                scope.launch {
                                    try {
                                        sync.checkCard(row, displayedBlobSha)
                                        onMessage("Card confirmed for this version.")
                                    } catch (error: ApiException) {
                                        onFailure(error)
                                        onMessage("Card not confirmed. ${error.describe()}")
                                    }
                                }
                            }) { Text("Answer is correct") }
                        }
                    }
                    Row {
                        // Online: the server verifies the new selection against the version it was made on.
                        if (row.serverId != null && !shown) TextButton(onClick = { onReattach(row) }) { Text("Reattach") }
                        if (row.serverId != null) TextButton(onClick = { editing = row }) { Text("Edit note") }
                        TextButton(onClick = { deleting = row }) { Text("Delete") }
                    }
                }
                HorizontalDivider()
            }
            item {
                Text("Linked from", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp))
                val found = backlinks
                val summary = when {
                    found == null -> "Finding notes that link here…"
                    found.listed == 0 -> "This note isn't in a saved note list, so links to it can't be found. Refresh the repository."
                    else -> listOfNotNull(
                        if (found.notes.isEmpty()) "No saved note links here." else null,
                        if (found.saved < found.listed) "Searched the ${found.saved} of ${found.listed} notes saved on this phone." else null,
                    ).joinToString(" ").ifEmpty { null }
                }
                summary?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
            }
            items(backlinks?.notes.orEmpty(), key = { "linked-${it.id}" }) { from ->
                EntryRow(from.title, folderOf(from.path).ifEmpty { null }, R.drawable.ic_description) { onOpenNote(from) }
            }
        }
    }

    editing?.let { row ->
        NoteDialog(title = "Edit note", quote = row.exactText, initial = row.note ?: "", onDismiss = { editing = null }, onSave = { text ->
            editing = null
            save(row, text.ifBlank { null }, row.version)
        })
    }
    deleting?.let { row ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(if (row.type == "CARD") "Delete this card?" else "Delete this highlight?") },
            text = {
                Text("“${row.drawn.exactText}”" + when {
                    row.type == "CARD" -> "\n\nIts question and its review history are deleted too. This cannot be undone."
                    row.note != null -> "\n\nIts note is deleted too. This cannot be undone."
                    else -> "\n\nThis cannot be undone."
                }, maxLines = 8, overflow = TextOverflow.Ellipsis)
            },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
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
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
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
internal fun NoteDialog(title: String, quote: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit, label: String = "Note", allowBlank: Boolean = true) {
    var text by rememberSaveable(title, quote, initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text("“$quote”", maxLines = 4, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                // No length cap here: the server owns it (AnnotationController.MAX_TEXT_CHARS) and its refusal is shown.
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text(label) })
            }
        },
        confirmButton = { TextButton(enabled = allowBlank || text.isNotBlank(), onClick = { onSave(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
