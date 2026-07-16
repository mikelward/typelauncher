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
import android.content.res.Configuration
import android.net.Uri
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import android.text.format.DateUtils
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.google.android.play.core.install.model.InstallStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

internal class LauncherViewModel(
    private val app: Application,
    private val workPackages: Set<String>,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val dockedAppStore = DockedAppStore(app)
    private val workDockedAppStore = DockedAppStore(app, DockedAppStore.WORK_PREFERENCES_NAME)
    private val hiddenAppStore = HiddenAppStore(app)
    private val renamedAppStore = RenamedAppStore(app)
    private val customBadgeStore = CustomBadgeStore(app)
    private val iconOverrideStore = IconOverrideStore(app)
    private val widgetStore = WidgetStore(app)
    private val dockSettingsStore = DockSettingsStore(app)
    private val appLaunchStatsStore = AppLaunchStatsStore(app)
    private val appMetadataStore = AppMetadataStore(app)
    private val iconSnapshotStore = IconSnapshotStore(app)
    private val playUpdateStore = PlayUpdateStore(app)
    // In-flight progress for the currently-known available version. Lets the
    // banner reflect Play's flexible-update download state (Starting →
    // Downloading → Downloaded) instead of just vanishing on first tap.
    private var currentPlayUpdateProgress: UpdateProgress = UpdateProgress.Idle
    private var installedApps: List<InstalledApp> = emptyList()
    // Set when the icon-picker callback fires before the cold-start
    // `loadInstalledApps()` coroutine has populated [installedApps] — the
    // launcher can be rebuilt from saved-instance after a full process
    // death while the document picker is foreground, and the rebuilt
    // session has no cached metadata to look the picked app up against.
    // Drained inside the same fresh-load coroutine that publishes
    // `isFreshAppLoadComplete = true`, so the override is applied as soon
    // as the authoritative app list lands instead of being silently dropped.
    private var pendingIconOverrideRequest: Pair<String, Uri>? = null
    private var agendaVersion = 0
    // Set the first time the UI signals that Home is fully drawn and the soft
    // keyboard is up (or a fallback timeout has elapsed). Gates the deferred
    // initial agenda load so the calendar query can't compete with the cold-
    // start app list load on Dispatchers.IO.
    private var initialAgendaTriggered = false
    // In-memory indices behind the typed-search content sections. Loaded off
    // the main thread (home-ready, source enable, permission grant, resume)
    // and filtered synchronously per keystroke alongside `filteredApps`, so
    // the keystroke path never touches a content resolver. Main-thread
    // confined; empty whenever the matching source is disabled.
    private var contactIndex: List<ContactResult> = emptyList()
    private var searchEventIndex: List<AgendaEvent> = emptyList()
    // Deconflicts concurrent index loads the same way `agendaVersion` does for
    // agenda loads: only the most recent request may publish. @Volatile because
    // the IO stage re-reads it before touching a provider (see
    // refreshContentSearchIndices) while every write happens on Main.
    @Volatile
    private var contentSearchVersion = 0
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
            AppIconLoader.evict(packageName, user)
            scheduleReload("packageAdded:$packageName")
        }
        override fun onPackageRemoved(packageName: String, user: UserHandle) {
            AppIconLoader.evict(packageName, user)
            scheduleReload("packageRemoved:$packageName")
        }
        override fun onPackageChanged(packageName: String, user: UserHandle) {
            AppIconLoader.evict(packageName, user)
            scheduleReload("packageChanged:$packageName")
        }
        override fun onPackagesAvailable(
            packageNames: Array<out String>,
            user: UserHandle,
            replacing: Boolean,
        ) {
            packageNames.forEach { AppIconLoader.evict(it, user) }
            scheduleReload("packagesAvailable:${packageNames.size}")
        }
        override fun onPackagesUnavailable(
            packageNames: Array<out String>,
            user: UserHandle,
            replacing: Boolean,
        ) {
            packageNames.forEach { AppIconLoader.evict(it, user) }
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
            // When the profile becomes available again — resumed from quiet mode
            // (AVAILABLE) or unlocked with its credential (UNLOCKED) — the
            // platform stops painting its "Couldn't add widget" placeholder
            // inside work-profile host views, but the already-rendered
            // placeholder isn't refreshed until the host view re-attaches. Nudge
            // the work-profile widget cards to recreate their host views so they
            // re-fetch RemoteViews now. Both transitions are covered because a
            // locked profile resumes via UNLOCKED, not AVAILABLE.
            if (action == Intent.ACTION_MANAGED_PROFILE_AVAILABLE ||
                action == Intent.ACTION_MANAGED_PROFILE_UNLOCKED
            ) {
                refreshWorkProfileWidgets()
            }
        }
    }
    private var managedProfileReceiverRegistered = false
    // Receiver for the date/time/timezone rollover broadcasts. Apps such as
    // Google Calendar redraw their launcher icon to show the current date
    // without bumping the package's `lastUpdateTime`, so the token-based cache
    // key never changes on its own and a long-lived launcher process would keep
    // painting yesterday's icon. At the midnight rollover (or a manual date /
    // time / timezone change that shifts the local date) we reload the app
    // list, which re-stamps the dynamic-calendar cache tokens for the new day
    // (see `applyDynamicCalendarToken`) so the on-screen icon refreshes within a
    // frame or two. The killed-overnight case is handled separately by the
    // per-day cache key surviving into `IconSnapshotStore`.
    private val dateChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            scheduleReload("dateChanged:$action")
            // Refresh the typed-search event *set* on a day/clock/zone change so
            // a foreground launcher rolls the 14-day window forward and drops
            // events that ended overnight, rather than waiting for the next
            // resume (the now-relative labels themselves are formatted per query
            // in eventResultsFor, so they never go stale on their own).
            refreshContentSearchIndices("dateChanged:$action")
        }
    }
    private var dateChangedReceiverRegistered = false
    // Set when a `LauncherApps.Callback` event fires before the cold-start
    // fresh load has published its result. We don't run a reload concurrently
    // with cold-start (it could race the cold-start state update and lose),
    // but we can't drop the event either — the cold-start `getActivityList`
    // may have already returned pre-event data. The cold-start coroutine
    // checks this flag after publishing and triggers a reload if set.
    private var reloadPendingDuringColdStart = false
    private var pendingWidgetPlacement: PendingWidgetPlacement? = null
    private val _uiState = MutableStateFlow(
        LauncherUiState(
            widgetIds = widgetStore.widgetIds,
            widgetPages = widgetStore.widgetPages,
            widgetHeights = widgetStore.customHeights,
            widgetProviderLabels = widgetStore.providerLabels,
            isDockEnabled = dockSettingsStore.isDockEnabled,
            appListLayout = dockSettingsStore.appListLayout,
            dockLayout = dockSettingsStore.dockLayout,
            dockIconSizeDp = dockSettingsStore.dockIconSizeDp,
            isWorkDockEnabled = dockSettingsStore.isWorkDockEnabled,
            appListSortOrder = dockSettingsStore.appListSortOrder,
            isKeyboardAutoShown = dockSettingsStore.isKeyboardAutoShown,
            isWallpaperShown = dockSettingsStore.isWallpaperShown,
            cardOpacity = dockSettingsStore.cardOpacity,
            keyboardReservation = dockSettingsStore.keyboardReservation,
            isAgendaEnabled = dockSettingsStore.isAgendaEnabled,
            isContactSearchEnabled = dockSettingsStore.isContactSearchEnabled,
            isCalendarSearchEnabled = dockSettingsStore.isCalendarSearchEnabled,
            themeMode = dockSettingsStore.themeMode,
            iconShape = dockSettingsStore.iconShape,
            iconTheme = dockSettingsStore.iconTheme,
            isLoadingApps = cachedMetadata.isEmpty(),
            playUpdate = PlayUpdateState.NotAvailable,
        ),
    )
    val uiState: StateFlow<LauncherUiState> = _uiState.asStateFlow()

    // One-shot signal the launcher uses to ask the search field to grab focus
    // and re-raise the soft keyboard. Emitted when the user pull-up gesture
    // reaches its second stage (recents bar already open), and when the activity
    // regains window focus on Home after the initial cold start. The IME show
    // itself happens in `SearchCard` because the Android IME only appears when
    // an editable view holds focus, and the `FocusRequester` lives there.
    private val _keyboardShowRequests = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val keyboardShowRequests: SharedFlow<Unit> = _keyboardShowRequests.asSharedFlow()

    init {
        LauncherDebugLog.event("LauncherViewModel initialized ${_uiState.value.debugSummary()}")
        // A backup restore or permission auto-reset can leave a content-search
        // toggle persisted on without its permission; coerce before anything
        // can render or load from the stale flag.
        coerceContentSearchSettingsToPermissions()
        // Seed the loader's mirror of the icon theme setting before any load
        // can run (loads start from composition, after this constructor), so
        // the very first rasterize pass already honors the persisted setting.
        AppIconLoader.iconTheme = dockSettingsStore.iconTheme
        // Seed the loader's resolved palette mirror alongside, so the very
        // first themed rasterize pass picks the palette matching what the
        // launcher's own Theme setting renders, not whatever the system night
        // mask happens to say.
        AppIconLoader.setThemedIconPalette(app, isLauncherThemeDark(dockSettingsStore.themeMode))
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
                        // The expected renderer state reads the AppIconLoader
                        // mirrors seeded synchronously in init above, so a
                        // snapshot saved under a different icon theme / palette
                        // (e.g. the theme changed but the process died before
                        // the post-eviction re-save) is purged, not restored.
                        val snapshots = iconSnapshotStore.load(currentIconRendererState())
                        for (snapshot in snapshots) {
                            // Dynamic calendar icons are date-specific, so a snapshot
                            // bitmap is stale the moment the day changes (or whenever a
                            // prior build cached the wrong day). Skip them on restore so
                            // the icon re-resolves fresh instead of painting a stale date.
                            if (isDynamicCalendarIconId(snapshot.id)) continue
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
                installedApps = cachedMetadata
                    .applyRenameOverrides()
                    .applyIconOverrides()
                    .applyDynamicCalendarToken()
                _uiState.update { state ->
                    val dockedIds = dockedAppIdsForState(state)
                    val workDockedIds = workDockedAppIdsForState(state)
                    val visibleApps = visibleInstalledApps()
                    val newRecentApps = visibleApps.filterRecent(appLaunchStatsStore.recentAppIds).markVisibility()
                    state.copy(
                        filteredApps = visibleApps.filterByName(
                            query = state.query,
                            appLaunchStatsStore = appLaunchStatsStore,
                            excludedAppIds = excludedFromAppList(state, dockedIds),
                            dockedAppIds = floatingDockedIdsForState(state, dockedIds, workDockedIds),
                            sortOrder = effectiveAppListSortOrder(state.appListSortOrder, state.homeLandscapeTier),
                        ).markVisibility(),
                        dockedApps = visibleApps
                            .filterDocked(dockedIds)
                            .markVisibility(),
                        dockPositions = dockedAppStore.dockedAppPositions,
                        dockFolders = resolvedDockFolders(visibleApps, dockedAppStore),
                        shouldShowDockAddHint = dockedAppStore.shouldShowAddButtonHint,
                        workDockedApps = visibleApps
                            .filterDocked(workDockedIds)
                            .markVisibility(),
                        workDockPositions = workDockedAppStore.dockedAppPositions,
                        workDockFolders = resolvedDockFolders(visibleApps, workDockedAppStore),
                        shouldShowWorkDockAddHint = workDockedAppStore.shouldShowAddButtonHint,
                        isWorkProfileConfigured = installedApps.any { it.isWorkApp },
                        isWorkProfileActive = installedApps.any { it.isWorkApp && !it.isQuietMode },
                        recentApps = newRecentApps,
                        hiddenApps = installedApps.filterHidden(hiddenAppStore.hiddenAppIds).markVisibility(),
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
                    // Prefill against what this device can actually render (see
                    // `deviceRenderableDockIconCount`) so a narrow phone that
                    // clamps the rendered row keeps an empty cell for the "+".
                    val deviceDockIconCount = deviceRenderableDockIconCount()
                    // Reserve one slot for the "+" add-button hint.
                    prefillDock(loadedApps, dockedAppStore, (deviceDockIconCount - 1).coerceAtLeast(0))
                    // Order matters: `prefillDock` calls `dockedAppStore.dock`,
                    // which clears the hint flag. Setting the flag here — after
                    // prefill returns — means the flag survives any number of
                    // prefill seeds and stays true until the first user dock.
                    // Skipped on upgrade installs where `prefillDock` did not
                    // run, so existing dock users never see the onboarding hint.
                    dockedAppStore.setShowAddButtonHint(
                        dockedAppStore.dockedAppIds.size < deviceDockIconCount,
                    )
                }
                dockedAppStore.markPrefilled()
            }
            maybePrefillWorkDock(loadedApps)
            _uiState.update { state ->
                val dockedIds = dockedAppIdsForState(state)
                val workDockedIds = workDockedAppIdsForState(state)
                val visibleApps = visibleInstalledApps()
                val newRecentApps = visibleApps.filterRecent(appLaunchStatsStore.recentAppIds).markVisibility()
                state.copy(
                    filteredApps = visibleApps.filterByName(
                        // Trimmed to match every steady-state refresh
                        // (refreshLists / refreshFilteredApps): this publish
                        // can land mid-typing, and filtering the raw string
                        // would silently change the visible results for a
                        // whitespace-padded query — a lone " " stops meaning
                        // "show all", and "maps " stops matching Maps.
                        query = state.query.trim(),
                        appLaunchStatsStore = appLaunchStatsStore,
                        excludedAppIds = excludedFromAppList(state, dockedIds),
                        dockedAppIds = floatingDockedIdsForState(state, dockedIds, workDockedIds),
                        sortOrder = effectiveAppListSortOrder(state.appListSortOrder, state.homeLandscapeTier),
                    ).markVisibility(),
                    dockedApps = visibleApps
                        .filterDocked(dockedIds)
                        .markVisibility(),
                    dockPositions = dockedAppStore.dockedAppPositions,
                    dockFolders = resolvedDockFolders(visibleApps, dockedAppStore),
                    shouldShowDockAddHint = dockedAppStore.shouldShowAddButtonHint,
                    workDockedApps = visibleApps
                        .filterDocked(workDockedIds)
                        .markVisibility(),
                    workDockPositions = workDockedAppStore.dockedAppPositions,
                    workDockFolders = resolvedDockFolders(visibleApps, workDockedAppStore),
                    shouldShowWorkDockAddHint = workDockedAppStore.shouldShowAddButtonHint,
                    isWorkProfileConfigured = installedApps.any { it.isWorkApp },
                    isWorkProfileActive = installedApps.any { it.isWorkApp && !it.isQuietMode },
                    recentApps = newRecentApps,
                    hiddenApps = installedApps.filterHidden(hiddenAppStore.hiddenAppIds).markVisibility(),
                    isLoadingApps = false,
                    isFreshAppLoadComplete = true,
                )
            }
            launch(ioDispatcher) { appMetadataStore.save(loadedApps) }
            // Replay any icon-pick that arrived during the cold-start window
            // (e.g. picker callback fired after a process-death recreation
            // before this load finished). Has to happen *after*
            // `installedApps = loadedApps` so the lookup runs against the
            // freshly-loaded list rather than the empty / cached-metadata one.
            //
            // Skip the drain when a package event landed during cold start:
            // the snapshot we just loaded predates the event, so the queued
            // id might still resolve to an `InstalledApp` the upcoming
            // reload will drop. The deferred `scheduleReload` below kicks
            // off a fresh load and drains there instead.
            if (!reloadPendingDuringColdStart) {
                drainPendingIconOverrideRequest()
            }
            LauncherDebugLog.event("LauncherViewModel initial load complete ${_uiState.value.debugSummary()}")
            if (reloadPendingDuringColdStart) {
                reloadPendingDuringColdStart = false
                scheduleReload("coldStartCompletedWithPendingEvent")
            }
        }
        registerLauncherAppsCallback()
        registerManagedProfileReceiver()
        registerDateChangedReceiver()
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
     * dimmed/normal state, plus `ACTION_MANAGED_PROFILE_UNLOCKED` so a profile
     * resumed by entering its credential refreshes its widgets too (a locked
     * profile resumes via UNLOCKED, not AVAILABLE). Reuses `scheduleReload` so
     * quiet-mode flips share the same coalescing + cold-start deferral as
     * package events. `RECEIVER_NOT_EXPORTED` is required on API 34+ for runtime
     * receivers; all three actions are protected system broadcasts only the OS
     * can send, so not-exported is the correct flag. Idempotent.
     */
    private fun registerManagedProfileReceiver() {
        if (managedProfileReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MANAGED_PROFILE_AVAILABLE)
            addAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE)
            addAction(Intent.ACTION_MANAGED_PROFILE_UNLOCKED)
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
     * Registers a runtime receiver for `ACTION_DATE_CHANGED` (fired at the
     * midnight rollover and on a manual date set) plus `ACTION_TIME_CHANGED` /
     * `ACTION_TIMEZONE_CHANGED` (clock and zone changes that can also shift the
     * local date), so the launcher refreshes dynamic-calendar app icons such as
     * Google Calendar when the day changes. Routes through `scheduleReload` so a
     * reload re-stamps the calendar cache tokens (see `applyDynamicCalendarToken`)
     * and shares the same coalescing + cold-start deferral as package events.
     * All three are protected system broadcasts only the OS can send, so
     * `RECEIVER_NOT_EXPORTED` is the correct flag (same as the managed-profile
     * receiver). Idempotent.
     */
    private fun registerDateChangedReceiver() {
        if (dateChangedReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        try {
            app.registerReceiver(dateChangedReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            dateChangedReceiverRegistered = true
            LauncherDebugLog.event("dateChangedReceiver registered")
        } catch (exception: RuntimeException) {
            LauncherDebugLog.warning("dateChangedReceiver register failed", exception)
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
            // The work-dock prefill skips (without latching) while the work
            // profile is paused, promising a retry "on the next fresh load
            // after the profile is resumed" — and resuming lands here via
            // ACTION_MANAGED_PROFILE_AVAILABLE → scheduleReload, not via
            // another cold start. Latched and guarded internally, so this is
            // a no-op on every reload after the seed (or when the work dock
            // is disabled).
            maybePrefillWorkDock(loadedApps)
            refreshLists()
            // Replay any icon-pick that was queued because cold-start
            // hadn't finished — `scheduleReload` runs both for ordinary
            // package events post-load and for the deferred-during-cold-
            // start replay. In the latter case the queued pick lands on
            // the post-reload list rather than on the stale cold-start
            // snapshot, which avoids writing an override file for a
            // package the reload would have dropped.
            drainPendingIconOverrideRequest()
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
        if (dateChangedReceiverRegistered) {
            try {
                app.unregisterReceiver(dateChangedReceiver)
                LauncherDebugLog.event("dateChangedReceiver unregistered")
            } catch (exception: RuntimeException) {
                LauncherDebugLog.warning("dateChangedReceiver unregister failed", exception)
            }
            dateChangedReceiverRegistered = false
        }
        super.onCleared()
    }

    internal fun reloadInstalledAppsForTest() {
        scheduleReload("test")
    }

    /**
     * Test seam for the paused-work-profile filter. Robolectric's
     * `ShadowLauncherApps` does not surface a foreign profile that
     * `ShadowUserManager.setQuietModeEnabled` can target, so the only way to
     * exercise the `isWorkApp && isQuietMode` filter that hides paused
     * work apps from every surface is to flip the two fields directly on
     * an existing personal-profile entry and re-derive the public lists.
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
     * Test seam mirroring [markAsPausedWorkAppForTest] but for the
     * profile-active branch: marks the matching personal-profile entry as
     * `isWorkApp = true, isQuietMode = false` and re-derives the dependent
     * lists. Used by the work-dock screenshot tests to flip
     * `isWorkProfileActive` true without going through `LauncherApps` (which
     * Robolectric's shadow cannot model for a real managed profile).
     */
    internal fun markAsActiveWorkAppForTest(packageName: String) {
        installedApps = installedApps.map { app ->
            if (app.packageName == packageName) {
                app.copy(isWorkApp = true, isQuietMode = false)
            } else {
                app
            }
        }
        refreshLists()
    }

    /**
     * Test seam that adds a work-profile *clone* of the existing personal
     * entry for [packageName], rather than flipping the personal entry's
     * profile flag. Mirrors the on-device shape where popular apps (Gmail,
     * Calendar, etc.) are installed in both profiles: the personal copy
     * stays in the main app list, and the cloned work copy carries
     * `isWorkApp = true` plus the supplied [workUser] handle so its
     * [InstalledApp.id] is distinct from the personal copy's id (the id
     * folds in `user.hashCode()`). No-op when no personal entry for the
     * given package exists yet.
     */
    internal fun addWorkProfileCopyForTest(packageName: String, workUser: UserHandle) {
        val personal = installedApps.firstOrNull { app ->
            app.packageName == packageName && !app.isWorkApp
        } ?: return
        if (installedApps.any { app -> app.packageName == packageName && app.isWorkApp }) return
        val clone = personal.copy(
            user = workUser,
            isWorkApp = true,
            isQuietMode = false,
        )
        installedApps = installedApps + clone
        refreshLists()
    }

    /**
     * Called by the UI once the Home screen has fully drawn its app list and
     * the soft keyboard is in place (or a fallback timeout has elapsed).
     * Publishes `isHomeReady` for downstream consumers (e.g. MainActivity's
     * deferred `AppWidgetHost.startListening`) and triggers the deferred first
     * agenda load on the IO dispatcher when the Agenda page is enabled.
     * Idempotent.
     */
    fun onHomeReady() {
        if (_uiState.value.isHomeReady) return
        _uiState.update { it.copy(isHomeReady = true) }
        LauncherDebugLog.event("onHomeReady published")
        triggerInitialAgendaLoadIfEnabled(reason = "homeReady")
        // Same deferral rationale as the agenda load: the content-search
        // indices are cold-start-adjacent IO, so they wait for home-ready
        // rather than competing with the app list on Dispatchers.IO.
        refreshContentSearchIndices(reason = "homeReady")
    }

    private fun triggerInitialAgendaLoadIfEnabled(reason: String) {
        val state = _uiState.value
        if (initialAgendaTriggered || !state.isHomeReady || !state.isAgendaEnabled) return
        initialAgendaTriggered = true
        LauncherDebugLog.event("$reason starting deferred agenda load")
        loadAgendaAsync(reason = reason, traceName = "agenda_initial_load")
    }

    fun setQuery(query: String) {
        // Query-only refresh: dock / recents / hidden lists don't
        // depend on the query, and rebuilding them on every keystroke produces
        // fresh list references that recompose unrelated UI for nothing. The
        // worst case is backspace, which grows the result set back toward the
        // full app list and made the lag user-visible.
        _uiState.update { state -> state.copy(query = query) }
        refreshFilteredApps()
    }

    fun showAgenda() {
        if (!_uiState.value.isAgendaEnabled) {
            LauncherDebugLog.event("showAgenda ignored; agenda disabled")
            return
        }
        _uiState.update {
            it.copy(
                destination = LauncherDestination.Agenda,
                isRecentsOpen = false,
            )
        }
        logState("showAgenda")
        loadAgendaAsync(reason = "showAgenda", traceName = "agenda_load")
    }

    fun showWidgets() {
        showWidgets(_uiState.value.lastWidgetPage)
    }

    fun showWidgets(pageIndex: Int) {
        _uiState.update { state ->
            val clamped = pageIndex.coerceInWidgetPages(state.widgetPages)
            state.copy(
                destination = LauncherDestination.Widgets(clamped),
                lastWidgetPage = clamped,
                isRecentsOpen = false,
            )
        }
        logState("showWidgets")
    }

    fun setRecentsOpen(isOpen: Boolean) {
        if (_uiState.value.isRecentsOpen == isOpen) return
        _uiState.update { it.copy(isRecentsOpen = isOpen) }
        logState("setRecentsOpen=$isOpen")
    }

    fun requestShowKeyboard() {
        // The soft keyboard owns the reserved slot, so an explicit request to
        // show it closes the user-opened recents tray right away — otherwise
        // the force-shown bar lingers over the keyboard's grow for the frame or
        // two before its insets register. Covers a pull-up that requests the
        // keyboard straight from an open tray.
        _uiState.update { it.copy(isRecentsOpen = false) }
        val emitted = _keyboardShowRequests.tryEmit(Unit)
        LauncherDebugLog.event("requestShowKeyboard emitted=$emitted")
    }

    fun setHomeLandscapeTier(tier: HomeLandscapeTier) {
        if (_uiState.value.homeLandscapeTier == tier) return
        _uiState.update { it.copy(homeLandscapeTier = tier) }
        // The filtered list depends on the tier in two ways, so it has to be
        // recomputed when the tier flips — e.g. rotating a phone into or out of
        // cramped landscape. (1) The dock-dedupe in `excludedFromAppList`: the
        // Compact state hides the docks, so their apps must reappear in the
        // list. (2) `effectiveAppListSortOrder`: the Compact state always sorts
        // by usage regardless of the persisted "Sort apps by" choice, so the
        // data ordering changes when entering/leaving Compact under a non-usage
        // sort.
        refreshFilteredApps()
        LauncherDebugLog.event("setHomeLandscapeTier tier=$tier")
    }

    /**
     * Pushed from the Compose layer: true while the keyboard is up in the
     * landscape `DockNoKeyboard` tier, where raising the keyboard hides the
     * dock. The filtered list has to be recomputed when it flips so the
     * dock-dedupe in [excludedFromAppList] follows the dock — docked apps
     * reappear in the list while the dock is suppressed, then re-dedupe once
     * the keyboard is dismissed and the dock returns.
     */
    fun setDockSuppressedByKeyboard(suppressed: Boolean) {
        if (_uiState.value.dockSuppressedByKeyboard == suppressed) return
        _uiState.update { it.copy(dockSuppressedByKeyboard = suppressed) }
        refreshFilteredApps()
        LauncherDebugLog.event("setDockSuppressedByKeyboard=$suppressed")
    }

    fun setKeyboardReservation(reservation: KeyboardReservation) {
        val coerced = reservation.copy(bottomPx = reservation.bottomPx.coerceAtLeast(0))
        if (_uiState.value.keyboardReservation == coerced) return
        dockSettingsStore.keyboardReservation = coerced
        _uiState.update { it.copy(keyboardReservation = coerced) }
        refreshFilteredApps()
        LauncherDebugLog.event(
            "setKeyboardReservation bottomPx=${coerced.bottomPx} source=${coerced.source} " +
                "config=${coerced.configFingerprint}",
        )
    }

    fun requestShowKeyboardOnHomeResume() {
        val state = _uiState.value
        if (
            state.destination is LauncherDestination.Home &&
            !state.isSettingsOpen &&
            !state.isAddingWidget &&
            state.isKeyboardAutoShown &&
            state.homeLandscapeTier == HomeLandscapeTier.Full
        ) {
            requestShowKeyboard()
        } else {
            LauncherDebugLog.event(
                "requestShowKeyboardOnHomeResume skipped destination=${state.destination} " +
                    "settings=${state.isSettingsOpen} addingWidget=${state.isAddingWidget} " +
                    "autoShown=${state.isKeyboardAutoShown} tier=${state.homeLandscapeTier}",
            )
        }
    }

    /**
     * Closes the open bottom bar (recents) when the launcher is resumed to
     * Home, so returning from another app starts with a clean Home rather than
     * carrying a stale open bar back.
     */
    fun closeSecondaryTrayOnResume() {
        val state = _uiState.value
        if (
            state.destination is LauncherDestination.Home &&
            !state.isSettingsOpen &&
            !state.isAddingWidget &&
            state.isRecentsOpen
        ) {
            _uiState.update { it.copy(isRecentsOpen = false) }
            LauncherDebugLog.event("closeSecondaryTrayOnResume")
        }
    }

    fun showWidgetPicker(
        pageIndex: Int = _uiState.value.lastWidgetPage,
        addToNewPageAfterSelection: Boolean = false,
    ) {
        pendingWidgetPlacement = PendingWidgetPlacement(
            pageIndex = pageIndex.coerceInWidgetPages(_uiState.value.widgetPages),
            addToNewPageAfterSelection = addToNewPageAfterSelection,
        )
        _uiState.update {
            val clamped = pageIndex.coerceInWidgetPages(it.widgetPages)
            it.copy(
                destination = LauncherDestination.Widgets(clamped),
                lastWidgetPage = clamped,
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
        pendingWidgetPlacement = null
        _uiState.update { it.copy(isAddingWidget = false, isLoadingAvailableWidgets = false) }
        logState("hideWidgetPicker")
    }

    fun showHome() {
        _uiState.update { it.copy(destination = LauncherDestination.Home) }
        logState("showHome")
    }

    fun returnToLauncherHome() {
        pendingWidgetPlacement = null
        val hadQuery = _uiState.value.query.isNotEmpty()
        _uiState.update {
            it.copy(
                destination = LauncherDestination.Home,
                isSettingsOpen = false,
                isAddingWidget = false,
                isLoadingAvailableWidgets = false,
                isRecentsOpen = false,
                query = "",
                homeReturnToken = it.homeReturnToken + 1,
            )
        }
        // Clearing the query has to rebuild the filtered list so the app list
        // matches the now-empty search field. Skip the rebuild when nothing was
        // typed so a plain home press on an already-empty Home doesn't churn
        // filteredApps (and recompose the list) for nothing.
        if (hadQuery) refreshFilteredApps()
        logState("returnToLauncherHome")
    }

    fun refreshPermissionDrivenUi() {
        LauncherDebugLog.event("refreshPermissionDrivenUi destination=${_uiState.value.destination}")
        val isDefaultLauncher = app.getSystemService<RoleManager>()?.isRoleHeld(RoleManager.ROLE_HOME) ?: false
        _uiState.update { it.copy(isDefaultLauncher = isDefaultLauncher) }
        if (_uiState.value.destination is LauncherDestination.Agenda) {
            refreshAgenda()
        }
        // Auto-reset (or a settings restore) may have revoked a source's
        // permission while its toggle is persisted on — coerce the toggles
        // first so the refresh below never queries a permission-less source.
        coerceContentSearchSettingsToPermissions()
        // Re-read contacts / calendar on every resume so edits made in the
        // Contacts or Calendar app while the launcher was backgrounded show up
        // in the next typed search. No-op unless a source is enabled.
        refreshContentSearchIndices(reason = "resume")
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
        // launch counts get cleaned up. The renderer state is captured here, alongside
        // the cacheSnapshot() harvest it describes, not inside the IO coroutine.
        val rendererState = currentIconRendererState()
        viewModelScope.launch(ioDispatcher) { iconSnapshotStore.save(snapshots, rendererState) }
    }

    /**
     * The renderer-state fingerprint for [IconSnapshotStore]: the icon theme plus the
     * resolved themed palette, read from the `AppIconLoader` mirrors (which this view
     * model seeds in init, before the snapshot restore launches, and keeps current on
     * every theme / palette change).
     */
    private fun currentIconRendererState(): String =
        IconSnapshotStore.rendererState(AppIconLoader.iconTheme, AppIconLoader.themedIconColors)

    private fun priorityIconCacheIds(): Set<String> {
        // `dockedOrFolderedAppIds` already unions loose docked apps with folder
        // members, so a folder of rarely-launched apps keeps its members' icon
        // snapshots and the dock paints real mini-icons on the next cold start.
        val docked = dockedAppStore.dockedOrFolderedAppIds + workDockedAppStore.dockedOrFolderedAppIds
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
            // Never persist a dynamic calendar icon: it depicts a specific day, so a
            // saved bitmap is wrong on any later day. Leaving it out means the next
            // save also prunes a previously-saved (now stale) one off disk.
            .filter { app -> app.id in priorityIds && app.packageName !in DYNAMIC_CALENDAR_PACKAGES }
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

    /**
     * Returns whether the calendar app actually launched, so callers that
     * chain side effects onto a successful handoff (the search path clearing
     * the query in [openEventResult]) can skip them when no calendar app
     * handles the intent. The Agenda screen ignores the result — its tap has
     * no follow-up state to guard.
     */
    fun openAgendaEvent(event: AgendaEvent): Boolean {
        LauncherDebugLog.event("openAgendaEvent eventId=${event.eventId} begin=${event.beginMillis}")
        val eventUri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, event.eventId)
        val intent = Intent(Intent.ACTION_VIEW, eventUri)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, event.beginMillis)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, event.endMillis)
        return try {
            startActivity(intent)
            true
        } catch (exception: ActivityNotFoundException) {
            LauncherDebugLog.warning("openAgendaEvent no activity for event uri", exception)
            false
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
        // Typing hides both docks and surfaces their apps in `filteredApps`
        // (floated to the top), so a docked match is normally the first
        // filtered result and resolves through `filteredApps.firstOrNull()`
        // below. The dock lookups remain as defense-in-depth for the edge
        // where no filtered match exists; they re-run the matcher rather than
        // reading the rows' displayed order so the query, not row position,
        // picks the target. The work dock is checked after the personal dock
        // so the personal copy of a shared app (e.g. Gmail) wins when both
        // profiles have it.
        val state = _uiState.value
        val target = state.filteredApps.firstOrNull()
            ?: state.dockedApps.firstOrNull { app -> app.displayName.launcherMatchTier(trimmedQuery) != null }
                ?.takeIf { state.isDockEnabled }
            ?: state.workDockedApps.firstOrNull { app -> app.displayName.launcherMatchTier(trimmedQuery) != null }
                ?.takeIf { state.isWorkDockEnabled && state.isWorkProfileActive }
        if (target != null) {
            launchApp(target)
            return
        }
        // Only when *zero* apps match does the first content result become the
        // Enter target — apps always own Enter when they match at any tier. The
        // order mirrors the rendered sections: contacts before events.
        val contact = state.contactResults.firstOrNull()
        if (contact != null) {
            openContactResult(contact)
            return
        }
        state.eventResults.firstOrNull()?.let(::openEventResult)
    }

    /**
     * Opens the tapped (or Enter-targeted) contact as Android's QuickContact
     * card — the compact call / message / email popup — instead of the full
     * Contacts screen, so acting on a searched-for person is a single tap from
     * the keyboard-driven result. The card is drawn by the Contacts app from
     * the same lookup URI the index already holds, so no phone number is read
     * and no new permission is needed; a contact with several numbers is
     * disambiguated by the card itself, and tapping the card's name still opens
     * the full contact. The search is cleared like an app launch, mirroring
     * [openEventResult]: only when the handoff actually launched, so a device
     * with no Contacts app to host the card leaves the query intact rather than
     * silently erasing it. No launch count is recorded — ranking inside the
     * section is match-tier + alphabetical, not usage.
     */
    fun openContactResult(contact: ContactResult) {
        LauncherDebugLog.event("openContactResult contactId=${contact.contactId}")
        val lookupUri = ContactsContract.Contacts.getLookupUri(contact.contactId, contact.lookupKey)
        val intent = Intent(ContactsContract.QuickContact.ACTION_QUICK_CONTACT).apply {
            data = lookupUri
            putExtra(ContactsContract.QuickContact.EXTRA_MODE, ContactsContract.QuickContact.MODE_LARGE)
        }
        try {
            startActivity(intent)
        } catch (exception: ActivityNotFoundException) {
            LauncherDebugLog.warning("openContactResult no activity for quick contact", exception)
            return
        }
        _uiState.update { it.copy(isRecentsOpen = false) }
        setQuery("")
    }

    /**
     * Opens an events-section result in the user's calendar app (same intent
     * path as tapping an Agenda row) and clears the search, mirroring
     * [openContactResult]. Like that path, the query is only cleared when the
     * handoff actually launched — on a device with no calendar app the tap
     * stays a no-op instead of silently erasing what the user typed.
     */
    fun openEventResult(event: AgendaEvent) {
        if (!openAgendaEvent(event)) return
        _uiState.update { it.copy(isRecentsOpen = false) }
        setQuery("")
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
        // Close the recents panel as we leave Home — when the user comes back
        // it should be tucked away again, not still expanded from before.
        _uiState.update { it.copy(isRecentsOpen = false) }
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

    /**
     * Unified "Dock" / "Undock" toggle for the long-press menu. Routes based
     * on the app's current membership so it always does the inverse of what
     * the menu label says:
     *  - already in the personal dock → undock from personal (regardless of
     *    `isWorkApp`, since the user pinned it there before the work dock
     *    existed and the menu shows "Undock");
     *  - already in the work dock → undock from work;
     *  - work app + work dock enabled + in neither dock → dock to work;
     *  - otherwise → dock to personal.
     */
    fun toggleDock(app: InstalledApp, maxDockedApps: Int) {
        val state = _uiState.value
        LauncherDebugLog.event(
            "toggleDock package=${app.packageName} docked=${app.isDocked} " +
                "workDocked=${app.isWorkDocked} workDockEnabled=${state.isWorkDockEnabled} " +
                "max=$maxDockedApps",
        )
        val columns = deviceRenderableDockIconCount(state.dockIconSizeDp)
        when {
            app.isDocked -> dockedAppStore.undock(app.id)
            app.isWorkDocked -> workDockedAppStore.undock(app.id)
            app.isWorkApp && state.isWorkDockEnabled ->
                workDockedAppStore.dock(app.id, columnCount = columns)
            else ->
                dockedAppStore.dock(app.id, columnCount = columns)
        }
        refreshLists()
        logState("toggleDock")
    }

    fun reorderDockedApps(appId: String, row: Int, column: Int) {
        val state = _uiState.value
        LauncherDebugLog.event("reorderDockedApps appId=$appId row=$row column=$column")
        dockedAppStore.move(
            appId = appId,
            row = row,
            column = column,
            columnCount = deviceRenderableDockIconCount(state.dockIconSizeDp),
            sortOrder = state.appListSortOrder,
        )
        refreshLists()
        logState("reorderDockedApps")
    }

    /**
     * Dock an app from the app list onto the personal dock at ([row], [column])
     * (the drag-from-list-to-dock gesture). Docks it, then moves it where it was
     * dropped — `move` swaps any occupant aside, exactly like a dock drag.
     */
    fun dockAppAtPosition(appId: String, row: Int, column: Int) =
        dockAppAtPosition(appId, row, column, dockedAppStore, "dockAppAtPosition")

    /**
     * Work-dock variant of [dockAppAtPosition]. The caller only routes a drop
     * here when the dragged app is a work app released over the work dock, so the
     * work dock stays work-apps-only.
     */
    fun dockAppAtWorkDockPosition(appId: String, row: Int, column: Int) =
        dockAppAtPosition(appId, row, column, workDockedAppStore, "dockAppAtWorkDockPosition")

    private fun dockAppAtPosition(
        appId: String,
        row: Int,
        column: Int,
        store: DockedAppStore,
        tag: String,
    ) {
        val state = _uiState.value
        LauncherDebugLog.event("$tag appId=$appId row=$row column=$column")
        val columns = deviceRenderableDockIconCount(state.dockIconSizeDp)
        store.dock(appId, columns)
        store.move(appId, row, column, columns, state.appListSortOrder)
        refreshLists()
        logState(tag)
    }

    /**
     * Dock an app from the app list and merge it onto an existing personal-dock
     * occupant [targetId], grouping them into a folder (the dock's center-drop
     * gesture). Guarded like [mergeDockFolderMemberInto]: a no-op if the target
     * folder is already at the member cap, so the app stays in the list.
     */
    fun dockAppIntoDockOccupant(appId: String, targetId: String) =
        dockAppIntoDockOccupant(appId, targetId, dockedAppStore, "dockAppIntoDockOccupant")

    /**
     * Work-dock variant of [dockAppIntoDockOccupant]. Routed only for a work app
     * released onto a work-dock occupant, so a work folder never gains a personal
     * member.
     */
    fun dockAppIntoWorkDockOccupant(appId: String, targetId: String) =
        dockAppIntoDockOccupant(appId, targetId, workDockedAppStore, "dockAppIntoWorkDockOccupant")

    private fun dockAppIntoDockOccupant(
        appId: String,
        targetId: String,
        store: DockedAppStore,
        tag: String,
    ) {
        val state = _uiState.value
        LauncherDebugLog.event("$tag appId=$appId target=$targetId")
        val targetFolder = store.dockFolders.firstOrNull { it.id == targetId }
        if (targetFolder != null && targetFolder.memberAppIds.size >= MAX_DOCK_FOLDER_MEMBERS) {
            return
        }
        val columns = deviceRenderableDockIconCount(state.dockIconSizeDp)
        store.dock(appId, columns)
        store.mergeIntoFolder(appId, targetId, columns)
        refreshLists()
        logState(tag)
    }

    /**
     * Explicit work-dock toggle invoked from the long-press menu on a
     * [WorkDockCard] entry. Operates only on the work-dock store, so the
     * dual-pin edge case (a work app prefilled into the work dock while still
     * being in the personal dock) un-docks from the work dock specifically —
     * leaving the personal entry untouched. The general [toggleDock] picks
     * the dock by app state, which is what the app-list menu wants but not
     * what a work-dock-card menu wants when both flags are true.
     */
    fun toggleWorkDock(app: InstalledApp, maxDockedApps: Int) {
        val state = _uiState.value
        LauncherDebugLog.event(
            "toggleWorkDock package=${app.packageName} workDocked=${app.isWorkDocked} " +
                "max=$maxDockedApps",
        )
        if (app.isWorkDocked) {
            workDockedAppStore.undock(app.id)
        } else {
            workDockedAppStore.dock(app.id, columnCount = deviceRenderableDockIconCount(state.dockIconSizeDp))
        }
        refreshLists()
        logState("toggleWorkDock")
    }

    fun reorderWorkDockedApps(appId: String, row: Int, column: Int) {
        val state = _uiState.value
        LauncherDebugLog.event("reorderWorkDockedApps appId=$appId row=$row column=$column")
        workDockedAppStore.move(
            appId = appId,
            row = row,
            column = column,
            columnCount = deviceRenderableDockIconCount(state.dockIconSizeDp),
            sortOrder = state.appListSortOrder,
        )
        refreshLists()
        logState("reorderWorkDockedApps")
    }

    // region Dock folders

    /**
     * Merge two personal-dock occupants into a folder (the drag-onto-icon
     * gesture). [targetId] may already be a folder, in which case [sourceId] is
     * added to it.
     */
    fun mergeDockItems(sourceId: String, targetId: String) =
        mergeDockItems(sourceId, targetId, dockedAppStore, "mergeDockItems")

    fun mergeWorkDockItems(sourceId: String, targetId: String) =
        mergeDockItems(sourceId, targetId, workDockedAppStore, "mergeWorkDockItems")

    private fun mergeDockItems(sourceId: String, targetId: String, store: DockedAppStore, tag: String) {
        LauncherDebugLog.event("$tag source=$sourceId target=$targetId")
        store.mergeIntoFolder(sourceId, targetId, deviceRenderableDockIconCount(_uiState.value.dockIconSizeDp))
        refreshLists()
        logState(tag)
    }

    fun addAppToDockFolder(folderId: String, appId: String) =
        addAppToFolder(folderId, appId, dockedAppStore, "addAppToDockFolder")

    fun addAppToWorkDockFolder(folderId: String, appId: String) =
        addAppToFolder(folderId, appId, workDockedAppStore, "addAppToWorkDockFolder")

    private fun addAppToFolder(folderId: String, appId: String, store: DockedAppStore, tag: String) {
        LauncherDebugLog.event("$tag folder=$folderId app=$appId")
        store.addToFolder(folderId, appId, deviceRenderableDockIconCount(_uiState.value.dockIconSizeDp))
        refreshLists()
        logState(tag)
    }

    fun removeAppFromDockFolder(folderId: String, appId: String) =
        removeAppFromFolder(folderId, appId, dockedAppStore, "removeAppFromDockFolder")

    fun removeAppFromWorkDockFolder(folderId: String, appId: String) =
        removeAppFromFolder(folderId, appId, workDockedAppStore, "removeAppFromWorkDockFolder")

    private fun removeAppFromFolder(folderId: String, appId: String, store: DockedAppStore, tag: String) {
        LauncherDebugLog.event("$tag folder=$folderId app=$appId")
        store.removeFromFolder(folderId, appId, deviceRenderableDockIconCount(_uiState.value.dockIconSizeDp))
        refreshLists()
        logState(tag)
    }

    fun undockAppFromDockFolder(folderId: String, appId: String) =
        undockAppFromFolder(folderId, appId, dockedAppStore, "undockAppFromDockFolder")

    fun undockAppFromWorkDockFolder(folderId: String, appId: String) =
        undockAppFromFolder(folderId, appId, workDockedAppStore, "undockAppFromWorkDockFolder")

    /**
     * "Undock" a folder member: take it out of the folder and off the dock
     * entirely (vs "Move out", which pops it back to a loose dock icon). The
     * member is removed from the folder, which would normally re-dock it loose,
     * then immediately undocked so it leaves the dock altogether.
     */
    private fun undockAppFromFolder(folderId: String, appId: String, store: DockedAppStore, tag: String) {
        LauncherDebugLog.event("$tag folder=$folderId app=$appId")
        val columns = deviceRenderableDockIconCount(_uiState.value.dockIconSizeDp)
        store.removeFromFolder(folderId, appId, columns)
        store.undock(appId)
        refreshLists()
        logState(tag)
    }

    fun renameDockFolder(folderId: String, name: String?) {
        LauncherDebugLog.event("renameDockFolder folder=$folderId")
        (folderStoreFor(folderId)).renameFolder(folderId, name)
        refreshLists()
        logState("renameDockFolder")
    }

    fun explodeDockFolder(folderId: String) {
        LauncherDebugLog.event("explodeDockFolder folder=$folderId")
        folderStoreFor(folderId).explodeFolder(folderId, deviceRenderableDockIconCount(_uiState.value.dockIconSizeDp))
        refreshLists()
        logState("explodeDockFolder")
    }

    // Reorder a member within its folder (Overlay-style drag). Folder ids are
    // unique across both stores, so route to whichever dock owns it.
    fun reorderDockFolderMember(folderId: String, appId: String, targetMemberId: String) {
        LauncherDebugLog.event("reorderDockFolderMember folder=$folderId app=$appId target=$targetMemberId")
        folderStoreFor(folderId).reorderFolderMember(folderId, appId, targetMemberId)
        refreshLists()
        logState("reorderDockFolderMember")
    }

    /**
     * Drag a member out of [folderId] and drop it onto the dock at ([row],
     * [column]) as a loose icon. Removing it from the folder re-docks it loose
     * (auto-collapsing a now-single-member folder); the follow-up move places it
     * where it was dropped, swapping any occupant aside exactly like a dock drag.
     */
    fun moveDockFolderMemberToDock(folderId: String, appId: String, row: Int, column: Int) {
        LauncherDebugLog.event("moveDockFolderMemberToDock folder=$folderId app=$appId row=$row column=$column")
        val state = _uiState.value
        val columns = deviceRenderableDockIconCount(state.dockIconSizeDp)
        val store = folderStoreFor(folderId)
        store.removeFromFolder(folderId, appId, columns)
        store.move(appId, row, column, columns, state.appListSortOrder)
        refreshLists()
        logState("moveDockFolderMemberToDock")
    }

    /**
     * Drag a member out of [folderId] and drop it onto another dock occupant
     * [targetId], merging the two into a folder (the dock's center-drop gesture).
     * The member is first removed from its folder (re-docked loose) so the merge
     * sees it as a top-level occupant.
     */
    fun mergeDockFolderMemberInto(folderId: String, appId: String, targetId: String) {
        LauncherDebugLog.event("mergeDockFolderMemberInto folder=$folderId app=$appId target=$targetId")
        val state = _uiState.value
        val columns = deviceRenderableDockIconCount(state.dockIconSizeDp)
        val store = folderStoreFor(folderId)
        // Bail before touching the source folder if the merge can't land: dropping
        // onto a target folder already at the member cap would otherwise strip the
        // member from its folder (and possibly collapse it) for a merge that then
        // no-ops — unlike a normal dock merge, which is an atomic no-op. Merging
        // onto a loose app always lands (it forms a new 2-member folder).
        val targetFolder = store.dockFolders.firstOrNull { it.id == targetId }
        if (targetFolder != null && targetFolder.memberAppIds.size >= MAX_DOCK_FOLDER_MEMBERS) {
            return
        }
        store.removeFromFolder(folderId, appId, columns)
        store.mergeIntoFolder(appId, targetId, columns)
        refreshLists()
        logState("mergeDockFolderMemberInto")
    }

    // A folder id is unique across both stores, so route a rename/explode to
    // whichever dock actually owns it.
    private fun folderStoreFor(folderId: String): DockedAppStore =
        if (workDockedAppStore.dockFolders.any { it.id == folderId }) workDockedAppStore else dockedAppStore

    private fun resolvedDockFolders(
        visibleApps: List<InstalledApp>,
        store: DockedAppStore,
    ): List<ResolvedDockFolder> {
        val byId = visibleApps.associateBy { it.id }
        return store.dockFolders.mapNotNull { folder ->
            val members = folder.memberAppIds.mapNotNull { byId[it] }
            if (members.isEmpty()) {
                null
            } else {
                ResolvedDockFolder(
                    id = folder.id,
                    members = members,
                    name = folder.name,
                    // The store's own count (hidden/uninstalled members
                    // included), so UI capacity checks match the store's.
                    persistedMemberCount = folder.memberAppIds.size,
                )
            }
        }
    }

    // endregion

    fun resetRank(app: InstalledApp) {
        LauncherDebugLog.event("resetRank package=${app.packageName}")
        appLaunchStatsStore.resetLaunchCount(app.id)
        refreshLists()
        logState("resetRank")
    }

    /**
     * Removes [app] from the recents bar without touching its launch count —
     * "Dismiss" on the recents bar takes that one entry off the bar, not
     * resetting the app's rank in the main list.
     */
    fun removeRecent(app: InstalledApp) {
        LauncherDebugLog.event("removeRecent package=${app.packageName}")
        appLaunchStatsStore.removeRecent(app.id)
        refreshLists()
        logState("removeRecent")
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

    /**
     * Sets a user-supplied display label for [app] that overrides the system-
     * provided launcher name everywhere in the UI and in typed search. Passing
     * a blank string, or one that matches the **currently rendered** default
     * label ([InstalledApp.displayBase], which is the raw system name for
     * personal apps and the work-prefixed form for work-profile apps), clears
     * any existing override (so the app reverts to whatever the launcher
     * would otherwise display). Comparing against `displayBase` rather than
     * the raw `name` matters for work-profile apps: a user who explicitly
     * renames "Work Calendar" to "Calendar" is choosing to drop the prefix,
     * and we must keep that override rather than treating it as a clear
     * because the trimmed text happens to match the raw system label. The
     * override is keyed by [InstalledApp.id], so it survives package
     * upgrades but a fresh install (which produces a new component) starts
     * from the system label again.
     */
    fun renameApp(app: InstalledApp, newName: String) {
        val trimmed = newName.trim()
        val effective: String? = if (trimmed.isEmpty() || trimmed == app.displayBase) {
            LauncherDebugLog.event("renameApp clear package=${app.packageName}")
            renamedAppStore.clear(app.id)
            null
        } else {
            LauncherDebugLog.event("renameApp package=${app.packageName} length=${trimmed.length}")
            renamedAppStore.rename(app.id, trimmed)
            trimmed
        }
        // Mirror the override onto the master installed-app list so the next
        // `filterByName` (which sorts and matches against `displayName`) sees
        // the new label. `markVisibility` later re-applies the same value when
        // it copies isDocked / isHidden / customName onto each derived list,
        // but it runs *after* `filterByName` — without this in-place patch,
        // typing the override into the search field returns no matches until
        // the next reload re-derives `displayName`.
        installedApps = installedApps.map { existing ->
            if (existing.id == app.id && existing.customName != effective) {
                existing.copy(customName = effective)
            } else {
                existing
            }
        }
        refreshLists()
        logState("renameApp")
    }

    /**
     * Sets a user-chosen corner-badge glyph for [app] that overrides the
     * auto-derived country/regional badge. Pass an empty / blank string to
     * clear the override (the app reverts to whatever the disambiguator pass
     * computed, or no badge at all). The override is keyed by [InstalledApp.
     * id] and persists across package upgrades but not fresh installs, the
     * same way [renameApp] is keyed.
     */
    fun setAppBadge(app: InstalledApp, glyph: String?) {
        val trimmed = glyph?.trim().orEmpty()
        val effective: String? = if (trimmed.isEmpty()) {
            LauncherDebugLog.event("setAppBadge clear package=${app.packageName}")
            customBadgeStore.clear(app.id)
            null
        } else {
            LauncherDebugLog.event("setAppBadge package=${app.packageName} length=${trimmed.length}")
            customBadgeStore.setBadge(app.id, trimmed)
            trimmed
        }
        // Mirror the override onto the master installed-app list so the next
        // render of any list derived from `installedApps` sees the new badge
        // before `markVisibility` next runs (parallels the in-place patch in
        // `renameApp` so the dialog can stay open and reflect the change).
        installedApps = installedApps.map { existing ->
            if (existing.id == app.id && existing.customBadge != effective) {
                existing.copy(customBadge = effective)
            } else {
                existing
            }
        }
        refreshLists()
        logState("setAppBadge")
    }

    /**
     * Replaces the launcher icon for [app] with the user-supplied image at
     * [sourceUri] (PNG, JPEG, WEBP, or SVG). The bytes are copied into
     * `filesDir/icon_overrides/` so the override survives package upgrades
     * and process restarts but is wiped if the launcher itself is
     * uninstalled — overrides are scoped to the launcher, not stored on
     * the picked file's original location (which may be a transient
     * `content://` Uri whose grant disappears when the picker activity
     * goes away).
     *
     * Runs the IO + decode work on [ioDispatcher] so the UI stays
     * responsive while a large image is being copied. Returns immediately;
     * the StateFlow update happens off the main thread once the file is on
     * disk.
     */
    fun setAppIconOverride(app: InstalledApp, sourceUri: Uri) {
        setAppIconOverrideInternal(app, sourceUri)
    }

    /**
     * Id-based overload used by the icon-picker callback in
     * `TypeLauncherApp`. The picker activity runs out-of-process and may
     * survive a launcher recreation or full process death; the picker
     * callback only carries an `InstalledApp.id` (round-tripped through
     * `rememberSaveable`), so the ViewModel does the lookup against the
     * authoritative installed-app list. Drops the request silently if the
     * id no longer resolves — for example if the package was uninstalled
     * while the picker was foreground — rather than racing the user with
     * an icon override against a since-vanished package.
     */
    fun setAppIconOverride(appId: String, sourceUri: Uri) {
        // Queue *every* cold-start pick — even when the cached-metadata
        // prefill already has [appId] — until the authoritative
        // `loadInstalledApps()` result lands. The cache can disagree with
        // the fresh load: a package uninstalled during the picker's
        // foreground window still appears in cached metadata, and an
        // override applied here would persist to disk even though the
        // fresh load drops the app. Deferring keeps the override file
        // from being written for a now-defunct InstalledApp; the drain
        // resolves against the post-load list and either applies cleanly
        // or logs a "dropped" event.
        if (!_uiState.value.isFreshAppLoadComplete) {
            LauncherDebugLog.event("setAppIconOverride deferred: id=$appId pending fresh load")
            pendingIconOverrideRequest = appId to sourceUri
            return
        }
        val app = installedApps.firstOrNull { it.id == appId }
        if (app == null) {
            LauncherDebugLog.event("setAppIconOverride dropped: id=$appId not found")
            return
        }
        setAppIconOverrideInternal(app, sourceUri)
    }

    private fun drainPendingIconOverrideRequest() {
        val request = pendingIconOverrideRequest ?: return
        pendingIconOverrideRequest = null
        val (appId, uri) = request
        val app = installedApps.firstOrNull { it.id == appId }
        if (app == null) {
            LauncherDebugLog.event("setAppIconOverride drained dropped: id=$appId not found in fresh load")
            return
        }
        setAppIconOverrideInternal(app, uri)
    }

    private fun setAppIconOverrideInternal(app: InstalledApp, sourceUri: Uri) {
        LauncherDebugLog.event("setAppIconOverride package=${app.packageName} scheme=${sourceUri.scheme}")
        viewModelScope.launch {
            val savedFile = withContext(ioDispatcher) {
                runCatching {
                    val resolver = this@LauncherViewModel.app.contentResolver
                    val mimeType = resolver.getType(sourceUri)
                    val extension = inferIconExtension(mimeType, sourceUri)
                        ?: throw IllegalArgumentException("Unsupported icon mime=$mimeType uri=$sourceUri")
                    val input = resolver.openInputStream(sourceUri)
                        ?: throw IllegalStateException("openInputStream returned null for $sourceUri")
                    input.use { stream -> iconOverrideStore.setIcon(app.id, stream, extension) }
                }.onFailure { error ->
                    LauncherDebugLog.event(
                        "setAppIconOverride failed package=${app.packageName} err=${error.javaClass.simpleName}",
                    )
                }.getOrNull()
            } ?: return@launch
            // Drop any cached bitmaps still pinned under the previous override
            // version so the very next render rasterises the new file. Without
            // this, the pre-upload entry would linger in the LRU until aged
            // out — harmless for correctness (the new `iconCacheId` misses and
            // triggers a fresh load) but a waste of memory.
            AppIconLoader.evict(app.packageName, app.user)
            val path = savedFile.absolutePath
            // Read the version the store captured at commit rather than
            // re-stat'ing: markVisibility later compares against exactly this
            // cached value, so reading the same source keeps the two in step.
            val version = iconOverrideStore.iconVersionFor(app.id)
            installedApps = installedApps.map { existing ->
                if (existing.id == app.id) {
                    existing.copy(customIconPath = path, customIconVersion = version)
                } else {
                    existing
                }
            }
            refreshLists()
            logState("setAppIconOverride")
        }
    }

    /**
     * Drops [app]'s user-supplied icon override (no-op if none was set) and
     * reverts every surface to whatever Android ships for the package. Counter
     * to [setAppIconOverride], the file IO is small (one or two `delete`s) so
     * this runs synchronously on the main thread alongside the in-memory
     * patch and `refreshLists` call.
     */
    fun clearAppIconOverride(app: InstalledApp) {
        LauncherDebugLog.event("clearAppIconOverride package=${app.packageName}")
        iconOverrideStore.clear(app.id)
        AppIconLoader.evict(app.packageName, app.user)
        installedApps = installedApps.map { existing ->
            if (existing.id == app.id && existing.customIconPath != null) {
                existing.copy(customIconPath = null, customIconVersion = 0L)
            } else {
                existing
            }
        }
        refreshLists()
        logState("clearAppIconOverride")
    }

    private fun inferIconExtension(mimeType: String?, uri: Uri): String? {
        when (mimeType?.lowercase()) {
            "image/svg+xml" -> return "svg"
            "image/png" -> return "png"
            "image/jpeg", "image/jpg" -> return "jpg"
            "image/webp" -> return "webp"
        }
        // Fall back to the path extension for `file://` Uris and for `content://`
        // providers that don't supply a MIME type. A handful of pickers (notably
        // some file-manager apps) hand back `application/octet-stream` and we
        // only have the path to go on.
        val pathExt = uri.lastPathSegment?.substringAfterLast('.', missingDelimiterValue = "")?.lowercase()
        return when (pathExt) {
            "svg", "png", "jpg", "jpeg", "webp" -> if (pathExt == "jpeg") "jpg" else pathExt
            else -> null
        }
    }

    fun addWidget(appWidgetId: Int) {
        val placement = pendingWidgetPlacement ?: PendingWidgetPlacement(
            pageIndex = _uiState.value.lastWidgetPage,
            addToNewPageAfterSelection = false,
        )
        val targetPage = if (placement.addToNewPageAfterSelection) {
            (placement.pageIndex + 1).coerceAtMost(widgetStore.widgetPages.size)
        } else {
            placement.pageIndex.coerceInWidgetPages(widgetStore.widgetPages)
        }
        LauncherDebugLog.event(
            "addWidget appWidgetId=$appWidgetId page=${placement.pageIndex} " +
                "newPage=${placement.addToNewPageAfterSelection}",
        )
        widgetStore.add(
            appWidgetId = appWidgetId,
            pageIndex = placement.pageIndex,
            addToNewPageAfter = placement.addToNewPageAfterSelection,
        )
        rememberWidgetProvider(appWidgetId)
        pendingWidgetPlacement = null
        _uiState.update {
            val widgetPages = widgetStore.widgetPages
            val clamped = targetPage.coerceInWidgetPages(widgetPages)
            it.copy(
                destination = LauncherDestination.Widgets(clamped),
                lastWidgetPage = clamped,
                isAddingWidget = false,
                isLoadingAvailableWidgets = false,
                widgetIds = widgetStore.widgetIds,
                widgetPages = widgetPages,
                widgetHeights = widgetStore.customHeights,
                widgetProviderLabels = widgetStore.providerLabels,
            )
        }
        logState("addWidget")
    }

    /**
     * Records the provider behind a just-added widget so a future backup
     * restore can offer to re-bind it (see [WidgetProviderRecord]). The widget
     * is bound by the time [addWidget] runs, so [AppWidgetManager.getAppWidgetInfo]
     * resolves here even though it won't after a restore strands the ID. Best
     * effort: a null lookup (a bind that resolves late) just skips the record,
     * and the widget falls back to the plain "unavailable" card.
     */
    private fun rememberWidgetProvider(appWidgetId: Int) {
        // getAppWidgetInfo crosses AppWidgetService and loadLabel goes through
        // PackageManager, so resolve off the main thread — this runs from the
        // add/replace success callback, on the frame that dismisses the picker
        // or configure screen. Only the resolve is on IO; the store write and
        // state update hop back to the main thread, where every other widget
        // mutation lives, so the store's in-memory pages are never touched
        // across threads.
        viewModelScope.launch {
            val record = withContext(ioDispatcher) {
                val info = runCatching { AppWidgetManager.getInstance(app).getAppWidgetInfo(appWidgetId) }.getOrNull()
                val component = info?.provider ?: return@withContext null
                val profile = info.profile ?: Process.myUserHandle()
                val serial = app.getSystemService<UserManager>()?.getSerialNumberForUser(profile) ?: 0L
                val label = info.loadLabel(app.packageManager)?.takeIf { it.isNotBlank() } ?: component.packageName
                WidgetProviderRecord(component, serial, label)
            } ?: return@launch
            // The widget may have been removed while the lookup ran; don't
            // resurrect a provider entry for an ID that's no longer tracked.
            if (appWidgetId !in widgetStore.widgetIds) return@launch
            widgetStore.setProvider(appWidgetId, record)
            _uiState.update { it.copy(widgetProviderLabels = widgetStore.providerLabels) }
            LauncherDebugLog.event(
                "rememberWidgetProvider id=$appWidgetId component=${record.component.flattenToShortString()}",
            )
        }
    }

    fun moveWidget(appWidgetId: Int, direction: WidgetMoveDirection) {
        LauncherDebugLog.event("moveWidget appWidgetId=$appWidgetId direction=$direction")
        widgetStore.move(appWidgetId, direction)
        _uiState.update {
            val widgetPages = widgetStore.widgetPages
            val current = (it.destination as? LauncherDestination.Widgets)?.pageIndex ?: it.lastWidgetPage
            val clamped = current.coerceInWidgetPages(widgetPages)
            it.copy(
                destination = LauncherDestination.Widgets(clamped),
                lastWidgetPage = clamped,
                widgetIds = widgetStore.widgetIds,
                widgetPages = widgetPages,
                widgetHeights = widgetStore.customHeights,
                widgetProviderLabels = widgetStore.providerLabels,
            )
        }
        logState("moveWidget")
    }

    fun resizeWidget(appWidgetId: Int, heightDp: Int) {
        LauncherDebugLog.event("resizeWidget appWidgetId=$appWidgetId heightDp=$heightDp")
        widgetStore.setCustomHeight(appWidgetId, heightDp)
        _uiState.update { it.copy(widgetHeights = widgetStore.customHeights) }
        logState("resizeWidget")
    }

    /**
     * Bumps [LauncherUiState.workProfileWidgetRefreshToken] so work-profile
     * widget cards recreate their host view and re-fetch RemoteViews now that
     * the managed profile is available again. See the token's doc in
     * `LauncherUiState`; driven by `ACTION_MANAGED_PROFILE_AVAILABLE`.
     */
    private fun refreshWorkProfileWidgets() {
        _uiState.update {
            it.copy(workProfileWidgetRefreshToken = it.workProfileWidgetRefreshToken + 1)
        }
        LauncherDebugLog.event(
            "refreshWorkProfileWidgets token=${_uiState.value.workProfileWidgetRefreshToken}",
        )
    }

    internal fun refreshWorkProfileWidgetsForTest() = refreshWorkProfileWidgets()

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
                destination = LauncherDestination.Agenda,
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
            val widgetPages = widgetStore.widgetPages
            val current = (it.destination as? LauncherDestination.Widgets)?.pageIndex ?: it.lastWidgetPage
            val clamped = current.coerceInWidgetPages(widgetPages)
            it.copy(
                destination = LauncherDestination.Widgets(clamped),
                lastWidgetPage = clamped,
                widgetIds = widgetStore.widgetIds,
                widgetPages = widgetPages,
                widgetHeights = widgetStore.customHeights,
                widgetProviderLabels = widgetStore.providerLabels,
                strandedWidgetIds = it.strandedWidgetIds - appWidgetId,
            )
        }
        logState("removeWidget")
    }

    /** The provider remembered for [appWidgetId], for re-binding a restore placeholder. */
    fun widgetProviderRecord(appWidgetId: Int): WidgetProviderRecord? =
        widgetStore.providerRecord(appWidgetId)

    /**
     * Swaps a restore placeholder's stranded [oldWidgetId] for the freshly-bound
     * [newWidgetId] in its existing slot (see [WidgetStore.replaceId]), then
     * refreshes the provider record from the now-live binding. The widget stops
     * being stranded and renders normally.
     */
    fun replaceWidget(oldWidgetId: Int, newWidgetId: Int) {
        LauncherDebugLog.event("replaceWidget old=$oldWidgetId new=$newWidgetId")
        widgetStore.replaceId(oldWidgetId, newWidgetId)
        rememberWidgetProvider(newWidgetId)
        _uiState.update {
            it.copy(
                widgetIds = widgetStore.widgetIds,
                widgetPages = widgetStore.widgetPages,
                widgetHeights = widgetStore.customHeights,
                widgetProviderLabels = widgetStore.providerLabels,
                strandedWidgetIds = it.strandedWidgetIds - oldWidgetId,
            )
        }
        logState("replaceWidget")
    }

    /**
     * Publishes the set of widget IDs the host hasn't allocated on this device,
     * computed by the startup reconciliation sweep. Those with a remembered
     * provider render as tappable restore placeholders. Purely informational —
     * nothing is deleted on this signal.
     */
    fun setStrandedWidgetIds(ids: Set<Int>) {
        LauncherDebugLog.event("setStrandedWidgetIds ${ids.size} ids=$ids")
        _uiState.update { it.copy(strandedWidgetIds = ids) }
    }

    fun openSettings() {
        _uiState.update { it.copy(isSettingsOpen = true, isRecentsOpen = false) }
        logState("openSettings")
    }

    fun closeSettings() {
        _uiState.update { it.copy(isSettingsOpen = false) }
        logState("closeSettings")
    }

    fun setPlayUpdateAvailable(availableVersionCode: Int?, installStatus: Int = InstallStatus.UNKNOWN) {
        val previous = _uiState.value.playUpdate as? PlayUpdateState.Available
        if (previous != null && previous.versionCode != availableVersionCode) {
            // A different version is being offered (rare — typically a server-side
            // rollout swap). Reset any in-flight progress so the new version is
            // presented from a clean Idle state.
            currentPlayUpdateProgress = UpdateProgress.Idle
        }
        val isPersistDismissed = availableVersionCode != null &&
            availableVersionCode == playUpdateStore.dismissedVersionCode
        val progress = progressForInstallStatus(installStatus, fallback = currentPlayUpdateProgress)
        currentPlayUpdateProgress = progress
        _uiState.update { state ->
            state.copy(
                playUpdate = PlayUpdateState.Available(
                    versionCode = availableVersionCode,
                    isDismissed = isPersistDismissed,
                    progress = progress,
                ),
            )
        }
        logState("setPlayUpdateAvailable=$availableVersionCode progress=$progress")
    }

    fun setPlayUpdateUnavailable() {
        currentPlayUpdateProgress = UpdateProgress.Idle
        _uiState.update { it.copy(playUpdate = PlayUpdateState.NotAvailable) }
        logState("setPlayUpdateUnavailable")
    }

    /**
     * Called when a Play update *check* fails (flaky network, Play transiently
     * unavailable) — distinct from a check that succeeds and reports no update
     * ([setPlayUpdateUnavailable]). A failed check carries no information about
     * an actual download, so it must not wipe a real in-flight or already-
     * downloaded update: doing so would hide the Restart action and drop the
     * progress the install listener drives (`setPlayUpdateProgress` early-
     * returns once the state is no longer `Available`). Those states
     * (`Downloading` / `Downloaded`) are preserved.
     *
     * `Starting` is the exception. It means the user tapped Update and the Play
     * sheet was launched (which consumed the cached `AppUpdateInfo`); a
     * cancelled sheet delivers no install-listener event, so a preserved
     * `Starting` would leave the banner stuck on an indefinite "Updating…"
     * spinner with its click disabled and dismiss hidden. Recover it to `Idle`
     * — exactly what the `UNKNOWN` success recheck does — so the user can tap
     * Update again. `Idle` and `NotAvailable` are left untouched.
     */
    fun setPlayUpdateCheckFailed() {
        val update = _uiState.value.playUpdate as? PlayUpdateState.Available
        if (update != null && update.progress == UpdateProgress.Starting) {
            currentPlayUpdateProgress = UpdateProgress.Idle
            _uiState.update { it.copy(playUpdate = update.copy(progress = UpdateProgress.Idle)) }
            logState("setPlayUpdateCheckFailed reset Starting->Idle")
        } else {
            logState("setPlayUpdateCheckFailed preserving=${_uiState.value.playUpdate}")
        }
    }

    fun dismissPlayUpdate() {
        val update = _uiState.value.playUpdate as? PlayUpdateState.Available ?: return
        playUpdateStore.dismissedVersionCode = update.versionCode ?: BuildConfig.VERSION_CODE + 1
        _uiState.update { it.copy(playUpdate = update.copy(isDismissed = true)) }
        logState("dismissPlayUpdate=${update.versionCode}")
    }

    /**
     * Drives the banner's in-flight status. Called when the user taps Update
     * (Starting). When [progress] is `Idle` the banner falls back to the
     * original "Update" CTA — used to recover from a cancelled Play sheet or
     * a download failure.
     */
    fun setPlayUpdateProgress(progress: UpdateProgress) {
        currentPlayUpdateProgress = progress
        val update = _uiState.value.playUpdate as? PlayUpdateState.Available ?: return
        if (update.progress == progress) return
        _uiState.update { it.copy(playUpdate = update.copy(progress = progress)) }
        logState("setPlayUpdateProgress=$progress")
    }

    /**
     * Bridge for `PlayUpdateChecker`'s `InstallStateUpdatedListener` — translates
     * Play's raw [InstallStatus] code into our [UpdateProgress] state machine
     * and applies it to the banner.
     */
    fun onPlayUpdateInstallStatus(installStatus: Int) {
        setPlayUpdateProgress(progressForInstallStatus(installStatus, fallback = currentPlayUpdateProgress))
    }

    private fun progressForInstallStatus(installStatus: Int, fallback: UpdateProgress): UpdateProgress =
        when (installStatus) {
            InstallStatus.PENDING -> UpdateProgress.Starting
            InstallStatus.DOWNLOADING -> UpdateProgress.Downloading
            InstallStatus.DOWNLOADED -> UpdateProgress.Downloaded
            InstallStatus.CANCELED, InstallStatus.FAILED -> UpdateProgress.Idle
            InstallStatus.UNKNOWN -> if (fallback == UpdateProgress.Starting) {
                // The Play sheet was dismissed without accepting — no listener
                // fires in that case, so onResume's recheck sees UNKNOWN and we
                // revert to Idle.
                UpdateProgress.Idle
            } else {
                fallback
            }
            else -> fallback
        }

    fun openPlayStoreListing() {
        LauncherDebugLog.event("openPlayStoreListing package=${app.packageName}")
        val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${app.packageName}"))
        try {
            startActivity(marketIntent)
        } catch (exception: ActivityNotFoundException) {
            LauncherDebugLog.warning("openPlayStoreListing market intent unavailable", exception)
            try {
                startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/apps/details?id=${app.packageName}"),
                    ),
                )
            } catch (browserException: ActivityNotFoundException) {
                // No Play Store *and* no browser (stripped-down OEM / kiosk
                // builds) — launching the web fallback unguarded crashed the
                // launcher. Nothing more to fall back to, so just log it.
                LauncherDebugLog.warning("openPlayStoreListing web fallback unavailable", browserException)
            }
        }
    }

    fun setDockEnabled(isEnabled: Boolean) {
        dockSettingsStore.isDockEnabled = isEnabled
        _uiState.update { it.copy(isDockEnabled = isEnabled) }
        refreshLists()
        logState("setDockEnabled")
    }

    fun setAppListLayout(layout: AppListLayout) {
        dockSettingsStore.appListLayout = layout
        _uiState.update { it.copy(appListLayout = layout) }
        logState("setAppListLayout")
    }

    fun setDockLayout(layout: DockLayout) {
        dockSettingsStore.dockLayout = layout
        _uiState.update { it.copy(dockLayout = layout) }
        logState("setDockLayout")
    }

    fun setAppListSortOrder(sortOrder: AppListSortOrder) {
        dockSettingsStore.appListSortOrder = sortOrder
        _uiState.update { it.copy(appListSortOrder = sortOrder) }
        refreshLists()
        logState("setAppListSortOrder")
    }

    fun setKeyboardAutoShown(isAutoShown: Boolean) {
        dockSettingsStore.isKeyboardAutoShown = isAutoShown
        _uiState.update { it.copy(isKeyboardAutoShown = isAutoShown) }
        logState("setKeyboardAutoShown=$isAutoShown")
    }

    fun setWallpaperShown(isShown: Boolean) {
        dockSettingsStore.isWallpaperShown = isShown
        _uiState.update { it.copy(isWallpaperShown = isShown) }
        logState("setWallpaperShown=$isShown")
    }

    fun setCardOpacity(opacity: Float) {
        dockSettingsStore.cardOpacity = opacity
        // Read back the clamped value the store persisted so state matches disk.
        val stored = dockSettingsStore.cardOpacity
        _uiState.update { it.copy(cardOpacity = stored) }
        logState("setCardOpacity=$stored")
    }

    fun setAgendaEnabled(isEnabled: Boolean) {
        dockSettingsStore.isAgendaEnabled = isEnabled
        _uiState.update { state ->
            val onAgenda = state.destination is LauncherDestination.Agenda
            state.copy(
                isAgendaEnabled = isEnabled,
                destination = if (!isEnabled && onAgenda) LauncherDestination.Home else state.destination,
                isRecentsOpen = state.isRecentsOpen && !onAgenda,
            )
        }
        logState("setAgendaEnabled=$isEnabled")
        if (isEnabled) {
            triggerInitialAgendaLoadIfEnabled(reason = "agendaEnabled")
        }
    }

    /**
     * Settings → "Search contacts". Only ever called with `true` after
     * READ_CONTACTS is granted (`MainActivity` gates the toggle on the
     * permission request), so enabled implies queryable. Enabling loads the
     * index; disabling drops it and empties the section immediately.
     */
    fun setContactSearchEnabled(isEnabled: Boolean) {
        dockSettingsStore.isContactSearchEnabled = isEnabled
        _uiState.update { it.copy(isContactSearchEnabled = isEnabled) }
        logState("setContactSearchEnabled=$isEnabled")
        if (isEnabled) {
            refreshContentSearchIndices(reason = "contactSearchEnabled")
        } else {
            // Invalidate any in-flight index load so it can't republish after
            // this clear — a disabled source must hold nothing in memory, not
            // just render nothing. The invalidation also discards an in-flight
            // load for the *other* source, so re-kick the refresh: it no-ops
            // when nothing is still enabled and reloads the survivor otherwise.
            contentSearchVersion++
            contactIndex = emptyList()
            // The "holds nothing in memory" contract covers decoded photo
            // thumbnails too, not just the name index.
            ContactPhotoLoader.evictAll()
            refreshFilteredApps()
            refreshContentSearchIndices(reason = "contactSearchDisabled")
        }
    }

    /**
     * Settings → "Search calendar events". Mirrors [setContactSearchEnabled]
     * for the events section (gated on READ_CALENDAR).
     */
    fun setCalendarSearchEnabled(isEnabled: Boolean) {
        dockSettingsStore.isCalendarSearchEnabled = isEnabled
        _uiState.update { it.copy(isCalendarSearchEnabled = isEnabled) }
        logState("setCalendarSearchEnabled=$isEnabled")
        if (isEnabled) {
            refreshContentSearchIndices(reason = "calendarSearchEnabled")
        } else {
            // Same in-flight invalidation + survivor re-kick as
            // setContactSearchEnabled.
            contentSearchVersion++
            searchEventIndex = emptyList()
            refreshFilteredApps()
            refreshContentSearchIndices(reason = "calendarSearchDisabled")
        }
    }

    /**
     * Turns a persisted content-search toggle back off when its runtime
     * permission is gone. Android's permission auto-reset (and a backup
     * restore onto a fresh install) can leave `contact_search_enabled` /
     * `calendar_search_enabled` true with no permission behind them — the
     * switch would render on while the source silently loads nothing, and the
     * first tap would turn it *off* instead of prompting. Coercing the flag to
     * off keeps the settings honest and makes the next tap the permission
     * request. Checked at construction and on every resume; the permission
     * check is gated on the flag so users who never enabled a source pay
     * nothing.
     */
    private fun coerceContentSearchSettingsToPermissions() {
        if (dockSettingsStore.isContactSearchEnabled && !hasContactsPermission()) {
            LauncherDebugLog.event("contact search disabled: permission not granted")
            setContactSearchEnabled(false)
        }
        if (dockSettingsStore.isCalendarSearchEnabled && !hasCalendarPermission()) {
            LauncherDebugLog.event("calendar search disabled: permission not granted")
            setCalendarSearchEnabled(false)
        }
    }

    /**
     * Reloads the in-memory contact / calendar-event indices off the main
     * thread for whichever sources are enabled. Called from home-ready (the
     * deferred cold-start load), the Settings enable path, the permission-grant
     * callback, and every resume — never from the keystroke path, which only
     * filters the already-loaded indices. A version counter deconflicts
     * overlapping loads the same way `agendaVersion` does; when a load lands
     * mid-typing, the visible results are recomputed so the sections fill in
     * under the current query.
     */
    private fun refreshContentSearchIndices(reason: String) {
        val state = _uiState.value
        val wantContacts = state.isContactSearchEnabled
        val wantEvents = state.isCalendarSearchEnabled
        if (!wantContacts && !wantEvents) return
        val requestVersion = ++contentSearchVersion
        viewModelScope.launch {
            // Each IO stage re-checks the version before touching its provider:
            // a disable that lands while this load is queued must not *query*
            // the just-opted-out source at all — the publish check below only
            // stops the result from landing, not the read itself.
            val contacts = if (wantContacts) {
                withContext(ioDispatcher) {
                    if (contentSearchVersion == requestVersion) loadContactIndex() else emptyList()
                }
            } else {
                contactIndex
            }
            val events = if (wantEvents) {
                withContext(ioDispatcher) {
                    if (contentSearchVersion == requestVersion) loadSearchEventIndex() else emptyList()
                }
            } else {
                searchEventIndex
            }
            if (contentSearchVersion != requestVersion) return@launch
            contactIndex = contacts
            searchEventIndex = events
            // Drop cached photo thumbnails whenever the contact index reloads:
            // a photo edited in the Contacts app keeps its stable
            // PHOTO_THUMBNAIL_URI, so the cache key alone can never observe the
            // change — the index reload (resume, enable, permission grant) is
            // the freshness boundary, same as for display names. Cheap in
            // practice: photos only decode for rendered search rows, so the
            // repopulation cost is a handful of thumbnail reads after the next
            // keystroke, off the main thread.
            if (wantContacts) ContactPhotoLoader.evictAll()
            LauncherDebugLog.event(
                "$reason content search indices loaded contacts=${contacts.size} events=${events.size}",
            )
            if (_uiState.value.query.isNotBlank()) refreshFilteredApps()
        }
    }

    /**
     * The in-memory index sizes, exposed for tests only: the opt-out contract
     * ("a disabled source holds nothing in memory") is otherwise invisible
     * from [uiState] — a stale index renders nothing while its setting is off,
     * so only the retention itself can be asserted.
     */
    @VisibleForTesting
    internal fun contentSearchIndexSizesForTest(): Pair<Int, Int> =
        contactIndex.size to searchEventIndex.size

    fun setThemeMode(mode: ThemeMode) {
        dockSettingsStore.themeMode = mode
        // Re-resolve the themed-icon palette: with the Monochrome icon theme,
        // plates must flip with the launcher's effective dark/light appearance
        // (the loader evicts its cache when the resolved colors change, so
        // on-screen icons re-rasterize with the new palette).
        AppIconLoader.setThemedIconPalette(app, isLauncherThemeDark(mode))
        _uiState.update { it.copy(themeMode = mode) }
        logState("setThemeMode=$mode")
    }

    /**
     * Resolves the launcher's effective dark/light appearance for [mode]:
     * `System` follows the device night mode, `Light` / `Dark` pin it
     * regardless. The same resolution `MainActivity.applyEdgeToEdgeForThemeMode`
     * and `TypeLauncherTheme` apply.
     */
    private fun isLauncherThemeDark(mode: ThemeMode): Boolean {
        val systemDark = (app.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        return when (mode) {
            ThemeMode.System -> systemDark
            ThemeMode.Light -> false
            ThemeMode.Dark -> true
        }
    }

    fun setIconShape(shape: IconShape) {
        dockSettingsStore.iconShape = shape
        _uiState.update { it.copy(iconShape = shape) }
        logState("setIconShape=$shape")
    }

    fun setIconTheme(theme: IconTheme) {
        dockSettingsStore.iconTheme = theme
        AppIconLoader.iconTheme = theme
        // Every cached tile was rasterized under the previous theme and the
        // cache key carries no rendering-mode component, so drop them all —
        // the next render of each icon re-rasterizes with (or without) the
        // themed plate + glyph. The next `persistIconSnapshot` then snapshots
        // the re-rendered cache, so the on-disk snapshot follows along.
        AppIconLoader.evictAll()
        _uiState.update { it.copy(iconTheme = theme) }
        logState("setIconTheme=$theme")
    }

    // The Settings slider still picks a per-row *count*; store the icon *size*
    // (dp) that fills that many icons at the current width, so the rendered count
    // round-trips and the stored size then adapts to other widths / Display
    // sizes on its own.
    fun setDockVisibleIconCount(count: Int) {
        val configuration = app.resources.configuration
        val shortEdgeDp = minOf(configuration.screenWidthDp, configuration.screenHeightDp)
        val clampedCount = count.coerceIn(dockSlotCountRange(shortEdgeDp))
        val targetIconSizeDp = dockIconSizeForSlotCount(shortEdgeDp, clampedCount)
        dockSettingsStore.dockIconSizeDp = targetIconSizeDp
        _uiState.update { it.copy(dockIconSizeDp = targetIconSizeDp) }
        logState("setDockVisibleIconCount requested=$count")
    }

    /**
     * Toggles the "Show work dock" preference. When flipped on for the first
     * time and a work profile is active, runs the work-dock prefill against
     * the current installed-app list so the row paints with seeded icons on
     * the very next composition rather than appearing empty. The prefill
     * latches `hasBeenPrefilled` so a subsequent off → on toggle does not
     * re-seed.
     */
    fun setWorkDockEnabled(isEnabled: Boolean) {
        dockSettingsStore.isWorkDockEnabled = isEnabled
        // Update state *before* prefilling so `maybePrefillWorkDock`'s guard
        // on `_uiState.value.isWorkDockEnabled` sees the new value. The
        // previous order let the guard fire on the stale `false`, so toggling
        // the switch on never seeded the store.
        _uiState.update { it.copy(isWorkDockEnabled = isEnabled) }
        if (isEnabled) {
            maybePrefillWorkDock(installedApps)
        }
        refreshLists()
        logState("setWorkDockEnabled=$isEnabled")
    }

    private fun refreshLists() {
        val query = _uiState.value.query.trim()
        _uiState.update { state ->
            val dockedIds = dockedAppIdsForState(state)
            val workDockedIds = workDockedAppIdsForState(state)
            val visibleApps = visibleInstalledApps()
            // Compute the new recents list first so the exclusion sees the
            // post-filterRecent display list rather than `state.recentApps`,
            // which is a snapshot from before this update and may not include
            // a launch that just landed via `recordLaunch` → `refreshLists`.
            val newRecentApps = visibleApps.filterRecent(appLaunchStatsStore.recentAppIds).markVisibility()
            state.copy(
                filteredApps = visibleApps.filterByName(
                    query = query,
                    appLaunchStatsStore = appLaunchStatsStore,
                    excludedAppIds = excludedFromAppList(state, dockedIds),
                    dockedAppIds = floatingDockedIdsForState(state, dockedIds, workDockedIds),
                    sortOrder = effectiveAppListSortOrder(state.appListSortOrder, state.homeLandscapeTier),
                ).markVisibility(),
                dockedApps = visibleApps.filterDocked(dockedIds).markVisibility(),
                dockPositions = dockedAppStore.dockedAppPositions,
                dockFolders = resolvedDockFolders(visibleApps, dockedAppStore),
                shouldShowDockAddHint = dockedAppStore.shouldShowAddButtonHint,
                workDockedApps = visibleApps.filterDocked(workDockedIds).markVisibility(),
                workDockPositions = workDockedAppStore.dockedAppPositions,
                workDockFolders = resolvedDockFolders(visibleApps, workDockedAppStore),
                shouldShowWorkDockAddHint = workDockedAppStore.shouldShowAddButtonHint,
                isWorkProfileConfigured = installedApps.any { it.isWorkApp },
                isWorkProfileActive = installedApps.any { it.isWorkApp && !it.isQuietMode },
                recentApps = newRecentApps,
                hiddenApps = installedApps.filterHidden(hiddenAppStore.hiddenAppIds).markVisibility(),
                contactResults = contactResultsFor(state, query),
                eventResults = eventResultsFor(state, query),
            )
        }
    }

    private fun refreshFilteredApps() {
        val query = _uiState.value.query.trim()
        _uiState.update { state ->
            val dockedIds = dockedAppIdsForState(state)
            val workDockedIds = workDockedAppIdsForState(state)
            state.copy(
                filteredApps = visibleInstalledApps().filterByName(
                    query = query,
                    appLaunchStatsStore = appLaunchStatsStore,
                    excludedAppIds = excludedFromAppList(state, dockedIds),
                    dockedAppIds = floatingDockedIdsForState(state, dockedIds, workDockedIds),
                    sortOrder = effectiveAppListSortOrder(state.appListSortOrder, state.homeLandscapeTier),
                ).markVisibility(),
                contactResults = contactResultsFor(state, query),
                eventResults = eventResultsFor(state, query),
            )
        }
    }

    // The typed-search content sections, recomputed wherever `filteredApps` is
    // (the keystroke fast-path and the full refresh) so the sections always
    // describe the same query as the app results beside them. Blank queries and
    // disabled sources return the `emptyList()` singleton, so the empty-query
    // copy keeps reference equality and recomposes nothing.
    private fun contactResultsFor(state: LauncherUiState, query: String): List<ContactResult> =
        if (state.isContactSearchEnabled) contactIndex.filterContactResults(query) else emptyList()

    private fun eventResultsFor(state: LauncherUiState, query: String): List<AgendaEvent> {
        if (!state.isCalendarSearchEnabled) return emptyList()
        // The time column shows now-relative labels ("Today", "Fri", an
        // in-progress range), so it is formatted here against the current
        // clock rather than baked into the index at load — otherwise a row
        // would keep the label it had when the index loaded, e.g. still show
        // "Today 9:00 AM" after a foreground-open launcher crosses the event's
        // start time instead of flipping to the in-progress range. Only the
        // matched rows (a handful) are formatted, and `now` is read once, so
        // the keystroke path stays cheap.
        val now = System.currentTimeMillis()
        return searchEventIndex.filterEventResults(query)
            .map { event -> event.copy(displayTime = event.formatSearchTime(app, now)) }
    }

    private fun dockedAppIdsForState(state: LauncherUiState): List<String> =
        dockedAppStore.dockedAppIdsFor(
            sortOrder = effectiveAppListSortOrder(state.appListSortOrder, state.homeLandscapeTier),
            columnCount = deviceRenderableDockIconCount(state.dockIconSizeDp),
        )

    private fun workDockedAppIdsForState(state: LauncherUiState): List<String> =
        workDockedAppStore.dockedAppIdsFor(
            sortOrder = effectiveAppListSortOrder(state.appListSortOrder, state.homeLandscapeTier),
            columnCount = deviceRenderableDockIconCount(state.dockIconSizeDp),
        )

    /**
     * The docked IDs that should float to the top of the main app list (the
     * "docked first" rule that makes pinned apps rank as if they had the
     * highest usage). Personal docked apps always float — they back the
     * empty-query rule and the typed-search surfacing, and (per SPEC) keep
     * floating even when the personal dock is disabled. Work docked apps only
     * float while their dock row is hidden and the list is the surface showing
     * them — i.e. during a typed search (when both docks disappear) or in the
     * cramped-landscape Compact state (when both docks are dropped) — and only
     * when the work dock is enabled with an active, non-quiet work profile.
     * Without that gate, stale pins left in the work store after the user turned
     * the work dock off would wrongly outrank real matches and could even become
     * the Enter/search launch target. When the work dock is on screen (a blank
     * query in the Full state) its apps live there, not floated in the list.
     * Personal IDs lead so the personal dock's muscle-memory order wins ties.
     */
    private fun floatingDockedIdsForState(
        state: LauncherUiState,
        personalDockedIds: List<String>,
        workDockedIds: List<String>,
    ): List<String> {
        // Folder members are docked too (inside a folder), so they float in the
        // list like loose docked apps when the dock is hidden. Expand folder
        // occupants to their members *in place* so a foldered app floats at the
        // folder's own dock-rank position, not after every top-level occupant.
        val personalFloating = dockedAppStore.expandFolderOccupants(personalDockedIds)
        val workFloating = workDockedAppStore.expandFolderOccupants(workDockedIds)
        // The work dock row is hidden — so its apps belong in the list and float
        // like personal pins — whenever a query is active, the Compact state has
        // dropped the docks, or the keyboard has suppressed the DockNoKeyboard
        // dock (matching `excludedFromAppList`, which surfaces those pins in the
        // list there). A blank query with the dock on screen keeps the work dock
        // visible, so only personal pins float then.
        val workDockHidden = state.query.isNotBlank() ||
            state.homeLandscapeTier == HomeLandscapeTier.Compact ||
            state.dockSuppressedByKeyboard
        if (!workDockHidden) return personalFloating
        // Re-derive the active-profile flag from `installedApps` for the same
        // reason `excludedFromAppList` does: the `state` snapshot visible here
        // can lag the package reload by a frame.
        val isWorkProfileActive = installedApps.any { it.isWorkApp && !it.isQuietMode }
        return if (state.isWorkDockEnabled && isWorkProfileActive) {
            // `distinct()` keeps the first (personal) occurrence of an app that
            // is pinned to both docks, so it ranks by its personal-dock
            // coordinates. Without it, `filterByName`'s `withIndex().associate`
            // would let the later work-dock index overwrite the personal one
            // and demote the app below its intended personal-order position.
            (personalFloating + workFloating).distinct()
        } else {
            personalFloating
        }
    }

    /**
     * The per-row dock count this device actually renders for [targetIconSizeDp]
     * (defaulting to the persisted `dockIconSizeDp`): how many target-sized icons
     * fit the current window's short edge (see [dockIconSizing]). Every
     * dock-store interaction that depends on the column grid (first-run prefill,
     * `dock`/`move` position writes, and the position→rank reads that order
     * docked apps in the list) sizes itself from this, so the persisted grid
     * always matches the grid the user sees — including when the system Display
     * size shrinks the dp-width and renders fewer, bigger icons.
     *
     * Uses the *current window's* short edge — `minOf(screenWidthDp,
     * screenHeightDp)`, the exact expression HomeScreen's `dockReferenceWidthDp`
     * uses — rather than `smallestScreenWidthDp`, which still reports the
     * device's smallest width in a narrower window (split-screen, free-form, a
     * foldable cover) where HomeScreen renders against the narrower window.
     */
    private fun deviceRenderableDockIconCount(targetIconSizeDp: Int = _uiState.value.dockIconSizeDp): Int {
        val configuration = app.resources.configuration
        val shortEdgeDp = minOf(configuration.screenWidthDp, configuration.screenHeightDp)
        return dockIconSizing(shortEdgeDp, targetIconSizeDp).slotCount
    }

    /**
     * Runs the one-time work-dock prefill the first time the user enables the
     * work dock on a device that has a managed profile. Latched independently
     * of the personal-dock prefill flag so toggling "Show work dock" off and
     * back on does not re-prefill — and so a user who never enables the work
     * dock never has anything written to the work store. Skips when the
     * profile is currently paused (quiet mode), so the seed sees the apps the
     * user actually has access to; the prefill retries on the next fresh load
     * after the profile is resumed.
     */
    private fun maybePrefillWorkDock(loadedApps: List<InstalledApp>) {
        if (!_uiState.value.isWorkDockEnabled) return
        if (workDockedAppStore.hasBeenPrefilled) return
        if (workDockedAppStore.dockedAppIds.isNotEmpty()) {
            workDockedAppStore.markPrefilled()
            return
        }
        if (loadedApps.none { it.isWorkApp && !it.isQuietMode }) return
        // Clamp to the device's renderable column count for the same reason as
        // the personal dock: on a narrow phone the stored count can exceed what
        // HomeScreen renders, so the popular-fallback tier must reserve a real
        // free cell for the "+" hint rather than fill the clamped row.
        val deviceDockIconCount = deviceRenderableDockIconCount()
        prefillWorkDock(
            installedApps = loadedApps,
            // Include personal-folder members: a work app pinned to the personal
            // dock and then grouped into a folder is still "on the personal
            // dock", so tier (a) should copy it into the work dock on first
            // enable (its loose id is gone — only the folder occupant remains).
            personalDockedIds = dockedAppStore.dockedOrFolderedAppIds,
            appLaunchStatsStore = appLaunchStatsStore,
            workDockStore = workDockedAppStore,
            maxSlots = deviceDockIconCount,
        )
        // Same ordering rule as the personal dock: set the hint flag *after*
        // `prefillWorkDock` returns so its internal `dock()` seeds don't
        // self-clear it. Typically false when tiers (a)/(b) contributed (they
        // target the full row), true when only tier (c)'s popular fallback
        // ran and reserved a slot.
        workDockedAppStore.setShowAddButtonHint(
            workDockedAppStore.dockedAppIds.size < deviceDockIconCount,
        )
        workDockedAppStore.markPrefilled()
    }

    /**
     * Returns the IDs of apps that should be omitted from the main app list
     * because they already render on another always-visible surface. Docked
     * apps are hidden from the list only while the dock row is actually on
     * screen (the dock row already shows them) — that is, with an empty query.
     * A non-blank query hides the dock row, so docked apps always surface in
     * the typed-search list (where they float to the top of their tier).
     * Secondary bars such as recents do not dedupe from the app list.
     */
    private fun excludedFromAppList(
        state: LauncherUiState,
        dockedIds: List<String>,
    ): Set<String> {
        val excluded = mutableSetOf<String>()
        // Docked apps are only deduped out of the main list while their dock
        // row is actually on screen. Two things hide the dock: typing a query
        // (which hides both docks — see `isDockSlotPresent` in HomeScreen) and
        // the cramped-landscape Compact state (which drops the docks entirely).
        // In either case the exclusion must stop, or the docked apps would
        // vanish from every surface and become unreachable — the Compact app
        // list is the *only* surface, so it must contain all launchable apps.
        // The dock is on screen with a blank query in any non-Compact state —
        // Full (which includes all of portrait) and the keyboard-down
        // DockNoKeyboard landscape state both render it — so the dedupe must
        // match `isDockSlotPresent` and resume there too. The exception is
        // DockNoKeyboard with the keyboard raised: that hides the dock to make
        // room, so its apps must reappear in the list (the only surface left)
        // until the keyboard is dismissed.
        val isDockUiVisible = state.query.isBlank() &&
            state.homeLandscapeTier != HomeLandscapeTier.Compact &&
            !state.dockSuppressedByKeyboard
        if (isDockUiVisible && state.isDockEnabled) {
            // Loose docked apps ∪ folder members (folder occupant ids excluded)
            // — a folder member dedupes out of the list under the same rule as a
            // loose docked app.
            excluded.addAll(dockedAppStore.dockedOrFolderedAppIds)
        }
        // Re-derive `isWorkProfileActive` from the authoritative
        // `installedApps` instead of reading `state.isWorkProfileActive`.
        // The `_uiState.update { state -> ... }` lambdas compute the new
        // value in the same `state.copy(...)` call that also produces the
        // filtered list, so the `state` parameter visible to this function
        // is the *old* snapshot and would skip the exclusion on the very
        // frame the work dock first becomes visible (leaving a duplicate
        // work-docked entry in the main list until the next refresh).
        // `state.isDockEnabled` is a persistent setting unaffected by package
        // reloads, so it stays correct from `state`.
        val isWorkProfileActive = installedApps.any { it.isWorkApp && !it.isQuietMode }
        if (
            isDockUiVisible &&
            state.isWorkDockEnabled &&
            isWorkProfileActive
        ) {
            excluded.addAll(workDockedAppStore.dockedOrFolderedAppIds)
        }
        return excluded
    }

    private fun visibleInstalledApps(): List<InstalledApp> {
        // Drop every work-profile app whose profile is currently in quiet
        // mode so paused work icons disappear from every derived surface
        // (search, dock, recents) without per-surface plumbing. The full `installedApps` list is left intact so the
        // entries reappear automatically when the work profile is resumed
        // (the broadcast receiver triggers a reload that flips `isQuietMode`
        // back to false). Personal-profile apps are never
        // `isQuietMode = true` (the `loadInstalledApps` per-profile lookup
        // excludes the personal user), so the predicate only drops work
        // entries.
        return installedApps.filterNot { app ->
            hiddenAppStore.contains(app.id) || (app.isWorkApp && app.isQuietMode)
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

    private fun hasContactsPermission(): Boolean =
        ContextCompat.checkSelfPermission(app, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /**
     * Loads the in-memory contact index for the typed-search contacts section:
     * every visible aggregated contact (the same set the Contacts app lists —
     * invisible auto-collected addresses are excluded), pre-sorted starred
     * contacts first (mirroring how the app list floats docked apps to the top
     * of their match tier) and alphabetically within each starred/non-starred
     * group via the locale-aware display-name collator, so the per-keystroke
     * filter's stable sort inherits that order within each match tier. Runs on
     * [ioDispatcher] only; a missing permission or a provider exception yields
     * an empty index rather than an error surface.
     */
    private fun loadContactIndex(): List<ContactResult> {
        if (!hasContactsPermission()) return emptyList()
        val contacts = mutableListOf<ContactResult>()
        val projection = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.LOOKUP_KEY,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
            ContactsContract.Contacts.PHOTO_THUMBNAIL_URI,
            ContactsContract.Contacts.STARRED,
        )
        try {
            app.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                projection,
                "${ContactsContract.Contacts.IN_VISIBLE_GROUP} = 1",
                null,
                null,
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
                val lookupIndex = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.LOOKUP_KEY)
                val nameIndex = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
                val photoIndex = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.PHOTO_THUMBNAIL_URI)
                val starredIndex = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.STARRED)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIndex)?.takeIf { it.isNotBlank() } ?: continue
                    val lookupKey = cursor.getString(lookupIndex) ?: continue
                    contacts += ContactResult(
                        contactId = cursor.getLong(idIndex),
                        lookupKey = lookupKey,
                        displayName = name,
                        photoThumbnailUri = cursor.getString(photoIndex)?.takeIf { it.isNotBlank() },
                        starred = cursor.getInt(starredIndex) != 0,
                    )
                }
            }
        } catch (exception: SecurityException) {
            LauncherDebugLog.warning("loadContactIndex security exception", exception)
            return emptyList()
        } catch (exception: RuntimeException) {
            // A provider-side failure (disabled/corrupt OEM contacts provider,
            // transient database error) surfaces here as a RuntimeException.
            // An optional search source must degrade to no results, never
            // crash the launcher's index-load coroutine.
            LauncherDebugLog.warning("loadContactIndex provider failure", exception)
            return emptyList()
        }
        // Sort here, once per load, rather than per keystroke: the filter's
        // stable tier sort keeps this order as the within-tier tie-break.
        // Starred (descending, so true sorts first) is the primary key so a
        // favorited contact always ranks above a non-favorited one within the
        // same match tier; alphabetical is the tie-break within each group.
        val order = displayNameOrder()
        return contacts.sortedWith(
            compareByDescending<ContactResult> { contact -> contact.starred }
                .thenBy(order) { contact -> contact.displayName },
        )
    }

    /**
     * Loads the in-memory event index for the typed-search calendar section —
     * the same `CalendarContract.Instances` query the agenda uses, over a
     * wider [SEARCH_EVENT_LOOKAHEAD_DAYS] window so search reaches further
     * ahead than the 7-day agenda page. Runs on [ioDispatcher] only.
     */
    private fun loadSearchEventIndex(): List<AgendaEvent> {
        if (!hasCalendarPermission()) return emptyList()
        return try {
            loadAgendaEvents(
                lookaheadDays = SEARCH_EVENT_LOOKAHEAD_DAYS,
                // forSearch, not forNow: the agenda's intersects-today all-day
                // rule would drop a next-week vacation/birthday from the index.
                organize = AgendaEventOrganizer::forSearch,
                // The now-relative time label is formatted per query in
                // `eventResultsFor`, not baked here, so it can't go stale
                // between index loads; the index only needs the raw event.
                formatDisplayTime = { _, _ -> "" },
            )
        } catch (exception: RuntimeException) {
            // Same provider-failure containment as loadContactIndex: the shared
            // calendar query only guards SecurityException itself (the agenda
            // page's long-standing behavior), but the search index also loads
            // on every resume, so a flaky provider must degrade to an empty
            // section rather than crash the launcher.
            LauncherDebugLog.warning("loadSearchEventIndex provider failure", exception)
            emptyList()
        }
    }

    private fun loadAgendaEvents(
        nowMillis: Long = System.currentTimeMillis(),
        lookaheadDays: Long = AGENDA_LOOKAHEAD_DAYS,
        organize: (List<AgendaEvent>, Long, Long, Long) -> List<AgendaEvent> = AgendaEventOrganizer::forNow,
        // The agenda groups events under day headers, so its rows show time
        // only; search results are a flat, headerless list, so they need the
        // day/date baked into the time column (see [formatSearchTime]).
        formatDisplayTime: (AgendaEvent, Long) -> String = { event, _ -> event.formatTime(app) },
    ): List<AgendaEvent> {
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val startOfDay = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val utcTodayStart = today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val utcTomorrowStart = today.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val queryEnd = today.plusDays(lookaheadDays).atStartOfDay(zone).toInstant().toEpochMilli()
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
                while (cursor.moveToNext()) {
                    events += AgendaEvent(
                        title = cursor.getString(titleIndex)?.takeIf { it.isNotBlank() }
                            ?: app.getString(R.string.agenda_event_untitled),
                        beginMillis = cursor.getLong(beginIndex),
                        endMillis = cursor.getLong(endIndex),
                        isAllDay = cursor.getInt(allDayIndex) == 1,
                        displayTime = "",
                        eventId = cursor.getLong(eventIdIndex),
                    )
                }
            }
        } catch (exception: SecurityException) {
            LauncherDebugLog.warning("loadAgendaEvents security exception", exception)
            return emptyList()
        }

        return organize(events, nowMillis, utcTodayStart, utcTomorrowStart)
            .map { event -> event.copy(displayTime = formatDisplayTime(event, nowMillis)) }
    }

    /**
     * Folds the current local date into the `iconCacheToken` of every
     * [DYNAMIC_CALENDAR_PACKAGES] app so its [InstalledApp.iconCacheId] — and
     * therefore its `AppIconLoader` cache key and `IconSnapshotStore` file — is
     * distinct per calendar day, forcing a fresh resolve when the day rolls over.
     * Applied both to the freshly-loaded list and to the cold-start metadata
     * prefill, so the prefill always reflects today regardless of which day the
     * persisted token was stamped on: a same-day cold start still hits the
     * snapshot, a cross-day cold start misses it and reloads today's dated icon
     * rather than painting yesterday's. Apps with a user-supplied custom icon are
     * left alone — that override is static, so there's nothing to refresh daily.
     */
    private fun List<InstalledApp>.applyDynamicCalendarToken(
        today: String = currentLocalDateKey(),
    ): List<InstalledApp> = map { app ->
        if (app.customIconPath != null) return@map app
        val stamped = dynamicCalendarIconToken(app.packageName, app.iconCacheToken, today)
        if (app.iconCacheToken == stamped) app else app.copy(iconCacheToken = stamped)
    }

    private fun currentLocalDateKey(): String = LocalDate.now(ZoneId.systemDefault()).toString()

    // Wraps a raw app label in the locale's work prefix when [isWork] is true
    // (e.g. en-US "Calendar" → "Work Calendar"). Returns the label unchanged
    // for personal apps. See [applyWorkPrefix] for the strip-then-format
    // policy (collapsing any pre-existing leading "Work " tokens before
    // re-prepending exactly one).
    private fun workLabel(rawLabel: String, isWork: Boolean): String {
        if (!isWork) return rawLabel
        return applyWorkPrefix(
            rawLabel = rawLabel,
            prefixWord = app.getString(R.string.app_label_with_work_prefix, "").trimEnd(),
        ) { stripped -> app.getString(R.string.app_label_with_work_prefix, stripped) }
    }

    // The un-prefixed noun a work-profile app searches by — the same stripped
    // label [workLabel] embeds in the "Work <noun>" template, so an app whose
    // own label already starts with the locale's work token (e.g. "Work Email")
    // still searches by its noun ("Email") rather than the still-prefixed raw
    // name. Returns the label unchanged for personal apps; consumed by
    // [InstalledApp.workPrefixStrippedSearchName].
    private fun workSearchName(rawLabel: String, isWork: Boolean): String {
        if (!isWork) return rawLabel
        return stripWorkPrefix(
            rawLabel = rawLabel,
            prefixWord = app.getString(R.string.app_label_with_work_prefix, "").trimEnd(),
        )
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
        // permission for any profile in the calling user's profile group —
        // but a profile being removed can leave the group between the
        // `profiles` snapshot and this call, so tolerate the rejection.
        val quietByUser: Map<UserHandle, Boolean> = profiles.associateWith { user ->
            user != personalUser && try {
                userManager?.isQuietModeEnabled(user) == true
            } catch (_: SecurityException) {
                false
            }
        }
        val profileApps = profiles
            .flatMap { user ->
                val activities = try {
                    launcherApps
                        ?.getActivityList(null, user)
                        .orEmpty()
                } catch (exception: SecurityException) {
                    // The profile left the caller's profile group after the
                    // `profiles` snapshot — the window during work-profile
                    // removal where teardown's package events schedule
                    // reloads while access is already revoked. Same race
                    // `resolveProfileApplicationInfo` and AppIconLoader
                    // tolerate; skip the dying profile instead of letting
                    // the exception crash the launcher out of the load
                    // coroutine. The removal's own callback triggers a
                    // fresh reload that no longer lists this profile.
                    LauncherDebugLog.warning(
                        "loadInstalledApps profile rejected user=${user.hashCode()}",
                        exception,
                    )
                    emptyList()
                }
                LauncherDebugLog.event(
                    "loadInstalledApps profile=${user.hashCode()} activities=${activities.size} " +
                        "quiet=${quietByUser[user] == true}",
                )
                activities
                    .map { activity ->
                        val rawLabel = activity.label.toString()
                        val workApp = user != personalUser ||
                            activity.applicationInfo.packageName in workPackages
                        InstalledApp(
                            name = rawLabel,
                            packageName = activity.applicationInfo.packageName,
                            launchIntent = Intent.makeMainActivity(activity.componentName),
                            user = user,
                            isWorkApp = workApp,
                            launchWithLauncherApps = true,
                            iconCacheToken = activity.applicationInfo.iconCacheToken(app.packageManager),
                            isQuietMode = quietByUser[user] == true,
                            displayBase = workLabel(rawLabel, workApp),
                            unprefixedName = workSearchName(rawLabel, workApp),
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
                        val rawLabel = resolveInfo.loadLabel(app.packageManager).toString()
                        val workApp = activityInfo.packageName in workPackages
                        InstalledApp(
                            name = rawLabel,
                            packageName = activityInfo.packageName,
                            launchIntent = Intent.makeMainActivity(
                                ComponentName(activityInfo.packageName, activityInfo.name),
                            ),
                            user = personalUser,
                            isWorkApp = workApp,
                            launchWithLauncherApps = false,
                            iconCacheToken = activityInfo.applicationInfo.iconCacheToken(app.packageManager),
                            displayBase = workLabel(rawLabel, workApp),
                            unprefixedName = workSearchName(rawLabel, workApp),
                        )
                    }
            }
            // Keying dedup on `id` (userHandle.hashCode():componentName) lets distinct
            // apps that happen to share a display name survive — e.g. Chase US
            // (com.chase.sig.android) and Chase UK (com.chase.uk.*) both show up,
            // and the disambiguator pass below tags them with regional badges.
            .distinctBy { launcherApp -> launcherApp.id }
            .sortedWith(compareBy(displayNameOrder()) { launcherApp -> launcherApp.name })
        return collected
            .applyDisambiguators()
            .applyRenameOverrides()
            .applyCustomBadges()
            .applyIconOverrides()
            .applyDynamicCalendarToken()
            .also { apps -> LauncherDebugLog.event("loadInstalledApps complete apps=${apps.size}") }
    }

    /**
     * Mirror persisted [CustomBadgeStore] entries onto each [InstalledApp]
     * in the freshly-loaded list so the badge override is visible on the
     * very first frame after a cold start. Same rationale as
     * [applyRenameOverrides]: `markVisibility` later re-applies the same
     * value, but it runs after `filterByName`, so without this pass the
     * first paint would briefly show the auto-disambiguator badge before
     * the user-chosen one takes over.
     */
    private fun List<InstalledApp>.applyCustomBadges(): List<InstalledApp> =
        map { app ->
            val customBadge = customBadgeStore.customBadgeFor(app.id)
            if (app.customBadge == customBadge) app else app.copy(customBadge = customBadge)
        }

    /**
     * Mirror persisted [RenamedAppStore] entries onto each [InstalledApp] in
     * the freshly-loaded list so `filterByName` (which sorts and matches
     * against `displayName`, which honours `customName` when set) sees the
     * override on the very first call after a cold start or package-event
     * reload. Without this pass, the master `installedApps` list comes back
     * with `customName = null` for every app and the override only attaches
     * later via `markVisibility` — which runs *after* `filterByName`, so
     * typing a previously-saved override label returns no matches until
     * the in-process `renameApp` patch (which mirrors customName onto
     * `installedApps` directly) is invoked.
     */
    private fun List<InstalledApp>.applyRenameOverrides(): List<InstalledApp> =
        map { app ->
            val customName = renamedAppStore.customNameFor(app.id)
            if (app.customName == customName) app else app.copy(customName = customName)
        }

    /**
     * Mirror persisted [IconOverrideStore] entries onto each [InstalledApp]
     * in the freshly-loaded list so `AppIconLoader.cached`/`load` (which key
     * by `iconCacheId`, which folds in the override version when one is set)
     * see the override on the very first frame after a cold start or
     * package-event reload. Without this pass, `iconCacheId` would resolve to
     * the system-icon variant and the renderer would briefly show the
     * pre-override icon.
     *
     * Skipped entirely when no overrides are persisted so the empty-overrides
     * case stays a single map call without touching the file system.
     */
    private fun List<InstalledApp>.applyIconOverrides(): List<InstalledApp> {
        val overriddenIds = iconOverrideStore.overriddenAppIds()
        if (overriddenIds.isEmpty()) return this
        return map { app ->
            if (app.id !in overriddenIds) {
                if (app.customIconPath == null) app else app.copy(customIconPath = null, customIconVersion = 0L)
            } else {
                val file = iconOverrideStore.iconFileFor(app.id)
                if (file == null) {
                    if (app.customIconPath == null) app else app.copy(customIconPath = null, customIconVersion = 0L)
                } else {
                    val path = file.absolutePath
                    val version = iconOverrideStore.iconVersionFor(app.id)
                    if (app.customIconPath == path && app.customIconVersion == version) {
                        app
                    } else {
                        app.copy(customIconPath = path, customIconVersion = version)
                    }
                }
            }
        }
    }

    private fun List<InstalledApp>.applyDisambiguators(): List<InstalledApp> {
        val labels = computeDisambiguators(this)
        if (labels.isEmpty()) return this
        return map { app -> labels[app.id]?.let { app.copy(disambiguator = it) } ?: app }
    }

    private fun loadAvailableWidgets(): List<WidgetProvider> {
        val widgetManager = AppWidgetManager.getInstance(app)
        val personalUser = Process.myUserHandle()
        val userManager = app.getSystemService<UserManager>()
        // Enumerate every available profile (personal + any work profiles) so
        // the picker surfaces work-profile widgets alongside personal ones.
        // `getInstalledProvidersForProfile` is the cross-profile counterpart to
        // `installedProviders`; it dispatches to the same AppWidgetService and
        // is restricted by the platform to profiles the launcher already has
        // access to. Falling back to `Process.myUserHandle()` keeps the picker
        // populated when LauncherApps is unavailable (test / non-launcher
        // contexts).
        val allProfiles = launcherAppsService?.profiles?.takeIf { it.isNotEmpty() }
            ?: listOf(personalUser)
        // Mirror `loadInstalledApps`: drop work-profile providers whose
        // profile is currently in quiet mode so the picker hides them
        // alongside the launcher icons. Also mirror its SecurityException
        // guard — a profile being removed can leave the caller's profile
        // group between the `profiles` snapshot and this call, and the
        // rejection would otherwise escape `showWidgetPicker`'s launch and
        // crash the launcher. Drop the dying profile like a quiet one; the
        // removal's own broadcast triggers a reload without it.
        val profiles = allProfiles.filterNot { profile ->
            profile != personalUser && try {
                userManager?.isQuietModeEnabled(profile) == true
            } catch (exception: SecurityException) {
                LauncherDebugLog.warning(
                    "loadAvailableWidgets profile rejected user=${profile.hashCode()}",
                    exception,
                )
                true
            }
        }
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
                    .map { info -> info.toWidgetProvider(app, personalUser, ::resolveProfileApplicationInfo) }
            }
            .distinctBy { provider -> provider.id }
            .sortedWith(
                displayNameOrder().let { displayNameOrder ->
                    compareBy<WidgetProvider, String>(displayNameOrder) { provider -> provider.appName }
                        .thenBy(displayNameOrder) { provider -> provider.label }
                },
            )
            .also { providers -> LauncherDebugLog.event("loadAvailableWidgets providers=${providers.size}") }
    }

    // Cross-profile ApplicationInfo lookup for widget-picker sections whose
    // package the personal-user PackageManager can't see (work-only apps).
    // SecurityException covers the profile leaving the caller's profile group
    // mid-load (same race AppIconLoader tolerates for icons).
    private fun resolveProfileApplicationInfo(packageName: String, profile: UserHandle): ApplicationInfo? =
        try {
            launcherAppsService?.getApplicationInfo(packageName, 0, profile)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        } catch (_: SecurityException) {
            null
        }

    private fun showWidgetPicker(availableWidgets: List<WidgetProvider>) {
        val pageIndex = _uiState.value.lastWidgetPage
        pendingWidgetPlacement = PendingWidgetPlacement(
            pageIndex = pageIndex,
            addToNewPageAfterSelection = false,
        )
        _uiState.update {
            it.copy(
                destination = LauncherDestination.Widgets(pageIndex),
                lastWidgetPage = pageIndex,
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
            val isWorkDocked = workDockedAppStore.contains(app.id)
            val isInFolder = dockedAppStore.isFolderMember(app.id) ||
                workDockedAppStore.isFolderMember(app.id)
            val isHidden = hiddenAppStore.contains(app.id)
            val customName = renamedAppStore.customNameFor(app.id)
            val customBadge = customBadgeStore.customBadgeFor(app.id)
            val overrideFile = iconOverrideStore.iconFileFor(app.id)
            val customIconPath = overrideFile?.absolutePath
            // Read the cached version rather than `overrideFile.lastModified()`:
            // this runs per overridden app on every keystroke refresh, and a
            // disk stat here re-hits the file system on the main thread each time.
            val customIconVersion = if (overrideFile == null) 0L else iconOverrideStore.iconVersionFor(app.id)
            if (app.isDocked == isDocked &&
                app.isWorkDocked == isWorkDocked &&
                app.isInFolder == isInFolder &&
                app.isHidden == isHidden &&
                app.customName == customName &&
                app.customBadge == customBadge &&
                app.customIconPath == customIconPath &&
                app.customIconVersion == customIconVersion
            ) {
                app
            } else {
                app.copy(
                    isDocked = isDocked,
                    isWorkDocked = isWorkDocked,
                    isInFolder = isInFolder,
                    isHidden = isHidden,
                    customName = customName,
                    customBadge = customBadge,
                    customIconPath = customIconPath,
                    customIconVersion = customIconVersion,
                )
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

private data class PendingWidgetPlacement(
    val pageIndex: Int,
    val addToNewPageAfterSelection: Boolean,
)

private fun Int.coerceInWidgetPages(widgetPages: List<List<Int>>): Int =
    coerceIn(0, widgetPages.lastIndex.coerceAtLeast(0))

private fun AgendaEvent.formatTime(context: Context): String {
    if (isAllDay) {
        return context.getString(R.string.agenda_all_day_label)
    }
    return DateUtils.formatDateRange(context, beginMillis, endMillis, DateUtils.FORMAT_SHOW_TIME).toString()
}

/**
 * The time-column text for a typed-search event row. Search results are a flat,
 * headerless list spanning the whole [SEARCH_EVENT_LOOKAHEAD_DAYS] window, so —
 * unlike the agenda, which leans on day-header separators for date context —
 * an upcoming row has to carry its own day or a match reads as if it were today.
 *
 * Called per query against the current [nowMillis] (see `eventResultsFor`), not
 * baked into the index at load, so the label can't go stale between reloads —
 * a row flips to the in-progress range the moment the event starts even while
 * the launcher stays foreground.
 *
 * A timed event that is happening *right now* keeps the agenda's start–end
 * range (so an in-progress event never reads as if it starts later — an
 * overnight event that began yesterday must not look like a future
 * "Today 11:00 PM"). Every other row stacks a day label over its start time:
 * "Today" for a match later today, an abbreviated weekday ("Sun") for the next
 * six days — before weekday names repeat within the window — then an
 * abbreviated month + day ("Jul 25") from a week out. Spaces inside each half
 * are made non-breaking and the two halves are joined with a newline so the
 * 64dp column never wraps a single label mid-token onto a clipped third line
 * (same reasoning as [formatTimeForRow]'s range stacking).
 */
internal fun AgendaEvent.formatSearchTime(context: Context, nowMillis: Long): String {
    // In progress now: show the range (which [formatTimeForRow] stacks), never
    // a day label over a start time that may be on a previous day.
    if (!isAllDay && beginMillis <= nowMillis && endMillis > nowMillis) {
        return formatTime(context)
    }
    val zone = ZoneId.systemDefault()
    val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    // An all-day event's begin is UTC midnight of its day (an Android calendar
    // quirk), so read it in UTC; a timed event's begin is a real instant.
    val eventDate = if (isAllDay) {
        Instant.ofEpochMilli(beginMillis).atZone(ZoneOffset.UTC).toLocalDate()
    } else {
        Instant.ofEpochMilli(beginMillis).atZone(zone).toLocalDate()
    }
    val daysAhead = eventDate.toEpochDay() - today.toEpochDay()
    val labelMillis = eventDate.atStartOfDay(zone).toInstant().toEpochMilli()
    val dateLabel = when {
        // Today (an event still to start today, or an all-day event covering
        // today — an in-progress timed event took the range path above).
        daysAhead <= 0 -> context.getString(R.string.agenda_day_today)
        // Within the week, before weekday names repeat inside the window.
        daysAhead < 7 -> DateUtils.formatDateTime(
            context,
            labelMillis,
            DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY,
        )
        else -> DateUtils.formatDateTime(
            context,
            labelMillis,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_NO_YEAR,
        )
    }.makeNonBreaking()
    val timeLabel = if (isAllDay) {
        context.getString(R.string.agenda_all_day_label)
    } else {
        DateUtils.formatDateTime(context, beginMillis, DateUtils.FORMAT_SHOW_TIME)
    }.makeNonBreaking()
    return "$dateLabel\n$timeLabel"
}

private fun String.makeNonBreaking(): String = replace(' ', '\u00A0')

// Internal (not private) so unit tests can drive the work-only resolver
// fallback with a fake resolver — Robolectric can't register a package in a
// managed profile's PackageManager.
internal fun AppWidgetProviderInfo.toWidgetProvider(
    context: Context,
    personalUser: UserHandle,
    // Resolves a package against the provider's own profile (backed by
    // LauncherApps in production). The personal-user PackageManager lookup
    // below can't see packages installed only in a work profile, and without
    // this fallback their picker sections render the raw package name with no
    // app icon.
    resolveProfileApp: (packageName: String, profile: UserHandle) -> ApplicationInfo?,
): WidgetProvider {
    val packageManager = context.packageManager
    val appInfo = try {
        packageManager.getApplicationInfo(provider.packageName, 0)
    } catch (_: PackageManager.NameNotFoundException) {
        resolveProfileApp(provider.packageName, profile)
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

// Internal (not private) so unit tests can exercise the work-only fallback
// directly — building a cross-profile LauncherActivityInfo under Robolectric
// is not practical.
internal fun ApplicationInfo.iconCacheToken(packageManager: PackageManager): String? {
    val packageInfo = try {
        packageManager.getPackageInfo(packageName, 0)
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }
    return packageInfo?.lastUpdateTime?.takeIf { time -> time > 0L }?.toString()
        // A package installed only in a work profile is invisible to the
        // launcher's own (personal-user) PackageManager, so the lookup above
        // throws and a work-only app would get no version token at all — its
        // icon cache entry would never be invalidated, painting stale art
        // indefinitely after the app updates while the launcher process is
        // dead (no LauncherApps callback fires for an update that completed
        // before registration). Fall back to the install path recorded in
        // this profile's own ApplicationInfo: the platform moves the APK to a
        // fresh directory on every update, so the path changes exactly when
        // the icon art can.
        ?: sourceDir?.takeIf { path -> path.isNotEmpty() }
}

// Packages whose launcher icon redraws to show the current date without bumping
// the package's `lastUpdateTime`. Their normal token-based cache key therefore
// never changes on its own, so the launcher would keep painting a stale
// (yesterday's) icon; `applyDynamicCalendarToken` folds the current local date
// into their cache key instead. Extend this set as other date-aware calendar
// apps are confirmed.
internal val DYNAMIC_CALENDAR_PACKAGES = setOf(
    "com.google.android.calendar", // Google Calendar
    "com.samsung.android.calendar", // Samsung Calendar
    "com.android.calendar", // AOSP / common OEM calendars
)

// Separator marking the date segment appended to a dynamic-calendar app's
// `iconCacheToken`. Re-stamping strips any existing segment before appending the
// new date, so the token can't accumulate one segment per day as it round-trips
// through `AppMetadataStore`.
private const val CALENDAR_TOKEN_SEPARATOR = "|cal:"

/**
 * Returns [baseToken] unchanged for an ordinary app. For a package in
 * [DYNAMIC_CALENDAR_PACKAGES], strips any previously-appended date segment and
 * appends [today] (an ISO local date such as `"2026-06-02"`), yielding a token
 * that is distinct per calendar day so the app re-resolves its dated icon when
 * the day rolls over.
 */
internal fun dynamicCalendarIconToken(packageName: String, baseToken: String?, today: String): String? {
    if (packageName !in DYNAMIC_CALENDAR_PACKAGES) return baseToken
    val base = baseToken?.substringBefore(CALENDAR_TOKEN_SEPARATOR)?.takeIf { it.isNotEmpty() }
    return (base ?: "") + CALENDAR_TOKEN_SEPARATOR + today
}

/**
 * True when [iconCacheId] (e.g. `"0:com.google.android.calendar/…@token"`) belongs
 * to a [DYNAMIC_CALENDAR_PACKAGES] package. Used to keep date-specific calendar
 * icons out of `IconSnapshotStore` — both excluding them from a fresh save and
 * skipping any already on disk when restoring — so they always re-resolve to
 * today rather than painting a persisted (stale) day. Matches the `package/`
 * component prefix so an unrelated package that merely starts with a calendar
 * package name can't false-positive.
 */
internal fun isDynamicCalendarIconId(iconCacheId: String): Boolean =
    DYNAMIC_CALENDAR_PACKAGES.any { pkg -> iconCacheId.contains("$pkg/") }

private const val SETTINGS_QUERY = "settings"
private const val AGENDA_LOOKAHEAD_DAYS = 7L

// The typed-search calendar section looks twice as far ahead as the agenda
// page: search is "find that appointment", not "what's next", so a 7-day
// horizon misses the dentist visit a week from Friday, while the index stays
// small enough to filter per keystroke.
private const val SEARCH_EVENT_LOOKAHEAD_DAYS = 14L
private const val WIDGET_CELL_ESTIMATE_DP = 56

// How many of the most-launched apps to include in the icon snapshot beyond the dock.
// Sized to cover the visible app rows on a typical phone screen (~12 text rows or ~24
// icon-only grid cells) plus a margin so quick scrolls also paint without flashing,
// while keeping cold-start file IO bounded.
private const val SNAPSHOT_TOP_LAUNCH_COUNT = 24

/**
 * Collapses any leading [prefixWord]-as-whole-word tokens in [rawLabel] and
 * then runs the stripped label through [format] (which embeds it in the
 * locale's "Work %1$s" template). The strip pass means a system label that
 * already begins with the prefix doesn't double up on the work-profile copy:
 *
 *   "Work Email"          → "Work Email"      (one in, one out)
 *   "Work Work Something" → "Work Something"  (doubled prefix collapses to one)
 *   "Workplace"           → "Work Workplace"  (single word, prefix added)
 *
 * The whitespace-after check is what spares "Workplace" / "Workspace" — those
 * are single words that merely *start with* the same four letters, not a
 * leading "Work " token. Pulled out of [LauncherViewModel.workLabel] as a
 * top-level helper so unit tests can exercise the policy without spinning up
 * the ViewModel.
 */
internal fun applyWorkPrefix(
    rawLabel: String,
    prefixWord: String,
    format: (String) -> String,
): String = format(stripWorkPrefix(rawLabel, prefixWord))

/**
 * Returns [rawLabel] with any leading [prefixWord]-as-whole-word tokens removed
 * — the underlying noun the user thinks of the app by, before the work-profile
 * prefix is (re-)applied. "Work Email" → "Email", "Work Work Something" →
 * "Something", "Workplace" → "Workplace" (no leading "Work " token to strip).
 * This is the canonical un-prefixed name used both as the base for the visible
 * "Work <noun>" label (via [applyWorkPrefix]) and as the typed-search signal in
 * [InstalledApp.workPrefixStrippedSearchName], so an app whose own label already
 * begins with the locale's work token still searches by its noun.
 */
internal fun stripWorkPrefix(rawLabel: String, prefixWord: String): String {
    var stripped = rawLabel
    while (prefixWord.isNotEmpty() &&
        stripped.length > prefixWord.length &&
        stripped.startsWith(prefixWord, ignoreCase = true) &&
        stripped[prefixWord.length].isWhitespace()
    ) {
        stripped = stripped.substring(prefixWord.length).trimStart()
    }
    return stripped
}
