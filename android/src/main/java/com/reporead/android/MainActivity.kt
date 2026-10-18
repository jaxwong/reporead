package com.reporead.android

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier

/** Where a saved instance state records the build that wrote it ([BuildConfig.VERSION_CODE]). */
internal const val SAVED_BY_BUILD = "savedByBuild"

/**
 * [saved] if this build wrote it, else null, so the activity starts afresh. Compose keeps saved UI state by its place
 * in the composition, not by the code that wrote it, so another build's state can sit where this one keeps something
 * else, and reading it there fails (ADR-27). Builds before this rule recorded none, so their state is not restored.
 */
internal fun savedByThisBuild(saved: Bundle?): Bundle? = saved?.takeIf {
    it.getInt(SAVED_BY_BUILD, -1) == BuildConfig.VERSION_CODE
}

class MainActivity : ComponentActivity() {
    /** The code from this build's sign-in redirect ([BuildConfig.SIGN_IN_REDIRECT]), consumed once by the app. */
    private val signInCode = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedByThisBuild(savedInstanceState))
        val app = application as RepoReadApplication
        app.account.startUp()
        // Debug builds only: lets Chrome DevTools inspect the reader over adb. Release builds never expose it.
        WebView.setWebContentsDebuggingEnabled(applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        if (savedInstanceState == null) receive(intent)
        setContent {
            // Follows the phone's light/dark setting with its wallpaper colors. The DayNight window theme in the
            // manifest also tells the reader's WebView, whose page switches palettes through prefers-color-scheme.
            val colors = if (isSystemInDarkTheme()) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)
            MaterialTheme(colorScheme = colors) {
                Surface(Modifier.fillMaxSize()) {
                    RepoReadApp(
                        app.account,
                        app.sessionStore,
                        app.api,
                        app.localStore.library(),
                        app.scope,
                        signInCode.value,
                        onSignInCodeConsumed = { signInCode.value = null },
                    )
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(SAVED_BY_BUILD, BuildConfig.VERSION_CODE)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receive(intent)
    }

    private fun receive(intent: Intent) {
        val uri = intent.data ?: return
        if (uri.buildUpon().clearQuery().build().toString() == BuildConfig.SIGN_IN_REDIRECT)
            signInCode.value = uri.getQueryParameter("code")
    }
}
