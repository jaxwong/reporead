package com.reporead.android.study

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.reporead.android.OpenAt
import com.reporead.android.R
import com.reporead.android.Screen
import com.reporead.android.database.AnnotationEntity
import com.reporead.android.database.CardBlock
import com.reporead.android.database.NotebookItem
import com.reporead.android.database.RepoReadDao
import com.reporead.android.database.ReviewEntity
import com.reporead.android.logging.DeviceLog
import com.reporead.android.sync.PhoneSync
import com.reporead.android.ui.AppBar
import com.reporead.android.ui.EmptyState
import com.reporead.android.ui.EntryRow
import com.reporead.android.ui.StatusLine
import com.reporead.android.ui.counted
import java.time.ZoneId
import java.util.UUID
import kotlin.random.Random
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where a review session's progress, and the grades it saved, are kept for process restoration. */
private const val SESSION = "session"
private const val SAVED_GRADES = "savedGrades"

/**
 * One review session, kept by its back-stack entry ([entryId]) and restored with it ([savedState]): the due cards it
 * asks, where it stands and the grade being saved. A rotation or a covering screen neither loses nor repeats a grade,
 * and a grade this phone accepted is committed even if the screen is left (PhoneSync).
 */
internal class ReviewViewModel(
    private val sync: PhoneSync,
    private val savedState: SavedStateHandle,
    private val entryId: String,
) : ViewModel() {
    data class State(
        val session: StudySession? = null,
        /**
         * The mutation ids of the grades this session asked to save. The review log's rows with these ids are this
         * session's, whatever time the server gave them.
         */
        val savedGrades: Set<String> = emptySet(),
        val saving: Boolean = false,
        val notice: String? = null,
    )

    private val _state =
        MutableStateFlow(
            State(
                session = savedState[SESSION],
                savedGrades = savedState.get<ArrayList<String>>(SAVED_GRADES).orEmpty().toSet(),
            )
        )
    val state: StateFlow<State> = _state.asStateFlow()

    private fun updateSession(nextSession: StudySession, notice: String? = _state.value.notice) {
        savedState[SESSION] = nextSession
        _state.update { it.copy(session = nextSession, saving = false, notice = notice) }
    }

    /** Chooses the session once, from the cards due now. */
    fun start(cards: List<NotebookItem>, reviewLog: List<ReviewEntity>) {
        if (_state.value.session != null) return
        updateSession(
            StudySession(
                dueSession(cards, reviewLog, System.currentTimeMillis(), Random.nextInt(), ZoneId.systemDefault()).map {
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
        if (stateSnapshot.saving) DeviceLog.info("Review skip not taken; entry=$entryId saving=true")
        else updateSession(session.skip())
    }

    /**
     * Each saved grade is one immutable local mutation; the background sync sends it. [answeredBlobSha] is the bank
     * version the question and answer were read from, so a grade is saved only while that version is current. A card
     * [wasAskedAgain] only decides whether it comes back in this session.
     */
    fun grade(annotation: AnnotationEntity, answeredBlobSha: String, wasAskedAgain: Boolean, grade: Int) {
        val stateSnapshot = _state.value
        val session = stateSnapshot.session ?: return
        if (session.current != annotation.mutationId) return
        if (wasAskedAgain) {
            updateSession(session.graded(grade))
            return
        }
        if (stateSnapshot.saving || !_state.compareAndSet(stateSnapshot, stateSnapshot.copy(saving = true))) {
            DeviceLog.info("Review grade not saved again; entry=$entryId mutationId=${annotation.mutationId}")
            return
        }
        DeviceLog.info("Review grade started; entry=$entryId mutationId=${annotation.mutationId}")
        val review =
            ReviewEntity(
                mutationId = UUID.randomUUID().toString(),
                cardMutationId = annotation.mutationId,
                grade = grade,
                reviewedAt = System.currentTimeMillis(),
                blobSha = answeredBlobSha,
                pending = true,
            )
        // Kept before the save starts, so a session restored after the grade was stored still knows it as its own.
        val savedGrades = _state.value.savedGrades + review.mutationId
        savedState[SAVED_GRADES] = ArrayList(savedGrades)
        _state.update { it.copy(savedGrades = savedGrades) }
        viewModelScope.launch {
            if (sync.gradeCard(review)) updateSession(session.graded(grade))
            else
                updateSession(
                    session.skip(),
                    "This card or its bank changed while answering; grade not saved.",
                )
        }
    }

    /** A refused grade never reached the server and is not in the schedule; dismissing only forgets it. */
    fun dismissRefused() {
        viewModelScope.launch { sync.dismissRefusedReviews() }
    }
}

/**
 * Review's first screen: what is due now, then the due cards of each topic, so a session can cover one topic when there
 * is no time for all of them. A topic with nothing due is left out.
 */
@Composable
internal fun ReviewTopicsScreen(dao: RepoReadDao, push: (Screen) -> Unit, onBack: () -> Unit) {
    val outlook = rememberReviewOutlook(dao)
    val cards = dao.notebook().collectAsStateWithLifecycle(null).value
    val reviewLog = dao.reviews().collectAsStateWithLifecycle(null).value
    Scaffold(topBar = { AppBar("Review", onBack = onBack) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (outlook == null || cards == null || reviewLog == null) {
                EmptyState("Loading saved cards…")
                return@Column
            }
            StatusLine(outlookLine(outlook))
            if (outlook.due == 0) return@Column
            // Keyed on the outlook too, which changes at local midnight, when more cards fall due.
            val dueTopics =
                remember(cards, reviewLog, outlook) {
                    dueByTopic(cards, reviewLog, System.currentTimeMillis(), ZoneId.systemDefault())
                }
            LazyColumn {
                item {
                    EntryRow(topicName(null), "${outlook.due} due", R.drawable.ic_notes) { push(Screen.Review(null)) }
                }
                items(dueTopics.keys.sorted(), key = { it }) { topic ->
                    EntryRow(topicName(topic), "${dueTopics.getValue(topic)} due", R.drawable.ic_folder) {
                        push(Screen.Review(topic))
                    }
                }
            }
        }
    }
}

/**
 * The due cards of [topic] (null: every topic), one at a time: the question alone, then — after an attempt — its answer
 * from the bank's current saved version, then a grade under it. A card's first grade in the session is saved; one
 * graded Again comes back later in the session, unsaved, until it is answered right. A card that cannot be graded is
 * shown with its fix instead.
 */
@Composable
internal fun ReviewScreen(
    dao: RepoReadDao,
    sync: PhoneSync,
    topic: String?,
    entryId: String,
    push: (Screen) -> Unit,
    onBack: () -> Unit,
) {
    val viewModel = viewModel { ReviewViewModel(sync, createSavedStateHandle(), entryId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val interviewBanks = rememberBanks(dao)
    val outlook = rememberReviewOutlook(dao)
    val cards = dao.notebook().collectAsStateWithLifecycle(null).value
    val reviewLog = dao.reviews().collectAsStateWithLifecycle(null).value
    LaunchedEffect(cards, reviewLog) {
        if (cards != null && reviewLog != null)
            viewModel.start(cards.filter { topic == null || interviewTopic(it.path) == topic }, reviewLog)
    }
    Scaffold(topBar = { AppBar("Review · ${topicName(topic)}", onBack = onBack) }) { padding ->
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
            val session = state.session
            if (session == null) {
                Text("Choosing due cards…")
                return@Column
            }
            // The cards this session saved a grade for, from the log.
            val gradedCardIds =
                reviewLog
                    .filter { it.rejection == null && it.mutationId in state.savedGrades }
                    .map { it.cardMutationId }
                    .toSet()
            val currentCardId = session.current
            if (currentCardId == null) {
                Text(
                    when {
                        session.started.isNotEmpty() ->
                            "Session complete: ${counted(session.completed(gradedCardIds), "card")} reviewed."
                        topic == null -> outlookLine(outlook)
                        else -> "Nothing due in ${topicName(topic)} now."
                    }
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
            if (bankQuestion != null) QuestionPrompt(bankQuestion.question)
            else Text(checkNotNull(annotation.question), style = MaterialTheme.typography.titleLarge)
            val cardBlock = annotation.cardBlock(card.deleted, card.currentBlobSha, card.savedBlobSha)
            if (cardBlock != null || bankQuestion == null) {
                FixCard(card, cardBlock ?: CardBlock.NOT_ASKED, push)
                return@Column
            }
            val wasAskedAgain = currentCardId in gradedCardIds
            key(currentCardId, bankQuestion.key) {
                AnswerSlot(session.revealed, viewModel::reveal) { visible, onPrepared ->
                    if (wasAskedAgain) AskedAgain()
                    Text("Interview bank · ${card.path}", style = MaterialTheme.typography.labelMedium)
                    BankAnswer(
                        bankQuestion.question,
                        visible,
                        annotation.documentId,
                        card.path,
                        dao,
                        Modifier.weight(1f),
                        push,
                        onPrepared,
                    )
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
                    GradeBar(
                        enabled = visible && !state.saving,
                        gaps = if (wasAskedAgain) null else gaps(scheduleStep),
                    ) { grade ->
                        viewModel.grade(annotation, bankQuestion.blobSha, wasAskedAgain, grade)
                    }
                }
            }
        }
    }
}
