package com.reporead.android.sync

import android.util.Log
import com.reporead.android.core.network.Api
import com.reporead.android.core.network.ApiException
import com.reporead.android.core.network.contract
import com.reporead.android.core.network.describe
import com.reporead.android.data.AnnotationRow
import com.reporead.android.data.BookmarkRow
import com.reporead.android.data.DocumentRow
import com.reporead.android.data.LocalStore
import com.reporead.android.data.NoteRow
import com.reporead.android.data.Passage
import com.reporead.android.data.ReadingRow
import com.reporead.android.data.RepositoryRow
import com.reporead.android.data.ReviewRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/** A changed section: ADDED, CHANGED, or REMOVED; empty [headingPath] is the beginning of the note; [blockId] is in the newer version. */
data class ChangedSection(val change: String, val headingPath: List<String>, val blockId: String?, val addedLines: Int, val removedLines: Int)

/** The server's comparison: [status] CHANGED (with [sections]), UNCHANGED, SINCE_UNAVAILABLE (with [reason]), or TOO_LARGE. */
data class Changes(val status: String, val reason: String?, val addedLines: Int, val removedLines: Int, val sections: List<ChangedSection>)

/**
 * Owns how the offline cache and the backend meet. Every network operation is explicit and foreground; a failed
 * call ends that operation and leaves the previous complete cache untouched. Nothing here retries.
 */
/** The page format this app needs from the server (MarkdownRenderer.FORMAT): 2 adds note links and Obsidian embeds, 3 footnotes. */
const val RENDER_FORMAT = 3

class Sync(private val api: Api, private val store: LocalStore, filesDir: File) {
    private val dao = store.library()
    private val imageRoot = File(filesDir, "images")
    private val exportRoot = File(filesDir, "exports")

    private fun JSONObject.nullableString(name: String): String? = if (isNull(name)) null else getString(name)

    suspend fun refreshRepositories() {
        val json = api.get("/api/repositories")
        val rows = contract {
            val array = json.getJSONArray("repositories")
            List(array.length()) {
                val item = array.getJSONObject(it)
                RepositoryRow(item.getLong("id"), item.getString("fullName"), item.getInt("documentCount"), item.nullableString("lastSyncedCommitSha"))
            }
        }
        dao.replaceRepositories(rows)
    }

    suspend fun refreshDocuments(repositoryId: Long) {
        val json = api.get("/api/repositories/$repositoryId/documents")
        val rows = contract {
            val array = json.getJSONArray("documents")
            List(array.length()) {
                val item = array.getJSONObject(it)
                DocumentRow(item.getLong("id"), repositoryId, item.getString("path"), item.getString("title"), item.getString("blobSha"),
                    item.nullableString("contentChangedAt")?.let { Instant.parse(it).toEpochMilli() })
            }
        }
        dao.replaceDocuments(repositoryId, rows)
    }

    /** Asks the backend to reconcile with GitHub, then reloads the now-current lists. */
    suspend fun refreshFromGitHub(repositoryId: Long) {
        api.post("/api/repositories/$repositoryId/sync")
        refreshDocuments(repositoryId)
        refreshRepositories()
    }

    data class Opened(val note: NoteRow, val staleReason: String?)

    /** The saved copy is [document]'s current version: same blob and same path (the page shows the path and resolves relative images against it). */
    private fun sameVersion(cached: NoteRow?, document: DocumentRow?) =
        cached != null && document != null && cached.blobSha == document.blobSha && cached.path == document.path

    /** The saved copy can be shown without a network call: the current version, in the page format this app needs. */
    private fun upToDate(cached: NoteRow?, document: DocumentRow?) = sameVersion(cached, document) && cached!!.renderFormat >= RENDER_FORMAT

    /**
     * A cached copy of the document's current version and path opens without a network call. Otherwise the current
     * version is fetched; if that fails, an older cached copy is shown with the reason, and a missing cache is the
     * failure itself. A current copy saved in an older page format (without search text, note links, embeds, or
     * footnotes) is fetched once more; if that fails it is still the current version, so it opens without a warning.
     */
    suspend fun openNote(documentId: Long): Opened {
        val cached = dao.note(documentId)
        val current = dao.document(documentId)
        if (upToDate(cached, current)) return Opened(cached!!, null)
        return try {
            Opened(fetchNote(documentId), null)
        } catch (error: ApiException) {
            if (cached == null) throw error
            val cachedIsCurrent = sameVersion(cached, current)
            Log.i("RepoRead", "Showing cached note; documentId=$documentId code=${error.code} current=$cachedIsCurrent")
            Opened(cached, if (cachedIsCurrent) null else error.describe())
        }
    }

