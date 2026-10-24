package com.reporead.android.search

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import com.reporead.android.OpenAt
import com.reporead.android.R
import com.reporead.android.Screen
import com.reporead.android.database.Passage
import com.reporead.android.database.RepoReadDao
import com.reporead.android.ui.AppBar
import com.reporead.android.ui.AppIcon
import com.reporead.android.ui.EmptyState
import com.reporead.android.ui.EntryRow
import com.reporead.android.ui.folderOf
import kotlinx.coroutines.delay

/** Matches of each kind shown per query; more are not listed. */
internal const val SEARCH_LIMIT = 50
private const val TYPING_PAUSE_MS = 150L

/** A LIKE pattern matching [query] anywhere, with LIKE's wildcards and the escape character taken literally. */
internal fun likePattern(query: String): String =
    "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"

/** The first occurrence search selected; the database already established that one exists. */
private fun matchStart(text: String, query: String): Int {
    val at = text.indexOf(query, ignoreCase = true)
    check(at >= 0) { "A text match must contain the query" }
    return at
}

/** One line of [text] around the first case-insensitive occurrence of [query]. */
internal fun snippet(text: String, query: String): String = snippet(text, query, matchStart(text, query))

private fun snippet(text: String, query: String, at: Int): String {
    val start = maxOf(0, at - 40)
    val end = minOf(text.length, at + query.length + 60)
    val line = text.substring(start, end).replace('\n', ' ')
    return (if (start > 0) "…" else "") + line + (if (end < text.length) "…" else "")
}

internal data class SearchResult(
    val documentId: Long,
    val title: String,
    val path: String,
    val detail: String,
    val openAt: OpenAt?,
)

/** The matches listed for a query; [isTruncated] when a result category reached the global [SEARCH_LIMIT]. */
internal data class SearchResults(val matches: List<SearchResult>, val isTruncated: Boolean)

/**
 * Searches only what is saved on this phone, which belongs to the signed-in account: note titles and paths in the saved
 * lists, the text of saved notes, and highlights with their notes.
 */
internal suspend fun search(dao: RepoReadDao, query: String): SearchResults {
    val pattern = likePattern(query)
    val names = dao.searchNoteNames(pattern, SEARCH_LIMIT)
    val noteText = dao.searchNoteText(pattern, SEARCH_LIMIT)
    val highlights = dao.searchHighlights(pattern, SEARCH_LIMIT)
    val results =
        names.map { note ->
            SearchResult(
                note.documentId,
                note.title,
                note.path,
                if (note.isTitleMatch) "Title" else "Path",
                null,
            )
        } +
            noteText.map { match ->
                val start = matchStart(match.text, query)
                SearchResult(
                    match.documentId,
                    match.title,
                    match.path,
                    "Note text · ${snippet(match.text, query, start)}",
                    OpenAt.Search(
                        Passage(
                            match.blobSha,
                            match.blockId,
                            start,
                            start + query.length,
                            match.text.substring(start, start + query.length),
                        )
                    ),
                )
            } +
            highlights.map { highlight ->
                val inQuote = highlight.exactText.contains(query, ignoreCase = true)
                SearchResult(
                    highlight.documentId,
                    highlight.title,
                    highlight.path,
                    if (inQuote) "Your highlight: “${snippet(highlight.exactText, query)}”"
                    else "Your note: ${snippet(highlight.note!!, query)}",
                    OpenAt.Highlight(highlight.mutationId),
                )
            }
    return SearchResults(
        results,
        isTruncated = names.size == SEARCH_LIMIT || noteText.size == SEARCH_LIMIT || highlights.size == SEARCH_LIMIT,
    )
}

/**
 * What search covers: [texts] saved pages among [listed] listed notes are searched by text, all listed notes by name.
 */
private fun searchScope(listed: Int, texts: Int): String =
    "Searching $texts of $listed notes by their text, all $listed by title and path, and your highlights." +
        if (texts < listed)
            " Notes not saved here are found by title and path only; a repository's menu has Save all notes on this phone."
        else ""

/** No match for [query]; when not every note is saved, says that the others were searched by name only. */
private fun noMatches(query: String, counts: Pair<Int, Int>?): String =
    "No saved note matches “$query”." +
        counts
            ?.let { (listed, texts) ->
                if (texts < listed)
                    " Only $texts of $listed notes are saved on this phone, so only those are searched by their text."
                else null
            }
            .orEmpty()

@Composable
fun SearchScreen(dao: RepoReadDao, push: (Screen) -> Unit, onBack: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<SearchResults?>(null) }
    var counts by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    LaunchedEffect(Unit) { counts = dao.savedListCount() to dao.searchableNoteCount() }
    LaunchedEffect(query) {
        results = null
        if (query.isBlank()) return@LaunchedEffect
        delay(TYPING_PAUSE_MS)
        results = search(dao, query.trim())
    }
    Scaffold(topBar = { AppBar("Search", onBack = onBack) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                query,
                { query = it },
                singleLine = true,
                label = { Text("Search saved notes") },
                leadingIcon = { AppIcon(R.drawable.ic_search, null) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            val found = results
            when {
                query.isBlank() -> EmptyState(counts?.let { (listed, texts) -> searchScope(listed, texts) }.orEmpty())
                found == null -> Unit
                found.matches.isEmpty() -> EmptyState(noMatches(query.trim(), counts))
                else ->
                    LazyColumn {
                        if (found.isTruncated)
                            item {
                                Text(
                                    "Some matches are omitted; refine the search to see others.",
                                    Modifier.padding(16.dp),
                                )
                            }
                        items(found.matches) { result ->
                            EntryRow(
                                result.title,
                                result.detail,
                                R.drawable.ic_description,
                                listOfNotNull(folderOf(result.path).ifEmpty { null }),
                            ) {
                                push(Screen.Reader(result.documentId, result.title, result.openAt))
                            }
                        }
                    }
            }
        }
    }
}
