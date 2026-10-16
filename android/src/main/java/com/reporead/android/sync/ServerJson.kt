package com.reporead.android.sync

import com.reporead.android.core.network.contract
import com.reporead.android.data.AnchorStatus
import com.reporead.android.data.AnnotationRow
import com.reporead.android.data.AnnotationState
import com.reporead.android.data.AnnotationType
import com.reporead.android.data.Passage
import com.reporead.android.data.ReadingRow
import com.reporead.android.data.ReviewRow
import java.time.Instant
import org.json.JSONException
import org.json.JSONObject

// RepoRead's server responses as Room rows. A missing or mistyped field breaks the contract (ApiException), never a
// crash.

internal fun JSONObject.nullableString(name: String): String? = if (isNull(name)) null else getString(name)

/** The constant of [E] that the field [name] names; any other value breaks the contract. */
internal inline fun <reified E : Enum<E>> JSONObject.enumValue(name: String): E {
    val value = getString(name)
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
        json.getString("mutationId"),
        json.getString("cardMutationId"),
        grade,
        Instant.parse(json.getString("reviewedAt")).toEpochMilli(),
        json.getString("blobSha"),
        pending = false,
    )
}

internal fun annotationRow(json: JSONObject) = contract {
    val anchor = json.getJSONObject("anchor")
    val location = json.getJSONObject("location")
    AnnotationRow(
        json.getString("mutationId"),
        json.getLong("id"),
        json.getLong("documentId"),
        anchor.getString("sourceBlobSha"),
        anchor.getString("blockId"),
        anchor.getInt("startOffset"),
        anchor.getInt("endOffset"),
        anchor.getString("exactText"),
        json.nullableString("note"),
        json.getInt("version"),
        Instant.parse(json.getString("createdAt")).toEpochMilli(),
        state = AnnotationState.ACKNOWLEDGED,
        prefixText = anchor.getString("prefixText"),
        suffixText = anchor.getString("suffixText"),
        status = json.enumValue<AnchorStatus>("status"),
        resolvedBlobSha = json.getString("resolvedBlobSha"),
        location =
            Passage(
                location.getString("sourceBlobSha"),
                location.getString("blockId"),
                location.getInt("startOffset"),
                location.getInt("endOffset"),
                location.getString("exactText"),
            ),
        type = json.enumValue<AnnotationType>("type"),
        question = json.nullableString("question"),
        checkedBlobSha = json.nullableString("checkedBlobSha"),
    )
}

internal fun readingRow(json: JSONObject) = contract {
    ReadingRow(
        json.getLong("documentId"),
        json.getString("lastReadBlobSha"),
        json.getInt("progressPercent"),
        json.getJSONObject("anchor").toString(),
        Instant.parse(json.getString("lastReadAt")).toEpochMilli(),
        pending = false,
    )
}
