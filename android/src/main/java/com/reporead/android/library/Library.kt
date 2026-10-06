package com.reporead.android.library

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
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
                       onSignIn: () -> Unit, onSignOut: () -> Unit) {
    var refresh by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf<String?>(null) }
    val repositories by dao.repositories().collectAsState(emptyList())
    val recent by dao.recentReading(5).collectAsState(emptyList())
    val bookmarks by dao.bookmarks().collectAsState(emptyList())
    if (signedIn) {
        // Pushes pending reading saves and bookmarks, then pulls the server's view: the explicit foreground sync point.
        RefreshOnEntry(refresh, onFailure, { status = it }) {
            sync.refreshRepositories()
            sync.syncReading()
        }
    }
    Column(Modifier.fillMaxSize()) {
        Header("Library")
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    ListEntry(row.title, "${row.progressPercent}% · ${row.path}") { push(Screen.Reader(row.documentId, row.title)) }
                }
            }
            if (bookmarks.isNotEmpty()) {
                section("Bookmarks")
                items(bookmarks, key = { "bookmark:${it.documentId}" }) { row ->
                    ListEntry(row.title, row.path) { push(Screen.Reader(row.documentId, row.title)) }
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
        }
    }
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
fun FolderScreen(sync: Sync, dao: LibraryDao, signedIn: Boolean, onFailure: (ApiException) -> Unit, screen: Screen.Folder, push: (Screen) -> Unit) {
    var status by remember { mutableStateOf<String?>(null) }
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
        if (signedIn && screen.path.isEmpty()) {
            Button(enabled = !refreshing, modifier = Modifier.padding(horizontal = 16.dp), onClick = {
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
        }
        Status(status)
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
