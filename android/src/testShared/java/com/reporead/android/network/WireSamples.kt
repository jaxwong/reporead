// Owns: Test-only answers built from the generated wire models, shared by the JVM and device tests.

package com.reporead.android.network

import com.reporead.android.BuildConfig
import com.reporead.android.network.wire.AccountDeletionDeleted
import com.reporead.android.network.wire.AnnotationsAnnotation
import com.reporead.android.network.wire.AnnotationsPassage
import com.reporead.android.network.wire.AppSessionsIssuedSession
import com.reporead.android.network.wire.AppUser
import com.reporead.android.network.wire.BookmarksBookmark
import com.reporead.android.network.wire.ConnectionDeletionCounts
import com.reporead.android.network.wire.ConnectionDeletionDisconnected
import com.reporead.android.network.wire.DocumentControllerContent
import com.reporead.android.network.wire.DocumentControllerSearchBlock
import com.reporead.android.network.wire.DocumentsSummary
import com.reporead.android.network.wire.LibraryRepository
import com.reporead.android.network.wire.LibrarySnapshot
import com.reporead.android.network.wire.ReadingStatesAnchor
import com.reporead.android.network.wire.ReadingStatesState
import com.reporead.android.network.wire.RepositoryConnectionsAvailable
import com.reporead.android.network.wire.RepositoryConnectionsConnection
import com.reporead.android.network.wire.RepositorySyncResult
import com.reporead.android.network.wire.ReviewsNotebook
import com.reporead.android.network.wire.ReviewsReview
import java.time.Instant
import kotlinx.serialization.encodeToString

/*
 * Every value here is test-only and labelled so. Built with the generated constructors, so a backend shape change fails
 * these tests' compilation (which CI runs for the device tests too) instead of a run on the phone.
 */

internal val testSha = "a".repeat(40)
internal val testCommit = "b".repeat(40)
internal val testAt: Instant = Instant.parse("2000-01-01T00:00:00Z")
internal const val TEST_HIGHLIGHT_MUTATION = "00000000-0000-0000-0000-000000000001"
internal const val TEST_REVIEW_MUTATION = "00000000-0000-0000-0000-000000000002"
internal const val TEST_CARD_MUTATION = "00000000-0000-0000-0000-000000000003"

/** A test-only answer as the backend sends it. */
internal inline fun <reified T> wire(value: T): String = wireJson.encodeToString(value)

internal fun testPassage() =
    AnnotationsPassage(
        blockId = "b1",
        endOffset = 4,
        exactText = "text",
        headingPath = emptyList(),
        prefixText = "",
        sourceBlobSha = testSha,
        startOffset = 0,
        suffixText = "",
    )

/** Highlight 9 on note 1; a card is the same passage with a question ([testCard]). */
internal fun testHighlight() =
    AnnotationsAnnotation(
        anchor = testPassage(),
        createdAt = testAt,
        documentId = 1,
        id = 9,
        location = testPassage(),
        mutationId = TEST_HIGHLIGHT_MUTATION,
        note = null,
        question = null,
        resolvedBlobSha = testSha,
        status = AnnotationsAnnotation.Status.ANCHORED,
        type = AnnotationsAnnotation.Type.HIGHLIGHT,
        updatedAt = testAt,
        version = 1,
    )

internal fun testCard() =
    testHighlight()
        .copy(
            id = 10,
            mutationId = TEST_CARD_MUTATION,
            type = AnnotationsAnnotation.Type.CARD,
            question = "Test-only question?",
        )

internal fun testReview() =
    ReviewsReview(
        blobSha = testSha,
        cardMutationId = TEST_CARD_MUTATION,
        grade = 4,
        mutationId = TEST_REVIEW_MUTATION,
        reviewedAt = testAt,
    )

internal fun testReading() =
    ReadingStatesState(
        anchor = ReadingStatesAnchor(blockIndex = 0, headingPath = emptyList(), textPrefix = null),
        documentId = 1,
        lastReadAt = testAt,
        lastReadBlobSha = testSha,
        progressPercent = 50,
    )

internal fun testBookmark() = BookmarksBookmark(createdAt = testAt, documentId = 1)

internal fun testDocument() =
    DocumentsSummary(
        blobSha = testSha,
        contentChangedAt = null,
        deleted = false,
        id = 1,
        path = "n.md",
        title = "Test-only note",
    )

/** Connection 7 to GitHub repository 11, never synced. */
internal fun testConnection() =
    RepositoryConnectionsConnection(
        defaultBranch = "main",
        documentCount = 1,
        fullName = "test-only/notes",
        githubRepositoryId = 11,
        id = 7,
        lastSyncedCommitSha = null,
        name = "notes",
        owner = "test-only",
    )

/** [testConnection] as the library lists it, with [documents]. */
internal fun testRepository(documents: List<DocumentsSummary> = listOf(testDocument())) =
    testConnection().let {
        LibraryRepository(
            defaultBranch = it.defaultBranch,
            documentCount = it.documentCount,
            documents = documents,
            fullName = it.fullName,
            githubRepositoryId = it.githubRepositoryId,
            id = it.id,
            lastSyncedCommitSha = it.lastSyncedCommitSha,
            name = it.name,
            owner = it.owner,
        )
    }

internal fun testLibrary(
    repositories: List<LibraryRepository> = listOf(testRepository()),
    readingStates: List<ReadingStatesState> = listOf(testReading()),
    bookmarks: List<BookmarksBookmark> = listOf(testBookmark()),
    annotations: List<AnnotationsAnnotation> = listOf(testHighlight(), testCard()),
    reviews: List<ReviewsReview> = listOf(testReview()),
) =
    LibrarySnapshot(
        bookmarks = bookmarks,
        notebook = ReviewsNotebook(annotations = annotations, reviews = reviews),
        readingStates = readingStates,
        repositories = repositories,
    )

internal fun testContent() =
    DocumentControllerContent(
        blocks = listOf(DocumentControllerSearchBlock(id = "b0", text = "text")),
        documentId = 1,
        html = "<main id=\"note\"><p>text</p></main>",
        path = "n.md",
        renderFormat = BuildConfig.RENDER_FORMAT,
        snapshotCommitSha = testCommit,
        sourceBlobSha = testSha,
        title = "Test-only note",
    )

internal fun testAvailable() =
    RepositoryConnectionsAvailable(
        connectionId = null,
        defaultBranch = "main",
        fullName = "test-only/notes",
        githubRepositoryId = 11,
        installationId = 7,
        privateRepository = true,
    )

internal fun testSynced() =
    RepositorySyncResult(commitSha = testCommit, documentCount = 1, repositoryId = 7, syncedAt = testAt)

internal fun testCounts() =
    ConnectionDeletionCounts(bookmarks = 1, cards = 1, documents = 1, highlights = 1, readingStates = 1)

internal fun testDisconnected(documentIds: List<Long> = listOf(1)) =
    ConnectionDeletionDisconnected(
        bookmarks = 1,
        cards = 1,
        documentIds = documentIds,
        highlights = 1,
        readingStates = 1,
    )

internal fun testAccountDeleted() =
    AccountDeletionDeleted(bookmarks = 1, cards = 1, highlights = 1, readingStates = 1, repositories = 1)

internal fun testSession(accessToken: String = "TEST_ONLY_SESSION", userId: Long = 1) =
    AppSessionsIssuedSession(
        accessToken = accessToken,
        expiresAt = testAt,
        user = AppUser(githubUserId = 42, id = userId, login = "test-only"),
    )
