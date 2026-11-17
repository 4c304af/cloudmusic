package app.yunting.expressive

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken

abstract class YuntingWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        withPlayer(context, pending) { }
    }
}

class NowPlayingWidget : YuntingWidget()
class CurrentLyricsWidget : YuntingWidget()
class RecentPlaybackWidget : YuntingWidget()

class WidgetControls : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in setOf("previous", "toggle", "next", "mode")) return
        val pending = goAsync()
        withPlayer(context, pending) { player ->
            when (action) {
                "previous" -> player.seekToPrevious()
                "toggle" -> if (player.playWhenReady) player.pause() else if (player.mediaItemCount > 0) {
                    if (player.playbackState == Player.STATE_IDLE) player.prepare()
                    player.play()
                }
                "next" -> player.seekToNextMediaItem()
                "mode" -> when {
                    player.shuffleModeEnabled -> { player.shuffleModeEnabled = false; player.repeatMode = Player.REPEAT_MODE_ONE }
                    player.repeatMode == Player.REPEAT_MODE_ONE -> player.repeatMode = Player.REPEAT_MODE_ALL
                    else -> player.shuffleModeEnabled = true
                }
            }
        }
    }
}

private fun withPlayer(context: Context, pending: BroadcastReceiver.PendingResult, action: (Player) -> Unit) {
    val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
    val future = MediaController.Builder(context, token).buildAsync()
    val handler = android.os.Handler(android.os.Looper.getMainLooper())
    var finished = false
    fun finish() {
        if (finished) return
        finished = true
        MediaController.releaseFuture(future)
        pending.finish()
    }
    val timeout = Runnable { finish() }
    handler.postDelayed(timeout, 6_000)
    future.addListener({
        if (!finished) {
            try { action(future.get()) } catch (e: Exception) {
                android.util.Log.w("YuntingWidget", "Could not connect to playback", e)
            } finally {
                handler.removeCallbacks(timeout)
                finish()
            }
        }
    }, ContextCompat.getMainExecutor(context))
}

private val widgetLayouts = mapOf(
    NowPlayingWidget::class.java to R.layout.now_playing_widget,
    CurrentLyricsWidget::class.java to R.layout.current_lyrics_widget,
    RecentPlaybackWidget::class.java to R.layout.recent_playback_widget,
)

internal fun hasWidgets(context: Context): Boolean = widgetLayouts.keys.any {
    AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, it)).isNotEmpty()
}

internal fun renderWidget(
    context: Context, player: Player, lyrics: List<LyricLine>, lyricDelay: Int,
    cover: Bitmap? = null, recent: List<Song> = emptyList(),
) {
    val manager = AppWidgetManager.getInstance(context)
    val line = lyrics.lastOrNull { it.timeMs + lyricDelay <= player.currentPosition }
    val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    for ((provider, layout) in widgetLayouts) {
        val ids = manager.getAppWidgetIds(ComponentName(context, provider))
        if (ids.isEmpty()) continue
        val views = RemoteViews(context.packageName, layout).apply {
            setOnClickPendingIntent(R.id.widget_card, PendingIntent.getActivity(context, layout,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .setAction(if (provider == RecentPlaybackWidget::class.java) "app.yunting.RECENT" else "app.yunting.PLAYER"), flags))
            when (provider) {
                NowPlayingWidget::class.java -> bindPlayback(context, player, line, cover, flags)
                CurrentLyricsWidget::class.java -> {
                    setTextViewText(R.id.widget_title, player.mediaMetadata.title ?: "云听")
                    setTextViewText(R.id.widget_lyric, line?.text ?: "暂无歌词")
                    setTextViewText(R.id.widget_translation, line?.translation.orEmpty())
                }
                else -> {
                    val rows = listOf(R.id.widget_recent_first, R.id.widget_recent_second, R.id.widget_recent_third)
                    for ((index, id) in rows.withIndex()) {
                        setTextViewText(id, recent.getOrNull(index)?.title ?: if (index == 0) "暂无记录" else "")
                    }
                }
            }
        }
        manager.updateAppWidget(ids, views)
    }
}

private fun RemoteViews.bindPlayback(context: Context, player: Player, line: LyricLine?, cover: Bitmap?, flags: Int) {
        val fraction = if (player.duration <= 0) 0 else (player.currentPosition.toDouble() / player.duration * 1000).toInt().coerceIn(0, 1000)
        setTextViewText(R.id.widget_title, player.mediaMetadata.title ?: "云听")
        setTextViewText(R.id.widget_artist, player.mediaMetadata.artist?.toString().orEmpty())
        if (cover == null) setImageViewResource(R.id.widget_cover, R.drawable.yunting_icon)
        else setImageViewBitmap(R.id.widget_cover, cover)
        setTextViewText(R.id.widget_lyric, line?.text.orEmpty())
        setTextViewText(R.id.widget_translation, line?.translation.orEmpty())
        setTextViewText(R.id.widget_toggle, if (player.isPlaying) "Ⅱ" else "▶")
        setContentDescription(R.id.widget_toggle, if (player.isPlaying) "暂停" else "播放")
        setTextViewText(R.id.widget_mode, when {
            player.shuffleModeEnabled -> "随机"
            player.repeatMode == Player.REPEAT_MODE_ONE -> "单曲"
            else -> "循环"
        })
        setProgressBar(R.id.widget_progress, 1000, fraction, false)
        for ((id, action) in listOf(R.id.widget_previous to "previous", R.id.widget_toggle to "toggle",
            R.id.widget_next to "next", R.id.widget_mode to "mode")) {
            setOnClickPendingIntent(id, PendingIntent.getBroadcast(context, 0,
                Intent(context, WidgetControls::class.java).setAction(action), flags))
        }
}
