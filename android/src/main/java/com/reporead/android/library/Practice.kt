package com.reporead.android.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.dp
import com.reporead.android.R
import com.reporead.android.Screen
import com.reporead.android.data.LibraryDao
import com.reporead.android.data.NotebookItem
import com.reporead.android.data.PracticePage
import com.reporead.android.data.ReviewRow
import com.reporead.android.reader.BankAnswer
import com.reporead.android.reader.StudyQuestion
import com.reporead.android.reader.interviewBank
import com.reporead.android.reader.interviewTopic
import com.reporead.android.reader.isInterviewBank
import com.reporead.android.sync.Sync
import com.reporead.android.ui.AppBar
import com.reporead.android.ui.EmptyState
import com.reporead.android.ui.EntryRow
import com.reporead.android.ui.StatusLine
import com.reporead.android.ui.counted
import com.reporead.android.ui.folderOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID
import java.time.ZoneId
import kotlin.random.Random

internal data class PracticeQuestion(val documentId: Long, val title: String, val path: String, val blobSha: String, val question: StudyQuestion) {
    /** The question within its bank's saved version; a session names its questions by this. */
    val key: String get() = "$documentId:$blobSha:${question.blockId}"
}

/** The listed interview banks (saved or not), the saved ones' questions, and their H3s that are not questions (path to heading). */
internal data class Banks(val pages: List<PracticePage>, val questions: List<PracticeQuestion>, val unrecognized: List<Pair<String, String>>)

internal fun readBanks(pages: List<PracticePage>): Banks {
    val banks = pages.filter { isInterviewBank(it.path) }
    val questions = mutableListOf<PracticeQuestion>()
    val unrecognized = mutableListOf<Pair<String, String>>()
    for (page in banks) {
        val html = page.html ?: continue
        val blobSha = checkNotNull(page.blobSha) { "Saved Practice page ${page.documentId} has HTML without its blob SHA" }
        val bank = interviewBank(html, page.path)
        bank.questions.mapTo(questions) { PracticeQuestion(page.documentId, page.title, page.path, blobSha, it) }
        bank.unrecognized.mapTo(unrecognized) { page.path to it }
    }
    return Banks(banks, questions, unrecognized)
}

/** Shuffled note groups, then one question per note per round; remaining questions never disappear. */
internal fun interleaveQuestions(questions: List<PracticeQuestion>, seed: Int): List<PracticeQuestion> {
    val random = Random(seed)
    val groups = questions.groupBy { it.documentId }.values.shuffled(random).map { it.shuffled(random) }
    return (0 until (groups.maxOfOrNull { it.size } ?: 0)).flatMap { round -> groups.mapNotNull { it.getOrNull(round) } }
}

/**
 * The keys of the questions that already have a card — in the same bank, with the same prompt and answer line — and so
 * are on the review schedule rather than new. Two identical prompts with different answer lines stay two questions.
 */
internal fun scheduledQuestions(questions: List<PracticeQuestion>, cards: List<NotebookItem>): Set<String> {
    val made = cards.filter { it.annotation.type == "CARD" }
        .map { Triple(it.annotation.documentId, it.annotation.question, it.annotation.drawn.exactText) }.toSet()
    return questions.filter { Triple(it.documentId, it.question.prompt, it.question.answer.text) in made }.map { it.key }.toSet()
}

/**
 * The session's queue after grading its front question: one graded below Good goes to the back, to be answered again
 * until it is graded Good or Easy — SM-2's same-day repetition (super-memory.com/english/ol/sm2.htm, step 7).
 */
internal fun afterGrade(queue: List<String>, grade: Int): List<String> = queue.drop(1) + if (grade < 4) queue.take(1) else emptyList()

internal fun inPracticeFolder(path: String, folder: String): Boolean {
    val prefix = folder.trim().trim('/')
    return prefix.isEmpty() || path.startsWith("$prefix/")
}

/** Every real ancestor folder is a topic, including folders whose notes have not been saved yet. */
internal fun practiceTopics(paths: List<String>): List<String> = paths.flatMap { path ->
    val parts = folderOf(path).split('/').filter { it.isNotEmpty() }
    parts.indices.map { parts.take(it + 1).joinToString("/") }
}.distinct().sortedBy { it.replace('/', '\u0000') }

