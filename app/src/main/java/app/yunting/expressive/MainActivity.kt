@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package app.yunting.expressive

import android.os.Bundle
import android.graphics.Bitmap
import android.content.Intent
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Comment
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private var widgetDestination by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        widgetDestination = intent.action
        setContent { YuntingTheme { MusicWorkspace(widgetDestination, { widgetDestination = null }) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        widgetDestination = intent.action
    }
}

private enum class Destination(val label: String, val icon: ImageVector) {
    Discover("首页", Icons.Rounded.Home),
    Search("搜索", Icons.Rounded.Search),
    Personal("个人", Icons.Rounded.Person),
    Library("歌单", Icons.Rounded.QueueMusic),
}

private enum class Page {
    Discover, Search, Personal, Library, Daily, Recent, Settings, Player, Login, Detail
}

@Composable
private fun MusicWorkspace(widgetDestination: String?, consumeDestination: () -> Unit, model: PlayerModel = viewModel()) {
    val state by model.state.collectAsState()
    var page by rememberSaveable { mutableStateOf(Destination.Discover) }
    var about by rememberSaveable { mutableStateOf(false) }
    var overlay by rememberSaveable { mutableStateOf<Page?>(null) }
    var returnTo by rememberSaveable { mutableStateOf<Page?>(null) }
    var focusSearch by rememberSaveable { mutableStateOf(false) }
    var searchHistoryOpen by rememberSaveable { mutableStateOf(false) }
    var voiceRequest by rememberSaveable { mutableIntStateOf(0) }
    var details by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var playingOverDetails by rememberSaveable { mutableStateOf(false) }
    var playerReturnDepths by rememberSaveable { mutableStateOf(arrayListOf<Int>()) }
    var playerShowsLyrics by rememberSaveable { mutableStateOf(false) }
    val saveablePages = rememberSaveableStateHolder()
    val detail = details.lastOrNull()
    val current = when {
        playingOverDetails -> Page.Player
        detail != null -> Page.Detail
        else -> overlay ?: Page.valueOf(page.name)
    }
    val searchingHistory = current == Page.Search && searchHistoryOpen
    val showNav = current != Page.Player && current != Page.Login && detail != "login" && !searchingHistory

    fun openDetail(route: String) {
        playingOverDetails = false
        if (details.lastOrNull() != route) details = ArrayList(details + route)
    }

    fun openDetailFromPlayer(route: String) {
        if (playingOverDetails) {
            playerReturnDepths = ArrayList(playerReturnDepths + details.size)
            details = ArrayList(details + route)
            playingOverDetails = false
        } else {
            openDetail(route)
        }
    }

    fun openSongArtist(song: Song) {
        val artistId = song.artists.singleOrNull()?.id ?: song.artistId.takeIf {
            song.artists.isEmpty() && !song.artist.contains(" / ") && it > 0L
        }
        if (artistId != null) {
            openDetailFromPlayer("artist/$artistId")
        } else {
            song.id.toLongOrNull()?.takeIf { it > 0L }?.let { openDetailFromPlayer("song-artists/$it") }
        }
    }

    fun goBack() {
        when {
            current == Page.Player && playerShowsLyrics -> playerShowsLyrics = false
            playingOverDetails -> playingOverDetails = false
            details.isNotEmpty() -> {
                details = ArrayList(details.dropLast(1))
                if (playerReturnDepths.lastOrNull() == details.size) {
                    playerReturnDepths = ArrayList(playerReturnDepths.dropLast(1))
                    playingOverDetails = true
                }
            }
            overlay != null -> {
                if (overlay == Page.Login) model.refreshAccount()
                overlay = returnTo
                returnTo = null
            }
            page == Destination.Library && state.playlist != null -> model.closePlaylist()
            else -> page = Destination.Discover
        }
    }

    fun navigate(target: Destination) {
        if (target == Destination.Library && target == page) model.closePlaylist()
        focusSearch = false
        searchHistoryOpen = false
        voiceRequest = 0
        overlay = null
        returnTo = null
        details = arrayListOf()
        playingOverDetails = false
        playerReturnDepths = arrayListOf()
        playerShowsLyrics = false
        page = target
    }

    fun search(voice: Boolean) {
        navigate(Destination.Search)
        if (voice) {
            voiceRequest = 1
        } else {
            searchHistoryOpen = true
            focusSearch = true
        }
    }

    LaunchedEffect(widgetDestination) {
        when (widgetDestination) {
            "app.yunting.RECENT" -> {
                model.closePlaylist()
                navigate(Destination.Library)
                overlay = Page.Recent
            }
            "app.yunting.PLAYER" -> {
                if (details.isNotEmpty()) playingOverDetails = true else if (overlay != Page.Player) {
                    returnTo = overlay
                    overlay = Page.Player
                }
            }
        }
        if (widgetDestination != null) consumeDestination()
    }
    BackHandler(details.isNotEmpty() || overlay != null || page != Destination.Discover) { goBack() }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { model.importAudio(it) }
    val chooseSong: (List<Song>, Int) -> Unit = { songs, index ->
        model.play(songs, index)
        if (details.isNotEmpty()) {
            playingOverDetails = true
        } else if (overlay != Page.Player) {
            returnTo = overlay
            overlay = Page.Player
        }
    }
    val showPlayer = {
        if (details.isNotEmpty()) {
            playingOverDetails = true
        } else {
            returnTo = overlay
            overlay = Page.Player
        }
    }

    val safeArea = WindowInsets.safeDrawing
    val bottomInsets = WindowInsets.navigationBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Bottom)
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    BoxWithConstraints(
        Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(safeArea.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
    ) {
        val wide = maxWidth > maxHeight
        Row(Modifier.fillMaxSize()) {
            if (wide && current != Page.Login && detail != "login" && !keyboardVisible && !searchingHistory &&
                !(current == Page.Player && playerShowsLyrics)
            ) {
                NavigationRail(
                    modifier = Modifier.fillMaxHeight().windowInsetsPadding(bottomInsets),
                    windowInsets = WindowInsets(0, 0, 0, 0),
                ) {
                    Destination.entries.forEach { destination ->
                        NavigationRailItem(selected = destination == page, onClick = { navigate(destination) },
                            icon = { Icon(destination.icon, contentDescription = null) }, label = { Text(destination.label) })
                    }
                }
            }
            Scaffold(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                containerColor = MaterialTheme.colorScheme.background,
                contentWindowInsets = safeArea.only(WindowInsetsSides.Bottom),
                bottomBar = {
                    if (showNav && !keyboardVisible && (!wide || state.now != null)) {
                        Column(Modifier.fillMaxWidth().windowInsetsPadding(bottomInsets)) {
                            NowPlayingBar(state, showPlayer, model::toggle)
                            if (!wide) FloatingNavigationBar(page, ::navigate)
                        }
                    }
                },
            ) { padding ->
                val dockPadding = padding.calculateBottomPadding()
                AnimatedContent(
                    targetState = current to detail.takeIf { current == Page.Detail },
                    modifier = Modifier.fillMaxSize().consumeWindowInsets(padding),
                    transitionSpec = {
                        val direction = if (targetState.first.ordinal > initialState.first.ordinal) 1 else -1
                        (slideInHorizontally(tween(230)) { it / 10 * direction } + fadeIn(tween(180)))
                            .togetherWith(slideOutHorizontally(tween(230)) { -it / 10 * direction } + fadeOut(tween(170)))
                    },
                    label = "page navigation",
                ) { (screen, route) ->
                    saveablePages.SaveableStateProvider(route ?: screen.name) {
                        // These scroll containers can reveal their last item above the dock independently.
                        val scrollsUnderDock = screen == Page.Discover || screen == Page.Search ||
                            screen == Page.Personal || screen == Page.Library
                        Box(Modifier.fillMaxSize().padding(bottom = if (scrollsUnderDock) 0.dp else dockPadding)) {
                            when (screen) {
                                Page.Discover -> ExpressiveHomePage(
                                    state,
                                    onAccount = { if (state.account == null) overlay = Page.Login else navigate(Destination.Personal) },
                                    onSettings = { overlay = Page.Settings },
                                    onSearch = { search(false) },
                                    onVoiceSearch = { search(true) },
                                    onDaily = { overlay = if (state.account == null) Page.Login else Page.Daily },
                                    onSong = chooseSong,
                                    onFeatured = { openDetail(it.route) },
                                    onRefreshFeatured = model::loadFeatured,
                                    bottomContentPadding = dockPadding,
                                )
                                Page.Search -> ExpressiveSearchPage(
                                    state, current == Page.Search, focusSearch, voiceRequest, model::search,
                                    onChoose = chooseSong,
                                    onOpenResult = ::openDetail,
                                    onRemoveHistory = model::removeSearchHistory,
                                    onClearHistory = model::clearSearchHistory,
                                    historyOpen = searchHistoryOpen,
                                    onHistoryOpenChange = { searchHistoryOpen = it },
                                    onBack = ::goBack,
                                    onPlaylist = { openDetail("playlist/${it.id}") },
                                    onStyles = { openDetail("styles") },
                                    onRecognition = { openDetail("recognition") },
                                    onVoiceConsumed = { voiceRequest = 0 },
                                    onFocusConsumed = { focusSearch = false },
                                    bottomContentPadding = dockPadding,
                                )
                                Page.Personal -> PersonalPage(
                                    state, onLogin = { overlay = Page.Login }, onLogout = model::logout,
                                    onSettings = { overlay = Page.Settings },
                                    onCloud = { openDetail("cloud") },
                                    onDownloads = { openDetail("downloads") },
                                    onListening = { openDetail("listening") },
                                    onProfile = { state.account?.id?.let { openDetail("profile/$it") } },
                                    bottomContentPadding = dockPadding,
                                )
                                Page.Library -> LibraryPage(
                                    state, model::openPlaylist, model::closePlaylist,
                                    onImport = { files.launch(arrayOf("audio/*")) },
                                    onChoose = chooseSong,
                                    onLogin = { overlay = Page.Login },
                                    onComments = { openDetail("comments/A_PL_0_/${it.id}") },
                                    onManage = { openDetail(if (it == null) "playlist-manage" else "playlist-manage/${it.id}") },
                                    bottomContentPadding = dockPadding,
                                )
                                Page.Daily -> Column(Modifier.fillMaxSize()) {
                                    PageHeading("每日推荐", ::goBack)
                                    FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        FilledTonalButton(onClick = model::discover, enabled = !state.discovering) { Text("刷新") }
                                        TextButton(onClick = { openDetail("daily-history") }) { Text("历史推荐") }
                                    }
                                    Songs(state.discovery, state.now?.id, state.ready, state.discovering,
                                        state.discoveryError, "暂无推荐曲目") { chooseSong(state.discovery, it) }
                                }
                                Page.Recent -> Column(Modifier.fillMaxSize()) {
                                    PageHeading("最近播放", ::goBack)
                                    Songs(state.recent, state.now?.id, state.ready, false, null, "暂无播放记录") {
                                        chooseSong(state.recent, it)
                                    }
                                }
                                Page.Settings -> SettingsPage(::goBack, { about = true })
                                Page.Player -> PlayerColorTheme(state.now?.cover) {
                                    PlaybackPage(
                                        state = state,
                                        model = model,
                                        wide = wide,
                                        lyricsExpanded = playerShowsLyrics,
                                        onOpenLyrics = { playerShowsLyrics = true },
                                        browse = { search(false) },
                                        onCollapse = ::goBack,
                                        onDetails = { openDetailFromPlayer("song/$it") },
                                        onAlbum = { openDetailFromPlayer("album/$it") },
                                        onArtist = { openDetailFromPlayer("artist/$it") },
                                        onArtistCredits = ::openSongArtist,
                                        onComments = { openDetailFromPlayer("comments/R_SO_4_/$it") },
                                        onLogin = { openDetailFromPlayer("login") },
                                    )
                                }
                                Page.Login -> LoginPage(::goBack)
                                Page.Detail -> when (route?.substringBefore('/')) {
                                    "artist" -> route.substringAfter('/').toLongOrNull()?.let { id ->
                                        ArtistPage(id, state.ready, state.now, ::goBack,
                                            onOpenAlbum = { openDetail("album/$it") },
                                            onSongComments = { openDetail("comments/R_SO_4_/$it") },
                                            onChoose = chooseSong)
                                    }
                                    "song-artists" -> route.substringAfter('/').toLongOrNull()?.let { id ->
                                        SongArtistsPage(id, state.now, ::goBack) { openDetail("artist/$it") }
                                    }
                                    "styles" -> StylesPage(::goBack, state.ready, chooseSong)
                                    "recognition" -> RecognitionPage(::goBack, state.ready, chooseSong)
                                    "playlist-manage" -> PlaylistManagementPage(
                                        state.account, route.substringAfter('/', "").toLongOrNull(), ::goBack, model::refreshUserPlaylists,
                                    )
                                    "daily-history" -> DailyHistoryPage(
                                        state.account, route.substringAfter('/', "").takeIf(String::isNotEmpty),
                                        state.ready, state.now?.id, ::goBack, { openDetail("daily-history/$it") }, chooseSong,
                                    )
                                    "cloud" -> CloudPage(state.account, state.ready, ::goBack,
                                        onLogin = { openDetail("login") }, onChoose = chooseSong)
                                    "downloads" -> DownloadsPage(::goBack, state.ready, chooseSong)
                                    "listening" -> ListeningPage(state.account, ::goBack) { openDetail("login") }
                                    "login" -> LoginPage {
                                        model.refreshAccount()
                                        goBack()
                                    }
                                    else -> OnlineDetailPage(
                                        route = route.orEmpty(), ready = state.ready, now = state.now,
                                        onBack = ::goBack, onOpen = ::openDetail, onChoose = chooseSong,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (about) {
        val context = LocalContext.current
        val statement = remember(context) { context.assets.open("云听声明.txt").bufferedReader().use { it.readText() } }
        AlertDialog(
            onDismissRequest = { about = false },
            title = { Text("云听 1.0.0") },
            text = { Text(statement) },
            confirmButton = { TextButton(onClick = { about = false }) { Text("完成") } },
        )
    }
}

@Composable
private fun FloatingNavigationBar(selected: Destination, onNavigate: (Destination) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 2.dp, bottom = 8.dp),
        contentAlignment = Alignment.Center) {
        Surface(
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
            shape = CircleShape,
            color = colors.surfaceContainerHigh,
            border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.65f)),
            shadowElevation = 4.dp,
        ) {
            NavigationBar(containerColor = Color.Transparent, windowInsets = WindowInsets(0, 0, 0, 0)) {
                Destination.entries.forEach { destination ->
                    NavigationBarItem(selected = destination == selected, onClick = { onNavigate(destination) },
                        icon = { Icon(destination.icon, contentDescription = null) }, label = { Text(destination.label) })
                }
            }
        }
    }
}

@Composable
private fun LibraryPage(
    state: PlayerState,
    openPlaylist: (Playlist) -> Unit,
    closePlaylist: () -> Unit,
    onImport: () -> Unit,
    onChoose: (List<Song>, Int) -> Unit,
    onLogin: () -> Unit,
    onComments: (Playlist) -> Unit,
    onManage: (Playlist?) -> Unit,
    bottomContentPadding: Dp,
) {
    val selected = state.playlist
    if (selected != null) {
        SongShelf(selected.title, "${selected.size} 首", state.playlistSongs,
            state.loadingPlaylist, state.playlistError, "歌单暂无曲目", state.now?.id, state.ready,
            action = {
                TextButton(onClick = closePlaylist) { Text("返回") }
                TextButton(onClick = { onComments(selected) }) { Text("评论") }
                if (state.account != null) TextButton(onClick = { onManage(selected) }) { Text("管理") }
            },
            onChoose = { onChoose(state.playlistSongs, it) }, bottomContentPadding = bottomContentPadding)
        return
    }
    val playlistCardHeight = with(LocalDensity.current) {
        (MaterialTheme.typography.titleMedium.lineHeight.toDp() * 2 + 116.dp).coerceAtLeast(176.dp)
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp + bottomContentPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("我的歌单", Modifier.weight(1f), style = MaterialTheme.typography.headlineLarge)
                if (state.account != null) {
                    FilledTonalIconButton(onClick = { onManage(null) }) { Icon(Icons.Rounded.Edit, "歌单管理") }
                }
            }
        }
        if (state.account == null) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                FilledTonalButton(onClick = onLogin) { Text("登录后查看歌单") }
            }
        } else if (state.userPlaylists.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(state.userPlaylistsError ?: if (state.loadingUserPlaylists) "正在加载歌单" else "暂无歌单",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            items(state.userPlaylists, key = { it.id }) { list ->
                Card(
                    onClick = { openPlaylist(list) },
                    modifier = Modifier.fillMaxWidth().height(playlistCardHeight),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
                ) {
                    Box(Modifier.fillMaxSize()) {
                        Cover(list.cover, Modifier.matchParentSize(), 0, Icons.Rounded.QueueMusic)
                        Surface(
                            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                        ) {
                            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                Text(list.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.titleMedium)
                                Text("${list.size} 首", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("本地音乐", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                FilledTonalButton(onClick = onImport) { Text("导入") }
            }
        }
        if (state.library.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text("导入音频文件即可开始播放", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        state.library.forEachIndexed { index, song ->
            item(span = { GridItemSpan(maxLineSpan) }) {
                SongCard(song, song.id == state.now?.id, state.ready) { onChoose(state.library, index) }
            }
        }
        if (state.recent.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text("最近播放", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleLarge)
            }
            state.recent.forEachIndexed { index, song ->
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SongCard(song, song.id == state.now?.id, state.ready) { onChoose(state.recent, index) }
                }
            }
        }
    }
}

@Composable
private fun LoginPage(onFinish: () -> Unit) {
    Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0), topBar = {
        TopAppBar(windowInsets = WindowInsets(0, 0, 0, 0), title = {
            Text("网易云音乐登录", maxLines = 1, overflow = TextOverflow.Ellipsis)
        }, navigationIcon = {
            IconButton(onClick = onFinish) { Icon(Icons.Rounded.Close, "关闭") }
        }, actions = {
            TextButton(onClick = { CookieManager.getInstance().flush(); onFinish() }) { Text("完成") }
        })
    }) { padding ->
        AndroidView(factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val uri = request.url
                        return uri.scheme != "https" || uri.host.let { it != "music.163.com" && it?.endsWith(".163.com") != true }
                    }
                }
                loadUrl("https://music.163.com/m/login")
            }
        }, modifier = Modifier.fillMaxSize().padding(padding), onRelease = WebView::destroy)
    }
}

