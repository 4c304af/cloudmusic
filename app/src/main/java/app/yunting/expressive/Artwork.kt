package app.yunting.expressive

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.net.Uri
import android.util.LruCache
import androidx.annotation.OptIn
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import java.io.ByteArrayOutputStream
import java.net.URL

internal object CoverCache {
    private val entries = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    fun load(url: String): Bitmap? {
        entries.get(url)?.let { return it }
        if (Uri.parse(url).scheme != "https") return null
        return runCatching {
            val connection = URL(url).openConnection()
            connection.connectTimeout = 5_000
            connection.readTimeout = 7_000
            connection.getInputStream().use { BitmapFactory.decodeStream(it) }
        }.getOrNull()?.also { entries.put(url, it) }
    }
}

internal data class Artwork(val session: ByteArray?, val widget: Bitmap)

internal fun prepareArtwork(bitmap: Bitmap): Artwork {
    val edge = minOf(bitmap.width, bitmap.height, 192)
    val widget = ThumbnailUtils.extractThumbnail(bitmap, edge, edge)
    // Publish the full image. Each media surface crops it to its own bounds.
    var candidate = bitmap
    val longest = maxOf(candidate.width, candidate.height)
    if (longest > 1024) {
        candidate = Bitmap.createScaledBitmap(candidate, maxOf(1, candidate.width * 1024 / longest),
            maxOf(1, candidate.height * 1024 / longest), true)
    }
    ByteArrayOutputStream().use { padded ->
        for (quality in listOf(92, 82, 72)) {
            padded.reset()
            if (candidate.compress(Bitmap.CompressFormat.JPEG, quality, padded) && padded.size() in 1..262_144) {
                return Artwork(padded.toByteArray(), widget)
            }
        }
    }
    return Artwork(null, widget)
}

@OptIn(UnstableApi::class)
internal class ArtworkPlayer(private val source: Player) : ForwardingSimpleBasePlayer(source) {
    private var artworkKey: Pair<String, String?>? = null
    private var bytes: ByteArray? = null

    fun publish(key: Pair<String, String?>?, artwork: ByteArray?) {
        artworkKey = key
        bytes = artwork
        invalidateState()
    }

    override fun getState(): State {
        val state = super.getState()
        val image = bytes ?: return state
        val current = source.currentMediaItem ?: return state
        if (artworkKey != (current.mediaId to source.mediaMetadata.artworkUri?.toString())) return state
        val metadata = state.currentMetadata.buildUpon().setArtworkData(image, MediaMetadata.PICTURE_TYPE_FRONT_COVER).build()
        return state.buildUpon().setPlaylist(state.timeline, state.currentTracks, metadata).build()
    }
}
