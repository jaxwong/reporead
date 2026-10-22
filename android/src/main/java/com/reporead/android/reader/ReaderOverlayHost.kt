package com.reporead.android.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.reporead.android.OpenAt
import com.reporead.android.Screen
import com.reporead.android.database.AnnotationEntity
import com.reporead.android.database.DocumentEntity
import com.reporead.android.database.Passage
import com.reporead.android.database.RepoReadDao
import com.reporead.android.sync.PhoneSync
import java.io.Serializable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** The one saved dialog or sheet over a note, including the input needed to restore it after recreation. */
internal sealed interface ReaderOverlay : Serializable {
    /** A new note is being written for [passage]. */
    data class Note(val passage: Passage) : ReaderOverlay

    /** An unsubmitted editor; [initial] also recovers a submitted edit that failed. */
    data class EditNote(
        val annotation: AnnotationEntity,
        val initial: String = annotation.note.orEmpty(),
    ) : ReaderOverlay

    /** Keeps the exact annotation whose deletion is awaiting confirmation. */
    data class Delete(val annotation: AnnotationEntity) : ReaderOverlay

    /** A tapped link to one note and its heading, previewed before opening. */
    data class Preview(val target: DocumentEntity, val heading: String?) : ReaderOverlay

    /** Notes sharing the linked name, for the reader to pick one. */
    data class LinkChoices(val heading: String?, val choices: List<DocumentEntity>) : ReaderOverlay

    /** Reserves the sole overlay for the page's current outline. */
    data object Outline : ReaderOverlay
}

internal data class ReaderConflict(
    val latestAnnotation: AnnotationEntity,
    val submittedNote: String?,
)

/** Renders the screen-owned overlay and forwards its operations to the existing ReaderViewModel owner. */
@Composable
internal fun ReaderOverlayHost(
    overlay: ReaderOverlay?,
    state: ReaderViewModel.State,
    session: ReaderSession?,
    sync: PhoneSync,
    dao: RepoReadDao,
    model: ReaderViewModel,
    scope: CoroutineScope,
    push: (Screen) -> Unit,
    isOpen: (ReaderOverlay) -> Boolean,
    close: (ReaderOverlay) -> Boolean,
) {
    when (val shown = overlay) {
        null -> {}
        ReaderOverlay.Outline -> {
            // Restored before the recreated page has its outline: shown once it does.
            val headings = session?.outline
            if (session != null && !headings.isNullOrEmpty())
                OutlineSheet(
                    headings,
                    session.outline?.sectionAt(session.topBlock),
                    onDismiss = { close(shown) },
                    onJump = { entry ->
                        if (close(shown))
                            scope.launch {
                                if (!session.showBlock(entry.blockId))
                                    model.showMessage("Couldn't scroll to “${entry.text}”; reopen the note.")
                            }
                    },
                )
        }
        is ReaderOverlay.Preview ->
            LinkPreviewSheet(
                shown.target,
                shown.heading,
                sync,
                dao,
                onDismiss = { close(shown) },
                onOpen = {
                    if (close(shown))
                        push(Screen.Reader(shown.target.id, shown.target.title, shown.heading?.let(OpenAt::Heading)))
                },
            )
        is ReaderOverlay.LinkChoices ->
            LinkChoicesDialog(
                shown.choices,
                onDismiss = { close(shown) },
                onChoose = { choice ->
                    if (close(shown)) push(Screen.Reader(choice.id, choice.title, shown.heading?.let(OpenAt::Heading)))
                },
            )
        is ReaderOverlay.Note ->
            NoteDialog(
                title = "Add a note",
                quote = shown.passage.exactText,
                initial = "",
                onDismiss = { close(shown) },
                onSave = { note -> if (close(shown)) model.save(shown.passage, note.ifBlank { null }) },
            )
        is ReaderOverlay.EditNote ->
            NoteDialog(
                title = "Edit note",
                quote = shown.annotation.exactText,
                initial = shown.initial,
                status = state.message,
                onDismiss = { close(shown) },
                onSave = { text ->
                    if (
                        isOpen(shown) &&
                            model.editNote(
                                shown.annotation,
                                text.ifBlank { null },
                                shown.annotation.version,
                            )
                    )
                        close(shown)
                },
            )
        is ReaderOverlay.Delete ->
            DeleteDialog(
                shown.annotation,
                onDismiss = { close(shown) },
                onConfirm = { if (isOpen(shown) && model.delete(shown.annotation)) close(shown) },
            )
    }
    if (overlay == null)
        state.conflict?.let { pendingConflict ->
            ConflictDialog(
                pendingConflict.latestAnnotation,
                pendingConflict.submittedNote,
                onDismiss = model::dismissConflict,
                onKeepMine = model::keepMine,
            )
        }
}

/** Several notes share a link's name: the reader picks one by its path. */
@Composable
private fun LinkChoicesDialog(
    choices: List<DocumentEntity>,
    onDismiss: () -> Unit,
    onChoose: (DocumentEntity) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Which note?") },
        text = {
            Column {
                for (choice in choices) TextButton(onClick = { onChoose(choice) }) { Text(choice.path) }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
