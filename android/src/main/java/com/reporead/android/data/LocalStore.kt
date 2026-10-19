package com.reporead.android.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.DeleteColumn
import androidx.room.DeleteTable
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.AutoMigrationSpec
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/*
 * The phone's offline projection of backend state, plus pending local changes. The backend owns durable state;
 * rows here are replaced from complete server responses and never from partial ones.
 */

/** v9: a saved note no longer records the commit it was fetched at, which nothing read. */
@DeleteColumn(tableName = "notes", columnName = "commitSha") class DropNoteCommit : AutoMigrationSpec

/**
 * v13: a note's title, path, current version and removal live only in its documents row, which now keeps removed notes
 * too. Reading, bookmark and annotation rows kept copies of them, and screens chose between the copies.
 */
@DeleteColumn.Entries(
    DeleteColumn(tableName = "reading_states", columnName = "title"),
    DeleteColumn(tableName = "reading_states", columnName = "path"),
    DeleteColumn(tableName = "reading_states", columnName = "deleted"),
    DeleteColumn(tableName = "bookmarks", columnName = "title"),
    DeleteColumn(tableName = "bookmarks", columnName = "path"),
    DeleteColumn(tableName = "bookmarks", columnName = "deleted"),
    DeleteColumn(tableName = "annotations", columnName = "cachedTitle"),
    DeleteColumn(tableName = "annotations", columnName = "cachedPath"),
    DeleteColumn(tableName = "annotations", columnName = "cachedCurrentBlobSha"),
    DeleteColumn(tableName = "annotations", columnName = "cachedDeleted"),
)
class DocumentsOwnNoteFacts : AutoMigrationSpec

/** v12: a bookmark no longer records the version bookmarked, which nothing read; a bookmark is of the note. */
@DeleteColumn(tableName = "bookmarks", columnName = "sourceBlobSha") class DropBookmarkVersion : AutoMigrationSpec

/**
 * v10: the note links of each saved page get their own table, filled here once from the pages already saved; Linked
 * from no longer reads every page (ADR-19). A cursor holds a few pages at a time; only links are kept.
 */
class KeepNoteLinks : AutoMigrationSpec {
    override fun onPostMigrate(db: SupportSQLiteDatabase) {
        val links = mutableListOf<NoteLinkRow>()
        db.query("select documentId, html from notes where html like '%/note-link?%'").use { pages ->
            while (pages.moveToNext()) {
                noteLinkQueries(pages.getString(1)).forEachIndexed { position, query ->
                    links += NoteLinkRow(pages.getLong(0), position, query)
                }
            }
        }
        for (link in links) {
            db.execSQL(
                "insert into note_links (documentId, position, query) values (?, ?, ?)",
                arrayOf<Any>(link.documentId, link.position, link.query),
            )
        }
    }
}

/**
 * v11: a highlight's standing with the server is one [AnnotationState] instead of the pending, rejection and deleting
 * flags, five of whose eight combinations were valid. Hand-written: each row's state is computed from its old flags.
 */
val ANNOTATION_STATE_10_11 =
    object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("alter table annotations add column state TEXT NOT NULL DEFAULT 'ACKNOWLEDGED'")
            db.execSQL(
                """update annotations set state = case when deleting then 'DELETING' when rejection is not null then 'REFUSED'
                      when pending then 'UNSENT' else 'ACKNOWLEDGED' end"""
            )
            db.execSQL("alter table annotations drop column pending")
            db.execSQL("alter table annotations drop column deleting")
        }
    }

/** v15: change summaries were retired; no user-owned row is removed. */
@DeleteTable(tableName = "change_baselines") class DropChangeBaselines : AutoMigrationSpec

/** v16: retire pages proven too large for the Pixel's 2 MiB CursorWindow, without loading them into one. */
class RetireUnreadablePages : AutoMigrationSpec {
    override fun onPostMigrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "delete from notes where length(cast(html as blob)) + coalesce(length(cast(searchText as blob)), 0) >= 2097152"
        )
        // Room enables foreign keys after migration, so the page deletion cannot cascade here.
        db.execSQL("delete from note_links where documentId not in (select documentId from notes)")
    }
}

@Database(
    entities =
        [
            RepositoryRow::class,
            DocumentRow::class,
            NoteRow::class,
            NoteLinkRow::class,
            ReadingRow::class,
            BookmarkRow::class,
            AnnotationRow::class,
            ReviewRow::class,
            ReviewLimitRow::class,
        ],
    version = 16,
    autoMigrations =
        [
            AutoMigration(from = 1, to = 2),
            AutoMigration(from = 2, to = 3),
            AutoMigration(from = 3, to = 4),
            AutoMigration(from = 4, to = 5),
            AutoMigration(from = 5, to = 6),
            AutoMigration(from = 6, to = 7),
            AutoMigration(from = 7, to = 8),
            AutoMigration(from = 8, to = 9, spec = DropNoteCommit::class),
            AutoMigration(from = 9, to = 10, spec = KeepNoteLinks::class),
            AutoMigration(from = 11, to = 12, spec = DropBookmarkVersion::class),
            AutoMigration(from = 12, to = 13, spec = DocumentsOwnNoteFacts::class),
            AutoMigration(from = 13, to = 14),
            AutoMigration(from = 14, to = 15, spec = DropChangeBaselines::class),
            AutoMigration(from = 15, to = 16, spec = RetireUnreadablePages::class),
        ],
)
abstract class LocalStore : RoomDatabase() {
    abstract fun library(): LibraryDao

    companion object {
        /** The database file [name] with every migration: the automatic ones above and the hand-written ones here. */
        fun open(context: Context, name: String): LocalStore =
            Room.databaseBuilder(context, LocalStore::class.java, name).addMigrations(ANNOTATION_STATE_10_11).build()
    }
}
