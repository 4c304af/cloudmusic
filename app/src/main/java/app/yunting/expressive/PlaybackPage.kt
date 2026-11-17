@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package app.yunting.expressive

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowBackIos
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.abs

@Composable
internal fun PlaybackPage(
    state: PlayerState,
    model: PlayerModel,
    wide: Boolean,
    lyricsExpanded: Boolean,
    onOpenLyrics: () -> Unit,
    browse: () -> Unit,
    onCollapse: () -> Unit,
    onDetails: (Long) -> Unit,
    onAlbum: (Long) -> Unit,
    onArtist: (Long) -> Unit,
    onArtistCredits: (Song) -> Unit,
    onComments: (Long) -> Unit,
    onLogin: () -> Unit,
) {
    val song = state.now
    val songId = song?.id?.toLongOrNull()?.takeIf { it > 0 }
    val songActions = rememberPlayerSongActions(song, state.account, model::refreshUserPlaylists, onLogin)
    var showQueue by rememberSaveable { mutableStateOf(false) }
    var showMiniLyrics by rememberSaveable { mutableStateOf(false) }
    var landscapePanel by rememberSaveable { mutableIntStateOf(0) }
    var position by remember(song?.id) { mutableLongStateOf(model.position()) }
    LaunchedEffect(song?.id) {
        if (song == null) return@LaunchedEffect
        while (true) {
            position = model.position()
            delay(120)
        }
    }
    val openComments: (() -> Unit)? = songId?.let { id -> { onComments(id) } }
    val seek: (Long) -> Unit = {
        position = it
        model.seek(it)
    }

    Column(Modifier.fillMaxSize().semantics { paneTitle = if (lyricsExpanded) "全屏歌词" else "正在播放" }) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            FilledTonalIconButton(onClick = onCollapse, modifier = Modifier.size(56.dp)) {
                if (lyricsExpanded) Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回播放页")
                else Icon(Icons.Rounded.ArrowDownward, "收起播放页")
            }
            if (lyricsExpanded) {
                Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                    Text("歌词", style = MaterialTheme.typography.titleLarge)
                    song?.let {
                        Text(it.title, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            PlayerOverflowMenu(
                song = song,
                onOpenDetails = { songId?.let(onDetails) },
                onOpenQueue = { showQueue = true },
                onAdjustLyrics = onOpenLyrics,
                modifier = Modifier.size(56.dp),
                onOpenAlbum = onAlbum,
                onOpenArtist = songId?.let { { onArtistCredits(song) } },
                onAddToPlaylist = songActions.onAddToPlaylist.takeIf { songActions.addEnabled },
                onFavorite = songActions.onFavorite.takeIf { songActions.favoriteEnabled },
                isFavorite = songActions.isFavorite,
                onOpenComments = openComments,
                miniLyricsVisible = showMiniLyrics,
                onToggleMiniLyrics = { showMiniLyrics = !showMiniLyrics },
                onChooseSimilar = model::play,
                onInsertSimilar = model::insertSimilarNext,
                onStartSimilar = model::startSimilarRoaming,
            )
        }
        Crossfade(
            targetState = lyricsExpanded,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            animationSpec = tween(180),
            label = "full-screen lyrics",
        ) { showLyrics ->
            when {
                song == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Icon(Icons.Rounded.GraphicEq, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(state.playbackError ?: "播放队列为空", style = MaterialTheme.typography.titleLarge)
                        Button(onClick = browse) { Text("搜索歌曲") }
                    }
                }
                showLyrics -> FullScreenLyrics(song, state, position, model, seek)
                wide -> Row(
                    Modifier.fillMaxSize().padding(horizontal = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                        val coverSize = minOf(maxWidth, 350.dp, (maxHeight - 185.dp).coerceAtLeast(112.dp))
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                            PlayerArtwork(song, showMiniLyrics, state, position, coverSize, onOpenLyrics)
                            PlayerMetadata(song, state, onArtist,
                                Modifier.widthIn(max = 350.dp).fillMaxWidth().padding(top = 6.dp))
                            PlayerTimeline(song.id, position, state, seek, Modifier.fillMaxWidth())
                            PlayerTransport(state, model, 56.dp, Modifier.fillMaxWidth())
                        }
                    }
                    Column(Modifier.weight(1.1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                        PanelTabs(landscapePanel) { landscapePanel = it }
                        Box(Modifier.weight(1f)) { PlayerPanel(landscapePanel, state, position, model) }
                    }
                }
                else -> BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    val coverWidth = (maxWidth - 40.dp).coerceIn(1.dp, 350.dp)
                    val coverSize = minOf(coverWidth, (maxHeight - 432.dp).coerceAtLeast(144.dp))
                    val transportHeight = when {
                        maxHeight >= 725.dp -> 96.dp
                        maxHeight >= 585.dp -> 72.dp
                        else -> 56.dp
                    }
                    Column(
                        Modifier.widthIn(max = 460.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                            .padding(start = 10.dp, end = 10.dp, top = 12.dp, bottom = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        PlayerArtwork(song, showMiniLyrics, state, position, coverSize, onOpenLyrics)
                        PlayerMetadata(song, state, onArtist,
                            Modifier.widthIn(max = 350.dp).fillMaxWidth().padding(top = 6.dp))
                        PlayerTimeline(song.id, position, state, seek,
                            Modifier.widthIn(max = 370.dp).fillMaxWidth())
                        PlayerTransport(state, model, transportHeight, Modifier.fillMaxWidth())
                        Spacer(Modifier.height(24.dp))
                        PlayerFeaturedComment(songId, { openComments?.invoke() },
                            Modifier.widthIn(max = 360.dp).fillMaxWidth().heightIn(min = 92.dp))
                    }
                }
            }
        }
    }
    if (showQueue) {
        ModalBottomSheet(onDismissRequest = { showQueue = false }) {
            Column(Modifier.fillMaxWidth().fillMaxHeight(0.68f)) {
                PageHeading("播放队列", { showQueue = false })
                Box(Modifier.weight(1f)) { PlayerPanel(1, state, position, model) }
            }
        }
    }
}

@Composable
private fun FullScreenLyrics(
    song: Song,
    state: PlayerState,
    position: Long,
    model: PlayerModel,
    onSeek: (Long) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.weight(1f).widthIn(max = 820.dp).fillMaxWidth()) {
            PlayerPanel(0, state, position, model, fullScreen = true)
        }
        Row(
            Modifier.widthIn(max = 620.dp).fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FilledTonalIconButton(onClick = model::toggle) {
                Icon(if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (state.playing) "暂停" else "播放")
            }
            PlayerTimeline(song.id, position, state, onSeek, Modifier.weight(1f))
        }
    }
}

