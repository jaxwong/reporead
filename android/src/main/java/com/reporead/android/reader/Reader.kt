package com.reporead.android.reader

import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.reporead.android.OpenAt
import com.reporead.android.R
import com.reporead.android.Screen
import com.reporead.android.core.network.ApiException
import com.reporead.android.core.network.Load
import com.reporead.android.core.network.LoadContent
import com.reporead.android.core.network.describe
import com.reporead.android.core.network.rememberLoad
import com.reporead.android.data.AnnotationRow
import com.reporead.android.data.DocumentRow
import com.reporead.android.data.LibraryDao
import com.reporead.android.data.Passage
import com.reporead.android.sync.Changes
import com.reporead.android.sync.Sync
import com.reporead.android.ui.AppBar
import com.reporead.android.ui.AppIcon
import java.io.Serializable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The displayed copy and its study model are published together, so reloads cannot use a previous version's blocks. */
private data class StudyPage(val opened: Sync.Opened, val study: StudyNote)

/**
 * The one dialog or sheet over the note: at most one is open, and it is kept across recreation, with what was typed in
 * it. Serializable so a screen can keep it in saved state.
 */
private sealed interface ReaderOverlay : Serializable {
    /** A new question is being written for [passage]. */
    data class Question(val passage: Passage) : ReaderOverlay

    /** A new note is being written for [passage]. */
    data class Note(val passage: Passage) : ReaderOverlay

    data class EditNote(val row: AnnotationRow) : ReaderOverlay

    data class Delete(val row: AnnotationRow) : ReaderOverlay

    /** An edit the server refused because another edit landed first: the user picks which text to keep. */
    data class Conflict(val current: AnnotationRow, val mine: String?) : ReaderOverlay

    /** A tapped link to one note and its heading, previewed before opening. */
    data class Preview(val target: DocumentRow, val heading: String?) : ReaderOverlay

    /** Notes sharing the linked name, for the reader to pick one. */
    data class LinkChoices(val heading: String?, val choices: List<DocumentRow>) : ReaderOverlay

    data object Outline : ReaderOverlay
}

