package com.reporead.android.reader

import com.reporead.android.data.DocumentRow
import org.junit.Assert.assertEquals
import org.junit.Test

class LinksTest {
    private val notes = listOf("stack.md", "algorithms/Stack.md", "algorithms/queue.md", "backend/queue.md", "backend/deep/Queue.md", "a b/c d.md")
        .mapIndexed { index, path -> DocumentRow(index.toLong(), 7, path, path.substringAfterLast('/').removeSuffix(".md"), "a".repeat(40)) }

    private fun paths(target: String?, path: String? = null, from: String = "notes/x.md") =
        resolveNoteLink(target, path, from, notes).map { it.path }

    @Test fun aBareNameMatchesFileNamesIgnoringCaseAndPrefersTheLinkingNotesFolder() {
        assertEquals(listOf("algorithms/Stack.md", "stack.md"), paths("stack"))
        assertEquals(listOf("algorithms/Stack.md"), paths("Stack", from = "algorithms/sort.md"))
        assertEquals(listOf("stack.md"), paths("stack.md", from = "root.md"))
        assertEquals(listOf("c d"), resolveNoteLink("C D", null, "x.md", notes).map { it.title })
    }

    @Test fun aNameWithAFolderMatchesTheEndOfAPath() {
        assertEquals(listOf("backend/deep/Queue.md"), paths("deep/queue"))
        assertEquals(listOf("algorithms/queue.md"), paths("algorithms/queue"))
        assertEquals(listOf("algorithms/queue.md", "backend/deep/Queue.md", "backend/queue.md"), paths("queue"))
    }

    @Test fun aRelativeMarkdownLinkMatchesItsExactPathAndNothingMatchesNothing() {
        assertEquals(listOf("backend/queue.md"), paths(null, path = "backend/queue.md"))
        assertEquals(emptyList<String>(), paths(null, path = "backend/Queue.md"))
        assertEquals(emptyList<String>(), paths("missing"))
        assertEquals(emptyList<String>(), paths(" "))
        assertEquals(emptyList<String>(), paths(null))
    }
}
