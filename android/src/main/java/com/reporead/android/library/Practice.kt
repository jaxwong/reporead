package com.reporead.android.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.reporead.android.OpenAt
import com.reporead.android.R
import com.reporead.android.Screen
import com.reporead.android.data.LibraryDao
import com.reporead.android.data.NotebookItem
import com.reporead.android.data.Passage
import com.reporead.android.data.PracticePage
import com.reporead.android.data.ReviewRow
import com.reporead.android.reader.BankAnswer
import com.reporead.android.reader.BankProblem
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
import java.util.UUID
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class PracticeQuestion(
    val documentId: Long,
    val title: String,
    val path: String,
    val blobSha: String,
    val question: StudyQuestion,
) {
    /** The question within its bank's saved version; a session names its questions by this. */
    val key: String
        get() = "$documentId:$blobSha:${question.blockId}"
}

/** One saved bank's warnings, identified by document and version rather than repository-relative path. */
internal data class BankProblems(
    val documentId: Long,
    val title: String,
    val path: String,
    val blobSha: String,
    val blocks: List<BankProblem>,
)

/** The listed interview banks (saved or not), the saved ones' questions, and their format warnings. */
internal data class Banks(
    val pages: List<PracticePage>,
    val questions: List<PracticeQuestion>,
    val unrecognized: List<BankProblems>,
)

internal fun readBanks(pages: List<PracticePage>): Banks {
    val banks = pages.filter { isInterviewBank(it.path) }
    val questions = mutableListOf<PracticeQuestion>()
    val unrecognized = mutableListOf<BankProblems>()
    for (page in banks) {
        val html = page.html ?: continue
        val blobSha =
            checkNotNull(page.blobSha) { "Saved Practice page ${page.documentId} has HTML without its blob SHA" }
        val bank = interviewBank(html, page.path)
        bank.questions.mapTo(questions) { PracticeQuestion(page.documentId, page.title, page.path, blobSha, it) }
        if (bank.unrecognized.isNotEmpty())
            unrecognized += BankProblems(page.documentId, page.title, page.path, blobSha, bank.unrecognized)
    }
    return Banks(banks, questions, unrecognized)
}

/**
 * Practice and Review sessions alternate notes: shuffled note groups, then one item per note per round, so remaining
 * items never disappear. [note] is each item's document.
 */
internal fun <T> interleaveByNote(items: List<T>, seed: Int, note: (T) -> Long): List<T> {
    val random = Random(seed)
    val groups = items.groupBy(note).values.shuffled(random).map { it.shuffled(random) }
    return (0 until (groups.maxOfOrNull { it.size } ?: 0)).flatMap { round ->
        groups.mapNotNull { it.getOrNull(round) }
    }
}

/**
 * The keys of the questions that already have a card on the review schedule ([reviewable]) — in the same bank, with the
 * same prompt and answer line — and so are not new. Two identical prompts with different answer lines stay two
 * questions. A question whose card the server refused is asked again.
 */
internal fun scheduledQuestions(questions: List<PracticeQuestion>, cards: List<NotebookItem>): Set<String> {
    val made =
        cards
            .filter(::reviewable)
            .map { Triple(it.annotation.documentId, it.annotation.question, it.annotation.drawn.exactText) }
            .toSet()
    return questions
        .filter { Triple(it.documentId, it.question.prompt, it.question.answer.text) in made }
        .map { it.key }
        .toSet()
}

internal fun inPracticeFolder(path: String, folder: String): Boolean {
    val prefix = folder.trim().trim('/')
    return prefix.isEmpty() || path.startsWith("$prefix/")
}

/** Every real ancestor folder is a topic, including folders whose notes have not been saved yet. */
internal fun practiceTopics(paths: List<String>): List<String> =
    paths
        .flatMap { path ->
            val parts = folderOf(path).split('/').filter { it.isNotEmpty() }
            parts.indices.map { parts.take(it + 1).joinToString("/") }
        }
        .distinct()
        .sortedBy { it.replace('/', '\u0000') }

