package com.reporead.android.library

import com.reporead.android.data.NotebookItem
import com.reporead.android.data.Passage
import com.reporead.android.data.ReviewRow
import com.reporead.android.reader.StudyQuestion
import com.reporead.android.reader.interviewBank
import org.jsoup.Jsoup
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.ceil
import kotlin.random.Random

internal data class ReviewSchedule(val dueAt: Long, val repetitions: Int, val intervalDays: Long, val ease: Double)

/** The grades offered, on SM-2's 0–5 scale: below 3 is a lapse. */
internal val GRADES = listOf(0 to "Again", 3 to "Hard", 4 to "Good", 5 to "Easy")

/** What each grade means against the answer shown, so a missed point is graded Again rather than Hard (Hard passes the card). */
internal const val GRADE_MEANING = "Again: wrong, or missed a point. Hard, Good, Easy: right, with serious effort, some hesitation, or none."

/**
 * The sole scheduler: SM-2 over the immutable log, ordered independently of arrival order (Wozniak's description,
 * super-memory.com/english/ol/sm2.htm). Intervals are 1, 6, then ceil(previous × ease) days; a grade of 3 or more
 * adjusts the ease, floored at 1.3; a grade below 3 restarts repetitions without changing the ease. A card is due from
 * the start of the local day in [zone] that its interval ends on, so an evening review is due the next morning.
 */
internal fun scheduleReviews(createdAt: Long, reviews: List<ReviewRow>, zone: ZoneId): ReviewSchedule {
    var state = ReviewSchedule(createdAt, 0, 0, 2.5)
    for (review in reviews.filter { it.rejection == null }.sortedWith(compareBy({ it.reviewedAt }, { it.mutationId }))) {
        require(review.grade in 0..5) { "Review ${review.mutationId} has grade ${review.grade}; expected 0..5" }
        val lapse = review.grade < 3
        val interval = when {
            lapse || state.repetitions == 0 -> 1L
            state.repetitions == 1 -> 6L
            else -> ceil(state.intervalDays * state.ease).toLong()
        }
        val distance = 5 - review.grade
        val ease = if (lapse) state.ease else (state.ease + 0.1 - distance * (0.08 + distance * 0.02)).coerceAtLeast(1.3)
        state = ReviewSchedule(dueDay(review.reviewedAt, interval, zone), if (lapse) 0 else state.repetitions + 1, interval, ease)
    }
    return state
}

/** The start of the local day [interval] days after [reviewedAt]'s; saturates instead of wrapping a distant date into the past. */
private fun dueDay(reviewedAt: Long, interval: Long, zone: ZoneId): Long {
    val day = Instant.ofEpochMilli(reviewedAt).atZone(zone).toLocalDate()
    if (interval > LocalDate.MAX.toEpochDay() - day.toEpochDay()) return Long.MAX_VALUE
    val start = day.plusDays(interval).atStartOfDay(zone).toInstant()
    return if (start.isAfter(Instant.ofEpochMilli(Long.MAX_VALUE))) Long.MAX_VALUE else start.toEpochMilli()
}

/**
 * A card a review session can show: the server did not refuse it and its note is still listed. A card that needs
 * checking is shown too, to be fixed in place; refused and removed ones are dealt with in Notebook.
 */
private fun reviewable(item: NotebookItem) = item.annotation.type == "CARD" && item.annotation.rejection == null && !item.annotation.cachedDeleted

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
 * What review holds now: [due] cards, of which [blocked] need checking first, and when none are due, the start of the
 * next day with cards due ([next], null with no cards) and how many fall due that day.
 */
internal data class ReviewOutlook(val due: Int, val blocked: Int, val next: Long?, val nextCount: Int)

internal fun reviewOutlook(items: List<NotebookItem>, reviews: List<ReviewRow>, now: Long, zone: ZoneId): ReviewOutlook {
    val times = dueTimes(items, reviews, zone)
    val due = times.filter { it.second <= now }
    val next = times.filter { it.second > now }.minOfOrNull { it.second }
    val nextDay = next?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
    return ReviewOutlook(due.size, due.count { (item) -> item.annotation.cardBlock(item.currentBlobSha, item.savedBlobSha) != null }, next,
        if (nextDay == null) 0 else times.count { it.second > now && Instant.ofEpochMilli(it.second).atZone(zone).toLocalDate() == nextDay })
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
