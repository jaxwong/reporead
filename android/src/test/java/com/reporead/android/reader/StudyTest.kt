package com.reporead.android.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StudyTest {
    private fun heading(id: Int, name: String, level: Int = 2) =
        """<h$level data-block-id="b$id" data-anchor-text="$name">$name</h$level>"""
    private fun block(id: Int, text: String, tag: String = "li") =
        """<$tag data-block-id="b$id" data-anchor-text="$text">$text</$tag>"""
    private fun page(body: String) = "<main id=note>$body</main>"

    @Test fun questionsComeFromBothStudySectionsAndStopAtPeerHeadings() {
        val html = page(heading(0, "Questions this file answers") + "<ol>" + block(1, "Why &amp; when?") +
            "<li>" + block(2, "A loose item?", "p") + "<ul>" + block(3, "A nested explanation") + "</ul></li></ol>" +
            heading(4, "Answer") + block(5, "Not a question") + heading(6, "Review and practice") +
            block(7, "Try this.", "p") + heading(8, "Details", 3) + "<ul>" + block(9, "A subheading task") + "</ul>" + heading(10, "Done"))
        val study = studyNote(html)
        assertTrue(study.recognized)
        assertEquals(listOf("Why & when?", "A loose item?", "A subheading task"), study.questions.map { it.text })
        assertEquals(listOf("b1", "b2", "b9"), study.questions.map { it.blockId })
        assertEquals("Try this.", study.questions.last().instruction)
    }

    @Test fun onlyTheFixedHeadingsAreRecognizedIgnoringCaseAndOuterWhitespace() {
        assertTrue(studyNote(page(heading(0, "  MISTAKES  "))).recognized)
        assertTrue(studyNote(page(heading(0, "Problem"))).recognized)
        assertFalse(studyNote(page(heading(0, "Problems") + block(1, "Questions this file answers", "pre"))).recognized)
        assertFalse(studyNote("").recognized)
        assertEquals(emptyList<StudyQuestion>(), studyNote(page(heading(0, "Questions this file answers"))).questions)
    }

    @Test fun leetcodeSectionsCollapseButProblemAndUnrecognizedSectionsDoNot() {
        val names = listOf("Problem", "Approach", "Brute Force Approach", "Optimized Approach", "Complexities", "Mistakes", "Notes")
        val study = studyNote(page(names.mapIndexed { i, name -> heading(i * 2, name) + block(i * 2 + 1, "body", "p") }.joinToString("")))
        assertEquals(listOf("b2", "b4", "b6", "b8", "b10"), study.collapsedHeadings)
        assertEquals(emptyList<StudyQuestion>(), study.questions)
    }

    @Test fun nestedListsAndCodeSamplesDoNotCreateExtraQuestionItems() {
        val study = studyNote(page(heading(0, "Review and practice") +
            "<ul><li>" + block(1, "First task", "p") + block(2, "Continuation", "p") +
            "<pre data-block-id=\"b3\" data-anchor-text=\"Not a question\"><code>code</code></pre>" +
            "<ul>" + block(4, "Substep") + "</ul></li>" + block(5, "Second task") + "</ul>"))
        assertEquals(listOf("First task", "Second task"), study.questions.map { it.text })
    }

    @Test fun aTightItemWithNestedPointsIsStillAQuestionPlacedAtItsFirstBlock() {
        // In "- Why?\n  - hint" the item's own text is in no block; the nested point is its first one.
        val study = studyNote(page(heading(0, "Questions this file answers") + "<ul><li>Why does <code>x</code> win?<ul>" +
            block(1, "A hint") + "</ul></li>" + block(2, "A plain item?") + "</ul>"))
        assertEquals(listOf("Why does x win?", "A plain item?"), study.questions.map { it.text })
        assertEquals(listOf("b1", "b2"), study.questions.map { it.blockId })
    }

    @Test fun questionsReadAsShownWithoutTheHiddenLinkSource() {
        val linked = "<li data-block-id=\"b1\" data-anchor-text=\"How does [[raft|Raft]] elect?[^1]\">How does " +
            "<a class=\"wikilink\" href=\"/note-link?target=raft\"><span class=\"wl-hidden\">[[raft|</span>Raft<span class=\"wl-hidden\">]]</span></a>" +
            " elect?<sup class=\"fn-ref\" data-footnote=\"1\"><span class=\"wl-hidden\">[^1]</span></sup></li>"
        val study = studyNote(page(heading(0, "Questions this file answers") + "<ul>$linked</ul>"))
        assertEquals(listOf("How does Raft elect?"), study.questions.map { it.text })
    }

    @Test fun reviewListItemsKeepTheirLeadingInstructionsButNotTrailingNavigation() {
        val study = studyNote(page(heading(0, "Review and practice") + block(1, "Trace one endpoint.", "p") +
            block(2, "Then test:", "p") + "<ul>" + block(3, "empty input") + block(4, "duplicate requests") + "</ul>" +
            block(5, "Next, compare:", "p") + "<ul>" + block(6, "two versions") + "</ul>" +
            block(7, "Continue with the next note.", "p")))
        assertEquals(listOf("empty input", "duplicate requests", "two versions"), study.questions.map { it.text })
        assertEquals(listOf("Trace one endpoint.\n\nThen test:", "Trace one endpoint.\n\nThen test:", "Next, compare:"),
            study.questions.map { it.instruction })
        assertEquals("Trace one endpoint.\n\nThen test:\n\nempty input", study.questions.first().prompt)
    }

    @Test fun aProseOnlyReviewUsesTheFirstParagraphAsOnePrompt() {
        val study = studyNote(page(heading(0, "Review and practice") + block(1, "Build a small example.", "p") +
            block(2, "Worked solution", "pre") + block(3, "Explanation and navigation.", "p")))
        assertEquals(listOf("Build a small example."), study.questions.map { it.text })
        assertEquals(listOf("b1"), study.questions.map { it.blockId })
    }
}
