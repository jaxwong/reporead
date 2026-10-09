package com.reporead.android.library

import com.reporead.android.reader.StudyQuestion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PracticeTest {
    @Test fun shuffleInterleavesNotesAndKeepsEveryQuestionExactlyOnceOnRepeatRuns() {
        val questions = (1L..3L).flatMap { id -> (0..3).map { i -> PracticeQuestion(id, "note", "core/note.md", StudyQuestion("b$i", "Question $id:$i")) } }
        repeat(20) {
            val mixed = interleaveQuestions(questions, it)
            assertEquals(questions.toSet(), mixed.toSet())
            assertEquals(questions.size, mixed.size)
            assertTrue(mixed.zipWithNext().all { (a, b) -> a.documentId != b.documentId })
        }
        assertEquals(emptyList<PracticeQuestion>(), interleaveQuestions(emptyList(), 0))
        assertEquals(questions.take(1), interleaveQuestions(questions.take(1), 0))
        val uneven = questions.take(5)
        assertEquals(uneven.toSet(), interleaveQuestions(uneven, 0).toSet())
        assertEquals(interleaveQuestions(questions, 42), interleaveQuestions(questions, 42))
    }

    @Test fun folderFilterUsesPathBoundariesAndIncludesDescendants() {
        assertTrue(inPracticeFolder("core/db/a.md", " core/ "))
        assertTrue(inPracticeFolder("core/db/a.md", "/core/db/"))
        assertTrue(inPracticeFolder("root.md", ""))
        assertFalse(inPracticeFolder("core-extra/a.md", "core"))
        assertFalse(inPracticeFolder("books/a.md", "core"))
    }

    @Test fun topicsUseOnlyActualFoldersAndIncludeEachAncestorOnce() {
        assertEquals(listOf("core", "core/db", "core/db/sql", "core-extra"),
            practiceTopics(listOf("root.md", "core/db/sql/a.md", "core/db/b.md", "core-extra/a.md", "core/db/sql/c.md")))
        assertEquals(emptyList<String>(), practiceTopics(emptyList()))
        assertEquals(emptyList<String>(), practiceTopics(listOf("root.md")))
    }
}
