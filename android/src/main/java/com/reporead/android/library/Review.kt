package com.reporead.android.library

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.reporead.android.OpenAt
import com.reporead.android.Screen
import com.reporead.android.core.network.ApiException
import com.reporead.android.core.network.describe
import com.reporead.android.data.AnchorStatus
import com.reporead.android.data.AnnotationRow
import com.reporead.android.data.AnnotationState
import com.reporead.android.data.AnnotationType
import com.reporead.android.data.LibraryDao
import com.reporead.android.data.NotebookItem
import com.reporead.android.data.Passage
import com.reporead.android.data.ReviewRow
import com.reporead.android.reader.BankAnswer
import com.reporead.android.reader.StudyQuestion
import com.reporead.android.reader.interviewBank
import com.reporead.android.sync.Sync
import com.reporead.android.ui.AppBar
import com.reporead.android.ui.StatusLine
import com.reporead.android.ui.counted
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

/** A card's next due time and the [step] of [STEPS] its last saved grade put it on; null before its first grade. */
internal data class ReviewSchedule(val dueAt: Long, val step: Int?)

/** The days between reviews, one step per Good: a fixed ladder, so each grade's effect is predictable. */
internal val STEPS = listOf(1L, 3, 7, 14, 30, 60, 120)

/** The grades offered, stored on a 0–5 scale (the server accepts 0–5): below 3 is Again. */
internal val GRADES = listOf(0 to "Again", 3 to "Hard", 4 to "Good", 5 to "Easy")

/**
 * What each grade means against the answer shown, so a missed point is graded Again rather than Hard (Hard passes the
 * card).
 */
internal const val GRADE_MEANING =
    "Again: wrong, or missed a point. Hard, Good, Easy: right, with serious effort, some hesitation, or none."

/**
 * The step a [grade] moves a card on [step] (null when new, so Good puts it on the first step) to, capped at the last.
 */
internal fun nextStep(step: Int?, grade: Int): Int =
    when (grade) {
        0,
        1,
        2 -> 0 // Again: back to the first step.
        3 -> step ?: 0 // Hard: stay.
        4 -> ((step ?: -1) + 1).coerceAtMost(STEPS.lastIndex) // Good: climb one.
        5 -> ((step ?: -1) + 2).coerceAtMost(STEPS.lastIndex) // Easy: climb two.
        else -> error("Grade $grade; expected 0..5")
    }

/** The days until a card on [step] is due again, by each grade of [GRADES]. */
internal fun gaps(step: Int?): Map<Int, Long> = GRADES.associate { (grade) -> grade to STEPS[nextStep(step, grade)] }

/**
 * The sole scheduler: the ladder over [card]'s own grades in the immutable log, ordered independently of arrival order.
 * A card is due from the start of the local day in [zone] that its step's gap ends on, so an evening review is due the
 * next morning.
 */
internal fun scheduleReviews(card: AnnotationRow, log: List<ReviewRow>, zone: ZoneId): ReviewSchedule =
    log.filter { it.cardMutationId == card.mutationId && it.rejection == null }
        .sortedWith(compareBy({ it.reviewedAt }, { it.mutationId }))
        .fold(ReviewSchedule(card.createdAt, null)) { state, review ->
            require(review.grade in 0..5) { "Review ${review.mutationId} has grade ${review.grade}; expected 0..5" }
            val step = nextStep(state.step, review.grade)
            val day = Instant.ofEpochMilli(review.reviewedAt).atZone(zone).toLocalDate()
            ReviewSchedule(day.plusDays(STEPS[step]).atStartOfDay(zone).toInstant().toEpochMilli(), step)
        }

/**
 * A card a review session can show: the server did not refuse it and its note is still listed. A card that needs
 * checking is shown too, to be fixed in place; refused and removed ones are dealt with in Notebook.
 */
private fun reviewable(item: NotebookItem) =
    item.annotation.type == AnnotationType.CARD && item.annotation.state != AnnotationState.REFUSED && !item.deleted

/** When each reviewable card is next due. */
private fun dueTimes(
    items: List<NotebookItem>,
    reviews: List<ReviewRow>,
    zone: ZoneId,
): List<Pair<NotebookItem, Long>> =
    items.filter(::reviewable).map { it to scheduleReviews(it.annotation, reviews, zone).dueAt }

/** Due cards, interleaving shuffled note groups; the cap comes only from the server's notebook response. */
internal fun dueSession(
    items: List<NotebookItem>,
    reviews: List<ReviewRow>,
    now: Long,
    limit: Int,
    seed: Int,
    zone: ZoneId,
): List<NotebookItem> {
    require(limit > 0) { "Expected positive server sessionLimit, got $limit" }
    val due = dueTimes(items, reviews, zone).filter { it.second <= now }.map { it.first }
    return interleaveByNote(due, seed) { it.annotation.documentId }.take(limit)
}

