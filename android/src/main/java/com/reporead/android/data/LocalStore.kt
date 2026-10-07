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
                         @Embedded(prefix = "location") val location: Passage? = null) {
    /** Where to draw it: the server's current location, or the original selection while the creation is pending. */
    val drawn: Passage get() = location ?: Passage(sourceBlobSha, blockId, startOffset, endOffset, exactText)

    /** The server could not find it reliably in [blobSha]. */
    fun orphanedIn(blobSha: String) = status == "ORPHANED" && resolvedBlobSha == blobSha
}

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
            deleteAnnotations(chunk)
        }
        clearDocuments(repositoryId)
        deleteRepository(repositoryId)
    }

    @Query("select * from notes where documentId = :documentId")
    suspend fun note(documentId: Long): NoteRow?

    /** The pages of [repositoryId]'s listed notes saved on this phone that link to any note. */
    @Query("""select n.documentId, n.html from notes n join documents d on d.id = n.documentId
              where d.repositoryId = :repositoryId and n.html like '%/note-link?%'""")
    suspend fun pagesWithNoteLinks(repositoryId: Long): List<SavedPage>

    @Query("select count(*) from notes n join documents d on d.id = n.documentId where d.repositoryId = :repositoryId")
    suspend fun savedNoteCount(repositoryId: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveNote(row: NoteRow)

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
              where a.exactText like :pattern escape '\' or a.note like :pattern escape '\'
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

    /** Read notes whose current version in the saved note lists is not the one last read; most recently changed first. */
    @Query("""select d.id as documentId, d.title, d.path, d.blobSha, d.changedAt, r.lastReadBlobSha
              from reading_states r join documents d on d.id = r.documentId
              where d.blobSha != r.lastReadBlobSha order by d.changedAt is null, d.changedAt desc, r.lastReadAt desc""")
    fun updatedSinceRead(): Flow<List<ChangedNote>>

    /** The most recently changed notes other than those updated since read: never read, or read in their current version. */
    @Query("""select d.id as documentId, d.title, d.path, d.blobSha, d.changedAt, r.lastReadBlobSha
              from documents d left join reading_states r on r.documentId = d.id
              where d.changedAt is not null and (r.lastReadBlobSha is null or r.lastReadBlobSha = d.blobSha)
              order by d.changedAt desc limit :limit""")
    fun recentlyChanged(limit: Int): Flow<List<ChangedNote>>

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
    version = 6,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5), AutoMigration(from = 5, to = 6)],
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
