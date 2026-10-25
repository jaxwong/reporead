package com.reporead.android.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.reporead.android.OpenAt
import com.reporead.android.Screen
import com.reporead.android.database.AnchorStatus
import com.reporead.android.database.AnnotationType
import com.reporead.android.database.NotebookItem
import com.reporead.android.database.RepoReadDao
import com.reporead.android.ui.AppBar
import com.reporead.android.ui.EmptyState
import com.reporead.android.ui.StatusLine
import com.reporead.android.ui.folderOf

internal fun inFolder(path: String, folder: String) = folder.isEmpty() || path.startsWith("$folder/")

/** Distinct ancestor folders of the note [paths], parents before their descendants. */
internal fun ancestorFolders(paths: List<String>): List<String> =
    paths
        .flatMap { path ->
            val parts = folderOf(path).split('/').filter { it.isNotEmpty() }
            parts.indices.map { parts.take(it + 1).joinToString("/") }
        }
        .distinct()
        .sortedBy { it.replace('/', '\u0000') }

@Composable
private fun Filter(label: String, choices: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        TextButton(onClick = { open = true }) {
            Text("$label: ${choices.firstOrNull { it.first == selected }?.second ?: selected}")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for ((key, text) in choices) DropdownMenuItem(
                text = { Text(text) },
                onClick = {
                    open = false
                    onSelect(key)
                },
            )
        }
    }
}

@Composable
internal fun NotebookScreen(
    dao: RepoReadDao,
    push: (Screen) -> Unit,
    onBack: () -> Unit,
) {
    val items by dao.notebook().collectAsStateWithLifecycle(emptyList())
    var folder by rememberSaveable { mutableStateOf("") }
    var document by rememberSaveable { mutableStateOf("") }
    var orphaned by rememberSaveable { mutableStateOf(false) }
    val folders =
        remember(items) { listOf("" to "All folders") + ancestorFolders(items.map { it.path }).map { it to it } }
    val notes =
        remember(items, folder) {
            listOf("" to "All notes") +
                items
                    .filter { inFolder(it.path, folder) }
                    .distinctBy { it.annotation.documentId }
                    .sortedBy { it.path }
                    .map { it.annotation.documentId.toString() to it.path }
        }
    val filtered = items.filter {
        inFolder(it.path, folder) &&
            (document.isEmpty() || it.annotation.documentId.toString() == document) &&
            (!orphaned || it.annotation.status == AnchorStatus.ORPHANED)
    }

    fun openPassage(item: NotebookItem) =
        push(Screen.Reader(item.annotation.documentId, item.title, OpenAt.Highlight(item.annotation.mutationId)))

    Scaffold(topBar = { AppBar("Notebook", onBack = onBack) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            StatusLine(
                "Highlights and cards are saved on this phone. Open the Library to load the server's complete notebook."
            )
            Filter("Folder", folders, folder) {
                folder = it
                document = ""
            }
            Filter("Note", notes, document) { document = it }
            TextButton(onClick = { orphaned = !orphaned }) {
                Text(if (orphaned) "Orphans only ✓" else "Orphans only")
            }
            if (filtered.isEmpty()) EmptyState("No matching highlights or cards.")
            else
                LazyColumn {
                    items(filtered, key = { it.annotation.mutationId }) { item ->
                        NotebookEntry(item, onOpen = { openPassage(item) })
                        HorizontalDivider()
                    }
                }
        }
    }
}

/** One highlight or card: its note's path, question, passage, own note, and why it needs attention, if it does. */
@Composable
private fun NotebookEntry(item: NotebookItem, onOpen: () -> Unit) {
    val annotation = item.annotation
    val reason =
        when {
            annotation.type == AnnotationType.CARD ->
                annotation.cardBlock(item.deleted, item.currentBlobSha, item.savedBlobSha)?.reason
            annotation.status == AnchorStatus.ORPHANED -> "Orphaned passage"
            else -> null
        }
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(item.path, style = MaterialTheme.typography.labelMedium)
        annotation.question?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
        Text(annotation.drawn.exactText)
        annotation.note?.let { Text(it) }
        StatusLine(listOfNotNull(reason, annotation.rejection).joinToString(": ").ifEmpty { null })
        TextButton(onClick = onOpen) { Text("Open passage") }
    }
}
