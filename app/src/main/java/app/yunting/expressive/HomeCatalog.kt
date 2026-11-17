package app.yunting.expressive

import org.json.JSONArray
import org.json.JSONObject

internal data class FeaturedPick(val route: String, val title: String, val cover: String)

internal object HomeCatalog {
    fun featuredPicks(): List<FeaturedPick> {
        val shelf = runCatching { featuredShelf() }.getOrDefault(emptyList())
        if (shelf.isNotEmpty()) return shelf

        // Some accounts do not receive this shelf. Use the account's dedicated playlist recommendations.
        return Catalog.weapi("/weapi/v1/discovery/recommend/resource").optJSONArray("recommend").objects()
            .mapNotNull { playlist ->
                val id = playlist.optLong("id").takeIf { it > 0 } ?: return@mapNotNull null
                val title = playlist.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val image = Catalog.httpsUri(playlist.optString("picUrl"))?.toString().orEmpty()
                FeaturedPick("playlist/$id", title, image)
            }.distinctBy(FeaturedPick::route).take(4).toList()
    }

    private fun featuredShelf(): List<FeaturedPick> {
        var cursor = ""
        repeat(3) {
            val page = Catalog.mobileApi("homepage/block/page", JSONObject().put("cursor", cursor).put("refresh", false))
                .optJSONObject("data") ?: return emptyList()
            val cards = page.optJSONArray("blocks").objects()
                .filter { block ->
                    val ui = block.optJSONObject("uiElement")
                    listOf("mainTitle", "subTitle").any { ui?.optJSONObject(it)?.optString("title")?.trim() == "专属精选" }
                }
                .flatMap { it.optJSONArray("creatives").objects() }
                .flatMap { it.optJSONArray("resources").objects() }
                .mapNotNull(::featuredCard)
                .distinctBy(FeaturedPick::route).take(4).toList()
            if (cards.isNotEmpty()) return cards
            val next = page.optString("cursor").takeUnless { it.isBlank() || it == "null" || it == cursor }
            if (!page.optBoolean("hasMore") || next == null) return emptyList()
            cursor = next
        }
        return emptyList()
    }

    private fun featuredCard(item: JSONObject): FeaturedPick? {
        val ui = item.optJSONObject("uiElement") ?: return null
        val title = ui.optJSONObject("mainTitle")?.optString("title")?.takeIf(String::isNotBlank) ?: return null
        val action = item.optString("action")
        val route = when (item.optString("resourceType").lowercase()) {
            "list", "playlist" -> (actionId(action, "playlist") ?: item.optLong("resourceId").takeIf { it > 0 })
                ?.let { "playlist/$it" }
            "album", "digitalalbum" -> actionId(action, "album")?.let { "album/$it" }
            "song" -> item.optLong("resourceId").takeIf { it > 0 }?.let { "song/$it" }
            else -> null
        } ?: return null
        val cover = Catalog.httpsUri(ui.optJSONObject("image")?.optString("imageUrl").orEmpty())?.toString().orEmpty()
        return FeaturedPick(route, title, cover)
    }

    private fun actionId(action: String, type: String): Long? = action.substringAfter("orpheus://$type/", "")
        .substringBefore('?').substringBefore('/').toLongOrNull()?.takeIf { it > 0 }

    private fun JSONArray?.objects(): Sequence<JSONObject> = (0 until (this?.length() ?: 0)).asSequence()
        .mapNotNull { this?.optJSONObject(it) }
}
