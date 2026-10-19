// Owns: Server/local-copy synchronization, pending-change disposition and ordered publication under the existing lock.

package com.reporead.android.sync

import android.database.sqlite.SQLiteException
import android.util.Log
import com.reporead.android.core.network.Api
import com.reporead.android.core.network.ApiException
import com.reporead.android.core.network.describe
import com.reporead.android.core.network.invalidResponse
import com.reporead.android.core.network.wire.AnnotationControllerCheckRequest
import com.reporead.android.core.network.wire.AnnotationControllerCreateRequest
import com.reporead.android.core.network.wire.AnnotationControllerEditRequest
import com.reporead.android.core.network.wire.AnnotationControllerReattachRequest
import com.reporead.android.core.network.wire.ConnectionDataCounts
import com.reporead.android.core.network.wire.DocumentControllerChanges
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
import com.reporead.android.data.LocalStore
import com.reporead.android.data.NoteRow
import com.reporead.android.data.Passage
import com.reporead.android.data.ReadingRow
import com.reporead.android.data.RepositoryRow
import com.reporead.android.data.ReviewRow
import com.reporead.android.data.SavedVersion
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The page format this app needs from the server (MarkdownRenderer.FORMAT): 2 adds note links and Obsidian embeds, 3
 * footnotes.
 */
const val RENDER_FORMAT = 3

/** Where notebook exports are written; res/xml/export_paths.xml shares exactly this directory and nothing else. */
fun exportDirectory(filesDir: File) = File(filesDir, "exports")

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
        "INVALID_READING_STATE",
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
 * What one sync could not do. [refused]: reading positions and bookmark changes the server refused; a refused position
 * stays on this phone only, a refused bookmark change is undone. [notSent]: why pending highlights and cards were not
 * sent this time; they wait for the next sync.
 */
data class LocalSync(val refused: List<RefusedChange>, val notSent: ApiException?)

/** The title is the document owner's snapshot at refusal, before a subsequent library replacement can remove it. */
data class RefusedChange(val kind: Kind, val documentId: Long, val title: String?, val error: ApiException) {
    /** Identifies the refused local operation without choosing any screen wording. */
    enum class Kind {
        READING,
        BOOKMARK,
    }
}

/** What clearing this phone's copy did ([Sync.clearRetiredCopy]). */
sealed interface LocalCleanup {
    /** Room's rows and every file are gone. */
    data object Done : LocalCleanup

    /** Room's rows are gone; [paths], each file and folder still there (deepest first), are not. */
    data class FilesLeft(val paths: List<File>) : LocalCleanup

    /** A WorkManager or Room database failure prevented removal ([error]); no file was tried. */
    data class DatabaseFailed(val error: SQLiteException) : LocalCleanup
}

/**
 * Owns how the offline cache and the backend meet. A failed call ends that operation and leaves the previous complete
 * cache untouched. Nothing here retries; every local change calls [onPendingChange], which asks for a background sync
 * (BackgroundSync.kt, ADR-08).
 */
