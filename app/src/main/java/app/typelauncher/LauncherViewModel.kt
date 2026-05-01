package app.typelauncher

import android.Manifest
import android.app.Application
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
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
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

internal class LauncherViewModel(
    private val app: Application,
    private val workPackages: Set<String>,
) : ViewModel() {
    private val dockedAppStore = DockedAppStore(app)
    private val widgetStore = WidgetStore(app)
    private val dockSettingsStore = DockSettingsStore(app)
    private val appLaunchStatsStore = AppLaunchStatsStore(app)
    private val settingsLaunchGate = SettingsLaunchGate()
    private var installedApps: List<InstalledApp> = loadInstalledApps()
    private val _uiState = MutableStateFlow(
        LauncherUiState(
            filteredApps = installedApps.filterByName("", appLaunchStatsStore, dockedAppStore.dockedAppIds).markDocked(),
            dockedApps = installedApps.filterDockedByName(dockedAppStore.dockedAppIds, "").markDocked(),
            widgetIds = widgetStore.widgetIds,
            agenda = loadAgendaState(),
            isDockEnabled = dockSettingsStore.isDockEnabled,
            dockIconSizeDp = dockSettingsStore.dockIconSizeDp,
        ),
    )
    val uiState: StateFlow<LauncherUiState> = _uiState.asStateFlow()

    fun setQuery(query: String) {
        _uiState.update { state -> state.copy(query = query) }
        refreshLists()
    }

    fun showAgenda() {
        _uiState.update { it.copy(screen = LauncherScreen.Agenda, agenda = loadAgendaState()) }
    }

    fun showWidgets() {
        _uiState.update { it.copy(screen = LauncherScreen.Widgets) }
    }

    fun showWidgetPicker() {
        showWidgetPicker(loadAvailableWidgets())
    }

    fun hideWidgetPicker() {
        _uiState.update { it.copy(isAddingWidget = false) }
    }

    fun showHome() {
        _uiState.update { it.copy(screen = LauncherScreen.Home) }
    }

    fun refreshPermissionDrivenUi() {
        if (_uiState.value.screen == LauncherScreen.Agenda) {
            refreshAgenda()
        }
    }

    fun refreshAgenda() {
        _uiState.update { it.copy(agenda = loadAgendaState()) }
    }

    fun launchActiveApp() {
        val query = _uiState.value.query
        if (query.trim().equals(SETTINGS_QUERY, ignoreCase = true)) {
            startActivity(Intent(Settings.ACTION_SETTINGS).asLauncherTaskIntent())
            setQuery("")
            return
        }
        _uiState.value.filteredApps.firstOrNull()?.let(::launchApp)
    }

    fun launchApp(app: InstalledApp) {
        val component = app.launchIntent.component
        if (app.launchWithLauncherApps && component != null) {
            this.app.getSystemService<LauncherApps>()?.startMainActivity(component, app.user, null, null)
        } else {
            startActivity(app.launchIntent.asLauncherTaskIntent())
        }
        appLaunchStatsStore.recordLaunch(app.id)
        setQuery("")
    }

    fun openAppInfo(app: InstalledApp) {
        startActivity(app.appInfoIntent)
    }

    fun toggleDock(app: InstalledApp, maxDockedApps: Int) {
        if (app.isDocked) {
            dockedAppStore.undock(app.id)
        } else if (!dockedAppStore.dock(app.id, maxDockedApps)) {
            Toast.makeText(
                this.app,
                this.app.getString(R.string.docked_apps_limit_message, maxDockedApps),
                Toast.LENGTH_SHORT,
            ).show()
        }
        refreshLists()
    }

    fun resetRank(app: InstalledApp) {
        appLaunchStatsStore.resetLaunchCount(app.id)
        refreshLists()
    }

    fun addWidget(appWidgetId: Int) {
        widgetStore.add(appWidgetId)
        _uiState.update {
            it.copy(
                screen = LauncherScreen.Widgets,
                isAddingWidget = false,
                widgetIds = widgetStore.widgetIds,
            )
        }
    }

    internal fun refreshAvailableWidgetsForTest() {
        _uiState.update { it.copy(availableWidgets = loadAvailableWidgets()) }
    }

    internal fun showWidgetPickerForTest(availableWidgets: List<WidgetProvider>) {
        showWidgetPicker(availableWidgets)
    }

    fun removeWidget(appWidgetId: Int) {
        widgetStore.remove(appWidgetId)
        _uiState.update { it.copy(screen = LauncherScreen.Widgets, widgetIds = widgetStore.widgetIds) }
    }

    fun openSettings() {
        _uiState.update { it.copy(isSettingsOpen = true) }
    }

    fun closeSettings() {
        _uiState.update { it.copy(isSettingsOpen = false) }
    }

    fun setDockEnabled(isEnabled: Boolean) {
        dockSettingsStore.isDockEnabled = isEnabled
        _uiState.update { it.copy(isDockEnabled = isEnabled) }
    }

    fun setDockIconSizeDp(sizeDp: Int) {
        val clampedSizeDp = sizeDp.coerceIn(MIN_DOCK_APP_ICON_SIZE_DP, MAX_DOCK_APP_ICON_SIZE_DP)
        dockSettingsStore.dockIconSizeDp = clampedSizeDp
        _uiState.update { it.copy(dockIconSizeDp = clampedSizeDp) }
    }

    private fun refreshLists() {
        val query = _uiState.value.query.trim()
        _uiState.update { state ->
            state.copy(
                filteredApps = installedApps.filterByName(query, appLaunchStatsStore, dockedAppStore.dockedAppIds).markDocked(),
                dockedApps = installedApps.filterDockedByName(dockedAppStore.dockedAppIds, query).markDocked(),
            )
        }
    }

    private fun startActivity(intent: Intent) {
        app.startActivity(intent.asLauncherTaskIntent())
    }

    private fun loadAgendaState(): AgendaUiState {
        if (!hasCalendarPermission()) {
            return AgendaUiState.PermissionRequired
        }
        val events = loadAgendaEvents()
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
                    )
                }
            }
        } catch (_: SecurityException) {
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
        return app.getSystemService<LauncherApps>()
            ?.profiles
            .orEmpty()
            .flatMap { user ->
                app.getSystemService<LauncherApps>()
                    ?.getActivityList(null, user)
                    .orEmpty()
                    .map { activity ->
                        InstalledApp(
                            name = activity.label.toString(),
                            packageName = activity.applicationInfo.packageName,
                            launchIntent = Intent.makeMainActivity(activity.componentName),
                            icon = activity.getIcon(0),
                            user = user,
                            isWorkApp = user != personalUser || activity.applicationInfo.packageName in workPackages,
                            launchWithLauncherApps = true,
                        )
                    }
            }
            .ifEmpty {
                app.packageManager.queryIntentActivities(launcherIntent, 0)
                    .map { resolveInfo ->
                        val activityInfo = resolveInfo.activityInfo
                        InstalledApp(
                            name = resolveInfo.loadLabel(app.packageManager).toString(),
                            packageName = activityInfo.packageName,
                            launchIntent = Intent.makeMainActivity(
                                ComponentName(activityInfo.packageName, activityInfo.name),
                            ),
                            icon = resolveInfo.loadIcon(app.packageManager),
                            user = personalUser,
                            isWorkApp = activityInfo.packageName in workPackages,
                            launchWithLauncherApps = false,
                        )
                    }
            }
            .distinctBy { launcherApp -> launcherApp.name.lowercase() to launcherApp.isWorkApp }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { launcherApp -> launcherApp.name })
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

    private fun showWidgetPicker(availableWidgets: List<WidgetProvider>) {
        _uiState.update {
            it.copy(
                screen = LauncherScreen.Widgets,
                isAddingWidget = true,
                availableWidgets = availableWidgets,
            )
        }
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
}

private fun AgendaEvent.formatTime(context: Context): String {
    if (isAllDay) {
        return context.getString(R.string.agenda_all_day_label)
    }
    val zone = ZoneId.systemDefault()
    val eventDay = Instant.ofEpochMilli(beginMillis).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(System.currentTimeMillis()).atZone(zone).toLocalDate()
    val flags = if (eventDay == today) {
        DateUtils.FORMAT_SHOW_TIME
    } else {
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH
    }
    return DateUtils.formatDateRange(context, beginMillis, endMillis, flags).toString()
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
