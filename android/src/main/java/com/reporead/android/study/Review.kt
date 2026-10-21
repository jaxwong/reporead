package com.reporead.android.study

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.reporead.android.OpenAt
import com.reporead.android.Screen
import com.reporead.android.core.log.AppLog
import com.reporead.android.core.network.ApiException
import com.reporead.android.core.network.describe
import com.reporead.android.data.AnnotationRow
import com.reporead.android.data.CardBlock
import com.reporead.android.data.LibraryDao
import com.reporead.android.data.NotebookItem
import com.reporead.android.data.Passage
import com.reporead.android.data.ReviewRow
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
            parseInterviewBank(html, path).questions.firstOrNull { it.answer.blockId == passage.blockId },
        )
    }

/**
 * The bank question a card's Canonical line asks now, so the card follows its current prompt; null for unmatched and
 * hand-written cards, which keep their saved one.
 */
internal fun reviewQuestion(item: NotebookItem, questions: List<PracticeQuestion>): StudyQuestion? =
    questions
        .firstOrNull {
            it.documentId == item.annotation.documentId && it.question.answer.text == item.annotation.drawn.exactText
        }
        ?.question

/** What review holds now, from this phone's cards and grades; null while they are read. */
@Composable
internal fun rememberReviewOutlook(dao: LibraryDao): ReviewOutlook? {
    val cards = dao.notebook().collectAsStateWithLifecycle(null).value
    val reviewLog = dao.reviews().collectAsStateWithLifecycle(null).value
    val today = rememberStartOfToday()
    return remember(cards, reviewLog, today) {
        if (cards == null || reviewLog == null) null
        else reviewOutlook(cards, reviewLog, System.currentTimeMillis(), today.zone)
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
 * in the 7 days after the outlook's day. [sessionLimit] is the server's session size.
 */
internal fun outlookLine(outlook: ReviewOutlook, sessionLimit: Int?): String {
    val next =
        outlook.next
            ?: return if (outlook.due > 0) dueParts(outlook, sessionLimit).joinToString(" · ")
            else "No cards yet: grade a question below, or make one from a passage in a note."
    val upcoming =
        if (next == outlook.today.plusDays(1)) "${outlook.nextCount} tomorrow"
        else
            "next on ${next.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()))}, ${counted(outlook.nextCount, "card")}"
    return (dueParts(outlook, sessionLimit) +
            upcoming +
            listOfNotNull(outlook.week.takeIf { it > outlook.nextCount }?.let { "$it in the next 7 days" }))
        .joinToString(" · ")
}

private fun dueParts(outlook: ReviewOutlook, sessionLimit: Int?): List<String> =
    if (outlook.due == 0) listOf("Nothing due")
    else
        listOfNotNull(
            "${outlook.due} due",
            sessionLimit?.takeIf { outlook.due > it }?.let { "$it per session" },
            outlook.blocked.takeIf { it > 0 }?.let { "$it to check first" },
        )

/** A card's answer as the saved copy of its version shows it. */
private sealed interface Revealed {
    data object Reading : Revealed

    /** The saved copy is of another version, or does not hold the passage. */
    data object Unverifiable : Revealed

    data class Shown(val answer: RevealedAnswer) : Revealed
}

/** A loaded answer paired with the passage it was loaded for, so a stale coroutine result is never shown. */
private data class LoadedAnswer(val passage: Passage, val revealed: Revealed)

/** Reads a card's answer from the saved copy of its version, from when the card is first shown. */
@Composable
private fun rememberRevealed(card: NotebookItem, dao: LibraryDao): Revealed {
    val annotation = card.annotation
    val loadedAnswer by
        produceState<LoadedAnswer?>(null, annotation.mutationId, annotation.drawn) {
            val savedPage = dao.note(annotation.documentId)
            val revealedAnswer =
                if (savedPage?.blobSha == annotation.drawn.blobSha)
                    withContext(Dispatchers.Default) {
                        revealedAnswer(savedPage.html, savedPage.path, annotation.drawn)
                    }
                else null
            value =
                LoadedAnswer(
                    annotation.drawn,
                    revealedAnswer?.let(Revealed::Shown) ?: Revealed.Unverifiable,
                )
        }
    return loadedAnswer?.takeIf { it.passage == annotation.drawn }?.revealed ?: Revealed.Reading
}

/** What a card is checked against: a bank question's answer, or the passage in its block's context. */
@Composable
private fun ColumnScope.CardAnswer(
    card: NotebookItem,
    revealedAnswer: RevealedAnswer,
    visible: Boolean,
    dao: LibraryDao,
    push: (Screen) -> Unit,
    onPrepared: () -> Unit,
) {
    val question = revealedAnswer.question
    if (question != null) {
        Text("Interview bank · ${card.path}", style = MaterialTheme.typography.labelMedium)
        BankAnswer(
            question,
            visible,
            card.annotation.documentId,
            card.path,
            dao,
            Modifier.weight(1f),
            push,
            onPrepared,
        )
    } else {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState(), enabled = visible),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(card.annotation.drawn.exactText, style = MaterialTheme.typography.titleLarge)
            Text("Context · ${card.path}", style = MaterialTheme.typography.labelMedium)
            Text(revealedAnswer.context)
        }
        LaunchedEffect(revealedAnswer) { onPrepared() }
    }
}

