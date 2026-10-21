// Owns: Device coverage that stored log lines reach the server once, and of the exit history the upload adds.

package com.reporead.android.sync

import android.app.ActivityManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker.Result
import com.reporead.android.auth.StoredSession
import com.reporead.android.data.LoopbackServer
import com.reporead.android.logging.AppLog
import com.reporead.android.logging.appLogDirectory
import com.reporead.android.network.Api
import com.reporead.android.network.wire.AppLogControllerLine
import com.reporead.android.network.wire.AppLogControllerUploadRequest
import com.reporead.android.network.wireJson
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
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
class AppLogUploadTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = File(context.cacheDir, "app-log-upload-test")

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

    private fun api(url: String) = Api(url, { StoredSession.Readable("TEST_ONLY_SESSION") }) {}

    private fun sentLines(server: LoopbackServer) =
        server.bodies.map { body ->
            wireJson.decodeFromString<AppLogControllerUploadRequest>(body).lines.map(AppLogControllerLine::message)
        }

    @Test
    fun linesAreSentOnceAndDeletedOnlyAfterTheServerLoggedThem() = runBlocking {
        AppLog.info("Reader ready; documentId=7")
        AppLog.warn("Bookmark refused; documentId=7 code=NOT_FOUND")

        LoopbackServer(mapOf("POST /api/app-logs" to (204 to ""))).use { server ->
            assertEquals(Result.success(), uploadAppLog(api(server.url), signedIn = true, attempt = 0))
            assertEquals(Result.success(), uploadAppLog(api(server.url), signedIn = true, attempt = 0))

            assertEquals(
                "nothing stored, nothing sent the second time",
                listOf(listOf("Reader ready; documentId=7", "Bookmark refused; documentId=7 code=NOT_FOUND")),
                sentLines(server),
            )
        }
        assertNull(AppLog.batch())
    }

    @Test
    fun linesTheServerDidNotLogAreKeptAndSentFirstNextTime() = runBlocking {
        AppLog.info("Library sync started; entryId=1")
        val closed = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }

        assertEquals(Result.retry(), uploadAppLog(api("http://127.0.0.1:$closed"), signedIn = true, attempt = 0))

        LoopbackServer(mapOf("POST /api/app-logs" to (204 to ""))).use { server ->
            uploadAppLog(api(server.url), signedIn = true, attempt = 1)
            assertEquals(listOf(listOf("Library sync started; entryId=1")), sentLines(server))
        }
        // The failed attempt's own lines wait for the next upload.
        assertTrue(AppLog.batch().orEmpty().any { it.contains("Backend I/O failed; method=POST path=/api/app-logs") })
    }

    @Test
    fun aBatchTheRouteRefusesIsDroppedAndSaysSo() = runBlocking {
        AppLog.info("Signed in")
        val refusal = """{"code":"INVALID_REQUEST","message":"test-only refusal"}"""

        LoopbackServer(mapOf("POST /api/app-logs" to (400 to refusal))).use { server ->
            assertEquals(Result.success(), uploadAppLog(api(server.url), signedIn = true, attempt = 0))
        }

        val left = AppLog.batch().orEmpty()
        assertTrue(left.toString(), left.none { it.contains("\"message\":\"Signed in\"") })
        assertTrue(left.toString(), left.any { it.contains("App log lines refused; lines=1 code=INVALID_REQUEST") })
    }

    @Test
    fun signedOutNothingIsSentAndTheLinesWait() = runBlocking {
        AppLog.info("Reader ready; documentId=7")

        LoopbackServer(mapOf("POST /api/app-logs" to (204 to ""))).use { server ->
            assertEquals(Result.success(), uploadAppLog(api(server.url), signedIn = false, attempt = 0))
            assertTrue(server.requests.isEmpty())
        }
        assertEquals(1, AppLog.batch().orEmpty().size)
    }

    /** Android's own exit records, as this phone holds them for the test's app: each logged once, by its own text. */
    @Test
    fun eachProcessExitIsLoggedOnceWithAndroidsDescription() {
        val activities = context.getSystemService(ActivityManager::class.java)
        val exits = activities.getHistoricalProcessExitReasons(null, 0, 0)
        val seen = File(store, "exits-seen")

        logProcessExits(activities, seen)
        logProcessExits(activities, seen)

        val lines = AppLog.batch().orEmpty().filter { it.contains("Process exited; at=") }
        assertEquals("one line per recorded exit, not repeated", exits.size, lines.size)
        for (exit in exits) {
            assertTrue(lines.toString(), lines.any { it.contains("reason=${exit.reason} (") })
        }
    }
}
