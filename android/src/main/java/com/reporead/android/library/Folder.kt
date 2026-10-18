// Owns: Repository-folder presentation and save/disconnect controls; Sync and WorkManager own the operations.

package com.reporead.android.library

import android.database.sqlite.SQLiteException
import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.reporead.android.R
import com.reporead.android.Screen
import com.reporead.android.core.network.ApiException
import com.reporead.android.core.network.describe
import com.reporead.android.data.DocumentRow
import com.reporead.android.data.LibraryDao
import com.reporead.android.sync.SavingNotes
import com.reporead.android.sync.Sync
import com.reporead.android.sync.requestSaveAllNotes
import com.reporead.android.sync.savingNotes
import com.reporead.android.sync.stopSavingNotes
import com.reporead.android.ui.AppBar
import com.reporead.android.ui.AppIcon
import com.reporead.android.ui.EmptyState
import com.reporead.android.ui.EntryRow
import com.reporead.android.ui.MenuAction
import com.reporead.android.ui.OverflowMenu
import com.reporead.android.ui.StatusLine
import com.reporead.android.ui.counted
import kotlinx.coroutines.launch

/** Immediate subfolders and notes of [folder] ("" is the repository root), derived from the document paths. */
internal fun children(documents: List<DocumentRow>, folder: String): Pair<List<String>, List<DocumentRow>> {
    val prefix = if (folder.isEmpty()) "" else "$folder/"
    val inside = documents.filter { it.path.startsWith(prefix) }
    val folders =
        inside.mapNotNull { it.path.removePrefix(prefix).substringBefore('/', "").ifEmpty { null } }.distinct().sorted()
    val notes = inside.filter { '/' !in it.path.removePrefix(prefix) }.sortedBy { it.title.lowercase() }
    return folders to notes
}

/** What Save all is doing or did last, for the status line. */
private fun describe(saving: SavingNotes): String =
    when (saving) {
        SavingNotes.Waiting -> "Waiting for a network connection, a retry, or Android to run Save all."
        is SavingNotes.Running ->
            if (saving.toFetch == 0) "Checking which notes need saving…"
            else "Saving notes on this phone: ${saving.done} of ${saving.toFetch}."
        is SavingNotes.Saved ->
            listOfNotNull(
                    "Saved ${counted(saving.fetched, "note")} on this phone; ${saving.alreadySaved} ${if (saving.alreadySaved == 1) "was" else "were"} already saved.",
                    saving.cannotShow
                        .takeIf { it > 0 }
                        ?.let { "$it can't be shown (too large); opening one says why." },
                )
                .joinToString(" ")
        is SavingNotes.Stopped ->
            "Saving stopped after ${saving.done} of ${saving.toFetch} notes; those are saved, and saving again continues from there. " +
                (saving.reason ?: "Something went wrong in RepoRead itself.")
    }