/**
 * A due card that cannot be graded, for [block], with what fixes it from here: confirming its answer in the note's new
 * version (a server call, so online only: [onConfirm], [confirming] while it runs, [problem] if it failed), or opening
 * the note to save that version, find or reattach the answer, or delete the card.
 */
@Composable
private fun ColumnScope.FixCard(
    card: NotebookItem,
    cardBlock: CardBlock,
    confirming: Boolean,
    problem: String?,
    dao: LibraryDao,
    push: (Screen) -> Unit,
    onConfirm: () -> Unit,
) {
    val annotation = card.annotation
    val openNote =
        @Composable { label: String ->
            TextButton(
                onClick = {
                    push(Screen.Reader(annotation.documentId, card.title, OpenAt.Highlight(annotation.mutationId)))
                }
            ) {
                Text(label)
            }
        }
    when (cardBlock) {
        CardBlock.REFUSED -> {
            Text(
                "The server refused this card, so it can't be reviewed: ${annotation.rejection} Delete it in its note's Highlights and notes."
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
            Text("The note changed since this card was checked. Is this still its answer?")
            problem?.let { StatusLine(it) }
            when (val revealedAnswer = rememberRevealed(card, dao)) {
                Revealed.Reading -> {}
                Revealed.Unverifiable ->
                    Text("The saved page cannot show this answer in context. Open the note to check it.")
                is Revealed.Shown -> CardAnswer(card, revealedAnswer.answer, true, dao, push, onPrepared = {})
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(enabled = !confirming, onClick = onConfirm) { Text("Answer is still right") }
                openNote("Open note")
            }
        }
    }
}

/** Where a review session's progress, and when it started, are kept for process restoration. */
private const val SESSION = "session"
private const val STARTED_AT = "startedAt"

/**
 * One review session, kept by its back-stack entry ([entryId]) and restored with it ([saved]): the due cards it asks,
 * where it stands, the grade being saved and a card being confirmed. A rotation or a covering screen neither loses nor
 * repeats either, and a grade or confirmation the server or this phone accepted is committed even if the screen is left
 * (Sync).
 */
internal class ReviewViewModel(
    private val sync: Sync,
    private val savedState: SavedStateHandle,
    private val entryId: String,
) : ViewModel() {
    data class State(
        val session: StudySession? = null,
        /** When the session started; the grades saved since are this session's. */
        val startedAt: Long = 0,
        val saving: Boolean = false,
        val notice: String? = null,
        /** The card whose answer is being confirmed with the server. */
        val confirming: String? = null,
        /** Why a card's confirmation failed, by card. */
        val problems: Map<String, String> = emptyMap(),
    )

    private val _state =
        MutableStateFlow(State(session = savedState[SESSION], startedAt = savedState.get<Long>(STARTED_AT) ?: 0))
    val state: StateFlow<State> = _state.asStateFlow()

    private fun updateSession(nextSession: StudySession, notice: String? = _state.value.notice) {
        savedState[SESSION] = nextSession
        _state.update { it.copy(session = nextSession, saving = false, notice = notice) }
    }

    /** Chooses the session once, from the cards due now. */
    fun start(cards: List<NotebookItem>, reviewLog: List<ReviewRow>, sessionLimit: Int) {
        if (_state.value.session != null) return
        val startedAt = System.currentTimeMillis()
        savedState[STARTED_AT] = startedAt
        _state.update { it.copy(startedAt = startedAt) }
        updateSession(
            StudySession(
                dueSession(cards, reviewLog, startedAt, sessionLimit, Random.nextInt(), ZoneId.systemDefault()).map {
                    it.annotation.mutationId
                }
            )
        )
    }

    fun reveal() {
        _state.value.session?.let { updateSession(it.reveal()) }
    }

    fun skip() {
        val stateSnapshot = _state.value
        val session = stateSnapshot.session ?: return
        if (stateSnapshot.saving) AppLog.info("Review skip not taken; entry=$entryId saving=true")
        else updateSession(session.skip())
    }

    /**
     * Each saved grade is one immutable local mutation; the background sync sends it. A card [wasAskedAgain] only
     * decides whether it comes back in this session.
     */
    fun grade(annotation: AnnotationRow, wasAskedAgain: Boolean, grade: Int) {
        val stateSnapshot = _state.value
        val session = stateSnapshot.session ?: return
        if (session.current != annotation.mutationId) return
        if (wasAskedAgain) {
            updateSession(session.graded(grade))
            return
        }
        if (stateSnapshot.saving || !_state.compareAndSet(stateSnapshot, stateSnapshot.copy(saving = true))) {
            AppLog.info("Review grade not saved again; entry=$entryId mutationId=${annotation.mutationId}")
            return
        }
        AppLog.info("Review grade started; entry=$entryId mutationId=${annotation.mutationId}")
        val review =
            ReviewRow(
                mutationId = UUID.randomUUID().toString(),
                cardMutationId = annotation.mutationId,
                grade = grade,
                reviewedAt = System.currentTimeMillis(),
                blobSha = annotation.drawn.blobSha,
                pending = true,
            )
        viewModelScope.launch {
            if (sync.recordReview(review)) updateSession(session.graded(grade))
            else
                updateSession(
                    session.skip(),
                    "Card changed while answering; grade not saved. Open it to check the answer.",
                )
        }
    }

    /** Online only: the server checks [annotation]'s answer in [blobSha]; confirmed, it goes to the session's back. */
    fun confirmCard(annotation: AnnotationRow, blobSha: String) {
        val stateSnapshot = _state.value
        if (
            stateSnapshot.confirming != null ||
                !_state.compareAndSet(stateSnapshot, stateSnapshot.copy(confirming = annotation.mutationId))
        ) {
            AppLog.info("Card confirmation not started; entry=$entryId confirming=${stateSnapshot.confirming}")
            return
        }
        AppLog.info("Card confirmation started; entry=$entryId mutationId=${annotation.mutationId}")
        viewModelScope.launch {
            try {
                sync.checkCard(annotation, blobSha)
                _state.update { it.copy(confirming = null, problems = it.problems - annotation.mutationId) }
                _state.value.session
                    ?.takeIf { it.current == annotation.mutationId }
                    ?.let { updateSession(it.requeue()) }
            } catch (failure: ApiException) {
                AppLog.warn("Card not confirmed from review; mutationId=${annotation.mutationId} code=${failure.code}")
                val problem = "Not confirmed: ${failure.describe()} Confirming needs the server."
                _state.update {
                    it.copy(confirming = null, problems = it.problems + (annotation.mutationId to problem))
                }
            }
        }
    }

    /** A refused grade never reached the server and is not in the schedule; dismissing only forgets it. */
    fun dismissRefused() {
        viewModelScope.launch { sync.dismissRefusedReviews() }
    }
}

/**
 * The due cards, one at a time: the question alone, then — after an attempt — its answer, then a grade under it. A
 * card's first grade in the session is saved; one graded Again comes back later in the session, unsaved, until it is
 * answered right. A card that needs checking is shown with its fix instead.
 */
@Composable
internal fun ReviewScreen(dao: LibraryDao, sync: Sync, entryId: String, push: (Screen) -> Unit, onBack: () -> Unit) {
    val viewModel = viewModel { ReviewViewModel(sync, createSavedStateHandle(), entryId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val interviewBanks = rememberBanks(dao)
    val outlook = rememberReviewOutlook(dao)
    val cards = dao.notebook().collectAsStateWithLifecycle(null).value
    val reviewLog = dao.reviews().collectAsStateWithLifecycle(null).value
    val sessionLimit = dao.reviewLimit().collectAsStateWithLifecycle(null).value
    LaunchedEffect(cards, reviewLog, sessionLimit) {
        if (cards != null && reviewLog != null && sessionLimit != null) viewModel.start(cards, reviewLog, sessionLimit)
    }
    Scaffold(topBar = { AppBar("Review", onBack = onBack) }) { padding ->
        // Not scrolled as a whole: a revealed answer takes the space left, and the grades stay under it.
        Column(
            Modifier.padding(padding).fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (cards == null || reviewLog == null || interviewBanks == null || outlook == null) {
                Text("Loading saved cards…")
                return@Column
            }
            StatusLine(state.notice)
            val refusedReviews = reviewLog.filter { it.rejection != null }
            if (refusedReviews.isNotEmpty())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${counted(refusedReviews.size, "grade")} not accepted by the server, so not counted: ${refusedReviews.last().rejection}",
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = viewModel::dismissRefused) { Text("Dismiss") }
                }
            val pendingReviewCount = reviewLog.count { it.pending && it.rejection == null }
            if (pendingReviewCount > 0)
                Text(
                    "$pendingReviewCount grades saved on this phone; they are sent once it is online.",
                    style = MaterialTheme.typography.bodySmall,
                )
            if (sessionLimit == null) {
                Text("Review starts after one sync with the server: open Library while signed in and online.")
                return@Column
            }
            val session = state.session
            if (session == null) {
                Text("Choosing due cards…")
                return@Column
            }
            // The cards this session saved a grade for, from the log.
            val gradedCardIds =
                reviewLog
                    .filter { it.rejection == null && it.reviewedAt >= state.startedAt }
                    .map { it.cardMutationId }
                    .toSet()
            val currentCardId = session.current
            if (currentCardId == null) {
                Text(
                    if (session.started.isEmpty()) outlookLine(outlook, sessionLimit)
                    else "Session complete: ${counted(session.completed(gradedCardIds), "card")} reviewed."
                )
                TextButton(onClick = onBack) { Text("Finish") }
                return@Column
            }
            val card = cards.firstOrNull { it.annotation.mutationId == currentCardId }
            SessionHeader(
                listOfNotNull("${counted(session.queue.size, "card")} left", card?.title).joinToString(" · "),
                skip = "Skip",
                enabled = !state.saving,
                onSkip = viewModel::skip,
            )
            if (card == null) {
                Text("This card was deleted.")
                return@Column
            }
            val annotation = card.annotation
            val bankQuestion = reviewQuestion(card, interviewBanks.questions)
            if (bankQuestion != null) QuestionPrompt(bankQuestion)
            else Text(checkNotNull(annotation.question), style = MaterialTheme.typography.titleLarge)
            val cardBlock = annotation.cardBlock(card.deleted, card.currentBlobSha, card.savedBlobSha)
            if (cardBlock != null) {
                FixCard(
                    card,
                    cardBlock,
                    confirming = state.confirming == annotation.mutationId,
                    problem = state.problems[annotation.mutationId],
                    dao,
                    push,
                    onConfirm = { viewModel.confirmCard(annotation, card.currentBlobSha) },
                )
                return@Column
            }
            // Read while the question is asked, so the answer is ready when it is revealed.
            val revealedAnswer = rememberRevealed(card, dao)
            val wasAskedAgain = currentCardId in gradedCardIds
            key(currentCardId, annotation.drawn) {
                AnswerSlot(session.revealed, viewModel::reveal) { visible, onPrepared ->
                    if (wasAskedAgain) AskedAgain()
                    when (revealedAnswer) {
                        Revealed.Reading -> Spacer(Modifier.weight(1f))
                        Revealed.Unverifiable -> {
                            Text("The saved page cannot verify this answer in context. Open the note to check it.")
                            LaunchedEffect(revealedAnswer) { onPrepared() }
                        }
                        is Revealed.Shown -> CardAnswer(card, revealedAnswer.answer, visible, dao, push, onPrepared)
                    }
                    TextButton(
                        enabled = visible,
                        onClick = {
                            push(
                                Screen.Reader(
                                    annotation.documentId,
                                    card.title,
                                    OpenAt.Highlight(currentCardId),
                                )
                            )
                        },
                    ) {
                        Text("Open in note")
                    }
                    val scheduleStep = scheduleReviews(annotation, reviewLog, ZoneId.systemDefault()).step
                    if (revealedAnswer is Revealed.Shown)
                        GradeBar(
                            enabled = visible && !state.saving,
                            gaps = if (wasAskedAgain) null else gaps(scheduleStep),
                        ) { grade ->
                            viewModel.grade(annotation, wasAskedAgain, grade)
                        }
                }
            }
        }
    }
}
