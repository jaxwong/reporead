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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.reporead.android.R
import com.reporead.android.database.AnnotationEntity
import com.reporead.android.database.AnnotationState
import com.reporead.android.database.AnnotationType
import com.reporead.android.database.CardBlock
import com.reporead.android.database.DocumentEntity
import com.reporead.android.database.RepoReadDao
import com.reporead.android.logging.DeviceLog
import com.reporead.android.ui.EntryRow
import com.reporead.android.ui.folderOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Saved notes that link to the open note, out of [saved] of the repository's [listed] notes saved on this phone (fewer
 * means the list may be missing some). [listed] is 0 when the note is in no saved list.
 */
private data class Backlinks(val notes: List<DocumentEntity>, val saved: Int, val listed: Int)

private fun status(row: AnnotationEntity, displayedBlobSha: String, notShown: Set<String>): String? =
    when {
        row.state == AnnotationState.REFUSED -> "Not saved to the server: ${row.rejection}"
        row.state == AnnotationState.UNSENT -> "Saved on this phone; waiting to sync"
        row.orphanedIn(displayedBlobSha) ->
            "The note changed and this passage could not be found reliably. Originally: …${row.prefixText}[${row.exactText}]${row.suffixText}…"
        row.drawn.blobSha != displayedBlobSha -> "Placed in another version of this note; not shown in the text"
        row.mutationId in notShown -> "Its text was not found in this version; not shown"
        row.drawn.exactText != row.exactText -> "Followed an edit of the note; now “${row.drawn.exactText}”"
        else -> null
    }

@Composable
internal fun NotesPanel(
    documentId: Long,
    annotations: List<AnnotationEntity>,
    displayedBlobSha: String,
    notShown: Set<String>,
    pageReady: Boolean,
    dao: RepoReadDao,
    onReveal: (AnnotationEntity) -> Unit,
    onReattach: (AnnotationEntity) -> Unit,
    onEdit: (AnnotationEntity) -> Unit,
    onDelete: (AnnotationEntity) -> Unit,
    onOpenNote: (DocumentEntity) -> Unit,
    modifier: Modifier,
) {
    // Whether this note left its repository, which a card's status names.
    val removed by produceState(false, documentId) { value = dao.document(documentId)?.deleted == true }
    // Computed on the phone from the saved copies' links, so it works offline and covers only notes saved here.
    val backlinks by
        produceState<Backlinks?>(null, documentId) {
            val note = dao.document(documentId)
            if (note == null) {
                value = Backlinks(emptyList(), 0, 0)
                return@produceState
            }
            val documents = dao.listedDocuments(note.repositoryId)
            val started = System.currentTimeMillis()
            val links =
                dao.noteLinks(note.repositoryId).groupBy({ it.documentId }, { it.query }).mapValues { (_, queries) ->
                    queries.mapNotNull(::noteLinkTarget)
                }
            val found = withContext(Dispatchers.Default) { linkedFrom(note, documents, links) }
            DeviceLog.info(
                "Linked from found; documentId=$documentId pagesWithLinks=${links.size} found=${found.size} ms=${System.currentTimeMillis() - started}"
            )
            value = Backlinks(found, dao.savedNoteCount(note.repositoryId), documents.size)
        }

    Column(modifier) {
        HorizontalDivider()
        if (annotations.isEmpty()) {
            Text("No highlights yet. Select text and choose Highlight or Add note.", Modifier.padding(16.dp))
        }
        LazyColumn {
            items(annotations, key = { it.mutationId }) { row ->
                AnnotationItem(
                    row,
                    shown = row.drawn.blobSha == displayedBlobSha && row.mutationId !in notShown,
                    pageReady = pageReady,
                    status = status(row, displayedBlobSha, notShown),
                    block =
                        if (row.type == AnnotationType.CARD) row.cardBlock(removed, displayedBlobSha, displayedBlobSha)
                        else null,
                    onReveal = { onReveal(row) },
                    onReattach = { onReattach(row) },
                    onEdit = { onEdit(row) },
                    onDelete = { onDelete(row) },
                )
                HorizontalDivider()
            }
            item {
                Text(
                    "Linked from",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp),
                )
                linkedFromSummary(backlinks)?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
            items(backlinks?.notes.orEmpty(), key = { "linked-${it.id}" }) { from ->
                EntryRow(from.title, folderOf(from.path).ifEmpty { null }, R.drawable.ic_description) {
                    onOpenNote(from)
                }
            }
        }
    }
}

/**
 * One highlight or card with what can be done with it. [shown]: drawn in the version on screen, which Show scrolls to
 * once the page is ready ([pageReady]); [status] says where it stands when that needs saying; [block] is why a card
 * cannot be reviewed yet.
 */
@Composable
private fun AnnotationItem(
    row: AnnotationEntity,
    shown: Boolean,
    pageReady: Boolean,
    status: String?,
    block: CardBlock?,
    onReveal: () -> Unit,
    onReattach: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            "“${if (row.type == AnnotationType.CARD) row.drawn.exactText else row.exactText}”",
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        row.question?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
        block?.let { Text(it.reason, style = MaterialTheme.typography.labelLarge) }
        row.note?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (shown) TextButton(enabled = pageReady, onClick = onReveal) { Text("Show") }
        Row {
            // Online: the server verifies the new selection against the version it was made on.
            if (row.serverId != null && !shown) TextButton(onClick = onReattach) { Text("Reattach") }
            if (row.serverId != null) TextButton(onClick = onEdit) { Text("Edit note") }
            TextButton(onClick = onDelete) { Text("Delete") }
        }
    }
}

/** What the Linked from list covers, when that needs saying; null when it is complete and not empty. */
private fun linkedFromSummary(found: Backlinks?): String? =
    when {
        found == null -> "Finding notes that link here…"
        found.listed == 0 ->
            "This note isn't in a saved note list, so links to it can't be found. Refresh the repository."
        else ->
            listOfNotNull(
                    if (found.notes.isEmpty()) "No saved note links here." else null,
                    if (found.saved < found.listed)
                        "Searched the ${found.saved} of ${found.listed} notes saved on this phone."
                    else null,
                )
                .joinToString(" ")
                .ifEmpty { null }
    }

/** An edit the server refused because another edit landed first: the user keeps the [current] note or [mine]. */
@Composable
internal fun ConflictDialog(current: AnnotationEntity, mine: String?, onKeepMine: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Changed elsewhere") },
        text = {
            Column {
                Text("Current note: ${current.note ?: "(none)"}")
                Text("Your edit: ${mine ?: "(none)"}", Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { TextButton(onClick = onKeepMine) { Text("Keep mine") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep current") } },
    )
}

/** Asks before deleting [row], saying what goes with it. */
@Composable
internal fun DeleteDialog(row: AnnotationEntity, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val goesWithIt =
        when {
            row.type == AnnotationType.CARD -> "Its question and its review history are deleted too. "
            row.note != null -> "Its note is deleted too. "
            else -> ""
        }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (row.type == AnnotationType.CARD) "Delete this card?" else "Delete this highlight?") },
        text = {
            Text(
                "“${row.drawn.exactText}”\n\n${goesWithIt}This cannot be undone.",
                maxLines = 8,
                overflow = TextOverflow.Ellipsis,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun NoteDialog(
    title: String,
    quote: String,
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    status: String? = null,
) {
    var text by rememberSaveable(title, quote, initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                status?.let { Text(it) }
                Text(
                    "“$quote”",
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
                // No length cap here: the server owns it (AnnotationController.MAX_TEXT_CHARS); its refusal is shown.
                OutlinedTextField(
                    text,
                    { text = it },
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    label = { Text("Note") },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
