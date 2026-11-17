package app.yunting.expressive

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
internal fun PageHeading(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilledTonalIconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") }
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
        actions()
    }
}

@Composable
internal fun PersonalPage(
    state: PlayerState,
    onLogin: () -> Unit,
    onLogout: () -> Unit,
    onSettings: () -> Unit,
    onCloud: () -> Unit,
    onDownloads: () -> Unit,
    onListening: () -> Unit,
    onProfile: () -> Unit,
    bottomContentPadding: Dp = 0.dp,
) {
    var confirmLogout by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 16.dp + bottomContentPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.AccountCircle, null, tint = MaterialTheme.colorScheme.primary)
            Text(state.account?.name ?: "个人中心", Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
            FilledTonalIconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, "设置") }
        }
        if (state.account == null) {
            Button(onClick = onLogin) { Text("登录网易云音乐") }
        } else {
            FilledTonalIconButton(onClick = onProfile) { Icon(Icons.Rounded.AccountCircle, "查看个人资料") }
        }
        for ((label, icon, action) in listOf(
            Triple("音乐云盘", Icons.Rounded.Cloud, onCloud),
            Triple("下载管理", Icons.Rounded.Download, onDownloads),
            Triple("听歌数据", Icons.Rounded.Insights, onListening),
        )) {
            OutlinedCard(onClick = action, modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large) {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                    Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null)
                }
            }
        }
        if (state.account != null) OutlinedButton(onClick = { confirmLogout = true }) { Text("退出登录") }
    }
    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("退出当前账号？") },
            confirmButton = {
                TextButton(onClick = { confirmLogout = false; onLogout() }) { Text("退出登录") }
            },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("取消") } },
        )
    }
}

@Composable
internal fun SettingsPage(onBack: () -> Unit, onAbout: () -> Unit) {
    val context = LocalContext.current
    val widgetManager = remember(context) { AppWidgetManager.getInstance(context) }
    val settings = remember(context) { context.getSharedPreferences("library", 0) }
    var lyricBridge by remember { mutableStateOf(settings.getBoolean("lyricBridge", false)) }
    Column(Modifier.fillMaxSize()) {
        PageHeading("设置", onBack)
        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PlaybackColorPreference(settings)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("词幕状态栏歌词", Modifier.weight(1f))
                Switch(checked = lyricBridge, onCheckedChange = {
                    lyricBridge = it
                    settings.edit().putBoolean("lyricBridge", it).apply()
                })
            }
            if (widgetManager.isRequestPinAppWidgetSupported) {
                Text("桌面小组件", style = MaterialTheme.typography.titleLarge)
                val providers = listOf(
                    NowPlayingWidget::class.java to "播放控制",
                    CurrentLyricsWidget::class.java to "当前歌词",
                    RecentPlaybackWidget::class.java to "最近播放",
                )
                for ((provider, label) in providers) {
                    OutlinedButton(onClick = {
                        widgetManager.requestPinAppWidget(ComponentName(context, provider), null, null)
                    }) { Text("添加${label}小组件") }
                }
            }
            TextButton(onClick = onAbout) { Text("关于云听") }
        }
    }
}
