// Owns: Server/local-copy synchronization, pending-change disposition and ordered publication under the existing lock.

package com.reporead.android.sync

import android.util.Log
import com.reporead.android.BuildConfig
import com.reporead.android.core.network.Api
import com.reporead.android.core.network.ApiException
import com.reporead.android.core.network.describe
import com.reporead.android.core.network.invalidResponse
import com.reporead.android.core.network.wire.AnnotationControllerCheckRequest
import com.reporead.android.core.network.wire.AnnotationControllerCreateRequest
import com.reporead.android.core.network.wire.AnnotationControllerEditRequest
import com.reporead.android.core.network.wire.AnnotationControllerReattachRequest
import com.reporead.android.core.network.wire.ConnectionDataCounts
import com.reporead.android.core.network.wire.ReadingControllerAnchorRequest
import com.reporead.android.core.network.wire.ReadingControllerSaveRequest
import com.reporead.android.core.network.wire.RepositoryConnectionsConnection
import com.reporead.android.core.network.wire.RepositoryControllerConnectRequest
import com.reporead.android.core.network.wire.ReviewControllerGradeRequest
import com.reporead.android.core.network.wireJson
import com.reporead.android.data.AnnotationRow
import com.reporead.android.data.AnnotationState
import com.reporead.android.data.AnnotationType
import com.reporead.android.data.BookmarkRow
import com.reporead.android.data.DocumentRow
import com.reporead.android.data.InvalidReviewGrade
import com.reporead.android.data.LocalStore
import com.reporead.android.data.NewCardGrade
import com.reporead.android.data.NoteRow
import com.reporead.android.data.Passage
import com.reporead.android.data.ReadingRow
import com.reporead.android.data.RepositoryRow
import com.reporead.android.data.ReviewRow
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
        "MUTATION_ID_REUSED",
        "CARD_CHANGED" -> status == 409
        "ANNOTATION_DELETED",
        "DOCUMENT_DELETED" -> status == 410
        "INVALID_ANCHOR",
        "UNSUPPORTED_CONTENT" -> status == 422
        else -> false
    }

/**
 * What one sync could not do. [refused]: bookmark changes the server refused and the phone undid. [notSent]: why
 * pending highlights and cards were not sent this time; they wait for the next sync.
 */
data class LocalSync(val refused: List<RefusedChange>, val notSent: ApiException?)

/** The title is the document owner's snapshot at refusal, before a subsequent library replacement can remove it. */
data class RefusedChange(val documentId: Long, val title: String?, val error: ApiException)

/**
 * Owns how the offline cache and the backend meet. A failed call ends that operation and leaves the previous complete
 * cache untouched. Nothing here retries; every local change calls [onPendingChange], which asks for a background sync
 * (BackgroundSync.kt, ADR-08).
 */
