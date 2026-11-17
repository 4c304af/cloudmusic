@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package app.yunting.expressive

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.LocalDate

private data class DetailLink(val title: String, val route: String, val cover: String = "")
private data class OnlineDetail(
    val title: String,
    val subtitle: String = "",
    val cover: String = "",
    val songs: List<Song> = emptyList(),
    val links: List<DetailLink> = emptyList(),
    val download: Song? = null,
)

@Composable
internal fun OnlineDetailPage(
    route: String, ready: Boolean, now: Song?, onBack: () -> Unit,
    onOpen: (String) -> Unit, onChoose: (List<Song>, Int) -> Unit,
) {
    val parts = route.split('/')
    if (parts.size == 3 && parts[0] == "comments") {
        parts[2].toLongOrNull()?.let { id ->
            CommentsPage(parts[1], id, onBack) { onOpen("profile/$it") }
        }
        return
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val downloads = remember(context) { DownloadStore(context) }
    var feedback by remember(route) { mutableStateOf<String?>(null) }
    var saving by remember(route) { mutableStateOf(false) }
    val detail = rememberRequest(route) { loadDetail(parts, now) }
    Column(Modifier.fillMaxSize()) {
        PageHeading(detail.value?.title ?: "详情", onBack)
        RequestContent(detail) { item ->
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (item.cover.isNotBlank() || item.subtitle.isNotBlank()) item {
                    Card(shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            if (item.cover.isNotBlank()) Cover(item.cover, Modifier.size(72.dp), 20)
                            if (item.subtitle.isNotBlank()) {
                                Text(item.subtitle, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
                item.download?.let { song ->
                    item {
                        FilledTonalButton(onClick = {
                            saving = true
                            feedback = null
                            scope.launch {
                                try {
                                    feedback = downloads.enqueue(song) ?: "已提交下载，可在下载管理查看"
                                } finally {
                                    saving = false
                                }
                            }
                        }, enabled = !saving) { Text(if (saving) "正在获取音源" else "下载歌曲") }
                        feedback?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    }
                }
                items(item.links, key = { it.route }) { link ->
                    OutlinedCard(onClick = { onOpen(link.route) }, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            if (link.cover.isNotBlank()) Cover(link.cover, Modifier.size(40.dp), 16, Icons.Rounded.Person)
                            Text(link.title, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
                if (item.songs.isNotEmpty()) {
                    item { Text("歌曲", style = MaterialTheme.typography.titleLarge) }
                    items(item.songs.size) { index ->
                        val song = item.songs[index]
                        SongCard(song, now?.id == song.id, ready) { onChoose(item.songs, index) }
                    }
                }
                if (item.links.isEmpty() && item.songs.isEmpty() && item.download == null) item {
                    Text("暂无可展示的内容", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun loadDetail(parts: List<String>, now: Song?): OnlineDetail {
    val id = parts.getOrNull(1)?.toLongOrNull()?.takeIf { it > 0 } ?: throw IOException()
    return when (parts.first()) {
        "playlist" -> {
            val response = Catalog.request("/api/playlist/detail", "id" to id.toString())
            val list = (response.optJSONObject("playlist") ?: response.optJSONObject("result")) ?: throw IOException()
            OnlineDetail(list.optString("name"), list.text("description"), cover(list, "coverImgUrl"),
                Catalog.songs(list.optJSONArray("tracks")), listOf(DetailLink("评论", "comments/A_PL_0_/$id")))
        }
        "album" -> {
            val data = Catalog.request("/api/v1/album/$id")
            val album = data.getJSONObject("album")
            val artists = parseSongArtists(album.optJSONArray("artists")).ifEmpty {
                parseSongArtists(JSONArray().put(album.optJSONObject("artist")))
            }
            OnlineDetail(album.optString("name"), album.text("description"), cover(album, "picUrl"),
                Catalog.songs(data.optJSONArray("songs") ?: album.optJSONArray("songs")),
                artists.map(::artistLink) + DetailLink("评论", "comments/R_AL_3_/$id"))
        }
        "profile" -> {
            val profile = Catalog.request("/api/v1/user/detail/$id").getJSONObject("profile")
            val playlists = runCatching { Catalog.userPlaylists(id) }.getOrDefault(emptyList())
            OnlineDetail(profile.optString("nickname"), profile.text("signature"), cover(profile, "avatarUrl"),
                links = listOf(
                    DetailLink("关注 · ${profile.optInt("follows")}", "following/$id"),
                    DetailLink("粉丝 · ${profile.optInt("followeds")}", "followers/$id"),
                ) + playlists.map { DetailLink(it.title, "playlist/${it.id}", it.cover) })
        }
        "following", "followers" -> {
            val follows = parts.first() == "following"
            val response = Catalog.request("/api/user/${if (follows) "getfollows" else "getfolloweds"}/$id",
                "limit" to "50", "offset" to "0")
            val users = response.optJSONArray(if (follows) "follow" else "followeds").objects()
            OnlineDetail(if (follows) "关注" else "粉丝", links = users.mapNotNull {
                val uid = it.optLong("userId").takeIf { value -> value > 0 } ?: return@mapNotNull null
                DetailLink(it.optString("nickname"), "profile/$uid", cover(it, "avatarUrl"))
            }.distinctBy(DetailLink::route))
        }
        "song" -> {
            val song = runCatching {
                Catalog.songs(Catalog.request("/api/song/detail", "ids" to "[$id]").optJSONArray("songs")).firstOrNull()
            }.getOrNull() ?: now?.takeIf { it.id == id.toString() } ?: throw IOException()
            val links = buildList {
                addAll(song.creditsOrLegacy().map(::artistLink))
                if (song.albumId > 0) add(DetailLink("专辑 · ${song.album}", "album/${song.albumId}"))
                add(DetailLink("评论", "comments/R_SO_4_/$id"))
            }
            OnlineDetail(song.title, song.artist, song.cover, links = links, download = song)
        }
        else -> throw IOException()
    }
}

private fun artistLink(artist: SongArtist) = DetailLink("歌手 · ${artist.name}", "artist/${artist.id}")

private fun Song.creditsOrLegacy(): List<SongArtist> = artists.ifEmpty {
    if (artistId > 0L) listOf(SongArtist(artistId, artist.substringBefore(" / ").ifBlank { "歌手" })) else emptyList()
}

@Composable
internal fun SongArtistsPage(songId: Long, now: Song?, onBack: () -> Unit, onOpenArtist: (Long) -> Unit) {
    val matchingSong = now?.takeIf { it.id == songId.toString() }
    val request = rememberRequest(songId) {
        matchingSong?.artists?.takeIf { it.isNotEmpty() } ?: try {
            Catalog.songCredits(songId).ifEmpty { matchingSong?.creditsOrLegacy().orEmpty() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            matchingSong?.creditsOrLegacy().orEmpty().ifEmpty { throw failure }
        }
    }
    Column(Modifier.fillMaxSize()) {
        PageHeading("查看歌手", onBack)
        RequestContent(request) { artists ->
            if (artists.isEmpty()) {
                Text("这首歌暂无可查看的歌手", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(artists, key = SongArtist::id) { artist ->
                        OutlinedCard(onClick = { onOpenArtist(artist.id) }, modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.large) {
                            Row(
                                Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Icon(Icons.Rounded.Person, null, tint = MaterialTheme.colorScheme.primary)
                                Text(artist.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun DailyHistoryPage(
    account: Account?, date: String?, ready: Boolean, selected: String?, onBack: () -> Unit,
    onDate: (String) -> Unit, onChoose: (List<Song>, Int) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        PageHeading(date ?: "历史推荐", onBack)
        if (account == null) {
            Text("登录后可查看历史推荐", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (date == null) {
            val dates = rememberRequest(account.id) {
                val values = Catalog.weapi("/weapi/discovery/recommend/songs/history/recent").getJSONObject("data")
                    .getJSONArray("dates")
                (0 until values.length()).mapNotNull { index ->
                    runCatching { LocalDate.parse(values.getString(index)) }.getOrNull()
                }.distinct().sortedDescending()
            }
            RequestContent(dates) { days ->
                if (days.isEmpty()) {
                    Text("暂无可查看的历史推荐", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(days, key = { it.toString() }) { day ->
                            OutlinedCard(onClick = { onDate(day.toString()) }, modifier = Modifier.fillMaxWidth()) {
                                Text(day.toString(), Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                }
            }
        } else {
            val tracks = rememberRequest(account.id to date) {
                val body = JSONObject().put("date", LocalDate.parse(date).toString())
                Catalog.songs(Catalog.weapi("/weapi/discovery/recommend/songs/history/detail", body)
                    .getJSONObject("data").optJSONArray("songs"))
            }
            RequestContent(tracks) { songs ->
                Songs(songs, selected, ready, false, null, "当天暂无推荐曲目") { onChoose(songs, it) }
            }
        }
    }
}

@Composable
internal fun ListeningPage(account: Account?, onBack: () -> Unit, onLogin: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        PageHeading("听歌数据", onBack)
        if (account == null) {
            FilledTonalButton(onClick = onLogin, modifier = Modifier.padding(horizontal = 16.dp)) { Text("登录后查看") }
        } else {
            val stats = rememberRequest(account.id) { Catalog.listeningDuration() }
            RequestContent(stats) { seconds ->
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = MaterialTheme.shapes.large) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("累计收听", style = MaterialTheme.typography.titleMedium)
                        Text("${seconds / 3_600} 小时 ${seconds / 60 % 60} 分钟", style = MaterialTheme.typography.headlineMedium)
                    }
                }
            }
        }
    }
}

private data class RequestState<T>(val value: T?, val failed: Boolean, val retry: () -> Unit)

@Composable
private fun <T : Any> rememberRequest(key: Any, load: () -> T): RequestState<T> {
    var value by remember(key) { mutableStateOf<T?>(null) }
    var failed by remember(key) { mutableStateOf(false) }
    var attempt by remember(key) { mutableIntStateOf(0) }
    val request by rememberUpdatedState(load)
    LaunchedEffect(key, attempt) {
        failed = false
        try {
            value = withContext(Dispatchers.IO) { request() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        }
    }
    return RequestState(value, failed) { attempt++ }
}

@Composable
private fun <T : Any> RequestContent(result: RequestState<T>, content: @Composable (T) -> Unit) {
    val value = result.value
    when {
        value != null -> content(value)
        result.failed -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("暂时无法加载此页面", color = MaterialTheme.colorScheme.error)
            FilledTonalButton(onClick = result.retry) { Text("重试") }
        }
        else -> LinearWavyProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
    }
}

private fun JSONArray?.objects() = (0 until (this?.length() ?: 0)).mapNotNull { this?.optJSONObject(it) }
private fun JSONObject.text(key: String) = if (isNull(key)) "" else optString(key)
private fun cover(data: JSONObject, key: String) = Catalog.httpsUri(data.text(key))?.toString().orEmpty()
