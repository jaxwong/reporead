package com.reporead.android.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnotationEntityTest {
    private val unsent =
        AnnotationEntity(
            "m1",
            null,
            7,
            "a".repeat(40),
            "b2",
            12,
            30,
            "declarative transactions",
            null,
            1,
            0,
            state = AnnotationState.UNSENT,
        )

    @Test
    fun scheduledLinesUseOnlyReviewableCardsAndDeduplicateByBankAndDrawnText() {
        val line = "Canonical answer: Test-only"
        val card =
            NotebookItem(
                unsent.copy(
                    type = AnnotationType.CARD,
                    question = "Test-only prompt?",
                    startOffset = 0,
                    endOffset = line.length,
                    exactText = line,
                ),
                "Test-only bank",
                "test-only/interview/bank.md",
                unsent.sourceBlobSha,
                false,
                unsent.sourceBlobSha,
            )
        val refused = card.copy(annotation = card.annotation.copy(state = AnnotationState.REFUSED))
        val removed = card.copy(deleted = true)
        val highlight = card.copy(annotation = card.annotation.copy(type = AnnotationType.HIGHLIGHT))
        assertTrue(reviewable(card))
        for (item in listOf(refused, removed, highlight)) assertFalse(reviewable(item))
        assertEquals(emptySet<Pair<Long, String>>(), scheduledLines(emptyList()))
        assertEquals(emptySet<Pair<Long, String>>(), scheduledLines(listOf(refused, removed, highlight)))
        val editedPrompt = card.copy(annotation = card.annotation.copy(question = "Test-only edited prompt?"))
        val elsewhere = card.copy(annotation = card.annotation.copy(documentId = 8))
        val movedLine = "Canonical answer: Test-only moved"
        val moved =
            card.copy(
                annotation =
                    card.annotation.copy(
                        location = Passage("c".repeat(40), "b9", 0, movedLine.length, movedLine),
                        status = AnchorStatus.REANCHORED,
                    )
            )
        assertEquals(
            setOf(7L to line, 8L to line, 7L to movedLine),
            scheduledLines(listOf(card, editedPrompt, elsewhere, moved, refused, removed, highlight)),
        )
        // Needing an answer check blocks grading, not membership of the review schedule.
        assertTrue(reviewable(card.copy(annotation = card.annotation.copy(status = AnchorStatus.ORPHANED))))
    }

    @Test
    fun onlyAnAcknowledgedAnnotationCanBeEditedOrReattached() {
        assertFalse(unsent.acknowledged)
        assertFalse(unsent.copy(state = AnnotationState.REFUSED, rejection = "capacity").acknowledged)
        assertTrue(unsent.copy(serverId = 3, state = AnnotationState.ACKNOWLEDGED).acknowledged)
    }

    @Test
    fun aCardCanBeGradedInItsNotesCurrentSavedVersionWithoutBeingConfirmedOrPlacedThere() {
        val current = "c".repeat(40)
        val older = "a".repeat(40)
        val card =
            unsent.copy(
                serverId = 3,
                state = AnnotationState.ACKNOWLEDGED,
                type = AnnotationType.CARD,
                question = "Test-only question?",
                status = AnchorStatus.ANCHORED,
                resolvedBlobSha = older,
                location = Passage(older, "b2", 12, 30, "declarative transactions"),
            )
        // Placed by the server only in an older version, orphaned there, or not sent yet: whether the current version
        // still asks its Canonical line is Review's match against the saved bank, not the server's placement.
        assertNull(card.cardBlock(false, current, current))
        assertNull(card.copy(status = AnchorStatus.ORPHANED).cardBlock(false, current, current))
        assertNull(
            unsent.copy(type = AnnotationType.CARD, question = "Test-only question?").cardBlock(false, current, current)
        )
        assertEquals(
            CardBlock.REFUSED,
            card
                .copy(state = AnnotationState.REFUSED, rejection = "Test-only refusal")
                .cardBlock(false, current, current),
        )
        assertEquals(CardBlock.REMOVED, card.cardBlock(true, current, current))
        assertEquals(CardBlock.NOT_SAVED, card.cardBlock(false, current, older))
        assertEquals(CardBlock.NOT_SAVED, card.cardBlock(false, current, null))
    }

    @Test
    fun anOrphanIsOrphanedOnlyInTheVersionTheServerResolved() {
        val orphan =
            unsent.copy(
                serverId = 3,
                state = AnnotationState.ACKNOWLEDGED,
                status = AnchorStatus.ORPHANED,
                resolvedBlobSha = "d".repeat(40),
                location = Passage("a".repeat(40), "b2", 12, 30, "declarative transactions"),
            )
        assertTrue(orphan.orphanedIn("d".repeat(40)))
        assertFalse("its last location is still valid in the older version", orphan.orphanedIn("a".repeat(40)))
        assertEquals("a".repeat(40), orphan.drawn.blobSha)
        assertFalse("a pending creation has no server status yet", unsent.orphanedIn("a".repeat(40)))
    }

    @Test
    fun aPhraseIsTheBlockAndOffsetsAHighlightIsDrawnAt() {
        val version = "a".repeat(40)
        val selected = Passage(version, "b2", 12, 30, "declarative transactions")
        assertEquals(unsent, highlightOn(listOf(unsent), selected))
        val notTheSamePhrase =
            listOf(
                Passage(version, "b2", 12, 23, "declarative"),
                Passage(version, "b2", 12, 31, "declarative transactions,"),
                Passage(version, "b3", 12, 30, "declarative transactions"),
                Passage("c".repeat(40), "b2", 12, 30, "declarative transactions"),
            )
        for (passage in notTheSamePhrase) assertNull(highlightOn(listOf(unsent), passage))
        assertNull("a card is not a highlight", highlightOn(listOf(unsent.copy(type = AnnotationType.CARD)), selected))
        val refused = unsent.copy(state = AnnotationState.REFUSED, rejection = "Test-only refusal")
        assertNull("a refused highlight is never sent, so it marks nothing", highlightOn(listOf(refused), selected))
        // An acknowledged highlight is where the server drew it, not where it was made.
        val moved =
            unsent.copy(
                serverId = 3,
                state = AnnotationState.ACKNOWLEDGED,
                status = AnchorStatus.REANCHORED,
                resolvedBlobSha = "c".repeat(40),
                location = Passage("c".repeat(40), "b3", 12, 29, "declarative transaction"),
            )
        assertNull(highlightOn(listOf(moved), selected))
        assertEquals(
            moved,
            highlightOn(listOf(moved), Passage("c".repeat(40), "b3", 12, 29, "declarative transaction")),
        )
    }
}
