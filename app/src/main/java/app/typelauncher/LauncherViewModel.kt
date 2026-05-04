package app.typelauncher

import android.Manifest
import android.app.Application
import android.app.role.RoleManager
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.provider.CalendarContract
import android.provider.Settings
import android.text.format.DateUtils
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

internal class LauncherViewModel(
    private val app: Application,
    private val workPackages: Set<String>,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val dockedAppStore = DockedAppStore(app)
    private val hiddenAppStore = HiddenAppStore(app)
    private val widgetStore = WidgetStore(app)
    private val dockSettingsStore = DockSettingsStore(app)
    private val appLaunchStatsStore = AppLaunchStatsStore(app)
    private val appMetadataStore = AppMetadataStore(app)
    private val iconSnapshotStore = IconSnapshotStore(app)
    private val playUpdateStore = PlayUpdateStore(app)
    private val settingsLaunchGate = SettingsLaunchGate()
    private var installedApps: List<InstalledApp> = emptyList()
    private var agendaVersion = 0
    // Set the first time the UI signals that Home is fully drawn and the soft
    // keyboard is up (or a fallback timeout has elapsed). Gates the deferred
    // initial agenda load so the calendar query can't compete with the cold-
    // start app list load on Dispatchers.IO.
    private var initialAgendaTriggered = false
    // Flips `true` when the deferred icon-snapshot restore coroutine finishes
    // populating `AppIconLoader`. Until then, `persistIconSnapshot` must skip
    // saving — `AppIconLoader.cacheSnapshot()` cannot represent the on-disk
    // state yet, so calling `IconSnapshotStore.save` would prune previously
    // persisted icons and regress the next cold-start first frame. Read and
    // written from the Main dispatcher only.
    private var iconSnapshotRestoreComplete = false
    private val cachedMetadata: List<InstalledApp> = appMetadataStore.load()
    // Cached so register/unregister go through the same `LauncherApps`
    // instance and we never silently miss the unregister because the service
    // is null on a future `getSystemService` call.
    private val launcherAppsService: LauncherApps? = app.getSystemService<LauncherApps>()
    // Most recently scheduled reload of the installed-app list. Held so a
    // burst of package events (e.g. an upgrade firing PACKAGE_REMOVED then
    // PACKAGE_ADDED) doesn't pile up redundant IO; the latest cancels its
    // predecessor.
    private var pendingReloadJob: Job? = null
    private val launcherAppsCallback = object : LauncherApps.Callback() {
        override fun onPackageAdded(packageName: String, user: UserHandle) {
            scheduleReload("packageAdded:$packageName")
        }
        override fun onPackageRemoved(packageName: String, user: UserHandle) {
            scheduleReload("packageRemoved:$packageName")
        }
        override fun onPackageChanged(packageName: String, user: UserHandle) {
            scheduleReload("packageChanged:$packageName")
        }
        override fun onPackagesAvailable(
            packageNames: Array<out String>,
            user: UserHandle,
            replacing: Boolean,
        ) {
            scheduleReload("packagesAvailable:${packageNames.size}")
        }
        override fun onPackagesUnavailable(
            packageNames: Array<out String>,
            user: UserHandle,
            replacing: Boolean,
        ) {
            scheduleReload("packagesUnavailable:${packageNames.size}")
        }
    }
    private var launcherAppsCallbackRegistered = false
    // Receiver for the system broadcasts the OS sends when the user toggles
    // "Pause work apps" (quiet mode). Reuses the package-event reload path so
    // the next `loadInstalledApps` repaints work icons in the dimmed/normal
    // treatment without a process restart.
    private val managedProfileReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            scheduleReload("managedProfile:$action")
        }
    }
    private var managedProfileReceiverRegistered = false
    // Set when a `LauncherApps.Callback` event fires before the cold-start
    // fresh load has published its result. We don't run a reload concurrently
    // with cold-start (it could race the cold-start state update and lose),
    // but we can't drop the event either — the cold-start `getActivityList`
    // may have already returned pre-event data. The cold-start coroutine
    // checks this flag after publishing and triggers a reload if set.
    private var reloadPendingDuringColdStart = false
    private val _uiState = MutableStateFlow(
        LauncherUiState(
            widgetIds = widgetStore.widgetIds,
            widgetHeights = widgetStore.customHeights,
            isDockEnabled = dockSettingsStore.isDockEnabled,
            isAppListIconOnly = dockSettingsStore.isAppListIconOnly,
            dockIconCount = dockSettingsStore.dockIconCount,
            appListSortOrder = dockSettingsStore.appListSortOrder,
            isRecentsAlwaysShown = dockSettingsStore.isRecentsAlwaysShown,
            notificationPullDownBehavior = dockSettingsStore.notificationPullDownBehavior,
            isKeyboardAutoShown = dockSettingsStore.isKeyboardAutoShown,
            themeMode = dockSettingsStore.themeMode,
            isLoadingApps = cachedMetadata.isEmpty(),
            hasNotificationAccess = ActiveNotifications.hasListenerAccess(app),
            playUpdate = PlayUpdateState.NotAvailable,
        ),
    )
    val uiState: StateFlow<LauncherUiState> = _uiState.asStateFlow()

    init {
        LauncherDebugLog.event("LauncherViewModel initialized ${_uiState.value.debugSummary()}")
        // Observe the notification listener's package set so the bar stays
        // current as the system posts/dismisses notifications. Routed through
        // a separate launch so the StateFlow's initial-value emission doesn't
        // run inside the ViewModel constructor — that would invoke a member
        // function (visibleInstalledApps / markVisibility) on a half-built
        // instance under Dispatchers.Main.immediate.
        viewModelScope.launch {
            ActiveNotifications.packages.collect { packages ->
                refreshNotifyingApps(packages)
            }
        }
        // Restore previously-rasterised icons off the main thread: the file read +
        // Bitmap allocation + copyPixelsFromBuffer per snapshot adds up to enough work
        // to delay setContent → first frame → the LaunchedEffect in SearchCard that
        // calls keyboard.show(). The first compose pass may miss on the cache for the
        // very first frame (rows render with the placeholder surface), but
        // rememberAppIconBitmap's LaunchedEffect re-checks the cache as soon as the
        // restore lands, so the icons snap in within a frame or two.
        //
        // Launching on the default Main dispatcher and switching to ioDispatcher via
        // withContext (rather than launch(ioDispatcher) { ... }) matches the fresh-
        // load coroutine below, so Robolectric's `composeRule.waitForIdle()` drains
        // it deterministically through the main looper.
        viewModelScope.launch {
            withContext(ioDispatcher) {
                androidTrace("launcher.icon_snapshot_restore") {
                    traceBlock("icon_snapshot_restore") { trace ->
                        val snapshots = iconSnapshotStore.load()
                        for (snapshot in snapshots) {
                            AppIconLoader.put(snapshot.id, snapshot.sizePx, snapshot.bitmap)
                        }
                        trace.incrementMetric("snapshot_count", snapshots.size.toLong())
                        if (snapshots.isNotEmpty()) {
                            LauncherDebugLog.event(
                                "LauncherViewModel restored icon snapshot count=${snapshots.size}",
                            )
                        }
                    }
                }
            }
            iconSnapshotRestoreComplete = true
        }
        if (cachedMetadata.isNotEmpty()) {
            androidTrace("launcher.metadata_prefill") {
                installedApps = cachedMetadata.applyDisambiguators()
                _uiState.update { state ->
                    val dockedIds = dockedAppStore.dockedAppIds
                    val activeDockedIds = dockedIds.takeIf { state.isDockEnabled }.orEmpty()
                    val visibleApps = visibleInstalledApps()
                    state.copy(
                        filteredApps = visibleApps.filterByName(
                            query = state.query,
                            appLaunchStatsStore = appLaunchStatsStore,
                            excludedAppIds = activeDockedIds,
                            dockedAppIds = dockedIds,
                            sortOrder = state.appListSortOrder,
                        ).markVisibility(),
                        dockedApps = visibleApps
                            .filterDocked(dockedAppStore.dockedAppIds)
                            .markVisibility(),
                        recentApps = visibleApps.filterRecent(appLaunchStatsStore.recentAppIds).markVisibility(),
                        hiddenApps = installedApps.filterHidden(hiddenAppStore.hiddenAppIds).markVisibility(),
                        notifyingApps = visibleApps
                            .filterNotifying(ActiveNotifications.packages.value)
                            .markVisibility(),
                    )
                }
            }
            LauncherDebugLog.event("LauncherViewModel rendered cached metadata count=${cachedMetadata.size}")
        }
        viewModelScope.launch {
            val initialLoadTrace = LauncherTelemetry.startTrace("launcher_initial_load")
            val loadedApps = withContext(ioDispatcher) {
                androidTrace("launcher.apps_load") {
                    traceBlock("installed_apps_load") { trace ->
                        loadInstalledApps().also { trace.incrementMetric("app_count", it.size.toLong()) }
                    }
                }
            }
            initialLoadTrace.incrementMetric("app_count", loadedApps.size.toLong())
            initialLoadTrace.stop()
            installedApps = loadedApps
            if (!dockedAppStore.hasBeenPrefilled) {
                if (dockedAppStore.dockedAppIds.isEmpty()) {
                    // Reserve one slot for the always-visible "+" add button.
                    prefillDock(loadedApps, dockedAppStore, (_uiState.value.dockIconCount - 1).coerceAtLeast(0))
                }
                dockedAppStore.markPrefilled()
            }
            _uiState.update { state ->
                val dockedIds = dockedAppStore.dockedAppIds
                val activeDockedIds = dockedIds.takeIf { state.isDockEnabled }.orEmpty()
                val visibleApps = visibleInstalledApps()
                state.copy(
                    filteredApps = visibleApps.filterByName(
                        query = state.query,
                        appLaunchStatsStore = appLaunchStatsStore,
                        excludedAppIds = activeDockedIds,
                        dockedAppIds = dockedIds,
                        sortOrder = state.appListSortOrder,
                    ).markVisibility(),
                    dockedApps = visibleApps
                        .filterDocked(dockedAppStore.dockedAppIds)
                        .markVisibility(),
                    recentApps = visibleApps.filterRecent(appLaunchStatsStore.recentAppIds).markVisibility(),
                    hiddenApps = installedApps.filterHidden(hiddenAppStore.hiddenAppIds).markVisibility(),
                    notifyingApps = visibleApps
                        .filterNotifying(ActiveNotifications.packages.value)
                        .markVisibility(),
                    isLoadingApps = false,
                    isFreshAppLoadComplete = true,
                )
            }
            launch(ioDispatcher) { appMetadataStore.save(loadedApps) }
            LauncherDebugLog.event("LauncherViewModel initial load complete ${_uiState.value.debugSummary()}")
            if (reloadPendingDuringColdStart) {
                reloadPendingDuringColdStart = false
                scheduleReload("coldStartCompletedWithPendingEvent")
            }
        }
        registerLauncherAppsCallback()
        registerManagedProfileReceiver()
    }

    /**
     * Registers the `LauncherApps.Callback` so package install/uninstall/change
     * events across any available profile trigger a reload. Called once at the
     * end of `init`, so it's already armed by the time the cold-start IO runs;
     * an event landing during cold-start is captured by
     * `reloadPendingDuringColdStart` and replayed when cold-start publishes,
     * preventing the race between the cold-start state update and a
     * concurrent reload's update. Idempotent so a future double-call (or a
     * test) can't double-register.
     */
    private fun registerLauncherAppsCallback() {
        if (launcherAppsCallbackRegistered) return
        val service = launcherAppsService ?: return
        try {
            service.registerCallback(launcherAppsCallback)
            launcherAppsCallbackRegistered = true
            LauncherDebugLog.event("LauncherApps.registerCallback")
        } catch (exception: RuntimeException) {
            LauncherDebugLog.warning("LauncherApps.registerCallback failed", exception)
        }
    }

    /**
     * Registers a runtime receiver for `ACTION_MANAGED_PROFILE_AVAILABLE` /
     * `ACTION_MANAGED_PROFILE_UNAVAILABLE` so toggling "Pause work apps" in
     * system settings reactively repaints work-profile icons in their
     * dimmed/normal state. Reuses `scheduleReload` so quiet-mode flips share
     * the same coalescing + cold-start deferral as package events.
     * `RECEIVER_NOT_EXPORTED` is required on API 34+ for runtime receivers;
     * both actions are protected system broadcasts only the OS can send, so
     * not-exported is the correct flag. Idempotent.
     */
    private fun registerManagedProfileReceiver() {
        if (managedProfileReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MANAGED_PROFILE_AVAILABLE)
            addAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE)
        }
        try {
            app.registerReceiver(managedProfileReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            managedProfileReceiverRegistered = true
            LauncherDebugLog.event("managedProfileReceiver registered")
        } catch (exception: RuntimeException) {
            LauncherDebugLog.warning("managedProfileReceiver register failed", exception)
        }
    }

    /**
     * Re-reads the installed-app list from `LauncherApps` and republishes the
     * derived UI state. Called from the `LauncherApps.Callback` when a package
     * is installed, uninstalled, replaced, or otherwise changed across any
     * available profile. Cancels any earlier in-flight reload so a burst of
     * events (e.g. an upgrade's PACKAGE_REMOVED + PACKAGE_ADDED) collapses to
     * a single IO read against the latest system state. While the cold-start
     * fresh load is still in flight, the request is deferred (via
     * `reloadPendingDuringColdStart`) and replayed once cold-start publishes,
     * so the reload's state update can't lose a race with cold-start's.
     */
    private fun scheduleReload(reason: String) {
        if (!_uiState.value.isFreshAppLoadComplete) {
            reloadPendingDuringColdStart = true
            LauncherDebugLog.event("scheduleReload deferred until cold-start completes reason=$reason")
            return
        }
        LauncherDebugLog.event("scheduleReload reason=$reason")
        pendingReloadJob?.cancel()
        pendingReloadJob = viewModelScope.launch {
            val loadedApps = withContext(ioDispatcher) {
                traceBlock("installed_apps_reload") { trace ->
                    loadInstalledApps().also { trace.incrementMetric("app_count", it.size.toLong()) }
                }
            }
            installedApps = loadedApps
            refreshLists()
            launch(ioDispatcher) { appMetadataStore.save(loadedApps) }
            LauncherDebugLog.event("scheduleReload complete reason=$reason apps=${loadedApps.size}")
        }
    }

    override fun onCleared() {
        if (launcherAppsCallbackRegistered) {
            try {
                launcherAppsService?.unregisterCallback(launcherAppsCallback)
                LauncherDebugLog.event("LauncherApps.unregisterCallback")
            } catch (exception: RuntimeException) {
                LauncherDebugLog.warning("LauncherApps.unregisterCallback failed", exception)
            }
            launcherAppsCallbackRegistered = false
        }
        if (managedProfileReceiverRegistered) {
            try {
                app.unregisterReceiver(managedProfileReceiver)
                LauncherDebugLog.event("managedProfileReceiver unregistered")
            } catch (exception: RuntimeException) {
                LauncherDebugLog.warning("managedProfileReceiver unregister failed", exception)
            }
            managedProfileReceiverRegistered = false
        }
        super.onCleared()
    }

    internal fun reloadInstalledAppsForTest() {
        scheduleReload("test")
    }

    /**
     * Test seam for the paused-work-profile rendering. Robolectric's
     * `ShadowLauncherApps` does not surface a foreign profile that
     * `ShadowUserManager.setQuietModeEnabled` can target, so the only way to
     * exercise both the work badge (`isWorkApp`) and the dimmed-icon branch
     * (`isQuietMode`) at once is to flip the two fields directly on an
     * existing personal-profile entry and re-derive the public lists.
     * Production code goes through the broadcast receiver -> `scheduleReload`
     * -> `loadInstalledApps` path instead.
     */
    internal fun markAsPausedWorkAppForTest(packageName: String) {
        installedApps = installedApps.map { app ->
            if (app.packageName == packageName) {
                app.copy(isWorkApp = true, isQuietMode = true)
            } else {
                app
            }
        }
        refreshLists()
    }

    /**
     * Called by the UI once the Home screen has fully drawn its app list and
     * the soft keyboard is in place (or a fallback timeout has elapsed).
     * Publishes `isHomeReady` for downstream consumers (e.g. MainActivity's
     * deferred `AppWidgetHost.startListening`) and triggers the deferred first
     * agenda load on the IO dispatcher. Idempotent.
     */
    fun onHomeReady() {
        if (_uiState.value.isHomeReady) return
        _uiState.update { it.copy(isHomeReady = true) }
        LauncherDebugLog.event("onHomeReady published; starting deferred agenda load")
        if (!initialAgendaTriggered) {
            initialAgendaTriggered = true
            loadAgendaAsync(reason = "homeReady", traceName = "agenda_initial_load")
        }
    }

    fun setQuery(query: String) {
        // Query-only refresh: dock / recents / hidden / notifying lists don't
        // depend on the query, and rebuilding them on every keystroke produces
        // fresh list references that recompose unrelated UI for nothing. The
        // worst case is backspace, which grows the result set back toward the
        // full app list and made the lag user-visible.
        _uiState.update { state -> state.copy(query = query) }
        refreshFilteredApps()
    }

    fun showAgenda() {
        _uiState.update {
            it.copy(screen = LauncherScreen.Agenda, isRecentsOpen = false, isNotificationBarOpen = false)
        }
        logState("showAgenda")
        loadAgendaAsync(reason = "showAgenda", traceName = "agenda_load")
    }

    fun showWidgets() {
        _uiState.update {
            it.copy(screen = LauncherScreen.Widgets, isRecentsOpen = false, isNotificationBarOpen = false)
        }
        logState("showWidgets")
    }

    fun setRecentsOpen(isOpen: Boolean) {
        if (_uiState.value.isRecentsOpen == isOpen) return
        _uiState.update { it.copy(isRecentsOpen = isOpen) }
        logState("setRecentsOpen=$isOpen")
    }

    fun setNotificationBarOpen(isOpen: Boolean) {
        if (_uiState.value.isNotificationBarOpen == isOpen) return
        _uiState.update { it.copy(isNotificationBarOpen = isOpen) }
        logState("setNotificationBarOpen=$isOpen")
    }

    fun showWidgetPicker() {
        _uiState.update {
            it.copy(
                screen = LauncherScreen.Widgets,
                isAddingWidget = true,
                isLoadingAvailableWidgets = true,
                availableWidgets = emptyList(),
            )
        }
        logState("showWidgetPicker loading")
        viewModelScope.launch {
            val providers = withContext(ioDispatcher) { loadAvailableWidgets() }
            _uiState.update { state ->
                if (!state.isAddingWidget) {
                    state
                } else {
                    state.copy(
                        availableWidgets = providers,
                        isLoadingAvailableWidgets = false,
                    )
                }
            }
            logState("showWidgetPicker loaded")
        }
    }

    fun hideWidgetPicker() {
        _uiState.update { it.copy(isAddingWidget = false, isLoadingAvailableWidgets = false) }
        logState("hideWidgetPicker")
    }

    fun showHome() {
        _uiState.update { it.copy(screen = LauncherScreen.Home) }
        logState("showHome")
    }

    fun returnToLauncherHome() {
        _uiState.update {
            it.copy(
                screen = LauncherScreen.Home,
                isSettingsOpen = false,
                isAddingWidget = false,
                isLoadingAvailableWidgets = false,
                isRecentsOpen = false,
                isNotificationBarOpen = false,
            )
        }
        logState("returnToLauncherHome")
    }

    fun refreshPermissionDrivenUi() {
        LauncherDebugLog.event("refreshPermissionDrivenUi screen=${_uiState.value.screen}")
        val isDefaultLauncher = app.getSystemService<RoleManager>()?.isRoleHeld(RoleManager.ROLE_HOME) ?: false
        val hasNotificationAccess = ActiveNotifications.hasListenerAccess(app)
        _uiState.update {
            it.copy(
                isDefaultLauncher = isDefaultLauncher,
                hasNotificationAccess = hasNotificationAccess,
            )
        }
        if (_uiState.value.screen == LauncherScreen.Agenda) {
            refreshAgenda()
        }
    }

    /**
     * Opens Android's "Notification access" settings page so the user can enable
     * the launcher's listener service. Without that grant, the notification bar
     * has no source of truth — the system never binds the listener — so the bar
     * exposes this as the empty-state CTA.
     */
    fun openNotificationAccessSettings() {
        LauncherDebugLog.event("openNotificationAccessSettings")
        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).asLauncherTaskIntent())
    }

    /**
     * Persists currently-cached icon bitmaps for the dock and the top-N most-launched
     * apps so the next cold start can restore them ahead of the first frame. Called from
     * `MainActivity.onStop` because by then the user has typically scrolled the app list
     * and dock enough that the priority icons are warm in `AppIconLoader`'s cache, and
     * the launcher is about to be backgrounded so the IO is not on a user-visible path.
     */
    fun persistIconSnapshot() {
        if (installedApps.isEmpty()) {
            // The fresh LauncherApps load hasn't completed yet, so an empty priority
            // set here would mean "we don't know what's installed", not "nothing is
            // worth keeping". Leave the on-disk snapshot alone rather than wiping the
            // previous session's icons.
            LauncherDebugLog.event("persistIconSnapshot skipped: fresh load incomplete")
            return
        }
        if (!iconSnapshotRestoreComplete) {
            // The deferred restore hasn't populated AppIconLoader yet, so
            // cacheSnapshot() can't represent any previously persisted icons:
            // calling IconSnapshotStore.save with that partial set would prune
            // them. Skip this save and let the next onStop after the restore
            // refresh the on-disk snapshot.
            LauncherDebugLog.event("persistIconSnapshot skipped: snapshot restore in flight")
            return
        }
        val priorityIds = priorityIconCacheIds()
        val snapshots = AppIconLoader.cacheSnapshot()
            .filterKeys { key -> key.id in priorityIds }
            .map { (key, bitmap) ->
                IconSnapshotStore.Snapshot(id = key.id, sizePx = key.sizePx, bitmap = bitmap)
            }
        LauncherDebugLog.event(
            "persistIconSnapshot priority=${priorityIds.size} snapshots=${snapshots.size}",
        )
        // An empty snapshots list still goes through to IconSnapshotStore.save so its
        // prune contract runs and orphan files left behind by undocking + resetting
        // launch counts get cleaned up.
        viewModelScope.launch(ioDispatcher) { iconSnapshotStore.save(snapshots) }
    }

    private fun priorityIconCacheIds(): Set<String> {
        val docked = dockedAppStore.dockedAppIds.toSet()
        val topByLaunches = installedApps
            .asSequence()
            .map { app -> app.id to appLaunchStatsStore.launchCount(app.id) }
            .filter { (_, count) -> count > 0 }
            .sortedByDescending { (_, count) -> count }
            .take(SNAPSHOT_TOP_LAUNCH_COUNT)
            .map { (id, _) -> id }
            .toSet()
        val priorityIds = docked + topByLaunches
        return installedApps
            .asSequence()
            .filter { app -> app.id in priorityIds }
            .map { app -> app.iconCacheId }
            .toSet()
    }

    fun refreshAgenda() {
        loadAgendaAsync(reason = "refreshAgenda", traceName = "agenda_load")
    }

    private fun loadAgendaAsync(reason: String, traceName: String) {
        // Any explicit load short-circuits the deferred home-ready load: if the
        // user already asked for the agenda (or a test seeded it), we don't
        // also want the timeout to fire a second load that overwrites the
        // result.
        initialAgendaTriggered = true
        val requestVersion = ++agendaVersion
        viewModelScope.launch {
            val newAgenda = withContext(ioDispatcher) {
                traceBlock(traceName) { trace ->
                    loadAgendaState().also {
                        trace.setAttribute("state", it::class.simpleName ?: "unknown")
                    }
                }
            }
            if (agendaVersion == requestVersion) {
                _uiState.update { it.copy(agenda = newAgenda) }
            }
            logState("$reason agenda load complete")
        }
    }

    fun openAgendaEvent(event: AgendaEvent) {
        LauncherDebugLog.event("openAgendaEvent eventId=${event.eventId} begin=${event.beginMillis}")
        val eventUri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, event.eventId)
        val intent = Intent(Intent.ACTION_VIEW, eventUri)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, event.beginMillis)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, event.endMillis)
        try {
            startActivity(intent)
        } catch (exception: ActivityNotFoundException) {
            LauncherDebugLog.warning("openAgendaEvent no activity for event uri", exception)
        }
    }

    fun launchActiveApp() {
        val query = _uiState.value.query
        LauncherDebugLog.event("launchActiveApp queryLength=${query.length} filtered=${_uiState.value.filteredApps.size}")
        val trimmedQuery = query.trim()
        if (trimmedQuery.isEmpty()) {
            LauncherDebugLog.event("launchActiveApp opening launcher settings")
            openSettings()
            return
        }
        if (trimmedQuery.equals(SETTINGS_QUERY, ignoreCase = true)) {
            LauncherDebugLog.event("launchActiveApp opening system settings")
            startActivity(Intent(Settings.ACTION_SETTINGS).asLauncherTaskIntent())
            setQuery("")
            return
        }
        // Docked apps are excluded from filteredApps while the dock is
        // enabled, so fall back to launching the first dock entry that
        // matches the query when no non-docked match exists. The dock row
        // itself is no longer filtered by typed search, so the fallback
        // re-runs the matcher here rather than reading state.dockedApps —
        // otherwise we would launch the first pinned app regardless of the
        // query. The fallback is gated on the dock being enabled because
        // dockedApps is still populated when the dock UI is hidden.
        val state = _uiState.value
        val target = state.filteredApps.firstOrNull()
            ?: state.dockedApps.firstOrNull { app -> app.name.launcherMatchTier(trimmedQuery) != null }
                ?.takeIf { state.isDockEnabled }
        target?.let(::launchApp)
    }

    fun launchApp(app: InstalledApp) {
        val component = app.launchIntent.component
        LauncherDebugLog.event(
            "launchApp package=${app.packageName} component=${component?.flattenToShortString()} " +
                "work=${app.isWorkApp} launcherApps=${app.launchWithLauncherApps}",
        )
        try {
            if (app.launchWithLauncherApps && component != null) {
                this.app.getSystemService<LauncherApps>()?.startMainActivity(component, app.user, null, null)
            } else {
                startActivity(app.launchIntent.asLauncherTaskIntent())
            }
        } catch (exception: ActivityNotFoundException) {
            LauncherDebugLog.warning("launchApp activity not found package=${app.packageName}", exception)
            return
        } catch (exception: SecurityException) {
            LauncherDebugLog.warning("launchApp security exception package=${app.packageName}", exception)
            return
        }
        appLaunchStatsStore.recordLaunch(app.id)
        // recordLaunch mutates the recents store, so the recentApps surface
        // and the launch-count tier in the main list both need to be
        // recomputed. setQuery's keystroke-fast-path skips that, so trigger
        // the full refresh here directly.
        refreshLists()
        // Close the recents panel and notification bar as we leave Home — when
        // the user comes back they should be tucked away again, not still
        // expanded from before.
        _uiState.update { it.copy(isRecentsOpen = false, isNotificationBarOpen = false) }
        setQuery("")
    }

    fun openAppInfo(app: InstalledApp) {
        LauncherDebugLog.event(
            "openAppInfo package=${app.packageName} work=${app.isWorkApp} launcherApps=${app.launchWithLauncherApps}",
        )
        // ACTION_APPLICATION_DETAILS_SETTINGS resolves the package against the
        // current user only, so a work-profile app routed through it lands on the
        // personal-profile copy (or 404s if there isn't one). LauncherApps is the
        // only cross-profile API: it dispatches the same Settings screen against
        // the supplied UserHandle so the work-profile copy actually opens.
        val component = app.launchIntent.component
        val launcherApps = launcherAppsService
        if (app.launchWithLauncherApps && component != null && launcherApps != null) {
            try {
                launcherApps.startAppDetailsActivity(component, app.user, null, null)
                return
            } catch (exception: ActivityNotFoundException) {
                LauncherDebugLog.warning("openAppInfo activity not found package=${app.packageName}", exception)
            } catch (exception: SecurityException) {
                LauncherDebugLog.warning("openAppInfo security exception package=${app.packageName}", exception)
            }
        }
        startActivity(app.appInfoIntent)
    }

    /**
     * Opens Android's app-info screen for Type Launcher itself, used by the settings
     * overflow "App info" action. From there the user can tap "Storage & cache" →
     * "Clear storage" to wipe launch counts, the metadata snapshot, persisted icon
     * snapshots, and any other on-disk state — the system clear-data flow drops the
     * process too, so the next launch is a true cold start.
     */
    fun openLauncherAppInfo() {
        LauncherDebugLog.event("openLauncherAppInfo package=${app.packageName}")
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${app.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(intent)
    }

    fun toggleDock(app: InstalledApp, maxDockedApps: Int) {
        LauncherDebugLog.event(
            "toggleDock package=${app.packageName} docked=${app.isDocked} " +
                "currentDocked=${dockedAppStore.dockedAppIds.size} max=$maxDockedApps",
        )
        if (app.isDocked) {
            dockedAppStore.undock(app.id)
        } else {
            dockedAppStore.dock(app.id)
        }
        refreshLists()
        logState("toggleDock")
    }

    fun reorderDockedApps(fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex) return
        LauncherDebugLog.event("reorderDockedApps from=$fromIndex to=$toIndex")
        dockedAppStore.reorder(fromIndex, toIndex)
        refreshLists()
        logState("reorderDockedApps")
    }

    fun resetRank(app: InstalledApp) {
        LauncherDebugLog.event("resetRank package=${app.packageName}")
        appLaunchStatsStore.resetLaunchCount(app.id)
        refreshLists()
        logState("resetRank")
    }

    /**
     * Removes [app] from the recents bar without touching its launch count —
     * "Dismiss" on the recents bar is the per-icon equivalent of swiping a
     * notification away from the notification bar: the user is taking that one
     * entry off the bar, not resetting the app's rank in the main list.
     */
    fun removeRecent(app: InstalledApp) {
        LauncherDebugLog.event("removeRecent package=${app.packageName}")
        appLaunchStatsStore.removeRecent(app.id)
        refreshLists()
        logState("removeRecent")
    }

    /**
     * Cancels every user-visible active notification for [app]'s package under
     * its profile. Backs the "Dismiss" action on the notification bar — once
     * the system clears those notifications the listener fires a refresh and
     * the package drops out of the bar. The package + user pair scopes the
     * cancel to the selected profile so dismissing the personal icon doesn't
     * also clear notifications for the work-profile copy of the same package
     * (and vice versa). No-op if the listener service isn't bound.
     */
    fun dismissNotificationsFor(app: InstalledApp) {
        LauncherDebugLog.event("dismissNotificationsFor package=${app.packageName} work=${app.isWorkApp}")
        NotificationDismisser.dismissNotificationsFor(app.packageName, app.user)
    }

    /**
     * Opens Android's per-app notification settings for [app]. Backs the
     * notification bar's "Settings" action — the user's escape hatch for "I
     * don't want this app to keep showing up in this bar." Falls back to the
     * generic app-info screen if the OEM has no notification-settings activity
     * registered for the package.
     */
    fun openNotificationSettingsFor(app: InstalledApp) {
        LauncherDebugLog.event("openNotificationSettingsFor package=${app.packageName}")
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, app.packageName)
        try {
            startActivity(intent)
        } catch (exception: ActivityNotFoundException) {
            LauncherDebugLog.warning("openNotificationSettingsFor missing activity, falling back to app info", exception)
            openAppInfo(app)
        }
    }

    fun hideApp(app: InstalledApp) {
        LauncherDebugLog.event("hideApp package=${app.packageName} docked=${app.isDocked}")
        hiddenAppStore.hide(app.id)
        refreshLists()
        logState("hideApp")
    }

    fun unhideApp(app: InstalledApp) {
        LauncherDebugLog.event("unhideApp package=${app.packageName}")
        hiddenAppStore.unhide(app.id)
        refreshLists()
        logState("unhideApp")
    }

    fun addWidget(appWidgetId: Int) {
        LauncherDebugLog.event("addWidget appWidgetId=$appWidgetId")
        widgetStore.add(appWidgetId)
        _uiState.update {
            it.copy(
                screen = LauncherScreen.Widgets,
                isAddingWidget = false,
                isLoadingAvailableWidgets = false,
                widgetIds = widgetStore.widgetIds,
                widgetHeights = widgetStore.customHeights,
            )
        }
        logState("addWidget")
    }

    fun resizeWidget(appWidgetId: Int, heightDp: Int) {
        LauncherDebugLog.event("resizeWidget appWidgetId=$appWidgetId heightDp=$heightDp")
        widgetStore.setCustomHeight(appWidgetId, heightDp)
        _uiState.update { it.copy(widgetHeights = widgetStore.customHeights) }
        logState("resizeWidget")
    }

    internal fun refreshAvailableWidgetsForTest() {
        _uiState.update { it.copy(availableWidgets = loadAvailableWidgets()) }
    }

    internal fun showAgendaEventsForTest(events: List<AgendaEvent>) {
        // Block the deferred home-ready load so the timeout can't overwrite the
        // seeded events with a real CalendarContract result.
        initialAgendaTriggered = true
        agendaVersion++
        _uiState.update {
            it.copy(
                screen = LauncherScreen.Agenda,
                agenda = if (events.isEmpty()) AgendaUiState.Empty else AgendaUiState.Events(events),
            )
        }
        logState("showAgendaEventsForTest")
    }

    internal fun showWidgetPickerForTest(availableWidgets: List<WidgetProvider>) {
        showWidgetPicker(availableWidgets)
    }

    fun removeWidget(appWidgetId: Int) {
        LauncherDebugLog.event("removeWidget appWidgetId=$appWidgetId")
        widgetStore.remove(appWidgetId)
        _uiState.update {
            it.copy(
                screen = LauncherScreen.Widgets,
                widgetIds = widgetStore.widgetIds,
                widgetHeights = widgetStore.customHeights,
            )
        }
        logState("removeWidget")
    }

    fun openSettings() {
        _uiState.update { it.copy(isSettingsOpen = true, isRecentsOpen = false, isNotificationBarOpen = false) }
        logState("openSettings")
    }

    fun closeSettings() {
        _uiState.update { it.copy(isSettingsOpen = false) }
        logState("closeSettings")
    }

    fun setPlayUpdateAvailable(availableVersionCode: Int?) {
        _uiState.update { state ->
            state.copy(
                playUpdate = PlayUpdateState.Available(
                    versionCode = availableVersionCode,
                    isDismissed = availableVersionCode != null &&
                        availableVersionCode == playUpdateStore.dismissedVersionCode,
                ),
            )
        }
        logState("setPlayUpdateAvailable=$availableVersionCode")
    }

    fun setPlayUpdateUnavailable() {
        _uiState.update { it.copy(playUpdate = PlayUpdateState.NotAvailable) }
        logState("setPlayUpdateUnavailable")
    }

    fun dismissPlayUpdate() {
        val update = _uiState.value.playUpdate as? PlayUpdateState.Available ?: return
        playUpdateStore.dismissedVersionCode = update.versionCode ?: BuildConfig.VERSION_CODE + 1
        _uiState.update { it.copy(playUpdate = update.copy(isDismissed = true)) }
        logState("dismissPlayUpdate=${update.versionCode}")
    }

    fun openPlayStoreListing() {
        LauncherDebugLog.event("openPlayStoreListing package=${app.packageName}")
        val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${app.packageName}"))
        try {
            startActivity(marketIntent)
        } catch (exception: ActivityNotFoundException) {
            LauncherDebugLog.warning("openPlayStoreListing market intent unavailable", exception)
            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=${app.packageName}"),
                ),
            )
        }
    }

    fun setDockEnabled(isEnabled: Boolean) {
        dockSettingsStore.isDockEnabled = isEnabled
        _uiState.update { it.copy(isDockEnabled = isEnabled) }
        refreshLists()
        logState("setDockEnabled")
    }

    fun setAppListIconOnly(isIconOnly: Boolean) {
        dockSettingsStore.isAppListIconOnly = isIconOnly
        _uiState.update { it.copy(isAppListIconOnly = isIconOnly) }
        logState("setAppListIconOnly")
    }

    fun setAppListSortOrder(sortOrder: AppListSortOrder) {
        dockSettingsStore.appListSortOrder = sortOrder
        _uiState.update { it.copy(appListSortOrder = sortOrder) }
        refreshLists()
        logState("setAppListSortOrder")
    }

    fun setRecentsAlwaysShown(isAlwaysShown: Boolean) {
        dockSettingsStore.isRecentsAlwaysShown = isAlwaysShown
        _uiState.update { it.copy(isRecentsAlwaysShown = isAlwaysShown) }
        logState("setRecentsAlwaysShown")
    }

    /**
     * Persists the user's Home pull-down behavior. Choosing the launcher's
     * notification bar prompts for notification listener access if needed; the
     * bar would otherwise open without notification data.
     */
    fun setNotificationPullDownBehavior(behavior: NotificationPullDownBehavior) {
        dockSettingsStore.notificationPullDownBehavior = behavior
        _uiState.update {
            it.copy(
                notificationPullDownBehavior = behavior,
                isNotificationBarOpen =
                    it.isNotificationBarOpen && behavior.showsLauncherNotificationBar,
            )
        }
        logState("setNotificationPullDownBehavior=$behavior")
        if (behavior.showsLauncherNotificationBar && !_uiState.value.hasNotificationAccess) {
            openNotificationAccessSettings()
        }
    }

    fun setKeyboardAutoShown(isAutoShown: Boolean) {
        dockSettingsStore.isKeyboardAutoShown = isAutoShown
        _uiState.update { it.copy(isKeyboardAutoShown = isAutoShown) }
        logState("setKeyboardAutoShown=$isAutoShown")
    }

    fun setThemeMode(mode: ThemeMode) {
        dockSettingsStore.themeMode = mode
        _uiState.update { it.copy(themeMode = mode) }
        logState("setThemeMode=$mode")
    }

    fun setDockVisibleIconCount(count: Int) {
        val clampedCount = count.coerceIn(MIN_DOCK_ICON_COUNT, MAX_DOCK_ICON_COUNT)
        dockSettingsStore.dockIconCount = clampedCount
        _uiState.update { it.copy(dockIconCount = clampedCount) }
        logState("setDockVisibleIconCount requested=$count")
    }

    private fun refreshLists() {
        val query = _uiState.value.query.trim()
        _uiState.update { state ->
            val dockedIds = dockedAppStore.dockedAppIds
            val visibleApps = visibleInstalledApps()
            state.copy(
                filteredApps = visibleApps.filterByName(
                    query = query,
                    appLaunchStatsStore = appLaunchStatsStore,
                    excludedAppIds = dockedIds.takeIf { state.isDockEnabled }.orEmpty(),
                    dockedAppIds = dockedIds,
                    sortOrder = state.appListSortOrder,
                ).markVisibility(),
                dockedApps = visibleApps.filterDocked(dockedAppStore.dockedAppIds).markVisibility(),
                recentApps = visibleApps.filterRecent(appLaunchStatsStore.recentAppIds).markVisibility(),
                hiddenApps = installedApps.filterHidden(hiddenAppStore.hiddenAppIds).markVisibility(),
                notifyingApps = visibleApps
                    .filterNotifying(ActiveNotifications.packages.value)
                    .markVisibility(),
            )
        }
    }

    private fun refreshFilteredApps() {
        val query = _uiState.value.query.trim()
        _uiState.update { state ->
            val dockedIds = dockedAppStore.dockedAppIds
            state.copy(
                filteredApps = visibleInstalledApps().filterByName(
                    query = query,
                    appLaunchStatsStore = appLaunchStatsStore,
                    excludedAppIds = dockedIds.takeIf { state.isDockEnabled }.orEmpty(),
                    dockedAppIds = dockedIds,
                    sortOrder = state.appListSortOrder,
                ).markVisibility(),
            )
        }
    }

    private fun visibleInstalledApps(): List<InstalledApp> =
        installedApps.filterNot { app -> hiddenAppStore.contains(app.id) }

    private fun refreshNotifyingApps(packages: Map<String, Long>) {
        _uiState.update { state ->
            state.copy(
                notifyingApps = visibleInstalledApps()
                    .filterNotifying(packages)
                    .markVisibility(),
            )
        }
    }

    private fun startActivity(intent: Intent) {
        LauncherDebugLog.event("startActivity intent=${intent.debugSummary()}")
        app.startActivity(intent.asLauncherTaskIntent())
    }

    private fun loadAgendaState(): AgendaUiState {
        val hasPermission = hasCalendarPermission()
        LauncherDebugLog.event("loadAgendaState hasPermission=$hasPermission")
        if (!hasPermission) {
            return AgendaUiState.PermissionRequired
        }
        val events = loadAgendaEvents()
        LauncherDebugLog.event("loadAgendaState events=${events.size}")
        return if (events.isEmpty()) AgendaUiState.Empty else AgendaUiState.Events(events)
    }

    private fun hasCalendarPermission(): Boolean =
        ContextCompat.checkSelfPermission(app, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    private fun loadAgendaEvents(nowMillis: Long = System.currentTimeMillis()): List<AgendaEvent> {
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val startOfDay = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val utcTodayStart = today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val utcTomorrowStart = today.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val queryEnd = today.plusDays(AGENDA_LOOKAHEAD_DAYS).atStartOfDay(zone).toInstant().toEpochMilli()
        val instanceUri = CalendarContract.Instances.CONTENT_URI.buildUpon().apply {
            ContentUris.appendId(this, startOfDay)
            ContentUris.appendId(this, queryEnd)
        }.build()
        val events = mutableListOf<AgendaEvent>()
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.DISPLAY_COLOR,
        )

        try {
            app.contentResolver.query(
                instanceUri,
                projection,
                null,
                null,
                "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { cursor ->
                val eventIdIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_ID)
                val titleIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
                val beginIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
                val endIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.END)
                val allDayIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.ALL_DAY)
                val colorIndex = cursor.getColumnIndex(CalendarContract.Instances.DISPLAY_COLOR)
                while (cursor.moveToNext()) {
                    events += AgendaEvent(
                        title = cursor.getString(titleIndex)?.takeIf { it.isNotBlank() }
                            ?: app.getString(R.string.agenda_event_untitled),
                        beginMillis = cursor.getLong(beginIndex),
                        endMillis = cursor.getLong(endIndex),
                        isAllDay = cursor.getInt(allDayIndex) == 1,
                        displayTime = "",
                        eventId = cursor.getLong(eventIdIndex),
                        calendarColor = colorIndex
                            .takeIf { it >= 0 && !cursor.isNull(it) }
                            ?.let(cursor::getInt),
                    )
                }
            }
        } catch (exception: SecurityException) {
            LauncherDebugLog.warning("loadAgendaEvents security exception", exception)
            return emptyList()
        }

        return AgendaEventOrganizer.forNow(
            events = events,
            nowMillis = nowMillis,
            utcTodayStartMillis = utcTodayStart,
            utcTomorrowStartMillis = utcTomorrowStart,
        ).map { event -> event.copy(displayTime = event.formatTime(app)) }
    }

    private fun loadInstalledApps(): List<InstalledApp> {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val personalUser = Process.myUserHandle()
        LauncherDebugLog.event("loadInstalledApps begin")
        val launcherApps = app.getSystemService<LauncherApps>()
        val userManager = app.getSystemService<UserManager>()
        val profiles = launcherApps?.profiles.orEmpty()
            .also { profiles -> LauncherDebugLog.event("loadInstalledApps profiles=${profiles.size}") }
        // Resolve quiet mode once per profile rather than per activity. The
        // personal profile is never in quiet mode, so skip the binder call for
        // it. `isQuietModeEnabled` is documented since API 24 and requires no
        // permission for any profile in the calling user's profile group.
        val quietByUser: Map<UserHandle, Boolean> = profiles.associateWith { user ->
            user != personalUser && (userManager?.isQuietModeEnabled(user) == true)
        }
        val profileApps = profiles
            .flatMap { user ->
                val activities = launcherApps
                    ?.getActivityList(null, user)
                    .orEmpty()
                LauncherDebugLog.event(
                    "loadInstalledApps profile=${user.hashCode()} activities=${activities.size} " +
                        "quiet=${quietByUser[user] == true}",
                )
                activities
                    .map { activity ->
                        InstalledApp(
                            name = activity.label.toString(),
                            packageName = activity.applicationInfo.packageName,
                            launchIntent = Intent.makeMainActivity(activity.componentName),
                            user = user,
                            isWorkApp = user != personalUser || activity.applicationInfo.packageName in workPackages,
                            launchWithLauncherApps = true,
                            iconCacheToken = activity.applicationInfo.iconCacheToken(app.packageManager),
                            isQuietMode = quietByUser[user] == true,
                        )
                    }
            }
        val collected = profileApps
            .ifEmpty {
                val resolveInfos = app.packageManager.queryIntentActivities(launcherIntent, 0)
                LauncherDebugLog.event("loadInstalledApps packageManagerFallback activities=${resolveInfos.size}")
                resolveInfos
                    .map { resolveInfo ->
                        val activityInfo = resolveInfo.activityInfo
                        InstalledApp(
                            name = resolveInfo.loadLabel(app.packageManager).toString(),
                            packageName = activityInfo.packageName,
                            launchIntent = Intent.makeMainActivity(
                                ComponentName(activityInfo.packageName, activityInfo.name),
                            ),
                            user = personalUser,
                            isWorkApp = activityInfo.packageName in workPackages,
                            launchWithLauncherApps = false,
                            iconCacheToken = activityInfo.applicationInfo.iconCacheToken(app.packageManager),
                        )
                    }
            }
            // Keying dedup on `id` (userHandle.hashCode():componentName) lets distinct
            // apps that happen to share a display name survive — e.g. Chase US
            // (com.chase.sig.android) and Chase UK (com.chase.uk.*) both show up,
            // and the disambiguator pass below tags them with regional badges.
            .distinctBy { launcherApp -> launcherApp.id }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { launcherApp -> launcherApp.name })
        return collected
            .applyDisambiguators()
            .also { apps -> LauncherDebugLog.event("loadInstalledApps complete apps=${apps.size}") }
    }

    private fun List<InstalledApp>.applyDisambiguators(): List<InstalledApp> {
        val labels = computeDisambiguators(this)
        if (labels.isEmpty()) return this
        return map { app -> labels[app.id]?.let { app.copy(disambiguator = it) } ?: app }
    }

    private fun loadAvailableWidgets(): List<WidgetProvider> {
        val widgetManager = AppWidgetManager.getInstance(app)
        val personalUser = Process.myUserHandle()
        // Enumerate every available profile (personal + any work profiles) so
        // the picker surfaces work-profile widgets alongside personal ones.
        // `getInstalledProvidersForProfile` is the cross-profile counterpart to
        // `installedProviders`; it dispatches to the same AppWidgetService and
        // is restricted by the platform to profiles the launcher already has
        // access to. Falling back to `Process.myUserHandle()` keeps the picker
        // populated when LauncherApps is unavailable (test / non-launcher
        // contexts).
        val profiles = launcherAppsService?.profiles?.takeIf { it.isNotEmpty() }
            ?: listOf(personalUser)
        return profiles
            .flatMap { profile ->
                val providers = widgetManager.getInstalledProvidersForProfile(profile)
                LauncherDebugLog.event(
                    "loadAvailableWidgets profile=${profile.hashCode()} providers=${providers.size}",
                )
                providers
                    .filter { info ->
                        info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_HIDE_FROM_PICKER == 0
                    }
                    .map { info -> info.toWidgetProvider(app, personalUser) }
            }
            .distinctBy { provider -> provider.id }
            .sortedWith(
                compareBy<WidgetProvider> { provider -> provider.appName.lowercase() }
                    .thenBy { provider -> provider.label.lowercase() },
            )
            .also { providers -> LauncherDebugLog.event("loadAvailableWidgets providers=${providers.size}") }
    }

    private fun showWidgetPicker(availableWidgets: List<WidgetProvider>) {
        _uiState.update {
            it.copy(
                screen = LauncherScreen.Widgets,
                isAddingWidget = true,
                isLoadingAvailableWidgets = false,
                availableWidgets = availableWidgets,
            )
        }
        logState("showWidgetPicker")
    }

    private fun List<InstalledApp>.markVisibility(): List<InstalledApp> =
        // Inputs always come from `installedApps` (via visibleInstalledApps()
        // and the filter helpers, none of which mutate elements), so each
        // app's `isWorkApp` is already authoritative — don't re-lookup.
        // The previous `installedApps.firstOrNull { it.id == app.id }` made
        // this O(n²) over the full installed-app list on every keystroke.
        map { app ->
            val isDocked = dockedAppStore.contains(app.id)
            val isHidden = hiddenAppStore.contains(app.id)
            if (app.isDocked == isDocked && app.isHidden == isHidden) {
                app
            } else {
                app.copy(isDocked = isDocked, isHidden = isHidden)
            }
        }

    companion object {
        fun factory(app: Application, workPackages: Set<String>): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
                    LauncherViewModel(app, workPackages) as T
            }
    }

    private fun logState(reason: String) {
        LauncherDebugLog.event("$reason ${_uiState.value.debugSummary()}")
    }
}

