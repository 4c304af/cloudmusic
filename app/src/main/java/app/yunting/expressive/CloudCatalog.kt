package app.yunting.expressive

import org.json.JSONObject
import org.json.JSONArray

internal data class CloudTrack(val storageId: Long, val song: Song)

internal data class CloudBatch(
    val tracks: List<CloudTrack>,
    val total: Int,
    val usedBytes: Long,
    val capacityBytes: Long,
    val nextOffset: Int?,
)

internal object CloudCatalog {
    fun remove(storageId: Long) {
        Catalog.weapi("/weapi/cloud/del", JSONObject().put("songIds", JSONArray().put(storageId)))
    }

    fun match(accountId: Long, storageId: Long, catalogId: Long) {
        Catalog.weapi(
            "/weapi/cloud/user/song/match",
            JSONObject().put("userId", accountId).put("songId", storageId).put("adjustSongId", catalogId),
        )
    }

    fun tracks(offset: Int): CloudBatch {
        val payload = Catalog.weapi("/weapi/v1/cloud/get", JSONObject().put("offset", offset).put("limit", 50))
        val entries = payload.optJSONArray("data")
        val entryCount = entries?.length() ?: 0
        val end = offset + entryCount
        val total = payload.optInt("count", end).coerceAtLeast(end)
        return CloudBatch(
            tracks = (0 until entryCount).mapNotNull { entries?.optJSONObject(it)?.cloudTrack() },
            total = total,
            usedBytes = payload.optLong("size").coerceAtLeast(0),
            capacityBytes = payload.optLong("maxSize").coerceAtLeast(0),
            nextOffset = end.takeIf { entryCount > 0 && payload.optBoolean("hasMore", end < total) },
        )
    }

    private fun JSONObject.cloudTrack(): CloudTrack? {
        val id = optLong("songId").takeIf { it > 0 } ?: return null
        val matched = optJSONObject("simpleSong").takeUnless { text("matchType") == "unmatched" }
        val catalogSong = matched?.let { Catalog.songs(org.json.JSONArray().put(it)).firstOrNull() }
        return CloudTrack(
            storageId = id,
            song = Song(
                id = catalogSong?.id ?: id.toString(),
                title = text("songName").ifBlank { catalogSong?.title.orEmpty() }.ifBlank { text("fileName") },
                artist = catalogSong?.artist.orEmpty().ifBlank { text("artist") },
                album = catalogSong?.album.orEmpty().ifBlank { text("album") },
                cover = catalogSong?.cover.orEmpty(),
                artistId = catalogSong?.artistId ?: 0,
                albumId = catalogSong?.albumId ?: 0,
            ),
        )
    }

    private fun JSONObject.text(key: String): String = if (isNull(key)) "" else optString(key).trim()
}
