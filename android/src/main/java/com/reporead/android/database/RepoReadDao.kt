package com.reporead.android.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/** Ids bound in one statement, so it stays under SQLite's limit on bound variables. */
private const val IDS_PER_STATEMENT = 500

@Dao
interface RepoReadDao {
    @Query("SELECT * FROM repositories ORDER BY fullName") fun repositories(): Flow<List<RepositoryEntity>>

    @Query("DELETE FROM repositories") suspend fun clearRepositories()

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertRepositories(rows: List<RepositoryEntity>)

    @Query("SELECT id FROM repositories") suspend fun repositoryIds(): List<Long>

    @Query("SELECT lastSyncedCommitSha FROM repositories WHERE id = :repositoryId")
    suspend fun repositoryCommitSha(repositoryId: Long): String?

    /**
     * The server's complete list replaces the saved one. A repository no longer listed was disconnected — here with its
     * answer lost, or on another device — so everything saved for its listed notes is forgotten too.
     */
    @Transaction
    suspend fun replaceRepositories(rows: List<RepositoryEntity>) {
        val listed = rows.map { it.id }.toSet()
        for (gone in repositoryIds().filter { it !in listed }) forgetRepository(gone, documentIds(gone))
        clearRepositories()
        insertRepositories(rows)
    }

    /**
     * The server's library, read from one snapshot, replaces the saved lists in one transaction, so the phone holds all
     * of one answer or none of it: the repositories and each one's notes, then the reading, bookmark and notebook rows
     * (acknowledged ones only; pending work stays), then saved copies nothing refers to any more. Their images are
     * swept from what is left (PhoneSync.sweepImages).
     */
    @Transaction
    suspend fun replaceFromServer(
        library: Map<RepositoryEntity, List<DocumentEntity>>,
        reading: List<ReadingStateEntity>,
        bookmarks: List<BookmarkEntity>,
        annotations: List<AnnotationEntity>,
        reviews: List<ReviewEntity>,
    ) {
        replaceRepositories(library.keys.toList())
        for ((repository, documents) in library) replaceDocuments(repository.id, documents)
        replaceRemoteReading(reading)
        replaceRemoteBookmarks(bookmarks)
        replaceRemoteNotebook(annotations, reviews)
        forgetUnreferencedSavedPages()
    }

    @Query("SELECT * FROM documents WHERE repositoryId = :repositoryId AND NOT deleted ORDER BY path")
    fun documents(repositoryId: Long): Flow<List<DocumentEntity>>

    /** The notes [repositoryId] lists now: what note links resolve against and Save all fetches. */
    @Query("SELECT * FROM documents WHERE repositoryId = :repositoryId AND NOT deleted")
    suspend fun listedDocuments(repositoryId: Long): List<DocumentEntity>

    /** Every note of [repositoryId], removed ones too. */
    @Query("SELECT id FROM documents WHERE repositoryId = :repositoryId")
    suspend fun documentIds(repositoryId: Long): List<Long>

    @Query("SELECT * FROM documents WHERE id = :id") suspend fun document(id: Long): DocumentEntity?

