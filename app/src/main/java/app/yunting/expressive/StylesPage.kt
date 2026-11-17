@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package app.yunting.expressive

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private data class MusicStyle(val id: Long, val title: String, val children: List<MusicStyle> = emptyList())
private data class StyleDetails(val cover: String, val description: String)
private data class StyleSongs(val songs: List<Song>, val nextOffset: Int?)

private fun JSONArray?.styleTags(): List<MusicStyle> = List(this?.length() ?: 0) { this?.optJSONObject(it) }
    .mapNotNull { entry ->
        val id = entry?.optLong("tagId")?.takeIf { it > 0 } ?: return@mapNotNull null
        val title = entry.optString("tagName").takeIf { !entry.isNull("tagName") && it.isNotBlank() }
            ?: return@mapNotNull null
        MusicStyle(id, title, entry.optJSONArray("childrenTags").styleTags())
    }.distinctBy(MusicStyle::id)

private suspend fun fetchStyles(): List<MusicStyle> = withContext(Dispatchers.IO) {
    Catalog.weapi("/weapi/tag/list/get").optJSONArray("data").styleTags()
}

private suspend fun fetchStyleDetails(id: Long): StyleDetails? = withContext(Dispatchers.IO) {
    val data = Catalog.weapi("/weapi/style-tag/home/head", JSONObject().put("tagId", id)).optJSONObject("data")
        ?: return@withContext null
    val covers = data.optJSONArray("cover")
    val cover = (0 until (covers?.length() ?: 0)).firstNotNullOfOrNull {
        Catalog.httpsUri(covers?.optString(it).orEmpty())?.toString()
    }.orEmpty()
    val description = if (data.isNull("desc")) "" else data.optString("desc").trim()
    StyleDetails(cover, description)
}

private suspend fun fetchStyleSongs(id: Long, offset: Int): StyleSongs = withContext(Dispatchers.IO) {
    val size = 20
    val fields = JSONObject().put("tagId", id).put("cursor", offset).put("size", size).put("sort", 0)
    val data = Catalog.weapi("/weapi/style-tag/home/song", fields).getJSONObject("data")
    val entries = data.optJSONArray("songs")
    val count = entries?.length() ?: 0
    val hasMore = data.optJSONObject("page")?.optBoolean("more", count >= size) ?: (count >= size)
    StyleSongs(Catalog.songs(entries), (offset + size).takeIf { hasMore && count > 0 })
}

@Composable
internal fun StylesPage(onBack: () -> Unit, ready: Boolean, onChoose: (List<Song>, Int) -> Unit) {
    var selected by rememberSaveable { mutableLongStateOf(0L) }
    var title by rememberSaveable { mutableStateOf("") }
    var parentId by rememberSaveable { mutableLongStateOf(0L) }
    var parentTitle by rememberSaveable { mutableStateOf("") }
    var groups by remember { mutableStateOf(emptyList<MusicStyle>()) }
    var failed by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var attempt by remember { mutableIntStateOf(0) }
    val gridState = rememberLazyGridState()

    fun back() {
        if (selected == 0L) {
            onBack()
        } else {
            selected = parentId
            title = parentTitle
            parentId = 0L
            parentTitle = ""
        }
    }

    BackHandler(enabled = selected != 0L, onBack = ::back)
    LaunchedEffect(attempt) {
        try {
            groups = fetchStyles()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        } finally {
            loading = false
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 720.dp).fillMaxSize()) {
            PageHeading(if (selected == 0L) "曲风" else title, ::back)
            if (selected == 0L) {
                when {
                    loading -> StyleLoading()
                    failed -> StyleFailure("暂时无法加载曲风") {
                        failed = false
                        loading = true
                        attempt++
                    }
                    groups.isEmpty() -> Text("暂无曲风", Modifier.padding(16.dp))
                    else -> LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        state = gridState,
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(groups, key = MusicStyle::id) { group ->
                            Card(
                                onClick = { selected = group.id; title = group.title },
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                                shape = MaterialTheme.shapes.large,
                                modifier = Modifier.heightIn(min = 84.dp),
                            ) {
                                Text(group.title, Modifier.padding(16.dp),
                                    style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            } else {
                val children = groups.firstOrNull { it.id == selected }?.children.orEmpty()
                key(selected) {
                    StyleDetail(selected, children, ready, onChoose) { child ->
                        parentId = selected
                        parentTitle = title
                        selected = child.id
                        title = child.title
                    }
                }
            }
        }
    }
}

@Composable
private fun StyleDetail(
    id: Long,
    children: List<MusicStyle>,
    ready: Boolean,
    onChoose: (List<Song>, Int) -> Unit,
    onChild: (MusicStyle) -> Unit,
) {
    var details by remember { mutableStateOf<StyleDetails?>(null) }
    var songs by remember { mutableStateOf(emptyList<Song>()) }
    var offset by remember { mutableStateOf<Int?>(0) }
    var attempt by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }

    LaunchedEffect(id) {
        details = try {
            fetchStyleDetails(id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }
    LaunchedEffect(attempt) {
        try {
            val result = fetchStyleSongs(id, offset ?: return@LaunchedEffect)
            songs = (songs + result.songs).distinctBy(Song::id)
            offset = result.nextOffset
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        } finally {
            loading = false
        }
    }

    fun load() {
        if (loading) return
        failed = false
        loading = true
        attempt++
    }

    LazyColumn(
        Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        details?.let { head ->
            if (head.cover.isNotBlank() || head.description.isNotBlank()) item {
                Card(shape = MaterialTheme.shapes.large) {
                    if (head.cover.isNotBlank()) Cover(head.cover, Modifier.fillMaxWidth().height(128.dp), radius = 0)
                    if (head.description.isNotBlank()) Text(head.description, Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (children.isNotEmpty()) item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(children, key = MusicStyle::id) { child ->
                    AssistChip(onClick = { onChild(child) }, label = { Text(child.title) })
                }
            }
        }
        if (songs.isNotEmpty()) item {
            Text("歌曲", Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.titleLarge)
        }
        itemsIndexed(songs, key = { _, song -> song.id }) { index, song ->
            ListItem(
                headlineContent = { Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = if (song.artist.isBlank()) null else ({
                    Text(song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }),
                leadingContent = { Cover(song, Modifier.size(48.dp), 16) },
                trailingContent = { Icon(Icons.Rounded.PlayArrow, null) },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                modifier = Modifier.clip(MaterialTheme.shapes.medium).clickable(enabled = ready, role = Role.Button) {
                    onChoose(songs, index)
                },
            )
        }
        when {
            loading -> item { StyleLoading() }
            failed -> item { StyleFailure("暂时无法加载曲风歌曲", ::load) }
            offset != null -> item {
                Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                    FilledTonalButton(onClick = ::load) { Text("加载更多") }
                }
            }
            songs.isEmpty() -> item { Text("此曲风暂无歌曲", Modifier.padding(8.dp)) }
        }
    }
}

@Composable
private fun StyleLoading() {
    LinearWavyProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
}

@Composable
private fun StyleFailure(message: String, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(message, color = MaterialTheme.colorScheme.error)
        FilledTonalButton(onClick = onRetry) { Text("重试") }
    }
}
