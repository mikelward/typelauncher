package app.typelauncher

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties

// Shared styling for the launcher's dropdown menus (app actions, widget
// actions, settings pickers, the overflow menu). Material 3's default menu
// surface uses a near-square 4dp corner and 14sp label text; rounding the
// corners and bumping the text one step up gives the popups a softer, more
// modern Material feel without changing their behavior.

/**
 * Corner radius for launcher dropdown-menu surfaces. A touch more rounded than
 * the Material 3 default (4dp) so the popups read as a soft card rather than a
 * sharp box. On the 4dp spacing grid.
 */
private val LauncherMenuCornerRadius = 16.dp

private val LauncherMenuShape = RoundedCornerShape(LauncherMenuCornerRadius)

/**
 * [DropdownMenu] with the launcher's rounded-corner surface. Behaves exactly
 * like the Material 3 menu otherwise; route every launcher menu through this so
 * the corner radius stays consistent across app actions, widget actions, and
 * the settings pickers.
 */
@Composable
internal fun LauncherDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    properties: PopupProperties = PopupProperties(focusable = true),
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        shape = LauncherMenuShape,
        properties = properties,
        content = content,
    )
}

/**
 * Text slot for a [LauncherDropdownMenu] item. Renders one type step up from the
 * Material 3 menu default (`bodyLarge`/16sp vs `labelLarge`/14sp) so the actions
 * are a little easier to read at a glance.
 */
@Composable
internal fun LauncherMenuItemText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge)
}
