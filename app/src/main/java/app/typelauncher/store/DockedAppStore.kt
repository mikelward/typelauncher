package app.typelauncher

import android.content.Context
import java.util.LinkedHashSet

internal class DockedAppStore(
    context: Context,
    preferencesName: String = DEFAULT_PREFERENCES_NAME,
) {
    private val sharedPreferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    // Guards every read and write of the in-memory dock state below. `dock`,
    // `undock`, and `move` each read the current `dockedIds` /
    // `dockPositions`, compute a new value, then write it back — a classic
    // read-modify-write. Without a lock, two of these running concurrently
    // (a user drag on the main thread racing a background reader, or any
    // future off-main-thread caller) can lose one update, and a reader can
    // observe `dockedIds` and `dockPositions` mid-mutation — half-applied
    // state where the two maps disagree, or a `ConcurrentModificationException`
    // thrown out of `toList()` / `joinToString` while another thread mutates
    // the `LinkedHashSet`. Holding `lock` across each whole operation makes the
    // mutations atomic and the snapshots returned by the getters internally
    // consistent. Contention is effectively nil (dock edits are rare and
    // user-driven), so the uncontended monitor acquisition adds no measurable
    // cost to the main thread.
    private val lock = Any()

    private var dockedIds = sharedPreferences.getString(KEY_DOCKED_APP_IDS, "").orEmpty()
        .split(DOCKED_APP_ID_SEPARATOR)
        .filter { appId -> appId.isNotBlank() }
        .toCollection(LinkedHashSet())
    private var dockPositions = sharedPreferences.getString(KEY_DOCKED_APP_POSITIONS, "").orEmpty()
        .parseDockPositions()

    // Folders are occupants too: their ids live in `dockedIds` / `dockPositions`
    // alongside app ids, and only the member-app mapping lives here. Keyed by
    // folder id, insertion-ordered for a stable serialization round-trip.
    private var folders: LinkedHashMap<String, DockFolder> =
        sharedPreferences.getString(KEY_DOCK_FOLDERS, "").orEmpty().parseDockFolders()

    val dockedAppIds: List<String>
        get() = synchronized(lock) { dockedIds.toList() }

    val dockedAppPositions: Map<String, DockPosition>
        get() = synchronized(lock) { dockPositions.toMap() }

    /** Snapshot of every folder in this dock (occupant id, members, name). */
    val dockFolders: List<DockFolder>
        get() = synchronized(lock) { folders.values.toList() }

    /** Every app id that currently lives inside any folder in this dock. */
    val folderMemberAppIds: Set<String>
        get() = synchronized(lock) { folders.values.flatMapTo(mutableSetOf()) { it.memberAppIds } }

    /**
     * Every app id that is "on the dock": loose docked apps ∪ all folder members
     * (folder occupant ids themselves excluded). The single source of truth for
     * "is this app docked?" — used by the main-list dedup, search docked-first
     * ranking, cold-start icon-snapshot priority, and work-dock prefill — so a
     * folder member is treated like a docked app everywhere without each
     * call-site re-deriving the union.
     */
    val dockedOrFolderedAppIds: Set<String>
        get() = synchronized(lock) {
            dockedIds.filterNotTo(mutableSetOf()) { it.isDockFolderId() } +
                folders.values.flatMap { it.memberAppIds }
        }

    /**
     * Expand any folder occupant in [occupantIds] to its members **in place**,
     * preserving order; a loose app id passes through unchanged. Used by the
     * search docked-first ranking so a foldered app floats at the folder's own
     * dock-rank position rather than after every top-level occupant.
     */
    fun expandFolderOccupants(occupantIds: List<String>): List<String> = synchronized(lock) {
        occupantIds.flatMap { id -> folders[id]?.memberAppIds ?: listOf(id) }
    }

    private fun isFolderMemberLocked(appId: String): Boolean =
        folders.values.any { folder -> appId in folder.memberAppIds }

    /** True when [appId] is a member of any folder in this dock. */
    fun isFolderMember(appId: String): Boolean = synchronized(lock) { isFolderMemberLocked(appId) }

    /** The id of the folder containing [appId], or null if it is not in one. */
    fun folderIdContaining(appId: String): String? = synchronized(lock) {
        folders.entries.firstOrNull { (_, folder) -> appId in folder.memberAppIds }?.key
    }

    fun dockedAppIdsFor(sortOrder: AppListSortOrder, columnCount: Int): List<String> =
        synchronized(lock) {
            dockedAppIdsInGridRankOrder(dockedIds.toList(), dockPositions, columnCount, sortOrder)
        }

    fun contains(appId: String): Boolean = synchronized(lock) { appId in dockedIds }

    fun dock(appId: String, columnCount: Int = DEFAULT_DOCK_ICON_COUNT) = synchronized(lock) {
        // A folder member is already on the dock (inside a folder), so dock() is
        // a no-op for it. Taking it out of the folder is the explicit
        // "Move out of folder" action (see [folderIdContaining] / removeFromFolder).
        if (appId in dockedIds || isFolderMemberLocked(appId)) {
            return@synchronized
        }
        // Re-resolve the persisted coordinates into their compacted form before
        // picking the new slot. resolvedDockPositions collapses empty rows for
        // rendering, so an app deleted from the top row leaves the survivors
        // persisted at row >= 1 even though they render at row 0. Choosing the
        // next slot against the raw sparse map would hand the new app a
        // coordinate that collides with a survivor, displacing it on the next
        // resolve. Compacting first keeps the persisted map and the new slot in
        // the same coordinate space, matching what `move` already does.
        dockPositions = resolvedDockPositions(dockedIds.toList(), dockPositions, columnCount).toMutableMap()
        dockPositions[appId] = nextAvailableDockPosition(dockedIds.toList(), dockPositions, columnCount)
        dockedIds.add(appId)
        // The "+" add-button hint is a one-shot onboarding affordance set after
        // prefill (LauncherViewModel.setShowAddButtonHint). Any successful dock
        // — prefill's own seeds *or* a user-initiated `toggleDock` — clears it.
        // Prefill calls this before the ViewModel turns the flag on, so it
        // can't clear what was never set; the first user dock after prefill
        // is the only path that actually flips it off.
        save(clearShowAddButtonHint = true)
    }

    fun undock(appId: String) = synchronized(lock) {
        if (dockedIds.remove(appId)) {
            dockPositions.remove(appId)
            save()
        }
    }

    fun move(appId: String, row: Int, column: Int, columnCount: Int, sortOrder: AppListSortOrder) =
        synchronized(lock) {
            if (appId !in dockedIds) return@synchronized
            val columns = columnCount.coerceAtLeast(1)
            val target = DockPosition(row.coerceAtLeast(0), column.coerceIn(0, columns - 1))
            val currentPositions = resolvedDockPositions(dockedIds.toList(), dockPositions, columns)
            val previous = currentPositions[appId]
            val occupant = currentPositions.entries.firstOrNull { (id, position) ->
                id != appId && position == target
            }?.key
            // Rebase the persisted map onto the compacted view before mutating.
            // `previous`, `occupant`, and `target` are all coordinates in the
            // compacted space the user sees, while the persisted map can still
            // hold sparse rows left behind by `undock` (it only removes the
            // entry). Writing compacted coordinates into the sparse map mixes
            // the two spaces: a bystander still persisted at a stale row keeps
            // that row, and because the moved/swapped apps now occupy the rows
            // in between, the row-collapse pass in `resolvedDockPositions`
            // can no longer repair it — the bystander is stranded on its own
            // row and the corruption is saved. Compact-first matches `dock`.
            dockPositions = currentPositions.toMutableMap()
            dockPositions[appId] = target
            if (occupant != null && previous != null) {
                dockPositions[occupant] = previous
            }
            dockPositions = resolvedDockPositions(dockedIds.toList(), dockPositions, columns).toMutableMap()
            dockedIds = LinkedHashSet(dockedAppIdsInGridRankOrder(dockedIds.toList(), dockPositions, columns, sortOrder))
            save()
        }

    /**
     * Merge two dock occupants into a folder and return the resulting folder id.
     * When [targetId] is already a folder, [sourceId] (or, if it too is a folder,
     * its members) is appended to it. Otherwise a new folder is created at the
     * target's slot holding `[targetId, sourceId]` (target first, so the existing
     * icon stays the primary mini-icon corner). The source occupant is removed
     * from the top-level list; the resulting folder takes the target's slot.
     * No-op (returns null) when the ids are equal, an id is missing, or the
     * merge would exceed [MAX_DOCK_FOLDER_MEMBERS].
     */
    fun mergeIntoFolder(sourceId: String, targetId: String, columnCount: Int = DEFAULT_DOCK_ICON_COUNT): String? =
        synchronized(lock) {
            if (sourceId == targetId) return@synchronized null
            if (sourceId !in dockedIds || targetId !in dockedIds) return@synchronized null
            val columns = columnCount.coerceAtLeast(1)
            compactLocked(columns)
            val sourceMembers = folders[sourceId]?.memberAppIds ?: listOf(sourceId)
            val targetFolder = folders[targetId]
            val resultId: String
            if (targetFolder != null) {
                val merged = (targetFolder.memberAppIds + sourceMembers).distinct()
                if (merged.size > MAX_DOCK_FOLDER_MEMBERS) return@synchronized null
                folders[targetId] = targetFolder.copy(memberAppIds = merged)
                removeOccupantLocked(sourceId)
                resultId = targetId
            } else {
                val members = (listOf(targetId) + sourceMembers).distinct()
                if (members.size > MAX_DOCK_FOLDER_MEMBERS) return@synchronized null
                val targetPosition = dockPositions[targetId] ?: return@synchronized null
                val folderId = newDockFolderId()
                removeOccupantLocked(sourceId)
                removeOccupantLocked(targetId)
                folders[folderId] = DockFolder(folderId, members)
                dockedIds.add(folderId)
                dockPositions[folderId] = targetPosition
                resultId = folderId
            }
            compactLocked(columns)
            save()
            resultId
        }

    /** Add a loose docked app into an existing folder. */
    fun addToFolder(folderId: String, appId: String, columnCount: Int = DEFAULT_DOCK_ICON_COUNT) =
        synchronized(lock) {
            val folder = folders[folderId] ?: return@synchronized
            if (appId !in dockedIds || appId.isDockFolderId()) return@synchronized
            if (appId in folder.memberAppIds) return@synchronized
            if (folder.memberAppIds.size >= MAX_DOCK_FOLDER_MEMBERS) return@synchronized
            val columns = columnCount.coerceAtLeast(1)
            compactLocked(columns)
            folders[folderId] = folder.copy(memberAppIds = folder.memberAppIds + appId)
            removeOccupantLocked(appId)
            compactLocked(columns)
            save()
        }

    /**
     * Remove [appId] from [folderId] and re-dock it as a loose icon. If the
     * folder is left with a single member, it auto-collapses: the lone member takes
     * over the folder's slot and the folder record is deleted.
     */
    fun removeFromFolder(folderId: String, appId: String, columnCount: Int = DEFAULT_DOCK_ICON_COUNT) =
        synchronized(lock) {
            val folder = folders[folderId] ?: return@synchronized
            if (appId !in folder.memberAppIds) return@synchronized
            val columns = columnCount.coerceAtLeast(1)
            compactLocked(columns)
            val remaining = folder.memberAppIds - appId
            if (remaining.size <= 1) {
                val folderPosition = dockPositions[folderId]
                removeOccupantLocked(folderId)
                folders.remove(folderId)
                val lone = remaining.firstOrNull()
                if (lone != null) {
                    dockedIds.add(lone)
                    if (folderPosition != null) {
                        dockPositions[lone] = folderPosition
                    } else {
                        dockPositions[lone] = nextAvailableDockPosition(dockedIds.toList(), dockPositions, columns)
                    }
                }
                placeAtNextSlotLocked(appId, columns)
            } else {
                folders[folderId] = folder.copy(memberAppIds = remaining)
                placeAtNextSlotLocked(appId, columns)
            }
            compactLocked(columns)
            save()
        }

    /**
     * Rename a folder. A blank name clears it back to unnamed. The name is
     * sanitized of the field/record separators (`\t`, `\n`, `\r`) used by the
     * `docked_folders` serialization, so a pasted name containing one can't
     * corrupt the on-disk format and make the folder unparsable on reload.
     */
    fun renameFolder(folderId: String, name: String?) = synchronized(lock) {
        val folder = folders[folderId] ?: return@synchronized
        val sanitized = name
            ?.replace('\t', ' ')
            ?.replace('\n', ' ')
            ?.replace('\r', ' ')
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        folders[folderId] = folder.copy(name = sanitized)
        save()
    }

    /**
     * Move [appId] to [targetIndex] within [folderId]'s member order (a no-op if
     * the folder or app is gone, or the index is unchanged). Backs the Overlay
     * folder card's drag-to-reorder. The index is clamped into range.
     */
    fun reorderFolderMember(folderId: String, appId: String, targetIndex: Int) = synchronized(lock) {
        val folder = folders[folderId] ?: return@synchronized
        val currentIndex = folder.memberAppIds.indexOf(appId)
        if (currentIndex < 0) return@synchronized
        val clamped = targetIndex.coerceIn(0, folder.memberAppIds.size - 1)
        if (clamped == currentIndex) return@synchronized
        val reordered = folder.memberAppIds.toMutableList()
        reordered.removeAt(currentIndex)
        reordered.add(clamped, appId)
        folders[folderId] = folder.copy(memberAppIds = reordered)
        save()
    }

    /**
     * Explode a folder: replace the folder occupant with its members as loose
     * icons (the first member takes the folder's slot, the rest fill the next
     * available slots).
     */
    fun explodeFolder(folderId: String, columnCount: Int = DEFAULT_DOCK_ICON_COUNT) = synchronized(lock) {
        val folder = folders[folderId] ?: return@synchronized
        val columns = columnCount.coerceAtLeast(1)
        compactLocked(columns)
        val folderPosition = dockPositions[folderId]
        removeOccupantLocked(folderId)
        folders.remove(folderId)
        folder.memberAppIds.forEachIndexed { index, memberId ->
            if (index == 0 && folderPosition != null) {
                dockedIds.add(memberId)
                dockPositions[memberId] = folderPosition
            } else {
                placeAtNextSlotLocked(memberId, columns)
            }
        }
        compactLocked(columns)
        save()
    }

    // Re-resolve the persisted coordinates into their compacted form, matching
    // what `dock` / `move` do before mutating. Keeps the persisted map and any
    // newly chosen slot in the same coordinate space.
    private fun compactLocked(columns: Int) {
        dockPositions = resolvedDockPositions(dockedIds.toList(), dockPositions, columns).toMutableMap()
    }

    private fun removeOccupantLocked(occupantId: String) {
        dockedIds.remove(occupantId)
        dockPositions.remove(occupantId)
    }

    private fun placeAtNextSlotLocked(occupantId: String, columns: Int) {
        dockPositions[occupantId] = nextAvailableDockPosition(dockedIds.toList(), dockPositions, columns)
        dockedIds.add(occupantId)
    }

    val hasBeenPrefilled: Boolean
        get() = sharedPreferences.getBoolean(KEY_DOCK_PREFILLED, false)

    fun markPrefilled() {
        sharedPreferences.edit().putBoolean(KEY_DOCK_PREFILLED, true).apply()
    }

    /**
     * One-shot onboarding flag for the dock's "+" add-button hint. Defaults to
     * `false`, so upgrade installs (and any state never reached via prefill)
     * never advertise the hint. Set true by `LauncherViewModel` right after a
     * fresh-install prefill that left an empty slot in the first row; cleared
     * inside [dock] the first time the user adds an app to this dock.
     */
    val shouldShowAddButtonHint: Boolean
        get() = sharedPreferences.getBoolean(KEY_SHOW_ADD_BUTTON_HINT, false)

    fun setShowAddButtonHint(show: Boolean) {
        sharedPreferences.edit().putBoolean(KEY_SHOW_ADD_BUTTON_HINT, show).apply()
    }

    private fun save(clearShowAddButtonHint: Boolean = false) {
        // Drop folders that are no longer valid occupants (their id fell out of
        // `dockedIds`, e.g. undocked) or that have decayed to zero members, so a
        // stale record can never be re-loaded as a phantom slot.
        folders.entries.removeAll { (folderId, folder) ->
            folderId !in dockedIds || folder.memberAppIds.isEmpty()
        }
        val editor = sharedPreferences.edit()
            .putString(KEY_DOCKED_APP_IDS, dockedIds.joinToString(DOCKED_APP_ID_SEPARATOR))
            .putString(
                KEY_DOCKED_APP_POSITIONS,
                dockPositions.filterKeys { appId -> appId in dockedIds }.toPreferencesString(),
            )
            .putString(KEY_DOCK_FOLDERS, foldersToPreferencesString(folders))
        if (clearShowAddButtonHint) {
            editor.putBoolean(KEY_SHOW_ADD_BUTTON_HINT, false)
        }
        editor.apply()
    }

    companion object {
        internal const val DEFAULT_PREFERENCES_NAME = "docked_apps"
        internal const val WORK_PREFERENCES_NAME = "work_docked_apps"
        private const val KEY_DOCKED_APP_IDS = "docked_app_ids"
        private const val KEY_DOCKED_APP_POSITIONS = "docked_app_positions"
        private const val KEY_DOCK_FOLDERS = "docked_folders"
        private const val KEY_DOCK_PREFILLED = "dock_prefilled"
        private const val KEY_SHOW_ADD_BUTTON_HINT = "show_add_button_hint"
        private const val DOCKED_APP_ID_SEPARATOR = "\n"
        private const val DOCK_POSITION_FIELD_SEPARATOR = "\t"
        private const val DOCK_FOLDER_MEMBER_SEPARATOR = ","
    }

    private fun String.parseDockPositions(): MutableMap<String, DockPosition> =
        lineSequence()
            .mapNotNull { line ->
                val fields = line.split(DOCK_POSITION_FIELD_SEPARATOR)
                if (fields.size != 3) return@mapNotNull null
                val row = fields[1].toIntOrNull() ?: return@mapNotNull null
                val column = fields[2].toIntOrNull() ?: return@mapNotNull null
                fields[0].takeIf { it.isNotBlank() }?.let { appId -> appId to DockPosition(row, column) }
            }
            .toMap()
            .toMutableMap()

    private fun Map<String, DockPosition>.toPreferencesString(): String =
        entries.joinToString(DOCKED_APP_ID_SEPARATOR) { (appId, position) ->
            listOf(appId, position.row.toString(), position.column.toString())
                .joinToString(DOCK_POSITION_FIELD_SEPARATOR)
        }

    // One folder per line: folderId \t name \t memberId,memberId,... The name
    // field may be empty (unnamed). Member ids are "${int}:${component}" and
    // never contain a comma, so a comma-joined list round-trips cleanly.
    private fun String.parseDockFolders(): LinkedHashMap<String, DockFolder> {
        val result = LinkedHashMap<String, DockFolder>()
        lineSequence().forEach { line ->
            if (line.isBlank()) return@forEach
            val fields = line.split(DOCK_POSITION_FIELD_SEPARATOR)
            if (fields.size != 3) return@forEach
            val folderId = fields[0].takeIf { it.isNotBlank() } ?: return@forEach
            val name = fields[1].takeIf { it.isNotBlank() }
            val members = fields[2].split(DOCK_FOLDER_MEMBER_SEPARATOR).filter { it.isNotBlank() }
            if (members.isEmpty()) return@forEach
            result[folderId] = DockFolder(folderId, members, name)
        }
        return result
    }

    private fun foldersToPreferencesString(folders: Map<String, DockFolder>): String =
        folders.entries.joinToString(DOCKED_APP_ID_SEPARATOR) { (folderId, folder) ->
            listOf(
                folderId,
                folder.name.orEmpty(),
                folder.memberAppIds.joinToString(DOCK_FOLDER_MEMBER_SEPARATOR),
            ).joinToString(DOCK_POSITION_FIELD_SEPARATOR)
        }
}

