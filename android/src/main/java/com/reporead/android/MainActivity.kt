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
import com.reporead.android.logging.DeviceLog
import com.reporead.android.sync.requestDeviceLogUpload

internal const val NAVIGATION_STATE_FORMAT_KEY = "navigationStateFormat"
/** Older formats may contain serialized classes whose package or type name no longer exists. */
internal const val NAVIGATION_STATE_FORMAT = 5

/**
 * Compose keys saved UI state by position, so the old string-list stack cannot be read as Navigation 3 state. Drop
 * unmarked/incompatible state once at the activity boundary; compatible updates retain drafts and navigation (ADR-27).
 */
internal fun compatibleSavedState(saved: Bundle?): Bundle? {
    if (saved == null) return null
    val format = saved.getInt(NAVIGATION_STATE_FORMAT_KEY, 0)
    if (format == NAVIGATION_STATE_FORMAT) return saved
    DeviceLog.info("Saved UI state reset; recordedFormat=$format expectedFormat=$NAVIGATION_STATE_FORMAT")
    return null
}

class MainActivity : ComponentActivity() {
    /** The code from this build's sign-in redirect ([BuildConfig.SIGN_IN_REDIRECT]), consumed once by the app. */
    private val signInCode = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(compatibleSavedState(savedInstanceState))
        val app = application as RepoReadApplication
        // Debug builds only: lets Chrome DevTools inspect the reader over adb. Release builds never expose it.
        WebView.setWebContentsDebuggingEnabled(applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        if (savedInstanceState == null) receive(intent)
        requestDeviceLogUpload(this)
        setContent {
            // Follows the phone's light/dark setting with its wallpaper colors. The DayNight window theme in the
            // manifest also tells the reader's WebView, whose page switches palettes through prefers-color-scheme.
            val colors = if (isSystemInDarkTheme()) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)
            MaterialTheme(colorScheme = colors) {
                Surface(Modifier.fillMaxSize()) {
                    RepoReadApp(
                        app.account,
                        app.phoneSync,
                        app.sessionStore,
                        app.api,
                        app.database.dao(),
                        app.scope,
                        signInCode.value,
                        onSignInCodeConsumed = { signInCode.value = null },
                    )
                }
            }
        }
    }

    /** Leaving the app sends what it just logged, and each start what the last run left, once there is a network. */
    override fun onStop() {
        super.onStop()
        requestDeviceLogUpload(this)
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
