package com.reporead.android.reader

import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reporead.android.BuildConfig
import com.reporead.android.core.network.Api
import com.reporead.android.data.NoteRow
import com.reporead.android.sync.NoteImages
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The page the link preview and bank answer are shown in; a test-only note and zero network calls. */
@RunWith(AndroidJUnit4::class)
class IsolatedPageTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    /** Mermaid, most of the page's script, is loaded only for a page with a diagram, which it then draws. */
    @Test
    fun onlyAPageWithADiagramLoadsMermaid() {
        val plain = """<p data-block-id="b0" data-anchor-text="Test-only page">Test-only page</p>"""
        val diagram = "flowchart LR\n  A --> B"
        val withDiagram =
            """<pre data-block-id="b0" data-mermaid data-anchor-text="$diagram"><code class="language-mermaid">$diagram</code></pre>"""
        val loads = "document.querySelector('script[src=\"/assets/diagrams.js\"]') !== null"
        assertEquals("false", shown(plain, loads))
        assertEquals("true", shown(withDiagram, "$loads && document.querySelector('.diagram svg') !== null"))
    }

    /**
     * Shows [body] as an isolated page and returns [script]'s value, as evaluateJavascript encodes it, once rendered.
     */
    private fun shown(body: String, script: String): String {
        val context = instrumentation.targetContext
        val sha = "a".repeat(40)
        val html = testPage(sha, body)
        val note =
            NoteRow(1, sha, "test-only/page.md", "Test-only page", html, 0, renderFormat = BuildConfig.RENDER_FORMAT)
        val images =
            NoteImages(
                Api("http://127.0.0.1:1", { error("The isolated page must not call the network") }) {},
                File(context.cacheDir, "isolated-page-test"),
            )
        val shown = CountDownLatch(1)
        lateinit var page: WebView
        instrumentation.runOnMainSync {
            page =
                WebView(context).apply {
                    showIsolatedPage(
                        images,
                        note,
                        "Test-only page",
                        null,
                        { error("The page did not render") },
                    ) {
                        shown.countDown()
                        true
                    }
                }
        }
        assertTrue("The page did not render", shown.await(20, TimeUnit.SECONDS))
        val answered = CountDownLatch(1)
        var result = ""
        instrumentation.runOnMainSync {
            page.evaluateJavascript(script) {
                result = it
                answered.countDown()
            }
        }
        assertTrue(answered.await(5, TimeUnit.SECONDS))
        instrumentation.runOnMainSync { page.destroy() }
        return result
    }

    /** A renderer can crash or be reclaimed by the system; the host is told, instead of the app ending with it. */
    @Test
    fun aPageWhoseRendererStopsIsReportedAsFailed() {
        val context = instrumentation.targetContext
        val sha = "a".repeat(40)
        val html = testPage(sha, """<p data-block-id="b0" data-anchor-text="Test-only page">Test-only page</p>""")
        val note =
            NoteRow(1, sha, "test-only/page.md", "Test-only page", html, 0, renderFormat = BuildConfig.RENDER_FORMAT)
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
