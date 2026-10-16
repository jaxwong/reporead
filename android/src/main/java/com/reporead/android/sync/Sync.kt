package com.reporead.android.sync

import android.util.Log
import com.reporead.android.core.network.Api
import com.reporead.android.core.network.ApiException
import com.reporead.android.core.network.contract
import com.reporead.android.core.network.describe
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
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

/** How a section differs between two versions (the server's NoteChanges.Change). */
enum class SectionChange {
    ADDED,
    CHANGED,
    REMOVED,
}

/** A changed section; empty [headingPath] is the beginning of the note; [blockId] is in the newer version. */
data class ChangedSection(
    val change: SectionChange,
    val headingPath: List<String>,
    val blockId: String?,
    val addedLines: Int,
    val removedLines: Int,
)

/** The server's answer to comparing two versions (DocumentController.ChangeStatus). */
enum class ChangeStatus {
    /** With [Changes.sections]. */
    CHANGED,
    UNCHANGED,
    /** The older version can't be read any more; [Changes.reason] says why. */
    SINCE_UNAVAILABLE,
    TOO_LARGE,
}

/** The server's comparison of the version last read with the one on screen. */
data class Changes(
    val status: ChangeStatus,
    val reason: String?,
    val addedLines: Int,
    val removedLines: Int,
    val sections: List<ChangedSection>,
)

/**
 * The page format this app needs from the server (MarkdownRenderer.FORMAT): 2 adds note links and Obsidian embeds, 3
 * footnotes.
 */
const val RENDER_FORMAT = 3

/** Where notebook exports are written; res/xml/export_paths.xml shares exactly this directory and nothing else. */
fun exportDirectory(filesDir: File) = File(filesDir, "exports")

private val REFUSAL_STATUSES = setOf(400, 404, 409, 410, 422)

/**
 * The server answered and refused this one request for a reason resending it cannot change (invalid, gone, a
 * conflicting edit): the item is marked or dropped and the run goes on. Any other failure — unreachable, signed out,
 * access denied, GitHub or the server failing, or an answer that is not RepoRead's — says nothing about the item, which
 * stays pending.
 */
internal fun ApiException.refusesItem() = status in REFUSAL_STATUSES && !code.startsWith("HTTP_")

/**
 * What one sync could not do. [refused]: reading positions and bookmark changes the server refused; a refused position
 * stays on this phone only, a refused bookmark change is undone. [notSent]: why pending highlights and cards were not
 * sent this time; they wait for the next sync.
 */
data class LocalSync(val refused: List<String>, val notSent: ApiException?)

/**
 * Owns how the offline cache and the backend meet. A failed call ends that operation and leaves the previous complete
 * cache untouched. Nothing here retries; every local change calls [onPendingChange], which asks for a background sync
 * (BackgroundSync.kt, ADR-08).
 */
