// Owns: Pure validated-wire-to-Room mappings and selection request conversion; no I/O or decoding policy.

package com.reporead.android.sync

import com.reporead.android.database.AnchorStatus
import com.reporead.android.database.AnnotationEntity
import com.reporead.android.database.AnnotationState
import com.reporead.android.database.AnnotationType
import com.reporead.android.database.Passage
import com.reporead.android.database.ReadingStateEntity
import com.reporead.android.database.ReviewEntity
import com.reporead.android.network.wire.AnnotationServiceSelection
import com.reporead.android.network.wire.AnnotationsAnnotation
import com.reporead.android.network.wire.AnnotationsPassage
import com.reporead.android.network.wire.ReadingStatesState
import com.reporead.android.network.wire.ReviewsReview
import com.reporead.android.network.wireJson
import kotlinx.serialization.encodeToString

internal fun Passage.anchor() =
    AnnotationServiceSelection(
        sourceBlobSha = blobSha,
        blockId = blockId,
        startOffset = startOffset,
        endOffset = endOffset,
        exactText = exactText,
    )

private fun AnnotationsPassage.passage() = Passage(sourceBlobSha, blockId, startOffset, endOffset, exactText)

internal fun AnnotationsAnnotation.toEntity() =
    AnnotationEntity(
        mutationId,
        id,
        documentId,
        anchor.sourceBlobSha,
        anchor.blockId,
        anchor.startOffset,
        anchor.endOffset,
        anchor.exactText,
        note,
        version,
        createdAt.toEpochMilli(),
        state = AnnotationState.ACKNOWLEDGED,
        prefixText = anchor.prefixText,
        suffixText = anchor.suffixText,
        status =
            when (status) {
                AnnotationsAnnotation.Status.ANCHORED -> AnchorStatus.ANCHORED
                AnnotationsAnnotation.Status.REANCHORED -> AnchorStatus.REANCHORED
                AnnotationsAnnotation.Status.ORPHANED -> AnchorStatus.ORPHANED
            },
        resolvedBlobSha = resolvedBlobSha,
        location = location.passage(),
        type =
            when (type) {
                AnnotationsAnnotation.Type.HIGHLIGHT -> AnnotationType.HIGHLIGHT
                AnnotationsAnnotation.Type.CARD -> AnnotationType.CARD
                AnnotationsAnnotation.Type.BOOKMARK -> error("checkRules admits no BOOKMARK annotation; id=$id")
            },
        question = question,
    )

internal fun ReviewsReview.toEntity() =
    ReviewEntity(mutationId, cardMutationId, grade, reviewedAt.toEpochMilli(), blobSha, pending = false)

internal fun ReadingStatesState.toEntity() =
    ReadingStateEntity(
        documentId,
        lastReadBlobSha,
        progressPercent,
        wireJson.encodeToString(anchor),
        lastReadAt.toEpochMilli(),
        pending = false,
    )
