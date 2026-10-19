package com.reporead.android.study

import com.reporead.android.data.AnnotationRow
import com.reporead.android.data.AnnotationState
import com.reporead.android.data.AnnotationType
import com.reporead.android.data.NotebookItem
import com.reporead.android.data.PracticePage
import org.junit.Assert.assertEquals
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
        state: AnnotationState = AnnotationState.ACKNOWLEDGED,
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
                state,
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
    fun aQuestionFollowsItsBanksCanonicalLineNotItsPrompt() {
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
            setOf(first.key),
            scheduledQuestions(questions, listOf(card(1, "Edited?", "Canonical answer: First"))),
        )
        val editedPrompt = first.copy(question = first.question.copy(blockId = "b3", text = "Edited?"))
        assertEquals(
            setOf(first.key, editedPrompt.key),
            scheduledQuestions(listOf(first, editedPrompt), listOf(card(1, "Same?", "Canonical answer: First"))),
        )
        // A card the server refused is on no schedule: Review leaves it out, so Practice asks its question again.
        assertEquals(
            emptySet<String>(),
            scheduledQuestions(
                questions,
                listOf(card(1, "Same?", "Canonical answer: First", state = AnnotationState.REFUSED)),
            ),
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
    fun twoPromptsOverOneCanonicalLineAreOneQuestion() {
        val html =
            "<main id=note><h3 data-block-id=b1 data-anchor-text='First?'>First?</h3>" +
                "<p data-block-id=b2 data-anchor-text='Canonical answer: X'>Canonical answer: X</p>" +
                "<h3 data-block-id=b3 data-anchor-text='Second?'>Second?</h3>" +
                "<p data-block-id=b4 data-anchor-text='Canonical answer: X'>Canonical answer: X</p></main>"
        val banks = readBanks(listOf(PracticePage(1, "Bank", "core/interview/bank.md", html, sha)))
        assertEquals(listOf("First?"), banks.questions.map { it.question.prompt })
        assertEquals(
            listOf(BankProblem("b3", "Second? — repeats a Canonical line")),
            banks.unrecognized.single().blocks,
        )
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
        assertEquals(
            listOf(BankProblems(1, "Bank", "core/db/interview/01.md", sha, listOf(BankProblem("b3", "Notes")))),
            banks.unrecognized,
        )
    }

    @Test
    fun warningsStayWithTheirDocumentWhenBanksShareAPath() {
        val path = "core/db/interview/01.md"
        val html = "<main id=note><h3 data-block-id=b0 data-anchor-text='Notes'>Notes</h3></main>"
        val banks =
            readBanks(
                listOf(
                    PracticePage(1, "First bank", path, html, sha),
                    PracticePage(2, "Second bank", path, html, "b".repeat(40)),
                )
            )
        assertEquals(
            listOf(
                BankProblems(1, "First bank", path, sha, listOf(BankProblem("b0", "Notes"))),
                BankProblems(2, "Second bank", path, "b".repeat(40), listOf(BankProblem("b0", "Notes"))),
            ),
            banks.unrecognized,
        )
        assertEquals(Banks(emptyList(), emptyList(), emptyList()), readBanks(emptyList()))
    }
}
