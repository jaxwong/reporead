// Owns: Reader screen orchestration, its entry's work and saved overlays; ReaderSession owns the page bridge and
// readiness.

package com.reporead.android.reader

import android.util.Log
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.reporead.android.OpenAt
import com.reporead.android.R
import com.reporead.android.Screen
import com.reporead.android.core.network.ApiException
import com.reporead.android.core.network.Load
import com.reporead.android.core.network.LoadContent
import com.reporead.android.core.network.describe
import com.reporead.android.core.network.wire.DocumentControllerChanges
import com.reporead.android.data.AnnotationRow
import com.reporead.android.data.DocumentRow
import com.reporead.android.data.LibraryDao
import com.reporead.android.data.Passage
import com.reporead.android.sync.Sync
import com.reporead.android.ui.AppBar
import com.reporead.android.ui.AppIcon
import java.io.Serializable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The displayed copy and its study model are published together, so reloads cannot use a previous version's blocks. */
internal data class StudyPage(val opened: Sync.Opened, val study: StudyNote)

/**
 * The one dialog or sheet over the note: at most one is open, and it is kept across recreation, with what was typed in
 * it. Serializable so a screen can keep it in saved state.
 */
internal sealed interface ReaderOverlay : Serializable {
    /** A new question is being written for [passage]. */
    data class Question(val passage: Passage) : ReaderOverlay

    /** A new note is being written for [passage]. */
    data class Note(val passage: Passage) : ReaderOverlay

    /** Keeps the annotation snapshot being edited across recreation. */
    data class EditNote(val row: AnnotationRow) : ReaderOverlay

    /** Keeps the exact annotation whose deletion is awaiting confirmation. */
    data class Delete(val row: AnnotationRow) : ReaderOverlay

    /** An edit the server refused because another edit landed first: the user picks which text to keep. */
    data class Conflict(val current: AnnotationRow, val mine: String?) : ReaderOverlay

    /** A tapped link to one note and its heading, previewed before opening. */
    data class Preview(val target: DocumentRow, val heading: String?) : ReaderOverlay

    /** Notes sharing the linked name, for the reader to pick one. */
    data class LinkChoices(val heading: String?, val choices: List<DocumentRow>) : ReaderOverlay

    /** Reserves the sole overlay for the page's current outline. */
    data object Outline : ReaderOverlay
}

/** What a reader entry keeps for process restoration. */
private const val SINCE = "since"
private const val REATTACHING = "reattaching"

/**
 * One reader entry's work ([entryId]), kept across rotation and while it is covered: the opened page, the summary of
 * changes since it was last read, the highlights' refresh and every change made to them, and what they reported. A
 * change the server or this phone accepted is committed even if the reader is left (Sync). [saved] restores the version
 * the summary starts from and the highlight being reattached.
 */
