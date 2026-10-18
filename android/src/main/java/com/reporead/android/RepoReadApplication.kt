package com.reporead.android

import android.app.Application
import com.reporead.android.auth.SessionStore
import com.reporead.android.core.network.Api
import com.reporead.android.data.LocalStore
import com.reporead.android.sync.Sync
import com.reporead.android.sync.forgetSaveAllNotes
import com.reporead.android.sync.requestSync
import kotlinx.coroutines.MainScope

/**
 * The one owner of the app's long-lived objects, shared by the activity and the background sync. Each is created on
 * first use, so instrumented tests, which run in this process with their own databases, never open the app's.
 */
class RepoReadApplication : Application() {
    val sessionStore by lazy { SessionStore(this) }

    /** Sends the stored session with every request; the session ends when the server refuses it (any 401). */
    val api by lazy { Api(BuildConfig.API_BASE_URL, sessionStore::read, sessionStore::endRefused) }

    val localStore by lazy { LocalStore.open(this, "reporead.db") }

    /**
     * For work that must finish after the screen that started it is gone, such as saving the reading position when a
     * note is left or the phone rotates. The scope survives those screens; a retired Sync cancels operations on removed
     * data.
     */
    val scope = MainScope()

    /** Sign-in, sign-out and account deletion, and where they stand. */
    val account by lazy {
        Account(sessionStore, api, Sync(api, localStore, filesDir) { requestSync(this) }, scope) {
            forgetSaveAllNotes(this)
        }
    }
}
