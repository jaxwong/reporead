package com.reporead.android.search

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchTest {
    @Test fun likeWildcardsAndTheEscapeCharacterAreMatchedLiterally() {
        assertEquals("%100\\% of a\\_b \\\\ c%", likePattern("100% of a_b \\ c"))
        assertEquals("%@Transactional%", likePattern("@Transactional"))
    }

    @Test fun snippetShowsOneLineAroundTheFirstMatchIgnoringCase() {
        val text = "First line.\n" + "x".repeat(50) + " Propagation REQUIRED joins the caller.\n" + "y".repeat(80)
        val snippet = snippet(text, "propagation required")
        assertEquals("…" + "x".repeat(39) + " Propagation REQUIRED joins the caller. " + "y".repeat(41) + "…", snippet)
        assertEquals("short note", snippet("short note", "NOTE"))
    }
}
