package com.reporead.android.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StudyTest {
    private val bank = "core/databases/interview/07-transactions.md"
    private fun heading(id: Int, name: String, level: Int = 2) =
        """<h$level data-block-id="b$id" data-anchor-text="$name">$name</h$level>"""
    private fun block(id: Int, text: String, tag: String = "li") =
        """<$tag data-block-id="b$id" data-anchor-text="$text">$text</$tag>"""
    private fun page(body: String) = "<main id=note>$body</main>"

    @Test fun eachNumberedBankQuestionHasItsCanonicalAnswerLineAndGuide() {
        val html = page(heading(0, "Bank", 1) + block(1, "Canonical source: the note.", "p") + heading(2, "1. Foundations") +
            block(3, "Answer in 90 seconds.", "p") + heading(4, "1. What is ACID?", 3) +
            block(5, "Interviewer is testing: protection.", "p") + block(6, "A complete answer should: name each property.", "p") +
            block(7, "Canonical answer: ACID in practical language", "p") + heading(8, "2. Why isolation levels?", 3) +
            block(9, "Interviewer is testing: the dial.", "p") + heading(10, "Detail", 4) + block(11, "Canonical answers: A and B", "p") +
            heading(12, "Diagnosis") + heading(13, "1. Why 503?", 3) + "<ul>" + block(14, "check the route") + "</ul>" +
            block(15, "Canonical answer: Routes", "p") + heading(16, "5. Rehearsal and evaluation") +
            heading(17, "1. Not a question", 3) + block(18, "Canonical answer: Not an answer", "p"))
        val study = studyNote(html, bank)
        assertTrue(study.recognized)
        assertEquals(listOf("What is ACID?", "Why isolation levels?", "Why 503?"), study.questions.map { it.text })
        assertEquals(listOf("b4", "b8", "b13"), study.questions.map { it.blockId })
        assertEquals(listOf(null, null, null), study.questions.map { it.instruction })
        assertEquals(listOf(StudyAnswer("b7", "Canonical answer: ACID in practical language"), StudyAnswer("b11", "Canonical answers: A and B"),
            StudyAnswer("b15", "Canonical answer: Routes")), study.questions.map { it.answer })
        assertEquals(listOf("Interviewer is testing: protection.", "A complete answer should: name each property.",
            "Canonical answer: ACID in practical language"), study.questions[0].guide)
        assertEquals(listOf("check the route", "Canonical answer: Routes"), study.questions[2].guide)
    }

    @Test fun followUpItemsTakeTheirGroupHeadingAsInstructionAndShareItsCanonicalPath() {
        val html = page(heading(0, "3. Design follow-ups") + block(1, "Asked after a design.", "p") +
            heading(2, "After you add a cache", 3) + "<ol>" + block(3, "Which key?") +
            "<li>What expires?<ul>" + block(4, "A hint") + "</ul></li></ol>" + block(5, "Canonical path: Caching → Expiry", "p") +
            heading(6, "1. How many threads?", 3) + block(7, "Canonical answer: Sizing", "p"))
        val study = studyNote(html, bank)
        assertEquals(listOf("Which key?", "What expires?", "How many threads?"), study.questions.map { it.text })
        assertEquals(listOf("b3", "b4", "b6"), study.questions.map { it.blockId })
        assertEquals("After you add a cache\n\nWhich key?", study.questions[0].prompt)
        assertEquals(StudyAnswer("b5", "Canonical path: Caching → Expiry"), study.questions[1].answer)
        assertEquals(listOf("Canonical path: Caching → Expiry"), study.questions[1].guide)
        assertEquals("How many threads?", study.questions[2].prompt)
    }

    @Test fun aQuestionWithoutAnAnswerLineIsStillPractisedButHasNoAnswer() {
        val study = studyNote(page(heading(0, "Stretch") + heading(1, "1. Why?", 3) + block(2, "Interviewer is testing: depth.", "p")), bank)
        assertEquals(listOf("Why?"), study.questions.map { it.text })
        assertNull(study.questions.single().answer)
    }

    @Test fun questionsComeOnlyFromNotesInAnInterviewFolder() {
        val html = page(heading(0, "Applied questions") + heading(1, "1. A bank question?", 3) + block(2, "Canonical answer: X", "p"))
        assertEquals(1, studyNote(html, "interview/one.md").questions.size)
        for (path in listOf("core/databases/07-transactions.md", "core/interview.md", "core/interviews/one.md")) {
            val study = studyNote(html, path)
            assertFalse(path, study.recognized)
            assertEquals(path, emptyList<StudyQuestion>(), study.questions)
        }
        // The core notes' own question sections are no longer a source.
        val core = page(heading(0, "Questions this file answers") + "<ul>" + block(1, "Why?") + "</ul>" +
            heading(2, "Review and practice") + block(3, "Try this.", "p"))
        assertFalse(studyNote(core, "core/databases/07-transactions.md").recognized)
        assertEquals(emptyList<StudyQuestion>(), studyNote(core, bank).questions)
        assertFalse(studyNote(page(heading(0, "Foundations")), bank).recognized)
        assertFalse(studyNote("", bank).recognized)
    }

    @Test fun onlyTheFixedLeetcodeHeadingsAreRecognizedIgnoringCaseAndOuterWhitespace() {
        assertTrue(studyNote(page(heading(0, "  MISTAKES  ")), "leetcode/a.md").recognized)
        assertTrue(studyNote(page(heading(0, "Problem")), "leetcode/a.md").recognized)
        assertFalse(studyNote(page(heading(0, "Problems") + block(1, "Mistakes", "pre")), "leetcode/a.md").recognized)
    }

    @Test fun leetcodeSectionsCollapseButProblemAndUnrecognizedSectionsDoNot() {
        val names = listOf("Problem", "Approach", "Brute Force Approach", "Optimized Approach", "Complexities", "Mistakes", "Notes")
        val study = studyNote(page(names.mapIndexed { i, name -> heading(i * 2, name) + block(i * 2 + 1, "body", "p") }.joinToString("")), "leetcode/a.md")
        assertEquals(listOf("b2", "b4", "b6", "b8", "b10"), study.collapsedHeadings)
        assertEquals(emptyList<StudyQuestion>(), study.questions)
    }

    @Test fun questionsReadAsShownWithoutTheHiddenLinkSource() {
        val linked = "<h3 data-block-id=\"b1\" data-anchor-text=\"1. How does [[raft|Raft]] elect?[^1]\">1. How does " +
            "<a class=\"wikilink\" href=\"/note-link?target=raft\"><span class=\"wl-hidden\">[[raft|</span>Raft<span class=\"wl-hidden\">]]</span></a>" +
            " elect?<sup class=\"fn-ref\" data-footnote=\"1\"><span class=\"wl-hidden\">[^1]</span></sup></h3>"
        val study = studyNote(page(heading(0, "Foundations") + linked), bank)
        assertEquals(listOf("How does Raft elect?"), study.questions.map { it.text })
    }
}
