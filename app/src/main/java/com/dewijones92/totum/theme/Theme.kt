package com.dewijones92.totum.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val LightColorScheme = lightColorScheme(
    primary = Tangerine40,
    onPrimary = Sand99,
    primaryContainer = Tangerine80,
    onPrimaryContainer = Tangerine20,
    secondary = Cyan40,
    onSecondary = Sand99,
    secondaryContainer = Cyan80,
    onSecondaryContainer = Cyan20,
    tertiary = Lemon40,
    onTertiary = Sand99,
    tertiaryContainer = Lemon80,
    onTertiaryContainer = Lemon20,
    background = Sand99,
    onBackground = Sand10,
    surface = Sand99,
    onSurface = Sand10,
    surfaceVariant = Sand95,
    onSurfaceVariant = Sand30,
    surfaceContainerLowest = Sand100,
    surfaceContainerLow = Sand97,
    surfaceContainer = Sand95,
    surfaceContainerHigh = Sand90,
    surfaceContainerHighest = Sand85,
    outline = Sand50,
    outlineVariant = Sand90,
    error = Red40,
    onError = Sand99,
    errorContainer = Red80,
    onErrorContainer = Red20,
)

private val DarkColorScheme = darkColorScheme(
    primary = Tangerine60,
    onPrimary = Tangerine10,
    primaryContainer = Tangerine20,
    onPrimaryContainer = Tangerine90,
    secondary = Cyan80,
    onSecondary = Cyan20,
    secondaryContainer = Cyan20,
    onSecondaryContainer = Cyan90,
    tertiary = Lemon80,
    onTertiary = Lemon20,
    tertiaryContainer = Lemon20,
    onTertiaryContainer = Lemon90,
    background = Sand10,
    onBackground = Sand95,
    surface = Sand10,
    onSurface = Sand95,
    surfaceVariant = Sand20,
    onSurfaceVariant = Sand90,
    surfaceContainerLowest = Sand5,
    surfaceContainerLow = Sand12,
    surfaceContainer = Sand15,
    surfaceContainerHigh = Sand20,
    surfaceContainerHighest = Sand25,
    outline = Sand50,
    outlineVariant = Sand30,
    error = Red80,
    onError = Red20,
    errorContainer = Red20,
    onErrorContainer = Red80,
)

/**
 * [dynamicColor] is **off by default**, which reverses the original decision recorded in
 * `CLAUDE.md`. Dewi chose a bright, defined brand (2026-07-25), and dynamic colour would
 * substitute the wallpaper's palette on every device that supports it — so the brand
 * would effectively never be seen. Kept as a parameter so a preview can still opt in.
 */
@Composable
fun TotumTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(colorScheme = colorScheme, typography = Typography, shapes = Shapes, content = content)
}
