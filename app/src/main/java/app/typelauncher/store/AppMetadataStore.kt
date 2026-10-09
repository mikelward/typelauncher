package app.typelauncher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Process
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Persists a small metadata snapshot for the personal-profile installed apps (and
 * pinned shortcuts and web links) so the
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
                    val packageName = obj.getString(KEY_PACKAGE)
                    // A web link is rebuilt as the launcher builds it, so the
                    // snapshot can't drift from what a load produces.
                    val webLinkId = obj.optString(KEY_WEB_LINK_ID).takeIf { it.isNotEmpty() }
                    val webLinkUrl = obj.optString(KEY_URL).takeIf { it.isNotEmpty() }
                    if (webLinkId != null) {
                        if (webLinkUrl != null) add(webLinkEntry(WebLink(webLinkId, obj.getString(KEY_NAME), webLinkUrl), personal))
                        continue
                    }
                    // A pinned shortcut is cached so it renders on the first
                    // frame like the apps around it; its component, when the
                    // system recorded one, is its publisher's activity.
                    val shortcutId = obj.optString(KEY_SHORTCUT_ID).takeIf { it.isNotEmpty() }
                    val component = ComponentName.unflattenFromString(obj.optString(KEY_COMPONENT))
                    val launchIntent = when {
                        component != null -> Intent.makeMainActivity(component)
                        shortcutId != null -> Intent().setPackage(packageName)
                        else -> continue
                    }
                    add(
                        InstalledApp(
                            name = obj.getString(KEY_NAME),
                            packageName = packageName,
                            launchIntent = launchIntent,
                            user = personal,
                            isWorkApp = obj.optBoolean(KEY_IS_WORK_APP, false),
                            launchWithLauncherApps = obj.optBoolean(KEY_LAUNCH_WITH_LAUNCHER_APPS, true),
                            iconCacheToken = obj.optString(KEY_ICON_CACHE_TOKEN).takeIf { it.isNotEmpty() },
                            // Defaults to true for a snapshot written before
                            // this key existed: the app list it came from is
                            // mostly ordinary apps, and the worst a wrong
                            // optimistic value costs is one Uninstall item on
                            // a system app until the live load replaces it.
                            isUninstallable = obj.optBoolean(KEY_IS_UNINSTALLABLE, true),
                            disambiguator = obj.optString(KEY_DISAMBIGUATOR).takeIf { it.isNotEmpty() },
                            shortcutId = shortcutId,
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
            if (app.user != personal) continue
            val webLinkId = app.webLinkId
            if (webLinkId != null) {
                val url = app.launchIntent.dataString ?: continue
                array.put(JSONObject().put(KEY_NAME, app.name).put(KEY_PACKAGE, app.packageName).put(KEY_WEB_LINK_ID, webLinkId).put(KEY_URL, url))
                continue
            }
            val component = app.launchIntent.component
            if (component == null && app.shortcutId == null) continue
            val obj = JSONObject().apply {
                put(KEY_NAME, app.name)
                put(KEY_PACKAGE, app.packageName)
                component?.let { put(KEY_COMPONENT, it.flattenToString()) }
                app.shortcutId?.let { put(KEY_SHORTCUT_ID, it) }
                put(KEY_IS_WORK_APP, app.isWorkApp)
                put(KEY_LAUNCH_WITH_LAUNCHER_APPS, app.launchWithLauncherApps)
                put(KEY_IS_UNINSTALLABLE, app.isUninstallable)
                app.iconCacheToken?.let { put(KEY_ICON_CACHE_TOKEN, it) }
                app.disambiguator?.takeIf { it.isNotEmpty() }?.let { put(KEY_DISAMBIGUATOR, it) }
            }
            array.put(obj)
        }
        sharedPreferences.edit()
            .putString(KEY_APPS, array.toString())
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "app_metadata"
        const val KEY_APPS = "apps"
        const val KEY_NAME = "name"
        const val KEY_PACKAGE = "package"
        const val KEY_COMPONENT = "component"
        const val KEY_IS_WORK_APP = "isWorkApp"
        const val KEY_LAUNCH_WITH_LAUNCHER_APPS = "launchWithLauncherApps"
        const val KEY_IS_UNINSTALLABLE = "isUninstallable"
        const val KEY_ICON_CACHE_TOKEN = "iconCacheToken"
        const val KEY_DISAMBIGUATOR = "disambiguator"
        const val KEY_SHORTCUT_ID = "shortcutId"
        const val KEY_WEB_LINK_ID = "webLinkId"
        const val KEY_URL = "url"
    }
}
