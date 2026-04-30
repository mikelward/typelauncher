package app.typelauncher

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ListView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.getSystemService

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val appSearchInput = findViewById<EditText>(R.id.app_search_input)
        appSearchInput.requestFocus()
        appSearchInput.setOnEditorActionListener { _, actionId, event ->
            val isSearchAction = actionId == EditorInfo.IME_ACTION_SEARCH
            val isEnterKey = event?.keyCode == KeyEvent.KEYCODE_ENTER

            if (!isSearchAction && !isEnterKey) {
                return@setOnEditorActionListener false
            }

            if (!appSearchInput.text.toString().trim().equals(SETTINGS_QUERY, ignoreCase = true)) {
                return@setOnEditorActionListener false
            }

            if (event == null || event.action == KeyEvent.ACTION_UP) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
            true
        }
        appSearchInput.post {
            getSystemService<InputMethodManager>()
                ?.showSoftInput(appSearchInput, InputMethodManager.SHOW_IMPLICIT)
        }

        val installedApps = installedApps()
        findViewById<ListView>(R.id.installed_apps_list).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                R.layout.installed_app_list_item,
                installedApps.map { app -> app.name },
            )
            setOnItemClickListener { _, _, position, _ ->
                startActivity(installedApps[position].launchIntent)
            }
        }
    }

    private fun installedApps(): List<InstalledApp> {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(launcherIntent, 0)
            .map { resolveInfo ->
                val activityInfo = resolveInfo.activityInfo
                InstalledApp(
                    name = resolveInfo.loadLabel(packageManager).toString(),
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
        val launchIntent: Intent,
    )

    private companion object {
        const val SETTINGS_QUERY = "settings"
    }
}
