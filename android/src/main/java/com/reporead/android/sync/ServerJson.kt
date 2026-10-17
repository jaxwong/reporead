package com.reporead.android.sync

import com.reporead.android.core.network.contract
import com.reporead.android.core.network.string
import com.reporead.android.data.AnchorStatus
import com.reporead.android.data.AnnotationRow
import com.reporead.android.data.AnnotationState
import com.reporead.android.data.AnnotationType
import com.reporead.android.data.Passage
import com.reporead.android.data.ReadingRow
import com.reporead.android.data.ReviewRow
import com.reporead.android.data.SyncState
import java.time.Instant
import org.json.JSONException
import org.json.JSONObject

// RepoRead's server responses as Room rows. A missing or mistyped field breaks the contract (ApiException); no crash.

internal fun JSONObject.nullableString(name: String): String? = if (isNull(name)) null else string(name)

/** The constant of [E] that the field [name] names; any other value breaks the contract. */
internal inline fun <reified E : Enum<E>> JSONObject.enumValue(name: String): E {
    val value = string(name)
    return enumValues<E>().firstOrNull { it.name == value } ?: throw JSONException("Unknown $name $value")
}

/** A passage as the server's annotation anchor. */
internal fun Passage.anchorJson(): JSONObject =
    JSONObject()
        .put("sourceBlobSha", blobSha)
        .put("blockId", blockId)
        .put("startOffset", startOffset)
        .put("endOffset", endOffset)
        .put("exactText", exactText)

internal fun reviewRow(json: JSONObject) = contract {
    val grade = json.getInt("grade")
    if (grade !in 0..5) throw JSONException("Invalid review grade $grade")
    ReviewRow(
        json.string("mutationId"),
        json.string("cardMutationId"),
        grade,
        Instant.parse(json.string("reviewedAt")).toEpochMilli(),
        json.string("blobSha"),
        state = SyncState.ACKNOWLEDGED,
    )
}

internal fun annotationRow(json: JSONObject) = contract {
    val anchor = json.getJSONObject("anchor")
    val location = json.getJSONObject("location")
    AnnotationRow(
        json.string("mutationId"),
        json.getLong("id"),
        json.getLong("documentId"),
        anchor.string("sourceBlobSha"),
        anchor.string("blockId"),
        anchor.getInt("startOffset"),
        anchor.getInt("endOffset"),
        anchor.string("exactText"),
        json.nullableString("note"),
        json.getInt("version"),
        Instant.parse(json.string("createdAt")).toEpochMilli(),
        state = AnnotationState.ACKNOWLEDGED,
        prefixText = anchor.string("prefixText"),
        suffixText = anchor.string("suffixText"),
        status = json.enumValue<AnchorStatus>("status"),
        resolvedBlobSha = json.string("resolvedBlobSha"),
        location =
            Passage(
                location.string("sourceBlobSha"),
                location.string("blockId"),
                location.getInt("startOffset"),
                location.getInt("endOffset"),
                location.string("exactText"),
            ),
        type = json.enumValue<AnnotationType>("type"),
        question = json.nullableString("question"),
        checkedBlobSha = json.nullableString("checkedBlobSha"),
    )
}

internal fun readingRow(json: JSONObject) = contract {
    ReadingRow(
        json.getLong("documentId"),
        json.string("lastReadBlobSha"),
        json.getInt("progressPercent"),
        json.getJSONObject("anchor").toString(),
        Instant.parse(json.string("lastReadAt")).toEpochMilli(),
        state = SyncState.ACKNOWLEDGED,
    )
}
