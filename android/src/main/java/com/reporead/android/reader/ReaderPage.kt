package com.reporead.android.reader

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.webkit.ConsoleMessage
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import com.reporead.android.database.SavedPageEntity
import com.reporead.android.logging.AppLog
import com.reporead.android.sync.IMAGE_PREFIX
import com.reporead.android.sync.NoteImages
import java.io.ByteArrayInputStream
import kotlin.coroutines.resume
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import org.json.JSONTokener

/*
 * One owner for how a rendered note page is hosted, shared by the reader and the pages shown beside it (a link preview,
 * a bank answer): the same isolation, the same resources, and the same wait for rendering.
 */

internal const val ASSET_HOST = "appassets.androidplatform.net"
internal const val ASSET_ORIGIN = "https://$ASSET_HOST"
private val IMAGE_TYPES =
    mapOf(
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "svg" to "image/svg+xml",
    )
private const val READY_POLL_MS = 150L
private const val READY_POLL_LIMIT = 100

/**
 * Repository content gets no credentials, no JavaScript bridge, and no network, file, or content access; native code
 * talks to the page through evaluateJavascript only. The system font size is applied as text zoom.
 */
internal fun WebView.isolateReaderPage() {
    // Until the page paints its own background, the app's surface shows through instead of a white flash.
    setBackgroundColor(Color.TRANSPARENT)
    // AndroidView doesn't clip native drawing; first paint must not cover surrounding Compose controls.
    clipToOutline = true
    // The reader runs only bundled scripts in its isolated page; network, file and content access stay blocked
    // (ADR-04).
    @SuppressLint("SetJavaScriptEnabled")
    settings.javaScriptEnabled = true
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    settings.blockNetworkLoads = true
    settings.textZoom = (context.resources.configuration.fontScale * 100).toInt()
    webChromeClient = PageConsole
}

/**
 * The page's console errors and warnings become app lines, so a reader script failure, such as a diagram Mermaid
 * rejected, reaches the server's log with its text. Other levels stay with WebView's own logcat output.
 */
private object PageConsole : WebChromeClient() {
    override fun onConsoleMessage(message: ConsoleMessage): Boolean {
        val level = message.messageLevel()
        if (level != ConsoleMessage.MessageLevel.ERROR && level != ConsoleMessage.MessageLevel.WARNING) return false
        AppLog.warn(
            "Page console; level=$level source=${message.sourceId()} line=${message.lineNumber()} " +
                "message=${message.message()}"
        )
        return true
    }
}

internal fun readerAssets(context: Context): WebViewAssetLoader =
    WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context)).build()

/**
 * The only resources a reader page can load: the app's bundled reader assets and this note version's images (from the
 * cache or the authenticated backend). Everything else is a 404. Called on a WebView background thread, so the blocking
 * image fetch is allowed here.
 */
internal fun readerResource(
    assets: WebViewAssetLoader,
    images: NoteImages,
    note: SavedPageEntity,
    url: Uri,
): WebResourceResponse {
    val path = url.path.orEmpty()
    if (url.host == ASSET_HOST) {
        if (path.startsWith("/assets/"))
            assets.shouldInterceptRequest(url)?.let {
                return it
            }
        val type = IMAGE_TYPES[path.substringAfterLast('.', "").lowercase()]
        val bytes =
            when {
                type == null -> null
                path.startsWith(IMAGE_PREFIX) ->
                    images.image(note.documentId, note.snapshotCommitSha, path.removePrefix(IMAGE_PREFIX))
                else -> null
            }
        if (type != null && bytes != null) {
            return WebResourceResponse(
                type,
                null,
                200,
                "OK",
                mapOf("X-Content-Type-Options" to "nosniff"),
                ByteArrayInputStream(bytes),
            )
        }
    }
    return WebResourceResponse(
        "text/plain",
        "UTF-8",
        404,
        "Unavailable",
        emptyMap(),
        ByteArrayInputStream("Unavailable in RepoRead reader".toByteArray()),
    )
}

/*
 * The one boundary for the page's scripts. Each runs as {value} or {error}, because WebView turns a script that throws
 * into null, which a cast or a comparison then misreads (a ClassCastException, or "not in this version"). A script that
 * throws is a broken invariant, raised with the page's own error and the expression.
 */
private fun pageScript(expression: String) =
    "(() => { try { return JSON.stringify({value: ($expression)}); } " +
        "catch (error) { return JSON.stringify({error: String(error?.stack ?? error)}); } })()"

private fun pageValue(expression: String, encoded: String): Any? {
    val reply = JSONTokener(encoded).nextValue() as? String ?: error("Page script did not run: $expression")
    val result = JSONObject(reply)
    check(!result.has("error")) { "Page script failed: $expression: ${result.getString("error")}" }
    return result.opt("value")
}

internal suspend fun WebView.evaluate(expression: String): Any? = suspendCancellableCoroutine { result ->
    evaluateJavascript(pageScript(expression)) { result.resumeWith(runCatching { pageValue(expression, it) }) }
}

/** Waits for rendering (diagrams change layout), within the caller's lifetime: "ready", "failed", or "timeout". */
internal suspend fun awaitRendered(view: WebView): String {
    for (attempt in 0..READY_POLL_LIMIT) {
        val state = view.evaluate("document.body.dataset.state || ''")
        if (state == "ready" || state == "failed") return state as String
        if (attempt < READY_POLL_LIMIT) delay(READY_POLL_MS)
    }
    return "timeout"
}

/**
 * One native barrier per opening, after its DOM changes. A non-drawable page (hidden window or zero-sized viewport) may
 * stay pending; there is no timed reveal of stale content. The owner handles release and renderer loss.
 */
internal suspend fun WebView.awaitVisualState() = suspendCancellableCoroutine { drawn ->
    postVisualStateCallback(
        0,
        object : WebView.VisualStateCallback() {
            override fun onComplete(requestId: Long) = drawn.resume(Unit)
        },
    )
}

/**
 * A native preview/answer page owns its rendering lifetime. Destroying it or losing its renderer cancels polling,
 * script results and native draw waits before any later result can touch the released page. The host owns visibility.
 */
internal open class IsolatedPage(context: Context) : WebView(context) {
    private val rendering = MainScope()

    /** Loads one saved page; nothing navigates. A missing [script] target is reported, never replaced by another. */
    fun show(
        images: NoteImages,
        note: SavedPageEntity,
        name: String,
        script: String?,
        onFailed: () -> Unit,
        onReady: suspend (found: Boolean) -> Unit,
    ) {
        val assets = readerAssets(context)
        isolateReaderPage()
        webViewClient =
            object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true

                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
                    readerResource(assets, images, note, request.url)

                override fun onPageFinished(view: WebView, url: String) {
                    rendering.launch {
                        val state = awaitRendered(view)
                        if (state != "ready") {
                            AppLog.warn("$name did not render; documentId=${note.documentId} state=$state")
                            onFailed()
                        } else onReady(script == null || view.evaluate(script) == true)
                    }
                }

                // Returning false would end the app with the page's renderer (a crash, or system reclamation).
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    AppLog.warn("$name stopped; documentId=${note.documentId} crashed=${detail.didCrash()}")
                    rendering.cancel()
                    onFailed()
                    return true
                }
            }
        loadDataWithBaseURL("$ASSET_ORIGIN/", note.html, "text/html", "UTF-8", null)
    }

    override fun destroy() {
        rendering.cancel()
        super.destroy()
    }
}
