package com.reporead.android.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The v12→v13 migration drops the copies of a note's title, path and removal from reading, bookmark and annotation rows
 * and keeps the rows; saved notes start as listed until the next sync says otherwise.
 */
@RunWith(AndroidJUnit4::class)
class NoteFactsMigrationTest {
    @Test
    fun pendingRowsKeepEverythingButTheirCopies() = runBlocking {
        val sha = "a".repeat(40)
        withMigratedStore(
            12,
            fill = { db ->
                db.execSQL(
                    "INSERT INTO documents(id, repositoryId, path, title, blobSha, changedAt) VALUES (1, 7, 'a.md', 'a', ?, NULL)",
                    arrayOf<Any>(sha),
                )
                db.execSQL(
                    """INSERT INTO reading_states(documentId, title, path, lastReadBlobSha, progressPercent, anchorJson, lastReadAt,
                    pending, deleted) VALUES (1, 'copy', 'copy.md', ?, 40, '{}', 5, 1, 0)""",
                    arrayOf<Any>(sha),
                )
                db.execSQL(
                    "INSERT INTO bookmarks(documentId, title, path, bookmarked, pending, changedAt, deleted) VALUES (1, 'copy', 'copy.md', 1, 1, 6, 0)"
                )
                db.execSQL(
                    """INSERT INTO annotations(mutationId, serverId, documentId, sourceBlobSha, blockId, startOffset, endOffset,
                    exactText, note, version, createdAt, state, cachedTitle, cachedPath, cachedDeleted) VALUES
                    ('unsent', NULL, 1, ?, 'b1', 0, 4, 'text', 'mine', 1, 7, 'UNSENT', 'copy', 'copy.md', 0)""",
                    arrayOf<Any>(sha),
                )
            },
        ) { dao ->
            assertEquals(false, dao.document(1)!!.deleted)
            assertEquals(listOf(ReadingStateEntity(1, sha, 40, "{}", 5, pending = true)), dao.pendingReading())
            assertEquals(
                listOf(BookmarkEntity(1, bookmarked = true, pending = true, changedAt = 6)),
                dao.pendingBookmarks(),
            )
            assertEquals("mine", dao.annotation("unsent")!!.note)
            assertEquals(
                listOf(ReadNote(1, "a", "a.md", deleted = false, progressPercent = 40)),
                dao.recentReading(5).first(),
            )
        }
    }
}
