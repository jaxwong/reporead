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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
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
import com.reporead.android.data.LocalStore
import com.reporead.android.sync.Sync
import com.reporead.android.library.AvailableScreen
import com.reporead.android.library.FolderScreen
import com.reporead.android.library.LibraryScreen
import com.reporead.android.library.NotebookScreen
import com.reporead.android.library.ReviewScreen
import com.reporead.android.reader.FigureScreen
import com.reporead.android.reader.ReaderScreen
import com.reporead.android.reader.StudyTarget
import com.reporead.android.search.SearchScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One navigation entry, encoded as strings so the back stack survives process death. */
sealed interface Screen {
    data object Repositories : Screen
    data object Available : Screen
    data class Folder(val repositoryId: Long, val repositoryName: String, val path: String) : Screen
    /** [heading]: open at this heading (from a note link) instead of the saved reading position. */
    data class Reader(val documentId: Long, val title: String, val heading: String? = null, val question: StudyTarget? = null, val annotation: String? = null, val reviewPrompt: String? = null) : Screen
    /** One table or diagram ([figure], the page's id for it) of the saved version [blobSha] of a note, full screen. */
    data class Figure(val documentId: Long, val blobSha: String, val figure: String, val title: String) : Screen
    data object Notebook : Screen
    data object Review : Screen
    data object Search : Screen
}

private fun encode(screen: Screen): List<String> = when (screen) {
    Screen.Repositories -> listOf("repositories")
    Screen.Available -> listOf("available")
    is Screen.Folder -> listOf("folder", screen.repositoryId.toString(), screen.repositoryName, screen.path)
    is Screen.Reader -> listOf("reader", screen.documentId.toString(), screen.title, screen.heading.orEmpty(),
        screen.question?.blobSha.orEmpty(), screen.question?.blockId.orEmpty(), screen.annotation.orEmpty(), screen.reviewPrompt.orEmpty())
    is Screen.Figure -> listOf("figure", screen.documentId.toString(), screen.blobSha, screen.figure, screen.title)
    Screen.Notebook -> listOf("notebook")
    Screen.Review -> listOf("review")
    Screen.Search -> listOf("search")
}

