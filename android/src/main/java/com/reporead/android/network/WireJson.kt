// Owns: The HTTP JSON policy, the generated models' strict scalar/time serializers and decoder-to-ApiException
// translation.

package com.reporead.android.network

import java.time.Instant
import java.time.format.DateTimeParseException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.modules.SerializersModule

/**
 * The generated models mark every number, boolean and time @Contextual (android/build.gradle.kts maps them to the
 * aliases below), so each one decodes through these serializers rather than kotlinx's coercing defaults.
 */
internal val wireJson = Json {
    ignoreUnknownKeys = true
    serializersModule = SerializersModule {
        contextual(Int::class, StrictInt)
        contextual(Long::class, StrictLong)
        contextual(Boolean::class, StrictBoolean)
        contextual(Instant::class, WireInstant)
    }
}

/**
 * The generator's names for OpenAPI's integer, int64 and boolean; mapping a type to its own name makes it @Contextual.
 */
typealias WireInt = Int

typealias WireLong = Long

typealias WireBoolean = Boolean

/**
 * Decodes an answer and checks its value rules ([checkRules]). Only external response decoding translates failed wire
 * rules; programmer/caller code is outside this catch.
 */
internal inline fun <reified T : Any> decodeResponse(body: String, status: Int): T =
    try {
        wireJson.decodeFromString<T>(body).also(::checkRules)
    } catch (error: IllegalArgumentException) {
        throw invalidResponse(status, error)
    }

internal fun invalidResponse(status: Int, cause: Throwable? = null) =
    ApiException(status, "INVALID_RESPONSE", "RepoRead's server response did not match the app's contract.", cause)

/** Checks the token kind before the library can coerce quoted numbers or booleans. */
private fun Decoder.scalar(): JsonPrimitive {
    val value = (this as JsonDecoder).decodeJsonElement()
    if (value !is JsonPrimitive || value.isString) throw SerializationException("Expected an unquoted scalar")
    return value
}

/** Owns Boolean token strictness, including inside the generated models' nullable fields and lists. */
internal object StrictBoolean : KSerializer<Boolean> {
    override val descriptor = PrimitiveSerialDescriptor("WireBoolean", PrimitiveKind.BOOLEAN)

    override fun deserialize(decoder: Decoder) =
        decoder.scalar().booleanOrNull ?: throw SerializationException("Expected a JSON boolean")

    override fun serialize(encoder: Encoder, value: Boolean) = encoder.encodeBoolean(value)
}

/** Owns integral Int token/range acceptance; generated serializers still own object and list structure. */
internal object StrictInt : KSerializer<Int> {
    override val descriptor = PrimitiveSerialDescriptor("WireInt", PrimitiveKind.INT)

    override fun deserialize(decoder: Decoder) =
        decoder.scalar().intOrNull ?: throw SerializationException("Expected an integral JSON Int")

    override fun serialize(encoder: Encoder, value: Int) = encoder.encodeInt(value)
}

/** Owns integral Long token/range acceptance without quoted-number coercion. */
internal object StrictLong : KSerializer<Long> {
    override val descriptor = PrimitiveSerialDescriptor("WireLong", PrimitiveKind.LONG)

    override fun deserialize(decoder: Decoder) =
        decoder.scalar().longOrNull ?: throw SerializationException("Expected an integral JSON Long")

    override fun serialize(encoder: Encoder, value: Long) = encoder.encodeLong(value)
}

/**
 * Owns ISO instant decoding and storage representability, so mapping cannot discover overflow after acknowledgement.
 */
internal object WireInstant : KSerializer<Instant> {
    override val descriptor = PrimitiveSerialDescriptor("WireInstant", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): Instant {
        val value = decoder.decodeString()
        return try {
            Instant.parse(value).also { it.toEpochMilli() }
        } catch (error: DateTimeParseException) {
            throw SerializationException("Expected an ISO instant", error)
        } catch (error: ArithmeticException) {
            throw SerializationException("Expected an instant representable as epoch milliseconds", error)
        }
    }

    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())
}