    @Query("DELETE FROM documents WHERE repositoryId = :repositoryId") suspend fun clearDocuments(repositoryId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertDocuments(rows: List<DocumentEntity>)

    @Transaction
    suspend fun replaceDocuments(repositoryId: Long, rows: List<DocumentEntity>) {
        clearDocuments(repositoryId)
        insertDocuments(rows)
    }

    @Query("DELETE FROM repositories WHERE id = :repositoryId") suspend fun deleteRepository(repositoryId: Long)

    @Query("DELETE FROM saved_pages WHERE documentId IN (:documentIds)")
    suspend fun deleteSavedPages(documentIds: List<Long>)

    @Query("DELETE FROM reading_states WHERE documentId IN (:documentIds)")
    suspend fun deleteReading(documentIds: List<Long>)

    @Query("DELETE FROM bookmarks WHERE documentId IN (:documentIds)")
    suspend fun deleteBookmarks(documentIds: List<Long>)

    @Query("DELETE FROM annotations WHERE documentId IN (:documentIds)")
    suspend fun deleteAnnotations(documentIds: List<Long>)

    /**
     * Removes everything this phone saved for a disconnected repository: the repository, its note list, and the saved
     * notes, reading states, bookmarks, and highlights (pending ones too) of [documentIds], the server's complete list.
     */
    @Transaction
    suspend fun forgetRepository(repositoryId: Long, documentIds: List<Long>) {
        for (chunk in documentIds.chunked(IDS_PER_STATEMENT)) {
            deleteSavedPages(chunk)
            deleteReading(chunk)
            deleteBookmarks(chunk)
            deleteReviewsOnDocuments(chunk)
            deleteAnnotations(chunk)
        }
        clearDocuments(repositoryId)
        deleteRepository(repositoryId)
    }

    @Query("SELECT * FROM saved_pages WHERE documentId = :documentId")
    suspend fun savedPage(documentId: Long): SavedPageEntity?

    /** Every saved page's version: the only images worth keeping (SavedPageImages.sweep). */
    @Query("SELECT documentId, blobSha, path, renderFormat, snapshotCommitSha FROM saved_pages")
    suspend fun allSavedVersions(): List<SavedVersion>

    /**
     * The listed notes in a folder named `interview`, each with its saved page only when that page is the listed
     * version: an older copy reads as unsaved, so Practice never asks a question whose grade would be refused. Only
     * banks: loading every saved page to find them exhausts the heap (invariants, Bounded memory). The match is a
     * superset of isInterviewBank (LIKE ignores ASCII case), which still decides.
     */
    @Query(
        """SELECT d.id AS documentId, d.title, d.path, n.html, n.blobSha
              FROM documents d LEFT JOIN saved_pages n ON n.documentId = d.id AND n.blobSha = d.blobSha
              WHERE NOT d.deleted AND ('/' || d.path) LIKE '%/interview/%' ORDER BY d.id"""
    )
    fun bankPages(): Flow<List<PracticePage>>

    /** The note links in [repositoryId]'s saved pages, for Linked from; the pages themselves are not read. */
    @Query(
        "SELECT l.* FROM note_links l JOIN documents d ON d.id = l.documentId WHERE d.repositoryId = :repositoryId AND NOT d.deleted"
    )
    suspend fun noteLinks(repositoryId: Long): List<NoteLinkEntity>

    @Query(
        "SELECT count(*) FROM saved_pages n JOIN documents d ON d.id = n.documentId WHERE d.repositoryId = :repositoryId AND NOT d.deleted"
    )
    suspend fun savedPageCount(repositoryId: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertSavedPage(page: SavedPageEntity)

    @Insert suspend fun insertNoteLinks(rows: List<NoteLinkEntity>)

    @Insert suspend fun insertSearchBlocks(rows: List<SearchBlockEntity>)

    @Query("DELETE FROM note_links WHERE documentId = :documentId") suspend fun deleteNoteLinks(documentId: Long)

    @Query("DELETE FROM search_blocks WHERE documentId = :documentId") suspend fun deleteSearchBlocks(documentId: Long)

    /** Saves a page with its backend-issued search blocks and derived note links, replacing the complete older copy. */
    @Transaction
    suspend fun savePage(page: SavedPageEntity, blocks: List<SearchBlockEntity>) {
        deleteNoteLinks(page.documentId)
        deleteSearchBlocks(page.documentId)
        insertSavedPage(page)
        insertSearchBlocks(blocks)
        insertNoteLinks(
            noteLinkQueries(page.html).mapIndexed { position, query ->
                NoteLinkEntity(page.documentId, position, query)
            }
        )
    }

    @Query(
        """SELECT documentId FROM saved_pages WHERE documentId NOT IN (SELECT id FROM documents WHERE NOT deleted)
              AND documentId NOT IN (SELECT documentId FROM reading_states) AND documentId NOT IN (SELECT documentId FROM bookmarks)
              AND documentId NOT IN (SELECT documentId FROM annotations)"""
    )
    suspend fun unreferencedSavedPageIds(): List<Long>

    /**
     * Deletes saved copies of notes that no saved list lists now and that no reading, bookmark, or annotation row
     * refers to — for example notes removed from their repository. Run only after those rows were replaced from the
     * server's complete lists.
     */
    @Transaction
    suspend fun forgetUnreferencedSavedPages() {
        for (chunk in unreferencedSavedPageIds().chunked(IDS_PER_STATEMENT)) deleteSavedPages(chunk)
    }

    /**
     * Highlights, cards, and grades on [repositoryId]'s listed notes that exist only on this phone (unsent or refused).
     */
    @Query(
        """SELECT (SELECT count(*) FROM annotations a JOIN documents d ON d.id = a.documentId WHERE d.repositoryId = :repositoryId AND a.state <> 'ACKNOWLEDGED')
              + (SELECT count(*) FROM review_log l JOIN annotations a ON a.mutationId = l.cardMutationId JOIN documents d ON d.id = a.documentId
                 WHERE d.repositoryId = :repositoryId AND l.pending)"""
    )
    suspend fun unsentInRepository(repositoryId: Long): Int

    /**
     * The reader's changes waiting to be sent, excluding work the server already refused. A pending reading position
     * also waits ([pendingReading]) but is not counted: every scroll saves one, and the background sender sends it.
     */
    @Query(
        """SELECT (SELECT count(*) FROM annotations WHERE state IN ('UNSENT', 'DELETING'))
              + (SELECT count(*) FROM review_log WHERE pending AND rejection IS NULL)
              + (SELECT count(*) FROM bookmarks WHERE pending)"""
    )
    fun unsentChangeCount(): Flow<Int>

    /** Name-only matches; a note with a body match is omitted because its exact [SavedPageTextMatch] is more useful. */
    @Query(
        """SELECT d.id AS documentId, d.title, d.path, d.title LIKE :pattern ESCAPE '\' AS isTitleMatch
              FROM documents d LEFT JOIN saved_pages n ON n.documentId = d.id
              WHERE (NOT d.deleted OR n.documentId IS NOT NULL)
              AND (d.title LIKE :pattern ESCAPE '\' OR d.path LIKE :pattern ESCAPE '\')
              AND NOT EXISTS (SELECT 1 FROM search_blocks b WHERE b.documentId = d.id AND b.text LIKE :pattern ESCAPE '\')
              ORDER BY d.title LIMIT :limit"""
    )
    suspend fun searchDocumentNames(pattern: String, limit: Int): List<DocumentNameMatch>

    /** The first canonical block per note containing [pattern], with its saved version and exact reader block id. */
    @Query(
        """SELECT d.id AS documentId, d.title, d.path, n.blobSha, b.blockId, b.text
              FROM search_blocks b JOIN saved_pages n ON n.documentId = b.documentId
              JOIN documents d ON d.id = b.documentId
              WHERE b.text LIKE :pattern ESCAPE '\'
              AND NOT EXISTS (SELECT 1 FROM search_blocks earlier
                WHERE earlier.documentId = b.documentId AND earlier.position < b.position
                AND earlier.text LIKE :pattern ESCAPE '\')
              ORDER BY d.title, b.position LIMIT :limit"""
    )
    suspend fun searchSavedPageText(pattern: String, limit: Int): List<SavedPageTextMatch>

    @Query(
        """SELECT a.mutationId, a.documentId, d.title, d.path, a.exactText, a.note
              FROM annotations a JOIN documents d ON d.id = a.documentId
              WHERE a.state <> 'DELETING' AND (a.exactText LIKE :pattern ESCAPE '\' OR a.note LIKE :pattern ESCAPE '\')
              ORDER BY a.createdAt DESC LIMIT :limit"""
    )
    suspend fun searchHighlights(pattern: String, limit: Int): List<HighlightMatch>

    @Query("SELECT count(*) FROM documents WHERE NOT deleted") suspend fun savedListCount(): Int

    /** Notes in the saved lists with a saved page: the part of [savedListCount] whose canonical blocks are searched. */
    @Query("SELECT count(*) FROM saved_pages n JOIN documents d ON d.id = n.documentId WHERE NOT d.deleted")
    suspend fun searchableSavedPageCount(): Int

    @Query(
        """SELECT r.documentId, d.title, d.path, d.deleted, r.progressPercent
              FROM reading_states r JOIN documents d ON d.id = r.documentId ORDER BY r.lastReadAt DESC LIMIT :limit"""
    )
    fun recentReading(limit: Int): Flow<List<ReadNote>>

    @Query("SELECT * FROM reading_states WHERE documentId = :documentId")
    suspend fun reading(documentId: Long): ReadingStateEntity?

    /**
     * Read notes whose current version in the saved note lists is not the one last read; most recently changed first.
     */
    @Query(
        """SELECT d.id AS documentId, d.title, d.path, d.blobSha, d.changedAt, r.lastReadBlobSha
              FROM reading_states r JOIN documents d ON d.id = r.documentId
              WHERE NOT d.deleted AND d.blobSha != r.lastReadBlobSha
              ORDER BY d.changedAt IS NULL, d.changedAt DESC, r.lastReadAt DESC"""
    )
    fun updatedSinceRead(): Flow<List<ChangedNote>>

    /**
     * The most recently changed notes other than those updated since read: never read, or read in their current
     * version.
     */
    @Query(
        """SELECT d.id AS documentId, d.title, d.path, d.blobSha, d.changedAt, r.lastReadBlobSha
              FROM documents d LEFT JOIN reading_states r ON r.documentId = d.id
              WHERE NOT d.deleted AND d.changedAt IS NOT NULL AND (r.lastReadBlobSha IS NULL OR r.lastReadBlobSha = d.blobSha)
              ORDER BY d.changedAt DESC LIMIT :limit"""
    )
    fun recentlyChanged(limit: Int): Flow<List<ChangedNote>>

    /**
     * Saves the reader's position in the version it displayed. Only for a note this phone lists ([document], removed
     * ones too): a save that arrives after a clear or a disconnect has no note, writes nothing, and returns false.
     */
    @Transaction
    suspend fun saveDisplayedReading(row: ReadingStateEntity): Boolean {
        if (document(row.documentId) == null) return false
        saveReading(row)
        return true
    }

    @Query("SELECT * FROM reading_states WHERE pending") suspend fun pendingReading(): List<ReadingStateEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveReading(row: ReadingStateEntity)

    /** Acknowledges only the save that was sent; a newer local save made meanwhile stays pending. */
    @Query("UPDATE reading_states SET pending = 0 WHERE documentId = :documentId AND lastReadAt = :lastReadAt")
    suspend fun acknowledgeReading(documentId: Long, lastReadAt: Long)

    /** A local pending save wins unless the server's state is at least as new. */
    @Transaction
    suspend fun mergeRemoteReading(remote: ReadingStateEntity) {
        val local = reading(remote.documentId)
        if (local == null || !local.pending || remote.lastReadAt >= local.lastReadAt)
            saveReading(remote.copy(pending = false))
    }

    @Query("DELETE FROM reading_states WHERE NOT pending") suspend fun clearAcknowledgedReading()

    /**
     * The server's complete list replaces acknowledged rows; pending local saves are kept or yield as
     * [mergeRemoteReading] says.
     */
    @Transaction
    suspend fun replaceRemoteReading(remote: List<ReadingStateEntity>) {
        clearAcknowledgedReading()
        remote.forEach { mergeRemoteReading(it) }
    }

    @Query(
        """SELECT b.documentId, d.title, d.path, d.deleted FROM bookmarks b JOIN documents d ON d.id = b.documentId
              WHERE b.bookmarked ORDER BY b.changedAt DESC"""
    )
    fun bookmarks(): Flow<List<BookmarkedNote>>

    @Query("SELECT * FROM bookmarks WHERE documentId = :documentId")
    fun bookmark(documentId: Long): Flow<BookmarkEntity?>

    @Query("SELECT * FROM bookmarks WHERE pending") suspend fun pendingBookmarks(): List<BookmarkEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveBookmark(row: BookmarkEntity)

    /** A bookmark change made on this phone, for a note it lists; as [saveDisplayedReading], false writes nothing. */
    @Transaction
    suspend fun saveLocalBookmark(row: BookmarkEntity): Boolean {
        if (document(row.documentId) == null) return false
        saveBookmark(row)
        return true
    }

    @Query("UPDATE bookmarks SET pending = 0 WHERE documentId = :documentId AND changedAt = :changedAt AND bookmarked")
    suspend fun acknowledgeBookmarkSet(documentId: Long, changedAt: Long)

    @Query("DELETE FROM bookmarks WHERE documentId = :documentId AND changedAt = :changedAt AND NOT bookmarked")
    suspend fun acknowledgeBookmarkCleared(documentId: Long, changedAt: Long)

    @Query("DELETE FROM bookmarks WHERE documentId = :documentId AND changedAt = :changedAt")
    suspend fun dropBookmark(documentId: Long, changedAt: Long)

    @Query("DELETE FROM bookmarks WHERE NOT pending") suspend fun clearAcknowledgedBookmarks()

    @Query("SELECT documentId FROM bookmarks WHERE pending") suspend fun pendingBookmarkIds(): List<Long>

    @Query("SELECT * FROM annotations WHERE documentId = :documentId AND state <> 'DELETING' ORDER BY createdAt")
    fun annotations(documentId: Long): Flow<List<AnnotationEntity>>

    @Query("SELECT * FROM annotations WHERE mutationId = :mutationId")
    suspend fun annotation(mutationId: String): AnnotationEntity?

    @Query("SELECT * FROM annotations WHERE state IN ('UNSENT', 'DELETING') ORDER BY createdAt")
    suspend fun waitingAnnotations(): List<AnnotationEntity>

    @Query(
        """SELECT a.*, d.title, d.path, d.blobSha AS currentBlobSha, d.deleted, n.blobSha AS savedBlobSha
              FROM annotations a JOIN documents d ON d.id = a.documentId LEFT JOIN saved_pages n ON n.documentId = a.documentId
              WHERE a.state <> 'DELETING' ORDER BY a.createdAt, a.mutationId"""
    )
    fun notebook(): Flow<List<NotebookItem>>

    @Query("SELECT * FROM review_log ORDER BY reviewedAt, mutationId") fun reviews(): Flow<List<ReviewEntity>>

    @Query("SELECT * FROM review_log WHERE pending AND rejection IS NULL ORDER BY reviewedAt, mutationId")
    suspend fun pendingReviews(): List<ReviewEntity>

    /** The range a grade may take is the server's to check: a grade it refuses comes back as INVALID_REVIEW. */
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveReview(row: ReviewEntity)

    /**
     * Admission and the card-plus-first-grade write share one SQLite transaction, independently of PhoneSync's lock.
     */
    @Transaction
    suspend fun saveCardWithFirstReview(card: AnnotationEntity, review: ReviewEntity): NewCardGrade {
        require(
            card.type == AnnotationType.CARD &&
                review.cardMutationId == card.mutationId &&
                review.blobSha == card.sourceBlobSha &&
                review.pending &&
                review.rejection == null
        ) {
            "Invalid first review ${review.mutationId} for card ${card.mutationId}"
        }
        if ((card.documentId to card.exactText) in scheduledLines(notebook().first()))
            return NewCardGrade.ALREADY_SCHEDULED
        val savedPage = savedPage(card.documentId) ?: return NewCardGrade.NOT_SAVED
        val document = document(card.documentId) ?: return NewCardGrade.NOT_SAVED
        if (savedPage.blobSha != card.sourceBlobSha || document.blobSha != card.sourceBlobSha || document.deleted)
            return NewCardGrade.BANK_CHANGED
        saveAnnotation(card)
        saveReview(review)
        return NewCardGrade.SAVED
    }

    /** A concurrent sync may invalidate/delete a card after it was revealed; never persist that stale grade. */
    @Transaction
    suspend fun recordReview(row: ReviewEntity): Boolean {
        require(row.pending && row.rejection == null) {
            "Invalid local review ${row.mutationId}"
        }
        val card = annotation(row.cardMutationId) ?: return false
        val document = document(card.documentId) ?: return false
        if (
            card.type != AnnotationType.CARD ||
                card.state == AnnotationState.DELETING ||
                row.blobSha != document.blobSha ||
                card.cardBlock(document.deleted, row.blobSha, savedPage(card.documentId)?.blobSha) != null
        )
            return false
        saveReview(row)
        return true
    }

    @Query("UPDATE review_log SET rejection = :reason WHERE mutationId = :mutationId")
    suspend fun rejectReview(mutationId: String, reason: String)

    /** Forgets grades the server refused; they never reached it and the schedule already ignores them. */
    @Query("DELETE FROM review_log WHERE rejection IS NOT NULL") suspend fun dismissRefusedReviews()

    @Query("DELETE FROM review_log WHERE cardMutationId = :mutationId")
    suspend fun deleteCardReviews(mutationId: String)

    @Query(
        "DELETE FROM review_log WHERE cardMutationId IN (SELECT mutationId FROM annotations WHERE documentId IN (:ids))"
    )
    suspend fun deleteReviewsOnDocuments(ids: List<Long>)

    @Query("DELETE FROM review_log WHERE NOT pending") suspend fun clearAcknowledgedReviews()

    @Query("DELETE FROM review_log WHERE cardMutationId NOT IN (SELECT mutationId FROM annotations)")
    suspend fun deleteMissingCardReviews()

    @Query("DELETE FROM annotations WHERE state = 'ACKNOWLEDGED'") suspend fun clearAcknowledgedNotebook()

    @Transaction
    suspend fun replaceRemoteNotebook(rows: List<AnnotationEntity>, reviews: List<ReviewEntity>) {
        clearAcknowledgedNotebook()
        rows.forEach { saveServerAnnotation(it) }
        clearAcknowledgedReviews()
        reviews.forEach { saveReview(it) }
        deleteMissingCardReviews()
    }

    @Transaction
    suspend fun removeAnnotation(mutationId: String) {
        deleteCardReviews(mutationId)
        deleteAnnotation(mutationId)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveAnnotation(row: AnnotationEntity)

    /**
     * A highlight created on this phone, on the saved page it was selected in. Checked and written in one transaction,
     * so two saves of one phrase cannot both find it free.
     */
    @Transaction
    suspend fun saveLocalAnnotation(row: AnnotationEntity): NewHighlight {
        require(row.type == AnnotationType.HIGHLIGHT && row.state == AnnotationState.UNSENT && row.serverId == null) {
            "Invalid new highlight ${row.mutationId}: type=${row.type} state=${row.state} serverId=${row.serverId}"
        }
        if (savedPage(row.documentId) == null) return NewHighlight.PageGone
        highlightOn(annotations(row.documentId).first(), row.drawn)?.let {
            return NewHighlight.AlreadyHighlighted(it)
        }
        saveAnnotation(row)
        return NewHighlight.Saved(row.mutationId)
    }

    /**
     * Saves the server's copy of an annotation, acknowledging a pending creation of it. One the user deleted meanwhile
     * stays marked for deletion, now with the server's id, so a sync or an answer arriving late cannot bring it back.
     */
    @Transaction
    suspend fun saveServerAnnotation(row: AnnotationEntity) {
        val deleting = annotation(row.mutationId)?.state == AnnotationState.DELETING
        saveAnnotation(
            row.copy(state = if (deleting) AnnotationState.DELETING else AnnotationState.ACKNOWLEDGED, rejection = null)
        )
    }

    @Query("UPDATE annotations SET state = 'DELETING' WHERE mutationId = :mutationId")
    suspend fun setDeleting(mutationId: String)

    @Query("DELETE FROM review_log WHERE cardMutationId = :mutationId AND pending")
    suspend fun deletePendingCardReviews(mutationId: String)

    /**
     * The user deleted a creation the server has not acknowledged: hide it now and send the deletion at the next sync.
     */
    @Transaction
    suspend fun markDeleting(mutationId: String) {
        deletePendingCardReviews(mutationId)
        setDeleting(mutationId)
    }

    @Query("DELETE FROM annotations WHERE mutationId = :mutationId") suspend fun deleteAnnotation(mutationId: String)

    @Query("UPDATE annotations SET state = 'REFUSED', rejection = :reason WHERE mutationId = :mutationId")
    suspend fun rejectAnnotation(mutationId: String, reason: String)

    @Query("DELETE FROM annotations WHERE documentId = :documentId AND state = 'ACKNOWLEDGED'")
    suspend fun clearAcknowledgedAnnotations(documentId: Long)

    /**
     * The server's complete list for a document replaces acknowledged rows. A pending creation the server already has
     * (same mutation id, e.g. after a lost acknowledgement) becomes acknowledged; other pending creations are kept.
     */
    @Transaction
    suspend fun replaceRemoteAnnotations(documentId: Long, remote: List<AnnotationEntity>) {
        clearAcknowledgedAnnotations(documentId)
        for (row in remote) saveServerAnnotation(row)
        deleteMissingCardReviews()
    }

    /** The server's complete bookmark list replaces acknowledged rows; pending local toggles are kept. */
    @Transaction
    suspend fun replaceRemoteBookmarks(remote: List<BookmarkEntity>) {
        clearAcknowledgedBookmarks()
        val pending = pendingBookmarkIds().toSet()
        for (row in remote) if (row.documentId !in pending) saveBookmark(row.copy(pending = false))
    }
}
