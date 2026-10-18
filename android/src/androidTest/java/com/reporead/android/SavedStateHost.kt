package com.reporead.android

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import com.reporead.android.auth.SessionStore
import com.reporead.android.core.network.Api
import com.reporead.android.data.LibraryDao
import kotlinx.coroutines.CoroutineScope

// Saved keys come from where composables are called. Keep this file unchanged: AppNavigationTest restores state that
// an earlier version of the app saved through it, at the keys it had then.

/**
 * The real RepoReadApp saving into [registry] instead of the activity's saved state, so a test can take what the app
 * saves, carry it through a Parcel as process death does, and restore it into a new activity.
 */
internal fun savedStateHost(
    registry: SaveableStateRegistry,
    account: Account,
    sessions: SessionStore,
    api: Api,
    dao: LibraryDao,
    scope: CoroutineScope,
): @Composable () -> Unit = {
    CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
        RepoReadApp(account, sessions, api, dao, scope, null) {}
    }
}
