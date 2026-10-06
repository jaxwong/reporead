package com.reporead.android.sync

import android.util.Log
import com.reporead.android.core.network.Api
import com.reporead.android.core.network.ApiException
import com.reporead.android.core.network.contract
import com.reporead.android.core.network.describe
import com.reporead.android.data.BookmarkRow
import com.reporead.android.data.DocumentRow
import com.reporead.android.data.LocalStore
import com.reporead.android.data.NoteRow
import com.reporead.android.data.ReadingRow
import com.reporead.android.data.RepositoryRow
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest
import java.time.Instant

/**
 * Owns how the offline cache and the backend meet. Every network operation is explicit and foreground; a failed
 * call ends that operation and leaves the previous complete cache untouched. Nothing here retries.
 */
class Sync(private val api: Api, private val store: LocalStore, filesDir: File) {
    private val dao = store.library()
    private val imageRoot = File(filesDir, "images")

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
                DocumentRow(item.getLong("id"), repositoryId, item.getString("path"), item.getString("title"), item.getString("blobSha"))
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

    /**
     * A cached copy of the document's current version opens without a network call. Otherwise the current version is
     * fetched; if that fails, an older cached copy is shown with the reason, and a missing cache is the failure itself.
     */
    suspend fun openNote(documentId: Long): Opened {
        val cached = dao.note(documentId)
        val current = dao.document(documentId)
        if (cached != null && cached.blobSha == current?.blobSha) return Opened(cached, null)
        val json = try {
            api.get("/api/documents/$documentId/content")
        } catch (error: ApiException) {
            if (cached == null) throw error
            Log.i("RepoRead", "Showing cached note; documentId=$documentId code=${error.code}")
            return Opened(cached, error.describe())
        }
        val note = contract {
            NoteRow(documentId, json.getString("sourceBlobSha"), json.getString("commitSha"), json.getString("path"),
                json.getString("title"), json.getString("html"), System.currentTimeMillis())
        }
        dao.saveNote(note)
        File(imageRoot, documentId.toString()).listFiles()?.filter { it.name != note.blobSha }?.forEach { it.deleteRecursively() }
        return Opened(note, null)
    }

    /** Only the reader calls this, with the version it actually displayed. */
    suspend fun saveReading(row: ReadingRow) = dao.saveReading(row.copy(pending = true))

    suspend fun setBookmark(note: NoteRow, bookmarked: Boolean) =
        dao.saveBookmark(BookmarkRow(note.documentId, note.title, note.path, note.blobSha, bookmarked, pending = true, System.currentTimeMillis()))

    /**
     * Pushes pending reading saves and bookmark toggles, then replaces acknowledged local rows with the server's complete
     * lists. Stops at the first failure; unacknowledged changes stay pending for the next explicit sync.
     */
    suspend fun syncReading() {
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
                        bookmarked = true, pending = false, Instant.parse(item.getString("createdAt")).toEpochMilli())
                }
            }
        })
    }

    private fun readingRow(json: JSONObject) = contract {
        ReadingRow(json.getLong("documentId"), json.getString("title"), json.getString("path"), json.getString("lastReadBlobSha"),
            json.getInt("progressPercent"), json.getJSONObject("anchor").toString(), Instant.parse(json.getString("lastReadAt")).toEpochMilli(),
            pending = false)
    }

    /**
     * A repository image for the displayed note version: from the cache, else fetched through the backend with the
     * bearer token (blocking; call from a background thread). Null when it is neither cached nor fetchable.
     */
    fun image(documentId: Long, blobSha: String, path: String): ByteArray? {
        val file = File(imageRoot, "$documentId/$blobSha/${sha256(path)}")
        if (file.isFile) return file.readBytes()
        val bytes = try {
            api.bytes("/api/documents/$documentId/image?path=${URLEncoder.encode(path, Charsets.UTF_8)}")
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
    }

    private fun sha256(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
