package com.reporead.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnotationRowTest {
    private val unsent =
        AnnotationRow(
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
    fun aPendingCreationIsDrawnWhereItWasMade() {
        assertEquals(Passage("a".repeat(40), "b2", 12, 30, "declarative transactions"), unsent.drawn)
        assertFalse(unsent.orphanedIn("a".repeat(40)))
    }

    @Test
    fun anAcknowledgedHighlightIsDrawnAtTheServersLocation() {
        val moved =
            unsent.copy(
                serverId = 3,
                state = AnnotationState.ACKNOWLEDGED,
                status = AnchorStatus.REANCHORED,
                resolvedBlobSha = "c".repeat(40),
                location = Passage("c".repeat(40), "b3", 12, 29, "declarative transaction"),
            )
        assertEquals("b3", moved.drawn.blockId)
        assertEquals("c".repeat(40), moved.drawn.blobSha)
    }

    @Test
    fun aCardCanBeGradedOnlyWhenItsConfirmedAnswerIsTheCurrentSavedVersion() {
        val current = "c".repeat(40)
        val card =
            unsent.copy(
                serverId = 3,
                state = AnnotationState.ACKNOWLEDGED,
                type = AnnotationType.CARD,
                question = "Test-only question?",
                status = AnchorStatus.ANCHORED,
                resolvedBlobSha = current,
                location = Passage(current, "b2", 12, 30, "declarative transactions"),
                checkedBlobSha = current,
            )
        val older = "a".repeat(40)
        assertNull(card.cardBlock(false, current, current))
        assertEquals(CardBlock.REMOVED, card.cardBlock(true, current, current))
        for ((block, changed) in
            listOf(
                CardBlock.REFUSED to card.copy(state = AnnotationState.REFUSED, rejection = "Test-only refusal"),
                CardBlock.ORPHANED to card.copy(status = AnchorStatus.ORPHANED),
                CardBlock.UNCONFIRMED to card.copy(checkedBlobSha = null),
                CardBlock.UNCONFIRMED to card.copy(checkedBlobSha = older),
                CardBlock.UNPLACED to card.copy(location = Passage(older, "b2", 12, 30, "declarative transactions")),
                // Made in the older version and not sent yet, it is drawn where it was made.
                CardBlock.UNSENT to
                    unsent.copy(type = AnnotationType.CARD, question = "Test-only question?", checkedBlobSha = older),
            )) assertEquals(changed.toString(), block, changed.cardBlock(false, current, current))
        assertEquals(CardBlock.NOT_SAVED, card.cardBlock(false, current, older))
        assertEquals(CardBlock.NOT_SAVED, card.cardBlock(false, current, null))
        // Saved first: confirming needs the saved page of the current version to show the answer.
        assertEquals(CardBlock.NOT_SAVED, card.copy(checkedBlobSha = null).cardBlock(false, current, older))
        // An unsent card made in the current version can be graded offline; its grade waits for it.
        assertNull(unsent.copy(type = AnnotationType.CARD, checkedBlobSha = older).cardBlock(false, older, older))
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
    }
}
