package com.reporead.android.library

import android.content.ActivityNotFoundException
import android.content.Intent
import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.reporead.android.OpenAt
import com.reporead.android.Screen
import com.reporead.android.data.AnchorStatus
import com.reporead.android.data.AnnotationType
import com.reporead.android.data.LibraryDao
import com.reporead.android.data.NotebookItem
import com.reporead.android.sync.Sync
import com.reporead.android.ui.AppBar
import com.reporead.android.ui.EmptyState
import com.reporead.android.ui.StatusLine
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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

/** A published export of [count] entries, waiting for the screen to share it. */
internal data class Export(val file: File, val count: Int)

/**
 * The notebook's export, kept by its back-stack entry ([entryId]): a rotation or a covering screen does not lose it,
 * and the file is shared once, by the screen shown when it is ready; a screen that was left shares nothing.
 */
internal class NotebookViewModel(private val sync: Sync, private val entryId: String) : ViewModel() {
    data class State(val exporting: Boolean = false, val status: String? = null, val export: Export? = null)

    private val current = MutableStateFlow(State())
    val state: StateFlow<State> = current.asStateFlow()

    fun export(entries: List<NotebookItem>) {
        val now = current.value
        if (now.exporting || !current.compareAndSet(now, now.copy(exporting = true, status = null))) {
            Log.i("RepoRead", "Notebook export not started; entry=$entryId running=true")
            return
        }
        Log.i("RepoRead", "Notebook export started; entry=$entryId entries=${entries.size}")
        viewModelScope.launch {
            try {
                val file = sync.exportNotebook { notebookMarkdown(entries) }
                current.update { it.copy(exporting = false, export = Export(file, entries.size)) }
            } catch (error: IOException) {
                Log.w("RepoRead", "Notebook export failed", error)
                current.update { it.copy(exporting = false, status = "Export failed: ${error.message}") }
            }
        }
    }

    /** The export, once, for the screen that shares it. */
    fun claimExport(): Export? {
        val now = current.value
        val export = now.export ?: return null
        return if (current.compareAndSet(now, now.copy(export = null))) export else null
    }

    /** What sharing the claimed export did. */
    fun shared(status: String) = current.update { it.copy(status = status) }
}

@Composable
internal fun NotebookScreen(
    dao: LibraryDao,
    sync: Sync,
    entryId: String,
    push: (Screen) -> Unit,
    onBack: () -> Unit,
) {
    val model = viewModel { NotebookViewModel(sync, entryId) }
    val state by model.state.collectAsStateWithLifecycle()
    val items by dao.notebook().collectAsStateWithLifecycle(emptyList())
    var folder by rememberSaveable { mutableStateOf("") }
    var document by rememberSaveable { mutableStateOf("") }
    var orphaned by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    // Sharing needs the Activity on screen now, never the one that asked for the export.
    if (state.export != null)
        LifecycleResumeEffect(Unit) {
            model.claimExport()?.let { export ->
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.exports", export.file)
                val share =
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/markdown"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                try {
                    context.startActivity(Intent.createChooser(share, "Export notebook"))
                    model.shared("Sharing ${export.count} entries as Markdown. GitHub is unchanged.")
                } catch (error: ActivityNotFoundException) {
                    Log.w("RepoRead", "No notebook share target", error)
                    model.shared("No app is available to share this Markdown file.")
                }
            }
            onPauseOrDispose {}
        }
    val folders =
        remember(items) { listOf("" to "All folders") + practiceTopics(items.map { it.path }).map { it to it } }
    val notes =
        remember(items, folder) {
            listOf("" to "All notes") +
                items
                    .filter { inPracticeFolder(it.path, folder) }
                    .distinctBy { it.annotation.documentId }
                    .sortedBy { it.path }
                    .map { it.annotation.documentId.toString() to it.path }
        }
    val filtered = items.filter {
        inPracticeFolder(it.path, folder) &&
            (document.isEmpty() || it.annotation.documentId.toString() == document) &&
            (!orphaned || it.annotation.status == AnchorStatus.ORPHANED)
    }

    fun openPassage(item: NotebookItem) =
        push(Screen.Reader(item.annotation.documentId, item.title, OpenAt.Highlight(item.annotation.mutationId)))

    Scaffold(topBar = { AppBar("Notebook", onBack = onBack) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            StatusLine(
                "Highlights and cards saved on this phone. Sync in Library to load the server's complete notebook."
            )
            Filter("Folder", folders, folder) {
                folder = it
                document = ""
            }
            Filter("Note", notes, document) { document = it }
            Row {
                TextButton(onClick = { orphaned = !orphaned }) {
                    Text(if (orphaned) "Orphans only ✓" else "Orphans only")
                }
                TextButton(enabled = filtered.isNotEmpty() && !state.exporting, onClick = { model.export(filtered) }) {
                    Text("Export Markdown")
                }
            }
            StatusLine(state.status)
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
    val row = item.annotation
    val reason =
        when {
            row.type == AnnotationType.CARD ->
                row.cardBlock(item.deleted, item.currentBlobSha, item.savedBlobSha)?.reason
            row.status == AnchorStatus.ORPHANED -> "Orphaned passage"
            else -> null
        }
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(item.path, style = MaterialTheme.typography.labelMedium)
        row.question?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
        Text(row.drawn.exactText)
        row.note?.let { Text(it) }
        StatusLine(listOfNotNull(reason, row.rejection).joinToString(": ").ifEmpty { null })
        TextButton(onClick = onOpen) { Text("Open passage") }
    }
}

/** Prefixing each source line preserves all characters, including blank lines, trailing spaces and CRLF. */
internal fun notebookMarkdown(items: List<NotebookItem>): String = buildString {
    append("# RepoRead notebook\n\n")
    for (item in items) {
        val row = item.annotation
        append("## ").append(item.title.replace('\n', ' ')).append("\n\n")
        append("Source: ").append(item.path.replace('\n', ' ')).append("\n\n")
        row.question?.let { append("Question:\n\n").append(it).append("\n\n") }
        append(row.drawn.exactText.split('\n').joinToString("\n") { "> $it" }).append("\n\n")
        row.note?.let { append("Note:\n\n").append(it).append("\n\n") }
        if (row.status == AnchorStatus.ORPHANED) append("Status: orphaned\n\n")
    }
}