@Composable
private fun PlayerArtwork(
    song: Song,
    showMiniLyrics: Boolean,
    state: PlayerState,
    position: Long,
    side: Dp,
    onOpenLyrics: () -> Unit,
) {
    Box(
        Modifier.size(side).clip(RoundedCornerShape(32.dp))
            .clickable(role = Role.Button, onClickLabel = "显示全屏歌词", onClick = onOpenLyrics)
            .semantics { contentDescription = "专辑封面" },
    ) {
        Cover(song.cover, Modifier.fillMaxSize(), radius = 32, emptyIcon = Icons.Rounded.Image)
        val line = if (showMiniLyrics) state.lyrics.lastOrNull { it.timeMs + state.lyricDelay <= position } else null
        if (line != null) {
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp).fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f),
                shape = MaterialTheme.shapes.large,
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(line.text, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (line.translation.isNotBlank()) {
                        Text(line.translation, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerMetadata(song: Song, state: PlayerState, onArtist: (Long) -> Unit, modifier: Modifier) {
    Column(modifier) {
        Text(song.title, fontSize = 28.sp, lineHeight = 36.sp)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            PlayerArtist(song, onArtist)
            PlayerOutputDevice(state.account)
        }
        state.playbackError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun PlayerTransport(state: PlayerState, model: PlayerModel, height: Dp, modifier: Modifier) {
    Row(modifier.height(height), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        val dimensions = Modifier.weight(1f).fillMaxHeight()
        val size = if (height >= 90.dp) 28.dp else 24.dp
        PlayerGroupButton(0, 4, model::cycleMode, dimensions) {
            val labels = listOf("列表循环", "随机播放", "单曲循环")
            val icons = listOf(Icons.Rounded.Refresh, Icons.Rounded.Shuffle, Icons.Rounded.RepeatOne)
            Icon(icons[state.mode], labels[state.mode], Modifier.size(size))
        }
        PlayerGroupButton(1, 4, model::previous, dimensions) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBackIos, "上一首", Modifier.size(size))
        }
        PlayerGroupButton(2, 4, model::toggle, dimensions) {
            AnimatedContent(state.playing, label = "play or pause") { playing ->
                Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "暂停" else "播放", Modifier.size(size))
            }
        }
        PlayerGroupButton(3, 4, model::next, dimensions) {
            Icon(Icons.AutoMirrored.Rounded.ArrowForwardIos, "下一首", Modifier.size(size))
        }
    }
}

@Composable
private fun PlayerGroupButton(
    index: Int,
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val shapes = when (index) {
        0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
        count - 1 -> ButtonGroupDefaults.connectedTrailingButtonShapes()
        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
    }
    FilledTonalIconButton(
        onClick = onClick,
        shapes = IconButtonShapes(shapes.shape, shapes.pressedShape),
        modifier = modifier,
        enabled = enabled,
        content = content,
    )
}

@Composable
private fun PlayerTimeline(track: String, position: Long, state: PlayerState, onSeek: (Long) -> Unit, modifier: Modifier) {
    var drag by remember(track) { mutableStateOf<Float?>(null) }
    var releasedSeek by remember(track) { mutableStateOf<Float?>(null) }
    val duration = state.duration
    val liveRatio = if (duration > 0L) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val smoothRatio by animateFloatAsState(liveRatio, tween(140, easing = LinearEasing), label = "timeline progress")
    LaunchedEffect(releasedSeek) {
        if (releasedSeek != null) {
            delay(160)
            releasedSeek = null
        }
    }
    val seekRatio = drag ?: releasedSeek
    val ratio = seekRatio ?: smoothRatio
    val slider = remember(track) { SliderState(value = liveRatio) }
    SideEffect { slider.value = ratio }
    val stroke = with(LocalDensity.current) { Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round) }
    val shownPosition = seekRatio?.let { (it * duration).toLong() } ?: position.coerceIn(0L, duration)
    val colors = MaterialTheme.colorScheme
    Column(modifier) {
        Slider(
            state = slider,
            onValueChange = { drag = it; releasedSeek = null },
            onValueChangeFinished = {
                drag?.let {
                    releasedSeek = it
                    onSeek((it * duration).toLong())
                }
                drag = null
            },
            enabled = duration > 0,
            modifier = Modifier.fillMaxWidth().height(30.dp).semantics {
                contentDescription = "歌曲进度"
                stateDescription = "${formatTime(shownPosition)} / ${formatTime(duration)}"
            },
            thumb = {
                val color = if (drag != null) colors.primary else Color.Transparent
                Box(Modifier.size(8.dp).background(color, CircleShape))
            },
            track = {
                LinearWavyProgressIndicator(
                    progress = { ratio },
                    modifier = Modifier.fillMaxWidth().height(16.dp).clearAndSetSemantics { },
                    color = colors.primary,
                    trackColor = colors.secondaryContainer,
                    stroke = stroke,
                    trackStroke = stroke,
                    gapSize = 5.dp,
                    stopSize = 4.dp,
                    amplitude = { if (drag == null) 0.65f else 0f },
                    waveSpeed = if (state.playing) 32.dp else 0.dp,
                )
            },
        )
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(shownPosition), fontSize = 10.sp, lineHeight = 12.sp, color = colors.onSurfaceVariant)
            Text(formatTime(duration), fontSize = 10.sp, lineHeight = 12.sp, color = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun PanelTabs(selected: Int, onChange: (Int) -> Unit) {
    SecondaryTabRow(selectedTabIndex = selected, containerColor = Color.Transparent) {
        Tab(selected == 0, { onChange(0) }, text = { Text("歌词") })
        Tab(selected == 1, { onChange(1) }, text = { Text("队列") })
    }
}

@Composable
private fun PlayerPanel(panel: Int, state: PlayerState, position: Long, model: PlayerModel, fullScreen: Boolean = false) {
    if (panel == 1) {
        Songs(state.queue, state.now?.id, state.ready, false, null, "播放队列为空", onChoose = model::select)
        return
    }
    val index = state.lyrics.indexOfLast { it.timeMs + state.lyricDelay <= position }
    val list = key(state.now?.id) { rememberLazyListState() }
    LaunchedEffect(index, state.now?.id) { if (index >= 0) list.animateScrollToItem((index - 1).coerceAtLeast(0)) }
    Column(Modifier.fillMaxSize()) {
        if (state.lyrics.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text("暂无同步歌词") }
        } else {
            LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = if (fullScreen) 20.dp else 10.dp),
                verticalArrangement = Arrangement.spacedBy(if (fullScreen) 20.dp else 12.dp)) {
                itemsIndexed(state.lyrics) { lineIndex, line ->
                    val active = lineIndex == index
                    val color by animateColorAsState(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, label = "lyric line")
                    Column(Modifier.fillMaxWidth().clickable { model.seek(line.timeMs + state.lyricDelay) }) {
                        Text(line.text, color = color, fontSize = if (fullScreen) 25.sp else 21.sp,
                            lineHeight = if (fullScreen) 35.sp else 30.sp,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium)
                        if (line.translation.isNotBlank()) {
                            Text(line.translation, fontSize = if (fullScreen) 16.sp else 13.sp,
                                lineHeight = if (fullScreen) 24.sp else 20.sp, color = color)
                        }
                    }
                }
            }
        }
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { model.setLyricDelay(state.lyricDelay - 100) }) { Text("提前") }
            TextButton(onClick = { model.setLyricDelay(0) }) {
                val ms = state.lyricDelay
                Text(if (ms == 0) "同步" else "${if (ms > 0) "+" else "−"}${abs(ms)} ms")
            }
            TextButton(onClick = { model.setLyricDelay(state.lyricDelay + 100) }) { Text("延后") }
        }
    }
}

private fun formatTime(ms: Long): String {
    val seconds = ms / 1_000
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}
