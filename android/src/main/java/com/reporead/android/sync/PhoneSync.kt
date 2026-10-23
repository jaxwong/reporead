// Owns: Server/local-copy synchronization, pending-change disposition and ordered publication under the existing lock.

package com.reporead.android.sync

import com.reporead.android.BuildConfig
import com.reporead.android.database.AnnotationEntity
import com.reporead.android.database.AnnotationState
import com.reporead.android.database.AnnotationType
import com.reporead.android.database.BookmarkEntity
import com.reporead.android.database.DocumentEntity
import com.reporead.android.database.NewCardGrade
import com.reporead.android.database.NewHighlight
import com.reporead.android.database.NoteSearchBlockEntity
import com.reporead.android.database.Passage
import com.reporead.android.database.ReadingStateEntity
import com.reporead.android.database.RepoReadDatabase
import com.reporead.android.database.RepositoryEntity
import com.reporead.android.database.ReviewEntity
import com.reporead.android.database.SavedPageEntity
import com.reporead.android.logging.DeviceLog
import com.reporead.android.network.Api
import com.reporead.android.network.ApiException
import com.reporead.android.network.describe
import com.reporead.android.network.invalidResponse
import com.reporead.android.network.wire.AnnotationControllerCreateRequest
import com.reporead.android.network.wire.AnnotationControllerEditRequest
import com.reporead.android.network.wire.AnnotationControllerReattachRequest
import com.reporead.android.network.wire.ConnectionDeletionCounts
import com.reporead.android.network.wire.ReadingControllerAnchorRequest
import com.reporead.android.network.wire.ReadingControllerSaveRequest
import com.reporead.android.network.wire.RepositoryConnectionsConnection
import com.reporead.android.network.wire.RepositoryControllerConnectRequest
import com.reporead.android.network.wire.ReviewControllerGradeRequest
import com.reporead.android.network.wireJson
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The server answered and refused this one request for a reason resending it cannot change (invalid, gone, a
 * conflicting edit): the item is marked or dropped and the run goes on. Any other failure — unreachable, signed out,
 * access denied, GitHub or the server failing, a renamed repository that only connecting it again fixes, or an answer
 * that is not RepoRead's — says nothing about the item, which stays pending.
 */
internal fun ApiException.refusesItem(): Boolean =
    when (code) {
        "INVALID_REQUEST",
        "INVALID_ANNOTATION",
        "INVALID_REVIEW" -> status == 400
        "NOT_FOUND",
        "SOURCE_NOT_FOUND" -> status == 404
        "ANNOTATION_CONFLICT",
        "MUTATION_ID_REUSED" -> status == 409
        "ANNOTATION_DELETED",
        "DOCUMENT_DELETED" -> status == 410
        "INVALID_ANCHOR",
        "UNSUPPORTED_CONTENT" -> status == 422
        else -> false
    }

/**
 * What one pending-change send could not do. [refused]: bookmark changes the server refused and the phone undid.
 * [notSent]: why pending highlights and cards were not sent this time; they wait for the next send.
 */
data class PendingChangesResult(val refused: List<RefusedChange>, val notSent: ApiException?)

/** The title is the document owner's snapshot at refusal, before a subsequent library replacement can remove it. */
data class RefusedChange(val documentId: Long, val title: String?, val error: ApiException)

/**
 * Owns how the offline cache and the backend meet. A failed call ends that operation and leaves the previous complete
 * cache untouched. Pending-change delivery is distinct from [refreshRepositoryFromGitHub], which asks the backend to
 * reconcile a repository snapshot. Nothing here retries; every local change calls [requestPendingChangeSend], which
 * asks WorkManager for a background pending-change send (PendingSyncWorker.kt, ADR-08).
 */