internal class DockSettingsStore(context: Context) {
    private val sharedPreferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    var isDockEnabled: Boolean
        get() = sharedPreferences.getBoolean(KEY_DOCK_ENABLED, true)
        set(value) {
            sharedPreferences.edit()
                .putBoolean(KEY_DOCK_ENABLED, value)
                .apply()
        }

    // The dock's target icon size, in dp. The rendered per-row count and the
    // grown-to-fill icon size are both derived from this against the live screen
    // width (see `dockIconSizing`), so the dock tracks the system Display size
    // (a dp grows in pixels as density grows) and is immune to screen-resolution
    // changes. Source of truth is [KEY_DOCK_TARGET_ICON_SIZE_DP]; older installs
    // migrate from the #433-era per-row count, then the pre-#433 legacy size.
    var dockIconSizeDp: Int
        get() {
            val preferences = sharedPreferences
            val raw = when {
                preferences.contains(KEY_DOCK_TARGET_ICON_SIZE_DP) ->
                    preferences.getInt(KEY_DOCK_TARGET_ICON_SIZE_DP, DEFAULT_DOCK_APP_ICON_SIZE_DP)
                preferences.contains(KEY_DOCK_ICON_COUNT) ->
                    dockIconSizeForSlotCount(
                        DEFAULT_DOCK_SCREEN_WIDTH_DP,
                        preferences.getInt(KEY_DOCK_ICON_COUNT, DEFAULT_DOCK_ICON_COUNT),
                    )
                preferences.contains(LEGACY_KEY_DOCK_ICON_SIZE_DP) ->
                    preferences.getInt(LEGACY_KEY_DOCK_ICON_SIZE_DP, DEFAULT_DOCK_APP_ICON_SIZE_DP)
                else -> DEFAULT_DOCK_APP_ICON_SIZE_DP
            }
            return raw.coerceIn(MIN_DOCK_APP_ICON_SIZE_DP, MAX_DOCK_APP_ICON_SIZE_DP)
        }
        set(value) {
            sharedPreferences.edit()
                .putInt(
                    KEY_DOCK_TARGET_ICON_SIZE_DP,
                    value.coerceIn(MIN_DOCK_APP_ICON_SIZE_DP, MAX_DOCK_APP_ICON_SIZE_DP),
                )
                .apply()
        }

