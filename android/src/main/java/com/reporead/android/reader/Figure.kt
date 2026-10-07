package com.reporead.android.reader

import android.content.pm.ActivityInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.reporead.android.R
import com.reporead.android.Screen
import com.reporead.android.data.LibraryDao
import com.reporead.android.data.NoteRow
import com.reporead.android.ui.AppIcon
import com.reporead.android.ui.EmptyState
import org.json.JSONObject
import org.json.JSONTokener

/**
 * One table or diagram of a note, full screen, with pinch zoom, turning with the phone even when rotation is locked.
 * It loads the saved copy of the version the reader showed in a second isolated page (no bridge, the same resources)
 * and asks the page to keep only that figure, so it is rebuilt from small keys when rotating recreates the activity.
 * Highlights are neither drawn nor made here.
 */
@Composable
fun FigureScreen(sync: com.reporead.android.sync.Sync, dao: LibraryDao, screen: Screen.Figure, onBack: () -> Unit) {
    val activity = LocalActivity.current ?: error("FigureScreen requires an activity")
    DisposableEffect(activity) {
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        val bars = activity.window.insetsController ?: error("FigureScreen requires a window insets controller")
        bars.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars.hide(WindowInsets.Type.systemBars())
        onDispose {
            bars.show(WindowInsets.Type.systemBars())
            // Rotating recreates the activity, which keeps the requested orientation; only leaving the figure resets it.
            if (!activity.isChangingConfigurations) activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    val note by produceState<NoteRow?>(null, screen) { value = dao.note(screen.documentId) }
    var failure by remember(screen) { mutableStateOf<String?>(null) }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        val saved = note
        when {
            failure != null -> EmptyState(failure!!)
            saved == null -> Unit
            saved.blobSha != screen.blobSha -> EmptyState("This note was updated since it was opened. Go back and open the table or diagram again.")
            else -> AndroidView(
                factory = { context -> figureView(context, sync, saved, screen.figure) { failure = it } },
                onRelease = { it.destroy() },
                modifier = Modifier.fillMaxSize(),
            )
        }
        IconButton(onClick = onBack, modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)) {
            AppIcon(R.drawable.ic_close, "Close full screen")
        }
    }
}

private fun figureView(context: android.content.Context, sync: com.reporead.android.sync.Sync, note: NoteRow, figure: String,
                       onFailure: (String) -> Unit): WebView {
    val assets = readerAssets(context)
    val main = Handler(Looper.getMainLooper())
    return WebView(context).apply {
        isolateReaderPage()
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        // Hidden until the page has removed everything but the figure.
        alpha = 0f
        webViewClient = object : WebViewClient() {
            // Nothing navigates here; links are followed in the reader.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) = readerResource(assets, sync, note, request.url)

            override fun onPageFinished(view: WebView, url: String) = awaitRendered(view, main) { state, _ ->
                if (state != "ready") {
                    Log.w("RepoRead", "Figure page did not render; documentId=${note.documentId} state=$state")
                    onFailure("This note could not be displayed.")
                    return@awaitRendered
                }
                view.evaluateJavascript("window.reporead.figure(${JSONObject.quote(figure)})") { encoded ->
                    val shown = JSONTokener(encoded).nextValue() == true
                    Log.i("RepoRead", "Figure shown; documentId=${note.documentId} figure=$figure found=$shown")
                    if (shown) view.alpha = 1f else onFailure("That table or diagram is not in this version of the note.")
                }
            }
        }
        loadDataWithBaseURL("$ASSET_ORIGIN/", note.html, "text/html", "UTF-8", null)
    }
}
