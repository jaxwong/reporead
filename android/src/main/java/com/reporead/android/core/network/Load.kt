package com.reporead.android.core.network

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Failed(val error: ApiException) : Load<Nothing>
    data class Ready<T>(val value: T) : Load<T>
}

/** Runs [block] when [key] changes. Failures are shown, never replaced with placeholder data. */
@Composable
fun <T> rememberLoad(key: Any?, onFailure: (ApiException) -> Unit, block: suspend () -> T): State<Load<T>> =
    produceState<Load<T>>(Load.Loading, key) {
        value = Load.Loading
        value = try {
            Load.Ready(block())
        } catch (error: ApiException) {
            onFailure(error)
            Load.Failed(error)
        }
    }

@Composable
fun <T> LoadContent(load: Load<T>, onRetry: () -> Unit, content: @Composable (T) -> Unit) {
    when (load) {
        Load.Loading -> CircularProgressIndicator(Modifier.padding(24.dp))
        is Load.Failed -> Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(load.error.describe())
            Button(onClick = onRetry) { Text("Try again") }
        }
        is Load.Ready -> content(load.value)
    }
}
