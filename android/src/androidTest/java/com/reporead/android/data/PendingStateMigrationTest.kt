package com.reporead.android.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The v13→v14 migration gives grades and reading positions the state their old columns meant, and a highlight waiting
 * to be sent the same word.
 */
@RunWith(AndroidJUnit4::class)
class PendingStateMigrationTest {
    @Test
    fun everyOldCombinationBecomesItsState() = runBlocking {
        withMigratedStore(
            13,
            fill = { db ->
                for ((mutationId, state) in listOf("unsent" to "UNSENT", "acknowledged" to "ACKNOWLEDGED")) {
                    db.execSQL(
                        """insert into annotations(mutationId, serverId, documentId, sourceBlobSha, blockId, startOffset, endOffset,
                        exactText, note, version, createdAt, state) values (?, null, 1, 'sha', 'b1', 0, 4, 'text', null, 1, 0, ?)""",
                        arrayOf<Any>(mutationId, state),
                    )
                }
                for ((mutationId, pending, rejection) in
                    listOf(
                        Triple("waiting", 1, null),
                        Triple("refused", 1, "Test-only refusal"),
                        Triple("acknowledged", 0, null),
                        // Meant nothing before; a rejection says the server refused it.
                        Triple("refused-not-pending", 0, "Test-only refusal"),
                    )) {
                    db.execSQL(
                        """insert into review_log(mutationId, cardMutationId, grade, reviewedAt, blobSha, pending, rejection)
                        values (?, 'card', 4, 1, 'sha', ?, ?)""",
                        arrayOf<Any?>(mutationId, pending, rejection),
                    )
                }
                for ((documentId, pending) in listOf(1 to 1, 2 to 0)) {
                    db.execSQL(
                        """insert into reading_states(documentId, lastReadBlobSha, progressPercent, anchorJson, lastReadAt, pending)
                        values (?, 'sha', 40, '{}', 5, ?)""",
                        arrayOf<Any>(documentId, pending),
                    )
                }
            },
        ) { dao ->
            assertEquals(AnnotationState.WAITING, dao.annotation("unsent")!!.state)
            assertEquals(AnnotationState.ACKNOWLEDGED, dao.annotation("acknowledged")!!.state)
            val grades = dao.reviews().first().associateBy { it.mutationId }
            assertEquals(SyncState.WAITING, grades.getValue("waiting").state)
            assertEquals(SyncState.REFUSED, grades.getValue("refused").state)
            assertEquals("Test-only refusal", grades.getValue("refused").rejection)
            assertEquals(SyncState.ACKNOWLEDGED, grades.getValue("acknowledged").state)
            assertEquals(SyncState.REFUSED, grades.getValue("refused-not-pending").state)
            assertEquals(SyncState.WAITING, dao.reading(1)!!.state)
            assertEquals(SyncState.ACKNOWLEDGED, dao.reading(2)!!.state)
            assertNull(dao.reading(1)!!.rejection)
        }
    }
}
