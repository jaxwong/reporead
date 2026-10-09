package com.reporead.android.reader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable

/** Debug-only host for instrumented reader lifecycle tests; never packaged in release. */
class ReaderTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val testContent = checkNotNull(content) { "ReaderTestActivity requires test content" }
        setContent { testContent() }
    }

    companion object {
        var content: (@Composable () -> Unit)? = null
    }
}
