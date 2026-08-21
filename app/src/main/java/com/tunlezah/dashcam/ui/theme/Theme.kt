package com.tunlezah.dashcam.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.tunlezah.dashcam.domain.settings.AppTheme

// Brand palette: deep road-night blues with a signal-cyan accent and a
// high-visibility recording red. Deliberately restrained — the camera preview
// is the hero of the main screen.
private val Cyan = Color(0xFF2D9CDB)
private val CyanDim = Color(0xFF1B6FA3)
private val RecordRed = Color(0xFFE5484D)
private val Amber = Color(0xFFF2C94C)

private val LightColors: ColorScheme = lightColorScheme(
    primary = CyanDim,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3EAF8),
    onPrimaryContainer = Color(0xFF0A2A3D),
    secondary = Color(0xFF4E6572),
    onSecondary = Color.White,
    error = RecordRed,
    background = Color(0xFFF7F9FB),
    onBackground = Color(0xFF16212B),
    surface = Color.White,
    onSurface = Color(0xFF16212B),
    surfaceVariant = Color(0xFFE4EBF0),
    onSurfaceVariant = Color(0xFF44545F),
    tertiary = Amber,
)

private val DarkColors: ColorScheme = darkColorScheme(
    primary = Cyan,
    onPrimary = Color(0xFF04121C),
    primaryContainer = Color(0xFF14405C),
    onPrimaryContainer = Color(0xFFBFE3F7),
    secondary = Color(0xFF9BB2BF),
    onSecondary = Color(0xFF15242D),
    error = RecordRed,
    background = Color(0xFF10161C),
    onBackground = Color(0xFFE3E9EE),
    surface = Color(0xFF161E26),
    onSurface = Color(0xFFE3E9EE),
    surfaceVariant = Color(0xFF232E38),
    onSurfaceVariant = Color(0xFFA5B4BF),
    tertiary = Amber,
)

/**
 * OLED theme: true-black background/surfaces. On the Edge 60 Fusion's pOLED
 * this genuinely reduces display power and heat; on the Moto G04's LCD it is
 * purely aesthetic (the backlight doesn't care) — documented in
 * docs/thermal-management.md.
 */
private val OledColors: ColorScheme = DarkColors.copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceVariant = Color(0xFF11161B),
)

@Composable
fun DashCamTheme(theme: AppTheme, content: @Composable () -> Unit) {
    val colors = when (theme) {
        AppTheme.LIGHT -> LightColors
        AppTheme.DARK -> DarkColors
        AppTheme.OLED -> OledColors
        AppTheme.SYSTEM -> if (isSystemInDarkTheme()) DarkColors else LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}

object DashCamColors {
    val recordRed = RecordRed
    val warningAmber = Amber
    val okGreen = Color(0xFF30A46C)
}
