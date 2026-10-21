package com.reporead.android.study

import com.reporead.android.reader.noteLinkParameters
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

private val NUMBERED = Regex("""^\d+\.\s+""")

// A question's labels, in the one order the bank contract allows after its prompt material.
private const val TESTING = "Interviewer is testing:"
private const val CHECKLIST = "A complete answer should:"
private const val WORKED = "Worked answer:"
private const val SHORT = "Short answer:"
/** The line that is the card: "Canonical answer:" asks the heading; "Canonical path:" asks it with its follow-ups. */
private const val CANONICAL_ANSWER = "Canonical answer:"
private const val CANONICAL_PATH = "Canonical path:"
private val CANONICAL = setOf(CANONICAL_ANSWER, CANONICAL_PATH)
private val LABELS = setOf(TESTING, CHECKLIST, WORKED, SHORT) + CANONICAL

/** What prompt material may be made of: paragraphs, lists and code blocks. */
private val PROMPT_TAGS = setOf("p", "ul", "ol", "pre")

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

/** One block of a question's prompt material, as shown. */
internal sealed interface PromptBlock {
    data class Paragraph(val text: String) : PromptBlock

    /** A list's top-level items; nested points are not shown. */
    data class Items(val items: List<String>, val ordered: Boolean) : PromptBlock

    data class Code(val text: String) : PromptBlock
}

/** A web link inside [LinkedText]: its [text] as shown and where it goes. */
internal data class WebLink(val text: String, val url: String)

/** Text as shown with the web links in it, as a bank writes a Worked answer. */
internal data class LinkedText(val text: String, val links: List<WebLink>)

/**
 * One bank question, as the bank contract shapes it. [material] is shown with the heading before the answer;
 * [followUps] are a follow-up group's list items, asked together under its heading because they share its one Canonical
 * line, which is the card. [shortAnswer] answers it; [testing] and [checklist] are the standard to grade against;
 * [worked] links a worked solution. [level] is the bank section it sits in (Foundations, Applied questions, …), shown
 * only.
 */
internal data class StudyQuestion(
    val blockId: String,
    val text: String,
    val answer: StudyAnswer,
    val shortAnswer: String,
    val testing: String,
    val checklist: List<String>,
    val material: List<PromptBlock> = emptyList(),
    val followUps: List<String> = emptyList(),
    val worked: LinkedText? = null,
    val level: String? = null,
) {
    /** The heading, then the follow-ups numbered in bank order. */
    val prompt: String
        get() =
            listOf(text, followUps.mapIndexed { index, item -> "${index + 1}. $item" }.joinToString("\n"))
                .filter { it.isNotEmpty() }
                .joinToString("\n\n")
}

/**
 * [unrecognized]: what looks like a question but is not, listed, never dropped silently: an H3 that breaks the question
 * shape (named with the rule it breaks), a Canonical line under no H3, or a later question repeating a Canonical line.
 */
internal data class InterviewBank(val questions: List<StudyQuestion>, val unrecognized: List<BankProblem>)

/** A bank block that makes no question; its identity is kept even when other blocks have the same text. */
internal data class BankProblem(val blockId: String, val text: String)

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
 * Reads the note's top-level blocks as the renderer wrote them. Each H3 of a bank (its section runs to the next H1–H3)
 * is a question when it follows the question shape, its heading without a leading "N. "; one that does not, a Canonical
 * line under no H3, and a repeated Canonical line are warnings.
 */
internal fun interviewBank(html: String, path: String): InterviewBank {
    if (!isInterviewBank(path)) return InterviewBank(emptyList(), emptyList())
    val note = Jsoup.parse(html).getElementById("note") ?: return InterviewBank(emptyList(), emptyList())
    val questions = mutableListOf<StudyQuestion>()
    val unrecognized = mutableListOf<BankProblem>()
    val canonicalLines = mutableSetOf<String>()
    var level: String? = null
    var group: Element? = null
    val section = mutableListOf<Element>()
    fun finishGroup() {
        group?.let { heading ->
            when (val read = bankQuestion(heading, section, level)) {
                is Read.Broken ->
                    unrecognized += BankProblem(heading.attr("data-block-id"), "${shownText(heading)} — ${read.rule}")
                is Read.Asked ->
                    if (canonicalLines.add(read.question.answer.text)) questions += read.question
                    else
                        unrecognized +=
                            BankProblem(read.question.blockId, "${read.question.text} — repeats a Canonical line")
            }
        }
        group = null
        section.clear()
    }
    for (block in note.children()) {
        val depth = headingDepth(block)
        if (depth != null && depth <= 3) {
            finishGroup()
            if (depth == 1) level = null
            if (depth == 2) level = shownText(block).replaceFirst(NUMBERED, "")
            if (depth == 3) group = block
            continue
        }
        if (group != null) section.add(block)
        else if (label(block) in CANONICAL) unrecognized += BankProblem(block.attr("data-block-id"), shownText(block))
    }
    finishGroup()
    return InterviewBank(questions, unrecognized)
}

private fun headingDepth(block: Element): Int? =
    block.tagName().takeIf { it.length == 2 && it[0] == 'h' && it[1] in '1'..'6' }?.let { it[1].digitToInt() }

