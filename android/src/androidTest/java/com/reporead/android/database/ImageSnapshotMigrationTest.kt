package com.reporead.android.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** v13→v14 preserves offline/user data without inventing a snapshot identity for old images. */
@RunWith(AndroidJUnit4::class)
class ImageSnapshotMigrationTest {
    @Test
    fun legacyPagesKeepTheirTextLinksAndPendingChangesButHaveNoImageIdentity() = runBlocking {
        val sha = "a".repeat(40)
        withMigratedStore(
            13,
            fill = { db ->
                db.execSQL(
                    "INSERT INTO documents(id, repositoryId, path, title, blobSha, changedAt, deleted) VALUES (1, 7, 'a.md', 'a', ?, NULL, 0)",
                    arrayOf(sha),
                )
                db.execSQL(
                    """INSERT INTO notes(documentId, blobSha, path, title, html, fetchedAt, searchText, renderFormat)
                    VALUES (1, ?, 'a.md', 'a', '<main><p data-block-id="b0" data-anchor-text="kept">kept</p></main>', 0, 'kept', 3)""",
                    arrayOf(sha),
                )
                db.execSQL("INSERT INTO note_links(documentId, position, query) VALUES (1, 0, 'path=b.md')")
                db.execSQL(
                    "INSERT INTO reading_states(documentId, lastReadBlobSha, progressPercent, anchorJson, lastReadAt, pending) VALUES (1, ?, 40, '{}', 5, 1)",
                    arrayOf(sha),
                )
                db.execSQL("INSERT INTO bookmarks(documentId, bookmarked, pending, changedAt) VALUES (1, 1, 1, 6)")
                db.execSQL(
                    """INSERT INTO annotations(mutationId, serverId, documentId, sourceBlobSha, blockId, startOffset,
                endOffset, exactText, note, version, createdAt, state) VALUES ('unsent', NULL, 1, ?, 'b1', 0, 4, 'text', 'mine', 1, 7, 'UNSENT')""",
                    arrayOf(sha),
                )
            },
        ) { dao ->
            assertEquals(
                SavedPageEntity(
                    1,
                    sha,
                    "a.md",
                    "a",
                    """<main><p data-block-id="b0" data-anchor-text="kept">kept</p></main>""",
                    0,
                    3,
                ),
                dao.savedPage(1),
            )
            assertEquals("b0", dao.searchSavedPageText("%kept%", 1).single().blockId)
            assertNull(dao.allSavedVersions().single().snapshotCommitSha)
            assertEquals(listOf(NoteLinkEntity(1, 0, "path=b.md")), dao.noteLinks(7))
            assertEquals(1, dao.pendingReading().size)
            assertEquals(1, dao.pendingBookmarks().size)
            assertEquals("mine", dao.waitingAnnotations().single().note)
        }
    }
}
