package com.reporead.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.reporead.android.auth.SessionStore
import com.reporead.android.auth.completeSignIn
import com.reporead.android.auth.startSignIn
import com.reporead.android.core.network.Api
import com.reporead.android.core.network.ApiException
import com.reporead.android.core.network.describe
import com.reporead.android.library.AvailableScreen
import com.reporead.android.library.FolderScreen
import com.reporead.android.library.RepositoriesScreen
import com.reporead.android.reader.ReaderScreen
import kotlinx.coroutines.launch

/** One navigation entry, encoded as strings so the back stack survives process death. */
sealed interface Screen {
    data object Repositories : Screen
    data object Available : Screen
    data class Folder(val repositoryId: Long, val repositoryName: String, val path: String) : Screen
    data class Reader(val documentId: Long, val title: String) : Screen
}

private fun encode(screen: Screen): List<String> = when (screen) {
    Screen.Repositories -> listOf("repositories")
    Screen.Available -> listOf("available")
    is Screen.Folder -> listOf("folder", screen.repositoryId.toString(), screen.repositoryName, screen.path)
    is Screen.Reader -> listOf("reader", screen.documentId.toString(), screen.title)
}

private fun decode(parts: List<String>): Screen = when (parts[0]) {
    "repositories" -> Screen.Repositories
    "available" -> Screen.Available
    "folder" -> Screen.Folder(parts[1].toLong(), parts[2], parts[3])
    "reader" -> Screen.Reader(parts[1].toLong(), parts[2])
    else -> error("Unknown saved screen ${parts[0]}")
}

private val StackSaver = listSaver<List<Screen>, String>(
    save = { stack -> stack.flatMap { screen -> encode(screen).let { listOf(it.size.toString()) + it } } },
    restore = { flat ->
        val stack = mutableListOf<Screen>()
        var index = 0
        while (index < flat.size) {
            val size = flat[index].toInt()
            stack += decode(flat.subList(index + 1, index + 1 + size))
            index += size + 1
        }
        stack
    },
)

@Composable
fun RepoReadApp(signInCode: String?, onSignInCodeConsumed: () -> Unit) {
    val context = LocalContext.current
    val store = remember { SessionStore(context.applicationContext) }
    val api = remember { Api(BuildConfig.API_BASE_URL) { store.accessToken() } }
    var signedIn by remember { mutableStateOf(store.accessToken() != null) }
    var signInMessage by remember { mutableStateOf<String?>(null) }
    var stack by rememberSaveable(stateSaver = StackSaver) { mutableStateOf(listOf<Screen>(Screen.Repositories)) }
    val scope = rememberCoroutineScope()

    val signOut: (String?) -> Unit = { message ->
        store.clear()
        signedIn = false
        signInMessage = message
        stack = listOf(Screen.Repositories)
    }
    // Any 401 means the app session or the server's GitHub token is gone; the only remedy is a new sign-in.
    val onFailure: (ApiException) -> Unit = { error -> if (error.status == 401) signOut("Your session ended. Sign in again.") }

    LaunchedEffect(signInCode) {
        val code = signInCode ?: return@LaunchedEffect
        onSignInCodeConsumed()
        signInMessage = "Finishing sign-in…"
        try {
            completeSignIn(code, api, store)
            signedIn = true
            signInMessage = null
        } catch (error: ApiException) {
            signInMessage = error.describe()
        }
    }

    if (!signedIn) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("RepoRead", style = MaterialTheme.typography.headlineMedium)
            Text("Read your GitHub notes. RepoRead only reads repositories you grant to its GitHub App.")
            Button(onClick = { startSignIn(context, BuildConfig.API_BASE_URL, store) }) { Text("Sign in with GitHub") }
            signInMessage?.let { Text(it) }
        }
        return
    }

    val push: (Screen) -> Unit = { stack = stack + it }
    BackHandler(enabled = stack.size > 1) { stack = stack.dropLast(1) }
    when (val screen = stack.last()) {
        Screen.Repositories -> RepositoriesScreen(api, onFailure, push, onSignOut = {
            scope.launch {
                val message = try {
                    api.delete("/api/app-auth/session")
                    null
                } catch (error: ApiException) {
                    if (error.status == 401) null
                    else "Signed out on this phone, but the server session could not be revoked (${error.describe()}). It expires within 30 days."
                }
                signOut(message)
            }
        })
        Screen.Available -> AvailableScreen(api, onFailure, onConnected = { stack = listOf(Screen.Repositories, it) })
        is Screen.Folder -> FolderScreen(api, onFailure, screen, push)
        is Screen.Reader -> ReaderScreen(api, onFailure, screen)
    }
}
