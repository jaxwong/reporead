package com.reporead.android.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.reporead.android.core.network.Load
import com.reporead.android.core.network.describe
import com.reporead.android.sync.ChangedSection
import com.reporead.android.sync.Changes

private fun lines(added: Int, removed: Int) = listOfNotNull(if (added > 0) "+$added" else null, if (removed > 0) "−$removed" else null)
    .joinToString(" ", postfix = " lines")

private fun label(section: ChangedSection): String {
    val title = section.headingPath.lastOrNull() ?: "Beginning of the note"
    return when (section.change) {
        "ADDED" -> "New section: $title"
        "REMOVED" -> "Removed: $title"
        else -> "$title (${lines(section.addedLines, section.removedLines)})"
    }
}

/** Where a section sits in the note, e.g. "in Databases › Isolation"; null at the top level. */
private fun parents(section: ChangedSection) = section.headingPath.dropLast(1).takeIf { it.isNotEmpty() }?.joinToString(" › ", prefix = "in ")

/**
 * What changed between the version last read and the one on screen. Tapping a current section scrolls the note to it;
 * removed sections are listed where they used to be, without a target.
 */
@Composable
internal fun ChangesPanel(changes: Load<Changes>, expanded: Boolean, onToggle: () -> Unit, onRetry: () -> Unit,
                          onOpen: (ChangedSection) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            val summary = when (changes) {
                Load.Loading -> "Changed since you last read — comparing…"
                is Load.Failed -> "Changed since you last read. Couldn't compare: ${changes.error.describe()}"
                is Load.Ready -> when (changes.value.status) {
                    "CHANGED" -> if (changes.value.sections.isEmpty()) "Changed since you last read: only line endings differ."
                        else "Changed since you last read: ${changes.value.sections.size} sections, " +
                            lines(changes.value.addedLines, changes.value.removedLines)
                    "SINCE_UNAVAILABLE" -> "Changed since you last read, but that version can't be compared. ${changes.value.reason}"
                    "TOO_LARGE" -> "Changed since you last read, too much to list by section. Read the full note."
                    "UNCHANGED" -> "Unchanged since you last read."
                    else -> error("Sync.changes admits only known statuses, not ${changes.value.status}")
                }
            }
            Text(summary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            when {
                changes is Load.Failed -> TextButton(onClick = onRetry) { Text("Try again") }
                changes is Load.Ready && changes.value.sections.isNotEmpty() -> TextButton(onClick = onToggle) { Text(if (expanded) "Hide" else "Show") }
            }
        }
        val sections = (changes as? Load.Ready)?.value?.sections.orEmpty()
        if (expanded && sections.isNotEmpty()) {
            LazyColumn(Modifier.heightIn(max = 240.dp)) {
                items(sections) { section ->
                    val open = section.change != "REMOVED"
                    Column(Modifier.fillMaxWidth().let { if (open) it.clickable { onOpen(section) } else it }
                        .padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(label(section), style = MaterialTheme.typography.bodyLarge)
                        parents(section)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
        HorizontalDivider()
    }
}
