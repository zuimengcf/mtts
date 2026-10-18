package com.mtts.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.os.Build

private val LightColors = lightColorScheme(
    primary = Color(0xFF4F6BED),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE0E6FF),
    onPrimaryContainer = Color(0xFF22318F),
    secondary = Color(0xFF8B5CF6),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEDE4FF),
    onSecondaryContainer = Color(0xFF46319C),
    tertiary = Color(0xFF0EA5E9),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD7F1FC),
    onTertiaryContainer = Color(0xFF02466B),
    background = Color(0xFFF8F9FE),
    onBackground = Color(0xFF1A1C20),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1C20),
    surfaceVariant = Color(0xFFF0F1F6),
    onSurfaceVariant = Color(0xFF4A4D55),
    outline = Color(0xFFD0D3DC),
    outlineVariant = Color(0xFFE2E4EC),
    error = Color(0xFFBF4040),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF7C0D0D)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8B9DF6),
    onPrimary = Color(0xFF1A1C20),
    primaryContainer = Color(0xFF3A4B9E),
    onPrimaryContainer = Color(0xFFE0E6FF),
    secondary = Color(0xFFA78BFA),
    onSecondary = Color(0xFF1A1C20),
    secondaryContainer = Color(0xFF5A3FA8),
    onSecondaryContainer = Color(0xFFEDE4FF),
    tertiary = Color(0xFF38BDF8),
    onTertiary = Color(0xFF0A1A24),
    tertiaryContainer = Color(0xFF0A4A6E),
    onTertiaryContainer = Color(0xFFD7F1FC),
    background = Color(0xFF141519),
    onBackground = Color(0xFFE4E5EA),
    surface = Color(0xFF1C1D22),
    onSurface = Color(0xFFE4E5EA),
    surfaceVariant = Color(0xFF26272E),
    onSurfaceVariant = Color(0xFFB9BBC4),
    outline = Color(0xFF383A42),
    outlineVariant = Color(0xFF2E3037),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF3A0202),
    errorContainer = Color(0xFF8C2929),
    onErrorContainer = Color(0xFFFFDAD6)
)

private val AppTypography = Typography(
    displaySmall = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold),
    headlineSmall = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium)
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun MttsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,   // Android 12+ 可开启壁纸动态取色
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val canDynamic = dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val colorScheme = when {
        canDynamic && darkTheme -> dynamicDarkColorScheme(context)
        canDynamic -> dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content
    )
}
