package app.typelauncher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Process
import android.os.UserHandle
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Persists a small metadata snapshot for the personal-profile installed apps so the
 * dock and the app list can render on the very first frame after a cold start, ahead
 * of the IO load that calls into `LauncherApps`. Work-profile apps aren't cached
 * because reconstructing the corresponding `UserHandle` requires the live system, and
 * those apps re-appear once the fresh load finishes.
 */
internal class AppMetadataStore(context: Context) {
    private val sharedPreferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): List<InstalledApp> {
        val raw = sharedPreferences.getString(KEY_APPS, null) ?: return emptyList()
        val personal = Process.myUserHandle()
        return try {
            val array = JSONArray(raw)
            buildList(array.length()) {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val component = ComponentName.unflattenFromString(obj.getString(KEY_COMPONENT))
                        ?: continue
                    add(
                        InstalledApp(
                            name = obj.getString(KEY_NAME),
                            packageName = obj.getString(KEY_PACKAGE),
                            launchIntent = Intent.makeMainActivity(component),
                            user = personal,
                            isWorkApp = obj.optBoolean(KEY_IS_WORK_APP, false),
                            launchWithLauncherApps = obj.optBoolean(KEY_LAUNCH_WITH_LAUNCHER_APPS, true),
                            iconCacheToken = obj.optString(KEY_ICON_CACHE_TOKEN).takeIf { it.isNotEmpty() },
                        ),
                    )
                }
            }
        } catch (_: JSONException) {
            emptyList()
        }
    }

    fun save(apps: List<InstalledApp>) {
        val personal = Process.myUserHandle()
        val array = JSONArray()
        for (app in apps) {
            if (!isPersonal(app.user, personal)) continue
            val component = app.launchIntent.component ?: continue
            val obj = JSONObject().apply {
                put(KEY_NAME, app.name)
                put(KEY_PACKAGE, app.packageName)
                put(KEY_COMPONENT, component.flattenToString())
                put(KEY_IS_WORK_APP, app.isWorkApp)
                put(KEY_LAUNCH_WITH_LAUNCHER_APPS, app.launchWithLauncherApps)
                app.iconCacheToken?.let { put(KEY_ICON_CACHE_TOKEN, it) }
            }
            array.put(obj)
        }
        sharedPreferences.edit()
            .putString(KEY_APPS, array.toString())
            .apply()
    }

    private fun isPersonal(user: UserHandle, personal: UserHandle): Boolean = user == personal

    private companion object {
        const val PREFERENCES_NAME = "app_metadata"
        const val KEY_APPS = "apps"
        const val KEY_NAME = "name"
        const val KEY_PACKAGE = "package"
        const val KEY_COMPONENT = "component"
        const val KEY_IS_WORK_APP = "isWorkApp"
        const val KEY_LAUNCH_WITH_LAUNCHER_APPS = "launchWithLauncherApps"
        const val KEY_ICON_CACHE_TOKEN = "iconCacheToken"
    }
}
