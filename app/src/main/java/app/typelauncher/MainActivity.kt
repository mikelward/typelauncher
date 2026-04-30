package app.typelauncher

import android.content.Intent
import android.os.Bundle
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
        appSearchInput.post {
            getSystemService<InputMethodManager>()
                ?.showSoftInput(appSearchInput, InputMethodManager.SHOW_IMPLICIT)
        }

        findViewById<ListView>(R.id.installed_apps_list).adapter = ArrayAdapter(
            this,
            R.layout.installed_app_list_item,
            installedAppNames(),
        )
    }

    private fun installedAppNames(): List<String> {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(launcherIntent, 0)
            .map { resolveInfo -> resolveInfo.loadLabel(packageManager).toString() }
            .distinct()
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
    }
}
