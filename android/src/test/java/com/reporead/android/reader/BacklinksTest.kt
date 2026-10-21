package com.reporead.android.reader

import com.reporead.android.database.DocumentEntity
import com.reporead.android.database.noteLinkQueries
import org.junit.Assert.assertEquals
import org.junit.Test

class BacklinksTest {
    private val notes =
        listOf("a/target.md", "b/target.md", "a/from.md", "b/other.md", "c/plain.md", "c/self.md").mapIndexed {
            index,
            path ->
            DocumentEntity(index.toLong(), 7, path, path.substringAfterLast('/').removeSuffix(".md"), "a".repeat(40))
        }

    private fun note(path: String) = notes.single { it.path == path }

    private fun link(query: String) = """<p><a href="/note-link?$query">x</a></p>"""

    /** As Linked from reads a page: the queries saved with it, decoded. */
    private fun noteLinks(html: String) = noteLinkQueries(html).mapNotNull(::noteLinkTarget)

    @Test
    fun linksAreDecodedLikeTheReaderAndSameNoteHeadingsAreLeftOut() {
        val html =
            link("target=T1.%20It's%20Your%20Life") +
                link("path=core/backend%20engineering/01.md&amp;heading=2-separate") +
                link("heading=Defaults") +
                link("target=a+b") +
                """<a href="https://example.com/note-link?target=x">y</a>"""
        // The server encodes per RFC 3986 (a space is %20), so "+" is a literal plus.
        assertEquals(
            listOf("T1. It's Your Life" to null, null to "core/backend engineering/01.md", "a+b" to null),
            noteLinks(html),
        )
        assertEquals(emptyList<Pair<String?, String?>>(), noteLinks("<p>No links</p>"))
    }

    /** The exact href MarkdownRendererTest.aPlusInALinkNameStaysLiteral pins for [[C++ templates#A+B]]. */
    @Test
    fun theRenderersEncodingOfAPlusDecodesToAPlus() {
        assertEquals(
            mapOf("target" to "C++ templates", "heading" to "A+B"),
            noteLinkParameters("target=C++%20templates&heading=A+B"),
        )
        assertEquals(mapOf("target" to "C++"), noteLinkParameters("target=C%2B%2B"))
    }

    @Test
    fun aNoteIsLinkedFromPagesWhoseLinksResolveToIt() {
        val pages =
            mapOf(
                note("a/from.md").id to noteLinks(link("target=target")), // same folder wins: a/target.md
                note("b/other.md").id to noteLinks(link("path=a/target.md")), // exact path
                note("c/plain.md").id to noteLinks(link("target=target")), // ambiguous from c/: could mean either
                note("c/self.md").id to noteLinks(link("target=self")), // links only to itself
                99L to noteLinks(link("path=a/target.md")), // not in the list
            )
        assertEquals(
            listOf("a/from.md", "b/other.md", "c/plain.md"),
            linkedFrom(note("a/target.md"), notes, pages).map { it.path },
        )
        assertEquals(listOf("c/plain.md"), linkedFrom(note("b/target.md"), notes, pages).map { it.path })
        assertEquals(emptyList<String>(), linkedFrom(note("c/self.md"), notes, pages).map { it.path })
        assertEquals(emptyList<String>(), linkedFrom(note("a/target.md"), notes, emptyMap()).map { it.path })
    }
}
