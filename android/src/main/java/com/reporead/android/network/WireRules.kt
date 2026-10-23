// Owns: The value rules decoded answers must meet that their generated shapes cannot state.

package com.reporead.android.network

import com.reporead.android.network.wire.AnnotationControllerAnnotationList
import com.reporead.android.network.wire.AnnotationsAnnotation
import com.reporead.android.network.wire.AnnotationsPassage
import com.reporead.android.network.wire.AppSessionsIssuedSession
import com.reporead.android.network.wire.AppUser
import com.reporead.android.network.wire.DocumentControllerContent
import com.reporead.android.network.wire.LibrarySnapshot
import com.reporead.android.network.wire.ReadingStatesAnchor
import com.reporead.android.network.wire.ReadingStatesState
import com.reporead.android.network.wire.ReviewsNotebook

private val LOWERCASE_SHA = Regex("[0-9a-f]{40}")

/**
 * Checks [answer] and every value it holds, throwing IllegalArgumentException at the first broken rule.
 * [decodeResponse] applies it to every answer, so a broken rule is INVALID_RESPONSE before Api publishes anything;
 * whether an answer is the one a request asked for stays with each Api route.
 */
internal fun checkRules(answer: Any) {
    when (answer) {
        is LibrarySnapshot -> {
            answer.repositories.uniqueKeys("repository ids") { it.id }
            val documents = answer.repositories.flatMap { it.documents }.uniqueKeys("document ids") { it.id }
            require(answer.readingStates.uniqueKeys("reading document ids") { it.documentId }.all { it in documents }) {
                "Expected every reading state to refer to a document in the snapshot"
            }
            require(answer.bookmarks.uniqueKeys("bookmark document ids") { it.documentId }.all { it in documents }) {
                "Expected every bookmark to refer to a document in the snapshot"
            }
            require(answer.notebook.annotations.all { it.documentId in documents }) {
                "Expected every annotation to refer to a document in the snapshot"
            }
            answer.readingStates.forEach(::checkRules)
            checkRules(answer.notebook)
        }
        is ReviewsNotebook -> {
            answer.annotations.uniqueKeys("annotation ids") { it.id }
            answer.annotations.uniqueKeys("annotation mutation ids") { it.mutationId }
            answer.reviews.uniqueKeys("review mutation ids") { it.mutationId }
            val cards =
                answer.annotations.filter { it.type == AnnotationsAnnotation.Type.CARD }.map { it.mutationId }.toSet()
            require(answer.reviews.all { it.cardMutationId in cards }) {
                "Expected every review to refer to a card in the notebook"
            }
            answer.annotations.forEach(::checkRules)
        }
        is ReadingStatesState -> {
            require(answer.progressPercent in 0..100) { "Expected reading progressPercent in 0..100" }
            checkRules(answer.anchor)
        }
        is ReadingStatesAnchor -> require(answer.blockIndex >= 0) { "Expected a non-negative reading blockIndex" }
        is AnnotationControllerAnnotationList -> answer.annotations.forEach(::checkRules)
        is AnnotationsAnnotation -> {
            require(answer.id > 0 && answer.version > 0) {
                "Expected a positive annotation id and version for later mutations"
            }
            require(answer.type != AnnotationsAnnotation.Type.CARD || !answer.question.isNullOrBlank()) {
                "Expected a non-blank CARD question"
            }
            checkRules(answer.anchor)
            checkRules(answer.location)
        }
        is AnnotationsPassage ->
            require(
                answer.startOffset >= 0 &&
                    answer.endOffset > answer.startOffset &&
                    answer.exactText.length == answer.endOffset - answer.startOffset
            ) {
                "Expected a non-empty UTF-16 passage range matching exactText"
            }
        // Snapshot identity prevents fetching images from an unrelated commit.
        is DocumentControllerContent -> {
            require(answer.snapshotCommitSha.matches(LOWERCASE_SHA)) { "Expected a lowercase snapshot Git SHA-1" }
            require(answer.blocks.all { it.id.isNotBlank() }) { "Expected non-blank content block ids" }
            answer.blocks.uniqueKeys("content block ids") { it.id }
        }
        // No GitHub credential is ever published; this is RepoRead's own session.
        is AppSessionsIssuedSession -> {
            require(answer.accessToken.isNotBlank()) { "Expected a non-blank accessToken" }
            checkRules(answer.user)
        }
        is AppUser -> require(answer.id > 0) { "Expected a persisted account id" }
    }
}

/** A duplicate identity would be silently overwritten by Room's REPLACE writes or the library map. */
private fun <T, K> List<T>.uniqueKeys(name: String, key: (T) -> K): Set<K> =
    map(key).toSet().also { require(it.size == size) { "Expected unique $name in the snapshot" } }
