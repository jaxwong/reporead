package com.reporead.android.library

import com.reporead.android.data.AnnotationState
import com.reporead.android.data.NotebookItem
import com.reporead.android.data.Passage
import com.reporead.android.data.ReviewRow
import com.reporead.android.reader.StudyQuestion
import com.reporead.android.reader.interviewBank
import org.jsoup.Jsoup
import java.time.Instant
import java.time.ZoneId
import kotlin.random.Random

/** A card's next due time and the [step] of [STEPS] its last saved grade put it on; null before its first grade. */
internal data class ReviewSchedule(val dueAt: Long, val step: Int?)

/** The days between reviews, one step per Good: a fixed ladder, so each grade's effect is predictable. */
internal val STEPS = listOf(1L, 3, 7, 14, 30, 60, 120)

/** The grades offered, stored on a 0–5 scale (the server accepts 0–5): below 3 is Again. */
internal val GRADES = listOf(0 to "Again", 3 to "Hard", 4 to "Good", 5 to "Easy")

/** What each grade means against the answer shown, so a missed point is graded Again rather than Hard (Hard passes the card). */
internal const val GRADE_MEANING = "Again: wrong, or missed a point. Hard, Good, Easy: right, with serious effort, some hesitation, or none."

/**
 * The step a [grade] moves a card on [step] (null when new) to: Again goes back to the first step; Hard stays, Good climbs
 * one and Easy two, from one below the first for a new card, capped at the last.
 */
internal fun nextStep(step: Int?, grade: Int): Int = if (grade < 3) 0 else ((step ?: -1) + grade - 3).coerceIn(0, STEPS.lastIndex)

/** The days until a card on [step] is due again after each of [GRADES]. */
internal fun gaps(step: Int?): List<Long> = GRADES.map { (grade) -> STEPS[nextStep(step, grade)] }

/**
 * The sole scheduler: the ladder over the immutable log, ordered independently of arrival order. A card is due from the
 * start of the local day in [zone] that its step's gap ends on, so an evening review is due the next morning.
 */
internal fun scheduleReviews(createdAt: Long, reviews: List<ReviewRow>, zone: ZoneId): ReviewSchedule =
    reviews.filter { it.rejection == null }.sortedWith(compareBy({ it.reviewedAt }, { it.mutationId }))
        .fold(ReviewSchedule(createdAt, null)) { state, review ->
            require(review.grade in 0..5) { "Review ${review.mutationId} has grade ${review.grade}; expected 0..5" }
            val step = nextStep(state.step, review.grade)
            val day = Instant.ofEpochMilli(review.reviewedAt).atZone(zone).toLocalDate()
            ReviewSchedule(day.plusDays(STEPS[step]).atStartOfDay(zone).toInstant().toEpochMilli(), step)
        }

/**
 * A card a review session can show: the server did not refuse it and its note is still listed. A card that needs
 * checking is shown too, to be fixed in place; refused and removed ones are dealt with in Notebook.
 */
private fun reviewable(item: NotebookItem) = item.annotation.type == "CARD" && item.annotation.state != AnnotationState.REFUSED && !item.annotation.cachedDeleted

/** When each reviewable card is next due. */
private fun dueTimes(items: List<NotebookItem>, reviews: List<ReviewRow>, zone: ZoneId): List<Pair<NotebookItem, Long>> {
    val logs = reviews.groupBy { it.cardMutationId }
    return items.filter(::reviewable).map { it to scheduleReviews(it.annotation.createdAt, logs[it.annotation.mutationId].orEmpty(), zone).dueAt }
}

/** Due cards, interleaving shuffled note groups; the cap comes only from the server's notebook response. */
internal fun dueSession(items: List<NotebookItem>, reviews: List<ReviewRow>, now: Long, limit: Int, seed: Int, zone: ZoneId): List<NotebookItem> {
    require(limit > 0) { "Expected positive server sessionLimit, got $limit" }
    val due = dueTimes(items, reviews, zone).filter { it.second <= now }.map { it.first }
    val random = Random(seed)
    val groups = due.groupBy { it.annotation.documentId }.values.shuffled(random).map { it.shuffled(random) }
    return (0 until (groups.maxOfOrNull { it.size } ?: 0)).flatMap { round -> groups.mapNotNull { it.getOrNull(round) } }.take(limit)
}

/**
 * What review holds now: [due] cards, of which [blocked] need checking first; the start of the next day with cards due
 * ([next], null when none are scheduled) and how many fall due that day; and how many fall due in the [week] after today.
 */
internal data class ReviewOutlook(val due: Int, val blocked: Int, val next: Long?, val nextCount: Int, val week: Int)

internal fun reviewOutlook(items: List<NotebookItem>, reviews: List<ReviewRow>, now: Long, zone: ZoneId): ReviewOutlook {
    val times = dueTimes(items, reviews, zone)
    val due = times.filter { it.second <= now }
    val later = times.map { it.second }.filter { it > now }
    val next = later.minOrNull()
    val day = { at: Long -> Instant.ofEpochMilli(at).atZone(zone).toLocalDate() }
    val weekEnd = day(now).plusDays(8).atStartOfDay(zone).toInstant().toEpochMilli()
    return ReviewOutlook(due.size, due.count { (item) -> item.annotation.cardBlock(item.currentBlobSha, item.savedBlobSha) != null },
        next, if (next == null) 0 else later.count { day(it) == day(next) }, later.count { it < weekEnd })
}

/** Exact canonical context, not an inferred answer or rendered DOM text. Null means the saved page cannot verify it. */
internal fun answerContext(html: String, passage: Passage): String? {
    val block = Jsoup.parse(html).select("#note [data-block-id]").firstOrNull { it.attr("data-block-id") == passage.blockId } ?: return null
    val text = block.attr("data-anchor-text")
    if (passage.startOffset < 0 || passage.endOffset > text.length || passage.endOffset <= passage.startOffset ||
        text.substring(passage.startOffset, passage.endOffset) != passage.exactText) return null
    return text
}

/** A verified answer's block [context] and, for a card made from an interview bank question, that [question]. */
internal data class RevealedAnswer(val context: String, val question: StudyQuestion?)

internal fun revealedAnswer(html: String, path: String, passage: Passage): RevealedAnswer? = answerContext(html, passage)?.let { context ->
    RevealedAnswer(context, interviewBank(html, path).questions.firstOrNull { it.answer.blockId == passage.blockId })
}

/** Prefixing each source line preserves all characters, including blank lines, trailing spaces and CRLF. */
internal fun notebookMarkdown(items: List<NotebookItem>): String = buildString {
    append("# RepoRead notebook\n\n")
    for (item in items) {
        val row = item.annotation
        append("## ").append(item.title.replace('\n', ' ')).append("\n\n")
        append("Source: ").append(item.path.replace('\n', ' ')).append("\n\n")
        row.question?.let { append("Question:\n\n").append(it).append("\n\n") }
        append(row.drawn.exactText.split('\n').joinToString("\n") { "> $it" }).append("\n\n")
        row.note?.let { append("Note:\n\n").append(it).append("\n\n") }
        if (row.status == "ORPHANED") append("Status: orphaned\n\n")
    }
}
