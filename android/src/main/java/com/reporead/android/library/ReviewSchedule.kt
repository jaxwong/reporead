package com.reporead.android.library

import com.reporead.android.data.AnnotationRow
import com.reporead.android.data.AnnotationState
import com.reporead.android.data.AnnotationType
import com.reporead.android.data.NotebookItem
import com.reporead.android.data.ReviewRow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

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
