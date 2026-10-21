package com.reporead.android.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** The v17→v18 migration drops the version a card was confirmed in and keeps the card and its grades. */
@RunWith(AndroidJUnit4::class)
class CardConfirmationMigrationTest {
    @Test
    fun aCardKeepsEverythingButItsConfirmation() = runBlocking {
        val sha = "a".repeat(40)
        withMigratedStore(
            17,
            fill = { db ->
                db.execSQL(
                    """insert into annotations(mutationId, serverId, documentId, sourceBlobSha, blockId, startOffset, endOffset,
                    exactText, note, version, createdAt, state, type, question, checkedBlobSha)
                    values ('card', 3, 1, ?, 'b1', 0, 4, 'line', null, 2, 5, 'ACKNOWLEDGED', 'CARD', 'Test-only question?', ?)""",
                    arrayOf<Any>(sha, "b".repeat(40)),
                )
                db.execSQL(
                    "insert into review_log(mutationId, cardMutationId, grade, reviewedAt, blobSha, pending) values ('grade', 'card', 4, 6, ?, 1)",
                    arrayOf<Any>(sha),
                )
            },
        ) { dao ->
            assertEquals(
                AnnotationRow(
                    "card",
                    3,
                    1,
                    sha,
                    "b1",
                    0,
                    4,
                    "line",
                    null,
                    2,
                    5,
                    AnnotationState.ACKNOWLEDGED,
                    type = AnnotationType.CARD,
                    question = "Test-only question?",
                ),
                dao.annotation("card"),
            )
            assertEquals(listOf(ReviewRow("grade", "card", 4, 6, sha, pending = true)), dao.reviews().first())
        }
    }
}