class PhoneSync(
    private val api: Api,
    store: RepoReadDatabase,
    filesDir: File,
    private val requestPendingChangeSend: () -> Unit,
) {
    private val dao = store.dao()

    private val onePhoneOperationAtATime = Mutex()

    /** Orders local writes and server answers for the process's one local copy. */
    private suspend fun <T> locked(work: suspend () -> T): T = onePhoneOperationAtATime.withLock {
        withContext(Dispatchers.IO) { work() }
    }

    /**
     * [locked], but its caller's cancellation does not stop it, from waiting for the lock to its local commit: a screen
     * left while a local write waits, or while the server confirms a change, does not lose that change here. Only for
     * one local write, or one mutation and its commit (at most two calls, each within Api's timeouts); read-only loads
     * and the pending-change passes stay cancellable.
     */
    private suspend fun <T> committed(work: suspend () -> T): T = withContext(NonCancellable) { locked(work) }

    /** Saved notes' images; deleted here whenever their notes go. */
    val images = NoteImages(api, filesDir)

    /** The [paths] a deletion [after] something left, logged; a later sweep deletes them again. */
    private fun logged(after: String, paths: List<File>): List<File> {
        if (paths.isNotEmpty()) DeviceLog.warn("Files left on the phone; after=$after count=${paths.size} paths=$paths")
        return paths
    }

    /**
     * Deletes the images of every version Room holds no page of: forgotten notes' and replaced versions'. Derived from
     * Room each time, so nothing remembers what to delete. Run wherever a saved page may have gone or been replaced:
     * after a page is saved, after the server's lists replace the saved ones, after a disconnect and at start.
     */
    private suspend fun sweepImages(): List<File> = logged("sweeping images", images.sweep(dao.allSavedVersions()))

    /**
     * Loads the server's whole library in one request, no GitHub call (GET /api/library): the repositories with every
     * note, which name every note the other rows refer to, the reading states, the bookmarks, and the notebook, all
     * read from one database snapshot (ADR-08). It replaces the acknowledged rows, never pending work, in one Room
     * transaction; a failed request or an answer that breaks the contract writes nothing. After the commit it sweeps
     * the images of the notes it forgot — in a repository no longer listed (disconnected on another device, or here
     * with the answer lost), or referred to by nothing ([sweepImages]).
     */
    private suspend fun loadLibrary() {
        val snapshot = api.fetchLibrarySnapshot()
        val repositoriesWithDocuments =
            snapshot.repositories.associate { repository ->
                RepositoryEntity(
                    repository.id,
                    repository.fullName,
                    repository.documentCount,
                    repository.lastSyncedCommitSha,
                ) to
                    repository.documents.map { document ->
                        DocumentEntity(
                            document.id,
                            repository.id,
                            document.path,
                            document.title,
                            document.blobSha,
                            document.contentChangedAt?.toEpochMilli(),
                            document.deleted,
                        )
                    }
            }
        val readingStates = snapshot.readingStates.map { it.toEntity() }
        val bookmarks =
            snapshot.bookmarks.map { BookmarkEntity(it.documentId, true, false, it.createdAt.toEpochMilli()) }
        val annotations = snapshot.notebook.annotations.map { it.toEntity() }
        val reviews = snapshot.notebook.reviews.map { it.toEntity() }
        dao.replaceFromServer(repositoriesWithDocuments, readingStates, bookmarks, annotations, reviews)
        sweepImages()
    }

    /** Reloads the server's whole library (one request, no GitHub call); pending changes wait for a send. */
    suspend fun refreshLibrary() = locked { loadLibrary() }

    /** Asks the backend to reconcile with GitHub, then reloads the now-current library. */
    suspend fun refreshRepositoryFromGitHub(repositoryId: Long) = committed {
        api.refreshRepositorySnapshot(repositoryId)
        loadLibrary()
    }

    /**
     * Connects [githubRepositoryId] through its GitHub App [installationId], then reloads the library, so the
     * connection is saved on this phone before it is reported. Connecting a connected repository again keeps its data
     * and takes GitHub's current name and default branch: the way back after either is renamed on GitHub.
     */
    suspend fun connect(githubRepositoryId: Long, installationId: Long): RepositoryConnectionsConnection = committed {
        api.connectRepository(githubRepositoryId, RepositoryControllerConnectRequest(installationId = installationId))
            .also { loadLibrary() }
    }

    /** Owns an open operation's saved page and optional fetch failure; the reader words the stale-copy notice. */
    data class Opened(val note: SavedPageEntity, val staleReason: ApiException?)

    /**
     * The saved copy matches a listed note's blob and path. Only repository images and blocked embeds also need the
     * same known snapshot: their contents or resolution may change without a Markdown edit.
     */
    private fun sameVersion(
        savedPage: SavedPageEntity,
        listedDocument: DocumentEntity?,
        repositoryCommitSha: String?,
    ): Boolean {
        val needsSnapshot = savedPage.html.contains(IMAGE_PREFIX) || savedPage.html.contains("image-blocked")
        return listedDocument != null &&
            !listedDocument.deleted &&
            savedPage.blobSha == listedDocument.blobSha &&
            savedPage.path == listedDocument.path &&
            (!needsSnapshot ||
                (savedPage.snapshotCommitSha != null && savedPage.snapshotCommitSha == repositoryCommitSha))
    }

    /** The saved copy can be shown without a network call: the current version, in the page format this app needs. */
    private fun upToDate(savedPage: SavedPageEntity, listedDocument: DocumentEntity?, repositoryCommitSha: String?) =
        sameVersion(savedPage, listedDocument, repositoryCommitSha) &&
            savedPage.renderFormat >= BuildConfig.RENDER_FORMAT

    /**
     * An [upToDate] cached copy opens without a network call. Otherwise the current version is fetched; if that fails,
     * an older cached copy is shown with the reason, and a missing cache is the failure itself. A current copy saved in
     * an older page format (without search blocks, note links, embeds, or later presentation fixes) is fetched once
     * more; if that fails it is still the current version, so it opens without a warning.
     */
    suspend fun openNote(documentId: Long): Opened = locked {
        val savedPage = dao.note(documentId)
        val listedDocument = dao.document(documentId)
        val repositoryCommitSha = listedDocument?.let { dao.repositoryCommitSha(it.repositoryId) }
        if (savedPage != null && upToDate(savedPage, listedDocument, repositoryCommitSha))
            return@locked Opened(savedPage, null)
        try {
            Opened(fetchNote(documentId).also { sweepImages() }, null)
        } catch (failure: ApiException) {
            if (savedPage == null) throw failure
            val savedPageIsCurrent = sameVersion(savedPage, listedDocument, repositoryCommitSha)
            DeviceLog.info(
                "Showing cached note; documentId=$documentId code=${failure.code} current=$savedPageIsCurrent"
            )
            Opened(savedPage, if (savedPageIsCurrent) null else failure)
        }
    }

    /**
     * Fetches and saves the note's current version (one GitHub call on the server); the copy is saved whole or not at
     * all.
     */
    private suspend fun fetchNote(documentId: Long): SavedPageEntity {
        val content = api.fetchDocumentContent(documentId)
        val savedPage =
            SavedPageEntity(
                documentId,
                content.sourceBlobSha,
                content.path,
                content.title,
                content.html,
                System.currentTimeMillis(),
                content.renderFormat,
                content.snapshotCommitSha,
            )
        val searchBlocks =
            content.blocks.mapIndexed { position, block ->
                NoteSearchBlockEntity(documentId, block.id, position, block.text)
            }
        dao.saveNote(savedPage, searchBlocks)
        return savedPage
    }

    /**
     * [fetched] new or updated copies whose image pass completed; [alreadySaved] were current and completed it too;
     * [cannotShow] pages skipped on a permanent content/image refusal, counted once and logged by id. Saved text stays.
     */
    data class SaveAllResult(val fetched: Int, val alreadySaved: Int, val cannotShow: Int)

    /**
     * Saves every note of [repositoryId] on the phone: reloads the library from the server ([refreshLibrary]), then
     * fetches each note whose saved copy is not [upToDate], one at a time. A permanent content/image refusal skips the
     * rest of that page and counts it once; any other API failure ends the run for the worker to retry or report. Each
     * page, including an already-current copy, saves its missing images before reporting progress. Saved text and
     * complete images survive failures and cancellation; another run checks the rest. At most one library call, one
     * content call per outdated note and 64 image calls per saved page (the server's reference limit). Run only by Save
     * all's background work (SaveAllNotesWorker.kt).
     */
    suspend fun saveAllNotes(
        repositoryId: Long,
        onProgress: suspend (done: Int, totalNotes: Int) -> Unit,
    ): SaveAllResult {
        refreshLibrary()
        val listedDocuments = dao.listedDocuments(repositoryId)
        val repositoryCommitSha = dao.repositoryCommitSha(repositoryId)
        DeviceLog.info("Saving all notes; repositoryId=$repositoryId documents=${listedDocuments.size}")
        var fetched = 0
        var alreadySaved = 0
        var cannotShow = 0
        onProgress(0, listedDocuments.size)
        listedDocuments.forEachIndexed { index, document ->
            try {
                locked {
                    val savedPage = dao.note(document.id)
                    if (savedPage != null && upToDate(savedPage, document, repositoryCommitSha)) {
                        images.save(savedPage)
                        alreadySaved++
                    } else {
                        images.save(fetchNote(document.id))
                        fetched++
                    }
                }
            } catch (failure: ApiException) {
                // Unlike refusesItem(), this classifies page failures after a fresh library load, not mutations.
                val canSkipPage =
                    when (failure.code) {
                        "UNSUPPORTED_CONTENT" -> failure.status == 422
                        "IMAGE_NOT_FOUND" -> failure.status == 404
                        "INVALID_IMAGE_PATH" -> failure.status == 400
                        else -> false
                    }
                if (!canSkipPage) {
                    DeviceLog.warn(
                        "Saving all notes stopped; repositoryId=$repositoryId fetched=$fetched documentId=${document.id} code=${failure.code}"
                    )
                    throw failure
                }
                DeviceLog.info("Page skipped; documentId=${document.id} code=${failure.code}")
                cannotShow++
            }
            onProgress(index + 1, listedDocuments.size)
        }
        locked { sweepImages() }
        DeviceLog.info(
            "Saved all notes; repositoryId=$repositoryId fetched=$fetched alreadySaved=$alreadySaved cannotShow=$cannotShow"
        )
        return SaveAllResult(fetched, alreadySaved, cannotShow)
    }

    /**
     * What disconnecting would delete: [stored] on the server, counted after sending what can be sent, and [unsent]
     * highlights, cards, and grades only on this phone (unsent or refused), deleted too; [notSent] says why some were
     * not sent.
     */
    data class DisconnectPreview(val stored: ConnectionDeletionCounts, val unsent: Int, val notSent: ApiException?)

    /**
     * Sends pending work, then counts what disconnecting [repositoryId] would delete, for the confirmation. Null when
     * the server no longer has the repository (an earlier disconnect whose answer was lost, or another device): this
     * phone's copies are then removed by reloading the server's library.
     */
    suspend fun disconnectPreview(repositoryId: Long): DisconnectPreview? = locked {
        val pendingChanges = sendPending().also { loadLibrary() }
        val deletionCounts =
            try {
                api.repositoryDeletionCounts(repositoryId)
            } catch (failure: ApiException) {
                if (failure.status != 404 || failure.code != "NOT_FOUND") throw failure
                DeviceLog.info("Repository already disconnected on the server; repositoryId=$repositoryId")
                loadLibrary()
                return@locked null
            }
        DisconnectPreview(deletionCounts, dao.unsentInRepository(repositoryId), pendingChanges.notSent)
    }

    /**
     * Online only: the server deletes the connection and everything stored for it, then this phone deletes its copies,
     * including unsynced changes for those notes. A failed request changes nothing on the phone, but the server may
     * already have deleted (its answer lost or unreadable). When the server no longer has the repository, disconnecting
     * again reloads the server's library instead. Either way the forgotten notes' images are deleted; files that stay
     * are logged and deleted again by the next sweep.
     */
    suspend fun disconnect(repositoryId: Long): Unit = committed {
        val disconnectResponse =
            try {
                api.disconnectRepository(repositoryId)
            } catch (failure: ApiException) {
                if (failure.status != 404 || failure.code != "NOT_FOUND") throw failure
                null
            }
        if (disconnectResponse == null) {
            DeviceLog.info("Repository already disconnected on the server; repositoryId=$repositoryId")
            loadLibrary()
        } else {
            val documentIds = disconnectResponse.documentIds
            dao.forgetRepository(repositoryId, documentIds)
            DeviceLog.info("Repository disconnected; repositoryId=$repositoryId documents=${documentIds.size}")
            sweepImages()
        }
    }

    /** Only the reader calls this, with the version it actually displayed. False for a disconnected note. */
    suspend fun saveReading(readingState: ReadingStateEntity): Boolean = committed {
        dao.saveDisplayedReading(readingState.copy(pending = true)).also { wasSaved ->
            if (wasSaved) requestPendingChangeSend()
            else DeviceLog.info("Reading position not saved; note gone; documentId=${readingState.documentId}")
        }
    }

    /** False when the phone no longer lists the note: nothing is saved. */
    suspend fun setBookmark(documentId: Long, bookmarked: Boolean): Boolean = committed {
        dao.saveLocalBookmark(BookmarkEntity(documentId, bookmarked, pending = true, System.currentTimeMillis()))
            .also { wasSaved ->
                if (wasSaved) requestPendingChangeSend()
                else DeviceLog.info("Bookmark not saved; note gone; documentId=$documentId")
            }
    }

    /**
     * Saves a grade as a pending local mutation; false when its card changed meanwhile and nothing was saved (see
     * recordReview).
     */
    suspend fun recordReview(review: ReviewEntity): Boolean = committed {
        dao.recordReview(review).also { if (it) requestPendingChangeSend() }
    }

    /**
     * The Library's send-and-refresh operation: sends what waits ([sendPending]), then loads the server's library
     * ([loadLibrary]).
     */
    suspend fun sendPendingChangesAndRefreshLibrary(): PendingChangesResult = locked {
        sendPending().also { loadLibrary() }
    }

    /**
     * The background sender: sends what waits, and nothing when nothing does (null); each send saves the server's
     * answer, so the lists are left to the screens.
     */
    suspend fun sendPendingChanges(): PendingChangesResult? = locked {
        if (dao.unsentChangeCount().first() == 0 && dao.pendingReading().isEmpty()) null else sendPending()
    }

    /**
     * Sends what waits, each saved as the server answers it. Highlight and card creations need GitHub; when GitHub, or
     * access to it, fails they wait, and the database-only work still runs (ADR-03). A refused item is marked and the
     * run goes on; any other failure ends the run with unacknowledged changes still pending.
     */
    private suspend fun sendPending(): PendingChangesResult {
        val annotationSendFailure =
            try {
                sendAnnotations()
                null
            } catch (failure: ApiException) {
                if (failure.unreachable) throw failure
                failure
            }
        sendReviews()
        val refusedBookmarks = mutableListOf<RefusedChange>()
        for (readingState in dao.pendingReading()) {
            val request =
                ReadingControllerSaveRequest(
                    lastReadBlobSha = readingState.lastReadBlobSha,
                    progressPercent = readingState.progressPercent,
                    anchor = wireJson.decodeFromString<ReadingControllerAnchorRequest>(readingState.anchorJson),
                    lastReadAt = Instant.ofEpochMilli(readingState.lastReadAt),
                )
            val remoteReadingState =
                try {
                    api.saveReading(readingState.documentId, request).toEntity()
                } catch (failure: ApiException) {
                    if (failure.status != 404 || failure.code != "NOT_FOUND") throw failure
                    DeviceLog.info("Reading state no longer owned; documentId=${readingState.documentId}")
                    // Not this user's note on the server any more: the position goes with it when the lists are
                    // next replaced. Malformed saves are programmer errors, not item refusals.
                    dao.acknowledgeReading(readingState.documentId, readingState.lastReadAt)
                    continue
                }
            dao.acknowledgeReading(readingState.documentId, readingState.lastReadAt)
            dao.mergeRemoteReading(remoteReadingState)
        }
        for (bookmark in dao.pendingBookmarks()) {
            try {
                if (bookmark.bookmarked) {
                    api.setBookmark(bookmark.documentId)
                    dao.acknowledgeBookmarkSet(bookmark.documentId, bookmark.changedAt)
                } else {
                    api.clearBookmark(bookmark.documentId)
                    dao.acknowledgeBookmarkCleared(bookmark.documentId, bookmark.changedAt)
                }
            } catch (failure: ApiException) {
                if (!failure.refusesItem()) throw failure
                DeviceLog.warn("Bookmark refused; documentId=${bookmark.documentId} code=${failure.code}")
                dao.dropBookmark(bookmark.documentId, bookmark.changedAt)
                refusedBookmarks +=
                    RefusedChange(
                        bookmark.documentId,
                        dao.document(bookmark.documentId)?.title,
                        failure,
                    )
            }
        }
        return PendingChangesResult(refusedBookmarks, annotationSendFailure)
    }

    /**
     * Saves a new highlight locally as a pending creation; it is sent with the saved mutation id until acknowledged.
     * Nothing is saved on a page disconnected meanwhile or on a phrase already highlighted. Cards are made only by
     * Practice ([gradeNewCard]).
     */
    suspend fun createAnnotation(passage: Passage, documentId: Long, note: String?): NewHighlight = committed {
        val saved = dao.saveLocalAnnotation(newAnnotation(passage, documentId, note, null))
        when (saved) {
            is NewHighlight.Saved -> requestPendingChangeSend()
            is NewHighlight.AlreadyHighlighted ->
                DeviceLog.info(
                    "Highlight not saved; phrase already highlighted; documentId=$documentId existing=${saved.existing.mutationId}"
                )
            NewHighlight.PageGone -> DeviceLog.info("Highlight not saved; page gone; documentId=$documentId")
        }
        saved
    }

    /**
     * Saves a Practice question's card and first grade through the DAO transaction, with no network calls. Identity is
     * the bank and Canonical line, not the prompt. A restored or concurrent submission writes nothing if its card is
     * already on the schedule. Room commits the pair atomically; an insert failure propagates with neither row saved,
     * rather than falling back to a card without a grade.
     */
    suspend fun gradeNewCard(passage: Passage, documentId: Long, question: String, grade: Int): NewCardGrade =
        committed {
            val card = newAnnotation(passage, documentId, null, question)
            val review =
                ReviewEntity(
                    mutationId = UUID.randomUUID().toString(),
                    cardMutationId = card.mutationId,
                    grade = grade,
                    reviewedAt = System.currentTimeMillis(),
                    blobSha = passage.blobSha,
                    pending = true,
                )
            val saveResult = dao.saveCardWithFirstReview(card, review)
            if (saveResult == NewCardGrade.SAVED) requestPendingChangeSend()
            DeviceLog.info("Practice grade; documentId=$documentId mutationId=${card.mutationId} outcome=$saveResult")
            saveResult
        }

    private fun newAnnotation(
        passage: Passage,
        documentId: Long,
        note: String?,
        question: String?,
    ): AnnotationEntity =
        AnnotationEntity(
            mutationId = UUID.randomUUID().toString(),
            serverId = null,
            documentId = documentId,
            sourceBlobSha = passage.blobSha,
            blockId = passage.blockId,
            startOffset = passage.startOffset,
            endOffset = passage.endOffset,
            exactText = passage.exactText,
            note = note,
            version = 1,
            createdAt = System.currentTimeMillis(),
            state = AnnotationState.UNSENT,
            type = if (question == null) AnnotationType.HIGHLIGHT else AnnotationType.CARD,
            question = question,
        )

    /**
     * Sends pending creations; a failure that is not one item's refusal stops it, and the rest wait, unsent, for the
     * next send. The same mutation id is replayed until the server answers, so a lost acknowledgement cannot create a
     * duplicate. A refusal is kept on the row and shown; it is not retried.
     */
    suspend fun pushAnnotations() = locked { sendAnnotations() }

    private suspend fun sendAnnotations() {
        for (annotation in dao.waitingAnnotations()) {
            val isDeleting = annotation.state == AnnotationState.DELETING
            check(annotation.serverId == null || isDeleting) { "Unsent ${annotation.mutationId} has a server id" }
            // A deletion whose creation the server has not acknowledged replays the creation first, to learn its id.
            val serverBackedAnnotation =
                if (annotation.serverId != null) annotation else sendCreation(annotation, isDeleting) ?: continue
            if (isDeleting) sendDeletion(serverBackedAnnotation)
        }
    }

    /**
     * Sends [annotation]'s creation and saves and returns the server's copy. Null when the server refused this one
     * item: it is then removed (deleted already, or being [isDeleting]) or kept as refused. Any other failure stops the
     * run.
     */
    private suspend fun sendCreation(annotation: AnnotationEntity, isDeleting: Boolean): AnnotationEntity? {
        val request =
            AnnotationControllerCreateRequest(
                mutationId = annotation.mutationId,
                anchor =
                    Passage(
                            annotation.sourceBlobSha,
                            annotation.blockId,
                            annotation.startOffset,
                            annotation.endOffset,
                            annotation.exactText,
                        )
                        .anchor(),
                note = annotation.note,
                type = annotation.type.name,
                question = annotation.question,
            )
        val serverAnnotation =
            try {
                api.createAnnotation(annotation.documentId, request).toEntity()
            } catch (failure: ApiException) {
                when {
                    (failure.status == 410 && failure.code == "ANNOTATION_DELETED") ||
                        (isDeleting && failure.refusesItem()) -> dao.removeAnnotation(annotation.mutationId)
                    failure.refusesItem() -> dao.rejectAnnotation(annotation.mutationId, failure.describe())
                    else -> {
                        DeviceLog.warn(
                            "Highlight creations stopped; documentId=${annotation.documentId} code=${failure.code}"
                        )
                        throw failure
                    }
                }
                DeviceLog.warn(
                    "Highlight creation refused; documentId=${annotation.documentId} code=${failure.code} " +
                        "deleting=$isDeleting"
                )
                return null
            }
        return serverAnnotation.also { dao.saveServerAnnotation(it) }
    }

    /**
     * Deletes on the server a creation the user deleted before it was acknowledged; throws a failure that is not this
     * item's.
     */
    private suspend fun sendDeletion(annotation: AnnotationEntity) {
        val annotationId = checkNotNull(annotation.serverId) { "Deleting ${annotation.mutationId} needs its server id" }
        try {
            api.deleteAnnotation(annotationId, annotation.version)
        } catch (failure: ApiException) {
            if (!failure.refusesItem()) {
                DeviceLog.warn("Highlight deletions stopped; annotationId=$annotationId code=${failure.code}")
                throw failure
            }
            if (failure.code != "NOT_FOUND") {
                // Changed elsewhere first (409): shown again rather than deleted unseen.
                DeviceLog.warn("Highlight deletion refused; annotationId=$annotationId code=${failure.code}")
                dao.saveAnnotation(annotation.copy(state = AnnotationState.ACKNOWLEDGED))
                return
            }
        }
        dao.removeAnnotation(annotation.mutationId)
        DeviceLog.info("Highlight deleted after its creation was acknowledged; annotationId=$annotationId")
    }

    /** Pending creations are sent first, so the server's list already contains them where possible. */
    suspend fun refreshAnnotations(documentId: Long) = locked {
        sendAnnotations()
        val annotations = api.annotations(documentId).annotations.map { it.toEntity() }
        dao.replaceRemoteAnnotations(documentId, annotations)
    }

    /** Online only. A 409 ANNOTATION_CONFLICT means another edit landed first; nothing local changes. */
    suspend fun editAnnotation(annotation: AnnotationEntity, note: String?, expectedVersion: Int): AnnotationEntity =
        committed {
            val annotationId =
                checkNotNull(annotation.serverId) {
                    "Only acknowledged highlights can be edited; ${annotation.mutationId} is pending"
                }
            val updated =
                api.editAnnotation(
                        annotationId,
                        AnnotationControllerEditRequest(note = note, expectedVersion = expectedVersion),
                    )
                    .toEntity()
            dao.saveAnnotation(updated)
            updated
        }

    /**
     * Online only: places an acknowledged highlight on a [passage] the user selected. A 409 ANNOTATION_CONFLICT means
     * another change landed first; nothing local changes.
     */
    suspend fun reattachAnnotation(annotation: AnnotationEntity, passage: Passage): AnnotationEntity = committed {
        val annotationId =
            checkNotNull(annotation.serverId) {
                "Only acknowledged highlights can be reattached; ${annotation.mutationId} is pending"
            }
        val updated =
            api.reattachAnnotation(
                    annotationId,
                    AnnotationControllerReattachRequest(
                        expectedVersion = annotation.version,
                        anchor = passage.anchor(),
                    ),
                )
                .toEntity()
        dao.saveAnnotation(updated)
        updated
    }

    /**
     * Deletes a highlight or card. An acknowledged one is deleted on the server now (online only; 409 if it changed
     * elsewhere). A creation the server has not acknowledged is marked for deletion on this phone, so it disappears at
     * once and offline too; the next send replays the creation (the server may have it after a lost acknowledgement)
     * and deletes it there. A refused creation the server never accepted is only local.
     */
    suspend fun deleteAnnotation(annotation: AnnotationEntity) = committed {
        when (annotation.state) {
            AnnotationState.ACKNOWLEDGED -> {
                val annotationId =
                    checkNotNull(annotation.serverId) { "Acknowledged ${annotation.mutationId} has no server id" }
                try {
                    api.deleteAnnotation(annotationId, annotation.version)
                } catch (failure: ApiException) {
                    if (failure.status != 404 || failure.code != "NOT_FOUND") throw failure
                }
                dao.removeAnnotation(annotation.mutationId)
            }
            AnnotationState.REFUSED -> dao.removeAnnotation(annotation.mutationId)
            AnnotationState.UNSENT -> {
                dao.markDeleting(annotation.mutationId)
                requestPendingChangeSend()
            }
            AnnotationState.DELETING ->
                error("Highlight ${annotation.mutationId} is already being deleted; it is not shown")
        }
    }

    private suspend fun sendReviews() {
        for (review in dao.pendingReviews()) {
            val card =
                checkNotNull(dao.annotation(review.cardMutationId)) {
                    "Review ${review.mutationId} references missing local card ${review.cardMutationId}"
                }
            if (card.state == AnnotationState.REFUSED) {
                dao.rejectReview(review.mutationId, "Card creation was refused: ${card.rejection}")
                continue
            }
            // Its card has not reached the server yet (its creation waits for GitHub), so the grade waits with it.
            val cardId = card.serverId ?: continue
            val acceptedReview =
                try {
                    api.recordReview(
                        cardId,
                        ReviewControllerGradeRequest(
                            mutationId = review.mutationId,
                            grade = review.grade,
                            reviewedAt = Instant.ofEpochMilli(review.reviewedAt),
                            blobSha = review.blobSha,
                        ),
                    )
                } catch (failure: ApiException) {
                    if (!failure.refusesItem()) throw failure
                    dao.rejectReview(review.mutationId, failure.describe())
                    DeviceLog.warn(
                        "Review refused; cardId=$cardId mutationId=${review.mutationId} code=${failure.code}"
                    )
                    continue
                }
            if (acceptedReview.cardMutationId != review.cardMutationId) throw invalidResponse(200)
            dao.saveReview(acceptedReview.toEntity())
        }
    }

    suspend fun dismissRefusedReviews() = committed { dao.dismissRefusedReviews() }

    /**
     * Once per process, when the app opens: deletes images Room holds no page of, such as those a deletion cut short by
     * process death left.
     */
    suspend fun startUp() {
        locked {
            sweepImages()
        }
    }
}
