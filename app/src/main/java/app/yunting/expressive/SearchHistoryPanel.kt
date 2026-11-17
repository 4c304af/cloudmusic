@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.yunting.expressive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private const val CollapsedHistoryCount = 4

@Composable
internal fun SearchHistoryPanel(
    history: List<String>,
    onSelect: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClear: () -> Unit,
) {
    if (history.isEmpty()) return

    var expanded by rememberSaveable { mutableStateOf(false) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    val visibleHistory = if (expanded) history else history.take(CollapsedHistoryCount)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("搜索历史", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { confirmClear = true }) { Text("清空") }
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            visibleHistory.forEach { query ->
                key(query) {
                    SearchHistoryChip(query, onSelect = { onSelect(query) }, onRemove = { onRemove(query) })
                }
            }
            if (history.size > CollapsedHistoryCount) {
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起" else "展开全部") }
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空搜索历史？") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    expanded = false
                    onClear()
                }) { Text("清空") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun SearchHistoryChip(query: String, onSelect: () -> Unit, onRemove: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.widthIn(max = 260.dp),
        shape = CircleShape,
        color = colors.surfaceContainerHigh,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = onSelect,
                modifier = Modifier.weight(1f, fill = false),
                colors = ButtonDefaults.textButtonColors(contentColor = colors.onSurface),
                contentPadding = PaddingValues(start = 14.dp, end = 4.dp),
            ) {
                Text(query, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onRemove, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Rounded.Close, "删除搜索记录：$query", modifier = Modifier.size(16.dp))
            }
        }
    }
}
