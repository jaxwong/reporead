package com.reporead.android.database

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reporead.android.OpenAt
import com.reporead.android.search.SEARCH_LIMIT
import com.reporead.android.search.SearchResult
import com.reporead.android.search.search
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Cache and pending-change rules against a real in-memory Room database on the device. Test-only data. */
@RunWith(AndroidJUnit4::class)
class RepoReadDatabaseTest {
    private lateinit var store: RepoReadDatabase
    private lateinit var dao: RepoReadDao

    @Before
    fun open() {
        store =
            Room.inMemoryDatabaseBuilder(
                    InstrumentationRegistry.getInstrumentation().targetContext,
                    RepoReadDatabase::class.java,
                )
                .build()
        dao = store.dao()
    }

    @After fun close() = store.close()

    private fun reading(at: Long, progress: Int, pending: Boolean) =
        ReadingStateEntity(
            1,
            "a".repeat(40),
            progress,
            "{\"headingPath\":[],\"textPrefix\":null,\"blockIndex\":0}",
            at,
            pending,
        )

    private fun bookmark(id: Long, bookmarked: Boolean, pending: Boolean, at: Long) =
        BookmarkEntity(id, bookmarked, pending, at)

    @Test
    fun pendingLocalProgressSurvivesAnOlderServerStateButYieldsToANewerOne() = runBlocking {
        dao.saveReading(reading(at = 200, progress = 60, pending = true))
        dao.mergeRemoteReading(reading(at = 100, progress = 10, pending = false))
        assertEquals(60, dao.reading(1)!!.progressPercent)
        assertTrue(dao.reading(1)!!.pending)
        dao.mergeRemoteReading(reading(at = 300, progress = 90, pending = false))
        assertEquals(90, dao.reading(1)!!.progressPercent)
        assertEquals(emptyList<ReadingStateEntity>(), dao.pendingReading())
    }

    @Test
    fun acknowledgingAnOldSaveLeavesANewerLocalSavePending() = runBlocking {
        dao.saveReading(reading(at = 200, progress = 60, pending = true))
        dao.acknowledgeReading(1, lastReadAt = 100)
        assertTrue(dao.reading(1)!!.pending)
        dao.acknowledgeReading(1, lastReadAt = 200)
        assertEquals(emptyList<ReadingStateEntity>(), dao.pendingReading())
    }

    @Test
    fun acknowledgedProgressIsReplacedByTheServer() = runBlocking {
        dao.saveReading(reading(at = 200, progress = 60, pending = false))
        dao.mergeRemoteReading(reading(at = 200, progress = 70, pending = false))
        assertEquals(70, dao.reading(1)!!.progressPercent)
    }

    @Test
    fun serverBookmarkListReplacesAcknowledgedRowsButKeepsPendingToggles() = runBlocking {
        dao.replaceDocuments(7, (1L..4L).map { DocumentEntity(it, 7, "$it.md", "$it", "a".repeat(40)) })
        dao.saveBookmark(bookmark(1, bookmarked = true, pending = false, at = 1))
        dao.saveBookmark(bookmark(2, bookmarked = false, pending = true, at = 2))
        dao.saveBookmark(bookmark(3, bookmarked = true, pending = true, at = 3))
        dao.replaceRemoteBookmarks(
            listOf(
                bookmark(2, bookmarked = true, pending = false, at = 0),
                bookmark(4, bookmarked = true, pending = false, at = 4),
            )
        )
        assertNull(dao.bookmark(1).first())
        assertEquals(false, dao.bookmark(2).first()!!.bookmarked)
        assertTrue(dao.bookmark(3).first()!!.pending)
        assertEquals(true, dao.bookmark(4).first()!!.bookmarked)
        assertEquals(listOf(4L, 3L), dao.bookmarks().first().map { it.documentId })
    }

    @Test
    fun bookmarkAcknowledgementsMatchTheToggleThatWasSent() = runBlocking {
        dao.saveBookmark(bookmark(1, bookmarked = false, pending = true, at = 5))
        dao.acknowledgeBookmarkCleared(1, changedAt = 4)
        assertEquals(1, dao.pendingBookmarks().size)
        dao.acknowledgeBookmarkCleared(1, changedAt = 5)
        assertNull(dao.bookmark(1).first())
        dao.saveBookmark(bookmark(2, bookmarked = true, pending = true, at = 6))
        dao.acknowledgeBookmarkSet(2, changedAt = 6)
        assertEquals(false, dao.bookmark(2).first()!!.pending)
    }

