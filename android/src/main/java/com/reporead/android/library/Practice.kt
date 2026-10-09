package com.reporead.android.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.reporead.android.R
import com.reporead.android.Screen
import com.reporead.android.data.LibraryDao
import com.reporead.android.data.PracticePage
import com.reporead.android.reader.studyNote
import com.reporead.android.reader.StudyQuestion
import com.reporead.android.reader.StudyTarget
import com.reporead.android.ui.EmptyState
import com.reporead.android.ui.EntryRow
import com.reporead.android.ui.StatusLine
import com.reporead.android.ui.folderOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.random.Random

internal data class PracticeQuestion(val documentId: Long, val title: String, val path: String, val blobSha: String, val question: StudyQuestion)
private data class PracticeSnapshot(val pages: List<PracticePage>, val questions: List<PracticeQuestion>)

/** Shuffled note groups, then one question per note per round; remaining questions never disappear. */
internal fun interleaveQuestions(questions: List<PracticeQuestion>, seed: Int): List<PracticeQuestion> {
    val random = Random(seed)
    val groups = questions.groupBy { it.documentId }.values.shuffled(random).map { it.shuffled(random) }
    return (0 until (groups.maxOfOrNull { it.size } ?: 0)).flatMap { round -> groups.mapNotNull { it.getOrNull(round) } }
}

internal fun inPracticeFolder(path: String, folder: String): Boolean {
    val prefix = folder.trim().trim('/')
    return prefix.isEmpty() || path.startsWith("$prefix/")
}

/** Every real ancestor folder is a topic, including folders whose notes have not been saved yet. */
internal fun practiceTopics(paths: List<String>): List<String> = paths.flatMap { path ->
    val parts = folderOf(path).split('/').filter { it.isNotEmpty() }
    parts.indices.map { parts.take(it + 1).joinToString("/") }
}.distinct().sortedBy { it.replace('/', '\u0000') }

/** Entirely local: no fetch, scores, or scheduling. A single Room snapshot owns the list and its coverage count. */
@Composable
internal fun PracticeTab(dao: LibraryDao, push: (Screen) -> Unit) {
    val pages by dao.practicePages().collectAsState(null)
    var topic by rememberSaveable { mutableStateOf<String?>(null) }
    var shuffle by rememberSaveable { mutableIntStateOf(Random.nextInt()) }
    var extracted by remember { mutableStateOf<PracticeSnapshot?>(null) }
    LaunchedEffect(pages) {
        extracted = null
        val saved = pages ?: return@LaunchedEffect
        extracted = withContext(Dispatchers.Default) {
            val questions = saved.flatMap { page -> page.html?.let { html ->
                val blobSha = checkNotNull(page.blobSha) { "Saved Practice page ${page.documentId} has HTML without its blob SHA" }
                studyNote(html).questions.map { PracticeQuestion(page.documentId, page.title, page.path, blobSha, it) }
            }.orEmpty() }
            PracticeSnapshot(saved, questions)
        }
    }
    val questions = remember(extracted, topic, shuffle) {
        topic?.let { folder -> extracted?.questions?.let { all -> interleaveQuestions(all.filter { inPracticeFolder(it.path, folder) }, shuffle) } }
    }
    Column(Modifier.fillMaxSize()) {
        val snapshot = extracted
        if (topic == null) {
            StatusLine("Choose a topic. Folders include their subfolders; All topics mixes questions across the library.")
            if (snapshot == null) EmptyState("Reading questions from saved notes…")
            else LazyColumn {
                item {
                    EntryRow("All topics", "${snapshot.questions.size} questions · mixed queue", R.drawable.ic_notes) { topic = "" }
                }
                items(practiceTopics(snapshot.pages.map { it.path }), key = { it }) { folder ->
                    val count = snapshot.questions.count { inPracticeFolder(it.path, folder) }
                    val notes = snapshot.pages.filter { inPracticeFolder(it.path, folder) }
                    EntryRow(folder, "$count questions · ${notes.count { it.html != null }}/${notes.size} notes saved", R.drawable.ic_folder) { topic = folder }
                }
            }
            return@Column
        }
        TextButton(onClick = { topic = null }) { Text("Topics · ${topic?.ifEmpty { "All topics" }}") }
        val listed = snapshot?.pages?.filter { inPracticeFolder(it.path, requireNotNull(topic)) }
        listed?.let { notes ->
            StatusLine("Questions from ${notes.count { it.html != null }} of ${notes.size} listed notes saved on this phone." +
                if (notes.any { it.html == null }) " Incomplete: save all notes to cover the rest." else "")
        }
        TextButton(onClick = { shuffle++ }, enabled = !questions.isNullOrEmpty()) { Text("Shuffle questions") }
        val ready = questions
        when {
            ready == null -> EmptyState("Reading questions from saved notes…")
            ready.isEmpty() -> EmptyState("No question items in this folder's saved notes. Practice uses Questions this file answers and Review and practice.")
            else -> LazyColumn {
                items(ready, key = { "${it.documentId}:${it.question.blockId}" }) { question ->
                    Column {
                        EntryRow(question.question.text, question.path, R.drawable.ic_notes) {
                            push(Screen.Reader(question.documentId, question.title, question = StudyTarget(question.blobSha, question.question.blockId)))
                        }
                        TextButton(onClick = { push(Screen.Reader(question.documentId, question.title, reviewPrompt = question.question.prompt)) }) {
                            Text("Add to review")
                        }
                    }
                }
            }
        }
    }
}
