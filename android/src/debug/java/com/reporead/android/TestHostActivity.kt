package com.reporead.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable

/** Debug-only host for every instrumented screen test; never packaged in release. */
class TestHostActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val testContent = checkNotNull(content) { "TestHostActivity requires test content" }
        setContent { testContent() }
    }

    companion object {
        var content: (@Composable () -> Unit)? = null
    }
}
