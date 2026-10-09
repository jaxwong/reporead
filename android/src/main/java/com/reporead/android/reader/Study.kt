package com.reporead.android.reader

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
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

/** Reads only the renderer's canonical blocks; code, tables, and nested list points are not question items. */
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

/** A question candidate: [block] identifies it, [text] is what the reader sees, [listItem] tells items from prose. */
private class Candidate(val block: Element, val text: String, val listItem: Boolean)

/**
 * One candidate per paragraph and per top-level list item, at the item's first block: nested points and further
 * paragraphs of an item belong to it. A tight item with nested points has its own text in no block ("- Why?" above
 * "  - hint"), so its text is the item's own and its identity the first nested block.
 */
private fun candidates(blocks: List<Element>): List<Candidate> {
    val seen = mutableSetOf<Element>()
    return blocks.mapNotNull { block ->
        val tag = block.tagName()
        if (tag != "p" && tag != "li") return@mapNotNull null
        val item = block.parents().lastOrNull { it.tagName() == "li" } ?: block.takeIf { tag == "li" }
        val text = when {
            item == null -> shownText(block)
            !seen.add(item) -> return@mapNotNull null
            block.closest("li") === item -> shownText(block)
            else -> shownText(item, skipNestedLists = true)
        }
        text.takeIf { it.isNotBlank() }?.let { Candidate(block, it, item != null) }
    }
}

/**
 * What a reader sees of [element]: its text without the hidden source of note links and footnotes (`.wl-hidden`) and,
 * with [skipNestedLists], without nested lists. Display only; identities are block ids into canonical text.
 */
private fun shownText(element: Element, skipNestedLists: Boolean = false): String = buildString {
    fun visit(node: Node) {
        when {
            node is TextNode -> append(node.wholeText)
            node is Element && (node.hasClass("wl-hidden") || (skipNestedLists && node !== element && node.tagName() in setOf("ul", "ol"))) -> Unit
            else -> node.childNodes().forEach(::visit)
        }
    }
    visit(element)
}.trim()

private fun questionItems(blocks: List<Element>, review: Boolean): List<StudyQuestion> {
    val items = candidates(blocks)
    fun question(item: Candidate, instruction: String? = null) = StudyQuestion(item.block.attr("data-block-id"), item.text, instruction)
    if (!review) return items.map { question(it) }
    if (items.none { it.listItem }) return items.take(1).map { question(it) }
    val questions = mutableListOf<StudyQuestion>()
    val instruction = mutableListOf<String>()
    var afterList = false
    for (item in items) {
        if (!item.listItem) {
            if (afterList) instruction.clear()
            instruction += item.text
            afterList = false
        } else {
            questions += question(item, instruction.takeIf { it.isNotEmpty() }?.joinToString("\n\n"))
            afterList = true
        }
    }
    return questions
}
