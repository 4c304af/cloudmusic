@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package app.yunting.expressive

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val purpleLight = lightColorScheme(
    primary = Color(0xFF32226F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF4C3889),
    onPrimaryContainer = Color.White,
    secondary = Color(0xFF342C45),
    secondaryContainer = Color(0xFF4B425D),
    onSecondaryContainer = Color.White,
    tertiaryContainer = Color(0xFF6C3644),
    onTertiaryContainer = Color.White,
    surface = Color(0xFFFAF8FE),
    background = Color(0xFFFAF8FE),
    onBackground = Color(0xFF010002),
    surfaceBright = Color(0xFFFAF8FE),
    surfaceDim = Color(0xFFE4E1E7),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF5F3F8),
    surfaceContainer = Color(0xFFEFEDF2),
    surfaceContainerHigh = Color(0xFFE9E7ED),
    surfaceContainerHighest = Color(0xFFE4E1E7),
    onSurface = Color(0xFF010002),
    onSurfaceVariant = Color(0xFF1D1A23),
    outline = Color(0xFF322F39),
    outlineVariant = Color(0xFF322F39),
    inverseSurface = Color(0xFF313034),
    inverseOnSurface = Color(0xFFF2F0F5),
    inversePrimary = Color(0xFFD2BCFC),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

private val purpleDark = darkColorScheme(
    primary = Color(0xFFF4EEFE),
    onPrimary = Color(0xFF010003),
    primaryContainer = Color(0xFFD2BCFC),
    onPrimaryContainer = Color(0xFF010003),
    secondary = Color(0xFFF4EEFF),
    secondaryContainer = Color(0xFFCDC1E1),
    onSecondaryContainer = Color(0xFF010003),
    tertiaryContainer = Color(0xFFF6B5C3),
    onTertiaryContainer = Color(0xFF020000),
    surface = Color(0xFF141317),
    background = Color(0xFF141317),
    onBackground = Color.White,
    surfaceBright = Color(0xFF363438),
    surfaceDim = Color(0xFF141317),
    surfaceContainerLowest = Color(0xFF0E0D11),
    surfaceContainerLow = Color(0xFF1C1B1F),
    surfaceContainer = Color(0xFF201F23),
    surfaceContainerHigh = Color(0xFF2B292D),
    surfaceContainerHighest = Color(0xFF363438),
    onSurface = Color.White,
    onSurfaceVariant = Color(0xFFF3EFFC),
    outline = Color(0xFFE5E0ED),
    outlineVariant = Color(0xFFE5E0ED),
    inverseSurface = Color(0xFFE4E1E7),
    inverseOnSurface = Color(0xFF313034),
    inversePrimary = Color(0xFF6750A4),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
)

@Composable
internal fun YuntingTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(context)
        dark -> purpleDark
        else -> purpleLight
    }
    MaterialExpressiveTheme(
        colorScheme = scheme,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(8.dp),
            small = RoundedCornerShape(16.dp),
            medium = RoundedCornerShape(20.dp),
            large = RoundedCornerShape(32.dp),
            extraLarge = RoundedCornerShape(40.dp),
        ),
        motionScheme = MotionScheme.standard(),
        content = content,
    )
}
