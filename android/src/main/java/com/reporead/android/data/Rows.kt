// Owns: Room entities and query projections, including local pending state; not HTTP parsing or reconciliation.

package com.reporead.android.data

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.io.Serializable

/** Owns the phone's connection/checkpoint projection; document metadata belongs to DocumentRow. */
@Entity(tableName = "repositories")
data class RepositoryRow(
    @PrimaryKey val id: Long,
    val fullName: String,
    val documentCount: Int,
    val lastSyncedCommitSha: String?,
)

/**
 * One note of a repository's complete server list, and the phone's one owner of its title, path, current version and
 * removal. [changedAt]: when a repository refresh found the note new or changed (epoch ms); null if RepoRead never saw
 * it change. [deleted]: the latest complete refresh did not have it; it stays here so the reading position, bookmark
 * and highlights kept on it are named, and the screens that list notes leave it out. Serializable so the reader can
 * keep a link's target across recreation.
 */
@Entity(tableName = "documents", indices = [Index("repositoryId")])
data class DocumentRow(
    @PrimaryKey val id: Long,
    val repositoryId: Long,
    val path: String,
    val title: String,
    val blobSha: String,
    val changedAt: Long? = null,
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
) : Serializable

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

/**
 * The latest complete rendered copy of an opened note, identified by document and source version. Its searchable
 * canonical blocks are [NoteSearchBlockRow] children. [renderFormat] is the server's page format for [html]; 0 for
 * copies saved before formats were recorded. [snapshotCommitSha] pins images and embed resolution; null means a legacy
 * page whose image identity is unknown, not permission to fetch current images.
 */
@Entity(tableName = "notes")
data class NoteRow(
    @PrimaryKey val documentId: Long,
    val blobSha: String,
    val path: String,
    val title: String,
    val html: String,
    val fetchedAt: Long,
    @ColumnInfo(defaultValue = "0") val renderFormat: Int = 0,
    val snapshotCommitSha: String? = null,
)

