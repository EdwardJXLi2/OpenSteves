package dev.hydranet.opensteves.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

enum class AppThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

private val LightColors = lightColorScheme(
    primary = Color(0xFF21695E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0EEEA),
    onPrimaryContainer = Color(0xFF164A42),
    secondary = Color(0xFF46645D),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0EEEA),
    onSecondaryContainer = Color(0xFF202B2E),
    tertiary = Color(0xFF53676F),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE1EAED),
    onTertiaryContainer = Color(0xFF202B2E),
    background = Color(0xFFF8FAF9),
    onBackground = Color(0xFF202B2E),
    surface = Color(0xFFF8FAF9),
    onSurface = Color(0xFF202B2E),
    surfaceVariant = Color(0xFFEAF0EE),
    onSurfaceVariant = Color(0xFF5D6C71),
    outline = Color(0xFF768781),
    outlineVariant = Color(0xFFDBE3E2),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFEAF0EE),
    surfaceContainerHighest = Color(0xFFE1E9E6),
    surfaceBright = Color.White,
    surfaceDim = Color(0xFFE1E9E6),
    surfaceTint = Color(0xFF21695E),
    inverseSurface = Color(0xFF202B2E),
    inverseOnSurface = Color(0xFFE6EAEB),
    inversePrimary = Color(0xFFA4D7CB),
    error = Color(0xFFAD3935),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDA),
    onErrorContainer = Color(0xFF7A2522),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA4D7CB),
    onPrimary = Color(0xFF101416),
    primaryContainer = Color(0xFF223B37),
    onPrimaryContainer = Color(0xFFA4D7CB),
    secondary = Color(0xFFADC9BF),
    onSecondary = Color(0xFF101416),
    secondaryContainer = Color(0xFF223B37),
    onSecondaryContainer = Color(0xFFE6EAEB),
    tertiary = Color(0xFFB6CBD3),
    onTertiary = Color(0xFF101416),
    tertiaryContainer = Color(0xFF2B3C44),
    onTertiaryContainer = Color(0xFFE6EAEB),
    background = Color(0xFF101416),
    onBackground = Color(0xFFE6EAEB),
    surface = Color(0xFF101416),
    onSurface = Color(0xFFE6EAEB),
    surfaceVariant = Color(0xFF263035),
    onSurfaceVariant = Color(0xFF9BA8AE),
    outline = Color(0xFF7C8C91),
    outlineVariant = Color(0xFF2D383E),
    surfaceContainerLowest = Color(0xFF0C1012),
    surfaceContainerLow = Color(0xFF192125),
    surfaceContainer = Color(0xFF1E262A),
    surfaceContainerHigh = Color(0xFF263035),
    surfaceContainerHighest = Color(0xFF303B40),
    surfaceBright = Color(0xFF303B40),
    surfaceDim = Color(0xFF101416),
    surfaceTint = Color(0xFFA4D7CB),
    inverseSurface = Color(0xFFE6EAEB),
    inverseOnSurface = Color(0xFF202B2E),
    inversePrimary = Color(0xFF21695E),
    error = Color(0xFFE6ABAA),
    onError = Color(0xFF482221),
    errorContainer = Color(0xFF59312F),
    onErrorContainer = Color(0xFFF9DEDA),
)

@Composable
fun OpenStevesTheme(
    themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
    }

    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(4.dp),
            small = RoundedCornerShape(6.dp),
            medium = RoundedCornerShape(6.dp),
            large = RoundedCornerShape(8.dp),
            extraLarge = RoundedCornerShape(10.dp),
        ),
        content = content,
    )
}
