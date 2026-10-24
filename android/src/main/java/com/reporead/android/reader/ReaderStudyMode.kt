package com.reporead.android.reader

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * The headings of a LeetCode solution note, as measured over the real notes (ADR-20). The reader's Study view collapses
 * them; nothing else is recognized there.
 */
private val COLLAPSED_HEADINGS =
    setOf("approach", "brute force approach", "optimized approach", "complexities", "mistakes")

/** The reader's Study view of a LeetCode note: the headings whose sections collapse until tapped. */
internal data class StudyNote(val isRecognized: Boolean, val collapsedHeadings: List<String>)

internal fun StudyNote.json(): JSONObject = JSONObject().put("collapsedHeadings", JSONArray(collapsedHeadings))

internal fun studyNote(html: String): StudyNote {
    val collapsed = mutableListOf<String>()
    var leetcode = false
    for (heading in Jsoup.parse(html).select("#note [data-block-id]").filter { isHeading(it) }) {
        when (heading.attr("data-anchor-text").trim().lowercase()) {
            in COLLAPSED_HEADINGS -> {
                leetcode = true
                collapsed += heading.attr("data-block-id")
            }
            "problem" -> leetcode = true
        }
    }
    return StudyNote(leetcode, collapsed)
}

private fun isHeading(block: Element): Boolean =
    block.tagName().let { it.length == 2 && it[0] == 'h' && it[1] in '1'..'6' }
