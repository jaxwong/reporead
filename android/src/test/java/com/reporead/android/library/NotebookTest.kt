package com.reporead.android.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotebookTest {
    @Test
    fun folderFilterUsesPathBoundariesAndIncludesDescendants() {
        assertTrue(inFolder("core/db/a.md", "core"))
        assertTrue(inFolder("core/db/a.md", "core/db"))
        assertTrue(inFolder("root.md", ""))
        assertFalse(inFolder("core-extra/a.md", "core"))
        assertFalse(inFolder("books/a.md", "core"))
    }

    @Test
    fun ancestorFoldersIncludeEachActualFolderOnce() {
        assertEquals(
            listOf("core", "core/db", "core/db/sql", "core-extra"),
            ancestorFolders(
                listOf("root.md", "core/db/sql/a.md", "core/db/b.md", "core-extra/a.md", "core/db/sql/c.md")
            ),
        )
        assertEquals(emptyList<String>(), ancestorFolders(emptyList()))
        assertEquals(emptyList<String>(), ancestorFolders(listOf("root.md")))
    }
}
