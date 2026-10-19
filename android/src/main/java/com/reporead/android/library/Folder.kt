// Owns: Repository-folder presentation and its entry's operations; Sync and WorkManager own the data they change.

package com.reporead.android.library

import android.app.Application
import android.database.sqlite.SQLiteException
import android.util.Log
import androidx.activity.compose.LocalActivity
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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

/** What the folder screen is doing; one at a time, so two of them never overlap. */
internal sealed interface FolderOperation {
    data object Idle : FolderOperation

    /** Reloading the server's library, after asking it to reconcile with GitHub or not. */
    data object Refreshing : FolderOperation

    /** Asking the server what disconnecting would delete. */
    data object Previewing : FolderOperation

    /** The confirmation is shown with what disconnecting deletes; the only owner of that dialog. */
    data class Confirming(val preview: Sync.DisconnectPreview) : FolderOperation

    data object Disconnecting : FolderOperation
}

/**
 * The folder screen's work, kept by its back-stack entry ([entryId]): a rotation or a covering screen neither stops nor
 * repeats it, and its outcome waits for the screen. Leaving the screen cancels a library load or a preview; a
 * disconnect or a refresh from GitHub the server answers is committed here regardless (Sync). [app] reaches Save all's
 * background work.
 */
internal class FolderViewModel(
    private val sync: Sync,
    private val app: Application,
    private val screen: Screen.Folder,
    private val entryId: String,
) : ViewModel() {
    data class State(
        val operation: FolderOperation = FolderOperation.Idle,
        val status: String? = null,
        /** This screen has loaded the library, so an empty list is the server's, not one never saved here. */
        val listed: Boolean = false,
        val stoppingSaveAll: Boolean = false,
        /** The repository is gone; the screen returns to the Library once ([claimDisconnected]). */
        val disconnected: Boolean = false,
    )

    private val current = MutableStateFlow(State())
    val state: StateFlow<State> = current.asStateFlow()

    /** The sign-in state the screen last showed; null before it was first shown. */
    private var shownSignedIn: Boolean? = null
    /** The screen was left for another (not rotated) since it was last shown, so showing it again is a return. */
    private var left = false

    /** Starts [next] only from an operation [allowed] permits; a duplicate or busy request starts nothing. */
    private fun begin(
        next: FolderOperation,
        allowed: (FolderOperation) -> Boolean = { it == FolderOperation.Idle },
    ): Boolean {
        val now = current.value
        val started = allowed(now.operation) && current.compareAndSet(now, now.copy(operation = next, status = null))
        if (!started)
            Log.i("RepoRead", "Folder operation not started; entry=$entryId running=${now.operation} requested=$next")
        return started
    }

    private fun finish(update: (State) -> State) = current.update { update(it).copy(operation = FolderOperation.Idle) }

    /**
     * The screen is shown while [signedIn]. The repository root reloads the library when it is first shown, after a
     * sign-in and when it is returned to — not after a rotation, which [left] tells apart.
     */
    fun shown(signedIn: Boolean) {
        val reason =
            when {
                !signedIn || screen.path.isNotEmpty() -> null
                shownSignedIn == null -> "created"
                shownSignedIn == false -> "signedIn"
                left -> "returned"
                else -> null
            }
        shownSignedIn = signedIn
        left = false
        if (reason != null) refresh(reason, "Showing saved data.") { sync.refreshLibrary() }
    }

    /** The screen left composition for another reason than a configuration change. */
    fun left() {
        left = true
    }

    fun refreshFromGitHub() =
        refresh("refreshFromGitHub", "Refresh failed; the previous list is unchanged.") {
            sync.refreshFromGitHub(screen.repositoryId)
        }

    private fun refresh(reason: String, failed: String, work: suspend () -> Unit) {
        if (!begin(FolderOperation.Refreshing)) return
        Log.i("RepoRead", "Folder refresh started; entry=$entryId repositoryId=${screen.repositoryId} reason=$reason")
        viewModelScope.launch {
            val status =
                try {
                    work()
                    null
                } catch (failure: ApiException) {
                    "$failed ${failure.describe()}"
                }
            finish { it.copy(status = status, listed = it.listed || status == null) }
        }
    }

    fun saveAll() {
        current.update { it.copy(status = null) }
        requestSaveAllNotes(app, screen.repositoryId)
    }

    fun stopSaving() {
        val now = current.value
        if (
            now.stoppingSaveAll ||
                !current.compareAndSet(now, now.copy(stoppingSaveAll = true, status = "Stopping saving notes…"))
        ) {
            Log.i("RepoRead", "Stopping Save all not started again; entry=$entryId")
            return
        }
        viewModelScope.launch {
            val status =
                try {
                    stopSavingNotes(app, screen.repositoryId)
                    "Saving stopped; notes already saved are kept. Saving again fetches only what is missing."
                } catch (error: SQLiteException) {
                    Log.w("RepoRead", "Could not stop Save all; repositoryId=${screen.repositoryId}", error)
                    "Couldn't stop saving notes (${error.javaClass.simpleName}); try Stop saving notes again."
                }
            current.update { it.copy(stoppingSaveAll = false, status = status) }
        }
    }

    /** Asks the server what disconnecting would delete, for the confirmation. */
    fun askToDisconnect() {
        if (!begin(FolderOperation.Previewing)) return
        Log.i("RepoRead", "Disconnect preview started; entry=$entryId repositoryId=${screen.repositoryId}")
        viewModelScope.launch {
            try {
                // Null: the server had already disconnected it, and this phone's copies are now gone too.
                val preview = sync.disconnectPreview(screen.repositoryId)
                if (preview == null) finish { it.copy(disconnected = true) }
                else current.update { it.copy(operation = FolderOperation.Confirming(preview)) }
            } catch (failure: ApiException) {
                finish { it.copy(status = "Can't disconnect now. ${failure.describe()}") }
            }
        }
    }

    fun cancelDisconnect() {
        val now = current.value
        if (now.operation is FolderOperation.Confirming)
            current.compareAndSet(now, now.copy(operation = FolderOperation.Idle))
    }

    fun disconnect() {
        if (!begin(FolderOperation.Disconnecting) { it is FolderOperation.Confirming }) return
        Log.i("RepoRead", "Disconnect started; entry=$entryId repositoryId=${screen.repositoryId}")
        viewModelScope.launch {
            try {
                sync.disconnect(screen.repositoryId)
                finish { it.copy(disconnected = true) }
            } catch (failure: ApiException) {
                // The server may have disconnected it with the answer lost; trying again then reconciles
                // (Sync.disconnect).
                finish {
                    it.copy(
                        status =
                            "Couldn't confirm the disconnect, so nothing was deleted on this phone; try again. ${failure.describe()}"
                    )
                }
            }
        }
    }

    /** True once, for the screen that returns to the Library after the repository went. */
    fun claimDisconnected(): Boolean {
        val now = current.value
        return now.disconnected && current.compareAndSet(now, now.copy(disconnected = false))
    }
}

