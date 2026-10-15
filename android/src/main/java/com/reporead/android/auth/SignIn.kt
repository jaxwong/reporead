package com.reporead.android.auth

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import com.reporead.android.BuildConfig
import com.reporead.android.core.network.Api
import com.reporead.android.core.network.ApiException
import com.reporead.android.core.network.contract
import java.security.MessageDigest
import java.security.SecureRandom
import org.json.JSONException
import org.json.JSONObject

private val RANDOM = SecureRandom()

private fun base64Url(bytes: ByteArray) =
    Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

/**
 * Opens GitHub sign-in in a Custom Tab, bound to a fresh PKCE verifier that never leaves this device. The server sends
 * the code back on this build's own scheme, so the release and debug apps installed side by side each get their own.
 */
fun startSignIn(context: Context, baseUrl: String, store: SessionStore) {
    val verifier = base64Url(ByteArray(32).also(RANDOM::nextBytes))
    val challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
    store.pendingVerifier = verifier
    CustomTabsIntent.Builder()
        .build()
        .launchUrl(
            context,
            Uri.parse("$baseUrl/app/sign-in?code_challenge=$challenge&scheme=${BuildConfig.SIGN_IN_SCHEME}"),
        )
}

/**
 * Exchanges the code from the sign-in redirect for a bearer session and stores it. The verifier is single-use either
 * way. Data saved on this phone belongs to the account that saved it, so the exchange names that account and the server
 * issues no session for another (ACCOUNT_MISMATCH): a sign-in never removes anything from the phone, and switching
 * accounts takes signing out, which says first what it would lose.
 */
suspend fun completeSignIn(code: String, api: Api, store: SessionStore) {
    val verifier =
        store.pendingVerifier
            ?: throw ApiException(
                0,
                "SIGN_IN_NOT_STARTED",
                "This sign-in was not started from this app. Tap Sign in again.",
            )
    store.pendingVerifier = null
    // A phone that saved data before owners were recorded has owner null; its data is taken to be this account's.
    val owner = store.dataOwner
    val request = JSONObject().put("code", code).put("codeVerifier", verifier)
    if (owner != null) request.put("expectedUserId", owner)
    val session = api.post("/api/app-auth/token", request)
    val (token, userId) =
        contract {
            val userId = session.getJSONObject("user").getLong("id")
            if (owner != null && userId != owner)
                throw JSONException("Session for user $userId; this phone holds $owner's data")
            session.getString("accessToken") to userId
        }
    Log.i("RepoRead", "Signed in; userId=$userId firstOnThisPhone=${owner == null}")
    store.dataOwner = userId
    store.save(token)
}
