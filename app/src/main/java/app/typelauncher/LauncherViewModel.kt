package app.typelauncher

import android.Manifest
import android.app.Application
import android.app.role.RoleManager
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.Process
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
    private val widgetStore = WidgetStore(app)
    private val dockSettingsStore = DockSettingsStore(app)
    private val appLaunchStatsStore = AppLaunchStatsStore(app)
    private val appMetadataStore = AppMetadataStore(app)
    private val iconSnapshotStore = IconSnapshotStore(app)
    private val settingsLaunchGate = SettingsLaunchGate()
    private var installedApps: List<InstalledApp> = emptyList()
    private var agendaVersion = 0
    // Set the first time the UI signals that Home is fully drawn and the soft
    // keyboard is up (or a fallback timeout has elapsed). Gates the deferred
    // initial agenda load so the calendar query can't compete with the cold-
    // start app list load on Dispatchers.IO.
    private var initialAgendaTriggered = false
    private val cachedMetadata: List<InstalledApp> = appMetadataStore.load()
    private val _uiState = MutableStateFlow(
        LauncherUiState(
            widgetIds = widgetStore.widgetIds,
            isDockEnabled = dockSettingsStore.isDockEnabled,
            isAppListIconOnly = dockSettingsStore.isAppListIconOnly,
            dockIconCount = dockSettingsStore.dockIconCount,
            appListSortOrder = dockSettingsStore.appListSortOrder,
            isRecentsAlwaysShown = dockSettingsStore.isRecentsAlwaysShown,
            isLoadingApps = cachedMetadata.isEmpty(),
        ),
    )
    val uiState: StateFlow<LauncherUiState> = _uiState.asStateFlow()

    init {
        LauncherDebugLog.event("LauncherViewModel initialized ${_uiState.value.debugSummary()}")
        // Restore previously-rasterised icons synchronously before setContent runs so the
        // first composed frame can pull from AppIconLoader's in-memory cache instead of
        // showing the placeholder surface while LauncherApps + drawable.toBitmap finish.
        val restoredIconCount = iconSnapshotStore.load()
            .onEach { snapshot -> AppIconLoader.put(snapshot.id, snapshot.sizePx, snapshot.bitmap) }
            .size
        if (restoredIconCount > 0) {
            LauncherDebugLog.event("LauncherViewModel restored icon snapshot count=$restoredIconCount")
        }
        if (cachedMetadata.isNotEmpty()) {
            installedApps = cachedMetadata
            _uiState.update { state ->
                val activeDockedIds = dockedAppStore.dockedAppIds
                    .takeIf { state.isDockEnabled }
                    .orEmpty()
                state.copy(
                    filteredApps = installedApps.filterByName(
                        query = state.query,
                        appLaunchStatsStore = appLaunchStatsStore,
                        excludedAppIds = activeDockedIds,
                        sortOrder = state.appListSortOrder,
                    ).markDocked(),
                    dockedApps = installedApps
                        .filterDockedByName(dockedAppStore.dockedAppIds, state.query)
                        .markDocked(),
                    recentApps = installedApps.filterRecent(appLaunchStatsStore.recentAppIds).markDocked(),
                )
            }
            LauncherDebugLog.event("LauncherViewModel rendered cached metadata count=${cachedMetadata.size}")
        }
        viewModelScope.launch {
            val initialLoadTrace = LauncherTelemetry.startTrace("launcher_initial_load")
            val loadedApps = withContext(ioDispatcher) {
                traceBlock("installed_apps_load") { trace ->
                    loadInstalledApps().also { trace.incrementMetric("app_count", it.size.toLong()) }
                }
            }
            initialLoadTrace.incrementMetric("app_count", loadedApps.size.toLong())
            initialLoadTrace.stop()
            installedApps = loadedApps
            _uiState.update { state ->
                val activeDockedIds = dockedAppStore.dockedAppIds
                    .takeIf { state.isDockEnabled }
                    .orEmpty()
                state.copy(
                    filteredApps = installedApps.filterByName(
                        query = state.query,
                        appLaunchStatsStore = appLaunchStatsStore,
                        excludedAppIds = activeDockedIds,
                        sortOrder = state.appListSortOrder,
                    ).markDocked(),
                    dockedApps = installedApps
                        .filterDockedByName(dockedAppStore.dockedAppIds, state.query)
                        .markDocked(),
                    recentApps = installedApps.filterRecent(appLaunchStatsStore.recentAppIds).markDocked(),
                    isLoadingApps = false,
                    isFreshAppLoadComplete = true,
                )
            }
            launch(ioDispatcher) { appMetadataStore.save(loadedApps) }
            LauncherDebugLog.event("LauncherViewModel initial load complete ${_uiState.value.debugSummary()}")
        }
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
        _uiState.update { state -> state.copy(query = query) }
        refreshLists()
        LauncherDebugLog.event(
            "setQuery length=${query.length} filtered=${_uiState.value.filteredApps.size} " +
                "docked=${_uiState.value.dockedApps.size}",
        )
    }

    fun showAgenda() {
        _uiState.update { it.copy(screen = LauncherScreen.Agenda, isRecentsOpen = false) }
        logState("showAgenda")
        loadAgendaAsync(reason = "showAgenda", traceName = "agenda_load")
    }

    fun showWidgets() {
        _uiState.update { it.copy(screen = LauncherScreen.Widgets, isRecentsOpen = false) }
        logState("showWidgets")
    }

    fun setRecentsOpen(isOpen: Boolean) {
        if (_uiState.value.isRecentsOpen == isOpen) return
        _uiState.update { it.copy(isRecentsOpen = isOpen) }
        logState("setRecentsOpen=$isOpen")
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
            )
        }
        logState("returnToLauncherHome")
    }

    fun refreshPermissionDrivenUi() {
        LauncherDebugLog.event("refreshPermissionDrivenUi screen=${_uiState.value.screen}")
        val isDefaultLauncher = app.getSystemService<RoleManager>()?.isRoleHeld(RoleManager.ROLE_HOME) ?: false
        _uiState.update { it.copy(isDefaultLauncher = isDefaultLauncher) }
        if (_uiState.value.screen == LauncherScreen.Agenda) {
            refreshAgenda()
        }
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
        val priorityIds = priorityIconAppIds()
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

    private fun priorityIconAppIds(): Set<String> {
        val docked = dockedAppStore.dockedAppIds.toSet()
        val topByLaunches = installedApps
            .asSequence()
            .map { app -> app.id to appLaunchStatsStore.launchCount(app.id) }
            .filter { (_, count) -> count > 0 }
            .sortedByDescending { (_, count) -> count }
            .take(SNAPSHOT_TOP_LAUNCH_COUNT)
            .map { (id, _) -> id }
            .toSet()
        return docked + topByLaunches
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
        // enabled, so fall back to launching the first matching dock entry
        // when no non-docked match exists. Otherwise a query that only
        // resolves to docked apps would leave Enter doing nothing despite a
        // visible matching dock icon. The fallback is gated on the dock
        // being enabled because dockedApps is still populated when the dock
        // UI is hidden — without the gate, a no-match query under a hidden
        // dock could surprise-launch an invisible entry.
        val state = _uiState.value
        val target = state.filteredApps.firstOrNull()
            ?: state.dockedApps.firstOrNull()?.takeIf { state.isDockEnabled }
        target?.let(::launchApp)
    }

    fun launchApp(app: InstalledApp) {
        val component = app.launchIntent.component
        LauncherDebugLog.event(
            "launchApp package=${app.packageName} component=${component?.flattenToShortString()} " +
                "work=${app.isWorkApp} launcherApps=${app.launchWithLauncherApps}",
        )
        if (app.launchWithLauncherApps && component != null) {
            this.app.getSystemService<LauncherApps>()?.startMainActivity(component, app.user, null, null)
        } else {
            startActivity(app.launchIntent.asLauncherTaskIntent())
        }
        appLaunchStatsStore.recordLaunch(app.id)
        // Close the recents panel as we leave Home — when the user comes back
        // it should be tucked away again, not still expanded.
        _uiState.update { it.copy(isRecentsOpen = false) }
        setQuery("")
    }

    fun openAppInfo(app: InstalledApp) {
        LauncherDebugLog.event("openAppInfo package=${app.packageName}")
        startActivity(app.appInfoIntent)
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

    fun resetRank(app: InstalledApp) {
        LauncherDebugLog.event("resetRank package=${app.packageName}")
        appLaunchStatsStore.resetLaunchCount(app.id)
        refreshLists()
        logState("resetRank")
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
            )
        }
        logState("addWidget")
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
        _uiState.update { it.copy(screen = LauncherScreen.Widgets, widgetIds = widgetStore.widgetIds) }
        logState("removeWidget")
    }

    fun openSettings() {
        _uiState.update { it.copy(isSettingsOpen = true, isRecentsOpen = false) }
        logState("openSettings")
    }

    fun closeSettings() {
        _uiState.update { it.copy(isSettingsOpen = false) }
        logState("closeSettings")
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

    fun setDockVisibleIconCount(count: Int) {
        val clampedCount = count.coerceIn(MIN_DOCK_ICON_COUNT, MAX_DOCK_ICON_COUNT)
        dockSettingsStore.dockIconCount = clampedCount
        _uiState.update { it.copy(dockIconCount = clampedCount) }
        logState("setDockVisibleIconCount requested=$count")
    }

    private fun refreshLists() {
        val query = _uiState.value.query.trim()
        _uiState.update { state ->
            state.copy(
                filteredApps = installedApps.filterByName(
                    query = query,
                    appLaunchStatsStore = appLaunchStatsStore,
                    excludedAppIds = dockedAppStore.dockedAppIds.takeIf { state.isDockEnabled }.orEmpty(),
                    sortOrder = state.appListSortOrder,
                ).markDocked(),
                dockedApps = installedApps.filterDockedByName(dockedAppStore.dockedAppIds, query).markDocked(),
                recentApps = installedApps.filterRecent(appLaunchStatsStore.recentAppIds).markDocked(),
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
        val profileApps = launcherApps
            ?.profiles
            .orEmpty()
            .also { profiles -> LauncherDebugLog.event("loadInstalledApps profiles=${profiles.size}") }
            .flatMap { user ->
                val activities = launcherApps
                    ?.getActivityList(null, user)
                    .orEmpty()
                LauncherDebugLog.event("loadInstalledApps profile=${user.hashCode()} activities=${activities.size}")
                activities
                    .map { activity ->
                        InstalledApp(
                            name = activity.label.toString(),
                            packageName = activity.applicationInfo.packageName,
                            launchIntent = Intent.makeMainActivity(activity.componentName),
                            user = user,
                            isWorkApp = user != personalUser || activity.applicationInfo.packageName in workPackages,
                            launchWithLauncherApps = true,
                        )
                    }
            }
        return profileApps
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
                        )
                    }
            }
            .distinctBy { launcherApp -> launcherApp.name.lowercase() to launcherApp.isWorkApp }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { launcherApp -> launcherApp.name })
            .also { apps -> LauncherDebugLog.event("loadInstalledApps complete apps=${apps.size}") }
    }

    private fun loadAvailableWidgets(): List<WidgetProvider> =
        AppWidgetManager.getInstance(app)
            .installedProviders
            .filter { provider -> provider.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_HIDE_FROM_PICKER == 0 }
            .map { provider -> provider.toWidgetProvider(app) }
            .sortedWith(
                compareBy<WidgetProvider> { provider -> provider.appName.lowercase() }
                    .thenBy { provider -> provider.label.lowercase() },
            )
            .also { providers -> LauncherDebugLog.event("loadAvailableWidgets providers=${providers.size}") }

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

    private fun List<InstalledApp>.markDocked(): List<InstalledApp> =
        map { launcherApp ->
            val storedApp = installedApps.firstOrNull { installedApp -> installedApp.id == launcherApp.id } ?: launcherApp
            launcherApp.copy(
                isDocked = dockedAppStore.contains(launcherApp.id),
                isWorkApp = storedApp.isWorkApp,
            )
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

private fun AppWidgetProviderInfo.toWidgetProvider(context: Context): WidgetProvider {
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
    )
}

private fun estimateCellSpan(sizeDp: Int): Int =
    ((sizeDp + WIDGET_CELL_ESTIMATE_DP - 1) / WIDGET_CELL_ESTIMATE_DP).coerceAtLeast(1)

private const val SETTINGS_QUERY = "settings"
private const val AGENDA_LOOKAHEAD_DAYS = 7L
private const val WIDGET_CELL_ESTIMATE_DP = 56

// How many of the most-launched apps to include in the icon snapshot beyond the dock.
// Sized to cover the visible app rows on a typical phone screen (~12 text rows or ~24
// icon-only grid cells) plus a margin so quick scrolls also paint without flashing,
// while keeping cold-start file IO bounded.
private const val SNAPSHOT_TOP_LAUNCH_COUNT = 24
