package com.reporead.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.reporead.android.network.ApiException
import com.reporead.android.network.describe

sealed interface Load<out T> {
    data object Loading : Load<Nothing>

    data class Failed(val failure: ApiException) : Load<Nothing>

    data class Ready<T>(val value: T) : Load<T>
}

@Composable
fun <T> LoadContent(load: Load<T>, onRetry: () -> Unit, content: @Composable (T) -> Unit) {
    when (load) {
        Load.Loading -> CircularProgressIndicator(Modifier.padding(24.dp))
        is Load.Failed ->
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(load.failure.describe())
                Button(onClick = onRetry) { Text("Try again") }
            }
        is Load.Ready -> content(load.value)
    }
}
