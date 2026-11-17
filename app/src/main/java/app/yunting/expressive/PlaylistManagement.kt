@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package app.yunting.expressive

import android.webkit.CookieManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import kotlin.coroutines.coroutineContext

@Composable
internal fun PlaylistManagementPage(
    account: Account?,
    initialPlaylistId: Long? = null,
    onBack: () -> Unit,
    onPlaylistsChanged: () -> Unit,
) {
    if (account == null) {
        Column(Modifier.fillMaxSize()) {
            PageHeading("歌单管理", onBack)
            ManageList {
                item { Text("登录网易云音乐后即可管理自建歌单。") }
            }
        }
        return
    }
    key(account.id) { OwnedPlaylists(account, initialPlaylistId, onBack, onPlaylistsChanged) }
}

@Composable
private fun OwnedPlaylists(
    account: Account,
    initialPlaylistId: Long?,
    onBack: () -> Unit,
    onPlaylistsChanged: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var playlists by remember { mutableStateOf<List<Playlist>>(emptyList()) }
    var revision by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var targetId by rememberSaveable(initialPlaylistId) { mutableStateOf(initialPlaylistId) }
    var action by remember { mutableStateOf<PlaylistAction?>(null) }
    var busy by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    val selected = playlists.firstOrNull { it.id == targetId }

    LaunchedEffect(revision) {
        loading = true
        val result = onIo { loadOwnedPlaylists(account.id) }
        result.onSuccess { playlists = it }
        failed = result.isFailure
        loading = false
    }

    fun back() {
        if (busy) return
        if (selected == null || initialPlaylistId != null) onBack() else {
            targetId = null
            feedback = null
            failure = null
        }
    }

    BackHandler(enabled = busy || (selected != null && initialPlaylistId == null)) { back() }
    fun submit(change: PlaylistAction, value: String = "", isPrivate: Boolean = false) {
        if (busy) return
        busy = true
        feedback = null
        failure = null
        scope.launch {
            try {
                val result = onIo { writePlaylist(account.id, change, value, isPrivate) }
                result.onSuccess { created ->
                    if (change is PlaylistAction.Delete) {
                        playlists = playlists.filterNot { it.id == change.playlist.id }
                        targetId = null
                    }
                    if (change == PlaylistAction.Create && created != null) {
                        playlists = playlists + Playlist(created, value, 0)
                        targetId = created
                    }
                    if (change is PlaylistAction.Rename) {
                        playlists = playlists.map { if (it.id == change.playlist.id) it.copy(title = value) else it }
                    }
                    revision++
                    feedback = change.success
                    action = null
                    onPlaylistsChanged()
                }
                result.onFailure {
                    failure = if (it is PlaylistSessionChanged) "登录状态已变更，请重新进入歌单管理。"
                        else "未能确认操作结果，请检查更新后的歌单再试。"
                    if (it !is PlaylistSessionChanged) revision++
                    action = null
                }
            } finally {
                busy = false
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        PageHeading("歌单管理", ::back)
        if (selected == null) {
            ManageList {
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { action = PlaylistAction.Create }, enabled = !busy) {
                            Icon(Icons.Rounded.Add, null)
                            Text("新建歌单")
                        }
                        TextButton(onClick = { revision++ }, enabled = !loading && !busy) { Text("刷新") }
                    }
                }
                if (loading || busy) item { PlaylistProgress() }
                feedback?.let { item { Text(it, color = MaterialTheme.colorScheme.primary) } }
                failure?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
                if (failed) item { Text("暂时无法加载自建歌单。", color = MaterialTheme.colorScheme.error) }
                else if (!loading && targetId != null) item {
                    Text("这个歌单不能编辑。可管理下方的自建歌单。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!loading && !failed && playlists.isEmpty()) item {
                    Text("还没有可管理的自建歌单。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(playlists, key = Playlist::id) { playlist ->
                    OutlinedCard(
                        onClick = {
                            targetId = playlist.id
                            feedback = null
                            failure = null
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                    ) {
                        ListItem(
                            headlineContent = { Text(playlist.title) },
                            supportingContent = { Text("${playlist.size} 首") },
                            leadingContent = { Cover(playlist.cover, Modifier.size(48.dp), 12, Icons.Rounded.QueueMusic) },
                            trailingContent = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) },
                        )
                    }
                }
            }
        } else {
            key(selected.id) {
                PlaylistContents(selected, revision, busy, feedback, failure,
                    onRefresh = { revision++ },
                    onAction = { action = it },
                    onAddSong = { submit(PlaylistAction.Add(selected, it)) },
                )
            }
        }
    }

    action?.let { current ->
        key(current) {
            when (current) {
                PlaylistAction.Create, is PlaylistAction.Rename -> PlaylistNameDialog(
                    initial = (current as? PlaylistAction.Rename)?.playlist?.title.orEmpty(),
                    create = current == PlaylistAction.Create,
                    busy = busy,
                    onClose = { action = null },
                    onSave = { name, isPrivate -> submit(current, name, isPrivate) },
                )
                is PlaylistAction.Remove, is PlaylistAction.Delete -> AlertDialog(
                    onDismissRequest = { if (!busy) action = null },
                    title = { Text(if (current is PlaylistAction.Delete) "删除歌单？" else "移除歌曲？") },
                    text = {
                        Text(if (current is PlaylistAction.Delete) {
                            "将从网易云删除「${current.playlist.title}」，此操作无法撤销。"
                        } else {
                            current as PlaylistAction.Remove
                            "将「${current.song.title}」从「${current.playlist.title}」移除。"
                        })
                    },
                    dismissButton = { TextButton(onClick = { action = null }, enabled = !busy) { Text("取消") } },
                    confirmButton = {
                        TextButton(onClick = { submit(current) }, enabled = !busy) {
                            Text(if (current is PlaylistAction.Delete) "删除" else "移除", color = MaterialTheme.colorScheme.error)
                        }
                    },
                )
                is PlaylistAction.Add -> Unit
            }
        }
    }
}

