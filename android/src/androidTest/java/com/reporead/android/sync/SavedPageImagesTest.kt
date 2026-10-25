package com.reporead.android.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reporead.android.auth.StoredSession
import com.reporead.android.database.LoopbackServer
import com.reporead.android.database.SavedPageEntity
import com.reporead.android.database.SavedVersion
import com.reporead.android.network.Api
import com.reporead.android.network.ApiException
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Test-only loopback server and a scratch folder, never the backend or the app's own files. */
@RunWith(AndroidJUnit4::class)
class SavedPageImagesTest {
    private val sha = "a".repeat(40)
    private val filesDir =
        File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "test-only-note-images")

    @Before
    @After
    fun clean() {
        check(filesDir.deleteRecursively())
    }

    @Test
    fun snapshotIdentityPinsBothTheRequestAndTheCacheEvenWhenMarkdownDidNotChange() {
        val newer = "b".repeat(40)
        LoopbackServer(
                mapOf(
                    "GET /api/documents/1/image?commitSha=$sha&path=a.png" to (200 to "OLD_PNG"),
                    "GET /api/documents/1/image?commitSha=$newer&path=a.png" to (200 to "NEW_PNG"),
                )
            )
            .use { server ->
                // A real v13 key in the retired namespace must not alias a new commit key with the same SHA string.
                val legacy = File(filesDir, "images/1/$sha").apply { check(mkdirs()) }
                val key =
                    MessageDigest.getInstance("SHA-256").digest("a.png".toByteArray()).joinToString("") {
                        "%02x".format(it)
                    }
                File(legacy, key).writeText("LEGACY_IMAGE_MUST_NOT_BE_READ")
                val images =
                    SavedPageImages(Api(server.url, { StoredSession.Readable("TEST_ONLY_SESSION") }) {}, filesDir)
                repeat(2) {
                    assertArrayEquals("OLD_PNG".toByteArray(), images.loadImageBytes(1, sha, "a.png"))
                    assertArrayEquals("NEW_PNG".toByteArray(), images.loadImageBytes(1, newer, "a.png"))
                }
                assertTrue(server.requests.toString(), server.requests.size == 2)
                images.deleteImagesExceptSavedPages(
                    listOf(SavedVersion(1, sha, "a.md", 3, sha), SavedVersion(1, sha, "a.md", 3, newer))
                )
                assertTrue(!legacy.exists())
                assertTrue(File(filesDir, "images/1/commit-$sha").listFiles()!!.none { it.name.endsWith(".partial") })
            }
    }

    @Test
    fun aLegacyPageCannotReadItsOldCacheOrFetchAnUnprovenSnapshot() = runBlocking {
        LoopbackServer(emptyMap()).use { server ->
            val directory = File(filesDir, "images/1/$sha").apply { check(mkdirs()) }
            File(directory, "old-key").writeText("OLD_IMAGE")
            val images = SavedPageImages(Api(server.url, { StoredSession.Readable("TEST_ONLY_SESSION") }) {}, filesDir)
            assertNull(images.loadImageBytes(1, null, "a.png"))
            images.saveImagesToDisk(
                SavedPageEntity(1, sha, "a.md", "a", """<main><img src="/repo-image/a.png"></main>""", 0)
            )
            images.deleteImagesExceptSavedPages(listOf(SavedVersion(1, sha, "a.md", 3, null)))
            assertTrue(!directory.exists())
            assertTrue(server.requests.isEmpty())
        }
    }

    @Test
    fun onlyTheReaderConvertsAnImageApiFailureToUnavailable() = runBlocking {
        val request = "GET /api/documents/1/image?commitSha=$sha&path=a.png"
        LoopbackServer(mapOf(request to (503 to """{"code":"GITHUB_UNAVAILABLE","message":"Test-only unavailable"}""")))
            .use { server ->
                val images =
                    SavedPageImages(Api(server.url, { StoredSession.Readable("TEST_ONLY_SESSION") }) {}, filesDir)
                assertNull(images.loadImageBytes(1, sha, "a.png"))
                val note =
                    SavedPageEntity(
                        1,
                        sha,
                        "a.md",
                        "a",
                        """<main><img src="/repo-image/a.png"></main>""",
                        0,
                        snapshotCommitSha = sha,
                    )
                val error = runCatching { images.saveImagesToDisk(note) }.exceptionOrNull()
                assertTrue("Save all must receive the image failure", error is ApiException)
                assertEquals("GITHUB_UNAVAILABLE", (error as ApiException).code)
                assertEquals(listOf(request, request), server.requests)
            }
    }

    @Test
    fun anUnreadableCachedImageDoesNotTriggerAnotherFetchStrategy() {
        LoopbackServer(mapOf("GET /api/documents/1/image?commitSha=$sha&path=a.png" to (200 to "PNG"))).use { server ->
            val images = SavedPageImages(Api(server.url, { StoredSession.Readable("TEST_ONLY_SESSION") }) {}, filesDir)
            assertArrayEquals("PNG".toByteArray(), images.loadImageBytes(1, sha, "a.png"))
            val file = File(filesDir, "images/1/commit-$sha").listFiles()!!.single()
            check(file.setReadable(false, false))
            try {
                assertNull(images.loadImageBytes(1, sha, "a.png"))
                assertTrue(server.requests.toString(), server.requests.size == 1)
            } finally {
                check(file.setReadable(true, true))
            }
        }
    }

    @Test
    fun cachedReadsAndSweepingAreSerializedWithoutLosingAnAvailableImage() {
        val payload = "P".repeat(256 * 1024)
        LoopbackServer(mapOf("GET /api/documents/1/image?commitSha=$sha&path=a.png" to (200 to payload))).use { server
            ->
            val images = SavedPageImages(Api(server.url, { StoredSession.Readable("TEST_ONLY_SESSION") }) {}, filesDir)
            val expected = payload.toByteArray()
            assertArrayEquals(expected, images.loadImageBytes(1, sha, "a.png"))
            val start = CountDownLatch(1)
            val reading = CompletableFuture.runAsync {
                check(start.await(10, SECONDS))
                repeat(100) { assertArrayEquals(expected, images.loadImageBytes(1, sha, "a.png")) }
            }
            val sweeping = CompletableFuture.runAsync {
                start.countDown()
                repeat(100) { assertTrue(images.deleteImagesExceptSavedPages(emptyList()).isEmpty()) }
            }
            reading.get(30, SECONDS)
            sweeping.get(30, SECONDS)
        }
    }

    /** Runs in WebView interception: a failed cache write must not take the page down. */
    @Test
    fun anImageThatCannotBeCachedIsStillShown() {
        LoopbackServer(mapOf("GET /api/documents/1/image?commitSha=$sha&path=a.png" to (200 to "PNG"))).use { server ->
            val images = SavedPageImages(Api(server.url, { StoredSession.Readable("TEST_ONLY_SESSION") }) {}, filesDir)
            // The version's folder cannot be created: a file has its name, so nothing can be written in it.
            File(filesDir, "images/1").mkdirs()
            File(filesDir, "images/1/commit-$sha").writeText("test-only, not a folder")
            assertArrayEquals("PNG".toByteArray(), images.loadImageBytes(1, sha, "a.png"))
        }
    }
}
