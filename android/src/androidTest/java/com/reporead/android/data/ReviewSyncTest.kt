package com.reporead.android.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reporead.android.core.network.Api
import com.reporead.android.core.network.ApiException
import com.reporead.android.sync.Sync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.time.Instant
import java.util.UUID

/** Explicit test-only loopback HTTP fixture; production Sync/Api/Room wiring, never the user's backend or database. */
@RunWith(AndroidJUnit4::class)
class ReviewSyncTest {
    @Test fun partialFailureKeepsUnacknowledgedGradesAndSecondRunAndReplaysDoNotDuplicateThem() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "test-only-review-sync-${UUID.randomUUID()}")
        check(directory.mkdirs())
        try {
            val store = Room.inMemoryDatabaseBuilder(context, LocalStore::class.java).build()
            try {
                ServerSocket(0, 10, InetAddress.getByName("127.0.0.1")).use { server ->
                    server.soTimeout = 5_000
                    val dao = store.library()
                    val card = AnnotationRow("card", 1, 7, "a".repeat(40), "b1", 0, 4, "text", null, 1, 0, false, null,
                        type = "CARD", question = "Test-only question?", checkedBlobSha = "a".repeat(40))
                    dao.saveAnnotation(card)
                    val grades = (1..10).map { ReviewRow(UUID.randomUUID().toString(), "card", 4, it.toLong(), "a".repeat(40), true) }
                    grades.forEach { dao.saveReview(it) }
                    val sync = Sync(Api("http://127.0.0.1:${server.localPort}") { "TEST_ONLY_SESSION" }, store, directory)
                    val stored = mutableMapOf<String, JSONObject>()
                    val first = async(Dispatchers.IO) { respond(server, 6, failLast = true, stored) }
                    try {
                        sync.pushReviews()
                        fail("Expected a typed 503 failure")
                    } catch (error: ApiException) { assertEquals("GITHUB_UNAVAILABLE", error.code) }
                    first.await()
                    assertEquals(5, stored.size)
                    assertEquals(5, dao.pendingReviews().size)
                    val second = async(Dispatchers.IO) { respond(server, 5, failLast = false, stored) }
                    sync.pushReviews()
                    second.await()
                    assertEquals(10, stored.size)
                    assertTrue(dao.pendingReviews().isEmpty())
                    // Lost local acknowledgement: explicitly resend the identical mutation.
                    dao.saveReview(grades.first())
                    val replay = async(Dispatchers.IO) { respond(server, 1, failLast = false, stored) }
                    sync.pushReviews()
                    replay.await()
                    assertEquals(10, stored.size)
                    assertTrue(dao.pendingReviews().isEmpty())
                    sync.pushReviews() // empty: makes no HTTP calls
                    assertEquals(10, stored.size)
                }
            } finally { store.close() }
        } finally { check(directory.deleteRecursively()) }
    }

    private fun respond(server: ServerSocket, count: Int, failLast: Boolean, stored: MutableMap<String, JSONObject>) {
        repeat(count) { index ->
            server.accept().use { socket ->
                socket.soTimeout = 5_000
                val input = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                assertEquals("POST /api/annotations/1/reviews HTTP/1.1", input.readLine())
                var length = 0
                while (true) {
                    val line = checkNotNull(input.readLine())
                    if (line.isEmpty()) break
                    if (line.startsWith("Content-Length:", ignoreCase = true)) length = line.substringAfter(':').trim().toInt()
                }
                val content = CharArray(length)
                var read = 0
                while (read < length) {
                    val added = input.read(content, read, length - read)
                    check(added > 0) { "Incomplete test HTTP body" }
                    read += added
                }
                val request = JSONObject(String(content))
                val failed = failLast && index == count - 1
                val response = if (failed) JSONObject().put("code", "GITHUB_UNAVAILABLE").put("message", "Test-only failure")
                else {
                    val id = request.getString("mutationId")
                    val row = JSONObject().put("mutationId", id).put("cardMutationId", "card").put("grade", request.getInt("grade"))
                        .put("reviewedAt", Instant.parse(request.getString("reviewedAt")).toString()).put("blobSha", request.getString("blobSha"))
                    val previous = stored.putIfAbsent(id, row)
                    if (previous != null) assertEquals(previous.toString(), row.toString())
                    row
                }
                val bytes = response.toString().toByteArray(Charsets.UTF_8)
                val output = socket.getOutputStream()
                output.write("HTTP/1.1 ${if (failed) "503 Unavailable" else "200 OK"}\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                output.write(bytes)
                output.flush()
            }
        }
    }
}