@Composable
private fun PlaylistContents(
    playlist: Playlist,
    revision: Int,
    busy: Boolean,
    feedback: String?,
    failure: String?,
    onRefresh: () -> Unit,
    onAction: (PlaylistAction) -> Unit,
    onAddSong: (Song) -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    var tracks by remember { mutableStateOf<List<Song>>(emptyList()) }
    var trackLoading by remember { mutableStateOf(true) }
    var trackError by remember { mutableStateOf(false) }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var submitted by remember { mutableStateOf("") }
    var searchRevision by remember { mutableIntStateOf(0) }
    var searching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<Song>>(emptyList()) }

    LaunchedEffect(playlist.id, revision) {
        trackLoading = true
        val result = onIo { Catalog.playlistTracks(playlist.id) }
        result.onSuccess { tracks = it.distinctBy(Song::id) }
        trackError = result.isFailure
        trackLoading = false
    }
    LaunchedEffect(showSearch, submitted, searchRevision) {
        if (!showSearch || submitted.isBlank()) return@LaunchedEffect
        searching = true
        val result = onIo { Catalog.search(submitted) }
        results = result.getOrDefault(emptyList()).filter { it.id.toLongOrNull()?.let { id -> id > 0 } == true }
            .distinctBy(Song::id)
        searchError = result.isFailure
        searching = false
    }
    fun submitSearch() {
        if (query.isBlank() || busy) return
        submitted = query.trim()
        searchRevision++
        keyboard?.hide()
    }
    val memberIds = remember(tracks) { tracks.mapTo(hashSetOf(), Song::id) }

    ManageList {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(playlist.title, style = MaterialTheme.typography.headlineSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { showSearch = !showSearch }, enabled = !busy) {
                        Text(if (showSearch) "收起搜索" else "添加歌曲")
                    }
                    TextButton(onClick = { onAction(PlaylistAction.Rename(playlist)) }, enabled = !busy) { Text("重命名") }
                    TextButton(onClick = { onAction(PlaylistAction.Delete(playlist)) }, enabled = !busy) {
                        Text("删除歌单", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        if (busy) item { PlaylistProgress() }
        feedback?.let { item { Text(it, color = MaterialTheme.colorScheme.primary) } }
        failure?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }

        if (showSearch) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        label = { Text("搜索歌曲或歌手") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { submitSearch() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    FilledTonalButton(onClick = ::submitSearch, enabled = query.isNotBlank() && !busy) { Text("搜索") }
                }
            }
            if (searching) item { PlaylistProgress() }
            else if (submitted.isNotBlank() && results.isEmpty()) item {
                Text(if (searchError) "暂时无法搜索，请重试。" else "未找到歌曲。")
            }
            if (!searching) items(results, key = { "search:${it.id}" }) { song ->
                val alreadyAdded = song.id in memberIds
                ManagedTrack(song) {
                    TextButton(onClick = { onAddSong(song) }, enabled = !alreadyAdded && !busy && !trackLoading && !trackError) {
                        Text(if (alreadyAdded) "已添加" else "添加")
                    }
                }
            }
        }

        item {
            Text("歌单歌曲", style = MaterialTheme.typography.titleLarge)
            if (trackError) TextButton(onClick = onRefresh, enabled = !busy) { Text("加载失败，重试") }
            else if (!trackLoading && tracks.isEmpty()) Text("歌单里还没有歌曲。")
        }
        if (trackLoading && !busy) item { PlaylistProgress() }
        items(tracks, key = { "member:${it.id}" }) { song ->
            ManagedTrack(song) {
                TextButton(onClick = { onAction(PlaylistAction.Remove(playlist, song)) }, enabled = !busy && !trackLoading) {
                    Text("移除")
                }
            }
        }
    }
}