internal class ReaderViewModel(
    private val sync: Sync,
    private val dao: LibraryDao,
    private val saved: SavedStateHandle,
    private val documentId: Long,
    private val entryId: String,
) : ViewModel() {
    data class State(
        val page: Load<StudyPage> = Load.Loading,
        /**
         * “Changed since you last read” compares the version on screen with this one: the oldest version whose changes
         * were never shown, else the version last read; null when the note was never read. It is read once per opening,
         * before the reader marks the version on screen as read.
         */
        val since: String? = null,
        /** Null when there is nothing to compare: never read, signed out, or reading the same version again. */
        val changes: Load<DocumentControllerChanges>? = null,
        val message: String? = null,
        /** The highlight the user is placing by selecting its passage; the selection menu then offers only Reattach. */
        val reattaching: AnnotationRow? = null,
        /** A server change to a highlight runs; one at a time. */
        val changing: Boolean = false,
        /** An overlay an operation opens (a conflict, a followed link), taken once by the screen ([claimOverlay]). */
        val overlay: ReaderOverlay? = null,
    )

    private val current =
        MutableStateFlow(State(since = saved[SINCE], reattaching = saved.get<AnnotationRow>(REATTACHING)))
    val state: StateFlow<State> = current.asStateFlow()

    /** The sign-in state the screen last showed; null before it was first shown. */
    private var signedIn: Boolean? = null
    /** The (since, displayed) versions the summary was loaded for, and the ones the screen has shown. */
    private var compared: Pair<String, String>? = null
    private var acknowledged: Pair<String, String>? = null
    private var comparing: Job? = null

    init {
        open("created")
    }

    private fun open(reason: String) {
        Log.i("RepoRead", "Reader open started; entry=$entryId documentId=$documentId reason=$reason")
        viewModelScope.launch {
            if (!saved.contains(SINCE)) {
                val since = dao.changeBaseline(documentId) ?: dao.reading(documentId)?.lastReadBlobSha
                saved[SINCE] = since
                current.update { it.copy(since = since) }
            }
            val page =
                try {
                    val opened = sync.openNote(documentId)
                    Load.Ready(StudyPage(opened, withContext(Dispatchers.Default) { studyNote(opened.note.html) }))
                } catch (error: ApiException) {
                    Load.Failed(error)
                }
            current.update { it.copy(page = page) }
            compare(retry = false)
        }
    }

    fun retry() {
        val now = current.value
        if (now.page !is Load.Failed || !current.compareAndSet(now, now.copy(page = Load.Loading))) return
        open("retry")
    }

    /**
     * The screen is shown while [signedIn]: the highlights are refreshed when it is first shown and after a sign-in.
     */
    fun shown(signedIn: Boolean) {
        val was = this.signedIn
        this.signedIn = signedIn
        if (signedIn && was != true) refreshHighlights(if (was == null) "created" else "signedIn")
        if (signedIn != was) compare(retry = false)
    }

    private fun refreshHighlights(reason: String) {
        Log.i("RepoRead", "Reader highlights refresh started; entry=$entryId documentId=$documentId reason=$reason")
        viewModelScope.launch {
            try {
                sync.refreshAnnotations(documentId)
            } catch (error: ApiException) {
                showMessage("Showing highlights saved on this phone. ${error.describe()}")
            }
        }
    }

    /**
     * Loads the summary once per (since, displayed) pair while signed in; a newer pair supersedes a load still running.
     * Reading the same version again needs no summary, so its baseline is cleared at once.
     */
    private fun compare(retry: Boolean) {
        val now = current.value
        val from = now.since
        val displayed = (now.page as? Load.Ready)?.value?.opened?.note?.blobSha
        if (from == null || displayed == null || signedIn != true) {
            comparing?.cancel()
            compared = null
            current.update { it.copy(changes = null) }
            return
        }
        val versions = from to displayed
        if (versions == compared && !retry) return
        compared = versions
        comparing?.cancel()
        if (from == displayed) {
            current.update { it.copy(changes = null) }
            comparing = viewModelScope.launch { sync.changesSeen(documentId, from) }
            return
        }
        Log.i("RepoRead", "Reader changes load started; entry=$entryId documentId=$documentId retry=$retry")
        current.update { it.copy(changes = Load.Loading) }
        comparing = viewModelScope.launch {
            val changes =
                try {
                    Load.Ready(sync.changes(documentId, from, displayed))
                } catch (error: ApiException) {
                    Load.Failed(error)
                }
            current.update { it.copy(changes = changes) }
        }
    }

    fun retryChanges() = compare(retry = true)

    /**
     * The summary is on the screen: only now is its baseline cleared, so one fetched while the reader was covered is
     * still shown at the next opening if it never was. Offline or failing, the baseline stays for the next opening too.
     */
    fun changesShown() {
        val versions = compared ?: return
        if (current.value.changes !is Load.Ready || versions == acknowledged) return
        acknowledged = versions
        viewModelScope.launch { sync.changesSeen(documentId, versions.first) }
    }

    fun showMessage(text: String?) = current.update { it.copy(message = text) }

    /** The overlay an operation opened, once, for the screen to show. */
    fun claimOverlay(): ReaderOverlay? {
        val now = current.value
        val overlay = now.overlay ?: return null
        return if (current.compareAndSet(now, now.copy(overlay = null))) overlay else null
    }

    /**
     * Saves a highlight (or, with a question, a card) on this phone, then sends it now when signed in. The message says
     * where it stands: nothing when it reached the server.
     */
    fun save(passage: Passage, note: String?, question: String?) {
        Log.i(
            "RepoRead",
            "Reader highlight save started; entry=$entryId documentId=$documentId card=${question != null}",
        )
        viewModelScope.launch {
            val id = sync.createAnnotation(passage, documentId, note, question)
            val kind = if (question == null) "Highlight" else "Card"
            showMessage(
                if (id == null) "$kind not saved: this note is no longer saved on this phone."
                else if (signedIn != true) "$kind saved on this phone; it is sent after you sign in."
                else
                    try {
                        sync.pushAnnotations()
                        dao.annotation(id)?.rejection?.let {
                            "$kind kept on this phone only; the server refused it: $it"
                        }
                    } catch (error: ApiException) {
                        "$kind saved on this phone; it will sync later. ${error.describe()}"
                    }
            )
        }
    }

    private fun placing(row: AnnotationRow?) {
        saved[REATTACHING] = row
        current.update { it.copy(reattaching = row, message = null) }
    }

    fun startReattaching(row: AnnotationRow) = placing(row)

    fun stopReattaching() = placing(null)

    /** Starts one server change to a highlight ([what]); another while it runs starts nothing. */
    private fun change(what: String, row: AnnotationRow, work: suspend () -> String?) {
        val now = current.value
        if (now.changing || !current.compareAndSet(now, now.copy(changing = true))) {
            Log.i("RepoRead", "Reader $what not started; entry=$entryId mutationId=${row.mutationId} changing=true")
            return
        }
        Log.i("RepoRead", "Reader $what started; entry=$entryId mutationId=${row.mutationId}")
        viewModelScope.launch {
            val message = work()
            current.update { it.copy(changing = false, message = message) }
        }
    }

    /** Online: the server verifies the new selection against the version it was made on. */
    fun reattach(row: AnnotationRow, passage: Passage) =
        change("reattach", row) {
            try {
                sync.reattachAnnotation(row, passage)
                saved[REATTACHING] = null
                current.update { it.copy(reattaching = null) }
                null
            } catch (error: ApiException) {
                "Not reattached. ${error.describe()}"
            }
        }

    // Editing and deleting acknowledged highlights need the server; offline they fail visibly and change nothing.
    fun editNote(row: AnnotationRow, note: String?, expectedVersion: Int) =
        change("edit", row) {
            try {
                sync.editAnnotation(row, note, expectedVersion)
                null
            } catch (error: ApiException) {
                if (error.code != "ANNOTATION_CONFLICT") "Edit not saved. ${error.describe()}"
                else
                    try {
                        sync.refreshAnnotations(row.documentId)
                        dao.annotation(row.mutationId)?.let {
                            current.update { state -> state.copy(overlay = ReaderOverlay.Conflict(it, note)) }
                            null
                        } ?: "This highlight was deleted elsewhere."
                    } catch (refresh: ApiException) {
                        "This note was changed elsewhere, and the latest version could not be loaded. ${refresh.describe()}"
                    }
            }
        }

    fun delete(row: AnnotationRow) =
        change("delete", row) {
            try {
                sync.deleteAnnotation(row)
                null
            } catch (error: ApiException) {
                "Not deleted. ${error.describe()}"
            }
        }

    /** Online only: the server checks the card's answer against the version on screen, [displayedBlobSha]. */
    fun confirmCard(row: AnnotationRow, displayedBlobSha: String) =
        change("card confirmation", row) {
            try {
                sync.checkCard(row, displayedBlobSha)
                "Card confirmed for this version."
            } catch (error: ApiException) {
                "Card not confirmed. ${error.describe()}"
            }
        }

    fun setBookmark(bookmarked: Boolean) {
        viewModelScope.launch { sync.setBookmark(documentId, bookmarked) }
    }

    /** Resolved against this repository's saved note list, so links work offline too. */
    fun followNoteLink(target: String?, path: String?, heading: String?) {
        viewModelScope.launch {
            val from = dao.document(documentId)
            if (from == null) {
                showMessage(
                    "This note isn't in a saved note list, so its links can't be followed. Refresh the repository."
                )
                return@launch
            }
            val matches = resolveNoteLink(target, path, from.path, dao.listedDocuments(from.repositoryId))
            when (matches.size) {
                0 ->
                    showMessage(
                        "No note “${target ?: path}” in this repository's saved list. Refresh the repository if it is new."
                    )
                1 -> current.update { it.copy(overlay = ReaderOverlay.Preview(matches.first(), heading)) }
                else -> current.update { it.copy(overlay = ReaderOverlay.LinkChoices(heading, matches)) }
            }
        }
    }
}

