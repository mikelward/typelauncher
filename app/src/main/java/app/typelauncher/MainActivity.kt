package app.typelauncher

import android.Manifest
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.os.UserHandle
import android.provider.CalendarContract
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.LinkedHashSet
import kotlin.math.max

internal const val TEST_WORK_PACKAGES_EXTRA = "app.typelauncher.TEST_WORK_PACKAGES"

class MainActivity : AppCompatActivity() {
    internal var latestAppMenu: PopupMenu? = null
        private set
    private lateinit var homeSearchKeyboardController: HomeSearchKeyboardController
    private var launcherScreenSwitcher: LauncherScreenSwitcher? = null
    private var searchInputView: EditText? = null
    private var agendaUi: AgendaUi? = null

    private val requestCalendarPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            refreshAgenda()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        launcherScreenSwitcher = findViewById<LauncherScreenSwitcher>(R.id.launcher_screen_switcher).apply {
            displayedChild = HOME_SCREEN_INDEX
            swipeListener = object : LauncherScreenSwitcher.SwipeListener {
                override fun onSwipeLeft(): Boolean = tryShowAgendaScreen()

                override fun onSwipeRight(): Boolean = tryShowHomeScreen()
            }
        }

        val root = findViewById<View>(R.id.main_root)
        val appSearchInput = findViewById<EditText>(R.id.app_search_input)
        val appSearchClearButton = findViewById<ImageButton>(R.id.app_search_clear_button)
        val settingsLaunchGate = SettingsLaunchGate()
        searchInputView = appSearchInput
        val dockedAppStore = DockedAppStore(this)
        val appLaunchStatsStore = AppLaunchStatsStore(this)
        setupAgendaUi()
        val installedApps = installedApps()
        val filteredApps = installedApps.toMutableList()
        val filteredDockedApps = installedApps.filterDockedByName(dockedAppStore.dockedAppIds, "").toMutableList()
        val installedAppNamesAdapter = InstalledAppsAdapter(
            this@MainActivity,
            filteredApps.toMutableList(),
        )
        val installedAppsCard = findViewById<LinearLayout>(R.id.installed_apps_card)
        val dockedAppsCard = findViewById<LinearLayout>(R.id.docked_apps_card)
        val dockedAppsHint = findViewById<TextView>(R.id.docked_apps_hint)
        val dockedAppsList = findViewById<LinearLayout>(R.id.docked_apps_list)
        val baseTop = root.paddingTop
        val baseBottom = root.paddingBottom
        homeSearchKeyboardController = HomeSearchKeyboardController(
            requestSearchFocus = { appSearchInput.requestFocus() },
            searchHasFocus = { appSearchInput.hasFocus() },
            searchHasWindowFocus = { appSearchInput.hasWindowFocus() },
            postToSearch = { block -> appSearchInput.post { block() } },
            postDelayedToSearch = { block, delayMillis -> appSearchInput.postDelayed({ block() }, delayMillis) },
            showSearchKeyboard = {
                getSystemService<InputMethodManager>()
                    ?.showSoftInput(appSearchInput, InputMethodManager.SHOW_IMPLICIT)
            },
        )
        homeSearchKeyboardController.showKeyboard()
        appSearchInput.setOnFocusChangeListener { _, hasFocus ->
            homeSearchKeyboardController.onSearchFocusChanged(hasFocus)
        }
        fun refreshLists(query: String) {
            filteredApps.replaceWith(installedApps.filterByName(query, appLaunchStatsStore))
            filteredDockedApps.replaceWith(installedApps.filterDockedByName(dockedAppStore.dockedAppIds, query))
            installedAppNamesAdapter.replaceWith(filteredApps)
            renderDockedApps(
                dockedApps = filteredDockedApps,
                dockedAppsRow = dockedAppsList,
                appSearchInput = appSearchInput,
                dockedAppStore = dockedAppStore,
                appLaunchStatsStore = appLaunchStatsStore,
                afterDockChanged = { refreshLists(appSearchInput.text.toString().trim()) },
                afterLaunch = { refreshLists(appSearchInput.text.toString().trim()) },
            )
            dockedAppsHint.isVisible = filteredDockedApps.isEmpty()
            dockedAppsList.isVisible = filteredDockedApps.isNotEmpty()
            installedAppsCard.requestLayout()
            dockedAppsCard.requestLayout()
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            homeSearchKeyboardController.onImeVisibilityChanged(
                imeVisible = ime.bottom > 0 || insets.isVisible(WindowInsetsCompat.Type.ime()),
            )
            val bottomInset = max(systemBars.bottom, ime.bottom)
            val combined = Insets.of(systemBars.left, systemBars.top, systemBars.right, bottomInset)
            view.setPadding(
                combined.left,
                baseTop + combined.top,
                combined.right,
                baseBottom + combined.bottom,
            )
            insets
        }
        ViewCompat.requestApplyInsets(root)
        appSearchInput.setOnEditorActionListener { _, actionId, event ->
            val isSearchAction = actionId == EditorInfo.IME_ACTION_SEARCH
            val isEnterKey = event?.keyCode == KeyEvent.KEYCODE_ENTER

            if (!isSearchAction && !isEnterKey) {
                return@setOnEditorActionListener false
            }

            if (settingsLaunchGate.shouldLaunch(
                    action = event?.action,
                    keyCode = event?.keyCode,
                    repeatCount = event?.repeatCount ?: 0,
                    downTime = event?.downTime,
                )
            ) {
                launchActiveApp(
                    filteredApps = filteredApps,
                    query = appSearchInput.text.toString(),
                    appSearchInput = appSearchInput,
                    appLaunchStatsStore = appLaunchStatsStore,
                    afterLaunch = { refreshLists(appSearchInput.text.toString().trim()) },
                )
            }
            true
        }
        appSearchClearButton.setOnClickListener {
            appSearchInput.text?.clear()
            appSearchInput.requestFocus()
        }
        findViewById<ListView>(R.id.installed_apps_list).apply {
            adapter = installedAppNamesAdapter
            setOnItemClickListener { _, _, position, _ ->
                launchAndClearQuery(
                    app = filteredApps[position],
                    appSearchInput = appSearchInput,
                    appLaunchStatsStore = appLaunchStatsStore,
                    afterLaunch = { refreshLists(appSearchInput.text.toString().trim()) },
                )
            }
            setOnItemLongClickListener { _, view, position, _ ->
                showAppMenu(
                    anchor = view,
                    app = filteredApps[position],
                    dockedAppStore = dockedAppStore,
                    afterDockChanged = { refreshLists(appSearchInput.text.toString().trim()) },
                )
                true
            }
        }
        refreshLists("")
        appSearchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) {
                val query = text?.toString().orEmpty().trim()
                refreshLists(query)
                appSearchClearButton.isVisible = text?.isNotEmpty() == true
            }

            override fun afterTextChanged(text: Editable?) = Unit
        })
        appSearchClearButton.isVisible = appSearchInput.text?.isNotEmpty() == true
    }

    override fun onResume() {
        super.onResume()
        if (launcherScreenSwitcher?.displayedChild == AGENDA_SCREEN_INDEX) {
            refreshAgenda()
        } else if (::homeSearchKeyboardController.isInitialized) {
            homeSearchKeyboardController.showKeyboard()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus &&
            launcherScreenSwitcher?.displayedChild == HOME_SCREEN_INDEX &&
            ::homeSearchKeyboardController.isInitialized
        ) {
            homeSearchKeyboardController.showKeyboard()
        }
    }

    private fun setupAgendaUi() {
        val agendaRoot = findViewById<View>(R.id.agenda_root)
        val permissionCard = findViewById<LinearLayout>(R.id.agenda_permission_card)
        val permissionButton = findViewById<Button>(R.id.agenda_permission_button)
        val emptyState = findViewById<TextView>(R.id.agenda_empty_state)
        val eventsCard = findViewById<LinearLayout>(R.id.agenda_events_card)
        val eventsList = findViewById<ListView>(R.id.agenda_events_list)
        val eventAdapter = AgendaEventsAdapter(this, mutableListOf())
        eventsList.adapter = eventAdapter
        agendaUi = AgendaUi(
            permissionCard = permissionCard,
            emptyState = emptyState,
            eventsCard = eventsCard,
            eventAdapter = eventAdapter,
        )
        permissionButton.setOnClickListener {
            requestCalendarPermissionLauncher.launch(Manifest.permission.READ_CALENDAR)
        }

        val baseTop = agendaRoot.paddingTop
        val baseBottom = agendaRoot.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(agendaRoot) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                systemBars.left,
                baseTop + systemBars.top,
                systemBars.right,
                baseBottom + systemBars.bottom,
            )
            insets
        }
        ViewCompat.requestApplyInsets(agendaRoot)
    }

    private fun tryShowAgendaScreen(): Boolean {
        if (launcherScreenSwitcher?.displayedChild != HOME_SCREEN_INDEX) {
            return false
        }
        showAgendaScreen()
        return true
    }

    private fun tryShowHomeScreen(): Boolean {
        if (launcherScreenSwitcher?.displayedChild != AGENDA_SCREEN_INDEX) {
            return false
        }
        showHomeScreen()
        return true
    }

    private fun showAgendaScreen() {
        launcherScreenSwitcher?.displayedChild = AGENDA_SCREEN_INDEX
        currentFocus?.let { focusedView ->
            getSystemService<InputMethodManager>()?.hideSoftInputFromWindow(focusedView.windowToken, 0)
        }
        refreshAgenda()
    }

    private fun showHomeScreen() {
        launcherScreenSwitcher?.displayedChild = HOME_SCREEN_INDEX
        searchInputView?.requestFocus()
        if (::homeSearchKeyboardController.isInitialized) {
            homeSearchKeyboardController.showKeyboard()
        }
    }

    private fun refreshAgenda() {
        val ui = agendaUi ?: return
        if (!hasCalendarPermission()) {
            ui.permissionCard.isVisible = true
            ui.eventsCard.isVisible = false
            ui.emptyState.isVisible = false
            ui.eventAdapter.replaceWith(emptyList())
            return
        }

        val events = loadAgendaEvents()
        ui.permissionCard.isVisible = false
        ui.eventAdapter.replaceWith(events)
        ui.eventsCard.isVisible = events.isNotEmpty()
        ui.emptyState.isVisible = events.isEmpty()
    }

    private fun hasCalendarPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

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
            contentResolver.query(
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
                            ?: getString(R.string.agenda_event_untitled),
                        beginMillis = cursor.getLong(beginIndex),
                        endMillis = cursor.getLong(endIndex),
                        isAllDay = cursor.getInt(allDayIndex) == 1,
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
        )
    }

    private fun installedApps(): List<InstalledApp> {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val personalUser = Process.myUserHandle()
        return getSystemService<LauncherApps>()
            ?.profiles
            .orEmpty()
            .flatMap { user ->
                getSystemService<LauncherApps>()
                    ?.getActivityList(null, user)
                    .orEmpty()
                    .map { activity ->
                        InstalledApp(
                            name = activity.label.toString(),
                            packageName = activity.applicationInfo.packageName,
                            launchIntent = Intent.makeMainActivity(activity.componentName),
                            icon = activity.getIcon(0),
                            user = user,
                            isWorkApp = user != personalUser,
                            launchWithLauncherApps = true,
                        )
                    }
            }
            .ifEmpty {
                packageManager.queryIntentActivities(launcherIntent, 0)
                    .map { resolveInfo ->
                        val activityInfo = resolveInfo.activityInfo
                        InstalledApp(
                            name = resolveInfo.loadLabel(packageManager).toString(),
                            packageName = activityInfo.packageName,
                            launchIntent = Intent.makeMainActivity(
                                ComponentName(activityInfo.packageName, activityInfo.name),
                            ),
                            icon = resolveInfo.loadIcon(packageManager),
                            user = personalUser,
                            isWorkApp = false,
                            launchWithLauncherApps = false,
                        )
                    }
            }
            .markWorkAppsForTests()
            .distinctBy { app -> app.name.lowercase() to app.isWorkApp }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { app -> app.name })
    }

    private fun List<InstalledApp>.markWorkAppsForTests(): List<InstalledApp> {
        val workPackages = intent
            ?.getStringArrayExtra(TEST_WORK_PACKAGES_EXTRA)
            ?.toSet()
            .orEmpty()
        if (workPackages.isEmpty()) {
            return this
        }
        return map { app ->
            if (app.packageName in workPackages) app.copy(isWorkApp = true) else app
        }
    }

    private data class InstalledApp(
        val name: String,
        val packageName: String,
        val launchIntent: Intent,
        val icon: Drawable,
        val user: UserHandle,
        val isWorkApp: Boolean,
        val launchWithLauncherApps: Boolean,
    ) {
        val id: String
            get() = "${user.hashCode()}:${launchIntent.component?.flattenToString() ?: packageName}"

        val appInfoIntent: Intent
            get() = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:$packageName"))

        override fun toString(): String = name
    }

    private fun List<InstalledApp>.filterByName(query: String, appLaunchStatsStore: AppLaunchStatsStore): List<InstalledApp> =
        if (query.isEmpty()) {
            sortedWith(
                compareByDescending<InstalledApp> { app -> appLaunchStatsStore.launchCount(app.id) }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { app -> app.name },
            )
        } else {
            filter { app -> app.name.contains(query, ignoreCase = true) }
        }

    private fun List<InstalledApp>.filterDockedByName(dockedAppIds: List<String>, query: String): List<InstalledApp> =
        filter { app -> app.id in dockedAppIds }
            .let { dockedApps ->
                if (query.isEmpty()) {
                    dockedApps
                } else {
                    dockedApps.filter { app -> app.name.contains(query, ignoreCase = true) }
                }
            }
            .sortedBy { app -> dockedAppIds.indexOf(app.id) }

    private fun MutableList<InstalledApp>.replaceWith(apps: List<InstalledApp>) {
        clear()
        addAll(apps)
    }

    private fun InstalledAppsAdapter.replaceWith(apps: List<InstalledApp>) {
        clear()
        addAll(apps)
    }

    private fun launchActiveApp(
        filteredApps: List<InstalledApp>,
        query: String,
        appSearchInput: EditText,
        appLaunchStatsStore: AppLaunchStatsStore,
        afterLaunch: () -> Unit,
    ) {
        if (query.trim().equals(SETTINGS_QUERY, ignoreCase = true)) {
            launchAndClearQuery(Intent(Settings.ACTION_SETTINGS), appSearchInput)
            return
        }
        filteredApps.firstOrNull()?.let { app ->
            launchAndClearQuery(
                app = app,
                appSearchInput = appSearchInput,
                appLaunchStatsStore = appLaunchStatsStore,
                afterLaunch = afterLaunch,
            )
        }
    }

    private fun launchAndClearQuery(
        app: InstalledApp,
        appSearchInput: EditText,
        appLaunchStatsStore: AppLaunchStatsStore,
        afterLaunch: () -> Unit,
    ) {
        launchApp(app)
        appLaunchStatsStore.recordLaunch(app.id)
        appSearchInput.text?.clear()
        afterLaunch()
    }

    private fun launchAndClearQuery(intent: Intent, appSearchInput: EditText) {
        startActivity(intent.asLauncherTaskIntent())
        appSearchInput.text?.clear()
    }

    private fun launchApp(app: InstalledApp) {
        val component = app.launchIntent.component
        if (app.launchWithLauncherApps && component != null) {
            getSystemService<LauncherApps>()
                ?.startMainActivity(component, app.user, null, null)
        } else {
            startActivity(app.launchIntent.asLauncherTaskIntent())
        }
    }

    private fun Intent.asLauncherTaskIntent(): Intent =
        Intent(this).addFlags(LAUNCHER_TASK_FLAGS)

    private fun renderDockedApps(
        dockedApps: List<InstalledApp>,
        dockedAppsRow: LinearLayout,
        appSearchInput: EditText,
        dockedAppStore: DockedAppStore,
        appLaunchStatsStore: AppLaunchStatsStore,
        afterDockChanged: () -> Unit,
        afterLaunch: () -> Unit,
    ) {
        dockedAppsRow.removeAllViews()
        dockedApps.forEach { app ->
            val button = ImageButton(this).apply {
                layoutParams = LinearLayout.LayoutParams(DOCK_APP_ICON_SIZE_DP.dpToPx(), DOCK_APP_ICON_SIZE_DP.dpToPx())
                background = null
                contentDescription = app.name
                scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                setImageDrawable(app.icon.constantState?.newDrawable()?.mutate() ?: app.icon)
                val padding = DOCK_APP_ICON_PADDING_DP.dpToPx()
                setPadding(padding, padding, padding, padding)
                setOnClickListener {
                    launchAndClearQuery(
                        app = app,
                        appSearchInput = appSearchInput,
                        appLaunchStatsStore = appLaunchStatsStore,
                        afterLaunch = afterLaunch,
                    )
                }
                setOnLongClickListener {
                    showAppMenu(
                        anchor = this,
                        app = app,
                        dockedAppStore = dockedAppStore,
                        afterDockChanged = afterDockChanged,
                    )
                    true
                }
            }
            dockedAppsRow.addView(button)
        }
    }

    private fun Int.dpToPx(): Int =
        (this * resources.displayMetrics.density).toInt()

    private fun showAppMenu(
        anchor: View,
        app: InstalledApp,
        dockedAppStore: DockedAppStore,
        afterDockChanged: () -> Unit,
    ) {
        val isDocked = dockedAppStore.contains(app.id)
        PopupMenu(this, anchor).apply {
            latestAppMenu = this
            menu.add(MENU_GROUP_APP_ACTIONS, MENU_ITEM_APP_INFO, 0, getString(R.string.app_menu_app_info))
            menu.add(
                MENU_GROUP_APP_ACTIONS,
                MENU_ITEM_TOGGLE_DOCK,
                1,
                getString(if (isDocked) R.string.app_menu_undock else R.string.app_menu_dock),
            )
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_ITEM_APP_INFO -> {
                        startActivity(app.appInfoIntent)
                        true
                    }
                    MENU_ITEM_TOGGLE_DOCK -> {
                        if (isDocked) {
                            dockedAppStore.undock(app.id)
                            afterDockChanged()
                        } else if (dockedAppStore.dock(app.id, dockCapacityFor())) {
                            afterDockChanged()
                        } else {
                            Toast.makeText(
                                this@MainActivity,
                                getString(R.string.docked_apps_limit_message, dockCapacityFor()),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                        true
                    }
                    else -> false
                }
            }
            show()
        }
    }

    private fun dockCapacityFor(): Int {
        val dockedAppsCard = findViewById<LinearLayout>(R.id.docked_apps_card)
        val dockedAppsRow = findViewById<LinearLayout>(R.id.docked_apps_list)
        val dockViewportWidth = (dockedAppsRow.parent as? View)?.width?.takeIf { width -> width > 0 }
            ?: dockedAppsCard.width.takeIf { width -> width > 0 }
        val fallbackViewportWidth = resources.displayMetrics.widthPixels - (DOCK_CARD_HORIZONTAL_MARGIN_DP * 2).dpToPx()
        val availableWidth = ((dockViewportWidth ?: fallbackViewportWidth) - dockedAppsRow.paddingLeft - dockedAppsRow.paddingRight)
            .coerceAtLeast(0)
        val iconWidth = DOCK_APP_ICON_SIZE_DP.dpToPx()
        val iconsThatFit = availableWidth / iconWidth
        return iconsThatFit.coerceAtLeast(MIN_DOCKED_APPS)
    }

    private companion object {
        const val SETTINGS_QUERY = "settings"
        const val MIN_DOCKED_APPS = 1
        const val DOCK_CARD_HORIZONTAL_MARGIN_DP = 24
        const val DOCK_APP_ICON_SIZE_DP = 56
        const val DOCK_APP_ICON_PADDING_DP = 8
        const val MENU_GROUP_APP_ACTIONS = 0
        const val MENU_ITEM_APP_INFO = 1
        const val MENU_ITEM_TOGGLE_DOCK = 2
        const val LAUNCHER_TASK_FLAGS =
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        const val AGENDA_SCREEN_INDEX = 0
        const val HOME_SCREEN_INDEX = 1
        const val AGENDA_LOOKAHEAD_DAYS = 7L
    }

    private class InstalledAppsAdapter(
        context: android.content.Context,
        apps: MutableList<InstalledApp>,
    ) : ArrayAdapter<InstalledApp>(context, R.layout.installed_app_list_item, apps) {
        private val inflater = LayoutInflater.from(context)
        private val activeBackgroundColor = context.getColor(R.color.active_app_background)
        private val defaultBackgroundColor = context.getColor(android.R.color.white)

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: inflater.inflate(R.layout.installed_app_list_item, parent, false)
            val textView = view.findViewById<TextView>(android.R.id.text1)
            val workBadge = view.findViewById<ImageView>(R.id.work_app_badge)
            val app = getItem(position)
            textView.text = app?.name.orEmpty()
            workBadge.isVisible = app?.isWorkApp == true
            view.setBackgroundColor(if (position == ACTIVE_APP_POSITION) activeBackgroundColor else defaultBackgroundColor)
            return view
        }

        private companion object {
            const val ACTIVE_APP_POSITION = 0
        }
    }

    private class AgendaEventsAdapter(
        context: Context,
        private val events: MutableList<AgendaEvent>,
    ) : ArrayAdapter<AgendaEvent>(context, R.layout.agenda_event_list_item, events) {
        private val inflater = LayoutInflater.from(context)

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: inflater.inflate(R.layout.agenda_event_list_item, parent, false)
            val event = getItem(position) ?: return view
            view.findViewById<TextView>(R.id.agenda_event_title).text = event.title
            view.findViewById<TextView>(R.id.agenda_event_time).text = formatTime(event)
            return view
        }

        fun replaceWith(items: List<AgendaEvent>) {
            clear()
            addAll(items)
        }

        private fun formatTime(event: AgendaEvent): CharSequence {
            if (event.isAllDay) {
                return context.getString(R.string.agenda_all_day_label)
            }
            val zone = ZoneId.systemDefault()
            val eventDay = Instant.ofEpochMilli(event.beginMillis).atZone(zone).toLocalDate()
            val today = Instant.ofEpochMilli(System.currentTimeMillis()).atZone(zone).toLocalDate()
            val flags = if (eventDay == today) {
                DateUtils.FORMAT_SHOW_TIME
            } else {
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH
            }
            return DateUtils.formatDateRange(context, event.beginMillis, event.endMillis, flags)
        }
    }

    private class DockedAppStore(context: Context) {
        private val sharedPreferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        private var dockedIds = sharedPreferences.getString(KEY_DOCKED_APP_IDS, "").orEmpty()
            .split(DOCKED_APP_ID_SEPARATOR)
            .filter { appId -> appId.isNotBlank() }
            .toCollection(LinkedHashSet())

        val dockedAppIds: List<String>
            get() = dockedIds.toList()

        fun contains(appId: String): Boolean = appId in dockedIds

        fun dock(appId: String, maxDockedApps: Int): Boolean {
            if (appId in dockedIds) {
                return true
            }
            if (dockedIds.size >= maxDockedApps) {
                return false
            }
            dockedIds.add(appId)
            save()
            return true
        }

        fun undock(appId: String) {
            if (dockedIds.remove(appId)) {
                save()
            }
        }

        private fun save() {
            sharedPreferences.edit()
                .putString(KEY_DOCKED_APP_IDS, dockedIds.joinToString(DOCKED_APP_ID_SEPARATOR))
                .apply()
        }

        private companion object {
            const val PREFERENCES_NAME = "docked_apps"
            const val KEY_DOCKED_APP_IDS = "docked_app_ids"
            const val DOCKED_APP_ID_SEPARATOR = "\n"
        }
    }

    private class AppLaunchStatsStore(context: Context) {
        private val sharedPreferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

        fun launchCount(appId: String): Int =
            sharedPreferences.getInt(appId.toLaunchCountKey(), 0)

        fun recordLaunch(appId: String) {
            sharedPreferences.edit()
                .putInt(appId.toLaunchCountKey(), launchCount(appId) + 1)
                .apply()
        }

        private fun String.toLaunchCountKey(): String = "$KEY_LAUNCH_COUNT_PREFIX$this"

        private companion object {
            const val PREFERENCES_NAME = "app_launch_stats"
            const val KEY_LAUNCH_COUNT_PREFIX = "launch_count:"
        }
    }

    private data class AgendaUi(
        val permissionCard: View,
        val emptyState: View,
        val eventsCard: View,
        val eventAdapter: AgendaEventsAdapter,
    )

    data class AgendaEvent(
        val title: String,
        val beginMillis: Long,
        val endMillis: Long,
        val isAllDay: Boolean,
    )
}

