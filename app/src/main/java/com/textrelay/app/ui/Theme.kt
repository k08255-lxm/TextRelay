package com.textrelay.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// A stable palette keeps connection, content and actions visually consistent across devices.
private val LightColors = lightColorScheme(
    primary = Color(0xFF176B5B), onPrimary = Color.White,
    primaryContainer = Color(0xFFE0EFE8), onPrimaryContainer = Color(0xFF164D40),
    secondary = Color(0xFF53665F), onSecondary = Color.White,
    secondaryContainer = Color(0xFFEAF2ED), onSecondaryContainer = Color(0xFF253C33),
    tertiary = Color(0xFF79603B), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF4EAD8), onTertiaryContainer = Color(0xFF574426),
    background = Color(0xFFF6F7F3), onBackground = Color(0xFF202B26),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF202B26),
    surfaceVariant = Color(0xFFEEF1EC), onSurfaceVariant = Color(0xFF606D65),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF8F9F6),
    surfaceContainer = Color(0xFFF0F3EE), surfaceContainerHigh = Color(0xFFE9EEE8),
    surfaceContainerHighest = Color(0xFFE2E8E0),
    outline = Color(0xFF77837A), outlineVariant = Color(0xFFDCE3DA),
    error = Color(0xFFAC3737), onError = Color.White,
    errorContainer = Color(0xFFFBE9E7), onErrorContainer = Color(0xFF792727)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8FD7BA), onPrimary = Color(0xFF10382B),
    primaryContainer = Color(0xFF253F34), onPrimaryContainer = Color(0xFFC8EFDB),
    secondary = Color(0xFFB4C9BC), onSecondary = Color(0xFF23372C),
    secondaryContainer = Color(0xFF25362D), onSecondaryContainer = Color(0xFFD6E9DD),
    tertiary = Color(0xFFE2C28E), onTertiary = Color(0xFF412F12),
    tertiaryContainer = Color(0xFF403623), onTertiaryContainer = Color(0xFFF2D8AE),
    background = Color(0xFF111713), onBackground = Color(0xFFE3EBE3),
    surface = Color(0xFF1B231D), onSurface = Color(0xFFE3EBE3),
    surfaceVariant = Color(0xFF28332B), onSurfaceVariant = Color(0xFFADBCAF),
    surfaceContainerLowest = Color(0xFF101612), surfaceContainerLow = Color(0xFF18201A),
    surfaceContainer = Color(0xFF202A22), surfaceContainerHigh = Color(0xFF27322A),
    surfaceContainerHighest = Color(0xFF303C32),
    outline = Color(0xFF849487), outlineVariant = Color(0xFF354337),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF4B2725), onErrorContainer = Color(0xFFFFDAD6)
)

private fun type(size: Int, line: Int, weight: FontWeight = FontWeight.Normal, tracking: Float = 0f) =
    TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = weight,
        fontSize = size.sp, lineHeight = line.sp, letterSpacing = tracking.sp)

private val RelayTypography = Typography(
    headlineLarge = type(32, 40, FontWeight.Bold, -0.8f),
    headlineMedium = type(26, 34, FontWeight.Bold, -0.5f),
    headlineSmall = type(23, 31, FontWeight.SemiBold, -0.3f),
    titleLarge = type(21, 29, FontWeight.SemiBold, -0.3f),
    titleMedium = type(16, 24, FontWeight.SemiBold),
    titleSmall = type(14, 20, FontWeight.SemiBold),
    bodyLarge = type(16, 26), bodyMedium = type(14, 22), bodySmall = type(12, 18),
    labelLarge = type(14, 20, FontWeight.Medium),
    labelMedium = type(12, 18, FontWeight.Medium, 0.2f),
    labelSmall = type(11, 16, FontWeight.Medium, 0.3f)
)

@Composable
fun TextRelayTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = RelayTypography,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(18.dp), large = RoundedCornerShape(24.dp),
            extraLarge = RoundedCornerShape(28.dp)
        ),
        content = content
    )
}
