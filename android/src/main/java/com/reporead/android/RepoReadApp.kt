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
import com.reporead.android.auth.SessionStore
import com.reporead.android.auth.startSignIn
import com.reporead.android.core.network.Api
import com.reporead.android.core.network.describe
import com.reporead.android.data.LibraryDao
import com.reporead.android.library.AvailableScreen
import com.reporead.android.library.FolderScreen
import com.reporead.android.library.LibraryScreen
import com.reporead.android.library.NotebookScreen
import com.reporead.android.library.PracticeScreen
import com.reporead.android.library.ReviewScreen
import com.reporead.android.reader.FigureScreen
import com.reporead.android.reader.ReaderScreen
import com.reporead.android.search.SearchScreen
import com.reporead.android.sync.LocalCleanup
import com.reporead.android.ui.counted
import kotlinx.coroutines.CoroutineScope

/** One navigation entry, encoded as strings so the back stack survives process death. */
sealed interface Screen {
    data object Repositories : Screen

    data object Available : Screen

    data class Folder(val repositoryId: Long, val repositoryName: String, val path: String) : Screen

    /** A note, opened once at [openAt] and otherwise where it was last read. */
    data class Reader(val documentId: Long, val title: String, val openAt: OpenAt? = null) : Screen

    /** One table or diagram ([figure], the page's id for it) of the saved version [blobSha] of a note, full screen. */
    data class Figure(
        val documentId: Long,
        val blobSha: String,
        val figure: String,
        val title: String,
        val snapshotCommitSha: String?,
    ) : Screen

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

    /** A Practice warning's exact block; block ids are meaningful only within [blobSha]. */
    data class Block(val blobSha: String, val blockId: String) : OpenAt
}

private fun encode(screen: Screen): List<String> =
    when (screen) {
        Screen.Repositories -> listOf("repositories")
        Screen.Available -> listOf("available")
        is Screen.Folder -> listOf("folder", screen.repositoryId.toString(), screen.repositoryName, screen.path)
        is Screen.Reader -> {
            // Existing heading/highlight slots stay unchanged for saved, pre-upgrade back stacks.
            listOf("reader", screen.documentId.toString(), screen.title) +
                when (val target = screen.openAt) {
                    null -> listOf("", "")
                    is OpenAt.Heading -> listOf(target.text, "")
                    is OpenAt.Highlight -> listOf("", target.mutationId)
                    is OpenAt.Block -> listOf("", "", target.blobSha, target.blockId)
                }
        }
        is Screen.Figure ->
            listOf(
                "figure",
                screen.documentId.toString(),
                screen.blobSha,
                screen.figure,
                screen.title,
                screen.snapshotCommitSha.orEmpty(),
            )
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
                    parts.size > 5 -> OpenAt.Block(parts[5], parts[6])
                    else -> null
                },
            )
        // A saved pre-upgrade destination has no image identity, just like its migrated NoteRow.
        "figure" -> Screen.Figure(parts[1].toLong(), parts[2], parts[3], parts[4], parts.getOrNull(5)?.ifEmpty { null })
        "notebook" -> Screen.Notebook
        "review" -> Screen.Review
        "practice" -> Screen.Practice(parts.getOrNull(1))
        "search" -> Screen.Search
        else -> error("Unknown saved screen ${parts[0]}")
    }

internal val StackSaver =
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

/** What an account operation reported, in the words of the screen it ends on. */
internal fun noticeText(notice: AccountNotice): String =
    when (notice) {
        is AccountNotice.NotRevoked ->
            "Signed out on this phone, but the server session could not be revoked (${notice.error.describe()}). It expires within 30 days."
        AccountNotice.AccountDeleted ->
            "Your RepoRead account was deleted. To check that RepoRead's GitHub authorization is gone, or to uninstall the App, see GitHub's Settings → Applications."
        is AccountNotice.DeletionNotConfirmed ->
            if (notice.error.status == 401)
                // A lost answer to an earlier deletion also ends the session; the two look the same.
                "Couldn't confirm: this phone is no longer signed in, so nothing was deleted on it. If you already deleted " +
                    "your account, it is gone; sign out to remove this phone's copy, or sign in to check."
            // The server may have deleted it with the answer lost; trying again then gets the 401 above.
            else
                "Couldn't confirm the account deletion, so nothing was deleted on this phone; try again. ${notice.error.describe()}"
        is AccountNotice.SignInFailed -> notice.error.describe()
    }

