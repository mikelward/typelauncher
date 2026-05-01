package app.typelauncher

import android.content.Intent
import android.net.Uri
import android.os.UserHandle
import android.provider.Settings

internal data class InstalledApp(
    val name: String,
    val packageName: String,
    val launchIntent: Intent,
    val user: UserHandle,
    val isWorkApp: Boolean,
    val launchWithLauncherApps: Boolean,
    val isDocked: Boolean = false,
) {
    val id: String
        get() = "${user.hashCode()}:${launchIntent.component?.flattenToString() ?: packageName}"

    val appInfoIntent: Intent
        get() = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:$packageName"))
            .asLauncherTaskIntent()

    override fun toString(): String = name
}
