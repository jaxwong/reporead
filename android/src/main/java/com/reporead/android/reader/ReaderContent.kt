package com.reporead.android.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.reporead.android.Screen
import com.reporead.android.database.AnnotationEntity
import com.reporead.android.database.RepoReadDao
import com.reporead.android.network.describe
import com.reporead.android.ui.LoadContent

/** The opened note, its notices, WebView and optional highlights panel. ReaderScreen owns their state and actions. */
@Composable
internal fun ColumnScope.ReaderContent(
    documentId: Long,
    state: ReaderViewModel.State,
    session: ReaderSession?,
    notesOpen: Boolean,
    annotations: List<AnnotationEntity>,
    dao: RepoReadDao,
    model: ReaderViewModel,
    onOverlay: (ReaderOverlay) -> Unit,
    onReattach: (AnnotationEntity) -> Unit,
    push: (Screen) -> Unit,
) {
    LoadContent(state.page, onRetry = model::retry) { ready ->
        val opened = ready.opened
        // The session was created from this same ready load.
        checkNotNull(session) { "No reader session for documentId=$documentId" }
        opened.staleReason?.let { ReaderNotice("Showing your saved copy. ${it.describe()}") }
        session.restoreNotice?.let { ReaderNotice(it) }
        state.message?.let { ReaderNotice(it) }
        state.reattaching?.let { annotation ->
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Select the passage for “${annotation.exactText}”, then choose Reattach here.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = model::stopReattaching) { Text("Cancel") }
            }
        }
        if (!session.stopped)
            Box(Modifier.fillMaxWidth().weight(1f)) {
                AndroidView(
                    factory = { context -> session.createView(context) },
                    onRelease = { view -> session.release(view) },
                    modifier = Modifier.fillMaxSize(),
                )
                if (!session.pageVisible) Text("Opening note…", Modifier.align(Alignment.Center))
            }
        if (notesOpen) {
            NotesPanel(
                opened.note.documentId,
                annotations,
                opened.note.blobSha,
                session.notShown,
                session.opened,
                dao,
                onReveal = { session.reveal(it.mutationId) },
                onEdit = { onOverlay(ReaderOverlay.EditNote(it)) },
                onDelete = { onOverlay(ReaderOverlay.Delete(it)) },
                onReattach = onReattach,
                onOpenNote = { push(Screen.Reader(it.id, it.title)) },
                modifier = Modifier.fillMaxWidth().weight(0.7f),
            )
        }
    }
}

@Composable
private fun ReaderNotice(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
}
