// Owns: Native PKCE setup and validated token exchange; Account owns the operation lifetime.

package com.reporead.android.auth

import android.content.Context
import android.util.Base64
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri
import com.reporead.android.BuildConfig
import com.reporead.android.core.log.AppLog
import com.reporead.android.core.network.Api
import com.reporead.android.core.network.wire.AppAuthControllerTokenRequest
import java.security.MessageDigest
import java.security.SecureRandom

private val RANDOM = SecureRandom()

private fun base64Url(bytes: ByteArray) =
    Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

/**
 * Opens GitHub sign-in in a Custom Tab, bound to a fresh PKCE verifier that never leaves this device. The server sends
 * the code to this build's own redirect ([BuildConfig.SIGN_IN_REDIRECT]): the release app's verified App Link, or the
 * debug app's scheme on the Mac's backend.
 */
fun startSignIn(context: Context, baseUrl: String, store: SessionStore) {
    val verifier = base64Url(ByteArray(32).also(RANDOM::nextBytes))
    val challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
    store.pendingVerifier = verifier
    CustomTabsIntent.Builder()
        .build()
        .launchUrl(
            context,
            "$baseUrl/app/sign-in?code_challenge=$challenge".toUri(),
        )
}

/** Exchanges the validated sign-in code and stores the session without changing saved notes. */
internal suspend fun completeSignIn(code: String, verifier: String, api: Api, store: SessionStore) {
    val session = api.exchangeToken(AppAuthControllerTokenRequest(code = code, codeVerifier = verifier))
    AppLog.info("Signed in; userId=${session.user.id}")
    store.save(session.accessToken)
}