@Composable
fun FolderScreen(
    sync: Sync,
    dao: LibraryDao,
    signedIn: Boolean,
    entryId: String,
    screen: Screen.Folder,
    push: (Screen) -> Unit,
    onBack: () -> Unit,
    onDisconnected: () -> Unit,
) {
    val context = LocalContext.current
    val model = viewModel { FolderViewModel(sync, context.applicationContext as Application, screen, entryId) }
    val state by model.state.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    DisposableEffect(model) { onDispose { if (activity?.isChangingConfigurations != true) model.left() } }
    LaunchedEffect(signedIn) { model.shown(signedIn) }
    if (state.disconnected)
        LifecycleResumeEffect(Unit) {
            if (model.claimDisconnected()) onDisconnected()
            onPauseOrDispose {}
        }
    // Runs in the background whether or not this screen is open (requestSaveAllNotes); this only shows it.
    val saving by
        remember(screen.repositoryId) { savingNotes(context, screen.repositoryId) }.collectAsStateWithLifecycle(null)
    val savingNow = saving is SavingNotes.Waiting || saving is SavingNotes.Running
    val documents by dao.documents(screen.repositoryId).collectAsStateWithLifecycle(null)
    val repository by dao.repositories().collectAsStateWithLifecycle(emptyList())
    val neverRefreshed = repository.firstOrNull { it.id == screen.repositoryId }?.lastSyncedCommitSha == null
    val root = screen.path.isEmpty()
    val operation = state.operation
    val running = operation == FolderOperation.Refreshing || operation == FolderOperation.Disconnecting
    val busy = operation != FolderOperation.Idle || savingNow
    Scaffold(
        topBar = {
            AppBar(
                if (root) screen.repositoryName else screen.path.substringAfterLast('/'),
                if (root) null else "${screen.repositoryName} / ${screen.path}",
                onBack = onBack,
                actions = {
                    if (signedIn && root) {
                        IconButton(enabled = !busy, onClick = model::refreshFromGitHub) {
                            AppIcon(R.drawable.ic_refresh, "Refresh from GitHub")
                        }
                        OverflowMenu(
                            listOf(
                                MenuAction(if (savingNow) "Stop saving notes" else "Save all notes on this phone") {
                                    if (savingNow) model.stopSaving() else if (!busy) model.saveAll()
                                },
                                MenuAction("Disconnect…") {
                                    if (!busy) model.askToDisconnect()
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
                running || savingNow -> LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            StatusLine(
                when {
                    operation == FolderOperation.Disconnecting -> "Disconnecting…"
                    // The screen's own message includes a cancellation failure while Save all still runs.
                    else -> state.status ?: progress?.let(::describe)
                }
            )
            val rows = documents ?: return@Column
            val (folders, notes) = children(rows, screen.path)
            when {
                rows.isEmpty() && neverRefreshed ->
                    EmptyState("This repository has not been refreshed yet. Tap the refresh icon above.")
                rows.isEmpty() && !state.listed ->
                    EmptyState("This repository's note list isn't saved on this phone yet.")
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
    (operation as? FolderOperation.Confirming)?.let { confirming ->
        DisconnectDialog(
            screen.repositoryName,
            confirming.preview,
            onDismiss = model::cancelDisconnect,
            onConfirm = model::disconnect,
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
