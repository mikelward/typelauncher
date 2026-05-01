package app.typelauncher

import android.content.Intent

internal fun Intent.asLauncherTaskIntent(): Intent =
    Intent(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
