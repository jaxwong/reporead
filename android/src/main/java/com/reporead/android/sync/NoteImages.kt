package com.reporead.android.sync

import android.util.Log
import com.reporead.android.core.network.Api
import com.reporead.android.core.network.ApiException
import com.reporead.android.data.SavedVersion
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Saved notes' images, kept per document and version (files/images/<document>/<blob>/<key>) and fetched through the
 * backend with the bearer token when missing. Blocking, for WebView request interception, and outside sign-out's lock;
 * [generation] keeps a fetch from writing back what a deletion removed meanwhile. Sync deletes them when their notes
 * go.
 */
class NoteImages(private val api: Api, filesDir: File) {
    private val root = File(filesDir, "images")

    /** Held only to write one fetched image, or to delete; never by a request to the server. */
    private val lock = Any()

    /**
     * Advanced by every deletion, under [lock]. A fetch writes its image only when no deletion ran since it started, so
     * an answer that arrives after a deletion cannot bring a deleted image back. In memory only: a new process starts
     * no fetch before it.
     */
    private var generation = 0L

    /**
     * A repository image for the displayed note version: from the cache, else fetched through the backend with the
     * bearer token (blocking; call from a background thread). Null when it is neither cached nor fetchable.
     */
    fun image(documentId: Long, blobSha: String, path: String): ByteArray? =
        cached(documentId, blobSha, sha256(path), "path=${URLEncoder.encode(path, Charsets.UTF_8)}")

    /** The same for an Obsidian embed by attachment name; the server resolves the name (missing or ambiguous: null). */
    fun embedImage(documentId: Long, blobSha: String, name: String): ByteArray? =
        cached(documentId, blobSha, sha256("embed:$name"), "embed=${URLEncoder.encode(name, Charsets.UTF_8)}")

    private fun cached(documentId: Long, blobSha: String, key: String, query: String): ByteArray? {
        val directory = File(root, "$documentId/$blobSha")
        val file = File(directory, key)
        if (file.isFile) return file.readBytes()
        val started = synchronized(lock) { generation }
        val bytes =
            try {
                api.bytes("/api/documents/$documentId/image?$query")
            } catch (error: ApiException) {
                Log.i("RepoRead", "Image unavailable; documentId=$documentId code=${error.code}")
                return null
            }
        synchronized(lock) {
            // A deletion ran since the request was sent: the page still gets these bytes, but they are not kept.
            if (generation != started) return bytes
            // A partial file of its own, because two pages can fetch the same image at once. A failed write (a full
            // disk) leaves the image uncached; the page still gets these bytes.
            try {
                directory.mkdirs()
                val partial = File.createTempFile(key, ".partial", directory)
                try {
                    partial.writeBytes(bytes)
                    if (!partial.renameTo(file)) throw IOException("Could not publish $partial")
                } finally {
                    partial.delete()
                }
            } catch (error: IOException) {
                Log.w("RepoRead", "Image not cached; documentId=$documentId error=${error.javaClass.simpleName}")
            }
        }
        return bytes
    }

    /**
     * Deletes every saved image but those of [saved], the versions Room holds a page of: other notes' and other
     * versions'. Returns what could not be deleted ([deleteTree]).
     */
    fun sweep(saved: List<SavedVersion>): List<File> = deleting {
        val kept = saved.groupBy({ it.documentId.toString() }, { it.blobSha })
        root.listFiles().orEmpty().flatMap { document ->
            val versions = kept[document.name] ?: return@flatMap deleteTree(document)
            document.listFiles().orEmpty().filter { it.name !in versions }.flatMap(::deleteTree)
        }
    }

    /** Runs [deletion] under [lock] after advancing [generation], so no fetch started before it writes afterwards. */
    private fun deleting(deletion: () -> List<File>): List<File> =
        synchronized(lock) {
            generation++
            deletion()
        }

    private fun sha256(value: String) =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}

/**
 * Deletes [path] and everything under it, and returns what is still there afterwards, deepest first: each file and
 * folder that could not be deleted (in a folder the app may not write, for example). Empty when it is all gone, and
 * when nothing was there, so running it again is how a failed deletion is finished. Returned, not thrown: these
 * deletions follow ones that cannot be undone (the server's, Room's), so the caller reports what is left instead of
 * stopping.
 */
internal fun deleteTree(path: File): List<File> {
    // Its result only says whether everything went; what is left is read back below.
    path.deleteRecursively()
    return path.walkBottomUp().filter { it.exists() }.toList()
}