    var isWorkDockEnabled: Boolean
        get() = sharedPreferences.getBoolean(KEY_WORK_DOCK_ENABLED, false)
        set(value) {
            sharedPreferences.edit()
                .putBoolean(KEY_WORK_DOCK_ENABLED, value)
                .apply()
        }

    /**
     * How the installed-app list renders (see [AppListLayout]). Stored by name
     * so an unknown value (a renamed entry from a newer build) falls back to the
     * default. When no enum value has been written yet, migrates the pre-enum
     * boolean [KEY_APP_LIST_ICON_ONLY]: `true` → [AppListLayout.IconOnly],
     * `false`/absent → [AppListLayout.NameBeside].
     */
    var appListLayout: AppListLayout
        get() {
            val name = sharedPreferences.getString(KEY_APP_LIST_LAYOUT, null)
            if (name != null) {
                return runCatching { AppListLayout.valueOf(name) }.getOrNull()
                    ?: AppListLayout.NameBeside
            }
            return if (sharedPreferences.getBoolean(KEY_APP_LIST_ICON_ONLY, false)) {
                AppListLayout.IconOnly
            } else {
                AppListLayout.NameBeside
            }
        }
        set(value) {
            sharedPreferences.edit()
                .putString(KEY_APP_LIST_LAYOUT, value.name)
                .apply()
        }

