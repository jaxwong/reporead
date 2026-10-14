package com.reporead.android.library

import android.content.ActivityNotFoundException
import android.content.Intent
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import com.reporead.android.core.network.ApiException
import com.reporead.android.core.network.describe
import com.reporead.android.data.LibraryDao
import com.reporead.android.data.NotebookItem
import com.reporead.android.data.Passage
import com.reporead.android.data.ReviewRow
import com.reporead.android.reader.BankAnswer
import com.reporead.android.sync.Sync
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
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
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
                        val reason = if (item.annotation.type == "CARD") item.annotation.cardBlock(item.currentBlobSha, item.savedBlobSha)?.reason
                            else if (item.annotation.status == "ORPHANED") "Orphaned passage" else null
                        StatusLine(listOfNotNull(reason, item.annotation.rejection).joinToString(": ").ifEmpty { null })
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

/**
 * The grades in one row under the answer, so grading never needs scrolling past it; Again, the only failing grade, is
 * set apart from the three that pass.
 */
@Composable
internal fun GradeBar(enabled: Boolean, onGrade: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(GRADE_MEANING, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((grade, label) in GRADES) {
                val modifier = Modifier.weight(1f)
                val padding = PaddingValues(horizontal = 4.dp)
                if (grade < 3) OutlinedButton(enabled = enabled, modifier = modifier, contentPadding = padding, onClick = { onGrade(grade) },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(label) }
                else Button(enabled = enabled, modifier = modifier, contentPadding = padding, onClick = { onGrade(grade) }) { Text(label) }
            }
        }
    }
}

/** What review holds now, from this phone's cards and grades; null while they are read. */
@Composable
internal fun rememberReviewOutlook(dao: LibraryDao): ReviewOutlook? {
    val items by dao.notebook().collectAsState(null)
    val reviews by dao.reviews().collectAsState(null)
    val ready = items
    val log = reviews
    return remember(ready, log) { if (ready == null || log == null) null else reviewOutlook(ready, log, System.currentTimeMillis(), ZoneId.systemDefault()) }
}

/** One line on what review holds: the due count, or the day the next cards fall due. [cap] is the server's session size. */
internal fun outlookLine(outlook: ReviewOutlook, cap: Int?, zone: ZoneId): String = when {
    outlook.due > 0 -> listOfNotNull("${outlook.due} due", cap?.takeIf { outlook.due > it }?.let { "$it per session" },
        outlook.blocked.takeIf { it > 0 }?.let { "$it to check first" }).joinToString(" · ")
    outlook.next != null -> "Nothing due · next on " +
        Instant.ofEpochMilli(outlook.next).atZone(zone).format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())) +
        ", ${counted(outlook.nextCount, "card")}"
    else -> "No cards yet: grade a question below, or make one from a passage in a note."
}

/** A card's answer from the saved copy of its version; null while reading or when that copy cannot verify it. */
@Composable
private fun rememberRevealed(item: NotebookItem, dao: LibraryDao): RevealedAnswer? {
    val row = item.annotation
    val context by produceState<Pair<Passage, RevealedAnswer>?>(null, row.mutationId, row.drawn) {
        val note = dao.note(row.documentId)
        value = if (note?.blobSha == row.drawn.blobSha) withContext(Dispatchers.Default) {
            revealedAnswer(note.html, note.path, row.drawn)?.let { row.drawn to it }
        } else null
    }
    return context?.takeIf { it.first == row.drawn }?.second
}

/** What a card is checked against: a bank question's linked section, or the passage in its block's context. */
@Composable
private fun ColumnScope.CardAnswer(item: NotebookItem, answer: RevealedAnswer, sync: Sync, dao: LibraryDao, push: (Screen) -> Unit) {
    val question = answer.question
    if (question != null) {
        Text("Interview bank · ${item.path}", style = MaterialTheme.typography.labelMedium)
        BankAnswer(question, item.annotation.documentId, item.path, sync, dao, Modifier.weight(1f), push)
    } else Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(item.annotation.drawn.exactText, style = MaterialTheme.typography.titleLarge)
        Text("Context · ${item.path}", style = MaterialTheme.typography.labelMedium)
        Text(answer.context)
    }
}

