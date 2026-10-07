package com.reporead.android.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OutlineTest {
    private val outline = listOf(OutlineEntry("b2", 2, 1, "Top"), OutlineEntry("b5", 5, 2, "Child"), OutlineEntry("b9", 9, 2, "Next"))

    @Test fun theSectionIsTheLastHeadingAtOrAboveTheTopBlock() {
        assertEquals("Top", outline.sectionAt(2)?.text)
        assertEquals("Top", outline.sectionAt(4)?.text)
        assertEquals("Child", outline.sectionAt(5)?.text)
        assertEquals("Next", outline.sectionAt(400)?.text)
    }

    @Test fun textBeforeTheFirstHeadingAndANoteWithoutHeadingsHaveNoSection() {
        assertNull(outline.sectionAt(0))
        assertNull(emptyList<OutlineEntry>().sectionAt(3))
    }
}