internal class HomeSearchKeyboardController(
    private val requestSearchFocus: () -> Unit,
    private val searchHasFocus: () -> Boolean,
    private val searchHasWindowFocus: () -> Boolean,
    private val postToSearch: (() -> Unit) -> Unit,
    private val postDelayedToSearch: (() -> Unit, Long) -> Unit,
    private val showSearchKeyboard: () -> Unit,
) {
    private var keyboardShowScheduled = false

    fun showKeyboard() {
        requestSearchFocus()
        postToSearch {
            if (searchHasFocus() && searchHasWindowFocus()) {
                showSearchKeyboard()
            }
        }
    }

    fun onSearchFocusChanged(hasFocus: Boolean) {
        if (hasFocus || !searchHasFocus()) {
            showKeyboard()
        }
    }

    fun onImeVisibilityChanged(imeVisible: Boolean) {
        if (!imeVisible && searchHasFocus()) {
            scheduleShowKeyboard()
        }
    }

    private fun scheduleShowKeyboard() {
        if (keyboardShowScheduled) {
            return
        }

        keyboardShowScheduled = true
        postDelayedToSearch(
            {
                keyboardShowScheduled = false
                showKeyboard()
            },
            KEYBOARD_RESHOW_DELAY_MILLIS,
        )
    }

    internal companion object {
        const val KEYBOARD_RESHOW_DELAY_MILLIS = 150L
    }
}

