package com.reporead.android.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Dao
import androidx.room.Database
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

@Entity(tableName = "documents", indices = [Index("repositoryId")])
data class DocumentRow(@PrimaryKey val id: Long, val repositoryId: Long, val path: String, val title: String, val blobSha: String)

/** The latest complete rendered copy of an opened note, identified by document and source version. */
@Entity(tableName = "notes")
data class NoteRow(@PrimaryKey val documentId: Long, val blobSha: String, val commitSha: String, val path: String,
                   val title: String, val html: String, val fetchedAt: Long)

/** [pending] is a local save the backend has not acknowledged. Last write wins by [lastReadAt]. */
@Entity(tableName = "reading_states")
data class ReadingRow(@PrimaryKey val documentId: Long, val title: String, val path: String, val lastReadBlobSha: String,
                      val progressPercent: Int, val anchorJson: String, val lastReadAt: Long, val pending: Boolean)

/** A row with bookmarked=false is a pending removal; it is deleted once the backend acknowledges it. */
@Entity(tableName = "bookmarks")
data class BookmarkRow(@PrimaryKey val documentId: Long, val title: String, val path: String, val sourceBlobSha: String,
                       val bookmarked: Boolean, val pending: Boolean, val changedAt: Long)

/**
 * A highlight, keyed by the client mutation id that created it (the server returns it for every highlight).
 * [pending] is a creation the backend has not acknowledged; the row is the local annotation and its pending mutation
 * in one write. [rejection] is the server's reason for refusing a creation; such rows are shown, not retried.
 */
@Entity(tableName = "annotations", indices = [Index("documentId")])
data class AnnotationRow(@PrimaryKey val mutationId: String, val serverId: Long?, val documentId: Long, val sourceBlobSha: String,
                         val blockId: String, val startOffset: Int, val endOffset: Int, val exactText: String, val note: String?,
                         val version: Int, val createdAt: Long, val pending: Boolean, val rejection: String?)

@Dao
interface LibraryDao {
    @Query("select * from repositories order by fullName")
    fun repositories(): Flow<List<RepositoryRow>>

    @Query("delete from repositories")
    suspend fun clearRepositories()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRepositories(rows: List<RepositoryRow>)

    @Transaction
    suspend fun replaceRepositories(rows: List<RepositoryRow>) {
        clearRepositories()
        insertRepositories(rows)
    }

    @Query("select * from documents where repositoryId = :repositoryId order by path")
    fun documents(repositoryId: Long): Flow<List<DocumentRow>>

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

    @Query("select * from notes where documentId = :documentId")
    suspend fun note(documentId: Long): NoteRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveNote(row: NoteRow)

    @Query("select * from reading_states order by lastReadAt desc limit :limit")
    fun recentReading(limit: Int): Flow<List<ReadingRow>>

    @Query("select * from reading_states where documentId = :documentId")
    suspend fun reading(documentId: Long): ReadingRow?

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

    @Query("select * from annotations where documentId = :documentId order by createdAt")
    fun annotations(documentId: Long): Flow<List<AnnotationRow>>

    @Query("select * from annotations where mutationId = :mutationId")
    suspend fun annotation(mutationId: String): AnnotationRow?

    @Query("select * from annotations where pending and rejection is null order by createdAt")
    suspend fun pendingAnnotations(): List<AnnotationRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveAnnotation(row: AnnotationRow)

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
        for (row in remote) saveAnnotation(row.copy(pending = false, rejection = null))
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
    entities = [RepositoryRow::class, DocumentRow::class, NoteRow::class, ReadingRow::class, BookmarkRow::class, AnnotationRow::class],
    version = 2,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
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
