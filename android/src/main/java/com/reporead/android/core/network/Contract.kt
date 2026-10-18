@file:kotlinx.serialization.UseSerializers(
    StrictBoolean::class,
    StrictInt::class,
    StrictLong::class,
    WireInstant::class,
)

package com.reporead.android.core.network

import com.reporead.android.data.AnchorStatus
import com.reporead.android.data.AnnotationType
import java.time.Instant
import kotlinx.serialization.Serializable

/** Library.Snapshot; the whole answer is decoded before any local replacement. */
@Serializable
data class Library(
    val repositories: List<Repository>,
    val readingStates: List<ReadingState>,
    val bookmarks: List<Bookmark>,
    val notebook: Notebook,
)

/** Library.Repository: RepositoryConnections.Connection is unwrapped on the wire. */
@Serializable
data class Repository(
    val id: Long,
    val fullName: String,
    val documentCount: Int,
    val lastSyncedCommitSha: String?,
    val documents: List<Document>,
)

/** Documents.Summary. */
@Serializable
data class Document(
    val id: Long,
    val path: String,
    val title: String,
    val blobSha: String,
    val contentChangedAt: Instant?,
    val deleted: Boolean,
)

/** Reviews.Notebook; the server, not the app, chooses the session ceiling. */
@Serializable
data class Notebook(val annotations: List<Annotation>, val reviews: List<Review>, val sessionLimit: Int) {
    init {
        require(sessionLimit > 0) { "Expected a positive notebook sessionLimit" }
    }
}

/** ReadingStates.Anchor, also used in ReadingController.SaveRequest. */
@Serializable
data class ReadingAnchor(val headingPath: List<String>, val textPrefix: String?, val blockIndex: Int) {
    init {
        require(blockIndex >= 0) { "Expected a non-negative reading blockIndex" }
    }
}

/** ReadingStates.State. */
@Serializable
data class ReadingState(
    val documentId: Long,
    val lastReadBlobSha: String,
    val progressPercent: Int,
    val anchor: ReadingAnchor,
    val lastReadAt: Instant,
) {
    init {
        require(progressPercent in 0..100) { "Expected reading progressPercent in 0..100" }
    }
}

/** Bookmarks.Bookmark. */
@Serializable data class Bookmark(val documentId: Long, val createdAt: Instant)

/** Annotations.Passage; location uses the same published shape via Annotations.Location's JsonValue. */
@Serializable
data class Anchor(
    val sourceBlobSha: String,
    val blockId: String,
    val startOffset: Int,
    val endOffset: Int,
    val exactText: String,
    val prefixText: String,
    val suffixText: String,
) {
    init {
        require(startOffset >= 0 && endOffset > startOffset && exactText.length == endOffset - startOffset) {
            "Expected a non-empty UTF-16 passage range matching exactText"
        }
    }
}

/** Annotations.Annotation; local pending state is deliberately not a wire field. */
@Serializable
data class Annotation(
    val id: Long,
    val mutationId: String,
    val documentId: Long,
    val anchor: Anchor,
    val location: Anchor,
    val note: String?,
    val version: Int,
    val createdAt: Instant,
    val status: AnchorStatus,
    val resolvedBlobSha: String,
    val type: AnnotationType,
    val question: String?,
    val checkedBlobSha: String?,
) {
    init {
        require(id > 0 && version > 0) { "Expected a positive annotation id and version for later mutations" }
        require(type != AnnotationType.CARD || !question.isNullOrBlank()) { "Expected a non-blank CARD question" }
    }
}

/** AnnotationController.AnnotationList. */
@Serializable data class AnnotationList(val annotations: List<Annotation>)

/** Reviews.Review. */
@Serializable
data class Review(
    val mutationId: String,
    val cardMutationId: String,
    val grade: Int,
    val reviewedAt: Instant,
    val blobSha: String,
) {
    init {
        require(grade in 0..5) { "Expected review grade in 0..5" }
    }
}

/** DocumentController.Content; snapshot identity prevents fetching images from an unrelated commit. */
@Serializable
data class NoteContent(
    val documentId: Long,
    val sourceBlobSha: String,
    val snapshotCommitSha: String,
    val path: String,
    val title: String,
    val html: String,
    val text: String,
    val renderFormat: Int,
) {
    init {
        require(snapshotCommitSha.matches(Regex("[0-9a-f]{40}"))) { "Expected a lowercase snapshot Git SHA-1" }
    }
}