@Composable
private fun ManageList(content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
private fun ManagedTrack(song: Song, action: @Composable () -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        ListItem(
            headlineContent = { Text(song.title) },
            supportingContent = { if (song.artist.isNotBlank()) Text(song.artist) },
            leadingContent = { Cover(song, Modifier.size(40.dp), radius = 12) },
            trailingContent = action,
            colors = ListItemDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        )
    }
}

@Composable
private fun PlaylistProgress() {
    LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
}

@Composable
private fun PlaylistNameDialog(
    initial: String,
    create: Boolean,
    busy: Boolean,
    onClose: () -> Unit,
    onSave: (String, Boolean) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initial) }
    var isPrivate by rememberSaveable { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text(if (create) "新建歌单" else "重命名歌单") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("歌单名称") }, singleLine = true,
                    enabled = !busy, modifier = Modifier.fillMaxWidth())
                if (create) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = isPrivate, onCheckedChange = { isPrivate = it }, enabled = !busy)
                    Text("设为私密歌单")
                }
            }
        },
        dismissButton = { TextButton(onClick = onClose, enabled = !busy) { Text("取消") } },
        confirmButton = {
            TextButton(
                onClick = { onSave(name.trim(), isPrivate) },
                enabled = !busy && name.isNotBlank() && (create || name.trim() != initial),
            ) { Text(if (create) "创建" else "保存") }
        },
    )
}

private sealed interface PlaylistAction {
    val success: String
    data object Create : PlaylistAction { override val success = "歌单已创建" }
    data class Rename(val playlist: Playlist) : PlaylistAction { override val success = "名称已保存" }
    data class Delete(val playlist: Playlist) : PlaylistAction { override val success = "歌单已删除" }
    data class Add(val playlist: Playlist, val song: Song) : PlaylistAction { override val success = "歌曲已添加" }
    data class Remove(val playlist: Playlist, val song: Song) : PlaylistAction { override val success = "歌曲已移除" }
}

internal fun loadOwnedPlaylists(accountId: Long): List<Playlist> {
    var offset = 0
    val owned = linkedMapOf<Long, Playlist>()
    repeat(20) {
        val response = Catalog.request("/api/user/playlist", "uid" to accountId.toString(), "limit" to "100",
            "offset" to offset.toString())
        val items = response.optJSONArray("playlist") ?: throw IOException("Missing playlist list")
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            val id = item.optLong("id").takeIf { it > 0 } ?: continue
            val owner = item.optLong("userId").takeIf { it > 0 } ?: item.optJSONObject("creator")?.optLong("userId")
            // NetEase's system-managed Liked Songs playlist is not an editable normal playlist.
            if (owner != accountId || item.optInt("specialType") == 5) continue
            owned[id] = Playlist(id, item.optString("name"), item.optInt("trackCount"),
                Catalog.httpsUri(item.optString("coverImgUrl"))?.toString().orEmpty())
        }
        if (!response.optBoolean("more") || items.length() == 0) return owned.values.toList()
        offset += items.length()
    }
    return owned.values.toList()
}

