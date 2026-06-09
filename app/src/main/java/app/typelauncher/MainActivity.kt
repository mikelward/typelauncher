package app.typelauncher

import android.Manifest
import android.app.role.RoleManager
import android.appwidget.AppWidgetManager
import android.content.ActivityNotFoundException
import android.content.ComponentCallbacks2
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.core.content.getSystemService
import androidx.core.view.doOnPreDraw
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

internal const val TEST_WORK_PACKAGES_EXTRA = "app.typelauncher.TEST_WORK_PACKAGES"
internal const val TEST_SEARCH_PLACEHOLDER_SUFFIX_EXTRA = "app.typelauncher.TEST_SEARCH_PLACEHOLDER_SUFFIX"
internal const val TEST_SEARCH_PLACEHOLDER_SUFFIX_PROPERTY = "app.typelauncher.TEST_SEARCH_PLACEHOLDER_SUFFIX"
private const val APP_WIDGET_HOST_ID = 1024
private const val PLAY_UPDATE_REQUEST_CODE = 42
// Safe alongside ActivityResultRegistry codes, which start at 0x00010000.
private const val CONFIGURE_WIDGET_REQUEST_CODE = 43

class MainActivity : ComponentActivity() {
    internal lateinit var viewModel: LauncherViewModel
        private set
    private lateinit var appWidgetHost: LauncherAppWidgetHost
    private lateinit var appWidgetManager: AppWidgetManager
    private lateinit var playUpdateChecker: PlayUpdateChecker
    private var hasSeenInitialWindowFocus = false