/**
 * A due card that needs something before it can be graded, with what fixes it from here: confirming its answer in the
 * note's new version (a server call, so online only), or opening the note to save that version or reattach the answer.
 * [onConfirmed] runs once the server has confirmed it.
 */
@Composable
private fun ColumnScope.FixCard(item: NotebookItem, sync: Sync, dao: LibraryDao, push: (Screen) -> Unit, onConfirmed: () -> Unit) {
    val row = item.annotation
    val current = item.currentBlobSha
    val openNote = @Composable { label: String ->
        TextButton(onClick = { push(Screen.Reader(row.documentId, item.title, annotation = row.mutationId)) }) { Text(label) }
    }
    when {
        row.status == "ORPHANED" -> {
            Text("Its answer is no longer in the note. Open the note, select the answer, then choose Reattach on this card in Highlights and notes.")
            openNote("Open note")
        }
        current == null || item.savedBlobSha != current -> {
            Text("The note's current version isn't saved on this phone. Open the note while online to save it, then review this card.")
            openNote("Open note")
        }
        row.serverId == null -> Text("This card hasn't reached the server yet, so it can't be confirmed. Open Library while online to sync it.")
        row.drawn.blobSha != current || (row.cachedCurrentBlobSha != null && row.cachedCurrentBlobSha != current) ->
            Text("The server hasn't placed this answer in the note's current version yet. Open Library while online to sync, then review it.")
        else -> {
            var confirming by remember(row.mutationId) { mutableStateOf(false) }
            var problem by remember(row.mutationId) { mutableStateOf<String?>(null) }
            val scope = rememberCoroutineScope()
            Text("The note changed since this card was checked. Is this still its answer?")
            problem?.let { StatusLine(it) }
            val answer = rememberRevealed(item, dao)
            if (answer == null) Text("The saved page cannot show this answer in context. Open the note to check it.")
            else CardAnswer(item, answer, sync, dao, push)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(enabled = !confirming, onClick = {
                    confirming = true
                    scope.launch {
                        try {
                            sync.checkCard(row, current)
                            onConfirmed()
                        } catch (error: ApiException) {
                            Log.w("RepoRead", "Card not confirmed from review; mutationId=${row.mutationId} code=${error.code}")
                            problem = "Not confirmed: ${error.describe()} Confirming needs the server."
                        } finally { confirming = false }
                    }
                }) { Text("Answer is still right") }
                openNote("Open note")
            }
        }
    }
}

/**
 * The due cards, one at a time: the question alone, then — after an attempt — its answer, then a grade under it. A card's
 * first grade in the session is saved; one graded below Good comes back later in the session, unsaved, until it is graded
 * Good or Easy (SM-2 step 7). A card that needs checking is shown with its fix instead.
 */
