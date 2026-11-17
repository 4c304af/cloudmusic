package app.yunting.expressive

import android.content.ContentResolver
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal class LyricBridge(scope: CoroutineScope, private val resolver: ContentResolver) {
    private val endpoint = Uri.parse("content://io.github.proify.lyricon.cloudbridge")
    private val sessionId = UUID.randomUUID().toString()
    private data class Update(val song: Song, val lines: List<LyricLine>, val delay: Int, val duration: Long,
        val position: Long, val playing: Boolean)
    private data class SongVersion(val song: Song, val lines: List<LyricLine>, val delay: Int, val duration: Long)
    private val updates = Channel<Update?>(Channel.CONFLATED)

    init {
        scope.launch(Dispatchers.IO) {
            var sent: SongVersion? = null
            for (update in updates) {
                if (update == null && sent == null) continue
                val version = update?.let { SongVersion(it.song, it.lines, it.delay, it.duration) }
                val extras = Bundle().apply {
                    putString("session", sessionId)
                    putLong("stamp", SystemClock.elapsedRealtimeNanos())
                    if (update != null && version != null) {
                        putInt("version", 1)
                        putLong("position", update.position)
                        putBoolean("playing", update.playing)
                        if (sent != version) putString("song", encode(version))
                    }
                }
                val accepted = runCatching {
                    resolver.call(endpoint, if (update == null) "clear" else "update", null, extras)
                        ?.getBoolean("accepted") == true
                }.getOrDefault(false)
                sent = version.takeIf { accepted }
            }
        }
    }

    fun publish(song: Song?, lines: List<LyricLine>, delay: Int, duration: Long, position: Long, playing: Boolean) {
        updates.trySend(song?.let { Update(it, lines, delay, duration.coerceAtLeast(0), position.coerceAtLeast(0), playing) })
    }

    private fun encode(version: SongVersion): String {
        val base = JSONObject().put("id", version.song.id)
            .put("name", version.song.title.take(512)).put("artist", version.song.artist.take(512))
            .put("duration", version.duration)
        val rows = JSONArray()
        version.lines.take(2000).forEachIndexed { index, line ->
            val begin = (line.timeMs + version.delay).coerceAtLeast(0)
            val end = version.lines.getOrNull(index + 1)?.let { it.timeMs + version.delay }
                ?: maxOf(version.duration, begin + 5_000)
            if (end > begin) rows.put(JSONObject().put("begin", begin).put("end", end)
                .put("text", line.text.take(500)).put("translation", line.translation.take(500)))
        }
        val packet = base.put("lyrics", rows).toString()
        return if (packet.toByteArray(Charsets.UTF_8).size <= 200_000) packet
            else base.put("lyrics", JSONArray()).toString()
    }
}
