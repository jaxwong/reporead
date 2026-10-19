// Owns: Library presentation and its entry's sync and confirmations; Sync owns synchronization decisions.

package com.reporead.android.library

import android.text.format.DateUtils
import android.util.Log
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.reporead.android.R
import com.reporead.android.Screen
import com.reporead.android.core.network.ApiException
import com.reporead.android.core.network.describe
import com.reporead.android.data.ChangedNote
import com.reporead.android.data.LibraryDao
import com.reporead.android.data.RepositoryRow
import com.reporead.android.sync.RefusedChange
import com.reporead.android.sync.Sync
import com.reporead.android.ui.AppBar
import com.reporead.android.ui.AppIcon
import com.reporead.android.ui.EmptyState
import com.reporead.android.ui.EntryRow
import com.reporead.android.ui.MenuAction
import com.reporead.android.ui.OverflowMenu
import com.reporead.android.ui.StatusLine
import com.reporead.android.ui.counted
import com.reporead.android.ui.folderOf
import com.reporead.android.ui.sectionTitle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** When a refresh found the note changed; RepoRead does not know the commit time. */
private fun seenChanged(note: ChangedNote) =
    note.changedAt?.let {
        "Seen changed ${DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)}"
    }

/** Renders a refused-operation snapshot; no UI sentence is carried by the synchronization result. */
internal fun RefusedChange.describe(): String {
    val note = title?.let { "“$it”" } ?: "a note no longer in your lists"
    val subject =
        when (kind) {
            RefusedChange.Kind.READING -> "Your place in $note"
            RefusedChange.Kind.BOOKMARK -> "The bookmark on $note"
        }
    return "$subject (${error.describe()})"
}

/** Owns the library's tab labels and icons, not synchronization state. */
private enum class LibraryTab(val label: String, val icon: Int) {
    READING("Reading", R.drawable.ic_book),
    BOOKMARKS("Bookmarks", R.drawable.ic_bookmark),
    PRACTICE("Practice", R.drawable.ic_notes),
    REPOSITORIES("Repositories", R.drawable.ic_folder),
}

/**
 * The Library's sync and sign-out confirmation, kept by its back-stack entry ([entryId]): a rotation neither stops nor
 * repeats the sync, and what it reported stays shown. Leaving the Library cancels a sync still running; its sends
 * commit what the server answered (Sync).
 */
internal class LibraryViewModel(private val sync: Sync, private val entryId: String) : ViewModel() {
    data class State(
        val syncing: Boolean = false,
        val status: String? = null,
        val confirmSignOut: Boolean = false,
    )

    private val current = MutableStateFlow(State())
    val state: StateFlow<State> = current.asStateFlow()

    /** The sign-in state the screen last showed; null before it was first shown. */
    private var shownSignedIn: Boolean? = null
    /** The screen was left for another (not rotated) since it was last shown, so showing it again is a return. */
    private var left = false

    /** The screen is shown while [signedIn]: it syncs when first shown, after a sign-in and when returned to. */
    fun shown(signedIn: Boolean) {
        val reason =
            when {
                !signedIn -> null
                shownSignedIn == null -> "created"
                shownSignedIn == false -> "signedIn"
                left -> "returned"
                else -> null
            }
        shownSignedIn = signedIn
        left = false
        if (reason != null) syncNow(reason)
    }

    /** The screen left composition for another reason than a configuration change. */
    fun left() {
        left = true
    }

    /** Pushes pending changes, then pulls the server's view; the background sync sends changes without it (ADR-08). */
    fun syncNow(reason: String = "asked") {
        val now = current.value
        if (now.syncing || !current.compareAndSet(now, now.copy(syncing = true))) {
            Log.i("RepoRead", "Library sync not started; entry=$entryId reason=$reason running=true")
            return
        }
        Log.i("RepoRead", "Library sync started; entry=$entryId reason=$reason")
        viewModelScope.launch {
            val status =
                try {
                    val synced = sync.syncLocalChanges()
                    listOfNotNull(
                            synced.notSent?.let {
                                "New highlights and cards stay on this phone for now. ${it.describe()}"
                            },
                            synced.refused
                                .takeIf { it.isNotEmpty() }
                                ?.let {
                                    "The server refused: ${it.joinToString("; ") { item -> item.describe() }}. A refused place stays on this phone."
                                },
                        )
                        .joinToString(" ")
                        .ifEmpty { null }
                } catch (error: ApiException) {
                    "Showing saved data. ${error.describe()}"
                }
            current.update { it.copy(syncing = false, status = status) }
        }
    }