    @Test
    fun replacingDocumentsAndRepositoriesUsesOnlyTheCompleteNewList() = runBlocking {
        dao.replaceDocuments(
            7,
            listOf(
                DocumentEntity(1, 7, "a.md", "a", "a".repeat(40)),
                DocumentEntity(2, 7, "b.md", "b", "b".repeat(40)),
            ),
        )
        dao.replaceDocuments(8, listOf(DocumentEntity(3, 8, "c.md", "c", "c".repeat(40))))
        dao.replaceDocuments(7, listOf(DocumentEntity(2, 7, "b.md", "b", "c".repeat(40))))
        assertEquals(listOf(2L), dao.documents(7).first().map { it.id })
        assertEquals(listOf(3L), dao.documents(8).first().map { it.id })
        dao.replaceRepositories(listOf(RepositoryEntity(7, "o/r", 1, null)))
        dao.replaceRepositories(emptyList())
        assertEquals(emptyList<RepositoryEntity>(), dao.repositories().first())
    }

    @Test
    fun bankPagesAreOnlyInterviewFoldersCountUnsavedBanksAndDropUnlistedCopies() = runBlocking {
        assertEquals(emptyList<PracticePage>(), dao.bankPages().first())
        dao.replaceDocuments(
            7,
            listOf(
                DocumentEntity(1, 7, "core/interview/a.md", "a", "a".repeat(40)),
                DocumentEntity(2, 7, "interview/b.md", "b", "b".repeat(40)),
                // Not banks: their pages are never loaded for practice.
                DocumentEntity(3, 7, "core/a.md", "core", "a".repeat(40)),
                DocumentEntity(4, 7, "core/interview.md", "x", "a".repeat(40)),
                DocumentEntity(5, 7, "core/interviews/a.md", "y", "a".repeat(40)),
            ),
        )
        dao.saveNoteWithoutSearch(SavedPageEntity(3, "a".repeat(40), "core/a.md", "core", "<main/>", 0))
        dao.saveNoteWithoutSearch(SavedPageEntity(1, "a".repeat(40), "old/a.md", "Old title", "<main/>", 0))
        dao.saveNoteWithoutSearch(SavedPageEntity(9, "a".repeat(40), "removed.md", "removed", "<main/>", 0))
        val partial = dao.bankPages().first()
        assertEquals(listOf(1L, 2L), partial.map { it.documentId })
        assertEquals("core/interview/a.md", partial.first().path)
        assertEquals("a", partial.first().title)
        assertEquals("a".repeat(40), partial.first().blobSha)
        assertNull(partial.last().blobSha)
        assertEquals(1, partial.count { it.html != null })
        // A copy older than the listed version reads as unsaved: grading its questions would be refused.
        dao.saveNoteWithoutSearch(SavedPageEntity(2, "c".repeat(40), "interview/b.md", "b", "<main>older</main>", 0))
        assertEquals(1, dao.bankPages().first().count { it.html != null })
        assertNull(dao.bankPages().first().last().blobSha)
        dao.saveNoteWithoutSearch(SavedPageEntity(2, "b".repeat(40), "interview/b.md", "b", "<main>complete</main>", 0))
        assertEquals(2, dao.bankPages().first().count { it.html != null })
        dao.replaceDocuments(7, listOf(DocumentEntity(2, 7, "interview/b.md", "b", "b".repeat(40))))
        assertEquals(listOf(2L), dao.bankPages().first().map { it.documentId })
        dao.forgetRepository(7, listOf(1L, 2L, 3L))
        assertEquals(emptyList<PracticePage>(), dao.bankPages().first())
    }