private fun topicName(topic: String?) =
    when (topic) {
        null -> "All topics"
        "" -> "Repository root"
        else -> topic
    }

/**
 * Saves a new question's first grade on this phone: its card, whose answer is the question's Canonical line in the
 * saved version it was asked from, then that grade. The background sync sends both. Returns why it saved nothing or
 * only the card, or null. A question that already has a card (graded just before the screen was recreated) is not saved
 * twice.
 */
internal suspend fun gradeNewQuestion(sync: Sync, dao: LibraryDao, question: PracticeQuestion, grade: Int): String? {
    if (scheduledQuestions(listOf(question), dao.notebook().first()).isNotEmpty()) return null
    if (
        dao.note(question.documentId)?.blobSha != question.blobSha ||
            dao.document(question.documentId)?.blobSha != question.blobSha
    ) {
        return "Not saved: this question's bank changed since it was asked. Open the bank while online, or use Save all notes on this phone in its repository's menu, then practise it again."
    }
    val answer = question.question.answer
    val passage = Passage(question.blobSha, answer.blockId, 0, answer.text.length, answer.text)
    val card =
        sync.createAnnotation(passage, question.documentId, null, question.question.prompt)
            ?: return "Not saved: this question's bank is no longer saved on this phone."
    val graded =
        sync.recordReview(
            ReviewRow(
                mutationId = UUID.randomUUID().toString(),
                cardMutationId = card,
                grade = grade,
                reviewedAt = System.currentTimeMillis(),
                blobSha = question.blobSha,
                pending = true,
            )
        )
    return if (graded) null else "The card was saved but not its grade, so it is due now in Review cards."
}

