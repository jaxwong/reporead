package com.reporead.android.data

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
                    "insert into documents(id, repositoryId, path, title, blobSha, changedAt) values (1, 7, 'a.md', 'a', ?, null)",
                    arrayOf<Any>(sha),
                )
                db.execSQL(
                    """insert into reading_states(documentId, title, path, lastReadBlobSha, progressPercent, anchorJson, lastReadAt,
                    pending, deleted) values (1, 'copy', 'copy.md', ?, 40, '{}', 5, 1, 0)""",
                    arrayOf<Any>(sha),
                )
                db.execSQL(
                    "insert into bookmarks(documentId, title, path, bookmarked, pending, changedAt, deleted) values (1, 'copy', 'copy.md', 1, 1, 6, 0)"
                )
                db.execSQL(
                    """insert into annotations(mutationId, serverId, documentId, sourceBlobSha, blockId, startOffset, endOffset,
                    exactText, note, version, createdAt, state, cachedTitle, cachedPath, cachedDeleted) values
                    ('unsent', null, 1, ?, 'b1', 0, 4, 'text', 'mine', 1, 7, 'UNSENT', 'copy', 'copy.md', 0)""",
                    arrayOf<Any>(sha),
                )
            },
        ) { dao ->
            assertEquals(false, dao.document(1)!!.deleted)
            assertEquals(listOf(ReadingRow(1, sha, 40, "{}", 5, state = SyncState.WAITING)), dao.waitingReading())
            assertEquals(
                listOf(BookmarkRow(1, bookmarked = true, pending = true, changedAt = 6)),
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
