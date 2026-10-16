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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.reporead.android.OpenAt
import com.reporead.android.Screen
import com.reporead.android.data.LibraryDao
import com.reporead.android.data.NotebookItem
import com.reporead.android.sync.exportDirectory
import com.reporead.android.ui.AppBar
import com.reporead.android.ui.EmptyState
import com.reporead.android.ui.StatusLine
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
internal fun NotebookScreen(dao: LibraryDao, push: (Screen) -> Unit, onBack: () -> Unit) {
    val items by dao.notebook().collectAsState(emptyList())
    var folder by rememberSaveable { mutableStateOf("") }
    var document by rememberSaveable { mutableStateOf("") }
    var orphaned by rememberSaveable { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
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
            (!orphaned || it.annotation.status == "ORPHANED")
    }
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
                TextButton(
                    enabled = filtered.isNotEmpty(),
                    onClick = {
                        val snapshot = filtered
                        scope.launch {
                            try {
                                val uri =
                                    withContext(Dispatchers.IO) {
                                        val directory = exportDirectory(context.filesDir)
                                        if (!directory.isDirectory && !directory.mkdirs())
                                            throw IOException("Cannot create RepoRead export directory")
                                        val file = File(directory, "notebook-${UUID.randomUUID()}.md")
                                        val partial = File(directory, "${file.name}.part")
                                        try {
                                            // Not Files.writeString: it needs API 36.1 and the app supports 34.
                                            partial.writeText(notebookMarkdown(snapshot), Charsets.UTF_8)
                                            Files.move(partial.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
                                        } finally {
                                            Files.deleteIfExists(partial.toPath())
                                        }
                                        FileProvider.getUriForFile(context, "${context.packageName}.exports", file)
                                    }
                                context.startActivity(
                                    Intent.createChooser(
                                        Intent(Intent.ACTION_SEND).apply {
                                            type = "text/markdown"
                                            putExtra(Intent.EXTRA_STREAM, uri)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        },
                                        "Export notebook",
                                    )
                                )
                                status = "Sharing ${snapshot.size} entries as Markdown. GitHub is unchanged."
                            } catch (error: IOException) {
                                status = "Export failed: ${error.message}"
                                Log.w("RepoRead", "Notebook export failed", error)
                            } catch (error: ActivityNotFoundException) {
                                status = "No app is available to share this Markdown file."
                                Log.w("RepoRead", "No notebook share target", error)
                            }
                        }
                    },
                ) {
                    Text("Export Markdown")
                }
            }
            StatusLine(status)
            if (filtered.isEmpty()) EmptyState("No matching highlights or cards.")
            else
                LazyColumn {
                    items(filtered, key = { it.annotation.mutationId }) { item ->
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Text(item.path, style = MaterialTheme.typography.labelMedium)
                            item.annotation.question?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                            Text(item.annotation.drawn.exactText)
                            item.annotation.note?.let { Text(it) }
                            val reason =
                                if (item.annotation.type == "CARD")
                                    item.annotation
                                        .cardBlock(item.deleted, item.currentBlobSha, item.savedBlobSha)
                                        ?.reason
                                else if (item.annotation.status == "ORPHANED") "Orphaned passage" else null
                            StatusLine(
                                listOfNotNull(reason, item.annotation.rejection).joinToString(": ").ifEmpty { null }
                            )
                            TextButton(
                                onClick = {
                                    push(
                                        Screen.Reader(
                                            item.annotation.documentId,
                                            item.title,
                                            OpenAt.Highlight(item.annotation.mutationId),
                                        )
                                    )
                                }
                            ) {
                                Text("Open passage")
                            }
                        }
                        HorizontalDivider()
                    }
                }
        }
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
        if (row.status == "ORPHANED") append("Status: orphaned\n\n")
    }
}
