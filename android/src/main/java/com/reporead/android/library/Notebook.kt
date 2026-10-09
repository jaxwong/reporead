package com.reporead.android.library

import android.content.ActivityNotFoundException
import android.content.Intent
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.reporead.android.Screen
import com.reporead.android.data.LibraryDao
import com.reporead.android.data.NotebookItem
import com.reporead.android.data.Passage
import com.reporead.android.data.ReviewRow
import com.reporead.android.sync.exportDirectory
import com.reporead.android.ui.AppBar
import com.reporead.android.ui.EmptyState
import com.reporead.android.ui.StatusLine
import com.reporead.android.ui.counted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.ZoneId
import java.util.UUID
import kotlin.random.Random

@Composable
private fun Filter(label: String, choices: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        TextButton(onClick = { open = true }) { Text("$label: ${choices.firstOrNull { it.first == selected }?.second ?: selected}") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for ((key, text) in choices) DropdownMenuItem(text = { Text(text) }, onClick = { open = false; onSelect(key) })
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
    val folders = remember(items) { listOf("" to "All folders") + practiceTopics(items.map { it.path }).map { it to it } }
    val notes = remember(items, folder) { listOf("" to "All notes") + items.filter { inPracticeFolder(it.path, folder) }
        .distinctBy { it.annotation.documentId }.sortedBy { it.path }.map { it.annotation.documentId.toString() to it.path } }
    val filtered = items.filter { inPracticeFolder(it.path, folder) && (document.isEmpty() || it.annotation.documentId.toString() == document) &&
        (!orphaned || it.annotation.status == "ORPHANED") }
    Scaffold(topBar = { AppBar("Notebook", onBack = onBack) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            StatusLine("Highlights and cards saved on this phone. Sync in Library to load the server's complete notebook.")
            Filter("Folder", folders, folder) { folder = it; document = "" }
            Filter("Note", notes, document) { document = it }
            Row {
                TextButton(onClick = { orphaned = !orphaned }) { Text(if (orphaned) "Orphans only ✓" else "Orphans only") }
                TextButton(enabled = filtered.isNotEmpty(), onClick = {
                    val snapshot = filtered
                    scope.launch {
                        try {
                            val uri = withContext(Dispatchers.IO) {
                                val directory = exportDirectory(context.filesDir)
                                if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create RepoRead export directory")
                                val file = File(directory, "notebook-${UUID.randomUUID()}.md")
                                val partial = File(directory, "${file.name}.part")
                                try {
                                    // Not Files.writeString: it needs API 36.1 and the app supports 34.
                                    partial.writeText(notebookMarkdown(snapshot), Charsets.UTF_8)
                                    Files.move(partial.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
                                } finally { Files.deleteIfExists(partial.toPath()) }
                                FileProvider.getUriForFile(context, "${context.packageName}.exports", file)
                            }
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                type = "text/markdown"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }, "Export notebook"))
                            status = "Sharing ${snapshot.size} entries as Markdown. GitHub is unchanged."
                        } catch (error: IOException) {
                            status = "Export failed: ${error.message}"
                            Log.w("RepoRead", "Notebook export failed", error)
                        } catch (error: ActivityNotFoundException) {
                            status = "No app is available to share this Markdown file."
                            Log.w("RepoRead", "No notebook share target", error)
                        }
                    }
                }) { Text("Export Markdown") }
            }
            StatusLine(status)
            if (filtered.isEmpty()) EmptyState("No matching highlights or cards.")
            else LazyColumn {
                items(filtered, key = { it.annotation.mutationId }) { item ->
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(item.path, style = MaterialTheme.typography.labelMedium)
                        item.annotation.question?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                        Text(item.annotation.drawn.exactText)
                        item.annotation.note?.let { Text(it) }
                        val reason = if (item.annotation.type == "CARD") cardCheckReason(item)
                            else if (item.annotation.status == "ORPHANED") "Orphaned passage" else null
                        StatusLine(reason ?: item.annotation.rejection)
                        TextButton(onClick = { push(Screen.Reader(item.annotation.documentId, item.title, annotation = item.annotation.mutationId)) }) {
                            Text("Open passage")
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
internal fun ReviewScreen(dao: LibraryDao, push: (Screen) -> Unit, onBack: () -> Unit) {
    val items by dao.notebook().collectAsState(null)
    val reviews by dao.reviews().collectAsState(null)
    val limit by dao.reviewLimit().collectAsState(null)
    var session by rememberSaveable { mutableStateOf<ArrayList<String>?>(null) }
    var startedAt by rememberSaveable { mutableStateOf(0L) }
    var revealed by rememberSaveable { mutableStateOf<String?>(null) }
    var skipped by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var saving by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val ready = items
    val log = reviews
    Scaffold(topBar = { AppBar("Review", onBack = onBack) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (ready == null || log == null) { Text("Loading saved cards…"); return@Column }
            val cards = ready.filter { it.annotation.type == "CARD" }
            val blocked = cards.count { cardCheckReason(it) != null }
            StatusLine(notice)
            if (blocked > 0) Text("${counted(blocked, "card")} need checking or a current saved note. Offline review uses the last known repository version.")
            val refused = log.filter { it.rejection != null }
            if (refused.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                // A refused grade never reached the server and is not part of the schedule; dismissing only forgets it.
                Text("${counted(refused.size, "grade")} not accepted by the server, so not counted: ${refused.last().rejection}",
                    modifier = Modifier.weight(1f))
                TextButton(onClick = { scope.launch { dao.dismissRefusedReviews() } }) { Text("Dismiss") }
            }
            val pending = log.count { it.pending && it.rejection == null }
            if (pending > 0) Text("$pending grades saved on this phone; waiting for Library sync.")
            val ids = session
            if (ids == null) {
                val cap = limit
                if (cap == null) Text("Sync in Library once to receive the server's session limit.")
                else {
                    val now = System.currentTimeMillis()
                    val due = dueSession(ready, log, now, cap, 0, ZoneId.systemDefault())
                    Text("${due.size} cards in the next session (at most $cap).")
                    Button(enabled = due.isNotEmpty(), onClick = {
                        startedAt = System.currentTimeMillis()
                        session = ArrayList(dueSession(ready, log, startedAt, cap, Random.nextInt(), ZoneId.systemDefault()).map { it.annotation.mutationId })
                        skipped = arrayListOf()
                        revealed = null
                    }) { Text("Start review") }
                }
                TextButton(onClick = { push(Screen.Notebook) }) { Text("Check cards in Notebook") }
                return@Column
            }
            // The log, not a remembered cursor, owns completion; a grade committed during rotation stays complete.
            val completed = log.filter { it.rejection == null && it.reviewedAt >= startedAt }.map { it.cardMutationId }.toSet() + skipped
            val id = ids.firstOrNull { it !in completed }
            if (id == null) {
                Text("Session complete: ${ids.size} cards reviewed or skipped.")
                TextButton(onClick = { session = null }) { Text("Finish") }
                return@Column
            }
            val item = ready.firstOrNull { it.annotation.mutationId == id }
            val reason = item?.let(::cardCheckReason) ?: "This card was deleted."
            Text("Card ${ids.indexOf(id) + 1} of ${ids.size}")
            if (item == null || cardCheckReason(item) != null) {
                Text(reason)
                TextButton(onClick = { skipped = ArrayList(skipped + id) }) { Text("Skip") }
                return@Column
            }
            val row = item.annotation
            Text(checkNotNull(row.question), style = MaterialTheme.typography.headlineSmall)
            if (revealed != id) Button(onClick = { revealed = id }) { Text("Reveal answer") }
            else {
                val context by produceState<Pair<Passage, String>?>(null, row.mutationId, row.drawn) {
                    val note = dao.note(row.documentId)
                    value = if (note?.blobSha == row.drawn.blobSha) withContext(Dispatchers.Default) { answerContext(note.html, row.drawn)?.let { row.drawn to it } } else null
                }
                val verified = context?.takeIf { it.first == row.drawn }?.second
                if (verified == null) {
                    Text("The saved page cannot verify this answer in context. Open the note to check it.")
                } else {
                    Text(row.drawn.exactText, style = MaterialTheme.typography.titleLarge)
                    Text("Context · ${item.path}", style = MaterialTheme.typography.labelMedium)
                    Text(verified)
                    // Each press persists one immutable mutation locally; network I/O waits for explicit Library sync.
                    for ((grade, label) in listOf(0 to "Again — no recall", 3 to "Hard — correct with difficulty", 4 to "Good — correct with hesitation", 5 to "Easy — perfect recall")) {
                        Button(enabled = !saving, modifier = Modifier.fillMaxWidth(), onClick = {
                            saving = true
                            val review = ReviewRow(UUID.randomUUID().toString(), id, grade, System.currentTimeMillis(), row.drawn.blobSha, true)
                            scope.launch {
                                try {
                                    val saved = withContext(NonCancellable) { dao.recordReview(review) }
                                    if (!saved) notice = "Card changed while answering; grade not saved. Open it to check the answer."
                                    revealed = null
                                } finally { saving = false }
                            }
                        }) { Text(label) }
                    }
                }
                TextButton(onClick = { push(Screen.Reader(row.documentId, item.title, annotation = id)) }) { Text("Open in note") }
                TextButton(enabled = !saving, onClick = { skipped = ArrayList(skipped + id); revealed = null }) { Text("Skip") }
            }
        }
    }
}
