package app.typelauncher

import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.LauncherApps.ShortcutQuery
import android.content.pm.ShortcutInfo
import android.os.Process
import android.os.UserHandle

internal fun LauncherApps.loadPinnedShortcutApps(user: UserHandle): List<InstalledApp> {
    if (!hasShortcutHostPermission()) return emptyList()
    val shortcuts = try {
        getShortcuts(
            ShortcutQuery().setQueryFlags(ShortcutQuery.FLAG_MATCH_PINNED),
            user,
        )
    } catch (exception: RuntimeException) {
        LauncherDebugLog.warning("loadPinnedShortcutApps failed user=${user.hashCode()}", exception)
        return emptyList()
    }
    return shortcuts
        .filter { shortcut -> shortcut.isEnabled }
        .mapNotNull { shortcut -> shortcut.toInstalledApp() }
}

internal fun ShortcutInfo.toInstalledApp(): InstalledApp? {
    val label = longLabel?.toString()?.takeIf { it.isNotBlank() }
        ?: shortLabel?.toString()?.takeIf { it.isNotBlank() }
        ?: return null
    val shortcutIntent = intents?.lastOrNull() ?: intent ?: Intent()
    val user = userHandle
    return InstalledApp(
        name = label,
        packageName = `package`,
        launchIntent = Intent(shortcutIntent),
        user = user,
        isWorkApp = user != Process.myUserHandle(),
        launchWithLauncherApps = false,
        shortcutId = id,
        iconCacheToken = lastChangedTimestamp.toString(),
    )
}
