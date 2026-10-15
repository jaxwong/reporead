package com.reporead.android.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Builds a test-only v7 database from the exported schema, then exercises the production v7→v8 migration. */
@RunWith(AndroidJUnit4::class)
class BaselineMigrationTest {
    @Test
    fun migratingKeepsUnsentWorkAndAddsNoDeletionsOrBaselines() = runBlocking {
        withMigratedStore(
            7,
            fill = { db ->
                db.execSQL(
                    "insert into documents(id, repositoryId, path, title, blobSha) values (1, 7, 'a.md', 'a', 'new')"
                )
                db.execSQL(
                    """insert into reading_states(documentId, title, path, lastReadBlobSha, progressPercent, anchorJson, lastReadAt, pending)
                values (1, 'a', 'a.md', 'old', 40, '{}', 1, 1)"""
                )
                db.execSQL(
                    """insert into annotations(mutationId, serverId, documentId, sourceBlobSha, blockId, startOffset, endOffset,
                exactText, note, version, createdAt, pending, rejection) values ('offline', null, 1, 'sha', 'b1', 0, 4, 'text', 'mine', 1, 0, 1, null)"""
                )
            },
        ) { dao ->
            val highlight = checkNotNull(dao.annotation("offline"))
            assertEquals(AnnotationState.UNSENT, highlight.state)
            assertEquals(listOf("offline"), dao.annotations(1).first().map { it.mutationId })
            assertTrue(dao.reading(1)!!.pending)
            assertNull(dao.changeBaseline(1))
            // A note read in an older version is still listed as updated without a baseline.
            assertEquals(
                listOf(1L to "old"),
                dao.updatedSinceRead().first().map { it.documentId to it.lastReadBlobSha },
            )
        }
    }
}
