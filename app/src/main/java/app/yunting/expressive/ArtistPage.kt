@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package app.yunting.expressive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class ArtistSection(val label: String) {
    Songs("歌曲"), Albums("专辑"), Biography("简介"), Members("成员"),
}

@Composable
internal fun ArtistPage(
    id: Long,
    ready: Boolean,
    now: Song?,
    onBack: () -> Unit,
    onOpenAlbum: (Long) -> Unit,
    onSongComments: (Long) -> Unit,
    onChoose: (List<Song>, Int) -> Unit,
) {
    var profile by remember(id) { mutableStateOf<ArtistProfile?>(null) }
    var failed by remember(id) { mutableStateOf(false) }
    var attempt by remember(id) { mutableIntStateOf(0) }
    LaunchedEffect(id, attempt) {
        failed = false
        try {
            profile = withContext(Dispatchers.IO) { ArtistCatalog.profile(id) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        }
    }

    Column(Modifier.fillMaxSize()) {
        PageHeading("歌手详情", onBack)
        val artist = profile
        when {
            artist != null -> ArtistContent(id, artist, ready, now, onOpenAlbum, onSongComments, onChoose)
            failed -> ArtistStatus("暂时无法加载歌手资料", Modifier.padding(horizontal = 16.dp)) { attempt++ }
            else -> LinearWavyProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
        }
    }
}

@Composable
private fun ArtistContent(
    id: Long,
    artist: ArtistProfile,
    ready: Boolean,
    now: Song?,
    onOpenAlbum: (Long) -> Unit,
    onSongComments: (Long) -> Unit,
    onChoose: (List<Song>, Int) -> Unit,
) {
    var section by rememberSaveable(id) { mutableStateOf(ArtistSection.Songs) }
    var albums by remember(id) { mutableStateOf<List<ArtistAlbum>>(emptyList()) }
    var nextOffset by remember(id) { mutableStateOf<Int?>(0) }
    var requestedOffset by remember(id) { mutableStateOf<Int?>(null) }
    var albumFailed by remember(id) { mutableStateOf(false) }
    var biography by remember(id) { mutableStateOf<ArtistBiography?>(null) }
    var biographyFailed by remember(id) { mutableStateOf(false) }
    var biographyRequested by remember(id) { mutableStateOf(false) }
    var biographyAttempt by remember(id) { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    fun loadAlbums() {
        if (requestedOffset != null) return
        val offset = nextOffset ?: return
        albumFailed = false
        requestedOffset = offset
    }

    LaunchedEffect(section) {
        if (section == ArtistSection.Albums && nextOffset == 0 && !albumFailed) loadAlbums()
        if (section == ArtistSection.Biography || section == ArtistSection.Members) biographyRequested = true
    }
    LaunchedEffect(id, biographyRequested, biographyAttempt) {
        if (!biographyRequested) return@LaunchedEffect
        biographyFailed = false
        try {
            biography = withContext(Dispatchers.IO) { ArtistCatalog.biography(id) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            biographyFailed = true
        }
    }
    LaunchedEffect(id, requestedOffset) {
        val offset = requestedOffset ?: return@LaunchedEffect
        try {
            val page = withContext(Dispatchers.IO) { ArtistCatalog.albums(id, offset) }
            val merged = (albums + page.albums).distinctBy(ArtistAlbum::id)
            nextOffset = page.nextOffset.takeIf { merged.size > albums.size }
            albums = merged
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            albumFailed = true
        } finally {
            requestedOffset = null
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "artist-header") {
                ArtistHero(artist, ready) { onChoose(artist.hotSongs, 0) }
            }
            stickyHeader(key = "artist-tabs") {
                SecondaryTabRow(
                    selectedTabIndex = section.ordinal,
                    containerColor = MaterialTheme.colorScheme.background,
                ) {
                    ArtistSection.entries.forEach { item ->
                        Tab(
                            selected = section == item,
                            onClick = {
                                if (section != item) {
                                    section = item
                                    if (listState.firstVisibleItemIndex > 0) {
                                        scope.launch { listState.scrollToItem(1) }
                                    }
                                }
                            },
                            text = { Text(item.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        )
                    }
                }
            }
            when (section) {
                ArtistSection.Songs -> {
                    if (artist.hotSongs.isEmpty()) item { ArtistStatus("暂无热门歌曲") }
                    else {
                        item { Text("热门歌曲", style = MaterialTheme.typography.titleMedium) }
                        itemsIndexed(artist.hotSongs, key = { _, song -> "song/${song.id}" }) { index, song ->
                            val songId = song.id.toLongOrNull()?.takeIf { it > 0 }
                            SongCard(
                                song, active = song.id == now?.id, enabled = ready,
                                onComments = songId?.let { { onSongComments(it) } },
                            ) { onChoose(artist.hotSongs, index) }
                        }
                    }
                }
                ArtistSection.Albums -> {
                    items(albums, key = { "album/${it.id}" }) { album ->
                        ArtistAlbumRow(album) { onOpenAlbum(album.id) }
                    }
                    when {
                        albumFailed -> item {
                            ArtistStatus("暂时无法加载专辑", onRetry = ::loadAlbums)
                        }
                        requestedOffset != null || nextOffset == 0 -> item {
                            LinearWavyProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 12.dp))
                        }
                        albums.isEmpty() -> item { ArtistStatus("暂无专辑") }
                        nextOffset != null -> item {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                FilledTonalButton(onClick = ::loadAlbums) { Text("加载更多专辑") }
                            }
                        }
                    }
                }
                ArtistSection.Biography -> {
                    val loaded = biography
                    val summary = loaded?.summary.orEmpty().ifBlank { artist.biography }
                    val sections = loaded?.sections.orEmpty().filterNot { it.text == summary }
                    if (summary.isNotBlank()) item(key = "bio-summary") { ArtistBioCard("", summary) }
                    items(sections) { entry -> ArtistBioCard(entry.title, entry.text) }
                    when {
                        biographyFailed -> item {
                            ArtistStatus("暂时无法加载${if (summary.isBlank()) "歌手简介" else "完整简介"}",
                                onRetry = { biographyAttempt++ })
                        }
                        loaded == null -> item {
                            LinearWavyProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 12.dp))
                        }
                        summary.isBlank() && sections.isEmpty() -> item { ArtistStatus("暂无公开的歌手简介") }
                    }
                }
                ArtistSection.Members -> {
                    val members = (biography ?: ArtistBiography("", emptyList())).memberSections(artist.biography)
                    items(members) { entry -> ArtistBioCard(entry.title, entry.text) }
                    if (members.isEmpty()) {
                        when {
                            biographyFailed -> item {
                                ArtistStatus("暂时无法加载成员资料", onRetry = { biographyAttempt++ })
                            }
                            biography == null -> item {
                                LinearWavyProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 12.dp))
                            }
                            else -> item { ArtistStatus("暂无公开的成员资料") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ArtistBioCard(title: String, text: String) {
    Card(shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (title.isNotBlank()) Text(title, style = MaterialTheme.typography.titleMedium)
            Text(text, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun ArtistHero(artist: ArtistProfile, ready: Boolean, onPlay: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val counts = listOfNotNull(artist.songCount?.let { "$it 首歌曲" }, artist.albumCount?.let { "$it 张专辑" })
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(artist.cover, Modifier.size(80.dp), radius = 40, emptyIcon = Icons.Rounded.Person)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(artist.name, style = MaterialTheme.typography.headlineSmall)
                    if (artist.aliases.isNotEmpty()) {
                        Text(artist.aliases.joinToString(" / "), style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (counts.isNotEmpty()) {
                        Text(counts.joinToString(" · "), style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant)
                    }
                }
            }
            if (artist.hotSongs.isNotEmpty()) {
                FilledTonalButton(onClick = onPlay, enabled = ready) {
                    Icon(Icons.Rounded.PlayArrow, null, Modifier.size(20.dp))
                    Text("播放热门歌曲", Modifier.padding(start = 6.dp))
                }
            }
        }
    }
}

@Composable
private fun ArtistAlbumRow(album: ArtistAlbum, onClick: () -> Unit) {
    val subtitle = listOfNotNull(album.year?.let { "${it}年" }, album.songCount?.let { "$it 首" }).joinToString(" · ")
    Surface(onClick = onClick, shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(album.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            supportingContent = if (subtitle.isNotBlank()) {{ Text(subtitle) }} else null,
            leadingContent = { Cover(album.cover, Modifier.size(48.dp), radius = 12, emptyIcon = Icons.Rounded.Album) },
            trailingContent = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
}

@Composable
private fun ArtistStatus(message: String, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (onRetry != null) FilledTonalButton(onClick = onRetry) { Text("重试") }
    }
}
