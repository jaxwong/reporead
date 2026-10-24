package com.reporead.android.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Builds a test-only v6 database from the exported schema, then opens it with the current schema, which runs every
 * production migration from v6 on: v7 adds the review tables, v9 rebuilds notes without commitSha.
 */
@RunWith(AndroidJUnit4::class)
class ReviewMigrationTest {
    @Test
    fun migratingPreservesSavedNotesAndUnsentHighlightsAndCreatesEmptyReviewTables() = runBlocking {
        withMigratedStore(
            6,
            fill = { db ->
                db.execSQL(
                    "INSERT INTO notes(documentId, blobSha, commitSha, path, title, html, fetchedAt, renderFormat) VALUES (1, 'sha', 'commit', 'a.md', 'a', '<html/>', 0, 3)"
                )
                db.execSQL(
                    """INSERT INTO annotations(mutationId, serverId, documentId, sourceBlobSha, blockId, startOffset, endOffset,
                exactText, note, version, createdAt, pending, rejection) VALUES ('offline', NULL, 1, 'sha', 'b1', 0, 4, 'text', 'mine', 1, 0, 1, NULL)"""
                )
            },
        ) { dao ->
            assertEquals("<html/>", dao.savedPage(1)!!.html)
            val highlight = checkNotNull(dao.annotation("offline"))
            assertEquals(AnnotationType.HIGHLIGHT, highlight.type)
            assertEquals("mine", highlight.note)
            assertEquals(AnnotationState.UNSENT, highlight.state)
            assertNull(highlight.question)
            assertTrue(dao.reviews().first().isEmpty())
        }
    }
}
