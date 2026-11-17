package app.yunting.expressive

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.CookieManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private const val SEARCH_HISTORY_KEY = "searchHistory"
private const val SEARCH_HISTORY_LIMIT = 20

internal data class PlayerState(
    val ready: Boolean = false,
    val now: Song? = null,
    val queue: List<Song> = emptyList(),
    val queueIndex: Int = -1,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val duration: Long = 0,
    val mode: Int = 0,
    val lyrics: List<LyricLine> = emptyList(),
    val lyricDelay: Int = 0,
    val library: List<Song> = emptyList(),
    val recent: List<Song> = emptyList(),
    val discovery: List<Song> = emptyList(),
    val featuredPicks: List<FeaturedPick> = emptyList(),
    val loadingFeatured: Boolean = false,
    val featuredError: Boolean = false,
    val account: Account? = null,
    val userPlaylists: List<Playlist> = emptyList(),
    val loadingUserPlaylists: Boolean = false,
    val userPlaylistsError: String? = null,
    val playlist: Playlist? = null,
    val playlistSongs: List<Song> = emptyList(),
    val loadingPlaylist: Boolean = false,
    val playlistError: String? = null,
    val searchQuery: String? = null,
    val searchResults: Map<SearchKind, SearchResultPage> = emptyMap(),
    val searchHistory: List<String> = emptyList(),
    val discovering: Boolean = false,
    val discoveryError: String? = null,
    val playbackError: String? = null,
)

internal class PlayerModel(private val app: Application) : AndroidViewModel(app), Player.Listener {
    private val preferences = app.getSharedPreferences("library", 0)
    private val store = PlaybackStore(app)
    val state = MutableStateFlow(PlayerState(library = loadLibrary(), recent = store.recent(),
        lyricDelay = preferences.getInt("lyricDelay", 0), searchHistory = loadSearchHistory()))
    private val connection = MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync()
    private var controller: MediaController? = null
    private val searchJobs = mutableMapOf<SearchKind, Job>()
    private var lyricJob: Job? = null
    private var accountJob: Job? = null
    private var featuredJob: Job? = null
    private var userPlaylistsJob: Job? = null
    private var playlistJob: Job? = null
    private var mediaSelectionVersion = 0L

    init {
        connection.addListener({
            try {
                controller = connection.get().also { it.addListener(this) }
                state.update { it.copy(ready = true) }
                sync()
            } catch (_: Exception) {
                state.update { it.copy(playbackError = "无法连接播放器") }
            }
        }, ContextCompat.getMainExecutor(app))
        refreshAccount()
    }

    fun refreshAccount() {
        accountJob?.cancel()
        featuredJob?.cancel()
        userPlaylistsJob?.cancel()
        playlistJob?.cancel()
        state.update { it.copy(discovering = true, discoveryError = null, loadingFeatured = true, featuredError = false) }
        accountJob = viewModelScope.launch {
            val account = withContext(Dispatchers.IO) { runCatching { Catalog.account() }.getOrNull() }
            state.update { it.copy(account = account, discovering = false, discovery = emptyList(),
                featuredPicks = emptyList(), loadingFeatured = account != null, featuredError = false,
                userPlaylists = emptyList(), loadingUserPlaylists = account != null, userPlaylistsError = null,
                playlist = null, playlistSongs = emptyList(),
                loadingPlaylist = false, playlistError = null) }
            if (account != null) {
                discover()
                loadFeatured()
                refreshUserPlaylists()
            }
        }
    }

    fun refreshUserPlaylists() {
        val accountId = state.value.account?.id ?: return
        userPlaylistsJob?.cancel()
        state.update { it.copy(loadingUserPlaylists = true, userPlaylistsError = null) }
        userPlaylistsJob = viewModelScope.launch {
            val entries = withContext(Dispatchers.IO) { runCatching { Catalog.userPlaylists(accountId) } }
            if (state.value.account?.id != accountId) return@launch
            state.update {
                it.copy(userPlaylists = entries.getOrElse { _ -> it.userPlaylists }, loadingUserPlaylists = false,
                    userPlaylistsError = if (entries.isFailure) "暂时无法加载账号歌单" else null)
            }
            entries.getOrNull()?.let { lists ->
                val selectedId = state.value.playlist?.id ?: return@let
                val updated = lists.find { it.id == selectedId }
                if (updated == null) closePlaylist() else openPlaylist(updated)
            }
        }
    }

    fun logout() {
        accountJob?.cancel()
        featuredJob?.cancel()
        state.update { it.copy(featuredPicks = emptyList(), loadingFeatured = true, featuredError = false) }
        CookieManager.getInstance().removeAllCookies {
            CookieManager.getInstance().flush()
            refreshAccount()
        }
    }

    fun loadFeatured() {
        val accountId = state.value.account?.id ?: return
        featuredJob?.cancel()
        state.update { it.copy(loadingFeatured = true, featuredError = false) }
        featuredJob = viewModelScope.launch {
            val picks = withContext(Dispatchers.IO) { runCatching { HomeCatalog.featuredPicks() } }
            state.update {
                if (it.account?.id != accountId) it else it.copy(
                    featuredPicks = picks.getOrElse { _ -> it.featuredPicks },
                    loadingFeatured = false,
                    featuredError = picks.isFailure,
                )
            }
        }
    }

