// Owns: Coverage that the phone's log lines are stored within their cap and reach the server once.

package com.reporead.android.core.log

import com.reporead.android.BuildConfig
import com.reporead.android.auth.StoredSession
import com.reporead.android.core.network.Api
import com.reporead.android.core.network.ApiException
import com.reporead.android.core.network.wire.AppLogControllerLine
import com.reporead.android.core.network.wire.AppLogControllerUploadRequest
import com.reporead.android.core.network.wireJson
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Owns the store, trimming, upload and throwable-trace assertions, against a test-only loopback server. */
class AppLogTest {
    @get:Rule val temporary = TemporaryFolder()

    private val received = mutableListOf<AppLogControllerUploadRequest>()
    /** The status the test-only server answers with; 204 is the route's success. */
    private var answer = 204
    private lateinit var server: HttpServer

    @Before
    fun start() {
        AppLog.start(temporary.root)
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/app-logs") { exchange ->
            received +=
                wireJson.decodeFromString<AppLogControllerUploadRequest>(exchange.requestBody.reader().readText())
            if (answer == 204) exchange.sendResponseHeaders(204, -1)
            else {
                val body = """{"code":"INVALID_REQUEST","message":"test-only refusal"}""".toByteArray()
                exchange.sendResponseHeaders(answer, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            exchange.close()
        }
        server.start()
    }

    @After
    fun stop() {
        server.stop(0)
    }

    private fun api(url: String = "http://127.0.0.1:${server.address.port}") =
        Api(url, { StoredSession.Readable("TEST_ONLY_SESSION") }) {}

    private fun stored(name: String = "pending.jsonl"): List<AppLogControllerLine> =
        File(temporary.root, name).takeIf { it.exists() }?.readLines().orEmpty().map { wireJson.decodeFromString(it) }

    @Test
    fun linesAreSentOnceWithTheAppVersionAndDeletedOnlyAfterTheServerLoggedThem() = runBlocking {
        AppLog.info("Reader ready; documentId=7")
        AppLog.warn("Bookmark refused; documentId=7 code=NOT_FOUND")

        AppLog.upload(api())

        assertEquals(1, received.size)
        assertTrue(received[0].lines.all { it.appVersion == BuildConfig.VERSION_NAME })
        assertEquals(
            listOf(
                AppLogControllerLine.Level.INFO to "Reader ready; documentId=7",
                AppLogControllerLine.Level.WARN to "Bookmark refused; documentId=7 code=NOT_FOUND",
            ),
            received[0].lines.map { it.level to it.message },
        )
        assertTrue(stored().isEmpty() && stored("sending.jsonl").isEmpty())
        AppLog.upload(api())
        assertEquals("nothing stored, nothing sent", 1, received.size)
    }

    @Test
    fun linesTheServerDidNotLogAreKeptAndSentFirstNextTime() = runBlocking {
        AppLog.info("Library sync started; entryId=1")

        // Port 1 refuses connections: the server never answered.
        val unreachable =
            assertThrows(ApiException::class.java) { runBlocking { AppLog.upload(api("http://127.0.0.1:1")) } }
        assertTrue(unreachable.unreachable)
        assertEquals(listOf("Library sync started; entryId=1"), stored("sending.jsonl").map { it.message })

        AppLog.upload(api())
        assertEquals(listOf("Library sync started; entryId=1"), received.single().lines.map { it.message })
        // The failed attempt's own line waits for the next upload.
        assertTrue(stored().single().message.startsWith("Backend I/O failed; method=POST path=/api/app-logs"))
    }

    @Test
    fun aBatchTheRouteRefusesIsDroppedAndSaysSo() = runBlocking {
        AppLog.info("Signed in")
        answer = 400

        AppLog.upload(api())

        assertTrue(stored("sending.jsonl").isEmpty())
        assertTrue(stored().any { it.message == "App log lines refused; lines=1 code=INVALID_REQUEST" })
    }

    @Test
    fun aFullStoreKeepsItsNewestLinesWithinTheCapAndCountsTheRest() {
        val line = "Reading position saved; documentId=1 percent=18 block=20 viewHeight=2048"
        repeat(5_000) { AppLog.info("$line n=$it") }

        val file = File(temporary.root, "pending.jsonl")
        assertTrue(file.length() <= BuildConfig.REQUEST_MAX_BYTES / 2)
        val lines = stored()
        assertTrue(lines.last().message == "$line n=4999")
        val dropped = lines.filter { it.message.startsWith("App log store full; dropped=") }
        assertTrue(dropped.isNotEmpty())
    }

    @Test
    fun theLongestLineSurvivesTrimmingEvenWhenEveryUnitIsEscaped() {
        AppLog.info("\u0001".repeat(MAX_LINE_CHARS + 10))
        repeat(3_000) { AppLog.info("Reader ready; documentId=$it") }
        AppLog.warn("\u0001".repeat(MAX_LINE_CHARS))

        val newest = stored().last()
        assertEquals(MAX_LINE_CHARS, newest.message.length)
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

        val (warned, crashed) = stored()
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
    fun aLineTheProcessEndedWhileWritingIsNotSent() = runBlocking {
        AppLog.info("Reader ready; documentId=7")
        File(temporary.root, "pending.jsonl").appendText("""{"at":"2026-10-21T06:02:11Z","appVersion":"0.1.1","lev""")

        AppLog.upload(api())

        assertEquals(listOf("Reader ready; documentId=7"), received.single().lines.map { it.message })
    }
}
