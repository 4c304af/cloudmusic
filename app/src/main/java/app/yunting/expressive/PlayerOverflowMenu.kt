@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.yunting.expressive

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.AccessTime
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

@Composable
internal fun PlayerOverflowMenu(
    song: Song?,
    onOpenDetails: () -> Unit,
    onOpenQueue: () -> Unit,
    onAdjustLyrics: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenAlbum: ((Long) -> Unit)? = null,
    onOpenArtist: (() -> Unit)? = null,
    onAddToPlaylist: (() -> Unit)? = null,
    onFavorite: (() -> Unit)? = null,
    isFavorite: Boolean = false,
    onOpenComments: (() -> Unit)? = null,
    miniLyricsVisible: Boolean? = null,
    onToggleMiniLyrics: (() -> Unit)? = null,
    onChooseSimilar: ((List<Song>, Int) -> Unit)? = null,
    onInsertSimilar: (suspend (Long) -> String)? = null,
    onStartSimilar: (suspend (Long) -> String)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember(context) { DownloadStore(context) }
    val settings = remember(context) { context.getSharedPreferences("library", Context.MODE_PRIVATE) }
    var playbackQuality by remember(settings) {
        mutableStateOf(PlaybackQuality.fromLevel(settings.getString(PLAYBACK_QUALITY_KEY, null)))
    }
    var showMenu by remember(song?.id) { mutableStateOf(false) }
    var showTimer by remember(song?.id) { mutableStateOf(false) }
    var showQuality by remember(song?.id) { mutableStateOf(false) }
    var showSimilarSongs by remember(song?.id) { mutableStateOf(false) }
    var downloading by remember(song?.id) { mutableStateOf(false) }
    var updatingSimilarQueue by remember(song?.id) { mutableStateOf(false) }
    FilledTonalIconButton(onClick = { showMenu = true }, modifier = modifier, enabled = song != null) {
        Icon(Icons.Rounded.MoreVert, "更多播放选项")
    }
    if (song == null) return
    val cloudId = song.id.toLongOrNull()?.takeIf { it > 0 }

    fun updateSimilarQueue(action: suspend (Long) -> String) {
        val id = cloudId ?: return
        if (updatingSimilarQueue) return
        updatingSimilarQueue = true
        scope.launch {
            try {
                Toast.makeText(context, action(id), Toast.LENGTH_SHORT).show()
            } finally {
                updatingSimilarQueue = false
            }
        }
    }

    if (showMenu) {
        val options = buildList {
            if (cloudId != null) {
                add(PlayerMenuOption(
                    title = if (isFavorite) "取消收藏" else "收藏歌曲",
                    icon = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    action = { onFavorite?.invoke() },
                    enabled = onFavorite != null,
                ))
            }
            if (onAddToPlaylist != null && cloudId != null) {
                add(PlayerMenuOption("添加到歌单", Icons.AutoMirrored.Rounded.PlaylistAdd, onAddToPlaylist))
            }
            if (onOpenComments != null) {
                add(PlayerMenuOption("查看歌曲评论", Icons.AutoMirrored.Rounded.Chat, onOpenComments))
            }
            if (onOpenAlbum != null && song.albumId > 0) {
                val title = song.album.takeIf(String::isNotBlank)?.let { "专辑 · $it" } ?: "查看专辑"
                add(PlayerMenuOption(title, Icons.Rounded.Album, { onOpenAlbum(song.albumId) }))
            }
            if (onOpenArtist != null && cloudId != null) {
                val title = song.artist.takeIf(String::isNotBlank)?.let { "歌手 · $it" } ?: "查看歌手"
                add(PlayerMenuOption(title, Icons.Rounded.Person, onOpenArtist))
            }
            if (cloudId != null) {
                if (onStartSimilar != null) {
                    add(PlayerMenuOption("开始相似歌曲漫游", Icons.Rounded.Explore, { updateSimilarQueue(onStartSimilar) }, !updatingSimilarQueue))
                }
                if (onInsertSimilar != null) {
                    add(PlayerMenuOption("插播相似歌曲", Icons.AutoMirrored.Rounded.QueueMusic, { updateSimilarQueue(onInsertSimilar) }, !updatingSimilarQueue))
                }
                add(PlayerMenuOption("分享", Icons.Rounded.Share, { shareSong(context, song, cloudId) }))
            }
            if (cloudId != null && song.localUri == null) {
                add(PlayerMenuOption(if (downloading) "正在获取音源" else "下载", Icons.Rounded.Download, {
                    downloading = true
                    scope.launch {
                        try {
                            val status = store.enqueue(song) ?: "已提交下载，可在下载管理查看"
                            Toast.makeText(context, status, Toast.LENGTH_SHORT).show()
                        } finally {
                            downloading = false
                        }
                    }
                }, enabled = !downloading))
                add(PlayerMenuOption("音质 · ${playbackQuality.label}", Icons.Rounded.Tune, { showQuality = true }))
            }
            add(PlayerMenuOption("定时关闭", Icons.Rounded.AccessTime, { showTimer = true }))
        }
        val extraOptions = buildList {
            if (cloudId != null) {
                add(PlayerMenuOption("歌曲详情", Icons.Rounded.Info, onOpenDetails))
                add(PlayerMenuOption("相似歌曲", Icons.Rounded.MusicNote, { showSimilarSongs = true }))
            }
            add(PlayerMenuOption("播放队列", Icons.AutoMirrored.Rounded.QueueMusic, onOpenQueue))
            add(PlayerMenuOption("歌词延迟", Icons.Rounded.Lyrics, onAdjustLyrics))
        }

        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val sheetScope = rememberCoroutineScope()
        var closing by remember { mutableStateOf(false) }
        fun dismiss(action: () -> Unit = {}) {
            if (closing) return
            closing = true
            sheetScope.launch {
                sheetState.hide()
                showMenu = false
                action()
            }
        }
        ModalBottomSheet(
            onDismissRequest = { showMenu = false },
            sheetState = sheetState,
            shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            dragHandle = {
                Surface(
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp).size(width = 40.dp, height = 4.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.40f),
                    shape = RoundedCornerShape(2.dp),
                ) {}
            },
        ) {
            LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 16.dp)) {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Cover(song, Modifier.size(54.dp), radius = 12)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(song.title, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                            if (song.artist.isNotBlank()) {
                                Text(song.artist, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                            }
                        }
                        IconButton(onClick = { dismiss() }) { Icon(Icons.Rounded.Close, "关闭播放选项") }
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                    if (cloudId == null) {
                        Text("本地歌曲未匹配到云端信息，仅支持部分操作",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
                    }
                }
                items(options.size) { index ->
                    val option = options[index]
                    PlayerOverflowMenuRow(option, enabled = option.enabled && !closing) { dismiss(option.action) }
                }
                if (onToggleMiniLyrics != null && miniLyricsVisible != null) item {
                    Row(
                        Modifier.fillMaxWidth().toggleable(miniLyricsVisible, role = Role.Switch) { onToggleMiniLyrics() }
                            .padding(horizontal = 20.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Subtitles, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("播放页小歌词", Modifier.weight(1f), fontSize = 15.sp)
                        Switch(miniLyricsVisible, onCheckedChange = null)
                    }
                }
                item { HorizontalDivider(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) }
                items(extraOptions.size) { index ->
                    val option = extraOptions[index]
                    PlayerOverflowMenuRow(option, enabled = !closing) { dismiss(option.action) }
                }
            }
        }
    }

    if (showTimer) SleepTimerDialog(onDismiss = { showTimer = false })
    if (showQuality) {
        PlaybackQualityDialog(playbackQuality, onDismiss = { showQuality = false }, onChoose = { quality ->
            playbackQuality = quality
            settings.edit().putString(PLAYBACK_QUALITY_KEY, quality.level).apply()
            showQuality = false
        })
    }
    if (showSimilarSongs && cloudId != null) {
        SimilarSongsSheet(cloudId, onDismiss = { showSimilarSongs = false }, onChoose = onChooseSimilar)
    }
}

