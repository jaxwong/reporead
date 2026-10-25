package com.reporead.android.study

import com.reporead.android.database.NOTE_LINK_PATH
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
    data class Items(val items: List<String>, val isOrdered: Boolean) : PromptBlock

    data class Code(val text: String) : PromptBlock
}

/** A web link inside [LinkedText]: where it goes and the [range] of the shown text it covers. */
internal data class WebLink(val url: String, val range: IntRange)

/** Text as shown with the web links in it, as a bank writes a Worked answer. */
internal data class LinkedText(val text: String, val links: List<WebLink>) {
    /**
     * Without the whitespace around [text]; a link's spaces at the edge are trimmed with it, the link keeps the rest.
     */
    fun trimmed(): LinkedText {
        val start = text.indexOfFirst { !it.isWhitespace() }.takeIf { it >= 0 } ?: text.length
        return part(start, maxOf(start, text.indexOfLast { !it.isWhitespace() } + 1))
    }

    /** What follows [label] at the start of [text], trimmed; all of it when the text does not start with the label. */
    fun after(label: String): LinkedText =
        if (text.startsWith(label)) part(label.length, text.length).trimmed() else this

    private fun part(start: Int, end: Int) =
        LinkedText(
            text.substring(start, end),
            links.mapNotNull { link ->
                val kept = maxOf(link.range.first, start) until minOf(link.range.last + 1, end)
                if (kept.isEmpty()) null else link.copy(range = (kept.first - start)..(kept.last - start))
            },
        )
}

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
 * line under no H3, and a repeated Canonical line are warnings. Which notes are banks is [isInterviewBank]'s to say.
 */