@Composable
fun ReaderScreen(
    sync: Sync,
    dao: LibraryDao,
    appScope: CoroutineScope,
    signedIn: Boolean,
    entryId: String,
    screen: Screen.Reader,
    onBack: () -> Unit,
    push: (Screen) -> Unit,
) {
    val model = viewModel { ReaderViewModel(sync, dao, createSavedStateHandle(), screen.documentId, entryId) }
    val state by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(signedIn) { model.shown(signedIn) }
    val page = (state.page as? Load.Ready)?.value
    var studying by rememberSaveable(screen.documentId) { mutableStateOf(false) }
    // True once the screen's one-time target (a linked heading or a notebook passage) has been opened. Saved, so
    // rotating or coming back to this screen restores the reading position instead of jumping to the target again.
    var targetShown by rememberSaveable(screen.documentId) { mutableStateOf(false) }
    var changesExpanded by rememberSaveable(screen.documentId) { mutableStateOf(true) }
    val bookmark by dao.bookmark(screen.documentId).collectAsStateWithLifecycle(null)
    val annotations by dao.annotations(screen.documentId).collectAsStateWithLifecycle(emptyList())
    var notesOpen by rememberSaveable(screen.documentId) { mutableStateOf(false) }
    var overlay by rememberSaveable(screen.documentId) { mutableStateOf<ReaderOverlay?>(null) }
    // An overlay an operation opened becomes the screen's own, the one value ADR-25 keeps.
    LaunchedEffect(state.overlay) { model.claimOverlay()?.let { overlay = it } }
    val scope = rememberCoroutineScope()

    /** Closes [shown] if it is still open; false when a tap already closed it, so it acts only once. */
    fun close(shown: ReaderOverlay): Boolean {
        if (overlay != shown) return false
        overlay = null
        return true
    }

    fun useSelection(selection: Selection, action: SelectionAction) {
        // The menu was built for the state at the time; Cancel may have ended that mode since.
        val placing = state.reattaching
        when (selection) {
            Selection.None -> model.showMessage("Select some text first.")
            is Selection.Unusable -> model.showMessage(selection.reason)
            is Selection.InBlock ->
                when (action) {
                    SelectionAction.REATTACH ->
                        if (placing != null) model.reattach(placing, selection.passage)
                        else model.showMessage("Reattaching was cancelled; choose Reattach again in Notes.")
                    SelectionAction.MAKE_QUESTION -> overlay = ReaderOverlay.Question(selection.passage)
                    SelectionAction.ADD_NOTE -> overlay = ReaderOverlay.Note(selection.passage)
                    SelectionAction.HIGHLIGHT -> model.save(selection.passage, null, null)
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
                    onNoteLink = model::followNoteLink,
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
                    reattaching = { state.reattaching != null },
                    onMessage = model::showMessage,
                    onSelection = ::useSelection,
                )
            }
        }
    fun toggleStudy() {
        studying = !studying
        session?.setStudy(studying)
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
                        if (page.study.recognized) {
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
                        IconButton(onClick = { model.setBookmark(!marked) }) {
                            AppIcon(
                                if (marked) R.drawable.ic_bookmark else R.drawable.ic_bookmark_border,
                                if (marked) "Remove bookmark" else "Bookmark",
                            )
                        }
                    }
                },
            )
        }
        LoadContent(state.page, onRetry = model::retry) { ready ->
            val opened = ready.opened
            // The session was created from this same ready load.
            checkNotNull(session) { "No reader session for documentId=${screen.documentId}" }
            opened.staleReason?.let { Notice("Showing your saved copy. ${it.describe()}") }
            session.restoreNotice?.let { Notice(it) }
            state.message?.let { Notice(it) }
            val since = state.since
            if (since != null && since != opened.note.blobSha && !signedIn)
                Notice("This note changed since you last read it. Sign in to see what changed.")
            state.reattaching?.let { row ->
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Select the passage for “${row.exactText}”, then choose Reattach here.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = model::stopReattaching) { Text("Cancel") }
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
            state.changes?.let { comparison ->
                // Shown, not merely fetched: only then is the baseline cleared.
                if (comparison is Load.Ready)
                    LifecycleResumeEffect(comparison) {
                        model.changesShown()
                        onPauseOrDispose {}
                    }
                ChangesPanel(
                    comparison,
                    changesExpanded,
                    onToggle = { changesExpanded = !changesExpanded },
                    onRetry = model::retryChanges,
                    onOpen =
                        if (!session.opened) null
                        else
                            { section ->
                                changesExpanded = false
                                scope.launch {
                                    if (!session.showBlock(section.blockId))
                                        model.showMessage(
                                            "That section isn't in the version on screen; reopen the note."
                                        )
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
                    dao,
                    onReveal = { session.reveal(it.mutationId) },
                    onConfirmCard = { model.confirmCard(it, opened.note.blobSha) },
                    onMakeQuestion = { row -> overlay = ReaderOverlay.Question(row.drawn) },
                    onEdit = { row -> overlay = ReaderOverlay.EditNote(row) },
                    onDelete = { row -> overlay = ReaderOverlay.Delete(row) },
                    onReattach = { row ->
                        model.startReattaching(row)
                        notesOpen = false
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
                onDismiss = { overlay = null },
                onOpen = {
                    if (close(shown))
                        push(Screen.Reader(shown.target.id, shown.target.title, shown.heading?.let(OpenAt::Heading)))
                },
            )
        is ReaderOverlay.LinkChoices ->
            LinkChoicesDialog(
                shown.choices,
                onDismiss = { overlay = null },
                onChoose = { choice ->
                    if (close(shown)) push(Screen.Reader(choice.id, choice.title, shown.heading?.let(OpenAt::Heading)))
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
                onSave = { question -> if (close(shown)) model.save(shown.passage, null, question) },
            )
        is ReaderOverlay.Note ->
            NoteDialog(
                title = "Add a note",
                quote = shown.passage.exactText,
                initial = "",
                onDismiss = { overlay = null },
                onSave = { note -> if (close(shown)) model.save(shown.passage, note.ifBlank { null }, null) },
            )
        is ReaderOverlay.EditNote ->
            NoteDialog(
                title = "Edit note",
                quote = shown.row.exactText,
                initial = shown.row.note ?: "",
                onDismiss = { overlay = null },
                onSave = { text ->
                    if (close(shown)) model.editNote(shown.row, text.ifBlank { null }, shown.row.version)
                },
            )
        is ReaderOverlay.Delete ->
            DeleteDialog(
                shown.row,
                onDismiss = { overlay = null },
                onConfirm = { if (close(shown)) model.delete(shown.row) },
            )
        is ReaderOverlay.Conflict ->
            ConflictDialog(
                shown.current,
                shown.mine,
                onDismiss = { overlay = null },
                onKeepMine = {
                    if (close(shown)) model.editNote(shown.current, shown.mine, shown.current.version)
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