private data class PlayerMenuOption(
    val title: String,
    val icon: ImageVector,
    val action: () -> Unit,
    val enabled: Boolean = true,
)

@Composable
private fun PlayerOverflowMenuRow(option: PlayerMenuOption, enabled: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(option.icon, null, Modifier.size(24.dp), tint = colors.onSurfaceVariant)
        Text(option.title, color = colors.onSurface.copy(alpha = if (enabled) 1f else 0.38f),
            fontSize = 15.sp)
    }
}

private fun shareSong(context: Context, song: Song, id: Long) {
    val title = listOf(song.title, song.artist).filter(String::isNotBlank).joinToString(" · ")
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, "$title\nhttps://music.163.com/song?id=$id")
    }
    runCatching { context.startActivity(Intent.createChooser(intent, "分享歌曲")) }
        .onFailure { Toast.makeText(context, "系统分享不可用", Toast.LENGTH_SHORT).show() }
}

@Composable
private fun PlaybackQualityDialog(
    selected: PlaybackQuality,
    onDismiss: () -> Unit,
    onChoose: (PlaybackQuality) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Tune, null) },
        title = { Text("选择播放音质") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).selectableGroup()) {
                Text("实际音质取决于音源和账号权限。")
                for (quality in PlaybackQuality.entries) {
                    Row(
                        Modifier.fillMaxWidth().selectable(selected == quality, role = Role.RadioButton) { onChoose(quality) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RadioButton(selected = selected == quality, onClick = null)
                        Text(quality.label)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SimilarSongsSheet(id: Long, onDismiss: () -> Unit, onChoose: ((List<Song>, Int) -> Unit)?) {
    var attempt by remember(id) { mutableIntStateOf(0) }
    var songs by remember(id) { mutableStateOf<List<Song>?>(null) }
    var failed by remember(id) { mutableStateOf(false) }
    LaunchedEffect(id, attempt) {
        failed = false
        try {
            songs = withContext(Dispatchers.IO) { Catalog.similarSongs(id) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("相似歌曲", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "关闭相似歌曲") }
                }
            }
            val loaded = songs
            when {
                failed -> item {
                    Text("暂时无法加载相似歌曲", color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { attempt++ }) { Text("重试") }
                }
                loaded == null -> item { LinearWavyProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 12.dp)) }
                loaded.isEmpty() -> item { Text("暂无相似歌曲") }
                else -> itemsIndexed(loaded, key = { _, song -> song.id }) { index, song ->
                    SongCard(song, active = false, enabled = onChoose != null) {
                        onDismiss()
                        onChoose?.invoke(loaded, index)
                    }
                }
            }
        }
    }
}

