package com.reporead.android

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.util.Log
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

internal const val NAVIGATION_STATE_FORMAT_KEY = "navigationStateFormat"
/** Change only when the saved navigation/UI format becomes incompatible, never for an ordinary build. */
// PR1 removes the serialized Figure destination; format 2 may still contain one.
internal const val NAVIGATION_STATE_FORMAT = 3

/**
 * Compose keys saved UI state by position, so the old string-list stack cannot be read as Navigation 3 state. Drop
 * unmarked/incompatible state once at the activity boundary; compatible updates retain drafts and navigation (ADR-27).
 */
internal fun compatibleSavedState(saved: Bundle?): Bundle? {
    if (saved == null) return null
    val format = saved.getInt(NAVIGATION_STATE_FORMAT_KEY, 0)
    if (format == NAVIGATION_STATE_FORMAT) return saved
    Log.i("RepoRead", "Saved UI state reset; recordedFormat=$format expectedFormat=$NAVIGATION_STATE_FORMAT")
    return null
}

class MainActivity : ComponentActivity() {
    /** The code from this build's sign-in redirect ([BuildConfig.SIGN_IN_REDIRECT]), consumed once by the app. */
    private val signInCode = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(compatibleSavedState(savedInstanceState))
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
        outState.putInt(NAVIGATION_STATE_FORMAT_KEY, NAVIGATION_STATE_FORMAT)
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
