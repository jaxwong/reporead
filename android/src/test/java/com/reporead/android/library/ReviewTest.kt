package com.reporead.android.library

import com.reporead.android.data.AnnotationRow
import com.reporead.android.data.AnnotationState
import com.reporead.android.data.NotebookItem
import com.reporead.android.data.Passage
import com.reporead.android.data.ReviewRow
import com.reporead.android.reader.AnswerLink
import com.reporead.android.reader.StudyAnswer
import com.reporead.android.reader.StudyQuestion
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class ReviewTest {
    private val day = 86_400_000L
    private val utc = ZoneOffset.UTC
    private fun scheduleReviews(createdAt: Long, reviews: List<ReviewRow>) = scheduleReviews(createdAt, reviews, utc)
    private fun dueSession(items: List<NotebookItem>, reviews: List<ReviewRow>, now: Long, limit: Int, seed: Int) =
        dueSession(items, reviews, now, limit, seed, utc)
    private fun grade(id: Int, value: Int, at: Long) = ReviewRow("g$id", "card", value, at, "sha", false)
    private fun card(id: Int, document: Long = 1) = NotebookItem(AnnotationRow("m$id", id.toLong(), document, "sha", "b1", 0, 4,
        "text", null, 1, 0, AnnotationState.ACKNOWLEDGED, null, type = "CARD", question = "Why?", checkedBlobSha = "sha"), "Note", "topic/a.md", "sha", "sha")

    @Test fun newCardsAreDueAndGoodClimbsTheLadderOneStepAndEasyTwo() {
        assertEquals(ReviewSchedule(0, null), scheduleReviews(0, emptyList()))
        val good = listOf(grade(1, 4, 0), grade(2, 4, day), grade(3, 4, 4 * day))
        assertEquals(ReviewSchedule(day, 0), scheduleReviews(0, good.take(1)))
        assertEquals(ReviewSchedule(4 * day, 1), scheduleReviews(0, good.take(2)))
        assertEquals(ReviewSchedule(11 * day, 2), scheduleReviews(0, good))
        val easy = listOf(grade(1, 5, 0), grade(2, 5, 3 * day), grade(3, 5, 17 * day))
        assertEquals(ReviewSchedule(77 * day, 5), scheduleReviews(0, easy))
    }

    @Test fun againGoesBackToTheFirstStepHardStaysAndArrivalOrderDoesNotChangeTheSchedule() {
        val good = listOf(grade(1, 4, 0), grade(2, 4, day), grade(3, 4, 4 * day))
        assertEquals(ReviewSchedule(12 * day, 0), scheduleReviews(0, good + grade(4, 0, 11 * day)))
        assertEquals(ReviewSchedule(18 * day, 2), scheduleReviews(0, good + grade(4, 3, 11 * day)))
        assertEquals(ReviewSchedule(day, 0), scheduleReviews(0, listOf(grade(1, 3, 0))))
        val log = good + grade(4, 0, 11 * day) + grade(5, 4, 12 * day)
        assertEquals(ReviewSchedule(15 * day, 1), scheduleReviews(0, log))
        assertEquals(scheduleReviews(0, log), scheduleReviews(0, log.reversed()))
        assertEquals(ReviewSchedule(0, null), scheduleReviews(0, listOf(grade(1, 4, 0).copy(rejection = "refused"))))
    }

    @Test fun theLadderStopsAtItsLastStep() {
        val easy = (1..20).map { grade(it, 5, it * day) }
        assertEquals(ReviewSchedule(140 * day, STEPS.lastIndex), scheduleReviews(0, easy))
    }

    @Test fun eachGradeShowsTheGapItWouldSchedule() {
        assertEquals(listOf(1L, 1, 1, 3), gaps(null))
        assertEquals(listOf(1L, 7, 14, 30), gaps(2))
        assertEquals(listOf(1L, 120, 120, 120), gaps(STEPS.lastIndex))
    }

    @Test fun aCardIsDueFromTheStartOfTheLocalDayItsIntervalEnds() {
        val bangkok = ZoneId.of("Asia/Bangkok")
        val evening = LocalDateTime.of(2026, 10, 9, 21, 0).atZone(bangkok).toInstant().toEpochMilli()
        val nextMorning = LocalDateTime.of(2026, 10, 10, 8, 0).atZone(bangkok).toInstant().toEpochMilli()
        val schedule = scheduleReviews(0, listOf(grade(1, 4, evening)), bangkok)
        assertEquals(LocalDateTime.of(2026, 10, 10, 0, 0).atZone(bangkok).toInstant().toEpochMilli(), schedule.dueAt)
        assertEquals(listOf("m1"), dueSession(listOf(card(1)), listOf(grade(1, 4, evening).copy(cardMutationId = "m1")),
            nextMorning, 40, 1, bangkok).map { it.annotation.mutationId })
    }

    @Test fun dueCardsNeedingACheckJoinTheSessionToBeFixedButRefusedOrRemovedOnesDoNot() {
        val ready = card(1)
        assertNull(ready.annotation.cardBlock(ready.currentBlobSha, ready.savedBlobSha))
        for (changed in listOf(ready.copy(currentBlobSha = "new"), ready.copy(currentBlobSha = null),
            ready.copy(annotation = ready.annotation.copy(cachedCurrentBlobSha = "server-newer")), ready.copy(savedBlobSha = null),
            ready.copy(annotation = ready.annotation.copy(checkedBlobSha = null)),
            ready.copy(annotation = ready.annotation.copy(status = "ORPHANED")))) {
            assertNotNull(changed.annotation.cardBlock(changed.currentBlobSha, changed.savedBlobSha))
            assertEquals(listOf(changed), dueSession(listOf(changed), emptyList(), 0, 40, 1))
        }
        for (gone in listOf(ready.copy(annotation = ready.annotation.copy(cachedDeleted = true)), ready.copy(annotation = ready.annotation.copy(state = AnnotationState.REFUSED, rejection = "refused")))) {
            assertNotNull(gone.annotation.cardBlock(gone.currentBlobSha, gone.savedBlobSha))
            assertTrue(dueSession(listOf(gone), emptyList(), 0, 40, 1).isEmpty())
            assertEquals(ReviewOutlook(0, 0, null, 0, 0), reviewOutlook(listOf(gone), emptyList(), 0, utc))
        }
    }

    @Test fun theOutlookCountsDueAndBlockedCardsTheNextDayWithCardsDueAndTheWeekAfterToday() {
        val blocked = card(2).let { it.copy(currentBlobSha = "new") }
        assertEquals(ReviewOutlook(2, 1, null, 0, 0), reviewOutlook(listOf(card(1), blocked), emptyList(), 0, utc))
        // Two cards fall due on day 1, one on day 4 and one on day 8, the day after the week that follows day 0.
        val log = listOf(grade(1, 4, 0).copy(cardMutationId = "m1"), grade(2, 4, 0).copy(cardMutationId = "m2"),
            grade(3, 4, 0).copy(cardMutationId = "m3"), grade(4, 4, day).copy(cardMutationId = "m3"),
            grade(5, 4, 0).copy(cardMutationId = "m4"), grade(6, 5, day).copy(cardMutationId = "m4"))
        assertEquals(ReviewOutlook(0, 0, day, 2, 3), reviewOutlook((1..4).map { card(it) }, log, day / 2, utc))
        assertEquals(ReviewOutlook(0, 0, null, 0, 0), reviewOutlook(emptyList(), emptyList(), 0, utc))
    }

    @Test fun theOutlookLineSaysWhatIsDueTheSessionSizeAndWhatFallsDueNext() {
        assertEquals("45 due · 40 per session · 2 to check first", outlookLine(ReviewOutlook(45, 2, null, 0, 0), 40, 0, utc))
        assertEquals("3 due", outlookLine(ReviewOutlook(3, 0, null, 0, 0), 40, 0, utc))
        assertEquals("3 due", outlookLine(ReviewOutlook(3, 0, null, 0, 0), null, 0, utc))
        assertEquals("3 due · 2 tomorrow", outlookLine(ReviewOutlook(3, 0, day, 2, 2), 40, day / 2, utc))
        assertEquals("Nothing due · 2 tomorrow · 5 in the next 7 days", outlookLine(ReviewOutlook(0, 0, day, 2, 5), 40, day / 2, utc))
        val later = outlookLine(ReviewOutlook(0, 0, 3 * day, 1, 4), 40, day / 2, utc)
        assertTrue(later, later.startsWith("Nothing due · next on ") && later.endsWith(", 1 card · 4 in the next 7 days"))
        assertTrue(outlookLine(ReviewOutlook(0, 0, 30 * day, 2, 0), 40, 0, utc).endsWith(", 2 cards"))
        assertEquals("No cards yet: grade a question below, or make one from a passage in a note.",
            outlookLine(ReviewOutlook(0, 0, null, 0, 0), 40, 0, utc))
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

    @Test fun aBankCardRevealsItsQuestionsLinkedAnswerAndOtherCardsOnlyTheirContext() {
        val html = "<main id=note><h2 data-block-id=b0 data-anchor-text='Foundations'>Foundations</h2>" +
            "<h3 data-block-id=b1 data-anchor-text='1. What is ACID?'>1. What is ACID?</h3>" +
            "<p data-block-id=b2 data-anchor-text='Interviewer is testing: protection.'>Interviewer is testing: protection.</p>" +
            "<p data-block-id=b3 data-anchor-text='Canonical answer: ACID'><strong>Canonical answer:</strong> " +
            "<a href='/note-link?path=core%2Fa.md&amp;heading=acid'>ACID</a></p></main>"
        val answer = Passage("sha", "b3", 0, 22, "Canonical answer: ACID")
        assertEquals(RevealedAnswer("Canonical answer: ACID", StudyQuestion("b1", "What is ACID?",
            StudyAnswer("b3", "Canonical answer: ACID", listOf(AnswerLink(null, "core/a.md", "acid", "ACID"))),
            guide = listOf("Interviewer is testing: protection."), level = "Foundations")), revealedAnswer(html, "core/interview/bank.md", answer))
        assertEquals(RevealedAnswer("Canonical answer: ACID", null), revealedAnswer(html, "core/bank.md", answer))
        assertEquals(RevealedAnswer("Interviewer is testing: protection.", null),
            revealedAnswer(html, "core/interview/bank.md", Passage("sha", "b2", 0, 11, "Interviewer")))
        assertNull(revealedAnswer(html, "core/interview/bank.md", answer.copy(exactText = "changed")))
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
