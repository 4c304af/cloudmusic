package app.yunting.expressive

import android.net.Uri
import android.os.Build
import android.util.Base64
import android.webkit.CookieManager
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal data class SongArtist(val id: Long, val name: String)

internal data class Song(
    val id: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val cover: String = "",
    val localUri: String? = null,
    val artistId: Long = 0,
    val albumId: Long = 0,
    val artists: List<SongArtist> = emptyList(),
) {
    fun mediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setUri(localUri ?: "yunting://song/$id")
        .setMediaMetadata(
            MediaMetadata.Builder().setTitle(title).setArtist(artist).setAlbumTitle(album)
                .setArtworkUri(cover.takeIf(String::isNotBlank)?.let(Uri::parse))
                .setExtras(android.os.Bundle().apply {
                    putLong("artistId", artistId)
                    putLong("albumId", albumId)
                    if (artists.isNotEmpty()) {
                        putLongArray("artistIds", artists.map(SongArtist::id).toLongArray())
                        putStringArray("artistNames", artists.map(SongArtist::name).toTypedArray())
                    }
                }).build()
        ).build()
}

internal data class LyricLine(val timeMs: Long, val text: String, val translation: String = "")
internal data class Account(val id: Long, val name: String, val avatar: String)
internal data class Playlist(val id: Long, val title: String, val size: Int, val cover: String = "")

internal enum class PlaybackQuality(val level: String?, val label: String) {
    Default(null, "默认"),
    Standard("standard", "标准音质"),
    High("exhigh", "极高音质"),
    Lossless("lossless", "无损音质（FLAC）"),
    HiRes("hires", "Hi-Res 无损"),
    Master("jymaster", "超清母带");

    companion object {
        fun fromLevel(level: String?): PlaybackQuality = entries.find { it.level == level } ?: Default
    }
}

internal fun parseSongArtists(entries: JSONArray?): List<SongArtist> =
    (0 until (entries?.length() ?: 0)).mapNotNull { index ->
        val entry = entries?.optJSONObject(index) ?: return@mapNotNull null
        val id = entry.optLong("id").takeIf { it > 0 } ?: return@mapNotNull null
        val name = (entry.opt("name") as? String)?.trim()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        SongArtist(id, name)
    }.distinctBy(SongArtist::id)

internal object Catalog {
    private const val origin = "https://music.163.com"
    private val timestamp = Regex("""\[(\d+):(\d+)(?:[.:](\d{1,3}))?]""")

    fun search(query: String): List<Song> {
        val entries = searchResponse(query, SearchKind.Songs).optJSONObject("result")?.optJSONArray("songs")
        val songs = songs(entries)
        if (songs.isEmpty()) return songs
        val details = runCatching {
            songs(request("/api/song/detail", "ids" to songs.joinToString(",", "[", "]") { it.id }).optJSONArray("songs"))
                .associateBy(Song::id)
        }.getOrDefault(emptyMap())
        return songs.map { details[it.id] ?: it }
    }

    fun search(query: String, kind: SearchKind): SearchResultPage = when (kind) {
        SearchKind.Songs -> SearchResultPage(songs = search(query))
        SearchKind.Artists -> SearchResultPage(entries = searchEntries(query, kind, "artists") { entry, id, title ->
            val aliases = entry.optJSONArray("alias")
            val subtitle = (0 until (aliases?.length() ?: 0)).mapNotNull { aliases?.opt(it) as? String }
                .filter(String::isNotBlank).distinct().joinToString(" / ")
            SearchEntry(title, subtitle, searchCover(entry, "picUrl", "img1v1Url"), "artist/$id")
        })
        SearchKind.Albums -> SearchResultPage(entries = searchEntries(query, kind, "albums") { entry, id, title ->
            val artists = entry.optJSONArray("artists")
            val subtitle = (0 until (artists?.length() ?: 0)).mapNotNull { artists?.optJSONObject(it)?.opt("name") as? String }
                .filter(String::isNotBlank).distinct().joinToString(" / ")
                .ifBlank { (entry.optJSONObject("artist")?.opt("name") as? String).orEmpty() }
            SearchEntry(title, subtitle, searchCover(entry, "picUrl", "blurPicUrl"), "album/$id")
        })
    }

