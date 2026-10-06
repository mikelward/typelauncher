package app.typelauncher

import java.time.ZoneId

/**
 * A short history of what changed the app list — apps installed, removed,
 * made available or unavailable, and each reload that followed, with the app
 * count it produced — kept apart from the main debug log for bug reports.
 *
 * The main log is a ring of a few hundred lines, and an ordinary hour of
 * opening and leaving the launcher fills it with lifecycle lines; by the time
 * someone reports "the app I just installed isn't in the list", the install
 * and the reload it triggered have usually been pushed out. Here they stay:
 * only app-list events are recorded, and a run of identical ones (an app that
 * reports itself changed every minute) collapses into one line with a count.
 *
 * In memory only, for this process: a restart reloads the whole list anyway.
 * Package names are recorded, as the bug report's app list already shows them
 * — the report's consent dialog names "list of installed apps".
 */
internal class AppListEventLog(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private class Entry(val text: String, val firstMillis: Long) {
        var count = 1
        var lastMillis = firstMillis
    }

    private val entries = ArrayDeque<Entry>()

    /** Records [text], or counts it again if it repeats the newest entry. */
    @Synchronized
    fun record(text: String) {
        val now = nowMillis()
        val newest = entries.lastOrNull()
        if (newest != null && newest.text == text) {
            newest.count++
            newest.lastMillis = now
            return
        }
        entries.addLast(Entry(text, now))
        while (entries.size > capacity) entries.removeFirst()
    }

    /** Oldest first, each stamped in [zone]; a repeated entry gives its count and last time. */
    @Synchronized
    fun lines(zone: ZoneId = ZoneId.systemDefault()): List<String> = entries.map { entry ->
        val first = "${formatLogTimestamp(entry.firstMillis, zone)} ${entry.text}"
        if (entry.count == 1) first else "$first (×${entry.count}, last ${formatLogTimestamp(entry.lastMillis, zone)})"
    }

    private companion object {
        const val DEFAULT_CAPACITY = 50
    }
}

/**
 * One package, or a batch summarized as its size and first few names. A work
 * profile turning on or off reports its whole app set in one callback, and
 * listing it all would make one entry big enough to push the rest of the
 * history out of the report's section.
 */
internal fun describePackageBatch(packageNames: List<String>): String = when {
    packageNames.size == 1 -> packageNames.single()
    packageNames.size <= BATCH_NAMES_SHOWN -> "${packageNames.size} packages (${packageNames.joinToString()})"
    else -> "${packageNames.size} packages (${packageNames.take(BATCH_NAMES_SHOWN).joinToString()}, …)"
}

private const val BATCH_NAMES_SHOWN = 3

/** This process's app-list history. See [AppListEventLog]. */
internal val appListEvents = AppListEventLog()
