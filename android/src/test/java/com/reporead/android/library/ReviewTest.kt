package com.reporead.android.library

import com.reporead.android.data.AnchorStatus
import com.reporead.android.data.AnnotationRow
import com.reporead.android.data.AnnotationState
import com.reporead.android.data.AnnotationType
import com.reporead.android.data.NotebookItem
import com.reporead.android.data.Passage
import com.reporead.android.data.ReviewRow
import com.reporead.android.reader.AnswerLink
import com.reporead.android.reader.StudyAnswer
import com.reporead.android.reader.StudyQuestion
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.*
import org.junit.Test

class ReviewTest {
    private val day = 86_400_000L
    private val utc = ZoneOffset.UTC

    private fun scheduleReviews(createdAt: Long, reviews: List<ReviewRow>, zone: ZoneId = utc) =
        scheduleReviews(card(0).annotation.copy(mutationId = "card", createdAt = createdAt), reviews, zone)

    private val day0 = LocalDate.of(1970, 1, 1)

    private fun dueSession(items: List<NotebookItem>, reviews: List<ReviewRow>, now: Long, limit: Int, seed: Int) =
        dueSession(items, reviews, now, limit, seed, utc)

    private fun grade(id: Int, value: Int, at: Long) = ReviewRow("g$id", "card", value, at, "sha", false)

    private fun card(id: Int, document: Long = 1) =
        NotebookItem(
            AnnotationRow(
                "m$id",
                id.toLong(),
                document,
                "sha",
                "b1",
                0,
                4,
                "text",
                null,
                1,
                0,
                AnnotationState.ACKNOWLEDGED,
                null,
                type = AnnotationType.CARD,
                question = "Why?",
                checkedBlobSha = "sha",
            ),
            "Note",
            "topic/a.md",
            currentBlobSha = "sha",
            deleted = false,
            savedBlobSha = "sha",
        )

    @Test
    fun aBankCardsPromptFollowsItsCanonicalLineAndOtherwiseKeepsItsSavedPrompt() {
        val item = card(1)
        val current =
            PracticeQuestion(
                1,
                "Bank",
                "topic/interview/bank.md",
                "new",
                StudyQuestion("b9", "Edited prompt?", StudyAnswer("b10", "text", emptyList())),
            )
        assertEquals("Edited prompt?", reviewPrompt(item, listOf(current)))
        assertEquals("Why?", reviewPrompt(item, emptyList()))
        assertEquals("Why?", reviewPrompt(item, listOf(current.copy(documentId = 2))))
        val editedAnswer =
            current.copy(question = current.question.copy(answer = current.question.answer.copy(text = "new line")))
        assertEquals("Why?", reviewPrompt(item, listOf(editedAnswer)))
    }

    @Test
    fun newCardsAreDueAndGoodClimbsTheLadderOneStepAndEasyTwo() {
        assertEquals(ReviewSchedule(0, null), scheduleReviews(0, emptyList()))
        val good = listOf(grade(1, 4, 0), grade(2, 4, day), grade(3, 4, 4 * day))
        assertEquals(ReviewSchedule(day, 0), scheduleReviews(0, good.take(1)))
        assertEquals(ReviewSchedule(4 * day, 1), scheduleReviews(0, good.take(2)))
        assertEquals(ReviewSchedule(11 * day, 2), scheduleReviews(0, good))
        val easy = listOf(grade(1, 5, 0), grade(2, 5, 3 * day), grade(3, 5, 17 * day))
        assertEquals(ReviewSchedule(77 * day, 5), scheduleReviews(0, easy))
    }

