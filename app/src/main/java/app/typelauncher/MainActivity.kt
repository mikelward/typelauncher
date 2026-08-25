package app.typelauncher

import android.Manifest
import android.app.ActivityOptions
import android.app.WallpaperManager
import android.app.role.RoleManager
import android.appwidget.AppWidgetManager
import android.content.ActivityNotFoundException
import android.content.ComponentCallbacks2
import android.content.ComponentName
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.UserHandle
import android.os.UserManager
import android.provider.Settings
import android.view.KeyEvent
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.view.doOnPreDraw
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Set on the restart intent `restartForWallpaperWindowMode` launches so the
// replacement instance reopens Settings, keeping the user where they were when
// they flipped the "Show wallpaper" switch. Honored only when
// `savedInstanceState` is null: a configuration change redelivers the same
// intent, and re-opening Settings on every rotation after the user closed them
// would be wrong.
internal const val EXTRA_REOPEN_SETTINGS = "app.typelauncher.REOPEN_SETTINGS"

internal const val TEST_WORK_PACKAGES_EXTRA = "app.typelauncher.TEST_WORK_PACKAGES"
internal const val TEST_SEARCH_PLACEHOLDER_SUFFIX_EXTRA = "app.typelauncher.TEST_SEARCH_PLACEHOLDER_SUFFIX"
internal const val TEST_SEARCH_PLACEHOLDER_SUFFIX_PROPERTY = "app.typelauncher.TEST_SEARCH_PLACEHOLDER_SUFFIX"
// APP_WIDGET_HOST_ID lives in WidgetRestore.kt — it is shared with the
// restore-broadcast receiver, which must construct/filter against the same
// host ID.
// Safe alongside ActivityResultRegistry codes, which start at 0x00010000.
private const val CONFIGURE_WIDGET_REQUEST_CODE = 43
// Persists WidgetAddFlow.pendingWidgetId across activity recreation so a
// bind/configure in flight when the user rotates / changes theme is not
// mistaken for an orphan by the startup sweep, and so the configure-result
// fallback can still recover the ID when the result intent omits the extra.
private const val KEY_PENDING_WIDGET_ID = "app.typelauncher.PENDING_WIDGET_ID"
// Persists the stranded widget ID a restore-placeholder tap is re-binding, so
// an in-place swap survives a configuration change during the provider's
// configure activity (mirrors KEY_PENDING_WIDGET_ID).
private const val KEY_RESTORE_TARGET_WIDGET_ID = "app.typelauncher.RESTORE_TARGET_WIDGET_ID"

/**
 * The `SendIntentException` AndroidX redelivers through an activity result when an
 * `IntentSender` (here, Play's update-confirmation sheet) could not be launched at
 * all, or null for any ordinary result. A failed launch opens no external activity,
 * so `onResume` never runs to recover from it — unlike a cancel of a sheet that did
 * open. Returning the exception rather than a bare boolean keeps its message and
 * stack trace available for the log, which is the only diagnostic this failure
 * leaves behind in the field.
 */
internal fun intentSenderLaunchException(result: ActivityResult): IntentSender.SendIntentException? =
    result.data?.getSerializableExtra(
        ActivityResultContracts.StartIntentSenderForResult.EXTRA_SEND_INTENT_EXCEPTION,
        IntentSender.SendIntentException::class.java,
    )

class MainActivity : ComponentActivity() {
    internal lateinit var viewModel: LauncherViewModel
        private set
    private lateinit var appWidgetHost: LauncherAppWidgetHost
    private lateinit var appWidgetManager: AppWidgetManager
    private lateinit var playUpdateChecker: PlayUpdateChecker

    /**
     * Bumped on every [checkPlayUpdate] call, so a stale, slower check
     * can't overwrite state a fresher one already set — the same pattern
     * applied at [PlayUpdateChecker]'s own generation guard, one layer down.
     */
    private var latestPlayUpdateRefresh = 0

    /**
     * The refresh of the last answer actually applied. Guarding on this
     * instead of [latestPlayUpdateRefresh] itself matters when a *newer*
     * check fails: a failure contributes no answer, so it must not suppress
     * an older check's still-unapplied, genuinely usable one — only a newer
     * check that itself produced an answer may do that.
     */
    private var appliedPlayUpdateRefresh = 0

    private var hasSeenInitialWindowFocus = false

    // True from the moment `bindWidget` accepts a tap until its allocate/bind
    // IPCs finish (or fail). `WidgetAddFlow.isAddInFlight` only turns true once
    // an ID is allocated, but those IPCs now run off the main thread, so this
    // flag carries the "one add at a time" guard across the async gap before
    // pendingWidgetId is set. Main-thread confined — only touched from
    // `bindWidget` and its lifecycleScope continuation, both on the main thread.
    private var bindLaunchInFlight = false

    // An ID that `bindWidget` has allocated but not yet marked pending, held
    // only for the duration of the off-main-thread bind IPC. reconcileOrphaned-
    // Widgets excludes it so the startup sweep can't delete a just-allocated ID
    // mid-handshake during that window (pendingWidgetId isn't set until after
    // the bind). @Volatile because it's written on the bind coroutine and read
    // by the sweep coroutine; deliberately not persisted, so a coroutine the
    // system cancels mid-bind leaves the merely-allocated ID for the next
    // sweep to reclaim rather than wedging a restored-but-unresolvable pending.
    @Volatile
    private var bindingWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID

    // The stranded widget ID a restore-placeholder tap is currently re-binding,
    // or INVALID for an ordinary add. When the bind/configure succeeds, the
    // freshly-allocated ID replaces this one in its existing slot rather than
    // being appended. Persisted across recreation (KEY_RESTORE_TARGET_WIDGET_ID)
    // so a rotation during the provider's configure activity still lands the
    // in-place swap. Main-thread confined.
    private var restoreTargetWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID

