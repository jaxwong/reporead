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
                status = "REANCHORED",
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
                type = "CARD",
                question = "Test-only question?",
                status = "ANCHORED",
                resolvedBlobSha = current,
                location = Passage(current, "b2", 12, 30, "declarative transactions"),
                checkedBlobSha = current,
            )
        assertNull(card.cardBlock(false, current, current))
        assertEquals(CardBlock.REMOVED, card.cardBlock(true, current, current))
        for ((block, changed) in
            listOf(
                CardBlock.NOT_SYNCED to card.copy(state = AnnotationState.REFUSED, rejection = "Test-only refusal"),
                CardBlock.ORPHANED to card.copy(status = "ORPHANED"),
                CardBlock.UNCONFIRMED to card.copy(checkedBlobSha = null),
                CardBlock.UNCONFIRMED to card.copy(checkedBlobSha = "a".repeat(40)),
                CardBlock.UNCONFIRMED to
                    card.copy(location = Passage("a".repeat(40), "b2", 12, 30, "declarative transactions")),
            )) assertEquals(changed.toString(), block, changed.cardBlock(false, current, current))
        assertEquals("the note is no longer listed", CardBlock.UNCONFIRMED, card.cardBlock(false, null, current))
        assertEquals(CardBlock.NOT_SAVED, card.cardBlock(false, current, "a".repeat(40)))
        assertEquals(CardBlock.NOT_SAVED, card.cardBlock(false, current, null))
    }

    @Test
    fun anOrphanIsOrphanedOnlyInTheVersionTheServerResolved() {
        val orphan =
            unsent.copy(
                serverId = 3,
                state = AnnotationState.ACKNOWLEDGED,
                status = "ORPHANED",
                resolvedBlobSha = "d".repeat(40),
                location = Passage("a".repeat(40), "b2", 12, 30, "declarative transactions"),
            )
        assertTrue(orphan.orphanedIn("d".repeat(40)))
        assertFalse("its last location is still valid in the older version", orphan.orphanedIn("a".repeat(40)))
        assertEquals("a".repeat(40), orphan.drawn.blobSha)
    }
}
