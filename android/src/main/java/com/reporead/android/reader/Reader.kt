// Owns: Reader screen orchestration, its entry's work and saved overlays; ReaderSession owns the displayed page and
// when it is ready, and ReaderPageHosting.kt owns the page's isolation, resources and script bridge.

package com.reporead.android.reader

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.reporead.android.OpenAt
import com.reporead.android.Screen
import com.reporead.android.database.AnnotationEntity
import com.reporead.android.database.NewHighlight
import com.reporead.android.database.Passage
import com.reporead.android.database.RepoReadDao
import com.reporead.android.database.highlightOn
import com.reporead.android.logging.DeviceLog
import com.reporead.android.network.ApiException
import com.reporead.android.network.describe
import com.reporead.android.sync.PhoneSync
import com.reporead.android.ui.Load
import java.io.Serializable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val FIND_TYPING_PAUSE_MS = 150L

/** The displayed copy and its study model are published together, so reloads cannot use a previous version's blocks. */
internal data class OpenedReaderPage(val opened: PhoneSync.Opened, val study: StudyModeAnalysis)

/** What a reader entry keeps for process restoration. */
private const val ANNOTATION_TO_REATTACH = "annotationToReattach"
private const val EDIT = "edit"

/** Only the user's submitted input is restored; the annotation is read again, and no mutation is replayed. */
private data class SubmittedEdit(val mutationId: String, val text: String?) : Serializable

/**
 * One reader entry's work ([entryId]), kept across rotation and while it is covered: the opened page, the highlights'
 * refresh and every change made to them, and what they reported. A change the server or this phone accepted is
 * committed even if the reader is left (PhoneSync). [saved] restores the highlight being reattached and a submitted
 * edit.
 */