    fun askToSignOut() = current.update { it.copy(confirmSignOut = true) }

    fun closeSignOut() = current.update { it.copy(confirmSignOut = false) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    sync: Sync,
    dao: LibraryDao,
    signedIn: Boolean,
    entryId: String,
    push: (Screen) -> Unit,
    notice: String?,
    onDismissNotice: () -> Unit,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onDeleteAccount: () -> Unit,
) {
    val model = viewModel { LibraryViewModel(sync, entryId) }
    val state by model.state.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    DisposableEffect(model) { onDispose { if (activity?.isChangingConfigurations != true) model.left() } }
    LaunchedEffect(signedIn) { model.shown(signedIn) }
    var tab by rememberSaveable { mutableStateOf(LibraryTab.READING) }
    var confirmDeleteAccount by rememberSaveable { mutableStateOf(false) }
    val repositories by dao.repositories().collectAsStateWithLifecycle(emptyList())
    val waiting by dao.waitingCount().collectAsStateWithLifecycle(0)
    val outlook = rememberReviewOutlook(dao)
    val deleteAccount = {
        confirmDeleteAccount = false
        onDeleteAccount()
    }
    Scaffold(
        topBar = {
            AppBar(
                "Library",
                actions = {
                    IconButton(onClick = { push(Screen.Search) }) { AppIcon(R.drawable.ic_search, "Search") }
                    val signOut = MenuAction("Sign out…", model::askToSignOut)
                    OverflowMenu(
                        if (signedIn) listOf(signOut, MenuAction("Delete account…") { confirmDeleteAccount = true })
                        else listOf(MenuAction("Sign in with GitHub", onSignIn))
                    )
                },
            )
        },
        bottomBar = {
            NavigationBar {
                for (entry in LibraryTab.entries) {
                    // Practice carries the review cards due now, so they are seen without opening it.
                    val due = if (entry == LibraryTab.PRACTICE) outlook?.due ?: 0 else 0
                    NavigationBarItem(
                        selected = tab == entry,
                        onClick = { tab = entry },
                        label = { Text(entry.label) },
                        icon = {
                            BadgedBox(badge = { if (due > 0) Badge { Text("$due") } }) { AppIcon(entry.icon, null) }
                        },
                    )
                }
            }
        },
        floatingActionButton = {
            if (signedIn && tab == LibraryTab.REPOSITORIES) {
                ExtendedFloatingActionButton(
                    onClick = { push(Screen.Available) },
                    // Material clears the text's semantics, so the icon names the button for accessibility.
                    icon = { AppIcon(R.drawable.ic_add, "Add repository") },
                    text = { Text("Add repository") },
                )
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (!signedIn) {
                StatusLine("GitHub sign-in needed. Saved notes are still readable; sign in to refresh and sync.")
                FilledTonalButton(onClick = onSignIn, modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text("Sign in with GitHub")
                }
            }
            notice?.let {
                StatusLine(it)
                TextButton(onClick = onDismissNotice) { Text("Dismiss account message") }
            }
            StatusLine(state.status)
            if (signedIn && waiting > 0) {
                // The background sync sends these once the phone is online; Sync now sends them while you watch.
                Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${counted(waiting, "change")} waiting to sync",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { model.syncNow() }, enabled = !state.syncing) { Text("Sync now") }
                }
            }
            TextButton(onClick = { push(Screen.Notebook) }) { Text("Notebook") }
            val content: @Composable () -> Unit = {
                when (tab) {
                    LibraryTab.READING -> ReadingTab(dao, push)
                    LibraryTab.BOOKMARKS -> BookmarksTab(dao, push)
                    LibraryTab.PRACTICE -> PracticeTab(dao, push)
                    LibraryTab.REPOSITORIES -> RepositoriesTab(repositories, signedIn, push)
                }
            }
            if (signedIn)
                PullToRefreshBox(
                    isRefreshing = state.syncing,
                    onRefresh = { model.syncNow() },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    content()
                }
            else Box(Modifier.fillMaxSize()) { content() }
        }
    }
    if (confirmDeleteAccount) {
        DeleteAccountDialog(
            repositories.map { it.fullName },
            onDismiss = { confirmDeleteAccount = false },
            onConfirm = { deleteAccount() },
        )
    }
    if (state.confirmSignOut) {
        SignOutDialog(
            onDismiss = model::closeSignOut,
            onConfirm = {
                model.closeSignOut()
                onSignOut()
            },
        )
    }
}

/** Signing out ends the session, not the local copy. */
@Composable
private fun SignOutDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sign out of RepoRead?") },
        text = { Text("Saved notes and changes stay on this phone. Syncing waits until you sign in again.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Sign out") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ReadingTab(dao: LibraryDao, push: (Screen) -> Unit) {
    val recent by dao.recentReading(5).collectAsStateWithLifecycle(emptyList())
    val updated by dao.updatedSinceRead().collectAsStateWithLifecycle(emptyList())
    val changed by dao.recentlyChanged(5).collectAsStateWithLifecycle(emptyList())
    val updatedIds = updated.map { it.documentId }.toSet()
    if (recent.isEmpty() && updated.isEmpty() && changed.isEmpty()) {
        EmptyState("Notes you read appear here. Open one from Repositories.")
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        if (recent.isNotEmpty()) {
            sectionTitle("Continue reading")
            items(recent, key = { "recent:${it.documentId}" }) { row ->
                val flags =
                    when {
                        row.deleted -> listOf("Removed from the repository")
                        row.documentId in updatedIds -> listOf("Updated since you read")
                        else -> emptyList()
                    }
                EntryRow(row.title, folderOf(row.path), R.drawable.ic_book, flags, progress = row.progressPercent) {
                    push(Screen.Reader(row.documentId, row.title))
                }
            }
        }
        if (updated.isNotEmpty()) {
            sectionTitle("Updated since you read")
            items(updated, key = { "updated:${it.documentId}" }) { note ->
                EntryRow(note.title, folderOf(note.path), R.drawable.ic_description, listOfNotNull(seenChanged(note))) {
                    push(Screen.Reader(note.documentId, note.title))
                }
            }
        }
        if (changed.isNotEmpty()) {
            sectionTitle("Recently changed")
            items(changed, key = { "changed:${it.documentId}" }) { note ->
                EntryRow(
                    note.title,
                    folderOf(note.path),
                    R.drawable.ic_description,
                    listOfNotNull(if (note.lastReadBlobSha == null) "Not read yet" else "Read", seenChanged(note)),
                ) {
                    push(Screen.Reader(note.documentId, note.title))
                }
            }
        }
    }
}

@Composable
private fun BookmarksTab(dao: LibraryDao, push: (Screen) -> Unit) {
    val bookmarks by dao.bookmarks().collectAsStateWithLifecycle(emptyList())
    if (bookmarks.isEmpty()) {
        EmptyState("No bookmarks yet. Tap the bookmark icon in a note to keep it here.")
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(bookmarks, key = { "bookmark:${it.documentId}" }) { row ->
            EntryRow(
                row.title,
                folderOf(row.path),
                R.drawable.ic_bookmark,
                if (row.deleted) listOf("Removed from the repository") else emptyList(),
            ) {
                push(Screen.Reader(row.documentId, row.title))
            }
        }
    }
}

@Composable
private fun RepositoriesTab(
    repositories: List<RepositoryRow>,
    signedIn: Boolean,
    push: (Screen) -> Unit,
) {
    if (repositories.isEmpty()) {
        EmptyState(if (signedIn) "No repositories connected yet. Tap Add repository." else "No saved repositories.")
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(repositories, key = { "repository:${it.id}" }) { repository ->
            EntryRow(
                repository.fullName,
                if (repository.lastSyncedCommitSha == null) "Not refreshed yet"
                else "${repository.documentCount} notes",
                R.drawable.ic_folder,
            ) {
                push(Screen.Folder(repository.id, repository.fullName, ""))
            }
        }
    }
}

/**
 * What a disconnect or account deletion leaves behind: the backups of the server's database already taken. Their
 * retention is owned by backend/deploy and described in backend/README.md (Backups), so it is not repeated here.
 */
internal const val BACKUP_COPIES =
    "Backups of RepoRead's database taken before now still hold a copy until they are replaced; backups taken by hand " +
        "before a migration are kept until deleted by hand. "

@Composable
private fun DeleteAccountDialog(repositories: List<String>, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete your RepoRead account?") },
        text = {
            Text(
                "RepoRead will delete, on its server, your connections" +
                    (if (repositories.isEmpty()) "" else " (${repositories.joinToString()})") +
                    ", note lists, reading progress, bookmarks, highlights and notes, cards and review logs, and sign this phone out. Saved notes and changes stay on this phone. RepoRead cannot undo the server deletion. " +
                    BACKUP_COPIES +
                    "Your GitHub repositories are not changed. RepoRead asks GitHub to revoke its authorization; to also uninstall " +
                    "the RepoRead App, use GitHub's Settings → Applications."
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete account") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
