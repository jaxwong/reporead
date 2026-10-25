package com.reporead.android.database

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The v19→v20 migration renames `notes` to `saved_pages` and `note_search_blocks` to `search_blocks`, keeping every row
 * and the cascades from the blocks and note links to their page.
 */
@RunWith(AndroidJUnit4::class)
class SavedPagesMigrationTest {
    @Test
    fun savedPagesKeepTheirRowsBlocksAndLinksUnderTheNewNames() = runBlocking {
        val sha = "a".repeat(40)
        lateinit var path: String
        withMigratedStore(
            19,
            fill = { db ->
                path = db.path
                db.execSQL("INSERT INTO repositories VALUES (1, 'o/r', 1, ?)", arrayOf<Any>("c".repeat(40)))
                db.execSQL("INSERT INTO documents VALUES (1, 1, 'a.md', 'a', ?, NULL, 0)", arrayOf<Any>(sha))
                db.execSQL(
                    "INSERT INTO notes(documentId, blobSha, path, title, html, fetchedAt, renderFormat, snapshotCommitSha) VALUES (1, ?, 'a.md', 'a', '<main/>', 5, 4, NULL)",
                    arrayOf<Any>(sha),
                )
                db.execSQL("INSERT INTO note_search_blocks VALUES (1, 'b0', 0, 'Needle in the saved page')")
                db.execSQL("INSERT INTO note_links VALUES (1, 0, 'path=b.md')")
            },
        ) { dao ->
            assertEquals(SavedPageEntity(1, sha, "a.md", "a", "<main/>", 5, 4, null), dao.savedPage(1))
            assertEquals(listOf("b0"), dao.searchSavedPageText("%Needle%", 10).map { it.blockId })
            assertEquals(listOf(NoteLinkEntity(1, 0, "path=b.md")), dao.noteLinks(1))
            SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                assertEquals(currentSchemaVersion(), db.version)
                db.rawQuery(
                        "SELECT name FROM sqlite_master WHERE type = 'table' AND name IN ('notes', 'note_search_blocks')",
                        null,
                    )
                    .use { assertFalse(it.moveToFirst()) }
                db.rawQuery("SELECT count(*) FROM saved_pages", null).use {
                    assertTrue(it.moveToFirst())
                    assertEquals(1, it.getInt(0))
                }
            }
            // The cascades followed the rename: a deleted page takes its blocks and links with it.
            dao.deleteSavedPages(listOf(1))
            assertTrue(dao.searchSavedPageText("%Needle%", 10).isEmpty())
            assertTrue(dao.noteLinks(1).isEmpty())
        }
    }
}
