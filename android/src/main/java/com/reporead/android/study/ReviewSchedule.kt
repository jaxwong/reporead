package com.reporead.android.study

import com.reporead.android.database.AnnotationEntity
import com.reporead.android.database.NotebookItem
import com.reporead.android.database.ReviewEntity
import com.reporead.android.database.reviewable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A card's next due time and the [step] of [STEPS] it is on: [FIRST_STEP] before its first grade. */
internal data class ReviewSchedule(val dueAt: Long, val step: Int)

/** The days between reviews, one step per Good: a fixed ladder, so each grade's effect is predictable. */
internal val STEPS = listOf(1L, 3, 7, 14, 30, 60, 120)

/**
 * Where a new card starts and Again returns one. A new card is on it, so its first grade gives Hard 1, Good 3 and Easy
 * 7 days, the same as a card graded Again before.
 */
internal const val FIRST_STEP = 0

/** The grades offered, stored on a 0–5 scale (the server accepts 0–5). */
internal val GRADES = listOf(0 to "Again", 3 to "Hard", 4 to "Good", 5 to "Easy")

/** Again, a miss: every grade below Hard. The one place that decides it; the server only stores grades. */
internal fun isMiss(grade: Int) = grade < 3

/**
 * What each grade means against the answer shown, so a missed point is graded Again rather than Hard (Hard passes the
 * card).
 */
internal const val GRADE_MEANING =
    "Again: wrong, or missed a point. Hard, Good, Easy: right, with serious effort, some hesitation, or none."

/** The step a [grade] moves a card on [step] to, capped at the last. */
internal fun nextStep(step: Int, grade: Int): Int =
    when {
        isMiss(grade) -> FIRST_STEP
        grade == 3 -> (step - 1).coerceAtLeast(FIRST_STEP) // Hard: step back one.
        grade == 4 -> (step + 1).coerceAtMost(STEPS.lastIndex) // Good: climb one.
        else -> (step + 2).coerceAtMost(STEPS.lastIndex) // Easy: climb two.
    }

/** The days until a card on [step] is due again, by each grade of [GRADES]. */
internal fun gaps(step: Int): Map<Int, Long> = GRADES.associate { (grade) -> grade to STEPS[nextStep(step, grade)] }

/**
 * The sole scheduler: the ladder over [card]'s own grades in the immutable log, ordered independently of arrival order.
 * A card is due from the start of the local day in [zone] that its step's gap ends on, so an evening review is due the
 * next morning.
 */
internal fun scheduleReviews(card: AnnotationEntity, log: List<ReviewEntity>, zone: ZoneId): ReviewSchedule =
    log.filter { it.cardMutationId == card.mutationId && it.rejection == null }
        .sortedWith(compareBy({ it.reviewedAt }, { it.mutationId }))
        .fold(ReviewSchedule(card.createdAt, FIRST_STEP)) { state, review ->
            val step = nextStep(state.step, review.grade)
            val day = Instant.ofEpochMilli(review.reviewedAt).atZone(zone).toLocalDate()
            ReviewSchedule(day.plusDays(STEPS[step]).atStartOfDay(zone).toInstant().toEpochMilli(), step)
        }

/**
 * When each reviewable card is next due. The log is grouped by card once: each card's own grades, not the whole log.
 */
private fun dueTimes(
    items: List<NotebookItem>,
    reviews: List<ReviewEntity>,
    zone: ZoneId,
): List<Pair<NotebookItem, Long>> {
    val byCard = reviews.groupBy { it.cardMutationId }
    return items.filter(::reviewable).map {
        it to scheduleReviews(it.annotation, byCard[it.annotation.mutationId].orEmpty(), zone).dueAt
    }
}

/** The cards in one review session. The phone builds sessions, offline too, so it owns their size (ADR-13). */
internal const val SESSION_LIMIT = 40

/** Due cards, interleaving shuffled note groups, at most [SESSION_LIMIT]. */
internal fun dueSession(
    items: List<NotebookItem>,
    reviews: List<ReviewEntity>,
    now: Long,
    seed: Int,
    zone: ZoneId,
): List<NotebookItem> {
    val due = dueTimes(items, reviews, zone).filter { it.second <= now }.map { it.first }
    return interleaveByNote(due, seed) { it.annotation.documentId }.take(SESSION_LIMIT)
}

/**
 * How many cards are due at [now] in each interview topic ([interviewTopic] of the card's bank), for choosing what to
 * review. A card outside an interview folder belongs to no topic and is counted only among all due cards.
 */
internal fun dueByTopic(
    cards: List<NotebookItem>,
    reviewLog: List<ReviewEntity>,
    now: Long,
    zone: ZoneId,
): Map<String, Int> =
    dueTimes(cards, reviewLog, zone)
        .filter { (_, dueAt) -> dueAt <= now }
        .mapNotNull { (card) -> interviewTopic(card.path) }
        .groupingBy { it }
        .eachCount()

/**
 * What review holds on [today]: [due] cards, of which [blocked] need their note saved first; the next day with cards
 * due ([next], null when none are scheduled) and how many fall due that day; and how many fall due in the [week] after
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
    reviews: List<ReviewEntity>,
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