/** A paragraph's label: the bold text ending in a colon that opens it. */
private fun label(block: Element): String? =
    block
        .takeIf { it.tagName() == "p" }
        ?.childNodes()
        ?.firstOrNull { it !is TextNode || !it.isBlank }
        ?.let { it as? Element }
        ?.takeIf { it.tagName() == "strong" }
        ?.let(::shownText)
        ?.takeIf { it.endsWith(":") }

/** What follows a labelled paragraph's [label]. */
private fun labelled(block: Element, label: String): String = shownText(block).removePrefix(label).trim()

private sealed interface Read {
    data class Asked(val question: StudyQuestion) : Read

    data class Broken(val rule: String) : Read
}

/** How a warning names a block that does not belong where it is. */
private fun describe(block: Element): String =
    when (block.tagName()) {
        "p" -> "paragraph"
        "ul",
        "ol" -> "list"
        "pre" -> "code block"
        "hr" -> "--- rule"
        "table" -> "table"
        "blockquote" -> "quote"
        else -> if (headingDepth(block) != null) "#### heading" else "<${block.tagName()}>"
    }

/**
 * The question of one bank H3 from its [blocks] (up to the next H1–H3): prompt material, then exactly the labels in
 * order, the checklist's bullets right after its label and the Canonical line last; otherwise the first rule it breaks.
 */
private fun bankQuestion(heading: Element, blocks: List<Element>, level: String?): Read {
    val canonical = blocks.filter { label(it) in CANONICAL }
    if (canonical.isEmpty()) return Read.Broken("no Canonical line")
    if (canonical.size > 1) return Read.Broken("more than one Canonical line")
    val start = blocks.indexOfFirst { label(it) != null }
    val material = blocks.subList(0, start)
    material
        .firstOrNull { it.tagName() !in PROMPT_TAGS }
        ?.let {
            return Read.Broken("a ${describe(it)} in the prompt")
        }
    // Insertion order is block order, which the label order is checked against.
    val labels = linkedMapOf<String, Element>()
    var checklist: Element? = null
    for (index in start until blocks.size) {
        val block = blocks[index]
        val name = label(block)
        when {
            name == null && block.tagName() == "ul" && label(blocks[index - 1]) == CHECKLIST -> checklist = block
            name == null -> return Read.Broken("a ${describe(block)} after the labels")
            name !in LABELS -> return Read.Broken("unknown label “$name”")
            labels.put(name, block) != null -> return Read.Broken("“$name” twice")
        }
    }
    val line = canonical.single()
    val kind = checkNotNull(label(line))
    val order = listOfNotNull(TESTING, CHECKLIST, WORKED.takeIf { it in labels }, SHORT, kind)
    order
        .firstOrNull { it !in labels }
        ?.let {
            return Read.Broken("no “$it”")
        }
    if (labels.keys.toList() != order) return Read.Broken("labels out of order")
    val testing =
        labelled(labels.getValue(TESTING), TESTING).ifEmpty {
            return Read.Broken("“$TESTING” has no text")
        }
    val shortAnswer =
        labelled(labels.getValue(SHORT), SHORT).ifEmpty {
            return Read.Broken("“$SHORT” has no text")
        }
    if (labelled(labels.getValue(CHECKLIST), CHECKLIST).isNotEmpty() || checklist == null)
        return Read.Broken("the checklist is not a bullet list under its label")
    if (checklist.select("li ul, li ol").isNotEmpty()) return Read.Broken("the checklist nests bullets")
    val worked =
        labels[WORKED]?.let { block ->
            val links =
                block.select("a[href^='https://'], a[href^='http://']").map { WebLink(shownText(it), it.attr("href")) }
            if (links.isEmpty()) return Read.Broken("“$WORKED” links nothing")
            LinkedText(labelled(block, WORKED), links)
        }
    val answer = StudyAnswer(line.attr("data-block-id"), line.attr("data-anchor-text"), answerLinks(line))
    if (answer.links.isEmpty()) return Read.Broken("the Canonical line links no note")
    val lists = if (kind == CANONICAL_PATH) material.filter { it.tagName() in setOf("ul", "ol") } else emptyList()
    return Read.Asked(
        StudyQuestion(
            heading.attr("data-block-id"),
            shownText(heading).replaceFirst(NUMBERED, ""),
            answer,
            shortAnswer,
            testing,
            checklist.children().map { shownText(it) },
            material.filter { it !in lists }.map(::promptBlock),
            lists.flatMap { list -> list.children().map { shownText(it, skipNestedLists = true) } },
            worked,
            level,
        )
    )
}

private fun promptBlock(block: Element): PromptBlock =
    when (block.tagName()) {
        "p" -> PromptBlock.Paragraph(shownText(block))
        "pre" -> PromptBlock.Code(shownText(block))
        else ->
            PromptBlock.Items(block.children().map { shownText(it, skipNestedLists = true) }, block.tagName() == "ol")
    }

/** The note links on an answer line, in order, as the server wrote them (MarkdownRenderer.noteLink). */
private fun answerLinks(line: Element): List<AnswerLink> =
    line.select("a[href]").mapNotNull { link ->
        val href = link.attr("href")
        if (!href.startsWith("/note-link?")) return@mapNotNull null
        val parameters = noteLinkParameters(href.substringAfter('?'))
        AnswerLink(parameters["target"], parameters["path"], parameters["heading"], shownText(link))
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
