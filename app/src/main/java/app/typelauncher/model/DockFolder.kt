package app.typelauncher

import androidx.compose.runtime.Immutable
import java.util.UUID

/**
 * A folder occupying a single dock slot. A folder is a *dock occupant* exactly
 * like an app: it lives in the store's occupant id list and carries a
 * [DockPosition], so all of the existing grid machinery
 * ([resolvedDockPositions], [nextAvailableDockPosition],
 * [dockedAppIdsInGridRankOrder]) works on it unchanged.
 *
 * [memberAppIds] are [InstalledApp.id] values, in display order: the first four
 * drive the 2×2 mini-icon corners and the full list drives the open popup grid.
 * A member app id lives in exactly one place — either the store's top-level
 * occupant list (a loose docked app) or one folder's [memberAppIds], never both.
 */
@Immutable
internal data class DockFolder(
    val id: String,
    val memberAppIds: List<String>,
    val name: String? = null,
)

/**
 * Largest number of apps a single dock folder holds. Sized to the 4×4 popup
 * grid; merges/adds past this are no-ops in [DockedAppStore].
 */
internal const val MAX_DOCK_FOLDER_MEMBERS = 16

/**
 * Reserved prefix for folder ids. [InstalledApp.id] is always
 * `"${user.hashCode()}:${component-or-package}"`, whose segment before the first
 * `:` is an integer, so a literal `folder:` prefix can never be produced by that
 * scheme — folder ids and app ids never collide.
 */
internal const val DOCK_FOLDER_ID_PREFIX = "folder:"

internal fun newDockFolderId(): String = DOCK_FOLDER_ID_PREFIX + UUID.randomUUID()

internal fun String.isDockFolderId(): Boolean = startsWith(DOCK_FOLDER_ID_PREFIX)

/**
 * A [DockFolder] resolved for rendering: [members] are the hydrated
 * [InstalledApp]s for the currently-installed, non-hidden member ids (in
 * persisted order), so the UI never has to resolve ids itself. Hidden or
 * uninstalled members are dropped from [members] but kept in persistence so an
 * unhide / reinstall restores them.
 */
@Immutable
internal data class ResolvedDockFolder(
    val id: String,
    val members: List<InstalledApp>,
    val name: String? = null,
)
