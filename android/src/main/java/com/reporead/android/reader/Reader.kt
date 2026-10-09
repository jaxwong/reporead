package com.reporead.android.reader

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.IconButton
import com.reporead.android.R
import com.reporead.android.Screen
import com.reporead.android.ui.AppBar
import com.reporead.android.ui.AppIcon
import com.reporead.android.core.network.ApiException
import com.reporead.android.core.network.LoadContent
import com.reporead.android.core.network.rememberLoad
import com.reporead.android.data.LibraryDao
import com.reporead.android.core.network.Load
import com.reporead.android.core.network.describe
import com.reporead.android.data.AnnotationRow
import com.reporead.android.data.NoteRow
import com.reporead.android.data.ReadingRow
import com.reporead.android.sync.Changes
import com.reporead.android.sync.Sync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

private const val NOTE_LINK = "/note-link"
/** The reader page's Copy link on a code block (reader.js addCodeTools); the text is then read with codeText(). */
private const val COPY_CODE = "/copy-code"
/** The reader page's Full screen link on a wide table or a diagram (reader.js addFigureTools). */
private const val FULL_SCREEN = "/full-screen"
private const val SAVE_AFTER_SCROLL_MS = 700L
private const val SECTION_AFTER_SCROLL_MS = 150L
/** How far one swipe must scroll before the bars hide or come back, so small jitters do nothing. */
private const val BARS_SCROLL_DP = 24

/** The displayed copy and its study model are published together, so reloads cannot use a previous version's blocks. */
private data class StudyPage(val opened: Sync.Opened, val study: StudyNote)

