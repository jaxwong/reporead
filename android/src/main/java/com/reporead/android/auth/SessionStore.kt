package com.reporead.android.auth

import android.content.Context
import android.content.SharedPreferences
import android.security.KeyStoreException
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.core.content.edit
import com.reporead.android.core.log.AppLog
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

private const val KEY_ALIAS = "reporead-session"
private const val PREFS = "reporead-session"
private const val TRANSFORMATION = "AES/GCM/NoPadding"

/**
 * The IV Android Keystore generates for AES/GCM, the only length it accepts back
 * (AndroidKeyStoreAuthenticatedAESCipherSpi).
 */
private const val GCM_IV_BYTES = 12
private const val GCM_TAG_BYTES = 16

/** What this phone holds for its sign-in. */
sealed interface StoredSession {
    data object Absent : StoredSession

    data class Readable(val token: String) : StoredSession

    /** It can never be read again, so it was deleted: only a new sign-in recovers (saved notes stay). */
    data object PermanentlyInvalid : StoredSession

    /** The Keystore failed in a way that may pass; the session is kept. */
    class TemporarilyUnavailable(val cause: GeneralSecurityException) : StoredSession
}

/** An IV and ciphertext as [SessionStore.save] writes them. */
internal class Envelope(val iv: ByteArray, val ciphertext: ByteArray)

/**
 * Reads "iv:ciphertext" (each Base64) without the Keystore, or null for text [SessionStore.save] could not have
 * written: not two parts, not Base64, an IV of another length, or a ciphertext shorter than its GCM tag.
 */
internal fun parseEnvelope(stored: String): Envelope? {
    val parts = stored.split(':')
    if (parts.size != 2) return null
    val (iv, ciphertext) =
        try {
            parts.map { Base64.getDecoder().decode(it) }
        } catch (notBase64: IllegalArgumentException) {
            return null
        }
    if (iv.size != GCM_IV_BYTES || ciphertext.size < GCM_TAG_BYTES) return null
    return Envelope(iv, ciphertext)
}

/**
 * What a Keystore [failure] to decrypt the stored session means. Permanent when the key is gone or can never decrypt
 * this ciphertext: KeyPermanentlyInvalidatedException (Cipher.init found the key missing or invalidated),
 * UnrecoverableKeyException (KeyStore.getKey could not load the key) unless [keyLoadMayPass], and AEADBadTagException
 * (the ciphertext does not authenticate under the current key: a missing key is replaced by a new one before
 * decrypting, so this is how a lost key shows). Anything else may pass.
 */
internal fun keystoreFailure(
    failure: GeneralSecurityException,
    keyLoadMayPass: Boolean = keystoreSaysItMayPass(failure),
): StoredSession =
    when (failure) {
        is UnrecoverableKeyException ->
            if (keyLoadMayPass) StoredSession.TemporarilyUnavailable(failure) else StoredSession.PermanentlyInvalid
        is KeyPermanentlyInvalidatedException,
        is AEADBadTagException -> StoredSession.PermanentlyInvalid
        else -> StoredSession.TemporarilyUnavailable(failure)
    }

/**
 * Android raises UnrecoverableKeyException for an invalidated key (no cause) and for any other Keystore error loading
 * it (the KeyStoreException as cause). Only an error of this key that never passes needs a new key; one Keystore marks
 * transient, or as a fault of the system rather than the key, may pass.
 */
private fun keystoreSaysItMayPass(failure: GeneralSecurityException): Boolean =
    (failure.cause as? KeyStoreException)?.let { it.isTransientFailure || it.isSystemError } == true

/**
 * Owns the app's bearer session on this device. The token is encrypted with an Android Keystore AES-GCM key; only
 * ciphertext is written to private preferences, which neither cloud backup nor device-to-device transfer copies
 * (allowBackup and data_extraction_rules in the manifest). The pending PKCE verifier is short-lived and kept only so
 * sign-in survives process death while the browser is open.
 */
class SessionStore(context: Context, name: String = PREFS) {
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    /** Whether a session is stored, without reading it; [read] may still find it unreadable. */
    val hasSession: Boolean
        get() = prefs.contains("token")

    /** The stored session. One that can never be read is deleted here, signing this phone out. */
    fun read(): StoredSession {
        val stored = prefs.getString("token", null) ?: return StoredSession.Absent
        val envelope = parseEnvelope(stored)
        if (envelope == null) AppLog.warn("Stored session is not text save() writes; signing out.")
        val session = envelope?.let(::decrypt) ?: StoredSession.PermanentlyInvalid
        if (session == StoredSession.PermanentlyInvalid) clear()
        return session
    }

    private fun decrypt(envelope: Envelope): StoredSession =
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BYTES * 8, envelope.iv))
            StoredSession.Readable(cipher.doFinal(envelope.ciphertext).toString(Charsets.UTF_8))
        } catch (failure: GeneralSecurityException) {
            keystoreFailure(failure).also {
                AppLog.warn(
                    "Stored session could not be decrypted; result=${it.javaClass.simpleName} failure=${failure.javaClass.simpleName}"
                )
            }
        }

    fun save(token: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encoded =
            listOf(cipher.iv, cipher.doFinal(token.toByteArray(Charsets.UTF_8))).joinToString(":") {
                Base64.getEncoder().encodeToString(it)
            }
        prefs.edit { putString("token", encoded) }
    }

    /** Ends the session on disk before returning, so process death cannot leave this phone signed in. */
    fun clear() = commit("remove the stored session") { remove("token") }

    /**
     * The server refused [sent] (a 401): ends the session unless a newer sign-in replaced it while that request ran.
     * One that cannot be read now is kept, since it may be newer; the next request that reads it is refused in turn.
     */
    fun endRefused(sent: String?) {
        if (sent != null && (read() as? StoredSession.Readable)?.token == sent) clear()
    }

    /** Whether a session is stored, as it changes through any SessionStore of these preferences. */
    fun signedIn(): Flow<Boolean> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changed ->
            if (changed == "token" || changed == null) trySend(hasSession)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        send(hasSession)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
        .distinctUntilChanged()

    private fun commit(what: String, change: SharedPreferences.Editor.() -> Unit) =
        check(prefs.edit().apply(change).commit()) { "Could not $what in $PREFS" }

    var pendingVerifier: String?
        get() = prefs.getString("verifier", null)
        set(value) = prefs.edit { putString("verifier", value) }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as SecretKey?)?.let {
            return it
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply {
                init(
                    KeyGenParameterSpec.Builder(
                            KEY_ALIAS,
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build()
                )
            }
            .generateKey()
    }
}
