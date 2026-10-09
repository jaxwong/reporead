package com.reporead.android.library

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.reporead.android.R
import com.reporead.android.Screen
import com.reporead.android.core.network.Api
import com.reporead.android.core.network.ApiException
import com.reporead.android.core.network.LoadContent
import com.reporead.android.core.network.contract
import com.reporead.android.core.network.describe
import com.reporead.android.core.network.rememberLoad
import com.reporead.android.data.ChangedNote
import com.reporead.android.data.DocumentRow
import com.reporead.android.data.LibraryDao
import com.reporead.android.sync.Sync
import com.reporead.android.ui.AppBar
import com.reporead.android.ui.AppIcon
import com.reporead.android.ui.EmptyState
import com.reporead.android.ui.EntryRow
import com.reporead.android.ui.MenuAction
import com.reporead.android.ui.OverflowMenu
import com.reporead.android.ui.StatusLine
import com.reporead.android.ui.folderOf
import com.reporead.android.ui.sectionTitle
import kotlinx.coroutines.launch
import org.json.JSONObject

private data class Available(val githubRepositoryId: Long, val installationId: Long, val fullName: String,
                             val privateRepository: Boolean, val connectionId: Long?)

/** When a refresh found the note changed; RepoRead does not know the commit time. */
private fun seenChanged(note: ChangedNote) = note.changedAt?.let {
    "Seen changed ${DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)}"
}

/** Runs [block] when the screen appears (and on [key] changes), reporting a failure as text; cached data stays visible. */
@Composable
private fun RefreshOnEntry(key: Any, onFailure: (ApiException) -> Unit, onRefreshing: (Boolean) -> Unit, onStatus: (String?) -> Unit,
                           block: suspend () -> Unit) {
    LaunchedEffect(key) {
        onRefreshing(true)
        try {
            block()
            onStatus(null)
        } catch (error: ApiException) {
            onFailure(error)
            onStatus("Showing saved data. ${error.describe()}")
        } finally {
            onRefreshing(false)
        }
    }
}

