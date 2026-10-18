package com.reporead.android

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import com.reporead.android.auth.StoredSession
import com.reporead.android.core.network.ApiException
import com.reporead.android.data.AnnotationState
import com.reporead.android.data.Passage
import com.reporead.android.data.ReviewRow
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in only: real dev session/backend/GitHub, writing labelled annotations on an existing scratch/ note only. */
class LiveApiContractTest {
    private fun app(): RepoReadApplication {
        assumeTrue(
            "Live writes require explicit scratch-note approval",
            InstrumentationRegistry.getArguments().getString("liveApiContract") == "true",
        )
        check(BuildConfig.DEBUG) { "Live contract checks must use RepoRead Dev" }
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as RepoReadApplication
        check(app.sessionStore.read() is StoredSession.Readable) { "RepoRead Dev needs its existing signed-in session" }
        return app
    }

    @Test
    fun scratchNoteExercisesRealReadsMutationsConflictsAndCleanup() = runBlocking {
        val app = app()
        val sync = app.account.sync
        val dao = app.localStore.library()
        check(
            dao.waitingAnnotations().isEmpty() &&
                dao.pendingReviews().isEmpty() &&
                dao.pendingReading().isEmpty() &&
                dao.pendingBookmarks().isEmpty()
        ) {
            "Do not send unrelated pending user work for a test"
        }
        sync.refreshLibrary()
        val library = app.api.library()
        val target =
            library.repositories.flatMap { it.documents }.firstOrNull { !it.deleted && it.path.startsWith("scratch/") }
                ?: error("No real scratch/ note exists; do not fabricate or push one")
        val repository = library.repositories.single { repo -> repo.documents.any { it.id == target.id } }
        app.api.availableRepositories()
        sync.refreshFromGitHub(repository.id)
        val content = app.api.noteContent(target.id)
        val opened = sync.openNote(target.id)
        assertEquals(content.sourceBlobSha, opened.note.blobSha)
        val block = checkNotNull(Jsoup.parse(content.html).selectFirst("[data-block-id][data-anchor-text]"))
        val text =
            block.attr("data-anchor-text").take(60).let {
                if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it
            }
        check(text.isNotBlank()) { "The scratch note needs a selectable canonical block" }
        val passage = Passage(content.sourceBlobSha, block.attr("data-block-id"), 0, text.length, text)
        val created = mutableListOf<String>()
        val wasBookmarked = dao.bookmark(target.id).first()?.bookmarked == true
        try {
            val highlight = checkNotNull(sync.createAnnotation(passage, target.id, "TEST_ONLY_API_CONTRACT"))
            created += highlight
            sync.pushAnnotations()
            val row = checkNotNull(dao.annotation(highlight))
            assertEquals(AnnotationState.ACKNOWLEDGED, row.state)
            val updated = sync.editAnnotation(row, "TEST_ONLY_API_CONTRACT_EDITED", row.version)
            val conflict =
                runCatching { sync.editAnnotation(updated, "TEST_ONLY_MUST_NOT_APPLY", row.version) }.exceptionOrNull()
                    as ApiException
            assertEquals(409, conflict.status)
            assertEquals("ANNOTATION_CONFLICT", conflict.code)
            assertEquals(updated.version, dao.annotation(highlight)?.version)
            sync.reattachAnnotation(updated, passage)
            sync.refreshAnnotations(target.id)
            assertEquals(1, app.api.annotations(target.id).annotations.count { it.mutationId == highlight })
            val cardId =
                checkNotNull(
                    sync.createAnnotation(
                        passage,
                        target.id,
                        "TEST_ONLY_API_CONTRACT_CARD",
                        "Test-only API contract question?",
                    )
                )
            created += cardId
            sync.pushAnnotations()
            sync.checkCard(checkNotNull(dao.annotation(cardId)), content.sourceBlobSha)
            val grade =
                ReviewRow(
                    UUID.randomUUID().toString(),
                    cardId,
                    4,
                    System.currentTimeMillis(),
                    content.sourceBlobSha,
                    true,
                )
            assertTrue(sync.recordReview(grade))
            sync.syncWaitingChanges()
            assertTrue(dao.pendingReviews().none { it.mutationId == grade.mutationId })
            val changes = app.api.changes(target.id, content.sourceBlobSha, content.sourceBlobSha)
            assertTrue(changes.sections.isEmpty())
            val missing = runCatching { app.api.noteContent(Long.MAX_VALUE) }.exceptionOrNull() as ApiException
            assertEquals(404, missing.status)
            assertEquals("NOT_FOUND", missing.code)
            val counts = app.api.storedData(repository.id)
            assertTrue(counts.documents > 0)
            if (!wasBookmarked) {
                sync.setBookmark(target.id, true)
                sync.syncWaitingChanges()
                assertEquals(false, dao.bookmark(target.id).first()?.pending)
            }
            sync.refreshLibrary()
            assertEquals(opened.note.blobSha, sync.openNote(target.id).note.blobSha)
        } finally {
            // Only ids created by this test are removed; existing highlights/cards/bookmarks are never swept.
            for (id in created.reversed()) dao.annotation(id)?.let { sync.deleteAnnotation(it) }
            if (!wasBookmarked && dao.bookmark(target.id).first()?.bookmarked == true) {
                sync.setBookmark(target.id, false)
                sync.syncWaitingChanges()
            }
        }
        assertTrue(created.all { dao.annotation(it) == null })
        assertTrue(app.api.annotations(target.id).annotations.none { it.mutationId in created })
        InstrumentationRegistry.getInstrumentation()
            .sendStatus(
                0,
                Bundle().apply {
                    putString("scratchDocumentId", target.id.toString())
                    putString("result", "Live reads, mutations, conflict and scratch-only cleanup passed")
                },
            )
    }

