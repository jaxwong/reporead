package com.reporead.android

import android.app.Application
import com.reporead.android.auth.SessionStore
import com.reporead.android.core.network.Api
import com.reporead.android.data.LocalStore
import com.reporead.android.sync.LocalCleanup
import com.reporead.android.sync.Sync
import com.reporead.android.sync.requestSync
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * The one owner of the app's long-lived objects, shared by the activity and the background sync. Each is created on
 * first use, so instrumented tests, which run in this process with their own databases, never open the app's.
 */
class RepoReadApplication : Application() {
    val sessionStore by lazy { SessionStore(this) }

    /** Sends the stored session with every request; the session ends when the server refuses it (any 401). */
    val api by lazy { Api(BuildConfig.API_BASE_URL, sessionStore::read, sessionStore::endRefused) }

    val localStore by lazy { LocalStore.open(this, "reporead.db") }

    val sync by lazy { Sync(api, localStore, filesDir) { requestSync(this) } }

    /**
     * For work that must finish after the screen that started it is gone, such as saving the reading position when a
     * note is left or the phone rotates. Never cancelled.
     */
    val scope = MainScope()

    /** What [removeCopy] did last in this process, for the first screen; null while it runs, and before it has. */
    val removal = MutableStateFlow<LocalCleanup?>(null)

    /**
     * Ends the session on this phone and deletes its copy; once Room's rows and every file are gone, forgets the
     * pending removal and the data's owner together. Only while a removal is pending ([SessionStore.startRemoval]):
     * sign-out and account deletion run it after their one server call, and Retry and the next start run it again until
     * it is done.
     */
    suspend fun removeCopy(): LocalCleanup {
        check(sessionStore.removalPending) { "Removing the phone's copy without a pending removal" }
        removal.value = null
        sessionStore.clear()
        val outcome = sync.clearAll()
        if (outcome == LocalCleanup.Done) sessionStore.finishRemoval()
        removal.value = outcome
        return outcome
    }

    private var started = false

    /**
     * The app's work at start, once per process: called by the activity, not [onCreate], because instrumented tests run
     * in this process and must never touch the app's files or database, and because a process started only to serve a
     * shared export must not delete it. A pending removal is finished first; its clear also does the start's work.
     */
    fun startUp() {
        if (started) return
        started = true
        // Undispatched: the session ends before the activity draws a screen that could sync with it.
        if (sessionStore.removalPending) scope.launch(start = CoroutineStart.UNDISPATCHED) { removeCopy() }
        else scope.launch { sync.startUp() }
    }
}