    /**
     * How every dock slot renders (see [DockLayout]). Stored by name so an
     * unknown value (a renamed entry from a newer build) falls back to the
     * default. Defaults to [DockLayout.IconOnly], which matches the
     * pre-setting behavior.
     */
    var dockLayout: DockLayout
        get() = sharedPreferences.getString(KEY_DOCK_LAYOUT, null)
            ?.let { name -> runCatching { DockLayout.valueOf(name) }.getOrNull() }
            ?: DockLayout.IconOnly
        set(value) {
            sharedPreferences.edit()
                .putString(KEY_DOCK_LAYOUT, value.name)
                .apply()
        }

    /**
     * How an opened dock folder arranges its apps (see [FolderOpenLayout]).
     * Stored by name so an unknown value (a renamed entry from a newer build)
     * falls back to the default. Defaults to [FolderOpenLayout.Grid], which
     * matches the pre-setting behavior.
     */
    var folderOpenLayout: FolderOpenLayout
        get() = sharedPreferences.getString(KEY_FOLDER_OPEN_LAYOUT, null)
            ?.let { name -> runCatching { FolderOpenLayout.valueOf(name) }.getOrNull() }
            ?: FolderOpenLayout.Grid
        set(value) {
            sharedPreferences.edit()
                .putString(KEY_FOLDER_OPEN_LAYOUT, value.name)
                .apply()
        }