    @Test
    fun aSavedPagesNoteLinksAreSavedReplacedAndDeletedWithIt() = runBlocking {
        val sha = "a".repeat(40)
        dao.replaceDocuments(
            7,
            listOf(
                DocumentEntity(1, 7, "a.md", "a", sha),
                DocumentEntity(2, 7, "b.md", "b", sha),
                DocumentEntity(3, 7, "c.md", "c", sha),
            ),
        )
        dao.replaceDocuments(8, listOf(DocumentEntity(4, 8, "d.md", "d", sha)))
        val linking =
            """<main><a href="/note-link?target=b">b</a><a href="/note-link?path=c.md&amp;heading=x">c</a></main>"""
        dao.saveNoteWithoutSearch(SavedPageEntity(1, sha, "a.md", "a", linking, 0))
        dao.saveNoteWithoutSearch(SavedPageEntity(2, sha, "b.md", "b", "<main>no links</main>", 0))
        dao.saveNoteWithoutSearch(SavedPageEntity(4, sha, "d.md", "d", linking, 0))
        // Note 3 is listed but not saved; note 4 links but belongs to another repository.
        assertEquals(
            listOf(NoteLinkEntity(1, 0, "target=b"), NoteLinkEntity(1, 1, "path=c.md&heading=x")),
            dao.noteLinks(7),
        )
        dao.saveNoteWithoutSearch(
            SavedPageEntity(1, sha, "a.md", "a", """<main><a href="/note-link?target=c">c</a></main>""", 0)
        )
        assertEquals(listOf(NoteLinkEntity(1, 0, "target=c")), dao.noteLinks(7))
        dao.deleteSavedPages(listOf(1L))
        assertEquals(emptyList<NoteLinkEntity>(), dao.noteLinks(7))
        assertEquals(
            listOf(NoteLinkEntity(4, 0, "target=b"), NoteLinkEntity(4, 1, "path=c.md&heading=x")),
            dao.noteLinks(8),
        )
    }

    private fun readAt(documentId: Long, blobSha: String, at: Long) =
        ReadingStateEntity(
            documentId,
            blobSha,
            50,
            "{\"headingPath\":[],\"textPrefix\":null,\"blockIndex\":0}",
            at,
            pending = false,
        )