private fun topicName(topic: String?) = when (topic) {
    null -> "All topics"
    "" -> "Repository root"
    else -> topic
}

/**
 * Saves a new question's first grade on this phone: its card, whose answer is the question's Canonical line in the
 * saved version it was asked from, then that grade. Library sync sends both. Returns why it saved nothing or only the
 * card, or null. A question that already has a card (graded just before the screen was recreated) is not saved twice.
 */
internal suspend fun gradeNewQuestion(sync: Sync, dao: LibraryDao, question: PracticeQuestion, grade: Int): String? {
    if (scheduledQuestions(listOf(question), dao.notebook().first()).isNotEmpty()) return null
    if (dao.note(question.documentId)?.blobSha != question.blobSha || dao.document(question.documentId)?.blobSha != question.blobSha) {
        return "Not saved: this question's bank changed since it was asked. Save all notes in Library, then practise it again."
    }
    val answer = question.question.answer
    val selection = JSONObject().put("sourceBlobSha", question.blobSha).put("blockId", answer.blockId)
        .put("startOffset", 0).put("endOffset", answer.text.length).put("exactText", answer.text)
    val card = sync.createAnnotation(selection, question.documentId, null, question.question.prompt)
    val graded = dao.recordReview(ReviewRow(UUID.randomUUID().toString(), card, grade, System.currentTimeMillis(), question.blobSha, true))
    return if (graded) null else "The card was saved but not its grade, so it is due now in Review cards."
}

/** Every listed interview bank and the saved ones' questions, read off the main thread; null until first read. */
@Composable
private fun rememberBanks(dao: LibraryDao): Banks? {
    val pages by dao.practicePages().collectAsState(null)
    val banks by produceState<Banks?>(null, pages) {
        val listed = pages ?: return@produceState
        value = withContext(Dispatchers.Default) { readBanks(listed) }
    }
    return banks
}

/**
 * Entirely local. Review comes first: the cards due now, or when the next ones are. Below it, the topics ask new
 * questions: the folders that hold an interview folder, and All topics mixing them.
 */
@Composable
internal fun PracticeTab(dao: LibraryDao, push: (Screen) -> Unit) {
    val banks = rememberBanks(dao)
    val cards by dao.notebook().collectAsState(null)
    val outlook = rememberReviewOutlook(dao)
    val cap by dao.reviewLimit().collectAsState(null)
    Column(Modifier.fillMaxSize()) {
        outlook?.let { EntryRow("Review", outlookLine(it, cap, ZoneId.systemDefault()), R.drawable.ic_refresh) { push(Screen.Review) } }
        StatusLine("New questions: each is asked once; your grade puts it on your review schedule.")
        val ready = banks
        val notebook = cards
        if (ready == null || notebook == null) {
            EmptyState("Reading questions from saved notes…")
            return@Column
        }
        val topics = ready.pages.mapNotNull { interviewTopic(it.path) }.distinct().sorted()
        if (topics.isEmpty()) {
            EmptyState("No interview folders in your note lists. Practice asks the questions in folders named interview.")
            return@Column
        }
        val scheduled = remember(ready, notebook) { scheduledQuestions(ready.questions, notebook) }
        LazyColumn {
            item { TopicRow(null, ready, scheduled, push) }
            items(topics, key = { it }) { topic -> TopicRow(topic, ready, scheduled, push) }
        }
    }
}

@Composable
private fun TopicRow(topic: String?, banks: Banks, scheduled: Set<String>, push: (Screen) -> Unit) {
    val inTopic = { path: String -> topic == null || interviewTopic(path) == topic }
    val questions = banks.questions.filter { inTopic(it.path) }
    val pages = banks.pages.filter { inTopic(it.path) }
    val new = questions.count { it.key !in scheduled }
    Column {
        EntryRow(topicName(topic), "$new new · ${questions.size - new} on your schedule · ${pages.count { it.html != null }}/${pages.size} banks saved",
            if (topic == null) R.drawable.ic_notes else R.drawable.ic_folder) { push(Screen.Practice(topic)) }
        // A heading that slipped the format is named, so its questions do not vanish without a trace.
        val problems = if (topic == null) emptyList() else banks.unrecognized.filter { inTopic(it.first) }
        if (problems.isNotEmpty()) StatusLine("Not questions (no Canonical answer or path line): " +
            problems.joinToString("; ") { (path, heading) -> "${path.substringAfterLast('/')} › $heading" })
    }
}

