// Owns: Device coverage that stored log lines reach the server once, and of the exit history the upload adds.

package com.reporead.android.sync

import android.app.ActivityManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker.Result
import com.reporead.android.auth.StoredSession
import com.reporead.android.database.LoopbackServer
import com.reporead.android.logging.DeviceLog
import com.reporead.android.logging.deviceLogDirectory
import com.reporead.android.network.Api
import com.reporead.android.network.wire.DeviceLogIngestControllerUploadRequest
import com.reporead.android.network.wire.DeviceLogLine
import com.reporead.android.network.wireJson
import java.io.File
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Against a test-only loopback server, never the backend; each test has its own store. */
@RunWith(AndroidJUnit4::class)
class DeviceLogUploadTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = File(context.cacheDir, "app-log-upload-test")

    @Before
    fun start() {
        store.deleteRecursively()
        DeviceLog.start(store) {}
    }

    @After
    fun restore() {
        DeviceLog.start(deviceLogDirectory(context)) {}
        store.deleteRecursively()
    }

    private fun api(url: String) = Api(url, { StoredSession.Readable("TEST_ONLY_SESSION") }) {}

    private fun sentBatches(server: LoopbackServer) =
        server.bodies.map { body ->
            wireJson.decodeFromString<DeviceLogIngestControllerUploadRequest>(body).lines
        }

    private fun claimedLines() =
        DeviceLog.claimPendingBatch().orEmpty().map { wireJson.decodeFromString<DeviceLogLine>(it) }

    @Test
    fun linesAreSentOnceAndDeletedOnlyAfterTheServerLoggedThem() = runBlocking {
        DeviceLog.info("Reader ready; documentId=7")
        DeviceLog.warn("Bookmark refused; documentId=7 code=NOT_FOUND")

        LoopbackServer(mapOf("POST /api/app-logs" to (204 to ""))).use { server ->
            assertEquals(Result.success(), uploadDeviceLogBatches(api(server.url), signedIn = true))
            assertEquals(Result.success(), uploadDeviceLogBatches(api(server.url), signedIn = true))

            assertEquals(
                "nothing stored, nothing sent the second time",
                listOf(listOf("Reader ready; documentId=7", "Bookmark refused; documentId=7 code=NOT_FOUND")),
                sentBatches(server).map { batch -> batch.map(DeviceLogLine::message) },
            )
        }
        assertNull(DeviceLog.claimPendingBatch())
    }

    @Test
    fun aLostAcknowledgementLeavesTheReceivedBatchReplayableAndItIsSentFirstNextTime() = runBlocking {
        DeviceLog.info("Library sync started; entryId=1")
        LoopbackServer.closeWithoutAnswering("POST /api/app-logs").use { server ->
            // Not retried on a backoff, which would hold later requests: the next request sends the lines.
            assertEquals(
                Result.failure(),
                uploadDeviceLogBatches(api(server.url), signedIn = true),
            )
            assertEquals(
                listOf("Library sync started; entryId=1"),
                sentBatches(server).single().map(DeviceLogLine::message),
            )
        }
        assertEquals(listOf("Library sync started; entryId=1"), claimedLines().map(DeviceLogLine::message))

        LoopbackServer(mapOf("POST /api/app-logs" to (204 to ""))).use { server ->
            assertEquals(Result.success(), uploadDeviceLogBatches(api(server.url), signedIn = true))
            val (replayed, since) = sentBatches(server).map { batch -> batch.map(DeviceLogLine::message) }
            assertEquals(listOf("Library sync started; entryId=1"), replayed)
            // The lines stored after the failure, its own included, go in the same run rather than waiting behind it.
            assertTrue(
                since.toString(),
                since.any { it.contains("Backend I/O failed; method=POST path=/api/app-logs") },
            )
            assertTrue(since.toString(), since.any { it.startsWith("Device log upload failed; lines=1 status=0") })
        }
        assertNull(DeviceLog.claimPendingBatch())
    }

    @Test
    fun aPendingLineWrittenBeforeTheRenameUploadsWithItsOriginalFields() = runBlocking {
        File(store, "pending.jsonl")
            .writeText(
                """{"at":"2026-10-21T06:02:11.123Z","appVersion":"0.1.432","level":"WARN","message":"Older run"}""" +
                    "\n"
            )

        LoopbackServer(mapOf("POST /api/app-logs" to (204 to ""))).use { server ->
            assertEquals(Result.success(), uploadDeviceLogBatches(api(server.url), signedIn = true))
            assertEquals(
                DeviceLogLine(
                    at = Instant.parse("2026-10-21T06:02:11.123Z"),
                    appVersion = "0.1.432",
                    level = DeviceLogLine.Level.WARN,
                    message = "Older run",
                ),
                sentBatches(server).single().single(),
            )
        }
        assertNull(DeviceLog.claimPendingBatch())
    }

    @Test
    fun aBatchTheRouteRefusesIsDroppedAndSaysSo() = runBlocking {
        DeviceLog.info("Signed in")
        val refusal = """{"code":"INVALID_REQUEST","message":"test-only refusal"}"""

        LoopbackServer(mapOf("POST /api/app-logs" to (400 to refusal))).use { server ->
            assertEquals(Result.success(), uploadDeviceLogBatches(api(server.url), signedIn = true))
            // This server refuses every batch, so the first refusal's own lines, sent next, are refused too.
            val (refused, since) = sentBatches(server).map { batch -> batch.map(DeviceLogLine::message) }
            assertEquals(listOf("Signed in"), refused)
            assertTrue(since.toString(), since.contains("Device log lines refused; lines=1 code=INVALID_REQUEST"))
        }

        val left = claimedLines()
        assertTrue(left.toString(), left.none { it.message == "Signed in" })
        assertTrue(
            left.toString(),
            left.any { it.message.contains("Device log lines refused; lines=2 code=INVALID_REQUEST") },
        )
    }

    @Test
    fun signedOutNothingIsSentAndTheLinesWait() = runBlocking {
        DeviceLog.info("Reader ready; documentId=7")

        LoopbackServer(mapOf("POST /api/app-logs" to (204 to ""))).use { server ->
            assertEquals(Result.success(), uploadDeviceLogBatches(api(server.url), signedIn = false))
            assertTrue(server.requests.isEmpty())
        }
        assertEquals(1, DeviceLog.claimPendingBatch().orEmpty().size)
    }

    /** Android's own exit records, as this phone holds them for the test's app: each logged once, by its own text. */
    @Test
    fun eachProcessExitIsLoggedOnceWithAndroidsDescription() {
        val activities = context.getSystemService(ActivityManager::class.java)
        val exits = activities.getHistoricalProcessExitReasons(null, 0, 0)
        val seen = File(store, "exits-seen")

        logProcessExits(activities, seen)
        logProcessExits(activities, seen)

        val lines = claimedLines().filter { it.message.contains("Process exited; at=") }
        assertEquals("one line per recorded exit, not repeated", exits.size, lines.size)
        for (exit in exits) {
            assertTrue(lines.toString(), lines.any { it.message.contains("reason=${exit.reason} (") })
        }
    }
}
