package app.yunting.expressive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

internal data class FeaturedSongComment(val author: String, val content: String)

@Composable
internal fun PlayerFeaturedComment(songId: Long?, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val id = songId?.takeIf { it > 0 }
    var comment by remember(id) { mutableStateOf<FeaturedSongComment?>(null) }
    var status by remember(id) { mutableStateOf("正在加载精选评论") }
    LaunchedEffect(id) {
        if (id == null) return@LaunchedEffect
        delay(250)
        try {
            comment = fetchFeaturedSongComment(id)
            status = "暂无精选评论"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            status = "暂时无法加载精选评论"
        }
    }

    Surface(
        onClick = onOpen,
        modifier = modifier.fillMaxWidth(),
        enabled = id != null,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(28.dp),
    ) {
        val preview = comment
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                if (preview == null) "精选评论" else "精选评论 · ${preview.author}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                preview?.content ?: if (id == null) "暂无精选评论" else status,
                Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