private fun AgendaEvent.formatTime(context: Context): String {
    if (isAllDay) {
        return context.getString(R.string.agenda_all_day_label)
    }
    return DateUtils.formatDateRange(context, beginMillis, endMillis, DateUtils.FORMAT_SHOW_TIME).toString()
}

private fun AppWidgetProviderInfo.toWidgetProvider(context: Context, personalUser: UserHandle): WidgetProvider {
    val packageManager = context.packageManager
    val appInfo = try {
        packageManager.getApplicationInfo(provider.packageName, 0)
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }
    val appName = appInfo?.loadLabel(packageManager)?.toString()
        ?.takeIf { label -> label.isNotBlank() }
        ?: provider.packageName
    val appIcon = appInfo?.loadIcon(packageManager)
    return WidgetProvider(
        appName = appName,
        label = loadLabel(packageManager).takeIf { label -> label.isNotBlank() } ?: appName,
        componentName = provider,
        profile = profile,
        icon = loadIcon(context, 0) ?: appIcon,
        appIcon = appIcon,
        minWidth = minWidth,
        minHeight = minHeight,
        targetCellWidth = targetCellWidth.takeIf { it > 0 } ?: estimateCellSpan(minWidth),
        targetCellHeight = targetCellHeight.takeIf { it > 0 } ?: estimateCellSpan(minHeight),
        previewImage = loadPreviewImage(context, 0),
        isWorkProvider = profile != personalUser,
    )
}

private fun estimateCellSpan(sizeDp: Int): Int =
    ((sizeDp + WIDGET_CELL_ESTIMATE_DP - 1) / WIDGET_CELL_ESTIMATE_DP).coerceAtLeast(1)

private fun ApplicationInfo.iconCacheToken(packageManager: PackageManager): String? {
    val packageInfo = try {
        packageManager.getPackageInfo(packageName, 0)
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }
    return packageInfo?.lastUpdateTime?.takeIf { time -> time > 0L }?.toString()
}

private const val SETTINGS_QUERY = "settings"
private const val AGENDA_LOOKAHEAD_DAYS = 7L
private const val WIDGET_CELL_ESTIMATE_DP = 56

// How many of the most-launched apps to include in the icon snapshot beyond the dock.
// Sized to cover the visible app rows on a typical phone screen (~12 text rows or ~24
// icon-only grid cells) plus a margin so quick scrolls also paint without flashing,
// while keeping cold-start file IO bounded.
private const val SNAPSHOT_TOP_LAUNCH_COUNT = 24
