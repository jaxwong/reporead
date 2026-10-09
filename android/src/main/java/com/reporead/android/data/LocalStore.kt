package com.reporead.android.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/*
 * The phone's offline projection of backend state, plus pending local changes. The backend owns durable state;
 * rows here are replaced from complete server responses and never from partial ones.
 */

@Entity(tableName = "repositories")
data class RepositoryRow(@PrimaryKey val id: Long, val fullName: String, val documentCount: Int, val lastSyncedCommitSha: String?)

/** [changedAt]: when a repository refresh found the note new or changed (epoch ms); null if RepoRead never saw it change. */
@Entity(tableName = "documents", indices = [Index("repositoryId")])
data class DocumentRow(@PrimaryKey val id: Long, val repositoryId: Long, val path: String, val title: String, val blobSha: String,
                       val changedAt: Long? = null)

/** A note in the library's change lists: its current version, when it was seen changing, and the version last read (null: never). */
data class ChangedNote(val documentId: Long, val title: String, val path: String, val blobSha: String, val changedAt: Long?,
                       val lastReadBlobSha: String?)

/**
 * The latest complete rendered copy of an opened note, identified by document and source version. [searchText] is its
 * blocks' canonical text, one per line; null for a copy saved before search existed. [renderFormat] is the server's page
 * format for [html]; 0 for copies saved before formats were recorded.
 */
@Entity(tableName = "notes")
data class NoteRow(@PrimaryKey val documentId: Long, val blobSha: String, val commitSha: String, val path: String,
                   val title: String, val html: String, val fetchedAt: Long, val searchText: String? = null,
                   @ColumnInfo(defaultValue = "0") val renderFormat: Int = 0)

/** A saved note's rendered page. */
data class SavedPage(val documentId: Long, val html: String)

/** One listed note for practice; null HTML means its questions are not available on this phone. */
data class PracticePage(val documentId: Long, val title: String, val path: String, val html: String?, val blobSha: String?)

/** A saved note matching a search: [searchText] is set when it matched by text, for a snippet. */
data class NoteMatch(val documentId: Long, val title: String, val path: String, val searchText: String?)

/** A highlight matching a search, with its note's title from the saved lists or saved copies. */
data class HighlightMatch(val documentId: Long, val title: String, val path: String, val exactText: String, val note: String?)

/**
 * [pending] is a local save the backend has not acknowledged. Last write wins by [lastReadAt]. [deleted]: the note was
 * not in the latest complete repository refresh, as last reported by the backend.
 */
@Entity(tableName = "reading_states")
data class ReadingRow(@PrimaryKey val documentId: Long, val title: String, val path: String, val lastReadBlobSha: String,
                      val progressPercent: Int, val anchorJson: String, val lastReadAt: Long, val pending: Boolean,
                      @ColumnInfo(defaultValue = "0") val deleted: Boolean = false)

/** A row with bookmarked=false is a pending removal; it is deleted once the backend acknowledges it. */
@Entity(tableName = "bookmarks")
data class BookmarkRow(@PrimaryKey val documentId: Long, val title: String, val path: String, val sourceBlobSha: String,
                       val bookmarked: Boolean, val pending: Boolean, val changedAt: Long,
                       @ColumnInfo(defaultValue = "0") val deleted: Boolean = false)

/** A passage in one source version of a note, as UTF-16 offsets into a block's canonical text. */
data class Passage(val blobSha: String, val blockId: String, val startOffset: Int, val endOffset: Int, val exactText: String)

/**
 * A highlight, keyed by the client mutation id that created it (the server returns it for every highlight).
 * [pending] is a creation the backend has not acknowledged; the row is the local annotation and its pending mutation
 * in one write. [rejection] is the server's reason for refusing a creation; such rows are shown, not retried.
 * [deleting]: the user deleted it before the server acknowledged its creation. It is hidden at once and stays, pending,
 * until a sync has replayed the creation (the server may already have it) and deleted it there.
 * The source/block/offset/exact fields and [prefixText]/[suffixText] are the original selection. [location], [status]
 * and [resolvedBlobSha] are the server's: where the highlight is now and what that means for that version (ANCHORED,
 * REANCHORED, ORPHANED). A pending creation has none of them and is drawn where it was made.
 */
