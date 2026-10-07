package com.reporead.android.reader

import com.reporead.android.data.DocumentRow
import com.reporead.android.data.SavedPage
import org.junit.Assert.assertEquals
import org.junit.Test

class BacklinksTest {
    private val notes = listOf("a/target.md", "b/target.md", "a/from.md", "b/other.md", "c/plain.md", "c/self.md")
        .mapIndexed { index, path -> DocumentRow(index.toLong(), 7, path, path.substringAfterLast('/').removeSuffix(".md"), "a".repeat(40)) }
    private fun note(path: String) = notes.single { it.path == path }
    private fun link(query: String) = """<p><a href="/note-link?$query">x</a></p>"""

    @Test fun linksAreDecodedLikeTheReaderAndSameNoteHeadingsAreLeftOut() {
        val html = link("target=T1.%20It's%20Your%20Life") + link("path=core/backend%20engineering/01.md&amp;heading=2-separate") +
            link("heading=Defaults") + link("target=a+b") + """<a href="https://example.com/note-link?target=x">y</a>"""
        assertEquals(listOf("T1. It's Your Life" to null, null to "core/backend engineering/01.md", "a b" to null), noteLinks(html))
        assertEquals(emptyList<Pair<String?, String?>>(), noteLinks("<p>No links</p>"))
    }

    @Test fun aNoteIsLinkedFromPagesWhoseLinksResolveToIt() {
        val pages = listOf(
            SavedPage(note("a/from.md").id, link("target=target")),          // same folder wins: a/target.md
            SavedPage(note("b/other.md").id, link("path=a/target.md")),      // exact path
            SavedPage(note("c/plain.md").id, link("target=target")),         // ambiguous from c/: could mean either
            SavedPage(note("c/self.md").id, link("target=self")),            // links only to itself
            SavedPage(99, link("path=a/target.md")),                         // not in the list
        )
        assertEquals(listOf("a/from.md", "b/other.md", "c/plain.md"), linkedFrom(note("a/target.md"), notes, pages).map { it.path })
        assertEquals(listOf("c/plain.md"), linkedFrom(note("b/target.md"), notes, pages).map { it.path })
        assertEquals(emptyList<String>(), linkedFrom(note("c/self.md"), notes, pages).map { it.path })
        assertEquals(emptyList<String>(), linkedFrom(note("a/target.md"), notes, emptyList()).map { it.path })
    }
}
