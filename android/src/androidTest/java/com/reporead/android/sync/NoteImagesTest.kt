package com.reporead.android.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reporead.android.core.network.Api
import com.reporead.android.data.LoopbackServer
import java.io.File
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Test-only loopback server and a scratch folder, never the backend or the app's own files. */
@RunWith(AndroidJUnit4::class)
class NoteImagesTest {
    private val sha = "a".repeat(40)
    private val filesDir =
        File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "test-only-note-images")

    @Before
    @After
    fun clean() {
        filesDir.deleteRecursively()
    }

    @Test
    fun aFetchedImageIsCachedAndServedFromTheCacheAfterwards() {
        LoopbackServer(mapOf("GET /api/documents/1/image?path=a.png" to (200 to "PNG"))).use { server ->
            val images = NoteImages(Api(server.url, { "TEST_ONLY_SESSION" }) {}, filesDir)
            assertArrayEquals("PNG".toByteArray(), images.image(1, sha, "a.png"))
            assertArrayEquals("PNG".toByteArray(), images.image(1, sha, "a.png"))
            assertTrue(server.requests.toString(), server.requests.size == 1)
            assertTrue(File(filesDir, "images/1/$sha").listFiles()!!.none { it.name.endsWith(".partial") })
        }
    }

    /** Runs inside the WebView's request interception, so a failed write must not take the page (or the app) down. */
    @Test
    fun anImageThatCannotBeCachedIsStillShown() {
        LoopbackServer(mapOf("GET /api/documents/1/image?path=a.png" to (200 to "PNG"))).use { server ->
            val images = NoteImages(Api(server.url, { "TEST_ONLY_SESSION" }) {}, filesDir)
            // The version's folder cannot be created: a file has its name, so nothing can be written in it.
            File(filesDir, "images/1").mkdirs()
            File(filesDir, "images/1/$sha").writeText("test-only, not a folder")
            assertArrayEquals("PNG".toByteArray(), images.image(1, sha, "a.png"))
        }
    }
}
