package com.reporead.android.library

import com.reporead.android.data.AnnotationRow
import com.reporead.android.data.NotebookItem
import com.reporead.android.data.Passage
import com.reporead.android.data.ReviewRow
import org.junit.Assert.*
import org.junit.Test

class ReviewTest {
    private val day = 86_400_000L
    private fun grade(id: Int, value: Int, at: Long) = ReviewRow("g$id", "card", value, at, "sha", false)
    private fun card(id: Int, document: Long = 1) = NotebookItem(AnnotationRow("m$id", id.toLong(), document, "sha", "b1", 0, 4,
        "text", null, 1, 0, false, null, type = "CARD", question = "Why?", checkedBlobSha = "sha"), "Note", "topic/a.md", "sha", "sha", false)

    @Test fun newCardsAreDueAndIntervalsFollowOneSixAndPriorEaseWithCeiling() {
        assertEquals(0L, scheduleReviews(0, emptyList()).dueAt)
        val log = listOf(grade(1, 4, 0), grade(2, 4, day), grade(3, 4, 7 * day))
        assertEquals(day, scheduleReviews(0, log.take(1)).dueAt)
        assertEquals(7 * day, scheduleReviews(0, log.take(2)).dueAt)
        assertEquals(22 * day, scheduleReviews(0, log).dueAt)
        val easy = listOf(grade(1, 5, 0), grade(2, 5, day), grade(3, 5, 7 * day))
        assertEquals(17L, scheduleReviews(0, easy).intervalDays)
    }

    @Test fun lapseResetsRepetitionsEaseHasAFloorAndArrivalOrderDoesNotChangeTheSchedule() {
        val log = (1..10).map { grade(it, 0, it * day) }
        assertEquals(1.3, scheduleReviews(0, log).ease, 0.00001)
        assertEquals(0, scheduleReviews(0, log).repetitions)
        assertEquals(11 * day, scheduleReviews(0, log).dueAt)
        assertEquals(scheduleReviews(0, log), scheduleReviews(0, log.reversed()))
        assertEquals(1L, scheduleReviews(0, log + grade(11, 4, 11 * day)).intervalDays)
        assertEquals(0L, scheduleReviews(0, listOf(grade(1, 4, 0).copy(rejection = "refused"))).dueAt)
    }

    @Test fun staleOrOrphanedOrUnconfirmedAnswersAndMissingSavedPagesAreNeverScheduled() {
        val ready = card(1)
        assertNull(cardCheckReason(ready))
        for (changed in listOf(ready.copy(currentBlobSha = "new"), ready.copy(currentBlobSha = null),
            ready.copy(annotation = ready.annotation.copy(cachedCurrentBlobSha = "server-newer")), ready.copy(savedBlobSha = null), ready.copy(removed = true),
            ready.copy(annotation = ready.annotation.copy(checkedBlobSha = null)),
            ready.copy(annotation = ready.annotation.copy(status = "ORPHANED")))) {
            assertNotNull(cardCheckReason(changed))
            assertTrue(dueSession(listOf(changed), emptyList(), 0, 40, 1).isEmpty())
        }
    }

    @Test fun sessionsRespectTheServerCapInterleaveNotesAndExcludeNotDueCards() {
        val items = (1..50).map { card(it, if (it <= 30) 1 else 2) }
        val session = dueSession(items, emptyList(), 0, 40, 7)
        assertEquals(40, session.size)
        assertEquals(setOf(1L, 2L), session.take(2).map { it.annotation.documentId }.toSet())
        assertEquals(session, dueSession(items, emptyList(), 0, 40, 7))
        val reviews = listOf(grade(1, 4, 0).copy(cardMutationId = "m1"))
        assertFalse(dueSession(items, reviews, 0, 40, 7).any { it.annotation.mutationId == "m1" })
        assertTrue(dueSession(emptyList(), emptyList(), 0, 40, 7).isEmpty())
    }

    @Test fun contextChecksOffsetsAndUtf16TextWithoutInventingAnAnswer() {
        val html = "<main id=note><p data-block-id=b1 data-anchor-text='Before 😀 &amp; after'>Before</p></main>"
        assertEquals("Before 😀 & after", answerContext(html, Passage("sha", "b1", 7, 9, "😀")))
        assertNull(answerContext(html, Passage("sha", "b1", 7, 9, "xx")))
        assertNull(answerContext(html, Passage("sha", "b9", 7, 9, "😀")))
    }

    @Test fun exportedBlockquotesRoundTripEveryCharacter() {
        val quote = "  😀 **bold** & <code>\r\n\n> nested\ntrailing  \n"
        val item = card(1).let { it.copy(annotation = it.annotation.copy(exactText = quote, endOffset = quote.length)) }
        val markdown = notebookMarkdown(listOf(item))
        val recovered = markdown.substringAfter("Source: topic/a.md\n\nQuestion:\n\nWhy?\n\n").removeSuffix("\n\n")
            .split('\n').joinToString("\n") { it.removePrefix("> ") }
        assertEquals(quote, recovered)
        assertEquals("# RepoRead notebook\n\n", notebookMarkdown(emptyList()))
    }
}