    @Test
    fun againGoesBackToTheFirstStepHardStaysAndArrivalOrderDoesNotChangeTheSchedule() {
        val good = listOf(grade(1, 4, 0), grade(2, 4, day), grade(3, 4, 4 * day))
        assertEquals(ReviewSchedule(12 * day, 0), scheduleReviews(0, good + grade(4, 0, 11 * day)))
        assertEquals(ReviewSchedule(18 * day, 2), scheduleReviews(0, good + grade(4, 3, 11 * day)))
        assertEquals(ReviewSchedule(day, 0), scheduleReviews(0, listOf(grade(1, 3, 0))))
        val log = good + grade(4, 0, 11 * day) + grade(5, 4, 12 * day)
        assertEquals(ReviewSchedule(15 * day, 1), scheduleReviews(0, log))
        assertEquals(scheduleReviews(0, log), scheduleReviews(0, log.reversed()))
        assertEquals(ReviewSchedule(0, null), scheduleReviews(0, listOf(grade(1, 4, 0).copy(rejection = "refused"))))
        assertEquals(ReviewSchedule(0, null), scheduleReviews(0, listOf(grade(1, 4, 0).copy(cardMutationId = "other"))))
    }

    @Test
    fun repeatedMissesStayOnTheFirstStepAndGradesTheServerAcceptsBelowHardCountAsAgain() {
        assertEquals(ReviewSchedule(11 * day, 0), scheduleReviews(0, (1..10).map { grade(it, 0, it * day) }))
        val good = listOf(grade(1, 4, 0), grade(2, 4, day), grade(3, 4, 4 * day))
        for (miss in 1..2) assertEquals(
            ReviewSchedule(12 * day, 0),
            scheduleReviews(0, good + grade(4, miss, 11 * day)),
        )
    }

    @Test
    fun theLadderStopsAtItsLastStep() {
        val easy = (1..20).map { grade(it, 5, it * day) }
        assertEquals(ReviewSchedule(140 * day, STEPS.lastIndex), scheduleReviews(0, easy))
    }

    @Test
    fun eachGradeShowsTheGapItWouldSchedule() {
        assertEquals(mapOf(0 to 1L, 3 to 1L, 4 to 1L, 5 to 3L), gaps(null))
        assertEquals(mapOf(0 to 1L, 3 to 7L, 4 to 14L, 5 to 30L), gaps(2))
        assertEquals(mapOf(0 to 1L, 3 to 120L, 4 to 120L, 5 to 120L), gaps(STEPS.lastIndex))
        // Each gap is what grading then schedules.
        val onStep2 = listOf(grade(1, 4, 0), grade(2, 4, day), grade(3, 4, 4 * day))
        for ((grade, gap) in gaps(2)) assertEquals(
            (11 + gap) * day,
            scheduleReviews(0, onStep2 + grade(4, grade, 11 * day)).dueAt,
        )
    }

    @Test
    fun aCardIsDueFromTheStartOfTheLocalDayItsIntervalEnds() {
        val bangkok = ZoneId.of("Asia/Bangkok")
        val evening = LocalDateTime.of(2026, 10, 9, 21, 0).atZone(bangkok).toInstant().toEpochMilli()
        val nextMorning = LocalDateTime.of(2026, 10, 10, 8, 0).atZone(bangkok).toInstant().toEpochMilli()
        val schedule = scheduleReviews(0, listOf(grade(1, 4, evening)), bangkok)
        assertEquals(LocalDateTime.of(2026, 10, 10, 0, 0).atZone(bangkok).toInstant().toEpochMilli(), schedule.dueAt)
        assertEquals(
            listOf("m1"),
            dueSession(
                    listOf(card(1)),
                    listOf(grade(1, 4, evening).copy(cardMutationId = "m1")),
                    nextMorning,
                    40,
                    1,
                    bangkok,
                )
                .map { it.annotation.mutationId },
        )
    }