/** DocumentController.ChangeStatus. */
@Serializable
enum class ChangeStatus {
    CHANGED,
    UNCHANGED,
    SINCE_UNAVAILABLE,
    TOO_LARGE,
}

/** NoteChanges.Change. */
@Serializable
enum class SectionChange {
    ADDED,
    CHANGED,
    REMOVED,
}

/** NoteChanges.Section; null blockId also represents the beginning of a note. */
@Serializable
data class ChangedSection(
    val change: SectionChange,
    val headingPath: List<String>,
    val blockId: String?,
    val addedLines: Int,
    val removedLines: Int,
)

/** DocumentController.Changes; identities are retained to match this answer to its request. */
@Serializable
data class Changes(
    val documentId: Long,
    val sinceBlobSha: String,
    val toBlobSha: String,
    val status: ChangeStatus,
    val reason: String?,
    val addedLines: Int,
    val removedLines: Int,
    val sections: List<ChangedSection>,
)

/** ConnectionData.Counts. */
@Serializable
data class StoredData(
    val documents: Int,
    val readingStates: Int,
    val bookmarks: Int,
    val highlights: Int,
    val cards: Int,
)

/** ConnectionData.Disconnected; only these document ids authorize removal of this phone's copies. */
@Serializable data class Disconnected(val documentIds: List<Long>)

/** AccountDeletion.Deleted; a JSON acknowledgement, not a no-content response. */
@Serializable
data class AccountDeleted(
    val repositories: Int,
    val readingStates: Int,
    val bookmarks: Int,
    val highlights: Int,
    val cards: Int,
)

/** RepositoryConnections.Available. */
@Serializable
data class Available(
    val githubRepositoryId: Long,
    val installationId: Long,
    val fullName: String,
    val privateRepository: Boolean,
    val connectionId: Long?,
)

/** RepositoryController.AvailableList. */
@Serializable data class AvailableList(val repositories: List<Available>)

/** RepositoryConnections.Connection (without the library's nested documents). */
@Serializable data class Connection(val id: Long, val githubRepositoryId: Long, val fullName: String)

/** RepositorySync.Result. */
@Serializable
data class RepositorySynced(
    val repositoryId: Long,
    val commitSha: String,
    val documentCount: Int,
    val syncedAt: Instant,
)

/** AppSessions.IssuedSession and AppUser; no GitHub credential is ever published. */
@Serializable
data class IssuedSession(val accessToken: String, val user: SessionUser) {
    init {
        require(accessToken.isNotBlank()) { "Expected a non-blank accessToken" }
    }
}

@Serializable
data class SessionUser(val id: Long) {
    init {
        require(id > 0) { "Expected a persisted account id" }
    }
}

/** ApiErrors.Body. Unknown codes remain text, not authority to reject an item. */
@Serializable
internal data class Failure(val code: String, val message: String) {
    init {
        require(code.isNotBlank()) { "Expected a non-blank failure code" }
    }
}

/** AppAuthController.TokenRequest. */
@Serializable data class TokenRequest(val code: String, val codeVerifier: String, val expectedUserId: Long?)

/** RepositoryController.ConnectRequest. */
@Serializable data class ConnectRequest(val installationId: Long)

/** ReadingController.SaveRequest. */
@Serializable
data class ReadingRequest(
    val lastReadBlobSha: String,
    val progressPercent: Int,
    val anchor: ReadingAnchor,
    val lastReadAt: Instant,
)

/** AnnotationService.Selection, shared by creation and reattachment. */
@Serializable
data class SelectionRequest(
    val sourceBlobSha: String,
    val blockId: String,
    val startOffset: Int,
    val endOffset: Int,
    val exactText: String,
)

/** AnnotationController.CreateRequest. */
@Serializable
data class CreateAnnotationRequest(
    val mutationId: String,
    val anchor: SelectionRequest,
    val note: String?,
    val type: AnnotationType,
    val question: String?,
)

/** AnnotationController.EditRequest. */
@Serializable data class EditAnnotationRequest(val note: String?, val expectedVersion: Int)

/** AnnotationController.ReattachRequest. */
@Serializable data class ReattachRequest(val expectedVersion: Int, val anchor: SelectionRequest)

/** AnnotationController.CheckRequest. */
@Serializable data class CheckRequest(val expectedVersion: Int, val blobSha: String)

/** ReviewController.GradeRequest. */
@Serializable
data class ReviewRequest(val mutationId: String, val grade: Int, val reviewedAt: Instant, val blobSha: String)
