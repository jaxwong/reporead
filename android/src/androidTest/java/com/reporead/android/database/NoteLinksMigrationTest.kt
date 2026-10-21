package com.reporead.android.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** The v9→v10 migration fills note_links from the pages already saved, so Linked from works without re-saving them. */
@RunWith(AndroidJUnit4::class)
class NoteLinksMigrationTest {
    @Test
    fun pagesSavedBeforeTheUpdateHaveTheirNoteLinksAfterIt() = runBlocking {
        val linking =
            """<main><a href="/note-link?target=b">b</a><a href="/note-link?heading=Top">top</a><a href="/note-link?path=c.md&amp;heading=x">c</a></main>"""
        withMigratedStore(
            9,
            fill = { db ->
                db.execSQL(
                    "insert into documents(id, repositoryId, path, title, blobSha) values (1, 7, 'a.md', 'a', 'sha'), (2, 7, 'b.md', 'b', 'sha')"
                )
                db.execSQL(
                    "insert into notes(documentId, blobSha, path, title, html, fetchedAt, renderFormat) values (1, 'sha', 'a.md', 'a', ?, 0, 3)",
                    arrayOf(linking),
                )
                db.execSQL(
                    "insert into notes(documentId, blobSha, path, title, html, fetchedAt, renderFormat) values (2, 'sha', 'b.md', 'b', '<main>none</main>', 0, 3)"
                )
            },
        ) { dao ->
            assertEquals(
                listOf(
                    NoteLinkEntity(1, 0, "target=b"),
                    NoteLinkEntity(1, 1, "heading=Top"),
                    NoteLinkEntity(1, 2, "path=c.md&heading=x"),
                ),
                dao.noteLinks(7),
            )
            assertNotNull("the page itself is kept", dao.note(1))
        }
    }
}
