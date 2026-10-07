package com.reporead.android.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONArray

/** A heading in the displayed note: [index] is its position among the page's blocks, comparable with the reading position's. */
internal data class OutlineEntry(val blockId: String, val index: Int, val level: Int, val text: String)

internal fun parseOutline(json: String): List<OutlineEntry> {
    val array = JSONArray(json)
    return List(array.length()) {
        val item = array.getJSONObject(it)
        OutlineEntry(item.getString("blockId"), item.getInt("index"), item.getInt("level"), item.getString("text"))
    }
}

/** The heading whose section contains the block at [topBlock]; null before the first heading. */
internal fun List<OutlineEntry>.sectionAt(topBlock: Int): OutlineEntry? = lastOrNull { it.index <= topBlock }

/** The note's headings, indented by level, with the section on screen marked; tapping one jumps to it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OutlineSheet(outline: List<OutlineEntry>, current: OutlineEntry?, onJump: (OutlineEntry) -> Unit, onDismiss: () -> Unit) {
    val topLevel = outline.minOf { it.level }
    val currentPosition = outline.indexOf(current)
    // Opens with the current section near the top instead of at the start of a long outline.
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (currentPosition - 2).coerceAtLeast(0))
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text("Outline", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 24.dp, bottom = 8.dp))
        LazyColumn(state = list) {
            itemsIndexed(outline, key = { _, entry -> entry.blockId }) { position, entry ->
                val isCurrent = position == currentPosition
                Text(entry.text.ifBlank { "(empty heading)" },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isCurrent) FontWeight.Bold else null,
                    color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().clickable { onJump(entry) }
                        .padding(start = 24.dp + 16.dp * (entry.level - topLevel), end = 24.dp, top = 10.dp, bottom = 10.dp))
            }
        }
    }
}
