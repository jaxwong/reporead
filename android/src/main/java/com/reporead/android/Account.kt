// Owns: App-lifetime account operations; SessionStore owns the session. Local notes are never removed here.

package com.reporead.android

import com.reporead.android.auth.SessionStore
import com.reporead.android.auth.completeSignIn
import com.reporead.android.logging.DeviceLog
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
    private val _state = MutableStateFlow<AccountState>(AccountState.Idle(null))
    val state: StateFlow<AccountState> = _state.asStateFlow()

    /** Starts [next] only when nothing runs: a second tap, or a sign-in code arriving meanwhile, does nothing. */
    private fun begin(next: AccountState): Boolean {
        val stateSnapshot = _state.value
        val started = stateSnapshot is AccountState.Idle && _state.compareAndSet(stateSnapshot, next)
        if (!started) DeviceLog.info("Account operation not started; running=$stateSnapshot requested=$next")
        return started
    }

    /** Failed revocation is logged, not retried; the server session expires within 30 days. */
    fun signOut() {
        if (!begin(AccountState.SigningOut)) return
        scope.launch {
            try {
                api.revokeSession()
            } catch (failure: ApiException) {
                if (failure.status == 401)
                    DeviceLog.info("Session already ended; status=${failure.status} code=${failure.code}")
                else DeviceLog.warn("Session revocation failed; status=${failure.status} code=${failure.code}")
            }
            store.clear()
            _state.value = AccountState.Idle(null)
        }
    }

    /** Clears the session on confirmation; Api may independently end it on a 401. Saved notes always stay. */
    fun deleteAccount() {
        if (!begin(AccountState.DeletingAccount)) return
        scope.launch {
            try {
                api.deleteAccount()
            } catch (failure: ApiException) {
                DeviceLog.warn("Account deletion not confirmed; status=${failure.status} code=${failure.code}")
                _state.value = AccountState.Idle(AccountNotice.DeletionNotConfirmed(failure))
                return@launch
            }
            store.clear()
            _state.value = AccountState.Idle(AccountNotice.AccountDeleted)
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
                _state.value = AccountState.Idle(null)
            } catch (failure: ApiException) {
                _state.value = AccountState.Idle(AccountNotice.SignInFailed(failure))
            }
        }
    }

    fun dismissNotice() {
        val stateSnapshot = _state.value
        if (stateSnapshot is AccountState.Idle && stateSnapshot.notice != null)
            _state.compareAndSet(stateSnapshot, AccountState.Idle(null))
    }
}
