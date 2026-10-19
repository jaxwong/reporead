package com.reporead.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable

/** Debug-only host for every instrumented screen test; never packaged in release. */
class TestHostActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val restored = restoredState ?: savedInstanceState
        restoredState = null
        super.onCreate(compatibleSavedState(restored))
        val testContent = checkNotNull(content) { "TestHostActivity requires test content" }
        setContent { testContent() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(NAVIGATION_STATE_FORMAT_KEY, NAVIGATION_STATE_FORMAT)
    }

    companion object {
        var content: (@Composable () -> Unit)? = null
        /** Test-only saved-instance-state fixture, consumed by the next new activity, never the real app's state. */
        var restoredState: Bundle? = null
    }
}
