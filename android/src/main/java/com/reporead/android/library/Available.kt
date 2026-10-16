package com.reporead.android.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.reporead.android.R
import com.reporead.android.Screen
import com.reporead.android.core.network.Api
import com.reporead.android.core.network.ApiException
import com.reporead.android.core.network.LoadContent
import com.reporead.android.core.network.contract
import com.reporead.android.core.network.describe
import com.reporead.android.core.network.rememberLoad
import com.reporead.android.core.network.string
import com.reporead.android.ui.AppBar
import com.reporead.android.ui.AppIcon
import com.reporead.android.ui.EmptyState
import com.reporead.android.ui.StatusLine
import kotlinx.coroutines.launch
import org.json.JSONObject

/** A repository both this user and the RepoRead GitHub App can read; [connectionId] is set once it is connected. */
private data class Available(
    val githubRepositoryId: Long,
    val installationId: Long,
    val fullName: String,
    val privateRepository: Boolean,
    val connectionId: Long?,
)

private suspend fun availableRepositories(api: Api): List<Available> {
    val json = api.get("/api/repositories/available")
    return contract {
        val array = json.getJSONArray("repositories")
        List(array.length()) {
            val item = array.getJSONObject(it)
            Available(
                item.getLong("githubRepositoryId"),
                item.getLong("installationId"),
                item.string("fullName"),
                item.getBoolean("privateRepository"),
                if (item.isNull("connectionId")) null else item.getLong("connectionId"),
            )
        }
    }
}

/**
 * Connects [repository] and returns its top folder. Connecting a connected repository again keeps its data and takes
 * GitHub's current name and default branch: the way back after either is renamed on GitHub.
 */
private suspend fun connect(api: Api, repository: Available): Screen.Folder {
    val json =
        api.post(
            "/api/repositories/${repository.githubRepositoryId}/connect",
            JSONObject().put("installationId", repository.installationId),
        )
    return contract { Screen.Folder(json.getLong("id"), json.string("fullName"), "") }
}

@Composable
fun AvailableScreen(api: Api, onBack: () -> Unit, onConnected: (Screen) -> Unit) {
    var reload by remember { mutableIntStateOf(0) }
    // The repository being connected; one at a time.
    var connecting by remember { mutableStateOf<Long?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val load by rememberLoad(reload) { availableRepositories(api) }

    fun connectTo(repository: Available) {
        connecting = repository.githubRepositoryId
        error = null
        scope.launch {
            try {
                onConnected(connect(api, repository))
            } catch (failure: ApiException) {
                error = failure.describe()
            } finally {
                connecting = null
            }
        }
    }

    Scaffold(topBar = { AppBar("Add repository", onBack = onBack) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            StatusLine("Only repositories you granted to the RepoRead GitHub App appear here.")
            StatusLine(error)
            LoadContent(load, onRetry = { reload++ }) { repositories ->
                if (repositories.isEmpty())
                    EmptyState(
                        "No repositories are available. Grant repositories to the RepoRead GitHub App on GitHub, then try again."
                    )
                else
                    LazyColumn {
                        items(repositories, key = { it.githubRepositoryId }) { repository ->
                            AvailableRow(repository, connecting, onConnect = { connectTo(repository) })
                        }
                    }
            }
        }
    }
}

/** A repository with Connect, or Update once connected; [connecting] is the repository being connected, if any. */
@Composable
private fun AvailableRow(repository: Available, connecting: Long?, onConnect: () -> Unit) {
    val connected = repository.connectionId != null
    val visibility = if (repository.privateRepository) "Private" else "Public"
    ListItem(
        leadingContent = { AppIcon(R.drawable.ic_folder, null) },
        headlineContent = { Text(repository.fullName) },
        supportingContent = { Text(if (connected) "$visibility · Connected" else visibility) },
        trailingContent = {
            Button(enabled = connecting == null, onClick = onConnect) {
                Text(
                    when {
                        connecting == repository.githubRepositoryId -> if (connected) "Updating…" else "Connecting…"
                        connected -> "Update"
                        else -> "Connect"
                    }
                )
            }
        },
    )
}