    private val requestDefaultLauncherLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {}
    private val requestCalendarPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            LauncherDebugLog.event("requestCalendarPermission result granted=$it")
            viewModel.refreshAgenda()
        }
    private val bindWidgetLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val resultWidgetId = result.data?.getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID,
            )
            LauncherDebugLog.event(
                "bindWidget resultCode=${result.resultCode} resultWidgetId=$resultWidgetId " +
                    "pendingWidgetId=${widgetAddFlow.pendingWidgetId}",
            )
            widgetAddFlow.onBindResult(result.resultCode == RESULT_OK, resultWidgetId)
        }

    // Owns pendingWidgetId and the bind → configure → add transitions; see
    // WidgetAddFlow's KDoc for why the pending ID must survive across the
    // whole configure launch. The callbacks read the lateinit
    // appWidgetManager / appWidgetHost / viewModel, which is safe because
    // they only run from activity-result and UI callbacks after onCreate.
    private val widgetAddFlow = WidgetAddFlow(
        launchConfigure = { appWidgetId ->
            val providerInfo = appWidgetManager.getAppWidgetInfo(appWidgetId)
            val configure = providerInfo?.configure
            if (configure != null) {
                LauncherDebugLog.event(
                    "configureOrAddWidget launching configure appWidgetId=$appWidgetId " +
                        "configure=${configure.flattenToShortString()}",
                )
                try {
                    // Host-mediated launch: the system grants the start
                    // across profiles and into non-exported configure
                    // activities. The previous explicit-intent launch
                    // resolved work-profile configure activities in the
                    // personal user and threw SecurityException on
                    // non-exported ones — and it was unguarded, so that
                    // throw crashed the launcher mid-"add widget". The
                    // result lands in onActivityResult under
                    // CONFIGURE_WIDGET_REQUEST_CODE.
                    appWidgetHost.startAppWidgetConfigureActivityForResult(
                        this,
                        appWidgetId,
                        0,
                        CONFIGURE_WIDGET_REQUEST_CODE,
                        null,
                    )
                    WidgetAddFlow.ConfigureLaunch.Launched
                } catch (exception: ActivityNotFoundException) {
                    LauncherDebugLog.warning("configure launch failed appWidgetId=$appWidgetId", exception)
                    Toast.makeText(this, R.string.widgets_picker_unavailable, Toast.LENGTH_SHORT).show()
                    WidgetAddFlow.ConfigureLaunch.Failed
                } catch (exception: SecurityException) {
                    LauncherDebugLog.warning("configure launch failed appWidgetId=$appWidgetId", exception)
                    Toast.makeText(this, R.string.widgets_picker_unavailable, Toast.LENGTH_SHORT).show()
                    WidgetAddFlow.ConfigureLaunch.Failed
                }
            } else {
                LauncherDebugLog.event(
                    "configureOrAddWidget adding appWidgetId=$appWidgetId hasProvider=${providerInfo != null}",
                )
                WidgetAddFlow.ConfigureLaunch.NotNeeded
            }
        },
        addWidget = { appWidgetId -> viewModel.addWidget(appWidgetId) },
        deleteWidget = { appWidgetId ->
            LauncherDebugLog.event("deletePendingWidget appWidgetId=$appWidgetId")
            appWidgetHost.deleteAppWidgetId(appWidgetId)
        },
    )

    // True until the ViewModel has reported `isHomeReady` for the first time.
    // While set, `onStart` skips `AppWidgetHost.startListening` so the cold-
    // start AppWidgetService Binder IPC doesn't contend with the apps load and
    // the IME show. Once home-ready fires we listen immediately (if started)
    // and on every subsequent `onStart` cycle.
    private var deferStartListening = true

    override fun onCreate(savedInstanceState: Bundle?) {
        LauncherDebugLog.activityCallback(this, "MainActivity.onCreate beforeSuper")
        LauncherDebugLog.event("onCreate savedInstanceState=${savedInstanceState.debugSummary()}")
        androidTrace("launcher.super_onCreate") { super.onCreate(savedInstanceState) }
        enableEdgeToEdge()
        LauncherDebugLog.event("onCreate afterSuper window=${window.debugSummary()}")
        // Wraps onCreate → first pre-draw so Firebase Performance shows the
        // launcher's own cold-start time alongside the SDK's auto-instrumented
        // app_start trace. The auto trace covers Application.onCreate +
        // Activity.onCreate + first draw, so this complements (rather than
        // replaces) it by isolating the launcher work specifically.
        val coldStartTrace = LauncherTelemetry.startTrace("launcher_cold_start")
        coldStartTrace.setAttribute(
            "saved_instance_state",
            if (savedInstanceState == null) "absent" else "present",
        )
        window.decorView.doOnPreDraw {
            LauncherDebugLog.event("MainActivity firstPreDraw window=${window.debugSummary()}")
            coldStartTrace.stop()
        }
        androidTrace("launcher.appwidget_init") {
            appWidgetHost = LauncherAppWidgetHost(applicationContext, APP_WIDGET_HOST_ID)
            appWidgetManager = AppWidgetManager.getInstance(this)
        }
        playUpdateChecker = PlayUpdateChecker(application)
        LauncherDebugLog.event("AppWidgetHost initialized hostId=$APP_WIDGET_HOST_ID")
        androidTrace("launcher.viewmodel_init") {
            viewModel = ViewModelProvider(
                this,
                LauncherViewModel.factory(
                    app = application,
                    workPackages = testWorkPackages(intent),
                ),
            )[LauncherViewModel::class.java]
        }
        playUpdateChecker.setInstallStatusListener(viewModel::onPlayUpdateInstallStatus)
        LauncherDebugLog.event("ViewModel ready ${viewModel.uiState.value.debugSummary()}")
        // Apply the persisted keyboard-auto-show preference before setContent
        // so the cold-start IME state matches the setting on the very first
        // frame. Compose owns the Home search focus target; the window keeps
        // the platform resize/show policy in sync with the user preference.
        applyKeyboardAutoShownPreference(viewModel.uiState.value.isKeyboardAutoShown)
        observeKeyboardAutoShownPreference()
        // Apply edge-to-edge with system-bar styling that matches the persisted
        // theme mode before setContent so the cold-start status/navigation bar
        // icon contrast lines up with the very first frame, then keep it in
        // sync as the user changes the setting at runtime.
        applyEdgeToEdgeForThemeMode(viewModel.uiState.value.themeMode)
        observeThemeModePreference()
        observeHomeReady()
        checkPlayUpdate()
        LauncherDebugLog.event("setContent begin")
        androidTrace("launcher.set_content") {
            setContent {
                LauncherDebugLog.event("setContent composing TypeLauncherTheme")
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                TypeLauncherTheme(themeMode = state.themeMode) {
                    TypeLauncherApp(
                        viewModel = viewModel,
                        appWidgetHost = appWidgetHost,
                        appWidgetManager = appWidgetManager,
                        onAddWidget = { request ->
                            viewModel.showWidgetPicker(
                                pageIndex = request.pageIndex,
                                addToNewPageAfterSelection = request.isCurrentPageScrollable,
                            )
                        },
                        onDismissWidgetPicker = viewModel::hideWidgetPicker,
                        onSelectWidget = ::bindWidget,
                        onRemoveWidget = ::removeWidget,
                        onRequestCalendarPermission = {
                            requestCalendarPermissionLauncher.launch(Manifest.permission.READ_CALENDAR)
                        },
                        onRequestDefaultLauncher = ::requestDefaultLauncher,
                        onSwipeDown = ::expandNotificationShade,
                        onStartPlayUpdate = ::startPlayUpdate,
                        onCompletePlayUpdate = ::completePlayUpdate,
                        searchPlaceholderSuffix = searchPlaceholderSuffix(),
                    )
                }
            }
        }
        LauncherDebugLog.event("setContent returned")
    }

    private fun searchPlaceholderSuffix(): String =
        System.getProperty(TEST_SEARCH_PLACEHOLDER_SUFFIX_PROPERTY)
            ?: testIntentExtra(TEST_SEARCH_PLACEHOLDER_SUFFIX_EXTRA)
            ?: BuildConfig.SEARCH_PLACEHOLDER_SUFFIX

    // Test-only intent extras are honored solely in debug builds. The launcher
    // activity is exported (it's the HOME entry point), so in a release build
    // any installed app could fire an Intent carrying TEST_WORK_PACKAGES and
    // make the launcher render fake work-profile state for the session. Gating
    // on BuildConfig.DEBUG keeps the instrumentation/Robolectric seams (both run
    // against the debug build) while closing that surface in production.
    private fun testWorkPackages(intent: Intent?): Set<String> =
        if (BuildConfig.DEBUG) {
            intent?.getStringArrayExtra(TEST_WORK_PACKAGES_EXTRA)?.toSet().orEmpty()
        } else {
            emptySet()
        }

    private fun testIntentExtra(name: String): String? =
        if (BuildConfig.DEBUG) intent.getStringExtra(name) else null

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        LauncherDebugLog.activityCallback(this, "MainActivity.onNewIntent", intent)
        handleLauncherIntent(intent)
    }

    internal fun handleLauncherIntent(intent: Intent) {
        if (!intent.isLauncherEntryIntent()) return
        LauncherDebugLog.event("handleLauncherIntent returning to launcher home")
        viewModel.returnToLauncherHome()
    }

    override fun onRestart() {
        super.onRestart()
        LauncherDebugLog.activityCallback(this, "MainActivity.onRestart")
    }

    override fun onStart() {
        super.onStart()
        LauncherDebugLog.activityCallback(this, "MainActivity.onStart")
        if (deferStartListening) {
            LauncherDebugLog.event("AppWidgetHost.startListening deferred until home ready")
        } else {
            startListeningSafely()
        }
    }

    override fun onResume() {
        super.onResume()
        LauncherDebugLog.activityCallback(this, "MainActivity.onResume")
        // Close any force-opened secondary bar so returning to Home starts with
        // the tray hidden. The keyboard-seen reset that prevents the tray
        // flashing in before the re-shown keyboard is handled inside
        // `TypeLauncherApp` via the activity lifecycle (synchronous, pre-frame).
        viewModel.closeSecondaryTrayOnResume()
        viewModel.refreshPermissionDrivenUi()
        checkPlayUpdate()
    }

    private fun checkPlayUpdate() {
        if (!::playUpdateChecker.isInitialized) return
        playUpdateChecker.checkForUpdate(
            onAvailable = viewModel::setPlayUpdateAvailable,
            onUnavailable = viewModel::setPlayUpdateUnavailable,
        )
    }

    private fun startPlayUpdate() {
        viewModel.setPlayUpdateProgress(UpdateProgress.Starting)
        if (!::playUpdateChecker.isInitialized || !playUpdateChecker.startUpdate(this, PLAY_UPDATE_REQUEST_CODE)) {
            // The in-app update flow could not be opened — fall back to the
            // Play Store listing and clear the in-flight state so the banner
            // can recover on next resume.
            viewModel.setPlayUpdateProgress(UpdateProgress.Idle)
            viewModel.openPlayStoreListing()
        }
    }

    private fun completePlayUpdate() {
        if (!::playUpdateChecker.isInitialized) return
        playUpdateChecker.completeFlexibleUpdate()
    }

    override fun onPostResume() {
        super.onPostResume()
        LauncherDebugLog.activityCallback(this, "MainActivity.onPostResume")
    }

    override fun onPause() {
        LauncherDebugLog.activityCallback(this, "MainActivity.onPause")
        super.onPause()
    }

    override fun onStop() {
        LauncherDebugLog.activityCallback(this, "MainActivity.onStop")
        stopListeningSafely()
        if (::viewModel.isInitialized) {
            viewModel.persistIconSnapshot()
        }
        super.onStop()
    }

    override fun onDestroy() {
        LauncherDebugLog.activityCallback(this, "MainActivity.onDestroy")
        // onStop normally already stopped listening, but make sure we never leak the
        // host's IPC binding if we get here without an onStop (e.g. process killed in
        // foreground and then re-attached). stopListening is idempotent.
        stopListeningSafely()
        // Each activity instance owns its own PlayUpdateChecker, so drop its
        // install-state listener here or the AppUpdateManager would retain a
        // dead listener (capturing this activity's ViewModel) on every
        // recreation. Idempotent if the listener was never registered.
        if (::playUpdateChecker.isInitialized) {
            playUpdateChecker.unregisterInstallListener()
        }
        super.onDestroy()
    }

    private fun stopListeningSafely() {
        if (!::appWidgetHost.isInitialized) return
        try {
            appWidgetHost.stopListening()
            LauncherDebugLog.event("AppWidgetHost.stopListening")
        } catch (exception: RuntimeException) {
            LauncherDebugLog.warning("AppWidgetHost.stopListening failed", exception)
        }
    }

    private fun startListeningSafely() {
        if (!::appWidgetHost.isInitialized) return
        try {
            appWidgetHost.startListening()
            LauncherDebugLog.event("AppWidgetHost.startListening")
        } catch (exception: RuntimeException) {
            LauncherDebugLog.warning("AppWidgetHost.startListening failed", exception)
        }
    }

    private fun observeKeyboardAutoShownPreference() {
        lifecycleScope.launch {
            viewModel.uiState
                .map { it.isKeyboardAutoShown }
                .distinctUntilChanged()
                .collect(::applyKeyboardAutoShownPreference)
        }
    }

    private fun observeThemeModePreference() {
        lifecycleScope.launch {
            viewModel.uiState
                .map { it.themeMode }
                .distinctUntilChanged()
                .collect(::applyEdgeToEdgeForThemeMode)
        }
    }

    /**
     * (Re-)applies `enableEdgeToEdge` with explicit `SystemBarStyle`s derived
     * from [mode], so the status / navigation bar icon contrast tracks the
     * launcher-selected theme rather than only the device's night-mode flag.
     * Without this the activity's bar styling is decided once in `onCreate` by
     * `enableEdgeToEdge`'s default detector (which reads
     * `Configuration.UI_MODE_NIGHT_MASK`); switching `Theme` to `Light` or
     * `Dark` at runtime would otherwise leave bar icons mismatched against the
     * new surface colors until the activity is recreated.
     */
    private fun applyEdgeToEdgeForThemeMode(mode: ThemeMode) {
        val systemDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val isDark = when (mode) {
            ThemeMode.System -> systemDark
            ThemeMode.Light -> false
            ThemeMode.Dark -> true
        }
        val style = if (isDark) {
            SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        } else {
            SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        }
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        LauncherDebugLog.event("applyEdgeToEdgeForThemeMode mode=$mode isDark=$isDark")
    }

    private fun applyKeyboardAutoShownPreference(autoShown: Boolean) {
        // Keep the window resize/show contract aligned with the Home keyboard
        // preference while Compose controls which field owns focus. When the
        // user opts out of Home auto-show, stateAlwaysHidden prevents retained
        // search focus from resurrecting the keyboard on launcher resume. Plain
        // stateHidden only applies on forward navigation.
        val mode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
            if (autoShown) {
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
            } else {
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
            }
        window.setSoftInputMode(mode)
        LauncherDebugLog.event("applyKeyboardAutoShownPreference autoShown=$autoShown mode=0x${mode.toString(16)}")
    }

    private fun observeHomeReady() {
        lifecycleScope.launch {
            viewModel.uiState.map { it.isHomeReady }.first { ready -> ready }
            deferStartListening = false
            // If we're already started, we previously skipped startListening in
            // onStart — kick it off now. If we're not started, the next onStart
            // will pick it up.
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                LauncherDebugLog.event("home ready: starting deferred AppWidgetHost listener")
                startListeningSafely()
            } else {
                LauncherDebugLog.event(
                    "home ready before STARTED; AppWidgetHost listener will start on next onStart",
                )
            }
            reconcileOrphanedWidgets()
        }
    }

    // True once the one-shot orphaned-widget sweep has run this process, so it
    // can't repeat if home-ready re-emits.
    private var hasReconciledWidgets = false

    /**
     * One-shot startup sweep of widget IDs the host has allocated but the
     * launcher no longer tracks — leaked by crashes / process death mid-add and
     * by the historical configure-cancel bug — deleting each so its bound
     * instance is released. Runs off the main thread (the host ID query and
     * deletes are Binder IPCs) and after home-ready so it never competes with
     * the cold-start app load. Deferred to here, not `onCreate`, because the
     * persisted widget set is already published in `uiState` by then.
     */
    private fun reconcileOrphanedWidgets() {
        if (hasReconciledWidgets || !::appWidgetHost.isInitialized) return
        hasReconciledWidgets = true
        lifecycleScope.launch(Dispatchers.IO) {
            val allocatedIds = runCatching { appWidgetHost.appWidgetIds }.getOrElse { exception ->
                LauncherDebugLog.warning("widget reconciliation: failed to read allocated ids", exception)
                return@launch
            }
            val orphans = orphanedAllocatedWidgetIds(
                allocatedIds = allocatedIds,
                knownWidgetIds = viewModel.uiState.value.widgetIds,
                pendingWidgetId = widgetAddFlow.pendingWidgetId,
            )
            if (orphans.isEmpty()) return@launch
            LauncherDebugLog.event("widget reconciliation deleting ${orphans.size} orphaned ids=$orphans")
            orphans.forEach { id ->
                runCatching {
                    appWidgetHost.deleteAppWidgetId(id)
                    appWidgetHost.forgetWidgetSize(id)
                }.onFailure { exception ->
                    LauncherDebugLog.warning("widget reconciliation: failed to delete orphaned id=$id", exception)
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        LauncherDebugLog.event("MainActivity.onSaveInstanceState beforeSuper outState=${outState.debugSummary()}")
        super.onSaveInstanceState(outState)
        LauncherDebugLog.event("MainActivity.onSaveInstanceState afterSuper outState=${outState.debugSummary()}")
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        LauncherDebugLog.event(
            "MainActivity.onRestoreInstanceState beforeSuper savedInstanceState=${savedInstanceState.debugSummary()}",
        )
        super.onRestoreInstanceState(savedInstanceState)
        LauncherDebugLog.event(
            "MainActivity.onRestoreInstanceState afterSuper savedInstanceState=${savedInstanceState.debugSummary()}",
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        LauncherDebugLog.event("MainActivity.onAttachedToWindow window=${window.debugSummary()}")
    }

    override fun onDetachedFromWindow() {
        LauncherDebugLog.event("MainActivity.onDetachedFromWindow window=${window.debugSummary()}")
        super.onDetachedFromWindow()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        LauncherDebugLog.event("MainActivity.onWindowFocusChanged hasFocus=$hasFocus window=${window.debugSummary()}")
        if (hasFocus && ::viewModel.isInitialized) {
            if (hasSeenInitialWindowFocus) {
                viewModel.requestShowKeyboardOnHomeResume()
            } else {
                hasSeenInitialWindowFocus = true
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        LauncherDebugLog.event(
            "MainActivity.onConfigurationChanged orientation=${newConfig.orientation} " +
                "keyboard=${newConfig.keyboard} keyboardHidden=${newConfig.keyboardHidden} " +
                "uiMode=0x${newConfig.uiMode.toString(16)} screenLayout=0x${newConfig.screenLayout.toString(16)}",
        )
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        LauncherDebugLog.event("MainActivity.onTrimMemory level=$level description=${level.trimMemoryDescription()}")
    }

    override fun onLowMemory() {
        super.onLowMemory()
        LauncherDebugLog.warning("MainActivity.onLowMemory")
    }

    override fun onUserLeaveHint() {
        LauncherDebugLog.activityCallback(this, "MainActivity.onUserLeaveHint")
        super.onUserLeaveHint()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        LauncherDebugLog.event("MainActivity.onKeyDown keyCode=$keyCode event=${event.debugSummary()}")
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        LauncherDebugLog.event("MainActivity.onKeyUp keyCode=$keyCode event=${event.debugSummary()}")
        return super.onKeyUp(keyCode, event)
    }

    // The widget configure activity is launched host-mediated
    // (AppWidgetHost.startAppWidgetConfigureActivityForResult), which only
    // reports back through the classic onActivityResult path — the
    // ActivityResultRegistry never sees the request.
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == CONFIGURE_WIDGET_REQUEST_CODE) {
            val resultWidgetId = data?.getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID,
            )
            LauncherDebugLog.event(
                "configureWidget resultCode=$resultCode resultWidgetId=$resultWidgetId " +
                    "pendingWidgetId=${widgetAddFlow.pendingWidgetId}",
            )
            widgetAddFlow.onConfigureResult(resultCode == RESULT_OK, resultWidgetId)
        }
    }

    private fun bindWidget(provider: WidgetProvider) {
        val appWidgetId = appWidgetHost.allocateAppWidgetId()
        widgetAddFlow.onBindStarted(appWidgetId)
        LauncherDebugLog.event(
            "bindWidget provider=${provider.componentName.flattenToShortString()} appWidgetId=$appWidgetId",
        )
        if (appWidgetManager.bindAppWidgetIdIfAllowed(appWidgetId, provider.profile, provider.componentName, null)) {
            LauncherDebugLog.event("bindWidget allowed appWidgetId=$appWidgetId")
            widgetAddFlow.onBindAllowed(appWidgetId)
            return
        }

        val bindIntent: Intent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider.componentName)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, provider.profile)
        try {
            LauncherDebugLog.event("bindWidget launching system bind intent appWidgetId=$appWidgetId")
            bindWidgetLauncher.launch(bindIntent)
        } catch (exception: ActivityNotFoundException) {
            LauncherDebugLog.warning("bindWidget failed: picker unavailable", exception)
            widgetAddFlow.onBindLaunchFailed()
            Toast.makeText(this, R.string.widgets_picker_unavailable, Toast.LENGTH_SHORT).show()
        } catch (exception: SecurityException) {
            LauncherDebugLog.warning("bindWidget failed: security exception", exception)
            widgetAddFlow.onBindLaunchFailed()
            Toast.makeText(this, R.string.widgets_picker_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    private fun removeWidget(appWidgetId: Int) {
        LauncherDebugLog.event("removeWidget appWidgetId=$appWidgetId")
        viewModel.removeWidget(appWidgetId)
        appWidgetHost.deleteAppWidgetId(appWidgetId)
        // Drop the persisted size cache entry so the widget_size_cache
        // SharedPreferences file doesn't accumulate dead widget IDs over
        // the device's lifetime as users add and remove widgets.
        appWidgetHost.forgetWidgetSize(appWidgetId)
    }

    private fun requestDefaultLauncher() {
        LauncherDebugLog.event("requestDefaultLauncher")
        val intent = getSystemService<RoleManager>()?.createRequestRoleIntent(RoleManager.ROLE_HOME)
            ?: Intent(Settings.ACTION_HOME_SETTINGS)
        try {
            requestDefaultLauncherLauncher.launch(intent)
        } catch (exception: ActivityNotFoundException) {
            // Some OEM builds ship no activity for ROLE_HOME's request intent
            // or for ACTION_HOME_SETTINGS — launching it unguarded crashed the
            // launcher. Swallow the miss so the prompt just no-ops instead.
            LauncherDebugLog.warning("requestDefaultLauncher no activity for home settings", exception)
        }
    }

    private fun expandNotificationShade() {
        NotificationShade.expand(this)
    }
}

private fun Intent.isLauncherEntryIntent(): Boolean =
    action == Intent.ACTION_MAIN &&
        (hasCategory(Intent.CATEGORY_HOME) || hasCategory(Intent.CATEGORY_LAUNCHER))

private fun Int.trimMemoryDescription(): String =
    when (this) {
        ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> "UI_HIDDEN"
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> "RUNNING_MODERATE"
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> "RUNNING_LOW"
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> "RUNNING_CRITICAL"
        ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> "BACKGROUND"
        ComponentCallbacks2.TRIM_MEMORY_MODERATE -> "MODERATE"
        ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> "COMPLETE"
        else -> "UNKNOWN"
    }
