package app.typelauncher

/**
 * Notices a tap on a work-profile widget that opened nothing.
 *
 * When the work profile comes back (unpaused, or unlocked), its widgets are
 * rebuilt from the content Android saved before the profile stopped. The tap
 * actions in that content can be dead until the widget's app sends fresh
 * content, and Android drops a dead tap without telling the launcher: a
 * report showed the work Calendar widget ignoring taps for about seven
 * seconds after an unlock. The platform hook that would say whether a tap
 * worked is hidden from apps, so this goes by what the launcher can see: a
 * tap that didn't move or long-press, followed by the launcher not pausing.
 *
 * A tap that legitimately stays in place (a list scroll, a toggle) looks the
 * same, so [DeadTap.stale] only marks one that landed soon after the profile
 * came back, on a widget that hasn't sent content since it was rebuilt.
 *
 * Plain Kotlin over caller-supplied `elapsedRealtime` times so it tests on
 * the JVM. Main thread only.
 */
internal class WorkWidgetTapCheck(
    private val staleWindowMillis: Long = STALE_WINDOW_MILLIS,
) {
    data class DeadTap(
        val widgetId: Int,
        /** Time from the work profile coming back to the tap; null if not seen this process. */
        val sinceProfileAvailableMillis: Long?,
        /** The widget's app has sent content since its view was built. */
        val providerUpdated: Boolean,
        /** Likely dead: soon after the profile came back, on content from before it. */
        val stale: Boolean,
    )

    private var profileAvailableAtMillis: Long? = null
    private var pending: DeadTap? = null

    /** The work profile became available or unlocked. */
    fun onWorkProfileAvailable(nowMillis: Long) {
        profileAvailableAtMillis = nowMillis
    }

    fun sinceProfileAvailableMillis(nowMillis: Long): Long? = profileAvailableAtMillis?.let { nowMillis - it }

    /** A tap landed on a work-profile widget. Replaces any earlier unresolved tap. */
    fun onTap(widgetId: Int, providerUpdated: Boolean, nowMillis: Long) {
        val since = sinceProfileAvailableMillis(nowMillis)
        val stale = !providerUpdated && since != null && since in 0..staleWindowMillis
        pending = DeadTap(widgetId, since, providerUpdated, stale)
    }

    /** The launcher paused, so the tap opened something. */
    fun onLauncherPaused() {
        pending = null
    }

    /**
     * The check delay after a tap passed. Returns the tap if the launcher
     * never paused; either way the tap is used up.
     */
    fun onCheckDue(): DeadTap? {
        val tap = pending
        pending = null
        return tap
    }

    companion object {
        /** How long after the profile comes back a widget's saved content is suspect. */
        const val STALE_WINDOW_MILLIS = 30_000L

        /** A tap that opens an activity pauses the launcher within about 100 ms. */
        const val CHECK_DELAY_MILLIS = 500L
    }
}
