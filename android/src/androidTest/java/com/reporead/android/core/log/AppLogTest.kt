// Owns: Device coverage of the phone's log store: what is kept, within what cap, and how a batch is handed over.

package com.reporead.android.core.log

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reporead.android.BuildConfig
import com.reporead.android.core.network.wire.AppLogControllerLine
import com.reporead.android.core.network.wireJson
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Each test has its own store; the app's own store is restored after it. */
@RunWith(AndroidJUnit4::class)
class AppLogTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = File(context.cacheDir, "app-log-test")

    @Before
    fun start() {
        store.deleteRecursively()
        AppLog.start(store)
    }

    @After
    fun restore() {
        AppLog.start(appLogDirectory(context))
        store.deleteRecursively()
    }

    /** The batch's lines, as the upload worker decodes them. */
    private fun batch(): List<AppLogControllerLine> =
        AppLog.batch().orEmpty().map { wireJson.decodeFromString<AppLogControllerLine>(it) }

    @Test
    fun aLineIsStoredAsTheLineTheServerTakesWithThisBuildsVersion() {
        AppLog.info("Reader ready; documentId=7")
        AppLog.warn("Bookmark refused; documentId=7 code=NOT_FOUND")

        val lines = batch()

        assertEquals(
            listOf(
                AppLogControllerLine.Level.INFO to "Reader ready; documentId=7",
                AppLogControllerLine.Level.WARN to "Bookmark refused; documentId=7 code=NOT_FOUND",
            ),
            lines.map { it.level to it.message },
        )
        assertTrue(lines.all { it.appVersion == BuildConfig.VERSION_NAME })
    }

    @Test
    fun aDebugLineIsNotStored() {
        AppLog.debug("Reading position saved; documentId=7 percent=18 block=20 viewHeight=2048")
        AppLog.info("Reader ready; documentId=7")

        assertEquals(listOf("Reader ready; documentId=7"), batch().map { it.message })
    }

    @Test
    fun aBatchStaysUntilSentAndLaterLinesWaitForTheNext() {
        assertNull(AppLog.batch())
        AppLog.info("First")
        assertEquals(listOf("First"), batch().map { it.message })

        AppLog.info("Second")
        assertEquals("an unsent batch is offered again, unchanged", listOf("First"), batch().map { it.message })

        AppLog.sent()
        assertEquals(listOf("Second"), batch().map { it.message })
        AppLog.sent()
        assertNull(AppLog.batch())
    }

    @Test
    fun aFullStoreKeepsItsNewestLinesWithinTheCapAndCountsTheRest() {
        val line = "Reader opened at a highlight; documentId=1 annotationId=2 found=true"
        repeat(5_000) { AppLog.info("$line n=$it") }

        assertTrue(File(store, "pending.jsonl").length() <= BuildConfig.REQUEST_MAX_BYTES / 2)
        val lines = batch()
        assertEquals("$line n=4999", lines.last().message)
        assertTrue(lines.any { it.message.startsWith("App log store full; dropped=") })
    }

    @Test
    fun theLongestLineSurvivesTrimmingEvenWhenEveryUnitIsEscaped() {
        AppLog.info("\u0001".repeat(MAX_LINE_CHARS + 10))
        repeat(3_000) { AppLog.info("Reader ready; documentId=$it") }
        AppLog.warn("\u0001".repeat(MAX_LINE_CHARS))

        assertEquals(MAX_LINE_CHARS, batch().last().message.length)
    }

    /** A crash's message is often the explanation; a reader script failure carries the page's JS stack in it. */
    @Test
    fun aThrowablesStoredCopyHasItsClassesMessagesAndFrames() {
        val error =
            IllegalStateException(
                "Page script failed: window.reporead.outline(): TypeError: x is undefined",
                IllegalArgumentException("Expected a non-blank failure code"),
            )

        AppLog.warn("Could not stop Save all; repositoryId=3", error)
        AppLog.crash(Thread.currentThread(), error)

        val (warned, crashed) = batch()
        for (line in listOf(warned, crashed)) {
            assertTrue(
                line.message,
                line.message.contains(
                    "java.lang.IllegalStateException: Page script failed: window.reporead.outline(): " +
                        "TypeError: x is undefined\n\tat "
                ),
            )
            assertTrue(
                line.message,
                line.message.contains(
                    "\nCaused by: java.lang.IllegalArgumentException: Expected a non-blank failure code\n\tat "
                ),
            )
            assertTrue(line.message, line.message.contains("AppLogTest.aThrowablesStoredCopy"))
        }
        assertTrue(warned.message.startsWith("Could not stop Save all; repositoryId=3\n"))
        assertEquals(AppLogControllerLine.Level.ERROR, crashed.level)
        assertTrue(crashed.message.startsWith("Crash; thread=${Thread.currentThread().name}\n"))
    }

    @Test
    fun aLineTheProcessEndedWhileWritingIsNotInTheBatch() {
        AppLog.info("Reader ready; documentId=7")
        File(store, "pending.jsonl").appendText("""{"at":"2026-10-21T06:02:11Z","appVersion":"0.1.1","lev""")

        assertEquals(listOf("Reader ready; documentId=7"), batch().map { it.message })
    }
}