/**
 * What review holds on [today]: [due] cards, of which [blocked] need checking first; the next day with cards due
 * ([next], null when none are scheduled) and how many fall due that day; and how many fall due in the [week] after
 * today.
 */
internal data class ReviewOutlook(
    val today: LocalDate,
    val due: Int,
    val blocked: Int,
    val next: LocalDate?,
    val nextCount: Int,
    val week: Int,
)

internal fun reviewOutlook(
    items: List<NotebookItem>,
    reviews: List<ReviewRow>,
    now: Long,
    zone: ZoneId,
): ReviewOutlook {
    val times = dueTimes(items, reviews, zone)
    val due = times.filter { it.second <= now }
    val laterDays = times.filter { it.second > now }.map { Instant.ofEpochMilli(it.second).atZone(zone).toLocalDate() }
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val next = laterDays.minOrNull()
    return ReviewOutlook(
        today,
        due.size,
        due.count { (item) -> item.annotation.cardBlock(item.deleted, item.currentBlobSha, item.savedBlobSha) != null },
        next,
        laterDays.count { it == next },
        laterDays.count { it <= today.plusDays(7) },
    )
}

/** Exact canonical context, not an inferred answer or rendered DOM text. Null means the saved page cannot verify it. */
internal fun answerContext(html: String, passage: Passage): String? {
    val block =
        Jsoup.parse(html).select("#note [data-block-id]").firstOrNull { it.attr("data-block-id") == passage.blockId }
            ?: return null
    val text = block.attr("data-anchor-text")
    if (
        passage.startOffset < 0 ||
            passage.endOffset > text.length ||
            passage.endOffset <= passage.startOffset ||
            text.substring(passage.startOffset, passage.endOffset) != passage.exactText
    )
        return null
    return text
}

/** A verified answer's block [context] and, for a card made from an interview bank question, that [question]. */
internal data class RevealedAnswer(val context: String, val question: StudyQuestion?)

internal fun revealedAnswer(html: String, path: String, passage: Passage): RevealedAnswer? =
    answerContext(html, passage)?.let { context ->
        RevealedAnswer(
            context,
            interviewBank(html, path).questions.firstOrNull { it.answer.blockId == passage.blockId },
        )
    }

/**
 * The grades in one row under the answer, so grading never needs scrolling past it; Again, the only failing grade, is
 * set apart from the three that pass. Each shows the days until the card is due again ([gaps], by grade); null when
 * this grade is not saved, so does not schedule.
 */
@Composable
internal fun GradeBar(enabled: Boolean, gaps: Map<Int, Long>?, onGrade: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(GRADE_MEANING, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((grade, label) in GRADES) {
                val modifier = Modifier.weight(1f)
                val padding = PaddingValues(horizontal = 4.dp)
                val content: @Composable () -> Unit = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(label)
                        gaps?.let {
                            Text(
                                counted(it.getValue(grade).toInt(), "day"),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
                if (grade < 3)
                    OutlinedButton(
                        enabled = enabled,
                        modifier = modifier,
                        contentPadding = padding,
                        onClick = { onGrade(grade) },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) {
                        content()
                    }
                else
                    Button(
                        enabled = enabled,
                        modifier = modifier,
                        contentPadding = padding,
                        onClick = { onGrade(grade) },
                    ) {
                        content()
                    }
            }
        }
    }
}

/** What review holds now, from this phone's cards and grades; null while they are read. */
@Composable
internal fun rememberReviewOutlook(dao: LibraryDao): ReviewOutlook? {
    val items by dao.notebook().collectAsState(null)
    val reviews by dao.reviews().collectAsState(null)
    val today = rememberStartOfToday()
    val ready = items
    val log = reviews
    return remember(ready, log, today) {
        if (ready == null || log == null) null else reviewOutlook(ready, log, System.currentTimeMillis(), today.zone)
    }
}

/**
 * The start of the phone's local day, in its time zone. Cards fall due at the start of a local day, so apart from cards
 * and grades only this changes an outlook: the date, and the zone that decides which day each grade fell on. It updates
 * at each local midnight while on screen, and whenever the screen starts, because the midnight timer does not run while
 * the phone sleeps and a zone change has no timer at all.
 */
@Composable
private fun rememberStartOfToday(): ZonedDateTime {
    var today by remember { mutableStateOf(startOfToday()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) today = startOfToday()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) {
        while (true) {
            val now = ZonedDateTime.now()
            delay(Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay(now.zone)).toMillis())
            today = startOfToday()
        }
    }
    return today
}

