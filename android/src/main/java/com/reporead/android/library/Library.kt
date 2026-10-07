package com.reporead.android.library

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
import kotlinx.coroutines.launch
import org.json.JSONObject

private data class Available(val githubRepositoryId: Long, val installationId: Long, val fullName: String,
                             val privateRepository: Boolean, val connectionId: Long?)

@Composable
private fun Header(title: String, subtitle: String? = null) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun Status(text: String?) {
    text?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) }
}

@Composable
private fun ListEntry(label: String, detail: String?, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
    HorizontalDivider()
}

/** When a refresh found the note changed; RepoRead does not know the commit time. */
private fun seenChanged(note: ChangedNote) = note.changedAt?.let {
    "seen changed ${DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)} · "
} ?: ""

private fun LazyListScope.section(title: String) {
    item(key = "section:$title") {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))
    }
}

/** Runs [block] when the screen appears (and on [key] changes), reporting a failure as text; cached data stays visible. */
@Composable
private fun RefreshOnEntry(key: Any, onFailure: (ApiException) -> Unit, onStatus: (String?) -> Unit, block: suspend () -> Unit) {
    LaunchedEffect(key) {
        onStatus("Refreshing…")
        try {
            block()
            onStatus(null)
        } catch (error: ApiException) {
            onFailure(error)
            onStatus("Showing saved data. ${error.describe()}")
        }
    }
}

