package com.reporead.android.library

import com.reporead.android.data.AnnotationRow
import com.reporead.android.data.AnnotationState
import com.reporead.android.data.AnnotationType
import com.reporead.android.data.NotebookItem
import com.reporead.android.data.PracticePage
import com.reporead.android.reader.StudyAnswer
import com.reporead.android.reader.StudyQuestion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PracticeTest {
    private val sha = "a".repeat(40)

    private fun question(document: Long, block: String, text: String, answer: String = "Canonical answer: $text") =
        PracticeQuestion(
            document,
            "note",
            "core/interview/note.md",
            sha,
            StudyQuestion(block, text, StudyAnswer("${block}a", answer, emptyList())),
        )

    private fun card(
        document: Long,
        question: String?,
        answer: String,
        type: AnnotationType = AnnotationType.CARD,
        blobSha: String = sha,
    ) =
        NotebookItem(
            AnnotationRow(
                "m$document$question$answer",
                1,
                document,
                blobSha,
                "x",
                0,
                answer.length,
                answer,
                null,
                1,
                0,
                AnnotationState.ACKNOWLEDGED,
                null,
                type = type,
                question = question,
                checkedBlobSha = blobSha,
            ),
            "note",
            "core/interview/note.md",
            currentBlobSha = blobSha,
            deleted = false,
            savedBlobSha = blobSha,
        )

    @Test
    fun shuffleInterleavesNotesAndKeepsEveryQuestionExactlyOnceOnRepeatRuns() {
        val questions = (1L..3L).flatMap { id -> (0..3).map { i -> question(id, "b$i", "Question $id:$i") } }
        repeat(20) { seed ->
            val mixed = interleaveByNote(questions, seed) { it.documentId }
            assertEquals(questions.toSet(), mixed.toSet())
            assertEquals(questions.size, mixed.size)
            assertTrue(mixed.zipWithNext().all { (a, b) -> a.documentId != b.documentId })
        }
        assertEquals(
            emptyList<PracticeQuestion>(),
            interleaveByNote(emptyList<PracticeQuestion>(), 0) { it.documentId },
        )
        assertEquals(questions.take(1), interleaveByNote(questions.take(1), 0) { it.documentId })
        val uneven = questions.take(5)
        assertEquals(uneven.toSet(), interleaveByNote(uneven, 0) { it.documentId }.toSet())
        assertEquals(
            interleaveByNote(questions, 42) { it.documentId },
            interleaveByNote(questions, 42) { it.documentId },
        )
    }

    @Test
    fun aQuestionIsScheduledOnlyByACardWithItsBankPromptAndAnswerLine() {
        val first = question(1, "b1", "Same?", "Canonical answer: First")
        val second = question(1, "b2", "Same?", "Canonical answer: Second")
        val elsewhere = question(2, "b1", "Same?", "Canonical answer: First")
        val questions = listOf(first, second, elsewhere)
        assertEquals(
            setOf(first.key),
            scheduledQuestions(questions, listOf(card(1, "Same?", "Canonical answer: First"))),
        )
        // A card follows its passage into newer versions of the bank, so the version does not decide.
        assertEquals(
            setOf(first.key),
            scheduledQuestions(
                questions,
                listOf(card(1, "Same?", "Canonical answer: First", blobSha = "b".repeat(40))),
            ),
        )
        assertEquals(
            emptySet<String>(),
            scheduledQuestions(
                questions,
                listOf(card(1, "Same?", "Canonical answer: First", type = AnnotationType.HIGHLIGHT)),
            ),
        )
        assertEquals(
            emptySet<String>(),
            scheduledQuestions(questions, listOf(card(1, "Edited?", "Canonical answer: First"))),
        )
        assertEquals(emptySet<String>(), scheduledQuestions(questions, emptyList()))
        // The prompt includes a follow-up's instruction.
        val followUp =
            PracticeQuestion(
                3,
                "note",
                "core/interview/note.md",
                sha,
                StudyQuestion(
                    "b1",
                    "Which key?",
                    StudyAnswer("b9", "Canonical path: Caching", emptyList()),
                    "After you add a cache",
                ),
            )
        assertEquals(
            setOf(followUp.key),
            scheduledQuestions(
                listOf(followUp),
                listOf(card(3, "After you add a cache\n\nWhich key?", "Canonical path: Caching")),
            ),
        )
    }

    @Test
    fun aMissedQuestionComesBackAtTheEndUntilAnsweredRight() {
        assertEquals(listOf("b", "c", "a"), afterGrade(listOf("a", "b", "c"), 0))
        assertEquals(listOf("b", "c"), afterGrade(listOf("a", "b", "c"), 3))
        assertEquals(listOf("b", "c"), afterGrade(listOf("a", "b", "c"), 4))
        assertEquals(listOf("b", "c"), afterGrade(listOf("a", "b", "c"), 5))
        assertEquals(listOf("a"), afterGrade(listOf("a"), 0))
        assertEquals(emptyList<String>(), afterGrade(listOf("a"), 4))
    }

    @Test
    fun banksAreTheListedNotesInInterviewFoldersAndNameTheirNonQuestions() {
        val html =
            "<main id=note><h3 data-block-id=b1 data-anchor-text='1. Asked?'>1. Asked?</h3>" +
                "<p data-block-id=b2 data-anchor-text='Canonical answer: X'>Canonical answer: X</p>" +
                "<h3 data-block-id=b3 data-anchor-text='Notes'>Notes</h3></main>"
        val pages =
            listOf(
                PracticePage(1, "Bank", "core/db/interview/01.md", html, sha),
                PracticePage(2, "Unsaved", "core/db/interview/02.md", null, null),
                PracticePage(3, "Core", "core/db/01.md", html, sha),
            )
        val banks = readBanks(pages)
        assertEquals(listOf(1L, 2L), banks.pages.map { it.documentId })
        assertEquals(listOf("1:$sha:b1"), banks.questions.map { it.key })
        assertEquals(listOf("core/db/interview/01.md" to "Notes"), banks.unrecognized)
    }

    @Test
    fun folderFilterUsesPathBoundariesAndIncludesDescendants() {
        assertTrue(inPracticeFolder("core/db/a.md", " core/ "))
        assertTrue(inPracticeFolder("core/db/a.md", "/core/db/"))
        assertTrue(inPracticeFolder("root.md", ""))
        assertFalse(inPracticeFolder("core-extra/a.md", "core"))
        assertFalse(inPracticeFolder("books/a.md", "core"))
    }

    @Test
    fun topicsUseOnlyActualFoldersAndIncludeEachAncestorOnce() {
        assertEquals(
            listOf("core", "core/db", "core/db/sql", "core-extra"),
            practiceTopics(
                listOf("root.md", "core/db/sql/a.md", "core/db/b.md", "core-extra/a.md", "core/db/sql/c.md")
            ),
        )
        assertEquals(emptyList<String>(), practiceTopics(emptyList()))
        assertEquals(emptyList<String>(), practiceTopics(listOf("root.md")))
    }
}
