package com.reporead.android.reader

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.webkit.WebViewAssetLoader
import com.reporead.android.OpenAt
import com.reporead.android.database.AnnotationEntity
import com.reporead.android.database.Passage
import com.reporead.android.database.ReadingStateEntity
import com.reporead.android.database.RepoReadDao
import com.reporead.android.database.SavedPageEntity
import com.reporead.android.logging.DeviceLog
import com.reporead.android.sync.PhoneSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private const val NOTE_LINK = "/note-link"
/** The reader page's Copy link on a code block (reader.js addCodeTools); the text is then read with codeText(). */
private const val COPY_CODE = "/copy-code"
private const val SAVE_AFTER_SCROLL_MS = 700L
private const val SECTION_AFTER_SCROLL_MS = 150L
/** How far one swipe must scroll before the bars hide or come back, so small jitters do nothing. */
private const val BARS_SCROLL_DP = 24
internal const val SEARCH_TARGET_KEY = "search-result"

internal data class FindResult(val query: String = "", val index: Int = -1, val count: Int = 0)

private data class DrawnHighlight(val key: String, val passage: Passage)

/** The page's text selection when a selection action is chosen (reader.js capture). */
internal sealed interface Selection {
    data object None : Selection

    /** Not inside one block, so it cannot be saved as it is; [reason] says how to fix it. */
    data class Unusable(val reason: String) : Selection

    data class InBlock(val passage: Passage) : Selection
}

/**
 * One displayed note version in the reader's WebView, from loading to release: where it opens, the saved position,
 * highlights, find, links and selections. ReaderPageHosting.kt owns how the page is isolated and scripted.
 */
