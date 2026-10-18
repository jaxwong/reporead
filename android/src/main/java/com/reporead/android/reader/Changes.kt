// Owns: Change-summary presentation and acknowledgement after display; the backend owns comparison results.

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
import com.reporead.android.core.network.wire.DocumentControllerChanges
import com.reporead.android.core.network.wire.NoteChangesSection

private fun lines(added: Int, removed: Int) =
    listOfNotNull(if (added > 0) "+$added" else null, if (removed > 0) "−$removed" else null)
        .joinToString(" ", postfix = " lines")

private fun label(section: NoteChangesSection): String {
    val title = section.headingPath.lastOrNull() ?: "Beginning of the note"
    return when (section.change) {
        NoteChangesSection.Change.ADDED -> "New section: $title"
        NoteChangesSection.Change.REMOVED -> "Removed: $title"
        NoteChangesSection.Change.CHANGED -> "$title (${lines(section.addedLines, section.removedLines)})"
    }
}

/** Where a section sits in the note, e.g. "in Databases › Isolation"; null at the top level. */
private fun parents(section: NoteChangesSection) =
    section.headingPath.dropLast(1).takeIf { it.isNotEmpty() }?.joinToString(" › ", prefix = "in ")

/** The panel's one line on what changed, or why it is not known yet. */
private fun summary(changes: Load<DocumentControllerChanges>): String =
    when (changes) {
        Load.Loading -> "Changed since you last read — comparing…"
        is Load.Failed -> "Changed since you last read. Couldn't compare: ${changes.error.describe()}"
        is Load.Ready -> {
            val comparison = changes.value
            when (comparison.status) {
                DocumentControllerChanges.Status.CHANGED ->
                    if (comparison.sections.isEmpty()) "Changed since you last read: only line endings differ."
                    else
                        "Changed since you last read: ${comparison.sections.size} sections, " +
                            lines(comparison.addedLines, comparison.removedLines)
                DocumentControllerChanges.Status.SINCE_UNAVAILABLE ->
                    "Changed since you last read, but that version can't be compared. ${comparison.reason}"
                DocumentControllerChanges.Status.TOO_LARGE ->
                    "Changed since you last read, too much to list by section. Read the full note."
                DocumentControllerChanges.Status.UNCHANGED -> "Unchanged since you last read."
            }
        }
    }

/**
 * What changed between the version last read and the one on screen. Tapping a current section scrolls the note to it
 * with [onOpen], null until the page is ready; removed sections are listed where they used to be, without a target.
 */
@Composable
internal fun ChangesPanel(
    changes: Load<DocumentControllerChanges>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onRetry: () -> Unit,
    onOpen: ((NoteChangesSection) -> Unit)?,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(summary(changes), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            when {
                changes is Load.Failed -> TextButton(onClick = onRetry) { Text("Try again") }
                changes is Load.Ready && changes.value.sections.isNotEmpty() ->
                    TextButton(onClick = onToggle) { Text(if (expanded) "Hide" else "Show") }
            }
        }
        val sections = (changes as? Load.Ready)?.value?.sections.orEmpty()
        if (expanded && sections.isNotEmpty()) {
            LazyColumn(Modifier.heightIn(max = 240.dp)) {
                items(sections) { section ->
                    val open = onOpen.takeIf { section.change != NoteChangesSection.Change.REMOVED }
                    Column(
                        Modifier.fillMaxWidth()
                            .let { if (open != null) it.clickable { open(section) } else it }
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text(label(section), style = MaterialTheme.typography.bodyLarge)
                        parents(section)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
        HorizontalDivider()
    }
}
