package app.typelauncher

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Where the long-press menus get an app's shortcuts from, and what tapping one
 * does. Provided once at the top of the tree so every app menu — app list,
 * search results, dock, dock folders, recents — offers them without threading
 * three more parameters through each.
 */
@Stable
internal class AppShortcutMenuSource(
    val shortcuts: StateFlow<Map<AppShortcutKey, List<AppShortcut>>>,
    val onLaunch: (InstalledApp, AppShortcut) -> Unit,
    val loadIcon: suspend (AppShortcut, sizePx: Int) -> ImageBitmap?,
) {
    companion object {
        /** No shortcuts: previews and tests that don't exercise them. */
        val None = AppShortcutMenuSource(MutableStateFlow(emptyMap()), { _, _ -> }, { _, _ -> null })
    }
}

internal val LocalAppShortcutMenu = staticCompositionLocalOf { AppShortcutMenuSource.None }

private val AppShortcutIconSize = 24.dp

/**
 * [app]'s own shortcuts, as the first rows of its long-press menu, with a
 * divider below them. Nothing at all when it has none or they haven't loaded
 * yet — the menu never waits on them — and none for a paused work app, which
 * can't launch, or a pinned web page, which is a shortcut itself.
 */
@Composable
internal fun ColumnScope.AppShortcutMenuItems(app: InstalledApp, onDismiss: () -> Unit) {
    if (app.isShortcut || app.isQuietMode) return
    val source = LocalAppShortcutMenu.current
    val all by source.shortcuts.collectAsState()
    val shortcuts = remember(all, app) { menuShortcutsFor(app, all) }
    if (shortcuts.isEmpty()) return
    shortcuts.forEach { shortcut ->
        DropdownMenuItem(
            text = { Text(shortcut.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1) },
            leadingIcon = { AppShortcutIcon(app, shortcut, source) },
            modifier = Modifier.testTag("$APP_SHORTCUT_ACTION_TAG:${shortcut.label}"),
            onClick = {
                onDismiss()
                source.onLaunch(app, shortcut)
            },
        )
    }
    HorizontalDivider(
        modifier = Modifier
            .padding(vertical = 4.dp)
            .testTag(APP_SHORTCUTS_DIVIDER_TAG),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

// A shortcut's icon is optional: one published without any (or whose icon
// fails to load) shows its app's icon instead of an empty tile, as it does
// while the shortcut's own is still loading.
@Composable
private fun AppShortcutIcon(app: InstalledApp, shortcut: AppShortcut, source: AppShortcutMenuSource) {
    val sizePx = with(LocalDensity.current) { AppShortcutIconSize.roundToPx() }.coerceAtLeast(1)
    val bitmap by produceState<ImageBitmap?>(null, shortcut, sizePx) {
        value = source.loadIcon(shortcut, sizePx)
    }
    val appIcon = rememberAppIconBitmap(app, AppShortcutIconSize)
    val shape = LocalAppIconShape.current.toComposeShape()
    Box(
        modifier = Modifier
            .size(AppShortcutIconSize)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        (bitmap ?: appIcon)?.let { icon ->
            Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(AppShortcutIconSize))
        }
    }
}
