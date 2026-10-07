package com.reporead.android.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.reporead.android.Screen
import com.reporead.android.data.LibraryDao
import kotlinx.coroutines.delay

/** Matches of each kind shown per query; more are not listed. */
internal const val SEARCH_LIMIT = 50
private const val TYPING_PAUSE_MS = 150L

/** A LIKE pattern matching [query] anywhere, with LIKE's wildcards and the escape character taken literally. */
internal fun likePattern(query: String): String =
    "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"

/** One line of [text] around the first case-insensitive occurrence of [query]. */
internal fun snippet(text: String, query: String): String {
    val at = text.indexOf(query, ignoreCase = true)
    check(at >= 0) { "A text match must contain the query" }
    val start = maxOf(0, at - 40)
    val end = minOf(text.length, at + query.length + 60)
    val line = text.substring(start, end).replace('\n', ' ')
    return (if (start > 0) "…" else "") + line + (if (end < text.length) "…" else "")
}

internal data class SearchResult(val documentId: Long, val title: String, val path: String, val detail: String)

/**
 * Searches only what is saved on this phone, which belongs to the signed-in account: note titles and paths in the
 * saved lists, the text of saved notes, and highlights with their notes.
 */
internal suspend fun search(dao: LibraryDao, query: String): Pair<List<SearchResult>, Boolean> {
    val pattern = likePattern(query)
    val notes = dao.searchNotes(pattern, SEARCH_LIMIT)
    val highlights = dao.searchHighlights(pattern, SEARCH_LIMIT)
    // A note can match as a list entry and as a saved copy; the text match is the more useful one.
    val byNote = notes.groupBy { it.documentId }.values.map { matches -> matches.firstOrNull { it.searchText != null } ?: matches.first() }
    val results = byNote.map { note ->
        SearchResult(note.documentId, note.title, note.path, note.searchText?.let { snippet(it, query) } ?: "Title or path")
    } + highlights.map { highlight ->
        val inQuote = highlight.exactText.contains(query, ignoreCase = true)
        SearchResult(highlight.documentId, highlight.title, highlight.path,
            if (inQuote) "Your highlight: “${snippet(highlight.exactText, query)}”" else "Your note: ${snippet(highlight.note!!, query)}")
    }
    return results to (notes.size == SEARCH_LIMIT || highlights.size == SEARCH_LIMIT)
}

@Composable
fun SearchScreen(dao: LibraryDao, push: (Screen) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<Pair<List<SearchResult>, Boolean>?>(null) }
    var counts by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    LaunchedEffect(Unit) { counts = dao.savedListCount() to dao.searchableNoteCount() }
    LaunchedEffect(query) {
        results = null
        if (query.isBlank()) return@LaunchedEffect
        delay(TYPING_PAUSE_MS)
        results = search(dao, query.trim())
    }
    Column(Modifier.fillMaxSize()) {
        Text("Search", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(16.dp))
        OutlinedTextField(query, { query = it }, singleLine = true, label = { Text("Search saved notes") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        val found = results
        when {
            query.isBlank() -> Text(counts?.let { (titles, texts) ->
                "Searches the titles of $titles notes in lists saved on this phone, the text of $texts notes saved on this phone, " +
                    "and your highlights. Open a note online to save it for text search."
            } ?: "", Modifier.padding(16.dp))
            found == null -> Unit
            found.first.isEmpty() -> Text("No saved note matches “${query.trim()}”. Only notes opened on this phone are searched by their text.",
                Modifier.padding(16.dp))
            else -> LazyColumn {
                if (found.second) item { Text("Showing the first $SEARCH_LIMIT matches; refine the search to see others.", Modifier.padding(16.dp)) }
                itemsIndexed(found.first) { _, result ->
                    Column(Modifier.fillMaxWidth().clickable { push(Screen.Reader(result.documentId, result.title)) }
                        .padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(result.title, style = MaterialTheme.typography.bodyLarge)
                        Text(result.detail, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                        Text(result.path, style = MaterialTheme.typography.bodySmall)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
