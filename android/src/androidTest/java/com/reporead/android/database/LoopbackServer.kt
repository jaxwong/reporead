package com.reporead.android.database

import android.util.Log
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.Collections
import kotlin.concurrent.thread

/**
 * Test-only loopback HTTP server: answers each "METHOD /path" from [routes] (status and JSON body) and records every
 * request line and body. [answering] also answers routes [routes] lacks from their request, for answers that must echo
 * it; [closeWithoutAnswering] records one route before its connection closes with no answer. Any other route is
 * answered 500 and recorded, so it fails the test visibly. [beforeAnswer] runs on the server thread after recording a
 * request, so a test can hold one open.
 */
internal class LoopbackServer
private constructor(
    private val routes: Map<String, Pair<Int, String>>,
    private val unansweredRoutes: Set<String>,
    private val beforeAnswer: (String) -> Unit,
    private val answer: (route: String, body: String) -> Pair<Int, String>?,
) : AutoCloseable {
    constructor(
        routes: Map<String, Pair<Int, String>>,
        beforeAnswer: (String) -> Unit = {},
    ) : this(routes, emptySet(), beforeAnswer, { _, _ -> null })

    companion object {
        fun closeWithoutAnswering(route: String) = LoopbackServer(emptyMap(), setOf(route), {}, { _, _ -> null })

        fun answering(
            routes: Map<String, Pair<Int, String>>,
            answer: (route: String, body: String) -> Pair<Int, String>?,
        ) = LoopbackServer(routes, emptySet(), {}, answer)
    }

    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val url = "http://127.0.0.1:${socket.localPort}"
    val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val bodies: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val worker =
        thread(isDaemon = true, name = "test-only-loopback") {
            while (true) {
                val client =
                    try {
                        socket.accept()
                    } catch (closed: SocketException) {
                        break
                    }
                client.use(::serve)
            }
        }

    private fun serve(client: Socket) {
        client.soTimeout = 5_000
        val input = client.getInputStream().bufferedReader(Charsets.UTF_8)
        val line = input.readLine() ?: return
        var length = 0
        while (true) {
            val header = input.readLine() ?: break
            if (header.isEmpty()) break
            if (header.startsWith("Content-Length:", ignoreCase = true))
                length = header.substringAfter(':').trim().toInt()
        }
        val body = CharArray(length)
        var read = 0
        while (read < length) {
            val added = input.read(body, read, length - read)
            check(added > 0) { "Incomplete test HTTP body" }
            read += added
        }
        val key = line.substringBeforeLast(" HTTP/")
        requests += key
        bodies += String(body)
        if (key in unansweredRoutes) return
        beforeAnswer(key)
        val (status, json) =
            routes[key] ?: answer(key, String(body)) ?: (500 to """{"code":"TEST_UNEXPECTED_ROUTE","message":"$key"}""")
        val bytes = json.toByteArray(Charsets.UTF_8)
        val output = client.getOutputStream()
        try {
            output.write(
                "HTTP/1.1 $status Test\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                    .toByteArray()
            )
            output.write(bytes)
            output.flush()
        } catch (gone: SocketException) {
            // The client stopped waiting (its read timeout) while beforeAnswer held the answer; it reads nothing more,
            // and later requests are still served.
            Log.i("RepoRead", "Test-only loopback answer not delivered; request=$key error=${gone.message}")
        }
    }

    override fun close() {
        socket.close()
        worker.join(5_000)
    }
}
