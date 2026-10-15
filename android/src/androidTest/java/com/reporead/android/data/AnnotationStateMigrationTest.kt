package com.reporead.android.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** The v10→v11 migration gives every highlight the state its old pending, rejection and deleting flags meant. */
@RunWith(AndroidJUnit4::class)
class AnnotationStateMigrationTest {
    @Test
    fun eachValidCombinationOfTheOldFlagsBecomesItsState() = runBlocking {
        withMigratedStore(
            10,
            fill = { db ->
                for ((mutationId, serverId, pending, rejection, deleting) in
                    listOf(
                        Flags("unsent", null, 1, null, 0),
                        Flags("refused", null, 1, "Test-only refusal", 0),
                        Flags("deleting-unacknowledged", null, 1, null, 1),
                        Flags("deleting-acknowledged", 9L, 1, null, 1),
                        Flags("acknowledged", 8L, 0, null, 0),
                    )) {
                    db.execSQL(
                        """insert into annotations(mutationId, serverId, documentId, sourceBlobSha, blockId, startOffset, endOffset,
                    exactText, note, version, createdAt, pending, rejection, deleting) values (?, ?, 1, 'sha', 'b1', 0, 4, 'text', null, 1, 0, ?, ?, ?)""",
                        arrayOf<Any?>(mutationId, serverId, pending, rejection, deleting),
                    )
                }
            },
        ) { dao ->
            assertEquals(AnnotationState.UNSENT, dao.annotation("unsent")!!.state)
            val refused = dao.annotation("refused")!!
            assertEquals(AnnotationState.REFUSED, refused.state)
            assertEquals("Test-only refusal", refused.rejection)
            assertEquals(AnnotationState.DELETING, dao.annotation("deleting-unacknowledged")!!.state)
            assertEquals(AnnotationState.DELETING, dao.annotation("deleting-acknowledged")!!.state)
            assertEquals(9L, dao.annotation("deleting-acknowledged")!!.serverId)
            assertEquals(AnnotationState.ACKNOWLEDGED, dao.annotation("acknowledged")!!.state)
        }
    }

    private data class Flags(
        val mutationId: String,
        val serverId: Long?,
        val pending: Int,
        val rejection: String?,
        val deleting: Int,
    )
}