@Composable
fun RepositoriesScreen(sync: Sync, dao: LibraryDao, signedIn: Boolean, onFailure: (ApiException) -> Unit, push: (Screen) -> Unit,
                       onSignIn: () -> Unit, onSignOut: () -> Unit, onAccountDeleted: () -> Unit) {
    var confirmDeleteAccount by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf<String?>(null) }
    val repositories by dao.repositories().collectAsState(emptyList())
    val recent by dao.recentReading(5).collectAsState(emptyList())
    val bookmarks by dao.bookmarks().collectAsState(emptyList())
    val updated by dao.updatedSinceRead().collectAsState(emptyList())
    val changed by dao.recentlyChanged(5).collectAsState(emptyList())
    val updatedIds = updated.map { it.documentId }.toSet()
    if (signedIn) {
        // Pushes pending reading saves and bookmarks, then pulls the server's view: the explicit foreground sync point.
        RefreshOnEntry(refresh, onFailure, { status = it }) {
            sync.refreshRepositories()
            sync.syncLocalChanges()
        }
    }
    Column(Modifier.fillMaxSize()) {
        Header("Library")
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { push(Screen.Search) }) { Text("Search") }
            if (signedIn) {
                Button(onClick = { push(Screen.Available) }) { Text("Add repository") }
                OutlinedButton(onClick = { refresh++ }) { Text("Sync") }
                OutlinedButton(onClick = onSignOut) { Text("Sign out") }
            } else {
                Button(onClick = onSignIn) { Text("Sign in with GitHub") }
            }
        }
        Status(if (signedIn) status else "Signed out. Saved notes are still readable; sign in to refresh and sync.")
        LazyColumn {
            if (recent.isNotEmpty()) {
                section("Continue reading")
                items(recent, key = { "recent:${it.documentId}" }) { row ->
                    val state = when {
                        row.deleted -> "Removed from the repository · "
                        row.documentId in updatedIds -> "Updated since you read · "
                        else -> ""
                    }
                    ListEntry(row.title, state + "${row.progressPercent}% · ${row.path}") { push(Screen.Reader(row.documentId, row.title)) }
                }
            }
            if (updated.isNotEmpty()) {
                section("Updated since you read")
                items(updated, key = { "updated:${it.documentId}" }) { note ->
                    ListEntry(note.title, seenChanged(note) + note.path) { push(Screen.Reader(note.documentId, note.title)) }
                }
            }
            if (changed.isNotEmpty()) {
                section("Recently changed")
                items(changed, key = { "changed:${it.documentId}" }) { note ->
                    ListEntry(note.title, (if (note.lastReadBlobSha == null) "Not read yet · " else "Read · ") + seenChanged(note) + note.path) {
                        push(Screen.Reader(note.documentId, note.title))
                    }
                }
            }
            if (bookmarks.isNotEmpty()) {
                section("Bookmarks")
                items(bookmarks, key = { "bookmark:${it.documentId}" }) { row ->
                    ListEntry(row.title, (if (row.deleted) "Removed from the repository · " else "") + row.path) {
                        push(Screen.Reader(row.documentId, row.title))
                    }
                }
            }
            section("Repositories")
            if (repositories.isEmpty()) {
                item(key = "repositories:empty") {
                    Text(if (signedIn) "No repositories connected yet. Tap Add repository." else "No saved repositories.", Modifier.padding(16.dp))
                }
            }
            items(repositories, key = { "repository:${it.id}" }) { repository ->
                ListEntry(repository.fullName, if (repository.lastSyncedCommitSha == null) "Not refreshed yet" else "${repository.documentCount} notes") {
                    push(Screen.Folder(repository.id, repository.fullName, ""))
                }
            }
            if (signedIn) {
                section("Account")
                item(key = "account:delete") {
                    TextButton(onClick = { confirmDeleteAccount = true }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Delete account…") }
                }
            }
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
private fun DeleteAccountDialog(repositories: List<String>, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete your RepoRead account?") },
        text = {
            Text("RepoRead will delete, on its server and this phone, your connections" +
                (if (repositories.isEmpty()) "" else " (${repositories.joinToString()})") +
                ", note lists, reading progress, bookmarks, highlights and notes, and sign this phone out. This cannot be undone. " +
                "Your GitHub repositories are not changed. To also remove RepoRead's GitHub authorization and App, use GitHub's " +
                "Settings → Applications.")
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete account") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun AvailableScreen(api: Api, onFailure: (ApiException) -> Unit, onConnected: (Screen) -> Unit) {
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
    Column(Modifier.fillMaxSize()) {
        Header("Add repository", "Only repositories you granted to the RepoRead GitHub App appear here.")
        Status(error)
        LoadContent(load, onRetry = { reload++ }) { repositories ->
            if (repositories.isEmpty()) {
                Text("No repositories are available. Grant repositories to the RepoRead GitHub App on GitHub, then try again.",
                    Modifier.padding(16.dp))
            } else LazyColumn {
                items(repositories, key = { it.githubRepositoryId }) { repository ->
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(repository.fullName)
                            Text(if (repository.privateRepository) "Private" else "Public", style = MaterialTheme.typography.bodySmall)
                        }
                        if (repository.connectionId != null) {
                            Text("Connected")
                        } else Button(enabled = connecting == null, onClick = {
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
                    }
                    HorizontalDivider()
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
                 onDisconnected: () -> Unit) {
    var status by remember { mutableStateOf<String?>(null) }
    /** What disconnecting would delete, fetched for the confirmation; non-null while it is shown. */
    var confirmDisconnect by remember { mutableStateOf<Sync.StoredData?>(null) }
    var disconnecting by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var listed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val documents by dao.documents(screen.repositoryId).collectAsState(null)
    val repository by dao.repositories().collectAsState(emptyList())
    val neverRefreshed = repository.firstOrNull { it.id == screen.repositoryId }?.lastSyncedCommitSha == null
    if (signedIn && screen.path.isEmpty()) {
        RefreshOnEntry(screen.repositoryId, onFailure, { status = it }) {
            sync.refreshDocuments(screen.repositoryId)
            listed = true
        }
    }
    Column(Modifier.fillMaxSize()) {
        Header(if (screen.path.isEmpty()) screen.repositoryName else screen.path.substringAfterLast('/'),
            if (screen.path.isEmpty()) null else "${screen.repositoryName} / ${screen.path}")
        if (signedIn && screen.path.isEmpty()) Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !refreshing && !disconnecting, onClick = {
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
            }) { Text(if (refreshing) "Refreshing…" else "Refresh from GitHub") }
            OutlinedButton(enabled = !refreshing && !disconnecting, onClick = {
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
            }) { Text("Disconnect…") }
        }
        Status(status)
        confirmDisconnect?.let { stored ->
            AlertDialog(
                onDismissRequest = { confirmDisconnect = null },
                title = { Text("Disconnect ${screen.repositoryName}?") },
                text = {
                    Text("RepoRead will delete, on its server and this phone, its list of ${stored.documents} notes, your reading progress on " +
                        "${stored.readingStates}, ${stored.bookmarks} bookmarks, and ${stored.highlights} highlights with their notes. " +
                        "This cannot be undone. The repository on GitHub is not changed; to remove " +
                        "RepoRead's access to it, uninstall or reconfigure the RepoRead GitHub App on GitHub.")
                },
                confirmButton = {
                    TextButton(onClick = {
                        confirmDisconnect = null
                        disconnecting = true
                        status = "Disconnecting…"
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
        val rows = documents ?: return@Column
        val (folders, notes) = children(rows, screen.path)
        when {
            rows.isEmpty() && neverRefreshed -> Text("This repository has not been refreshed yet. Tap Refresh from GitHub.", Modifier.padding(16.dp))
            rows.isEmpty() && !listed -> Text("This repository's note list isn't saved on this phone yet.", Modifier.padding(16.dp))
            folders.isEmpty() && notes.isEmpty() ->
                Text(if (screen.path.isEmpty()) "This repository has no Markdown notes." else "This folder is empty.", Modifier.padding(16.dp))
            else -> LazyColumn {
                items(folders, key = { "folder:$it" }) { folder ->
                    ListEntry("📁 $folder", null) { push(screen.copy(path = if (screen.path.isEmpty()) folder else "${screen.path}/$folder")) }
                }
                items(notes, key = { "note:${it.id}" }) { note -> ListEntry(note.title, null) { push(Screen.Reader(note.id, note.title)) } }
            }
        }
    }
}