@Composable
private fun SleepTimerDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val settings = remember(context) { context.getSharedPreferences("library", Context.MODE_PRIVATE) }
    var remaining by remember(settings) { mutableLongStateOf(settings.sleepTimerRemaining()) }
    var custom by remember { mutableStateOf(false) }
    var customInput by remember { mutableStateOf("") }
    val customMinutes = customInput.toLongOrNull()?.takeIf { it in 1L..1_439L }

    fun schedule(minutes: Long) {
        val deadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(minutes)
        settings.edit().putLong(SLEEP_TIMER_DEADLINE_KEY, deadline).apply()
        onDismiss()
    }

    LaunchedEffect(settings) {
        while (remaining > 0) {
            delay(1_000L)
            remaining = settings.sleepTimerRemaining()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Timer, null) },
        title = { Text("定时关闭") },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val minutesLeft = (remaining + 59_999L) / 60_000L
                Text(if (minutesLeft > 0) "约 $minutesLeft 分钟后暂停" else "选择多久后暂停播放")
                if (custom) {
                    OutlinedTextField(
                        value = customInput,
                        onValueChange = { value -> customInput = value.filter { it in '0'..'9' }.take(4) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("分钟") },
                        supportingText = { Text("1–1439 分钟") },
                        isError = customInput.isNotEmpty() && customMinutes == null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )
                } else {
                    for (minutes in listOf(10L, 15L, 30L, 45L, 60L)) {
                        TextButton(onClick = { schedule(minutes) }) { Text("$minutes 分钟") }
                    }
                    TextButton(onClick = { custom = true }) { Text("自定义时间") }
                }
            }
        },
        confirmButton = {
            if (custom) {
                TextButton(onClick = { customMinutes?.let(::schedule) }, enabled = customMinutes != null) {
                    Text("开启定时")
                }
            } else {
                TextButton(onClick = onDismiss) { Text("完成") }
            }
        },
        dismissButton = {
            if (custom) {
                TextButton(onClick = { custom = false }) { Text("返回") }
            } else if (remaining > 0) {
                TextButton(onClick = {
                    settings.edit().remove(SLEEP_TIMER_DEADLINE_KEY).apply()
                    onDismiss()
                }) { Text("取消定时") }
            }
        },
    )
}
