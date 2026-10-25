// Owns: Snapshot-keyed image files; Api owns authenticated HTTP fetching.

package com.reporead.android.sync

import androidx.core.net.toUri
import com.reporead.android.database.SavedPageEntity
import com.reporead.android.database.SavedVersion
import com.reporead.android.logging.DeviceLog
import com.reporead.android.network.Api
import com.reporead.android.network.ApiException
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.jsoup.Jsoup

internal const val IMAGE_PREFIX = "/repo-image/"

/**
 * Saved pages' images, kept per document and snapshot (files/images/<document>/commit-<sha>/<key>) and fetched through
 * the backend with the bearer token when missing. WebView request interception is blocking and outside PhoneSync's
 * lock; Save all uses its per-page lock. [deletionCount] keeps a fetch from writing back what a deletion removed
 * meanwhile. PhoneSync deletes them when their notes go.
 */
class SavedPageImages(private val api: Api, filesDir: File) {
    private val root = File(filesDir, "images")

    /** Held for a cached read, publishing one fetched image, or deletion; never for an HTTP request. */
    private val lock = Any()

    /**
     * Advanced by every deletion, under [lock]. A fetch writes its image only when no deletion ran since it started, so
     * an answer that arrives after a deletion cannot bring a deleted image back. In memory only: a new process starts
     * no fetch before it.
     */
    private var deletionCount = 0L

    /**
     * Save all's image pass, on a background thread under PhoneSync's per-page lock so the saved snapshot cannot be
     * replaced or disconnected meanwhile. The server emits at most 64 image references; each distinct path is tried
     * once, sequentially. API failures propagate to PhoneSync, which owns their disposition for this run.
     */
    internal suspend fun save(note: SavedPageEntity) {
        if (note.snapshotCommitSha == null) return
        val paths =
            Jsoup.parse(note.html)
                .select("img[src^=$IMAGE_PREFIX]")
                .map { image ->
                    image.attr("src").toUri().path!!.removePrefix(IMAGE_PREFIX)
                }
                .distinct()
        for (path in paths) {
            currentCoroutineContext().ensureActive()
            fetch(note.documentId, note.snapshotCommitSha, path)
        }
    }

    /**
     * A repository image for the displayed note version: from the cache, else fetched through the backend with the
     * bearer token (blocking; call from a background thread). Null when it is neither cached nor fetchable.
     */
    fun image(documentId: Long, snapshotCommitSha: String?, path: String): ByteArray? =
        try {
            fetch(documentId, snapshotCommitSha, path)
        } catch (failure: ApiException) {
            DeviceLog.info("Image unavailable; documentId=$documentId code=${failure.code}")
            null
        }

    private fun fetch(documentId: Long, snapshotCommitSha: String?, path: String): ByteArray? {
        // Upgraded pages retain their text, but cannot honestly identify any image until fetched again.
        if (snapshotCommitSha == null) return null
        val key = sha256(path)
        val directory = File(root, "$documentId/${snapshotDirectoryName(snapshotCommitSha)}")
        val file = File(directory, key)
        val deletionCountAtStart =
            synchronized(lock) {
                try {
                    if (file.isFile) return file.readBytes()
                } catch (ioFailure: IOException) {
                    DeviceLog.warn(
                        "Cached image unreadable; documentId=$documentId snapshot=$snapshotCommitSha",
                        ioFailure,
                    )
                    return null
                }
                deletionCount
            }
        val bytes = api.noteImage(documentId, snapshotCommitSha, path)
        synchronized(lock) {
            // A deletion ran since the request was sent: the page still gets these bytes, but they are not kept.
            if (deletionCount != deletionCountAtStart) return bytes
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
            } catch (ioFailure: IOException) {
                DeviceLog.warn("Image not cached; documentId=$documentId error=${ioFailure.javaClass.simpleName}")
            }
        }
        return bytes
    }

    /**
     * Deletes every saved image but those of [saved], the versions Room holds a page of: other notes' and other
     * versions'. Returns what could not be deleted ([deleteTree]).
     */
    fun sweep(saved: List<SavedVersion>): List<File> = deleting {
        // Legacy pages have no provable image snapshot: their old blob-keyed cache is retired, not read as a fallback.
        val kept =
            saved
                .mapNotNull { page ->
                    page.snapshotCommitSha?.let { page.documentId.toString() to snapshotDirectoryName(it) }
                }
                .groupBy({ it.first }, { it.second })
        root.listFiles().orEmpty().flatMap { document ->
            val versions = kept[document.name] ?: return@flatMap deleteTree(document)
            document.listFiles().orEmpty().filter { it.name !in versions }.flatMap(::deleteTree)
        }
    }

    /**
     * Runs [deletion] under [lock] after advancing [deletionCount], so no fetch started before it writes afterwards.
     */
    private fun deleting(deletion: () -> List<File>): List<File> =
        synchronized(lock) {
            deletionCount++
            deletion()
        }

    private fun sha256(value: String) =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    /** Disjoint from the retired bare blob-SHA folders, even if two supplied SHA strings happen to be equal. */
    private fun snapshotDirectoryName(commitSha: String) = "commit-$commitSha"
}

/**
 * Deletes [path] and everything under it, and returns what is still there afterwards, deepest first: each file and
 * folder that could not be deleted (in a folder the app may not write, for example). Empty when it is all gone, and
 * when nothing was there, so running it again is how a failed deletion is finished. Returned, not thrown: these
 * deletions follow ones that cannot be undone (the server's, Room's), so the caller reports what is left instead of
 * stopping.
 */
private fun deleteTree(path: File): List<File> {
    // Its result only says whether everything went; what is left is read back below.
    path.deleteRecursively()
    return path.walkBottomUp().filter { it.exists() }.toList()
}
