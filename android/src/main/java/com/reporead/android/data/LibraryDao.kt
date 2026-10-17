package com.reporead.android.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** Ids bound in one statement, so it stays under SQLite's limit on bound variables. */
private const val IDS_PER_STATEMENT = 500

@Dao
interface LibraryDao {
    @Query("select * from repositories order by fullName") fun repositories(): Flow<List<RepositoryRow>>

    @Query("delete from repositories") suspend fun clearRepositories()

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertRepositories(rows: List<RepositoryRow>)

    @Query("select id from repositories") suspend fun repositoryIds(): List<Long>

    /**
     * The server's complete list replaces the saved one. A repository no longer listed was disconnected — here with its
     * answer lost, or on another device — so everything saved for its listed notes is forgotten too.
     */
    @Transaction
    suspend fun replaceRepositories(rows: List<RepositoryRow>) {
        val listed = rows.map { it.id }.toSet()
        for (gone in repositoryIds().filter { it !in listed }) forgetRepository(gone, documentIds(gone))
        clearRepositories()
        insertRepositories(rows)
    }

    /**
     * The server's library, read from one snapshot, replaces the saved lists in one transaction, so the phone holds all
     * of one answer or none of it: the repositories and each one's notes, then the reading, bookmark and notebook rows
     * (acknowledged ones only; pending work stays), then saved copies nothing refers to any more. Their images are
     * swept from what is left (Sync.sweepImages).
     */
    @Transaction
    suspend fun replaceFromServer(
        library: Map<RepositoryRow, List<DocumentRow>>,
        reading: List<ReadingRow>,
        bookmarks: List<BookmarkRow>,
        annotations: List<AnnotationRow>,
        reviews: List<ReviewRow>,
        sessionLimit: Int,
    ) {
        replaceRepositories(library.keys.toList())
        for ((repository, documents) in library) replaceDocuments(repository.id, documents)
        replaceRemoteReading(reading)
        replaceRemoteBookmarks(bookmarks)
        replaceRemoteNotebook(annotations, reviews, sessionLimit)
        forgetUnreferencedNotes()
    }

    @Query("select * from documents where repositoryId = :repositoryId and not deleted order by path")
    fun documents(repositoryId: Long): Flow<List<DocumentRow>>

    /** The notes [repositoryId] lists now: what note links resolve against and Save all fetches. */
    @Query("select * from documents where repositoryId = :repositoryId and not deleted")
    suspend fun listedDocuments(repositoryId: Long): List<DocumentRow>

    /** Every note of [repositoryId], removed ones too. */
    @Query("select id from documents where repositoryId = :repositoryId")
    suspend fun documentIds(repositoryId: Long): List<Long>

    @Query("select * from documents where id = :id") suspend fun document(id: Long): DocumentRow?

