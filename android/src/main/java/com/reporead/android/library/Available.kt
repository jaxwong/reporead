// Owns: The Add repository screen and its entry's list and connection; Sync saves what connecting made.

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
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.reporead.android.R
import com.reporead.android.Screen
import com.reporead.android.logging.DeviceLog
import com.reporead.android.network.Api
import com.reporead.android.network.ApiException
import com.reporead.android.network.describe
import com.reporead.android.network.wire.RepositoryConnectionsAvailable
import com.reporead.android.network.wire.RepositoryConnectionsConnection
import com.reporead.android.sync.Sync
import com.reporead.android.ui.AppBar
import com.reporead.android.ui.AppIcon
import com.reporead.android.ui.EmptyState
import com.reporead.android.ui.Load
import com.reporead.android.ui.LoadContent
import com.reporead.android.ui.StatusLine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Add repository's list and connection, kept by its back-stack entry ([entryId]): a rotation neither fetches the list
 * again nor cancels a connect, and the connected repository's folder opens once, from the screen then shown. Leaving
 * the screen cancels a list fetch; a connection the server made is still saved on this phone (Sync.connect).
 */
internal class AvailableViewModel(private val api: Api, private val sync: Sync, private val entryId: String) :
    ViewModel() {
    data class State(
        val listing: Load<List<RepositoryConnectionsAvailable>> = Load.Loading,
        /** The repository being connected; one at a time. */
        val connecting: Long? = null,
        val error: String? = null,
        /** Connected and saved here; the screen opens its folder once ([claimConnected]). */
        val connected: RepositoryConnectionsConnection? = null,
    )

    private val current = MutableStateFlow(State())
    val state: StateFlow<State> = current.asStateFlow()

    init {
        list("created")
    }

    fun retry() {
        val now = current.value
        if (now.listing !is Load.Failed || !current.compareAndSet(now, now.copy(listing = Load.Loading))) {
            DeviceLog.info(
                "Available list not fetched again; entry=$entryId listing=${now.listing.javaClass.simpleName}"
            )
            return
        }
        list("retry")
    }

    private fun list(reason: String) {
        DeviceLog.info("Available list started; entry=$entryId reason=$reason")
        viewModelScope.launch {
            val listing =
                try {
                    Load.Ready(api.availableRepositories().repositories)
                } catch (error: ApiException) {
                    Load.Failed(error)
                }
            current.update { it.copy(listing = listing) }
        }
    }

    fun connect(repository: RepositoryConnectionsAvailable) {
        val now = current.value
        if (
            now.connecting != null ||
                !current.compareAndSet(now, now.copy(connecting = repository.githubRepositoryId, error = null))
        ) {
            DeviceLog.info(
                "Connect not started; entry=$entryId connecting=${now.connecting} requested=${repository.githubRepositoryId}"
            )
            return
        }
        DeviceLog.info("Connect started; entry=$entryId githubRepositoryId=${repository.githubRepositoryId}")
        viewModelScope.launch {
            try {
                val connected = sync.connect(repository.githubRepositoryId, repository.installationId)
                current.update { it.copy(connecting = null, connected = connected) }
            } catch (failure: ApiException) {
                current.update { it.copy(connecting = null, error = failure.describe()) }
            }
        }
    }

    /** The connected repository, once, for the screen that opens its folder. */
    fun claimConnected(): RepositoryConnectionsConnection? {
        val now = current.value
        val connected = now.connected ?: return null
        return if (current.compareAndSet(now, now.copy(connected = null))) connected else null
    }
}

@Composable
fun AvailableScreen(api: Api, sync: Sync, entryId: String, onBack: () -> Unit, onConnected: (Screen) -> Unit) {
    val model = viewModel { AvailableViewModel(api, sync, entryId) }
    val state by model.state.collectAsStateWithLifecycle()
    if (state.connected != null)
        LifecycleResumeEffect(Unit) {
            model.claimConnected()?.let { onConnected(Screen.Folder(it.id, it.fullName, "")) }
            onPauseOrDispose {}
        }
    Scaffold(topBar = { AppBar("Add repository", onBack = onBack) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            StatusLine("Only repositories you granted to the RepoRead GitHub App appear here.")
            StatusLine(state.error)
            LoadContent(state.listing, onRetry = model::retry) { repositories ->
                if (repositories.isEmpty())
                    EmptyState(
                        "No repositories are available. Grant repositories to the RepoRead GitHub App on GitHub, then try again."
                    )
                else
                    LazyColumn {
                        items(repositories, key = { it.githubRepositoryId }) { repository ->
                            AvailableRow(repository, state.connecting, onConnect = { model.connect(repository) })
                        }
                    }
            }
        }
    }
}

/** A repository with Connect, or Update once connected; [connecting] is the repository being connected, if any. */
@Composable
private fun AvailableRow(repository: RepositoryConnectionsAvailable, connecting: Long?, onConnect: () -> Unit) {
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
