package com.reporead.android.database

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/** The v18→v19 migration drops the stored review session size and keeps the cards and their grades. */
@RunWith(AndroidJUnit4::class)
class SessionLimitMigrationTest {
    @Test
    fun theSessionSizeTableGoesAndCardsAndGradesStay() = runBlocking {
        val sha = "a".repeat(40)
        lateinit var path: String
        withMigratedStore(
            18,
            fill = { db ->
                path = db.path
                db.execSQL("INSERT INTO review_limits VALUES (1, 40)")
                db.execSQL(
                    """INSERT INTO annotations(mutationId, serverId, documentId, sourceBlobSha, blockId, startOffset, endOffset,
                    exactText, note, version, createdAt, state, type, question)
                    VALUES ('card', 3, 1, ?, 'b1', 0, 4, 'line', NULL, 2, 5, 'ACKNOWLEDGED', 'CARD', 'Test-only question?')""",
                    arrayOf<Any>(sha),
                )
                db.execSQL(
                    "INSERT INTO review_log(mutationId, cardMutationId, grade, reviewedAt, blobSha, pending) VALUES ('grade', 'card', 4, 6, ?, 1)",
                    arrayOf<Any>(sha),
                )
            },
        ) { dao ->
            assertEquals("Test-only question?", dao.annotation("card")?.question)
            assertEquals(listOf(ReviewEntity("grade", "card", 4, 6, sha, pending = true)), dao.reviews().first())
            SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                assertEquals(currentSchemaVersion(), db.version)
                db.rawQuery("SELECT name FROM sqlite_master WHERE name = 'review_limits'", null).use {
                    assertFalse(it.moveToFirst())
                }
            }
        }
    }
}
