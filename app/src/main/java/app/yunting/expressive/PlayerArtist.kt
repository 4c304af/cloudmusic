package app.yunting.expressive

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun PlayerArtist(song: Song, onOpen: (Long) -> Unit, modifier: Modifier = Modifier) {
    val songId = song.id.toLongOrNull()?.takeIf { it > 0 }
    val embedded = remember(song.artists) {
        song.artists.filter { it.id > 0 && it.name.isNotBlank() }.distinctBy(SongArtist::id)
    }
    val legacy = song.artistId.takeIf { it > 0 && song.artist.isNotBlank() && " / " !in song.artist }
        ?.let { SongArtist(it, song.artist) }
    val openArtist by rememberUpdatedState(onOpen)
    var expanded by remember(song.id) { mutableStateOf(false) }
    var fetched by remember(song.id) { mutableStateOf<List<SongArtist>?>(null) }
    var failed by remember(song.id) { mutableStateOf(false) }
    var attempt by remember(song.id) { mutableIntStateOf(0) }
    val credits = embedded.ifEmpty { fetched.orEmpty() }

    LaunchedEffect(songId, expanded, attempt, embedded) {
        if (!expanded || songId == null || embedded.isNotEmpty() || fetched != null) return@LaunchedEffect
        failed = false
        val result = onIo { Catalog.songCredits(songId).ifEmpty { listOfNotNull(legacy) } }
        result.onSuccess { artists ->
            fetched = artists
            artists.singleOrNull()?.let { artist ->
                expanded = false
                openArtist(artist.id)
            }
        }
        failed = result.isFailure
    }

    Box(modifier) {
        val link = if (credits.isNotEmpty() || songId != null || legacy != null) {
            Modifier.clickable(
                interactionSource = null,
                indication = null,
                onClickLabel = "查看歌手",
                role = Role.Button,
                onClick = {
                    val single = credits.singleOrNull() ?: legacy?.takeIf { credits.isEmpty() && songId == null }
                    if (single != null) openArtist(single.id) else expanded = true
                },
            )
        } else Modifier
        Text(
            text = song.artist.ifBlank { credits.joinToString(" / ") { it.name }.ifBlank { "未知歌手" } },
            modifier = link,
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 22.sp, lineHeight = 29.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 320.dp),
        ) {
            when {
                credits.isNotEmpty() -> credits.forEach { artist ->
                    DropdownMenuItem(
                        text = { Text(artist.name) },
                        onClick = {
                            expanded = false
                            openArtist(artist.id)
                        },
                    )
                }
                failed -> DropdownMenuItem(
                    text = { Text("加载歌手失败，重试") },
                    onClick = { failed = false; attempt++ },
                )
                fetched != null -> DropdownMenuItem(
                    text = { Text("这首歌暂无可查看的歌手") },
                    onClick = {},
                    enabled = false,
                )
                else -> DropdownMenuItem(text = { Text("正在加载歌手…") }, onClick = {}, enabled = false)
            }
        }
    }
}
