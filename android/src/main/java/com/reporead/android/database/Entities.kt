// Owns: Room table entities and the value types stored inside them; not query projections or wire records.

package com.reporead.android.database

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.io.Serializable
import kotlinx.serialization.Serializable as KotlinSerializable

/** Owns the phone's connection/checkpoint projection; document metadata belongs to DocumentEntity. */
@Entity(tableName = "repositories")
data class RepositoryEntity(
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
data class DocumentEntity(
    @PrimaryKey val id: Long,
    val repositoryId: Long,
    val path: String,
    val title: String,
    val blobSha: String,
    val changedAt: Long? = null,
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
) : Serializable

/**
 * The latest complete rendered copy of an opened note, identified by document and source version. Its searchable
 * canonical blocks are [SearchBlockEntity] children. [renderFormat] is the server's page format for [html]; 0 for
 * copies saved before formats were recorded. [snapshotCommitSha] pins images and embed resolution; null means a legacy
 * page whose image identity is unknown, not permission to fetch current images.
 */
@Entity(tableName = "saved_pages")
data class SavedPageEntity(
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
    tableName = "search_blocks",
    primaryKeys = ["documentId", "blockId"],
    indices = [Index(value = ["documentId", "position"], unique = true)],
    foreignKeys =
        [
            ForeignKey(
                entity = SavedPageEntity::class,
                parentColumns = ["documentId"],
                childColumns = ["documentId"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
)
data class SearchBlockEntity(val documentId: Long, val blockId: String, val position: Int, val text: String)

/**
 * One /note-link in a saved page, written with the page ([RepoReadDao.savePage]) and deleted with it, so Linked from
 * reads these rows instead of every saved page.
 */
@Entity(
    tableName = "note_links",
    primaryKeys = ["documentId", "position"],
    foreignKeys =
        [
            ForeignKey(
                entity = SavedPageEntity::class,
                parentColumns = ["documentId"],
                childColumns = ["documentId"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
)
data class NoteLinkEntity(val documentId: Long, val position: Int, val query: String)

/** [pending] is a local save the backend has not acknowledged. Last write wins by [lastReadAt]. */
@Entity(tableName = "reading_states")
data class ReadingStateEntity(
    @PrimaryKey val documentId: Long,
    val lastReadBlobSha: String,
    val progressPercent: Int,
    val anchorJson: String,
    val lastReadAt: Long,
    val pending: Boolean,
)

/** A row with bookmarked=false is a pending removal; it is deleted once the backend acknowledges it. */
@Entity(tableName = "bookmarks")
data class BookmarkEntity(
    @PrimaryKey val documentId: Long,
    val bookmarked: Boolean,
    val pending: Boolean,
    val changedAt: Long,
)

/**
 * A passage in one source version of a note, as UTF-16 offsets into a block's canonical text. Java- and Kotlin-
 * serializable so saved reader state can keep one across activity recreation and process death.
 */
@KotlinSerializable
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
    /** The server refused the creation ([AnnotationEntity.rejection] says why): shown, never sent again. */
    REFUSED,
    /**
     * The user deleted it: hidden at once, and deleted on the server by the next sync, which first replays the creation
     * when the server has not acknowledged it (it may already have it).
     */
    DELETING,
    /** The server's copy. */
    ACKNOWLEDGED,
}

/** A highlight marks a passage; a card is an interview-bank question, anchored to its Canonical line. */
enum class AnnotationType {
    HIGHLIGHT,
    CARD,
}

/** What the server's location for a highlight means in the version it names ([AnnotationEntity.resolvedBlobSha]). */
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
data class AnnotationEntity(
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
) : Serializable {
    /** Where to draw it: the server's current location, or the original selection while the creation is pending. */
    val drawn: Passage
        get() = location ?: Passage(sourceBlobSha, blockId, startOffset, endOffset, exactText)

    /**
     * The server has its copy, so a note edit or a reattach can be sent (both are server operations). An unsent or
     * refused creation has nothing on the server to change yet. Every acknowledged row carries its server id.
     */
    val acknowledged: Boolean
        get() = state == AnnotationState.ACKNOWLEDGED

    /** The server could not find it reliably in [blobSha]. */
    fun orphanedIn(blobSha: String) = status == AnchorStatus.ORPHANED && resolvedBlobSha == blobSha

    /**
     * Why this card cannot be graded when its note is [deleted] from the repository or at [currentBlobSha], with
     * [savedBlobSha] saved; null when it can be, once Review finds its Canonical line among that saved bank's questions
     * ([CardBlock.NOT_ASKED]). The first reason that applies is the one to fix first, and Review offers one remedy per
     * reason.
     */
    fun cardBlock(deleted: Boolean, currentBlobSha: String, savedBlobSha: String?): CardBlock? =
        when {
            state == AnnotationState.REFUSED -> CardBlock.REFUSED
            deleted -> CardBlock.REMOVED
            savedBlobSha != currentBlobSha -> CardBlock.NOT_SAVED
            else -> null
        }
}

/** Pending grades and the server log share the same immutable client mutation id. */
@Entity(tableName = "review_log", indices = [Index("cardMutationId")])
data class ReviewEntity(
    @PrimaryKey val mutationId: String,
    val cardMutationId: String,
    val grade: Int,
    val reviewedAt: Long,
    val blobSha: String,
    val pending: Boolean,
    val rejection: String? = null,
)