internal class ReaderViewModel(
    private val sync: PhoneSync,
    private val dao: RepoReadDao,
    private val saved: SavedStateHandle,
    private val documentId: Long,
    private val entryId: String,
) : ViewModel() {
    data class State(
        val page: Load<OpenedReaderPage> = Load.Loading,
        val message: String? = null,
        /** The highlight the user is placing by selecting its passage; the selection menu then offers only Reattach. */
        val annotationToReattach: AnnotationEntity? = null,
        /** A highlight operation runs; one at a time. */
        val changing: Boolean = false,
        val conflict: ReaderConflict? = null,
        /** A followed link or recovered editor, taken once by the screen ([claimOverlay]). */
        val overlay: ReaderOverlay? = null,
    )

    private val _state =
        MutableStateFlow(State(annotationToReattach = saved.get<AnnotationEntity>(ANNOTATION_TO_REATTACH)))
    val state: StateFlow<State> = _state.asStateFlow()

    /** The sign-in state the screen last showed; null before it was first shown. */
    private var signedIn: Boolean? = null

    init {
        open("created")
        saved.get<SubmittedEdit>(EDIT)?.let { draft ->
            DeviceLog.info("Reader edit restored; entry=$entryId mutationId=${draft.mutationId}")
            _state.update { it.copy(changing = true) }
            viewModelScope.launch {
                val annotation = dao.annotation(draft.mutationId)
                if (annotation == null || annotation.note == draft.text) saved[EDIT] = null
                _state.update {
                    it.copy(
                        changing = false,
                        conflict =
                            if (annotation != null && annotation.note != draft.text)
                                ReaderConflict(annotation, draft.text)
                            else null,
                        message =
                            when {
                                annotation == null -> "The edited highlight is no longer on this phone."
                                annotation.note == draft.text -> "The previous edit is saved on this phone."
                                else ->
                                    "The previous edit was interrupted. Check the saved note before submitting again."
                            },
                    )
                }
            }
        }
    }

    private fun open(reason: String) {
        DeviceLog.info("Reader open started; entry=$entryId documentId=$documentId reason=$reason")
        viewModelScope.launch {
            val page =
                try {
                    val opened = sync.openNote(documentId)
                    Load.Ready(
                        OpenedReaderPage(
                            opened,
                            withContext(Dispatchers.Default) { analyzeStudyMode(opened.note.html) },
                        )
                    )
                } catch (failure: ApiException) {
                    Load.Failed(failure)
                }
            _state.update { it.copy(page = page) }
        }
    }

    fun retry() {
        val stateSnapshot = _state.value
        if (
            stateSnapshot.page !is Load.Failed ||
                !_state.compareAndSet(stateSnapshot, stateSnapshot.copy(page = Load.Loading))
        )
            return
        open("retry")
    }

    /**
     * The screen is shown while [signedIn]: the highlights are refreshed when it is first shown and after a sign-in.
     */
    fun shown(signedIn: Boolean) {
        val was = this.signedIn
        this.signedIn = signedIn
        if (signedIn && was != true) refreshHighlights(if (was == null) "created" else "signedIn")
    }

    private fun refreshHighlights(reason: String) {
        DeviceLog.info("Reader highlights refresh started; entry=$entryId documentId=$documentId reason=$reason")
        viewModelScope.launch {
            try {
                sync.sendAndLoadAnnotations(documentId)
            } catch (failure: ApiException) {
                showMessage("Showing highlights saved on this phone. ${failure.describe()}")
            }
        }
    }

    fun showMessage(text: String?) = _state.update { it.copy(message = text) }

    /** The overlay an operation opened, once, for the screen to show. */
    fun claimOverlay(): ReaderOverlay? {
        val stateSnapshot = _state.value
        val overlay = stateSnapshot.overlay ?: return null
        if (!_state.compareAndSet(stateSnapshot, stateSnapshot.copy(overlay = null))) return null
        if (overlay is ReaderOverlay.EditNote) {
            DeviceLog.info("Reader edit returned to editor; entry=$entryId mutationId=${overlay.annotation.mutationId}")
            saved[EDIT] = null
        }
        return overlay
    }

    /**
     * Saves a highlight on this phone, then sends it now when signed in. The message says where it stands: nothing when
     * it reached the server. A phrase already highlighted keeps its one highlight.
     */
    fun save(passage: Passage, note: String?) {
        DeviceLog.info("Reader highlight save started; entry=$entryId documentId=$documentId")
        viewModelScope.launch {
            val saved = sync.saveNewHighlight(passage, documentId, note)
            showMessage(
                when (saved) {
                    NewHighlight.PageGone -> "Highlight not saved: this note is no longer saved on this phone."
                    is NewHighlight.AlreadyHighlighted ->
                        if (note == null) "This passage is already highlighted."
                        else "Note not saved: this passage is already highlighted. Edit its note instead."
                    is NewHighlight.Saved ->
                        if (signedIn != true) "Highlight saved on this phone; it is sent after you sign in."
                        else
                            try {
                                sync.sendAnnotations()
                                dao.annotation(saved.mutationId)?.rejection?.let {
                                    "Highlight kept on this phone only; the server refused it: $it"
                                }
                            } catch (failure: ApiException) {
                                "Highlight saved on this phone; it will sync later. ${failure.describe()}"
                            }
                }
            )
        }
    }

    private fun placing(annotation: AnnotationEntity?) {
        saved[ANNOTATION_TO_REATTACH] = annotation
        _state.update { it.copy(annotationToReattach = annotation, message = null) }
    }

    fun startReattaching(annotation: AnnotationEntity) = placing(annotation)

    fun stopReattaching() = placing(null)

    /** Starts one server change to a highlight ([what]); another while it runs starts nothing. */
    private fun change(what: String, annotation: AnnotationEntity, work: suspend () -> String?): Boolean {
        val stateSnapshot = _state.value
        if (
            stateSnapshot.changing ||
                stateSnapshot.conflict != null ||
                stateSnapshot.overlay is ReaderOverlay.EditNote ||
                !_state.compareAndSet(stateSnapshot, stateSnapshot.copy(changing = true, message = null))
        ) {
            DeviceLog.info("Reader $what not started; entry=$entryId mutationId=${annotation.mutationId} changing=true")
            showMessage("Finish the current highlight change before submitting another.")
            return false
        }
        DeviceLog.info("Reader $what started; entry=$entryId mutationId=${annotation.mutationId}")
        viewModelScope.launch {
            val message = work()
            _state.update { it.copy(changing = false, message = message) }
        }
        return true
    }

    /** Online: the server verifies the new selection against the version it was made on. */
    fun reattach(annotation: AnnotationEntity, passage: Passage) =
        change("reattach", annotation) {
            try {
                sync.reattachAnnotation(annotation, passage)
                saved[ANNOTATION_TO_REATTACH] = null
                _state.update { it.copy(annotationToReattach = null) }
                null
            } catch (failure: ApiException) {
                "Not reattached. ${failure.describe()}"
            }
        }

    // Editing and deleting acknowledged highlights need the server; offline they fail visibly and change nothing.
    fun editNote(annotation: AnnotationEntity, note: String?, expectedVersion: Int) =
        change("edit", annotation) {
            saved[EDIT] = SubmittedEdit(annotation.mutationId, note)
            DeviceLog.info("Reader edit input retained; entry=$entryId mutationId=${annotation.mutationId}")
            try {
                sync.editAnnotation(annotation, note, expectedVersion)
                DeviceLog.info("Reader edit committed; entry=$entryId mutationId=${annotation.mutationId}")
                saved[EDIT] = null
                null
            } catch (failure: ApiException) {
                DeviceLog.info(
                    "Reader edit not committed; entry=$entryId mutationId=${annotation.mutationId} code=${failure.code}"
                )
                if (failure.code != "ANNOTATION_CONFLICT") {
                    _state.update { it.copy(overlay = ReaderOverlay.EditNote(annotation, note.orEmpty())) }
                    "Edit not saved. ${failure.describe()}"
                } else
                    try {
                        sync.sendAndLoadAnnotations(annotation.documentId)
                        val latest = dao.annotation(annotation.mutationId)
                        if (latest == null) {
                            saved[EDIT] = null
                            "This highlight was deleted elsewhere."
                        } else {
                            _state.update { it.copy(conflict = ReaderConflict(latest, note)) }
                            null
                        }
                    } catch (refresh: ApiException) {
                        _state.update { it.copy(overlay = ReaderOverlay.EditNote(annotation, note.orEmpty())) }
                        "This note was changed elsewhere, and the latest version could not be loaded. ${refresh.describe()}"
                    }
            }
        }

    fun dismissConflict() {
        DeviceLog.info("Reader conflict dismissed; entry=$entryId conflict=${_state.value.conflict != null}")
        saved[EDIT] = null
        _state.update { it.copy(conflict = null) }
    }

    fun keepMine() {
        val conflict = _state.value.conflict ?: return
        _state.update { it.copy(conflict = null) }
        editNote(
            conflict.latestAnnotation,
            conflict.submittedNote,
            conflict.latestAnnotation.version,
        )
    }

    fun delete(annotation: AnnotationEntity) =
        change("delete", annotation) {
            try {
                sync.deleteAnnotation(annotation)
                null
            } catch (failure: ApiException) {
                "Not deleted. ${failure.describe()}"
            }
        }

    fun setBookmark(bookmarked: Boolean) {
        viewModelScope.launch {
            if (!sync.setBookmark(documentId, bookmarked))
                showMessage("Bookmark not saved: this note is no longer saved on this phone.")
        }
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
                1 -> _state.update { it.copy(overlay = ReaderOverlay.Preview(matches.first(), heading)) }
                else -> _state.update { it.copy(overlay = ReaderOverlay.LinkChoices(heading, matches)) }
            }
        }
    }
}

