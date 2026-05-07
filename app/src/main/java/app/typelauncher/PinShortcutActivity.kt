package app.typelauncher

import android.app.Activity
import android.content.pm.LauncherApps
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.getSystemService

class PinShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val launcherApps = getSystemService<LauncherApps>()
        val request = launcherApps?.getPinItemRequest(intent)
        val accepted = request?.requestType == LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT &&
            request.isValid &&
            request.accept()
        if (accepted) {
            val label = request.shortcutInfo.shortLabel?.toString()?.takeIf { it.isNotBlank() }
            Toast.makeText(
                this,
                if (label == null) "Added shortcut" else "Added $label",
                Toast.LENGTH_SHORT,
            ).show()
            LauncherDebugLog.event("PinShortcutActivity accepted shortcut package=${request.shortcutInfo.`package`}")
        } else {
            LauncherDebugLog.event("PinShortcutActivity ignored invalid pin request")
        }
        finish()
    }
}
