// Owns: Pure validated-wire-to-Room mappings and selection request conversion; no I/O or decoding policy.

package com.reporead.android.sync

import com.reporead.android.core.network.Anchor
import com.reporead.android.core.network.Annotation
import com.reporead.android.core.network.ReadingState
import com.reporead.android.core.network.Review
import com.reporead.android.core.network.SelectionRequest
import com.reporead.android.core.network.wireJson
import com.reporead.android.data.AnnotationRow
import com.reporead.android.data.AnnotationState
import com.reporead.android.data.Passage
import com.reporead.android.data.ReadingRow
import com.reporead.android.data.ReviewRow
import kotlinx.serialization.encodeToString

internal fun Passage.anchor() = SelectionRequest(blobSha, blockId, startOffset, endOffset, exactText)

private fun Anchor.passage() = Passage(sourceBlobSha, blockId, startOffset, endOffset, exactText)

internal fun Annotation.toRow() =
    AnnotationRow(
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
        status = status,
        resolvedBlobSha = resolvedBlobSha,
        location = location.passage(),
        type = type,
        question = question,
        checkedBlobSha = checkedBlobSha,
    )

internal fun Review.toRow() =
    ReviewRow(mutationId, cardMutationId, grade, reviewedAt.toEpochMilli(), blobSha, pending = false)

internal fun ReadingState.toRow() =
    ReadingRow(
        documentId,
        lastReadBlobSha,
        progressPercent,
        wireJson.encodeToString(anchor),
        lastReadAt.toEpochMilli(),
        pending = false,
    )
