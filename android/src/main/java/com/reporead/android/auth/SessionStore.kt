package com.reporead.android.auth

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.GeneralSecurityException
import java.security.KeyStore
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

    fun accessToken(): String? {
        val stored = prefs.getString("token", null) ?: return null
        val (iv, ciphertext) = stored.split(':').map { Base64.decode(it, Base64.NO_WRAP) }
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
        } catch (error: GeneralSecurityException) {
            // A Keystore key can be invalidated by the platform; the only recovery is a new sign-in.
            Log.w(
                "RepoRead",
                "Stored session could not be decrypted; signing out. failure=${error.javaClass.simpleName}",
            )
            clear()
            null
        }
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
     * Ends the session on this phone, on disk before it returns: sign-out runs it before deleting the phone's copy, and
     * apply() writes 100 ms later, so process death meanwhile would leave the phone signed in.
     */
    fun clear() = commit("remove the stored session") { remove("token") }

    /**
     * The server refused [sent] (a 401): ends the session unless a newer sign-in replaced it while that request ran.
     */
    fun endRefused(sent: String?) {
        if (sent != null && accessToken() == sent) clear()
    }

    /** Whether a session is stored, as it changes through any SessionStore (they all share these preferences). */
    fun signedIn(): Flow<Boolean> = observed("token") { prefs.contains("token") }

    /** [key]'s value, read by [read], as it changes; one listener shape for each value a screen follows. */
    private fun <T> observed(key: String, read: () -> T): Flow<T> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changed ->
            if (changed == key || changed == null) trySend(read())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        send(read())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
        .distinctUntilChanged()

    /** The RepoRead user id whose data is saved on this phone; null when nothing is known to be saved. */
    val dataOwner: Long?
        get() = if (prefs.contains("dataOwner")) prefs.getLong("dataOwner", 0) else null

    fun recordOwner(userId: Long) = prefs.edit().putLong("dataOwner", userId).apply()

    /**
     * Set from just before a sign-out or account deletion asks the server until this phone's copy is gone
     * (RepoReadApplication.removeCopy); meanwhile the phone signs in as no account (completeSignIn), and the app
     * finishes the removal when it next starts. A missing session with a known owner cannot say this: a session that
     * expired keeps its saved notes.
     */
    val removalPending: Boolean
        get() = prefs.getBoolean("removalPending", false)

    fun removalPendingChanges(): Flow<Boolean> = observed("removalPending") { removalPending }

    /** On disk before the server is asked, so process death after it still removes the phone's copy at next start. */
    fun startRemoval() = commit("record a pending removal") { putBoolean("removalPending", true) }

    /**
     * The server refused or did not answer an account deletion: nothing on the phone changed, so nothing is removed.
     */
    fun cancelRemoval() = commit("cancel the pending removal") { remove("removalPending") }

    /** The phone's copy is gone: the pending removal and the data's owner are forgotten in one write. */
    fun finishRemoval() =
        commit("finish the removal") {
            remove("removalPending")
            remove("dataOwner")
        }

    private fun commit(what: String, change: SharedPreferences.Editor.() -> Unit) =
        check(prefs.edit().apply(change).commit()) { "Could not $what in $PREFS" }

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
