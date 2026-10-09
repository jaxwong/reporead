package com.reporead.android.reader

import com.reporead.android.data.DocumentRow
import java.net.URLDecoder

/**
 * The parameters of a /note-link query as the server writes them (MarkdownRenderer.noteLink, RFC 3986 encoding): only
 * percent escapes are decoded and "+" is a literal plus, so `[[C++ templates]]` keeps its name. Not form decoding, which
 * would read "+" as a space.
 */
internal fun noteLinkParameters(query: String): Map<String, String> = query.split('&').filter { it.isNotEmpty() }.associate { parameter ->
    fun decode(part: String) = URLDecoder.decode(part.replace("+", "%2B"), Charsets.UTF_8)
    decode(parameter.substringBefore('=')) to decode(parameter.substringAfter('=', ""))
}

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
