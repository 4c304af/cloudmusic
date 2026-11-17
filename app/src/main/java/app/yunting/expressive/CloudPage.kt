@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package app.yunting.expressive

import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun CloudPage(
    account: Account?,
    ready: Boolean,
    onBack: () -> Unit,
    onLogin: () -> Unit,
    onChoose: (List<Song>, Int) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        if (account == null) {
            PageHeading("音乐云盘", onBack)
            Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("登录后查看你的云盘歌曲", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = onLogin) { Text("登录网易云音乐") }
                }
            }
        } else {
            key(account.id) { CloudSongs(account.id, ready, onBack, onChoose) }
        }
    }
}

@Composable
private fun CloudSongs(accountId: Long, ready: Boolean, onBack: () -> Unit, onChoose: (List<Song>, Int) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uploader: CloudUploadModel = viewModel(key = "cloud-upload-$accountId")
    val upload by uploader.status.collectAsState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { files ->
        uploader.upload(context, accountId, files)
    }
    var tracks by remember { mutableStateOf(emptyList<CloudTrack>()) }
    var batch by remember { mutableStateOf<CloudBatch?>(null) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var requestedOffset by remember { mutableIntStateOf(0) }
    var attempt by remember { mutableIntStateOf(0) }
    var uploadRevision by remember { mutableIntStateOf(upload.revision) }
    var menuTrack by remember { mutableStateOf<Long?>(null) }
    var removeTrack by remember { mutableStateOf<CloudTrack?>(null) }
    var matchTrack by remember { mutableStateOf<CloudTrack?>(null) }
    var updating by remember { mutableStateOf(false) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(attempt) {
        try {
            val result = withContext(Dispatchers.IO) { CloudCatalog.tracks(requestedOffset) }
            tracks = if (requestedOffset == 0) result.tracks else (tracks + result.tracks).distinctBy(CloudTrack::storageId)
            batch = result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        } finally {
            loading = false
        }
    }

    fun load(offset: Int) {
        if (loading) return
        requestedOffset = offset
        failed = false
        loading = true
        attempt++
    }

    LaunchedEffect(upload.revision, upload.running, loading) {
        if (!upload.running && !loading && uploadRevision != upload.revision) {
            uploadRevision = upload.revision
            load(0)
        }
    }

    val searchQuery = if (searchOpen) query.trim() else ""
    val nextOffset = batch?.nextOffset
    LaunchedEffect(searchQuery, nextOffset, loading, failed, updating) {
        if (searchQuery.isNotEmpty() && nextOffset != null && !failed && !loading && !updating) {
            delay(250)
            load(nextOffset)
        }
    }

    fun update(error: String, operation: () -> Unit) {
        if (updating || loading) return
        updating = true
        actionError = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    check(Catalog.account()?.id == accountId)
                    operation()
                }
                removeTrack = null
                matchTrack = null
                load(0)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                actionError = error
            } finally {
                updating = false
            }
        }
    }

    PageHeading("音乐云盘", onBack) {
        IconButton(onClick = { searchOpen = !searchOpen }) {
            Icon(Icons.Rounded.Search, if (searchOpen) "关闭云盘搜索" else "搜索云盘歌曲")
        }
        FilledTonalIconButton(onClick = { picker.launch(arrayOf("audio/*")) }, enabled = !upload.running) {
            Icon(Icons.Rounded.CloudUpload, "上传本地音乐")
        }
    }
    val visibleTracks = remember(tracks, searchQuery) {
        if (searchQuery.isEmpty()) tracks else tracks.filter { track ->
            val song = track.song
            song.title.contains(searchQuery, ignoreCase = true) || song.artist.contains(searchQuery, ignoreCase = true) ||
                song.album.contains(searchQuery, ignoreCase = true)
        }
    }
    val queue = remember(visibleTracks) { visibleTracks.map(CloudTrack::song) }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (searchOpen) item {
            OutlinedTextField(value = query, onValueChange = { query = it },
                label = { Text("搜索歌名、歌手或专辑") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        batch?.let { loaded ->
            item {
                CloudUsage(loaded, loading, onRefresh = { load(0) })
            }
        }
        if (upload.total > 0) item {
            CloudUploadPanel(upload, onRetry = { uploader.retry(context, accountId) }, onCancel = uploader::cancel)
        }
        if (loading && tracks.isEmpty()) item {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { LoadingIndicator() }
        }
        if (!loading && !failed && tracks.isEmpty()) item {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("云盘暂无歌曲", color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { picker.launch(arrayOf("audio/*")) }, enabled = !upload.running) {
                    Text("上传本地音乐")
                }
            }
        }
        if (!loading && !failed && tracks.isNotEmpty() && visibleTracks.isEmpty() && nextOffset == null) item {
            Text("没有找到匹配的云盘歌曲", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        itemsIndexed(visibleTracks, key = { _, track -> track.storageId }) { index, track ->
            val song = track.song
            ListItem(
                headlineContent = { Text(song.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                supportingContent = if (song.artist.isBlank()) null else ({
                    Text(song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }),
                leadingContent = { Cover(song, Modifier.size(48.dp), 16) },
                trailingContent = {
                    Box {
                        IconButton(onClick = { menuTrack = track.storageId }, enabled = !loading && !updating) {
                            Icon(Icons.Rounded.MoreVert, "歌曲操作")
                        }
                        DropdownMenu(expanded = menuTrack == track.storageId, onDismissRequest = { menuTrack = null }) {
                            DropdownMenuItem(text = { Text("重新匹配") }, onClick = {
                                menuTrack = null
                                actionError = null
                                matchTrack = track
                            })
                            DropdownMenuItem(text = { Text("从云盘删除") }, onClick = {
                                menuTrack = null
                                actionError = null
                                removeTrack = track
                            })
                        }
                    }
                },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                modifier = Modifier.heightIn(min = 72.dp).clip(RoundedCornerShape(20.dp))
                    .clickable(enabled = ready, role = Role.Button) { onChoose(queue, index) },
            )
        }
        if (failed) item {
            Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("暂时无法加载云盘歌曲", color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { load(requestedOffset) }) { Text("重试") }
            }
        }
        if (loading && tracks.isNotEmpty()) item {
            LinearWavyProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 12.dp))
        }
        if (!failed && !loading && nextOffset != null) item {
            if (searchQuery.isNotEmpty()) {
                Text("正在搜索更多云盘歌曲", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                TextButton(onClick = { load(nextOffset) }, modifier = Modifier.fillMaxWidth()) { Text("加载更多") }
            }
        }
    }

    removeTrack?.let { track ->
        AlertDialog(
            onDismissRequest = { if (!updating) removeTrack = null },
            title = { Text("从云盘删除？") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("将从音乐云盘移除「${track.song.title}」。")
                    actionError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = { update("删除失败，请重试") { CloudCatalog.remove(track.storageId) } },
                    enabled = !updating && !loading) {
                    Text(if (updating) "正在删除" else "删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { removeTrack = null }, enabled = !updating) { Text("取消") } },
        )
    }

    matchTrack?.let { track ->
        key(track.storageId) {
            CloudMatchDialog(
                track = track,
                updating = updating || loading,
                error = actionError,
                onDismiss = { matchTrack = null },
                onMatch = { songId ->
                    update("匹配失败，请重试") { CloudCatalog.match(accountId, track.storageId, songId) }
                },
            )
        }
    }
}

@Composable
private fun CloudUploadPanel(upload: CloudUploadStatus, onRetry: () -> Unit, onCancel: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(24.dp), color = colors.surfaceContainerHigh) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(when {
                upload.running -> "上传音乐"
                upload.stopped -> "上传已停止 · 已上传 ${upload.completed} / ${upload.total} 首"
                else -> "已上传 ${upload.completed} / ${upload.total} 首"
            }, style = MaterialTheme.typography.titleMedium)
            if (upload.running) {
                Text(upload.file, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val percent = upload.progress
                Text(if (percent == null) upload.stage else "${upload.stage} $percent%", style = MaterialTheme.typography.bodyMedium)
                if (percent == null) LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                else LinearWavyProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = onCancel) { Text("停止上传") }
            }
            upload.errors.take(3).forEach { message -> Text(message, color = colors.error) }
            if (upload.errors.size > 3) Text("还有 ${upload.errors.size - 3} 个文件未上传", color = colors.error)
            if (!upload.running && upload.errors.isNotEmpty()) TextButton(onClick = onRetry) { Text("重试失败项") }
        }
    }
}

@Composable
private fun CloudMatchDialog(
    track: CloudTrack,
    updating: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onMatch: (Long) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf(track.song.title) }
    var request by remember { mutableStateOf<String?>(null) }
    var songs by remember { mutableStateOf(emptyList<Song>()) }
    var selected by remember { mutableStateOf<Song?>(null) }
    var searching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    LaunchedEffect(revision) {
        val name = request ?: return@LaunchedEffect
        searching = true
        searchError = false
        songs = emptyList()
        try {
            songs = withContext(Dispatchers.IO) { Catalog.search(name).distinctBy(Song::id) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            searchError = true
        } finally {
            searching = false
        }
    }

    AlertDialog(
        onDismissRequest = { if (!updating) onDismiss() },
        title = { Text("重新匹配云盘歌曲") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(track.song.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (selected != null) {
                    Text("匹配到「${selected?.title}」？")
                } else {
                    OutlinedTextField(value = query, onValueChange = { query = it },
                        label = { Text("歌曲名或歌手") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        enabled = !updating)
                    TextButton(
                        onClick = { request = query.trim(); revision++ },
                        enabled = !updating && !searching && query.isNotBlank(),
                    ) { Text("搜索") }
                    when {
                        searching -> LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                        searchError -> Text("搜索失败，请重试", color = MaterialTheme.colorScheme.error)
                        request != null && songs.isEmpty() -> Text("没有找到歌曲")
                        songs.isNotEmpty() -> LazyColumn(Modifier.heightIn(max = 200.dp)) {
                            items(songs, key = Song::id) { song ->
                                ListItem(
                                    headlineContent = { Text(song.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                    supportingContent = { Text(song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    modifier = Modifier.clickable(enabled = !updating) { selected = song },
                                )
                            }
                        }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            val songId = selected?.id?.toLongOrNull()
            if (songId != null) TextButton(onClick = { onMatch(songId) }, enabled = !updating) {
                Text(if (updating) "正在匹配" else "确认匹配")
            }
        },
        dismissButton = {
            TextButton(onClick = { if (selected == null) onDismiss() else selected = null }, enabled = !updating) {
                Text(if (selected == null) "关闭" else "更换歌曲")
            }
        },
    )
}

@Composable
private fun CloudUsage(data: CloudBatch, loading: Boolean, onRefresh: () -> Unit) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(32.dp), color = colors.primaryContainer, contentColor = colors.onPrimaryContainer) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${data.total} 首", Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
                TextButton(onClick = onRefresh, enabled = !loading) {
                    Text("刷新", color = if (loading) colors.onPrimaryContainer.copy(alpha = 0.38f) else colors.onPrimaryContainer)
                }
            }
            if (data.capacityBytes > 0) {
                val used = Formatter.formatShortFileSize(context, data.usedBytes)
                val capacity = Formatter.formatShortFileSize(context, data.capacityBytes)
                val fraction = (data.usedBytes.toDouble() / data.capacityBytes).toFloat().coerceIn(0f, 1f)
                Text("已用 $used / $capacity", style = MaterialTheme.typography.bodyMedium)
                LinearWavyProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    color = colors.onPrimaryContainer, trackColor = colors.onPrimaryContainer.copy(alpha = 0.24f), amplitude = { 0.5f })
            }
        }
    }
}
