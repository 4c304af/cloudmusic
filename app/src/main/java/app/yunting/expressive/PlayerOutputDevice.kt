@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package app.yunting.expressive

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRouter
import android.media.MediaRouter2
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal object PlaybackOutput {
    private val choice = MutableStateFlow<AudioDeviceInfo?>(null)
    val preferred = choice.asStateFlow()

    fun select(device: AudioDeviceInfo?) {
        choice.value = device
    }
}

@Composable
internal fun PlayerOutputDevice(account: Account?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val preferred by PlaybackOutput.preferred.collectAsState()
    val routes = rememberOutputRoutes()
    val manualDevice = routes.devices.firstOrNull { it.id == preferred?.id }
    LaunchedEffect(preferred?.id, manualDevice?.id) {
        if (preferred != null && manualDevice == null) PlaybackOutput.select(null)
    }
    val name = manualDevice?.displayName()
        ?: routes.active.takeIf { it.size > 1 }?.let { "${it.size} 台设备" }
        ?: routes.routeName
        ?: routes.active.firstOrNull()?.displayName()
        ?: "跟随系统"
    val type = manualDevice?.outputType()
        ?: OutputType.Multiple.takeIf { routes.active.size > 1 }
        ?: routes.routeType
        ?: OutputType.Unknown
    var showSheet by remember { mutableStateOf(false) }

    Row(
        modifier = modifier.clickable(
            interactionSource = null,
            indication = null,
            role = Role.Button,
            onClickLabel = "管理播放设备",
            onClick = { showSheet = true },
        ).padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            type.icon,
            contentDescription = type.label,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            name,
            modifier = Modifier.weight(1f, fill = false),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 19.sp, lineHeight = 26.sp),
        )
    }
    if (showSheet) {
        OutputDeviceSheet(context, routes, manualDevice, account) { showSheet = false }
    }
}

private data class OutputRoutes(
    val devices: List<AudioDeviceInfo>,
    val active: List<AudioDeviceInfo>,
    val routeName: String?,
    val routeType: OutputType?,
)

@Suppress("DEPRECATION")
@Composable
private fun rememberOutputRoutes(): OutputRoutes {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(AudioManager::class.java) }
    val router = remember(context) { context.getSystemService(MediaRouter::class.java) }
    val audioAttributes = remember {
        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    }
    fun read(): OutputRoutes {
        val devices = manager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS).orEmpty()
            .filter { it.type in mediaOutputTypes }
        val active = if (Build.VERSION.SDK_INT >= 33) {
            manager?.getAudioDevicesForAttributes(audioAttributes).orEmpty().filter { it.type in mediaOutputTypes }
        } else emptyList()
        val selectedRoute = router?.getSelectedRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO)
        val routeName = selectedRoute?.name?.toString()?.trim()?.takeIf(String::isNotEmpty)
        val customRouteName = routeName.takeIf { selectedRoute != router?.defaultRoute }
        val activeDevice = active.firstOrNull().takeIf { customRouteName == null }
        val name = customRouteName ?: activeDevice?.displayName() ?: routeName ?: devices.singleOrNull()?.displayName()
        val matchingDevice = name?.let { route -> devices.firstOrNull { it.displayName().equals(route, ignoreCase = true) } }
        return OutputRoutes(devices, active, name, (activeDevice ?: matchingDevice)?.outputType() ?: selectedRoute?.outputType())
    }
    var routes by remember(context) { mutableStateOf(read()) }
    DisposableEffect(manager, router) {
        val deviceListener = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) { routes = read() }
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) { routes = read() }
        }
        val routeListener = object : MediaRouter.SimpleCallback() {
            override fun onRouteChanged(router: MediaRouter, info: MediaRouter.RouteInfo) { routes = read() }
            override fun onRouteSelected(router: MediaRouter, type: Int, info: MediaRouter.RouteInfo) { routes = read() }
            override fun onRouteUnselected(router: MediaRouter, type: Int, info: MediaRouter.RouteInfo) { routes = read() }
        }
        manager?.registerAudioDeviceCallback(deviceListener, Handler(Looper.getMainLooper()))
        router?.addCallback(MediaRouter.ROUTE_TYPE_LIVE_AUDIO, routeListener)
        onDispose {
            manager?.unregisterAudioDeviceCallback(deviceListener)
            router?.removeCallback(routeListener)
        }
    }
    return routes
}

