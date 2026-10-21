// Owns: App-lifetime account operations; SessionStore owns the session. Local notes are never removed here.

package com.reporead.android

import com.reporead.android.auth.SessionStore
import com.reporead.android.auth.completeSignIn
import com.reporead.android.logging.AppLog
import com.reporead.android.network.Api
import com.reporead.android.network.ApiException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the last account operation reported, for the screen it ends on, until [Account.dismissNotice]. */
sealed interface AccountNotice {
    data object AccountDeleted : AccountNotice

    /** The server did not confirm deletion; a 401 says nothing about whether the account exists. */
    data class DeletionNotConfirmed(val error: ApiException) : AccountNotice

    /** Keeps the failed sign-in outcome until the user dismisses it; it does not own session persistence. */
    data class SignInFailed(val error: ApiException) : AccountNotice
}

/** What the account is doing; one operation runs at a time. */
sealed interface AccountState {
    /** Nothing runs; [notice] is what the last operation reported. */
    data class Idle(val notice: AccountNotice?) : AccountState

    /** Reserves the account operation while the app exchanges the validated sign-in code. */
    data object SigningIn : AccountState

    data object SigningOut : AccountState

    data object DeletingAccount : AccountState
}

/**
 * Runs one account operation at a time in the app's scope, surviving screen recreation. External calls: sign-in 1 (code
 * exchange), sign-out 1 (revocation), account deletion 1; none is retried. Ending a session never changes Room, images
 * or background work.
 */
class Account(private val store: SessionStore, private val api: Api, private val scope: CoroutineScope) {
    private val current = MutableStateFlow<AccountState>(AccountState.Idle(null))
    val state: StateFlow<AccountState> = current.asStateFlow()

    /** Starts [next] only when nothing runs: a second tap, or a sign-in code arriving meanwhile, does nothing. */
    private fun begin(next: AccountState): Boolean {
        val now = current.value
        val started = now is AccountState.Idle && current.compareAndSet(now, next)
        if (!started) AppLog.info("Account operation not started; running=$now requested=$next")
        return started
    }

    /** Failed revocation is logged, not retried; the server session expires within 30 days. */
    fun signOut() {
        if (!begin(AccountState.SigningOut)) return
        scope.launch {
            try {
                api.revokeSession()
            } catch (error: ApiException) {
                if (error.status == 401) AppLog.info("Session already ended; status=${error.status} code=${error.code}")
                else AppLog.warn("Session revocation failed; status=${error.status} code=${error.code}")
            }
            store.clear()
            current.value = AccountState.Idle(null)
        }
    }

    /** Clears the session on confirmation; Api may independently end it on a 401. Saved notes always stay. */
    fun deleteAccount() {
        if (!begin(AccountState.DeletingAccount)) return
        scope.launch {
            try {
                api.deleteAccount()
            } catch (error: ApiException) {
                AppLog.warn("Account deletion not confirmed; status=${error.status} code=${error.code}")
                current.value = AccountState.Idle(AccountNotice.DeletionNotConfirmed(error))
                return@launch
            }
            store.clear()
            current.value = AccountState.Idle(AccountNotice.AccountDeleted)
        }
    }

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
}