@Composable
fun ReaderScreen(sync: Sync, dao: LibraryDao, appScope: CoroutineScope, signedIn: Boolean, onFailure: (ApiException) -> Unit,
                 screen: Screen.Reader, onBack: () -> Unit, push: (Screen) -> Unit) {
    var reload by remember { mutableIntStateOf(0) }
    /*
     * The version last read when the note was opened (null: never read). Captured once, before the reader saves the
     * displayed version as read, and kept across activity recreation; reopening the note captures it again.
     */
    var since by rememberSaveable(screen.documentId) { mutableStateOf<String?>(null) }
    var sinceCaptured by rememberSaveable(screen.documentId) { mutableStateOf(false) }
    val load by rememberLoad(screen.documentId to reload, onFailure) {
        if (!sinceCaptured) {
            since = dao.reading(screen.documentId)?.lastReadBlobSha
            sinceCaptured = true
        }
        val opened = sync.openNote(screen.documentId)
        StudyPage(opened, withContext(Dispatchers.Default) { studyNote(opened.note.html) })
    }
    val displayed = (load as? Load.Ready)?.value?.opened?.note?.blobSha
    val study = (load as? Load.Ready)?.value?.study
    var studying by rememberSaveable(screen.documentId) { mutableStateOf(screen.question != null) }
    var questionOpened by rememberSaveable(screen.documentId) { mutableStateOf(false) }
    var changesReload by remember { mutableIntStateOf(0) }
    var changesExpanded by rememberSaveable(screen.documentId) { mutableStateOf(true) }
    // Null when there is nothing to compare: never read, or reading the same version again.
    val changes by produceState<Load<Changes>?>(null, since, displayed, signedIn, changesReload) {
        val from = since
        if (from == null || displayed == null || from == displayed || !signedIn) {
            value = null
            return@produceState
        }
        value = Load.Loading
        value = try {
            Load.Ready(sync.changes(screen.documentId, from, displayed))
        } catch (error: ApiException) {
            onFailure(error)
            Load.Failed(error)
        }
    }
    val bookmark by dao.bookmark(screen.documentId).collectAsState(null)
    val annotations by dao.annotations(screen.documentId).collectAsState(emptyList())
    var restoreNotice by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var notesOpen by remember { mutableStateOf(false) }
    var answerPrompt by rememberSaveable(screen.documentId) { mutableStateOf(screen.reviewPrompt) }
    var newCardSelection by rememberSaveable(screen.documentId) { mutableStateOf<String?>(null) }
    var newCardPrompt by rememberSaveable(screen.documentId) { mutableStateOf<String?>(null) }
    var annotationOpened by rememberSaveable(screen.documentId) { mutableStateOf(false) }
    var newSelection by remember { mutableStateOf<JSONObject?>(null) }
    var notShown by remember { mutableStateOf(emptySet<String>()) }
    /** The highlight the user is placing by selecting its passage; the selection menu then offers only Reattach here. */
    var reattaching by remember { mutableStateOf<AnnotationRow?>(null) }
    /** A tapped link to one note and its heading, previewed before opening. */
    var preview by remember { mutableStateOf<Pair<com.reporead.android.data.DocumentRow, String?>?>(null) }
    /** Notes sharing the linked name, for the reader to pick one. */
    var linkChoices by remember { mutableStateOf<Pair<String?, List<com.reporead.android.data.DocumentRow>>?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    /** The displayed page's session, for scrolling to a heading linked from the same note. */
    var sessionRef by remember { mutableStateOf<ReaderSession?>(null) }
    /** The displayed page's headings (null until it has rendered) and the first block at the top of the screen. */
    var outline by remember(displayed) { mutableStateOf<List<OutlineEntry>?>(null) }
    var topBlock by remember(displayed) { mutableIntStateOf(0) }
    var outlineOpen by remember { mutableStateOf(false) }
    var barsShown by remember { mutableStateOf(true) }
    ReadingBars(barsShown)
    val section = outline?.sectionAt(topBlock)

    if (signedIn) {
        LaunchedEffect(screen.documentId) {
            try {
                sync.refreshAnnotations(screen.documentId)
            } catch (error: ApiException) {
                onFailure(error)
                message = "Showing highlights saved on this phone. ${error.describe()}"
            }
        }
    }
    val create: (JSONObject, String?) -> Unit = { selection, note ->
        appScope.launch {
            sync.createAnnotation(selection, screen.documentId, note)
            message = try {
                sync.pushAnnotations()
                null
            } catch (error: ApiException) {
                onFailure(error)
                "Highlight saved on this phone; it will sync later. ${error.describe()}"
            }
        }
    }

    val makeCard: (JSONObject, String) -> Unit = { selection, question ->
        appScope.launch {
            withContext(NonCancellable) { sync.createAnnotation(selection, screen.documentId, null, question) }
            answerPrompt = null
            message = "Card saved on this phone; waiting for Library sync."
            if (signedIn) {
                try { sync.pushAnnotations() }
                catch (error: ApiException) {
                    onFailure(error)
                    message = "Card saved on this phone; it will sync later. ${error.describe()}"
                }
            }
        }
    }

    val reattach: (AnnotationRow, JSONObject) -> Unit = { row, selection ->
        appScope.launch {
            message = try {
                sync.reattachAnnotation(row, selection)
                reattaching = null
                null
            } catch (error: ApiException) {
                onFailure(error)
                if (error.code == "ANNOTATION_CONFLICT") "This highlight was changed elsewhere; sync, reopen the note, and try again."
                else "Not reattached. ${error.describe()}"
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        androidx.compose.animation.AnimatedVisibility(barsShown) {
            AppBar(screen.title, subtitle = section?.text?.ifBlank { null }, onBack = onBack, actions = {
                val opened = (load as? Load.Ready)?.value?.opened
                if (!outline.isNullOrEmpty()) {
                    IconButton(onClick = { outlineOpen = true }) { AppIcon(R.drawable.ic_toc, "Outline") }
                }
                if (opened != null) {
                    if (study?.recognized == true) {
                        TextButton(onClick = {
                            studying = !studying
                            sessionRef?.setStudy(studying)
                        }) { Text(if (studying) "Read" else "Study") }
                    }
                    IconButton(onClick = { notesOpen = !notesOpen }) {
                        BadgedBox(badge = { if (annotations.isNotEmpty()) Badge { Text("${annotations.size}") } }) {
                            AppIcon(R.drawable.ic_notes, if (notesOpen) "Hide highlights and notes" else "Highlights and notes")
                        }
                    }
                    val marked = bookmark?.bookmarked == true
                    IconButton(onClick = { appScope.launch { sync.setBookmark(opened.note, !marked) } }) {
                        AppIcon(if (marked) R.drawable.ic_bookmark else R.drawable.ic_bookmark_border, if (marked) "Remove bookmark" else "Bookmark")
                    }
                }
            })
        }
        LoadContent(load, onRetry = { reload++ }) { page ->
            val opened = page.opened
            opened.staleReason?.let { Notice("Showing your saved copy. $it") }
            restoreNotice?.let { Notice(it) }
            message?.let { Notice(it) }
            if (since != null && since != opened.note.blobSha && !signedIn) Notice("This note changed since you last read it. Sign in to see what changed.")
            answerPrompt?.let { prompt ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Question: $prompt\nSelect its answer passage, then choose Use as answer.",
                        modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { answerPrompt = null }) { Text("Cancel") }
                }
            }
            if (studying && page.study.questions.isNotEmpty()) {
                TextButton(onClick = {
                    sessionRef?.currentQuestion { blockId ->
                        val prompt = page.study.questions.firstOrNull { it.blockId == blockId }?.prompt
                        if (prompt != null) {
                            answerPrompt = prompt
                            studying = false
                            sessionRef?.setStudy(false)
                        } else message = "Choose a question from Practice to add it to review."
                    }
                }) { Text("Add question to review") }
            }
            reattaching?.let { row ->
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Select the passage for “${row.exactText}”, then choose Reattach here.", style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = { reattaching = null }) { Text("Cancel") }
                }
            }
            val lifecycle = LocalLifecycleOwner.current.lifecycle
            val session = remember(opened.note) {
                ReaderSession(opened.note, sync, dao, appScope, openAtHeading = screen.heading,
                    study = page.study, studying = { studying },
                    openAtAnnotation = screen.annotation.takeUnless { annotationOpened },
                    onAnnotationOpened = { annotationOpened = true; notesOpen = true }, openAtQuestion = screen.question.takeUnless { questionOpened },
                    onQuestionOpened = { questionOpened = true },
                    onQuestionMissing = { studying = false },
                    onRestoreNotice = { restoreNotice = it },
                    onNoteLink = { target, path, heading ->
                        scope.launch {
                            // Resolved against the saved note list of this note's repository, so links work offline too.
                            val from = dao.document(opened.note.documentId)
                            when {
                                target == null && path == null -> heading?.let { text ->
                                    sessionRef?.showHeading(text) { shown -> if (!shown) message = "No heading “$text” in this note." }
                                }
                                from == null -> message = "This note isn't in a saved note list, so its links can't be followed. Refresh the repository."
                                else -> {
                                    val matches = resolveNoteLink(target, path, from.path, dao.documentsOnce(from.repositoryId))
                                    when (matches.size) {
                                        0 -> message = "No note “${target ?: path}” in this repository's saved list. Refresh the repository if it is new."
                                        1 -> preview = matches.first() to heading
                                        else -> linkChoices = heading to matches
                                    }
                                }
                            }
                        }
                    },
                    onNotShown = { notShown = it },
                    onOutline = { outline = it },
                    onFigure = { figure -> push(Screen.Figure(opened.note.documentId, opened.note.blobSha, figure, opened.note.title)) },
                    onTopBlock = { topBlock = it },
                    onBarsShown = { barsShown = it },
                    reattaching = { reattaching != null },
                    selectingAnswer = { answerPrompt != null },
                    onSelection = { selection, action ->
                        val placing = reattaching
                        when {
                            selection == null -> message = "Select some text first."
                            selection.has("error") -> message = selection.getString("error")
                            action == SelectionAction.REATTACH && placing != null -> reattach(placing, selection)
                            action == SelectionAction.ANSWER -> {
                                newCardSelection = selection.toString()
                                newCardPrompt = checkNotNull(answerPrompt)
                            }
                            action == SelectionAction.MAKE_QUESTION -> {
                                newCardSelection = selection.toString()
                                newCardPrompt = null
                            }
                            action == SelectionAction.ADD_NOTE -> newSelection = selection
                            else -> create(selection, null)
                        }
                    })
            }
            androidx.compose.runtime.SideEffect { sessionRef = session }
            DisposableEffect(lifecycle, session) {
                // Process death can follow ON_STOP, so the position is captured whenever the reader leaves the screen.
                val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) session.capture() }
                lifecycle.addObserver(observer)
                onDispose { lifecycle.removeObserver(observer) }
            }
            LaunchedEffect(session, annotations) { session.showHighlights(annotations) }
            changes?.let { comparison ->
                ChangesPanel(comparison, changesExpanded, onToggle = { changesExpanded = !changesExpanded }, onRetry = { changesReload++ },
                    onOpen = { section ->
                        changesExpanded = false
                        session.showBlock(section.blockId) { shown ->
                            if (!shown) message = "That section isn't in the version on screen; reopen the note."
                        }
                    })
            }
            AndroidView(
                factory = { context -> session.createView(context) },
                onRelease = { view -> session.release(view) },
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
            if (notesOpen) {
                NotesPanel(opened.note.documentId, annotations, opened.note.blobSha, notShown, sync, dao, appScope, onFailure,
                    onReveal = { session.reveal(it.mutationId) }, onMessage = { message = it },
                    onMakeQuestion = { row ->
                        val passage = row.drawn
                        newCardSelection = JSONObject().put("sourceBlobSha", passage.blobSha).put("blockId", passage.blockId)
                            .put("startOffset", passage.startOffset).put("endOffset", passage.endOffset).put("exactText", passage.exactText).toString()
                        newCardPrompt = null
                    },
                    onReattach = { row ->
                        reattaching = row
                        notesOpen = false
                        message = null
                    },
                    onOpenNote = { from -> push(Screen.Reader(from.id, from.title)) },
                    modifier = Modifier.fillMaxWidth().weight(0.7f))
            }
        }
    }
    outline?.takeIf { outlineOpen && it.isNotEmpty() }?.let { headings ->
        OutlineSheet(headings, section, onDismiss = { outlineOpen = false }, onJump = { entry ->
            outlineOpen = false
            sessionRef?.showBlock(entry.blockId) { shown -> if (!shown) message = "Couldn't scroll to “${entry.text}”; reopen the note." }
        })
    }
    preview?.let { (target, heading) ->
        LinkPreviewSheet(target, heading, sync, dao, onDismiss = { preview = null }, onOpen = {
            preview = null
            push(Screen.Reader(target.id, target.title, heading))
        })
    }
    linkChoices?.let { (heading, choices) ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { linkChoices = null },
            title = { Text("Which note?") },
            text = {
                Column {
                    for (choice in choices) {
                        TextButton(onClick = {
                            linkChoices = null
                            push(Screen.Reader(choice.id, choice.title, heading))
                        }) { Text(choice.path) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { linkChoices = null }) { Text("Cancel") } },
        )
    }
    newCardSelection?.let { encoded ->
        val selection = JSONObject(encoded)
        NoteDialog(title = "Make a question", quote = selection.getString("exactText"), initial = newCardPrompt.orEmpty(),
            label = "Question", allowBlank = false, onDismiss = { newCardSelection = null }, onSave = { question ->
                newCardSelection = null
                makeCard(selection, question)
            })
    }
    newSelection?.let { selection ->
        NoteDialog(title = "Add a note", quote = selection.getString("exactText"), initial = "",
            onDismiss = { newSelection = null }, onSave = { note ->
                newSelection = null
                create(selection, note.ifBlank { null })
            })
    }
}