/** Where removing this phone's copy stands while it is pending: running, or what stopped it, with [onRetry]. */
@Composable
private fun RemovalStatus(outcome: LocalCleanup?, onRetry: () -> Unit) {
    when (outcome) {
        // Done is reported as the end of the removal, never shown here.
        null,
        LocalCleanup.Done -> Text("Removing this phone's copy of your notes…")
        is LocalCleanup.FilesLeft ->
            Text(
                "Signed out, but ${counted(outcome.paths.size, "file or folder", "files and folders")} could not be removed from this phone. Signing in waits until they are."
            )
        is LocalCleanup.DatabaseFailed ->
            Text(
                "Signed out, but this phone's saved notes could not be deleted (${outcome.error.javaClass.simpleName}). Signing in waits until they are."
            )
    }
    if (outcome is LocalCleanup.FilesLeft || outcome is LocalCleanup.DatabaseFailed)
        TextButton(onClick = onRetry) { Text("Retry") }
}

@Composable
fun RepoReadApp(
    account: Account,
    store: SessionStore,
    api: Api,
    dao: LibraryDao,
    appScope: CoroutineScope,
    signInCode: String?,
    onSignInCodeConsumed: () -> Unit,
) {
    val context = LocalContext.current
    val sync = account.sync
    // Saved notes stay readable while signed out; only refreshing and syncing wait for a new sign-in.
    val signedIn by remember { store.signedIn() }.collectAsState(remember { store.hasSession })
    val accountState by account.state.collectAsState()
    var stack by rememberSaveable(sync, stateSaver = StackSaver) { mutableStateOf(listOf<Screen>(Screen.Repositories)) }
    // Screens below the top keep their saved state (scroll, tabs, one-shot targets already applied, the version a
    // summary starts from) while covered, as they do across recreation; it is dropped once the entry leaves the stack.
    val screenStates = rememberSaveableStateHolder()
    var keptStates by remember { mutableStateOf(emptySet<String>()) }
    val savedRepositories by dao.repositories().collectAsState(null)

    val signIn = { startSignIn(context, BuildConfig.API_BASE_URL, store) }

    // The exchange runs in the app's scope (Account), so the code is consumed at once and a rotation cannot cut it off.
    LaunchedEffect(signInCode) {
        val code = signInCode ?: return@LaunchedEffect
        account.signIn(code)
        onSignInCodeConsumed()
    }
    // A removal leaves nothing the old screens showed: the next account starts at the first screen.
    val removing = accountState is AccountState.Removing
    LaunchedEffect(removing) {
        if (removing) {
            screenStates.removeState(ROOT_STATE)
            stack = listOf(Screen.Repositories)
        }
    }

    val saved = savedRepositories ?: return
    val notice = (accountState as? AccountState.Idle)?.notice
    // While an account operation runs, only this screen shows, so nothing reads or changes what it removes. Signed
    // out, saved notes stay readable, unless there are none or the last operation has something to say.
    val showAccount =
        when (accountState) {
            is AccountState.Idle -> !signedIn && (saved.isEmpty() || notice != null)
            else -> true
        }
    if (showAccount) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("RepoRead", style = MaterialTheme.typography.headlineMedium)
            Text("Read your GitHub notes. RepoRead only reads repositories you grant to its GitHub App.")
            when (val now = accountState) {
                is AccountState.Removing -> RemovalStatus(now.stopped, account::retryRemoval)
                AccountState.DeletingAccount -> Text("Deleting your RepoRead account…")
                AccountState.SigningIn -> Text("Finishing sign-in…")
                is AccountState.Idle -> Button(onClick = signIn) { Text("Sign in with GitHub") }
            }
            notice?.let { Text(noticeText(it)) }
            // A refused sign-in or deletion changes nothing saved here, and saved notes stay readable signed out.
            if (notice != null && saved.isNotEmpty())
                TextButton(onClick = account::dismissNotice) { Text("Back to saved notes") }
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
                        signedIn,
                        push,
                        notice = notice?.let(::noticeText),
                        onDismissNotice = account::dismissNotice,
                        onSignIn = signIn,
                        onSignOut = account::signOut,
                        onDeleteAccount = account::deleteAccount,
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
                is Screen.Reader -> ReaderScreen(sync, dao, appScope, signedIn, screen, onBack = pop, push = push)
                is Screen.Figure -> FigureScreen(sync, dao, screen, onBack = pop)
                Screen.Notebook -> NotebookScreen(dao, sync, push, onBack = pop)
                Screen.Review -> ReviewScreen(dao, sync, push, onBack = pop)
                is Screen.Practice -> PracticeScreen(dao, sync, screen.topic, push, onBack = pop)
                Screen.Search -> SearchScreen(dao, push, onBack = pop)
            }
        }
    }
}
