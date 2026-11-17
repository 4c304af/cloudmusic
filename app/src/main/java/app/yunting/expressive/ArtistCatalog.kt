package app.yunting.expressive

import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset

internal data class ArtistProfile(
    val name: String,
    val cover: String,
    val aliases: List<String>,
    val biography: String,
    val songCount: Int?,
    val albumCount: Int?,
    val hotSongs: List<Song>,
)

internal data class ArtistAlbum(
    val id: Long,
    val name: String,
    val cover: String,
    val year: Int?,
    val songCount: Int?,
)

internal data class ArtistAlbumPage(val albums: List<ArtistAlbum>, val nextOffset: Int?)

internal data class ArtistBioSection(val title: String, val text: String)

internal data class ArtistBiography(val summary: String, val sections: List<ArtistBioSection>)

private val memberHeading = Regex(
    "(?:(?:乐[团队]|樂[團隊]|组合|組合|团体|團體)|(?:现任|現任|现有|現有|历任|歷任|前任|前|原|曾在[队隊]|离队|離隊)){0,2}" +
        "成[员員](?:名单|名單|介绍|介紹)?|(?:(?:current|former|past|band)\\s+)?members",
    RegexOption.IGNORE_CASE,
)
private val biographyHeading = Regex(
    "演出.*(?:联系|聯繫|合作)|(?:资料|資料|基本信息|基本資料|联系方式|聯絡方式|聯繫方式|联系|聯繫|合作|" +
        "成立时间|成立時間|经纪公司|經紀公司|代表作品|主要成就|email|e-mail|contact|booking)",
    RegexOption.IGNORE_CASE,
)
private val numberedMemberRoster = Regex(
    """由([^。！？!?；;\r\n]{1,200}?)([二三四五六七八九十两兩2-9]|10)(?:名|位)[^、,，。！？!?；;\r\n]{0,16}?成[员員](?:所)?(?:组成|組成)""",
)
private val memberAlias = Regex("""[\p{L}\p{M} ·・.'’\-]{1,40}[（(][\p{L}\p{M} ·・.'’\-]{1,55}[）)]""")
private val memberSeparator = Regex("""(?<=[）)])\s*[、，,和及与與]\s*""")

internal fun ArtistBiography.memberSections(fallbackSummary: String): List<ArtistBioSection> {
    val titledSections = sections.filter { memberHeading.matches(it.title.cleanBioHeading()) && it.text.isNotBlank() }
    if (titledSections.isNotEmpty()) return titledSections.distinctBy(ArtistBioSection::text)

    val summaries = listOf(summary, fallbackSummary).filter(String::isNotBlank).distinct()
    val labeledSections = summaries.flatMap { it.labeledMemberSections() }.distinctBy(ArtistBioSection::text)
    if (labeledSections.isNotEmpty()) return labeledSections

    return listOfNotNull(summaries.firstNotNullOfOrNull { it.numberedMemberSection() })
}

private fun String.cleanBioHeading() = trim().trim('—', '–', '-', '－', '#', '【', '】', '〔', '〕', '[', ']').trim()

private fun String.labeledMemberSections(): List<ArtistBioSection> {
    val lines = lines()
    return lines.mapIndexedNotNull { index, line ->
        val colon = line.indexOfAny(charArrayOf('：', ':'))
        val title = line.take(if (colon < 0) line.length else colon).cleanBioHeading()
        if (!memberHeading.matches(title)) return@mapIndexedNotNull null
        val inlineText = if (colon < 0) "" else line.substring(colon + 1).trim()
        val text = inlineText.ifBlank {
            lines.asSequence().drop(index + 1).dropWhile(String::isBlank)
                .takeWhile { memberLine ->
                    val heading = memberLine.substringBefore('：').substringBefore(':').cleanBioHeading()
                    memberLine.isNotBlank() && !memberHeading.matches(heading) && !biographyHeading.matches(heading)
                }.joinToString("\n").trim()
        }
        text.takeIf(String::isNotBlank)?.let { ArtistBioSection(title, it) }
    }
}