internal class SettingsLaunchGate {
    private var lastHandledEnterDownTime = NO_DOWN_TIME

    fun shouldLaunch(action: Int?, keyCode: Int?, repeatCount: Int, downTime: Long?): Boolean {
        if (action == null || keyCode == null) {
            lastHandledEnterDownTime = NO_DOWN_TIME
            return true
        }

        if (keyCode != KeyEvent.KEYCODE_ENTER) {
            lastHandledEnterDownTime = NO_DOWN_TIME
            return true
        }

        return when (action) {
            KeyEvent.ACTION_DOWN -> {
                if (repeatCount > 0) {
                    false
                } else {
                    lastHandledEnterDownTime = downTime ?: NO_DOWN_TIME
                    true
                }
            }
            KeyEvent.ACTION_UP -> {
                val wasHandledOnDown = downTime != null && downTime == lastHandledEnterDownTime
                if (wasHandledOnDown) {
                    lastHandledEnterDownTime = NO_DOWN_TIME
                    false
                } else {
                    true
                }
            }
            else -> false
        }
    }

    private companion object {
        const val NO_DOWN_TIME = Long.MIN_VALUE
    }
}

internal object AgendaEventOrganizer {
    fun forNow(
        events: List<MainActivity.AgendaEvent>,
        nowMillis: Long,
        utcTodayStartMillis: Long,
        utcTomorrowStartMillis: Long,
    ): List<MainActivity.AgendaEvent> {
        val todayAllDayEvents = events.asSequence()
            .filter { event ->
                event.isAllDay &&
                    event.beginMillis < utcTomorrowStartMillis &&
                    event.endMillis > utcTodayStartMillis
            }
            .sortedBy { event -> event.beginMillis }
            .toList()
        val intersectingOrUpcoming = events.asSequence()
            .filter { event ->
                !event.isAllDay &&
                    event.endMillis >= nowMillis
            }
            .sortedBy { event -> event.beginMillis }
            .toList()
        return todayAllDayEvents + intersectingOrUpcoming
    }
}