internal class ReaderSession(
    private val note: SavedPageEntity,
    private val sync: PhoneSync,
    private val dao: RepoReadDao,
    /** Survives the view so final local saves can finish. */
    private val appScope: CoroutineScope,
    /** Where the page opens; null is where the note was last read. */
    private val openAt: OpenAt?,
    /** The page has opened at [openAt], found or not; never called for null. */
    private val onOpenedAtTarget: () -> Unit,
    private val study: StudyModeAnalysis,
    private val studying: () -> Boolean,
    /**
     * A tapped link to another note: an Obsidian [target] name or a repository [path], and an optional heading. A link
     * to a heading of this note is followed here.
     */
    private val onNoteLink: (target: String?, path: String?, heading: String?) -> Unit,
    /** Whether a highlight is being reattached, which changes the selection menu. */
    private val isReattaching: () -> Boolean,
    /** A short message about something the page could not do (a link, the browser, rendering). */
    private val onMessage: (String) -> Unit,
    /** The selection when a selection action is chosen, and that action. */
    private val onSelection: (Selection, SelectionAction) -> Unit,
) {
    /** The page's headings once it has opened; null before, if it did not render, and after it is gone ([opened]). */
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

    /** The page's renderer is gone (it crashed, or the system reclaimed it), so the screen no longer shows the page. */
    var stopped by mutableStateOf(false)
        private set

    /** The hidden WebView has reached its final location, or has exposed its own rendering failure. */
    var pageVisible by mutableStateOf(false)
        private set

    /** The active in-note query and occurrence; [query] identifies which input the count answers. */
    var findResult by mutableStateOf(FindResult())
        private set

    /**
     * The page has rendered and opened, so its scripts can be used and scrolling saves the position. The screen offers
     * the controls that use the page only then; saving waits for it so that loading never overwrites the saved
     * position.
     */
    val opened: Boolean
        get() = outline != null

    private val main = Handler(Looper.getMainLooper())
    private var view: WebView? = null
    private var persistentHighlights = emptyList<DrawnHighlight>()
    private var searchPassage = (openAt as? OpenAt.Search)?.passage
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
     * the page oscillated during the drag.
     */
    private var fingerDown = false
    private var swipeScroll = 0

    fun createView(context: Context): WebView =
        ReaderSelectionWebView(context, isReattaching, ::onSelectionAction).apply {
            isolateReaderPage()
            alpha = 0f
            webViewClient = PageClient(readerAssets(context))
            trackScrolling(this)
            view = this
            loadDataWithBaseURL("$ASSET_ORIGIN/", note.html, "text/html", "UTF-8", null)
        }

    /** An action chosen in the page's text-selection menu; [finish] closes the menu. */
    private fun onSelectionAction(action: SelectionAction, finish: () -> Unit) {
        val webView = openedView()
        if (webView == null) {
            onMessage("The note is still loading; select the text again in a moment.")
            return
        }
        appScope.launch {
            val selection = captureSelection(webView)
            // A selection that cannot be used stays on screen with its handles, so it can be adjusted.
            if (selection is Selection.InBlock) finish()
            onSelection(selection, action)
        }
    }

    /** Serves the page only the reader's own resources, opens it once loaded, and turns tapped links into actions. */
    private inner class PageClient(private val assets: WebViewAssetLoader) : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            // The page itself never navigates away; the app opens what was tapped.
            if (request.hasGesture()) openLink(view, request.url)
            return true
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
            readerResource(assets, sync.images, note, request.url)

        override fun onPageFinished(view: WebView, url: String) {
            opening?.cancel()
            opening = appScope.launch { open(view) }
        }

        // Returning false would end the app with the renderer. This page is lost and can run nothing more, so it saves
        // nothing; the position saved last stands.
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            DeviceLog.warn("Reader page stopped; documentId=${note.documentId} crashed=${detail.didCrash()}")
            main.removeCallbacksAndMessages(null)
            opening?.cancel()
            outline = null
            this@ReaderSession.view = null
            stopped = true
            pageVisible = false
            onMessage("This page stopped: Android ended the process that draws it. Go back and open the note again.")
            return true
        }
    }

    /** A tapped link: a note link as a new reader screen, a page tool here, a web or email link in another app. */
    private fun openLink(view: WebView, url: Uri) {
        if (url.host == ASSET_HOST) {
            when (url.path) {
                NOTE_LINK -> openNoteLink(url)
                COPY_CODE -> url.getQueryParameter("block")?.let { appScope.launch { copyCode(it) } }
            }
        } else if (url.scheme == "https" || url.scheme == "http" || url.scheme == "mailto") {
            try {
                view.context.startActivity(Intent(Intent.ACTION_VIEW, url).addCategory(Intent.CATEGORY_BROWSABLE))
            } catch (failure: ActivityNotFoundException) {
                DeviceLog.warn("No app for an external note link; scheme=${url.scheme}")
                onMessage(
                    if (url.scheme == "mailto") "No email app is installed to open this link."
                    else "No browser is installed to open this link."
                )
            }
        }
    }

    /**
     * A link to another note goes to the screen, which resolves it; a link to a heading of this note is followed here.
     */
    private fun openNoteLink(url: Uri) {
        val link = noteLinkParameters(url.encodedQuery.orEmpty())
        val target = link["target"]
        val path = link["path"]
        val heading = link["heading"]
        if (target != null || path != null) onNoteLink(target, path, heading)
        else if (heading != null)
            appScope.launch { if (!showHeading(heading)) onMessage("No heading “$heading” in this note.") }
    }

    /**
     * Saves the position and reports the section once scrolling settles, and moves the bars with the reader's swipes:
     * by each swipe's net scroll when the finger lifts, and back on reaching the top.
     */
    // The touch listener only watches: it returns false, so the page still handles every touch, clicks and
    // accessibility actions included.
    @SuppressLint("ClickableViewAccessibility")
    private fun trackScrolling(webView: ReaderSelectionWebView) {
        val barsThreshold = (BARS_SCROLL_DP * webView.resources.displayMetrics.density).toInt()
        webView.setOnTouchListener { view, event ->
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
        webView.setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
            main.removeCallbacks(saveAfterScroll)
            main.removeCallbacks(sectionAfterScroll)
            if (opened) {
                main.postDelayed(saveAfterScroll, SAVE_AFTER_SCROLL_MS)
                main.postDelayed(sectionAfterScroll, SECTION_AFTER_SCROLL_MS)
            }
            if (fingerDown) swipeScroll += scrollY - oldScrollY
            // A fling that reaches the top brings the bars back; nothing else moves them until a finger lifts.
            else if (touched && scrollY <= barsThreshold) barsShown = true
        }
    }

    private fun openedView(): WebView? = view?.takeIf { opened }

    /**
     * Waits for the page to render, opens it at [openAt] or where the note was last read, then saves that position and
     * keeps the drawn highlights current until the view is released.
     */
    private suspend fun open(view: WebView) {
        val state = awaitRendered(view)
        if (state != "ready") {
            // A page that failed to render shows why itself.
            view.alpha = 1f
            pageVisible = true
            DeviceLog.warn("Reader did not render; position not saved; documentId=${note.documentId} state=$state")
            if (state == "timeout")
                onMessage(
                    "This note did not finish rendering, so highlights and your reading position are off here. Reopen it to try again."
                )
            return
        }
        val configured =
            view.evaluate(
                "(window.reporead.configureStudy(${study.json()}), window.reporead.setStudy(${studying()}), true)"
            )
        check(configured == true) { "Study layout failed; documentId=${note.documentId} blobSha=${note.blobSha}" }
        DeviceLog.info("Reader ready; documentId=${note.documentId} viewHeight=${view.height}")
        val headings = parseOutline(view.evaluate("window.reporead.outline()") as JSONArray)
        val highlights = dao.annotations(note.documentId)
        // Drawn before opening, so that a highlight to open at is on the page.
        persistentHighlights = visibleHighlights(highlights.first())
        drawHighlights(view)
        // A link whose target is gone opens the note where it was last read, as an ordinary open does, so the save
        // below keeps the reader's place instead of replacing it with the top of the page.
        suspend fun missing(what: String) = listOfNotNull(what, restoreSaved(view)).joinToString(" ")
        restoreNotice =
            when (openAt) {
                null -> restoreSaved(view)
                is OpenAt.Heading -> {
                    val found = view.evaluate("window.reporead.showHeading(${JSONObject.quote(openAt.text)})") == true
                    DeviceLog.info("Reader opened at a linked heading; documentId=${note.documentId} found=$found")
                    if (found) null else missing("No heading “${openAt.text}” in this note.")
                }
                is OpenAt.Block -> {
                    val found =
                        note.blobSha == openAt.blobSha &&
                            view.evaluate("window.reporead.showBlock(${JSONObject.quote(openAt.blockId)})") == true
                    DeviceLog.info(
                        "Reader opened at an exact block; documentId=${note.documentId} " +
                            "blobSha=${openAt.blobSha} blockId=${openAt.blockId} found=$found"
                    )
                    if (found) null
                    else
                        missing(
                            "That location is not in this version of the note. Open it again from its current source."
                        )
                }
                is OpenAt.Search -> {
                    val passage = openAt.passage
                    val found =
                        note.blobSha == passage.blobSha &&
                            view.evaluate("window.reporead.reveal(${JSONObject.quote(SEARCH_TARGET_KEY)})") == true
                    DeviceLog.info(
                        "Reader opened at a search match; documentId=${note.documentId} " +
                            "blobSha=${passage.blobSha} blockId=${passage.blockId} found=$found"
                    )
                    if (found) null
                    else missing("That search match is not in this version of the note. Search the current note again.")
                }
                is OpenAt.Highlight -> {
                    val found = view.evaluate("window.reporead.reveal(${JSONObject.quote(openAt.mutationId)})") == true
                    DeviceLog.info("Reader opened at a highlight; documentId=${note.documentId} found=$found")
                    if (found) null
                    else
                        missing("That passage isn't marked in this version of the note; Highlights and notes says why.")
                }
            }
        // A completed positioning script can still leave an older native frame queued.
        view.awaitVisualState()
        view.alpha = 1f
        pageVisible = true
        if (openAt != null) onOpenedAtTarget()
        outline = headings
        save(view)
        // Room sends the list again after every write to the annotations table, any note's; the page is redrawn only
        // when what it draws changed: a new highlight, an edit, a sync that moved one.
        highlights.map(::visibleHighlights).distinctUntilChanged().collect {
            persistentHighlights = it
            drawHighlights(view)
        }
    }

    /** Restores where the note was last read; returns what to tell the reader when that place could not be found. */
    private suspend fun restoreSaved(view: WebView): String? {
        val saved = dao.reading(note.documentId) ?: return null
        val mode = view.evaluate("window.reporead.restore(${saved.anchorJson}, ${saved.progressPercent})") as String
        DeviceLog.info(
            "Reader restored; documentId=${note.documentId} mode=$mode savedPercent=${saved.progressPercent} " +
                "savedBlock=${JSONObject(saved.anchorJson).getInt("blockIndex")} viewHeight=${view.height}"
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
        val position = view.evaluate("window.reporead.position()") as JSONObject
        val anchor = position.getJSONObject("anchor")
        val block = anchor.getInt("blockIndex")
        topBlock = block
        val row =
            ReadingStateEntity(
                note.documentId,
                note.blobSha,
                position.getInt("progressPercent"),
                anchor.toString(),
                System.currentTimeMillis(),
                pending = true,
            )
        if (sync.saveReading(row))
            DeviceLog.debug(
                "Reading position saved; documentId=${note.documentId} percent=${row.progressPercent} block=$block " +
                    "viewHeight=${view.height}"
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
        outline = null
        pageVisible = false
        appScope.launch {
            try {
                if (wasOpened) save(webView)
            } finally {
                webView.destroy()
            }
        }
    }

    /** Saved highlights in this displayed version; equality prevents redraws for writes concerning another note. */
    private fun visibleHighlights(rows: List<AnnotationEntity>) =
        rows.filter { it.drawn.blobSha == note.blobSha }.map { DrawnHighlight(it.mutationId, it.drawn) }

    /** The sole payload sent to the page highlighter: saved highlights plus the current search occurrence. */
    private fun drawn(): String {
        val highlights = persistentHighlights.toMutableList()
        searchPassage
            ?.takeIf { it.blobSha == note.blobSha }
            ?.let { highlights += DrawnHighlight(SEARCH_TARGET_KEY, it) }
        return JSONArray(highlights.map { highlightJson(it.key, it.passage) }).toString()
    }

    private fun highlightJson(key: String, passage: Passage) =
        JSONObject()
            .put("key", key)
            .put("blockId", passage.blockId)
            .put("startOffset", passage.startOffset)
            .put("endOffset", passage.endOffset)
            .put("exactText", passage.exactText)

    /**
     * Draws [drawn]; saved highlights the page cannot place are listed, while a search miss is handled by its owner.
     */
    private suspend fun drawHighlights(view: WebView) {
        val missing = view.evaluate("window.reporead.highlight(${drawn()})") as JSONArray
        notShown = List(missing.length()) { missing.getString(it) }.filter { it != SEARCH_TARGET_KEY }.toSet()
    }

    /** Finds one literal occurrence while counting all of them, then draws and reveals only the active occurrence. */
    suspend fun find(query: String, index: Int) {
        require(index >= 0) { "Find occurrence index must be non-negative: $index" }
        val webView = openedView() ?: return
        if (query.isEmpty()) {
            clearFind()
            return
        }
        val found = webView.evaluate("window.reporead.findText(${JSONObject.quote(query)}, $index)") as JSONObject
        val count = found.getInt("count")
        val match = found.optJSONObject("match")
        searchPassage = match?.let {
            Passage(
                note.blobSha,
                it.getString("blockId"),
                it.getInt("startOffset"),
                it.getInt("endOffset"),
                it.getString("exactText"),
            )
        }
        findResult = FindResult(query, found.getInt("index"), count)
        drawHighlights(webView)
        if (searchPassage != null) {
            touched = false
            check(webView.evaluate("window.reporead.reveal(${JSONObject.quote(SEARCH_TARGET_KEY)})") == true) {
                "Find result was drawn but not revealed; documentId=${note.documentId} query=$query index=$index"
            }
        }
    }

    /** Ends in-note find and removes its transient highlight; saved highlights are redrawn unchanged. */
    suspend fun clearFind() {
        searchPassage = null
        findResult = FindResult()
        openedView()?.let { drawHighlights(it) }
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
            DeviceLog.warn(
                "Changed section not found; documentId=${note.documentId} blobSha=${note.blobSha} blockId=$blockId"
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
        appScope.launch { webView.evaluate("window.reporead.reveal(${JSONObject.quote(key)})") }
    }

    fun setStudy(enabled: Boolean) {
        val webView = openedView() ?: return
        touched = false
        DeviceLog.debug("Study view changed; documentId=${note.documentId} enabled=$enabled")
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
            DeviceLog.warn("Copy link names no code block; documentId=${note.documentId} block=$blockId")
            return
        }
        val clipboard = webView.context.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("Code", text))
        DeviceLog.debug("Code copied; documentId=${note.documentId} block=$blockId chars=${text.length}")
    }

    /**
     * The page's selection. A broken canonical mapping is an invariant failure, which capture() throws and evaluate
     * raises, never an approximate anchor.
     */
    private suspend fun captureSelection(view: WebView): Selection {
        val selection = view.evaluate("window.reporead.capture()") as? JSONObject ?: return Selection.None
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