    private fun searchResponse(query: String, kind: SearchKind): JSONObject =
        request("/api/search/get/web", "s" to query, "type" to kind.apiType, "limit" to "35")

    private fun searchEntries(
        query: String,
        kind: SearchKind,
        resultKey: String,
        parse: (JSONObject, Long, String) -> SearchEntry,
    ): List<SearchEntry> {
        val response = searchResponse(query, kind)
        val entries = response.optJSONObject("result")?.optJSONArray(resultKey) ?: response.optJSONArray(resultKey) ?: return emptyList()
        return (0 until entries.length()).mapNotNull { index ->
            val entry = entries.optJSONObject(index) ?: return@mapNotNull null
            val id = entry.optLong("id").takeIf { it > 0 } ?: return@mapNotNull null
            val title = (entry.opt("name") as? String)?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            parse(entry, id, title)
        }.distinctBy(SearchEntry::route)
    }

    private fun searchCover(entry: JSONObject, vararg fields: String): String =
        fields.firstNotNullOfOrNull { httpsUri(entry.optString(it))?.toString() }.orEmpty()

    fun account(): Account? {
        val profile = request("/api/nuser/account/get").optJSONObject("profile") ?: return null
        val id = profile.optLong("userId").takeIf { it > 0 } ?: return null
        return Account(id, profile.optString("nickname"),
            httpsUri(profile.optString("avatarUrl"))?.toString().orEmpty())
    }

    fun dailyRecommendations(): List<Song> {
        val response = request("/api/v3/discovery/recommend/songs")
        return songs(response.optJSONObject("data")?.optJSONArray("dailySongs") ?: response.optJSONArray("recommend"))
    }

    fun userPlaylists(id: Long): List<Playlist> {
        val entries = request("/api/user/playlist", "uid" to id.toString(), "limit" to "100").optJSONArray("playlist")
        return List(entries?.length() ?: 0) { entries?.optJSONObject(it) }.mapNotNull { entry ->
            val playlistId = entry?.optLong("id")?.takeIf { it > 0 } ?: return@mapNotNull null
            Playlist(playlistId, entry.optString("name"), entry.optInt("trackCount"),
                httpsUri(entry.optString("coverImgUrl"))?.toString().orEmpty())
        }
    }

    fun playlistTracks(id: Long): List<Song> {
        val response = request("/api/playlist/detail", "id" to id.toString())
        return songs((response.optJSONObject("playlist") ?: response.optJSONObject("result"))?.optJSONArray("tracks"))
    }

    fun songCredits(id: Long): List<SongArtist> {
        require(id > 0) { "曲目编号无效" }
        return songs(request("/api/song/detail", "ids" to "[$id]").optJSONArray("songs"))
            .firstOrNull { it.id == id.toString() }?.artists.orEmpty()
    }

    fun playbackUri(id: String, requireFull: Boolean = false, quality: PlaybackQuality = PlaybackQuality.Default): Uri {
        if (id.toLongOrNull() == null) throw IOException("曲目编号无效")
        val preferredUrl = quality.level?.let { level ->
            runCatching {
                val fields = JSONObject().put("ids", "[$id]").put("level", level).put("encodeType", "flac")
                val item = mobileApi("song/enhance/player/url/v1", fields).optJSONArray("data")?.optJSONObject(0)
                if (item?.isNull("freeTrialInfo") == true && item.optInt("code") == 200) {
                    httpsUri(item.optString("url"))
                } else null
            }.getOrNull()
        }
        if (preferredUrl != null) return preferredUrl
        val data = request("/api/song/enhance/player/url", "ids" to "[$id]", "br" to "320000")
        val items = data.optJSONArray("data")
        val item = items?.optJSONObject(0)
        if (requireFull && item != null && !item.isNull("freeTrialInfo")) throw IOException("试听曲目无法离线下载")
        val url = item?.takeUnless { it.isNull("url") }?.optString("url").orEmpty()
        return httpsUri(url) ?: throw IOException("这首歌暂无可播放音源")
    }

