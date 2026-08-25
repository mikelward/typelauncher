package app.typelauncher

import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
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
    // Re-reads the hint whenever the wallpaper itself changes, not just when
    // the setting flips — Settings can now send the user to the system picker
    // and back (Settings -> Wallpaper -> Change), so a swap mid-session is an
    // ordinary flow rather than something that could only happen with the
    // launcher out of the picture.
    val wallpaperColorsGeneration = rememberWallpaperColorsGeneration(wallpaperVisible)
    LaunchedEffect(wallpaperVisible, surfaceIsLight, wallpaperColorsGeneration) {
        if (!wallpaperVisible) return@LaunchedEffect
        // Falls back to the surface-based value when the new wallpaper exposes
        // no hint, rather than keeping the previous one's: on a refresh the
        // cached value belongs to a wallpaper that is no longer on screen, and
        // it can be exactly the wrong way round for the one that is.
        val darkIcons = withContext(Dispatchers.IO) { wallpaperSupportsDarkText(context) }
            ?: surfaceIsLight
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

/**
 * Registers a callback for system wallpaper color changes. Exists as a seam so
 * the refresh below can be tested: Robolectric's `ShadowWallpaperManager` has
 * no colors API at all, so the framework path cannot be driven from a JVM
 * test, but everything worth testing — which changes count, and whether the
 * registration is released — lives above this interface rather than inside it.
 */
internal fun interface WallpaperColorsRegistrar {
    /**
     * Registers [onColorsChanged], which receives the framework's `which`
     * bitmask. Returns a function that unregisters it, or null when the
     * callback is unavailable on this device.
     */
    fun register(onColorsChanged: (which: Int) -> Unit): (() -> Unit)?
}

/**
 * The real registrar, over [WallpaperManager]. Callbacks are delivered on the
 * main-thread handler so a tick lands where Compose state expects it.
 */
internal class AndroidWallpaperColorsRegistrar(private val context: Context) : WallpaperColorsRegistrar {
    override fun register(onColorsChanged: (which: Int) -> Unit): (() -> Unit)? {
        val manager = WallpaperManager.getInstance(context)
        val listener = WallpaperManager.OnColorsChangedListener { _, which -> onColorsChanged(which) }
        // Named rather than returned as a trailing block: a bare lambda right
        // after the register call parses as an argument to it.
        val unregister = {
            try {
                manager.removeOnColorsChangedListener(listener)
            } catch (exception: RuntimeException) {
                // Nothing left to release if the framework already dropped it;
                // log so a leaked listener is traceable, not silent.
                LauncherDebugLog.warning("wallpaper colors listener removal failed", exception)
            }
        }
        return try {
            manager.addOnColorsChangedListener(listener, Handler(Looper.getMainLooper()))
            unregister
        } catch (exception: RuntimeException) {
            LauncherDebugLog.warning("wallpaper colors listener unavailable", exception)
            null
        }
    }
}

/**
 * Emits once per *system* wallpaper color change.
 *
 * `FLAG_SYSTEM` only: the lock-screen wallpaper never backs the launcher's
 * window, so its colors say nothing about this contrast.
 *
 * **Emits once as soon as the subscription is established**, before any change
 * arrives. That closes a race rather than being a convenience: the consumer's
 * first contrast read and this registration happen independently, so a
 * wallpaper changed in between would be observed by neither — the read returns
 * the old colors and the listener wasn't live yet — and the stale contrast
 * would then persist indefinitely. The establishment tick makes the consumer
 * re-read once it is actually listening, so the only state it can settle on is
 * one it either read after subscribing or was told about.
 *
 * `callbackFlow` owns the lifecycle, which is what makes the release safe
 * under cancellation: `awaitClose` runs whether the collector is cancelled
 * mid-registration or long after, so there is no window where the callback is
 * registered but the cleanup path has been skipped. A device with no callback
 * completes immediately and never emits, so the caller simply keeps the
 * contrast it already resolved.
 *
 * Deliberately carries no `flowOn` of its own — the caller chooses the
 * dispatcher (see [rememberWallpaperColorsGeneration], which registers off the
 * main thread). Pinning it here would put the producer on a real dispatcher
 * that a test scheduler cannot advance.
 */
internal fun wallpaperColorChanges(registrar: WallpaperColorsRegistrar): Flow<Unit> = callbackFlow {
    val unregister = registrar.register { which ->
        if (which and WallpaperManager.FLAG_SYSTEM != 0) trySend(Unit)
    }
    if (unregister == null) {
        // `callbackFlow` requires the channel to be closed before the block
        // returns; closing it finishes the flow rather than hanging a
        // collector on a callback that never comes.
        close()
        return@callbackFlow
    }
    // The establishment tick, sent only once the listener is actually live.
    trySend(Unit)
    awaitClose(unregister)
}

/**
 * Ticks each time the system wallpaper's colors change, so an effect keyed on
 * it re-reads [wallpaperSupportsDarkText] instead of holding the hint it
 * resolved once.
 *
 * This exists because the launcher can hand off to the system wallpaper picker
 * from inside Settings. Before that, changing the wallpaper meant leaving the
 * launcher altogether and coming back to a fresh read; now the user can leave
 * Settings, pick a bright wallpaper, and return to a page still drawing its
 * bare text and system-bar icons for the dark one it resolved on the way in —
 * unreadable until Settings is closed or the activity is recreated. Keyed on
 * the wallpaper's own colors rather than on the picker's return, so a change
 * made by any other app is picked up too.
 *
 * Ticks once when the listener goes live (see [wallpaperColorChanges]) and
 * once per system wallpaper change after that. Stays 0 and never ticks while
 * [enabled] is false.
 */
@Composable
internal fun rememberWallpaperColorsGeneration(
    enabled: Boolean,
    // Null means the real one over WallpaperManager; tests pass a fake. Held in
    // `remember` rather than defaulted in the signature so the effect keys on a
    // stable instance instead of restarting on every recomposition.
    registrar: WallpaperColorsRegistrar? = null,
): Int {
    val context = LocalContext.current
    val colorsRegistrar = remember(context, registrar) {
        registrar ?: AndroidWallpaperColorsRegistrar(context)
    }
    var generation by remember { mutableIntStateOf(0) }
    LaunchedEffect(colorsRegistrar, enabled) {
        if (!enabled) return@LaunchedEffect
        // Registration and release are Binder calls into the wallpaper service,
        // and this composable is hosted at the carousel level and on the
        // Settings page — both first-frame paths, where a slow system service
        // must not cost a frame.
        wallpaperColorChanges(colorsRegistrar)
            .flowOn(Dispatchers.IO)
            .collect { generation++ }
    }
    return generation
}