    @Query("delete from documents where repositoryId = :repositoryId") suspend fun clearDocuments(repositoryId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertDocuments(rows: List<DocumentRow>)

    @Transaction
    suspend fun replaceDocuments(repositoryId: Long, rows: List<DocumentRow>) {
        clearDocuments(repositoryId)
        insertDocuments(rows)
    }

    @Query("delete from repositories where id = :repositoryId") suspend fun deleteRepository(repositoryId: Long)

    @Query("delete from notes where documentId in (:documentIds)") suspend fun deleteNotes(documentIds: List<Long>)

    @Query("delete from reading_states where documentId in (:documentIds)")
    suspend fun deleteReading(documentIds: List<Long>)

    @Query("delete from bookmarks where documentId in (:documentIds)")
    suspend fun deleteBookmarks(documentIds: List<Long>)

    @Query("delete from annotations where documentId in (:documentIds)")
    suspend fun deleteAnnotations(documentIds: List<Long>)

    @Query("delete from change_baselines where documentId in (:documentIds)")
    suspend fun deleteBaselines(documentIds: List<Long>)

    /**
     * Removes everything this phone saved for a disconnected repository: the repository, its note list, and the saved
     * notes, reading states, bookmarks, and highlights (pending ones too) of [documentIds], the server's complete list.
     */
    @Transaction
    suspend fun forgetRepository(repositoryId: Long, documentIds: List<Long>) {
        for (chunk in documentIds.chunked(IDS_PER_STATEMENT)) {
            deleteNotes(chunk)
            deleteReading(chunk)
            deleteBookmarks(chunk)
            deleteReviewsOnDocuments(chunk)
            deleteAnnotations(chunk)
            deleteBaselines(chunk)
        }
        clearDocuments(repositoryId)
        deleteRepository(repositoryId)
    }

    @Query("select * from notes where documentId = :documentId") suspend fun note(documentId: Long): NoteRow?

    /** The saved pages of [repositoryId]'s notes, without their HTML, which Save all compares but does not need. */
    @Query(
        """select documentId, blobSha, path, renderFormat from notes
           where documentId in (select id from documents where repositoryId = :repositoryId)"""
    )
    suspend fun savedVersions(repositoryId: Long): List<SavedVersion>

    /** Every saved page's version: the only images worth keeping (NoteImages.sweep). */
    @Query("select documentId, blobSha, path, renderFormat from notes")
    suspend fun allSavedVersions(): List<SavedVersion>

    /**
     * The listed notes in a folder named `interview`, each with its saved page only when that page is the listed
     * version: an older copy reads as unsaved, so Practice never asks a question whose grade would be refused. Only
     * banks: loading every saved page to find them exhausts the heap (invariants, Bounded memory). The match is a
     * superset of isInterviewBank (LIKE ignores ASCII case), which still decides.
     */
    @Query(
        """select d.id as documentId, d.title, d.path, n.html, n.blobSha
              from documents d left join notes n on n.documentId = d.id and n.blobSha = d.blobSha
              where not d.deleted and ('/' || d.path) like '%/interview/%' order by d.id"""
    )
    fun bankPages(): Flow<List<PracticePage>>

    /** The note links in [repositoryId]'s saved pages, for Linked from; the pages themselves are not read. */
    @Query(
        "select l.* from note_links l join documents d on d.id = l.documentId where d.repositoryId = :repositoryId and not d.deleted"
    )
    suspend fun noteLinks(repositoryId: Long): List<NoteLinkRow>

    @Query(
        "select count(*) from notes n join documents d on d.id = n.documentId where d.repositoryId = :repositoryId and not d.deleted"
    )
    suspend fun savedNoteCount(repositoryId: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertNote(row: NoteRow)

    @Insert suspend fun insertNoteLinks(rows: List<NoteLinkRow>)

    @Query("delete from note_links where documentId = :documentId") suspend fun deleteNoteLinks(documentId: Long)

    /** Saves a page with the note links it holds, replacing an older copy and its links. */
    @Transaction
    suspend fun saveNote(row: NoteRow) {
        deleteNoteLinks(row.documentId)
        insertNote(row)
        insertNoteLinks(
            noteLinkQueries(row.html).mapIndexed { position, query -> NoteLinkRow(row.documentId, position, query) }
        )
    }

    @Query(
        """select documentId from notes where documentId not in (select id from documents where not deleted)
              and documentId not in (select documentId from reading_states) and documentId not in (select documentId from bookmarks)
              and documentId not in (select documentId from annotations)"""
    )
    suspend fun unreferencedNoteIds(): List<Long>

    /**
     * Deletes saved copies of notes that no saved list lists now and that no reading, bookmark, or annotation row
     * refers to — for example notes removed from their repository. Run only after those rows were replaced from the
     * server's complete lists.
     */
    @Transaction
    suspend fun forgetUnreferencedNotes() {
        for (chunk in unreferencedNoteIds().chunked(IDS_PER_STATEMENT)) deleteNotes(chunk)
    }

    /**
     * Highlights, cards, and grades on [repositoryId]'s listed notes that exist only on this phone (unsent or refused).
     */
    @Query(
        """select (select count(*) from annotations a join documents d on d.id = a.documentId where d.repositoryId = :repositoryId and a.state <> 'ACKNOWLEDGED')
              + (select count(*) from review_log l join annotations a on a.mutationId = l.cardMutationId join documents d on d.id = a.documentId
                 where d.repositoryId = :repositoryId and l.pending)"""
    )
    suspend fun unsentInRepository(repositoryId: Long): Int

    /** Every change on this phone that the server has not acknowledged (unsent or refused): what signing out loses. */
    @Query(
        """select (select count(*) from annotations where state <> 'ACKNOWLEDGED') + (select count(*) from review_log where pending)
              + (select count(*) from reading_states where pending) + (select count(*) from bookmarks where pending)"""
    )
    suspend fun unsentCount(): Int

    /**
     * Changes waiting to be sent: [unsentCount] without the ones the server refused, which sending again cannot
     * deliver.
     */
    @Query(
        """select (select count(*) from annotations where state in ('UNSENT', 'DELETING'))
              + (select count(*) from review_log where pending and rejection is null)
              + (select count(*) from reading_states where pending) + (select count(*) from bookmarks where pending)"""
    )
    fun waitingCount(): Flow<Int>

    /**
     * Notes in the saved lists whose title or path matches, and saved copies whose title, path, or text matches.
     * [pattern] is a LIKE pattern with \ as the escape character (ASCII letters match case-insensitively).
     */
    @Query(
        """select id as documentId, title, path, null as searchText from documents
              where not deleted and (title like :pattern escape '\' or path like :pattern escape '\')
              union
              select documentId, title, path, case when searchText like :pattern escape '\' then searchText end from notes
              where title like :pattern escape '\' or path like :pattern escape '\' or searchText like :pattern escape '\'
              order by title limit :limit"""
    )
    suspend fun searchNotes(pattern: String, limit: Int): List<NoteMatch>

    @Query(
        """select a.documentId, d.title, d.path, a.exactText, a.note
              from annotations a join documents d on d.id = a.documentId
              where a.state <> 'DELETING' and (a.exactText like :pattern escape '\' or a.note like :pattern escape '\')
              order by a.createdAt desc limit :limit"""
    )
    suspend fun searchHighlights(pattern: String, limit: Int): List<HighlightMatch>

    @Query("select count(*) from documents where not deleted") suspend fun savedListCount(): Int

    /** Notes in the saved lists whose saved copy has search text: the part of [savedListCount] searched by text. */
    @Query(
        "select count(*) from notes n join documents d on d.id = n.documentId where not d.deleted and n.searchText is not null"
    )
    suspend fun searchableNoteCount(): Int

    @Query(
        """select r.documentId, d.title, d.path, d.deleted, r.progressPercent
              from reading_states r join documents d on d.id = r.documentId order by r.lastReadAt desc limit :limit"""
    )
    fun recentReading(limit: Int): Flow<List<ReadNote>>

    @Query("select * from reading_states where documentId = :documentId")
    suspend fun reading(documentId: Long): ReadingRow?

    /**
     * Read notes whose current version in the saved note lists is not the one last read, or whose changes since an
     * older version have not been shown yet; most recently changed first. [ChangedNote.lastReadBlobSha] is that
     * baseline.
     */
    @Query(
        """select d.id as documentId, d.title, d.path, d.blobSha, d.changedAt, coalesce(b.sinceBlobSha, r.lastReadBlobSha) as lastReadBlobSha
              from reading_states r join documents d on d.id = r.documentId left join change_baselines b on b.documentId = d.id
              where not d.deleted and (d.blobSha != r.lastReadBlobSha or b.sinceBlobSha is not null)
              order by d.changedAt is null, d.changedAt desc, r.lastReadAt desc"""
    )
    fun updatedSinceRead(): Flow<List<ChangedNote>>

    /**
     * The most recently changed notes other than those updated since read: never read, or read (and seen) in their
     * current version.
     */
    @Query(
        """select d.id as documentId, d.title, d.path, d.blobSha, d.changedAt, r.lastReadBlobSha
              from documents d left join reading_states r on r.documentId = d.id
              where not d.deleted and d.changedAt is not null and (r.lastReadBlobSha is null or r.lastReadBlobSha = d.blobSha)
              and d.id not in (select documentId from change_baselines)
              order by d.changedAt desc limit :limit"""
    )
    fun recentlyChanged(limit: Int): Flow<List<ChangedNote>>

    @Query("select sinceBlobSha from change_baselines where documentId = :documentId")
    suspend fun changeBaseline(documentId: Long): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveBaseline(row: ChangeBaselineRow)

    /** The summary of changes since [since] has been shown; a different baseline set meanwhile stays. */
    @Query("delete from change_baselines where documentId = :documentId and sinceBlobSha = :since")
    suspend fun changesSeen(documentId: Long, since: String)

    /**
     * Saves the reader's position in the version it displayed. Moving to another version keeps the version left behind
     * as the change baseline, unless an older one is still unseen, so the next summary covers every change not yet
     * seen.
     */
    @Transaction
    suspend fun saveDisplayedReading(row: ReadingRow) {
        val previous = reading(row.documentId)
        if (
            previous != null &&
                previous.lastReadBlobSha != row.lastReadBlobSha &&
                changeBaseline(row.documentId) == null
        ) {
            saveBaseline(ChangeBaselineRow(row.documentId, previous.lastReadBlobSha))
        }
        saveReading(row)
    }

    @Query("select * from reading_states where pending") suspend fun pendingReading(): List<ReadingRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveReading(row: ReadingRow)

    /** Acknowledges only the save that was sent; a newer local save made meanwhile stays pending. */
    @Query("update reading_states set pending = 0 where documentId = :documentId and lastReadAt = :lastReadAt")
    suspend fun acknowledgeReading(documentId: Long, lastReadAt: Long)

    /** A local pending save wins unless the server's state is at least as new. */
    @Transaction
    suspend fun mergeRemoteReading(remote: ReadingRow) {
        val local = reading(remote.documentId)
        if (local == null || !local.pending || remote.lastReadAt >= local.lastReadAt)
            saveReading(remote.copy(pending = false))
    }

    @Query("delete from reading_states where not pending") suspend fun clearAcknowledgedReading()

    /**
     * The server's complete list replaces acknowledged rows; pending local saves are kept or yield as
     * [mergeRemoteReading] says.
     */
    @Transaction
    suspend fun replaceRemoteReading(remote: List<ReadingRow>) {
        clearAcknowledgedReading()
        remote.forEach { mergeRemoteReading(it) }
    }

    @Query(
        """select b.documentId, d.title, d.path, d.deleted from bookmarks b join documents d on d.id = b.documentId
              where b.bookmarked order by b.changedAt desc"""
    )
    fun bookmarks(): Flow<List<BookmarkedNote>>

    @Query("select * from bookmarks where documentId = :documentId") fun bookmark(documentId: Long): Flow<BookmarkRow?>

    @Query("select * from bookmarks where pending") suspend fun pendingBookmarks(): List<BookmarkRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveBookmark(row: BookmarkRow)

    @Query("update bookmarks set pending = 0 where documentId = :documentId and changedAt = :changedAt and bookmarked")
    suspend fun acknowledgeBookmarkSet(documentId: Long, changedAt: Long)

    @Query("delete from bookmarks where documentId = :documentId and changedAt = :changedAt and not bookmarked")
    suspend fun acknowledgeBookmarkCleared(documentId: Long, changedAt: Long)

    @Query("delete from bookmarks where documentId = :documentId and changedAt = :changedAt")
    suspend fun dropBookmark(documentId: Long, changedAt: Long)

    @Query("delete from bookmarks where not pending") suspend fun clearAcknowledgedBookmarks()

    @Query("select documentId from bookmarks where pending") suspend fun pendingBookmarkIds(): List<Long>

    @Query("select * from annotations where documentId = :documentId and state <> 'DELETING' order by createdAt")
    fun annotations(documentId: Long): Flow<List<AnnotationRow>>

    @Query("select * from annotations where mutationId = :mutationId")
    suspend fun annotation(mutationId: String): AnnotationRow?

    @Query("select * from annotations where state in ('UNSENT', 'DELETING') order by createdAt")
    suspend fun waitingAnnotations(): List<AnnotationRow>

    @Query(
        """select a.*, d.title, d.path, d.blobSha as currentBlobSha, d.deleted, n.blobSha as savedBlobSha
              from annotations a join documents d on d.id = a.documentId left join notes n on n.documentId = a.documentId
              where a.state <> 'DELETING' order by a.createdAt, a.mutationId"""
    )
    fun notebook(): Flow<List<NotebookItem>>

    @Query("select * from review_log order by reviewedAt, mutationId") fun reviews(): Flow<List<ReviewRow>>

    @Query("select * from review_log where pending and rejection is null order by reviewedAt, mutationId")
    suspend fun pendingReviews(): List<ReviewRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveReview(row: ReviewRow)

    /** A concurrent sync may invalidate/delete a card after it was revealed; never persist that stale grade. */
    @Transaction
    suspend fun recordReview(row: ReviewRow): Boolean {
        require(row.grade in 0..5 && row.pending && row.rejection == null) { "Invalid local review ${row.mutationId}" }
        val card = annotation(row.cardMutationId) ?: return false
        val document = document(card.documentId) ?: return false
        if (
            card.type != AnnotationType.CARD ||
                card.state == AnnotationState.DELETING ||
                row.blobSha != document.blobSha ||
                card.cardBlock(document.deleted, row.blobSha, note(card.documentId)?.blobSha) != null
        )
            return false
        saveReview(row)
        return true
    }

    @Query("update review_log set rejection = :reason where mutationId = :mutationId")
    suspend fun rejectReview(mutationId: String, reason: String)

    /** Forgets grades the server refused; they never reached it and the schedule already ignores them. */
    @Query("delete from review_log where rejection is not null") suspend fun dismissRefusedReviews()

    @Query("select sessionLimit from review_limits where id = 1") fun reviewLimit(): Flow<Int?>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveReviewLimit(row: ReviewLimitRow)

    @Query("delete from review_log where cardMutationId = :mutationId")
    suspend fun deleteCardReviews(mutationId: String)

    @Query(
        "delete from review_log where cardMutationId in (select mutationId from annotations where documentId in (:ids))"
    )
    suspend fun deleteReviewsOnDocuments(ids: List<Long>)

    @Query("delete from review_log where not pending") suspend fun clearAcknowledgedReviews()

    @Query("delete from review_log where cardMutationId not in (select mutationId from annotations)")
    suspend fun deleteMissingCardReviews()

    @Query("delete from annotations where state = 'ACKNOWLEDGED'") suspend fun clearAcknowledgedNotebook()

    @Transaction
    suspend fun replaceRemoteNotebook(rows: List<AnnotationRow>, reviews: List<ReviewRow>, limit: Int) {
        clearAcknowledgedNotebook()
        rows.forEach { saveServerAnnotation(it) }
        clearAcknowledgedReviews()
        reviews.forEach { saveReview(it) }
        deleteMissingCardReviews()
        saveReviewLimit(ReviewLimitRow(sessionLimit = limit))
    }

    @Transaction
    suspend fun removeAnnotation(mutationId: String) {
        deleteCardReviews(mutationId)
        deleteAnnotation(mutationId)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveAnnotation(row: AnnotationRow)

    /**
     * Saves the server's copy of an annotation, acknowledging a pending creation of it. One the user deleted meanwhile
     * stays marked for deletion, now with the server's id, so a sync or an answer arriving late cannot bring it back.
     */
    @Transaction
    suspend fun saveServerAnnotation(row: AnnotationRow) {
        val deleting = annotation(row.mutationId)?.state == AnnotationState.DELETING
        saveAnnotation(
            row.copy(state = if (deleting) AnnotationState.DELETING else AnnotationState.ACKNOWLEDGED, rejection = null)
        )
    }

    @Query("update annotations set state = 'DELETING' where mutationId = :mutationId")
    suspend fun setDeleting(mutationId: String)

    @Query("delete from review_log where cardMutationId = :mutationId and pending")
    suspend fun deletePendingCardReviews(mutationId: String)

    /**
     * The user deleted a creation the server has not acknowledged: hide it now and send the deletion at the next sync.
     */
    @Transaction
    suspend fun markDeleting(mutationId: String) {
        deletePendingCardReviews(mutationId)
        setDeleting(mutationId)
    }

    @Query("delete from annotations where mutationId = :mutationId") suspend fun deleteAnnotation(mutationId: String)

    @Query("update annotations set state = 'REFUSED', rejection = :reason where mutationId = :mutationId")
    suspend fun rejectAnnotation(mutationId: String, reason: String)

    @Query("delete from annotations where documentId = :documentId and state = 'ACKNOWLEDGED'")
    suspend fun clearAcknowledgedAnnotations(documentId: Long)

    /**
     * The server's complete list for a document replaces acknowledged rows. A pending creation the server already has
     * (same mutation id, e.g. after a lost acknowledgement) becomes acknowledged; other pending creations are kept.
     */
    @Transaction
    suspend fun replaceRemoteAnnotations(documentId: Long, remote: List<AnnotationRow>) {
        clearAcknowledgedAnnotations(documentId)
        for (row in remote) saveServerAnnotation(row)
        deleteMissingCardReviews()
    }

    /** The server's complete bookmark list replaces acknowledged rows; pending local toggles are kept. */
    @Transaction
    suspend fun replaceRemoteBookmarks(remote: List<BookmarkRow>) {
        clearAcknowledgedBookmarks()
        val pending = pendingBookmarkIds().toSet()
        for (row in remote) if (row.documentId !in pending) saveBookmark(row.copy(pending = false))
    }
}
