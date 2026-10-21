package com.reporead.android

import android.app.Application
import com.reporead.android.auth.SessionStore
import com.reporead.android.database.RepoReadDatabase
import com.reporead.android.logging.DeviceLog
import com.reporead.android.logging.deviceLogDirectory
import com.reporead.android.network.Api
import com.reporead.android.sync.Sync
import com.reporead.android.sync.requestDeviceLogUpload
import com.reporead.android.sync.requestSync
import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/** Long enough for WorkManager to record a request on a busy phone, short enough not to hold a crash noticeably. */
private const val CRASH_UPLOAD_WAIT_SECONDS = 2L

/**
 * The one owner of the app's long-lived objects, shared by the activity and the background sync. Each is created on
 * first use, so instrumented tests, which run in this process with their own databases, never open the app's.
 */
class RepoReadApplication : Application() {
    /**
     * Before any screen, worker or test in this process logs. A crash's line is stored and its upload recorded before
     * Android ends the process, so Android starts the app in the background to send it, without the app being opened.
     */
    override fun onCreate() {
        super.onCreate()
        DeviceLog.start(deviceLogDirectory(this)) { requestDeviceLogUpload(this, Duration.ofSeconds(2)) }
        val ending = checkNotNull(Thread.getDefaultUncaughtExceptionHandler()) { "Android sets the crash handler" }
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            DeviceLog.crash(thread, error)
            try {
                // Bounded: a crash on WorkManager's own thread would otherwise wait for itself.
                requestDeviceLogUpload(this).result.get(CRASH_UPLOAD_WAIT_SECONDS, TimeUnit.SECONDS)
            } catch (notRecorded: TimeoutException) {
                DeviceLog.crashUploadNotRecorded(notRecorded)
            } catch (notRecorded: ExecutionException) {
                DeviceLog.crashUploadNotRecorded(notRecorded)
            } finally {
                ending.uncaughtException(thread, error)
            }
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
