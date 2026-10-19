package com.reporead.android.study

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StudyTest {
    private val bank = "core/databases/interview/07-transactions.md"

    private fun heading(id: Int, name: String, level: Int = 2) =
        """<h$level data-block-id="b$id" data-anchor-text="$name">$name</h$level>"""

    private fun block(id: Int, text: String, tag: String = "li") =
        """<$tag data-block-id="b$id" data-anchor-text="$text">$text</$tag>"""

    /**
     * An answer line as the server renders a Markdown link to a heading: label, then link text whose href carries path
     * and heading.
     */
    private fun answer(id: Int, label: String, vararg links: Pair<String, String>) =
        """<p data-block-id="b$id" data-anchor-text="$label: ${links.joinToString(" and ") { it.second }}"><strong>$label:</strong> """ +
            links.joinToString(" and ") { (href, text) -> """<a href="/note-link?$href">$text</a>""" } +
            "</p>"

    private fun page(body: String) = "<main id=note>$body</main>"

    @Test
    fun eachBankH3WithACanonicalAnswerLineIsAQuestionWithItsLinksGuideAndLevel() {
        val html =
            page(
                heading(0, "Bank", 1) +
                    block(1, "Canonical source: the note.", "p") +
                    heading(2, "1. Foundations") +
                    block(3, "Answer in 90 seconds.", "p") +
                    heading(4, "1. What is ACID?", 3) +
                    block(5, "Interviewer is testing: protection.", "p") +
                    block(6, "A complete answer should: name each property.", "p") +
                    answer(
                        7,
                        "Canonical answer",
                        "path=core%2Fdatabases%2F07.md&heading=acid-in-practical-language" to
                            "ACID in practical language",
                    ) +
                    heading(8, "2. Why isolation levels?", 3) +
                    block(9, "Interviewer is testing: the dial.", "p") +
                    heading(10, "Detail", 4) +
                    answer(11, "Canonical answers", "path=core%2Fa.md&heading=a" to "A", "target=b" to "B") +
                    heading(12, "Diagnosis") +
                    heading(13, "1. Why 503?", 3) +
                    "<ul>" +
                    block(14, "check the route") +
                    "</ul>" +
                    answer(15, "Canonical answer", "path=core%2Froutes.md" to "Routes")
            )
        val questions = interviewBank(html, bank).questions
        assertEquals(listOf("What is ACID?", "Why isolation levels?", "Why 503?"), questions.map { it.text })
        assertEquals(listOf("b4", "b8", "b13"), questions.map { it.blockId })
        assertEquals(listOf(null, null, null), questions.map { it.instruction })
        assertEquals(listOf("Foundations", "Foundations", "Diagnosis"), questions.map { it.level })
        assertEquals(
            StudyAnswer(
                "b7",
                "Canonical answer: ACID in practical language",
                listOf(
                    AnswerLink(null, "core/databases/07.md", "acid-in-practical-language", "ACID in practical language")
                ),
            ),
            questions[0].answer,
        )
        assertEquals(
            listOf(AnswerLink(null, "core/a.md", "a", "A"), AnswerLink("b", null, null, "B")),
            questions[1].answer.links,
        )
        assertEquals("Canonical answers: A and B", questions[1].answer.text)
        // The guide is what to check an answer against; the answer line itself is shown as its linked sections.
        assertEquals(
            listOf("Interviewer is testing: protection.", "A complete answer should: name each property."),
            questions[0].guide,
        )
        assertEquals(listOf("check the route"), questions[2].guide)
    }

    @Test
    fun sectionNamesAndNumbersOnlyLabelSoTheContractsOwnTemplateIsOneQuestion() {
        // The bank contract's template: an unnumbered H3 with a bulleted checklist, under a section name never
        // measured.
        val html =
            page(
                heading(0, "Behavioural") +
                    heading(1, "What is the difference between replication and sharding?", 3) +
                    block(2, "Interviewer is testing: copying versus splitting.", "p") +
                    block(3, "A complete answer should cover:", "p") +
                    "<ul>" +
                    block(4, "what each technique does;") +
                    block(5, "one cost of each.") +
                    "</ul>" +
                    answer(
                        6,
                        "Canonical answer",
                        "path=core%2Fr.md&heading=replication-versus-sharding" to "Replication versus sharding",
                    )
            )
        val bank = interviewBank(html, bank)
        assertEquals(listOf("What is the difference between replication and sharding?"), bank.questions.map { it.text })
        assertEquals("Behavioural", bank.questions.single().level)
        assertEquals(
            listOf(
                "Interviewer is testing: copying versus splitting.",
                "A complete answer should cover:",
                "what each technique does;",
                "one cost of each.",
            ),
            bank.questions.single().guide,
        )
        assertEquals(emptyList<BankProblem>(), bank.unrecognized)
    }

    @Test
    fun aFollowUpKeepsItsGroupInstructionAndRepeatsAreWarnings() {
        val html =
            page(
                heading(0, "3. Design follow-ups") +
                    block(1, "Asked after a design.", "p") +
                    heading(2, "After you add a cache", 3) +
                    "<ol>" +
                    block(3, "Which key?") +
                    "<li>What expires?<ul>" +
                    block(4, "A hint") +
                    "</ul></li></ol>" +
                    answer(
                        5,
                        "Canonical path",
                        "path=core%2Fc.md&heading=caching" to "Caching",
                        "path=core%2Fc.md&heading=expiry" to "Expiry",
                    ) +
                    heading(6, "1. How many threads?", 3) +
                    answer(7, "Canonical answer", "path=core%2Fs.md" to "Sizing")
            )
        val parsed = interviewBank(html, bank)
        val questions = parsed.questions
        assertEquals(listOf("Which key?", "How many threads?"), questions.map { it.text })
        assertEquals(listOf("b3", "b6"), questions.map { it.blockId })
        assertEquals("After you add a cache\n\nWhich key?", questions[0].prompt)
        assertEquals(listOf("Caching", "Expiry"), questions[0].answer.links.map { it.text })
        assertEquals(emptyList<String>(), questions[0].guide)
        assertEquals("How many threads?", questions[1].prompt)
        assertEquals(listOf(BankProblem("b4", "What expires? — repeats a Canonical line")), parsed.unrecognized)
    }

    @Test
    fun aHeadingWithoutACanonicalLineIsNotAQuestionButIsListed() {
        val html =
            page(
                heading(0, "Stretch") +
                    heading(1, "1. Why?", 3) +
                    block(2, "Interviewer is testing: depth.", "p") +
                    heading(3, "After you deploy", 3) +
                    answer(4, "Canonical path", "path=core%2Fd.md" to "Deploys") +
                    heading(5, "2. How?", 3) +
                    answer(6, "Canonical answer", "path=core%2Fh.md" to "How")
            )
        val bank = interviewBank(html, bank)
        assertEquals(listOf("How?"), bank.questions.map { it.text })
        // A path with no items to ask is a format slip too.
        assertEquals(listOf(BankProblem("b1", "1. Why?"), BankProblem("b3", "After you deploy")), bank.unrecognized)
    }

    @Test
    fun aCanonicalLineThatMakesNoQuestionIsListedToo() {
        val html =
            page(
                heading(0, "Applied questions") +
                    // Under no question heading.
                    answer(1, "Canonical answer", "target=x" to "X") +
                    // Questions written as H4s under one H3: which line answers the H3 is unknown.
                    heading(2, "Transactions", 3) +
                    heading(3, "1. Why?", 4) +
                    answer(4, "Canonical answer", "target=a" to "A") +
                    heading(5, "2. How?", 4) +
                    answer(6, "Canonical answer", "target=b" to "B")
            )
        val bank = interviewBank(html, bank)
        assertEquals(emptyList<StudyQuestion>(), bank.questions)
        assertEquals(
            listOf(BankProblem("b1", "Canonical answer: X"), BankProblem("b2", "Transactions")),
            bank.unrecognized,
        )
    }

    @Test
    fun repeatedHeadingsKeepTheirOwnBlocksRatherThanTheFirstMatchingText() {
        val html =
            page(
                heading(0, "Repeated", 3) +
                    block(1, "Canonical answer: X", "p") +
                    heading(2, "Repeated", 3) +
                    heading(3, "Repeated", 3)
            )
        val parsed = interviewBank(html, bank)
        assertEquals(listOf("b0"), parsed.questions.map { it.blockId })
        assertEquals(listOf(BankProblem("b2", "Repeated"), BankProblem("b3", "Repeated")), parsed.unrecognized)
    }

    @Test
    fun aRepeatedCanonicalLineKeepsTheFirstQuestionAndWarnsAtTheLaterPrompt() {
        val parsed =
            interviewBank(
                page(
                    heading(1, "First?", 3) +
                        block(2, "Canonical answer: X", "p") +
                        heading(3, "Second?", 3) +
                        block(4, "Canonical answer: X", "p") +
                        heading(5, "Different?", 3) +
                        block(6, "Canonical answer: Y", "p")
                ),
                bank,
            )
        assertEquals(listOf("First?", "Different?"), parsed.questions.map { it.text })
        assertEquals(listOf(BankProblem("b3", "Second? — repeats a Canonical line")), parsed.unrecognized)
    }

    @Test
    fun anAnswerLineWithoutALinkStillMakesAQuestion() {
        val question =
            interviewBank(page(heading(0, "1. Why?", 3) + block(1, "Canonical answer: see the note", "p")), bank)
                .questions
                .single()
        assertEquals(StudyAnswer("b1", "Canonical answer: see the note", emptyList()), question.answer)
        assertNull(question.level)
    }

    @Test
    fun questionsComeOnlyFromNotesInAnInterviewFolder() {
        val html =
            page(
                heading(0, "Applied questions") +
                    heading(1, "1. A bank question?", 3) +
                    answer(2, "Canonical answer", "target=x" to "X")
            )
        assertEquals(1, interviewBank(html, "interview/one.md").questions.size)
        for (path in listOf("core/databases/07-transactions.md", "core/interview.md", "core/interviews/one.md")) {
            assertEquals(path, InterviewBank(emptyList(), emptyList()), interviewBank(html, path))
        }
        assertEquals(InterviewBank(emptyList(), emptyList()), interviewBank("", bank))
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
            "<p data-block-id=\"b2\" data-anchor-text=\"Canonical answer: [[raft#Election|Election]]\">Canonical answer: " +
                "<a class=\"wikilink\" href=\"/note-link?target=raft&amp;heading=Election\"><span class=\"wl-hidden\">[[raft#Election|</span>Election" +
                "<span class=\"wl-hidden\">]]</span></a></p>"
        val question = interviewBank(page(heading(0, "Foundations") + linked + line), bank).questions.single()
        assertEquals("How does Raft elect?", question.text)
        assertEquals(listOf(AnswerLink("raft", null, "Election", "Election")), question.answer.links)
    }
}
