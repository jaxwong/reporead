package com.reporead.android.data

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Upgrades from v14/v15 retire unreadable pages and structure searchable blocks, never user-owned work. */
@RunWith(AndroidJUnit4::class)
class FeatureRemovalMigrationTest {
    @Test
    fun anEmptyStoreUpgradesWithoutInventingRows() = runBlocking {
        for (version in listOf(14, 15)) {
            withMigratedStore(version, fill = {}) { dao ->
                assertTrue(dao.allSavedVersions().isEmpty())
                assertEquals(0, dao.waitingCount().first())
            }
        }
    }

    @Test
    fun retiringSummariesAndUnreadablePagesKeepsReadablePagesAndUserOwnedRows() = runBlocking {
        for (version in listOf(14, 15)) {
            val sha = "a".repeat(40)
            val snapshot = "b".repeat(40)
            lateinit var path: String
            withMigratedStore(
                version,
                fill = { db ->
                    path = db.path
                    db.execSQL("insert into repositories values (7, 'test/notes', 6, ?)", arrayOf(snapshot))
                    for (id in 1..6) {
                        db.execSQL(
                            "insert into documents values (?, 7, 'a.md', 'a', ?, null, 0)",
                            arrayOf<Any>(id, sha),
                        )
                        db.execSQL(
                            "insert into reading_states values (?, ?, 40, '{}', 5, ?)",
                            arrayOf<Any>(id, sha, if (id == 1) 1 else 0),
                        )
                        db.execSQL("insert into bookmarks values (?, 1, ?, 6)", arrayOf(id, if (id == 1) 1 else 0))
                        if (version == 14)
                            db.execSQL("insert into change_baselines values (?, ?)", arrayOf<Any>(id, "c".repeat(40)))
                    }
                    db.execSQL(
                        """insert into notes values (1, ?, 'a.md', 'a',
                        '<main><p data-block-id="b0" data-anchor-text="kept&#10;line">kept</p></main>',
                        0, 'ambiguous flattened text', 3, ?)""",
                        arrayOf(sha, snapshot),
                    )
                    db.execSQL("insert into note_links values (1, 0, 'path=b.md')")
                    // The cutoff is the combined UTF-8 byte count, including a null searchText. Do not load these pages
                    // into a CursorWindow: the migration must retire them inside SQLite first.
                    val largeSuffix = """<p data-block-id="b4" data-anchor-text="near limit">near limit</p></main>"""
                    val largeSearchable =
                        "<main>" + "x".repeat(2_097_151 - "<main>".length - largeSuffix.length) + largeSuffix
                    for ((id, html, text) in
                        listOf(
                            Triple(2, "x".repeat(1_048_576), "y".repeat(1_048_576)),
                            Triple(3, "x".repeat(2_097_153), null),
                            Triple(4, largeSearchable, null),
                            Triple(5, "é".repeat(1_048_576), null),
                            Triple(6, "", "é".repeat(1_048_576)),
                        )) {
                        db.execSQL(
                            "insert into notes values (?, ?, 'a.md', 'a', ?, 0, ?, 3, ?)",
                            arrayOf<Any?>(id, sha, html, text, snapshot),
                        )
                        db.execSQL("insert into note_links values (?, 0, 'path=gone.md')", arrayOf(id))
                    }
                    db.execSQL(
                        """insert into annotations(mutationId, serverId, documentId, sourceBlobSha, blockId, startOffset,
                    endOffset, exactText, note, version, createdAt, state, type, question, checkedBlobSha)
                    values ('card', null, 2, ?, 'b1', 0, 4, 'text', 'mine', 1, 7, 'UNSENT', 'CARD', 'Why?', ?)""",
                        arrayOf(sha, sha),
                    )
                    db.execSQL(
                        """insert into annotations(mutationId, serverId, documentId, sourceBlobSha, blockId, startOffset,
                    endOffset, exactText, note, version, createdAt, state)
                    values ('highlight', 9, 2, ?, 'b1', 0, 4, 'text', 'saved', 1, 7, 'ACKNOWLEDGED')""",
                        arrayOf(sha),
                    )
                    db.execSQL("insert into review_log values ('grade', 'card', 2, 8, ?, 1, null)", arrayOf(sha))
                    db.execSQL("insert into review_limits values (1, 40)")
                },
            ) { dao ->
                assertEquals(listOf(RepositoryRow(7, "test/notes", 6, snapshot)), dao.repositories().first())
                assertEquals(6, dao.listedDocuments(7).size)
                assertEquals(
                    "upgrade from v$version",
                    setOf(1L, 4L),
                    dao.allSavedVersions().map { it.documentId }.toSet(),
                )
                assertNull(dao.note(2))
                assertNull(dao.note(3))
                assertNull(dao.note(5))
                assertNull(dao.note(6))
                assertEquals(
                    NoteRow(
                        1,
                        sha,
                        "a.md",
                        "a",
                        """<main><p data-block-id="b0" data-anchor-text="kept&#10;line">kept</p></main>""",
                        0,
                        3,
                        snapshot,
                    ),
                    dao.note(1),
                )
                assertEquals("kept\nline", dao.searchNoteText("%kept%", 1).single().text)
                assertEquals("b4", dao.searchNoteText("%near limit%", 1).single().blockId)
                assertEquals(setOf(1L, 4L), dao.noteLinks(7).map { it.documentId }.toSet())
                assertTrue(dao.noteLinks(7).contains(NoteLinkRow(1, 0, "path=b.md")))
                assertEquals(ReadingRow(1, sha, 40, "{}", 5, pending = true), dao.reading(1))
                assertEquals(ReadingRow(2, sha, 40, "{}", 5, pending = false), dao.reading(2))
                assertEquals(BookmarkRow(1, true, true, 6), dao.bookmark(1).first())
                assertEquals(BookmarkRow(2, true, false, 6), dao.bookmark(2).first())
                assertEquals("mine", dao.annotation("card")!!.note)
                assertEquals(2L, dao.annotation("card")!!.documentId)
                assertEquals(AnnotationType.CARD, dao.annotation("card")!!.type)
                assertEquals(AnnotationState.UNSENT, dao.annotation("card")!!.state)
                assertEquals(AnnotationState.ACKNOWLEDGED, dao.annotation("highlight")!!.state)
                assertEquals(listOf(ReviewRow("grade", "card", 2, 8, sha, pending = true)), dao.reviews().first())
                assertEquals(40, dao.reviewLimit().first())
                SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                    assertEquals(18, db.version)
                    db.rawQuery("pragma foreign_key_check", null).use { assertFalse(it.moveToFirst()) }
                    db.rawQuery("select name from sqlite_master where name = 'change_baselines'", null).use {
                        assertFalse(it.moveToFirst())
                    }
                    db.rawQuery("pragma table_info(notes)", null).use { columns ->
                        val names = mutableSetOf<String>()
                        while (columns.moveToNext()) names += columns.getString(1)
                        assertFalse("searchText column must be removed", "searchText" in names)
                    }
                }
            }
        }
    }
}
