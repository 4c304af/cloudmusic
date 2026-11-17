package app.yunting.expressive

import android.app.PendingIntent
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

internal const val SLEEP_TIMER_DEADLINE_KEY = "sleepTimerDeadline"
internal const val PLAYBACK_QUALITY_KEY = "playbackQuality"

internal fun SharedPreferences.sleepTimerRemaining(): Long =
    (getLong(SLEEP_TIMER_DEADLINE_KEY, 0) - System.currentTimeMillis()).coerceAtLeast(0)

@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private lateinit var store: PlaybackStore
    private lateinit var downloads: DownloadStore
    private lateinit var settings: SharedPreferences
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var lyricBridge: LyricBridge
    private val settingChanged = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "lyricBridge" || key == "lyricDelay") session?.player?.let(::updateWidget)
        if (key == SLEEP_TIMER_DEADLINE_KEY) scheduleSleepTimer()
        if (key == PLAYBACK_QUALITY_KEY) reloadStreamQuality()
    }
    private var widgetLyrics = emptyList<LyricLine>()
    private var lyricMediaId: String? = null
    private var widgetLyricJob: Job? = null
    private var qualityReloadJob: Job? = null
    private var artworkJob: Job? = null
    private var artworkKey: Pair<String, String?>? = null
    private var widgetCover: Bitmap? = null
    private var lastNavigationAt = -NAVIGATION_COOLDOWN_MS
    private val handler = Handler(Looper.getMainLooper())
    private val sleepTimer = Runnable {
        if (settings.sleepTimerRemaining() > 0L) {
            scheduleSleepTimer()
        } else if (settings.contains(SLEEP_TIMER_DEADLINE_KEY)) {
            session?.player?.pause()
            settings.edit().remove(SLEEP_TIMER_DEADLINE_KEY).apply()
        }
    }
    private val updateWidget = object : Runnable {
        override fun run() {
            val player = session?.player ?: return
            updateWidget(player)
            if (player.isPlaying) handler.postDelayed(this, 1_000)
        }
    }
    private val saveProgress = object : Runnable {
        override fun run() {
            val player = session?.player ?: return
            if (!player.isPlaying) return
            store.save(player)
            handler.postDelayed(this, 5_000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        settings = getSharedPreferences("library", MODE_PRIVATE)
        settings.registerOnSharedPreferenceChangeListener(settingChanged)
        lyricBridge = LyricBridge(scope, contentResolver)
        downloads = DownloadStore(this)
        val source = ResolvingDataSource.Factory(DefaultDataSource.Factory(this)) { spec ->
            if (spec.uri.scheme != "yunting") return@Factory spec
            val id = checkNotNull(spec.uri.lastPathSegment)
            val quality = PlaybackQuality.fromLevel(settings.getString(PLAYBACK_QUALITY_KEY, null))
            spec.withUri(downloads.offlineUri(id) ?: Catalog.playbackUri(id, quality = quality))
        }
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(source))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build().apply { repeatMode = Player.REPEAT_MODE_ALL }
        store = PlaybackStore(this)
        store.restore(player)
        scope.launch {
            PlaybackOutput.preferred.collect { player.setPreferredAudioDevice(it) }
        }
        val artworkPlayer = ArtworkPlayer(player)
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                store.save(player, events.contains(Player.EVENT_TIMELINE_CHANGED))
                if (player.isPlaying) player.currentMediaItem?.let(store::record)
                if (events.contains(Player.EVENT_IS_PLAYING_CHANGED)) {
                    handler.removeCallbacks(saveProgress)
                    if (player.isPlaying) handler.postDelayed(saveProgress, 5_000)
                    handler.removeCallbacks(updateWidget)
                    if (player.isPlaying) handler.postDelayed(updateWidget, 1_000)
                }
                if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_MEDIA_METADATA_CHANGED)) {
                    loadArtwork(player, artworkPlayer)
                }
                updateWidget(player)
            }
        })
        val launch = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        session = MediaSession.Builder(this, artworkPlayer).setSessionActivity(launch)
            .setCallback(object : MediaSession.Callback {
                override fun onPlayerCommandRequest(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    playerCommand: Int,
                ): Int {
                    if (playerCommand !in NAVIGATION_COMMANDS || mayNavigate()) return SessionResult.RESULT_SUCCESS
                    return SessionResult.RESULT_ERROR_BAD_VALUE
                }

                override fun onPostConnect(session: MediaSession, controller: MediaSession.ControllerInfo) {
                    if (controller.packageName == packageName) updateWidget(player)
                }

                override fun onAddMediaItems(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    mediaItems: List<MediaItem>,
                ): ListenableFuture<List<MediaItem>> {
                    if (controller.packageName != packageName) return Futures.immediateFuture(emptyList())
                    return Futures.immediateFuture(mediaItems.mapNotNull { item ->
                        val id = item.mediaId
                        val uri = when {
                            id.toLongOrNull() != null -> Uri.parse("yunting://song/$id")
                            id.startsWith("content://") -> Uri.parse(id)
                            else -> return@mapNotNull null
                        }
                        item.buildUpon().setUri(uri).build()
                    })
                }
            }).build()
        loadArtwork(player, artworkPlayer)
        scheduleSleepTimer()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    private fun reloadStreamQuality() {
        qualityReloadJob?.cancel()
        val player = session?.player ?: return
        val song = player.currentMediaItem ?: return
        if (song.mediaId.toLongOrNull() == null) return
        qualityReloadJob = scope.launch {
            val hasOfflineFile = withContext(Dispatchers.IO) { downloads.offlineUri(song.mediaId) != null }
            if (hasOfflineFile || player.currentMediaItem != song) return@launch
            if (player.playbackState !in setOf(Player.STATE_READY, Player.STATE_BUFFERING)) return@launch

            val itemIndex = player.currentMediaItemIndex
            val position = player.currentPosition
            val shouldPlay = player.playWhenReady
            player.stop()
            player.seekTo(itemIndex, position)
            player.playWhenReady = shouldPlay
            player.prepare()
        }
    }

    private fun scheduleSleepTimer() {
        handler.removeCallbacks(sleepTimer)
        if (settings.contains(SLEEP_TIMER_DEADLINE_KEY)) {
            handler.postDelayed(sleepTimer, settings.sleepTimerRemaining())
        }
    }

    private fun mayNavigate(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastNavigationAt < NAVIGATION_COOLDOWN_MS) return false
        lastNavigationAt = now
        return true
    }

    private fun updateWidget(player: Player) {
        val desktop = hasWidgets(this)
        val bridge = settings.getBoolean("lyricBridge", false)
        if (!desktop && !bridge) {
            lyricBridge.publish(null, emptyList(), 0, 0, 0, false)
            return
        }
        val id = player.currentMediaItem?.mediaId
        if (lyricMediaId != id) {
            lyricMediaId = id
            widgetLyrics = emptyList()
            widgetLyricJob?.cancel()
            if (id?.toLongOrNull() != null) {
                widgetLyricJob = scope.launch {
                    val lines = withContext(Dispatchers.IO) { runCatching { Catalog.lyrics(id) }.getOrDefault(emptyList()) }
                    if (lyricMediaId == id) {
                        widgetLyrics = lines
                        updateWidget(player)
                    }
                }
            }
        }
        val delayMs = settings.getInt("lyricDelay", 0)
        if (desktop) renderWidget(this, player, widgetLyrics, delayMs, widgetCover, store.recent().take(3))
        lyricBridge.publish(if (bridge) player.currentMediaItem?.toSong() else null, widgetLyrics, delayMs,
            player.duration, player.currentPosition, player.isPlaying)
    }

    private fun loadArtwork(player: Player, published: ArtworkPlayer) {
        val key = player.currentMediaItem?.let { it.mediaId to player.mediaMetadata.artworkUri?.toString() }
        if (artworkKey == key) return
        artworkKey = key
        widgetCover = null
        artworkJob?.cancel()
        published.publish(key, null)
        val url = key?.second ?: return
        artworkJob = scope.launch {
            val artwork = withContext(Dispatchers.IO) { CoverCache.load(url)?.let(::prepareArtwork) } ?: return@launch
            if (artworkKey != key) return@launch
            widgetCover = artwork.widget
            published.publish(key, artwork.session)
            updateWidget(player)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(saveProgress)
        handler.removeCallbacks(updateWidget)
        handler.removeCallbacks(sleepTimer)
        settings.unregisterOnSharedPreferenceChangeListener(settingChanged)
        scope.cancel()
        session?.let { it.player.release(); it.release() }
        session = null
        super.onDestroy()
    }

    private companion object {
        const val NAVIGATION_COOLDOWN_MS = 280L
        val NAVIGATION_COMMANDS = setOf(
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        )
    }
}