@Composable
fun ReaderScreen(
    sync: PhoneSync,
    dao: RepoReadDao,
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
    val bookmark by dao.bookmark(screen.documentId).collectAsStateWithLifecycle(null)
    val annotations by dao.annotations(screen.documentId).collectAsStateWithLifecycle(emptyList())
    var notesOpen by rememberSaveable(screen.documentId) { mutableStateOf(false) }
    var overlay by rememberSaveable(screen.documentId) { mutableStateOf<ReaderOverlay?>(null) }
    var findOpen by rememberSaveable(screen.documentId) { mutableStateOf(false) }
    var findQuery by
        rememberSaveable(screen.documentId) {
            mutableStateOf((screen.openAt as? OpenAt.Search)?.passage?.exactText.orEmpty())
        }
    var requestedFindIndex by rememberSaveable(screen.documentId) { mutableStateOf(0) }
    // An overlay an operation opened becomes the screen's own, the one value ADR-25 keeps.
    LaunchedEffect(state.overlay, overlay) {
        if (overlay == null) model.claimOverlay()?.let { overlay = it }
    }
    val scope = rememberCoroutineScope()

    /** Closes [shown] if it is still open; false when a tap already closed it, so it acts only once. */
    fun close(shown: ReaderOverlay): Boolean {
        if (overlay != shown) return false
        overlay = null
        return true
    }

    fun useSelection(selection: Selection, action: SelectionAction) {
        // The menu was built for the state at the time; Cancel may have ended that mode since.
        val placing = state.annotationToReattach
        when (selection) {
            Selection.None -> model.showMessage("Select some text first.")
            is Selection.Unusable -> model.showMessage(selection.reason)
            is Selection.InBlock ->
                when (action) {
                    SelectionAction.REATTACH ->
                        if (placing != null) model.reattach(placing, selection.passage)
                        else model.showMessage("Reattaching was cancelled; choose Reattach again in Notes.")
                    // A note for a phrase that is already highlighted goes into that highlight's note.
                    SelectionAction.ADD_NOTE -> {
                        val existing = highlightOn(annotations, selection.passage)
                        when {
                            existing == null -> overlay = ReaderOverlay.Note(selection.passage)
                            existing.acknowledged -> overlay = ReaderOverlay.EditNote(existing)
                            else ->
                                model.showMessage(
                                    "This passage is already highlighted; its note can be edited once the server accepts it."
                                )
                        }
                    }
                    SelectionAction.HIGHLIGHT -> model.save(selection.passage, null)
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
                    isReattaching = { state.annotationToReattach != null },
                    onMessage = model::showMessage,
                    onSelection = ::useSelection,
                )
            }
        }
    fun toggleStudy() {
        studying = !studying
        session?.setStudy(studying)
    }

    fun closeFind() {
        findOpen = false
        scope.launch { session?.clearFind() }
    }

    BackHandler(findOpen, ::closeFind)
    LaunchedEffect(findOpen, findQuery, requestedFindIndex, session?.opened) {
        if (!findOpen || session?.opened != true) return@LaunchedEffect
        if (findQuery.isEmpty()) {
            session.clearFind()
            return@LaunchedEffect
        }
        delay(FIND_TYPING_PAUSE_MS)
        session.find(findQuery, requestedFindIndex)
    }

    val barsShown = session?.barsShown ?: true
    ReadingBars(barsShown || findOpen)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    if (session != null) {
        DisposableEffect(lifecycle, session) {
            // Process death can follow ON_STOP, so the position is captured whenever the reader leaves the screen.
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) session.savePosition()
            }
            lifecycle.addObserver(observer)
            onDispose { lifecycle.removeObserver(observer) }
        }
    }

    Column(Modifier.fillMaxSize()) {
        androidx.compose.animation.AnimatedVisibility(barsShown || findOpen) {
            Column {
                ReaderToolbar(
                    title = screen.title,
                    page = page,
                    session = session,
                    annotations = annotations,
                    bookmark = bookmark,
                    studying = studying,
                    notesOpen = notesOpen,
                    onBack = if (findOpen) ::closeFind else onBack,
                    onOutline = { overlay = ReaderOverlay.Outline },
                    onFind = { findOpen = true },
                    onToggleStudy = ::toggleStudy,
                    onToggleNotes = { notesOpen = !notesOpen },
                    onSetBookmark = model::setBookmark,
                )
                if (findOpen) {
                    val result = session?.findResult?.takeIf { it.query == findQuery }
                    ReaderFindBar(
                        findQuery,
                        result,
                        onQuery = {
                            findQuery = it
                            requestedFindIndex = 0
                        },
                        onPrevious = {
                            if (result != null && result.count > 0)
                                requestedFindIndex = (result.index - 1 + result.count) % result.count
                        },
                        onNext = {
                            if (result != null && result.count > 0)
                                requestedFindIndex = (result.index + 1) % result.count
                        },
                        onClose = ::closeFind,
                    )
                }
            }
        }
        ReaderContent(
            documentId = screen.documentId,
            state = state,
            session = session,
            notesOpen = notesOpen,
            annotations = annotations,
            dao = dao,
            model = model,
            onOverlay = { overlay = it },
            onReattach = { annotation ->
                model.startReattaching(annotation)
                notesOpen = false
            },
            push = push,
        )
    }
    ReaderOverlayHost(
        overlay = overlay,
        state = state,
        session = session,
        sync = sync,
        dao = dao,
        model = model,
        scope = scope,
        push = push,
        isOpen = { overlay == it },
        close = ::close,
    )
}