@Composable
fun FolderScreen(
    sync: Sync,
    dao: LibraryDao,
    signedIn: Boolean,
    screen: Screen.Folder,
    push: (Screen) -> Unit,
    onBack: () -> Unit,
    onDisconnected: () -> Unit,
) {
    var status by remember { mutableStateOf<String?>(null) }
    // What disconnecting would delete, fetched for the confirmation; non-null while it is shown.
    var confirmDisconnect by remember { mutableStateOf<Sync.DisconnectPreview?>(null) }
    var disconnecting by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var listed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // Runs in the background whether or not this screen is open (requestSaveAllNotes); this only shows it.
    val saving by remember(screen.repositoryId) { savingNotes(context, screen.repositoryId) }.collectAsState(null)
    val savingNow = saving is SavingNotes.Waiting || saving is SavingNotes.Running
    val documents by dao.documents(screen.repositoryId).collectAsState(null)
    val repository by dao.repositories().collectAsState(emptyList())
    val neverRefreshed = repository.firstOrNull { it.id == screen.repositoryId }?.lastSyncedCommitSha == null
    val root = screen.path.isEmpty()
    if (signedIn && root) {
        RefreshOnEntry(screen.repositoryId, { refreshing = it }, { status = it }) {
            sync.refreshLibrary()
            listed = true
            null
        }
    }
    val refreshFromGitHub = {
        refreshing = true
        status = null
        scope.launch {
            try {
                sync.refreshFromGitHub(screen.repositoryId)
                listed = true
            } catch (failure: ApiException) {
                status = "Refresh failed; the previous list is unchanged. ${failure.describe()}"
            } finally {
                refreshing = false
            }
        }
    }
    val saveAll = {
        status = null
        requestSaveAllNotes(context, screen.repositoryId)
    }
    val stopSaving = {
        status = "Stopping saving notes…"
        scope.launch {
            try {
                stopSavingNotes(context, screen.repositoryId)
                status = "Saving stopped; notes already saved are kept. Saving again fetches only what is missing."
            } catch (error: SQLiteException) {
                Log.w("RepoRead", "Could not stop Save all; repositoryId=${screen.repositoryId}", error)
                status = "Couldn't stop saving notes (${error.javaClass.simpleName}); try Stop saving notes again."
            }
        }
    }
    // Asks the server what disconnecting would delete, for the confirmation.
    val askToDisconnect = {
        status = null
        scope.launch {
            try {
                // Null: the server had already disconnected it, and this phone's copies are now gone too.
                val preview = sync.disconnectPreview(screen.repositoryId)
                if (preview == null) onDisconnected() else confirmDisconnect = preview
            } catch (failure: ApiException) {
                status = "Can't disconnect now. ${failure.describe()}"
            }
        }
    }
    val disconnect = {
        confirmDisconnect = null
        disconnecting = true
        scope.launch {
            try {
                sync.disconnect(screen.repositoryId)
                onDisconnected()
            } catch (failure: ApiException) {
                // The server may have disconnected it with the answer lost; trying again then reconciles
                // (Sync.disconnect).
                status =
                    "Couldn't confirm the disconnect, so nothing was deleted on this phone; try again. ${failure.describe()}"
            } finally {
                disconnecting = false
            }
        }
    }
    val busy = refreshing || disconnecting || savingNow
    Scaffold(
        topBar = {
            AppBar(
                if (root) screen.repositoryName else screen.path.substringAfterLast('/'),
                if (root) null else "${screen.repositoryName} / ${screen.path}",
                onBack = onBack,
                actions = {
                    if (signedIn && root) {
                        IconButton(enabled = !busy, onClick = { refreshFromGitHub() }) {
                            AppIcon(R.drawable.ic_refresh, "Refresh from GitHub")
                        }
                        OverflowMenu(
                            listOf(
                                MenuAction(if (savingNow) "Stop saving notes" else "Save all notes on this phone") {
                                    if (savingNow) stopSaving() else if (!busy) saveAll()
                                },
                                MenuAction("Disconnect…") {
                                    if (!busy) askToDisconnect()
                                },
                            )
                        )
                    }
                },
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            val progress = saving
            when {
                progress is SavingNotes.Running && progress.toFetch > 0 ->
                    LinearProgressIndicator(
                        progress = { progress.done.toFloat() / progress.toFetch },
                        modifier = Modifier.fillMaxWidth(),
                        drawStopIndicator = {},
                    )
                refreshing || disconnecting || savingNow -> LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            StatusLine(
                when {
                    disconnecting -> "Disconnecting…"
                    // The screen's own message includes a cancellation failure while Save all still runs.
                    else -> status ?: progress?.let(::describe)
                }
            )
            val rows = documents ?: return@Column
            val (folders, notes) = children(rows, screen.path)
            when {
                rows.isEmpty() && neverRefreshed ->
                    EmptyState("This repository has not been refreshed yet. Tap the refresh icon above.")
                rows.isEmpty() && !listed -> EmptyState("This repository's note list isn't saved on this phone yet.")
                folders.isEmpty() && notes.isEmpty() ->
                    EmptyState(if (root) "This repository has no Markdown notes." else "This folder is empty.")
                else ->
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(folders, key = { "folder:$it" }) { folder ->
                            EntryRow(folder, null, R.drawable.ic_folder) {
                                push(screen.copy(path = if (root) folder else "${screen.path}/$folder"))
                            }
                        }
                        items(notes, key = { "note:${it.id}" }) { note ->
                            EntryRow(note.title, null, R.drawable.ic_description) {
                                push(Screen.Reader(note.id, note.title))
                            }
                        }
                    }
            }
        }
    }
    confirmDisconnect?.let { preview ->
        DisconnectDialog(
            screen.repositoryName,
            preview,
            onDismiss = { confirmDisconnect = null },
            onConfirm = { disconnect() },
        )
    }
}

/** What disconnecting [repositoryName] deletes, on the server and this phone, before it is confirmed. */
@Composable
private fun DisconnectDialog(
    repositoryName: String,
    preview: Sync.DisconnectPreview,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val stored = preview.stored
    val unsent =
        if (preview.unsent == 0) ""
        else
            "${counted(preview.unsent, "highlight, card, or grade", "highlights, cards, or grades")} only on this phone " +
                "go too${preview.notSent?.let { " (not sent: ${it.describe()})" } ?: ""}. "
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Disconnect $repositoryName?") },
        text = {
            Text(
                "RepoRead will delete, on its server and this phone, its list of ${counted(stored.documents, "note")}, your reading progress on " +
                    "${counted(stored.readingStates, "note")}, ${counted(stored.bookmarks, "bookmark")}, ${counted(stored.highlights, "highlight")} with their notes, " +
                    "and ${counted(stored.cards, "card")} with their review logs. " +
                    unsent +
                    "RepoRead cannot undo this. " +
                    BACKUP_COPIES +
                    "The repository on GitHub is not changed; to remove " +
                    "RepoRead's access to it, uninstall or reconfigure the RepoRead GitHub App on GitHub."
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Disconnect") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