class Sync(
    private val api: Api,
    private val store: LocalStore,
    private val filesDir: File,
    private val onPendingChange: () -> Unit,
) {
    private val dao = store.library()

    /** One local-copy lifetime: retired instances never admit another operation, even after re-sign-in. */
    @Volatile private var retired = false
    private val oneSyncAtATime = Mutex()

    /** Local writes, network answers and export publication share the same order as removal. */
    private suspend fun <T> locked(work: suspend () -> T): T = oneSyncAtATime.withLock {
        withContext(Dispatchers.IO) {
            if (retired) throw CancellationException("This phone's local copy was removed")
            work()
        }
    }

    /**
     * [locked], but its caller's cancellation does not stop it, from waiting for the lock to its local commit: a screen
     * left while a local write waits, or while the server confirms a change, does not lose that change here. Retirement
     * still refuses it. Only for one local write, or one mutation and its commit (at most two calls, each within Api's
     * timeouts); read-only loads and the pending-change passes stay cancellable.
     */
    private suspend fun <T> committed(work: suspend () -> T): T = withContext(NonCancellable) { locked(work) }

    /** Closes admission immediately; the clear waits for an already admitted operation, not for screens. */
    fun retire() {
        retired = true
        images.retire()
    }

    /** Only Account replaces the retired instance, after every row and file is gone. */
    internal fun freshCopy(): Sync {
        check(retired) { "Replacing a local copy that has not been retired" }
        return Sync(api, store, filesDir, onPendingChange)
    }

    /** Saved notes' images; deleted here whenever their notes go. */
    val images = NoteImages(api, filesDir)
    private val exportRoot = exportDirectory(filesDir)

    /** The [paths] a deletion [after] something left, logged; a later sweep or clear deletes them again. */
    private fun logged(after: String, paths: List<File>): List<File> {
        if (paths.isNotEmpty())
            Log.w("RepoRead", "Files left on the phone; after=$after count=${paths.size} paths=$paths")
        return paths
    }

    /**
     * Deletes the images of every version Room holds no page of: forgotten notes' and replaced versions'. Derived from
     * Room each time, so nothing remembers what to delete. Run wherever a saved page may have gone or been replaced:
     * after a page is saved, after the server's lists replace the saved ones, after a disconnect, at a clear and at
     * start.
     */
    private suspend fun sweepImages(): List<File> = logged("sweeping images", images.sweep(dao.allSavedVersions()))

    /**
     * Exports are kept only until they are shared: deleted at start, at a disconnect and at a clear, so none outlives
     * the notes it quotes. An app that reads a shared export only after one of those finds it gone.
     */
    private fun deleteExports(): List<File> = logged("deleting exports", deleteTree(exportRoot))

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
        dao.replaceFromServer(library, reading, bookmarks, annotations, reviews, answer.notebook.sessionLimit)
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
     * The saved copy is the current version of [document], a listed note: same blob, path and repository snapshot. The
     * snapshot matters even when Markdown is unchanged: images and embed resolution may have changed.
     */
    private fun sameVersion(cached: SavedVersion, document: DocumentRow?, commitSha: String?) =
        document != null &&
            !document.deleted &&
            cached.blobSha == document.blobSha &&
            cached.path == document.path &&
            cached.snapshotCommitSha != null &&
            cached.snapshotCommitSha == commitSha

    /** The saved copy can be shown without a network call: the current version, in the page format this app needs. */
    private fun upToDate(cached: SavedVersion, document: DocumentRow?, commitSha: String?) =
        sameVersion(cached, document, commitSha) && cached.renderFormat >= RENDER_FORMAT

    /**
     * A cached copy of the document's current version, path and repository snapshot opens without a network call.
     * Otherwise the current version is fetched; if that fails, an older cached copy is shown with the reason, and a
     * missing cache is the failure itself. A current copy saved in an older page format (without search text, note
     * links, embeds, or footnotes) is fetched once more; if that fails it is still the current version, so it opens
     * without a warning.
     */
    suspend fun openNote(documentId: Long): Opened = locked {
        val cached = dao.note(documentId)
        val current = dao.document(documentId)
        val commitSha = current?.let { dao.repositoryCommitSha(it.repositoryId) }
        if (cached != null && upToDate(cached.version, current, commitSha)) return@locked Opened(cached, null)
        try {
            Opened(fetchNote(documentId), null)
        } catch (error: ApiException) {
            if (cached == null) throw error
            val cachedIsCurrent = sameVersion(cached.version, current, commitSha)
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
        // Images left behind are logged and skipped: the page asks only for this version's, and the next sweep deletes
        // them.
        sweepImages()
        return note
    }

    /**
     * [fetched] new or updated copies; [alreadySaved] were current; [cannotShow] notes the server refused to render
     * (too large), each logged by id.
     */
    data class SavedAll(val fetched: Int, val alreadySaved: Int, val cannotShow: Int)

    /**
     * Saves every note of [repositoryId] on the phone: reloads the library from the server ([refreshLibrary]), then
     * fetches each note whose saved copy is not [upToDate], one at a time. A note the server refuses to render
     * (UNSUPPORTED_CONTENT) is counted and skipped; any other failure ends the run, keeping the copies already saved.
     * Cancelling keeps them too, so running again continues where it stopped. Images are not fetched; they are saved
     * when a note is read online. Run only by Save all's background work (BackgroundSync.kt).
     */
    suspend fun saveAllNotes(repositoryId: Long, onProgress: suspend (done: Int, toFetch: Int) -> Unit): SavedAll {
        refreshLibrary()
        val documents = dao.listedDocuments(repositoryId)
        val commitSha = dao.repositoryCommitSha(repositoryId)
        val saved = dao.savedVersions(repositoryId).associateBy { it.documentId }
        val missing = documents.filter { document ->
            saved[document.id]?.let { upToDate(it, document, commitSha) } != true
        }
        Log.i(
            "RepoRead",
            "Saving all notes; repositoryId=$repositoryId documents=${documents.size} toFetch=${missing.size}",
        )
        var cannotShow = 0
        onProgress(0, missing.size)
        missing.forEachIndexed { index, document ->
            try {
                locked { fetchNote(document.id) }
            } catch (error: ApiException) {
                if (error.code != "UNSUPPORTED_CONTENT") {
                    Log.w(
                        "RepoRead",
                        "Saving all notes stopped; repositoryId=$repositoryId fetched=${index - cannotShow} documentId=${document.id} code=${error.code}",
                    )
                    throw error
                }
                Log.i("RepoRead", "Note cannot be shown; documentId=${document.id} code=${error.code}")
                cannotShow++
            }
            onProgress(index + 1, missing.size)
        }
        Log.i(
            "RepoRead",
            "Saved all notes; repositoryId=$repositoryId fetched=${missing.size - cannotShow} cannotShow=$cannotShow",
        )
        return SavedAll(missing.size - cannotShow, documents.size - missing.size, cannotShow)
    }

    /**
     * Which sections changed between [since], the version last read, and [to], the version on screen. Online only;
     * nothing is cached.
     */
    suspend fun changes(documentId: Long, since: String, to: String): DocumentControllerChanges = locked {
        api.changes(documentId, since, to)
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
                deleteExports()
                return@locked null
            }
        DisconnectPreview(json, dao.unsentInRepository(repositoryId), synced.notSent)
    }

    /**
     * Online only: the server deletes the connection and everything stored for it, then this phone deletes its copies,
     * including unsynced changes for those notes. A failed request changes nothing on the phone, but the server may
     * already have deleted (its answer lost or unreadable). When the server no longer has the repository, disconnecting
     * again reloads the server's library instead. Either way exports and the forgotten notes' images are deleted; files
     * that stay are logged and deleted again by the next sweep.
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
        deleteExports()
    }

    /**
     * Only the reader calls this, with the version it actually displayed. False for a disconnected note; a retired
     * local copy cancels the operation before accessing Room.
     */
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
     * answer, so the lists are left to the screens. A retired copy cancels the operation before any request or write.
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
                    if (!error.refusesItem()) throw error
                    Log.w("RepoRead", "Reading state refused; documentId=${row.documentId} code=${error.code}")
                    // Not this user's note on the server any more: the position goes with it when the lists are
                    // next replaced. Any other refusal keeps the position on this phone, pending, and offers it again
                    // at the next sync.
                    if (error.status == 404) dao.acknowledgeReading(row.documentId, row.lastReadAt)
                    refused +=
                        RefusedChange(
                            RefusedChange.Kind.READING,
                            row.documentId,
                            dao.document(row.documentId)?.title,
                            error,
                        )
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
                        RefusedChange.Kind.BOOKMARK,
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
     * mutation id until acknowledged. Null for a page disconnected meanwhile; a retired local copy cancels before
     * accessing Room.
     */
    suspend fun createAnnotation(passage: Passage, documentId: Long, note: String?, question: String? = null): String? =
        committed {
            saveNewAnnotation(passage, documentId, note, question)
        }

    /**
     * A new question's card for [passage] with its first [grade], committed together, so leaving the screen between
     * them cannot keep the card without its grade. Null when the page is gone and nothing was saved; otherwise whether
     * the grade was saved too ([recordReview]).
     */
    suspend fun gradeNewCard(passage: Passage, documentId: Long, question: String, grade: Int): Boolean? = committed {
        val card = saveNewAnnotation(passage, documentId, null, question) ?: return@committed null
        val review =
            ReviewRow(
                mutationId = UUID.randomUUID().toString(),
                cardMutationId = card,
                grade = grade,
                reviewedAt = System.currentTimeMillis(),
                blobSha = passage.blobSha,
                pending = true,
            )
        dao.recordReview(review).also { if (it) onPendingChange() }
    }

    private suspend fun saveNewAnnotation(
        passage: Passage,
        documentId: Long,
        note: String?,
        question: String?,
    ): String? {
        val row =
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
        if (!dao.saveLocalAnnotation(row)) {
            Log.i("RepoRead", "Highlight not saved; page gone; documentId=$documentId")
            return null
        }
        onPendingChange()
        return row.mutationId
    }

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

    /** Owns export files, not their Markdown formatting or Android sharing. A retired copy cannot publish. */
    suspend fun exportNotebook(markdown: () -> String): File = locked {
        if (!exportRoot.isDirectory && !exportRoot.mkdirs())
            throw IOException("Cannot create RepoRead export directory")
        val file = File(exportRoot, "notebook-${UUID.randomUUID()}.md")
        val partial = File(exportRoot, "${file.name}.part")
        try {
            // Files.writeString requires API 36.1; the app supports API 34.
            partial.writeText(markdown(), Charsets.UTF_8)
            Files.move(partial.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(partial.toPath())
        }
        file
    }

    suspend fun changesSeen(documentId: Long, since: String) = committed { dao.changesSeen(documentId, since) }

    suspend fun dismissRefusedReviews() = locked { dao.dismissRefusedReviews() }

    /**
     * Explicit sign-out removes every cached note, image, and pending change from this phone; a running sync finishes
     * first. Says what it could not delete; running it again tries again, whenever it runs. A database failure is the
     * expected kind only (SQLiteException: a full or failing disk); any other failure is a bug and is thrown.
     */
    suspend fun clearRetiredCopy(): LocalCleanup {
        check(retired) { "Clearing a local copy while operations are still admitted" }
        return oneSyncAtATime.withLock {
            withContext(Dispatchers.IO) {
                val left =
                    try {
                        store.clearAllTables()
                        deleteExports() + sweepImages()
                    } catch (error: SQLiteException) {
                        Log.w("RepoRead", "Could not clear the phone's database", error)
                        return@withContext LocalCleanup.DatabaseFailed(error)
                    }
                if (left.isEmpty()) LocalCleanup.Done else LocalCleanup.FilesLeft(left)
            }
        }
    }

    /**
     * Once per process, when the app opens: exports from before are deleted, and images Room holds no page of, such as
     * those a deletion cut short by process death left.
     */
    suspend fun startUp() {
        locked {
            deleteExports()
            sweepImages()
        }
    }
}
