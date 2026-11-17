package app.yunting.expressive

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException

internal data class CuratedCategory(val title: String, val cover: String)

internal data class CuratedPlaylistPage(val playlists: List<Playlist>, val nextCursor: Long?)

internal object CuratedCatalog {
    suspend fun categories(): List<CuratedCategory> = withContext(Dispatchers.IO) {
        coroutineScope {
            val samples = async {
                try {
                    requestPlaylists("全部", 0, 50)
                } catch (_: IOException) {
                    emptyList()
                } catch (_: org.json.JSONException) {
                    emptyList()
                }
            }
            val tags = Catalog.request("/api/playlist/highquality/tags").getJSONArray("tags")
            val covers = mutableMapOf<String, String>()
            for (item in samples.await()) {
                val image = cover(item)
                if (image.isBlank()) continue
                val itemTags = item.optJSONArray("tags") ?: continue
                for (index in 0 until itemTags.length()) {
                    val name = itemTags.optString(index)
                    if (name.isNotBlank()) covers.putIfAbsent(name, image)
                }
            }
            (0 until tags.length()).mapNotNull { index ->
                val title = tags.optJSONObject(index)?.optString("name").orEmpty()
                if (title.isBlank() || title == "全部") null else CuratedCategory(title, covers[title].orEmpty())
            }.distinctBy(CuratedCategory::title)
        }
    }

    suspend fun playlists(category: String, cursor: Long): CuratedPlaylistPage = withContext(Dispatchers.IO) {
        val response = listResponse(category, cursor, 24)
        val playlists = parseList(response).mapNotNull { item ->
            val id = item.optLong("id")
            val title = item.optString("name")
            if (id <= 0 || title.isBlank()) null else Playlist(
                id = id, title = title, size = item.optInt("trackCount"), cover = cover(item),
            )
        }.distinctBy(Playlist::id)
        val lastTime = response.optLong("lasttime")
        val nextCursor = lastTime.takeIf { response.optBoolean("more") && it > 0 && it != cursor && playlists.isNotEmpty() }
        CuratedPlaylistPage(playlists, nextCursor)
    }

    private fun requestPlaylists(category: String, cursor: Long, limit: Int) =
        parseList(listResponse(category, cursor, limit))

    private fun listResponse(category: String, cursor: Long, limit: Int) = Catalog.request(
        "/api/playlist/highquality/list", "cat" to category, "lasttime" to cursor.toString(), "limit" to limit.toString(),
    )

    private fun parseList(response: JSONObject): List<JSONObject> {
        val rows = response.getJSONArray("playlists")
        return (0 until rows.length()).mapNotNull(rows::optJSONObject)
    }

    private fun cover(item: JSONObject): String {
        val uri = Uri.parse(item.optString("coverImgUrl"))
        val scheme = uri.scheme
        if (uri.host.isNullOrBlank() || (scheme != "https" && scheme != "http")) return ""
        return uri.buildUpon().scheme("https").appendQueryParameter("param", "400y400").build().toString()
    }
}
