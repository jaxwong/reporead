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
import com.reporead.android.R
import com.reporead.android.core.network.ApiException
import com.reporead.android.core.network.describe
import com.reporead.android.data.AnnotationRow
import com.reporead.android.data.AnnotationState
import com.reporead.android.data.AnnotationType
import com.reporead.android.data.CardBlock
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

private fun status(row: AnnotationRow, displayedBlobSha: String, notShown: Set<String>): String? =
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
    annotations: List<AnnotationRow>,
    displayedBlobSha: String,
    notShown: Set<String>,
    sync: Sync,
    dao: LibraryDao,
    scope: CoroutineScope,
    onReveal: (AnnotationRow) -> Unit,
    onMessage: (String?) -> Unit,
    onMakeQuestion: (AnnotationRow) -> Unit,
    onReattach: (AnnotationRow) -> Unit,
    onOpenNote: (DocumentRow) -> Unit,
    modifier: Modifier,
) {
    var editing by remember { mutableStateOf<AnnotationRow?>(null) }
    var conflict by remember { mutableStateOf<Conflict?>(null) }
    // The highlight or card the user asked to delete, until they confirm.
    var deleting by remember { mutableStateOf<AnnotationRow?>(null) }
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
            Log.i(
                "RepoRead",
                "Linked from found; documentId=$documentId pagesWithLinks=${links.size} found=${found.size} ms=${System.currentTimeMillis() - started}",
            )
            value = Backlinks(found, dao.savedNoteCount(note.repositoryId), documents.size)
        }

    // Editing and deleting acknowledged highlights need the server; offline they fail visibly and change nothing.
    fun save(row: AnnotationRow, note: String?, expectedVersion: Int) = scope.launch {
        try {
            sync.editAnnotation(row, note, expectedVersion)
            onMessage(null)
        } catch (error: ApiException) {
            if (error.code == "ANNOTATION_CONFLICT") {
                try {
                    sync.refreshAnnotations(row.documentId)
                    dao.annotation(row.mutationId)?.let { conflict = Conflict(it, note) }
                        ?: onMessage("This highlight was deleted elsewhere.")
                } catch (refresh: ApiException) {
                    onMessage(
                        "This note was changed elsewhere, and the latest version could not be loaded. ${refresh.describe()}"
                    )
                }
            } else {
                onMessage("Edit not saved. ${error.describe()}")
            }
        }
    }

    // Online only: the server checks the card's answer against the version on screen.
    fun confirmCard(row: AnnotationRow) = scope.launch {
        try {
            sync.checkCard(row, displayedBlobSha)
            onMessage("Card confirmed for this version.")
        } catch (error: ApiException) {
            onMessage("Card not confirmed. ${error.describe()}")
        }
    }

    fun delete(row: AnnotationRow) = scope.launch {
        try {
            sync.deleteAnnotation(row)
            onMessage(null)
        } catch (error: ApiException) {
            onMessage(
                if (error.code == "ANNOTATION_CONFLICT")
                    "This highlight was changed elsewhere; sync and review it before deleting."
                else "Not deleted. ${error.describe()}"
            )
        }
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
                    status = status(row, displayedBlobSha, notShown),
                    block =
                        if (row.type == AnnotationType.CARD) row.cardBlock(removed, displayedBlobSha, displayedBlobSha)
                        else null,
                    onReveal = { onReveal(row) },
                    onMakeQuestion = { onMakeQuestion(row) },
                    onConfirmCard = { confirmCard(row) },
                    onReattach = { onReattach(row) },
                    onEdit = { editing = row },
                    onDelete = { deleting = row },
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

    editing?.let { row ->
        NoteDialog(
            title = "Edit note",
            quote = row.exactText,
            initial = row.note ?: "",
            onDismiss = { editing = null },
            onSave = { text ->
                editing = null
                save(row, text.ifBlank { null }, row.version)
            },
        )
    }
    deleting?.let { row ->
        DeleteDialog(
            row,
            onDismiss = { deleting = null },
            onConfirm = {
                deleting = null
                delete(row)
            },
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
            confirmButton = {
                TextButton(
                    onClick = {
                        conflict = null
                        save(current, mine, current.version)
                    }
                ) {
                    Text("Keep mine")
                }
            },
            dismissButton = { TextButton(onClick = { conflict = null }) { Text("Keep current") } },
        )
    }
}

/**
 * One highlight or card with what can be done with it. [shown]: drawn in the version on screen; [status] says where it
 * stands when that needs saying; [block] is why a card cannot be reviewed yet.
 */
@Composable
private fun AnnotationItem(
    row: AnnotationRow,
    shown: Boolean,
    status: String?,
    block: CardBlock?,
    onReveal: () -> Unit,
    onMakeQuestion: () -> Unit,
    onConfirmCard: () -> Unit,
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
        Row {
            if (shown) TextButton(onClick = onReveal) { Text("Show") }
            if (shown && row.type == AnnotationType.HIGHLIGHT)
                TextButton(onClick = onMakeQuestion) { Text("Make a question") }
            if (shown && block == CardBlock.UNCONFIRMED && row.serverId != null)
                TextButton(onClick = onConfirmCard) { Text("Answer is still right") }
        }
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

/** Asks before deleting [row], saying what goes with it. */
@Composable
private fun DeleteDialog(row: AnnotationRow, onDismiss: () -> Unit, onConfirm: () -> Unit) {
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
    label: String = "Note",
    allowBlank: Boolean = true,
) {
    var text by rememberSaveable(title, quote, initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
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
                    label = { Text(label) },
                )
            }
        },
        confirmButton = {
            TextButton(enabled = allowBlank || text.isNotBlank(), onClick = { onSave(text) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