private fun String.numberedMemberSection(): ArtistBioSection? = numberedMemberRoster.findAll(this).firstNotNullOfOrNull { match ->
    val (roster, countText) = match.destructured
    val count = countText.toIntOrNull() ?: when (countText) {
        "两", "兩" -> 2
        else -> "一二三四五六七八九十".indexOf(countText) + 1
    }
    // Accept prose only when every explicitly counted member has a name and a parenthesized alias.
    val members = roster.trim().split(memberSeparator)
    if (members.size == count && members.all(memberAlias::matches)) ArtistBioSection("成员", members.joinToString("\n"))
    else null
}

internal object ArtistCatalog {
    fun profile(id: Long): ArtistProfile {
        val response = Catalog.request("/api/artist/$id")
        val artist = response.optJSONObject("artist") ?: throw IOException("歌手资料缺失")
        val name = artist.string("name").ifBlank { throw IOException("歌手姓名缺失") }
        val alias = artist.optJSONArray("alias")
        val aliases = (0 until (alias?.length() ?: 0)).mapNotNull { alias?.opt(it) as? String }
        return ArtistProfile(
            name = name,
            cover = artist.artwork("picUrl", "img1v1Url"),
            aliases = (aliases + artist.string("trans")).map(String::trim)
                .filter { it.isNotBlank() && it != name }.distinct(),
            biography = artist.string("briefDesc"),
            songCount = artist.count("musicSize"),
            albumCount = artist.count("albumSize"),
            hotSongs = Catalog.songs(response.optJSONArray("hotSongs")).distinctBy(Song::id),
        )
    }

    fun albums(id: Long, offset: Int): ArtistAlbumPage {
        val response = Catalog.request("/api/artist/albums/$id", "limit" to "24", "offset" to offset.toString())
        val items = response.optJSONArray("hotAlbums") ?: throw IOException("专辑列表缺失")
        val albums = (0 until items.length()).mapNotNull { index ->
            val entry = items.optJSONObject(index) ?: return@mapNotNull null
            val albumId = entry.optLong("id").takeIf { it > 0 } ?: return@mapNotNull null
            val title = entry.string("name").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val year = entry.optLong("publishTime").takeIf { it > 0 }?.let {
                Instant.ofEpochMilli(it).atOffset(ZoneOffset.UTC).year.takeIf { year -> year in 1000..9999 }
            }
            ArtistAlbum(albumId, title, entry.artwork("picUrl", "blurPicUrl"), year, entry.count("size"))
        }
        val next = (offset + items.length()).takeIf { items.length() > 0 && response.optBoolean("more") }
        return ArtistAlbumPage(albums.distinctBy(ArtistAlbum::id), next)
    }

    fun biography(id: Long): ArtistBiography {
        val response = Catalog.weapi("/weapi/artist/introduction", JSONObject().put("id", id))
        val data = response.optJSONObject("data") ?: response
        val summary = data.string("briefDesc")
        val introduction = data.optJSONArray("introduction")
        val sections = (0 until (introduction?.length() ?: 0)).mapNotNull { index ->
            val entry = introduction?.optJSONObject(index) ?: return@mapNotNull null
            val text = entry.string("txt").takeIf { it.isNotBlank() && it != summary } ?: return@mapNotNull null
            ArtistBioSection(entry.string("ti"), text)
        }.distinctBy(ArtistBioSection::text)
        return ArtistBiography(summary, sections)
    }

    private fun JSONObject.string(key: String) = (opt(key) as? String).orEmpty().trim()

    private fun JSONObject.count(key: String) = optInt(key, -1).takeIf { it >= 0 }

    private fun JSONObject.artwork(vararg keys: String) = keys.firstNotNullOfOrNull {
        Catalog.httpsUri(string(it))?.toString()
    }.orEmpty()
}
