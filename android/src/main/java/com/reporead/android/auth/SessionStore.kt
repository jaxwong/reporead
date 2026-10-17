package com.reporead.android.auth

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import com.reporead.android.core.network.ApiException
import java.security.GeneralSecurityException
import java.security.KeyStore
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

/**
 * Owns the app's bearer session on this device. The token is encrypted with an Android Keystore AES-GCM key; only
 * ciphertext is written to private preferences, which neither cloud backup nor device-to-device transfer copies
 * (allowBackup and data_extraction_rules in the manifest). The pending PKCE verifier is short-lived and kept only so
 * sign-in survives process death while the browser is open.
 */
class SessionStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Whether a session is stored; [accessToken] may still fail to read it. */
    val hasSession: Boolean
        get() = prefs.contains("token")

    /**
     * The stored session, or null when there is none. One that can never be read is deleted, signing the phone out:
     * text [save] did not write, a key gone or invalidated (KeyPermanentlyInvalidatedException at Cipher.init), or
     * ciphertext the current key does not authenticate (AEADBadTagException: the key was replaced). Any other Keystore
     * failure may pass, so the session is kept and the request fails before anything is sent.
     */
    fun accessToken(): String? {
        val stored = prefs.getString("token", null) ?: return null
        val parts =
            try {
                stored.split(':').map { Base64.decode(it, Base64.NO_WRAP) }
            } catch (malformed: IllegalArgumentException) {
                return forgetUnreadable("malformed")
            }
        if (parts.size != 2) return forgetUnreadable("malformed")
        val (iv, ciphertext) = parts
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
        } catch (error: KeyPermanentlyInvalidatedException) {
            forgetUnreadable(error.javaClass.simpleName)
        } catch (error: AEADBadTagException) {
            forgetUnreadable(error.javaClass.simpleName)
        } catch (error: GeneralSecurityException) {
            Log.w("RepoRead", "Stored session not readable now; kept. failure=${error.javaClass.simpleName}")
            throw ApiException(
                0,
                "SESSION_UNREADABLE",
                "This phone could not read its sign-in just now (${error.javaClass.simpleName}). Nothing was sent; try again.",
            )
        }
    }

    /** The only recovery from a session that can never be read is a new sign-in; saved notes stay. */
    private fun forgetUnreadable(failure: String): String? {
        Log.w("RepoRead", "Stored session can never be read; signing out. failure=$failure")
        clear()
        return null
    }

    fun save(token: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encoded =
            listOf(cipher.iv, cipher.doFinal(token.toByteArray(Charsets.UTF_8))).joinToString(":") {
                Base64.encodeToString(it, Base64.NO_WRAP)
            }
        prefs.edit().putString("token", encoded).apply()
    }

    /**
     * Ends the session on this phone, on disk before it returns: sign-out runs it before a deletion that can crash the
     * app, and apply() writes 100 ms later, so a crash meanwhile would leave the phone signed in.
     */
    fun clear() = check(prefs.edit().remove("token").commit()) { "Could not remove the stored session from $PREFS" }

    /**
     * The server refused [sent] (a 401): ends the session unless a newer sign-in replaced it while that request ran.
     */
    fun endRefused(sent: String?) {
        if (sent != null && accessToken() == sent) clear()
    }

    /** Whether a session is stored, as it changes through any SessionStore (they all share these preferences). */
    fun signedIn(): Flow<Boolean> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "token" || key == null) trySend(hasSession)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        send(hasSession)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
        .distinctUntilChanged()

    /** The RepoRead user id whose data is saved on this phone; null when nothing is known to be saved. */
    var dataOwner: Long?
        get() = if (prefs.contains("dataOwner")) prefs.getLong("dataOwner", 0) else null
        set(value) =
            prefs.edit().apply { if (value == null) remove("dataOwner") else putLong("dataOwner", value) }.apply()

    var pendingVerifier: String?
        get() = prefs.getString("verifier", null)
        set(value) = prefs.edit().putString("verifier", value).apply()

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
