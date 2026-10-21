package com.reporead.android

import android.app.Application
import com.reporead.android.auth.SessionStore
import com.reporead.android.database.RepoReadDatabase
import com.reporead.android.logging.DeviceLog
import com.reporead.android.logging.deviceLogDirectory
import com.reporead.android.network.Api
import com.reporead.android.sync.Sync
import com.reporead.android.sync.requestSync
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/**
 * The one owner of the app's long-lived objects, shared by the activity and the background sync. Each is created on
 * first use, so instrumented tests, which run in this process with their own databases, never open the app's.
 */
class RepoReadApplication : Application() {
    /** Before any screen, worker or test in this process logs; a crash's line is stored before Android ends it. */
    override fun onCreate() {
        super.onCreate()
        DeviceLog.start(deviceLogDirectory(this))
        val ending = checkNotNull(Thread.getDefaultUncaughtExceptionHandler()) { "Android sets the crash handler" }
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            DeviceLog.crash(thread, error)
            ending.uncaughtException(thread, error)
        }
    }

    val sessionStore by lazy { SessionStore(this) }

    /** Sends the stored session with every request; the session ends when the server refuses it (any 401). */
    val api by lazy { Api(BuildConfig.API_BASE_URL, sessionStore::read, sessionStore::endRefused) }

    val database by lazy { RepoReadDatabase.open(this, "reporead.db") }

    /**
     * For work that must finish after the screen that started it is gone, such as saving the reading position when a
     * note is left or the phone rotates. The scope survives those screens.
     */
    val scope = MainScope()

    /** One local copy per process; the image sweep is independent of sessions and also runs for worker-only starts. */
    val sync by lazy {
        Sync(api, database, filesDir) { requestSync(this) }.also { sync -> scope.launch { sync.startUp() } }
    }

    /** Sign-in, sign-out and account deletion, and where they stand. */
    val account by lazy { Account(sessionStore, api, scope) }
}