    fun openPlaylist(playlist: Playlist) {
        playlistJob?.cancel()
        state.update { it.copy(playlist = playlist, loadingPlaylist = true, playlistSongs = emptyList(), playlistError = null) }
        playlistJob = viewModelScope.launch {
            val tracks = withContext(Dispatchers.IO) { runCatching { Catalog.playlistTracks(playlist.id) } }
            state.update { it.copy(loadingPlaylist = false, playlistSongs = tracks.getOrDefault(emptyList()),
                playlistError = if (tracks.isFailure) "暂时无法加载歌单歌曲" else null) }
        }
    }

    fun closePlaylist() {
        playlistJob?.cancel()
        state.update { it.copy(playlist = null, loadingPlaylist = false, playlistSongs = emptyList(), playlistError = null) }
    }

    fun discover() {
        if (state.value.account == null) return
        if (state.value.discovering) return
        state.update { it.copy(discovering = true, discoveryError = null) }
        accountJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { Catalog.dailyRecommendations() } }
            state.update {
                it.copy(discovering = false, discovery = result.getOrDefault(emptyList()),
                    discoveryError = if (result.isFailure) "暂时无法连接网易云音乐" else null)
            }
        }
    }

    fun search(query: String, kind: SearchKind, force: Boolean = false, recordHistory: Boolean = false) {
        val term = query.trim()
        if (term.isBlank()) return
        if (recordHistory) {
            val previous = state.value.searchHistory.filterNot { it.equals(term, ignoreCase = true) }
            saveSearchHistory((listOf(term) + previous).take(SEARCH_HISTORY_LIMIT))
        }

        if (term != state.value.searchQuery) {
            searchJobs.values.forEach { it.cancel() }
            searchJobs.clear()
            state.update { it.copy(searchQuery = term, searchResults = emptyMap()) }
        }
        if (!force && kind in state.value.searchResults) return

        searchJobs.remove(kind)?.cancel()
        state.update { it.copy(searchResults = it.searchResults + (kind to SearchResultPage(loading = true))) }
        searchJobs[kind] = viewModelScope.launch {
            val page = withContext(Dispatchers.IO) {
                try {
                    Catalog.search(term, kind)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    SearchResultPage(error = "搜索失败，请稍后重试")
                }
            }
            state.update { if (it.searchQuery == term) it.copy(searchResults = it.searchResults + (kind to page)) else it }
        }
    }

    fun removeSearchHistory(query: String) {
        saveSearchHistory(state.value.searchHistory.filterNot { it.equals(query, ignoreCase = true) })
    }

    fun clearSearchHistory() = saveSearchHistory(emptyList())

    private fun loadSearchHistory(): List<String> = runCatching {
        val data = JSONArray(preferences.getString(SEARCH_HISTORY_KEY, "[]"))
        (0 until data.length()).asSequence().mapNotNull { (data.opt(it) as? String)?.trim() }
            .filter(String::isNotBlank).distinctBy { it.lowercase() }.take(SEARCH_HISTORY_LIMIT).toList()
    }.getOrDefault(emptyList())

    private fun saveSearchHistory(history: List<String>) {
        if (history == state.value.searchHistory) return
        preferences.edit().putString(SEARCH_HISTORY_KEY, JSONArray(history).toString()).apply()
        state.update { it.copy(searchHistory = history) }
    }

    fun play(songs: List<Song>, index: Int) {
        val player = controller ?: return
        if (index !in songs.indices) return
        state.update { it.copy(playbackError = null) }
        player.setMediaItems(songs.map(Song::mediaItem), index, C.TIME_UNSET)
        player.prepare()
        player.play()
    }

    suspend fun insertSimilarNext(seedId: Long): String = withSimilarSongs(seedId) { player, songs ->
        val queuedIds = (0 until player.mediaItemCount).mapTo(mutableSetOf()) { player.getMediaItemAt(it).mediaId }
        val next = songs.firstOrNull { it.id !in queuedIds } ?: return@withSimilarSongs "相似歌曲已在播放队列中"
        player.addMediaItems(player.currentMediaItemIndex + 1, listOf(next.mediaItem()))
        "已将《${next.title}》插入当前歌曲之后"
    }

    suspend fun startSimilarRoaming(seedId: Long): String = withSimilarSongs(seedId) { player, songs ->
        val seed = player.currentMediaItem ?: return@withSimilarSongs "歌曲已切换，请重试"
        val position = player.currentPosition.coerceAtLeast(0L)
        state.update { it.copy(playbackError = null) }
        player.shuffleModeEnabled = false
        player.repeatMode = Player.REPEAT_MODE_ALL
        player.setMediaItems(listOf(seed) + songs.map(Song::mediaItem), 0, position)
        player.prepare()
        player.play()
        "已开始相似歌曲漫游，共 ${songs.size + 1} 首"
    }

    private suspend fun withSimilarSongs(
        seedId: Long,
        updateQueue: (MediaController, List<Song>) -> String,
    ): String {
        val player = controller ?: return "播放器尚未连接"
        if (player.currentMediaItem?.mediaId != seedId.toString()) return "歌曲已切换，请重试"
        if (!player.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS)) return "暂时无法修改播放队列"
        val seedVersion = mediaSelectionVersion
        val songs = try {
            withContext(Dispatchers.IO) { Catalog.similarSongs(seedId) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return "暂时无法加载相似歌曲"
        }
        if (controller !== player || mediaSelectionVersion != seedVersion || player.currentMediaItem?.mediaId != seedId.toString()) {
            return "歌曲已切换，请重试"
        }
        if (songs.isEmpty()) return "暂无相似歌曲"
        return updateQueue(player, songs)
    }

    fun select(index: Int) {
        controller?.let {
            if (index !in 0 until it.mediaItemCount) return
            state.update { value -> value.copy(playbackError = null) }
            it.seekToDefaultPosition(index)
            if (it.playbackState == Player.STATE_IDLE) it.prepare()
            it.play()
        }
    }

    fun toggle() {
        controller?.let {
            if (it.playWhenReady) it.pause() else {
                state.update { value -> value.copy(playbackError = null) }
                if (it.playbackState == Player.STATE_IDLE) it.prepare()
                it.play()
            }
        }
    }

    fun next() { controller?.seekToNextMediaItem() }
    fun previous() { controller?.seekToPreviousMediaItem() }
    fun seek(position: Long) { controller?.seekTo(position.coerceIn(0, state.value.duration)) }
    fun position(): Long = controller?.currentPosition?.coerceAtLeast(0) ?: 0

    fun cycleMode() {
        val player = controller ?: return
        when (state.value.mode) {
            0 -> { player.repeatMode = Player.REPEAT_MODE_ALL; player.shuffleModeEnabled = true }
            1 -> { player.shuffleModeEnabled = false; player.repeatMode = Player.REPEAT_MODE_ONE }
            else -> player.repeatMode = Player.REPEAT_MODE_ALL
        }
    }

    fun setLyricDelay(ms: Int) {
        val value = ms.coerceIn(-10_000, 10_000)
        preferences.edit().putInt("lyricDelay", value).apply()
        state.update { it.copy(lyricDelay = value) }
    }

    fun importAudio(uris: List<Uri>) = viewModelScope.launch {
        val added = withContext(Dispatchers.IO) {
            uris.mapNotNull { uri ->
                runCatching {
                    app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    val title = app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { if (it.moveToFirst()) it.getString(0) else null }
                        ?.substringBeforeLast('.') ?: "本地歌曲"
                    Song(uri.toString(), title, "本地音乐", localUri = uri.toString())
                }.getOrNull()
            }
        }
        val library = (state.value.library + added).distinctBy(Song::id)
        val json = JSONArray()
        library.forEach { json.put(JSONObject().put("uri", it.localUri).put("title", it.title)) }
        preferences.edit().putString("audio", json.toString()).apply()
        state.update { it.copy(library = library, playbackError = if (added.isEmpty() && uris.isNotEmpty()) "未能导入所选文件" else it.playbackError) }
    }

    override fun onEvents(player: Player, events: Player.Events) {
        if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) mediaSelectionVersion++
        sync()
    }

    override fun onPlayerError(error: PlaybackException) {
        state.update { it.copy(playbackError = "无法播放这首歌，可切歌或重试") }
    }

    private fun sync() {
        val player = controller ?: return
        val previous = state.value
        val queue = List(player.mediaItemCount) { player.getMediaItemAt(it).toSong() }
        val current = queue.getOrNull(player.currentMediaItemIndex)
        val songChanged = previous.now?.id != current?.id
        state.update {
            it.copy(now = current, queue = queue, recent = store.recent(), queueIndex = player.currentMediaItemIndex,
                playing = player.isPlaying, buffering = player.playbackState == Player.STATE_BUFFERING,
                duration = player.duration.coerceAtLeast(0),
                lyrics = if (songChanged) emptyList() else it.lyrics,
                mode = if (player.shuffleModeEnabled) 1 else if (player.repeatMode == Player.REPEAT_MODE_ONE) 2 else 0)
        }
        if (!songChanged) return
        lyricJob?.cancel()
        if (current == null) return
        lyricJob = viewModelScope.launch {
            val lyrics = withContext(Dispatchers.IO) { runCatching { Catalog.lyrics(current.id) }.getOrDefault(emptyList()) }
            state.update { if (it.now?.id == current.id) it.copy(lyrics = lyrics) else it }
        }
    }

    private fun loadLibrary(): List<Song> = runCatching {
        val data = JSONArray(preferences.getString("audio", "[]"))
        List(data.length()) { index ->
            val entry = data.getJSONObject(index)
            val uri = entry.getString("uri")
            Song(uri, entry.getString("title"), "本地音乐", localUri = uri)
        }
    }.getOrDefault(emptyList())

    override fun onCleared() {
        controller?.removeListener(this)
        MediaController.releaseFuture(connection)
    }
}