private enum class LibraryTab(val label: String, val icon: Int) {
    READING("Reading", R.drawable.ic_book),
    BOOKMARKS("Bookmarks", R.drawable.ic_bookmark),
    PRACTICE("Practice", R.drawable.ic_notes),
    REPOSITORIES("Repositories", R.drawable.ic_folder),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(sync: Sync, dao: LibraryDao, signedIn: Boolean, onFailure: (ApiException) -> Unit, push: (Screen) -> Unit,
                  onSignIn: () -> Unit, onSignOut: () -> Unit, onAccountDeleted: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(LibraryTab.READING) }
    var confirmDeleteAccount by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val repositories by dao.repositories().collectAsState(emptyList())
    if (signedIn) {
        // Pushes pending reading saves and bookmarks, then pulls the server's view: the explicit foreground sync point.
        RefreshOnEntry(refresh, onFailure, { refreshing = it }, { status = it }) {
            sync.refreshRepositories()
            sync.syncLocalChanges()
        }
    }
    Scaffold(
        topBar = {
            AppBar("Library", actions = {
                IconButton(onClick = { push(Screen.Search) }) { AppIcon(R.drawable.ic_search, "Search") }
                OverflowMenu(if (signedIn) listOf(
                    MenuAction("Sync now") { refresh++ },
                    MenuAction("Sign out", onSignOut),
                    MenuAction("Delete account…") { confirmDeleteAccount = true },
                ) else listOf(MenuAction("Sign in with GitHub", onSignIn)))
            })
        },
        bottomBar = {
            NavigationBar {
                for (entry in LibraryTab.entries) {
                    NavigationBarItem(selected = tab == entry, onClick = { tab = entry },
                        icon = { AppIcon(entry.icon, null) }, label = { Text(entry.label) })
                }
            }
        },
        floatingActionButton = {
            if (signedIn && tab == LibraryTab.REPOSITORIES) {
                ExtendedFloatingActionButton(onClick = { push(Screen.Available) }, icon = { AppIcon(R.drawable.ic_add, null) },
                    text = { Text("Add repository") })
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (!signedIn) {
                StatusLine("Signed out. Saved notes are still readable; sign in to refresh and sync.")
                FilledTonalButton(onClick = onSignIn, modifier = Modifier.padding(horizontal = 16.dp)) { Text("Sign in with GitHub") }
            }
            StatusLine(status)
            Row {
                TextButton(onClick = { push(Screen.Review) }) { Text("Review cards") }
                TextButton(onClick = { push(Screen.Notebook) }) { Text("Notebook") }
            }
            val content: @Composable () -> Unit = {
                when (tab) {
                    LibraryTab.READING -> ReadingTab(dao, push)
                    LibraryTab.BOOKMARKS -> BookmarksTab(dao, push)
                    LibraryTab.PRACTICE -> PracticeTab(dao, push)
                    LibraryTab.REPOSITORIES -> RepositoriesTab(repositories, signedIn, push)
                }
            }
            if (signedIn) PullToRefreshBox(isRefreshing = refreshing, onRefresh = { refresh++ }, modifier = Modifier.fillMaxSize()) { content() }
            else Box(Modifier.fillMaxSize()) { content() }
        }
    }
    if (confirmDeleteAccount) {
        DeleteAccountDialog(repositories.map { it.fullName }, onDismiss = { confirmDeleteAccount = false }, onConfirm = {
            confirmDeleteAccount = false
            status = "Deleting account…"
            scope.launch {
                try {
                    sync.deleteAccount()
                    onAccountDeleted()
                } catch (failure: ApiException) {
                    onFailure(failure)
                    status = "Account not deleted; nothing was deleted on this phone. ${failure.describe()}"
                }
            }
        })
    }
}

@Composable
private fun ReadingTab(dao: LibraryDao, push: (Screen) -> Unit) {
    val recent by dao.recentReading(5).collectAsState(emptyList())
    val updated by dao.updatedSinceRead().collectAsState(emptyList())
    val changed by dao.recentlyChanged(5).collectAsState(emptyList())
    val updatedIds = updated.map { it.documentId }.toSet()
    if (recent.isEmpty() && updated.isEmpty() && changed.isEmpty()) {
        EmptyState("Notes you read appear here. Open one from Repositories.")
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        if (recent.isNotEmpty()) {
            sectionTitle("Continue reading")
            items(recent, key = { "recent:${it.documentId}" }) { row ->
                val flags = when {
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
                EntryRow(note.title, folderOf(note.path), R.drawable.ic_description,
                    listOfNotNull(if (note.lastReadBlobSha == null) "Not read yet" else "Read", seenChanged(note))) {
                    push(Screen.Reader(note.documentId, note.title))
                }
            }
        }
    }
}

@Composable
private fun BookmarksTab(dao: LibraryDao, push: (Screen) -> Unit) {
    val bookmarks by dao.bookmarks().collectAsState(emptyList())
    if (bookmarks.isEmpty()) {
        EmptyState("No bookmarks yet. Tap the bookmark icon in a note to keep it here.")
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(bookmarks, key = { "bookmark:${it.documentId}" }) { row ->
            EntryRow(row.title, folderOf(row.path), R.drawable.ic_bookmark, if (row.deleted) listOf("Removed from the repository") else emptyList()) {
                push(Screen.Reader(row.documentId, row.title))
            }
        }
    }
}

@Composable
private fun RepositoriesTab(repositories: List<com.reporead.android.data.RepositoryRow>, signedIn: Boolean, push: (Screen) -> Unit) {
    if (repositories.isEmpty()) {
        EmptyState(if (signedIn) "No repositories connected yet. Tap Add repository." else "No saved repositories.")
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(repositories, key = { "repository:${it.id}" }) { repository ->
            EntryRow(repository.fullName, if (repository.lastSyncedCommitSha == null) "Not refreshed yet" else "${repository.documentCount} notes",
                R.drawable.ic_folder) {
                push(Screen.Folder(repository.id, repository.fullName, ""))
            }
        }
    }
}

@Composable
private fun DeleteAccountDialog(repositories: List<String>, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete your RepoRead account?") },
        text = {
            Text("RepoRead will delete, on its server and this phone, your connections" +
                (if (repositories.isEmpty()) "" else " (${repositories.joinToString()})") +
                ", note lists, reading progress, bookmarks, highlights and notes, cards and review logs, and sign this phone out. This cannot be undone. " +
                "Your GitHub repositories are not changed. To also remove RepoRead's GitHub authorization and App, use GitHub's " +
                "Settings → Applications.")
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete account") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun AvailableScreen(api: Api, onFailure: (ApiException) -> Unit, onBack: () -> Unit, onConnected: (Screen) -> Unit) {
    var reload by remember { mutableIntStateOf(0) }
    var connecting by remember { mutableStateOf<Long?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val load by rememberLoad(reload, onFailure) {
        val json = api.get("/api/repositories/available")
        contract {
            json.getJSONArray("repositories").let { array ->
                List(array.length()) {
                    val item = array.getJSONObject(it)
                    Available(item.getLong("githubRepositoryId"), item.getLong("installationId"), item.getString("fullName"),
                        item.getBoolean("privateRepository"), if (item.isNull("connectionId")) null else item.getLong("connectionId"))
                }
            }
        }
    }
    Scaffold(topBar = { AppBar("Add repository", onBack = onBack) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            StatusLine("Only repositories you granted to the RepoRead GitHub App appear here.")
            StatusLine(error)
            LoadContent(load, onRetry = { reload++ }) { repositories ->
                if (repositories.isEmpty()) {
                    EmptyState("No repositories are available. Grant repositories to the RepoRead GitHub App on GitHub, then try again.")
                } else LazyColumn {
                    items(repositories, key = { it.githubRepositoryId }) { repository ->
                        ListItem(
                            leadingContent = { AppIcon(R.drawable.ic_folder, null) },
                            headlineContent = { Text(repository.fullName) },
                            supportingContent = { Text(if (repository.privateRepository) "Private" else "Public") },
                            trailingContent = {
                                if (repository.connectionId != null) Text("Connected")
                                else Button(enabled = connecting == null, onClick = {
                                    connecting = repository.githubRepositoryId
                                    error = null
                                    scope.launch {
                                        try {
                                            val json = api.post("/api/repositories/${repository.githubRepositoryId}/connect",
                                                JSONObject().put("installationId", repository.installationId))
                                            val id = contract { json.getLong("id") }
                                            val fullName = contract { json.getString("fullName") }
                                            onConnected(Screen.Folder(id, fullName, ""))
                                        } catch (failure: ApiException) {
                                            onFailure(failure)
                                            error = failure.describe()
                                        } finally {
                                            connecting = null
                                        }
                                    }
                                }) { Text(if (connecting == repository.githubRepositoryId) "Connecting…" else "Connect") }
                            },
                        )
                    }
                }
            }
        }
    }
}

/** Immediate subfolders and notes of [folder] ("" is the repository root), derived from the document paths. */
internal fun children(documents: List<DocumentRow>, folder: String): Pair<List<String>, List<DocumentRow>> {
    val prefix = if (folder.isEmpty()) "" else "$folder/"
    val inside = documents.filter { it.path.startsWith(prefix) }
    val folders = inside.mapNotNull { it.path.removePrefix(prefix).substringBefore('/', "").ifEmpty { null } }.distinct().sorted()
    val notes = inside.filter { '/' !in it.path.removePrefix(prefix) }.sortedBy { it.title.lowercase() }
    return folders to notes
}

@Composable
fun FolderScreen(sync: Sync, dao: LibraryDao, signedIn: Boolean, onFailure: (ApiException) -> Unit, screen: Screen.Folder, push: (Screen) -> Unit,
                 onBack: () -> Unit, onDisconnected: () -> Unit) {
    var status by remember { mutableStateOf<String?>(null) }
    /** What disconnecting would delete, fetched for the confirmation; non-null while it is shown. */
    var confirmDisconnect by remember { mutableStateOf<Sync.StoredData?>(null) }
    var disconnecting by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var listed by remember { mutableStateOf(false) }
    /** Notes fetched so far and to fetch, while saving every note; null otherwise. Leaving the screen stops it. */
    var saving by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val scope = rememberCoroutineScope()
    val documents by dao.documents(screen.repositoryId).collectAsState(null)
    val repository by dao.repositories().collectAsState(emptyList())
    val neverRefreshed = repository.firstOrNull { it.id == screen.repositoryId }?.lastSyncedCommitSha == null
    val root = screen.path.isEmpty()
    if (signedIn && root) {
        RefreshOnEntry(screen.repositoryId, onFailure, { refreshing = it }, { status = it }) {
            sync.refreshDocuments(screen.repositoryId)
            listed = true
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
                onFailure(failure)
                status = "Refresh failed; the previous list is unchanged. ${failure.describe()}"
            } finally {
                refreshing = false
            }
        }
    }
    val saveAll = {
        status = null
        saving = 0 to 0
        scope.launch {
            status = try {
                val saved = sync.saveAllNotes(screen.repositoryId) { done, toFetch -> saving = done to toFetch }
                listed = true
                listOfNotNull(
                    "Saved ${saved.fetched} ${if (saved.fetched == 1) "note" else "notes"} on this phone; ${saved.alreadySaved} ${if (saved.alreadySaved == 1) "was" else "were"} already saved.",
                    saved.cannotShow.takeIf { it.isNotEmpty() }?.let { "${it.size} can't be shown (too large): ${it.joinToString()}" },
                ).joinToString(" ")
            } catch (failure: ApiException) {
                onFailure(failure)
                val (done, toFetch) = saving ?: (0 to 0)
                "Stopped after $done of $toFetch notes; those are saved, and saving again continues from there. ${failure.describe()}"
            } finally {
                saving = null
            }
        }
    }
    val busy = refreshing || disconnecting || saving != null
    Scaffold(topBar = {
        AppBar(if (root) screen.repositoryName else screen.path.substringAfterLast('/'),
            if (root) null else "${screen.repositoryName} / ${screen.path}", onBack = onBack, actions = {
                if (signedIn && root) {
                    IconButton(enabled = !busy, onClick = { refreshFromGitHub() }) {
                        AppIcon(R.drawable.ic_refresh, "Refresh from GitHub")
                    }
                    OverflowMenu(listOf(MenuAction("Save all notes on this phone") {
                        if (!busy) saveAll()
                    }, MenuAction("Disconnect…") {
                        if (busy) return@MenuAction
                        status = null
                        scope.launch {
                            try {
                                // Unsent changes are sent first, so the confirmation counts everything that will be deleted.
                                sync.syncLocalChanges()
                                confirmDisconnect = sync.storedData(screen.repositoryId)
                            } catch (failure: ApiException) {
                                onFailure(failure)
                                status = "Can't disconnect now. ${failure.describe()}"
                            }
                        }
                    }))
                }
            })
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            val progress = saving
            when {
                progress != null && progress.second > 0 ->
                    LinearProgressIndicator(progress = { progress.first.toFloat() / progress.second }, modifier = Modifier.fillMaxWidth(), drawStopIndicator = {})
                refreshing || disconnecting || progress != null -> LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            StatusLine(when {
                disconnecting -> "Disconnecting…"
                progress != null && progress.second > 0 -> "Saving notes on this phone: ${progress.first} of ${progress.second}. Leaving this screen stops it."
                progress != null -> "Checking which notes need saving…"
                else -> status
            })
            val rows = documents ?: return@Column
            val (folders, notes) = children(rows, screen.path)
            when {
                rows.isEmpty() && neverRefreshed -> EmptyState("This repository has not been refreshed yet. Tap the refresh icon above.")
                rows.isEmpty() && !listed -> EmptyState("This repository's note list isn't saved on this phone yet.")
                folders.isEmpty() && notes.isEmpty() -> EmptyState(if (root) "This repository has no Markdown notes." else "This folder is empty.")
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(folders, key = { "folder:$it" }) { folder ->
                        EntryRow(folder, null, R.drawable.ic_folder) { push(screen.copy(path = if (root) folder else "${screen.path}/$folder")) }
                    }
                    items(notes, key = { "note:${it.id}" }) { note ->
                        EntryRow(note.title, null, R.drawable.ic_description) { push(Screen.Reader(note.id, note.title)) }
                    }
                }
            }
        }
    }
    confirmDisconnect?.let { stored ->
        AlertDialog(
            onDismissRequest = { confirmDisconnect = null },
            title = { Text("Disconnect ${screen.repositoryName}?") },
            text = {
                Text("RepoRead will delete, on its server and this phone, its list of ${stored.documents} notes, your reading progress on " +
                    "${stored.readingStates}, ${stored.bookmarks} bookmarks, and ${stored.highlights} highlights with their notes, and ${stored.cards} cards with their review logs. " +
                    "This cannot be undone. The repository on GitHub is not changed; to remove " +
                    "RepoRead's access to it, uninstall or reconfigure the RepoRead GitHub App on GitHub.")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDisconnect = null
                    disconnecting = true
                    scope.launch {
                        try {
                            sync.disconnect(screen.repositoryId)
                            onDisconnected()
                        } catch (failure: ApiException) {
                            onFailure(failure)
                            status = "Not disconnected; nothing was deleted on this phone. ${failure.describe()}"
                        } finally {
                            disconnecting = false
                        }
                    }
                }) { Text("Disconnect") }
            },
            dismissButton = { TextButton(onClick = { confirmDisconnect = null }) { Text("Cancel") } },
        )
    }
}
