package app.typelauncher

internal data class BuildSourceInfo(
    val branch: String,
    val sha: String,
    val isDirty: Boolean,
    val configuredAtMillis: Long,
) {
    val isLocalBuild: Boolean
        get() = branch.isNotBlank() && sha.isNotBlank() && configuredAtMillis > 0L
}

internal fun buildSourceInfoFromConfig(): BuildSourceInfo? =
    BuildSourceInfo(
        branch = BuildConfig.LOCAL_BUILD_BRANCH,
        sha = BuildConfig.LOCAL_BUILD_SHA,
        isDirty = BuildConfig.LOCAL_BUILD_DIRTY,
        configuredAtMillis = BuildConfig.LOCAL_BUILD_TIME_MILLIS,
    ).takeIf { it.isLocalBuild }

internal fun BuildSourceInfo.displayText(nowMillis: Long = System.currentTimeMillis()): String {
    val displayBranch = branch.substringAfter('/')
    val dirtySuffix = if (isDirty) " (dirty)" else ""
    val relativeTime = relativeAge(configuredAtMillis, nowMillis)
    return "$displayBranch · $sha$dirtySuffix · $relativeTime"
}

private fun relativeAge(startMillis: Long, nowMillis: Long): String {
    val elapsedMinutes = ((nowMillis - startMillis).coerceAtLeast(0L) / MILLIS_PER_MINUTE).coerceAtLeast(0L)
    val (value, unit) = when {
        elapsedMinutes < MINUTES_PER_HOUR -> elapsedMinutes to "minute"
        elapsedMinutes < MINUTES_PER_DAY -> elapsedMinutes / MINUTES_PER_HOUR to "hour"
        else -> elapsedMinutes / MINUTES_PER_DAY to "day"
    }
    val plural = if (value == 1L) unit else "${unit}s"
    return "$value $plural ago"
}

private const val MILLIS_PER_MINUTE = 60_000L
private const val MINUTES_PER_HOUR = 60L
private const val MINUTES_PER_DAY = 24L * MINUTES_PER_HOUR
