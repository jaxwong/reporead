package com.reporead.android.library

import com.reporead.android.data.DocumentRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderTest {
    private fun note(id: Long, path: String) =
        DocumentRow(id, 1, path, path.substringAfterLast('/').removeSuffix(".md"), "a".repeat(40))

    private val documents =
        listOf(
            note(1, "README.md"),
            note(2, "backend/spring.md"),
            note(3, "backend/data/postgres.md"),
            note(4, "algorithms/graphs.md"),
            note(5, "Backend/upper.md"),
        )

    @Test
    fun rootListsTopLevelFoldersAndNotes() {
        val (folders, notes) = children(documents, "")
        assertEquals(listOf("Backend", "algorithms", "backend"), folders)
        assertEquals(listOf(1L), notes.map { it.id })
    }

    @Test
    fun nestedFolderListsOnlyItsDirectChildren() {
        val (folders, notes) = children(documents, "backend")
        assertEquals(listOf("data"), folders)
        assertEquals(listOf(2L), notes.map { it.id })
    }

    @Test
    fun folderNamePrefixDoesNotMatchASiblingFolder() {
        val (folders, notes) = children(listOf(note(1, "back/a.md"), note(2, "backend/b.md")), "back")
        assertEquals(emptyList<String>(), folders)
        assertEquals(listOf(1L), notes.map { it.id })
    }

    @Test
    fun emptyLibraryAndMissingFolderAreEmpty() {
        assertEquals(emptyList<String>() to emptyList<DocumentRow>(), children(emptyList(), ""))
        assertEquals(emptyList<String>() to emptyList<DocumentRow>(), children(documents, "missing"))
    }

    @Test
    fun folderFilterUsesPathBoundariesAndIncludesDescendants() {
        assertTrue(inFolder("core/db/a.md", " core/ "))
        assertTrue(inFolder("core/db/a.md", "/core/db/"))
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
