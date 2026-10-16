package com.reporead.android.reader

import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reporead.android.core.network.Api
import com.reporead.android.data.NoteRow
import com.reporead.android.sync.NoteImages
import com.reporead.android.sync.RENDER_FORMAT
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The page the figure, link preview and bank answer are shown in; a test-only note and zero network calls. */
@RunWith(AndroidJUnit4::class)
class IsolatedPageTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    /** A renderer can crash or be reclaimed by the system; the host is told, instead of the app ending with it. */
    @Test
    fun aPageWhoseRendererStopsIsReportedAsFailed() {
        val context = instrumentation.targetContext
        val sha = "a".repeat(40)
        val html =
            """<!doctype html><html><head><link rel="stylesheet" href="/assets/reader.css">
            <script defer src="/assets/reader.js"></script></head>
            <body data-source-blob-sha="$sha" data-max-diagram-chars="20000" data-max-edges="200">
            <p id="render-status"></p><main id="note"><p data-block-id="b0" data-anchor-text="Test-only page">Test-only page</p></main></body></html>"""
        val note = NoteRow(1, sha, "test-only/page.md", "Test-only page", html, 0, renderFormat = RENDER_FORMAT)
        val api = Api("http://127.0.0.1:1", { error("The isolated page must not call the network") }) {}
        val images = NoteImages(api, File(context.cacheDir, "isolated-page-test"))
        val shown = CountDownLatch(1)
        val failed = CountDownLatch(1)
        lateinit var page: WebView
        instrumentation.runOnMainSync {
            page =
                WebView(context).apply {
                    showIsolatedPage(
                        images,
                        note,
                        "Test-only page",
                        null,
                        onFullScreen = null,
                        { failed.countDown() },
                    ) {
                        shown.countDown()
                        true
                    }
                }
        }
        assertTrue("The page did not render", shown.await(15, TimeUnit.SECONDS))
        instrumentation.runOnMainSync { page.loadUrl("chrome://crash") }
        assertTrue("The stopped renderer was not reported", failed.await(10, TimeUnit.SECONDS))
        instrumentation.runOnMainSync { page.destroy() }
    }
}
