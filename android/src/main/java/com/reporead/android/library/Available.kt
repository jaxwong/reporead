// Owns: The Add repository screen and its entry's list and connection; PhoneSync saves what connecting made.

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
import com.reporead.android.sync.PhoneSync
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
 * the screen cancels a list fetch; a connection the server made is still saved on this phone (PhoneSync.connect).
 */
internal class AvailableViewModel(private val api: Api, private val sync: PhoneSync, private val entryId: String) :
    ViewModel() {
    data class State(
        val listing: Load<List<RepositoryConnectionsAvailable>> = Load.Loading,
        /** The repository being connected; one at a time. */
        val connectingRepositoryId: Long? = null,
        val error: String? = null,
        /** Connected and saved here; the screen opens its folder once ([claimConnected]). */
        val connectedRepository: RepositoryConnectionsConnection? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        list("created")
    }

    fun retry() {
        val stateSnapshot = _state.value
        if (
            stateSnapshot.listing !is Load.Failed ||
                !_state.compareAndSet(stateSnapshot, stateSnapshot.copy(listing = Load.Loading))
        ) {
            DeviceLog.info(
                "Available list not fetched again; entry=$entryId listing=${stateSnapshot.listing.javaClass.simpleName}"
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
                } catch (failure: ApiException) {
                    Load.Failed(failure)
                }
            _state.update { it.copy(listing = listing) }
        }
    }

    fun connect(repository: RepositoryConnectionsAvailable) {
        val stateSnapshot = _state.value
        if (
            stateSnapshot.connectingRepositoryId != null ||
                !_state.compareAndSet(
                    stateSnapshot,
                    stateSnapshot.copy(connectingRepositoryId = repository.githubRepositoryId, error = null),
                )
        ) {
            DeviceLog.info(
                "Connect not started; entry=$entryId connectingRepositoryId=${stateSnapshot.connectingRepositoryId} requested=${repository.githubRepositoryId}"
            )
            return
        }
        DeviceLog.info("Connect started; entry=$entryId githubRepositoryId=${repository.githubRepositoryId}")
        viewModelScope.launch {
            try {
                val connection = sync.connect(repository.githubRepositoryId, repository.installationId)
                _state.update { it.copy(connectingRepositoryId = null, connectedRepository = connection) }
            } catch (failure: ApiException) {
                _state.update { it.copy(connectingRepositoryId = null, error = failure.describe()) }
            }
        }
    }

    /** The connected repository, once, for the screen that opens its folder. */
    fun claimConnected(): RepositoryConnectionsConnection? {
        val stateSnapshot = _state.value
        val connectedRepository = stateSnapshot.connectedRepository ?: return null
        return if (_state.compareAndSet(stateSnapshot, stateSnapshot.copy(connectedRepository = null)))
            connectedRepository
        else null
    }
}

@Composable
fun AvailableScreen(api: Api, sync: PhoneSync, entryId: String, onBack: () -> Unit, onConnected: (Screen) -> Unit) {
    val model = viewModel { AvailableViewModel(api, sync, entryId) }
    val state by model.state.collectAsStateWithLifecycle()
    if (state.connectedRepository != null)
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
                            AvailableRow(
                                repository,
                                state.connectingRepositoryId,
                                onConnect = { model.connect(repository) },
                            )
                        }
                    }
            }
        }
    }
}

/**
 * A repository with Connect, or Update once GitHub renamed a connected one; a connected repository with current names
 * has nothing to do here. [connectingRepositoryId] names the repository being connected, if any.
 */
@Composable
private fun AvailableRow(
    repository: RepositoryConnectionsAvailable,
    connectingRepositoryId: Long?,
    onConnect: () -> Unit,
) {
    val isConnected = repository.connectionId != null
    val visibility = if (repository.privateRepository) "Private" else "Public"
    ListItem(
        leadingContent = { AppIcon(R.drawable.ic_folder, null) },
        headlineContent = { Text(repository.fullName) },
        supportingContent = {
            Text(
                when {
                    repository.outdated -> "$visibility · Renamed on GitHub"
                    isConnected -> "$visibility · Connected"
                    else -> visibility
                }
            )
        },
        trailingContent =
            if (isConnected && !repository.outdated) null
            else {
                {
                    Button(enabled = connectingRepositoryId == null, onClick = onConnect) {
                        Text(
                            when {
                                connectingRepositoryId == repository.githubRepositoryId ->
                                    if (isConnected) "Updating…" else "Connecting…"
                                isConnected -> "Update"
                                else -> "Connect"
                            }
                        )
                    }
                }
            },
    )
}
