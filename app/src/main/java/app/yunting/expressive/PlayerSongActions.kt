@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package app.yunting.expressive

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.IOException
import kotlin.coroutines.coroutineContext

internal data class PlayerSongActions(
    val isFavorite: Boolean,
    val favoriteEnabled: Boolean,
    val addEnabled: Boolean,
    val onFavorite: () -> Unit,
    val onAddToPlaylist: () -> Unit,
)

@Composable
internal fun rememberPlayerSongActions(
    song: Song?,
    account: Account?,
    onPlaylistsChanged: () -> Unit,
    onLogin: () -> Unit,
): PlayerSongActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accountId = account?.id
    val songId = song?.id?.toLongOrNull()?.takeIf { it > 0 }
    val activeAccountId by rememberUpdatedState(accountId)
    val notifyPlaylistsChanged by rememberUpdatedState(onPlaylistsChanged)
    var favorites by remember(accountId) { mutableStateOf<Set<Long>?>(null) }
    var loadingFavorites by remember(accountId) { mutableStateOf(accountId != null) }
    var writingFavorite by remember(accountId) { mutableStateOf(false) }
    var showLogin by remember(accountId) { mutableStateOf(false) }
    var chosenSong by remember(accountId) { mutableStateOf<Song?>(null) }

    LaunchedEffect(accountId) {
        if (accountId == null) return@LaunchedEffect
        favorites = onIo { likedSongs(accountId, requireAccountSession(accountId)) }.getOrNull()
        loadingFavorites = false
    }

    fun toggleFavorite() {
        if (songId == null || writingFavorite || loadingFavorites) return
        if (accountId == null) {
            showLogin = true
            return
        }
        writingFavorite = true
        val known = favorites
        scope.launch {
            try {
                val result = onIo {
                    val session = requireAccountSession(accountId)
                    val ids = known ?: likedSongs(accountId, session)
                    val favorite = songId !in ids
                    coroutineContext.ensureActive()
                    Catalog.mobileApi(
                        "song/like",
                        JSONObject().put("trackId", songId).put("like", favorite),
                        expectedLoginCookie = session,
                    )
                    if (favorite) ids + songId else ids - songId
                }
                if (result.isSuccess) {
                    favorites = result.getOrThrow()
                    if (activeAccountId == accountId) notifyPlaylistsChanged()
                } else {
                    // Re-read after a failed response so a completed remote write is reflected in the heart.
                    favorites = onIo { likedSongs(accountId, requireAccountSession(accountId)) }.getOrNull()
                    if (activeAccountId == accountId) {
                        Toast.makeText(context, "未能确认收藏状态，请重试", Toast.LENGTH_SHORT).show()
                    }
                }
            } finally {
                writingFavorite = false
            }
        }
    }

    if (showLogin) {
        AlertDialog(
            onDismissRequest = { showLogin = false },
            title = { Text("登录网易云音乐") },
            text = { Text("登录后即可收藏歌曲或添加到自己的歌单。") },
            confirmButton = {
                TextButton(onClick = { showLogin = false; onLogin() }) { Text("登录") }
            },
            dismissButton = { TextButton(onClick = { showLogin = false }) { Text("取消") } },
        )
    }

    val target = chosenSong
    val targetId = target?.id?.toLongOrNull()
    if (accountId != null && target != null && targetId != null) {
        key(accountId, targetId) {
            AddToPlaylistDialog(
                song = target,
                onDismiss = { chosenSong = null },
                loadPlaylists = { loadOwnedPlaylists(accountId) },
                addSong = { addTrackToPlaylist(accountId, it, targetId) },
                onComplete = { playlist, added ->
                    chosenSong = null
                    if (activeAccountId == accountId) {
                        Toast.makeText(context, if (added) "已加入「${playlist.title}」" else "歌曲已在「${playlist.title}」中",
                            Toast.LENGTH_SHORT).show()
                        notifyPlaylistsChanged()
                    }
                },
            )
        }
    }

    return PlayerSongActions(
        isFavorite = songId != null && favorites?.contains(songId) == true,
        favoriteEnabled = songId != null && !loadingFavorites && !writingFavorite,
        addEnabled = songId != null,
        onFavorite = ::toggleFavorite,
        onAddToPlaylist = {
            if (songId != null) {
                if (accountId == null) showLogin = true else chosenSong = song
            }
        },
    )
}

private fun likedSongs(accountId: Long, session: String): Set<Long> {
    val ids = Catalog.mobileApi(
        "song/like/get", JSONObject().put("uid", accountId), expectedLoginCookie = session,
    ).optJSONArray("ids") ?: throw IOException("Missing liked song IDs")
    return buildSet {
        for (index in 0 until ids.length()) {
            val id = ids.optLong(index)
            if (id > 0) add(id)
        }
    }
}

@Composable
private fun AddToPlaylistDialog(
    song: Song,
    onDismiss: () -> Unit,
    loadPlaylists: () -> List<Playlist>,
    addSong: suspend (Long) -> Boolean,
    onComplete: (Playlist, Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var playlists by remember { mutableStateOf<List<Playlist>?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    var writeFailed by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var writing by remember { mutableStateOf(false) }
    LaunchedEffect(retry) {
        loadFailed = false
        val result = onIo { loadPlaylists() }
        playlists = result.getOrNull()
        loadFailed = result.isFailure
    }

    fun add(playlist: Playlist) {
        if (writing) return
        writing = true
        writeFailed = false
        scope.launch {
            try {
                val result = onIo { addSong(playlist.id) }
                result.onSuccess { onComplete(playlist, it) }
                writeFailed = result.isFailure
            } finally {
                writing = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!writing) onDismiss() },
        title = { Text("添加到歌单") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(song.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall)
                if ((!loadFailed && playlists == null) || writing) LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                if (writeFailed) Text("未能确认添加结果，请重试。", color = MaterialTheme.colorScheme.error)
                val lists = playlists
                when {
                    loadFailed -> TextButton(onClick = { retry++ }) { Text("加载失败，重试") }
                    lists?.isEmpty() == true -> Text("还没有自建歌单。")
                    lists != null -> LazyColumn(
                        Modifier.fillMaxWidth().heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(lists, key = Playlist::id) { playlist ->
                            Surface(onClick = { add(playlist) }, enabled = !writing,
                                shape = MaterialTheme.shapes.medium) {
                                ListItem(
                                    supportingContent = { Text("${playlist.size} 首") },
                                    leadingContent = {
                                        Cover(playlist.cover, Modifier.size(40.dp), 10, Icons.AutoMirrored.Rounded.QueueMusic)
                                    },
                                ) { Text(playlist.title, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = !writing) { Text("取消") }
        },
    )
}
