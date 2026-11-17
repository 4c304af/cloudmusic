@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package app.yunting.expressive

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.carousel.HorizontalMultiBrowseCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import kotlin.math.ceil
import kotlin.math.floor

@Composable
internal fun ExpressiveHomePage(
    state: PlayerState,
    onAccount: () -> Unit,
    onSettings: () -> Unit,
    onSearch: () -> Unit,
    onVoiceSearch: () -> Unit,
    onDaily: () -> Unit,
    onSong: (List<Song>, Int) -> Unit,
    onFeatured: (FeaturedPick) -> Unit,
    onRefreshFeatured: () -> Unit,
    bottomContentPadding: Dp = 0.dp,
) {
    val inset = Modifier.padding(horizontal = 16.dp)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 12.dp, bottom = 12.dp + bottomContentPadding),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { HomeHeader(state.account, onAccount, onSettings, inset.fillMaxWidth()) }
        item { HomeSearchBar(inset.fillMaxWidth(), onSearch, onVoiceSearch) }
        item { ContinueListening(state, onDaily, onSong, inset.fillMaxWidth()) }
        if (state.discovery.isNotEmpty()) {
            item {
                Text("为您定制", inset, style = homeSectionStyle())
            }
            item {
                HomeCarousel(state.discovery, 156.dp, Song::title, Song::cover, enabled = state.ready) { index ->
                    onSong(state.discovery, index)
                }
            }
        }
        item { Text("专属精选", inset, style = homeSectionStyle()) }
        item {
            if (state.featuredPicks.isNotEmpty()) {
                HomeCarousel(state.featuredPicks, 140.dp, FeaturedPick::title, FeaturedPick::cover) { index ->
                    onFeatured(state.featuredPicks[index])
                }
            } else {
                FeaturedStatus(state, onAccount, onRefreshFeatured, inset.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun homeSectionStyle() = MaterialTheme.typography.headlineSmall.copy(fontSize = 28.sp, lineHeight = 36.sp)

@Composable
private fun ContinueListening(
    state: PlayerState,
    onDaily: () -> Unit,
    onSong: (List<Song>, Int) -> Unit,
    modifier: Modifier,
) {
    BoxWithConstraints(modifier) {
        val minWidth = 290.dp + 200.dp * (LocalDensity.current.fontScale - 1f).coerceAtLeast(0f)
        if (maxWidth >= minWidth) {
            Row(
                Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RecentSongs(state, onSong, Modifier.weight(1.18f).fillMaxHeight())
                DailyCard(state, onDaily, Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                RecentSongs(state, onSong, Modifier.fillMaxWidth())
                DailyCard(state, onDaily, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun RecentSongs(state: PlayerState, onSong: (List<Song>, Int) -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val recent = state.recent.take(2)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("接着听", style = homeSectionStyle())
        if (recent.isEmpty()) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = colors.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
            ) {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.History, null, tint = colors.onSurfaceVariant)
                    Text("暂无播放记录", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }
        } else {
            recent.forEachIndexed { index, song ->
                val top = if (index == 0) 24.dp else 8.dp
                val bottom = if (index == recent.lastIndex) 24.dp else 8.dp
                Surface(
                    onClick = { onSong(state.recent, index) }, enabled = state.ready,
                    shape = RoundedCornerShape(top, top, bottom, bottom),
                    color = colors.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 70.dp),
                ) {
                    Row(
                        Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Cover(song, Modifier.size(38.dp), radius = 19)
                        Column(Modifier.weight(1f)) {
                            Text(song.title, style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (song.artist.isNotBlank()) Text(song.artist, style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, Modifier.size(18.dp),
                            tint = colors.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeHeader(account: Account?, onAccount: () -> Unit, onSettings: () -> Unit, modifier: Modifier) {
    BoxWithConstraints(modifier) {
        val compact = maxWidth < 320.dp
        val buttonSize = if (compact) 48.dp else 56.dp
        val name = account?.name?.takeIf(String::isNotBlank)
        Row(horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (account == null) {
                FilledTonalIconButton(onClick = onAccount, modifier = Modifier.size(buttonSize)) {
                    Icon(Icons.Rounded.Person, "登录网易云音乐")
                }
            } else {
                FilledTonalIconButton(
                    onClick = onAccount,
                    modifier = Modifier.size(buttonSize).semantics { contentDescription = "个人中心" },
                ) {
                    if (account.avatar.isBlank()) {
                        Icon(Icons.Rounded.Person, null)
                    } else {
                        Cover(account.avatar, Modifier.size(buttonSize), radius = 28, emptyIcon = Icons.Rounded.Person)
                    }
                }
            }
            Text(
                text = if (name == null) "欢迎来到云听" else "$name，欢迎回来",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 19.sp, lineHeight = 26.sp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            FilledTonalIconButton(onClick = onSettings, modifier = Modifier.size(buttonSize)) {
                Icon(Icons.Rounded.Settings, "设置")
            }
        }
    }
}

@Composable
private fun HomeSearchBar(modifier: Modifier, onSearch: () -> Unit, onVoiceSearch: () -> Unit) {
    Surface(modifier = modifier, shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier.weight(1f).heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onSearch)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("搜索", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onVoiceSearch, modifier = Modifier.padding(end = 4.dp)) {
                Icon(Icons.Rounded.Mic, "语音搜索")
            }
        }
    }
}

@Composable
private fun DailyCard(state: PlayerState, onOpen: () -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val day = LocalDate.now()
    val status = when {
        state.discovering -> "正在加载"
        state.account == null -> "登录查看推荐"
        state.discoveryError != null -> state.discoveryError
        state.discovery.isEmpty() -> "暂无推荐歌曲"
        else -> "${day.monthValue}月${day.dayOfMonth}日 · ${state.discovery.size}\u00a0首"
    }
    Card(onClick = onOpen, modifier = modifier.heightIn(min = 176.dp), shape = RoundedCornerShape(32.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerHigh, contentColor = colors.onSurface)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val coverSong = state.discovery.firstOrNull { it.cover.isNotBlank() }
            if (coverSong != null) {
                Cover(coverSong, Modifier.fillMaxWidth().height(76.dp), radius = 20)
            } else {
                Surface(modifier = Modifier.fillMaxWidth().height(76.dp), shape = RoundedCornerShape(20.dp),
                    color = colors.primaryContainer, contentColor = colors.onPrimaryContainer) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.CalendarToday, null, Modifier.size(28.dp))
                    }
                }
            }
            Text("今日为你推荐", style = MaterialTheme.typography.titleMedium)
            Text(status, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            if (state.discovering) {
                LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp),
                    color = colors.primary, trackColor = colors.secondaryContainer)
            }
        }
    }
}

@Composable
private fun FeaturedStatus(state: PlayerState, onAccount: () -> Unit, onRetry: () -> Unit, modifier: Modifier) {
    Surface(modifier, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when {
                state.loadingFeatured -> {
                    Text("正在加载专属精选", style = MaterialTheme.typography.bodyMedium)
                    LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                }
                state.account == null -> TextButton(onClick = onAccount) { Text("登录查看专属精选") }
                state.featuredError -> {
                    Text("暂时无法加载专属精选", style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = onRetry) { Text("重试") }
                }
                else -> Text("暂无专属精选", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun <T> HomeCarousel(
    entries: List<T>,
    height: Dp,
    title: (T) -> String,
    cover: (T) -> String,
    enabled: Boolean = true,
    onOpen: (Int) -> Unit,
) {
    val carousel = rememberCarouselState { minOf(4, entries.size) }
    val shape = RoundedCornerShape(24.dp)
    val titleStyle = MaterialTheme.typography.titleSmall
    val extraHeight = with(LocalDensity.current) { (titleStyle.lineHeight.toDp() - 20.dp).coerceAtLeast(0.dp) }
    HorizontalMultiBrowseCarousel(
        state = carousel,
        preferredItemWidth = 216.dp,
        modifier = Modifier.fillMaxWidth().height(height + extraHeight),
        itemSpacing = 8.dp,
        contentPadding = PaddingValues(horizontal = 16.dp),
    ) { index ->
        val entry = entries.getOrNull(index) ?: return@HorizontalMultiBrowseCarousel
        val label = title(entry)
        val drawInfo = carouselItemDrawInfo
        Card(
            onClick = { onOpen(index) },
            enabled = enabled,
            modifier = Modifier.fillMaxSize().maskClip(shape).semantics { contentDescription = label },
            shape = shape,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            Cover(cover(entry), Modifier.weight(1f).fillMaxWidth(), radius = 16)
            Text(
                label,
                modifier = Modifier
                    .layout { measurable, constraints ->
                        // Size and position the title inside the visible mask so scrolling never crops its start.
                        val mask = drawInfo.maskRect
                        val width = constraints.maxWidth
                        val left = if (mask.isEmpty) 0 else ceil(mask.left).toInt().coerceIn(0, width)
                        val right = if (mask.isEmpty) width else floor(mask.right).toInt().coerceIn(left, width)
                        val visibleWidth = right - left
                        val title = measurable.measure(constraints.copy(minWidth = visibleWidth, maxWidth = visibleWidth))
                        val showTitle = visibleWidth >= 56.dp.roundToPx()
                        layout(width, title.height) { if (showTitle) title.place(left, 0) }
                    }
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .clearAndSetSemantics {},
                style = titleStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
