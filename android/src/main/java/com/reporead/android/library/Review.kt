package com.reporead.android.library

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import com.reporead.android.data.CardBlock
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

/** A card's answer as the saved copy of its version shows it. */
private sealed interface Revealed {
    data object Reading : Revealed

    /** The saved copy is of another version, or does not hold the passage. */
    data object Unverifiable : Revealed

    data class Shown(val answer: RevealedAnswer) : Revealed
}

/** Reads a card's answer from the saved copy of its version, from when the card is first shown. */
@Composable
private fun rememberRevealed(item: NotebookItem, dao: LibraryDao): Revealed {
    val row = item.annotation
    val read by
        produceState<Pair<Passage, Revealed>?>(null, row.mutationId, row.drawn) {
            val note = dao.note(row.documentId)
            val answer =
                if (note?.blobSha == row.drawn.blobSha)
                    withContext(Dispatchers.Default) { revealedAnswer(note.html, note.path, row.drawn) }
                else null
            value = row.drawn to (answer?.let(Revealed::Shown) ?: Revealed.Unverifiable)
        }
    return read?.takeIf { it.first == row.drawn }?.second ?: Revealed.Reading
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
 * A due card that cannot be graded, for [block], with what fixes it from here: confirming its answer in the note's new
 * version (a server call, so online only), or opening the note to save that version, find or reattach the answer, or
 * delete the card. [onConfirmed] runs once the server has confirmed it.
 */
@Composable
private fun ColumnScope.FixCard(
    item: NotebookItem,
    block: CardBlock,
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
    when (block) {
        CardBlock.REFUSED -> {
            Text(
                "The server refused this card, so it can't be reviewed: ${row.rejection} Delete it in its note's Highlights and notes."
            )
            openNote("Open note")
        }
        CardBlock.REMOVED -> {
            Text(
                "Its note was removed from the repository, so this card can't be reviewed. Delete it in the note's Highlights and notes, or skip it."
            )
            openNote("Open note")
        }
        CardBlock.ORPHANED -> {
            Text(
                "Its answer is no longer in the note. Open the note, select the answer, then choose Reattach on this card in Highlights and notes."
            )
            openNote("Open note")
        }
        CardBlock.NOT_SAVED -> {
            Text(
                "The note's current version isn't saved on this phone. Open the note while online to save it, then review this card."
            )
            openNote("Open note")
        }
        CardBlock.UNSENT ->
            Text(
                "This card hasn't reached the server yet, so it can't be found in the note's new version. Open Library while online to sync it."
            )
        // Opening the note lists its highlights, which places the card; a sync does not.
        CardBlock.UNPLACED -> {
            Text(
                "The note changed since this card was made. Open the note while online to find its answer there, then confirm it here."
            )
            openNote("Open note")
        }
        CardBlock.UNCONFIRMED -> {
            var confirming by remember(row.mutationId) { mutableStateOf(false) }
            var problem by remember(row.mutationId) { mutableStateOf<String?>(null) }
            val scope = rememberCoroutineScope()
            fun confirm() {
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
            }
            Text("The note changed since this card was checked. Is this still its answer?")
            problem?.let { StatusLine(it) }
            when (val answer = rememberRevealed(item, dao)) {
                Revealed.Reading -> {}
                Revealed.Unverifiable ->
                    Text("The saved page cannot show this answer in context. Open the note to check it.")
                is Revealed.Shown -> CardAnswer(item, answer.answer, sync, dao, push)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(enabled = !confirming, onClick = ::confirm) { Text("Answer is still right") }
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
    // Chosen once from the due cards when the session starts.
    var session by rememberSaveable { mutableStateOf<StudySession?>(null) }
    var startedAt by rememberSaveable { mutableLongStateOf(0L) }
    var saving by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val ready = items
    val log = reviews
    val cap = limit
    LaunchedEffect(ready, log, cap) {
        if (session != null || ready == null || log == null || cap == null) return@LaunchedEffect
        startedAt = System.currentTimeMillis()
        session =
            StudySession(
                dueSession(ready, log, startedAt, cap, Random.nextInt(), ZoneId.systemDefault()).map {
                    it.annotation.mutationId
                }
            )
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
                    // A refused grade never reached the server and is not in the schedule; dismissing only forgets it.
                    Text(
                        "${counted(refused.size, "grade")} not accepted by the server, so not counted: ${refused.last().rejection}",
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { scope.launch { sync.dismissRefusedReviews() } }) { Text("Dismiss") }
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
            val asked = session
            if (asked == null) {
                Text("Choosing due cards…")
                return@Column
            }
            // The cards this session saved a grade for, from the log.
            val graded =
                log.filter { it.rejection == null && it.reviewedAt >= startedAt }.map { it.cardMutationId }.toSet()
            val id = asked.current
            if (id == null) {
                Text(
                    if (asked.started.isEmpty())
                        outlookLine(reviewOutlook(ready, log, System.currentTimeMillis(), ZoneId.systemDefault()), cap)
                    else "Session complete: ${counted(asked.completed(graded), "card")} reviewed."
                )
                TextButton(onClick = onBack) { Text("Finish") }
                return@Column
            }
            val item = ready.firstOrNull { it.annotation.mutationId == id }
            SessionHeader(
                listOfNotNull("${counted(asked.queue.size, "card")} left", item?.title).joinToString(" · "),
                skip = "Skip",
                enabled = !saving,
            ) {
                session = asked.skip()
            }
            if (item == null) {
                Text("This card was deleted.")
                return@Column
            }
            val row = item.annotation
            Text(checkNotNull(row.question), style = MaterialTheme.typography.titleLarge)
            val block = item.annotation.cardBlock(item.deleted, item.currentBlobSha, item.savedBlobSha)
            if (block != null) {
                // Confirmed, it goes to the back of the session like any card still to answer.
                FixCard(item, block, sync, dao, push, onConfirmed = { session = asked.requeue() })
                return@Column
            }
            // Read while the question is asked, so the answer is ready when it is revealed.
            val answer = rememberRevealed(item, dao)
            if (!asked.revealed) {
                AttemptFirst { session = asked.reveal() }
                return@Column
            }
            val again = id in graded
            if (again) AskedAgain()
            when (answer) {
                Revealed.Reading -> {}
                Revealed.Unverifiable ->
                    Text("The saved page cannot verify this answer in context. Open the note to check it.")
                is Revealed.Shown -> CardAnswer(item, answer.answer, sync, dao, push)
            }
            TextButton(onClick = { push(Screen.Reader(row.documentId, item.title, OpenAt.Highlight(id))) }) {
                Text("Open in note")
            }
            // Each saved grade is one immutable local mutation; the background sync sends it. A card asked again only
            // decides whether it comes back in this session.
            fun saveGrade(grade: Int) {
                if (again) {
                    session = asked.graded(grade)
                    return
                }
                saving = true
                val review =
                    ReviewRow(
                        mutationId = UUID.randomUUID().toString(),
                        cardMutationId = id,
                        grade = grade,
                        reviewedAt = System.currentTimeMillis(),
                        blobSha = row.drawn.blobSha,
                        pending = true,
                    )
                scope.launch {
                    try {
                        val saved = withContext(NonCancellable) { sync.recordReview(review) }
                        if (saved) session = asked.graded(grade)
                        else {
                            notice = "Card changed while answering; grade not saved. Open it to check the answer."
                            session = asked.skip()
                        }
                    } finally {
                        saving = false
                    }
                }
            }
            val step = scheduleReviews(row, log, ZoneId.systemDefault()).step
            if (answer is Revealed.Shown)
                GradeBar(enabled = !saving, gaps = if (again) null else gaps(step), onGrade = ::saveGrade)
        }
    }
}