@Composable
private fun OutputDeviceSheet(
    context: Context,
    routes: OutputRoutes,
    manualDevice: AudioDeviceInfo?,
    account: Account?,
    onDismiss: () -> Unit,
) {
    var multi by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    val colors = MaterialTheme.colorScheme
    fun openSystemPanel() {
        if (context.showSystemOutputSwitcher()) {
            PlaybackOutput.select(null)
            onDismiss()
        } else {
            notice = if (context.openOutputSettings(Settings.ACTION_SOUND_SETTINGS)) null
            else "请从系统媒体控件打开输出面板"
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
        containerColor = colors.background,
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(onClick = ::openSystemPanel) { Icon(Icons.Rounded.SurroundSound, "打开系统输出") }
                Column(Modifier.weight(1f)) {
                    Text("声音输出", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        if (multi) "多设备由系统音频共享管理" else manualDevice?.displayName() ?: routes.routeName ?: "跟随系统",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "关闭声音输出") }
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("单设备", "多设备").forEachIndexed { index, label ->
                    SegmentedButton(
                        selected = multi == (index == 1),
                        onClick = { multi = index == 1 },
                        shape = SegmentedButtonDefaults.itemShape(index, count = 2),
                    ) { Text(label) }
                }
            }
            if (multi) {
                val summary = if (routes.active.size > 1) "当前系统正在向 ${routes.active.size} 台设备输出"
                else "可同时播放的设备由系统决定"
                Text(summary, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            } else {
                OutputRow(
                    Icons.Rounded.SpeakerGroup, "跟随系统", "使用系统选择的设备或音频共享组合",
                    selected = manualDevice == null, onClick = { PlaybackOutput.select(null) },
                )
            }
            if (routes.devices.isEmpty()) {
                Text("未检测到可单独选择的输出设备", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
            routes.devices.forEach { device ->
                val selected = if (multi) routes.active.any { it.id == device.id } else device.id == manualDevice?.id
                val type = device.outputType()
                OutputRow(
                    type.icon, device.displayName(), type.label, selected = selected,
                    onClick = { if (multi) openSystemPanel() else PlaybackOutput.select(device) },
                )
            }
            OutputRow(
                Icons.Rounded.SurroundSound, "系统输出与音频共享", "在系统面板中管理输出设备",
                onClick = ::openSystemPanel,
            )
            OutputRow(Icons.Rounded.Add, "添加新设备", "打开蓝牙设置配对耳机与音箱", onClick = {
                if (!context.openOutputSettings(Settings.ACTION_BLUETOOTH_SETTINGS)) notice = "此设备未提供蓝牙设置"
            })
            notice?.let { Text(it, color = colors.error, style = MaterialTheme.typography.bodyMedium) }
            OutputListeningTime(account)
        }
    }
}

@Composable
private fun OutputRow(
    icon: ImageVector,
    title: String,
    description: String,
    selected: Boolean? = null,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = if (selected == true) colors.secondaryContainer else colors.surfaceContainerHigh,
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, tint = colors.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(description, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            when (selected) {
                null -> Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null)
                true -> Icon(Icons.Rounded.CheckCircle, "已选中", tint = colors.primary)
                false -> Icon(Icons.Rounded.RadioButtonUnchecked, null, tint = colors.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun OutputListeningTime(account: Account?) {
    if (account == null) return
    var seconds by remember(account.id) { mutableStateOf<Long?>(null) }
    var failed by remember(account.id) { mutableStateOf(false) }
    var attempt by remember(account.id) { mutableIntStateOf(0) }
    LaunchedEffect(account.id, attempt) {
        failed = false
        try {
            val session = requireAccountSession(account.id)
            seconds = onIo { Catalog.listeningDuration(session) }.getOrThrow()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        }
    }
    Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("听歌时间", style = MaterialTheme.typography.titleMedium)
                Text(seconds?.let { "累计收听 ${it / 3_600} 小时 ${it / 60 % 60} 分钟" }
                    ?: if (failed) "暂时无法加载听歌时间" else "正在读取累计听歌时间",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
            IconButton(onClick = { attempt++ }, enabled = seconds != null || failed) {
                Icon(Icons.Rounded.Refresh, "刷新听歌时间")
            }
        }
    }
}

private fun Context.showSystemOutputSwitcher(): Boolean {
    if (Build.VERSION.SDK_INT < 34) return false
    return runCatching { MediaRouter2.getInstance(this).showSystemOutputSwitcher() }.getOrDefault(false)
}

private fun Context.openOutputSettings(action: String): Boolean = try {
    startActivity(Intent(action))
    true
} catch (_: ActivityNotFoundException) {
    false
} catch (_: SecurityException) {
    false
}

private val mediaOutputTypes = setOf(
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_WIRED_HEADSET,
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
    AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_BLE_BROADCAST,
    AudioDeviceInfo.TYPE_HEARING_AID, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET,
    AudioDeviceInfo.TYPE_USB_ACCESSORY, AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC,
    AudioDeviceInfo.TYPE_HDMI_EARC, AudioDeviceInfo.TYPE_DOCK, AudioDeviceInfo.TYPE_LINE_ANALOG,
)

private fun AudioDeviceInfo.displayName(): String =
    productName.toString().trim().ifBlank { outputType().label }

private enum class OutputType(val label: String, val icon: ImageVector) {
    BuiltInSpeaker("内置扬声器", Icons.Rounded.Speaker),
    Speaker("扬声器", Icons.Rounded.Speaker),
    Bluetooth("蓝牙音频", Icons.Rounded.BluetoothAudio),
    Headphones("有线耳机", Icons.Rounded.Headset),
    Usb("USB 音频", Icons.Rounded.Usb),
    Hdmi("HDMI 音频", Icons.Rounded.Tv),
    Tv("电视音频", Icons.Rounded.Tv),
    Dock("扩展坞音频", Icons.Rounded.Dock),
    Line("线路输出", Icons.Rounded.SettingsInputComponent),
    Multiple("多设备输出", Icons.Rounded.SpeakerGroup),
    Unknown("音频设备", Icons.Rounded.SurroundSound),
}

private fun AudioDeviceInfo.outputType(): OutputType = when (type) {
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> OutputType.BuiltInSpeaker
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER,
    AudioDeviceInfo.TYPE_BLE_BROADCAST, AudioDeviceInfo.TYPE_HEARING_AID -> OutputType.Bluetooth
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> OutputType.Headphones
    AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> OutputType.Usb
    AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_HDMI_EARC -> OutputType.Hdmi
    AudioDeviceInfo.TYPE_DOCK -> OutputType.Dock
    AudioDeviceInfo.TYPE_LINE_ANALOG -> OutputType.Line
    else -> OutputType.Unknown
}

@Suppress("DEPRECATION")
private fun MediaRouter.RouteInfo.outputType(): OutputType? = when (deviceType) {
    MediaRouter.RouteInfo.DEVICE_TYPE_BLUETOOTH -> OutputType.Bluetooth
    MediaRouter.RouteInfo.DEVICE_TYPE_SPEAKER -> OutputType.Speaker
    MediaRouter.RouteInfo.DEVICE_TYPE_TV -> OutputType.Tv
    else -> null
}