/** Every listed interview bank and the saved ones' questions, read off the main thread; null until first read. */
@Composable
private fun rememberBanks(dao: LibraryDao): Banks? {
    val pages by dao.bankPages().collectAsState(null)
    val banks by
        produceState<Banks?>(null, pages) {
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
        outlook?.let { EntryRow("Review", outlookLine(it, cap), R.drawable.ic_refresh) { push(Screen.Review) } }
        StatusLine("New questions: each is asked once; your grade puts it on your review schedule.")
        val ready = banks
        val notebook = cards
        if (ready == null || notebook == null) {
            EmptyState("Reading questions from saved notes…")
            return@Column
        }
        val topics = ready.pages.mapNotNull { interviewTopic(it.path) }.distinct().sorted()
        if (topics.isEmpty()) {
            EmptyState(
                "No interview folders in your note lists. Practice asks the questions in folders named interview."
            )
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
        EntryRow(
            topicName(topic),
            "$new new · ${questions.size - new} on your schedule · ${pages.count { it.html != null }}/${pages.size} banks up to date",
            if (topic == null) R.drawable.ic_notes else R.drawable.ic_folder,
        ) {
            push(Screen.Practice(topic))
        }
        // A heading that slipped the format is named, so its questions do not vanish without a trace.
        val problems = if (topic == null) emptyList() else banks.unrecognized.filter { inTopic(it.path) }
        if (problems.isNotEmpty()) NotQuestions(problems, push)
    }
}

/** The topic's warnings, grouped by bank; tapping one opens its exact block in the version that was checked. */
@Composable
private fun NotQuestions(problems: List<BankProblems>, push: (Screen) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    ListItem(
        modifier = Modifier.clickable { open = !open },
        headlineContent = {
            Text(
                counted(problems.sumOf { it.blocks.size }, "heading is not a question", "headings are not questions"),
                color = MaterialTheme.colorScheme.error,
            )
        },
        supportingContent = { Text("Each needs one Canonical answer or path line under its own ### heading.") },
        trailingContent = { Text(if (open) "Hide" else "Show", style = MaterialTheme.typography.labelLarge) },
    )
    if (!open) return
    for (bank in problems) {
        Text(
            bank.path.substringAfterLast('/'),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
        )
        for (block in bank.blocks) {
            Text(
                block.text,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier =
                    Modifier.fillMaxWidth()
                        .clickable {
                            push(Screen.Reader(bank.documentId, bank.title, OpenAt.Block(bank.blobSha, block.blockId)))
                        }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}

/**
 * One topic's new questions, one at a time: the question alone, then — after an attempt — the bank's checklist and the
 * linked section, then a grade. The first grade makes the question a card (Review cards brings it back when due); one
 * graded Again comes back later in the session, unsaved, until it is answered right.
 */
@Composable
internal fun PracticeScreen(dao: LibraryDao, sync: Sync, topic: String?, push: (Screen) -> Unit, onBack: () -> Unit) {
    val banks = rememberBanks(dao)
    val cards by dao.notebook().collectAsState(null)
    // Chosen once from the topic's questions without a card, by key.
    var session by rememberSaveable { mutableStateOf<StudySession?>(null) }
    var saving by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val questions =
        remember(banks, topic) { banks?.questions?.filter { topic == null || interviewTopic(it.path) == topic } }
    val notebook = cards
    val scheduled =
        remember(questions, notebook) {
            if (questions == null || notebook == null) null else scheduledQuestions(questions, notebook)
        }
    LaunchedEffect(questions, scheduled) {
        if (session != null || questions == null || scheduled == null) return@LaunchedEffect
        session =
            StudySession(
                interleaveByNote(questions.filter { it.key !in scheduled }, Random.nextInt()) { it.documentId }
                    .map { it.key }
            )
    }
    Scaffold(topBar = { AppBar("Practice · ${topicName(topic)}", onBack = onBack) }) { padding ->
        // Not scrolled as a whole: the answer takes the space left, and the grades stay under it.
        Column(
            Modifier.padding(padding).fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val asked = session
            if (asked == null || questions == null || scheduled == null) {
                Text("Reading questions from saved notes…")
                return@Column
            }
            StatusLine(notice)
            // A session question that has a card here was graded in this session: the cards made, not a counter.
            val key = asked.current
            if (key == null) {
                Text(
                    if (asked.started.isEmpty())
                        "No new questions here: each question in its up-to-date banks already has a card. They come back under Review when due."
                    else
                        "Session complete: ${counted(asked.completed(scheduled), "question")} graded. They come back under Review when due."
                )
                TextButton(onClick = onBack) { Text("Finish") }
                return@Column
            }
            val question = questions.firstOrNull { it.key == key }
            SessionHeader(
                listOfNotNull(
                        counted(asked.queue.size, "question") + " left",
                        question?.question?.level,
                        question?.title,
                    )
                    .joinToString(" · "),
                skip = if (question == null) "Next" else "Skip",
                enabled = !saving,
            ) {
                session = asked.skip()
            }
            if (question == null) {
                Text(
                    "This question's bank changed since the session started, so it is skipped. Start a new session for the new version."
                )
                return@Column
            }
            Text(question.question.prompt, style = MaterialTheme.typography.titleLarge)
            if (!asked.revealed) {
                AttemptFirst { session = asked.reveal() }
                return@Column
            }
            val again = key in scheduled
            if (again) AskedAgain()
            BankAnswer(question.question, question.documentId, question.path, sync, dao, Modifier.weight(1f), push)
            GradeBar(enabled = !saving, gaps = if (again) null else gaps(null)) { grade ->
                saving = true
                scope.launch {
                    try {
                        val problem =
                            if (again) null
                            else withContext(NonCancellable) { gradeNewQuestion(sync, dao, question, grade) }
                        notice = problem
                        session = if (problem == null) asked.graded(grade) else asked.skip()
                    } finally {
                        saving = false
                    }
                }
            }
        }
    }
}
