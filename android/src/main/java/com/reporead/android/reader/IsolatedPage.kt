package com.reporead.android.reader

import android.content.Context
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.reporead.android.database.SavedPageEntity
import com.reporead.android.logging.DeviceLog
import com.reporead.android.sync.SavedPageImages
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * A native link-preview page owns its rendering lifetime. Destroying it or losing its renderer cancels polling, script
 * results and native draw waits before any later result can touch the released page. The host owns visibility.
 */
internal open class IsolatedPage(context: Context) : WebView(context) {
    private val rendering = MainScope()

    /** Loads one saved page; nothing navigates. A missing [script] target is reported, never replaced by another. */
    fun show(
        images: SavedPageImages,
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
                            DeviceLog.warn("$name did not render; documentId=${note.documentId} state=$state")
                            onFailed()
                        } else onReady(script == null || view.evaluate(script) == true)
                    }
                }

                // Returning false would end the app with the page's renderer (a crash, or system reclamation).
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    DeviceLog.warn("$name stopped; documentId=${note.documentId} crashed=${detail.didCrash()}")
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
