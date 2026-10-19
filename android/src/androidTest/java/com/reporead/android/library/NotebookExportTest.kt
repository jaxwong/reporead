// Owns: T11 for the notebook export: shared once by the Notebook shown, never by one that was left.

package com.reporead.android.library

import android.content.Intent
import android.content.IntentFilter
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reporead.android.Entry
import com.reporead.android.Screen
import com.reporead.android.TestEntries
import com.reporead.android.TestHostActivity
import com.reporead.android.auth.StoredSession
import com.reporead.android.awaitText
import com.reporead.android.awaitTextStarting
import com.reporead.android.click
import com.reporead.android.core.network.Api
import com.reporead.android.core.network.testChanges
import com.reporead.android.core.network.testSha
import com.reporead.android.core.network.wire
import com.reporead.android.data.AnnotationRow
import com.reporead.android.data.AnnotationState
import com.reporead.android.data.DocumentRow
import com.reporead.android.data.LocalStore
import com.reporead.android.data.LoopbackServer
import com.reporead.android.sync.Sync
import com.reporead.android.sync.exportDirectory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real NotebookScreen in decorated entries, with production Sync over an in-memory database. Sharing needs the
 * export in the app's own files/exports (the FileProvider's only root), so Sync is given the Dev app's files directory;
 * the test holds the lock with a summary request, which writes and sweeps nothing, never with a library load, whose
 * image sweep would reach that directory's saved images. It deletes the exports it made. The share sheet is
 * intercepted.
 */
@RunWith(AndroidJUnit4::class)
class NotebookExportTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var store: LocalStore
    private val entries = TestEntries(Screen.Repositories)
    private val exports = exportDirectory(context.filesDir)
    private val exportsBefore = exports.list().orEmpty().toSet()
    private val changes = "GET /api/documents/1/changes?since=${"c".repeat(40)}&to=$testSha"

    @Before
    fun open() = runBlocking {
        store = Room.inMemoryDatabaseBuilder(context, LocalStore::class.java).build()
        val dao = store.library()
        dao.replaceDocuments(7, listOf(DocumentRow(1, 7, "a.md", "Test-only note", testSha)))
        dao.saveAnnotation(
            AnnotationRow(
                "test-only-notebook",
                null,
                1,
                testSha,
                "b0",
                0,
                4,
                "Test",
                null,
                1,
                0,
                state = AnnotationState.UNSENT,
            )
        )
    }

    @After
    fun close() {
        instrumentation.waitForIdleSync()
        TestHostActivity.content = null
        store.close()
        exports
            .listFiles()
            .orEmpty()
            .filter { it.name !in exportsBefore }
            .forEach { check(it.delete()) { "Cannot delete $it" } }
    }

    @Test
    fun anExportIsSharedOnceByTheNotebookShownAndNeverByOneLeft() = runBlocking {
        val holds = List(3) { CountDownLatch(1) to CountDownLatch(1) }
        val shares = instrumentation.addMonitor(IntentFilter(Intent.ACTION_CHOOSER), null, true)
        val answer = 200 to wire(testChanges().copy(sinceBlobSha = "c".repeat(40), toBlobSha = testSha))
        LoopbackServer(mapOf(changes to answer)) {
                val (arrived, release) = holds.first { it.first.count == 1L }
                arrived.countDown()
                check(release.await(10, SECONDS)) { "Test never released the held summary" }
            }
            .use { server ->
                val sync =
                    Sync(
                        Api(server.url, { StoredSession.Readable("TEST_ONLY_SESSION") }) {},
                        store,
                        context.filesDir,
                    ) {}
                TestHostActivity.content = entries.content { entry ->
                    MaterialTheme {
                        if (entry.screen == Screen.Notebook)
                            NotebookScreen(
                                store.library(),
                                sync,
                                entry.id,
                                push = { entries.stack += Entry("test-only-cover", it) },
                                onBack = { entries.stack.remove(entry) },
                            )
                        else Text("test-only-entry:${entry.id}")
                    }
                }
                ActivityScenario.launch(TestHostActivity::class.java).use { scenario ->
                    scenario.onActivity { entries.stack += Entry("test-only-notebook", Screen.Notebook) }
                    awaitTextStarting("Highlights and cards saved on this phone.")

                    /** Exports while a summary request holds the lock, doing [meanwhile] before letting it go. */
                    suspend fun exportWhileHeld(hold: Int, meanwhile: () -> Unit) {
                        val (arrived, release) = holds[hold]
                        val held = async(Dispatchers.IO) { sync.changes(1, "c".repeat(40), testSha) }
                        try {
                            assertTrue(arrived.await(10, SECONDS))
                            click("Export Markdown")
                            meanwhile()
                        } finally {
                            release.countDown()
                        }
                        held.await()
                    }
                    try {
                        exportWhileHeld(0) {
                            scenario.recreate()
                            awaitTextStarting("Highlights and cards saved on this phone.")
                        }
                        awaitTextStarting("Sharing 1 entries as Markdown.")
                        assertEquals(1, shares.hits)

                        exportWhileHeld(1) {
                            click("Open passage")
                            awaitText("test-only-entry:test-only-cover")
                        }
                        // Ready while covered: nothing is shared until the Notebook is shown again.
                        delay(1_000)
                        assertEquals(1, shares.hits)
                        scenario.onActivity { entries.stack.removeAt(entries.stack.lastIndex) }
                        awaitTextStarting("Highlights and cards saved on this phone.")
                        withTimeout(10_000) { while (shares.hits < 2) delay(50) }

                        exportWhileHeld(2) {
                            scenario.onActivity { entries.stack.removeAt(entries.stack.lastIndex) }
                            awaitText("test-only-entry:test-only-root")
                        }
                        delay(1_000)
                        assertEquals(2, shares.hits)
                        assertEquals(List(3) { changes }, server.requests)
                    } finally {
                        instrumentation.removeMonitor(shares)
                    }
                }
            }
    }
}