    // The dispatcher the activity's Binder IPCs (widget-host orphan
    // reconciliation and removal, the wallpaper-offset report) hop onto, kept
    // off the main thread. Injectable so Robolectric tests can pass a
    // deterministic dispatcher, mirroring LauncherViewModel.ioDispatcher — the
    // production default is Dispatchers.IO.
    @VisibleForTesting
    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    private val requestDefaultLauncherLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {}
    private val requestCalendarPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            LauncherDebugLog.event("requestCalendarPermission result granted=$it")
            viewModel.refreshAgenda()
        }
    // Settings "Search contacts" / "Search calendar events": the toggle only
    // persists once its permission is granted, so a denial simply leaves the
    // switch off — no revert bookkeeping.
    private val requestContactSearchPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            LauncherDebugLog.event("requestContactSearchPermission result granted=$granted")
            if (granted) viewModel.setContactSearchEnabled(true)
        }
    private val requestCalendarSearchPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            LauncherDebugLog.event("requestCalendarSearchPermission result granted=$granted")
            if (granted) viewModel.setCalendarSearchEnabled(true)
        }
    // The first Call on a contact's quick-action card requests CALL_PHONE; the
    // ViewModel parked the number and resumes it on the result (placing the
    // call when granted, opening the dialer when refused).
    private val requestCallPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            LauncherDebugLog.event("requestCallPermission result granted=$granted")
            viewModel.onCallPermissionResult(granted)
        }
    // The first favorite/set-default action on a contact requests WRITE_CONTACTS;
    // the ViewModel parked the write and replays it on the result.
    private val requestWriteContactsPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            LauncherDebugLog.event("requestWriteContactsPermission result granted=$granted")
            viewModel.onWriteContactsPermissionResult(granted)
        }
    // Ordinarily does nothing with the result. `onResume` (which always runs
    // right after this, since returning from an actually-opened sheet is a
    // resume) already calls `checkPlayUpdate` unconditionally, and that
    // recheck is what flips the banner off "Updating…" and repopulates
    // `updateInfo` together atomically — a canceled sheet doesn't need this
    // callback to also flip the banner to `Idle` (Type Launcher's
    // UNKNOWN-with-Starting-fallback already reverts it) or to also trigger a
    // second, concurrent `checkPlayUpdate`. A second call here raced the
    // resume's: whichever check's response landed second could clobber a
    // progress state the other had already moved past — e.g. overwriting a
    // just-started second update attempt back to `Idle` (Codex on PR #647).
    //
    // The one case this callback does handle: the sheet's IntentSender can
    // fail to launch at all (a stale/invalid sender, Play Store transiently
    // unavailable). AndroidX catches that `SendIntentException` internally
    // and redelivers it here instead of throwing synchronously from
    // `startUpdate` — as a non-OK result carrying `EXTRA_SEND_INTENT_
    // EXCEPTION` — so no external activity ever opens and `onResume` never
    // runs to recover it (Codex on PR #647). Recover the same way a
    // synchronous launch failure already does in `startPlayUpdate`.
    //
    // Registered as a method reference rather than a forwarding lambda on
    // purpose: a lambda body would be a line of untested indirection between
    // the registry and the tested handler, and the only way to cover it would
    // be dispatching through the registry by request code, which AndroidX
    // assigns by registration order. Referencing the handler directly means
    // there is no forwarding step left to break (Codex on PR #647).
    private val playUpdateLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult(),
            ::onPlayUpdateLaunchResult,
        )
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
                // A widget provider need not have a launcher activity, so its
                // package can be absent from the installed-app list the
                // redactor is seeded from. Remember it before it is logged.
                TelemetryRedaction.rememberPackage(configure.packageName)
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
        addWidget = { appWidgetId ->
            // A restore in flight re-binds an existing placeholder: swap the new
            // ID into the stranded widget's slot instead of appending a second
            // copy. restoreTargetWidgetId is consumed here (the flow's success
            // terminal); the cancel/fail terminal clears it in deleteWidget.
            val restoreTarget = restoreTargetWidgetId
            restoreTargetWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
            when {
                restoreTarget == AppWidgetManager.INVALID_APPWIDGET_ID -> viewModel.addWidget(appWidgetId)
                restoreTarget in viewModel.uiState.value.widgetIds -> viewModel.replaceWidget(restoreTarget, appWidgetId)
                else -> {
                    // The user removed the placeholder while its bind/configure
                    // was still on screen. The in-place swap can't land (the slot
                    // is gone), so honor the removal: delete the freshly-bound ID
                    // instead of adding it — otherwise it leaks in AppWidgetService.
                    LauncherDebugLog.event(
                        "restore target $restoreTarget removed mid-flight; deleting fresh id=$appWidgetId",
                    )
                    lifecycleScope.launch(ioDispatcher) {
                        runCatching { appWidgetHost.deleteAppWidgetId(appWidgetId) }
                            .onFailure { exception ->
                                LauncherDebugLog.warning("failed to delete abandoned restore id=$appWidgetId", exception)
                            }
                    }
                    appWidgetHost.forgetWidgetSize(appWidgetId)
                }
            }
        },
        deleteWidget = { appWidgetId ->
            // A canceled/failed bind ends any restore attempt too.
            restoreTargetWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
            LauncherDebugLog.event("deletePendingWidget appWidgetId=$appWidgetId")
            // The host-binding delete is a Binder IPC — keep it off the main
            // thread (same policy as removeWidget and
            // reconcileOrphanedWidgets). This lambda runs from main-thread
            // activity-result callbacks (bind/configure canceled or failed),
            // so a busy AppWidgetService would otherwise stall the exact
            // frame on which the launcher redraws after the system dialog
            // dismisses. If the process dies before the delete lands, the
            // next start's orphan sweep releases the id.
            lifecycleScope.launch(ioDispatcher) {
                runCatching { appWidgetHost.deleteAppWidgetId(appWidgetId) }
                    .onFailure { exception ->
                        LauncherDebugLog.warning(
                            "deletePendingWidget: failed to delete id=$appWidgetId",
                            exception,
                        )
                    }
            }
        },
    )

    // True until the ViewModel has reported `isHomeReady` for the first time.
    // While set, `onStart` skips `AppWidgetHost.startListening` so the cold-
    // start AppWidgetService Binder IPC doesn't contend with the apps load and
    // the IME show. Once home-ready fires we listen immediately (if started)
    // and on every subsequent `onStart` cycle.
    private var deferStartListening = true

    // The "Show wallpaper" value the current window was built for, captured in
    // `onCreate` right after `applyWallpaperWindowMode`. The preference observer
    // compares against it so it recreates the activity only on a genuine runtime
    // change, never on the StateFlow's initial replay of the value onCreate
    // already applied (which would loop recreate forever). See
    // `observeWallpaperShownPreference`.
    private var appliedWallpaperShown = false

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
        // Bridge the ViewModel's "ask for CALL_PHONE" effect to this Activity's
        // permission launcher — the ViewModel has no Activity to prompt from.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.requestCallPermission.collect {
                    requestCallPermissionLauncher.launch(Manifest.permission.CALL_PHONE)
                }
            }
        }
        // Same bridge for the WRITE_CONTACTS ask the favorite / set-default
        // actions raise.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.requestWriteContactsPermission.collect {
                    requestWriteContactsPermissionLauncher.launch(Manifest.permission.WRITE_CONTACTS)
                }
            }
        }
        // Re-seed the in-flight add ID before anything (the orphan sweep, a
        // re-delivered configure result) can act on a stale INVALID value.
        savedInstanceState?.getInt(KEY_PENDING_WIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?.let { widgetAddFlow.restorePendingWidgetId(it) }
        restoreTargetWidgetId = savedInstanceState
            ?.getInt(KEY_RESTORE_TARGET_WIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        playUpdateChecker = PlayUpdateChecker(application)
        // Registered eagerly, not lazily on the first in-progress checkForUpdate
        // answer: a rotation between accepting the update sheet and Play's first
        // PENDING/DOWNLOADING report tears down the old checker's listener
        // (onDestroy) with the new one not yet holding any — if the very next
        // ambient check then also fails (network hiccup), nothing would ever
        // register one, stranding the banner on the busy Starting placeholder
        // (Codex on PR #648, round 11). Registering here removes the gap
        // entirely instead of patching around this one narrow timing. Deferred
        // rather than called inline: first-listener registration can do
        // platform receiver-registration IPC, and cold start / configuration
        // recreation are sacred first-frame paths this repo never blocks on
        // Play Core work. `doOnPreDraw` + `post` is what actually gets it
        // *after* the first frame — a bare `View.post` from `onCreate` runs
        // when the decor attaches, which is inside the first traversal and so
        // still ahead of the first draw (Codex on PR #648, rounds 12 and 15).
        window.decorView.doOnPreDraw { it.post { playUpdateChecker.registerInstallListener() } }
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
        // A download that fails or is canceled without the user ever
        // leaving this screen is heard only through this listener, with no
        // onResume in between to refresh the checker's cached handle —
        // startUpdate() already cleared it the moment the sheet opened.
        // Recheck now instead of waiting on a resume that may not come.
        playUpdateChecker.setInstallStatusListener { status ->
            val recoveryToken = viewModel.onPlayUpdateInstallStatus(status)
            if (recoveryToken != null) checkPlayUpdate(recoveryToken)
        }
        LauncherDebugLog.event("ViewModel ready ${viewModel.uiState.value.debugSummary()}")
        // A "Show wallpaper" toggle restarts the launcher through a fresh
        // activity start (see `restartForWallpaperWindowMode`); the restart
        // intent asks the new instance to land back in Settings so the toggle
        // doesn't kick the user out to Home. Gated on a null
        // `savedInstanceState` because a configuration change redelivers the
        // same intent, and reopening Settings on every rotation after the user
        // closed them would be wrong.
        if (savedInstanceState == null &&
            intent?.getBooleanExtra(EXTRA_REOPEN_SETTINGS, false) == true
        ) {
            viewModel.openSettings()
        }
        // Apply the persisted keyboard-auto-show preference before setContent
        // so the cold-start IME state matches the setting on the very first
        // frame. Compose owns the Home search focus target; the window keeps
        // the platform resize/show policy in sync with the user preference.
        // Seed the landscape tier from `resources.configuration` here too, so a
        // rotation into a cramped landscape (which recreates the activity) opens
        // with the keyboard already suppressed rather than flashing it for the
        // frame before Compose recomputes.
        val seedTier = computeHomeLandscapeTier()
        viewModel.setHomeLandscapeTier(seedTier)
        applyKeyboardAutoShownPreference(
            viewModel.uiState.value.isKeyboardAutoShown && seedTier == HomeLandscapeTier.Full,
        )
        observeKeyboardAutoShownPreference()
        // Apply edge-to-edge with system-bar styling that matches the persisted
        // theme mode before setContent so the cold-start status/navigation bar
        // icon contrast lines up with the very first frame, then keep it in
        // sync as the user changes the setting at runtime.
        applyEdgeToEdgeForThemeMode(viewModel.uiState.value.themeMode)
        observeThemeModePreference()
        // Put the window's wallpaper flag + surface format under the persisted
        // "Show wallpaper" preference before setContent so the first frame is
        // already correct, then keep it in sync as the user toggles the setting.
        applyWallpaperWindowMode(viewModel.uiState.value.isWallpaperShown)
        appliedWallpaperShown = viewModel.uiState.value.isWallpaperShown
        observeWallpaperShownPreference()
        observeHomeReady()
        checkPlayUpdate()
        LauncherDebugLog.event("setContent begin")
        androidTrace("launcher.set_content") {
            setContent {
                LauncherDebugLog.event("setContent composing TypeLauncherTheme")
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                TypeLauncherTheme(themeMode = state.themeMode) {
                    CompositionLocalProvider(LocalAppIconShape provides state.iconShape) {
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
                        onRestoreWidget = ::onRestoreWidget,
                        onRequestCalendarPermission = {
                            requestCalendarPermissionLauncher.launch(Manifest.permission.READ_CALENDAR)
                        },
                        onContactSearchEnabledChanged = { enabled ->
                            if (enabled && !hasPermission(Manifest.permission.READ_CONTACTS)) {
                                requestContactSearchPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                            } else {
                                viewModel.setContactSearchEnabled(enabled)
                            }
                        },
                        onCalendarSearchEnabledChanged = { enabled ->
                            if (enabled && !hasPermission(Manifest.permission.READ_CALENDAR)) {
                                requestCalendarSearchPermissionLauncher.launch(Manifest.permission.READ_CALENDAR)
                            } else {
                                viewModel.setCalendarSearchEnabled(enabled)
                            }
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
        // Was the launcher actually on screen when it died? A launcher process
        // lives in the background all day, so plenty of its crashes are never
        // seen by anyone — this key is what separates those from the ones that
        // interrupted the user, both for triage and for judging how often the
        // post-crash prompt is asking about a crash nobody witnessed. An
        // in-memory SDK map write, so it is safe to do inline here.
        LauncherTelemetry.setCustomKey(TelemetryKey.FOREGROUND, true.toString())
        // Close any force-opened secondary bar so returning to Home starts with
        // the tray hidden. The keyboard-seen reset that prevents the tray
        // flashing in before the re-shown keyboard is handled inside
        // `TypeLauncherApp` via the activity lifecycle (synchronous, pre-frame).
        viewModel.closeSecondaryTrayOnResume()
        viewModel.refreshPermissionDrivenUi()
        checkPlayUpdate()
    }

    /**
     * [recoveryToken] is `null` for an ordinary ambient check and the id
     * `LauncherViewModel.beginPlayUpdateRecovery` returned when this call
     * itself *is* the recovery — threaded through only to the failure path,
     * since only the recovery's own originating failure may resolve it
     * (`LauncherViewModel.setPlayUpdateCheckFailed` ignores anything else).
     * A success resolves unconditionally regardless of which check
     * delivered it, so it needs no token. An earlier version instead
     * compared this activity's own request-generation counter against a
     * value stored on the ViewModel, which broke across activity recreation
     * (an activity-local counter restarts at zero on rotation while the
     * ViewModel's retained comparison value doesn't) and still let a later,
     * merely-overlapping ambient failure resolve a recovery that wasn't its
     * own (Codex on PR #648, round 8).
     *
     * Every callback below also bails out if this activity [isDestroyed]:
     * `playUpdateChecker` is recreated per activity instance (`onCreate`),
     * so a callback that lands after this instance is destroyed belongs to
     * an *orphaned* checker whose `updateInfo` the recreated activity's own
     * `playUpdateChecker` field no longer refers to. Applying its result
     * would resolve the retained ViewModel's state (a recovery, a fresh
     * available/unavailable answer) as if the *current* checker had it,
     * when only the destroyed one does — the next real Update tap would
     * find nothing to launch and silently fall back to the external Play
     * listing (Codex on PR #648, round 9). The recreated activity's own
     * next check (`onResume`, which fires immediately after recreation)
     * naturally supersedes whatever the orphaned check would have reported.
     */
    private fun checkPlayUpdate(recoveryToken: Int? = null) {
        if (!::playUpdateChecker.isInitialized) return
        val refresh = ++latestPlayUpdateRefresh
        playUpdateChecker.checkForUpdate(
            onAvailable = { versionCode, installStatus ->
                if (isDestroyed) return@checkForUpdate
                if (refresh < appliedPlayUpdateRefresh) return@checkForUpdate
                appliedPlayUpdateRefresh = refresh
                viewModel.setPlayUpdateAvailable(versionCode, installStatus)
            },
            onUnavailable = {
                if (isDestroyed) return@checkForUpdate
                if (refresh < appliedPlayUpdateRefresh) return@checkForUpdate
                appliedPlayUpdateRefresh = refresh
                viewModel.setPlayUpdateUnavailable()
            },
            onCheckFailed = {
                if (isDestroyed) return@checkForUpdate
                viewModel.setPlayUpdateCheckFailed(recoveryToken)
            },
        )
    }

    private fun startPlayUpdate() {
        viewModel.setPlayUpdateProgress(UpdateProgress.Starting)
        if (!::playUpdateChecker.isInitialized || !playUpdateChecker.startUpdate(playUpdateLauncher)) {
            recoverFromFailedPlayUpdateLaunch()
        }
    }

    /**
     * The result half of [playUpdateLauncher].
     *
     * A sheet that never opened at all produces no resume to recover from —
     * that's the redelivered `SendIntentException` case — and would
     * otherwise strand the banner on "Updating…"; fall back to the store
     * listing the same way a synchronous launch failure already does.
     *
     * An ordinary decline (the sheet opened and the user backed out) is
     * known with certainty here — this is the activity result itself, not a
     * guess from Play's ambiguous `UNKNOWN` install status — but
     * `startUpdate` already consumed the cached `AppUpdateInfo` the moment
     * the sheet opened, so a decline leaves this screen with no handle
     * either way. An earlier version set the banner straight back to `Idle`
     * on a decline, which exposed a tappable Update before anything had
     * repopulated that handle: a quick retry would find nothing to launch
     * and fall back to the external Play listing (Codex on PR #648). Stay
     * busy and trigger a recovery check instead — safe to do now that a
     * decline is detected directly rather than inferred from `onResume`'s
     * `UNKNOWN`, which is what the overlapping-check race in an earlier
     * version (Codex on PR #647) was actually about: `UNKNOWN` couldn't
     * tell "declined" from "accepted, but the download hasn't registered
     * yet", so a resume recheck racing ahead of a genuine accept could snap
     * that download's banner back to "Update" too. That ambiguity is gone —
     * [progressForInstallStatus]'s `UNKNOWN` handling always preserves
     * whatever progress was already showing — and only the recovery
     * check's own failure, identified by its token, can resolve the busy
     * placeholder it parks the banner on (see [checkPlayUpdate]).
     */
    @VisibleForTesting
    internal fun onPlayUpdateLaunchResult(result: ActivityResult) {
        val launchException = intentSenderLaunchException(result)
        if (launchException != null) {
            LauncherDebugLog.warning("Play update sheet failed to launch", launchException)
            recoverFromFailedPlayUpdateLaunch()
            return
        }
        if (result.resultCode != RESULT_OK) {
            val recoveryToken = viewModel.beginPlayUpdateRecovery()
            checkPlayUpdate(recoveryToken)
        }
    }

    // The in-app update flow could not be opened — fall back to the Play
    // Store listing and clear the in-flight state so the banner recovers
    // immediately rather than being stuck showing "Updating…" with nothing
    // to tap. Shared by both ways a launch can fail to open anything: the
    // synchronous `false` from `startUpdate` above, and the asynchronous
    // `SendIntentException` `playUpdateLauncher` can report after `startUpdate`
    // already returned `true`.
    private fun recoverFromFailedPlayUpdateLaunch() {
        viewModel.setPlayUpdateProgress(UpdateProgress.Idle)
        viewModel.openPlayStoreListing()
    }

    private fun completePlayUpdate() {
        if (!::playUpdateChecker.isInitialized) return
        val attempt = viewModel.beginPlayUpdateRestartAttempt()
        val attemptedVersionCode = (viewModel.uiState.value.playUpdate as? PlayUpdateState.Available)?.versionCode
        playUpdateChecker.completeFlexibleUpdate(
            onFailure = { viewModel.setPlayUpdateRestartFailed(attempt, attemptedVersionCode) },
        )
    }

    override fun onPostResume() {
        super.onPostResume()
        LauncherDebugLog.activityCallback(this, "MainActivity.onPostResume")
    }

    override fun onPause() {
        LauncherDebugLog.activityCallback(this, "MainActivity.onPause")
        LauncherTelemetry.setCustomKey(TelemetryKey.FOREGROUND, false.toString())
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
        // A recovery this instance started can no longer be resolved by its
        // own check now that this checker's callbacks are being torn down —
        // see LauncherViewModel.abandonPendingPlayUpdateRecovery (Codex on
        // PR #648, round 10).
        viewModel.abandonPendingPlayUpdateRecovery()
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
                // The window's auto-show contract follows the *effective*
                // decision: the user's preference AND the layout actually
                // fitting the keyboard. In a cramped landscape tier we apply
                // stateAlwaysHidden so retained search focus can't resurrect the
                // IME, matching Compose's suppressed auto-show. The tier is
                // pushed into state by the Compose layer (and seeded in onCreate).
                .map { it.isKeyboardAutoShown && it.homeLandscapeTier == HomeLandscapeTier.Full }
                .distinctUntilChanged()
                .collect(::applyKeyboardAutoShownPreference)
        }
    }

    /**
     * Resolves the [HomeLandscapeTier] from `resources.configuration` and the
     * persisted keyboard reservation, for seeding the window soft-input mode in
     * [onCreate] before Compose has computed it. Reconstructs the reservation
     * fingerprint with the persisted nav-bar inset so a stored landscape
     * keyboard height applies when orientation / size / density match; otherwise
     * the fit falls back to the landscape estimate.
     */
    private fun computeHomeLandscapeTier(): HomeLandscapeTier {
        val config = resources.configuration
        val state = viewModel.uiState.value
        val navBottomPx = state.keyboardReservation.configFingerprint?.navBottomPx ?: 0
        val fingerprint = KeyboardReservationConfig(
            orientation = config.orientation,
            screenWidthDp = config.screenWidthDp,
            screenHeightDp = config.screenHeightDp,
            densityDpi = config.densityDpi,
            navBottomPx = navBottomPx,
        )
        val isWorkDockVisible = state.isWorkDockEnabled && state.isWorkProfileActive
        // Configuration.screenHeightDp includes the system bars on Android 15+
        // (see rememberHomeLandscapeUi); subtract them from the window's
        // current insets so the tier seeded here matches the one Compose
        // resolves a few frames later.
        val verticalSystemBarsPx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            val bars = windowManager.currentWindowMetrics.windowInsets.getInsets(
                WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars(),
            )
            bars.top + bars.bottom
        } else {
            0
        }
        return resolveHomeLandscapeTier(
            homeLandscapeMetrics(
                screenWidthDp = config.screenWidthDp,
                screenHeightDp = config.screenHeightDp,
                densityDpi = config.densityDpi,
                targetDockIconSizeDp = state.dockIconSizeDp,
                isPersonalDockEnabled = state.isDockEnabled,
                personalDockOccupantIds = if (state.isDockEnabled) {
                    state.dockedApps.map { it.id } + state.dockFolders.map { it.id }
                } else {
                    emptyList()
                },
                isWorkDockVisible = isWorkDockVisible,
                workDockedAppIds = if (isWorkDockVisible) {
                    state.workDockedApps.map { it.id } + state.workDockFolders.map { it.id }
                } else {
                    emptyList()
                },
                workDockPositions = state.workDockPositions,
                keyboardReservation = state.keyboardReservation,
                reservationFingerprint = fingerprint,
                dockLayout = state.dockLayout,
                appListLayout = state.appListLayout,
                fontScale = config.fontScale,
                verticalSystemBarsPx = verticalSystemBarsPx,
            ),
        )
    }

    private fun observeThemeModePreference() {
        lifecycleScope.launch {
            viewModel.uiState
                .map { it.themeMode }
                .distinctUntilChanged()
                .collect(::applyEdgeToEdgeForThemeMode)
        }
    }

    private fun observeWallpaperShownPreference() {
        lifecycleScope.launch {
            viewModel.uiState
                .map { it.isWallpaperShown }
                .distinctUntilChanged()
                .collect { wallpaperShown ->
                    // Skip the StateFlow's initial replay: `onCreate` already
                    // applied this value and recorded it, so only a genuine
                    // runtime toggle reaches the restart below.
                    if (wallpaperShown == appliedWallpaperShown) return@collect
                    // The setting changed while the activity is running.
                    // Flipping `FLAG_SHOW_WALLPAPER` and the surface format
                    // (opaque <-> translucent) needs a window rebuilt from
                    // scratch, and `recreate()` is not enough: on-device, the
                    // window that comes back from an in-place relaunch still
                    // composites as opaque, so Home's transparent wallpaper
                    // slot keeps showing whatever was drawn before (the
                    // "Settings still visible under Home after enabling Show
                    // wallpaper" smear survived the recreate() approach). Only
                    // a genuinely fresh activity start — the same path as a
                    // cold launch, which is verified clean — reliably builds
                    // the window with the new format from its first frame. The
                    // toggle is a rare, deliberate action, so the one-off
                    // restart (and the state reload it implies) is acceptable.
                    appliedWallpaperShown = wallpaperShown
                    restartForWallpaperWindowMode()
                }
        }
    }

    /**
     * Tears this instance down and starts a fresh one, carrying
     * [EXTRA_REOPEN_SETTINGS] so the replacement reopens Settings where the
     * user flipped the switch. Used instead of [recreate] for "Show wallpaper"
     * toggles: an in-place relaunch reuses activity/window state, and on-device
     * the rebuilt window kept compositing as opaque — leaving the previous
     * screen's pixels stranded in Home's transparent wallpaper slot — while a
     * true fresh start builds the window through the same path as a cold
     * launch, which shows the wallpaper correctly. The transition is animated
     * away so the swap reads as an in-place refresh, matching what recreate()
     * looked like.
     */
    private fun restartForWallpaperWindowMode() {
        val restart = Intent(this, MainActivity::class.java)
            .putExtra(EXTRA_REOPEN_SETTINGS, true)
            // Hardening for the singleTask launch mode: intent delivery already
            // skips a finishing instance (finish() below runs before
            // startActivity, and only non-finishing activities are eligible for
            // onNewIntent reuse), but CLEAR_TASK makes the fresh-root guarantee
            // explicit — the home task is cleared down to a brand-new root
            // instance instead of relying on that exclusion, so the restart can
            // never be routed to this dying instance.
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        finish()
        startActivity(restart, ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle())
        LauncherDebugLog.event("restartForWallpaperWindowMode")
    }

    /**
     * Puts the window's wallpaper machinery entirely under the persisted `Show
     * wallpaper` preference: with it on, request `FLAG_SHOW_WALLPAPER` (so the
     * system composites the live wallpaper behind the window — the only path
     * that works on API 34+, since `WallpaperManager.getDrawable()` needs
     * permissions a launcher can't hold) and a translucent surface format; with
     * it off, clear the flag and go back to an opaque surface.
     *
     * Gating *everything* on the setting — not just the window background flip
     * Home already does — is what keeps the default launcher clean: with the
     * setting off there is no wallpaper flag, so nothing composites a wallpaper
     * behind the window and nothing forwards the window's touch events to a live
     * wallpaper, and the opaque surface lets the activity behind the launcher
     * stay occluded and stopped (no extra background compositing on the launch
     * path).
     *
     * The translucent format is required whenever the wallpaper can show:
     * revealing it means Home punches its own window background transparent, and
     * HWUI only clears the surface to transparent every frame when the surface
     * is translucent-format. An opaque-format surface skips that clear over the
     * punched-through regions and leaves stale pixels behind (a typed query
     * drawn over its own placeholder, a dock frozen at its previous card
     * opacity, the pull chevron ghosted into an app cell).
     *
     * Keyed on the setting rather than the live wallpaper-visible state so the
     * flag/format flip only on the rare, deliberate toggle — never on a Home
     * entry/exit or a carousel swipe (which is what left the earlier "Home
     * didn't repaint after Settings" smear when the flag was toggled per
     * navigation against an opaque surface). Home still flips only its *window
     * background* transparent/opaque as the wallpaper becomes visible/hidden.
     *
     * Applied only from `onCreate`, before `setContent`, so the window is built
     * with the correct flag/format from its first frame. A runtime toggle does
     * NOT patch the live window — patching a live surface between opaque and
     * translucent left stale pixels stranded in Home's wallpaper slot (Settings
     * still showing under Home after enabling the setting from Settings), and
     * `recreate()` was not enough either: on-device the relaunched window still
     * composited as opaque, so the smear survived. Instead
     * `observeWallpaperShownPreference` finishes the activity and starts a
     * fresh instance on a genuine change (`restartForWallpaperWindowMode`), so
     * this runs against a window built through the same path as a cold launch —
     * the one path verified to composite the wallpaper correctly.
     */
    private fun applyWallpaperWindowMode(wallpaperShown: Boolean) {
        if (wallpaperShown) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
            window.setFormat(PixelFormat.TRANSLUCENT)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
            window.setFormat(PixelFormat.OPAQUE)
        }
        LauncherDebugLog.event("applyWallpaperWindowMode wallpaperShown=$wallpaperShown")
    }

    /**
     * Reports a fixed, centered wallpaper offset for this window. The system
     * pans a wallpaper crop that is larger than the display by the offsets the
     * wallpaper-target window reports, and a window that never reports any is
     * defaulted to the wallpaper's left edge (right edge in RTL) — not its
     * center — so a wallpaper the user centered in the system picker rendered
     * shifted sideways behind Home. The launcher never scrolls the wallpaper
     * with the carousel, so a single centered report per window attach keeps
     * it exactly where the picker previewed it (the zero step sizes tell live
     * wallpapers there are no pages to parallax between). Skipped when the
     * window was built without `FLAG_SHOW_WALLPAPER`: nothing composites a
     * wallpaper there, and the "Show wallpaper" restart builds a fresh window
     * whose own attach reports the offsets. `setWallpaperOffsets` is a window
     * manager Binder call and the attach runs just before the first frame, so
     * the IPC hops onto [ioDispatcher] to stay off the cold-start main thread.
     */
    @VisibleForTesting
    internal fun reportCenteredWallpaperOffsets(windowToken: IBinder) {
        if (!appliedWallpaperShown) return
        lifecycleScope.launch(ioDispatcher) {
            val wallpaperManager = WallpaperManager.getInstance(this@MainActivity)
            wallpaperManager.setWallpaperOffsetSteps(0f, 0f)
            wallpaperManager.setWallpaperOffsets(windowToken, 0.5f, 0.5f)
            LauncherDebugLog.event("reportCenteredWallpaperOffsets")
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
        // Mirror the resolved plate/glyph palette into the icon loader so
        // monochrome themed plates follow the launcher's effective theme AND
        // the current dynamic system colors. This is also the path that
        // re-seeds after a configuration change recreates the activity — a
        // system night-mode flip while `Theme` is `System`, and a wallpaper /
        // dynamic-color change, which recreates with the SAME resolved isDark
        // but new accent colors (the loader compares the resolved pair, so it
        // evicts stale monochrome tiles in exactly that case). The ViewModel
        // survives recreation, so its init-time seed alone would go stale.
        AppIconLoader.setThemedIconPalette(this, isDark)
        val style = if (isDark) {
            SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        } else {
            SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        }
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        LauncherDebugLog.event("applyEdgeToEdgeForThemeMode mode=$mode isDark=$isDark")
    }

    // SOFT_INPUT_ADJUST_RESIZE is deprecated in favor of a WindowInsets/IME
    // migration, but that reworks the keyboard-reservation state machine and
    // can't be device-verified here, so it stays suppressed for now. The
    // STATE_ALWAYS_* flags it's combined with are not deprecated.
    @Suppress("DEPRECATION")
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
     *
     * Deliberately one-directional: it never prunes an ID the launcher tracks
     * but the host hasn't allocated. A backup restore repopulates the widget
     * store before the platform's `ACTION_APPWIDGET_HOST_RESTORED` remap
     * (`WidgetRestoredReceiver`) has run and before providers — which reinstall
     * asynchronously over minutes to hours — are back, so a widget the user
     * still wants routinely reads as "not allocated here" during that window.
     * Deleting on that signal would destroy the restored layout mid-flight.
     * Instead it *publishes* that set (`setStrandedWidgetIds`) so those widgets
     * render as re-bindable restore placeholders; nothing is deleted.
     */
    private fun reconcileOrphanedWidgets() {
        if (hasReconciledWidgets || !::appWidgetHost.isInitialized) return
        hasReconciledWidgets = true
        lifecycleScope.launch(ioDispatcher) {
            val allocatedIds = runCatching { appWidgetHost.appWidgetIds }.getOrElse { exception ->
                LauncherDebugLog.warning("widget reconciliation: failed to read allocated ids", exception)
                return@launch
            }
            val knownWidgetIds = viewModel.uiState.value.widgetIds
            val orphans = orphanedAllocatedWidgetIds(
                allocatedIds = allocatedIds,
                knownWidgetIds = knownWidgetIds,
                pendingWidgetId = widgetAddFlow.pendingWidgetId,
                // An add that allocated its ID but hasn't finished the bind IPC
                // is tracked here, not yet in pendingWidgetId — spare it too.
                bindingWidgetId = bindingWidgetId,
            )
            // Widgets the launcher tracks that the host hasn't allocated here —
            // restore residue. Read only, never deleted (see the KDoc): they
            // render as re-bindable restore placeholders. Excludes the two
            // in-flight IDs so an add racing the sweep isn't mislabeled stranded.
            val allocatedSet = allocatedIds.toHashSet()
            val stranded = knownWidgetIds.filterTo(mutableSetOf()) { id ->
                id != AppWidgetManager.INVALID_APPWIDGET_ID &&
                    id != widgetAddFlow.pendingWidgetId &&
                    id != bindingWidgetId &&
                    id !in allocatedSet
            }
            if (orphans.isNotEmpty()) {
                LauncherDebugLog.event("widget reconciliation deleting ${orphans.size} orphaned ids=$orphans")
                orphans.forEach { id ->
                    // The host-binding delete is a Binder IPC — keep it off the
                    // main thread.
                    runCatching { appWidgetHost.deleteAppWidgetId(id) }
                        .onFailure { exception ->
                            LauncherDebugLog.warning(
                                "widget reconciliation: failed to delete orphaned id=$id",
                                exception,
                            )
                        }
                }
            }
            // forgetWidgetSize and the stranded publish touch main-thread-confined
            // state (the host's cachedSizes map that HostedWidgetCard's size-hint
            // path reads/writes every layout pass and which carries no lock, and
            // the ViewModel's state), so hop back to the main thread.
            withContext(Dispatchers.Main) {
                orphans.forEach { id -> appWidgetHost.forgetWidgetSize(id) }
                viewModel.setStrandedWidgetIds(stranded)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        LauncherDebugLog.event("MainActivity.onSaveInstanceState beforeSuper outState=${outState.debugSummary()}")
        // Carry the in-flight add ID across recreation; see KEY_PENDING_WIDGET_ID.
        outState.putInt(KEY_PENDING_WIDGET_ID, widgetAddFlow.pendingWidgetId)
        outState.putInt(KEY_RESTORE_TARGET_WIDGET_ID, restoreTargetWidgetId)
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
        window.decorView.windowToken?.let(::reportCenteredWallpaperOffsets)
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
    @Suppress("DEPRECATION")
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
        startWidgetBind(provider.componentName, provider.profile)
    }

    /**
     * Re-binds a stranded restore placeholder ([widgetId]) to the provider it
     * was remembered with, swapping the fresh ID into its slot on success (see
     * the [widgetAddFlow] `addWidget` callback and [LauncherViewModel.replaceWidget]).
     * Best effort: a missing record or an unresolvable user (a work profile that
     * didn't survive a cross-device restore) surfaces a toast and leaves the
     * placeholder in place.
     */
    private fun onRestoreWidget(widgetId: Int) {
        val record = viewModel.widgetProviderRecord(widgetId)
        if (record == null) {
            LauncherDebugLog.warning("onRestoreWidget: no provider record for id=$widgetId")
            return
        }
        val profile = getSystemService<UserManager>()?.getUserForSerialNumber(record.profileSerial)
        if (profile == null) {
            LauncherDebugLog.warning(
                "onRestoreWidget: unresolved profile serial=${record.profileSerial} id=$widgetId",
            )
            Toast.makeText(this, R.string.widgets_picker_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        TelemetryRedaction.rememberPackage(record.component.packageName)
        LauncherDebugLog.event(
            "onRestoreWidget id=$widgetId component=${record.component.flattenToShortString()}",
        )
        startWidgetBind(record.component, profile, restoreTargetId = widgetId)
    }

    /**
     * Allocates a host widget ID that doesn't collide with one the launcher
     * already tracks. Restore keeps stranded placeholder IDs in the store even
     * though the host has freed them, so `allocateAppWidgetId` can hand back one
     * of those IDs — and adding a widget under an ID already in the store is a
     * no-op ([WidgetStore.add]) / mis-slot ([WidgetStore.replaceId]). Re-rolls
     * past any collision, releasing the discarded IDs. Runs on [ioDispatcher]
     * (host IPCs); reads the tracked set from the ViewModel's state snapshot.
     * `allocateAppWidgetId` returns a distinct fresh ID each call, so at most
     * `tracked.size` of them can collide before one is untracked — the loop is
     * bounded by that, not an arbitrary constant, so even a restore with a long
     * run of stranded IDs (say 1..N) can't exhaust the retries and leak a
     * collision. (A contract-violating host that repeats IDs would still exit
     * after `tracked.size` tries; that's logged rather than looping forever.)
     */
    private fun allocateFreshWidgetId(): Int {
        val tracked = viewModel.uiState.value.widgetIds.toHashSet()
        val discarded = mutableListOf<Int>()
        var id = appWidgetHost.allocateAppWidgetId()
        while (id in tracked && discarded.size < tracked.size) {
            discarded += id
            id = appWidgetHost.allocateAppWidgetId()
        }
        if (discarded.isNotEmpty()) {
            LauncherDebugLog.event("allocateFreshWidgetId re-rolled past ${discarded.size} tracked ids=$discarded")
            discarded.forEach { discardedId -> runCatching { appWidgetHost.deleteAppWidgetId(discardedId) } }
        }
        if (id in tracked) {
            LauncherDebugLog.warning("allocateFreshWidgetId exhausted retries, id=$id still tracked")
        }
        return id
    }

    private fun startWidgetBind(
        component: ComponentName,
        profile: UserHandle,
        restoreTargetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID,
    ) {
        // One add at a time: WidgetAddFlow tracks a single pendingWidgetId, so
        // a second Add tap while a bind/configure launch is still in flight
        // (the window before its dialog covers the picker) must be ignored.
        // The allocate/bind IPCs below run off the main thread, so between the
        // tap and the ID allocation `isAddInFlight` is still false; the
        // synchronous `bindLaunchInFlight` latch closes that window so the
        // refused second tap doesn't allocate — and leak — a second ID.
        if (widgetAddFlow.isAddInFlight || bindLaunchInFlight) {
            LauncherDebugLog.event(
                "bindWidget ignored: add in flight pendingWidgetId=${widgetAddFlow.pendingWidgetId}",
            )
            return
        }
        // Set only past the guard so a refused tap doesn't clobber an in-flight
        // restore target. INVALID for an ordinary add clears any stale target.
        restoreTargetWidgetId = restoreTargetId
        bindLaunchInFlight = true
        // allocateAppWidgetId and bindAppWidgetIdIfAllowed are AppWidgetService
        // Binder IPCs — keep them off the main thread (same policy as
        // removeWidget / reconcileOrphanedWidgets) so a busy AppWidgetService
        // can't stall the frame on which the picker dismisses. Everything that
        // mutates WidgetAddFlow state, launches the system dialog, or shows a
        // Toast stays on the main thread, where lifecycleScope.launch resumes.
        lifecycleScope.launch {
            val appWidgetId = withContext(ioDispatcher) {
                allocateFreshWidgetId().also { id ->
                    // Guard the fresh ID against the startup orphan sweep before
                    // yielding for the bind IPC — set on the same thread as the
                    // allocation so no sweep can observe it allocated-but-
                    // unprotected across the hop back to the main thread.
                    bindingWidgetId = id
                }
            }
            TelemetryRedaction.rememberPackage(component.packageName)
            LauncherDebugLog.event(
                "bindWidget provider=${component.flattenToShortString()} appWidgetId=$appWidgetId " +
                    "restoreTargetId=$restoreTargetId",
            )
            val allowed = try {
                withContext(ioDispatcher) {
                    appWidgetManager.bindAppWidgetIdIfAllowed(appWidgetId, profile, component, null)
                }
            } catch (exception: RuntimeException) {
                // A restore tap binds a *remembered* provider, which may no
                // longer exist — its app hasn't reinstalled yet, was removed, or
                // changed the provider class. AppWidgetService throws
                // (IllegalArgumentException "unknown provider", or SecurityException)
                // rather than returning false, and this runs off the main thread
                // in lifecycleScope, so an uncaught throw crashes the launcher.
                // Release the allocated ID and reset the latches (onBindStarted
                // hasn't run yet, so no pendingWidgetId is wedged), then surface
                // the same unavailable toast as a failed picker launch.
                LauncherDebugLog.warning(
                    "bindWidget failed: provider ${component.flattenToShortString()} not bindable",
                    exception,
                )
                withContext(ioDispatcher) { runCatching { appWidgetHost.deleteAppWidgetId(appWidgetId) } }
                bindingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
                bindLaunchInFlight = false
                restoreTargetWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
                Toast.makeText(this@MainActivity, R.string.widgets_picker_unavailable, Toast.LENGTH_SHORT).show()
                return@launch
            }
            // Commit the pending ID only now — past both cancellable IPCs and
            // with no suspension point between here and the resolving call
            // below. If a configuration change or process death cancels this
            // coroutine mid-IPC, pendingWidgetId was never set (so it isn't
            // persisted into a wedged isAddInFlight) and the merely-allocated
            // ID is reclaimed by the next sweep (bindingWidgetId, being
            // transient, is gone on recreation so it no longer shields it).
            // Setting pending before the bind — as the old synchronous path
            // could — would instead leave a restored-but-unresolvable pending.
            widgetAddFlow.onBindStarted(appWidgetId)
            // pendingWidgetId now carries the sweep exclusion and the re-entrancy
            // guard, so hand both off: drop the pre-pending shield and latch.
            bindingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
            bindLaunchInFlight = false
            if (allowed) {
                LauncherDebugLog.event("bindWidget allowed appWidgetId=$appWidgetId")
                widgetAddFlow.onBindAllowed(appWidgetId)
                return@launch
            }

            val bindIntent: Intent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, component)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, profile)
            try {
                LauncherDebugLog.event("bindWidget launching system bind intent appWidgetId=$appWidgetId")
                bindWidgetLauncher.launch(bindIntent)
            } catch (exception: ActivityNotFoundException) {
                LauncherDebugLog.warning("bindWidget failed: picker unavailable", exception)
                widgetAddFlow.onBindLaunchFailed()
                Toast.makeText(this@MainActivity, R.string.widgets_picker_unavailable, Toast.LENGTH_SHORT).show()
            } catch (exception: SecurityException) {
                LauncherDebugLog.warning("bindWidget failed: security exception", exception)
                widgetAddFlow.onBindLaunchFailed()
                Toast.makeText(this@MainActivity, R.string.widgets_picker_unavailable, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun removeWidget(appWidgetId: Int) {
        LauncherDebugLog.event("removeWidget appWidgetId=$appWidgetId")
        viewModel.removeWidget(appWidgetId)
        // The host-binding delete is a Binder IPC — keep it off the main
        // thread (same policy as reconcileOrphanedWidgets) so a busy
        // AppWidgetService can't stall the menu-dismiss frame. If the process
        // dies before the delete lands, the next start's orphan sweep
        // releases the id.
        lifecycleScope.launch(ioDispatcher) {
            runCatching { appWidgetHost.deleteAppWidgetId(appWidgetId) }
                .onFailure { exception ->
                    LauncherDebugLog.warning("removeWidget: failed to delete id=$appWidgetId", exception)
                }
        }
        // Drop the persisted size cache entry so the widget_size_cache
        // SharedPreferences file doesn't accumulate dead widget IDs over
        // the device's lifetime as users add and remove widgets. Stays on
        // the main thread: the host's size cache map is main-thread confined.
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

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
}

private fun Intent.isLauncherEntryIntent(): Boolean =
    action == Intent.ACTION_MAIN &&
        (hasCategory(Intent.CATEGORY_HOME) || hasCategory(Intent.CATEGORY_LAUNCHER))

// The RUNNING_* / MODERATE / COMPLETE levels are deprecated as of API 35, but
// onTrimMemory still delivers them on the pre-35 devices we support (minSdk 34),
// so they stay in the description map for log fidelity.
@Suppress("DEPRECATION")
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