    /**
     * How every app icon's tile is rendered. [IconTheme.Default] keeps each
     * app's own icon art; [IconTheme.Monochrome] re-renders icons from their
     * app's monochrome themed-icon glyph in theme colors. Stored by name so an
     * unknown value (a renamed entry from a newer build) falls back to
     * [IconTheme.Default].
     */
    var iconTheme: IconTheme
        get() = sharedPreferences.getString(KEY_ICON_THEME, null)
            ?.let { name -> runCatching { IconTheme.valueOf(name) }.getOrNull() }
            ?: IconTheme.Default
        set(value) {
            sharedPreferences.edit()
                .putString(KEY_ICON_THEME, value.name)
                .apply()
        }

    var appListSortOrder: AppListSortOrder
        get() = sharedPreferences.getString(KEY_APP_LIST_SORT_ORDER, null)
            ?.let { name -> runCatching { AppListSortOrder.valueOf(name) }.getOrNull() }
            ?: AppListSortOrder.Usage
        set(value) {
            sharedPreferences.edit()
                .putString(KEY_APP_LIST_SORT_ORDER, value.name)
                .apply()
        }

    /**
     * Controls whether the soft keyboard is auto-shown when Home is brought to
     * the foreground. When `true` (default) the search field is focused and
     * `keyboard.show()` runs on cold start, matching the original always-typing
     * launcher behavior. When `false` the search field is not auto-focused and
     * `MainActivity` applies `stateAlwaysHidden` so retained focus doesn't undo
     * the preference when the launcher resumes.
     */
    var isKeyboardAutoShown: Boolean
        get() = sharedPreferences.getBoolean(KEY_KEYBOARD_AUTO_SHOWN, true)
        set(value) {
            sharedPreferences.edit()
                .putBoolean(KEY_KEYBOARD_AUTO_SHOWN, value)
                .apply()
        }

