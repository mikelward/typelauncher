package app.typelauncher

/**
 * First-run prefill for the work-apps dock. Mirrors [prefillDock] but layered
 * over three signals so the row is rarely empty on first enable:
 *
 *  (a) Work-profile apps the user already pinned to the personal dock.
 *  (b) Most-launched work-profile apps with `launchCount > 0`, descending.
 *  (c) `POPULAR_APP_PACKAGES` fallback against the work-profile copies.
 *
 * When tiers (a) and (b) both add nothing, tier (c) stops at `maxSlots - 1`
 * so the trailing cell holds the "+" empty-state hint until the user takes
 * their first work-dock-add action — same convention as [prefillDock] on a
 * fresh personal-dock install. When (a) or (b) seed at least one entry,
 * the prefill targets the full `maxSlots` so the row is fully populated.
 *
 * Work apps that are already in the personal dock are *copied* into the work
 * dock; [InstalledApp.id] includes the user-hash so personal and work entries
 * are distinct keys and the personal-dock entry is untouched.
 */
internal fun prefillWorkDock(
    installedApps: List<InstalledApp>,
    personalDockedIds: Set<String>,
    appLaunchStatsStore: AppLaunchStatsStore,
    workDockStore: DockedAppStore,
    maxSlots: Int,
) {
    val target = maxSlots.coerceAtLeast(0)
    if (target == 0) return
    val visibleWorkApps = installedApps.filter { it.isWorkApp && !it.isQuietMode }
    if (visibleWorkApps.isEmpty()) return
    val columnCount = target.coerceAtLeast(1)
    val byPackage = visibleWorkApps.groupBy { it.packageName }

    // (a) Work apps already pinned in the personal dock.
    visibleWorkApps
        .filter { it.id in personalDockedIds }
        .forEach { app ->
            if (workDockStore.dockedAppIds.size >= target) return@forEach
            workDockStore.dock(app.id, columnCount = columnCount)
        }

    // (b) Most-launched work apps with `launchCount > 0`, descending.
    if (workDockStore.dockedAppIds.size < target) {
        visibleWorkApps
            .filter { app -> app.id !in workDockStore.dockedAppIds }
            .map { app -> app to appLaunchStatsStore.launchCount(app.id) }
            .filter { (_, count) -> count > 0 }
            .sortedByDescending { (_, count) -> count }
            .forEach { (app, _) ->
                if (workDockStore.dockedAppIds.size >= target) return@forEach
                workDockStore.dock(app.id, columnCount = columnCount)
            }
    }

    // (c) Popular-package fallback. When (a) and (b) both contributed
    // nothing, leave one trailing slot empty so the "+" hint sits in it
    // until the user takes their first work-dock-add action — matching
    // the personal-dock convention in LauncherViewModel.
    if (workDockStore.dockedAppIds.size < target) {
        val popularTarget = if (workDockStore.dockedAppIds.isEmpty()) {
            (target - 1).coerceAtLeast(0)
        } else {
            target
        }
        for (packageName in POPULAR_APP_PACKAGES) {
            if (workDockStore.dockedAppIds.size >= popularTarget) break
            val app = byPackage[packageName]?.firstOrNull() ?: continue
            if (app.id in workDockStore.dockedAppIds) continue
            workDockStore.dock(app.id, columnCount = columnCount)
        }
    }
}
