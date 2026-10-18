package com.reporead.android

import android.database.sqlite.SQLiteException
import android.util.Log
import com.reporead.android.auth.SessionStore
import com.reporead.android.auth.completeSignIn
import com.reporead.android.core.network.Api
import com.reporead.android.core.network.ApiException
import com.reporead.android.sync.LocalCleanup
import com.reporead.android.sync.LocalSync
import com.reporead.android.sync.Sync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the last account operation reported, for the screen it ends on, until [Account.dismissNotice]. */
sealed interface AccountNotice {
    /** Signed out on this phone, but the server session could not be revoked ([error]); it expires on its own. */
    data class NotRevoked(val error: ApiException) : AccountNotice

    data object AccountDeleted : AccountNotice

    /** The server did not confirm the deletion ([error], a 401 included), so nothing on the phone was removed. */
    data class DeletionNotConfirmed(val error: ApiException) : AccountNotice

    data class SignInFailed(val error: ApiException) : AccountNotice
}

/** What the account is doing; one operation runs at a time. */
sealed interface AccountState {
    /** Nothing runs; [notice] is what the last operation reported. */
    data class Idle(val notice: AccountNotice?) : AccountState

    data object SigningIn : AccountState

    /** Waiting for the server: the phone's copy is untouched unless it confirms. */
    data object DeletingAccount : AccountState

    /**
     * The phone's copy is being removed ([SessionStore.removalPending] is on disk): running while [stopped] is null,
     * otherwise what stopped the last attempt, which [Account.retryRemoval] runs again. [notice] is reported once done.
     */
    data class Removing(val stopped: LocalCleanup?, val notice: AccountNotice?) : AccountState
}

/**
 * Owns sign-in, sign-out and account deletion for the app's lifetime (RepoReadApplication), so a rotation or a closed
 * screen never stops one part-way or loses what it reported, and screens only show [state] and ask. Persisted facts
 * stay with [store]. Each removal retires [sync] permanently before clearing it; only successful cleanup publishes a
 * fresh copy for new screens. Late work keeps the retired instance, never authority over the next copy.
 *
 * External calls: sign-out 1 (revoke the session), account deletion 1, sign-in 1 (the code exchange); none is retried.
 * [forgetSaveAll] stops every Save all and forgets their outcomes at each removal (BackgroundSync.kt), which owns that
 * work; Account owns when.
 */
