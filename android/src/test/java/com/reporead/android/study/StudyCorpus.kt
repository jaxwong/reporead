package com.reporead.android.study

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Reads every bank among the pages the backend's opt-in RenderedCorpus wrote (its `pages` directory, named by
 * REPOREAD_STUDY_PAGES) and fails naming each break of the bank format: an H3 that is no question (no Canonical line,
 * or a path with nothing to ask), an answer line that links nothing, or a link to a path with no page. Headings inside
 * linked notes are checked on the phone, where the reader finds them. Read-only and opt-in; the pages are private notes
 * and are never committed. Run it after adding or editing interview folders.
 */
class StudyCorpus {
    @Test
    fun everyBankFollowsTheFormat() {
        val pages = System.getenv("REPOREAD_STUDY_PAGES")
        assumeTrue("Set REPOREAD_STUDY_PAGES to RenderedCorpus's pages directory", pages != null)
        val root = File(checkNotNull(pages))
        require(root.isDirectory) { "REPOREAD_STUDY_PAGES=$pages is not a directory" }
        val counts = sortedMapOf<String, Int>()
        val problems = mutableListOf<String>()
        for (file in root.walkTopDown().filter { it.isFile && it.name.endsWith(".md.html") }) {
            val path = file.relativeTo(root).invariantSeparatorsPath.removeSuffix(".html")
            val bank = interviewBank(file.readText(), path)
            bank.unrecognized.forEach { problems += "$path: not a question: ${it.text}" }
            if (bank.questions.isEmpty()) continue
            counts.merge(path.substringBeforeLast('/'), bank.questions.size, Int::plus)
            for (answer in bank.questions.map { it.answer }.distinct()) {
                if (answer.links.isEmpty()) problems += "$path: answer links nothing: ${answer.text}"
                answer.links
                    .mapNotNull { it.path }
                    .filterNot { File(root, "$it.html").isFile }
                    .forEach { problems += "$path: no page for link to $it" }
            }
        }
        counts.forEach { (folder, count) -> println("$count questions in $folder") }
        println("${counts.values.sum()} questions in ${counts.size} folders")
        assertEquals("Bank format problems", emptyList<String>(), problems)
    }
}