@Composable
private fun SongShelf(
    title: String, subtitle: String, songs: List<Song>, busy: Boolean, error: String?, empty: String,
    selected: String?, enabled: Boolean, action: @Composable () -> Unit, onChoose: (Int) -> Unit,
    bottomContentPadding: Dp,
) {
    Column(Modifier.fillMaxSize()) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(30.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 6.dp),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { action() }
            }
        }
        Songs(songs, selected, enabled, busy, error, empty, bottomContentPadding, onChoose)
    }
}

@Composable
internal fun Songs(
    songs: List<Song>, selected: String?, enabled: Boolean, busy: Boolean, error: String?, empty: String,
    bottomContentPadding: Dp = 0.dp, onChoose: (Int) -> Unit,
) {
    if (busy) {
        Box(Modifier.fillMaxSize().padding(bottom = bottomContentPadding), contentAlignment = Alignment.Center) { LoadingIndicator() }
    } else if (songs.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(bottom = bottomContentPadding).padding(22.dp), contentAlignment = Alignment.Center) {
            Text(error ?: empty, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        LazyColumn(contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 10.dp, bottom = 10.dp + bottomContentPadding),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsIndexed(songs) { index, song -> SongCard(song, song.id == selected, enabled) { onChoose(index) } }
        }
    }
}

