package com.reporead.android.reader

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
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
import com.reporead.android.data.NoteRow
import com.reporead.android.data.Passage
import com.reporead.android.data.ReadingRow
import com.reporead.android.sync.Changes
import com.reporead.android.sync.Sync
import com.reporead.android.ui.AppBar
import com.reporead.android.ui.AppIcon
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

private const val NOTE_LINK = "/note-link"
/** The reader page's Copy link on a code block (reader.js addCodeTools); the text is then read with codeText(). */
private const val COPY_CODE = "/copy-code"
/** The reader page's Full screen link on a wide table or a diagram (reader.js addFigureTools). */
internal const val FULL_SCREEN = "/full-screen"
private const val SAVE_AFTER_SCROLL_MS = 700L
private const val SECTION_AFTER_SCROLL_MS = 150L
/** How far one swipe must scroll before the bars hide or come back, so small jitters do nothing. */
private const val BARS_SCROLL_DP = 24

/** The displayed copy and its study model are published together, so reloads cannot use a previous version's blocks. */
private data class StudyPage(val opened: Sync.Opened, val study: StudyNote)

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
    /*
     * The version whose changes the summary starts from (null: never read): the oldest version read whose changes have
     * not been shown yet, else the version last read. Captured once, before the reader saves the displayed version as
     * read, and kept across activity recreation; reopening the note captures it again.
     */
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
    /*
     * The screen's one-shot target — a linked heading or a notebook passage — has been shown. Saved,
     * so rotating or returning to this screen restores the reading position instead of jumping back to the target.
     */
    var targetShown by rememberSaveable(screen.documentId) { mutableStateOf(false) }
    var changesReload by remember { mutableIntStateOf(0) }
    var changesExpanded by rememberSaveable(screen.documentId) { mutableStateOf(true) }
    // Null when there is nothing to compare: never read, or reading the same version again. The baseline is used up
    // only once the server's answer is on screen; offline or failing, it is kept for the next opening.
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
    var notesOpen by remember { mutableStateOf(false) }
    /** The passage a new question is being written for. */
    var questionPassage by rememberSaveable(screen.documentId) { mutableStateOf<Passage?>(null) }
    /** The passage a new note is being written for. */
    var notePassage by remember { mutableStateOf<Passage?>(null) }
    /**
     * The highlight the user is placing by selecting its passage; the selection menu then offers only Reattach here.
     */
    var reattaching by remember { mutableStateOf<AnnotationRow?>(null) }
    /** A tapped link to one note and its heading, previewed before opening. */
    var preview by remember { mutableStateOf<Pair<DocumentRow, String?>?>(null) }
    /** Notes sharing the linked name, for the reader to pick one. */
    var linkChoices by remember { mutableStateOf<Pair<String?, List<DocumentRow>>?>(null) }
    val scope = rememberCoroutineScope()
    var outlineOpen by remember { mutableStateOf(false) }

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
                    if (error.code == "ANNOTATION_CONFLICT")
                        "This highlight was changed elsewhere; sync, reopen the note, and try again."
                    else "Not reattached. ${error.describe()}"
                }
        }
    }

    /** One session per displayed copy: it owns that page's view and everything the page reports. */
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
                    onNoteLink = { target, path, heading ->
                        scope.launch {
                            // Resolved against the saved note list of this note's repository, so links work offline
                            // too.
                            val from = dao.document(opened.note.documentId)
                            if (from == null) {
                                message =
                                    "This note isn't in a saved note list, so its links can't be followed. Refresh the repository."
                                return@launch
                            }
                            val matches =
                                resolveNoteLink(target, path, from.path, dao.listedDocuments(from.repositoryId))
                            when (matches.size) {
                                0 ->
                                    message =
                                        "No note “${target ?: path}” in this repository's saved list. Refresh the repository if it is new."
                                1 -> preview = matches.first() to heading
                                else -> linkChoices = heading to matches
                            }
                        }
                    },
                    onFigure = { figure ->
                        push(Screen.Figure(opened.note.documentId, opened.note.blobSha, figure, opened.note.title))
                    },
                    reattaching = { reattaching != null },
                    onMessage = { message = it },
                    onSelection = { selection, action ->
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
                                    SelectionAction.MAKE_QUESTION -> questionPassage = selection.passage
                                    SelectionAction.ADD_NOTE -> notePassage = selection.passage
                                    SelectionAction.HIGHLIGHT -> save(selection.passage, null, null)
                                }
                        }
                    },
                )
            }
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
                        IconButton(onClick = { outlineOpen = true }) { AppIcon(R.drawable.ic_toc, "Outline") }
                    }
                    if (opened != null) {
                        if (page?.study?.recognized == true) {
                            TextButton(
                                onClick = {
                                    studying = !studying
                                    session?.setStudy(studying)
                                }
                            ) {
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
                        IconButton(
                            onClick = { appScope.launch { sync.setBookmark(opened.note.documentId, !marked) } }
                        ) {
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
                    onOpen = { section ->
                        changesExpanded = false
                        scope.launch {
                            if (!session.showBlock(section.blockId))
                                message = "That section isn't in the version on screen; reopen the note."
                        }
                    },
                )
            }
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
                    sync,
                    dao,
                    appScope,
                    onReveal = { session.reveal(it.mutationId) },
                    onMessage = { message = it },
                    onMakeQuestion = { row -> questionPassage = row.drawn },
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
    val headings = session?.outline
    if (outlineOpen && session != null && !headings.isNullOrEmpty()) {
        OutlineSheet(
            headings,
            section,
            onDismiss = { outlineOpen = false },
            onJump = { entry ->
                outlineOpen = false
                scope.launch {
                    if (!session.showBlock(entry.blockId))
                        message = "Couldn't scroll to “${entry.text}”; reopen the note."
                }
            },
        )
    }
    preview?.let { (target, heading) ->
        LinkPreviewSheet(
            target,
            heading,
            sync,
            dao,
            onDismiss = { preview = null },
            onOpen = {
                preview = null
                push(Screen.Reader(target.id, target.title, heading?.let(OpenAt::Heading)))
            },
        )
    }
    linkChoices?.let { (heading, choices) ->
        AlertDialog(
            onDismissRequest = { linkChoices = null },
            title = { Text("Which note?") },
            text = {
                Column {
                    for (choice in choices) {
                        TextButton(
                            onClick = {
                                linkChoices = null
                                push(Screen.Reader(choice.id, choice.title, heading?.let(OpenAt::Heading)))
                            }
                        ) {
                            Text(choice.path)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { linkChoices = null }) { Text("Cancel") } },
        )
    }
    questionPassage?.let { passage ->
        NoteDialog(
            title = "Make a question",
            quote = passage.exactText,
            initial = "",
            label = "Question",
            allowBlank = false,
            onDismiss = { questionPassage = null },
            onSave = { question ->
                questionPassage = null
                save(passage, null, question)
            },
        )
    }
    notePassage?.let { passage ->
        NoteDialog(
            title = "Add a note",
            quote = passage.exactText,
            initial = "",
            onDismiss = { notePassage = null },
            onSave = { note ->
                notePassage = null
                save(passage, note.ifBlank { null }, null)
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

@Composable
private fun Notice(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/** The page's text selection when a selection action is chosen (reader.js capture). */
private sealed interface Selection {
    data object None : Selection

    /** Not inside one block, so it cannot be saved as it is; [reason] says how to fix it. */
    data class Unusable(val reason: String) : Selection

    data class InBlock(val passage: Passage) : Selection
}

/**
 * Runs [script] in the page and returns its result as JSONTokener decodes it: a String, Int, Boolean, JSONObject.NULL,
 * and so on.
 */
private suspend fun WebView.evaluate(script: String): Any? = suspendCancellableCoroutine { result ->
    evaluateJavascript(script) { encoded -> result.resume(JSONTokener(encoded).nextValue()) }
}

/**
 * One displayed note version in an isolated WebView. Repository content gets no credentials, no JavaScript bridge, and
 * no network, file, or content access; native code reads and restores the position through evaluateJavascript only.
 */
private class ReaderSession(
    private val note: NoteRow,
    private val sync: Sync,
    private val dao: LibraryDao,
    /** Never cancelled, so the position saved when the view is released reaches the database. */
    private val appScope: CoroutineScope,
    /** Where the page opens; null is where the note was last read. */
    private val openAt: OpenAt?,
    /** The page has opened at [openAt], found or not; never called for null. */
    private val onOpenedAtTarget: () -> Unit,
    private val study: StudyNote,
    private val studying: () -> Boolean,
    /**
     * A tapped link to another note: an Obsidian [target] name or a repository [path], and an optional heading. A link
     * to a heading of this note is followed here.
     */
    private val onNoteLink: (target: String?, path: String?, heading: String?) -> Unit,
    /** A tapped Full screen link: the page's id for that table or diagram. */
    private val onFigure: (String) -> Unit,
    /** Whether a highlight is being reattached, which changes the selection menu. */
    private val reattaching: () -> Boolean,
    /** A short message about something the page could not do (a link, the browser, rendering). */
    private val onMessage: (String) -> Unit,
    /** The selection when a selection action is chosen, and that action. */
    private val onSelection: (Selection, SelectionAction) -> Unit,
) {
    /** The page's headings, once rendering has finished; null until then. */
    var outline by mutableStateOf<List<OutlineEntry>?>(null)
        private set

    /** The index of the first block visible at the top, after scrolling settles and whenever the position is saved. */
    var topBlock by mutableIntStateOf(0)
        private set

    /** The highlights of this version that the page could not draw, by key. */
    var notShown by mutableStateOf(emptySet<String>())
        private set

    /** Why the page did not open exactly where it was meant to; null when it did, or before it opened. */
    var restoreNotice by mutableStateOf<String?>(null)
        private set

    /**
     * Whether the bars should show: false after the reader scrolls down, true after scrolling up or reaching the top.
     */
    var barsShown by mutableStateOf(true)
        private set

    private val main = Handler(Looper.getMainLooper())
    private var view: WebView? = null
    /**
     * The page has rendered and opened, so its scripts can be used and scrolling saves the position. Saving waits for
     * this so that the top of the page, shown while loading, never overwrites the saved position.
     */
    private var opened = false
    /** Opening the page, then keeping its highlights current; cancelled when the view is released. */
    private var opening: Job? = null
    private val saveAfterScroll = Runnable { savePosition() }
    private val sectionAfterScroll = Runnable { reportTopBlock() }
    /**
     * Only scrolling that follows a touch on the page moves the bars; jumps made by the app (restoring the position,
     * the outline, Show) clear it, so they never hide the bars.
     */
    private var touched = false
    /**
     * The bars change only when the finger lifts, by that swipe's net scroll (pixels, positive down). Moving the view
     * under a finger that is still down made the page read the move as more scrolling the other way, so the bars and
     * the page oscillated during the drag (seen on the Pixel 8a).
     */
    private var fingerDown = false
    private var swipeScroll = 0

    fun createView(context: Context): WebView {
        val assets = readerAssets(context)
        return ReaderWebView(context, reattaching) { action, finish ->
                val webView = openedView()
                if (webView == null) onMessage("The note is still loading; select the text again in a moment.")
                else
                    appScope.launch {
                        val selection = captureSelection(webView)
                        // A selection that cannot be used stays on screen with its handles, so it can be adjusted.
                        if (selection is Selection.InBlock) finish()
                        onSelection(selection, action)
                    }
            }
            .apply {
                isolateReaderPage()
                webViewClient =
                    object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            // The page itself never navigates away. A tapped link is opened by the app: a note
                            // link as a new reader screen, a web or email link in another app.
                            if (!request.hasGesture()) return true
                            val url = request.url
                            if (url.host == ASSET_HOST) {
                                when (url.path) {
                                    NOTE_LINK -> {
                                        val link = noteLinkParameters(url.encodedQuery.orEmpty())
                                        val target = link["target"]
                                        val path = link["path"]
                                        val heading = link["heading"]
                                        if (target != null || path != null) onNoteLink(target, path, heading)
                                        else if (heading != null)
                                            appScope.launch {
                                                if (!showHeading(heading))
                                                    onMessage("No heading “$heading” in this note.")
                                            }
                                    }
                                    COPY_CODE ->
                                        url.getQueryParameter("block")?.let { appScope.launch { copyCode(it) } }
                                    FULL_SCREEN -> url.getQueryParameter("figure")?.let(onFigure)
                                }
                                return true
                            }
                            if (url.scheme == "https" || url.scheme == "http" || url.scheme == "mailto") {
                                try {
                                    view.context.startActivity(
                                        Intent(Intent.ACTION_VIEW, url).addCategory(Intent.CATEGORY_BROWSABLE)
                                    )
                                } catch (error: ActivityNotFoundException) {
                                    Log.w("RepoRead", "No app for an external note link; scheme=${url.scheme}")
                                    onMessage(
                                        if (url.scheme == "mailto") "No email app is installed to open this link."
                                        else "No browser is installed to open this link."
                                    )
                                }
                            }
                            return true
                        }

                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
                            readerResource(assets, sync.images, note, request.url)

                        override fun onPageFinished(view: WebView, url: String) {
                            opening?.cancel()
                            opening = appScope.launch { open(view) }
                        }
                    }
                val barsThreshold = (BARS_SCROLL_DP * context.resources.displayMetrics.density).toInt()
                setOnTouchListener { view, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            touched = true
                            fingerDown = true
                            swipeScroll = 0
                        }
                        MotionEvent.ACTION_UP,
                        MotionEvent.ACTION_CANCEL -> {
                            fingerDown = false
                            when {
                                view.scrollY <= barsThreshold || swipeScroll < -barsThreshold -> barsShown = true
                                swipeScroll > barsThreshold -> barsShown = false
                            }
                        }
                    }
                    false
                }
                setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
                    main.removeCallbacks(saveAfterScroll)
                    main.removeCallbacks(sectionAfterScroll)
                    if (opened) {
                        main.postDelayed(saveAfterScroll, SAVE_AFTER_SCROLL_MS)
                        main.postDelayed(sectionAfterScroll, SECTION_AFTER_SCROLL_MS)
                    }
                    if (fingerDown) swipeScroll += scrollY - oldScrollY
                    // A fling that reaches the top brings the bars back; nothing else changes them without a finger
                    // lifting.
                    else if (touched && scrollY <= barsThreshold) barsShown = true
                }
                view = this
                loadDataWithBaseURL("$ASSET_ORIGIN/", note.html, "text/html", "UTF-8", null)
            }
    }

    private fun openedView(): WebView? = view?.takeIf { opened }

    /**
     * Waits for the page to render, opens it at [openAt] or where the note was last read, then saves that position and
     * keeps the drawn highlights current until the view is released.
     */
    private suspend fun open(view: WebView) {
        val state = suspendCancellableCoroutine { rendered ->
            awaitRendered(view, main) { state, _ -> rendered.resume(state) }
        }
        if (state != "ready") {
            // A page that failed to render shows why itself.
            Log.w("RepoRead", "Reader did not render; position not saved; documentId=${note.documentId} state=$state")
            if (state == "timeout")
                onMessage(
                    "This note did not finish rendering, so highlights and your reading position are off here. Reopen it to try again."
                )
            return
        }
        val configured =
            view.evaluate(
                "window.reporead.configureStudy(${study.json()}); window.reporead.setStudy(${studying()}); true"
            )
        check(configured == true) { "Study layout failed; documentId=${note.documentId} blobSha=${note.blobSha}" }
        Log.i("RepoRead", "Reader ready; documentId=${note.documentId} viewHeight=${view.height}")
        outline = parseOutline(view.evaluate("JSON.stringify(window.reporead.outline())") as String)
        val highlights = dao.annotations(note.documentId)
        // Drawn before opening, so that a highlight to open at is on the page.
        drawHighlights(view, highlights.first())
        restoreNotice =
            when (openAt) {
                null -> restoreSaved(view)
                is OpenAt.Heading -> {
                    val found = view.evaluate("window.reporead.showHeading(${JSONObject.quote(openAt.text)})") == true
                    Log.i("RepoRead", "Reader opened at a linked heading; documentId=${note.documentId} found=$found")
                    if (found) null else "No heading “${openAt.text}” in this note; showing the top."
                }
                is OpenAt.Highlight -> {
                    val found = view.evaluate("window.reporead.reveal(${JSONObject.quote(openAt.mutationId)})") == true
                    Log.i("RepoRead", "Reader opened at a highlight; documentId=${note.documentId} found=$found")
                    if (found) null
                    else "That passage isn't marked in this version of the note; Highlights and notes says why."
                }
            }
        if (openAt != null) onOpenedAtTarget()
        opened = true
        save(view)
        // Room sends the list drawn above again first, then every change: a new highlight, an edit, a sync.
        highlights.collect { drawHighlights(view, it) }
    }

    /** Restores where the note was last read; returns what to tell the reader when that place could not be found. */
    private suspend fun restoreSaved(view: WebView): String? {
        val saved = dao.reading(note.documentId) ?: return null
        val mode = view.evaluate("window.reporead.restore(${saved.anchorJson}, ${saved.progressPercent})") as String
        Log.i(
            "RepoRead",
            "Reader restored; documentId=${note.documentId} mode=$mode savedPercent=${saved.progressPercent} " +
                "savedBlock=${JSONObject(saved.anchorJson).getInt("blockIndex")} viewHeight=${view.height}",
        )
        return when {
            mode == "collapsed" -> "Resumed at a collapsed study section; tap Show / hide section to reveal it."
            mode == "section" -> "Resumed at the start of the section you were reading; the exact passage changed."
            mode == "block" || mode == "percent" ->
                "Resumed near your last position; the exact passage could not be found."
            saved.lastReadBlobSha != note.blobSha ->
                "This note changed since you last read it; resumed at the same passage."
            else -> null
        }
    }

    /** Saves the position in the version on screen. */
    private suspend fun save(view: WebView) {
        main.removeCallbacks(saveAfterScroll)
        val position = JSONObject(view.evaluate("JSON.stringify(window.reporead.position())") as String)
        val anchor = position.getJSONObject("anchor")
        val block = anchor.getInt("blockIndex")
        topBlock = block
        val row =
            ReadingRow(
                note.documentId,
                note.blobSha,
                position.getInt("progressPercent"),
                anchor.toString(),
                System.currentTimeMillis(),
                pending = true,
            )
        sync.saveReading(row)
        Log.i(
            "RepoRead",
            "Reading position saved; documentId=${note.documentId} percent=${row.progressPercent} block=$block " +
                "viewHeight=${view.height}",
        )
    }

    /** Saves the position now, as when the screen stops: process death can follow. */
    fun savePosition() {
        val webView = openedView() ?: return
        appScope.launch { save(webView) }
    }

    private fun reportTopBlock() {
        val webView = openedView() ?: return
        appScope.launch { topBlock = webView.evaluate("window.reporead.position().anchor.blockIndex") as Int }
    }

    /** Saves the final position, then destroys the view; later lifecycle events no longer touch it. */
    fun release(webView: WebView) {
        main.removeCallbacks(saveAfterScroll)
        main.removeCallbacks(sectionAfterScroll)
        opening?.cancel()
        val wasOpened = opened
        view = null
        opened = false
        appScope.launch {
            if (wasOpened) save(webView)
            webView.destroy()
        }
    }

    /** Draws each highlight whose current location is in the displayed version; others are listed, not drawn. */
    private suspend fun drawHighlights(view: WebView, rows: List<AnnotationRow>) {
        val payload =
            JSONArray(
                rows
                    .filter { it.drawn.blobSha == note.blobSha }
                    .map {
                        val passage = it.drawn
                        JSONObject()
                            .put("key", it.mutationId)
                            .put("blockId", passage.blockId)
                            .put("startOffset", passage.startOffset)
                            .put("endOffset", passage.endOffset)
                            .put("exactText", passage.exactText)
                    }
            )
        val missing = JSONArray(view.evaluate("JSON.stringify(window.reporead.highlight($payload))") as String)
        notShown = List(missing.length()) { missing.getString(it) }.toSet()
    }

    /**
     * Scrolls to a changed section's heading block in this version, or the top for null; false when it is not there.
     */
    suspend fun showBlock(blockId: String?): Boolean {
        val webView = openedView() ?: return false
        touched = false
        val shown =
            webView.evaluate(
                "window.reporead.showBlock(${if (blockId == null) "null" else JSONObject.quote(blockId)})"
            ) == true
        if (!shown)
            Log.w(
                "RepoRead",
                "Changed section not found; documentId=${note.documentId} blobSha=${note.blobSha} blockId=$blockId",
            )
        return shown
    }

    /** Scrolls to the first heading whose text is [text] (ignoring case), as Obsidian heading links do. */
    private suspend fun showHeading(text: String): Boolean {
        val webView = openedView() ?: return false
        touched = false
        return webView.evaluate("window.reporead.showHeading(${JSONObject.quote(text)})") == true
    }

    fun reveal(key: String) {
        val webView = openedView() ?: return
        touched = false
        webView.evaluateJavascript("window.reporead.reveal(${JSONObject.quote(key)})", null)
    }

    fun setStudy(enabled: Boolean) {
        val webView = openedView() ?: return
        touched = false
        Log.i("RepoRead", "Study view changed; documentId=${note.documentId} enabled=$enabled")
        appScope.launch {
            webView.evaluate("window.reporead.setStudy($enabled)")
            save(webView)
        }
    }

    /** Android 13+ confirms clipboard writes itself, so the reader shows nothing more. */
    private suspend fun copyCode(blockId: String) {
        val webView = openedView() ?: return
        // A link in the note's own text can name /copy-code with something that is not a code block.
        val text = webView.evaluate("window.reporead.codeText(${JSONObject.quote(blockId)})") as? String
        if (text == null) {
            Log.w("RepoRead", "Copy link names no code block; documentId=${note.documentId} block=$blockId")
            return
        }
        val clipboard = webView.context.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("Code", text))
        Log.i("RepoRead", "Code copied; documentId=${note.documentId} block=$blockId chars=${text.length}")
    }

    /**
     * The page's selection. A broken canonical mapping is an invariant failure, raised here, never an approximate
     * anchor.
     */
    private suspend fun captureSelection(view: WebView): Selection {
        val encoded =
            view.evaluate(
                "(() => { try { return JSON.stringify({selection: window.reporead.capture()}); } " +
                    "catch (error) { return JSON.stringify({invariant: error.message}); } })()"
            ) as String
        val result = JSONObject(encoded)
        check(!result.has("invariant")) {
            "Selection capture failed; documentId=${note.documentId}: ${result.getString("invariant")}"
        }
        val selection = result.optJSONObject("selection") ?: return Selection.None
        if (selection.has("error")) return Selection.Unusable(selection.getString("error"))
        return Selection.InBlock(
            Passage(
                selection.getString("sourceBlobSha"),
                selection.getString("blockId"),
                selection.getInt("startOffset"),
                selection.getInt("endOffset"),
                selection.getString("exactText"),
            )
        )
    }
}
