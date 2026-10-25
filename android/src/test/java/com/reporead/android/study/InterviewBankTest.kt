package com.reporead.android.study

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InterviewBankTest {
    private fun heading(id: String, name: String, level: Int = 3) =
        """<h$level data-block-id="$id" data-anchor-text="$name">$name</h$level>"""

    private fun paragraph(id: String, text: String) = """<p data-block-id="$id" data-anchor-text="$text">$text</p>"""

    /** A labelled paragraph as the server renders `**Label:** text`. */
    private fun labelled(id: String, label: String, text: String = "") =
        """<p data-block-id="$id" data-anchor-text="$label $text"><strong>$label</strong> $text</p>"""

    private fun bullets(id: String, vararg items: String, tag: String = "ul") =
        "<$tag>" +
            items.withIndex().joinToString("") { (index, item) ->
                """<li data-block-id="$id-$index" data-anchor-text="$item">$item</li>"""
            } +
            "</$tag>"

    /**
     * A Canonical line as the server renders a Markdown link to a heading: label, then links carrying path and heading.
     */
    private fun line(id: String, label: String, vararg links: Pair<String, String>) =
        """<p data-block-id="$id" data-anchor-text="$label ${links.joinToString(" and ") { it.second }}"><strong>$label</strong> """ +
            links.joinToString(" and ") { (href, text) -> """<a href="/note-link?$href">$text</a>""" } +
            "</p>"

    /** One question's blocks after its heading, in contract order; a test drops, swaps or adds one to break a rule. */
    private fun parts(n: Int, kind: String = "Canonical answer:") =
        mutableListOf(
            labelled("t$n", "Interviewer is testing:", "recall."),
            labelled("c$n", "A complete answer should:"),
            bullets("i$n", "name it", "say why"),
            labelled("s$n", "Short answer:", "It is $n."),
            line("a$n", kind, "path=core%2Fx.md&heading=x$n" to "Section $n"),
        )

    private fun question(n: Int, title: String, parts: List<String> = parts(n)) =
        heading("q$n", title) + parts.joinToString("")

    private fun page(body: String) = "<main id=note>$body</main>"

    @Test
    fun aQuestionInTheContractsShapeReadsEveryPart() {
        val html =
            page(
                heading("h0", "Bank", 1) +
                    paragraph("p0", "Answer in 90 seconds.") +
                    heading("h1", "1. Foundations", 2) +
                    heading("q1", "1. What is ACID?") +
                    paragraph("e1", "Evidence:") +
                    "<pre data-block-id=\"x1\" data-anchor-text=\"\$ ls\n\"><code>\$ ls\nREADME</code></pre>" +
                    bullets("o1", "one", "two", tag = "ol") +
                    labelled("t1", "Interviewer is testing:", "protection.") +
                    labelled("c1", "A complete answer should:") +
                    bullets("i1", "name each property", "say which the database enforces") +
                    """<p data-block-id="w1" data-anchor-text="Worked answer: Bitly (free)."><strong>Worked answer:</strong> <a href="https://example.com/bitly">Bitly</a> (free).</p>""" +
                    labelled("s1", "Short answer:", "Four guarantees of a transaction.") +
                    line(
                        "a1",
                        "Canonical answer:",
                        "path=core%2Fdatabases%2F07.md&heading=acid-in-practical-language" to
                            "ACID in practical language",
                        "target=b" to "B",
                    ) +
                    heading("h2", "Diagnosis", 2) +
                    question(2, "2. Why 503?")
            )
        val parsed = parseInterviewBank(html)
        assertEquals(emptyList<BankProblem>(), parsed.unrecognized)
        val (acid, diagnosis) = parsed.questions
        assertEquals(
            StudyQuestion(
                "q1",
                "What is ACID?",
                StudyAnswer(
                    "a1",
                    "Canonical answer: ACID in practical language and B",
                    listOf(
                        AnswerLink(
                            null,
                            "core/databases/07.md",
                            "acid-in-practical-language",
                            "ACID in practical language",
                        ),
                        AnswerLink("b", null, null, "B"),
                    ),
                ),
                shortAnswer = "Four guarantees of a transaction.",
                testing = "protection.",
                checklist = listOf("name each property", "say which the database enforces"),
                material =
                    listOf(
                        PromptBlock.Paragraph("Evidence:"),
                        PromptBlock.Code("\$ ls\nREADME"),
                        PromptBlock.Items(listOf("one", "two"), isOrdered = true),
                    ),
                worked = LinkedText("Bitly (free).", listOf(WebLink("Bitly", "https://example.com/bitly"))),
                level = "Foundations",
            ),
            acid,
        )
        assertEquals("What is ACID?", acid.prompt)
        assertEquals("Diagnosis", diagnosis.level)
        assertEquals("It is 2.", diagnosis.shortAnswer)
        assertNull(diagnosis.worked)
    }

    @Test
    fun aPathAsksItsPromptListsTopLevelItemsAsFollowUpsAndShowsTheRest() {
        val html =
            page(
                heading("h0", "3. Design follow-ups", 2) +
                    heading("q1", "After you add a cache") +
                    paragraph("p1", "Assume one region.") +
                    "<ol>" +
                    """<li data-block-id="f0" data-anchor-text="Which key?">Which key?</li>""" +
                    "<li>What expires?<ul>" +
                    """<li data-block-id="f1" data-anchor-text="A hint">A hint</li>""" +
                    "</ul></li></ol>" +
                    parts(1, "Canonical path:").joinToString("")
            )
        val question = parseInterviewBank(html).questions.single()
        assertEquals(listOf("Which key?", "What expires?"), question.followUps)
        assertEquals("After you add a cache\n\n1. Which key?\n2. What expires?", question.prompt)
        assertEquals(listOf(PromptBlock.Paragraph("Assume one region.")), question.material)
        assertEquals("Canonical path: Section 1", question.answer.text)
    }

    @Test
    fun aQuestionBreakingTheShapeIsAWarningNamingTheFirstRuleItBreaks() {
        val worked =
            """<p data-block-id="w" data-anchor-text="Worked answer: see notes"><strong>Worked answer:</strong> see notes</p>"""
        val cases: List<Pair<String, (MutableList<String>) -> Unit>> =
            listOf(
                "no Canonical line" to { it.removeAt(4) },
                "more than one Canonical line" to { it += line("a9", "Canonical answer:", "target=y" to "Y") },
                "no “Interviewer is testing:”" to { it.removeAt(0) },
                "no “A complete answer should:”" to
                    {
                        it.removeAt(2)
                        it.removeAt(1)
                    },
                "no “Short answer:”" to { it.removeAt(3) },
                "labels out of order" to { it.add(0, it.removeAt(3)) },
                "“Short answer:” twice" to { it.add(3, labelled("s9", "Short answer:", "Again.")) },
                "unknown label “A complete answer should cover:”" to
                    {
                        it[1] = labelled("c9", "A complete answer should cover:")
                    },
                "the checklist is not a bullet list under its label" to
                    {
                        it[1] = labelled("c9", "A complete answer should:", "name it; say why")
                        it.removeAt(2)
                    },
                "a list after the labels" to { it[2] = bullets("i9", "name it", tag = "ol") },
                "the checklist nests bullets" to
                    {
                        it[2] =
                            "<ul><li>name it<ul>" +
                                """<li data-block-id="n" data-anchor-text="x">x</li>""" +
                                "</ul></li></ul>"
                    },
                "a paragraph after the labels" to { it.add(4, paragraph("z", "An aside.")) },
                "a --- rule after the labels" to { it += "<hr>" },
                "a #### heading after the labels" to { it.add(4, heading("h9", "Detail", 4)) },
                "a table in the prompt" to
                    {
                        it.add(0, """<table><tr><td data-block-id="td" data-anchor-text="x">x</td></tr></table>""")
                    },
                "“Short answer:” has no text" to { it[3] = labelled("s9", "Short answer:") },
                "“Interviewer is testing:” has no text" to { it[0] = labelled("t9", "Interviewer is testing:") },
                "“Worked answer:” links nothing" to { it.add(3, worked) },
                "the Canonical line links no note" to
                    {
                        it[4] =
                            """<p data-block-id="a9" data-anchor-text="Canonical answer: see"><strong>Canonical answer:</strong> see</p>"""
                    },
            )
        for ((rule, breaks) in cases) {
            val blocks = parts(1).also(breaks)
            val parsed = parseInterviewBank(page(question(1, "1. Why?", blocks)))
            assertEquals(rule, emptyList<StudyQuestion>(), parsed.questions)
            assertEquals(rule, listOf(BankProblem("q1", "1. Why? — $rule")), parsed.unrecognized)
        }
    }

    @Test
    fun onlyABoldLabelIsALabel() {
        // A plain "Canonical answer:" is prose: neither a question's line nor a stray one.
        val parsed =
            parseInterviewBank(
                page(
                    question(1, "Why?", parts(1).also { it[4] = paragraph("a1", "Canonical answer: Plain") }) +
                        paragraph("a2", "Canonical answer: Plain")
                )
            )
        assertEquals(listOf(BankProblem("q1", "Why? — no Canonical line")), parsed.unrecognized)
    }

    @Test
    fun aCanonicalLineUnderNoQuestionIsListedToo() {
        val parsed =
            parseInterviewBank(
                page(heading("h0", "Applied questions", 2) + line("a0", "Canonical answer:", "target=x" to "X"))
            )
        assertEquals(emptyList<StudyQuestion>(), parsed.questions)
        assertEquals(listOf(BankProblem("a0", "Canonical answer: X")), parsed.unrecognized)
    }

    @Test
    fun repeatedHeadingsKeepTheirOwnBlocksRatherThanTheFirstMatchingText() {
        val parsed =
            parseInterviewBank(page(question(1, "Repeated") + heading("r2", "Repeated") + heading("r3", "Repeated")))
        assertEquals(listOf("q1"), parsed.questions.map { it.blockId })
        assertEquals(
            listOf(
                BankProblem("r2", "Repeated — no Canonical line"),
                BankProblem("r3", "Repeated — no Canonical line"),
            ),
            parsed.unrecognized,
        )
    }

    @Test
    fun aRepeatedCanonicalLineKeepsTheFirstQuestionAndWarnsAtTheLaterPrompt() {
        val parsed =
            parseInterviewBank(
                page(
                    question(1, "First?") +
                        question(
                            2,
                            "Second?",
                            parts(2).also { it[4] = line("a2", "Canonical answer:", "target=z" to "Section 1") },
                        ) +
                        question(3, "Different?")
                )
            )
        assertEquals(listOf("First?", "Different?"), parsed.questions.map { it.text })
        assertEquals(listOf(BankProblem("q2", "Second? — repeats a Canonical line")), parsed.unrecognized)
    }

    @Test
    fun aPageWithoutItsNoteHoldsNoQuestions() {
        assertEquals(InterviewBank(emptyList(), emptyList()), parseInterviewBank(""))
    }

    @Test
    fun aBanksTopicIsTheFolderHoldingItsNearestInterviewFolder() {
        assertEquals("core/databases", interviewTopic("core/databases/interview/01.md"))
        assertEquals("core/databases", interviewTopic("core/databases/interview/deep/01.md"))
        assertEquals("core/interview/x", interviewTopic("core/interview/x/interview/01.md"))
        assertEquals("", interviewTopic("interview/01.md"))
        for (path in listOf("core/databases/01.md", "core/interview.md", "core/interviews/01.md", "interview")) {
            assertNull(path, interviewTopic(path))
            assertFalse(path, isInterviewBank(path))
        }
        assertTrue(isInterviewBank("core/databases/interview/01.md"))
    }

    @Test
    fun questionsAndLinksReadAsShownWithoutTheHiddenLinkSource() {
        val linked =
            "<h3 data-block-id=\"b1\" data-anchor-text=\"1. How does [[raft|Raft]] elect?[^1]\">1. How does " +
                "<a class=\"wikilink\" href=\"/note-link?target=raft\"><span class=\"wl-hidden\">[[raft|</span>Raft<span class=\"wl-hidden\">]]</span></a>" +
                " elect?<sup class=\"fn-ref\" data-footnote=\"1\"><span class=\"wl-hidden\">[^1]</span></sup></h3>"
        val line =
            "<p data-block-id=\"b2\" data-anchor-text=\"Canonical answer: [[raft#Election|Election]]\"><strong>Canonical answer:</strong> " +
                "<a class=\"wikilink\" href=\"/note-link?target=raft&amp;heading=Election\"><span class=\"wl-hidden\">[[raft#Election|</span>Election" +
                "<span class=\"wl-hidden\">]]</span></a></p>"
        val question =
            parseInterviewBank(
                    page(heading("h0", "Foundations", 2) + linked + parts(1).dropLast(1).joinToString("") + line)
                )
                .questions
                .single()
        assertEquals("How does Raft elect?", question.text)
        assertEquals(listOf(AnswerLink("raft", null, "Election", "Election")), question.answer.links)
    }
}
