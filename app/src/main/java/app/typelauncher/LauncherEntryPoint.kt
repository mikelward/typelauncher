package app.typelauncher

import android.content.Intent

internal enum class LauncherEntryPoint {
    HomeScreen,
    AppIcon,
    Other,
}

internal fun Intent?.launcherEntryPoint(): LauncherEntryPoint =
    when {
        this?.action == Intent.ACTION_MAIN && hasCategory(Intent.CATEGORY_HOME) -> LauncherEntryPoint.HomeScreen
        this?.action == Intent.ACTION_MAIN && hasCategory(Intent.CATEGORY_LAUNCHER) -> LauncherEntryPoint.AppIcon
        else -> LauncherEntryPoint.Other
    }
