package com.reporead.android.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderStudyModeTest {
    private fun heading(id: Int, name: String, level: Int = 2) =
        """<h$level data-block-id="b$id" data-anchor-text="$name">$name</h$level>"""

    private fun block(id: Int, text: String, tag: String = "li") =
        """<$tag data-block-id="b$id" data-anchor-text="$text">$text</$tag>"""

    private fun page(body: String) = "<main id=note>$body</main>"

    @Test
    fun onlyTheFixedLeetcodeHeadingsAreRecognizedIgnoringCaseAndOuterWhitespace() {
        assertTrue(studyNote(page(heading(0, "  MISTAKES  "))).isRecognized)
        assertTrue(studyNote(page(heading(0, "Problem"))).isRecognized)
        assertFalse(studyNote(page(heading(0, "Problems") + block(1, "Mistakes", "pre"))).isRecognized)
        // A bank is practised in Practice, not studied in the reader.
        assertFalse(
            studyNote(page(heading(0, "Foundations") + heading(1, "1. Why?", 3) + block(2, "Canonical answer: X", "p")))
                .isRecognized
        )
        assertFalse(studyNote("").isRecognized)
    }

    @Test
    fun leetcodeSectionsCollapseButProblemAndUnrecognizedSectionsDoNot() {
        val names =
            listOf(
                "Problem",
                "Approach",
                "Brute Force Approach",
                "Optimized Approach",
                "Complexities",
                "Mistakes",
                "Notes",
            )
        val study =
            studyNote(
                page(
                    names
                        .mapIndexed { i, name -> heading(i * 2, name) + block(i * 2 + 1, "body", "p") }
                        .joinToString("")
                )
            )
        assertEquals(listOf("b2", "b4", "b6", "b8", "b10"), study.collapsedHeadings)
    }
}