@Entity(tableName = "annotations", indices = [Index("documentId")])
data class AnnotationRow(@PrimaryKey val mutationId: String, val serverId: Long?, val documentId: Long, val sourceBlobSha: String,
                         val blockId: String, val startOffset: Int, val endOffset: Int, val exactText: String, val note: String?,
                         val version: Int, val createdAt: Long, val pending: Boolean, val rejection: String?,
                         @ColumnInfo(defaultValue = "") val prefixText: String = "",
                         @ColumnInfo(defaultValue = "") val suffixText: String = "",
                         val status: String? = null, val resolvedBlobSha: String? = null,
                         @Embedded(prefix = "location") val location: Passage? = null,
                         @ColumnInfo(defaultValue = "'HIGHLIGHT'") val type: String = "HIGHLIGHT",
                         val question: String? = null, val checkedBlobSha: String? = null,
                         @ColumnInfo(defaultValue = "''") val cachedTitle: String = "",
                         @ColumnInfo(defaultValue = "''") val cachedPath: String = "",
                         val cachedCurrentBlobSha: String? = null,
                         @ColumnInfo(defaultValue = "0") val cachedDeleted: Boolean = false,
                         @ColumnInfo(defaultValue = "0") val deleting: Boolean = false) {
    /** Where to draw it: the server's current location, or the original selection while the creation is pending. */
    val drawn: Passage get() = location ?: Passage(sourceBlobSha, blockId, startOffset, endOffset, exactText)

    /** The server could not find it reliably in [blobSha]. */
    fun orphanedIn(blobSha: String) = status == "ORPHANED" && resolvedBlobSha == blobSha
}

/**
 * The version a note was last read in before a newer one was shown, kept until a change summary from it has been shown,
 * so opening the note offline or leaving before the summary loads does not use it up. Phone-only: what the user has
 * not seen yet is not the server's.
 */
@Entity(tableName = "change_baselines")
data class ChangeBaselineRow(@PrimaryKey val documentId: Long, val sinceBlobSha: String)

/** Pending grades and the server log share the same immutable client mutation id. */
@Entity(tableName = "review_log", indices = [Index("cardMutationId")])
data class ReviewRow(@PrimaryKey val mutationId: String, val cardMutationId: String, val grade: Int,
                     val reviewedAt: Long, val blobSha: String, val pending: Boolean, val rejection: String? = null)

/** The server supplies this ceiling; a phone without a complete notebook response cannot start a session. */
@Entity(tableName = "review_limits")
data class ReviewLimitRow(@PrimaryKey val id: Int = 1, val sessionLimit: Int)

data class NotebookItem(@Embedded val annotation: AnnotationRow, val title: String, val path: String,
                        val currentBlobSha: String?, val savedBlobSha: String?, val removed: Boolean)

@Dao
interface LibraryDao {
    @Query("select * from repositories order by fullName")
    fun repositories(): Flow<List<RepositoryRow>>

    @Query("delete from repositories")
    suspend fun clearRepositories()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRepositories(rows: List<RepositoryRow>)

    @Query("select id from repositories")
    suspend fun repositoryIds(): List<Long>

    /**
     * The server's complete list replaces the saved one. A repository no longer listed was disconnected — here with its
     * answer lost, or on another device — so everything saved for its listed notes is forgotten too. Returns those
     * notes' ids, for the files kept beside the database.
     */
    @Transaction
    suspend fun replaceRepositories(rows: List<RepositoryRow>): List<Long> {
        val listed = rows.map { it.id }.toSet()
        val forgotten = mutableListOf<Long>()
        for (gone in repositoryIds().filter { it !in listed }) {
            val ids = documentsOnce(gone).map { it.id }
            forgetRepository(gone, ids)
            forgotten += ids
        }
        clearRepositories()
        insertRepositories(rows)
        return forgotten
    }

