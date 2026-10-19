package com.reporead.android.data

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** v14→v15 drops only change summaries' local state; saved pages and user-owned work survive. */
@RunWith(AndroidJUnit4::class)
class FeatureRemovalMigrationTest {
    @Test
    fun droppingChangeSummariesKeepsSavedNotesAndUserOwnedRows() = runBlocking {
        val sha = "a".repeat(40)
        val snapshot = "b".repeat(40)
        lateinit var path: String
        withMigratedStore(
            14,
            fill = { db ->
                path = db.path
                db.execSQL("insert into repositories values (7, 'test/notes', 2, ?)", arrayOf(snapshot))
                for (id in 1..2) {
                    db.execSQL(
                        "insert into documents values (?, 7, 'a.md', 'a', ?, null, 0)",
                        arrayOf<Any>(id, sha),
                    )
                    db.execSQL(
                        "insert into reading_states values (?, ?, 40, '{}', 5, ?)",
                        arrayOf<Any>(id, sha, if (id == 1) 1 else 0),
                    )
                    db.execSQL("insert into bookmarks values (?, 1, ?, 6)", arrayOf(id, if (id == 1) 1 else 0))
                    db.execSQL("insert into change_baselines values (?, ?)", arrayOf<Any>(id, "c".repeat(40)))
                }
                db.execSQL(
                    "insert into notes values (1, ?, 'a.md', 'a', '<main>kept</main>', 0, 'kept', 3, ?)",
                    arrayOf(sha, snapshot),
                )
                db.execSQL("insert into note_links values (1, 0, 'path=b.md')")
                db.execSQL(
                    """insert into annotations(mutationId, serverId, documentId, sourceBlobSha, blockId, startOffset,
                    endOffset, exactText, note, version, createdAt, state, type, question, checkedBlobSha)
                    values ('card', null, 1, ?, 'b1', 0, 4, 'text', 'mine', 1, 7, 'UNSENT', 'CARD', 'Why?', ?)""",
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
            assertEquals(listOf(RepositoryRow(7, "test/notes", 2, snapshot)), dao.repositories().first())
            assertEquals(2, dao.listedDocuments(7).size)
            assertEquals(NoteRow(1, sha, "a.md", "a", "<main>kept</main>", 0, "kept", 3, snapshot), dao.note(1))
            assertEquals(listOf(NoteLinkRow(1, 0, "path=b.md")), dao.noteLinks(7))
            assertEquals(ReadingRow(1, sha, 40, "{}", 5, pending = true), dao.reading(1))
            assertEquals(ReadingRow(2, sha, 40, "{}", 5, pending = false), dao.reading(2))
            assertEquals(BookmarkRow(1, true, true, 6), dao.bookmark(1).first())
            assertEquals(BookmarkRow(2, true, false, 6), dao.bookmark(2).first())
            assertEquals("mine", dao.annotation("card")!!.note)
            assertEquals(AnnotationType.CARD, dao.annotation("card")!!.type)
            assertEquals(AnnotationState.UNSENT, dao.annotation("card")!!.state)
            assertEquals(AnnotationState.ACKNOWLEDGED, dao.annotation("highlight")!!.state)
            assertEquals(listOf(ReviewRow("grade", "card", 2, 8, sha, pending = true)), dao.reviews().first())
            assertEquals(40, dao.reviewLimit().first())
            SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                assertEquals(15, db.version)
                db.rawQuery("select name from sqlite_master where name = 'change_baselines'", null).use {
                    assertFalse(it.moveToFirst())
                }
            }
        }
    }
}
