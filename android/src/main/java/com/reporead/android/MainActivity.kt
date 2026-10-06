package com.reporead.android

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf

class MainActivity : ComponentActivity() {
    /** The code from a reporead://auth redirect, consumed once by the app. */
    private val signInCode = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Debug builds only: lets Chrome DevTools inspect the reader over adb. Release builds never expose it.
        WebView.setWebContentsDebuggingEnabled(applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        if (savedInstanceState == null) receive(intent)
        setContent {
            MaterialTheme { RepoReadApp(signInCode.value, onSignInCodeConsumed = { signInCode.value = null }) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receive(intent)
    }

    private fun receive(intent: Intent) {
        val uri = intent.data ?: return
        if (uri.scheme == "reporead" && uri.host == "auth") signInCode.value = uri.getQueryParameter("code")
    }
}
