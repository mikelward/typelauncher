package app.typelauncher

import android.content.ComponentName
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
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.getSystemService
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.max

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        applyImeInsets()

        val appSearchInput = findViewById<EditText>(R.id.app_search_input)
        val settingsLaunchGate = SettingsLaunchGate()
        appSearchInput.requestFocus()
        appSearchInput.post {
            getSystemService<InputMethodManager>()
                ?.showSoftInput(appSearchInput, InputMethodManager.SHOW_IMPLICIT)
        }

        val installedApps = installedApps()
        val filteredApps = installedApps.toMutableList()
        val installedAppNamesAdapter = InstalledAppsAdapter(
            this@MainActivity,
            filteredApps.map { app -> app.name }.toMutableList(),
        )
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
                launchActiveApp(filteredApps, appSearchInput.text.toString())
            }
            true
        }
        findViewById<ListView>(R.id.installed_apps_list).apply {
            adapter = installedAppNamesAdapter
            setOnItemClickListener { _, _, position, _ ->
                startActivity(filteredApps[position].launchIntent)
            }
            setOnItemLongClickListener { _, _, position, _ ->
                startActivity(filteredApps[position].appInfoIntent)
                true
            }
        }
        appSearchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) {
                val query = text?.toString().orEmpty().trim()
                filteredApps.replaceWith(installedApps.filterByName(query))
                installedAppNamesAdapter.clear()
                installedAppNamesAdapter.addAll(filteredApps.map { app -> app.name })
            }

            override fun afterTextChanged(text: Editable?) = Unit
        })
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

    private fun MutableList<InstalledApp>.replaceWith(apps: List<InstalledApp>) {
        clear()
        addAll(apps)
    }

    private fun launchActiveApp(filteredApps: List<InstalledApp>, query: String) {
        if (query.trim().equals(SETTINGS_QUERY, ignoreCase = true)) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
            return
        }
        filteredApps.firstOrNull()?.launchIntent?.let(::startActivity)
    }

    private fun applyImeInsets() {
        val root = findViewById<android.view.View>(R.id.main_root)
        val baseTop = root.paddingTop
        val baseBottom = root.paddingBottom
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
    }

    private companion object {
        const val SETTINGS_QUERY = "settings"
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