internal fun parseInterviewBank(html: String): InterviewBank {
    val bankDocument = Jsoup.parse(html).getElementById("note") ?: return InterviewBank(emptyList(), emptyList())
    val questions = mutableListOf<StudyQuestion>()
    val unrecognized = mutableListOf<BankProblem>()
    val seenCanonicalAnswers = mutableSetOf<String>()
    var level: String? = null
    var questionHeading: Element? = null
    val questionBlocks = mutableListOf<Element>()
    fun finishQuestion() {
        questionHeading?.let { heading ->
            when (val parsed = parseQuestion(heading, questionBlocks, level)) {
                is QuestionParseResult.Invalid ->
                    unrecognized += BankProblem(heading.attr("data-block-id"), "${shownText(heading)} — ${parsed.rule}")
                is QuestionParseResult.Parsed ->
                    if (seenCanonicalAnswers.add(parsed.question.answer.text)) questions += parsed.question
                    else
                        unrecognized +=
                            BankProblem(parsed.question.blockId, "${parsed.question.text} — repeats a Canonical line")
            }
        }
        questionHeading = null
        questionBlocks.clear()
    }
    for (block in bankDocument.children()) {
        val depth = headingDepth(block)
        if (depth != null && depth <= 3) {
            finishQuestion()
            if (depth == 1) level = null
            if (depth == 2) level = shownText(block).replaceFirst(NUMBERED, "")
            if (depth == 3) questionHeading = block
            continue
        }
        if (questionHeading != null) questionBlocks.add(block)
        else if (label(block) in CANONICAL) unrecognized += BankProblem(block.attr("data-block-id"), shownText(block))
    }
    finishQuestion()
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
private fun labelled(block: Element, label: String): String = shown(block).after(label).text

/**
 * [labelled]'s text of [block] with the range of each web link in it as the page placed it, so a link text that also
 * appears elsewhere links where it was written. Null when no link shows any of its text.
 */
private fun linkedText(block: Element, label: String): LinkedText? =
    shown(block).after(label).takeIf { it.links.isNotEmpty() }

private sealed interface QuestionParseResult {
    data class Parsed(val question: StudyQuestion) : QuestionParseResult

    data class Invalid(val rule: String) : QuestionParseResult
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
private fun parseQuestion(heading: Element, blocks: List<Element>, level: String?): QuestionParseResult {
    val canonicalBlocks = blocks.filter { label(it) in CANONICAL }
    if (canonicalBlocks.isEmpty()) return QuestionParseResult.Invalid("no Canonical line")
    if (canonicalBlocks.size > 1) return QuestionParseResult.Invalid("more than one Canonical line")
    val firstLabelIndex = blocks.indexOfFirst { label(it) != null }
    val promptBlocks = blocks.subList(0, firstLabelIndex)
    promptBlocks
        .firstOrNull { it.tagName() !in PROMPT_TAGS }
        ?.let {
            return QuestionParseResult.Invalid("a ${describe(it)} in the prompt")
        }
    // Insertion order is block order, which the label order is checked against.
    val labels = linkedMapOf<String, Element>()
    var checklist: Element? = null
    for (index in firstLabelIndex until blocks.size) {
        val block = blocks[index]
        val labelName = label(block)
        when {
            labelName == null && block.tagName() == "ul" && label(blocks[index - 1]) == CHECKLIST -> checklist = block
            labelName == null -> return QuestionParseResult.Invalid("a ${describe(block)} after the labels")
            labelName !in LABELS -> return QuestionParseResult.Invalid("unknown label “$labelName”")
            labels.put(labelName, block) != null -> return QuestionParseResult.Invalid("“$labelName” twice")
        }
    }
    val canonicalBlock = canonicalBlocks.single()
    val canonicalLabel = checkNotNull(label(canonicalBlock))
    val requiredLabelOrder = listOfNotNull(TESTING, CHECKLIST, WORKED.takeIf { it in labels }, SHORT, canonicalLabel)
    requiredLabelOrder
        .firstOrNull { it !in labels }
        ?.let {
            return QuestionParseResult.Invalid("no “$it”")
        }
    if (labels.keys.toList() != requiredLabelOrder) return QuestionParseResult.Invalid("labels out of order")
    val testing =
        labelled(labels.getValue(TESTING), TESTING).ifEmpty {
            return QuestionParseResult.Invalid("“$TESTING” has no text")
        }
    val shortAnswer =
        labelled(labels.getValue(SHORT), SHORT).ifEmpty {
            return QuestionParseResult.Invalid("“$SHORT” has no text")
        }
    if (labelled(labels.getValue(CHECKLIST), CHECKLIST).isNotEmpty() || checklist == null)
        return QuestionParseResult.Invalid("the checklist is not a bullet list under its label")
    if (checklist.select("li ul, li ol").isNotEmpty()) return QuestionParseResult.Invalid("the checklist nests bullets")
    val worked =
        labels[WORKED]?.let { block ->
            linkedText(block, WORKED) ?: return QuestionParseResult.Invalid("“$WORKED” links nothing")
        }
    val canonicalAnswer =
        StudyAnswer(
            canonicalBlock.attr("data-block-id"),
            canonicalBlock.attr("data-anchor-text"),
            answerLinks(canonicalBlock),
        )
    if (canonicalAnswer.links.isEmpty()) return QuestionParseResult.Invalid("the Canonical line links no note")
    val followUpLists =
        if (canonicalLabel == CANONICAL_PATH) promptBlocks.filter { it.tagName() in setOf("ul", "ol") } else emptyList()
    return QuestionParseResult.Parsed(
        StudyQuestion(
            heading.attr("data-block-id"),
            shownText(heading).replaceFirst(NUMBERED, ""),
            canonicalAnswer,
            shortAnswer,
            testing,
            checklist.children().map { shownText(it) },
            promptBlocks.filter { it !in followUpLists }.map(::promptBlock),
            followUpLists.flatMap { list -> list.children().map { shownText(it, skipNestedLists = true) } },
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
        if (!href.startsWith("$NOTE_LINK_PATH?")) return@mapNotNull null
        val parameters = noteLinkParameters(href.substringAfter('?'))
        AnswerLink(parameters["target"], parameters["path"], parameters["heading"], shownText(link))
    }

/** [shown]'s text alone. */
private fun shownText(element: Element, skipNestedLists: Boolean = false): String = shown(element, skipNestedLists).text

/**
 * What a reader sees of [element]: its text without the hidden source of note links and footnotes (`.wl-hidden`) and,
 * with [skipNestedLists], without nested lists, with each web link in it where the page placed it. Display only;
 * identities are block ids into canonical text.
 */
private fun shown(element: Element, skipNestedLists: Boolean = false): LinkedText {
    val text = StringBuilder()
    val links = mutableListOf<WebLink>()
    fun visit(node: Node) {
        when {
            node is TextNode -> text.append(node.wholeText)
            node is Element &&
                (node.hasClass("wl-hidden") ||
                    (skipNestedLists && node !== element && node.tagName() in setOf("ul", "ol"))) -> Unit
            node is Element -> {
                val start = text.length
                node.childNodes().forEach(::visit)
                val href = node.attr("href")
                if (node.tagName() == "a" && (href.startsWith("https://") || href.startsWith("http://")))
                    links += WebLink(href, start until text.length)
            }
            else -> node.childNodes().forEach(::visit)
        }
    }
    visit(element)
    return LinkedText(text.toString(), links).trimmed()
}