class Account(
    private val store: SessionStore,
    private val api: Api,
    sync: Sync,
    private val scope: CoroutineScope,
    private val forgetSaveAll: suspend () -> Unit,
) {
    var sync: Sync = sync
        private set

    private val current =
        MutableStateFlow<AccountState>(
            if (store.removalPending) AccountState.Removing(null, null) else AccountState.Idle(null)
        )
    val state: StateFlow<AccountState> = current.asStateFlow()

    /** Starts [next] only when nothing runs: a second tap, or a sign-in code arriving meanwhile, does nothing. */
    private fun begin(next: AccountState): Boolean {
        val now = current.value
        val started = now is AccountState.Idle && current.compareAndSet(now, next)
        if (!started) Log.i("RepoRead", "Account operation not started; running=$now requested=$next")
        return started
    }

    private val startup by lazy {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val now = current.value
            if (now is AccountState.Removing) remove(now.notice) else sync.startUp()
        }
    }

    /** Both activity and worker enter through the same once-per-process cleanup. */
    fun startUp() = startup

    /** Pending removal runs first; a busy or stopped account never starts ordinary background work. */
    suspend fun syncWaitingChanges(): LocalSync? {
        startUp().join()
        if (current.value !is AccountState.Idle) return null
        return sync.syncWaitingChanges()
    }

    /**
     * Save all's background run ([Sync.saveAllNotes]), entered like [syncWaitingChanges]; null while another operation
     * runs, when nothing may write to this phone's copy.
     */
    suspend fun saveAllNotes(
        repositoryId: Long,
        onProgress: suspend (done: Int, toFetch: Int) -> Unit,
    ): Sync.SavedAll? {
        startUp().join()
        if (current.value !is AccountState.Idle) return null
        return sync.saveAllNotes(repositoryId, onProgress)
    }

    /** Revokes the session on the server when it can, then removes this phone's copy, unsent changes included. */
    fun signOut() {
        if (!begin(AccountState.Removing(null, null))) return
        // On disk before the server is asked: the user chose to remove this phone's copy whatever the server answers,
        // so process death from here on still removes it at the next start.
        store.startRemoval()
        sync.retire()
        scope.launch {
            val notRevoked =
                try {
                    api.delete("/api/app-auth/session")
                    null
                } catch (error: ApiException) {
                    // A 401: the server had already ended it, so nothing is left to revoke.
                    if (error.status == 401) null else AccountNotice.NotRevoked(error)
                }
            remove(notRevoked)
        }
    }

    /**
     * Asks the server to delete the account; only its confirmation removes this phone's copy. A refused, unanswered or
     * interrupted request, or a 401 (which says nothing about the account), keeps everything here.
     */
    fun deleteAccount() {
        if (!begin(AccountState.DeletingAccount)) return
        scope.launch {
            try {
                api.delete("/api/account")
            } catch (error: ApiException) {
                Log.w("RepoRead", "Account deletion not confirmed; status=${error.status} code=${error.code}")
                current.value = AccountState.Idle(AccountNotice.DeletionNotConfirmed(error))
                return@launch
            }
            // Confirmed: from here the removal finishes even across process death.
            store.startRemoval()
            remove(AccountNotice.AccountDeleted)
        }
    }

    /** Runs a stopped removal again. */
    fun retryRemoval() {
        val now = current.value
        if (now !is AccountState.Removing || now.stopped == null) return
        if (current.compareAndSet(now, now.copy(stopped = null))) scope.launch { remove(now.notice) }
    }

    /**
     * Exchanges the code from the sign-in redirect for a session. Data saved with no recorded owner cannot be proven to
     * be the new account's, so it is removed first, as a sign-out would, and no session is issued over it; a removal
     * that stops waits for Retry, and the sign-in for a new code.
     */
    fun signIn(code: String) {
        if (!begin(AccountState.SigningIn)) return
        scope.launch {
            try {
                val verifier =
                    store.pendingVerifier
                        ?: throw ApiException(
                            0,
                            "SIGN_IN_NOT_STARTED",
                            "This sign-in was not started from this app. Tap Sign in again.",
                        )
                store.pendingVerifier = null
                if (store.dataOwner == null) {
                    store.startRemoval()
                    val outcome = removeCopy()
                    if (outcome != LocalCleanup.Done) {
                        current.value = AccountState.Removing(outcome, null)
                        return@launch
                    }
                }
                completeSignIn(code, verifier, api, store)
                current.value = AccountState.Idle(null)
            } catch (error: ApiException) {
                current.value = AccountState.Idle(AccountNotice.SignInFailed(error))
            }
        }
    }

    fun dismissNotice() {
        val now = current.value
        if (now is AccountState.Idle && now.notice != null) current.compareAndSet(now, AccountState.Idle(null))
    }

    /** Removes this phone's copy as [AccountState.Removing], reporting [notice] once it is done. */
    private suspend fun remove(notice: AccountNotice?) {
        current.value = AccountState.Removing(null, notice)
        val outcome = removeCopy()
        current.value =
            if (outcome == LocalCleanup.Done) AccountState.Idle(notice) else AccountState.Removing(outcome, notice)
    }

    /**
     * Ends the session here and clears Room and the files; once all are gone, forgets the pending removal and the
     * data's owner together. Only while a removal is pending, so an interrupted one is finished at the next start.
     */
    private suspend fun removeCopy(): LocalCleanup {
        check(store.removalPending) { "Removing the phone's copy without a pending removal" }
        sync.retire()
        store.clear()
        // A restarted job must not acquire the next copy after the pending removal is forgotten.
        try {
            forgetSaveAll()
        } catch (error: SQLiteException) {
            Log.w("RepoRead", "Could not cancel and forget Save all before removing the phone's copy", error)
            return LocalCleanup.DatabaseFailed(error)
        }
        val outcome = sync.clearRetiredCopy()
        if (outcome == LocalCleanup.Done) {
            store.finishRemoval()
            sync = sync.freshCopy()
        }
        Log.i("RepoRead", "Phone's copy removed; outcome=${outcome.javaClass.simpleName}")
        return outcome
    }
}
