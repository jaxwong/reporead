package com.reporead.android.sync

import com.reporead.android.auth.StoredSession
import com.reporead.android.core.network.Api
import com.reporead.android.data.SavedVersion
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * What deleting saved images leaves on the phone, with real files in a temporary folder. A folder the app may not write
 * stands in for a file that cannot be deleted; the tests need a user that is not root.
 */
class NoteImagesDeletionTest {
    @get:Rule val temporary = TemporaryFolder()

    private val current = "a".repeat(40)
    private val older = "b".repeat(40)
    private val readOnly = mutableListOf<File>()

    /** Never makes a request: deleting needs no server. */
    private fun images() = NoteImages(Api("http://test-only.invalid", { StoredSession.Absent }) {}, temporary.root)

    private fun image(documentId: Long, snapshotCommitSha: String) =
        File(temporary.root, "images/$documentId/commit-$snapshotCommitSha/key").also {
            it.parentFile!!.mkdirs()
            it.writeText("test-only image")
        }

    /** Nothing in [folder] can be deleted until the test ends. */
    private fun cannotDeleteIn(folder: File) {
        check(folder.setWritable(false) && !folder.canWrite()) {
            "$folder is still writable; run as a user that is not root"
        }
        readOnly += folder
    }

    @After
    fun allowDeleting() {
        readOnly.forEach { it.setWritable(true) }
    }

    /**
     * Fetches one image through a test-only loopback server that holds the request until [whileHeld] has run, as a
     * deletion can run while the reader waits for the server.
     */
    private fun fetchAcross(whileHeld: (NoteImages) -> Unit) {
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            arrived.countDown()
            check(release.await(10, SECONDS)) { "Test never released the held request" }
            exchange.sendResponseHeaders(200, 3)
            exchange.responseBody.use { it.write("PNG".toByteArray()) }
        }
        server.start()
        try {
            val images =
                NoteImages(Api("http://127.0.0.1:${server.address.port}", { StoredSession.Absent }) {}, temporary.root)
            var bytes: ByteArray? = null
            val fetch = thread { bytes = images.image(1, current, "a.png") }
            try {
                check(arrived.await(10, SECONDS)) { "The fetch never reached the server" }
                whileHeld(images)
            } finally {
                release.countDown()
                fetch.join()
            }
            assertArrayEquals("PNG".toByteArray(), bytes)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun aFetchedImageIsKeptWhenNothingWasDeletedMeanwhile() {
        fetchAcross {}
        assertEquals(1, File(temporary.root, "images/1/commit-$current").listFiles()!!.size)
    }

    @Test
    fun aFetchThatStartedBeforeADeletionDoesNotWriteItsImageBack() {
        fetchAcross { it.sweep(emptyList()) }
        assertFalse(File(temporary.root, "images/1").exists())
    }

    private fun saved(documentId: Long, snapshotCommitSha: String) =
        SavedVersion(documentId, "c".repeat(40), "test-only.md", 3, snapshotCommitSha)

    /** What is left under images/, as document/version folders. */
    private fun versionsLeft() =
        File(temporary.root, "images")
            .listFiles()
            .orEmpty()
            .flatMap { document -> document.listFiles().orEmpty().map { "${document.name}/${it.name}" } }
            .toSet()

    @Test
    fun sweepingKeepsExactlyTheSavedVersionsImages() {
        image(1, current)
        image(1, older)
        image(2, current)
        image(3, current)
        val images = images()
        assertEquals(emptyList<File>(), images.sweep(listOf(saved(1, current), saved(2, current), saved(4, current))))
        assertEquals(setOf("1/commit-$current", "2/commit-$current"), versionsLeft())
        assertFalse(File(temporary.root, "images/3").exists())
        assertEquals(emptyList<File>(), images.sweep(listOf(saved(1, current), saved(2, current))))
        assertEquals(setOf("1/commit-$current", "2/commit-$current"), versionsLeft())
    }

    @Test
    fun sweepingWithNothingSavedDeletesEveryImage() {
        image(1, current)
        image(2, older)
        assertEquals(emptyList<File>(), images().sweep(emptyList()))
        assertEquals(emptySet<String>(), versionsLeft())
        assertEquals(emptyList<File>(), images().sweep(emptyList()))
        assertEquals(emptyList<File>(), deleteTree(File(temporary.root, "never-created")))
    }

    @Test
    fun whatCannotBeDeletedIsReturnedDeepestFirstAndTheRestIsDeleted() {
        val stuck = image(1, current)
        val other = image(2, current)
        cannotDeleteIn(stuck.parentFile!!)
        assertEquals(listOf(stuck, stuck.parentFile, File(temporary.root, "images/1")), images().sweep(emptyList()))
        assertFalse(other.exists())
    }

    @Test
    fun sweepingAgainOnceTheCauseIsGoneDeletesWhatWasLeft() {
        val stuck = image(1, current)
        cannotDeleteIn(stuck.parentFile!!)
        val images = images()
        assertTrue(images.sweep(emptyList()).isNotEmpty())
        allowDeleting()
        assertEquals(emptyList<File>(), images.sweep(emptyList()))
        assertFalse(File(temporary.root, "images/1").exists())
    }

    @Test
    fun anOlderVersionThatCannotBeDeletedIsReturnedAndTheSavedVersionKept() {
        val kept = image(1, current)
        val stuck = image(1, older)
        cannotDeleteIn(stuck.parentFile!!)
        assertEquals(listOf(stuck, stuck.parentFile), images().sweep(listOf(saved(1, current))))
        assertTrue(kept.exists())
        allowDeleting()
        assertEquals(emptyList<File>(), images().sweep(listOf(saved(1, current))))
        assertTrue(kept.exists())
        assertFalse(stuck.parentFile!!.exists())
    }
}