    /**
     * Last non-navigation-bar-inclusive IME bottom inset reported while the
     * keyboard was opening, paired with the configuration it was measured
     * under and the source that produced it. Used to reserve Home's
     * keyboard slot before the IME reports its next animation target,
     * avoiding one full-height pre-keyboard frame on warm starts and
     * carousel returns. The configuration fingerprint makes the cache
     * shrink-safe: on rotation, density change, navigation-mode switch,
     * or any other property that changes the IME's pixel height, the
     * persisted value is ignored on the next cold start so a stale
     * too-large reservation cannot survive the configuration change.
     *
     * Legacy installs that wrote only [KEY_KEYBOARD_RESERVATION_BOTTOM_PX]
     * (without the fingerprint keys) are loaded with a null fingerprint
     * and treated as a wildcard until the next IME observation overwrites
     * them, so upgrades still benefit from the cached value.
     */
    var keyboardReservation: KeyboardReservation
        get() {
            val bottomPx = sharedPreferences.getInt(KEY_KEYBOARD_RESERVATION_BOTTOM_PX, 0)
                .coerceAtLeast(0)
            val hasConfig = sharedPreferences.contains(KEY_KEYBOARD_RESERVATION_ORIENTATION)
            val configFingerprint = if (hasConfig) {
                KeyboardReservationConfig(
                    orientation = sharedPreferences.getInt(KEY_KEYBOARD_RESERVATION_ORIENTATION, 0),
                    screenWidthDp = sharedPreferences.getInt(KEY_KEYBOARD_RESERVATION_SCREEN_WIDTH_DP, 0),
                    screenHeightDp = sharedPreferences.getInt(KEY_KEYBOARD_RESERVATION_SCREEN_HEIGHT_DP, 0),
                    densityDpi = sharedPreferences.getInt(KEY_KEYBOARD_RESERVATION_DENSITY_DPI, 0),
                    navBottomPx = sharedPreferences.getInt(KEY_KEYBOARD_RESERVATION_NAV_BOTTOM_PX, 0),
                )
            } else {
                null
            }
            val source = sharedPreferences.getString(KEY_KEYBOARD_RESERVATION_SOURCE, null)
                ?.let { name -> runCatching { KeyboardReservationSource.valueOf(name) }.getOrNull() }
                ?: KeyboardReservationSource.AnimationTarget
            return KeyboardReservation(
                bottomPx = bottomPx,
                configFingerprint = configFingerprint,
                source = source,
            )
        }
        set(value) {
            val editor = sharedPreferences.edit()
                .putInt(KEY_KEYBOARD_RESERVATION_BOTTOM_PX, value.bottomPx.coerceAtLeast(0))
                .putString(KEY_KEYBOARD_RESERVATION_SOURCE, value.source.name)
            val fingerprint = value.configFingerprint
            if (fingerprint == null) {
                editor
                    .remove(KEY_KEYBOARD_RESERVATION_ORIENTATION)
                    .remove(KEY_KEYBOARD_RESERVATION_SCREEN_WIDTH_DP)
                    .remove(KEY_KEYBOARD_RESERVATION_SCREEN_HEIGHT_DP)
                    .remove(KEY_KEYBOARD_RESERVATION_DENSITY_DPI)
                    .remove(KEY_KEYBOARD_RESERVATION_NAV_BOTTOM_PX)
            } else {
                editor
                    .putInt(KEY_KEYBOARD_RESERVATION_ORIENTATION, fingerprint.orientation)
                    .putInt(KEY_KEYBOARD_RESERVATION_SCREEN_WIDTH_DP, fingerprint.screenWidthDp)
                    .putInt(KEY_KEYBOARD_RESERVATION_SCREEN_HEIGHT_DP, fingerprint.screenHeightDp)
                    .putInt(KEY_KEYBOARD_RESERVATION_DENSITY_DPI, fingerprint.densityDpi)
                    .putInt(KEY_KEYBOARD_RESERVATION_NAV_BOTTOM_PX, fingerprint.navBottomPx)
            }
            editor.apply()
        }

