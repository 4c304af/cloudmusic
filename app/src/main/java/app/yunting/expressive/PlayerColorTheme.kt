package app.yunting.expressive

import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import androidx.annotation.RequiresApi
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.hypot

private const val PLAYBACK_COLOR_SOURCE = "playbackColorSource"
private val coverColors = LruCache<String, Int>(100)

private enum class PlaybackColorSource(val value: String, val label: String) {
    Wallpaper("wallpaper", "手机壁纸"),
    Cover("cover", "歌曲封面"),
}

@Composable
private fun rememberPlaybackColorSource(settings: SharedPreferences): PlaybackColorSource {
    fun readSource(): PlaybackColorSource {
        val value = settings.getString(PLAYBACK_COLOR_SOURCE, null)
        return PlaybackColorSource.entries.firstOrNull { it.value == value } ?: PlaybackColorSource.Wallpaper
    }
    var source by remember(settings) { mutableStateOf(readSource()) }
    DisposableEffect(settings) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == PLAYBACK_COLOR_SOURCE) source = readSource()
        }
        settings.registerOnSharedPreferenceChangeListener(listener)
        source = readSource()
        onDispose { settings.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return source
}

@Composable
internal fun PlaybackColorPreference(settings: SharedPreferences) {
    val source = rememberPlaybackColorSource(settings)
    OutlinedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Text(
            "播放页取色",
            Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 4.dp),
            style = MaterialTheme.typography.titleMedium,
        )
        Column(Modifier.fillMaxWidth().selectableGroup().padding(bottom = 4.dp)) {
            for (option in PlaybackColorSource.entries) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(
                        selected = source == option,
                        role = Role.RadioButton,
                        onClick = { settings.edit().putString(PLAYBACK_COLOR_SOURCE, option.value).apply() },
                    ).padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = source == option, onClick = null)
                    Text(option.label, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

@Composable
internal fun PlayerColorTheme(artworkUrl: String?, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val settings = remember(context) { context.getSharedPreferences("library", Context.MODE_PRIVATE) }
    val source = rememberPlaybackColorSource(settings)
    val url = artworkUrl?.takeIf { source == PlaybackColorSource.Cover && it.isNotBlank() }
    val seed = when {
        url != null -> key(url) {
            val initial = remember(url) { coverColors.get(url) }
            produceState(initial) {
                if (initial == null) {
                    value = withContext(Dispatchers.IO) {
                        CoverCache.load(url)?.let { bitmap ->
                            runCatching { coverColor(bitmap) }.getOrNull()?.also { color -> coverColors.put(url, color) }
                        }
                    }
                }
            }.value
        }
        Build.VERSION.SDK_INT in Build.VERSION_CODES.O_MR1 until Build.VERSION_CODES.S ->
            rememberWallpaperColor(context)
        else -> null
    }
    val base = MaterialTheme.colorScheme
    val dark = isSystemInDarkTheme()
    val scheme = remember(base, dark, seed) {
        if (seed == null) base else coverColorScheme(base, dark, seed)
    }
    MaterialTheme(colorScheme = scheme) {
        Surface(Modifier.fillMaxSize(), color = scheme.background, contentColor = scheme.onBackground, content = content)
    }
}

@RequiresApi(Build.VERSION_CODES.O_MR1)
@Composable
private fun rememberWallpaperColor(context: Context): Int? {
    val manager = remember(context) { WallpaperManager.getInstance(context) }
    var changes by remember(manager) { mutableIntStateOf(0) }
    DisposableEffect(manager) {
        val listener = WallpaperManager.OnColorsChangedListener { _, which ->
            if (which and WallpaperManager.FLAG_SYSTEM != 0) changes++
        }
        val registered = runCatching {
            manager.addOnColorsChangedListener(listener, Handler(Looper.getMainLooper()))
        }.isSuccess
        onDispose { if (registered) manager.removeOnColorsChangedListener(listener) }
    }
    return produceState<Int?>(null, manager, changes) {
        value = withContext(Dispatchers.IO) {
            runCatching { manager.getWallpaperColors(WallpaperManager.FLAG_SYSTEM)?.primaryColor?.toArgb() }
                .getOrNull()
        }
    }.value
}

private fun coverColor(bitmap: Bitmap): Int {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
        return WallpaperColors.fromBitmap(bitmap).primaryColor.toArgb()
    }
    return bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
}

private fun coverColorScheme(base: ColorScheme, dark: Boolean, seed: Int): ColorScheme {
    val lab = DoubleArray(3)
    ColorUtils.colorToLAB(seed, lab)
    val chroma = hypot(lab[1], lab[2])
    val scale = if (chroma > 50.0) 50.0 / chroma else 1.0
    val a = lab[1] * scale
    val b = lab[2] * scale
    fun tone(light: Int, night: Int, strength: Double): Color {
        val luminance = if (dark) night else light
        return Color(ColorUtils.LABToColor(luminance.toDouble(), a * strength, b * strength))
    }
    fun primary(light: Int, night: Int) = tone(light, night, 1.0)
    fun secondary(light: Int, night: Int) = tone(light, night, 0.48)
    fun neutral(light: Int, night: Int) = tone(light, night, 0.18)

    return base.copy(
        primary = primary(34, 82),
        onPrimary = primary(100, 18),
        primaryContainer = primary(90, 32),
        onPrimaryContainer = primary(10, 94),
        inversePrimary = primary(82, 34),
        primaryFixed = primary(90, 90),
        primaryFixedDim = primary(80, 80),
        onPrimaryFixed = primary(10, 10),
        onPrimaryFixedVariant = primary(30, 30),
        secondary = secondary(35, 82),
        onSecondary = secondary(100, 18),
        secondaryContainer = secondary(90, 31),
        onSecondaryContainer = secondary(10, 94),
        secondaryFixed = secondary(90, 90),
        secondaryFixedDim = secondary(80, 80),
        onSecondaryFixed = secondary(10, 10),
        onSecondaryFixedVariant = secondary(30, 30),
        tertiary = secondary(32, 84),
        onTertiary = secondary(100, 16),
        tertiaryContainer = secondary(89, 30),
        onTertiaryContainer = secondary(10, 94),
        tertiaryFixed = secondary(90, 90),
        tertiaryFixedDim = secondary(80, 80),
        onTertiaryFixed = secondary(10, 10),
        onTertiaryFixedVariant = secondary(30, 30),
        background = neutral(98, 10),
        onBackground = neutral(10, 93),
        surface = neutral(98, 10),
        onSurface = neutral(10, 93),
        surfaceVariant = neutral(91, 28),
        onSurfaceVariant = neutral(29, 80),
        surfaceTint = primary(34, 82),
        surfaceBright = neutral(99, 25),
        surfaceDim = neutral(87, 10),
        surfaceContainerLowest = neutral(100, 7),
        surfaceContainerLow = neutral(96, 13),
        surfaceContainer = neutral(94, 16),
        surfaceContainerHigh = neutral(92, 20),
        surfaceContainerHighest = neutral(89, 24),
        inverseSurface = neutral(21, 92),
        inverseOnSurface = neutral(95, 17),
        outline = neutral(48, 62),
        outlineVariant = neutral(78, 42),
    )
}