    @Test
    fun dueCardsNeedingACheckJoinTheSessionToBeFixedButRefusedOrRemovedOnesDoNot() {
        val ready = card(1)
        assertNull(ready.annotation.cardBlock(ready.deleted, ready.currentBlobSha, ready.savedBlobSha))
        for (changed in
            listOf(
                ready.copy(currentBlobSha = "new"),
                ready.copy(savedBlobSha = null),
                ready.copy(annotation = ready.annotation.copy(checkedBlobSha = null)),
                ready.copy(annotation = ready.annotation.copy(status = AnchorStatus.ORPHANED)),
            )) {
            assertNotNull(changed.annotation.cardBlock(changed.deleted, changed.currentBlobSha, changed.savedBlobSha))
            assertEquals(listOf(changed), dueSession(listOf(changed), emptyList(), 0, 40, 1))
        }
        for (gone in
            listOf(
                ready.copy(deleted = true),
                ready.copy(annotation = ready.annotation.copy(state = AnnotationState.REFUSED, rejection = "refused")),
            )) {
            assertNotNull(gone.annotation.cardBlock(gone.deleted, gone.currentBlobSha, gone.savedBlobSha))
            assertTrue(dueSession(listOf(gone), emptyList(), 0, 40, 1).isEmpty())
            assertEquals(ReviewOutlook(day0, 0, 0, null, 0, 0), reviewOutlook(listOf(gone), emptyList(), 0, utc))
        }
    }

    @Test
    fun theOutlookCountsDueAndBlockedCardsTheNextDayWithCardsDueAndTheWeekAfterToday() {
        val blocked = card(2).let { it.copy(currentBlobSha = "new") }
        assertEquals(
            ReviewOutlook(day0, 2, 1, null, 0, 0),
            reviewOutlook(listOf(card(1), blocked), emptyList(), 0, utc),
        )
        // Two cards fall due on day 1, one on day 4 and one on day 8, the day after the week that follows day 0.
        val log =
            listOf(
                grade(1, 4, 0).copy(cardMutationId = "m1"),
                grade(2, 4, 0).copy(cardMutationId = "m2"),
                grade(3, 4, 0).copy(cardMutationId = "m3"),
                grade(4, 4, day).copy(cardMutationId = "m3"),
                grade(5, 4, 0).copy(cardMutationId = "m4"),
                grade(6, 5, day).copy(cardMutationId = "m4"),
            )
        assertEquals(
            ReviewOutlook(day0, 0, 0, day0.plusDays(1), 2, 3),
            reviewOutlook((1..4).map { card(it) }, log, day / 2, utc),
        )
        assertEquals(ReviewOutlook(day0, 0, 0, null, 0, 0), reviewOutlook(emptyList(), emptyList(), 0, utc))
    }

    @Test
    fun theOutlookLineSaysWhatIsDueTheSessionSizeAndWhatFallsDueNext() {
        assertEquals(
            "45 due · 40 per session · 2 to check first",
            outlookLine(ReviewOutlook(day0, 45, 2, null, 0, 0), 40),
        )
        assertEquals("3 due", outlookLine(ReviewOutlook(day0, 3, 0, null, 0, 0), 40))
        assertEquals("3 due", outlookLine(ReviewOutlook(day0, 3, 0, null, 0, 0), null))
        assertEquals("3 due · 2 tomorrow", outlookLine(ReviewOutlook(day0, 3, 0, day0.plusDays(1), 2, 2), 40))
        assertEquals(
            "Nothing due · 2 tomorrow · 5 in the next 7 days",
            outlookLine(ReviewOutlook(day0, 0, 0, day0.plusDays(1), 2, 5), 40),
        )
        val later = outlookLine(ReviewOutlook(day0, 0, 0, day0.plusDays(3), 1, 4), 40)
        assertTrue(
            later,
            later.startsWith("Nothing due · next on ") && later.endsWith(", 1 card · 4 in the next 7 days"),
        )
        assertTrue(outlookLine(ReviewOutlook(day0, 0, 0, day0.plusDays(30), 2, 0), 40).endsWith(", 2 cards"))
        assertEquals(
            "No cards yet: grade a question below, or make one from a passage in a note.",
            outlookLine(ReviewOutlook(day0, 0, 0, null, 0, 0), 40),
        )
    }

