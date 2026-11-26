package tv.tellyport.ui.theme

import androidx.compose.material3.darkColorScheme as m3DarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme as TvMaterialTheme
import androidx.tv.material3.darkColorScheme as tvDarkColorScheme
import androidx.compose.material3.MaterialTheme as M3MaterialTheme

object TellyportColors {
    val Background = Color(0xFF0B0F19)
    val Surface = Color(0xFF131A2A)
    val SurfaceElevated = Color(0xFF1B253A)
    val SurfaceFocused = Color(0xFF22304C)
    val BorderSubtle = Color(0xFF2A3956)
    val FocusRing = Color(0xFF38BDF8)

    val Primary = Color(0xFF38BDF8)
    val OnPrimary = Color(0xFF062033)
    val Secondary = Color(0xFF818CF8)
    val OnSecondary = Color(0xFF111827)

    val TextPrimary = Color(0xFFF8FAFC)
    val TextSecondary = Color(0xFF94A3B8)
    val TextMuted = Color(0xFF64748B)

    val Success = Color(0xFF22C55E)
    val SuccessContainer = Color(0xFF0F2E1D)
    val Warning = Color(0xFFF59E0B)
    val WarningContainer = Color(0xFF33230A)
    val Danger = Color(0xFFEF4444)
    val DangerContainer = Color(0xFF361215)
    val InfoContainer = Color(0xFF0F2940)
}

@OptIn(ExperimentalTvMaterial3Api::class)
private val TellyportTvColorScheme = tvDarkColorScheme(
    primary = TellyportColors.Primary,
    onPrimary = TellyportColors.OnPrimary,
    primaryContainer = TellyportColors.InfoContainer,
    onPrimaryContainer = TellyportColors.TextPrimary,
    secondary = TellyportColors.Secondary,
    onSecondary = TellyportColors.OnSecondary,
    background = TellyportColors.Background,
    onBackground = TellyportColors.TextPrimary,
    surface = TellyportColors.Surface,
    onSurface = TellyportColors.TextPrimary,
    surfaceVariant = TellyportColors.SurfaceElevated,
    onSurfaceVariant = TellyportColors.TextSecondary,
    error = TellyportColors.Danger,
    onError = TellyportColors.TextPrimary
)

private val TellyportM3ColorScheme = m3DarkColorScheme(
    primary = TellyportColors.Primary,
    onPrimary = TellyportColors.OnPrimary,
    primaryContainer = TellyportColors.InfoContainer,
    onPrimaryContainer = TellyportColors.TextPrimary,
    secondary = TellyportColors.Secondary,
    onSecondary = TellyportColors.OnSecondary,
    background = TellyportColors.Background,
    onBackground = TellyportColors.TextPrimary,
    surface = TellyportColors.Surface,
    onSurface = TellyportColors.TextPrimary,
    surfaceVariant = TellyportColors.SurfaceElevated,
    onSurfaceVariant = TellyportColors.TextSecondary,
    outline = TellyportColors.BorderSubtle,
    error = TellyportColors.Danger,
    onError = TellyportColors.TextPrimary
)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TellyportTheme(content: @Composable () -> Unit) {
    TvMaterialTheme(colorScheme = TellyportTvColorScheme) {
        M3MaterialTheme(
            colorScheme = TellyportM3ColorScheme,
            content = content
        )
    }
}
