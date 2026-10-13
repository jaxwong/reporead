package com.reporead.android.reader

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.json.JSONArray
import org.json.JSONObject

/** P3's measured LeetCode headings. This model owns recognition for both the reader and the offline practice list. */
private val COLLAPSED_HEADINGS = setOf("approach", "brute force approach", "optimized approach", "complexities", "mistakes")
/** The interview banks' question sections, measured 2026-10-13 over 85 banks; a leading "N. " is not part of the name. */
private val BANK_SECTIONS = setOf("foundations", "applied questions", "design follow-ups", "stretch", "diagnosis")
private val NUMBERED = Regex("""^\d+\.\s+""")
/** The line a bank question names its answer with; follow-up groups share one "Canonical path". */
private val ANSWER_LINE = Regex("""^Canonical (answer|answers|path):""")

/** A Practice selection names a canonical block only within the saved version it came from. */
data class StudyTarget(val blobSha: String, val blockId: String)

/** A bank question's authored answer: its "Canonical answer" line's block and that block's canonical text. */
internal data class StudyAnswer(val blockId: String, val text: String)

/** [guide] is what the bank says about the question besides the question itself, shown when its card is revealed. */
internal data class StudyQuestion(val blockId: String, val text: String, val instruction: String? = null,
                                  val answer: StudyAnswer? = null, val guide: List<String> = emptyList()) {
    val prompt: String get() = listOfNotNull(instruction, text).joinToString("\n\n")
}
internal data class StudyNote(val recognized: Boolean, val collapsedHeadings: List<String>, val questions: List<StudyQuestion>)

internal fun StudyNote.json(): JSONObject = JSONObject().put("collapsedHeadings", JSONArray(collapsedHeadings))
    .put("questions", JSONArray(questions.map { JSONObject().put("blockId", it.blockId).put("text", it.prompt) }))

/** Questions come only from interview banks: notes in a folder named `interview`, at any depth. */
internal fun isInterviewBank(path: String): Boolean = "interview" in path.split('/').dropLast(1)

/**
 * Reads only the renderer's canonical blocks. In a bank's question sections, each numbered H3 is one question, and each
 * top-level list item under an unnumbered H3 ("After you …") is one, with that heading as its instruction.
 */
internal fun studyNote(html: String, path: String): StudyNote {
    val bank = isInterviewBank(path)
    val blocks = Jsoup.parse(html).select("#note [data-block-id]")
    val collapsed = mutableListOf<String>()
    val questions = mutableListOf<StudyQuestion>()
    var leetcode = false
    var questionSection = false
    var group: Element? = null
    val section = mutableListOf<Element>()
    fun finishGroup() {
        group?.let { questions += bankQuestions(it, section) }
        group = null
        section.clear()
    }
    for (block in blocks) {
        val tag = block.tagName()
        if (tag.length == 2 && tag[0] == 'h' && tag[1] in '1'..'6') {
            val level = tag[1].digitToInt()
            val name = block.attr("data-anchor-text").trim()
            if (level <= 3) finishGroup()
            if (level <= 2) questionSection = bank && level == 2 && name.replaceFirst(NUMBERED, "").lowercase() in BANK_SECTIONS
            if (level == 3 && questionSection) group = block
            when (name.lowercase()) {
                in COLLAPSED_HEADINGS -> { leetcode = true; collapsed += block.attr("data-block-id") }
                "problem" -> leetcode = true
            }
            continue
        }
        if (group != null) section.add(block)
    }
    finishGroup()
    return StudyNote(leetcode || questions.isNotEmpty(), collapsed, questions)
}

/** The questions under one bank H3, from its [blocks] up to the next H1–H3. */
private fun bankQuestions(heading: Element, blocks: List<Element>): List<StudyQuestion> {
    val items = candidates(blocks)
    val answer = blocks.firstOrNull { it.tagName() == "p" && ANSWER_LINE.containsMatchIn(it.attr("data-anchor-text")) }
        ?.let { StudyAnswer(it.attr("data-block-id"), it.attr("data-anchor-text")) }
    val title = shownText(heading)
    if (NUMBERED.containsMatchIn(title)) {
        return listOf(StudyQuestion(heading.attr("data-block-id"), title.replaceFirst(NUMBERED, ""), null, answer, items.map { it.text }))
    }
    val guide = items.filterNot { it.listItem }.map { it.text }
    return items.filter { it.listItem }.map { StudyQuestion(it.block.attr("data-block-id"), it.text, title, answer, guide) }
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
