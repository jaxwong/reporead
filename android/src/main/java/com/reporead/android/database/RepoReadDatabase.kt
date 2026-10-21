package com.reporead.android.database

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
import org.jsoup.Jsoup

private const val MIGRATION_HTML_CHUNK_CHARS = 65_536

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
        val links = mutableListOf<NoteLinkEntity>()
        db.query("SELECT documentId, html FROM notes WHERE html LIKE '%/note-link?%'").use { pages ->
            while (pages.moveToNext()) {
                noteLinkQueries(pages.getString(1)).forEachIndexed { position, query ->
                    links += NoteLinkEntity(pages.getLong(0), position, query)
                }
            }
        }
        for (link in links) {
            db.execSQL(
                "INSERT INTO note_links (documentId, position, query) VALUES (?, ?, ?)",
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
            db.execSQL("ALTER TABLE annotations ADD COLUMN state TEXT NOT NULL DEFAULT 'ACKNOWLEDGED'")
            db.execSQL(
                """UPDATE annotations SET state = CASE WHEN deleting THEN 'DELETING' WHEN rejection IS NOT NULL THEN 'REFUSED'
                      WHEN pending THEN 'UNSENT' ELSE 'ACKNOWLEDGED' END"""
            )
            db.execSQL("ALTER TABLE annotations DROP COLUMN pending")
            db.execSQL("ALTER TABLE annotations DROP COLUMN deleting")
        }
    }

/** v15: change summaries were retired; no user-owned row is removed. */
@DeleteTable(tableName = "change_baselines") class DropChangeBaselines : AutoMigrationSpec

/** v16: retire pages proven too large for the Pixel's 2 MiB CursorWindow, without loading them into one. */
class RetireUnreadablePages : AutoMigrationSpec {
    override fun onPostMigrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "DELETE FROM notes WHERE length(CAST(html AS blob)) + coalesce(length(CAST(searchText AS blob)), 0) >= 2097152"
        )
        // Room enables foreign keys after migration, so the page deletion cannot cascade here.
        db.execSQL("DELETE FROM note_links WHERE documentId NOT IN (SELECT documentId FROM notes)")
    }
}

/** v17: replace ambiguous flattened search text with the canonical block identities already present in saved HTML. */
@DeleteColumn(tableName = "notes", columnName = "searchText")
class StructureSearchBlocks : AutoMigrationSpec {
    override fun onPostMigrate(db: SupportSQLiteDatabase) {
        val pages = mutableListOf<Pair<Long, Int>>()
        db.query("SELECT documentId, length(html) FROM notes").use { rows ->
            while (rows.moveToNext()) pages += rows.getLong(0) to rows.getInt(1)
        }
        for ((documentId, length) in pages) {
            // v16 retained only pages below 2 MiB; 64 Ki-character slices therefore bound this to 32 reads per page
            // while keeping every CursorWindow far below the device limit.
            val html =
                buildString(length) {
                    var start = 1
                    while (start <= length) {
                        db.query(
                                "SELECT substr(html, ?, ?) FROM notes WHERE documentId = ?",
                                arrayOf<Any>(start, MIGRATION_HTML_CHUNK_CHARS, documentId),
                            )
                            .use { chunk ->
                                check(chunk.moveToFirst()) {
                                    "Missing saved documentId=$documentId during v17 migration"
                                }
                                append(chunk.getString(0))
                            }
                        start += MIGRATION_HTML_CHUNK_CHARS
                    }
                }
            Jsoup.parse(html).select("[data-block-id][data-anchor-text]").forEachIndexed { position, block ->
                db.execSQL(
                    "INSERT INTO note_search_blocks (documentId, blockId, position, text) VALUES (?, ?, ?, ?)",
                    arrayOf<Any>(
                        documentId,
                        block.attr("data-block-id"),
                        position,
                        block.attr("data-anchor-text"),
                    ),
                )
            }
        }
    }
}

/** v18: a card is never confirmed in a version, so the version it was last confirmed in is gone (ADR-13). */
@DeleteColumn(tableName = "annotations", columnName = "checkedBlobSha") class DropCardConfirmation : AutoMigrationSpec

@Database(
    entities =
        [
            RepositoryEntity::class,
            DocumentEntity::class,
            SavedPageEntity::class,
            NoteSearchBlockEntity::class,
            NoteLinkEntity::class,
            ReadingStateEntity::class,
            BookmarkEntity::class,
            AnnotationEntity::class,
            ReviewEntity::class,
            ReviewLimitEntity::class,
        ],
    version = 18,
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
            AutoMigration(from = 16, to = 17, spec = StructureSearchBlocks::class),
            AutoMigration(from = 17, to = 18, spec = DropCardConfirmation::class),
        ],
)
abstract class RepoReadDatabase : RoomDatabase() {
    abstract fun dao(): RepoReadDao

    companion object {
        /** The database file [name] with every migration: the automatic ones above and the hand-written ones here. */
        fun open(context: Context, name: String): RepoReadDatabase =
            Room.databaseBuilder(context, RepoReadDatabase::class.java, name)
                .addMigrations(ANNOTATION_STATE_10_11)
                .build()
    }
}
