package app.typelauncher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
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
        val pinnedAppStore = PinnedAppStore(this)
        appSearchInput.requestFocus()
        appSearchInput.post {
            getSystemService<InputMethodManager>()
                ?.showSoftInput(appSearchInput, InputMethodManager.SHOW_IMPLICIT)
        }

        val installedApps = installedApps()
        val filteredApps = installedApps.toMutableList()
        val filteredPinnedApps = installedApps.filterPinnedByName(pinnedAppStore.pinnedAppIds, "").toMutableList()
        val installedAppNamesAdapter = InstalledAppsAdapter(
            this@MainActivity,
            filteredApps.map { app -> app.name }.toMutableList(),
        )
        val pinnedAppNamesAdapter = InstalledAppsAdapter(
            this@MainActivity,
            filteredPinnedApps.map { app -> app.name }.toMutableList(),
        )
        val installedAppsCard = findViewById<LinearLayout>(R.id.installed_apps_card)
        val pinnedAppsCard = findViewById<LinearLayout>(R.id.pinned_apps_card)
        val pinnedAppsList = findViewById<ListView>(R.id.pinned_apps_list)
        val baseTop = root.paddingTop
        val baseBottom = root.paddingBottom
        var usableHeight = 0
        fun updatePinnedHeight() {
            val visibleRows = filteredPinnedApps.size.coerceAtMost(MAX_PINNED_APPS)
            if (visibleRows == 0 || usableHeight == 0) {
                pinnedAppsCard.layoutParams = pinnedAppsCard.layoutParams.apply {
                    height = ViewGroup.LayoutParams.WRAP_CONTENT
                }
                return
            }

            val rowHeight = (PINNED_APP_ROW_HEIGHT_DP * resources.displayMetrics.density).toInt()
            val desiredListHeight = rowHeight * visibleRows
            val cardVerticalPadding = pinnedAppsCard.paddingTop + pinnedAppsCard.paddingBottom
            val maxCardHeight = usableHeight / 2
            val listHeight = minOf(desiredListHeight, maxCardHeight - cardVerticalPadding)
                .coerceAtLeast(0)
            pinnedAppsList.layoutParams = pinnedAppsList.layoutParams.apply {
                height = listHeight
            }
            pinnedAppsCard.layoutParams = pinnedAppsCard.layoutParams.apply {
                height = listHeight + cardVerticalPadding
            }
        }
        fun refreshLists(query: String) {
            filteredApps.replaceWith(installedApps.filterByName(query))
            filteredPinnedApps.replaceWith(installedApps.filterPinnedByName(pinnedAppStore.pinnedAppIds, query))
            installedAppNamesAdapter.replaceWith(filteredApps.map { app -> app.name })
            pinnedAppNamesAdapter.replaceWith(filteredPinnedApps.map { app -> app.name })
            pinnedAppsCard.isVisible = filteredPinnedApps.isNotEmpty()
            updatePinnedHeight()
            installedAppsCard.requestLayout()
            pinnedAppsCard.requestLayout()
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
            usableHeight = (view.height - view.paddingTop - view.paddingBottom).coerceAtLeast(0)
            updatePinnedHeight()
            insets
        }
        root.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            usableHeight = (view.height - view.paddingTop - view.paddingBottom).coerceAtLeast(0)
            updatePinnedHeight()
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
                    pinnedAppStore = pinnedAppStore,
                    afterPinnedChanged = { refreshLists(appSearchInput.text.toString().trim()) },
                )
                true
            }
        }
        pinnedAppsList.apply {
            adapter = pinnedAppNamesAdapter
            setOnItemClickListener { _, _, position, _ ->
                launchAndClearQuery(filteredPinnedApps[position].launchIntent, appSearchInput)
            }
            setOnItemLongClickListener { _, view, position, _ ->
                showAppMenu(
                    anchor = view,
                    app = filteredPinnedApps[position],
                    pinnedAppStore = pinnedAppStore,
                    afterPinnedChanged = { refreshLists(appSearchInput.text.toString().trim()) },
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
                )
            }
            .distinctBy { app -> app.name }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { app -> app.name })
    }

    private data class InstalledApp(
        val name: String,
        val packageName: String,
        val launchIntent: Intent,
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

    private fun List<InstalledApp>.filterPinnedByName(pinnedAppIds: List<String>, query: String): List<InstalledApp> =
        filter { app -> app.id in pinnedAppIds }
            .filterByName(query)
            .sortedBy { app -> pinnedAppIds.indexOf(app.id) }

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
        startActivity(intent)
        appSearchInput.text?.clear()
    }

    private fun showAppMenu(
        anchor: View,
        app: InstalledApp,
        pinnedAppStore: PinnedAppStore,
        afterPinnedChanged: () -> Unit,
    ) {
        val isPinned = pinnedAppStore.contains(app.id)
        PopupMenu(this, anchor).apply {
            latestAppMenu = this
            menu.add(MENU_GROUP_APP_ACTIONS, MENU_ITEM_APP_INFO, 0, getString(R.string.app_menu_app_info))
            menu.add(
                MENU_GROUP_APP_ACTIONS,
                MENU_ITEM_TOGGLE_PIN,
                1,
                getString(if (isPinned) R.string.app_menu_unpin else R.string.app_menu_pin),
            )
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_ITEM_APP_INFO -> {
                        startActivity(app.appInfoIntent)
                        true
                    }
                    MENU_ITEM_TOGGLE_PIN -> {
                        if (isPinned) {
                            pinnedAppStore.unpin(app.id)
                            afterPinnedChanged()
                        } else if (pinnedAppStore.pin(app.id)) {
                            afterPinnedChanged()
                        } else {
                            Toast.makeText(
                                this@MainActivity,
                                R.string.pinned_apps_limit_message,
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

    private companion object {
        const val SETTINGS_QUERY = "settings"
        const val MAX_PINNED_APPS = 4
        const val PINNED_APP_ROW_HEIGHT_DP = 48
        const val MENU_GROUP_APP_ACTIONS = 0
        const val MENU_ITEM_APP_INFO = 1
        const val MENU_ITEM_TOGGLE_PIN = 2
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

    private class PinnedAppStore(context: Context) {
        private val sharedPreferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        private var pinnedIds = sharedPreferences.getString(KEY_PINNED_APP_IDS, "").orEmpty()
            .split(PINNED_APP_ID_SEPARATOR)
            .filter { appId -> appId.isNotBlank() }
            .toCollection(LinkedHashSet())

        val pinnedAppIds: List<String>
            get() = pinnedIds.toList()

        fun contains(appId: String): Boolean = appId in pinnedIds

        fun pin(appId: String): Boolean {
            if (appId in pinnedIds) {
                return true
            }
            if (pinnedIds.size >= MAX_PINNED_APPS) {
                return false
            }
            pinnedIds.add(appId)
            save()
            return true
        }

        fun unpin(appId: String) {
            if (pinnedIds.remove(appId)) {
                save()
            }
        }

        private fun save() {
            sharedPreferences.edit()
                .putString(KEY_PINNED_APP_IDS, pinnedIds.joinToString(PINNED_APP_ID_SEPARATOR))
                .apply()
        }

        private companion object {
            const val PREFERENCES_NAME = "pinned_apps"
            const val KEY_PINNED_APP_IDS = "pinned_app_ids"
            const val PINNED_APP_ID_SEPARATOR = "\n"
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
