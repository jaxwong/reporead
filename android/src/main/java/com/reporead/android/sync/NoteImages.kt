package com.reporead.android.sync

import android.util.Log
import com.reporead.android.core.network.Api
import com.reporead.android.core.network.ApiException
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Saved notes' images, kept per document and version (files/images/<document>/<blob>/<key>) and fetched through the
 * backend with the bearer token when missing. Blocking, for WebView request interception, and outside sign-out's lock:
 * an image written after a clear is removed at the next one. Sync deletes them when their notes go.
 */
class NoteImages(private val api: Api, filesDir: File) {
    private val root = File(filesDir, "images")

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
        val bytes =
            try {
                api.bytes("/api/documents/$documentId/image?$query")
            } catch (error: ApiException) {
                Log.i("RepoRead", "Image unavailable; documentId=$documentId code=${error.code}")
                return null
            }
        // A partial file of its own, because two pages can fetch the same image at once. A failed write (a full disk,
        // or keepOnly removing the folder meanwhile) leaves the image uncached; the page still gets these bytes.
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
        return bytes
    }

    /** Deletes every saved image of [documentIds]. */
    fun delete(documentIds: List<Long>) {
        for (id in documentIds) File(root, id.toString()).let {
            check(!it.exists() || it.deleteRecursively()) { "Could not remove saved images at $it" }
        }
    }

    /** Deletes [documentId]'s images of every version but [blobSha], once that version's page is saved. */
    fun keepOnly(documentId: Long, blobSha: String) {
        File(root, documentId.toString())
            .listFiles()
            ?.filter { it.name != blobSha }
            ?.forEach { old ->
                check(old.deleteRecursively()) { "Could not remove images of an older version at $old" }
            }
    }

    /** Deletes every saved image (sign-out). */
    fun clear() {
        check(!root.exists() || root.deleteRecursively()) { "Could not remove saved images at $root" }
    }

    private fun sha256(value: String) =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
