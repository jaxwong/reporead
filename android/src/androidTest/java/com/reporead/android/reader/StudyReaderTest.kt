package com.reporead.android.reader

import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONTokener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.io.ByteArrayInputStream

/** Test-only pages in a real isolated WebView; no app database or source repository is changed. */
@RunWith(AndroidJUnit4::class)
class StudyReaderTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var view: WebView

    @After fun close() {
        if (::view.isInitialized) instrumentation.runOnMainSync { view.destroy() }
    }

    private fun block(id: Int, tag: String, text: String) = """<$tag data-block-id="b$id" data-anchor-text="$text">$text</$tag>"""

    private fun load(body: String) {
        val html = """<!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1">
            <link rel="stylesheet" href="/assets/reader.css"><script defer src="/assets/reader.js"></script></head>
            <body data-source-blob-sha="${"a".repeat(40)}" data-max-diagram-chars="20000" data-max-edges="200">
            <p id="render-status"></p><main id="note">$body</main></body></html>"""
        val ready = CountDownLatch(1)
        var state: String? = null
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val assets = readerAssets(context)
            view = WebView(context).apply {
                isolateReaderPage()
                measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
                layout(0, 0, 400, 800)
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
                        assets.shouldInterceptRequest(request.url) ?: WebResourceResponse("text/plain", "UTF-8", 404, "Unavailable", emptyMap(),
                            ByteArrayInputStream("Unavailable in study fixture".toByteArray()))
                    override fun onPageFinished(view: WebView, url: String) = awaitRendered(view, Handler(Looper.getMainLooper())) { result, _ ->
                        state = result
                        ready.countDown()
                    }
                }
                loadDataWithBaseURL("$ASSET_ORIGIN/", html, "text/html", "UTF-8", null)
            }
        }
        assertTrue("Study fixture did not finish within 25 seconds", ready.await(25, TimeUnit.SECONDS))
        assertEquals("ready", state)
        checkScript("window.reporead.configureStudy(${studyNote(html).json()}); return true;")
    }

    private fun checkScript(script: String) {
        val done = CountDownLatch(1)
        var result: String? = null
        instrumentation.runOnMainSync {
            view.evaluateJavascript("""JSON.stringify((() => {
                const check = (condition, message) => { if (!condition) throw new Error(message); };
                try { $script } catch (error) { return {error: error.stack}; }
            })())""") { result = it; done.countDown() }
        }
        assertTrue("Study script did not finish within 5 seconds", done.await(5, TimeUnit.SECONDS))
        assertEquals("Study script failed: $result", "true", JSONTokener(result).nextValue())
    }

    @Test fun collapsingPreservesCanonicalTextRestoresToVisibleHeadingAndAllowsHighlights() {
        load(block(0, "h1", "Test-only study fixture") + block(1, "h2", "Problem") + block(2, "p", "Solve this problem.") +
            block(3, "h2", "Approach") + block(4, "p", "The revealed answer.") + block(5, "h3", "Mistakes") +
            block(6, "p", "A nested mistake.") + block(7, "h2", "Complexities") + block(8, "p", "Linear time.") +
            block(12, "h2", "Context") + (13..40).joinToString("") { block(it, "p", "Test-only context paragraph $it. ".repeat(10)) })
        checkScript("""
            const api = window.reporead;
            const block = id => document.querySelector('[data-block-id="' + id + '"]');
            api.setStudy(true);
            check(block('b2').getBoundingClientRect().height > 0, 'Problem must stay visible');
            check(block('b4').getBoundingClientRect().height === 0, 'Approach must collapse');
            check(block('b6').getBoundingClientRect().height === 0, 'Nested Mistakes must collapse');
            const saved = {headingPath: [], textPrefix: block('b6').dataset.anchorText.slice(0, 64), blockIndex: 6};
            check(api.restore(saved, 0) === 'collapsed', 'Hidden passage must report collapsed, not exact');
            check(api.position().anchor.blockIndex === 3, 'Position must name visible Approach heading');
            api.showBlock('b6');
            check(block('b6').getBoundingClientRect().height > 0, 'Jump must reveal all collapsed ancestors');
            const range = document.createRange(); range.selectNodeContents(block('b6'));
            getSelection().removeAllRanges(); getSelection().addRange(range);
            check(api.capture().exactText === 'A nested mistake.', 'Selection capture in revealed section');
            check(api.highlight([{key:'test-only', blockId:'b6', startOffset:0, endOffset:1, exactText:'A'}]).length === 0, 'Highlight in revealed section');
            api.setStudy(false);
            check(block('b4').getBoundingClientRect().height > 0, 'Read mode must reveal everything');
            check([...document.querySelectorAll('#note [data-block-id]')].every(b => b.textContent === b.dataset.anchorText), 'Canonical text after collapse and highlight');
            return true;
        """.trimIndent())
    }

    @Test fun anAnswerPageShowsOnlyTheLinkedSectionWithItsSubsections() {
        load(block(0, "h1", "Test-only core note") + block(1, "p", "Introduction.") + block(2, "h2", "What SQL is") +
            block(3, "p", "The answer.") + block(4, "h3", "A detail") + "<ul>" + block(5, "li", "Inside the answer.") + "</ul>" +
            block(6, "h2", "The logical order") + block(7, "p", "Another answer.") + block(8, "h1", "Appendix") + block(9, "p", "Last."))
        checkScript("""
            const api = window.reporead;
            const shown = id => document.querySelector('[data-block-id="' + id + '"]').getBoundingClientRect().height > 0;
            const only = ids => ['b0','b1','b2','b3','b4','b5','b6','b7','b8','b9'].every(id => shown(id) === ids.includes(id));
            check(api.showSection('what-sql-is'), 'A GitHub anchor names the section');
            check(only(['b2','b3','b4','b5']), 'Only the section and its subsections show');
            check(window.scrollY === 0, 'The section starts at the top');
            check(api.showSection('The logical order'), 'Heading text names the section too');
            check(only(['b6','b7']), 'A section ends at the next heading of its level or higher');
            check(!api.showSection('missing-heading'), 'A missing heading is reported');
            check(api.showSection(null), 'A link to the whole note keeps everything else as it was');
            check([...document.querySelectorAll('#note [data-block-id]')].every(b => b.textContent === b.dataset.anchorText), 'Canonical text after showSection');
            return true;
        """.trimIndent())
    }
}
