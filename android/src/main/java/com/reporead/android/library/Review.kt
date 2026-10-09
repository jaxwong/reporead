package com.reporead.android.library

import com.reporead.android.data.NotebookItem
import com.reporead.android.data.Passage
import com.reporead.android.data.ReviewRow
import org.jsoup.Jsoup
import kotlin.math.ceil
import kotlin.random.Random

private const val DAY_MS = 86_400_000L
internal data class ReviewSchedule(val dueAt: Long, val repetitions: Int, val intervalDays: Long, val ease: Double)

/** The sole scheduler: SM-2 intervals/ease over the immutable log, ordered independently of arrival order. */
internal fun scheduleReviews(createdAt: Long, reviews: List<ReviewRow>): ReviewSchedule {
    var state = ReviewSchedule(createdAt, 0, 0, 2.5)
    for (review in reviews.filter { it.rejection == null }.sortedWith(compareBy({ it.reviewedAt }, { it.mutationId }))) {
        require(review.grade in 0..5) { "Review ${review.mutationId} has grade ${review.grade}; expected 0..5" }
        val interval = when {
            review.grade < 3 || state.repetitions == 0 -> 1L
            state.repetitions == 1 -> 6L
            else -> ceil(state.intervalDays * state.ease).toLong()
        }
        val distance = 5 - review.grade
        val ease = (state.ease + 0.1 - distance * (0.08 + distance * 0.02)).coerceAtLeast(1.3)
        // Saturate at the epoch-millisecond range instead of wrapping a distant due date into the past.
        val dueAt = if (interval > (Long.MAX_VALUE - review.reviewedAt.coerceAtLeast(0)) / DAY_MS) Long.MAX_VALUE
            else review.reviewedAt + interval * DAY_MS
        state = ReviewSchedule(dueAt, if (review.grade < 3) 0 else state.repetitions + 1, interval, ease)
    }
    return state
}

internal fun cardCheckReason(item: NotebookItem): String? {
    val row = item.annotation
    require(row.type == "CARD") { "Expected CARD, got ${row.type} for ${row.mutationId}" }
    return when {
        row.rejection != null -> "Card not synced: ${row.rejection}"
        item.removed -> "Note removed from the repository"
        row.status == "ORPHANED" -> "Check this card: reattach its answer"
        row.checkedBlobSha == null || row.checkedBlobSha != item.currentBlobSha || row.drawn.blobSha != item.currentBlobSha ||
            (row.cachedCurrentBlobSha != null && row.checkedBlobSha != row.cachedCurrentBlobSha) ->
            "Check this card: the note changed or its answer is unconfirmed"
        item.savedBlobSha != item.currentBlobSha -> "Save the current note before reviewing"
        else -> null
    }
}

/** Interleaves shuffled note groups; the cap comes only from the server's notebook response. */
internal fun dueSession(items: List<NotebookItem>, reviews: List<ReviewRow>, now: Long, limit: Int, seed: Int): List<NotebookItem> {
    require(limit > 0) { "Expected positive server sessionLimit, got $limit" }
    val logs = reviews.groupBy { it.cardMutationId }
    val due = items.filter { it.annotation.type == "CARD" && cardCheckReason(it) == null &&
        scheduleReviews(it.annotation.createdAt, logs[it.annotation.mutationId].orEmpty()).dueAt <= now }
    val random = Random(seed)
    val groups = due.groupBy { it.annotation.documentId }.values.shuffled(random).map { it.shuffled(random) }
    return (0 until (groups.maxOfOrNull { it.size } ?: 0)).flatMap { round -> groups.mapNotNull { it.getOrNull(round) } }.take(limit)
}

/** Exact canonical context, not an inferred answer or rendered DOM text. Null means the saved page cannot verify it. */
internal fun answerContext(html: String, passage: Passage): String? {
    val block = Jsoup.parse(html).select("#note [data-block-id]").firstOrNull { it.attr("data-block-id") == passage.blockId } ?: return null
    val text = block.attr("data-anchor-text")
    if (passage.startOffset < 0 || passage.endOffset > text.length || passage.endOffset <= passage.startOffset ||
        text.substring(passage.startOffset, passage.endOffset) != passage.exactText) return null
    return text
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
