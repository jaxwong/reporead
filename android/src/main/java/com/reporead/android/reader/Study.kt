package com.reporead.android.reader

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * The headings of a LeetCode solution note, as measured over the real notes (ADR-20). The reader's Study view collapses
 * them; nothing else is recognized there.
 */
private val COLLAPSED_HEADINGS =
    setOf("approach", "brute force approach", "optimized approach", "complexities", "mistakes")
private val NUMBERED = Regex("""^\d+\.\s+""")
/**
 * The line that makes a bank H3 a question: "Canonical answer(s):" for one numbered question, "Canonical path:" for a
 * follow-up group. Section names and numbers only label.
 */
private val ANSWER_LINE = Regex("""^Canonical (answers?|path):""")

/** The reader's Study view of a LeetCode note: the headings whose sections collapse until tapped. */
internal data class StudyNote(val recognized: Boolean, val collapsedHeadings: List<String>)

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

/**
 * A link on a bank's answer line: a repository [path] (Markdown link) or an Obsidian [target], its [heading], and its
 * text as shown.
 */
internal data class AnswerLink(val target: String?, val path: String?, val heading: String?, val text: String)

/**
 * A bank question's authored answer: its Canonical line's block, that block's canonical text, and the sections it
 * links.
 */
internal data class StudyAnswer(val blockId: String, val text: String, val links: List<AnswerLink>)

/**
 * One bank question. [instruction] is a follow-up group's heading; [level] is the bank section it sits in (Foundations,
 * Applied questions, …), shown only; [guide] is what the bank says besides the question and its answer line: what the
 * interviewer is testing and what a complete answer covers.
 */
internal data class StudyQuestion(
    val blockId: String,
    val text: String,
    val answer: StudyAnswer,
    val instruction: String? = null,
    val guide: List<String> = emptyList(),
    val level: String? = null,
) {
    val prompt: String
        get() = listOfNotNull(instruction, text).joinToString("\n\n")
}

/**
 * [unrecognized]: what looks like a question but is not, listed, never dropped silently: an H3 without exactly one
 * Canonical line (or a path without items), and a Canonical line under no H3.
 */
internal data class InterviewBank(val questions: List<StudyQuestion>, val unrecognized: List<String>)

/**
 * The Practice topic of the note at [path]: the folder holding its nearest folder named `interview` ("" at the
 * repository root). Null for a note outside every interview folder, which is no bank.
 */
internal fun interviewTopic(path: String): String? {
    val folders = path.split('/').dropLast(1)
    val interview = folders.lastIndexOf("interview")
    return if (interview < 0) null else folders.take(interview).joinToString("/")
}

/** Questions come only from interview banks: notes in a folder named `interview`, at any depth. */
internal fun isInterviewBank(path: String): Boolean = interviewTopic(path) != null

/**
 * Reads only the renderer's canonical blocks. Each H3 of a bank whose section (up to the next H1–H3) has exactly one
 * Canonical line is a question: "Canonical answer:" makes the heading the question, without a leading "N. "; "Canonical
 * path:" makes each top-level list item under it one, with the heading as its instruction.
 */
internal fun interviewBank(html: String, path: String): InterviewBank {
    if (!isInterviewBank(path)) return InterviewBank(emptyList(), emptyList())
    val questions = mutableListOf<StudyQuestion>()
    val unrecognized = mutableListOf<String>()
    var level: String? = null
    var group: Element? = null
    val section = mutableListOf<Element>()
    fun finishGroup() {
        group?.let { heading ->
            val found = bankQuestions(heading, section, level)
            if (found.isEmpty()) unrecognized += shownText(heading) else questions += found
        }
        group = null
        section.clear()
    }
    for (block in Jsoup.parse(html).select("#note [data-block-id]")) {
        if (isHeading(block)) {
            val depth = block.tagName()[1].digitToInt()
            if (depth <= 3) finishGroup()
            if (depth == 1) level = null
            if (depth == 2) level = shownText(block).replaceFirst(NUMBERED, "")
            if (depth == 3) group = block
            continue
        }
        if (group != null) section.add(block) else if (answerLine(block) != null) unrecognized += shownText(block)
    }
    finishGroup()
    return InterviewBank(questions, unrecognized)
}

private fun isHeading(block: Element): Boolean =
    block.tagName().let { it.length == 2 && it[0] == 'h' && it[1] in '1'..'6' }

/** A paragraph starting "Canonical answer:" or "Canonical path:", and which of the two. */
private fun answerLine(block: Element): Pair<Element, String>? =
    ANSWER_LINE.find(block.attr("data-anchor-text"))
        ?.takeIf { block.tagName() == "p" }
        ?.let { block to it.groupValues[1] }

/**
 * The questions under one bank H3, from its [blocks] up to the next H1–H3; none unless exactly one is a Canonical line,
 * as with several (questions written as H4s) it is unknown which answers the heading.
 */
private fun bankQuestions(heading: Element, blocks: List<Element>, level: String?): List<StudyQuestion> {
    val (line, kind) = blocks.mapNotNull(::answerLine).singleOrNull() ?: return emptyList()
    val answer = StudyAnswer(line.attr("data-block-id"), line.attr("data-anchor-text"), answerLinks(line))
    val items = candidates(blocks).filter { it.block !== line }
    val title = shownText(heading).replaceFirst(NUMBERED, "")
    if (kind != "path") {
        return listOf(StudyQuestion(heading.attr("data-block-id"), title, answer, null, items.map { it.text }, level))
    }
    val guide = items.filterNot { it.listItem }.map { it.text }
    return items
        .filter { it.listItem }
        .map { StudyQuestion(it.block.attr("data-block-id"), it.text, answer, title, guide, level) }
}

/** The note links on an answer line, in order, as the server wrote them (MarkdownRenderer.noteLink). */
private fun answerLinks(line: Element): List<AnswerLink> =
    line.select("a[href]").mapNotNull { link ->
        val href = link.attr("href")
        if (!href.startsWith("/note-link?")) return@mapNotNull null
        val parameters = noteLinkParameters(href.substringAfter('?'))
        AnswerLink(parameters["target"], parameters["path"], parameters["heading"], shownText(link))
    }

/** A question candidate: [block] identifies it, [text] is what the reader sees, [listItem] tells items from prose. */
private class Candidate(val block: Element, val text: String, val listItem: Boolean)

/**
 * One candidate per paragraph and per top-level list item, at the item's first block: nested points and further
 * paragraphs of an item belong to it. A tight item with nested points has its own text in no block ("- Why?" above " -
 * hint"), so its text is the item's own and its identity the first nested block.
 */
private fun candidates(blocks: List<Element>): List<Candidate> {
    val seen = mutableSetOf<Element>()
    return blocks.mapNotNull { block ->
        val tag = block.tagName()
        if (tag != "p" && tag != "li") return@mapNotNull null
        val item = block.parents().lastOrNull { it.tagName() == "li" } ?: block.takeIf { tag == "li" }
        val text =
            when {
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
            node is Element &&
                (node.hasClass("wl-hidden") ||
                    (skipNestedLists && node !== element && node.tagName() in setOf("ul", "ol"))) -> Unit
            else -> node.childNodes().forEach(::visit)
        }
    }
    visit(element)
}
    .trim()
