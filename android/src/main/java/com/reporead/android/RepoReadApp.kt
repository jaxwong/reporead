package com.reporead.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.reporead.android.auth.completeSignIn
import com.reporead.android.auth.startSignIn
import com.reporead.android.core.network.ApiException
import com.reporead.android.core.network.describe
import com.reporead.android.library.AvailableScreen
import com.reporead.android.library.FolderScreen
import com.reporead.android.library.LibraryScreen
import com.reporead.android.library.NotebookScreen
import com.reporead.android.library.PracticeScreen
import com.reporead.android.library.ReviewScreen
import com.reporead.android.reader.FigureScreen
import com.reporead.android.reader.ReaderScreen
import com.reporead.android.search.SearchScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** One navigation entry, encoded as strings so the back stack survives process death. */
sealed interface Screen {
    data object Repositories : Screen

    data object Available : Screen

    data class Folder(val repositoryId: Long, val repositoryName: String, val path: String) : Screen

    /** A note, opened once at [openAt] and otherwise where it was last read. */
    data class Reader(val documentId: Long, val title: String, val openAt: OpenAt? = null) : Screen

    /** One table or diagram ([figure], the page's id for it) of the saved version [blobSha] of a note, full screen. */
    data class Figure(val documentId: Long, val blobSha: String, val figure: String, val title: String) : Screen

    data object Notebook : Screen

    data object Review : Screen

    /** A practice session over the questions of one topic's interview folder; null mixes every topic. */
    data class Practice(val topic: String?) : Screen

    data object Search : Screen
}

/** A place to open a note at instead of where it was last read. */
sealed interface OpenAt {
    /** A heading named by a note link. */
    data class Heading(val text: String) : OpenAt

    /** A highlight or card, from the notebook or a review. */
    data class Highlight(val mutationId: String) : OpenAt
}

private fun encode(screen: Screen): List<String> =
    when (screen) {
        Screen.Repositories -> listOf("repositories")
        Screen.Available -> listOf("available")
        is Screen.Folder -> listOf("folder", screen.repositoryId.toString(), screen.repositoryName, screen.path)
        is Screen.Reader -> {
            // One slot per kind of target, so a back stack saved by an earlier version still restores.
            val (heading, highlight) =
                when (val target = screen.openAt) {
                    null -> "" to ""
                    is OpenAt.Heading -> target.text to ""
                    is OpenAt.Highlight -> "" to target.mutationId
                }
            listOf("reader", screen.documentId.toString(), screen.title, heading, highlight)
        }
        is Screen.Figure -> listOf("figure", screen.documentId.toString(), screen.blobSha, screen.figure, screen.title)
        Screen.Notebook -> listOf("notebook")
        Screen.Review -> listOf("review")
        is Screen.Practice -> listOfNotNull("practice", screen.topic)
        Screen.Search -> listOf("search")
    }

private fun decode(parts: List<String>): Screen =
    when (parts[0]) {
        "repositories" -> Screen.Repositories
        "available" -> Screen.Available
        "folder" -> Screen.Folder(parts[1].toLong(), parts[2], parts[3])
        "reader" ->
            Screen.Reader(
                parts[1].toLong(),
                parts[2],
                when {
                    parts[3].isNotEmpty() -> OpenAt.Heading(parts[3])
                    parts[4].isNotEmpty() -> OpenAt.Highlight(parts[4])
                    else -> null
                },
            )
        "figure" -> Screen.Figure(parts[1].toLong(), parts[2], parts[3], parts[4])
        "notebook" -> Screen.Notebook
        "review" -> Screen.Review
        "practice" -> Screen.Practice(parts.getOrNull(1))
        "search" -> Screen.Search
        else -> error("Unknown saved screen ${parts[0]}")
    }

private val StackSaver =
    listSaver<List<Screen>, String>(
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
 * The key of one back-stack entry's saved UI state. The index tells repeated screens apart (a note linking to a note
 * that links back), so each entry keeps its own state while it is in the stack.
 */
private fun entryKey(index: Int, screen: Screen) = "$index:" + encode(screen).joinToString("\u001f")

private val ROOT_STATE = entryKey(0, Screen.Repositories)

