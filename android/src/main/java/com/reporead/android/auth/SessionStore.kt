package com.reporead.android.auth

import android.content.Context
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

private const val KEY_ALIAS = "reporead-session"
private const val PREFS = "reporead-session"

/**
 * Owns the app's bearer session on this device. The token is encrypted with an Android Keystore AES-GCM key;
 * only ciphertext is written to private preferences (backups are disabled in the manifest).
 * The pending PKCE verifier is short-lived and kept only so sign-in survives process death while the browser is open.
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
            Log.w("RepoRead", "Stored session could not be decrypted; signing out. failure=${error.javaClass.simpleName}")
            clear()
            null
        }
    }

    fun save(token: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encoded = listOf(cipher.iv, cipher.doFinal(token.toByteArray(Charsets.UTF_8)))
            .joinToString(":") { Base64.encodeToString(it, Base64.NO_WRAP) }
        prefs.edit().putString("token", encoded).apply()
    }

    fun clear() = prefs.edit().remove("token").apply()

    /** The RepoRead user id whose data is saved on this phone; null when nothing is known to be saved. */
    var dataOwner: Long?
        get() = if (prefs.contains("dataOwner")) prefs.getLong("dataOwner", 0) else null
        set(value) = prefs.edit().apply { if (value == null) remove("dataOwner") else putLong("dataOwner", value) }.apply()

    var pendingVerifier: String?
        get() = prefs.getString("verifier", null)
        set(value) = prefs.edit().putString("verifier", value).apply()

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as SecretKey?)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }
}
