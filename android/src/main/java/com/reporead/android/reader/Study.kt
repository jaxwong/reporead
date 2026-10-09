package com.reporead.android.reader

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.json.JSONArray
import org.json.JSONObject

/** P3's measured headings. This model owns recognition for both the reader and the offline practice list. */
private val QUESTION_HEADINGS = setOf("questions this file answers", "review and practice")
private val COLLAPSED_HEADINGS = setOf("approach", "brute force approach", "optimized approach", "complexities", "mistakes")

/** A Practice selection names a canonical block only within the saved version it came from. */
data class StudyTarget(val blobSha: String, val blockId: String)

internal data class StudyQuestion(val blockId: String, val text: String, val instruction: String? = null) {
    val prompt: String get() = listOfNotNull(instruction, text).joinToString("\n\n")
}
internal data class StudyNote(val recognized: Boolean, val collapsedHeadings: List<String>, val questions: List<StudyQuestion>)

internal fun StudyNote.json(): JSONObject = JSONObject().put("collapsedHeadings", JSONArray(collapsedHeadings))
    .put("questions", JSONArray(questions.map { JSONObject().put("blockId", it.blockId).put("text", it.prompt) }))

/** Reads only the renderer's canonical blocks; code, tables, and nested list explanations are not question items. */
internal fun studyNote(html: String): StudyNote {
    val blocks = Jsoup.parse(html).select("#note [data-block-id]")
    val collapsed = mutableListOf<String>()
    val questions = mutableListOf<StudyQuestion>()
    var recognized = false
    var questionLevel: Int? = null
    var review = false
    val section = mutableListOf<Element>()
    fun finishSection() {
        questions += questionItems(section, review)
        section.clear()
        questionLevel = null
    }
    for (block in blocks) {
        val tag = block.tagName()
        if (tag.length == 2 && tag[0] == 'h' && tag[1] in '1'..'6') {
            val level = tag[1].digitToInt()
            val name = block.attr("data-anchor-text").trim().lowercase()
            if (questionLevel?.let { level <= it || name in QUESTION_HEADINGS } == true) finishSection()
            when (name) {
                in QUESTION_HEADINGS -> { recognized = true; questionLevel = level; review = name == "review and practice" }
                in COLLAPSED_HEADINGS -> { recognized = true; collapsed += block.attr("data-block-id") }
                "problem" -> recognized = true
            }
            continue
        }
        if (questionLevel != null) section.add(block)
    }
    finishSection()
    return StudyNote(recognized, collapsed, questions)
}

private fun questionItems(blocks: List<Element>, review: Boolean): List<StudyQuestion> {
    val items = blocks.filter { block ->
        val tag = block.tagName()
        val item = block.closest("li")
        (tag == "p" || tag == "li") && block.attr("data-anchor-text").isNotBlank() &&
            (item == null || (item.parents().none { it.tagName() == "li" } && item.select("[data-block-id]").first() == block))
    }
    fun question(block: Element, instruction: String? = null) =
        StudyQuestion(block.attr("data-block-id"), block.attr("data-anchor-text").trim(), instruction)
    if (!review) return items.map { question(it) }
    if (items.none { it.closest("li") != null }) return items.take(1).map { question(it) }
    val questions = mutableListOf<StudyQuestion>()
    val instruction = mutableListOf<String>()
    var afterList = false
    for (block in items) {
        if (block.closest("li") == null) {
            if (afterList) instruction.clear()
            instruction += block.attr("data-anchor-text").trim()
            afterList = false
        } else {
            questions += question(block, instruction.takeIf { it.isNotEmpty() }?.joinToString("\n\n"))
            afterList = true
        }
    }
    return questions
}