    fun lyrics(id: String): List<LyricLine> {
        if (id.toLongOrNull() == null) return emptyList()
        val value = request("/api/song/lyric", "id" to id, "lv" to "-1", "tv" to "-1")
        val translated = parseLrc(value.optJSONObject("tlyric")?.optString("lyric").orEmpty()).toMap()
        return parseLrc(value.optJSONObject("lrc")?.optString("lyric").orEmpty())
            .distinctBy { it.first }.sortedBy { it.first }
            .map { (time, line) -> LyricLine(time, line, translated[time].orEmpty()) }
    }

    fun similarSongs(id: Long): List<Song> {
        require(id > 0)
        val response = mobileApi("v1/discovery/simiSong", JSONObject().put("songid", id.toString()))
        return songs(response.optJSONArray("songs")).filter { it.id != id.toString() }.distinctBy(Song::id)
    }

    private fun parseLrc(text: String): List<Pair<Long, String>> = text.lineSequence().flatMap { row ->
        val matches = timestamp.findAll(row).toList()
        val lyric = matches.lastOrNull()?.let { row.substring(it.range.last + 1).trim() }.orEmpty()
        if (lyric.isBlank()) emptySequence() else matches.asSequence().map { stamp ->
            val (minutes, seconds, fraction) = stamp.destructured
            ((minutes.toLong() * 60 + seconds.toLong()) * 1000 + fraction.padEnd(3, '0').toLong()) to lyric
        }
    }.toList()

    fun songs(entries: JSONArray?): List<Song> = List(entries?.length() ?: 0) { entries?.optJSONObject(it) }
        .mapNotNull { entry ->
            val id = entry?.optLong("id")?.takeIf { it > 0 } ?: return@mapNotNull null
            val artists = entry.optJSONArray("artists") ?: entry.optJSONArray("ar")
            val album = entry.optJSONObject("album") ?: entry.optJSONObject("al")
            Song(
                id = id.toString(),
                title = entry.optString("name"),
                artist = List(artists?.length() ?: 0) { artists?.optJSONObject(it)?.optString("name").orEmpty() }
                    .filter(String::isNotBlank).joinToString(" / "),
                album = album?.optString("name").orEmpty(),
                cover = httpsUri(album?.optString("picUrl").orEmpty())?.toString().orEmpty(),
                artistId = artists?.optJSONObject(0)?.optLong("id") ?: 0,
                albumId = album?.optLong("id") ?: 0,
                artists = (parseSongArtists(entry.optJSONArray("artists")) + parseSongArtists(entry.optJSONArray("ar")))
                    .distinctBy(SongArtist::id),
            )
        }

    fun httpsUri(value: String): Uri? {
        val uri = Uri.parse(value)
        return when {
            uri.host.isNullOrBlank() -> null
            uri.scheme == "https" -> uri
            uri.scheme == "http" -> uri.buildUpon().scheme("https").build()
            else -> null
        }
    }

    fun request(path: String, vararg fields: Pair<String, String>): JSONObject =
        connect("$origin$path?${form(fields.asList())}")

    fun listeningDuration(expectedLoginCookie: String? = null): Long {
        val data = weapi("/weapi/content/activity/listen/data/total", expectedLoginCookie = expectedLoginCookie)
            .getJSONObject("data")
        return data.getLong("totalDuration")
    }

    fun weapi(path: String, fields: JSONObject = JSONObject(), expectedLoginCookie: String? = null): JSONObject {
        val url = "$origin$path"
        val cookie = CookieManager.getInstance().getCookie(url).orEmpty()
        verifyLoginCookie(cookie, expectedLoginCookie)
        val csrf = cookieValue(cookie, "__csrf").orEmpty()
        val payload = JSONObject(fields.toString()).put("csrf_token", csrf)
        return connect("$url?csrf_token=${URLEncoder.encode(csrf, "UTF-8")}", WebApiForm.encode(payload), mapOf("Cookie" to cookie))
    }