@Composable
fun ReaderScreen(
    sync: Sync,
    dao: LibraryDao,
    appScope: CoroutineScope,
    signedIn: Boolean,
    screen: Screen.Reader,
    onBack: () -> Unit,
    push: (Screen) -> Unit,
) {
    var reload by remember { mutableIntStateOf(0) }
    // “Changed since you last read” compares the version on screen with this one: the oldest version whose changes were
    // never shown, else the version last read; null when the note was never read. It is read once per opening, before
    // the reader marks the version on screen as read, and kept across rotation.
    var since by rememberSaveable(screen.documentId) { mutableStateOf<String?>(null) }
    var sinceCaptured by rememberSaveable(screen.documentId) { mutableStateOf(false) }
    val load by
        rememberLoad(screen.documentId to reload) {
            if (!sinceCaptured) {
                since = dao.changeBaseline(screen.documentId) ?: dao.reading(screen.documentId)?.lastReadBlobSha
                sinceCaptured = true
            }
            val opened = sync.openNote(screen.documentId)
            StudyPage(opened, withContext(Dispatchers.Default) { studyNote(opened.note.html) })
        }
    val page = (load as? Load.Ready)?.value
    val displayed = page?.opened?.note?.blobSha
    var studying by rememberSaveable(screen.documentId) { mutableStateOf(false) }
    // True once the screen's one-time target (a linked heading or a notebook passage) has been opened. Saved, so
    // rotating or coming back to this screen restores the reading position instead of jumping to the target again.
    var targetShown by rememberSaveable(screen.documentId) { mutableStateOf(false) }
    var changesReload by remember { mutableIntStateOf(0) }
    var changesExpanded by rememberSaveable(screen.documentId) { mutableStateOf(true) }
    // Null when there is nothing to compare: never read, or reading the same version again. The baseline is cleared
    // only after the server's comparison is shown; offline or failing, it stays for the next opening.
    val changes by
        produceState<Load<Changes>?>(null, since, displayed, signedIn, changesReload) {
            val from = since
            if (from == null || displayed == null || !signedIn) {
                value = null
                return@produceState
            }
            if (from == displayed) {
                dao.changesSeen(screen.documentId, from)
                value = null
                return@produceState
            }
            value = Load.Loading
            value =
                try {
                    Load.Ready(sync.changes(screen.documentId, from, displayed)).also {
                        dao.changesSeen(screen.documentId, from)
                    }
                } catch (error: ApiException) {
                    Load.Failed(error)
                }
        }
    val bookmark by dao.bookmark(screen.documentId).collectAsState(null)
    val annotations by dao.annotations(screen.documentId).collectAsState(emptyList())
    var message by remember { mutableStateOf<String?>(null) }
    var notesOpen by rememberSaveable(screen.documentId) { mutableStateOf(false) }
    var overlay by rememberSaveable(screen.documentId) { mutableStateOf<ReaderOverlay?>(null) }
    // The highlight the user is placing by selecting its passage; the selection menu then offers only Reattach here. A
    // mode, not an overlay: the outline and links stay usable while the passage is found.
    var reattaching by rememberSaveable(screen.documentId) { mutableStateOf<AnnotationRow?>(null) }
    val scope = rememberCoroutineScope()

    if (signedIn) {
        LaunchedEffect(screen.documentId) {
            try {
                sync.refreshAnnotations(screen.documentId)
            } catch (error: ApiException) {
                message = "Showing highlights saved on this phone. ${error.describe()}"
            }
        }
    }
    /**
     * Saves a highlight (or, with a question, a card) on this phone, then sends it now when signed in. The message says
     * where it stands: nothing when it reached the server.
     */
    val save: (passage: Passage, note: String?, question: String?) -> Unit = { passage, note, question ->
        appScope.launch {
            val id = sync.createAnnotation(passage, screen.documentId, note, question)
            val kind = if (question == null) "Highlight" else "Card"
            message =
                if (!signedIn) "$kind saved on this phone; it is sent after you sign in."
                else
                    try {
                        sync.pushAnnotations()
                        dao.annotation(id)?.rejection?.let {
                            "$kind kept on this phone only; the server refused it: $it"
                        }
                    } catch (error: ApiException) {
                        "$kind saved on this phone; it will sync later. ${error.describe()}"
                    }
        }
    }

    val reattach: (AnnotationRow, Passage) -> Unit = { row, passage ->
        appScope.launch {
            message =
                try {
                    sync.reattachAnnotation(row, passage)
                    reattaching = null
                    null
                } catch (error: ApiException) {
                    "Not reattached. ${error.describe()}"
                }
        }
    }

    // Editing and deleting acknowledged highlights need the server; offline they fail visibly and change nothing.
    fun editNote(row: AnnotationRow, note: String?, expectedVersion: Int) {
        appScope.launch {
            message =
                try {
                    sync.editAnnotation(row, note, expectedVersion)
                    null
                } catch (error: ApiException) {
                    if (error.code != "ANNOTATION_CONFLICT") "Edit not saved. ${error.describe()}"
                    else
                        try {
                            sync.refreshAnnotations(row.documentId)
                            dao.annotation(row.mutationId)?.let {
                                overlay = ReaderOverlay.Conflict(it, note)
                                null
                            } ?: "This highlight was deleted elsewhere."
                        } catch (refresh: ApiException) {
                            "This note was changed elsewhere, and the latest version could not be loaded. ${refresh.describe()}"
                        }
                }
        }
    }

    fun delete(row: AnnotationRow) {
        appScope.launch {
            message =
                try {
                    sync.deleteAnnotation(row)
                    null
                } catch (error: ApiException) {
                    "Not deleted. ${error.describe()}"
                }
        }
    }

    // Resolved against this repository's saved note list, so links work offline too.
    fun followNoteLink(target: String?, path: String?, heading: String?) {
        scope.launch {
            val from = dao.document(screen.documentId)
            if (from == null) {
                message =
                    "This note isn't in a saved note list, so its links can't be followed. Refresh the repository."
                return@launch
            }
            val matches = resolveNoteLink(target, path, from.path, dao.listedDocuments(from.repositoryId))
            when (matches.size) {
                0 ->
                    message =
                        "No note “${target ?: path}” in this repository's saved list. Refresh the repository if it is new."
                1 -> overlay = ReaderOverlay.Preview(matches.first(), heading)
                else -> overlay = ReaderOverlay.LinkChoices(heading, matches)
            }
        }
    }

    fun useSelection(selection: Selection, action: SelectionAction) {
        // The menu was built for the state at the time; Cancel may have ended that mode since.
        val placing = reattaching
        when (selection) {
            Selection.None -> message = "Select some text first."
            is Selection.Unusable -> message = selection.reason
            is Selection.InBlock ->
                when (action) {
                    SelectionAction.REATTACH ->
                        if (placing != null) reattach(placing, selection.passage)
                        else message = "Reattaching was cancelled; choose Reattach again in Notes."
                    SelectionAction.MAKE_QUESTION -> overlay = ReaderOverlay.Question(selection.passage)
                    SelectionAction.ADD_NOTE -> overlay = ReaderOverlay.Note(selection.passage)
                    SelectionAction.HIGHLIGHT -> save(selection.passage, null, null)
                }
        }
    }

    // One session per displayed copy: it owns that page's view and everything the page reports.
    val session =
        remember(page?.opened?.note) {
            page?.let { ready ->
                val opened = ready.opened
                ReaderSession(
                    opened.note,
                    sync,
                    dao,
                    appScope,
                    openAt = screen.openAt.takeUnless { targetShown },
                    onOpenedAtTarget = {
                        targetShown = true
                        // A passage opened from the notebook or a review is shown with the note's highlights.
                        if (screen.openAt is OpenAt.Highlight) notesOpen = true
                    },
                    study = ready.study,
                    studying = { studying },
                    onNoteLink = ::followNoteLink,
                    onFigure = { figure ->
                        push(
                            Screen.Figure(
                                opened.note.documentId,
                                opened.note.blobSha,
                                figure,
                                opened.note.title,
                                opened.note.snapshotCommitSha,
                            )
                        )
                    },
                    reattaching = { reattaching != null },
                    onMessage = { message = it },
                    onSelection = ::useSelection,
                )
            }
        }
    fun toggleStudy() {
        studying = !studying
        session?.setStudy(studying)
    }

    fun toggleBookmark() {
        val marked = bookmark?.bookmarked == true
        appScope.launch { sync.setBookmark(screen.documentId, !marked) }
    }

    val barsShown = session?.barsShown ?: true
    ReadingBars(barsShown)
    val section = session?.let { it.outline?.sectionAt(it.topBlock) }

    Column(Modifier.fillMaxSize()) {
        androidx.compose.animation.AnimatedVisibility(barsShown) {
            AppBar(
                screen.title,
                subtitle = section?.text?.ifBlank { null },
                onBack = onBack,
                actions = {
                    val opened = page?.opened
                    if (!session?.outline.isNullOrEmpty()) {
                        IconButton(onClick = { overlay = ReaderOverlay.Outline }) {
                            AppIcon(R.drawable.ic_toc, "Outline")
                        }
                    }
                    if (opened != null) {
                        if (page?.study?.recognized == true) {
                            // Only once the page is ready: before, it would change nothing on the page.
                            TextButton(enabled = session?.opened == true, onClick = ::toggleStudy) {
                                Text(if (studying) "Read" else "Study")
                            }
                        }
                        IconButton(onClick = { notesOpen = !notesOpen }) {
                            BadgedBox(badge = { if (annotations.isNotEmpty()) Badge { Text("${annotations.size}") } }) {
                                AppIcon(
                                    R.drawable.ic_notes,
                                    if (notesOpen) "Hide highlights and notes" else "Highlights and notes",
                                )
                            }
                        }
                        val marked = bookmark?.bookmarked == true
                        IconButton(onClick = ::toggleBookmark) {
                            AppIcon(
                                if (marked) R.drawable.ic_bookmark else R.drawable.ic_bookmark_border,
                                if (marked) "Remove bookmark" else "Bookmark",
                            )
                        }
                    }
                },
            )
        }
        LoadContent(load, onRetry = { reload++ }) { ready ->
            val opened = ready.opened
            // The session was created from this same ready load.
            checkNotNull(session) { "No reader session for documentId=${screen.documentId}" }
            opened.staleReason?.let { Notice("Showing your saved copy. $it") }
            session.restoreNotice?.let { Notice(it) }
            message?.let { Notice(it) }
            if (since != null && since != opened.note.blobSha && !signedIn)
                Notice("This note changed since you last read it. Sign in to see what changed.")
            reattaching?.let { row ->
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Select the passage for “${row.exactText}”, then choose Reattach here.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { reattaching = null }) { Text("Cancel") }
                }
            }
            val lifecycle = LocalLifecycleOwner.current.lifecycle
            DisposableEffect(lifecycle, session) {
                // Process death can follow ON_STOP, so the position is captured whenever the reader leaves the screen.
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_STOP) session.savePosition()
                }
                lifecycle.addObserver(observer)
                onDispose { lifecycle.removeObserver(observer) }
            }
            changes?.let { comparison ->
                ChangesPanel(
                    comparison,
                    changesExpanded,
                    onToggle = { changesExpanded = !changesExpanded },
                    onRetry = { changesReload++ },
                    onOpen =
                        if (!session.opened) null
                        else
                            { section ->
                                changesExpanded = false
                                scope.launch {
                                    if (!session.showBlock(section.blockId))
                                        message = "That section isn't in the version on screen; reopen the note."
                                }
                            },
                )
            }
            if (!session.stopped)
                AndroidView(
                    factory = { context -> session.createView(context) },
                    onRelease = { view -> session.release(view) },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            if (notesOpen) {
                NotesPanel(
                    opened.note.documentId,
                    annotations,
                    opened.note.blobSha,
                    session.notShown,
                    session.opened,
                    sync,
                    dao,
                    appScope,
                    onReveal = { session.reveal(it.mutationId) },
                    onMessage = { message = it },
                    onMakeQuestion = { row -> overlay = ReaderOverlay.Question(row.drawn) },
                    onEdit = { row -> overlay = ReaderOverlay.EditNote(row) },
                    onDelete = { row -> overlay = ReaderOverlay.Delete(row) },
                    onReattach = { row ->
                        reattaching = row
                        notesOpen = false
                        message = null
                    },
                    onOpenNote = { from -> push(Screen.Reader(from.id, from.title)) },
                    modifier = Modifier.fillMaxWidth().weight(0.7f),
                )
            }
        }
    }
    when (val shown = overlay) {
        null -> {}
        ReaderOverlay.Outline -> {
            // Restored before the recreated page has its outline: shown once it does.
            val headings = session?.outline
            if (session != null && !headings.isNullOrEmpty())
                OutlineSheet(
                    headings,
                    section,
                    onDismiss = { overlay = null },
                    onJump = { entry ->
                        overlay = null
                        scope.launch {
                            if (!session.showBlock(entry.blockId))
                                message = "Couldn't scroll to “${entry.text}”; reopen the note."
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
                onDismiss = { overlay = null },
                onOpen = {
                    overlay = null
                    push(Screen.Reader(shown.target.id, shown.target.title, shown.heading?.let(OpenAt::Heading)))
                },
            )
        is ReaderOverlay.LinkChoices ->
            LinkChoicesDialog(
                shown.choices,
                onDismiss = { overlay = null },
                onChoose = { choice ->
                    overlay = null
                    push(Screen.Reader(choice.id, choice.title, shown.heading?.let(OpenAt::Heading)))
                },
            )
        is ReaderOverlay.Question ->
            NoteDialog(
                title = "Make a question",
                quote = shown.passage.exactText,
                initial = "",
                label = "Question",
                allowBlank = false,
                onDismiss = { overlay = null },
                onSave = { question ->
                    overlay = null
                    save(shown.passage, null, question)
                },
            )
        is ReaderOverlay.Note ->
            NoteDialog(
                title = "Add a note",
                quote = shown.passage.exactText,
                initial = "",
                onDismiss = { overlay = null },
                onSave = { note ->
                    overlay = null
                    save(shown.passage, note.ifBlank { null }, null)
                },
            )
        is ReaderOverlay.EditNote ->
            NoteDialog(
                title = "Edit note",
                quote = shown.row.exactText,
                initial = shown.row.note ?: "",
                onDismiss = { overlay = null },
                onSave = { text ->
                    overlay = null
                    editNote(shown.row, text.ifBlank { null }, shown.row.version)
                },
            )
        is ReaderOverlay.Delete ->
            DeleteDialog(
                shown.row,
                onDismiss = { overlay = null },
                onConfirm = {
                    overlay = null
                    delete(shown.row)
                },
            )
        is ReaderOverlay.Conflict ->
            ConflictDialog(
                shown.current,
                shown.mine,
                onDismiss = { overlay = null },
                onKeepMine = {
                    overlay = null
                    editNote(shown.current, shown.mine, shown.current.version)
                },
            )
    }
}

/**
 * The system bars follow the reader's top bar: hidden while reading down the note, shown briefly by a swipe from the
 * edge, and always shown again when the reader is left.
 */
@Composable
private fun ReadingBars(shown: Boolean) {
    val activity = LocalActivity.current ?: error("ReaderScreen requires an activity")
    val bars = activity.window.insetsController ?: error("ReaderScreen requires a window insets controller")
    DisposableEffect(bars) {
        bars.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose { bars.show(WindowInsets.Type.systemBars()) }
    }
    LaunchedEffect(shown) {
        if (shown) bars.show(WindowInsets.Type.systemBars()) else bars.hide(WindowInsets.Type.systemBars())
    }
}

/** Several notes share a link's name: the reader picks one by its path. */
@Composable
private fun LinkChoicesDialog(choices: List<DocumentRow>, onDismiss: () -> Unit, onChoose: (DocumentRow) -> Unit) {
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

@Composable
private fun Notice(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
}
