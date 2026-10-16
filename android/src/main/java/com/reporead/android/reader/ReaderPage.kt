package com.reporead.android.reader

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import com.reporead.android.data.NoteRow
import com.reporead.android.sync.NoteImages
import java.io.ByteArrayInputStream
import org.json.JSONTokener

/*
 * One owner for how a rendered note page is hosted, shared by the reader and the pages shown beside it (the full-screen
 * figure, a link preview, a bank answer): the same isolation, the same resources, and the same wait for rendering.
 */

internal const val ASSET_HOST = "appassets.androidplatform.net"
internal const val ASSET_ORIGIN = "https://$ASSET_HOST"
private const val IMAGE_PREFIX = "/repo-image/"
private const val EMBED_PREFIX = "/repo-embed/"
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
    settings.javaScriptEnabled = true
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    settings.blockNetworkLoads = true
    settings.textZoom = (context.resources.configuration.fontScale * 100).toInt()
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
    note: NoteRow,
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
                    images.image(note.documentId, note.blobSha, path.removePrefix(IMAGE_PREFIX))
                path.startsWith(EMBED_PREFIX) ->
                    images.embedImage(note.documentId, note.blobSha, path.removePrefix(EMBED_PREFIX))
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

/**
 * Waits for the page's rendering (diagrams change the layout) and passes "ready", "failed", or "timeout" to [then],
 * with the number of polls it took.
 */
internal fun awaitRendered(view: WebView, main: Handler, then: (state: String, attempts: Int) -> Unit) {
    fun poll(attempt: Int) {
        view.evaluateJavascript("document.body.dataset.state || ''") { encoded ->
            when (val state = JSONTokener(encoded).nextValue()) {
                "ready",
                "failed" -> then(state as String, attempt)
                else ->
                    if (attempt < READY_POLL_LIMIT) main.postDelayed({ poll(attempt + 1) }, READY_POLL_MS)
                    else then("timeout", attempt)
            }
        }
    }
    poll(0)
}

/**
 * Shows the saved [note] in this WebView as a page beside the reader: the full-screen figure, a link preview, or a bank
 * answer. It stays hidden while it renders; then [script] (null: none) keeps the part to show, and [reveal] receives
 * whether that part is in this version and returns whether to show the page. [onFailed] runs when the page does not
 * render or its renderer stops, and the host then removes it; [name] says which page in the log. Nothing navigates; a
 * tapped Full screen link passes its figure id to [onFullScreen], when given.
 */
internal fun WebView.showIsolatedPage(
    images: NoteImages,
    note: NoteRow,
    name: String,
    script: String?,
    onFullScreen: ((String) -> Unit)?,
    onFailed: () -> Unit,
    reveal: (found: Boolean) -> Boolean,
) {
    val assets = readerAssets(context)
    val main = Handler(Looper.getMainLooper())
    isolateReaderPage()
    alpha = 0f
    webViewClient =
        object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                if (onFullScreen != null && request.hasGesture() && url.host == ASSET_HOST && url.path == FULL_SCREEN)
                    url.getQueryParameter("figure")?.let(onFullScreen)
                return true
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
                readerResource(assets, images, note, request.url)

            override fun onPageFinished(view: WebView, url: String) =
                awaitRendered(view, main) { state, _ ->
                    when {
                        state != "ready" -> {
                            Log.w("RepoRead", "$name did not render; documentId=${note.documentId} state=$state")
                            onFailed()
                        }
                        script == null -> if (reveal(true)) view.alpha = 1f
                        else ->
                            view.evaluateJavascript(script) { encoded ->
                                if (reveal(JSONTokener(encoded).nextValue() == true)) view.alpha = 1f
                            }
                    }
                }

            // Returning false would end the app with the page's renderer (a crash, or the system reclaiming it).
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                Log.w("RepoRead", "$name stopped; documentId=${note.documentId} crashed=${detail.didCrash()}")
                main.removeCallbacksAndMessages(null)
                onFailed()
                return true
            }
        }
    loadDataWithBaseURL("$ASSET_ORIGIN/", note.html, "text/html", "UTF-8", null)
}