    fun mobileApi(path: String, fields: JSONObject, expectedLoginCookie: String? = null): JSONObject {
        val apiPath = "/api/$path"
        val clientVersion = "9.0.90"
        val androidVersion = Build.VERSION.RELEASE
        val payload = JSONObject(fields.toString())
            .put("e_r", false)
            .put("header", JSONObject().put("os", "android").put("osver", androidVersion).put("appver", clientVersion)
                .put("requestId", System.currentTimeMillis().toString()))
            .toString()
        val digest = MessageDigest.getInstance("MD5")
            .digest("nobody${apiPath}use${payload}md5forencrypt".toByteArray(Charsets.UTF_8)).toHex()
        val data = "$apiPath-36cd479b6b5-$payload-36cd479b6b5-$digest"
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec("e82ckenh8dichen8".toByteArray(Charsets.US_ASCII), "AES"))
        val params = cipher.doFinal(data.toByteArray(Charsets.UTF_8)).toHex().uppercase()
        val cookie = CookieManager.getInstance().getCookie(origin).orEmpty()
        verifyLoginCookie(cookie, expectedLoginCookie)
        val cookies = cookie.split(';').map(String::trim)
            .filter { it.substringBefore('=') in setOf("MUSIC_U", "MUSIC_A", "__csrf", "NMTID", "_ntes_nuid", "_ntes_nnid") }
        return connect("https://interface.music.163.com/eapi/$path", listOf("params" to params), mapOf(
            "User-Agent" to "NeteaseMusic/$clientVersion (Linux; Android $androidVersion)",
            "Cookie" to (cookies + "os=android" + "appver=$clientVersion" + "osver=$androidVersion").joinToString("; "),
        ))
    }

    private fun ByteArray.toHex() = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private fun verifyLoginCookie(cookie: String, expected: String?) {
        if (expected != null && cookieValue(cookie, "MUSIC_U") != expected) throw IOException("登录账号已变化，请返回后重试")
    }

    private fun cookieValue(cookie: String, name: String): String? = cookie.split(';').map(String::trim)
        .firstOrNull { it.startsWith("$name=") }?.substringAfter('=')

    private fun connect(
        url: String,
        body: List<Pair<String, String>>? = null,
        headers: Map<String, String> = emptyMap(),
    ): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 10_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Referer", "$origin/")
            connection.setRequestProperty("User-Agent", "Mozilla/5.0")
            CookieManager.getInstance().getCookie(url)?.let { connection.setRequestProperty("Cookie", it) }
            headers.forEach(connection::setRequestProperty)
            if (body != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(form(body)) }
            }
            val value = connection.inputStream.bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
            if (value.optInt("code", 200) != 200) throw IOException("服务暂时无法提供该内容")
            value
        } finally {
            connection.disconnect()
        }
    }

    private fun form(fields: List<Pair<String, String>>) =
        fields.joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, "UTF-8")}" }
}

private object WebApiForm {
    private const val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    private val random = SecureRandom()
    private val modulus = BigInteger(
        "e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7b725152b3ab17a876aea8a5aa76d2e417629ec4ee341f56135fccf695280104e0312ecbda92557c93870114af6c9d05c4f7f0c3685b7a46bee255932575cce10b424d813cfe4875d3e82047b97ddef52741d546b8e289dc6935b3ece0462db0a22b8e7", 16)

    fun encode(payload: JSONObject): List<Pair<String, String>> {
        val key = buildString { repeat(16) { append(alphabet[random.nextInt(alphabet.length)]) } }
        val encrypted = encrypt(encrypt(payload.toString(), "0CoJUm6Qyw8W8jud"), key)
        val secret = BigInteger(1, key.reversed().toByteArray(Charsets.US_ASCII))
            .modPow(BigInteger.valueOf(65537), modulus).toString(16).padStart(256, '0')
        return listOf("params" to encrypted, "encSecKey" to secret)
    }

    private fun encrypt(value: String, key: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.toByteArray(Charsets.US_ASCII), "AES"),
            IvParameterSpec("0102030405060708".toByteArray(Charsets.US_ASCII)))
        return Base64.encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }
}