private class PlaylistSessionChanged : IOException()

private fun loginCookie(): String? = CookieManager.getInstance().getCookie("https://music.163.com")
    ?.split(';')?.asSequence()?.map(String::trim)?.firstOrNull { it.startsWith("MUSIC_U=") }
    ?.substringAfter('=')?.takeIf(String::isNotBlank)

internal suspend fun requireAccountSession(accountId: Long): String {
    val session = loginCookie() ?: throw PlaylistSessionChanged()
    if (Catalog.account()?.id != accountId || loginCookie() != session) throw PlaylistSessionChanged()
    coroutineContext.ensureActive()
    return session
}

private suspend fun writePlaylist(accountId: Long, action: PlaylistAction, name: String, isPrivate: Boolean): Long? {
    val session = requireAccountSession(accountId)
    when (action) {
        PlaylistAction.Create -> {
            val response = Catalog.mobileApi("playlist/create", JSONObject()
                .put("name", name).put("type", "NORMAL").put("privacy", if (isPrivate) 10 else 0),
                expectedLoginCookie = session)
            return response.optLong("id").takeIf { it > 0 }
                ?: response.optJSONObject("playlist")?.optLong("id")?.takeIf { it > 0 }
        }
        is PlaylistAction.Rename -> Catalog.mobileApi("playlist/update/name",
            JSONObject().put("id", action.playlist.id).put("name", name), expectedLoginCookie = session)
        is PlaylistAction.Delete -> Catalog.weapi("/weapi/playlist/remove",
            JSONObject().put("ids", "[${action.playlist.id}]"), expectedLoginCookie = session)
        is PlaylistAction.Add -> changeTracks(action.playlist.id, action.song.id, "add", session)
        is PlaylistAction.Remove -> changeTracks(action.playlist.id, action.song.id, "del", session)
    }
    return null
}

internal suspend fun addTrackToPlaylist(accountId: Long, playlistId: Long, trackId: Long): Boolean {
    val session = requireAccountSession(accountId)
    if (playlistContainsTrack(playlistId, trackId)) return false
    coroutineContext.ensureActive()
    try {
        changeTracks(playlistId, trackId.toString(), "add", session)
    } catch (error: IOException) {
        // An already-present song or a lost write response can both yield an error from this API.
        coroutineContext.ensureActive()
        if (loginCookie() != session || !runCatching { playlistContainsTrack(playlistId, trackId) }.getOrDefault(false)) {
            throw error
        }
        return false
    }
    return true
}

private fun playlistContainsTrack(playlistId: Long, trackId: Long): Boolean {
    val response = Catalog.request("/api/playlist/detail", "id" to playlistId.toString())
    val data = response.optJSONObject("playlist") ?: response.optJSONObject("result")
    val tracks = data?.optJSONArray("trackIds") ?: data?.optJSONArray("tracks")
        ?: throw IOException("Missing playlist tracks")
    return (0 until tracks.length()).any { index -> tracks.optJSONObject(index)?.optLong("id") == trackId }
}

private fun changeTracks(playlistId: Long, songId: String, op: String, session: String) {
    val track = songId.toLongOrNull()?.takeIf { it > 0 } ?: throw IOException("Invalid track ID")
    Catalog.mobileApi("playlist/manipulate/tracks",
        JSONObject().put("op", op).put("pid", playlistId)
            .put("trackIds", JSONArray().put(track.toString()).toString()).put("imme", "true"),
        expectedLoginCookie = session)
}

internal suspend fun <T> onIo(task: suspend () -> T): Result<T> = withContext(Dispatchers.IO) {
    try {
        Result.success(task())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }
}