class Sync(
    private val api: Api,
    store: LocalStore,
    filesDir: File,
    private val onPendingChange: () -> Unit,
) {
    private val dao = store.library()

    private val oneSyncAtATime = Mutex()

    /** Orders local writes and server answers for the process's one local copy. */
    private suspend fun <T> locked(work: suspend () -> T): T = oneSyncAtATime.withLock {
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
        if (paths.isNotEmpty())
            Log.w("RepoRead", "Files left on the phone; after=$after count=${paths.size} paths=$paths")
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
        val answer = api.library()
        val library =
            answer.repositories.associate { repository ->
                RepositoryRow(
                    repository.id,
                    repository.fullName,
                    repository.documentCount,
                    repository.lastSyncedCommitSha,
                ) to
                    repository.documents.map { document ->
                        DocumentRow(
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
        val reading = answer.readingStates.map { it.toRow() }
        val bookmarks = answer.bookmarks.map { BookmarkRow(it.documentId, true, false, it.createdAt.toEpochMilli()) }
        val annotations = answer.notebook.annotations.map { it.toRow() }
        val reviews = answer.notebook.reviews.map { it.toRow() }
        try {
            dao.replaceFromServer(library, reading, bookmarks, annotations, reviews, answer.notebook.sessionLimit)
        } catch (error: InvalidReviewGrade) {
            throw invalidResponse(200, error)
        }
        sweepImages()
    }

    /** Reloads the server's whole library (one request, no GitHub call); pending changes wait for a sync. */
    suspend fun refreshLibrary() = locked { loadLibrary() }

    /** Asks the backend to reconcile with GitHub, then reloads the now-current library. */
    suspend fun refreshFromGitHub(repositoryId: Long) = committed {
        api.syncRepository(repositoryId)
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
    data class Opened(val note: NoteRow, val staleReason: ApiException?)

    /**
     * The saved copy matches a listed note's blob and path. Only repository images and blocked embeds also need the
     * same known snapshot: their contents or resolution may change without a Markdown edit.
     */
    private fun sameVersion(cached: NoteRow, document: DocumentRow?, commitSha: String?): Boolean {
        val needsSnapshot = cached.html.contains(IMAGE_PREFIX) || cached.html.contains("image-blocked")
        return document != null &&
            !document.deleted &&
            cached.blobSha == document.blobSha &&
            cached.path == document.path &&
            (!needsSnapshot || (cached.snapshotCommitSha != null && cached.snapshotCommitSha == commitSha))
    }

    /** The saved copy can be shown without a network call: the current version, in the page format this app needs. */
    private fun upToDate(cached: NoteRow, document: DocumentRow?, commitSha: String?) =
        sameVersion(cached, document, commitSha) && cached.renderFormat >= BuildConfig.RENDER_FORMAT

    /**
     * An [upToDate] cached copy opens without a network call. Otherwise the current version is fetched; if that fails,
     * an older cached copy is shown with the reason, and a missing cache is the failure itself. A current copy saved in
     * an older page format (without search text, note links, embeds, or later presentation fixes) is fetched once more;
     * if that fails it is still the current version, so it opens without a warning.
     */
    suspend fun openNote(documentId: Long): Opened = locked {
        val cached = dao.note(documentId)
        val current = dao.document(documentId)
        val commitSha = current?.let { dao.repositoryCommitSha(it.repositoryId) }
        if (cached != null && upToDate(cached, current, commitSha)) return@locked Opened(cached, null)
        try {
            Opened(fetchNote(documentId).also { sweepImages() }, null)
        } catch (error: ApiException) {
            if (cached == null) throw error
            val cachedIsCurrent = sameVersion(cached, current, commitSha)
            Log.i("RepoRead", "Showing cached note; documentId=$documentId code=${error.code} current=$cachedIsCurrent")
            Opened(cached, if (cachedIsCurrent) null else error)
        }
    }

    /**
     * Fetches and saves the note's current version (one GitHub call on the server); the copy is saved whole or not at
     * all.
     */
    private suspend fun fetchNote(documentId: Long): NoteRow {
        val answer = api.noteContent(documentId)
        val note =
            NoteRow(
                documentId,
                answer.sourceBlobSha,
                answer.path,
                answer.title,
                answer.html,
                System.currentTimeMillis(),
                answer.text,
                answer.renderFormat,
                answer.snapshotCommitSha,
            )
        dao.saveNote(note)
        return note
    }

    /**
     * [fetched] new or updated copies whose image pass completed; [alreadySaved] were current and completed it too;
     * [cannotShow] pages skipped on a permanent content/image refusal, counted once and logged by id. Saved text stays.
     */
    data class SavedAll(val fetched: Int, val alreadySaved: Int, val cannotShow: Int)

    /**
     * Saves every note of [repositoryId] on the phone: reloads the library from the server ([refreshLibrary]), then
     * fetches each note whose saved copy is not [upToDate], one at a time. A permanent content/image refusal skips the
     * rest of that page and counts it once; any other API failure ends the run for the worker to retry or report. Each
     * page, including an already-current copy, saves its missing images before reporting progress. Saved text and
     * complete images survive failures and cancellation; another run checks the rest. At most one library call, one
     * content call per outdated note and 64 image calls per saved page (the server's reference limit). Run only by Save
     * all's background work (BackgroundSync.kt).
     */
    suspend fun saveAllNotes(repositoryId: Long, onProgress: suspend (done: Int, toFetch: Int) -> Unit): SavedAll {
        refreshLibrary()
        val documents = dao.listedDocuments(repositoryId)
        val commitSha = dao.repositoryCommitSha(repositoryId)
        Log.i("RepoRead", "Saving all notes; repositoryId=$repositoryId documents=${documents.size}")
        var fetched = 0
        var alreadySaved = 0
        var cannotShow = 0
        onProgress(0, documents.size)
        documents.forEachIndexed { index, document ->
            try {
                locked {
                    val cached = dao.note(document.id)
                    if (cached != null && upToDate(cached, document, commitSha)) {
                        images.save(cached)
                        alreadySaved++
                    } else {
                        images.save(fetchNote(document.id))
                        fetched++
                    }
                }
            } catch (error: ApiException) {
                // Unlike refusesItem(), this classifies page failures after a fresh library load, not mutations.
                val skipped =
                    when (error.code) {
                        "UNSUPPORTED_CONTENT" -> error.status == 422
                        "IMAGE_NOT_FOUND" -> error.status == 404
                        "INVALID_IMAGE_PATH" -> error.status == 400
                        else -> false
                    }
                if (!skipped) {
                    Log.w(
                        "RepoRead",
                        "Saving all notes stopped; repositoryId=$repositoryId fetched=$fetched documentId=${document.id} code=${error.code}",
                    )
                    throw error
                }
                Log.i("RepoRead", "Page skipped; documentId=${document.id} code=${error.code}")
                cannotShow++
            }
            onProgress(index + 1, documents.size)
        }
        locked { sweepImages() }
        Log.i(
            "RepoRead",
            "Saved all notes; repositoryId=$repositoryId fetched=$fetched alreadySaved=$alreadySaved cannotShow=$cannotShow",
        )
        return SavedAll(fetched, alreadySaved, cannotShow)
    }

    /**
     * What disconnecting would delete: [stored] on the server, counted after sending what can be sent, and [unsent]
     * highlights, cards, and grades only on this phone (unsent or refused), deleted too; [notSent] says why some were
     * not sent.
     */
    data class DisconnectPreview(val stored: ConnectionDataCounts, val unsent: Int, val notSent: ApiException?)

    /**
     * Sends pending work, then counts what disconnecting [repositoryId] would delete, for the confirmation. Null when
     * the server no longer has the repository (an earlier disconnect whose answer was lost, or another device): this
     * phone's copies are then removed by reloading the server's library.
     */
    suspend fun disconnectPreview(repositoryId: Long): DisconnectPreview? = locked {
        val synced = sendPending().also { loadLibrary() }
        val json =
            try {
                api.storedData(repositoryId)
            } catch (error: ApiException) {
                if (error.status != 404 || error.code != "NOT_FOUND") throw error
                Log.i("RepoRead", "Repository already disconnected on the server; repositoryId=$repositoryId")
                loadLibrary()
                return@locked null
            }
        DisconnectPreview(json, dao.unsentInRepository(repositoryId), synced.notSent)
    }

    /**
     * Online only: the server deletes the connection and everything stored for it, then this phone deletes its copies,
     * including unsynced changes for those notes. A failed request changes nothing on the phone, but the server may
     * already have deleted (its answer lost or unreadable). When the server no longer has the repository, disconnecting
     * again reloads the server's library instead. Either way the forgotten notes' images are deleted; files that stay
     * are logged and deleted again by the next sweep.
     */
    suspend fun disconnect(repositoryId: Long): Unit = committed {
        val json =
            try {
                api.disconnectRepository(repositoryId)
            } catch (error: ApiException) {
                if (error.status != 404 || error.code != "NOT_FOUND") throw error
                null
            }
        if (json == null) {
            Log.i("RepoRead", "Repository already disconnected on the server; repositoryId=$repositoryId")
            loadLibrary()
        } else {
            val documentIds = json.documentIds
            dao.forgetRepository(repositoryId, documentIds)
            Log.i("RepoRead", "Repository disconnected; repositoryId=$repositoryId documents=${documentIds.size}")
            sweepImages()
        }
    }

    /** Only the reader calls this, with the version it actually displayed. False for a disconnected note. */
    suspend fun saveReading(row: ReadingRow): Boolean = committed {
        dao.saveDisplayedReading(row.copy(pending = true)).also { saved ->
            if (saved) onPendingChange()
            else Log.i("RepoRead", "Reading position not saved; note gone; documentId=${row.documentId}")
        }
    }

    /** False when the phone no longer lists the note: nothing is saved. */
    suspend fun setBookmark(documentId: Long, bookmarked: Boolean): Boolean = committed {
        dao.saveLocalBookmark(BookmarkRow(documentId, bookmarked, pending = true, System.currentTimeMillis())).also {
            saved ->
            if (saved) onPendingChange() else Log.i("RepoRead", "Bookmark not saved; note gone; documentId=$documentId")
        }
    }

    /**
     * Saves a grade as a pending local mutation; false when its card changed meanwhile and nothing was saved (see
     * recordReview).
     */
    suspend fun recordReview(row: ReviewRow): Boolean = committed {
        dao.recordReview(row).also { if (it) onPendingChange() }
    }

    /**
     * The sync run by library open, pull to refresh and Sync now: sends what waits ([sendPending]), then loads the
     * server's library ([loadLibrary]).
     */
    suspend fun syncLocalChanges(): LocalSync = locked { sendPending().also { loadLibrary() } }

    /**
     * The background sync's run: sends what waits, and nothing when nothing does (null); each send saves the server's
     * answer, so the lists are left to the screens.
     */
    suspend fun syncWaitingChanges(): LocalSync? = locked {
        if (dao.waitingCount().first() == 0) null else sendPending()
    }

    /**
     * Sends what waits, each saved as the server answers it. Highlight and card creations need GitHub; when GitHub, or
     * access to it, fails they wait, and the database-only work still runs (ADR-03). A refused item is marked and the
     * run goes on; any other failure ends the run with unacknowledged changes still pending.
     */
    private suspend fun sendPending(): LocalSync {
        val notSent =
            try {
                sendAnnotations()
                null
            } catch (error: ApiException) {
                if (error.unreachable) throw error
                error
            }
        sendReviews()
        val refused = mutableListOf<RefusedChange>()
        for (row in dao.pendingReading()) {
            val body =
                ReadingControllerSaveRequest(
                    lastReadBlobSha = row.lastReadBlobSha,
                    progressPercent = row.progressPercent,
                    anchor = wireJson.decodeFromString<ReadingControllerAnchorRequest>(row.anchorJson),
                    lastReadAt = Instant.ofEpochMilli(row.lastReadAt),
                )
            val current =
                try {
                    api.saveReading(row.documentId, body).toRow()
                } catch (error: ApiException) {
                    if (error.status != 404 || error.code != "NOT_FOUND") throw error
                    Log.i("RepoRead", "Reading state no longer owned; documentId=${row.documentId}")
                    // Not this user's note on the server any more: the position goes with it when the lists are
                    // next replaced. Malformed saves are programmer errors, not item refusals.
                    dao.acknowledgeReading(row.documentId, row.lastReadAt)
                    continue
                }
            dao.acknowledgeReading(row.documentId, row.lastReadAt)
            dao.mergeRemoteReading(current)
        }
        for (row in dao.pendingBookmarks()) {
            try {
                if (row.bookmarked) {
                    api.setBookmark(row.documentId)
                    dao.acknowledgeBookmarkSet(row.documentId, row.changedAt)
                } else {
                    api.clearBookmark(row.documentId)
                    dao.acknowledgeBookmarkCleared(row.documentId, row.changedAt)
                }
            } catch (error: ApiException) {
                if (!error.refusesItem()) throw error
                Log.w("RepoRead", "Bookmark refused; documentId=${row.documentId} code=${error.code}")
                dao.dropBookmark(row.documentId, row.changedAt)
                refused +=
                    RefusedChange(
                        row.documentId,
                        dao.document(row.documentId)?.title,
                        error,
                    )
            }
        }
        return LocalSync(refused, notSent)
    }

    /**
     * Saves a new highlight (or, with [question], a card) locally as a pending creation; it is sent with the returned
     * mutation id until acknowledged. Null for a page disconnected meanwhile.
     */
    suspend fun createAnnotation(passage: Passage, documentId: Long, note: String?, question: String? = null): String? =
        committed {
            val row = newAnnotation(passage, documentId, note, question)
            if (!dao.saveLocalAnnotation(row)) {
                Log.i("RepoRead", "Highlight not saved; page gone; documentId=$documentId")
                return@committed null
            }
            onPendingChange()
            row.mutationId
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
                ReviewRow(
                    mutationId = UUID.randomUUID().toString(),
                    cardMutationId = card.mutationId,
                    grade = grade,
                    reviewedAt = System.currentTimeMillis(),
                    blobSha = passage.blobSha,
                    pending = true,
                )
            val result = dao.saveCardWithFirstReview(card, review)
            if (result == NewCardGrade.SAVED) onPendingChange()
            Log.i("RepoRead", "Practice grade; documentId=$documentId mutationId=${card.mutationId} outcome=$result")
            result
        }

    private fun newAnnotation(
        passage: Passage,
        documentId: Long,
        note: String?,
        question: String?,
    ): AnnotationRow =
        AnnotationRow(
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
            checkedBlobSha = if (question == null) null else passage.blobSha,
        )

    /**
     * Sends pending creations; a failure that is not one item's refusal stops it, and the rest wait, unsent, for the
     * next sync. The same mutation id is replayed until the server answers, so a lost acknowledgement cannot create a
     * duplicate. A refusal is kept on the row and shown; it is not retried.
     */
    suspend fun pushAnnotations() = locked { sendAnnotations() }

    private suspend fun sendAnnotations() {
        for (row in dao.waitingAnnotations()) {
            val deleting = row.state == AnnotationState.DELETING
            check(row.serverId == null || deleting) { "Unsent ${row.mutationId} has a server id" }
            // A deletion whose creation the server has not acknowledged replays the creation first, to learn its id.
            val acknowledged = if (row.serverId != null) row else sendCreation(row, deleting) ?: continue
            if (deleting) sendDeletion(acknowledged)
        }
    }

    /**
     * Sends [row]'s creation and saves and returns the server's copy. Null when the server refused this one item: it is
     * then removed (deleted already, or being [deleting]) or kept as refused. Any other failure stops the run.
     */
    private suspend fun sendCreation(row: AnnotationRow, deleting: Boolean): AnnotationRow? {
        val body =
            AnnotationControllerCreateRequest(
                mutationId = row.mutationId,
                anchor =
                    Passage(row.sourceBlobSha, row.blockId, row.startOffset, row.endOffset, row.exactText).anchor(),
                note = row.note,
                type = row.type.name,
                question = row.question,
            )
        val created =
            try {
                api.createAnnotation(row.documentId, body).toRow()
            } catch (error: ApiException) {
                when {
                    (error.status == 410 && error.code == "ANNOTATION_DELETED") || (deleting && error.refusesItem()) ->
                        dao.removeAnnotation(row.mutationId)
                    error.refusesItem() -> dao.rejectAnnotation(row.mutationId, error.describe())
                    else -> {
                        Log.w(
                            "RepoRead",
                            "Highlight creations stopped; documentId=${row.documentId} code=${error.code}",
                        )
                        throw error
                    }
                }
                Log.w(
                    "RepoRead",
                    "Highlight creation refused; documentId=${row.documentId} code=${error.code} deleting=$deleting",
                )
                return null
            }
        return created.also { dao.saveServerAnnotation(it) }
    }

    /**
     * Deletes on the server a creation the user deleted before it was acknowledged; throws a failure that is not this
     * item's.
     */
    private suspend fun sendDeletion(row: AnnotationRow) {
        val serverId = checkNotNull(row.serverId) { "Deleting ${row.mutationId} needs its server id" }
        try {
            api.deleteAnnotation(serverId, row.version)
        } catch (error: ApiException) {
            if (!error.refusesItem()) {
                Log.w("RepoRead", "Highlight deletions stopped; annotationId=$serverId code=${error.code}")
                throw error
            }
            if (error.code != "NOT_FOUND") {
                // Changed elsewhere first (409): shown again rather than deleted unseen.
                Log.w("RepoRead", "Highlight deletion refused; annotationId=$serverId code=${error.code}")
                dao.saveAnnotation(row.copy(state = AnnotationState.ACKNOWLEDGED))
                return
            }
        }
        dao.removeAnnotation(row.mutationId)
        Log.i("RepoRead", "Highlight deleted after its creation was acknowledged; annotationId=$serverId")
    }

    /** Pending creations are sent first, so the server's list already contains them where possible. */
    suspend fun refreshAnnotations(documentId: Long) = locked {
        sendAnnotations()
        val rows = api.annotations(documentId).annotations.map { it.toRow() }
        dao.replaceRemoteAnnotations(documentId, rows)
    }

    /** Online only. A 409 ANNOTATION_CONFLICT means another edit landed first; nothing local changes. */
    suspend fun editAnnotation(row: AnnotationRow, note: String?, expectedVersion: Int): AnnotationRow = committed {
        val serverId =
            checkNotNull(row.serverId) {
                "Only acknowledged highlights can be edited; ${row.mutationId} is pending"
            }
        val updated =
            api.editAnnotation(
                    serverId,
                    AnnotationControllerEditRequest(note = note, expectedVersion = expectedVersion),
                )
                .toRow()
        dao.saveAnnotation(updated)
        updated
    }

    /**
     * Online only: places an acknowledged highlight on a [passage] the user selected. A 409 ANNOTATION_CONFLICT means
     * another change landed first; nothing local changes.
     */
    suspend fun reattachAnnotation(row: AnnotationRow, passage: Passage): AnnotationRow = committed {
        val serverId =
            checkNotNull(row.serverId) {
                "Only acknowledged highlights can be reattached; ${row.mutationId} is pending"
            }
        val updated =
            api.reattachAnnotation(
                    serverId,
                    AnnotationControllerReattachRequest(expectedVersion = row.version, anchor = passage.anchor()),
                )
                .toRow()
        dao.saveAnnotation(updated)
        updated
    }

    /**
     * Deletes a highlight or card. An acknowledged one is deleted on the server now (online only; 409 if it changed
     * elsewhere). A creation the server has not acknowledged is marked for deletion on this phone, so it disappears at
     * once and offline too; the next sync replays the creation (the server may have it after a lost acknowledgement)
     * and deletes it there. A refused creation the server never accepted is only local.
     */
    suspend fun deleteAnnotation(row: AnnotationRow) = committed {
        when (row.state) {
            AnnotationState.ACKNOWLEDGED -> {
                val serverId = checkNotNull(row.serverId) { "Acknowledged ${row.mutationId} has no server id" }
                try {
                    api.deleteAnnotation(serverId, row.version)
                } catch (error: ApiException) {
                    if (error.status != 404 || error.code != "NOT_FOUND") throw error
                }
                dao.removeAnnotation(row.mutationId)
            }
            AnnotationState.REFUSED -> dao.removeAnnotation(row.mutationId)
            AnnotationState.UNSENT -> {
                dao.markDeleting(row.mutationId)
                onPendingChange()
            }
            AnnotationState.DELETING -> error("Highlight ${row.mutationId} is already being deleted; it is not shown")
        }
    }

    private suspend fun sendReviews() {
        for (row in dao.pendingReviews()) {
            val card =
                checkNotNull(dao.annotation(row.cardMutationId)) {
                    "Review ${row.mutationId} references missing local card ${row.cardMutationId}"
                }
            if (card.state == AnnotationState.REFUSED) {
                dao.rejectReview(row.mutationId, "Card creation was refused: ${card.rejection}")
                continue
            }
            // Its card has not reached the server yet (its creation waits for GitHub), so the grade waits with it.
            val id = card.serverId ?: continue
            val remote =
                try {
                    api.recordReview(
                        id,
                        ReviewControllerGradeRequest(
                            mutationId = row.mutationId,
                            grade = row.grade,
                            reviewedAt = Instant.ofEpochMilli(row.reviewedAt),
                            blobSha = row.blobSha,
                        ),
                    )
                } catch (error: ApiException) {
                    if (!error.refusesItem()) throw error
                    dao.rejectReview(row.mutationId, error.describe())
                    Log.w("RepoRead", "Review refused; cardId=$id mutationId=${row.mutationId} code=${error.code}")
                    continue
                }
            if (remote.cardMutationId != row.cardMutationId) throw invalidResponse(200)
            dao.saveReview(remote.toRow())
        }
    }

    suspend fun checkCard(row: AnnotationRow, blobSha: String) = committed {
        val id = checkNotNull(row.serverId) { "Only acknowledged cards can be confirmed; ${row.mutationId} is pending" }
        dao.saveAnnotation(
            api.checkCard(id, AnnotationControllerCheckRequest(expectedVersion = row.version, blobSha = blobSha))
                .toRow()
        )
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