/**
 * One topic's new questions, one at a time: the question alone, then — after an attempt — the bank's checklist and the
 * linked section, then a grade. The first grade makes the question a card (Review cards brings it back when due); one
 * graded below Good comes back later in the session, unsaved, until it is graded Good or Easy.
 */
@Composable
internal fun PracticeScreen(dao: LibraryDao, sync: Sync, topic: String?, push: (Screen) -> Unit, onBack: () -> Unit) {
    val banks = rememberBanks(dao)
    val cards by dao.notebook().collectAsState(null)
    /** The keys still to ask, chosen once from the topic's questions without a card; the front one is shown. */
    var queue by rememberSaveable { mutableStateOf<ArrayList<String>?>(null) }
    /** The questions the session started with. The cards made since, not a counter, say how many were graded. */
    var started by rememberSaveable { mutableStateOf<ArrayList<String>>(arrayListOf()) }
    var revealed by rememberSaveable { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val questions = remember(banks, topic) { banks?.questions?.filter { topic == null || interviewTopic(it.path) == topic } }
    val notebook = cards
    val scheduled = remember(questions, notebook) { if (questions == null || notebook == null) null else scheduledQuestions(questions, notebook) }
    LaunchedEffect(questions, scheduled) {
        if (queue != null || questions == null || scheduled == null) return@LaunchedEffect
        started = ArrayList(interleaveQuestions(questions.filter { it.key !in scheduled }, Random.nextInt()).map { it.key })
        queue = ArrayList(started)
    }
    val next = { remaining: List<String> ->
        queue = ArrayList(remaining)
        revealed = false
    }
    Scaffold(topBar = { AppBar("Practice · ${topicName(topic)}", onBack = onBack) }) { padding ->
        // Not scrolled as a whole: the answer takes the space left, and the grades stay under it.
        Column(Modifier.padding(padding).fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val ids = queue
            if (ids == null || questions == null || scheduled == null) {
                Text("Reading questions from saved notes…")
                return@Column
            }
            StatusLine(notice)
            val key = ids.firstOrNull()
            if (key == null) {
                Text(if (started.isEmpty()) "No new questions here: each saved question already has a card. They come back under Review when due."
                    else "Session complete: ${counted(started.count { it in scheduled }, "question")} graded. They come back under Review when due.")
                TextButton(onClick = onBack) { Text("Finish") }
                return@Column
            }
            val question = questions.firstOrNull { it.key == key }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(listOfNotNull(counted(ids.size, "question") + " left", question?.question?.level, question?.title).joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                TextButton(enabled = !saving, onClick = { next(ids.drop(1)) }) { Text(if (question == null) "Next" else "Skip") }
            }
            if (question == null) {
                Text("This question's bank was saved again since the session started, so it is skipped. Start a new session for the new version.")
                return@Column
            }
            Text(question.question.prompt, style = MaterialTheme.typography.titleLarge)
            if (!revealed) {
                Text("Answer it aloud or in writing first, then check it.")
                Button(onClick = { revealed = true }) { Text("Show answer") }
                return@Column
            }
            // Only a session question that has a card here was graded earlier in this session.
            val again = key in scheduled
            if (again) Text("Asked again: its first grade is saved. This grade only decides whether it comes back in this session.",
                style = MaterialTheme.typography.bodySmall)
            BankAnswer(question.question, question.documentId, question.path, sync, dao, Modifier.weight(1f), push)
            GradeBar(enabled = !saving) { grade ->
                saving = true
                scope.launch {
                    try {
                        val problem = if (again) null else withContext(NonCancellable) { gradeNewQuestion(sync, dao, question, grade) }
                        notice = problem
                        next(if (problem == null) afterGrade(ids, grade) else ids.drop(1))
                    } finally { saving = false }
                }
            }
        }
    }
}
