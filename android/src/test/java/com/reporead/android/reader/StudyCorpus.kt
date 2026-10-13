package com.reporead.android.reader

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Extracts questions from every page the backend's opt-in RenderedCorpus wrote (its `pages` directory, named by
 * REPOREAD_STUDY_PAGES) and fails listing each bank question with no Canonical answer line, so it can't become a card.
 * Read-only and opt-in; the pages are private notes and are never committed. Run it after adding interview folders.
 */
class StudyCorpus {
    @Test fun everyBankQuestionHasAnAnswerLine() {
        val pages = System.getenv("REPOREAD_STUDY_PAGES")
        assumeTrue("Set REPOREAD_STUDY_PAGES to RenderedCorpus's pages directory", pages != null)
        val root = File(checkNotNull(pages))
        require(root.isDirectory) { "REPOREAD_STUDY_PAGES=$pages is not a directory" }
        val counts = sortedMapOf<String, Int>()
        val unanswered = mutableListOf<String>()
        for (file in root.walkTopDown().filter { it.isFile && it.name.endsWith(".md.html") }) {
            val path = file.relativeTo(root).invariantSeparatorsPath.removeSuffix(".html")
            val questions = studyNote(file.readText(), path).questions
            if (questions.isEmpty()) continue
            counts.merge(path.substringBeforeLast('/'), questions.size, Int::plus)
            questions.filter { it.answer == null }.forEach { unanswered += "$path: ${it.text}" }
        }
        counts.forEach { (folder, count) -> println("$count questions in $folder") }
        println("${counts.values.sum()} questions in ${counts.size} folders")
        assertEquals("Bank questions without a Canonical answer line", emptyList<String>(), unanswered)
    }
}
