package com.reporead.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnotationRowTest {
    private val pending = AnnotationRow("m1", null, 7, "a".repeat(40), "b2", 12, 30, "declarative transactions", null, 1, 0,
        pending = true, rejection = null)

    @Test fun aPendingCreationIsDrawnWhereItWasMade() {
        assertEquals(Passage("a".repeat(40), "b2", 12, 30, "declarative transactions"), pending.drawn)
        assertFalse(pending.orphanedIn("a".repeat(40)))
    }

    @Test fun anAcknowledgedHighlightIsDrawnAtTheServersLocation() {
        val moved = pending.copy(serverId = 3, pending = false, status = "REANCHORED", resolvedBlobSha = "c".repeat(40),
            location = Passage("c".repeat(40), "b3", 12, 29, "declarative transaction"))
        assertEquals("b3", moved.drawn.blockId)
        assertEquals("c".repeat(40), moved.drawn.blobSha)
    }

    @Test fun anOrphanIsOrphanedOnlyInTheVersionTheServerResolved() {
        val orphan = pending.copy(serverId = 3, pending = false, status = "ORPHANED", resolvedBlobSha = "d".repeat(40),
            location = Passage("a".repeat(40), "b2", 12, 30, "declarative transactions"))
        assertTrue(orphan.orphanedIn("d".repeat(40)))
        assertFalse("its last location is still valid in the older version", orphan.orphanedIn("a".repeat(40)))
        assertEquals("a".repeat(40), orphan.drawn.blobSha)
    }
}
