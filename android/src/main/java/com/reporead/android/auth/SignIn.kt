package com.reporead.android.auth

import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import com.reporead.android.core.network.Api
import com.reporead.android.core.network.ApiException
import com.reporead.android.core.network.contract
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import android.util.Base64

private val RANDOM = SecureRandom()

private fun base64Url(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

/** Opens GitHub sign-in in a Custom Tab, bound to a fresh PKCE verifier that never leaves this device. */
fun startSignIn(context: Context, baseUrl: String, store: SessionStore) {
    val verifier = base64Url(ByteArray(32).also(RANDOM::nextBytes))
    val challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
    store.pendingVerifier = verifier
    CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse("$baseUrl/app/sign-in?code_challenge=$challenge"))
}

/** Exchanges the code from reporead://auth for a bearer session. The verifier is single-use either way. */
suspend fun completeSignIn(code: String, api: Api, store: SessionStore) {
    val verifier = store.pendingVerifier
        ?: throw ApiException(0, "SIGN_IN_NOT_STARTED", "This sign-in was not started from this app. Tap Sign in again.")
    store.pendingVerifier = null
    val session = api.post("/api/app-auth/token", JSONObject().put("code", code).put("codeVerifier", verifier))
    store.save(contract { session.getString("accessToken") })
}