/**
 * The system bars follow the reader's top bar: hidden while reading down the note, shown briefly by a swipe from the
 * edge, and always shown again when the reader is left.
 */
@Composable
private fun ReadingBars(shown: Boolean) {
    val activity = androidx.activity.compose.LocalActivity.current ?: error("ReaderScreen requires an activity")
    val bars = activity.window.insetsController ?: error("ReaderScreen requires a window insets controller")
    DisposableEffect(bars) {
        bars.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose { bars.show(android.view.WindowInsets.Type.systemBars()) }
    }
    LaunchedEffect(shown) {
        if (shown) bars.show(android.view.WindowInsets.Type.systemBars()) else bars.hide(android.view.WindowInsets.Type.systemBars())
    }
}

@Composable
private fun Notice(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
}

/**
 * One displayed note version in an isolated WebView. Repository content gets no credentials, no JavaScript bridge, and
 * no network, file, or content access; native code reads and restores the position through evaluateJavascript only.
 */
private class ReaderSession(
    private val note: NoteRow,
    private val sync: Sync,
    private val dao: LibraryDao,
    private val scope: CoroutineScope,
    /** A heading to open at instead of the saved position (the target of a note link). */
    private val openAtHeading: String?,
    private val study: StudyNote,
    private val studying: () -> Boolean,
    private var openAtAnnotation: String?,
    private val onAnnotationOpened: () -> Unit,
    private val openAtQuestion: StudyTarget?,
    private val onQuestionOpened: () -> Unit,
    private val onQuestionMissing: () -> Unit,
    private val onRestoreNotice: (String?) -> Unit,
    /** A tapped note link: an Obsidian [target] name or a repository [path], and an optional heading. */
    private val onNoteLink: (target: String?, path: String?, heading: String?) -> Unit,
    private val onNotShown: (Set<String>) -> Unit,
    /** A tapped Full screen link: the page's id for that table or diagram. */
    private val onFigure: (String) -> Unit,
    /** The page's headings, once rendering has finished. */
    private val onOutline: (List<OutlineEntry>) -> Unit,
    /** The index of the first block visible at the top, after scrolling settles and whenever the position is saved. */
    private val onTopBlock: (Int) -> Unit,
    /** Whether the bars should show: false after the reader scrolls down, true after scrolling up or reaching the top. */
    private val onBarsShown: (Boolean) -> Unit,
    /** Whether a highlight is being reattached, which changes the selection menu. */
    private val reattaching: () -> Boolean,
    private val selectingAnswer: () -> Boolean,
    /** The captured selection (null when empty, {error} when it spans blocks) and the chosen action. */
    private val onSelection: (JSONObject?, SelectionAction) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var view: WebView? = null
    /** Rendering finished, so highlights and the scripts' functions can be used. */
    private var ready = false
    private var highlights: List<AnnotationRow> = emptyList()
    /** Saving starts only after the saved position is restored, so the top of the page never overwrites it. */
    private var restored = false
    private val saveAfterScroll = Runnable { capture() }
    private val sectionAfterScroll = Runnable { reportTopBlock() }
    /**
     * Only scrolling that follows a touch on the page moves the bars; jumps made by the app (restoring the position, the
     * outline, Show) clear it, so they never hide the bars.
     */
    private var touched = false
    /**
     * The bars change only when the finger lifts, by that swipe's net scroll (pixels, positive down). Moving the view
     * under a finger that is still down made the page read the move as more scrolling the other way, so the bars and the
     * page oscillated during the drag (seen on the Pixel 8a).
     */
    private var fingerDown = false
    private var swipeScroll = 0

    fun createView(context: Context): WebView {
        val assets = readerAssets(context)
        return ReaderWebView(context, reattaching, selectingAnswer) { action, finish ->
            captureSelection { selection ->
                finish()
                onSelection(selection, action)
            }
        }.apply {
            isolateReaderPage()
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val url = request.url
                    if (request.hasGesture() && url.host == ASSET_HOST && url.path == NOTE_LINK) {
                        onNoteLink(url.getQueryParameter("target"), url.getQueryParameter("path"), url.getQueryParameter("heading"))
                        return true
                    }
                    if (request.hasGesture() && url.host == ASSET_HOST && url.path == COPY_CODE) {
                        url.getQueryParameter("block")?.let { copyCode(view, it) }
                        return true
                    }
                    if (request.hasGesture() && url.host == ASSET_HOST && url.path == FULL_SCREEN) {
                        url.getQueryParameter("figure")?.let(onFigure)
                        return true
                    }
                    if (request.hasGesture() && (url.scheme == "https" || url.scheme == "http") && url.host != ASSET_HOST) {
                        try {
                            view.context.startActivity(Intent(Intent.ACTION_VIEW, url).addCategory(Intent.CATEGORY_BROWSABLE))
                        } catch (error: ActivityNotFoundException) {
                            Log.w("RepoRead", "No browser for an external note link")
                        }
                    }
                    // The page itself never navigates away; note links are opened by the app as new reader screens.
                    return true
                }

                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
                    readerResource(assets, sync, note, request.url)

                override fun onPageFinished(view: WebView, url: String) = awaitReady(view)
            }
            val barsThreshold = (BARS_SCROLL_DP * context.resources.displayMetrics.density).toInt()
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        touched = true
                        fingerDown = true
                        swipeScroll = 0
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        fingerDown = false
                        when {
                            view.scrollY <= barsThreshold || swipeScroll < -barsThreshold -> onBarsShown(true)
                            swipeScroll > barsThreshold -> onBarsShown(false)
                        }
                    }
                }
                false
            }
            setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
                main.removeCallbacks(saveAfterScroll)
                main.removeCallbacks(sectionAfterScroll)
                if (restored) main.postDelayed(saveAfterScroll, SAVE_AFTER_SCROLL_MS)
                if (ready) main.postDelayed(sectionAfterScroll, SECTION_AFTER_SCROLL_MS)
                if (fingerDown) swipeScroll += scrollY - oldScrollY
                // A fling that reaches the top brings the bars back; nothing else changes them without a finger lifting.
                else if (touched && scrollY <= barsThreshold) onBarsShown(true)
            }
            view = this
            loadDataWithBaseURL("$ASSET_ORIGIN/", note.html, "text/html", "UTF-8", null)
        }
    }

    private fun awaitReady(view: WebView) {
        awaitRendered(view, main) { state, attempt ->
            when (state) {
                "ready" -> {
                    view.evaluateJavascript("window.reporead.configureStudy(${study.json()}); window.reporead.setStudy(${studying()}); true") { encoded ->
                        check(JSONTokener(encoded).nextValue() == true) { "Study layout failed; documentId=${note.documentId} blobSha=${note.blobSha}" }
                        Log.i("RepoRead", "Reader ready; documentId=${note.documentId} viewHeight=${view.height} attempt=$attempt")
                        ready = true
                        applyHighlights()
                        view.evaluateJavascript("JSON.stringify(window.reporead.outline())") { outline ->
                            onOutline(parseOutline(JSONTokener(outline).nextValue() as String))
                        }
                        restore(view)
                    }
                }
                "failed" -> Log.w("RepoRead", "Reader render failed; position not saved; documentId=${note.documentId}")
                else -> Log.w("RepoRead", "Reader never became ready; position not saved; documentId=${note.documentId}")
            }
        }
    }

    private fun restore(view: WebView) {
        val question = openAtQuestion
        if (question != null) {
            view.evaluateJavascript("window.reporead.studyQuestion(${JSONObject.quote(question.blobSha)}, ${JSONObject.quote(question.blockId)})") { encoded ->
                val shown = JSONTokener(encoded).nextValue() == true
                onQuestionOpened()
                onRestoreNotice(if (shown) null else "This practice question isn't in the displayed version; showing the note. Choose it again from Practice.")
                if (!shown) {
                    onQuestionMissing()
                    view.evaluateJavascript("window.reporead.setStudy(false)", null)
                }
                restored = true
                capture()
            }
            return
        }
        val heading = openAtHeading
        if (heading != null) {
            view.evaluateJavascript("window.reporead.showHeading(${JSONObject.quote(heading)})") { encoded ->
                val shown = JSONTokener(encoded).nextValue() == true
                Log.i("RepoRead", "Reader opened at a linked heading; documentId=${note.documentId} found=$shown")
                onRestoreNotice(if (shown) null else "No heading “$heading” in this note; showing the top.")
                restored = true
                capture()
            }
            return
        }
        scope.launch {
            val saved = dao.reading(note.documentId)
            if (saved == null) {
                restored = true
                capture()
                return@launch
            }
            view.evaluateJavascript("window.reporead.restore(${saved.anchorJson}, ${saved.progressPercent})") { encoded ->
                val mode = JSONTokener(encoded).nextValue() as String
                Log.i("RepoRead", "Reader restored; documentId=${note.documentId} mode=$mode savedPercent=${saved.progressPercent} " +
                    "savedBlock=${JSONObject(saved.anchorJson).getInt("blockIndex")} viewHeight=${view.height}")
                onRestoreNotice(when {
                    mode == "collapsed" -> "Resumed at a collapsed study section; tap Show / hide section to reveal it."
                    mode == "section" -> "Resumed at the start of the section you were reading; the exact passage changed."
                    mode == "block" || mode == "percent" -> "Resumed near your last position; the exact passage could not be found."
                    saved.lastReadBlobSha != note.blobSha -> "This note changed since you last read it; resumed at the same passage."
                    else -> null
                })
                restored = true
                capture()
            }
        }
    }

    /** Saves the current position for the version on screen, then runs [then]. */
    fun capture(then: () -> Unit = {}) {
        jumpToAnnotation()
        main.removeCallbacks(saveAfterScroll)
        val webView = view
        if (webView == null || !restored) {
            then()
            return
        }
        webView.evaluateJavascript("JSON.stringify(window.reporead.position())") { encoded ->
            val position = JSONObject(JSONTokener(encoded).nextValue() as String)
            val row = ReadingRow(note.documentId, note.title, note.path, note.blobSha, position.getInt("progressPercent"),
                position.getJSONObject("anchor").toString(), System.currentTimeMillis(), pending = true)
            val viewHeight = webView.height
            // Teardown can cancel the UI scope before this callback. Finish this captured local write, not a network sync.
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                withContext(NonCancellable) {
                    sync.saveReading(row)
                    Log.i("RepoRead", "Reading position saved; documentId=${note.documentId} percent=${row.progressPercent} " +
                        "block=${position.getJSONObject("anchor").getInt("blockIndex")} viewHeight=$viewHeight")
                    then()
                }
            }
            onTopBlock(position.getJSONObject("anchor").getInt("blockIndex"))
        }
    }

    private fun reportTopBlock() {
        val webView = view ?: return
        if (!ready) return
        webView.evaluateJavascript("window.reporead.position().anchor.blockIndex") { encoded -> onTopBlock(JSONTokener(encoded).nextValue() as Int) }
    }

    /** Saves the final position before destroying the view; later lifecycle events no longer touch it. */
    fun release(webView: WebView) {
        main.removeCallbacks(sectionAfterScroll)
        capture { webView.destroy() }
        view = null
        restored = false
        ready = false
    }

    /** Draws each highlight whose current location is in the displayed version; others are listed, not drawn. */
    fun showHighlights(rows: List<AnnotationRow>) {
        highlights = rows
        applyHighlights()
    }

    private fun applyHighlights() {
        val webView = view ?: return
        if (!ready) return
        val payload = JSONArray(highlights.filter { it.drawn.blobSha == note.blobSha }.map {
            val passage = it.drawn
            JSONObject().put("key", it.mutationId).put("blockId", passage.blockId).put("startOffset", passage.startOffset)
                .put("endOffset", passage.endOffset).put("exactText", passage.exactText)
        })
        webView.evaluateJavascript("JSON.stringify(window.reporead.highlight($payload))") { encoded ->
            val missing = JSONArray(JSONTokener(encoded).nextValue() as String)
            onNotShown(List(missing.length()) { missing.getString(it) }.toSet())
            jumpToAnnotation()
        }
    }

    /** Scrolls to a changed section's heading block in this version, or the top for null; reports whether it was found. */
    fun showBlock(blockId: String?, then: (Boolean) -> Unit) {
        val webView = view
        if (webView == null || !ready) {
            then(false)
            return
        }
        touched = false
        webView.evaluateJavascript("window.reporead.showBlock(${if (blockId == null) "null" else JSONObject.quote(blockId)})") { encoded ->
            val shown = JSONTokener(encoded).nextValue() == true
            if (!shown) Log.w("RepoRead", "Changed section not found; documentId=${note.documentId} blobSha=${note.blobSha} blockId=$blockId")
            then(shown)
        }
    }

    /** Scrolls to the first heading whose text is [text] (ignoring case), as Obsidian heading links do. */
    fun showHeading(text: String, then: (Boolean) -> Unit) {
        val webView = view
        if (webView == null || !ready) {
            then(false)
            return
        }
        touched = false
        webView.evaluateJavascript("window.reporead.showHeading(${JSONObject.quote(text)})") { encoded -> then(JSONTokener(encoded).nextValue() == true) }
    }

    fun reveal(key: String) {
        val webView = view ?: return
        if (!ready) return
        touched = false
        webView.evaluateJavascript("window.reporead.reveal(${JSONObject.quote(key)})", null)
    }

    private fun jumpToAnnotation() {
        val key = openAtAnnotation ?: return
        val webView = view ?: return
        if (!ready || !restored || highlights.none { it.mutationId == key }) return
        openAtAnnotation = null
        webView.evaluateJavascript("window.reporead.reveal(${JSONObject.quote(key)})") { onAnnotationOpened() }
    }

    fun currentQuestion(then: (String?) -> Unit) {
        val webView = view
        if (webView == null || !ready) { then(null); return }
        webView.evaluateJavascript("window.reporead.currentStudyQuestion()") { encoded ->
            val blockId = JSONTokener(encoded).nextValue() as? String
            then(blockId)
        }
    }

    fun setStudy(enabled: Boolean) {
        val webView = view ?: return
        if (!ready) return
        touched = false
        webView.evaluateJavascript("window.reporead.setStudy($enabled)") { capture() }
        Log.i("RepoRead", "Study view changed; documentId=${note.documentId} enabled=$enabled")
    }

    /** Android 13+ confirms clipboard writes itself, so the reader shows nothing more. */
    private fun copyCode(webView: WebView, blockId: String) {
        if (!ready) return
        webView.evaluateJavascript("window.reporead.codeText(${JSONObject.quote(blockId)})") { encoded ->
            val text = JSONTokener(encoded).nextValue() as String
            val clipboard = webView.context.getSystemService(android.content.ClipboardManager::class.java)
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Code", text))
            Log.i("RepoRead", "Code copied; documentId=${note.documentId} block=$blockId chars=${text.length}")
        }
    }

    private fun captureSelection(then: (JSONObject?) -> Unit) {
        val webView = view
        if (webView == null || !ready) {
            then(null)
            return
        }
        webView.evaluateJavascript("JSON.stringify(window.reporead.capture())") { encoded ->
            val value = JSONTokener(encoded).nextValue()
            then(if (value is String && value != "null") JSONObject(value) else null)
        }
    }
}
