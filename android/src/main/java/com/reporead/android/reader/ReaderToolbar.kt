package com.reporead.android.reader

import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.reporead.android.R
import com.reporead.android.ui.AppBar
import com.reporead.android.ui.AppIcon

/** The note's navigation and reading actions; the screen owns each value and action. */
@Composable
internal fun ReaderToolbar(
    title: String,
    sectionTitle: String?,
    outlineAvailable: Boolean,
    controlsVisible: Boolean,
    pageActionsEnabled: Boolean,
    studyAvailable: Boolean,
    studying: Boolean,
    notesOpen: Boolean,
    annotationCount: Int,
    bookmarked: Boolean,
    onBack: () -> Unit,
    onOutline: () -> Unit,
    onFind: () -> Unit,
    onToggleStudy: () -> Unit,
    onToggleNotes: () -> Unit,
    onToggleBookmark: () -> Unit,
) {
    AppBar(
        title,
        subtitle = sectionTitle,
        onBack = onBack,
        actions = {
            if (outlineAvailable) {
                IconButton(onClick = onOutline) { AppIcon(R.drawable.ic_toc, "Outline") }
            }
            if (controlsVisible) {
                IconButton(enabled = pageActionsEnabled, onClick = onFind) {
                    AppIcon(R.drawable.ic_search, "Find in note")
                }
                if (studyAvailable) {
                    TextButton(enabled = pageActionsEnabled, onClick = onToggleStudy) {
                        Text(if (studying) "Read" else "Study")
                    }
                }
                IconButton(onClick = onToggleNotes) {
                    BadgedBox(badge = { if (annotationCount > 0) Badge { Text("$annotationCount") } }) {
                        AppIcon(
                            R.drawable.ic_notes,
                            if (notesOpen) "Hide highlights and notes" else "Highlights and notes",
                        )
                    }
                }
                IconButton(onClick = onToggleBookmark) {
                    AppIcon(
                        if (bookmarked) R.drawable.ic_bookmark else R.drawable.ic_bookmark_border,
                        if (bookmarked) "Remove bookmark" else "Bookmark",
                    )
                }
            }
        },
    )
}

/** The reader's Ctrl-F controls; [result] is null while the current [query] is still being evaluated. */
@Composable
internal fun ReaderFindBar(
    query: String,
    result: FindResult?,
    onQuery: (String) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val count =
        when {
            query.isEmpty() -> ""
            result == null -> "Searching…"
            result.count == 0 -> "No matches"
            else -> "${result.index + 1} of ${result.count}"
        }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            label = { Text("Find in note") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onNext() }),
            modifier = Modifier.weight(1f).focusRequester(focus),
        )
        Text(count, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 4.dp))
        IconButton(enabled = result?.count?.let { it > 0 } == true, onClick = onPrevious) {
            AppIcon(R.drawable.ic_arrow_up, "Previous match")
        }
        IconButton(enabled = result?.count?.let { it > 0 } == true, onClick = onNext) {
            AppIcon(R.drawable.ic_arrow_down, "Next match")
        }
        IconButton(onClick = onClose) { AppIcon(R.drawable.ic_close, "Close find") }
    }
}

/**
 * The system bars follow the reader's top bar: hidden while reading down the note, shown briefly by a swipe from the
 * edge, and always shown again when the reader is left.
 */
@Composable
internal fun ReadingBars(shown: Boolean) {
    val activity = LocalActivity.current ?: error("ReaderScreen requires an activity")
    val bars = activity.window.insetsController ?: error("ReaderScreen requires a window insets controller")
    DisposableEffect(bars) {
        bars.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose { bars.show(WindowInsets.Type.systemBars()) }
    }
    LaunchedEffect(shown) {
        if (shown) bars.show(WindowInsets.Type.systemBars()) else bars.hide(WindowInsets.Type.systemBars())
    }
}
