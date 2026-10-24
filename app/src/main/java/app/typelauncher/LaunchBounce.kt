package app.typelauncher

/**
 * Notices an app launch that came straight back to Home: the start call
 * succeeded and the launcher left the screen, but a Home intent arrived
 * before the app could plausibly have been used. A work app whose entry
 * activity closed without showing anything looked exactly like this, and to
 * the user like a tap that did nothing.
 *
 * Plain Kotlin over caller-supplied `elapsedRealtime` times so it tests on
 * the JVM. Main thread only.
 */
internal class LaunchBounceDetector(
    private val windowMillis: Long = BOUNCE_WINDOW_MILLIS,
) {
    data class Bounce(
        val label: String,
        val loggedPackage: String,
        val isWorkApp: Boolean,
        val afterMillis: Long,
        val workProfile: WorkProfileState? = null,
        /** Tap to the launcher leaving the screen: how long the app took to cover Home. */
        val hiddenAfterMillis: Long? = null,
    ) {
        /**
         * The launch went into a paused work profile: the one case worth a
         * toast. A Home press inside the window reads exactly like any other
         * bounce, so those only reach the log.
         */
        val wasIntoPausedProfile: Boolean get() = workProfile == WorkProfileState.PAUSED
    }

    private class Pending(
        val label: String,
        val loggedPackage: String,
        val isWorkApp: Boolean,
        val atMillis: Long,
        var hiddenAtMillis: Long? = null,
        var workProfile: WorkProfileState? = null,
    )

    private var pending: Pending? = null

    /** Called as a tap starts an app, before the start call goes out. */
    fun onLaunchStarted(label: String, loggedPackage: String, isWorkApp: Boolean, nowMillis: Long) {
        pending = Pending(label, loggedPackage, isWorkApp, nowMillis)
    }

    /** The start call went out; [workProfile] is the work profile's state as it did. */
    fun onLaunchSucceeded(workProfile: WorkProfileState?) {
        pending?.workProfile = workProfile
    }

    /** The start call was refused; that path reports its own failure. */
    fun onLaunchFailed() {
        pending = null
    }

    /** The launcher stopped being visible, so the app did take the screen. */
    fun onLauncherHidden(nowMillis: Long) {
        val launch = pending ?: return
        if (launch.hiddenAtMillis == null) launch.hiddenAtMillis = nowMillis
    }

    /**
     * A Home intent reached the launcher. Returns the launch it bounced, if
     * any; either way the pending launch is used up.
     */
    fun onHomeIntent(nowMillis: Long): Bounce? {
        val launch = pending ?: return null
        pending = null
        val after = nowMillis - launch.atMillis
        val hiddenAt = launch.hiddenAtMillis ?: return null
        if (after < 0 || after > windowMillis) return null
        return Bounce(
            launch.label,
            launch.loggedPackage,
            launch.isWorkApp,
            after,
            launch.workProfile,
            hiddenAfterMillis = hiddenAt - launch.atMillis,
        )
    }

    companion object {
        // Both reported bounces landed 1.0–1.3 s after the tap. Two seconds
        // leaves room for a slow start while staying short of a real visit;
        // the cost of a miss either way is one toast or one missing toast.
        const val BOUNCE_WINDOW_MILLIS = 2_000L
    }
}

/**
 * A work profile's state as a launch into it goes out, for the log: whether a
 * work app that bounced was tapped into a paused or locked profile.
 */
internal enum class WorkProfileState(val logValue: String) {
    RUNNING("running"),
    PAUSED("paused"),
    LOCKED("locked"),
    UNKNOWN("unknown"),
    ;

    companion object {
        /** `null` for a read that failed. Paused wins: a paused profile also reads locked. */
        fun of(quietMode: Boolean?, unlocked: Boolean?): WorkProfileState = when {
            quietMode == null || unlocked == null -> UNKNOWN
            quietMode -> PAUSED
            !unlocked -> LOCKED
            else -> RUNNING
        }
    }
}