    /**
     * Controls whether Agenda participates in the carousel. Defaults to true
     * to preserve the existing three-page launcher for current users.
     */
    var isAgendaEnabled: Boolean
        get() = sharedPreferences.getBoolean(KEY_AGENDA_ENABLED, true)
        set(value) {
            sharedPreferences.edit()
                .putBoolean(KEY_AGENDA_ENABLED, value)
                .apply()
        }

    /**
     * Settings → "Theme" mode. Defaults to [ThemeMode.System] so the launcher
     * follows the device's night-mode configuration; users can pin to
     * [ThemeMode.Light] or [ThemeMode.Dark] to override the system.
     */
    var themeMode: ThemeMode
        get() = sharedPreferences.getString(KEY_THEME_MODE, null)
            ?.let { name -> runCatching { ThemeMode.valueOf(name) }.getOrNull() }
            ?: ThemeMode.System
        set(value) {
            sharedPreferences.edit()
                .putString(KEY_THEME_MODE, value.name)
                .apply()
        }

    /**
     * Settings → "Icon shape". Defaults to [IconShape.System] so the launcher
     * follows the device's adaptive-icon mask; users can pin to [IconShape.Circle]
     * or [IconShape.Squircle] to override it.
     */
    var iconShape: IconShape
        get() = sharedPreferences.getString(KEY_ICON_SHAPE, null)
            ?.let { name -> runCatching { IconShape.valueOf(name) }.getOrNull() }
            ?: IconShape.System
        set(value) {
            sharedPreferences.edit()
                .putString(KEY_ICON_SHAPE, value.name)
                .apply()
        }

