@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package app.yunting.expressive

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private enum class RecognitionSource { Microphone, Device }
private enum class RecognitionPhase { Idle, Recording, Matching }

@Composable
internal fun RecognitionPage(
    onBack: () -> Unit,
    ready: Boolean,
    onChoose: (List<Song>, Int) -> Unit,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val recognizer = remember(context) { MusicRecognizer(context) }
    val historyStore = remember(context) { IdentificationHistory(context) }
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    var phase by remember { mutableStateOf(RecognitionPhase.Idle) }
    var pendingSource by rememberSaveable { mutableStateOf(RecognitionSource.Microphone) }
    var progress by remember { mutableFloatStateOf(0f) }
    var result by remember { mutableStateOf(emptyList<Song>()) }
    var history by remember { mutableStateOf(historyStore.songs()) }
    var message by remember { mutableStateOf<String?>(null) }
    var showHistory by rememberSaveable { mutableStateOf(false) }

    fun record(source: RecognitionSource, deviceGrant: Intent? = null) {
        if (job != null) return
        progress = 0f
        result = emptyList()
        message = null
        phase = RecognitionPhase.Recording
        val operation = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val samples = when (source) {
                    RecognitionSource.Microphone -> captureMicrophone { progress = it }
                    RecognitionSource.Device -> {
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || deviceGrant == null) {
                            throw RecognitionError("当前设备无法采集播放声音")
                        }
                        capturePlayback(context, deviceGrant) { progress = it }
                    }
                }
                phase = RecognitionPhase.Matching
                val found = recognizer.identify(samples)
                result = found
                if (found.isEmpty()) {
                    message = "没有识别到歌曲，换一段声音再试"
                } else {
                    history = historyStore.add(found.first())
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: RecognitionError) {
                message = error.message
            } catch (_: SecurityException) {
                message = "系统未允许采集声音"
            } catch (_: Exception) {
                message = "暂时无法识别，请稍后重试"
            } finally {
                job = null
                phase = RecognitionPhase.Idle
            }
        }
        job = operation
        operation.start()
    }

    val projectionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { response ->
        val data = response.data
        if (response.resultCode == Activity.RESULT_OK && data != null) record(RecognitionSource.Device, data)
        else message = "未允许采集设备声音"
    }
    fun begin(source: RecognitionSource) {
        if (source == RecognitionSource.Microphone) {
            record(source)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val manager = context.getSystemService(MediaProjectionManager::class.java)
                if (manager == null) {
                    message = "此设备无法打开音频采集"
                } else {
                    projectionLauncher.launch(manager.createScreenCaptureIntent())
                }
            } catch (_: Exception) {
                message = "无法打开系统音频采集"
            }
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed) begin(pendingSource)
        else message = "请在系统应用权限中允许云听使用麦克风，再试一次"
    }
    fun choose(source: RecognitionSource) {
        if (job != null) return
        pendingSource = source
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            begin(source)
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && pendingSource == RecognitionSource.Microphone) job?.cancel()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    BackHandler(enabled = showHistory) { showHistory = false }

    Column(Modifier.fillMaxSize()) {
        if (showHistory) {
            PageHeading("识曲历史", onBack = { showHistory = false })
        } else {
            PageHeading("听歌识曲", onBack = onBack) {
                FilledTonalIconButton(onClick = { showHistory = true }, enabled = job == null) {
                    Icon(Icons.Rounded.History, "识曲历史")
                }
            }
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                Modifier.widthIn(max = 620.dp).fillMaxWidth(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (showHistory) {
                    if (history.isEmpty()) item { RecognitionNotice("暂无识曲历史") }
                    itemsIndexed(history, key = { _, song -> song.id }) { index, song ->
                        SongCard(song, active = false, enabled = ready) { onChoose(history, index) }
                    }
                } else {
                    item {
                        Surface(
                            shape = MaterialTheme.shapes.extraLarge,
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                                    Icon(Icons.Rounded.Mic, null, Modifier.size(68.dp).padding(19.dp))
                                }
                                Text(
                                    when (phase) {
                                        RecognitionPhase.Recording -> "正在聆听"
                                        RecognitionPhase.Matching -> "正在匹配歌曲"
                                        RecognitionPhase.Idle -> "识别正在播放的音乐"
                                    },
                                    style = MaterialTheme.typography.titleLarge,
                                    textAlign = TextAlign.Center,
                                )
                                when (phase) {
                                    RecognitionPhase.Recording -> LinearWavyProgressIndicator(
                                        progress = { progress },
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                    )
                                    RecognitionPhase.Matching -> LoadingIndicator()
                                    RecognitionPhase.Idle -> {
                                        Text(
                                            "录音会在本机转换为指纹，由网易云音乐匹配歌曲。",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            textAlign = TextAlign.Center,
                                        )
                                        Button(
                                            onClick = { choose(RecognitionSource.Microphone) },
                                            modifier = Modifier.fillMaxWidth(),
                                        ) { Text("用麦克风识别") }
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                            FilledTonalButton(
                                                onClick = { choose(RecognitionSource.Device) },
                                                modifier = Modifier.fillMaxWidth(),
                                            ) { Text("识别设备播放的音乐") }
                                        }
                                    }
                                }
                                if (job != null) TextButton(onClick = { job?.cancel() }) { Text("停止") }
                            }
                        }
                    }
                    message?.let { item { RecognitionNotice(it) } }
                    if (result.isNotEmpty()) {
                        item { Text("识别结果", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleLarge) }
                        itemsIndexed(result, key = { _, song -> song.id }) { index, song ->
                            SongCard(song, active = index == 0, enabled = ready) { onChoose(result, index) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecognitionNotice(message: String) {
    Text(
        message,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 16.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private class IdentificationHistory(context: Context) {
    private val prefs = context.getSharedPreferences("song_identification", Context.MODE_PRIVATE)

    fun songs(): List<Song> = runCatching {
        Catalog.songs(JSONArray(prefs.getString("tracks", "[]")))
    }.getOrDefault(emptyList())

    fun add(song: Song): List<Song> {
        val next = (listOf(song) + songs().filterNot { it.id == song.id }).take(50)
        val tracks = JSONArray()
        next.forEach { match ->
            tracks.put(JSONObject()
                .put("id", match.id)
                .put("name", match.title)
                .put("ar", JSONArray().put(JSONObject().put("id", match.artistId).put("name", match.artist)))
                .put("al", JSONObject().put("id", match.albumId).put("name", match.album).put("picUrl", match.cover)))
        }
        prefs.edit().putString("tracks", tracks.toString()).apply()
        return next
    }
}