private fun startOfToday(): ZonedDateTime =
    ZoneId.systemDefault().let { zone -> LocalDate.now(zone).atStartOfDay(zone) }

/**
 * One line on what review holds: the due count, then what falls due next — tomorrow or on the next day with cards — and
 * in the 7 days after the outlook's day. [cap] is the server's session size.
 */
internal fun outlookLine(outlook: ReviewOutlook, cap: Int?): String {
    val next =
        outlook.next
            ?: return if (outlook.due > 0) dueParts(outlook, cap).joinToString(" · ")
            else "No cards yet: grade a question below, or make one from a passage in a note."
    val upcoming =
        if (next == outlook.today.plusDays(1)) "${outlook.nextCount} tomorrow"
        else
            "next on ${next.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()))}, ${counted(outlook.nextCount, "card")}"
    return (dueParts(outlook, cap) +
            upcoming +
            listOfNotNull(outlook.week.takeIf { it > outlook.nextCount }?.let { "$it in the next 7 days" }))
        .joinToString(" · ")
}

private fun dueParts(outlook: ReviewOutlook, cap: Int?): List<String> =
    if (outlook.due == 0) listOf("Nothing due")
    else
        listOfNotNull(
            "${outlook.due} due",
            cap?.takeIf { outlook.due > it }?.let { "$it per session" },
            outlook.blocked.takeIf { it > 0 }?.let { "$it to check first" },
        )

/** A card's answer from the saved copy of its version; null while reading or when that copy cannot verify it. */
@Composable
private fun rememberRevealed(item: NotebookItem, dao: LibraryDao): RevealedAnswer? {
    val row = item.annotation
    val context by
        produceState<Pair<Passage, RevealedAnswer>?>(null, row.mutationId, row.drawn) {
            val note = dao.note(row.documentId)
            value =
                if (note?.blobSha == row.drawn.blobSha)
                    withContext(Dispatchers.Default) {
                        revealedAnswer(note.html, note.path, row.drawn)?.let { row.drawn to it }
                    }
                else null
        }
    return context?.takeIf { it.first == row.drawn }?.second
}

/** What a card is checked against: a bank question's linked section, or the passage in its block's context. */
@Composable
private fun ColumnScope.CardAnswer(
    item: NotebookItem,
    answer: RevealedAnswer,
    sync: Sync,
    dao: LibraryDao,
    push: (Screen) -> Unit,
) {
    val question = answer.question
    if (question != null) {
        Text("Interview bank · ${item.path}", style = MaterialTheme.typography.labelMedium)
        BankAnswer(question, item.annotation.documentId, item.path, sync, dao, Modifier.weight(1f), push)
    } else
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
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
private fun ColumnScope.FixCard(
    item: NotebookItem,
    sync: Sync,
    dao: LibraryDao,
    push: (Screen) -> Unit,
    onConfirmed: () -> Unit,
) {
    val row = item.annotation
    val current = item.currentBlobSha
    val openNote =
        @Composable { label: String ->
            TextButton(
                onClick = { push(Screen.Reader(row.documentId, item.title, OpenAt.Highlight(row.mutationId))) }
            ) {
                Text(label)
            }
        }
    when {
        row.status == AnchorStatus.ORPHANED -> {
            Text(
                "Its answer is no longer in the note. Open the note, select the answer, then choose Reattach on this card in Highlights and notes."
            )
            openNote("Open note")
        }
        item.savedBlobSha != current -> {
            Text(
                "The note's current version isn't saved on this phone. Open the note while online to save it, then review this card."
            )
            openNote("Open note")
        }
        row.serverId == null ->
            Text(
                "This card hasn't reached the server yet, so it can't be confirmed. Open Library while online to sync it."
            )
        row.drawn.blobSha != current ->
            Text(
                "The server hasn't placed this answer in the note's current version yet. Open Library while online to sync, then review it."
            )
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
                Button(
                    enabled = !confirming,
                    onClick = {
                        confirming = true
                        scope.launch {
                            try {
                                sync.checkCard(row, current)
                                onConfirmed()
                            } catch (error: ApiException) {
                                Log.w(
                                    "RepoRead",
                                    "Card not confirmed from review; mutationId=${row.mutationId} code=${error.code}",
                                )
                                problem = "Not confirmed: ${error.describe()} Confirming needs the server."
                            } finally {
                                confirming = false
                            }
                        }
                    },
                ) {
                    Text("Answer is still right")
                }
                openNote("Open note")
            }
        }
    }
}

