// Owns: Room query projections and database-local value results; not table schemas or wire records.

package com.reporead.android.database

import androidx.room.Embedded

/**
 * A note in the library's change lists: its current version, when it was seen changing, and the version last read
 * (null: never).
 */
data class ChangedNote(
    val documentId: Long,
    val title: String,
    val path: String,
    val blobSha: String,
    val changedAt: Long?,
    val lastReadBlobSha: String?,
)

/** A saved page's identity without its HTML, used to retain only that snapshot's images. */
data class SavedVersion(
    val documentId: Long,
    val blobSha: String,
    val path: String,
    val renderFormat: Int,
    val snapshotCommitSha: String?,
)

private val NOTE_LINK_QUERY = Regex("""href="/note-link\?([^"]*)"""")

/** The query of each /note-link in a saved page, in page order, as the reader receives it when the link is tapped. */
fun noteLinkQueries(html: String): List<String> =
    NOTE_LINK_QUERY.findAll(html).map { it.groupValues[1].replace("&amp;", "&") }.toList()

/** Owns the bounded query projection used to recognize questions in saved interview-bank pages. */
data class PracticePage(
    val documentId: Long,
    val title: String,
    val path: String,
    val html: String?,
    val blobSha: String?,
)

/** A note whose current title or path matches. Body matches are [NoteTextMatch] rows instead. */
data class NoteNameMatch(val documentId: Long, val title: String, val path: String, val titleMatch: Boolean)

/** One canonical block containing a search phrase, with the exact saved version its [blockId] belongs to. */
data class NoteTextMatch(
    val documentId: Long,
    val title: String,
    val path: String,
    val blobSha: String,
    val blockId: String,
    val text: String,
)

/** A read note in Continue reading, named by its documents row. */
data class ReadNote(
    val documentId: Long,
    val title: String,
    val path: String,
    val deleted: Boolean,
    val progressPercent: Int,
)

/** A bookmarked note, named by its documents row. */
data class BookmarkedNote(val documentId: Long, val title: String, val path: String, val deleted: Boolean)

/** A highlight matching a search, with its note's title and path. */
data class HighlightMatch(
    val mutationId: String,
    val documentId: Long,
    val title: String,
    val path: String,
    val exactText: String,
    val note: String?,
)

/**
 * Owns the phone-side review restrictions, in AnnotationEntity.cardBlock's order. A card is a bank question, authored
 * with its answer, so a new version of its bank never needs confirming: only whether that version still asks it.
 */
enum class CardBlock(val reason: String) {
    /** The server refused its creation, which is never sent again ([AnnotationEntity.rejection] says why). */
    REFUSED("Refused by the server"),
    REMOVED("Note removed from the repository"),
    NOT_SAVED("Save the current note before reviewing"),
    /** Decided by Review, which reads the saved bank: no question there has the card's Canonical line. */
    NOT_ASKED("Its bank no longer asks this question"),
}

/** A new Practice grade writes both rows or neither; an already scheduled question is a successful no-op. */
enum class NewCardGrade {
    SAVED,
    ALREADY_SCHEDULED,
    BANK_CHANGED,
    NOT_SAVED,
}

/** A new highlight is written only on a saved page, and only on a phrase no highlight marks yet. */
sealed interface NewHighlight {
    data class Saved(val mutationId: String) : NewHighlight

    /** [existing] marks the same phrase; nothing was written. */
    data class AlreadyHighlighted(val existing: AnnotationEntity) : NewHighlight

    /** The page is gone (a clear or a disconnect landed first); nothing was written. */
    data object PageGone : NewHighlight
}

/** Distinguishes a bad server grade at the write boundary from a database or programmer failure. */
internal class InvalidReviewGrade(mutationId: String, grade: Int) :
    IllegalArgumentException("Review $mutationId has grade $grade; expected 0..5")

/** A highlight or card with its note's documents row: [deleted] from the repository, at [currentBlobSha]. */
data class NotebookItem(
    @Embedded val annotation: AnnotationEntity,
    val title: String,
    val path: String,
    val currentBlobSha: String,
    val deleted: Boolean,
    val savedBlobSha: String?,
)

/**
 * A card on the review schedule: the server did not refuse it and its note is still listed. Review shows it (one that
 * needs checking, with its fix), and Practice does not ask its question again; refused and removed ones are listed in
 * Notebook.
 */
internal fun reviewable(item: NotebookItem) =
    item.annotation.type == AnnotationType.CARD && item.annotation.state != AnnotationState.REFUSED && !item.deleted

/** Scheduled question identity is the bank document and drawn Canonical line, never the saved prompt or version. */
internal fun scheduledLines(cards: List<NotebookItem>): Set<Pair<Long, String>> =
    cards.filter(::reviewable).map { it.annotation.documentId to it.annotation.drawn.exactText }.toSet()

/**
 * The highlight among [rows] drawn on [passage]'s phrase, if any; a phrase has at most one, whose note holds everything
 * written about it. Overlapping but different phrases are different highlights.
 */
internal fun highlightOn(rows: List<AnnotationEntity>, passage: Passage): AnnotationEntity? = rows.firstOrNull {
    it.type == AnnotationType.HIGHLIGHT && phrase(it.drawn) == phrase(passage)
}

private data class Phrase(val blobSha: String, val blockId: String, val start: Int, val end: Int)

/**
 * A passage's span in its block and version, without leading and trailing whitespace, so a selection with or without
 * the space after a word is the same phrase. Kept outside [Passage], whose Java-serialized form is saved screen state.
 */
private fun phrase(passage: Passage) =
    with(passage) {
        Phrase(
            blobSha,
            blockId,
            startOffset + exactText.length - exactText.trimStart().length,
            endOffset - (exactText.length - exactText.trimEnd().length),
        )
    }
