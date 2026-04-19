package org.tyflocentrum.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// a11y: needs manual verification - sprawdz kontrast niestandardowej palety i wskaznikow fokusu na realnym urzadzeniu.
private val LightColors = lightColorScheme(
    primary = Color(0xFF005345),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF7BD9C0),
    onPrimaryContainer = Color(0xFF002019),
    secondary = Color(0xFF5B4200),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFF8F4EC),
    onBackground = Color(0xFF1C1B17),
    surface = Color(0xFFFFFBF3),
    onSurface = Color(0xFF1C1B17),
    surfaceVariant = Color(0xFFE5DFD0),
    onSurfaceVariant = Color(0xFF49463D),
    outline = Color(0xFF797568),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7BD9C0),
    onPrimary = Color(0xFF00382D),
    primaryContainer = Color(0xFF005345),
    onPrimaryContainer = Color(0xFFA2F5DA),
    secondary = Color(0xFFF0BE5C),
    onSecondary = Color(0xFF312100),
    background = Color(0xFF141310),
    onBackground = Color(0xFFE8E2D9),
    surface = Color(0xFF1C1B17),
    onSurface = Color(0xFFE8E2D9),
    surfaceVariant = Color(0xFF49463D),
    onSurfaceVariant = Color(0xFFCAC5B7),
    outline = Color(0xFF938F82),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005)
)

@Composable
fun TyflocentrumTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
