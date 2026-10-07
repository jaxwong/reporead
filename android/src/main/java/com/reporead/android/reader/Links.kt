package com.reporead.android.reader

import com.reporead.android.data.DocumentRow

/**
 * The notes a link from the note at [fromPath] can mean, among one repository's saved notes, following Obsidian:
 * a [path] (from a relative Markdown link) must match exactly; a [target] with a folder must match the end of a path;
 * a bare target matches a note's file name. Names compare case-insensitively and ".md" is optional. Of several
 * matches, one in the linking note's folder wins; otherwise all are returned for the reader to choose from.
 */
internal fun resolveNoteLink(target: String?, path: String?, fromPath: String, notes: List<DocumentRow>): List<DocumentRow> {
    if (path != null) return notes.filter { it.path == path }
    val wanted = target?.trim()?.lowercase()?.removeSuffix(".md")?.ifEmpty { null } ?: return emptyList()
    val matches = notes.filter {
        val name = it.path.lowercase().removeSuffix(".md")
        if ('/' in wanted) name == wanted || name.endsWith("/$wanted") else name.substringAfterLast('/') == wanted
    }
    if (matches.size <= 1) return matches
    val folder = fromPath.substringBeforeLast('/', "")
    return matches.filter { it.path.substringBeforeLast('/', "") == folder }.takeIf { it.size == 1 } ?: matches.sortedBy { it.path }
}
