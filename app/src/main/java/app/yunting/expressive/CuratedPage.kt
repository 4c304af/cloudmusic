@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package app.yunting.expressive

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException

@Composable
internal fun CuratedPage(
    onBack: () -> Unit,
    onPlaylist: (Playlist) -> Unit,
    active: Boolean = true,
    bottomContentPadding: Dp = 0.dp,
    browseActions: (@Composable () -> Unit)? = null,
) {
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = category
    val back: () -> Unit = {
        if (category == null) onBack() else category = null
    }
    BackHandler(enabled = active && selected != null, onBack = back)
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val gutter = if (maxWidth < 360.dp) 12.dp else 16.dp
        val columnGap = if (maxWidth < 360.dp) 12.dp else 20.dp
        Column(Modifier.widthIn(max = 720.dp).fillMaxSize()) {
            if (browseActions == null || selected != null) {
                PageHeading(selected ?: "精品歌单", back)
            }
            key(selected) {
                var categories by remember { mutableStateOf(emptyList<CuratedCategory>()) }
                var playlists by remember { mutableStateOf(emptyList<Playlist>()) }
                var cursor by remember { mutableStateOf<Long?>(0L) }
                var loading by remember { mutableStateOf(true) }
                var error by remember { mutableStateOf<String?>(null) }
                var requestNumber by remember { mutableIntStateOf(0) }

                fun load() {
                    if (loading) return
                    loading = true
                    error = null
                    requestNumber++
                }

                LaunchedEffect(requestNumber) {
                    try {
                        if (selected == null) {
                            categories = CuratedCatalog.categories()
                        } else {
                            val next = cursor ?: return@LaunchedEffect
                            val page = CuratedCatalog.playlists(selected, next)
                            playlists = (playlists + page.playlists).distinctBy(Playlist::id)
                            cursor = page.nextCursor
                        }
                    } catch (failure: CancellationException) {
                        throw failure
                    } catch (_: Exception) {
                        error = if (selected == null) "暂时无法加载精品歌单分类" else "暂时无法加载歌单"
                    } finally {
                        loading = false
                    }
                }

                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(start = gutter, end = gutter, bottom = 16.dp + bottomContentPadding),
                    horizontalArrangement = Arrangement.spacedBy(columnGap),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    if (selected == null) {
                        if (browseActions != null) item(span = { GridItemSpan(maxLineSpan) }) {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("精品歌单", style = MaterialTheme.typography.headlineLarge)
                                browseActions()
                            }
                        }
                        items(categories, key = CuratedCategory::title) { item ->
                            CuratedCard(item.title, item.cover) { category = item.title }
                        }
                    } else {
                        items(playlists, key = Playlist::id) { item ->
                            CuratedCard(item.title, item.cover) { onPlaylist(item) }
                        }
                    }
                    val isEmpty = if (selected == null) categories.isEmpty() else playlists.isEmpty()
                    when {
                        loading -> item(span = { GridItemSpan(maxLineSpan) }) {
                            LinearWavyProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 12.dp))
                        }
                        error != null -> item(span = { GridItemSpan(maxLineSpan) }) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                                FilledTonalButton(onClick = ::load) { Text("重试") }
                            }
                        }
                        isEmpty -> item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(if (selected == null) "暂无精品歌单分类" else "此分类暂无歌单")
                        }
                        selected != null && cursor != null -> item(span = { GridItemSpan(maxLineSpan) }) {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                FilledTonalButton(onClick = ::load) { Text("加载更多") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CuratedCard(title: String, cover: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 176.dp),
        shape = MaterialTheme.shapes.large,
    ) {
        Box(Modifier.fillMaxWidth().heightIn(min = 176.dp)) {
            Cover(cover, Modifier.matchParentSize(), radius = 0)
            Text(
                title,
                modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(colors.surface.copy(alpha = 0.72f), colors.surface)))
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                color = colors.onSurface,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
