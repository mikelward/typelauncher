package app.typelauncher

import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Single owner of the status/navigation-bar icon contrast for the carousel
 * pages: surface-based while [wallpaperVisible] is off, refined from the
 * wallpaper's own color hints while it is on.
 *
 * Hosted once at the carousel level ([TypeLauncherApp]) and fed the aggregate
 * "any visible page is revealing the wallpaper" flag, rather than per page:
 * a carousel transition composes two visible pages at once (and a canceled
 * drag reverts one of them), so per-page effects fight over the bars during
 * exactly those windows — the reverted page's dispose would restore
 * surface-based icons over a page still showing the wallpaper, with no
 * remaining effect keyed to correct it. One owner keyed on the aggregate has
 * no hand-off to race, and a transition between two wallpaper-revealing pages
 * keeps the refined contrast without re-running anything. The settings page
 * replaces the carousel wholesale (this owner unmounts with it) and manages
 * the bars with its own effect, so the two never overlap.
 *
 * The surface-based value applies immediately on every flip (no IPC); the
 * wallpaper refinement reads `getWallpaperColors` — a system-service Binder
 * IPC — off the main thread and applies only once resolved, so the first
 * frame is never blocked. Mirrors the effect pair HomeScreen owned when Home
 * was the only wallpaper-revealing page.
 */
@Composable
internal fun WallpaperBarContrast(wallpaperVisible: Boolean) {
    val context = LocalContext.current
    val view = LocalView.current
    // Whether the launcher's solid background is light — the no-IPC default
    // (dark icons over a light surface), and what the bars return to when the
    // wallpaper stops showing.
    val surfaceIsLight = MaterialTheme.colorScheme.background.luminance() > 0.5f
    DisposableEffect(wallpaperVisible, surfaceIsLight) {
        val window = context.findActivity()?.window
        if (window != null) {
            val bars = WindowInsetsControllerCompat(window, view)
            bars.isAppearanceLightStatusBars = surfaceIsLight
            bars.isAppearanceLightNavigationBars = surfaceIsLight
        }
        onDispose {
            context.findActivity()?.window?.let { disposeWindow ->
                val bars = WindowInsetsControllerCompat(disposeWindow, view)
                bars.isAppearanceLightStatusBars = surfaceIsLight
                bars.isAppearanceLightNavigationBars = surfaceIsLight
            }
        }
    }
    LaunchedEffect(wallpaperVisible, surfaceIsLight) {
        if (!wallpaperVisible) return@LaunchedEffect
        val darkIcons = withContext(Dispatchers.IO) { wallpaperSupportsDarkText(context) }
            ?: return@LaunchedEffect
        context.findActivity()?.window?.let { window ->
            val bars = WindowInsetsControllerCompat(window, view)
            bars.isAppearanceLightStatusBars = darkIcons
            bars.isAppearanceLightNavigationBars = darkIcons
        }
    }
}

/**
 * Whether dark status/navigation bar icons are legible over the system
 * wallpaper — read from the wallpaper's own `HINT_SUPPORTS_DARK_TEXT` color
 * hint, which the system computes for exactly this (legibility of a dark
 * foreground over the whole wallpaper, not just its dominant color). Exposed
 * for Material You with no permission, unlike the wallpaper *image*. Null when
 * the colors can't be read, so the caller falls back to the launcher surface's
 * own luminance.
 */
internal fun wallpaperSupportsDarkText(context: Context): Boolean? =
    try {
        WallpaperManager.getInstance(context)
            .getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
            ?.let { (it.colorHints and WallpaperColors.HINT_SUPPORTS_DARK_TEXT) != 0 }
    } catch (_: Exception) {
        null
    }
