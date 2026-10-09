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

    private fun load(questions: Boolean) {
        fun block(id: Int, tag: String, text: String) = """<$tag data-block-id="b$id" data-anchor-text="$text">$text</$tag>"""
        val body = block(0, "h1", "Test-only study fixture") + block(1, "h2", "Problem") + block(2, "p", "Solve this problem.") +
            block(3, "h2", "Approach") + block(4, "p", "The revealed answer.") + block(5, "h3", "Mistakes") +
            block(6, "p", "A nested mistake.") + block(7, "h2", "Complexities") + block(8, "p", "Linear time.") +
            (if (questions) block(9, "h2", "Questions this file answers") + "<ul>" + block(10, "li", "First question?") + block(11, "li", "Second question?") + block(44, "li", "First question?") + "</ul>" +
                block(41, "h2", "Review and practice") + block(42, "p", "Trace a request, then test:") + "<ul>" + block(43, "li", "empty input") + "</ul>" else "") +
            block(12, "h2", "Context") + (13..40).joinToString("") { block(it, "p", "Test-only context paragraph $it. ".repeat(10)) }
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
        load(questions = false)
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

    @Test fun questionsAppearOneAtATimeRestoreAndRevealTheUnchangedNote() {
        load(questions = true)
        checkScript("""
            const api = window.reporead;
            api.setStudy(true);
            check(getComputedStyle(document.getElementById('note')).display === 'none', 'Recall must hide answers');
            check(document.querySelector('.study-question').textContent === 'First question?', 'First question');
            check(api.position().progressPercent < 100, 'Recall panel must not mark the note finished');
            document.querySelector('.study-next').click();
            const saved = api.position();
            check(saved.anchor.blockIndex === 11, 'Save the current question, not the first hidden block');
            api.setStudy(false); api.setStudy(true);
            check(api.restore(saved.anchor, saved.progressPercent) === 'exact', 'Question position restores exactly');
            check(document.querySelector('.study-question').textContent === 'Second question?', 'Restored question');
            document.querySelector('.study-answer').click();
            check(getComputedStyle(document.getElementById('note')).display !== 'none', 'Read the answer shows note');
            check(api.currentStudyQuestion() === 'b11', 'Add to review keeps the authored question after revealing');
            const sha = 'a'.repeat(40);
            check(api.studyQuestion(sha, 'b10'), 'Queue question opens');
            check(api.studyQuestion(sha, 'b44'), 'Duplicate prompt opens by its own block');
            check(api.currentStudyQuestion() === 'b44', 'Duplicate questions retain distinct review identities');
            check(api.position().anchor.blockIndex === [...document.querySelectorAll('#note [data-block-id]')].findIndex(b => b.dataset.blockId === 'b44'), 'Second identical prompt must not open first');
            check(!api.studyQuestion('b'.repeat(40), 'b44'), 'A different source version must reject the block identity');
            check(api.studyQuestion(sha, 'b43'), 'Queue opens an instruction-aware prompt');
            check(document.querySelector('.study-question').textContent === 'Trace a request, then test:\n\nempty input', 'Prompt retains leading instruction');
            check(!api.studyQuestion(sha, 'removed'), 'Missing question must not silently open another');
            check([...document.querySelectorAll('#note [data-block-id]')].every(b => b.textContent === b.dataset.anchorText), 'Canonical text after recall');
            return true;
        """.trimIndent())
    }
}
