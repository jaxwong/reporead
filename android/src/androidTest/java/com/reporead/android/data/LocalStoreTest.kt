package com.reporead.android.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Cache and pending-change rules against a real in-memory Room database on the device. Test-only data. */
@RunWith(AndroidJUnit4::class)
class LocalStoreTest {
    private lateinit var store: LocalStore
    private lateinit var dao: LibraryDao

    @Before fun open() {
        store = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, LocalStore::class.java).build()
        dao = store.library()
    }

    @After fun close() = store.close()

    private fun reading(at: Long, progress: Int, pending: Boolean) =
        ReadingRow(1, "title", "a.md", "a".repeat(40), progress, "{\"headingPath\":[],\"textPrefix\":null,\"blockIndex\":0}", at, pending)

    private fun bookmark(id: Long, bookmarked: Boolean, pending: Boolean, at: Long) =
        BookmarkRow(id, "title", "a.md", "a".repeat(40), bookmarked, pending, at)

    @Test fun pendingLocalProgressSurvivesAnOlderServerStateButYieldsToANewerOne() = runBlocking {
        dao.saveReading(reading(at = 200, progress = 60, pending = true))
        dao.mergeRemoteReading(reading(at = 100, progress = 10, pending = false))
        assertEquals(60, dao.reading(1)!!.progressPercent)
        assertTrue(dao.reading(1)!!.pending)
        dao.mergeRemoteReading(reading(at = 300, progress = 90, pending = false))
        assertEquals(90, dao.reading(1)!!.progressPercent)
        assertEquals(emptyList<ReadingRow>(), dao.pendingReading())
    }

    @Test fun acknowledgingAnOldSaveLeavesANewerLocalSavePending() = runBlocking {
        dao.saveReading(reading(at = 200, progress = 60, pending = true))
        dao.acknowledgeReading(1, lastReadAt = 100)
        assertTrue(dao.reading(1)!!.pending)
        dao.acknowledgeReading(1, lastReadAt = 200)
        assertEquals(emptyList<ReadingRow>(), dao.pendingReading())
    }

    @Test fun acknowledgedProgressIsReplacedByTheServer() = runBlocking {
        dao.saveReading(reading(at = 200, progress = 60, pending = false))
        dao.mergeRemoteReading(reading(at = 200, progress = 70, pending = false))
        assertEquals(70, dao.reading(1)!!.progressPercent)
    }

    @Test fun serverBookmarkListReplacesAcknowledgedRowsButKeepsPendingToggles() = runBlocking {
        dao.saveBookmark(bookmark(1, bookmarked = true, pending = false, at = 1))
        dao.saveBookmark(bookmark(2, bookmarked = false, pending = true, at = 2))
        dao.saveBookmark(bookmark(3, bookmarked = true, pending = true, at = 3))
        dao.replaceRemoteBookmarks(listOf(bookmark(2, bookmarked = true, pending = false, at = 0), bookmark(4, bookmarked = true, pending = false, at = 4)))
        assertNull(dao.bookmark(1).first())
        assertEquals(false, dao.bookmark(2).first()!!.bookmarked)
        assertTrue(dao.bookmark(3).first()!!.pending)
        assertEquals(true, dao.bookmark(4).first()!!.bookmarked)
        assertEquals(listOf(4L, 3L), dao.bookmarks().first().map { it.documentId })
    }

    @Test fun bookmarkAcknowledgementsMatchTheToggleThatWasSent() = runBlocking {
        dao.saveBookmark(bookmark(1, bookmarked = false, pending = true, at = 5))
        dao.acknowledgeBookmarkCleared(1, changedAt = 4)
        assertEquals(1, dao.pendingBookmarks().size)
        dao.acknowledgeBookmarkCleared(1, changedAt = 5)
        assertNull(dao.bookmark(1).first())
        dao.saveBookmark(bookmark(2, bookmarked = true, pending = true, at = 6))
        dao.acknowledgeBookmarkSet(2, changedAt = 6)
        assertEquals(false, dao.bookmark(2).first()!!.pending)
    }

    @Test fun replacingDocumentsAndRepositoriesUsesOnlyTheCompleteNewList() = runBlocking {
        dao.replaceDocuments(7, listOf(DocumentRow(1, 7, "a.md", "a", "a".repeat(40)), DocumentRow(2, 7, "b.md", "b", "b".repeat(40))))
        dao.replaceDocuments(8, listOf(DocumentRow(3, 8, "c.md", "c", "c".repeat(40))))
        dao.replaceDocuments(7, listOf(DocumentRow(2, 7, "b.md", "b", "c".repeat(40))))
        assertEquals(listOf(2L), dao.documents(7).first().map { it.id })
        assertEquals(listOf(3L), dao.documents(8).first().map { it.id })
        dao.replaceRepositories(listOf(RepositoryRow(7, "o/r", 1, null)))
        dao.replaceRepositories(emptyList())
        assertEquals(emptyList<RepositoryRow>(), dao.repositories().first())
    }

    @Test fun practiceSnapshotCountsUnsavedNotesAndDropsUnlistedCopiesOnRepeatRuns() = runBlocking {
        assertEquals(emptyList<PracticePage>(), dao.practicePages().first())
        dao.replaceDocuments(7, listOf(DocumentRow(1, 7, "core/a.md", "a", "a".repeat(40)),
            DocumentRow(2, 7, "core/b.md", "b", "b".repeat(40))))
        dao.saveNote(NoteRow(1, "a".repeat(40), "c".repeat(40), "old/a.md", "Old title", "<main/>", 0))
        dao.saveNote(NoteRow(9, "a".repeat(40), "c".repeat(40), "removed.md", "removed", "<main/>", 0))
        val partial = dao.practicePages().first()
        assertEquals(listOf(1L, 2L), partial.map { it.documentId })
        assertEquals("core/a.md", partial.first().path)
        assertEquals("a", partial.first().title)
        assertEquals(1, partial.count { it.html != null })
        dao.saveNote(NoteRow(2, "b".repeat(40), "c".repeat(40), "core/b.md", "b", "<main>complete</main>", 0))
        assertEquals(2, dao.practicePages().first().count { it.html != null })
        dao.replaceDocuments(7, listOf(DocumentRow(2, 7, "core/b.md", "b", "b".repeat(40))))
        assertEquals(listOf(2L), dao.practicePages().first().map { it.documentId })
        dao.forgetRepository(7, listOf(1L, 2L))
        assertEquals(emptyList<PracticePage>(), dao.practicePages().first())
    }

    private fun readAt(documentId: Long, blobSha: String, at: Long) =
        ReadingRow(documentId, "title", "n.md", blobSha, 50, "{\"headingPath\":[],\"textPrefix\":null,\"blockIndex\":0}", at, pending = false)

    @Test fun libraryListsNotesUpdatedSinceReadAndOtherRecentChangesOnce() = runBlocking {
        val a = "a".repeat(40)
        val b = "b".repeat(40)
        dao.replaceDocuments(7, listOf(
            DocumentRow(1, 7, "read-old.md", "read-old", b, changedAt = 300),
            DocumentRow(2, 7, "read-current.md", "read-current", b, changedAt = 400),
            DocumentRow(3, 7, "never-read.md", "never-read", b, changedAt = 500),
            DocumentRow(4, 7, "unseen-change.md", "unseen-change", b, changedAt = null),
            DocumentRow(5, 7, "first-refresh.md", "first-refresh", b, changedAt = null),
            DocumentRow(6, 7, "older-change.md", "older-change", b, changedAt = 100)))
        dao.saveReading(readAt(1, a, at = 10))
        dao.saveReading(readAt(2, b, at = 20))
        dao.saveReading(readAt(4, a, at = 30))
        dao.saveReading(readAt(9, a, at = 40)) // a note whose document is not in any saved list

        // Read in another version: most recently seen change first; a change RepoRead never timed comes last.
        assertEquals(listOf(1L, 4L), dao.updatedSinceRead().first().map { it.documentId })
        val changed = dao.recentlyChanged(5).first()
        assertEquals(listOf(3L, 2L, 6L), changed.map { it.documentId })
        assertNull(changed.first().lastReadBlobSha)
        assertEquals(b, changed[1].lastReadBlobSha)
        assertEquals(listOf(3L, 2L), dao.recentlyChanged(2).first().map { it.documentId })

        // Reading the current version moves the note out of Updated since you read.
        dao.saveReading(readAt(1, b, at = 50))
        assertEquals(listOf(4L), dao.updatedSinceRead().first().map { it.documentId })
        assertEquals(listOf(3L, 2L, 1L, 6L), dao.recentlyChanged(5).first().map { it.documentId })
    }

    @Test fun searchMatchesSavedTitlesTextAndHighlightsWithLiteralWildcards() = runBlocking {
        dao.replaceDocuments(7, listOf(DocumentRow(1, 7, "java/Spring.md", "Spring", "a".repeat(40)),
            DocumentRow(2, 7, "db/Postgres.md", "Postgres", "b".repeat(40)), DocumentRow(3, 7, "misc/100_percent.md", "100_percent", "c".repeat(40))))
        dao.saveNote(NoteRow(2, "b".repeat(40), "c".repeat(40), "db/Postgres.md", "Postgres", "<html/>", 0, "MVCC\nUse 100% of the TRANSACTION log"))
        dao.saveNote(NoteRow(9, "d".repeat(40), "c".repeat(40), "gone/Old.md", "Old", "<html/>", 0, null))
        dao.saveAnnotation(AnnotationRow("m1", 5, 1, "a".repeat(40), "b1", 0, 4, "Propagation", "about transactions", 1, 0, pending = false, rejection = null))

        // Title in a saved list, saved text (ASCII case-insensitive), and a highlight's note all match "transaction".
        assertEquals(listOf(2L), dao.searchNotes("%transaction%", 50).filter { it.searchText != null }.map { it.documentId })
        assertEquals(listOf(1L), dao.searchHighlights("%transaction%", 50).map { it.documentId })
        assertEquals(listOf(1L), dao.searchNotes("%spring%", 50).map { it.documentId })
        // A saved copy of a note no longer in any list still matches by title; one saved before search has no text.
        assertEquals(listOf(9L), dao.searchNotes("%old%", 50).map { it.documentId })
        // Escaped wildcards are literal: "0\_p" matches "100_percent" only, "0\%" only the text with "100%".
        assertEquals(listOf(3L), dao.searchNotes("%0\\_p%", 50).map { it.documentId })
        assertEquals(listOf(2L), dao.searchNotes("%0\\%%", 50).map { it.documentId })
        assertEquals(emptyList<NoteMatch>(), dao.searchNotes("%nothing like this%", 50))
        assertEquals(2, dao.searchNotes("%s%", 2).size)
    }

    @Test fun forgettingADisconnectedRepositoryRemovesOnlyItsRowsIncludingPendingOnes() = runBlocking {
        dao.replaceRepositories(listOf(RepositoryRow(7, "o/notes", 2, "c".repeat(40)), RepositoryRow(8, "o/other", 1, "c".repeat(40))))
        dao.replaceDocuments(7, listOf(DocumentRow(1, 7, "a.md", "a", "a".repeat(40))))
        dao.replaceDocuments(8, listOf(DocumentRow(3, 8, "c.md", "c", "c".repeat(40))))
        for (id in listOf(1L, 2L, 3L)) {
            dao.saveNote(NoteRow(id, "a".repeat(40), "c".repeat(40), "n.md", "n", "<html/>", 0, "text"))
            dao.saveReading(readAt(id, "a".repeat(40), at = id).copy(pending = id == 2L))
            dao.saveBookmark(bookmark(id, bookmarked = true, pending = false, at = id))
            dao.saveAnnotation(annotation("m$id", null, null, pending = true).copy(documentId = id))
        }
        // Document 2 was deleted upstream: it is in the server's list of the connection's documents, not in the saved list.
        dao.forgetRepository(7, listOf(1L, 2L))
        assertEquals(listOf(8L), dao.repositories().first().map { it.id })
        assertEquals(emptyList<DocumentRow>(), dao.documents(7).first())
        assertEquals(listOf(3L), dao.documents(8).first().map { it.id })
        assertNull(dao.note(1)); assertNull(dao.note(2)); assertEquals("text", dao.note(3)!!.searchText)
        assertNull(dao.reading(2)); assertEquals(emptyList<ReadingRow>(), dao.pendingReading())
        assertEquals(listOf(3L), dao.bookmarks().first().map { it.documentId })
        assertEquals(listOf("m3"), dao.pendingAnnotations().map { it.mutationId })
    }

    private fun annotation(mutationId: String, serverId: Long?, note: String?, pending: Boolean, rejection: String? = null) =
        AnnotationRow(mutationId, serverId, 1, "a".repeat(40), "b2", 0, 4, "text", note, 1, 0, pending, rejection)

    @Test fun serverAnnotationListAcknowledgesAMatchingPendingCreationAndKeepsOthers() = runBlocking {
        dao.saveAnnotation(annotation("lost-ack", null, "mine", pending = true))
        dao.saveAnnotation(annotation("offline", null, "later", pending = true))
        dao.saveAnnotation(annotation("deleted-elsewhere", 9, null, pending = false))
        dao.replaceRemoteAnnotations(1, listOf(annotation("lost-ack", 7, "mine", pending = false), annotation("other-device", 8, null, pending = false)))
        val rows = dao.annotations(1).first().associateBy { it.mutationId }
        assertEquals(setOf("lost-ack", "offline", "other-device"), rows.keys)
        assertEquals(7L, rows.getValue("lost-ack").serverId)
        assertEquals(false, rows.getValue("lost-ack").pending)
        assertTrue(rows.getValue("offline").pending)
    }

    @Test fun refusedCreationsAreKeptVisibleButNotRetried() = runBlocking {
        dao.saveAnnotation(annotation("refused", null, null, pending = true))
        dao.rejectAnnotation("refused", "The selected text does not match that version of the note.")
        assertEquals(emptyList<AnnotationRow>(), dao.pendingAnnotations())
        assertEquals("The selected text does not match that version of the note.", dao.annotation("refused")!!.rejection)
    }

    /** Note edits use PATCH; Android's HttpURLConnection must accept it before any network I/O. */
    @Test fun platformHttpClientAcceptsPatch() {
        val connection = java.net.URL("http://127.0.0.1:9/").openConnection() as java.net.HttpURLConnection
        connection.requestMethod = "PATCH"
        assertEquals("PATCH", connection.requestMethod)
    }
}