    /** Fetches and saves the note's current version (one GitHub call on the server); the copy is saved whole or not at all. */
    private suspend fun fetchNote(documentId: Long): NoteRow {
        val json = api.get("/api/documents/$documentId/content")
        val note = contract {
            NoteRow(documentId, json.getString("sourceBlobSha"), json.getString("commitSha"), json.getString("path"),
                json.getString("title"), json.getString("html"), System.currentTimeMillis(), json.getString("text"), json.getInt("renderFormat"))
        }
        dao.saveNote(note)
        File(imageRoot, documentId.toString()).listFiles()?.filter { it.name != note.blobSha }?.forEach { it.deleteRecursively() }
        return note
    }

    /** [fetched] new or updated copies; [alreadySaved] were current; [cannotShow] are the paths the server refused to render (too large). */
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
        val documents = dao.documentsOnce(repositoryId)
        val missing = documents.filter { !upToDate(dao.note(it.id), it) }
        Log.i("RepoRead", "Saving all notes; repositoryId=$repositoryId documents=${documents.size} toFetch=${missing.size}")
        val cannotShow = mutableListOf<String>()
        onProgress(0, missing.size)
        missing.forEachIndexed { index, document ->
            try {
                fetchNote(document.id)
            } catch (error: ApiException) {
                if (error.code != "UNSUPPORTED_CONTENT") {
                    Log.w("RepoRead", "Saving all notes stopped; repositoryId=$repositoryId fetched=${index - cannotShow.size} documentId=${document.id} code=${error.code}")
                    throw error
                }
                Log.i("RepoRead", "Note cannot be shown; documentId=${document.id} code=${error.code}")
                cannotShow += document.path
            }
            onProgress(index + 1, missing.size)
        }
        Log.i("RepoRead", "Saved all notes; repositoryId=$repositoryId fetched=${missing.size - cannotShow.size} cannotShow=${cannotShow.size}")
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
            val status = json.getString("status")
            if (status !in setOf("CHANGED", "UNCHANGED", "SINCE_UNAVAILABLE", "TOO_LARGE")) throw JSONException("Unknown change status $status")
            Changes(status, json.nullableString("reason"), json.getInt("addedLines"), json.getInt("removedLines"),
                List(sections.length()) {
                    val item = sections.getJSONObject(it)
                    val path = item.getJSONArray("headingPath")
                    ChangedSection(item.getString("change"), List(path.length()) { index -> path.getString(index) },
                        item.nullableString("blockId"), item.getInt("addedLines"), item.getInt("removedLines"))
                })
        }
    }

    data class StoredData(val documents: Int, val readingStates: Int, val bookmarks: Int, val highlights: Int, val cards: Int)

    /** What disconnecting [repositoryId] would delete on the server, for the confirmation. */
    suspend fun storedData(repositoryId: Long): StoredData {
        val json = api.get("/api/repositories/$repositoryId/stored-data")
        return contract { StoredData(json.getInt("documents"), json.getInt("readingStates"), json.getInt("bookmarks"), json.getInt("highlights"), json.getInt("cards")) }
    }

    /**
     * Online only: the server deletes the connection and everything stored for it, then this phone deletes its copies,
     * including unsynced changes for those notes. A failure changes nothing on the phone.
     */
    suspend fun disconnect(repositoryId: Long) {
        val json = api.delete("/api/repositories/$repositoryId") ?: throw ApiException(204, "INVALID_RESPONSE", "RepoRead's server returned an unexpected response.")
        val documentIds = contract { json.getJSONArray("documentIds").let { array -> List(array.length()) { array.getLong(it) } } }
        dao.forgetRepository(repositoryId, documentIds)
        check(!exportRoot.exists() || exportRoot.deleteRecursively()) { "Could not remove private notebook exports at $exportRoot" }
        for (id in documentIds) File(imageRoot, id.toString()).deleteRecursively()
        Log.i("RepoRead", "Repository disconnected; repositoryId=$repositoryId documents=${documentIds.size}")
    }

    /** Online only: the server deletes the account and everything it stored; then this phone's copy is cleared. */
    suspend fun deleteAccount() {
        api.delete("/api/account")
        withContext(Dispatchers.IO) { clearAll() }
    }

    /** Only the reader calls this, with the version it actually displayed. */
    suspend fun saveReading(row: ReadingRow) = dao.saveReading(row.copy(pending = true))

    suspend fun setBookmark(note: NoteRow, bookmarked: Boolean) =
        dao.saveBookmark(BookmarkRow(note.documentId, note.title, note.path, note.blobSha, bookmarked, pending = true, System.currentTimeMillis()))

    /**
     * Pushes pending highlight creations, reading saves, and bookmark toggles, then replaces acknowledged local reading
     * and bookmark rows with the server's complete lists. Stops at the first failure; unacknowledged changes stay pending
     * for the next explicit sync.
     */
    suspend fun syncLocalChanges() {
        pushAnnotations()
        pushReviews()
        refreshNotebook()
        for (row in dao.pendingReading()) {
            val body = JSONObject().put("lastReadBlobSha", row.lastReadBlobSha).put("progressPercent", row.progressPercent)
                .put("anchor", JSONObject(row.anchorJson)).put("lastReadAt", Instant.ofEpochMilli(row.lastReadAt).toString())
            val current = try {
                api.put("/api/documents/${row.documentId}/reading-state", body)
            } catch (error: ApiException) {
                if (error.status != 404) throw error
                // The document is no longer this user's on the server; the local history stays, but cannot be sent.
                Log.w("RepoRead", "Reading state rejected as not found; documentId=${row.documentId}")
                dao.acknowledgeReading(row.documentId, row.lastReadAt)
                continue
            }
            dao.acknowledgeReading(row.documentId, row.lastReadAt)
            dao.mergeRemoteReading(readingRow(current))
        }
        for (row in dao.pendingBookmarks()) {
            try {
                if (row.bookmarked) {
                    api.put("/api/documents/${row.documentId}/bookmark", JSONObject().put("sourceBlobSha", row.sourceBlobSha))
                    dao.acknowledgeBookmarkSet(row.documentId, row.changedAt)
                } else {
                    api.delete("/api/documents/${row.documentId}/bookmark")
                    dao.acknowledgeBookmarkCleared(row.documentId, row.changedAt)
                }
            } catch (error: ApiException) {
                if (error.status != 404) throw error
                Log.w("RepoRead", "Bookmark rejected as not found; documentId=${row.documentId}")
                dao.dropBookmark(row.documentId, row.changedAt)
            }
        }
        val states = api.get("/api/reading-states")
        val remoteReading = contract { states.getJSONArray("readingStates").let { array -> List(array.length()) { readingRow(array.getJSONObject(it)) } } }
        remoteReading.forEach { dao.mergeRemoteReading(it) }
        val bookmarks = api.get("/api/bookmarks")
        dao.replaceRemoteBookmarks(contract {
            bookmarks.getJSONArray("bookmarks").let { array ->
                List(array.length()) {
                    val item = array.getJSONObject(it)
                    BookmarkRow(item.getLong("documentId"), item.getString("title"), item.getString("path"), item.getString("sourceBlobSha"),
                        bookmarked = true, pending = false, Instant.parse(item.getString("createdAt")).toEpochMilli(),
                        deleted = item.getBoolean("deleted"))
                }
            }
        })
    }

    /** Saves a new highlight locally as a pending creation; it is sent with this mutation id until acknowledged. */
    suspend fun createAnnotation(selection: JSONObject, documentId: Long, note: String?, question: String? = null) {
        val saved = checkNotNull(dao.note(documentId)) { "Cannot create an annotation without its displayed saved note; documentId=$documentId" }
        val current = dao.document(documentId)
        val row = contract {
            AnnotationRow(UUID.randomUUID().toString(), null, documentId, selection.getString("sourceBlobSha"), selection.getString("blockId"),
                selection.getInt("startOffset"), selection.getInt("endOffset"), selection.getString("exactText"), note, 1,
                System.currentTimeMillis(), pending = true, rejection = null,
                type = if (question == null) "HIGHLIGHT" else "CARD", question = question,
                checkedBlobSha = if (question == null) null else selection.getString("sourceBlobSha"),
                cachedTitle = saved.title, cachedPath = saved.path, cachedCurrentBlobSha = current?.blobSha)
        }
        dao.saveAnnotation(row)
    }

    /**
     * Sends pending creations. The same mutation id is replayed until the server answers, so a lost acknowledgement
     * cannot create a duplicate. A refusal is kept on the row and shown; it is not retried.
     */
    suspend fun pushAnnotations() {
        for (row in dao.pendingAnnotations()) {
            val body = JSONObject().put("mutationId", row.mutationId).put("type", row.type).put("question", row.question ?: JSONObject.NULL)
                .put("note", row.note ?: JSONObject.NULL).put("anchor",
                JSONObject().put("sourceBlobSha", row.sourceBlobSha).put("blockId", row.blockId).put("startOffset", row.startOffset)
                    .put("endOffset", row.endOffset).put("exactText", row.exactText))
            val created = try {
                api.post("/api/documents/${row.documentId}/annotations", body)
            } catch (error: ApiException) {
                when (error.code) {
                    "ANNOTATION_DELETED" -> dao.removeAnnotation(row.mutationId)
                    "INVALID_ANCHOR", "INVALID_ANNOTATION", "MUTATION_ID_REUSED", "NOT_FOUND" -> dao.rejectAnnotation(row.mutationId, error.describe())
                    else -> throw error
                }
                Log.w("RepoRead", "Highlight creation refused; documentId=${row.documentId} code=${error.code}")
                continue
            }
            dao.saveAnnotation(annotationRow(created))
        }
    }

    /** Pending creations are sent first, so the server's list already contains them where possible. */
    suspend fun refreshAnnotations(documentId: Long) {
        pushAnnotations()
        val json = api.get("/api/documents/$documentId/annotations")
        dao.replaceRemoteAnnotations(documentId, contract {
            json.getJSONArray("annotations").let { array -> List(array.length()) { annotationRow(array.getJSONObject(it)) } }
        })
    }

    /** Online only. A 409 ANNOTATION_CONFLICT means another edit landed first; nothing local changes. */
    suspend fun editAnnotation(row: AnnotationRow, note: String?, expectedVersion: Int): AnnotationRow {
        val serverId = checkNotNull(row.serverId) { "Only acknowledged highlights can be edited; ${row.mutationId} is pending" }
        val updated = annotationRow(api.patch("/api/annotations/$serverId",
            JSONObject().put("note", note ?: JSONObject.NULL).put("expectedVersion", expectedVersion)))
        dao.saveAnnotation(updated)
        return updated
    }

    /**
     * Online only: places an acknowledged highlight on a selection the user made. A 409 ANNOTATION_CONFLICT means another
     * change landed first; nothing local changes.
     */
    suspend fun reattachAnnotation(row: AnnotationRow, selection: JSONObject): AnnotationRow {
        val serverId = checkNotNull(row.serverId) { "Only acknowledged highlights can be reattached; ${row.mutationId} is pending" }
        val updated = annotationRow(api.post("/api/annotations/$serverId/reattach",
            JSONObject().put("expectedVersion", row.version).put("anchor", selection)))
        dao.saveAnnotation(updated)
        return updated
    }

    /** Online for acknowledged highlights; a pending or refused creation the server never accepted is only local. */
    suspend fun deleteAnnotation(row: AnnotationRow) {
        val serverId = row.serverId
        if (serverId != null) {
            try {
                api.delete("/api/annotations/$serverId?expectedVersion=${row.version}")
            } catch (error: ApiException) {
                if (error.status != 404) throw error
            }
        } else {
            check(row.pending) { "Highlight ${row.mutationId} has no server id but is not pending" }
        }
        dao.removeAnnotation(row.mutationId)
    }

    /** One database-only read; complete responses replace acknowledged projections, never pending work. */
    suspend fun refreshNotebook() {
        val json = api.get("/api/notebook")
        val (annotations, reviews, limit) = contract {
            val annotations = json.getJSONArray("annotations")
            val reviews = json.getJSONArray("reviews")
            val limit = json.getInt("sessionLimit")
            if (limit <= 0) throw JSONException("Expected a positive server sessionLimit, got $limit")
            Triple(List(annotations.length()) { annotationRow(annotations.getJSONObject(it)) },
                List(reviews.length()) { reviewRow(reviews.getJSONObject(it)) }, limit)
        }
        dao.replaceRemoteNotebook(annotations, reviews, limit)
    }

    suspend fun pushReviews() {
        for (row in dao.pendingReviews()) {
            val card = dao.annotation(row.cardMutationId)
            if (card == null) error("Review ${row.mutationId} references missing local card ${row.cardMutationId}")
            if (card.rejection != null) {
                dao.rejectReview(row.mutationId, "Card creation was refused: ${card.rejection}")
                continue
            }
            val id = checkNotNull(card.serverId) { "Push annotations before reviews; card ${card.mutationId} is pending" }
            val remote = try {
                api.post("/api/annotations/$id/reviews", JSONObject().put("mutationId", row.mutationId).put("grade", row.grade)
                    .put("reviewedAt", Instant.ofEpochMilli(row.reviewedAt).toString()).put("blobSha", row.blobSha))
            } catch (error: ApiException) {
                if (error.code !in setOf("CARD_CHANGED", "MUTATION_ID_REUSED", "INVALID_REVIEW", "NOT_FOUND")) throw error
                dao.rejectReview(row.mutationId, error.describe())
                Log.w("RepoRead", "Review refused; cardId=$id mutationId=${row.mutationId} code=${error.code}")
                continue
            }
            dao.saveReview(reviewRow(remote))
        }
    }

    suspend fun checkCard(row: AnnotationRow, blobSha: String) {
        val id = checkNotNull(row.serverId) { "Only acknowledged cards can be confirmed; ${row.mutationId} is pending" }
        dao.saveAnnotation(annotationRow(api.post("/api/annotations/$id/check",
            JSONObject().put("expectedVersion", row.version).put("blobSha", blobSha))))
    }

    private fun reviewRow(json: JSONObject) = contract {
        val grade = json.getInt("grade")
        if (grade !in 0..5) throw JSONException("Invalid review grade $grade")
        ReviewRow(json.getString("mutationId"), json.getString("cardMutationId"), grade,
            Instant.parse(json.getString("reviewedAt")).toEpochMilli(), json.getString("blobSha"), pending = false)
    }

    private fun annotationRow(json: JSONObject) = contract {
        val anchor = json.getJSONObject("anchor")
        val location = json.getJSONObject("location")
        AnnotationRow(json.getString("mutationId"), json.getLong("id"), json.getLong("documentId"), anchor.getString("sourceBlobSha"),
            anchor.getString("blockId"), anchor.getInt("startOffset"), anchor.getInt("endOffset"), anchor.getString("exactText"),
            json.nullableString("note"), json.getInt("version"), Instant.parse(json.getString("createdAt")).toEpochMilli(),
            pending = false, rejection = null, prefixText = anchor.getString("prefixText"), suffixText = anchor.getString("suffixText"),
            status = json.getString("status"), resolvedBlobSha = json.getString("resolvedBlobSha"),
            location = Passage(location.getString("sourceBlobSha"), location.getString("blockId"), location.getInt("startOffset"),
                location.getInt("endOffset"), location.getString("exactText")),
            type = json.getString("type"), question = json.nullableString("question"), checkedBlobSha = json.nullableString("checkedBlobSha"),
            cachedTitle = json.getString("title"), cachedPath = json.getString("path"),
            cachedCurrentBlobSha = json.getString("currentBlobSha"), cachedDeleted = json.getBoolean("deleted"))
    }

    private fun readingRow(json: JSONObject) = contract {
        ReadingRow(json.getLong("documentId"), json.getString("title"), json.getString("path"), json.getString("lastReadBlobSha"),
            json.getInt("progressPercent"), json.getJSONObject("anchor").toString(), Instant.parse(json.getString("lastReadAt")).toEpochMilli(),
            pending = false, deleted = json.getBoolean("deleted"))
    }

    /**
     * A repository image for the displayed note version: from the cache, else fetched through the backend with the
     * bearer token (blocking; call from a background thread). Null when it is neither cached nor fetchable.
     */
    fun image(documentId: Long, blobSha: String, path: String): ByteArray? =
        cachedImage(documentId, blobSha, sha256(path), "path=${URLEncoder.encode(path, Charsets.UTF_8)}")

    /** The same for an Obsidian embed by attachment name; the server resolves the name (missing or ambiguous: null). */
    fun embedImage(documentId: Long, blobSha: String, name: String): ByteArray? =
        cachedImage(documentId, blobSha, sha256("embed:$name"), "embed=${URLEncoder.encode(name, Charsets.UTF_8)}")

    private fun cachedImage(documentId: Long, blobSha: String, key: String, query: String): ByteArray? {
        val file = File(imageRoot, "$documentId/$blobSha/$key")
        if (file.isFile) return file.readBytes()
        val bytes = try {
            api.bytes("/api/documents/$documentId/image?$query")
        } catch (error: ApiException) {
            Log.i("RepoRead", "Image unavailable; documentId=$documentId code=${error.code}")
            return null
        }
        file.parentFile!!.mkdirs()
        val partial = File(file.parentFile, file.name + ".partial")
        partial.writeBytes(bytes)
        check(partial.renameTo(file)) { "Could not publish cached image for document $documentId" }
        return bytes
    }

    /** Explicit sign-out removes every cached note, image, and pending change from this phone. */
    fun clearAll() {
        store.clearAllTables()
        imageRoot.deleteRecursively()
        check(!exportRoot.exists() || exportRoot.deleteRecursively()) { "Could not remove private notebook exports at $exportRoot" }
    }

    private fun sha256(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
