// Owns: The value rules decoded answers must meet that their generated shapes cannot state.

package com.reporead.android.core.network

import com.reporead.android.core.network.wire.AnnotationControllerAnnotationList
import com.reporead.android.core.network.wire.AnnotationsAnnotation
import com.reporead.android.core.network.wire.AnnotationsPassage
import com.reporead.android.core.network.wire.AppSessionsIssuedSession
import com.reporead.android.core.network.wire.AppUser
import com.reporead.android.core.network.wire.DocumentControllerContent
import com.reporead.android.core.network.wire.LibrarySnapshot
import com.reporead.android.core.network.wire.ReadingStatesAnchor
import com.reporead.android.core.network.wire.ReadingStatesState
import com.reporead.android.core.network.wire.ReviewsNotebook
import com.reporead.android.core.network.wire.ReviewsReview

private val LOWERCASE_SHA = Regex("[0-9a-f]{40}")

/**
 * Checks [answer] and every value it holds, throwing IllegalArgumentException at the first broken rule.
 * [decodeResponse] applies it to every answer, so a broken rule is INVALID_RESPONSE before Api publishes anything;
 * whether an answer is the one a request asked for stays with each Api route.
 */
internal fun checkRules(answer: Any) {
    when (answer) {
        is LibrarySnapshot -> {
            answer.readingStates.forEach(::checkRules)
            checkRules(answer.notebook)
        }
        is ReviewsNotebook -> {
            // The server, not the app, chooses the session ceiling.
            require(answer.sessionLimit > 0) { "Expected a positive notebook sessionLimit" }
            answer.annotations.forEach(::checkRules)
            answer.reviews.forEach(::checkRules)
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
            // Bookmarks are their own list; these answers hold highlights and cards only, the app's AnnotationType.
            require(answer.type != AnnotationsAnnotation.Type.BOOKMARK) { "Expected a highlight or card" }
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
        is ReviewsReview -> require(answer.grade in 0..5) { "Expected review grade in 0..5" }
        // Snapshot identity prevents fetching images from an unrelated commit.
        is DocumentControllerContent ->
            require(answer.snapshotCommitSha.matches(LOWERCASE_SHA)) { "Expected a lowercase snapshot Git SHA-1" }
        // No GitHub credential is ever published; this is RepoRead's own session.
        is AppSessionsIssuedSession -> {
            require(answer.accessToken.isNotBlank()) { "Expected a non-blank accessToken" }
            checkRules(answer.user)
        }
        is AppUser -> require(answer.id > 0) { "Expected a persisted account id" }
    }
}
