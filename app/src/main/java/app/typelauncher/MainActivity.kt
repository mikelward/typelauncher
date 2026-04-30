package app.typelauncher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.getSystemService
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import java.util.LinkedHashSet
import kotlin.math.max

class MainActivity : AppCompatActivity() {
    internal var latestAppMenu: PopupMenu? = null
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val root = findViewById<View>(R.id.main_root)
        val appSearchInput = findViewById<EditText>(R.id.app_search_input)
        val appSearchClearButton = findViewById<ImageButton>(R.id.app_search_clear_button)
        val settingsLaunchGate = SettingsLaunchGate()
        val dockedAppStore = DockedAppStore(this)
        appSearchInput.requestFocus()
        appSearchInput.post {
            getSystemService<InputMethodManager>()
                ?.showSoftInput(appSearchInput, InputMethodManager.SHOW_IMPLICIT)
        }

        val installedApps = installedApps()
        val filteredApps = installedApps.toMutableList()
        val filteredDockedApps = installedApps.filterDockedByName(dockedAppStore.dockedAppIds, "").toMutableList()
        val installedAppNamesAdapter = InstalledAppsAdapter(
            this@MainActivity,
            filteredApps.map { app -> app.name }.toMutableList(),
        )
        val installedAppsCard = findViewById<LinearLayout>(R.id.installed_apps_card)
        val dockedAppsCard = findViewById<LinearLayout>(R.id.docked_apps_card)
        val dockedAppsHint = findViewById<TextView>(R.id.docked_apps_hint)
        val dockedAppsList = findViewById<LinearLayout>(R.id.docked_apps_list)
        val baseTop = root.paddingTop
        val baseBottom = root.paddingBottom
        fun refreshLists(query: String) {
            filteredApps.replaceWith(installedApps.filterByName(query))
            filteredDockedApps.replaceWith(installedApps.filterDockedByName(dockedAppStore.dockedAppIds, query))
            installedAppNamesAdapter.replaceWith(filteredApps.map { app -> app.name })
            renderDockedApps(
                dockedApps = filteredDockedApps,
                dockedAppsRow = dockedAppsList,
                appSearchInput = appSearchInput,
                dockedAppStore = dockedAppStore,
                afterDockChanged = { refreshLists(appSearchInput.text.toString().trim()) },
            )
            dockedAppsHint.isVisible = filteredDockedApps.isEmpty()
            dockedAppsList.isVisible = filteredDockedApps.isNotEmpty()
            installedAppsCard.requestLayout()
            dockedAppsCard.requestLayout()
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
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
                launchActiveApp(filteredApps, appSearchInput.text.toString(), appSearchInput)
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
                launchAndClearQuery(filteredApps[position].launchIntent, appSearchInput)
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

    private fun installedApps(): List<InstalledApp> {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(launcherIntent, 0)
            .map { resolveInfo ->
                val activityInfo = resolveInfo.activityInfo
                InstalledApp(
                    name = resolveInfo.loadLabel(packageManager).toString(),
                    packageName = activityInfo.packageName,
                    launchIntent = Intent.makeMainActivity(
                        ComponentName(activityInfo.packageName, activityInfo.name),
                    ),
                    icon = resolveInfo.loadIcon(packageManager),
                )
            }
            .distinctBy { app -> app.name }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { app -> app.name })
    }

    private data class InstalledApp(
        val name: String,
        val packageName: String,
        val launchIntent: Intent,
        val icon: Drawable,
    ) {
        val id: String
            get() = launchIntent.component?.flattenToString() ?: packageName

        val appInfoIntent: Intent
            get() = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:$packageName"))
    }

    private fun List<InstalledApp>.filterByName(query: String): List<InstalledApp> =
        if (query.isEmpty()) {
            this
        } else {
            filter { app -> app.name.contains(query, ignoreCase = true) }
        }

    private fun List<InstalledApp>.filterDockedByName(dockedAppIds: List<String>, query: String): List<InstalledApp> =
        filter { app -> app.id in dockedAppIds }
            .filterByName(query)
            .sortedBy { app -> dockedAppIds.indexOf(app.id) }

    private fun MutableList<InstalledApp>.replaceWith(apps: List<InstalledApp>) {
        clear()
        addAll(apps)
    }

    private fun ArrayAdapter<String>.replaceWith(appNames: List<String>) {
        clear()
        addAll(appNames)
    }

    private fun launchActiveApp(
        filteredApps: List<InstalledApp>,
        query: String,
        appSearchInput: EditText,
    ) {
        if (query.trim().equals(SETTINGS_QUERY, ignoreCase = true)) {
            launchAndClearQuery(Intent(Settings.ACTION_SETTINGS), appSearchInput)
            return
        }
        filteredApps.firstOrNull()?.launchIntent?.let { intent ->
            launchAndClearQuery(intent, appSearchInput)
        }
    }

    private fun launchAndClearQuery(intent: Intent, appSearchInput: EditText) {
        startActivity(intent.asLauncherTaskIntent())
        appSearchInput.text?.clear()
    }

    private fun Intent.asLauncherTaskIntent(): Intent =
        Intent(this).addFlags(LAUNCHER_TASK_FLAGS)

    private fun renderDockedApps(
        dockedApps: List<InstalledApp>,
        dockedAppsRow: LinearLayout,
        appSearchInput: EditText,
        dockedAppStore: DockedAppStore,
        afterDockChanged: () -> Unit,
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
                    launchAndClearQuery(app.launchIntent, appSearchInput)
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
                                R.string.docked_apps_limit_message,
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
    }

    private class InstalledAppsAdapter(
        context: android.content.Context,
        appNames: MutableList<String>,
    ) : ArrayAdapter<String>(context, R.layout.installed_app_list_item, appNames) {
        private val inflater = LayoutInflater.from(context)
        private val activeBackgroundColor = context.getColor(R.color.active_app_background)
        private val defaultBackgroundColor = context.getColor(android.R.color.white)

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: inflater.inflate(R.layout.installed_app_list_item, parent, false)
            val textView = view.findViewById<TextView>(android.R.id.text1)
            textView.text = getItem(position).orEmpty()
            view.setBackgroundColor(if (position == ACTIVE_APP_POSITION) activeBackgroundColor else defaultBackgroundColor)
            return view
        }

        private companion object {
            const val ACTIVE_APP_POSITION = 0
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
