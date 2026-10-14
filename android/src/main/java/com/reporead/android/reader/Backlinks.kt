package com.reporead.android.reader

import com.reporead.android.data.DocumentRow

/**
 * Where a saved page's note link points, as (target, path), decoded as the reader decodes a tapped link
 * ([noteLinkParameters]); null for a link to a heading of the same note, which has neither.
 */
internal fun noteLinkTarget(query: String): Pair<String?, String?>? {
    val parameters = noteLinkParameters(query)
    val target = parameters["target"]
    val path = parameters["path"]
    return if (target == null && path == null) null else target to path
}

/**
 * The notes whose saved pages link to [note], from each page's [links] ([noteLinkTarget], by document id), resolved like a
 * tapped link against the repository's [documents]: a link that could mean several notes counts when [note] is one of
 * them. Sorted by path; [note] itself is left out.
 */
internal fun linkedFrom(note: DocumentRow, documents: List<DocumentRow>, links: Map<Long, List<Pair<String?, String?>>>): List<DocumentRow> {
    val byId = documents.associateBy { it.id }
    return links.mapNotNull { (documentId, pageLinks) ->
        val from = byId[documentId]?.takeIf { it.id != note.id } ?: return@mapNotNull null
        from.takeIf { pageLinks.any { (target, path) -> resolveNoteLink(target, path, from.path, documents).any { it.id == note.id } } }
    }.sortedBy { it.path }
}
