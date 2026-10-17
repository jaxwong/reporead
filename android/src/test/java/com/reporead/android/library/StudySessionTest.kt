package com.reporead.android.library

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StudySessionTest {
    private val session = StudySession(listOf("a", "b", "c"))

    @Test
    fun aMissComesBackAtTheEndUntilAnsweredRightAndARightAnswerLeaves() {
        assertEquals(listOf("b", "c", "a"), session.graded(0).queue)
        for (right in listOf(3, 4, 5)) assertEquals(listOf("b", "c"), session.graded(right).queue)
        val last = StudySession(listOf("a"))
        assertEquals(listOf("a"), last.graded(0).queue)
        assertNull(last.graded(4).current)
    }

    @Test
    fun eachMoveAsksTheNextItemAloneAndKeepsWhatTheSessionStartedWith() {
        assertEquals("a", session.current)
        val revealed = session.reveal()
        assertTrue(revealed.revealed)
        for (moved in listOf(revealed.skip(), revealed.requeue(), revealed.graded(0), revealed.graded(4))) {
            assertFalse("The next item is asked alone", moved.revealed)
            assertEquals(listOf("a", "b", "c"), moved.started)
        }
        assertEquals(listOf("b", "c"), revealed.skip().queue)
        assertEquals(listOf("b", "c", "a"), revealed.requeue().queue)
        assertNull(StudySession(emptyList()).current)
    }

    @Test
    fun completedCountsTheStartedItemsWhoseFirstGradeIsStored() {
        val done = session.graded(4).graded(0).graded(4).graded(4)
        assertNull(done.current)
        assertEquals(2, done.completed(setOf("a", "c", "elsewhere")))
        assertEquals(0, done.completed(emptySet()))
    }

    /** Saved state keeps a session whole across recreation and process death. */
    @Test
    fun aSessionSurvivesSerialization() {
        val moved = session.reveal().graded(0)
        val bytes = ByteArrayOutputStream().also { ObjectOutputStream(it).use { out -> out.writeObject(moved) } }
        val read = ObjectInputStream(ByteArrayInputStream(bytes.toByteArray())).use { it.readObject() }
        assertEquals(moved, read)
    }
}