    @Test
    fun anUnreachableBackendKeepsACachedScratchNoteAndItsNewHighlightPending() = runBlocking {
        val app = app()
        val id = checkNotNull(InstrumentationRegistry.getArguments().getString("scratchDocumentId")).toLong()
        val dao = app.localStore.library()
        val doc = checkNotNull(dao.document(id))
        check(doc.path.startsWith("scratch/"))
        val before = checkNotNull(dao.note(id))
        val error = runCatching { app.api.library() }.exceptionOrNull() as ApiException
        assertEquals("BACKEND_UNAVAILABLE", error.code)
        assertEquals(before.blobSha, app.account.sync.openNote(id).note.blobSha)
        val block = checkNotNull(Jsoup.parse(before.html).selectFirst("[data-block-id][data-anchor-text]"))
        val text = block.attr("data-anchor-text")
        val mutation =
            checkNotNull(
                app.account.sync.createAnnotation(
                    Passage(before.blobSha, block.attr("data-block-id"), 0, text.length, text),
                    id,
                    "TEST_ONLY_API_CONTRACT_OFFLINE",
                )
            )
        assertEquals(AnnotationState.UNSENT, dao.annotation(mutation)?.state)
        InstrumentationRegistry.getInstrumentation()
            .sendStatus(0, Bundle().apply { putString("offlineMutationId", mutation) })
    }

    @Test
    fun restoringTheBackendSendsTheSameScratchMutationOnceAndThenRemovesOnlyIt() = runBlocking {
        val app = app()
        val mutation = checkNotNull(InstrumentationRegistry.getArguments().getString("offlineMutationId"))
        val dao = app.localStore.library()
        val local = checkNotNull(dao.annotation(mutation))
        check(
            local.note == "TEST_ONLY_API_CONTRACT_OFFLINE" &&
                dao.document(local.documentId)?.path?.startsWith("scratch/") == true
        )
        app.account.sync.pushAnnotations()
        app.account.sync.pushAnnotations()
        assertEquals(1, app.api.annotations(local.documentId).annotations.count { it.mutationId == mutation })
        app.account.sync.deleteAnnotation(checkNotNull(dao.annotation(mutation)))
        assertNull(dao.annotation(mutation))
        assertTrue(app.api.annotations(local.documentId).annotations.none { it.mutationId == mutation })
    }
}