    @Test
    fun libraryListsNotesUpdatedSinceReadAndOtherRecentChangesOnce() = runBlocking {
        val a = "a".repeat(40)
        val b = "b".repeat(40)
        dao.replaceDocuments(
            7,
            listOf(
                DocumentEntity(1, 7, "read-old.md", "read-old", b, changedAt = 300),
                DocumentEntity(2, 7, "read-current.md", "read-current", b, changedAt = 400),
                DocumentEntity(3, 7, "never-read.md", "never-read", b, changedAt = 500),
                DocumentEntity(4, 7, "unseen-change.md", "unseen-change", b, changedAt = null),
                DocumentEntity(5, 7, "first-refresh.md", "first-refresh", b, changedAt = null),
                DocumentEntity(6, 7, "older-change.md", "older-change", b, changedAt = 100),
            ),
        )
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

    @Test
    fun searchMatchesSavedTitlesTextAndHighlightsWithLiteralWildcards() = runBlocking {
        dao.replaceDocuments(
            7,
            listOf(
                DocumentEntity(1, 7, "java/Spring.md", "Spring", "a".repeat(40)),
                DocumentEntity(2, 7, "db/Postgres.md", "Postgres", "b".repeat(40)),
                DocumentEntity(3, 7, "misc/100_percent.md", "100_percent", "c".repeat(40)),
                DocumentEntity(9, 7, "gone/Old.md", "Old", "d".repeat(40), deleted = true),
                DocumentEntity(10, 7, "gone/Older.md", "Older", "e".repeat(40), deleted = true),
            ),
        )
        dao.savePage(
            SavedPageEntity(
                2,
                "b".repeat(40),
                "db/Postgres.md",
                "Postgres",
                "<html/>",
                0,
            ),
            listOf(
                SearchBlockEntity(2, "b0", 0, "MVCC\nkeeps an internal newline"),
                SearchBlockEntity(2, "b7", 1, "Use 100% of the TRANSACTION log"),
            ),
        )
        dao.saveNoteWithoutSearch(SavedPageEntity(9, "d".repeat(40), "gone/Old.md", "Old", "<html/>", 0))
        dao.saveAnnotation(
            AnnotationEntity(
                "m1",
                5,
                1,
                "a".repeat(40),
                "b1",
                0,
                4,
                "Propagation",
                "about transactions",
                1,
                0,
                state = AnnotationState.ACKNOWLEDGED,
            )
        )

        // Title in a saved list, an exact canonical block, and a highlight's note all match independently.
        val textMatch = dao.searchSavedPageText("%transaction%", 50).single()
        assertEquals(2L, textMatch.documentId)
        assertEquals("b7", textMatch.blockId)
        assertEquals(listOf(1L), dao.searchHighlights("%transaction%", 50).map { it.documentId })
        assertEquals(listOf(1L), dao.searchDocumentNames("%spring%", 50).map { it.documentId })
        // A removed note still matches by name while its saved copy is kept, and not without one.
        assertEquals(listOf(9L), dao.searchDocumentNames("%old%", 50).map { it.documentId })
        // Escaped wildcards are literal: "0\_p" matches "100_percent" only, "0\%" only the text with "100%".
        assertEquals(listOf(3L), dao.searchDocumentNames("%0\\_p%", 50).map { it.documentId })
        assertEquals(listOf(2L), dao.searchSavedPageText("%0\\%%", 50).map { it.documentId })
        assertTrue(dao.searchDocumentNames("%nothing like this%", 50).isEmpty())
        assertTrue(dao.searchSavedPageText("%nothing like this%", 50).isEmpty())
        assertEquals(2, dao.searchDocumentNames("%s%", 2).size)
    }

    @Test
    fun oneRepetitiveNoteContributesOnlyOneBodyResultWithoutHidingTheNextNote() = runBlocking {
        val sha = "a".repeat(40)
        dao.replaceDocuments(
            7,
            listOf(
                DocumentEntity(1, 7, "a.md", "A", sha),
                DocumentEntity(2, 7, "b.md", "B", sha),
            ),
        )
        dao.savePage(
            SavedPageEntity(1, sha, "a.md", "A", "<html/>", 0),
            (0..5).map { position -> SearchBlockEntity(1, "b$position", position, "Needle $position") },
        )
        dao.savePage(
            SavedPageEntity(2, sha, "b.md", "B", "<html/>", 0),
            listOf(SearchBlockEntity(2, "b0", 0, "Needle in the next note")),
        )

        val rows = dao.searchSavedPageText("%needle%", 50)
        assertEquals(listOf(1L, 2L), rows.map { it.documentId })
        assertEquals(listOf("b0", "b0"), rows.map { it.blockId })
        val found = search(dao, "needle")
        assertEquals(
            rows.map {
                OpenAt.Search(Passage(it.blobSha, it.blockId, 0, 6, "Needle"))
            },
            found.matches.map { it.openAt },
        )
        assertFalse(found.isTruncated)
    }

    @Test
    fun searchAppliesTheGlobalLimitAfterNamingMatchesByTheirCurrentDocuments() = runBlocking {
        val sha = "a".repeat(40)
        val listed = (1L..60L).map { DocumentEntity(it, 7, "topics/Topic $it.md", "Topic $it", sha) }
        dao.replaceDocuments(7, listed + DocumentEntity(61, 7, "now/Renamed.md", "Renamed", "b".repeat(40)))
        // A body match supersedes the same note's name match, so each exact block appears once.
        for (row in listed) dao.savePage(
            SavedPageEntity(row.id, sha, row.path, row.title, "<html/>", 0),
            listOf(SearchBlockEntity(row.id, "b0", 0, "A topic in detail")),
        )
        // Saved before the note was renamed and moved: its documents row names it, not the older copy.
        dao.savePage(
            SavedPageEntity(61, "c".repeat(40), "then/Original.md", "Original", "<html/>", 0),
            listOf(SearchBlockEntity(61, "b3", 0, "Written about a topic")),
        )

        val found = search(dao, "topic")
        assertEquals(SEARCH_LIMIT, found.matches.map { it.documentId }.distinct().size)
        assertEquals(SEARCH_LIMIT, found.matches.size)
        assertTrue(found.isTruncated)
        assertEquals(emptyList<SearchResult>(), search(dao, "original").matches)
        val written = search(dao, "written").matches.single()
        assertEquals("Renamed" to "now/Renamed.md", written.title to written.path)
        assertEquals("Note text · Written about a topic", written.detail)
        assertEquals(OpenAt.Search(Passage("c".repeat(40), "b3", 0, 7, "Written")), written.openAt)
    }

    @Test
    fun forgettingADisconnectedRepositoryRemovesOnlyItsRowsIncludingPendingOnes() = runBlocking {
        dao.replaceRepositories(
            listOf(RepositoryEntity(7, "o/notes", 2, "c".repeat(40)), RepositoryEntity(8, "o/other", 1, "c".repeat(40)))
        )
        dao.replaceDocuments(7, listOf(DocumentEntity(1, 7, "a.md", "a", "a".repeat(40))))
        dao.replaceDocuments(8, listOf(DocumentEntity(3, 8, "c.md", "c", "c".repeat(40))))
        for (id in listOf(1L, 2L, 3L)) {
            dao.saveNoteWithoutSearch(SavedPageEntity(id, "a".repeat(40), "n.md", "n", "<html/>", 0))
            dao.saveReading(readAt(id, "a".repeat(40), at = id).copy(pending = id == 2L))
            dao.saveBookmark(bookmark(id, bookmarked = true, pending = false, at = id))
            dao.saveAnnotation(annotation("m$id", null, null, AnnotationState.UNSENT).copy(documentId = id))
        }
        // Document 2 was deleted upstream: it is in the server's list of the connection's documents, not in the saved
        // list.
        dao.forgetRepository(7, listOf(1L, 2L))
        assertEquals(listOf(8L), dao.repositories().first().map { it.id })
        assertEquals(emptyList<DocumentEntity>(), dao.documents(7).first())
        assertEquals(listOf(3L), dao.documents(8).first().map { it.id })
        assertNull(dao.savedPage(1))
        assertNull(dao.savedPage(2))
        assertTrue(dao.savedPage(3) != null)
        assertNull(dao.reading(2))
        assertEquals(emptyList<ReadingStateEntity>(), dao.pendingReading())
        assertEquals(listOf(3L), dao.bookmarks().first().map { it.documentId })
        assertEquals(listOf("m3"), dao.waitingAnnotations().map { it.mutationId })
    }

    @Test
    fun aRepositoryMissingFromTheServersListIsForgottenWithItsNotesAndUnsentWork() = runBlocking {
        dao.replaceRepositories(
            listOf(RepositoryEntity(7, "o/notes", 1, "c".repeat(40)), RepositoryEntity(8, "o/other", 1, "c".repeat(40)))
        )
        dao.replaceDocuments(7, listOf(DocumentEntity(1, 7, "a.md", "a", "a".repeat(40))))
        dao.replaceDocuments(8, listOf(DocumentEntity(3, 8, "c.md", "c", "c".repeat(40))))
        for (id in listOf(1L, 3L)) {
            dao.saveNoteWithoutSearch(SavedPageEntity(id, "a".repeat(40), "n.md", "n", "<html/>", 0))
            dao.saveReading(readAt(id, "a".repeat(40), at = id).copy(pending = true))
            dao.saveAnnotation(annotation("m$id", null, null, AnnotationState.UNSENT).copy(documentId = id))
        }
        // Disconnected with its answer lost, or from another device: the server no longer lists repository 7.
        dao.replaceRepositories(listOf(RepositoryEntity(8, "o/other", 1, "c".repeat(40))))
        assertEquals(listOf(8L), dao.repositories().first().map { it.id })
        assertNull(dao.savedPage(1))
        assertNull(dao.reading(1))
        assertNull(dao.annotation("m1"))
        assertTrue(dao.savedPage(3) != null)
        assertEquals(listOf(3L), dao.pendingReading().map { it.documentId })
        dao.replaceRepositories(listOf(RepositoryEntity(8, "o/other", 1, "c".repeat(40))))
        assertTrue(dao.savedPage(3) != null)
    }

    @Test
    fun theServersReadingListReplacesAcknowledgedRowsAndKeepsPendingSaves() = runBlocking {
        dao.replaceDocuments(7, (1L..4L).map { DocumentEntity(it, 7, "$it.md", "$it", "a".repeat(40)) })
        dao.saveReading(readAt(1, "a".repeat(40), at = 10))
        dao.saveReading(readAt(2, "a".repeat(40), at = 10).copy(pending = true))
        dao.saveReading(readAt(3, "a".repeat(40), at = 10))
        dao.replaceRemoteReading(listOf(readAt(3, "b".repeat(40), at = 20), readAt(4, "a".repeat(40), at = 5)))
        assertNull(dao.reading(1))
        assertTrue(dao.reading(2)!!.pending)
        assertEquals("b".repeat(40), dao.reading(3)!!.lastReadBlobSha)
        assertEquals(setOf(2L, 3L, 4L), dao.recentReading(5).first().map { it.documentId }.toSet())
    }

    @Test
    fun onlySavedCopiesThatNothingListsOrRefersToAreForgotten() = runBlocking {
        dao.replaceDocuments(
            7,
            listOf(
                DocumentEntity(1, 7, "listed.md", "listed", "a".repeat(40)),
                DocumentEntity(6, 7, "removed.md", "removed", "a".repeat(40), deleted = true),
            ),
        )
        for (id in 1L..6L) dao.saveNoteWithoutSearch(SavedPageEntity(id, "a".repeat(40), "n$id.md", "n", "<html/>", 0))
        dao.saveReading(readAt(2, "a".repeat(40), at = 1))
        dao.saveBookmark(bookmark(3, bookmarked = true, pending = false, at = 1))
        dao.saveAnnotation(annotation("m4", 4, null, AnnotationState.ACKNOWLEDGED).copy(documentId = 4))
        // A removed note's copy goes once nothing refers to it, as an unlisted one's does.
        dao.forgetUnreferencedSavedPages()
        assertEquals(listOf(1L, 2L, 3L, 4L), (1L..6L).filter { dao.savedPage(it) != null })
    }

    @Test
    fun unsentCountsIncludeRefusedWorkAndStayWithinTheirRepository() = runBlocking {
        dao.replaceDocuments(7, listOf(DocumentEntity(1, 7, "a.md", "a", "a".repeat(40))))
        dao.replaceDocuments(8, listOf(DocumentEntity(3, 8, "c.md", "c", "c".repeat(40))))
        dao.saveAnnotation(annotation("pending", null, null, AnnotationState.UNSENT).copy(documentId = 1))
        dao.saveAnnotation(annotation("refused", null, null, AnnotationState.REFUSED, "Refused").copy(documentId = 1))
        dao.saveAnnotation(
            annotation("card", 9, null, AnnotationState.ACKNOWLEDGED)
                .copy(documentId = 1, type = AnnotationType.CARD, question = "Why?")
        )
        dao.saveReview(ReviewEntity("grade", "card", 4, 1, "a".repeat(40), true))
        dao.saveAnnotation(annotation("elsewhere", null, null, AnnotationState.UNSENT).copy(documentId = 3))
        dao.saveReading(readAt(3, "a".repeat(40), at = 1).copy(pending = true))
        dao.saveBookmark(bookmark(3, bookmarked = true, pending = true, at = 1))
        assertEquals(3, dao.unsentInRepository(7))
        assertEquals(1, dao.unsentInRepository(8))
        // Neither a refused highlight (kept locally) nor the pending reading position counts as unsent.
        assertEquals(4, dao.unsentChangeCount().first())
    }

    @Test
    fun aRemovedNoteIsNamedWhereItsUsersDataIsKeptAndLeftOutOfEveryListOfNotes() = runBlocking {
        val (a, b) = listOf("a".repeat(40), "b".repeat(40))
        val links = """<main><a href="/note-link?target=listed">listed</a></main>"""
        dao.replaceDocuments(
            7,
            listOf(
                DocumentEntity(1, 7, "interview/listed.md", "listed", a),
                DocumentEntity(2, 7, "interview/removed.md", "removed", a, deleted = true),
                DocumentEntity(3, 7, "gone.md", "gone", a, changedAt = 5, deleted = true),
            ),
        )
        for (id in listOf(1L, 2L)) {
            dao.saveNoteWithoutSearch(SavedPageEntity(id, a, "interview/n$id.md", "n$id", links, 0))
            dao.saveReading(readAt(id, b, at = id))
            dao.saveBookmark(bookmark(id, bookmarked = true, pending = false, at = id))
            dao.saveAnnotation(annotation("m$id", id, null, AnnotationState.ACKNOWLEDGED).copy(documentId = id))
        }

        assertEquals(listOf(1L), dao.documents(7).first().map { it.id })
        assertEquals(listOf(1L), dao.listedDocuments(7).map { it.id })
        assertEquals(listOf(1L, 2L, 3L), dao.documentIds(7).sorted())
        assertEquals(listOf(1L), dao.bankPages().first().map { it.documentId })
        assertEquals(listOf(1L), dao.noteLinks(7).map { it.documentId })
        assertEquals(1, dao.savedPageCount(7))
        assertEquals(1, dao.savedListCount())
        assertEquals(1, dao.searchableSavedPageCount())
        assertEquals(emptyList<DocumentNameMatch>(), dao.searchDocumentNames("%gone%", 10))
        assertEquals(listOf(1L), dao.updatedSinceRead().first().map { it.documentId })
        assertTrue(dao.recentlyChanged(5).first().isEmpty())
        // Named, and marked removed, wherever the user's own data on it is kept.
        val removed = mapOf(1L to false, 2L to true)
        assertEquals(removed, dao.recentReading(5).first().associate { it.documentId to it.deleted })
        assertEquals("removed", dao.recentReading(5).first().single { it.deleted }.title)
        assertEquals(removed, dao.bookmarks().first().associate { it.documentId to it.deleted })
        assertEquals(removed, dao.notebook().first().associate { it.annotation.documentId to it.deleted })
        assertEquals(setOf("listed", "removed"), dao.searchHighlights("%text%", 10).map { it.title }.toSet())
    }

    @Test
    fun aCreationDeletedBeforeItsAcknowledgementStaysDeletedThroughServerLists() = runBlocking {
        dao.replaceDocuments(7, listOf(DocumentEntity(1, 7, "a.md", "a", "a".repeat(40))))
        dao.saveAnnotation(
            annotation("deleted", null, null, AnnotationState.UNSENT)
                .copy(type = AnnotationType.CARD, question = "Why?")
        )
        dao.saveReview(ReviewEntity("grade", "deleted", 4, 1, "a".repeat(40), pending = true))
        dao.markDeleting("deleted")
        assertTrue(dao.annotations(1).first().isEmpty())
        assertTrue(dao.notebook().first().isEmpty())
        assertTrue(dao.searchHighlights("%text%", 10).isEmpty())
        assertTrue(dao.pendingReviews().isEmpty())
        assertEquals(listOf("deleted"), dao.waitingAnnotations().map { it.mutationId })
        // The server had it after all (a lost acknowledgement), from a listing, the notebook, or a late answer.
        val serverCopy = annotation("deleted", 9, null, AnnotationState.ACKNOWLEDGED)
        dao.replaceRemoteAnnotations(1, listOf(serverCopy))
        dao.replaceRemoteNotebook(listOf(serverCopy), emptyList())
        dao.saveServerAnnotation(serverCopy)
        val kept = dao.annotation("deleted")!!
        assertEquals(AnnotationState.DELETING, kept.state)
        assertEquals(9L, kept.serverId)
        assertTrue(dao.annotations(1).first().isEmpty())
        assertEquals(1, dao.unsentInRepository(7))
    }

    private fun annotation(
        mutationId: String,
        serverId: Long?,
        note: String?,
        state: AnnotationState,
        rejection: String? = null,
    ) = AnnotationEntity(mutationId, serverId, 1, "a".repeat(40), "b2", 0, 4, "text", note, 1, 0, state, rejection)

    @Test
    fun serverAnnotationListAcknowledgesAMatchingPendingCreationAndKeepsOthers() = runBlocking {
        dao.saveAnnotation(annotation("lost-ack", null, "mine", AnnotationState.UNSENT))
        dao.saveAnnotation(annotation("offline", null, "later", AnnotationState.UNSENT))
        dao.saveAnnotation(annotation("deleted-elsewhere", 9, null, AnnotationState.ACKNOWLEDGED))
        dao.replaceRemoteAnnotations(
            1,
            listOf(
                annotation("lost-ack", 7, "mine", AnnotationState.ACKNOWLEDGED),
                annotation("other-device", 8, null, AnnotationState.ACKNOWLEDGED),
            ),
        )
        val rows = dao.annotations(1).first().associateBy { it.mutationId }
        assertEquals(setOf("lost-ack", "offline", "other-device"), rows.keys)
        assertEquals(7L, rows.getValue("lost-ack").serverId)
        assertEquals(AnnotationState.ACKNOWLEDGED, rows.getValue("lost-ack").state)
        assertEquals(AnnotationState.UNSENT, rows.getValue("offline").state)
    }

    @Test
    fun refusedCreationsAreKeptVisibleButNotRetried() = runBlocking {
        dao.saveAnnotation(annotation("refused", null, null, AnnotationState.UNSENT))
        dao.rejectAnnotation("refused", "The selected text does not match that version of the note.")
        assertEquals(emptyList<AnnotationEntity>(), dao.waitingAnnotations())
        assertEquals(
            "The selected text does not match that version of the note.",
            dao.annotation("refused")!!.rejection,
        )
    }

    /** Note edits use PATCH; Android's HttpURLConnection must accept it before any network I/O. */
    @Test
    fun platformHttpClientAcceptsPatch() {
        val connection = java.net.URL("http://127.0.0.1:9/").openConnection() as java.net.HttpURLConnection
        connection.requestMethod = "PATCH"
        assertEquals("PATCH", connection.requestMethod)
    }

    @Test
    fun firstGradesRecheckTheBankAndExistingScheduleInsideTheirTransaction() = runBlocking {
        val sha = "a".repeat(40)
        val card =
            annotation("new-card", null, null, AnnotationState.UNSENT)
                .copy(
                    type = AnnotationType.CARD,
                    question = "Test-only question?",
                )
        val review = ReviewEntity("first-grade", card.mutationId, 4, 0, sha, true)
        dao.saveCardWithFirstReview(card, review)
        assertTrue("A missing bank must not admit a first grade", dao.annotations(1).first().isEmpty())
        dao.saveNoteWithoutSearch(SavedPageEntity(1, sha, "a.md", "Test-only bank", "<html/>", 0))
        dao.saveCardWithFirstReview(card, review)
        assertTrue(dao.reviews().first().isEmpty())
        val document = DocumentEntity(1, 7, "a.md", "Test-only bank", sha)
        for (changed in listOf(document.copy(deleted = true), document.copy(blobSha = "b".repeat(40)))) {
            dao.insertDocuments(listOf(changed))
            dao.saveCardWithFirstReview(card, review)
            assertTrue(dao.annotations(1).first().isEmpty())
            assertTrue(dao.reviews().first().isEmpty())
        }
        dao.insertDocuments(listOf(document))
        dao.saveCardWithFirstReview(card, review)
        dao.saveCardWithFirstReview(
            card.copy(mutationId = "duplicate", question = "Edited prompt?"),
            review.copy(mutationId = "duplicate-grade", cardMutationId = "duplicate"),
        )
        assertEquals(listOf("new-card"), dao.annotations(1).first().map { it.mutationId })
        assertEquals(listOf("first-grade"), dao.reviews().first().map { it.mutationId })
    }

    @Test
    fun gradesSurviveNotebookRefreshAndAcknowledgementAndOnlyCurrentAnswersCanBeGraded() = runBlocking {
        val sha = "a".repeat(40)
        dao.replaceDocuments(7, listOf(DocumentEntity(1, 7, "a.md", "a", sha)))
        dao.saveNoteWithoutSearch(SavedPageEntity(1, sha, "a.md", "a", "<html/>", 0))
        val card =
            annotation("card", null, null, AnnotationState.UNSENT).copy(type = AnnotationType.CARD, question = "Why?")
        dao.saveAnnotation(card)
        val grades = (1..10).map { ReviewEntity("g$it", "card", 4, it.toLong(), sha, true) }
        grades.forEach { assertTrue(dao.recordReview(it)) }
        dao.replaceRemoteNotebook(listOf(card.copy(serverId = 8, state = AnnotationState.ACKNOWLEDGED)), emptyList())
        assertEquals(10, dao.pendingReviews().size)
        dao.replaceRemoteNotebook(
            listOf(card.copy(serverId = 8, state = AnnotationState.ACKNOWLEDGED)),
            grades.map { it.copy(pending = false) },
        )
        assertTrue(dao.pendingReviews().isEmpty())
        assertEquals(10, dao.reviews().first().size)
        dao.replaceRemoteNotebook(
            listOf(card.copy(serverId = 8, state = AnnotationState.ACKNOWLEDGED)),
            grades.map { it.copy(pending = false) },
        )
        assertEquals(10, dao.reviews().first().size)
        // Answered in a version that is no longer the note's current one.
        assertEquals(false, dao.recordReview(grades[0].copy(mutationId = "stale", blobSha = "b".repeat(40))))
        // The card as a pending offline creation again.
        dao.saveAnnotation(card)
        dao.replaceRemoteNotebook(emptyList(), emptyList())
        // Pending offline creations stay; acknowledged deletions remove associated logs.
        assertEquals(AnnotationState.UNSENT, dao.annotation("card")!!.state)
        dao.removeAnnotation("card")
        assertTrue(dao.reviews().first().isEmpty())
    }

    @Test
    fun disconnectRemovesCardsAndPendingGradesButKeepsOtherRepositories() = runBlocking {
        for (id in listOf(1L, 2L)) {
            dao.saveAnnotation(
                annotation("card$id", id, null, AnnotationState.ACKNOWLEDGED)
                    .copy(documentId = id, type = AnnotationType.CARD, question = "Why?")
            )
            dao.saveReview(ReviewEntity("grade$id", "card$id", 4, id, "a".repeat(40), true))
        }
        dao.forgetRepository(7, listOf(1L))
        assertNull(dao.annotation("card1"))
        assertEquals(listOf("grade2"), dao.pendingReviews().map { it.mutationId })
        dao.replaceRemoteAnnotations(2, emptyList())
        assertTrue(dao.reviews().first().isEmpty())
    }
}
