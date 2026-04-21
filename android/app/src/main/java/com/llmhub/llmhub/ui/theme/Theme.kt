package com.llmhub.llmhub.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.llmhub.llmhub.components.NeuralHubColors
import com.llmhub.llmhub.data.ThemeMode

private val BorderDark = Color(0xFF1A1A1E)

private val DarkColorScheme = darkColorScheme(
    primary = NeuralHubColors.AccentCyan,
    onPrimary = Color.Black,
    primaryContainer = Color(0x1A00F2FF),
    onPrimaryContainer = NeuralHubColors.AccentCyan,
    secondary = NeuralHubColors.AccentMagenta,
    onSecondary = Color.Black,
    secondaryContainer = Color(0x1AFF00EA),
    onSecondaryContainer = NeuralHubColors.AccentMagenta,
    tertiary = NeuralHubColors.AccentGreen,
    onTertiary = Color.Black,
    tertiaryContainer = Color(0x1A00FFAA),
    onTertiaryContainer = NeuralHubColors.AccentGreen,
    error = Error80,
    onError = Error20,
    errorContainer = Error30,
    onErrorContainer = Error90,
    background = NeuralHubColors.Bg,
    onBackground = Color.White,
    surface = NeuralHubColors.Bg,
    onSurface = Color.White,
    surfaceVariant = NeuralHubColors.Glass,
    onSurfaceVariant = NeuralHubColors.TextDim,
    outline = NeuralHubColors.GlassBorder,
    outlineVariant = BorderDark,
    scrim = Neutral0,
    inverseSurface = Neutral90,
    inverseOnSurface = Neutral20,
    inversePrimary = Primary40,
    surfaceDim = NeuralHubColors.Bg,
    surfaceBright = NeuralHubColors.Glass,
    surfaceContainerLowest = NeuralHubColors.Bg,
    surfaceContainerLow = NeuralHubColors.Bg,
    surfaceContainer = NeuralHubColors.Bg,
    surfaceContainerHigh = NeuralHubColors.Glass,
    surfaceContainerHighest = NeuralHubColors.GlassHighlight
)

private val LightColorScheme = lightColorScheme(
    primary = NeuralHubColors.AccentCyan,
    onPrimary = Color.Black,
    primaryContainer = Color(0x1A00F2FF),
    onPrimaryContainer = NeuralHubColors.AccentCyan,
    secondary = NeuralHubColors.AccentMagenta,
    onSecondary = Color.Black,
    secondaryContainer = Color(0x1AFF00EA),
    onSecondaryContainer = NeuralHubColors.AccentMagenta,
    tertiary = NeuralHubColors.AccentGreen,
    onTertiary = Color.Black,
    tertiaryContainer = Color(0x1A00FFAA),
    onTertiaryContainer = NeuralHubColors.AccentGreen,
    error = Error40,
    onError = Color(0xFFFFFFFF),
    errorContainer = Error90,
    onErrorContainer = Error10,
    background = Neutral99,
    onBackground = Neutral10,
    surface = Neutral99,
    onSurface = Neutral10,
    surfaceVariant = Neutral90,
    onSurfaceVariant = Neutral30,
    outline = Neutral50,
    outlineVariant = Neutral80,
    scrim = Neutral0,
    inverseSurface = Neutral20,
    inverseOnSurface = Neutral95,
    inversePrimary = Primary80,
    surfaceDim = Neutral87,
    surfaceBright = Neutral98,
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Neutral96,
    surfaceContainer = Neutral94,
    surfaceContainerHigh = Neutral92,
    surfaceContainerHighest = Neutral90
)

@Composable
fun LlmHubTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // Use surface color for better readability
            window.statusBarColor = colorScheme.surface.toArgb()
            window.navigationBarColor = colorScheme.surface.toArgb()
            
            // Set status bar and nav bar content to dark in light theme, light in dark theme
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !darkTheme
            insetsController.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = Shapes,
        content = content
    )
}