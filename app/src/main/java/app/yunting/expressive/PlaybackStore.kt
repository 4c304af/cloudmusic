package app.yunting.expressive

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import org.json.JSONArray
import org.json.JSONObject

internal class PlaybackStore(context: Context) {
    private val prefs = context.getSharedPreferences("playback", Context.MODE_PRIVATE)

    fun restore(player: Player) {
        val items = readSongs("queue").map(Song::mediaItem)
        if (items.isEmpty()) return
        player.setMediaItems(items, prefs.getInt("index", 0).coerceIn(items.indices), prefs.getLong("position", 0))
        player.repeatMode = prefs.getInt("repeat", Player.REPEAT_MODE_ALL)
        player.shuffleModeEnabled = prefs.getBoolean("shuffle", false)
    }

    fun save(player: Player, queueChanged: Boolean = false) {
        val edit = prefs.edit().putInt("index", player.currentMediaItemIndex)
            .putLong("position", player.currentPosition.coerceAtLeast(0))
            .putInt("repeat", player.repeatMode).putBoolean("shuffle", player.shuffleModeEnabled)
        if (queueChanged) {
            val songs = List(player.mediaItemCount) { player.getMediaItemAt(it).toSong() }
            edit.putString("queue", encode(songs))
        }
        edit.apply()
    }

    fun record(item: MediaItem) {
        val recent = readSongs("recent")
        val track = item.toSong()
        val previous = recent.find { it.id == track.id }
        val song = if (track.artists.isEmpty() && previous != null) track.copy(artists = previous.artists) else track
        if (recent.firstOrNull()?.let { it.id == song.id && it.artists == song.artists } == true) return
        prefs.edit().putString("recent", encode((listOf(song) + recent.filterNot { it.id == song.id }).take(50))).apply()
    }

    fun recent(): List<Song> = readSongs("recent")

    private fun readSongs(key: String): List<Song> = runCatching {
        val rows = JSONArray(prefs.getString(key, "[]"))
        List(rows.length()) { index ->
            val json = rows.getJSONObject(index)
            Song(
                id = json.getString("id"), title = json.getString("title"), artist = json.getString("artist"),
                album = json.optString("album"), cover = json.optString("cover"),
                localUri = json.optString("local").takeIf(String::isNotBlank),
                artistId = json.optLong("artistId"), albumId = json.optLong("albumId"),
                artists = parseSongArtists(json.optJSONArray("artists")),
            )
        }
    }.getOrDefault(emptyList())

    private fun encode(songs: List<Song>): String = JSONArray().apply {
        songs.forEach {
            val artists = JSONArray().apply {
                it.artists.forEach { artist -> put(JSONObject().put("id", artist.id).put("name", artist.name)) }
            }
            put(JSONObject().put("id", it.id).put("title", it.title).put("artist", it.artist)
                .put("album", it.album).put("cover", it.cover).put("local", it.localUri.orEmpty())
                .put("artistId", it.artistId).put("albumId", it.albumId).put("artists", artists))
        }
    }.toString()
}

internal fun MediaItem.toSong(): Song {
    val extras = mediaMetadata.extras
    val artistIds = extras?.getLongArray("artistIds") ?: longArrayOf()
    val artistNames = extras?.getStringArray("artistNames").orEmpty()
    val artists = artistIds.indices.mapNotNull { index ->
        val id = artistIds[index].takeIf { it > 0 } ?: return@mapNotNull null
        val name = artistNames.getOrNull(index)?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        SongArtist(id, name)
    }.distinctBy(SongArtist::id)
    return Song(
        id = mediaId,
        title = mediaMetadata.title?.toString().orEmpty(),
        artist = mediaMetadata.artist?.toString().orEmpty(),
        album = mediaMetadata.albumTitle?.toString().orEmpty(),
        cover = mediaMetadata.artworkUri?.toString().orEmpty(),
        localUri = mediaId.takeIf { it.startsWith("content://") },
        artistId = extras?.getLong("artistId") ?: 0,
        albumId = extras?.getLong("albumId") ?: 0,
        artists = artists,
    )
}
