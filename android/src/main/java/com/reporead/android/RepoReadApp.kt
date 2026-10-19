package com.reporead.android

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSerializable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.runtime.serialization.NavBackStackSerializer
import androidx.navigation3.ui.NavDisplay
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
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.Serializable

/** A place in the app; saved with the back stack ([Entry]), so it survives process death. */
@Serializable
sealed interface Screen {
    @Serializable data object Repositories : Screen

    @Serializable data object Available : Screen

    @Serializable data class Folder(val repositoryId: Long, val repositoryName: String, val path: String) : Screen

    /** A note, opened once at [openAt] and otherwise where it was last read. */
    @Serializable data class Reader(val documentId: Long, val title: String, val openAt: OpenAt? = null) : Screen

    /** One table or diagram ([figure], the page's id for it) of the saved version [blobSha] of a note, full screen. */
    @Serializable
    data class Figure(
        val documentId: Long,
        val blobSha: String,
        val figure: String,
        val title: String,
        val snapshotCommitSha: String?,
    ) : Screen

    @Serializable data object Notebook : Screen

    @Serializable data object Review : Screen

    /** A practice session over the questions of one topic's interview folder; null mixes every topic. */
    @Serializable data class Practice(val topic: String?) : Screen

    @Serializable data object Search : Screen
}

/** A place to open a note at instead of where it was last read. */
@Serializable
sealed interface OpenAt {
    /** A heading named by a note link. */
    @Serializable data class Heading(val text: String) : OpenAt

    /** A highlight or card, from the notebook or a review. */
    @Serializable data class Highlight(val mutationId: String) : OpenAt

    /** A Practice warning's exact block; block ids are meaningful only within [blobSha]. */
    @Serializable data class Block(val blobSha: String, val blockId: String) : OpenAt
}

/**
 * One back-stack entry: [screen], and the [id] its saved UI state is kept under from its push until it leaves the
 * stack. The id, not the screen, tells repeated destinations apart (a note linking to a note that links back); it is
 * saved with the stack, so a restored entry finds its state again.
 */
@Serializable data class Entry(val id: String, val screen: Screen) : NavKey

/** A new entry for [screen], with an id no other entry has. */
private fun entry(screen: Screen) = Entry(UUID.randomUUID().toString(), screen)

/** How the back stack is saved with the activity and across process death. */
internal val BackStackSerializer = NavBackStackSerializer(Entry.serializer())

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
    // Saved notes stay readable while signed out; only refreshing and syncing wait for a new sign-in.
    val signedIn by remember { store.signedIn() }.collectAsState(remember { store.hasSession })
    val accountState by account.state.collectAsState()
    // Account publishes a new copy after every cleanup, including ownerless sign-in, which never enters Removing.
    val backStack =
        rememberSerializable(account.sync, serializer = BackStackSerializer) {
            NavBackStack(entry(Screen.Repositories))
        }
    val savedRepositories by dao.repositories().collectAsState(null)

    val signIn = { startSignIn(context, BuildConfig.API_BASE_URL, store) }
    val push: (Screen) -> Unit = { backStack += entry(it) }
    // Back to the Library, which keeps its own state: a finished connect or disconnect leaves nothing above it.
    val popToRoot = { backStack.subList(1, backStack.size).clear() }

    // Entries are rebuilt only when the stack changes, so their content reads the account and sign-in state each time
    // it
    // draws, never values captured when the stack last changed.
    val entries =
        rememberEntries(backStack) { entry ->
            val sync = account.sync
            // Removes this entry, never another: a second tap on a screen already left does nothing.
            val onBack: () -> Unit = { backStack.remove(entry) }
            when (val screen = entry.screen) {
                Screen.Repositories ->
                    LibraryScreen(
                        sync,
                        dao,
                        signedIn,
                        entry.id,
                        push,
                        notice = (accountState as? AccountState.Idle)?.notice?.let(::noticeText),
                        onDismissNotice = account::dismissNotice,
                        onSignIn = signIn,
                        onSignOut = account::signOut,
                        onDeleteAccount = account::deleteAccount,
                    )
                Screen.Available ->
                    AvailableScreen(
                        api,
                        sync,
                        entry.id,
                        onBack,
                        onConnected = {
                            popToRoot()
                            push(it)
                        },
                    )
                is Screen.Folder ->
                    FolderScreen(sync, dao, signedIn, entry.id, screen, push, onBack, onDisconnected = popToRoot)
                is Screen.Reader -> ReaderScreen(sync, dao, appScope, signedIn, entry.id, screen, onBack, push)
                is Screen.Figure -> FigureScreen(sync, dao, screen, onBack)
                Screen.Notebook -> NotebookScreen(dao, sync, entry.id, push, onBack)
                Screen.Review -> ReviewScreen(dao, sync, entry.id, push, onBack)
                is Screen.Practice -> PracticeScreen(dao, sync, screen.topic, entry.id, push, onBack)
                Screen.Search -> SearchScreen(dao, push, onBack)
            }
        }

    // The exchange runs in the app's scope (Account), so the code is consumed at once and a rotation cannot cut it off.
    LaunchedEffect(signInCode) {
        val code = signInCode ?: return@LaunchedEffect
        account.signIn(code)
        onSignInCodeConsumed()
    }
    // A removal leaves nothing the old screens showed: their entries and saved state go, and the next account starts at
    // a new root. The account screen covers it until the removal is done, so it is first drawn with the fresh Sync.
    val removing = accountState is AccountState.Removing
    LaunchedEffect(removing) {
        if (removing) {
            backStack.clear()
            backStack += entry(Screen.Repositories)
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

    ShownEntry(entries, onBack = { backStack.removeLastOrNull() })
}

/**
 * [backStack]'s entries, each drawn by [content] and keeping its saved UI state (scroll, tabs, one-shot targets already
 * applied) and its ViewModels while it is in the stack: covered by another screen or by the account screen, and across
 * recreation. Both go once the entry leaves the stack.
 */
@Composable
internal fun rememberEntries(backStack: NavBackStack<Entry>, content: @Composable (Entry) -> Unit) =
    rememberDecoratedNavEntries(
        backStack,
        listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator()),
    ) { entry ->
        NavEntry(entry, contentKey = entry.id) { content(entry) }
    }

/** Only the top entry is drawn, and screens switch at once, as they always have; Back at the root leaves the app. */
@Composable
internal fun ShownEntry(entries: List<NavEntry<Entry>>, onBack: () -> Unit) {
    val instant = ContentTransform(EnterTransition.None, ExitTransition.None)
    NavDisplay(
        entries,
        onBack = onBack,
        transitionSpec = { instant },
        popTransitionSpec = { instant },
        predictivePopTransitionSpec = { instant },
    )
}