/**
 * The due cards, one at a time: the question alone, then — after an attempt — its answer, then a grade under it. A
 * card's first grade in the session is saved; one graded Again comes back later in the session, unsaved, until it is
 * answered right. A card that needs checking is shown with its fix instead.
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
        started =
            ArrayList(
                dueSession(ready, log, startedAt, cap, Random.nextInt(), ZoneId.systemDefault()).map {
                    it.annotation.mutationId
                }
            )
        queue = ArrayList(started)
    }
    val next = { remaining: List<String> ->
        queue = ArrayList(remaining)
        revealed = false
    }
    Scaffold(topBar = { AppBar("Review", onBack = onBack) }) { padding ->
        // Not scrolled as a whole: a revealed answer takes the space left, and the grades stay under it.
        Column(
            Modifier.padding(padding).fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (ready == null || log == null) {
                Text("Loading saved cards…")
                return@Column
            }
            StatusLine(notice)
            val refused = log.filter { it.rejection != null }
            if (refused.isNotEmpty())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // A refused grade never reached the server and is not part of the schedule; dismissing only forgets
                    // it.
                    Text(
                        "${counted(refused.size, "grade")} not accepted by the server, so not counted: ${refused.last().rejection}",
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { scope.launch { dao.dismissRefusedReviews() } }) { Text("Dismiss") }
                }
            val pending = log.count { it.pending && it.rejection == null }
            if (pending > 0)
                Text(
                    "$pending grades saved on this phone; they are sent once it is online.",
                    style = MaterialTheme.typography.bodySmall,
                )
            if (cap == null) {
                Text("Review starts after one sync with the server: open Library while signed in and online.")
                return@Column
            }
            val ids = queue
            if (ids == null) {
                Text("Choosing due cards…")
                return@Column
            }
            // The log, not the queue, says which cards this session saved a grade for, so a grade saved just before the
            // screen was recreated is never saved twice.
            val graded =
                log.filter { it.rejection == null && it.reviewedAt >= startedAt }.map { it.cardMutationId }.toSet()
            val id = ids.firstOrNull()
            if (id == null) {
                Text(
                    if (started.isEmpty())
                        outlookLine(reviewOutlook(ready, log, System.currentTimeMillis(), ZoneId.systemDefault()), cap)
                    else "Session complete: ${counted(started.count { it in graded }, "card")} reviewed."
                )
                TextButton(onClick = onBack) { Text("Finish") }
                return@Column
            }
            val item = ready.firstOrNull { it.annotation.mutationId == id }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    listOfNotNull("${counted(ids.size, "card")} left", item?.title).joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(enabled = !saving, onClick = { next(ids.drop(1)) }) { Text("Skip") }
            }
            if (item == null) {
                Text("This card was deleted.")
                return@Column
            }
            val row = item.annotation
            Text(checkNotNull(row.question), style = MaterialTheme.typography.titleLarge)
            if (item.annotation.cardBlock(item.deleted, item.currentBlobSha, item.savedBlobSha) != null) {
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
            if (again)
                Text(
                    "Asked again: its first grade is saved. This grade only decides whether it comes back in this session.",
                    style = MaterialTheme.typography.bodySmall,
                )
            val answer = rememberRevealed(item, dao)
            if (answer == null) Text("The saved page cannot verify this answer in context. Open the note to check it.")
            else CardAnswer(item, answer, sync, dao, push)
            TextButton(onClick = { push(Screen.Reader(row.documentId, item.title, OpenAt.Highlight(id))) }) {
                Text("Open in note")
            }
            // Each saved grade is one immutable local mutation; the background sync sends it.
            val step = scheduleReviews(row, log, ZoneId.systemDefault()).step
            if (answer != null)
                GradeBar(enabled = !saving, gaps = if (again) null else gaps(step)) { grade ->
                    if (again) next(afterGrade(ids, grade))
                    else {
                        saving = true
                        val review =
                            ReviewRow(
                                UUID.randomUUID().toString(),
                                id,
                                grade,
                                System.currentTimeMillis(),
                                row.drawn.blobSha,
                                true,
                            )
                        scope.launch {
                            try {
                                val saved = withContext(NonCancellable) { sync.recordReview(review) }
                                if (saved) next(afterGrade(ids, grade))
                                else {
                                    notice =
                                        "Card changed while answering; grade not saved. Open it to check the answer."
                                    next(ids.drop(1))
                                }
                            } finally {
                                saving = false
                            }
                        }
                    }
                }
        }
    }
}