@Composable
internal fun SongCard(
    song: Song, active: Boolean, enabled: Boolean, onComments: (() -> Unit)? = null, click: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val background by animateColorAsState(
        if (active) colors.secondaryContainer else colors.surfaceContainerLow,
        label = "track selection",
    )
    val foreground = if (active) colors.onSecondaryContainer else colors.onSurface
    Surface(color = background, contentColor = foreground, shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable(enabled = enabled, onClick = click)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(song, Modifier.size(48.dp), 12)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                Text(song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (active) foreground else colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            if (active || onComments == null) {
                Icon(if (active) Icons.Rounded.GraphicEq else Icons.Rounded.PlayArrow, null,
                    tint = if (active) foreground else colors.primary, modifier = Modifier.padding(horizontal = 8.dp))
            }
            if (onComments != null) {
                IconButton(onClick = onComments) {
                    Icon(Icons.AutoMirrored.Rounded.Comment, "查看《${song.title}》评论", tint = colors.primary)
                }
            }
        }
    }
}

@Composable
internal fun Cover(song: Song, modifier: Modifier, radius: Int = 32) =
    Cover(song.cover, modifier, radius)

@Composable
internal fun Cover(url: String, modifier: Modifier, radius: Int = 32,
    emptyIcon: ImageVector = Icons.Rounded.GraphicEq) {
    val cover = key(url) {
        produceState<Bitmap?>(null) {
            if (url.isNotBlank()) value = withContext(Dispatchers.IO) { CoverCache.load(url) }
        }
    }.value
    Surface(modifier = modifier, shape = RoundedCornerShape(radius.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        Box(contentAlignment = Alignment.Center) {
            val bitmap = cover
            if (bitmap == null) {
                Icon(emptyIcon, null, Modifier.fillMaxSize(0.35f), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            } else {
                Image(bitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun NowPlayingBar(state: PlayerState, onOpen: () -> Unit, onToggle: () -> Unit) {
    val song = state.now ?: return
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
        Surface(
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
            shape = CircleShape,
            color = colors.secondaryContainer,
            contentColor = colors.onSecondaryContainer,
            border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.65f)),
            shadowElevation = 4.dp,
        ) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 64.dp)
                    .clickable(onClickLabel = "打开正在播放", onClick = onOpen)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Cover(song, Modifier.size(44.dp), radius = 22)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(song.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (song.artist.isNotBlank()) {
                        Text(song.artist, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                FilledIconButton(onClick = onToggle, modifier = Modifier.size(48.dp)) { PlaybackIcon(state) }
            }
        }
    }
}

@Composable
private fun PlaybackIcon(state: PlayerState) {
    if (state.buffering) LoadingIndicator(Modifier.size(24.dp).semantics { contentDescription = "播放或暂停，正在缓冲" })
    else Icon(if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (state.playing) "暂停" else "播放")
}