    @Test
    fun anOutlookDescribesTheDayItWasMadeFor() {
        // Made late on day 0: tomorrow is day 1, whenever the line is drawn.
        val log = listOf(grade(1, 4, 0).copy(cardMutationId = "m1"), grade(2, 5, 0).copy(cardMutationId = "m2"))
        val outlook = reviewOutlook(listOf(card(1), card(2)), log, day - 3_600_000, utc)
        assertEquals(ReviewOutlook(day0, 0, 0, day0.plusDays(1), 1, 2), outlook)
        assertEquals("Nothing due · 1 tomorrow · 2 in the next 7 days", outlookLine(outlook, 40))
    }

    @Test
    fun sessionsRespectTheServerCapInterleaveNotesAndExcludeNotDueCards() {
        val items = (1..50).map { card(it, if (it <= 30) 1 else 2) }
        val session = dueSession(items, emptyList(), 0, 40, 7)
        assertEquals(40, session.size)
        assertEquals(setOf(1L, 2L), session.take(2).map { it.annotation.documentId }.toSet())
        assertEquals(session, dueSession(items, emptyList(), 0, 40, 7))
        val reviews = listOf(grade(1, 4, 0).copy(cardMutationId = "m1"))
        assertFalse(dueSession(items, reviews, 0, 40, 7).any { it.annotation.mutationId == "m1" })
        assertTrue(dueSession(emptyList(), emptyList(), 0, 40, 7).isEmpty())
    }

    @Test
    fun contextChecksOffsetsAndUtf16TextWithoutInventingAnAnswer() {
        val html = "<main id=note><p data-block-id=b1 data-anchor-text='Before 😀 &amp; after'>Before</p></main>"
        assertEquals("Before 😀 & after", answerContext(html, Passage("sha", "b1", 7, 9, "😀")))
        assertNull(answerContext(html, Passage("sha", "b1", 7, 9, "xx")))
        assertNull(answerContext(html, Passage("sha", "b9", 7, 9, "😀")))
    }

    @Test
    fun aBankCardRevealsItsQuestionsLinkedAnswerAndOtherCardsOnlyTheirContext() {
        val html =
            "<main id=note><h2 data-block-id=b0 data-anchor-text='Foundations'>Foundations</h2>" +
                "<h3 data-block-id=b1 data-anchor-text='1. What is ACID?'>1. What is ACID?</h3>" +
                "<p data-block-id=b2 data-anchor-text='Interviewer is testing: protection.'>Interviewer is testing: protection.</p>" +
                "<p data-block-id=b3 data-anchor-text='Canonical answer: ACID'><strong>Canonical answer:</strong> " +
                "<a href='/note-link?path=core%2Fa.md&amp;heading=acid'>ACID</a></p></main>"
        val answer = Passage("sha", "b3", 0, 22, "Canonical answer: ACID")
        assertEquals(
            RevealedAnswer(
                "Canonical answer: ACID",
                StudyQuestion(
                    "b1",
                    "What is ACID?",
                    StudyAnswer("b3", "Canonical answer: ACID", listOf(AnswerLink(null, "core/a.md", "acid", "ACID"))),
                    guide = listOf("Interviewer is testing: protection."),
                    level = "Foundations",
                ),
            ),
            revealedAnswer(html, "core/interview/bank.md", answer),
        )
        assertEquals(RevealedAnswer("Canonical answer: ACID", null), revealedAnswer(html, "core/bank.md", answer))
        assertEquals(
            RevealedAnswer("Interviewer is testing: protection.", null),
            revealedAnswer(html, "core/interview/bank.md", Passage("sha", "b2", 0, 11, "Interviewer")),
        )
        assertNull(revealedAnswer(html, "core/interview/bank.md", answer.copy(exactText = "changed")))
    }
}
