package com.reporead.android.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import kotlinx.coroutines.launch
import org.json.JSONObject

private data class Connection(val id: Long, val fullName: String, val documentCount: Int, val lastSyncedCommitSha: String?)
private data class Available(val githubRepositoryId: Long, val installationId: Long, val fullName: String,
                             val privateRepository: Boolean, val connectionId: Long?)
internal data class DocumentSummary(val id: Long, val path: String, val title: String)
private data class DocumentList(val lastSyncedCommitSha: String?, val documents: List<DocumentSummary>)

private fun JSONObject.nullableString(name: String): String? = if (isNull(name)) null else getString(name)

private fun connection(json: JSONObject) = Connection(json.getLong("id"), json.getString("fullName"),
    json.getInt("documentCount"), json.nullableString("lastSyncedCommitSha"))

@Composable
private fun Header(title: String, subtitle: String? = null) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun ListEntry(label: String, detail: String?, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
    HorizontalDivider()
}

@Composable
fun RepositoriesScreen(api: Api, onFailure: (ApiException) -> Unit, push: (Screen) -> Unit, onSignOut: () -> Unit) {
    var reload by remember { mutableIntStateOf(0) }
    val load by rememberLoad(reload, onFailure) {
        val json = api.get("/api/repositories")
        contract { json.getJSONArray("repositories").let { array -> List(array.length()) { connection(array.getJSONObject(it)) } } }
    }
    Column(Modifier.fillMaxSize()) {
        Header("Library")
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { push(Screen.Available) }) { Text("Add repository") }
            OutlinedButton(onClick = onSignOut) { Text("Sign out") }
        }
        LoadContent(load, onRetry = { reload++ }) { connections ->
            if (connections.isEmpty()) {
                Text("No repositories connected yet. Tap Add repository.", Modifier.padding(16.dp))
            } else LazyColumn {
                items(connections, key = { it.id }) { repository ->
                    ListEntry(repository.fullName,
                        if (repository.lastSyncedCommitSha == null) "Not refreshed yet" else "${repository.documentCount} notes") {
                        push(Screen.Folder(repository.id, repository.fullName, ""))
                    }
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
        error?.let { Text(it, Modifier.padding(horizontal = 16.dp)) }
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
                                    val connected = contract { connection(json) }
                                    onConnected(Screen.Folder(connected.id, connected.fullName, ""))
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
internal fun children(documents: List<DocumentSummary>, folder: String): Pair<List<String>, List<DocumentSummary>> {
    val prefix = if (folder.isEmpty()) "" else "$folder/"
    val inside = documents.filter { it.path.startsWith(prefix) }
    val folders = inside.mapNotNull { it.path.removePrefix(prefix).substringBefore('/', "").ifEmpty { null } }.distinct().sorted()
    val notes = inside.filter { '/' !in it.path.removePrefix(prefix) }.sortedBy { it.title.lowercase() }
    return folders to notes
}

@Composable
fun FolderScreen(api: Api, onFailure: (ApiException) -> Unit, screen: Screen.Folder, push: (Screen) -> Unit) {
    var reload by remember { mutableIntStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    var refreshError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val load by rememberLoad(reload, onFailure) {
        val json = api.get("/api/repositories/${screen.repositoryId}/documents")
        contract {
            val array = json.getJSONArray("documents")
            DocumentList(json.nullableString("lastSyncedCommitSha"), List(array.length()) {
                val item = array.getJSONObject(it)
                DocumentSummary(item.getLong("id"), item.getString("path"), item.getString("title"))
            })
        }
    }
    Column(Modifier.fillMaxSize()) {
        Header(if (screen.path.isEmpty()) screen.repositoryName else screen.path.substringAfterLast('/'),
            if (screen.path.isEmpty()) null else "${screen.repositoryName} / ${screen.path}")
        if (screen.path.isEmpty()) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(enabled = !refreshing, onClick = {
                    refreshing = true
                    refreshError = null
                    scope.launch {
                        try {
                            api.post("/api/repositories/${screen.repositoryId}/sync")
                            reload++
                        } catch (failure: ApiException) {
                            onFailure(failure)
                            refreshError = "Refresh failed; the previous list is unchanged. ${failure.describe()}"
                        } finally {
                            refreshing = false
                        }
                    }
                }) { Text(if (refreshing) "Refreshing…" else "Refresh from GitHub") }
            }
            refreshError?.let { Text(it, Modifier.padding(16.dp)) }
        }
        LoadContent(load, onRetry = { reload++ }) { list ->
            val (folders, notes) = children(list.documents, screen.path)
            when {
                list.lastSyncedCommitSha == null -> Text("This repository has not been refreshed yet. Tap Refresh from GitHub.", Modifier.padding(16.dp))
                folders.isEmpty() && notes.isEmpty() ->
                    Text(if (screen.path.isEmpty()) "This repository has no Markdown notes." else "This folder is empty.", Modifier.padding(16.dp))
                else -> LazyColumn {
                    items(folders, key = { "folder:$it" }) { folder ->
                        ListEntry("📁 $folder", null) {
                            push(screen.copy(path = if (screen.path.isEmpty()) folder else "${screen.path}/$folder"))
                        }
                    }
                    items(notes, key = { "note:${it.id}" }) { note -> ListEntry(note.title, null) { push(Screen.Reader(note.id, note.title)) } }
                }
            }
        }
    }
}