@Composable
fun RepoReadApp(signInCode: String?, onSignInCodeConsumed: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as RepoReadApplication
    val store = app.sessionStore
    val api = app.api
    val sync = app.sync
    val dao = app.localStore.library()
    // Saved notes stay readable while signed out; only refreshing and syncing wait for a new sign-in.
    val signedIn by remember { store.signedIn() }.collectAsState(remember { store.accessToken() != null })
    var signInMessage by remember { mutableStateOf<String?>(null) }
    var stack by rememberSaveable(stateSaver = StackSaver) { mutableStateOf(listOf<Screen>(Screen.Repositories)) }
    // Screens below the top keep their saved state (scroll, tabs, one-shot targets already applied, the version a
    // summary starts from) while covered, as they do across recreation; it is dropped once the entry leaves the stack.
    val screenStates = rememberSaveableStateHolder()
    var keptStates by remember { mutableStateOf(emptySet<String>()) }
    val savedRepositories by dao.repositories().collectAsState(null)

    val signIn = { startSignIn(context, BuildConfig.API_BASE_URL, store) }
    // Ends the session here, deletes this phone's copy of private notes, unsynced changes included, and goes back to
    // the first screen signed out; [message] says why. Ending it before the deletion, which can fail, means a failure
    // cannot leave the phone signed in; the data's owner is forgotten only once the copy is gone, so until then only
    // its account can sign in again (completeSignIn). Callers run it in the app's scope: a rotation must not stop it
    // part-way.
    val endSession: suspend (String?) -> Unit = { message ->
        store.clear()
        sync.clearAll()
        store.dataOwner = null
        screenStates.removeState(ROOT_STATE)
        signInMessage = message
        stack = listOf(Screen.Repositories)
    }
    // Revokes the session on the server when it can, then ends it on this phone.
    val signOut: () -> Unit = {
        app.scope.launch {
            val message =
                try {
                    api.delete("/api/app-auth/session")
                    null
                } catch (error: ApiException) {
                    if (error.status == 401) null
                    else
                        "Signed out on this phone, but the server session could not be revoked (${error.describe()}). It expires within 30 days."
                }
            endSession(message)
        }
    }

    // Keyed on the code, so the code is cleared only after the exchange ends: clearing it first would
    // restart this effect and cancel the exchange after the server had already issued the session.
    LaunchedEffect(signInCode) {
        val code = signInCode ?: return@LaunchedEffect
        signInMessage = "Finishing sign-in…"
        try {
            completeSignIn(code, api, store)
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
            // A refused sign-in changes nothing saved here, and saved notes stay readable signed out.
            if (saved.isNotEmpty() && signInMessage != null)
                TextButton(onClick = { signInMessage = null }) { Text("Back to saved notes") }
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
                    LibraryScreen(
                        sync,
                        dao,
                        app.scope,
                        signedIn,
                        push,
                        onSignIn = signIn,
                        onSignOut = signOut,
                        onAccountDeleted = {
                            // The server deleted the account and its sessions; this phone's copy goes as at sign-out.
                            endSession(
                                "Your RepoRead account was deleted. To check that RepoRead's GitHub authorization is gone, or to uninstall the App, see GitHub's Settings → Applications."
                            )
                        },
                    )
                Screen.Available ->
                    AvailableScreen(api, onBack = pop, onConnected = { stack = listOf(Screen.Repositories, it) })
                is Screen.Folder ->
                    FolderScreen(
                        sync,
                        dao,
                        signedIn,
                        screen,
                        push,
                        onBack = pop,
                        onDisconnected = { stack = listOf(Screen.Repositories) },
                    )
                is Screen.Reader -> ReaderScreen(sync, dao, app.scope, signedIn, screen, onBack = pop, push = push)
                is Screen.Figure -> FigureScreen(sync, dao, screen, onBack = pop)
                Screen.Notebook -> NotebookScreen(dao, push, onBack = pop)
                Screen.Review -> ReviewScreen(dao, sync, push, onBack = pop)
                is Screen.Practice -> PracticeScreen(dao, sync, screen.topic, push, onBack = pop)
                Screen.Search -> SearchScreen(dao, push, onBack = pop)
            }
        }
    }
}
