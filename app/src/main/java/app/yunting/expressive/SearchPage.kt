@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package app.yunting.expressive

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private enum class SearchScreen { Browse, History, Results }

@Composable
internal fun ExpressiveSearchPage(
    state: PlayerState,
    active: Boolean,
    focusSearch: Boolean,
    voiceRequest: Int,
    onSearch: (String, SearchKind, Boolean, Boolean) -> Unit,
    onChoose: (List<Song>, Int) -> Unit,
    onOpenResult: (String) -> Unit,
    onRemoveHistory: (String) -> Unit,
    onClearHistory: () -> Unit,
    historyOpen: Boolean,
    onHistoryOpenChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onPlaylist: (Playlist) -> Unit,
    onStyles: () -> Unit,
    onRecognition: () -> Unit,
    onVoiceConsumed: () -> Unit,
    onFocusConsumed: () -> Unit,
    bottomContentPadding: Dp = 0.dp,
) {
    val colors = MaterialTheme.colorScheme
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val suggestion = state.discovery.firstOrNull()?.title?.takeIf { it.isNotBlank() }
    var input by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf<String?>(null) }
    var kind by rememberSaveable { mutableStateOf(SearchKind.Songs) }
    var historyOpenedFromShortcut by rememberSaveable { mutableStateOf(false) }
    var voiceError by rememberSaveable { mutableStateOf<String?>(null) }
    val screenState = rememberSaveableStateHolder()
    val screen = when {
        historyOpen -> SearchScreen.History
        submitted != null -> SearchScreen.Results
        else -> SearchScreen.Browse
    }

    fun submit(text: String) {
        val query = text.trim().ifBlank { suggestion.orEmpty() }
        if (query.isBlank()) return
        voiceError = null
        input = query
        submitted = query
        kind = SearchKind.Songs
        historyOpenedFromShortcut = false
        onHistoryOpenChange(false)
        keyboard?.hide()
        focusManager.clearFocus()
        onSearch(query, SearchKind.Songs, false, true)
    }

    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val query = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull { it.isNotBlank() }
            if (query == null) voiceError = "没有识别到内容，请重试或输入关键词。" else submit(query)
        }
    }

    fun startVoiceSearch() {
        voiceError = null
        keyboard?.hide()
        focusManager.clearFocus()
        val request = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "说出歌曲或歌手")
        try {
            voice.launch(request)
        } catch (_: ActivityNotFoundException) {
            voiceError = "当前设备没有可用的语音识别服务。"
        } catch (_: SecurityException) {
            voiceError = "系统语音识别服务无法启动。"
        }
    }

    LaunchedEffect(active, focusSearch, voiceRequest) {
        if (!active) return@LaunchedEffect
        if (voiceRequest != 0) {
            startVoiceSearch()
            onVoiceConsumed()
        } else if (focusSearch) {
            historyOpenedFromShortcut = true
            input = ""
            submitted = null
            kind = SearchKind.Songs
            focus.requestFocus()
            keyboard?.show()
        }
        if (focusSearch) onFocusConsumed()
    }
    LaunchedEffect(active, submitted, kind) {
        val query = submitted ?: return@LaunchedEffect
        if (active && (state.searchQuery != query || kind !in state.searchResults)) {
            onSearch(query, kind, false, false)
        }
    }

    fun openHistoryFromField() {
        if (!historyOpen) historyOpenedFromShortcut = false
        onHistoryOpenChange(true)
    }

    fun focusHistory() {
        openHistoryFromField()
        focus.requestFocus()
        keyboard?.show()
    }

    fun clearInput() {
        input = ""
        voiceError = null
        focusHistory()
    }

    fun closeHistory() {
        val returnToPreviousPage = historyOpenedFromShortcut
        historyOpenedFromShortcut = false
        input = submitted.orEmpty()
        voiceError = null
        onHistoryOpenChange(false)
        keyboard?.hide()
        focusManager.clearFocus()
        if (returnToPreviousPage) onBack()
    }

    BackHandler(enabled = active && screen != SearchScreen.Browse) {
        if (screen == SearchScreen.History) {
            closeHistory()
        } else {
            input = ""
            submitted = null
            kind = SearchKind.Songs
            voiceError = null
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val gutter = if (maxWidth < 360.dp) 12.dp else 16.dp
        val hasQuery = input.isNotEmpty()
        val fontScale = LocalDensity.current.fontScale
        val clearOutsideField = hasQuery && !historyOpen &&
            (maxWidth < 320.dp || (maxWidth < 360.dp && fontScale >= 1.3f))

        Column(Modifier.widthIn(max = 720.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(start = gutter, end = gutter, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (historyOpen) {
                    IconButton(onClick = ::closeHistory) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") }
                }
                TextField(
                    value = input,
                    onValueChange = {
                        input = it
                        voiceError = null
                    },
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp)
                        .focusRequester(focus).onFocusChanged { if (it.isFocused && active) openHistoryFromField() },
                    singleLine = true,
                    shape = CircleShape,
                    placeholder = {
                        Text(suggestion ?: "搜索歌曲、歌手或专辑",
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    leadingIcon = {
                        IconButton(
                            onClick = { if (historyOpen) submit(input) else focusHistory() },
                            enabled = !historyOpen || input.isNotBlank() || suggestion != null,
                        ) {
                            Icon(Icons.Rounded.Search, if (historyOpen) "搜索" else "打开搜索历史")
                        }
                    },
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (hasQuery && !clearOutsideField) {
                                IconButton(onClick = ::clearInput) { Icon(Icons.Rounded.Close, "清除搜索") }
                            }
                            IconButton(onClick = ::startVoiceSearch) { Icon(Icons.Rounded.Mic, "语音搜索") }
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { submit(input) }),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = colors.surfaceContainerHigh,
                        unfocusedContainerColor = colors.surfaceContainerHigh,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                )
                if (clearOutsideField) {
                    IconButton(onClick = ::clearInput) { Icon(Icons.Rounded.Close, "清除搜索") }
                }
            }
            voiceError?.let {
                Text(it, Modifier.padding(horizontal = gutter), color = colors.error, style = MaterialTheme.typography.bodyMedium)
            }
            AnimatedContent(
                targetState = screen,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                transitionSpec = {
                    val direction = if (targetState.ordinal > initialState.ordinal) SlideDirection.Left else SlideDirection.Right
                    (fadeIn(tween(220)) + slideIntoContainer(direction, tween(240)) { it / 10 }) togetherWith
                        (fadeOut(tween(160)) + slideOutOfContainer(direction, tween(240)) { it / 10 })
                },
                label = "search screen",
            ) { visibleScreen ->
                screenState.SaveableStateProvider(visibleScreen.name) {
                    when (visibleScreen) {
                        SearchScreen.Results -> Column(Modifier.fillMaxSize()) {
                            SecondaryTabRow(selectedTabIndex = kind.ordinal, containerColor = Color.Transparent) {
                                SearchKind.entries.forEach { tab ->
                                    Tab(selected = kind == tab, onClick = { kind = tab }, text = {
                                        Text(tab.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    })
                                }
                            }
                            val query = submitted ?: state.searchQuery
                            key(query, kind) {
                                SearchResults(state, query, kind, gutter, bottomContentPadding, onChoose, onOpenResult) {
                                    query?.let { onSearch(it, kind, true, false) }
                                }
                            }
                        }
                        SearchScreen.History -> Column(
                            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                                .padding(start = gutter, end = gutter, top = 4.dp, bottom = 16.dp + bottomContentPadding),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            if (state.searchHistory.isEmpty()) {
                                Text("搜索历史", style = MaterialTheme.typography.titleLarge)
                                Text("暂无搜索历史", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                            } else {
                                SearchHistoryPanel(state.searchHistory, ::submit, onRemoveHistory, onClearHistory)
                            }
                        }
                        SearchScreen.Browse -> CuratedPage(
                            onBack, onPlaylist, active && screen == SearchScreen.Browse,
                            bottomContentPadding = bottomContentPadding,
                        ) {
                            DiscoveryEntries(onStyles, onRecognition)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiscoveryEntries(onStyles: () -> Unit, onRecognition: () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(onClick = onStyles) { Text("曲风") }
        FilledTonalButton(onClick = onRecognition) { Text("听歌识曲") }
    }
}

@Composable
private fun SearchResults(
    state: PlayerState,
    submitted: String?,
    kind: SearchKind,
    gutter: Dp,
    bottomContentPadding: Dp,
    onChoose: (List<Song>, Int) -> Unit,
    onOpenResult: (String) -> Unit,
    onRetry: () -> Unit,
) {
    val page = state.searchResults[kind].takeIf { submitted == state.searchQuery }
    val colors = MaterialTheme.colorScheme
    val songs = page?.songs.orEmpty()
    val entries = page?.entries.orEmpty()
    when {
        page == null || page.loading -> Box(
            Modifier.fillMaxSize().padding(horizontal = gutter, vertical = 8.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            LinearWavyProgressIndicator(Modifier.fillMaxWidth(), color = colors.primary, trackColor = colors.secondaryContainer)
        }
        page.error != null || (songs.isEmpty() && entries.isEmpty()) -> Box(
            Modifier.fillMaxSize().padding(start = gutter, end = gutter, bottom = bottomContentPadding),
            contentAlignment = Alignment.Center,
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(page.error ?: "没有找到匹配的${kind.label}", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                if (page.error != null) FilledTonalButton(onClick = onRetry) { Text("重试") }
            }
        }
        else -> LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = gutter, end = gutter, top = 16.dp, bottom = 16.dp + bottomContentPadding),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            itemsIndexed(songs, key = { _, song -> song.id }) { index, song ->
                val subtitle = listOf(song.artist, song.album).filter(String::isNotBlank).joinToString(" · ")
                SearchResultRow(song.title, subtitle, song.cover, Icons.Rounded.Person,
                    index == 0, index == songs.lastIndex, song.id == state.now?.id, state.ready) {
                    onChoose(songs, index)
                }
            }
            itemsIndexed(entries, key = { _, entry -> entry.route }) { index, entry ->
                val fallback = if (kind == SearchKind.Artists) Icons.Rounded.Person else Icons.Rounded.Album
                SearchResultRow(entry.title, entry.subtitle, entry.cover, fallback,
                    index == 0, index == entries.lastIndex) {
                    onOpenResult(entry.route)
                }
            }
        }
    }
}

@Composable
private fun SearchResultRow(
    title: String, subtitle: String, cover: String, fallback: ImageVector,
    first: Boolean, last: Boolean, active: Boolean = false, enabled: Boolean = true, onClick: () -> Unit,
) {
    val top = if (first) 28.dp else 8.dp
    val bottom = if (last) 28.dp else 8.dp
    val colors = MaterialTheme.colorScheme
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.98f else 1f, tween(110), label = "result press")
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(topStart = top, topEnd = top, bottomEnd = bottom, bottomStart = bottom),
        color = if (active) colors.secondaryContainer else colors.surfaceContainerLow,
        interactionSource = interactions,
        modifier = Modifier.fillMaxWidth().graphicsLayer {
            scaleX = scale
            scaleY = scale
        },
    ) {
        ListItem(
            headlineContent = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            supportingContent = subtitle.takeIf(String::isNotBlank)?.let { details ->
                { Text(details, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            },
            leadingContent = { Cover(cover, Modifier.size(40.dp), radius = 20, emptyIcon = fallback) },
            trailingContent = {
                if (active) Icon(Icons.Rounded.GraphicEq, "当前歌曲")
                else Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null)
            },
            colors = ListItemDefaults.colors(
                containerColor = Color.Transparent,
                headlineColor = if (active) colors.onSecondaryContainer else colors.onSurface,
                supportingColor = if (active) colors.onSecondaryContainer else colors.onSurfaceVariant,
                trailingIconColor = if (active) colors.onSecondaryContainer else colors.onSurfaceVariant,
            ),
            modifier = Modifier.heightIn(min = 72.dp),
        )
    }
}
