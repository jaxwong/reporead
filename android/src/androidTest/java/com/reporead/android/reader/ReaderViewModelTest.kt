// Owns: ReaderViewModel's admission of server changes to highlights, at its action boundary rather than through taps.

package com.reporead.android.reader

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reporead.android.BuildConfig
import com.reporead.android.auth.StoredSession
import com.reporead.android.database.DocumentEntity
import com.reporead.android.database.LoopbackServer
import com.reporead.android.database.RepoReadDatabase
import com.reporead.android.database.RepositoryEntity
import com.reporead.android.database.SavedPageEntity
import com.reporead.android.database.saveNoteWithoutSearch
import com.reporead.android.network.Api
import com.reporead.android.network.TEST_HIGHLIGHT_MUTATION
import com.reporead.android.network.testHighlight
import com.reporead.android.network.testSha
import com.reporead.android.network.wire
import com.reporead.android.sync.PhoneSync
import com.reporead.android.sync.toEntity
import com.reporead.android.ui.Load
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** A test-only note with one acknowledged highlight; production PhoneSync, Api and Room against a loopback server. */
@RunWith(AndroidJUnit4::class)
class ReaderViewModelTest {
    private lateinit var store: RepoReadDatabase
    private lateinit var directory: File
    /** The owner a back-stack entry would be; clearing it is the entry leaving. */
    private val owners = ViewModelStore()

    @Before
    fun open() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        store = Room.inMemoryDatabaseBuilder(context, RepoReadDatabase::class.java).build()
        directory = File(context.cacheDir, "test-only-reader-model-${UUID.randomUUID()}").also { check(it.mkdirs()) }
        val dao = store.dao()
        dao.replaceRepositories(listOf(RepositoryEntity(7, "test-only/notes", 1, testSha)))
        dao.replaceDocuments(7, listOf(DocumentEntity(1, 7, "n.md", "Test-only note", testSha)))
        dao.saveNoteWithoutSearch(
            SavedPageEntity(
                1,
                testSha,
                "n.md",
                "Test-only note",
                "<main id=note></main>",
                0,
                renderFormat = BuildConfig.RENDER_FORMAT,
                snapshotCommitSha = testSha,
            )
        )
        dao.saveServerAnnotation(testHighlight().toEntity())
    }

    @After
    fun close() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { owners.clear() }
        store.close()
        check(directory.deleteRecursively())
    }

    @Test
    fun aBookmarkOnADisconnectedNoteReportsThatItWasNotSaved() = runBlocking {
        LoopbackServer(emptyMap()).use { server ->
            val dao = store.dao()
            val sync =
                PhoneSync(Api(server.url, { StoredSession.Readable("TEST_ONLY_SESSION") }) {}, store, directory) {}
            val model =
                withContext(Dispatchers.Main) {
                    val factory = viewModelFactory {
                        initializer { ReaderViewModel(sync, dao, SavedStateHandle(), 1, "test-only-entry") }
                    }
                    ViewModelProvider.create(owners, factory)[ReaderViewModel::class]
                }
            // Finish the model's local page load before forgetting the repository.
            withTimeout(10_000) { while (model.state.value.page !is Load.Ready) delay(20) }
            dao.forgetRepository(7, listOf(1))
            for (bookmarked in listOf(true, false)) {
                withContext(Dispatchers.Main) {
                    model.showMessage(null)
                    model.setBookmark(bookmarked)
                }
                withTimeout(10_000) { while (model.state.value.message == null) delay(20) }
                assertEquals(
                    "Bookmark not saved: this note is no longer saved on this phone.",
                    model.state.value.message,
                )
            }
            assertTrue(server.requests.isEmpty())
        }
    }

    /** A failed edit reopens its editor with its own reason; the screen-wide notice is not where that reason goes. */
    @Test
    fun aFailedEditReturnsToItsEditorWithItsReason() = runBlocking {
        val unreachable = Api("http://127.0.0.1:1", { StoredSession.Readable("TEST_ONLY_SESSION") }) {}
        val sync = PhoneSync(unreachable, store, directory) {}
        val dao = store.dao()
        val highlight = checkNotNull(dao.annotation(TEST_HIGHLIGHT_MUTATION))
        val model =
            withContext(Dispatchers.Main) {
                val factory = viewModelFactory {
                    initializer { ReaderViewModel(sync, dao, SavedStateHandle(), 1, "test-only-entry") }
                }
                ViewModelProvider.create(owners, factory)[ReaderViewModel::class].also {
                    it.editNote(highlight, "Test-only edit", highlight.version)
                }
            }
        withTimeout(10_000) { while (model.state.value.isChanging || model.state.value.overlay == null) delay(20) }
        val editor = model.state.value.overlay as ReaderOverlay.EditNote
        assertEquals("Test-only edit", editor.initial)
        assertTrue(editor.failure.toString(), editor.failure.orEmpty().startsWith("Edit not saved. "))
        assertNull(model.state.value.message)
    }

    /** T14: a second change while one runs starts nothing; once it has finished, the next one runs. */
    @Test
    fun oneServerChangeToAHighlightRunsAtATime() = runBlocking {
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        val edited = testHighlight().copy(note = "Test-only first", version = 2)
        LoopbackServer(
                mapOf(
                    "PATCH /api/annotations/9" to (200 to wire(edited)),
                    "DELETE /api/annotations/9?expectedVersion=2" to (204 to ""),
                )
            ) { key ->
                if (key == "PATCH /api/annotations/9") {
                    arrived.countDown()
                    check(release.await(10, SECONDS)) { "Test never released the edit" }
                }
            }
            .use { server ->
                val sync =
                    PhoneSync(Api(server.url, { StoredSession.Readable("TEST_ONLY_SESSION") }) {}, store, directory) {}
                val dao = store.dao()
                val row = checkNotNull(dao.annotation(TEST_HIGHLIGHT_MUTATION))
                val model =
                    withContext(Dispatchers.Main) {
                        val factory = viewModelFactory {
                            initializer { ReaderViewModel(sync, dao, SavedStateHandle(), 1, "test-only-entry") }
                        }
                        ViewModelProvider.create(owners, factory)[ReaderViewModel::class].also {
                            it.editNote(row, "Test-only first", row.version)
                            it.editNote(row, "Test-only second", row.version)
                            it.delete(row)
                        }
                    }
                try {
                    assertTrue(arrived.await(10, SECONDS))
                } finally {
                    release.countDown()
                }
                withTimeout(10_000) { while (model.state.value.isChanging) delay(20) }
                assertEquals(listOf("PATCH /api/annotations/9"), server.requests)
                assertEquals("Test-only first", dao.annotation(TEST_HIGHLIGHT_MUTATION)?.note)
                // The second run: a change asked for after the first finished runs.
                val now = checkNotNull(dao.annotation(TEST_HIGHLIGHT_MUTATION))
                withContext(Dispatchers.Main) { model.delete(now) }
                withTimeout(10_000) { while (dao.annotation(TEST_HIGHLIGHT_MUTATION) != null) delay(20) }
                assertEquals(
                    listOf("PATCH /api/annotations/9", "DELETE /api/annotations/9?expectedVersion=2"),
                    server.requests,
                )
            }
    }
}