private fun decode(parts: List<String>): Screen = when (parts[0]) {
    "repositories" -> Screen.Repositories
    "available" -> Screen.Available
    "folder" -> Screen.Folder(parts[1].toLong(), parts[2], parts[3])
    "reader" -> Screen.Reader(parts[1].toLong(), parts[2], parts.getOrNull(3)?.ifEmpty { null },
        parts.getOrNull(5)?.ifEmpty { null }?.let { StudyTarget(parts[4], it) },
        parts.getOrNull(6)?.ifEmpty { null }, parts.getOrNull(7)?.ifEmpty { null })
    "figure" -> Screen.Figure(parts[1].toLong(), parts[2], parts[3], parts[4])
    "notebook" -> Screen.Notebook
    "review" -> Screen.Review
    "search" -> Screen.Search
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

/**
 * The key of one back-stack entry's saved UI state. The index tells repeated screens apart (a note linking to a note that
 * links back), so each entry keeps its own state while it is in the stack.
 */
private fun entryKey(index: Int, screen: Screen) = "$index:" + encode(screen).joinToString("\u001f")

private val ROOT_STATE = entryKey(0, Screen.Repositories)

@Composable
fun RepoReadApp(signInCode: String?, onSignInCodeConsumed: () -> Unit) {
    val context = LocalContext.current
    val store = remember { SessionStore(context.applicationContext) }
    val api = remember { Api(BuildConfig.API_BASE_URL) { store.accessToken() } }
    val local = remember { LocalStore.get(context) }
    val sync = remember { Sync(api, local, context.applicationContext.filesDir) }
    val dao = remember { local.library() }
    var signedIn by remember { mutableStateOf(store.accessToken() != null) }
    var signInMessage by remember { mutableStateOf<String?>(null) }
    var stack by rememberSaveable(stateSaver = StackSaver) { mutableStateOf(listOf<Screen>(Screen.Repositories)) }
    val scope = rememberCoroutineScope()
    // Screens below the top keep their saved state (scroll, tabs, one-shot targets already applied, the version a summary
    // starts from) while covered, as they do across recreation; it is dropped once the entry leaves the stack.
    val screenStates = rememberSaveableStateHolder()
    var keptStates by remember { mutableStateOf(emptySet<String>()) }
    val savedRepositories by dao.repositories().collectAsState(null)

    // Any 401 means the app session or the server's GitHub token is gone. Saved notes stay readable; only refreshing
    // and syncing wait for a new sign-in.
    val onFailure: (ApiException) -> Unit = { error ->
        if (error.status == 401) {
            store.clear()
            signedIn = false
        }
    }
    val signIn = { startSignIn(context, BuildConfig.API_BASE_URL, store) }

    // Keyed on the code, so the code is cleared only after the exchange ends: clearing it first would
    // restart this effect and cancel the exchange after the server had already issued the session.
    LaunchedEffect(signInCode) {
        val code = signInCode ?: return@LaunchedEffect
        signInMessage = "Finishing sign-in…"
        try {
            completeSignIn(code, api, store) {
                withContext(Dispatchers.IO) { sync.clearAll() }
                screenStates.removeState(ROOT_STATE)
            }
            signedIn = true
            signInMessage = null
        } catch (error: ApiException) {
            signInMessage = error.describe()
        } catch (cancelled: CancellationException) {
            signInMessage = "Sign-in was interrupted. Tap Sign in with GitHub again."
            throw cancelled
        } finally {
            onSignInCodeConsumed()
        }
    }

    val saved = savedRepositories ?: return
    if (!signedIn && (saved.isEmpty() || signInMessage != null)) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("RepoRead", style = MaterialTheme.typography.headlineMedium)
            Text("Read your GitHub notes. RepoRead only reads repositories you grant to its GitHub App.")
            Button(onClick = signIn) { Text("Sign in with GitHub") }
            signInMessage?.let { Text(it) }
        }
        return
    }

    val push: (Screen) -> Unit = { stack = stack + it }
    val pop: () -> Unit = { stack = stack.dropLast(1) }
    BackHandler(enabled = stack.size > 1) { stack = stack.dropLast(1) }
    val screen = stack.last()
    val current = stack.mapIndexed(::entryKey)
    SideEffect {
        (keptStates - current.toSet()).forEach(screenStates::removeState)
        keptStates = current.toSet()
    }
    // Keyed by entry so each screen starts with its own remembered state, also when it follows one of the same kind (a
    // note opened from a note, a subfolder): otherwise an open notes panel or a status line carries over.
    key(current.last()) {
    screenStates.SaveableStateProvider(current.last()) {
    when (screen) {
        Screen.Repositories ->
        LibraryScreen(sync, dao, signedIn, onFailure, push, onSignIn = signIn, onSignOut = {
            scope.launch {
                val message = try {
                    api.delete("/api/app-auth/session")
                    null
                } catch (error: ApiException) {
                    if (error.status == 401) null
                    else "Signed out on this phone, but the server session could not be revoked (${error.describe()}). It expires within 30 days."
                }
                // Explicit sign-out removes this phone's copy of private notes, including unsynced changes.
                withContext(Dispatchers.IO) { sync.clearAll() }
                screenStates.removeState(ROOT_STATE)
                store.clear()
                store.dataOwner = null
                signedIn = false
                signInMessage = message
                stack = listOf(Screen.Repositories)
            }
        }, onAccountDeleted = {
            // The server deleted the account and its sessions, and Sync cleared this phone's copy.
            store.clear()
            store.dataOwner = null
            screenStates.removeState(ROOT_STATE)
            signedIn = false
            signInMessage = "Your RepoRead account was deleted. To also remove RepoRead's authorization on GitHub, use GitHub's Settings → Applications."
            stack = listOf(Screen.Repositories)
        })
        Screen.Available -> AvailableScreen(api, onFailure, onBack = pop, onConnected = { stack = listOf(Screen.Repositories, it) })
        is Screen.Folder -> FolderScreen(sync, dao, signedIn, onFailure, screen, push, onBack = pop,
            onDisconnected = { stack = listOf(Screen.Repositories) })
        is Screen.Reader -> ReaderScreen(sync, dao, scope, signedIn, onFailure, screen, onBack = pop, push = push)
        is Screen.Figure -> FigureScreen(sync, dao, screen, onBack = pop)
        Screen.Notebook -> NotebookScreen(dao, push, onBack = pop)
        Screen.Review -> ReviewScreen(dao, push, onBack = pop)
        Screen.Search -> SearchScreen(dao, push, onBack = pop)
    }
    }
    }
}
