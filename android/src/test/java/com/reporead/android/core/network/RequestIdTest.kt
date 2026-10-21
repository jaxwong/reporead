// Owns: Coverage that a failed request names the server's request id, so the server's log for it can be found.

package com.reporead.android.core.network

import com.reporead.android.auth.StoredSession
import com.reporead.android.core.log.AppLog
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Owns the request-id assertions for refused and unanswered requests. */
class RequestIdTest {
    @get:Rule val temporary = TemporaryFolder()

    @Before
    fun startLog() {
        AppLog.start(temporary.root)
    }

    @Test
    fun aRefusedRequestNamesTheServersRequestId() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val body = """{"code":"NOT_FOUND","message":"Not found."}""".toByteArray()
            exchange.responseHeaders.add("X-Request-Id", "test-only-request-id")
            exchange.sendResponseHeaders(404, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val api = Api("http://127.0.0.1:${server.address.port}", { StoredSession.Absent }) {}

            val error = assertThrows(ApiException::class.java) { api.noteImage(1, "a".repeat(40), "a.png") }

            assertEquals(404, error.status)
            assertEquals("NOT_FOUND", error.code)
            assertEquals("test-only-request-id", error.requestId)
            // The line the server's log is searched with reaches the phone's stored log too.
            val stored = File(temporary.root, "pending.jsonl").readText()
            assertTrue(
                stored,
                stored.contains(
                    "Backend refused; method=GET path=/api/documents/1/image status=404 code=NOT_FOUND requestId=test-only-request-id"
                ),
            )
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun aRequestNothingAnsweredHasNoRequestId() {
        // Port 1 refuses connections, so no answer and no id arrive.
        val api = Api("http://127.0.0.1:1", { StoredSession.Absent }) {}

        val error = assertThrows(ApiException::class.java) { api.noteImage(1, "a".repeat(40), "a.png") }

        assertEquals("BACKEND_UNAVAILABLE", error.code)
        assertNull(error.requestId)
    }
}