@Composable
internal fun ReviewScreen(dao: LibraryDao, sync: Sync, push: (Screen) -> Unit, onBack: () -> Unit) {
    val items by dao.notebook().collectAsState(null)
    val reviews by dao.reviews().collectAsState(null)
    val limit by dao.reviewLimit().collectAsState(null)
    /** The cards still to show, chosen once from the due cards when the session starts; the front one is shown. */
    var queue by rememberSaveable { mutableStateOf<ArrayList<String>?>(null) }
    var started by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var startedAt by rememberSaveable { mutableLongStateOf(0L) }
    var revealed by rememberSaveable { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val ready = items
    val log = reviews
    val cap = limit
    LaunchedEffect(ready, log, cap) {
        if (queue != null || ready == null || log == null || cap == null) return@LaunchedEffect
        startedAt = System.currentTimeMillis()
        started = ArrayList(dueSession(ready, log, startedAt, cap, Random.nextInt(), ZoneId.systemDefault()).map { it.annotation.mutationId })
        queue = ArrayList(started)
    }
    val next = { remaining: List<String> ->
        queue = ArrayList(remaining)
        revealed = false
    }
    Scaffold(topBar = { AppBar("Review", onBack = onBack) }) { padding ->
        // Not scrolled as a whole: a revealed answer takes the space left, and the grades stay under it.
        Column(Modifier.padding(padding).fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (ready == null || log == null) { Text("Loading saved cards…"); return@Column }
            StatusLine(notice)
            val refused = log.filter { it.rejection != null }
            if (refused.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                // A refused grade never reached the server and is not part of the schedule; dismissing only forgets it.
                Text("${counted(refused.size, "grade")} not accepted by the server, so not counted: ${refused.last().rejection}",
                    modifier = Modifier.weight(1f))
                TextButton(onClick = { scope.launch { dao.dismissRefusedReviews() } }) { Text("Dismiss") }
            }
            val pending = log.count { it.pending && it.rejection == null }
            if (pending > 0) Text("$pending grades saved on this phone; they are sent once it is online.", style = MaterialTheme.typography.bodySmall)
            if (cap == null) {
                Text("Review starts after one sync with the server: open Library while signed in and online.")
                return@Column
            }
            val ids = queue
            if (ids == null) { Text("Choosing due cards…"); return@Column }
            // The log, not the queue, says which cards this session saved a grade for, so a grade saved just before the
            // screen was recreated is never saved twice.
            val graded = log.filter { it.rejection == null && it.reviewedAt >= startedAt }.map { it.cardMutationId }.toSet()
            val id = ids.firstOrNull()
            if (id == null) {
                Text(if (started.isEmpty()) outlookLine(reviewOutlook(ready, log, System.currentTimeMillis(), ZoneId.systemDefault()), cap, ZoneId.systemDefault())
                    else "Session complete: ${counted(started.count { it in graded }, "card")} reviewed.")
                TextButton(onClick = onBack) { Text("Finish") }
                return@Column
            }
            val item = ready.firstOrNull { it.annotation.mutationId == id }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(listOfNotNull("${counted(ids.size, "card")} left", item?.title).joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                TextButton(enabled = !saving, onClick = { next(ids.drop(1)) }) { Text("Skip") }
            }
            if (item == null) { Text("This card was deleted."); return@Column }
            val row = item.annotation
            Text(checkNotNull(row.question), style = MaterialTheme.typography.titleLarge)
            if (item.annotation.cardBlock(item.currentBlobSha, item.savedBlobSha) != null) {
                // Confirmed, it goes to the back of the session like any card still to answer.
                FixCard(item, sync, dao, push, onConfirmed = { next(ids.drop(1) + id) })
                return@Column
            }
            if (!revealed) {
                Text("Answer it aloud or in writing first, then check it.")
                Button(onClick = { revealed = true }) { Text("Show answer") }
                return@Column
            }
            val again = id in graded
            if (again) Text("Asked again: its first grade is saved. This grade only decides whether it comes back in this session.",
                style = MaterialTheme.typography.bodySmall)
            val answer = rememberRevealed(item, dao)
            if (answer == null) Text("The saved page cannot verify this answer in context. Open the note to check it.")
            else CardAnswer(item, answer, sync, dao, push)
            TextButton(onClick = { push(Screen.Reader(row.documentId, item.title, annotation = id)) }) { Text("Open in note") }
            // Each saved grade is one immutable local mutation; the background sync sends it.
            if (answer != null) GradeBar(enabled = !saving) { grade ->
                if (again) next(afterGrade(ids, grade))
                else {
                    saving = true
                    val review = ReviewRow(UUID.randomUUID().toString(), id, grade, System.currentTimeMillis(), row.drawn.blobSha, true)
                    scope.launch {
                        try {
                            val saved = withContext(NonCancellable) { sync.recordReview(review) }
                            if (saved) next(afterGrade(ids, grade))
                            else {
                                notice = "Card changed while answering; grade not saved. Open it to check the answer."
                                next(ids.drop(1))
                            }
                        } finally { saving = false }
                    }
                }
            }
        }
    }
}
