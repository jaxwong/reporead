package com.reporead.android.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** The v11→v12 migration drops the version a bookmark recorded and keeps every bookmark, a pending change too. */
@RunWith(AndroidJUnit4::class)
class BookmarkVersionMigrationTest {
    @Test
    fun aPendingBookmarkKeepsEverythingButItsVersion() = runBlocking {
        withMigratedStore(
            11,
            fill = { db ->
                db.execSQL(
                    """INSERT INTO bookmarks(documentId, title, path, sourceBlobSha, bookmarked, pending, changedAt, deleted)
                    VALUES (1, 'Test-only note', 'a.md', ?, 1, 1, 5, 0)""",
                    arrayOf<Any>("a".repeat(40)),
                )
            },
        ) { dao ->
            assertEquals(
                listOf(BookmarkEntity(1, bookmarked = true, pending = true, changedAt = 5)),
                dao.pendingBookmarks(),
            )
        }
    }
}
