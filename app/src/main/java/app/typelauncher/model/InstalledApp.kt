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
    val isHidden: Boolean = false,
    val category: AppCategory = AppCategory.Other,
) {
    val id: String
        get() = "${user.hashCode()}:${launchIntent.component?.flattenToString() ?: packageName}"

    // FLAG_ACTIVITY_CLEAR_TASK: if Settings is already running on a different page
    // (e.g. Bluetooth), NEW_TASK alone reuses that task and shows the page on top
    // instead of App Info. CLEAR_TASK resets the task so we always land on App Info.
    val appInfoIntent: Intent
        get() = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:$packageName"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

    override fun toString(): String = name
}
