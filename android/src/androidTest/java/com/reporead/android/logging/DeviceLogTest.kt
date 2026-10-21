// Owns: Device coverage of the phone's log store: what is kept, within what cap, and how a batch is handed over.

package com.reporead.android.logging

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reporead.android.BuildConfig
import com.reporead.android.network.wire.DeviceLogLine
import com.reporead.android.network.wireJson
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
class DeviceLogTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = File(context.cacheDir, "app-log-test")

    @Before
    fun start() {
        store.deleteRecursively()
        DeviceLog.start(store)
    }

    @After
    fun restore() {
        DeviceLog.requestUploadOnWarning {}
        DeviceLog.start(deviceLogDirectory(context))
        store.deleteRecursively()
    }

    /** The batch's lines, as the upload worker decodes them. */
    private fun batch(): List<DeviceLogLine> =
        DeviceLog.claimPendingBatch().orEmpty().map { wireJson.decodeFromString<DeviceLogLine>(it) }

    @Test
    fun aLineIsStoredAsTheLineTheServerTakesWithThisBuildsVersion() {
        DeviceLog.info("Reader ready; documentId=7")
        DeviceLog.warn("Bookmark refused; documentId=7 code=NOT_FOUND")

        val lines = batch()

        assertEquals(
            listOf(
                DeviceLogLine.Level.INFO to "Reader ready; documentId=7",
                DeviceLogLine.Level.WARN to "Bookmark refused; documentId=7 code=NOT_FOUND",
            ),
            lines.map { it.level to it.message },
        )
        assertTrue(lines.all { it.appVersion == BuildConfig.VERSION_NAME })
    }

    /** A warning is what a crash is diagnosed from, so it asks for an upload; other lines wait for the next one. */
    @Test
    fun onlyAWarningRequestsAnUpload() {
        var requests = 0
        DeviceLog.requestUploadOnWarning { requests++ }

        DeviceLog.debug("Reading position saved; documentId=7 percent=18 block=20 viewHeight=2048")
        DeviceLog.info("Reader ready; documentId=7")
        assertEquals(0, requests)

        DeviceLog.warn("Bookmark refused; documentId=7 code=NOT_FOUND")
        assertEquals(1, requests)
    }

    @Test
    fun aDebugLineIsNotStored() {
        DeviceLog.debug("Reading position saved; documentId=7 percent=18 block=20 viewHeight=2048")
        DeviceLog.info("Reader ready; documentId=7")

        assertEquals(listOf("Reader ready; documentId=7"), batch().map { it.message })
    }

    @Test
    fun aLostAcknowledgementLeavesOneReplayableClaimAndLaterLinesWaitForTheNext() {
        assertNull(DeviceLog.claimPendingBatch())
        DeviceLog.info("First")
        assertEquals(listOf("First"), batch().map { it.message })

        DeviceLog.info("Second")
        assertEquals("the unacknowledged claim is replayed unchanged", listOf("First"), batch().map { it.message })

        DeviceLog.finishClaimedBatch()
        assertEquals(listOf("Second"), batch().map { it.message })
        DeviceLog.finishClaimedBatch()
        assertNull(DeviceLog.claimPendingBatch())
    }

    @Test
    fun aFullStoreKeepsItsNewestLinesWithinTheCapAndCountsTheRest() {
        val line = "Reader opened at a highlight; documentId=1 annotationId=2 found=true"
        repeat(5_000) { DeviceLog.info("$line n=$it") }

        assertTrue(File(store, "pending.jsonl").length() <= BuildConfig.REQUEST_MAX_BYTES / 2)
        val lines = batch()
        assertEquals("$line n=4999", lines.last().message)
        assertTrue(lines.any { it.message.startsWith("Device log store full; dropped=") })
    }

    @Test
    fun theLongestLineSurvivesTrimmingEvenWhenEveryUnitIsEscaped() {
        DeviceLog.info("\u0001".repeat(MAX_LINE_CHARS + 10))
        repeat(3_000) { DeviceLog.info("Reader ready; documentId=$it") }
        DeviceLog.warn("\u0001".repeat(MAX_LINE_CHARS))

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

        DeviceLog.warn("Could not stop Save all; repositoryId=3", error)
        DeviceLog.crash(Thread.currentThread(), error)

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
            assertTrue(line.message, line.message.contains("DeviceLogTest.aThrowablesStoredCopy"))
        }
        assertTrue(warned.message.startsWith("Could not stop Save all; repositoryId=3\n"))
        assertEquals(DeviceLogLine.Level.ERROR, crashed.level)
        assertTrue(crashed.message.startsWith("Crash; thread=${Thread.currentThread().name}\n"))
    }

    @Test
    fun aLineTheProcessEndedWhileWritingIsNotInTheBatch() {
        DeviceLog.info("Reader ready; documentId=7")
        File(store, "pending.jsonl").appendText("""{"at":"2026-10-21T06:02:11Z","appVersion":"0.1.1","lev""")

        assertEquals(listOf("Reader ready; documentId=7"), batch().map { it.message })
    }
}
