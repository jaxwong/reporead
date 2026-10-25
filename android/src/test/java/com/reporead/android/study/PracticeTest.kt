package com.reporead.android.study

import com.reporead.android.database.AnnotationEntity
import com.reporead.android.database.AnnotationState
import com.reporead.android.database.AnnotationType
import com.reporead.android.database.NotebookItem
import com.reporead.android.database.PracticePage
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
            StudyQuestion(
                block,
                text,
                StudyAnswer("${block}a", answer, emptyList()),
                "Short.",
                "Recall.",
                listOf("Name it"),
            ),
        )

    /**
     * One bank question in the contract's shape, as the server renders it: heading [id], then its Canonical line, whose
     * text is "Canonical answer: [line]".
     */
    private fun asked(id: String, heading: String, line: String) =
        "<h3 data-block-id=$id data-anchor-text='$heading'>$heading</h3>" +
            "<p data-block-id=${id}t data-anchor-text='Interviewer is testing: recall.'><strong>Interviewer is testing:</strong> recall.</p>" +
            "<p data-block-id=${id}c data-anchor-text='A complete answer should:'><strong>A complete answer should:</strong></p>" +
            "<ul><li data-block-id=${id}i data-anchor-text='name it'>name it</li></ul>" +
            "<p data-block-id=${id}s data-anchor-text='Short answer: It is.'><strong>Short answer:</strong> It is.</p>" +
            "<p data-block-id=${id}a data-anchor-text='Canonical answer: $line'><strong>Canonical answer:</strong> " +
            "<a href='/note-link?target=x'>$line</a></p>"

    private fun card(document: Long, question: String?, answer: String, blobSha: String = sha) =
        NotebookItem(
            AnnotationEntity(
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
                type = AnnotationType.CARD,
                question = question,
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
    }

    @Test
    fun banksAreTheListedNotesInInterviewFoldersAndNameTheirNonQuestions() {
        val html =
            "<main id=note>" +
                asked("b1", "1. Asked?", "X") +
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
            listOf(
                BankProblems(
                    1,
                    "Bank",
                    "core/db/interview/01.md",
                    sha,
                    listOf(BankProblem("b3", "Notes — no Canonical line")),
                )
            ),
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
                BankProblems(1, "First bank", path, sha, listOf(BankProblem("b0", "Notes — no Canonical line"))),
                BankProblems(
                    2,
                    "Second bank",
                    path,
                    "b".repeat(40),
                    listOf(BankProblem("b0", "Notes — no Canonical line")),
                ),
            ),
            banks.unrecognized,
        )
        assertEquals(Banks(emptyList(), emptyList(), emptyList()), readBanks(emptyList()))
    }
}