    /**
     * When true, the bug-report consent dialog is suppressed and Settings →
     * "Report bug" shares immediately. Set by the "Don't show this again"
     * checkbox on the consent dialog itself.
     */
    var isBugReportConsentSuppressed: Boolean
        get() = sharedPreferences.getBoolean(KEY_BUG_REPORT_CONSENT_SUPPRESSED, false)
        set(value) {
            sharedPreferences.edit()
                .putBoolean(KEY_BUG_REPORT_CONSENT_SUPPRESSED, value)
                .apply()
        }

    private companion object {
        const val PREFERENCES_NAME = "dock_settings"
        const val KEY_DOCK_ENABLED = "dock_enabled"
        const val KEY_DOCK_TARGET_ICON_SIZE_DP = "dock_target_icon_size_dp"
        const val KEY_DOCK_ICON_COUNT = "dock_icon_count"
        const val KEY_WORK_DOCK_ENABLED = "work_dock_enabled"
        // Legacy boolean, kept for migration into KEY_APP_LIST_LAYOUT only.
        const val KEY_APP_LIST_ICON_ONLY = "app_list_icon_only"
        const val KEY_APP_LIST_LAYOUT = "app_list_layout"
        const val KEY_DOCK_LAYOUT = "dock_layout"
        const val KEY_FOLDER_OPEN_LAYOUT = "folder_open_layout"
        const val KEY_ICON_THEME = "icon_theme"
        const val KEY_APP_LIST_SORT_ORDER = "app_list_sort_order"
        const val KEY_KEYBOARD_AUTO_SHOWN = "keyboard_auto_shown"
        const val KEY_KEYBOARD_RESERVATION_BOTTOM_PX = "keyboard_reservation_bottom_px"
        const val KEY_KEYBOARD_RESERVATION_ORIENTATION = "keyboard_reservation_orientation"
        const val KEY_KEYBOARD_RESERVATION_SCREEN_WIDTH_DP = "keyboard_reservation_screen_width_dp"
        const val KEY_KEYBOARD_RESERVATION_SCREEN_HEIGHT_DP = "keyboard_reservation_screen_height_dp"
        const val KEY_KEYBOARD_RESERVATION_DENSITY_DPI = "keyboard_reservation_density_dpi"
        const val KEY_KEYBOARD_RESERVATION_NAV_BOTTOM_PX = "keyboard_reservation_nav_bottom_px"
        const val KEY_KEYBOARD_RESERVATION_SOURCE = "keyboard_reservation_source"
        const val KEY_AGENDA_ENABLED = "agenda_enabled"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_ICON_SHAPE = "icon_shape"
        const val KEY_BUG_REPORT_CONSENT_SUPPRESSED = "bug_report_consent_suppressed"
    }
}

private const val LEGACY_KEY_DOCK_ICON_SIZE_DP = "dock_icon_size_dp"
