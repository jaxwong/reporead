package com.reporead.android.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.reporead.android.R

/* Shared building blocks so every screen has the same bar, rows, and messages. */

@Composable
fun AppIcon(@DrawableRes icon: Int, description: String?) = Icon(painterResource(icon), description)

/** A screen's top bar: back arrow when [onBack] is set, a one-line title with an optional subtitle, and actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppBar(title: String, subtitle: String? = null, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(
        title = {
            Column {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        },
        navigationIcon = { onBack?.let { IconButton(onClick = it) { AppIcon(R.drawable.ic_arrow_back, "Back") } } },
        actions = actions,
    )
}

data class MenuAction(val label: String, val onClick: () -> Unit)

/** The ⋮ overflow menu for actions that should not compete with the screen's content. */
@Composable
fun OverflowMenu(items: List<MenuAction>) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { AppIcon(R.drawable.ic_more_vert, "More") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (item in items) DropdownMenuItem(text = { Text(item.label) }, onClick = { open = false; item.onClick() })
        }
    }
}

/** "1 note", "2 notes": a count with its noun in the right number. */
fun counted(count: Int, one: String, many: String = one + "s") = "$count ${if (count == 1) one else many}"

/** The folder part of a repository path ("" at the root), shown under a note's title instead of the whole path. */
fun folderOf(path: String) = path.substringBeforeLast('/', "")

/** One note or folder in a list. [progress] (0–100) draws a reading-progress bar; [flags] are short states such as "Updated". */
@Composable
fun EntryRow(title: String, detail: String?, @DrawableRes icon: Int, flags: List<String> = emptyList(), progress: Int? = null,
             onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { AppIcon(icon, null) },
        headlineContent = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                val line = (flags + listOfNotNull(detail?.ifEmpty { null })).joinToString(" · ")
                if (line.isNotEmpty()) Text(line, maxLines = 2, overflow = TextOverflow.Ellipsis)
                progress?.let {
                    LinearProgressIndicator(progress = { it / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp), drawStopIndicator = {})
                }
            }
        },
        trailingContent = progress?.let { { Text("$it%", style = MaterialTheme.typography.labelMedium) } },
    )
}

fun LazyListScope.sectionTitle(title: String) {
    item(key = "section:$title") {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp))
    }
}

/** A calm explanation where a list would be, so an empty screen never looks broken. */
@Composable
fun EmptyState(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(24.dp))
}

/** A one-line status or failure under the top bar. */
@Composable
fun StatusLine(text: String?) {
    text?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp))
    }
}
