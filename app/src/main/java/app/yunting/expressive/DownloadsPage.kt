@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package app.yunting.expressive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
internal fun DownloadsPage(
    onBack: () -> Unit,
    ready: Boolean,
    onChoose: (List<Song>, Int) -> Unit,
) {
    val context = LocalContext.current
    val store = remember(context) { DownloadStore(context) }
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<DownloadEntry>?>(null) }
    var selection by remember { mutableStateOf<DownloadEntry?>(null) }
    var busyId by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(store) {
        while (true) {
            entries = store.entries()
            delay(1_000)
        }
    }

    fun update(entry: DownloadEntry, action: suspend () -> String?) {
        if (busyId != null) return
        busyId = entry.song.id
        scope.launch {
            try {
                message = action()
                entries = store.entries()
            } finally {
                busyId = null
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        PageHeading("下载管理", onBack)
        val current = entries
        when {
            current == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                LoadingIndicator()
            }
            current.isEmpty() -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("暂无下载。在播放页可保存歌曲以离线收听。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> {
                val completed = current.filter { it.status == DownloadStatus.Complete }.map(DownloadEntry::song)
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(current, key = { it.song.id }) { entry ->
                        DownloadRow(
                            entry,
                            ready,
                            anyBusy = busyId != null,
                            updating = busyId == entry.song.id,
                            onPlay = {
                                val index = completed.indexOfFirst { it.id == entry.song.id }
                                if (index >= 0) onChoose(completed, index)
                            },
                            onRetry = { update(entry) { store.enqueue(entry.song) } },
                            onRemove = { selection = entry },
                        )
                    }
                }
            }
        }
    }

    selection?.let { entry ->
        AlertDialog(
            onDismissRequest = { selection = null },
            title = { Text(if (entry.status == DownloadStatus.Complete) "删除离线文件？" else "移除下载？") },
            text = { Text(entry.song.title) },
            dismissButton = { TextButton(onClick = { selection = null }) { Text("取消") } },
            confirmButton = {
                TextButton(onClick = {
                    selection = null
                    update(entry) { store.remove(entry.song.id) }
                }) { Text("移除") }
            },
        )
    }
    message?.let { text ->
        AlertDialog(
            onDismissRequest = { message = null },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("知道了") } },
        )
    }
}

@Composable
private fun DownloadRow(
    entry: DownloadEntry,
    ready: Boolean,
    anyBusy: Boolean,
    updating: Boolean,
    onPlay: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val status = when (entry.status) {
        DownloadStatus.Queued -> "等待下载"
        DownloadStatus.Waiting -> "等待网络或系统调度"
        DownloadStatus.Transferring -> entry.progress?.let { "下载中 · ${(it * 100).roundToInt()}%" } ?: "下载中"
        DownloadStatus.Complete -> "可离线播放"
        DownloadStatus.Failed -> entry.error ?: "下载失败"
    }
    val description = listOf(entry.song.artist, status).filter(String::isNotBlank).joinToString(" · ")
    Surface(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = colors.surfaceContainerLow,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Cover(entry.song, Modifier.size(44.dp), 12)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(entry.song.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(description, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (entry.status == DownloadStatus.Failed) colors.error else colors.onSurfaceVariant)
                }
                when {
                    updating -> LoadingIndicator(Modifier.size(24.dp))
                    entry.status == DownloadStatus.Complete -> IconButton(onClick = onPlay, enabled = ready && !anyBusy) {
                        Icon(Icons.Rounded.PlayArrow, "离线播放")
                    }
                    entry.status == DownloadStatus.Failed -> IconButton(onClick = onRetry, enabled = !anyBusy) {
                        Icon(Icons.Rounded.Refresh, "重新下载")
                    }
                }
                IconButton(onClick = onRemove, enabled = !anyBusy) {
                    Icon(Icons.Rounded.Delete, "移除下载")
                }
            }
            if (entry.status == DownloadStatus.Transferring) {
                val progress = entry.progress
                val bar = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                if (progress == null) LinearWavyProgressIndicator(bar)
                else LinearWavyProgressIndicator(progress = { progress }, modifier = bar)
            }
        }
    }
}