class Sync(
    private val api: Api,
    private val store: LocalStore,
    filesDir: File,
    private val onPendingChange: () -> Unit,
) {
    private val dao = store.library()

    /**
     * Every operation that writes the server's answers into Room, and clearing the phone, run under this lock, so
     * nothing fetched before sign-out or an account switch is written after it (ADR-22). The app has one Sync
     * (RepoReadApplication), so the screens and the background sync share it. Not reentrant: locked operations call
     * only private helpers. Local saves and the reader's image fetches ([NoteImages]) do not wait; a late image is
     * removed at the next clear.
     */
    private val oneSyncAtATime = Mutex()

    /** Saved notes' images; deleted here whenever their notes go. */
    val images = NoteImages(api, filesDir)
    private val exportRoot = exportDirectory(filesDir)

    /**
     * The server's complete repository list, then each repository's complete note list (one request each, no GitHub
     * call): the documents table names every note the reading, bookmark and annotation rows refer to.
     */
    private suspend fun loadRepositories() {
        val json = api.get("/api/repositories")
        val rows = contract {
            val array = json.getJSONArray("repositories")
            List(array.length()) {
                val item = array.getJSONObject(it)
                RepositoryRow(
                    item.getLong("id"),
                    item.getString("fullName"),
                    item.getInt("documentCount"),
                    item.nullableString("lastSyncedCommitSha"),
                )
            }
        }
        // Not in the server's complete list: disconnected on another device, or here with the answer lost.
        images.delete(dao.replaceRepositories(rows))
        for (row in rows) loadDocuments(row.id)
    }

    suspend fun refreshDocuments(repositoryId: Long) = oneSyncAtATime.withLock { loadDocuments(repositoryId) }

    private suspend fun loadDocuments(repositoryId: Long) {
        val json = api.get("/api/repositories/$repositoryId/documents")
        val rows = contract {
            val array = json.getJSONArray("documents")
            List(array.length()) {
                val item = array.getJSONObject(it)
                DocumentRow(
                    item.getLong("id"),
                    repositoryId,
                    item.getString("path"),
                    item.getString("title"),
                    item.getString("blobSha"),
                    item.nullableString("contentChangedAt")?.let { Instant.parse(it).toEpochMilli() },
                    item.getBoolean("deleted"),
                )
            }
        }
        dao.replaceDocuments(repositoryId, rows)
    }

    /** Asks the backend to reconcile with GitHub, then reloads the now-current lists. */
    suspend fun refreshFromGitHub(repositoryId: Long) = oneSyncAtATime.withLock {
        api.post("/api/repositories/$repositoryId/sync")
        loadRepositories()
    }

    data class Opened(val note: NoteRow, val staleReason: String?)

    /**
     * The saved copy is the current version of [document], a listed note: same blob and same path (the page shows the
     * path and resolves relative images against it).
     */
    private fun sameVersion(cached: NoteRow, document: DocumentRow?) =
        document != null && !document.deleted && cached.blobSha == document.blobSha && cached.path == document.path

    /** The saved copy can be shown without a network call: the current version, in the page format this app needs. */
    private fun upToDate(cached: NoteRow, document: DocumentRow?) =
        sameVersion(cached, document) && cached.renderFormat >= RENDER_FORMAT

    /**
     * A cached copy of the document's current version and path opens without a network call. Otherwise the current
     * version is fetched; if that fails, an older cached copy is shown with the reason, and a missing cache is the
     * failure itself. A current copy saved in an older page format (without search text, note links, embeds, or
     * footnotes) is fetched once more; if that fails it is still the current version, so it opens without a warning.
     */
    suspend fun openNote(documentId: Long): Opened {
        val cached = dao.note(documentId)
        val current = dao.document(documentId)
        if (cached != null && upToDate(cached, current)) return Opened(cached, null)
        return try {
            Opened(oneSyncAtATime.withLock { fetchNote(documentId) }, null)
        } catch (error: ApiException) {
            if (cached == null) throw error
            val cachedIsCurrent = sameVersion(cached, current)
            Log.i("RepoRead", "Showing cached note; documentId=$documentId code=${error.code} current=$cachedIsCurrent")
            Opened(cached, if (cachedIsCurrent) null else error.describe())
        }
    }

    /**
     * Fetches and saves the note's current version (one GitHub call on the server); the copy is saved whole or not at
     * all.
     */
    private suspend fun fetchNote(documentId: Long): NoteRow {
        val json = api.get("/api/documents/$documentId/content")
        val note = contract {
            NoteRow(
                documentId,
                json.getString("sourceBlobSha"),
                json.getString("path"),
                json.getString("title"),
                json.getString("html"),
                System.currentTimeMillis(),
                json.getString("text"),
                json.getInt("renderFormat"),
            )
        }
        dao.saveNote(note)
        images.keepOnly(documentId, note.blobSha)
        return note
    }

    /**
     * [fetched] new or updated copies; [alreadySaved] were current; [cannotShow] are the paths the server refused to
     * render (too large).
     */
    data class SavedAll(val fetched: Int, val alreadySaved: Int, val cannotShow: List<String>)

    /**
     * Saves every note of [repositoryId] on the phone: refreshes the note list from the server (no GitHub call), then
     * fetches each note whose saved copy is not [upToDate], one at a time. A note the server refuses to render
     * (UNSUPPORTED_CONTENT) is listed and skipped; any other failure ends the run, keeping the copies already saved.
     * Cancelling (leaving the screen) also keeps them, so running again continues where it stopped. Images are not
     * fetched; they are saved when a note is read online.
     */
    suspend fun saveAllNotes(repositoryId: Long, onProgress: (done: Int, toFetch: Int) -> Unit): SavedAll {
        refreshDocuments(repositoryId)
        val documents = dao.listedDocuments(repositoryId)
        val missing = documents.filter { document ->
            val saved = dao.note(document.id)
            saved == null || !upToDate(saved, document)
        }
        Log.i(
            "RepoRead",
            "Saving all notes; repositoryId=$repositoryId documents=${documents.size} toFetch=${missing.size}",
        )
        val cannotShow = mutableListOf<String>()
        onProgress(0, missing.size)
        missing.forEachIndexed { index, document ->
            try {
                oneSyncAtATime.withLock { fetchNote(document.id) }
            } catch (error: ApiException) {
                if (error.code != "UNSUPPORTED_CONTENT") {
                    Log.w(
                        "RepoRead",
                        "Saving all notes stopped; repositoryId=$repositoryId fetched=${index - cannotShow.size} documentId=${document.id} code=${error.code}",
                    )
                    throw error
                }
                Log.i("RepoRead", "Note cannot be shown; documentId=${document.id} code=${error.code}")
                cannotShow += document.path
            }
            onProgress(index + 1, missing.size)
        }
        Log.i(
            "RepoRead",
            "Saved all notes; repositoryId=$repositoryId fetched=${missing.size - cannotShow.size} cannotShow=${cannotShow.size}",
        )
        return SavedAll(missing.size - cannotShow.size, documents.size - missing.size, cannotShow)
    }

    /**
     * Which sections changed between [since], the version last read, and [to], the version on screen. Online only;
     * nothing is cached.
     */
    suspend fun changes(documentId: Long, since: String, to: String): Changes {
        val json = api.get("/api/documents/$documentId/changes?since=$since&to=$to")
        return contract {
            val sections = json.getJSONArray("sections")
            Changes(
                json.enumValue<ChangeStatus>("status"),
                json.nullableString("reason"),
                json.getInt("addedLines"),
                json.getInt("removedLines"),
                List(sections.length()) {
                    val item = sections.getJSONObject(it)
                    val path = item.getJSONArray("headingPath")
                    ChangedSection(
                        item.enumValue<SectionChange>("change"),
                        List(path.length()) { index -> path.getString(index) },
                        item.nullableString("blockId"),
                        item.getInt("addedLines"),
                        item.getInt("removedLines"),
                    )
                },
            )
        }
    }

    data class StoredData(
        val documents: Int,
        val readingStates: Int,
        val bookmarks: Int,
        val highlights: Int,
        val cards: Int,
    )

    /**
     * What disconnecting would delete: [stored] on the server, counted after sending what can be sent, and [unsent]
     * highlights, cards, and grades only on this phone (unsent or refused), deleted too; [notSent] says why some were
     * not sent.
     */
    data class DisconnectPreview(val stored: StoredData, val unsent: Int, val notSent: String?)

    /**
     * Sends pending work, then counts what disconnecting [repositoryId] would delete, for the confirmation. Null when
     * the server no longer has the repository (an earlier disconnect whose answer was lost, or another device): this
     * phone's copies are then removed by reconciling with the server's list.
     */
    suspend fun disconnectPreview(repositoryId: Long): DisconnectPreview? = oneSyncAtATime.withLock {
        val synced = sendAndReplace()
        val json =
            try {
                api.get("/api/repositories/$repositoryId/stored-data")
            } catch (error: ApiException) {
                if (error.code != "NOT_FOUND") throw error
                Log.i("RepoRead", "Repository already disconnected on the server; repositoryId=$repositoryId")
                loadRepositories()
                return@withLock null
            }
        val stored = contract {
            StoredData(
                json.getInt("documents"),
                json.getInt("readingStates"),
                json.getInt("bookmarks"),
                json.getInt("highlights"),
                json.getInt("cards"),
            )
        }
        DisconnectPreview(stored, dao.unsentInRepository(repositoryId), synced.notSent?.describe())
    }

    /**
     * Online only: the server deletes the connection and everything stored for it, then this phone deletes its copies,
     * including unsynced changes for those notes. A failure changes nothing on the phone. When the server no longer has
     * the repository (an earlier answer was lost), the phone reconciles with the server's complete list instead.
     */
    suspend fun disconnect(repositoryId: Long) = oneSyncAtATime.withLock {
        val json =
            try {
                api.delete("/api/repositories/$repositoryId")
                    ?: throw ApiException(204, "INVALID_RESPONSE", "RepoRead's server returned an unexpected response.")
            } catch (error: ApiException) {
                if (error.code != "NOT_FOUND") throw error
                Log.i("RepoRead", "Repository already disconnected on the server; repositoryId=$repositoryId")
                loadRepositories()
                return@withLock
            }
        val documentIds = contract {
            json.getJSONArray("documentIds").let { array -> List(array.length()) { array.getLong(it) } }
        }
        dao.forgetRepository(repositoryId, documentIds)
        check(!exportRoot.exists() || exportRoot.deleteRecursively()) {
            "Could not remove private notebook exports at $exportRoot"
        }
        images.delete(documentIds)
        Log.i("RepoRead", "Repository disconnected; repositoryId=$repositoryId documents=${documentIds.size}")
    }

    /** Online only: the server deletes the account and everything it stored; then this phone's copy is cleared. */
    suspend fun deleteAccount() {
        api.delete("/api/account")
        clearAll()
    }

    /** Only the reader calls this, with the version it actually displayed. */
    suspend fun saveReading(row: ReadingRow) {
        dao.saveDisplayedReading(row.copy(pending = true))
        onPendingChange()
    }

    suspend fun setBookmark(documentId: Long, bookmarked: Boolean) {
        dao.saveBookmark(BookmarkRow(documentId, bookmarked, pending = true, System.currentTimeMillis()))
        onPendingChange()
    }

    /**
     * Saves a grade as a pending local mutation; false when its card changed meanwhile and nothing was saved (see
     * recordReview).
     */
    suspend fun recordReview(row: ReviewRow): Boolean = dao.recordReview(row).also { if (it) onPendingChange() }

    /**
     * The sync of local changes, run by library open, pull to refresh, Sync now, and the background sync: sends pending
     * work, then loads the server's complete lists: repositories and every repository's notes (one request each), then
     * reading, bookmark, and notebook rows, which replace the acknowledged local ones. Highlight and card creations
     * need GitHub; when GitHub, or access to it, fails they wait, and the database-only work still runs (ADR-03). A
     * refused item is marked and the run goes on; any other failure ends the run with unacknowledged changes still
     * pending. The notebook, which grows without paging, is read last so its failure cannot hold back the rest.
     */
    suspend fun syncLocalChanges(): LocalSync = oneSyncAtATime.withLock { sendAndReplace() }

    /**
     * The background sync's run: [syncLocalChanges], but only when changes wait to be sent; null when none did. Checked
     * under the lock, so after sign-out has cleared the phone it makes no request and writes nothing.
     */
    suspend fun syncWaitingChanges(): LocalSync? = oneSyncAtATime.withLock {
        if (dao.waitingCount().first() == 0) null else sendAndReplace()
    }

    /** A note named in a message: its title, or what it is when no saved list has it (the server no longer does). */
    private suspend fun noteName(documentId: Long) =
        dao.document(documentId)?.let { "“${it.title}”" } ?: "a note no longer in your lists"

    private suspend fun sendAndReplace(): LocalSync {
        // The one push step whose failure does not end the run: creations need GitHub, so when it fails they wait and
        // the database-only work below still runs (ADR-03).
        val notSent =
            try {
                sendAnnotations()
                null
            } catch (error: ApiException) {
                // Nothing else can reach the server either.
                if (error.status == 0) throw error
                error
            }
        sendReviews()
        val refused = mutableListOf<String>()
        for (row in dao.pendingReading()) {
            val body =
                JSONObject()
                    .put("lastReadBlobSha", row.lastReadBlobSha)
                    .put("progressPercent", row.progressPercent)
                    .put("anchor", JSONObject(row.anchorJson))
                    .put("lastReadAt", Instant.ofEpochMilli(row.lastReadAt).toString())
            val current =
                try {
                    api.put("/api/documents/${row.documentId}/reading-state", body)
                } catch (error: ApiException) {
                    if (!error.refusesItem()) throw error
                    Log.w("RepoRead", "Reading state refused; documentId=${row.documentId} code=${error.code}")
                    // Not this user's note on the server any more: the position goes with it when the lists are
                    // replaced below. Any other refusal keeps the position on this phone, pending, and offers it
                    // again at the next sync.
                    if (error.status == 404) dao.acknowledgeReading(row.documentId, row.lastReadAt)
                    refused += "Your place in ${noteName(row.documentId)} (${error.describe()})"
                    continue
                }
            dao.acknowledgeReading(row.documentId, row.lastReadAt)
            dao.mergeRemoteReading(readingRow(current))
        }
        for (row in dao.pendingBookmarks()) {
            try {
                if (row.bookmarked) {
                    api.put("/api/documents/${row.documentId}/bookmark")
                    dao.acknowledgeBookmarkSet(row.documentId, row.changedAt)
                } else {
                    api.delete("/api/documents/${row.documentId}/bookmark")
                    dao.acknowledgeBookmarkCleared(row.documentId, row.changedAt)
                }
            } catch (error: ApiException) {
                if (!error.refusesItem()) throw error
                Log.w("RepoRead", "Bookmark refused; documentId=${row.documentId} code=${error.code}")
                dao.dropBookmark(row.documentId, row.changedAt)
                refused += "The bookmark on ${noteName(row.documentId)} (${error.describe()})"
            }
        }
        loadRepositories()
        val states = api.get("/api/reading-states")
        val remoteReading = contract {
            states.getJSONArray("readingStates").let { array ->
                List(array.length()) { readingRow(array.getJSONObject(it)) }
            }
        }
        dao.replaceRemoteReading(remoteReading)
        val bookmarks = api.get("/api/bookmarks")
        dao.replaceRemoteBookmarks(
            contract {
                bookmarks.getJSONArray("bookmarks").let { array ->
                    List(array.length()) {
                        val item = array.getJSONObject(it)
                        BookmarkRow(
                            item.getLong("documentId"),
                            bookmarked = true,
                            pending = false,
                            Instant.parse(item.getString("createdAt")).toEpochMilli(),
                        )
                    }
                }
            }
        )
        refreshNotebook()
        // Last, once every list is the server's complete one: copies nothing refers to any more.
        images.delete(dao.forgetUnreferencedNotes())
        return LocalSync(refused, notSent)
    }

    /**
     * Saves a new highlight (or, with [question], a card) locally as a pending creation; it is sent with the returned
     * mutation id until acknowledged.
     */
    suspend fun createAnnotation(passage: Passage, documentId: Long, note: String?, question: String? = null): String {
        checkNotNull(dao.note(documentId)) {
            "Cannot create an annotation without its displayed saved note; documentId=$documentId"
        }
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
        dao.saveAnnotation(row)
        onPendingChange()
        return row.mutationId
    }

    /**
     * Sends pending creations; a failure that is not one item's refusal stops it, and the rest wait, unsent, for the
     * next sync. The same mutation id is replayed until the server answers, so a lost acknowledgement cannot create a
     * duplicate. A refusal is kept on the row and shown; it is not retried.
     */
    suspend fun pushAnnotations() = oneSyncAtATime.withLock { sendAnnotations() }

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
            JSONObject()
                .put("mutationId", row.mutationId)
                .put("type", row.type.name)
                .put("question", row.question ?: JSONObject.NULL)
                .put("note", row.note ?: JSONObject.NULL)
                .put(
                    "anchor",
                    Passage(row.sourceBlobSha, row.blockId, row.startOffset, row.endOffset, row.exactText).anchorJson(),
                )
        val created =
            try {
                api.post("/api/documents/${row.documentId}/annotations", body)
            } catch (error: ApiException) {
                when {
                    error.code == "ANNOTATION_DELETED" || (deleting && error.refusesItem()) ->
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
        return annotationRow(created).also { dao.saveServerAnnotation(it) }
    }

    /**
     * Deletes on the server a creation the user deleted before it was acknowledged; throws a failure that is not this
     * item's.
     */
    private suspend fun sendDeletion(row: AnnotationRow) {
        val serverId = checkNotNull(row.serverId) { "Deleting ${row.mutationId} needs its server id" }
        try {
            api.delete("/api/annotations/$serverId?expectedVersion=${row.version}")
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
    suspend fun refreshAnnotations(documentId: Long) = oneSyncAtATime.withLock {
        sendAnnotations()
        val json = api.get("/api/documents/$documentId/annotations")
        dao.replaceRemoteAnnotations(
            documentId,
            contract {
                json.getJSONArray("annotations").let { array ->
                    List(array.length()) { annotationRow(array.getJSONObject(it)) }
                }
            },
        )
    }

    /** Online only. A 409 ANNOTATION_CONFLICT means another edit landed first; nothing local changes. */
    suspend fun editAnnotation(row: AnnotationRow, note: String?, expectedVersion: Int): AnnotationRow =
        oneSyncAtATime.withLock {
            val serverId =
                checkNotNull(row.serverId) {
                    "Only acknowledged highlights can be edited; ${row.mutationId} is pending"
                }
            val updated =
                annotationRow(
                    api.patch(
                        "/api/annotations/$serverId",
                        JSONObject().put("note", note ?: JSONObject.NULL).put("expectedVersion", expectedVersion),
                    )
                )
            dao.saveAnnotation(updated)
            updated
        }

    /**
     * Online only: places an acknowledged highlight on a [passage] the user selected. A 409 ANNOTATION_CONFLICT means
     * another change landed first; nothing local changes.
     */
    suspend fun reattachAnnotation(row: AnnotationRow, passage: Passage): AnnotationRow = oneSyncAtATime.withLock {
        val serverId =
            checkNotNull(row.serverId) {
                "Only acknowledged highlights can be reattached; ${row.mutationId} is pending"
            }
        val updated =
            annotationRow(
                api.post(
                    "/api/annotations/$serverId/reattach",
                    JSONObject().put("expectedVersion", row.version).put("anchor", passage.anchorJson()),
                )
            )
        dao.saveAnnotation(updated)
        updated
    }

    /**
     * Deletes a highlight or card. An acknowledged one is deleted on the server now (online only; 409 if it changed
     * elsewhere). A creation the server has not acknowledged is marked for deletion on this phone, so it disappears at
     * once and offline too; the next sync replays the creation (the server may have it after a lost acknowledgement)
     * and deletes it there. A refused creation the server never accepted is only local.
     */
    suspend fun deleteAnnotation(row: AnnotationRow) = oneSyncAtATime.withLock {
        when (row.state) {
            AnnotationState.ACKNOWLEDGED -> {
                val serverId = checkNotNull(row.serverId) { "Acknowledged ${row.mutationId} has no server id" }
                try {
                    api.delete("/api/annotations/$serverId?expectedVersion=${row.version}")
                } catch (error: ApiException) {
                    if (error.code != "NOT_FOUND") throw error
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

    /** One database-only read; complete responses replace acknowledged projections, never pending work. */
    private suspend fun refreshNotebook() {
        val json = api.get("/api/notebook")
        val (annotations, reviews, limit) =
            contract {
                val annotations = json.getJSONArray("annotations")
                val reviews = json.getJSONArray("reviews")
                val limit = json.getInt("sessionLimit")
                if (limit <= 0) throw JSONException("Expected a positive server sessionLimit, got $limit")
                Triple(
                    List(annotations.length()) { annotationRow(annotations.getJSONObject(it)) },
                    List(reviews.length()) { reviewRow(reviews.getJSONObject(it)) },
                    limit,
                )
            }
        dao.replaceRemoteNotebook(annotations, reviews, limit)
    }

    suspend fun pushReviews() = oneSyncAtATime.withLock { sendReviews() }

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
                    api.post(
                        "/api/annotations/$id/reviews",
                        JSONObject()
                            .put("mutationId", row.mutationId)
                            .put("grade", row.grade)
                            .put("reviewedAt", Instant.ofEpochMilli(row.reviewedAt).toString())
                            .put("blobSha", row.blobSha),
                    )
                } catch (error: ApiException) {
                    if (!error.refusesItem()) throw error
                    dao.rejectReview(row.mutationId, error.describe())
                    Log.w("RepoRead", "Review refused; cardId=$id mutationId=${row.mutationId} code=${error.code}")
                    continue
                }
            dao.saveReview(reviewRow(remote))
        }
    }

    suspend fun checkCard(row: AnnotationRow, blobSha: String) = oneSyncAtATime.withLock {
        val id = checkNotNull(row.serverId) { "Only acknowledged cards can be confirmed; ${row.mutationId} is pending" }
        dao.saveAnnotation(
            annotationRow(
                api.post(
                    "/api/annotations/$id/check",
                    JSONObject().put("expectedVersion", row.version).put("blobSha", blobSha),
                )
            )
        )
    }

    /**
     * Explicit sign-out removes every cached note, image, and pending change from this phone; a running sync finishes
     * first.
     */
    suspend fun clearAll() = oneSyncAtATime.withLock {
        withContext(Dispatchers.IO) {
            store.clearAllTables()
            images.clear()
            check(!exportRoot.exists() || exportRoot.deleteRecursively()) {
                "Could not remove private notebook exports at $exportRoot"
            }
        }
    }
}