    @Query("select * from documents where repositoryId = :repositoryId order by path")
    fun documents(repositoryId: Long): Flow<List<DocumentRow>>

    @Query("select * from documents where repositoryId = :repositoryId")
    suspend fun documentsOnce(repositoryId: Long): List<DocumentRow>

    @Query("select * from documents where id = :id")
    suspend fun document(id: Long): DocumentRow?

    @Query("delete from documents where repositoryId = :repositoryId")
    suspend fun clearDocuments(repositoryId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDocuments(rows: List<DocumentRow>)

    @Transaction
    suspend fun replaceDocuments(repositoryId: Long, rows: List<DocumentRow>) {
        clearDocuments(repositoryId)
        insertDocuments(rows)
    }

    @Query("delete from repositories where id = :repositoryId")
    suspend fun deleteRepository(repositoryId: Long)

    @Query("delete from notes where documentId in (:documentIds)")
    suspend fun deleteNotes(documentIds: List<Long>)

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
        // Bounded so each statement stays under SQLite's limit on bound variables.
        for (chunk in documentIds.chunked(500)) {
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

    @Query("select * from notes where documentId = :documentId")
    suspend fun note(documentId: Long): NoteRow?

    @Query("""select d.id as documentId, d.title, d.path, n.html, n.blobSha
              from documents d left join notes n on n.documentId = d.id order by d.id""")
    fun practicePages(): Flow<List<PracticePage>>

    /** The pages of [repositoryId]'s listed notes saved on this phone that link to any note. */
    @Query("""select n.documentId, n.html from notes n join documents d on d.id = n.documentId
              where d.repositoryId = :repositoryId and n.html like '%/note-link?%'""")
    suspend fun pagesWithNoteLinks(repositoryId: Long): List<SavedPage>

    @Query("select count(*) from notes n join documents d on d.id = n.documentId where d.repositoryId = :repositoryId")
    suspend fun savedNoteCount(repositoryId: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveNote(row: NoteRow)

    @Query("""select documentId from notes where documentId not in (select id from documents)
              and documentId not in (select documentId from reading_states) and documentId not in (select documentId from bookmarks)
              and documentId not in (select documentId from annotations)""")
    suspend fun unreferencedNoteIds(): List<Long>

    /**
     * Deletes saved copies of notes that are in no saved note list and that no reading, bookmark, or annotation row
     * refers to — for example a disconnected repository's removed notes. Run only after those rows were replaced from
     * the server's complete lists. Returns the deleted ids, for their images.
     */
    @Transaction
    suspend fun forgetUnreferencedNotes(): List<Long> {
        val ids = unreferencedNoteIds()
        for (chunk in ids.chunked(500)) deleteNotes(chunk)
        return ids
    }

    /** Highlights, cards, and grades on [repositoryId]'s listed notes that exist only on this phone (unsent or refused). */
    @Query("""select (select count(*) from annotations a join documents d on d.id = a.documentId where d.repositoryId = :repositoryId and a.pending)
              + (select count(*) from review_log l join annotations a on a.mutationId = l.cardMutationId join documents d on d.id = a.documentId
                 where d.repositoryId = :repositoryId and l.pending)""")
    suspend fun unsentInRepository(repositoryId: Long): Int

    /** Every change on this phone that the server has not acknowledged (unsent or refused): what signing out loses. */
    @Query("""select (select count(*) from annotations where pending) + (select count(*) from review_log where pending)
              + (select count(*) from reading_states where pending) + (select count(*) from bookmarks where pending)""")
    suspend fun unsentCount(): Int

    /**
     * Notes in the saved lists whose title or path matches, and saved copies whose title, path, or text matches.
     * [pattern] is a LIKE pattern with \ as the escape character (ASCII letters match case-insensitively).
     */
    @Query("""select id as documentId, title, path, null as searchText from documents
              where title like :pattern escape '\' or path like :pattern escape '\'
              union
              select documentId, title, path, case when searchText like :pattern escape '\' then searchText end from notes
              where title like :pattern escape '\' or path like :pattern escape '\' or searchText like :pattern escape '\'
              order by title limit :limit""")
    suspend fun searchNotes(pattern: String, limit: Int): List<NoteMatch>

    @Query("""select a.documentId, coalesce(d.title, n.title, '') as title, coalesce(d.path, n.path, '') as path, a.exactText, a.note
              from annotations a left join documents d on d.id = a.documentId left join notes n on n.documentId = a.documentId
              where not a.deleting and (a.exactText like :pattern escape '\' or a.note like :pattern escape '\')
              order by a.createdAt desc limit :limit""")
    suspend fun searchHighlights(pattern: String, limit: Int): List<HighlightMatch>

    @Query("select count(*) from documents")
    suspend fun savedListCount(): Int

    /** Notes in the saved lists whose saved copy has search text: the part of [savedListCount] searched by text. */
    @Query("select count(*) from notes n join documents d on d.id = n.documentId where n.searchText is not null")
    suspend fun searchableNoteCount(): Int

    @Query("select * from reading_states order by lastReadAt desc limit :limit")
    fun recentReading(limit: Int): Flow<List<ReadingRow>>

    @Query("select * from reading_states where documentId = :documentId")
    suspend fun reading(documentId: Long): ReadingRow?

    /**
     * Read notes whose current version in the saved note lists is not the one last read, or whose changes since an older
     * version have not been shown yet; most recently changed first. [ChangedNote.lastReadBlobSha] is that baseline.
     */
    @Query("""select d.id as documentId, d.title, d.path, d.blobSha, d.changedAt, coalesce(b.sinceBlobSha, r.lastReadBlobSha) as lastReadBlobSha
              from reading_states r join documents d on d.id = r.documentId left join change_baselines b on b.documentId = d.id
              where d.blobSha != r.lastReadBlobSha or b.sinceBlobSha is not null
              order by d.changedAt is null, d.changedAt desc, r.lastReadAt desc""")
    fun updatedSinceRead(): Flow<List<ChangedNote>>

    /** The most recently changed notes other than those updated since read: never read, or read (and seen) in their current version. */
    @Query("""select d.id as documentId, d.title, d.path, d.blobSha, d.changedAt, r.lastReadBlobSha
              from documents d left join reading_states r on r.documentId = d.id
              where d.changedAt is not null and (r.lastReadBlobSha is null or r.lastReadBlobSha = d.blobSha)
              and d.id not in (select documentId from change_baselines)
              order by d.changedAt desc limit :limit""")
    fun recentlyChanged(limit: Int): Flow<List<ChangedNote>>

    @Query("select sinceBlobSha from change_baselines where documentId = :documentId")
    suspend fun changeBaseline(documentId: Long): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveBaseline(row: ChangeBaselineRow)

    /** The summary of changes since [since] has been shown; a different baseline set meanwhile stays. */
    @Query("delete from change_baselines where documentId = :documentId and sinceBlobSha = :since")
    suspend fun changesSeen(documentId: Long, since: String)

    /**
     * Saves the reader's position in the version it displayed. Moving to another version keeps the version left behind
     * as the change baseline, unless an older one is still unseen, so the next summary covers every change not yet seen.
     */
    @Transaction
    suspend fun saveDisplayedReading(row: ReadingRow) {
        val previous = reading(row.documentId)
        if (previous != null && previous.lastReadBlobSha != row.lastReadBlobSha && changeBaseline(row.documentId) == null) {
            saveBaseline(ChangeBaselineRow(row.documentId, previous.lastReadBlobSha))
        }
        saveReading(row)
    }

    @Query("select * from reading_states where pending")
    suspend fun pendingReading(): List<ReadingRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveReading(row: ReadingRow)

    /** Acknowledges only the save that was sent; a newer local save made meanwhile stays pending. */
    @Query("update reading_states set pending = 0 where documentId = :documentId and lastReadAt = :lastReadAt")
    suspend fun acknowledgeReading(documentId: Long, lastReadAt: Long)

    /** A local pending save wins unless the server's state is at least as new. */
    @Transaction
    suspend fun mergeRemoteReading(remote: ReadingRow) {
        val local = reading(remote.documentId)
        if (local == null || !local.pending || remote.lastReadAt >= local.lastReadAt) saveReading(remote.copy(pending = false))
    }

    @Query("delete from reading_states where not pending")
    suspend fun clearAcknowledgedReading()

    /** The server's complete list replaces acknowledged rows; pending local saves are kept or yield as [mergeRemoteReading] says. */
    @Transaction
    suspend fun replaceRemoteReading(remote: List<ReadingRow>) {
        clearAcknowledgedReading()
        remote.forEach { mergeRemoteReading(it) }
    }

    @Query("select * from bookmarks where bookmarked order by changedAt desc")
    fun bookmarks(): Flow<List<BookmarkRow>>

    @Query("select * from bookmarks where documentId = :documentId")
    fun bookmark(documentId: Long): Flow<BookmarkRow?>

    @Query("select * from bookmarks where pending")
    suspend fun pendingBookmarks(): List<BookmarkRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveBookmark(row: BookmarkRow)

    @Query("update bookmarks set pending = 0 where documentId = :documentId and changedAt = :changedAt and bookmarked")
    suspend fun acknowledgeBookmarkSet(documentId: Long, changedAt: Long)

    @Query("delete from bookmarks where documentId = :documentId and changedAt = :changedAt and not bookmarked")
    suspend fun acknowledgeBookmarkCleared(documentId: Long, changedAt: Long)

    @Query("delete from bookmarks where documentId = :documentId and changedAt = :changedAt")
    suspend fun dropBookmark(documentId: Long, changedAt: Long)

    @Query("delete from bookmarks where not pending")
    suspend fun clearAcknowledgedBookmarks()

    @Query("select documentId from bookmarks where pending")
    suspend fun pendingBookmarkIds(): List<Long>

    @Query("select * from annotations where documentId = :documentId and not deleting order by createdAt")
    fun annotations(documentId: Long): Flow<List<AnnotationRow>>

    @Query("select * from annotations where mutationId = :mutationId")
    suspend fun annotation(mutationId: String): AnnotationRow?

    @Query("select * from annotations where pending and rejection is null order by createdAt")
    suspend fun pendingAnnotations(): List<AnnotationRow>

    @Query("""select a.*, coalesce(d.title, n.title, a.cachedTitle) as title,
              coalesce(d.path, n.path, a.cachedPath) as path, d.blobSha as currentBlobSha,
              n.blobSha as savedBlobSha, a.cachedDeleted as removed
              from annotations a left join documents d on d.id = a.documentId left join notes n on n.documentId = a.documentId
              where not a.deleting order by a.createdAt, a.mutationId""")
    fun notebook(): Flow<List<NotebookItem>>

    @Query("select * from review_log order by reviewedAt, mutationId")
    fun reviews(): Flow<List<ReviewRow>>

    @Query("select * from review_log where pending and rejection is null order by reviewedAt, mutationId")
    suspend fun pendingReviews(): List<ReviewRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveReview(row: ReviewRow)

    /** A concurrent sync may invalidate/delete a card after it was revealed; never persist that stale grade. */
    @Transaction
    suspend fun recordReview(row: ReviewRow): Boolean {
        require(row.grade in 0..5 && row.pending && row.rejection == null) { "Invalid local review ${row.mutationId}" }
        val card = annotation(row.cardMutationId) ?: return false
        val current = document(card.documentId) ?: return false
        val saved = note(card.documentId) ?: return false
        if (card.type != "CARD" || card.deleting || card.rejection != null || card.cachedDeleted || card.status == "ORPHANED" ||
            card.checkedBlobSha != row.blobSha || card.drawn.blobSha != row.blobSha || current.blobSha != row.blobSha ||
            saved.blobSha != row.blobSha || (card.cachedCurrentBlobSha != null && card.cachedCurrentBlobSha != row.blobSha)) return false
        saveReview(row)
        return true
    }

    @Query("update review_log set rejection = :reason where mutationId = :mutationId")
    suspend fun rejectReview(mutationId: String, reason: String)

    /** Forgets grades the server refused; they never reached it and the schedule already ignores them. */
    @Query("delete from review_log where rejection is not null")
    suspend fun dismissRefusedReviews()

    @Query("select sessionLimit from review_limits where id = 1")
    fun reviewLimit(): Flow<Int?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveReviewLimit(row: ReviewLimitRow)

    @Query("delete from review_log where cardMutationId = :mutationId")
    suspend fun deleteCardReviews(mutationId: String)

    @Query("delete from review_log where cardMutationId in (select mutationId from annotations where documentId in (:ids))")
    suspend fun deleteReviewsOnDocuments(ids: List<Long>)

    @Query("delete from review_log where not pending")
    suspend fun clearAcknowledgedReviews()

    @Query("delete from review_log where cardMutationId not in (select mutationId from annotations)")
    suspend fun deleteMissingCardReviews()

    @Query("delete from annotations where not pending")
    suspend fun clearAcknowledgedNotebook()

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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveAnnotation(row: AnnotationRow)

    /**
     * Saves the server's copy of an annotation, acknowledging a pending creation of it. One the user deleted meanwhile
     * stays marked for deletion, now with the server's id, so a sync or an answer arriving late cannot bring it back.
     */
    @Transaction
    suspend fun saveServerAnnotation(row: AnnotationRow) {
        val deleting = annotation(row.mutationId)?.deleting == true
        saveAnnotation(if (deleting) row.copy(pending = true, rejection = null, deleting = true) else row.copy(pending = false, rejection = null))
    }

    @Query("update annotations set deleting = 1 where mutationId = :mutationId")
    suspend fun setDeleting(mutationId: String)

    @Query("delete from review_log where cardMutationId = :mutationId and pending")
    suspend fun deletePendingCardReviews(mutationId: String)

    /** The user deleted a creation the server has not acknowledged: hide it now and send the deletion at the next sync. */
    @Transaction
    suspend fun markDeleting(mutationId: String) {
        deletePendingCardReviews(mutationId)
        setDeleting(mutationId)
    }

    @Query("delete from annotations where mutationId = :mutationId")
    suspend fun deleteAnnotation(mutationId: String)

    @Query("update annotations set rejection = :reason where mutationId = :mutationId")
    suspend fun rejectAnnotation(mutationId: String, reason: String)

    @Query("delete from annotations where documentId = :documentId and not pending")
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

@Database(
    entities = [RepositoryRow::class, DocumentRow::class, NoteRow::class, ReadingRow::class, BookmarkRow::class, AnnotationRow::class,
        ReviewRow::class, ReviewLimitRow::class, ChangeBaselineRow::class],
    version = 8,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5), AutoMigration(from = 5, to = 6), AutoMigration(from = 6, to = 7), AutoMigration(from = 7, to = 8)],
)
abstract class LocalStore : RoomDatabase() {
    abstract fun library(): LibraryDao

    companion object {
        @Volatile private var instance: LocalStore? = null

        /** One database instance per process, shared across activity recreation. */
        fun get(context: Context): LocalStore = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, LocalStore::class.java, "reporead.db").build().also { instance = it }
        }
    }
}