/** One backend-issued canonical block of a saved page, searchable without loading the page HTML. */
@Entity(
    tableName = "note_search_blocks",
    primaryKeys = ["documentId", "blockId"],
    indices = [Index(value = ["documentId", "position"], unique = true)],
    foreignKeys =
        [
            ForeignKey(
                entity = NoteRow::class,
                parentColumns = ["documentId"],
                childColumns = ["documentId"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
)
data class NoteSearchBlockRow(val documentId: Long, val blockId: String, val position: Int, val text: String)

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

/**
 * One /note-link in a saved page, written with the page ([LibraryDao.saveNote]) and deleted with it, so Linked from
 * reads these rows instead of every saved page.
 */
@Entity(
    tableName = "note_links",
    primaryKeys = ["documentId", "position"],
    foreignKeys =
        [
            ForeignKey(
                entity = NoteRow::class,
                parentColumns = ["documentId"],
                childColumns = ["documentId"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
)
data class NoteLinkRow(val documentId: Long, val position: Int, val query: String)

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
    val noteTruncated: Boolean,
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

/** [pending] is a local save the backend has not acknowledged. Last write wins by [lastReadAt]. */
@Entity(tableName = "reading_states")
data class ReadingRow(
    @PrimaryKey val documentId: Long,
    val lastReadBlobSha: String,
    val progressPercent: Int,
    val anchorJson: String,
    val lastReadAt: Long,
    val pending: Boolean,
)

/** A row with bookmarked=false is a pending removal; it is deleted once the backend acknowledges it. */
@Entity(tableName = "bookmarks")
data class BookmarkRow(
    @PrimaryKey val documentId: Long,
    val bookmarked: Boolean,
    val pending: Boolean,
    val changedAt: Long,
)

/**
 * A passage in one source version of a note, as UTF-16 offsets into a block's canonical text. Serializable so a screen
 * can keep one across activity recreation.
 */
data class Passage(
    val blobSha: String,
    val blockId: String,
    val startOffset: Int,
    val endOffset: Int,
    val exactText: String,
) : Serializable

/** Where a highlight or card stands with the server. */
enum class AnnotationState {
    /** Created on this phone; the next sync sends the creation. */
    UNSENT,
    /** The server refused the creation ([AnnotationRow.rejection] says why): shown, never sent again. */
    REFUSED,
    /**
     * The user deleted it: hidden at once, and deleted on the server by the next sync, which first replays the creation
     * when the server has not acknowledged it (it may already have it).
     */
    DELETING,
    /** The server's copy. */
    ACKNOWLEDGED,
}

/** A highlight marks a passage; a card also asks a question that the passage answers. */
enum class AnnotationType {
    HIGHLIGHT,
    CARD,
}

/** What the server's location for a highlight means in the version it names ([AnnotationRow.resolvedBlobSha]). */
enum class AnchorStatus {
    /** The original selection, in that version. */
    ANCHORED,
    /** Found again in that version, by the server or by the user. */
    REANCHORED,
    /** Not found reliably; shown with its original context until it is reattached. */
    ORPHANED,
}

/**
 * A highlight, keyed by the client mutation id that created it (the server returns it for every highlight). [state]
 * says where it stands with the server; the row is the local annotation and its unsent mutation in one write.
 * [rejection] is set only when [state] is REFUSED. The source/block/offset/exact fields and [prefixText]/[suffixText]
 * are the original selection. [location], [status] and [resolvedBlobSha] are the server's: where the highlight is now
 * and what that means for that version ([AnchorStatus]). A pending creation has none of them and is drawn where it was
 * made. Serializable so the reader can keep the one being edited, deleted or reattached across recreation.
 */
@Entity(tableName = "annotations", indices = [Index("documentId")])
data class AnnotationRow(
    @PrimaryKey val mutationId: String,
    val serverId: Long?,
    val documentId: Long,
    val sourceBlobSha: String,
    val blockId: String,
    val startOffset: Int,
    val endOffset: Int,
    val exactText: String,
    val note: String?,
    val version: Int,
    val createdAt: Long,
    @ColumnInfo(defaultValue = "'ACKNOWLEDGED'") val state: AnnotationState,
    val rejection: String? = null,
    @ColumnInfo(defaultValue = "") val prefixText: String = "",
    @ColumnInfo(defaultValue = "") val suffixText: String = "",
    val status: AnchorStatus? = null,
    val resolvedBlobSha: String? = null,
    @Embedded(prefix = "location") val location: Passage? = null,
    @ColumnInfo(defaultValue = "'HIGHLIGHT'") val type: AnnotationType = AnnotationType.HIGHLIGHT,
    val question: String? = null,
    val checkedBlobSha: String? = null,
) : Serializable {
    /** Where to draw it: the server's current location, or the original selection while the creation is pending. */
    val drawn: Passage
        get() = location ?: Passage(sourceBlobSha, blockId, startOffset, endOffset, exactText)

    /** The server could not find it reliably in [blobSha]. */
    fun orphanedIn(blobSha: String) = status == AnchorStatus.ORPHANED && resolvedBlobSha == blobSha

    /**
     * Why this card cannot be graded when its note is [deleted] from the repository or at [currentBlobSha], with
     * [savedBlobSha] saved; null when it can be. The phone's one rule (ADR-13), the server's Card.gradeableAt: the
     * first reason that applies is the one to fix first, and Review offers one remedy per reason.
     */
    fun cardBlock(deleted: Boolean, currentBlobSha: String, savedBlobSha: String?): CardBlock? =
        when {
            state == AnnotationState.REFUSED -> CardBlock.REFUSED
            deleted -> CardBlock.REMOVED
            status == AnchorStatus.ORPHANED -> CardBlock.ORPHANED
            savedBlobSha != currentBlobSha -> CardBlock.NOT_SAVED
            drawn.blobSha != currentBlobSha -> if (serverId == null) CardBlock.UNSENT else CardBlock.UNPLACED
            checkedBlobSha != currentBlobSha -> CardBlock.UNCONFIRMED
            else -> null
        }
}

/** Owns the first applicable phone-side review restriction, matching AnnotationRow.cardBlock's ordered decision. */
enum class CardBlock(val reason: String) {
    /** The server refused its creation, which is never sent again ([AnnotationRow.rejection] says why). */
    REFUSED("Refused by the server"),
    REMOVED("Note removed from the repository"),
    ORPHANED("Check this card: reattach its answer"),
    NOT_SAVED("Save the current note before reviewing"),
    /** Made in an older version and not sent yet, so the server has not placed it in the current one. */
    UNSENT("Not synced yet, so not found in the new version"),
    /** The server places a card in a new version only when its note's highlights are listed (ADR-06). */
    UNPLACED("Check this card: open its note to find the answer in the new version"),
    /** Placed in the current version (or reattached) and not confirmed there. */
    UNCONFIRMED("Check this card: confirm its answer"),
}

/** A new Practice grade writes both rows or neither; an already scheduled question is a successful no-op. */
enum class NewCardGrade {
    SAVED,
    ALREADY_SCHEDULED,
    BANK_CHANGED,
    NOT_SAVED,
}

/** Distinguishes a bad server grade at the write boundary from a database or programmer failure. */
internal class InvalidReviewGrade(mutationId: String, grade: Int) :
    IllegalArgumentException("Review $mutationId has grade $grade; expected 0..5")

/** Pending grades and the server log share the same immutable client mutation id. */
@Entity(tableName = "review_log", indices = [Index("cardMutationId")])
data class ReviewRow(
    @PrimaryKey val mutationId: String,
    val cardMutationId: String,
    val grade: Int,
    val reviewedAt: Long,
    val blobSha: String,
    val pending: Boolean,
    val rejection: String? = null,
)

/** The server supplies this ceiling; a phone without a complete notebook response cannot start a session. */
@Entity(tableName = "review_limits") data class ReviewLimitRow(@PrimaryKey val id: Int = 1, val sessionLimit: Int)

/** A highlight or card with its note's documents row: [deleted] from the repository, at [currentBlobSha]. */
data class NotebookItem(
    @Embedded val annotation: AnnotationRow,
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
