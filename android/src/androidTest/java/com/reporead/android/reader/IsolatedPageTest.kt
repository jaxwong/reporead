package com.reporead.android.reader

import android.graphics.Outline
import android.graphics.Rect
import android.webkit.ValueCallback
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reporead.android.BuildConfig
import com.reporead.android.database.SavedPageEntity
import com.reporead.android.network.Api
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

    @Test
    fun aReleasedPageCannotReportALateScriptResult() {
        val context = instrumentation.targetContext
        val sha = "a".repeat(40)
        val note =
            SavedPageEntity(
                1,
                sha,
                "test-only/page.md",
                "Test-only page",
                testPage(sha, "<p>Test-only late-result page</p>"),
                0,
                renderFormat = BuildConfig.RENDER_FORMAT,
            )
        val images =
            NoteImages(
                Api("http://127.0.0.1:1", { error("The isolated page must not call the network") }) {},
                File(context.cacheDir, "isolated-page-test"),
            )
        val waiting = CountDownLatch(1)
        lateinit var lateResult: () -> Unit
        lateinit var page: WebView
        var reported = 0
        var released = false
        instrumentation.runOnMainSync {
            // Test-only delivery gate: the real WebView evaluates the script before its result is held.
            page =
                object : IsolatedPage(context) {
                        override fun evaluateJavascript(script: String, resultCallback: ValueCallback<String>?) {
                            super.evaluateJavascript(script) { result ->
                                if (script.contains("testLateResult")) {
                                    lateResult = { checkNotNull(resultCallback).onReceiveValue(result) }
                                    waiting.countDown()
                                } else checkNotNull(resultCallback).onReceiveValue(result)
                            }
                        }
                    }
                    .apply {
                        show(
                            images,
                            note,
                            "Test-only late result",
                            "window.testLateResult = true",
                            {
                                error("The page did not render")
                            },
                        ) {
                            reported++
                        }
                    }
        }
        try {
            assertTrue("The real script result was not delivered", waiting.await(15, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                page.destroy()
                released = true
                lateResult()
            }
            instrumentation.waitForIdleSync()
            assertEquals("A released page must not report readiness", 0, reported)
        } finally {
            if (!released) instrumentation.runOnMainSync { page.destroy() }
        }
    }

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

    /** A reader script's console error reaches the app's stored log lines, text included, for the server's log. */
    @Test
    fun aPageConsoleErrorBecomesAnAppLine() {
        val plain = """<p data-block-id="b0" data-anchor-text="Test-only page">Test-only page</p>"""
        assertEquals("true", shown(plain, "(console.error('Test-only console error 1f3a'), true)"))

        val folder = File(instrumentation.targetContext.filesDir, "app-log")
        val stored =
            listOf("pending.jsonl", "sending.jsonl").joinToString("") {
                File(folder, it).takeIf(File::exists)?.readText().orEmpty()
            }
        assertTrue(stored.takeLast(2_000), stored.contains("Page console; level=ERROR source="))
        assertTrue(stored.takeLast(2_000), stored.contains("message=Test-only console error 1f3a"))
    }

    /**
     * Shows [body] as an isolated page and returns [script]'s value, as evaluateJavascript encodes it, once rendered.
     */
    private fun shown(body: String, script: String): String {
        val context = instrumentation.targetContext
        val sha = "a".repeat(40)
        val html = testPage(sha, body)
        val note =
            SavedPageEntity(
                1,
                sha,
                "test-only/page.md",
                "Test-only page",
                html,
                0,
                renderFormat = BuildConfig.RENDER_FORMAT,
            )
        val images =
            NoteImages(
                Api("http://127.0.0.1:1", { error("The isolated page must not call the network") }) {},
                File(context.cacheDir, "isolated-page-test"),
            )
        val shown = CountDownLatch(1)
        lateinit var page: WebView
        instrumentation.runOnMainSync {
            page =
                IsolatedPage(context).apply {
                    alpha = 0.4f
                    layout(0, 0, 400, 800)
                    show(
                        images,
                        note,
                        "Test-only page",
                        null,
                        { error("The page did not render") },
                    ) {
                        shown.countDown()
                    }
                }
        }
        try {
            assertTrue("The page did not render", shown.await(20, TimeUnit.SECONDS))
            val answered = CountDownLatch(1)
            var result = ""
            instrumentation.runOnMainSync {
                assertEquals("Only the page's host owns visibility", 0.4f, page.alpha)
                assertTrue("Isolated native pages must keep painting within their bounds", page.clipToOutline)
                val outline = Outline().also { page.outlineProvider.getOutline(page, it) }
                val clip = Rect()
                assertTrue("Native clipping is rectangular", outline.canClip() && outline.getRect(clip))
                assertEquals("Clipping follows the viewport", Rect(0, 0, page.width, page.height), clip)
                page.evaluateJavascript(script) {
                    result = it
                    answered.countDown()
                }
            }
            assertTrue(answered.await(5, TimeUnit.SECONDS))
            return result
        } finally {
            instrumentation.runOnMainSync { page.destroy() }
        }
    }

    /** A renderer can crash or be reclaimed by the system; the host is told, instead of the app ending with it. */
    @Test
    fun aPageWhoseRendererStopsIsReportedAsFailed() {
        val context = instrumentation.targetContext
        val sha = "a".repeat(40)
        val html = testPage(sha, """<p data-block-id="b0" data-anchor-text="Test-only page">Test-only page</p>""")
        val note =
            SavedPageEntity(
                1,
                sha,
                "test-only/page.md",
                "Test-only page",
                html,
                0,
                renderFormat = BuildConfig.RENDER_FORMAT,
            )
        val api = Api("http://127.0.0.1:1", { error("The isolated page must not call the network") }) {}
        val images = NoteImages(api, File(context.cacheDir, "isolated-page-test"))
        val shown = CountDownLatch(1)
        val failed = CountDownLatch(1)
        lateinit var page: WebView
        instrumentation.runOnMainSync {
            page =
                IsolatedPage(context).apply {
                    show(
                        images,
                        note,
                        "Test-only page",
                        null,
                        { failed.countDown() },
                    ) {
                        shown.countDown()
                    }
                }
        }
        assertTrue("The page did not render", shown.await(15, TimeUnit.SECONDS))
        instrumentation.runOnMainSync { page.loadUrl("chrome://crash") }
        assertTrue("The stopped renderer was not reported", failed.await(10, TimeUnit.SECONDS))
        instrumentation.runOnMainSync { page.destroy() }
    }
}
