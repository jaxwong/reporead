package com.reporead.android.reader

import com.reporead.android.data.DocumentRow
import com.reporead.android.data.SavedPage

private val NOTE_LINK_HREF = Regex("""href="/note-link\?([^"]*)"""")

/**
 * The links to other notes in a saved page, as (target, path), decoded as the reader decodes a tapped link
 * ([noteLinkParameters]). Links to a heading of the same note have neither and are left out.
 */
internal fun noteLinks(html: String): List<Pair<String?, String?>> = NOTE_LINK_HREF.findAll(html).mapNotNull { match ->
    val parameters = noteLinkParameters(match.groupValues[1].replace("&amp;", "&"))
    val target = parameters["target"]
    val path = parameters["path"]
    if (target == null && path == null) null else target to path
}.toList()

/**
 * The notes whose saved [pages] link to [note], resolved like a tapped link against the repository's
 * [documents]: a link that could mean several notes counts when [note] is one of them. Sorted by path; [note] itself is left out.
 */
internal fun linkedFrom(note: DocumentRow, documents: List<DocumentRow>, pages: List<SavedPage>): List<DocumentRow> {
    val byId = documents.associateBy { it.id }
    return pages.mapNotNull { (documentId, html) ->
        val from = byId[documentId]?.takeIf { it.id != note.id } ?: return@mapNotNull null
        from.takeIf { noteLinks(html).any { (target, path) -> resolveNoteLink(target, path, from.path, documents).any { it.id == note.id } } }
    }.sortedBy { it.path }
}
