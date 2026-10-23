package app.typelauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LaunchBounceDetectorTest {
    private val detector = LaunchBounceDetector()

    private fun launchAt(millis: Long) =
        detector.onLaunchStarted("Work App One", "com.example.app", isWorkApp = true, nowMillis = millis)

    @Test
    fun homeIntentSoonAfterLaunchThatLeftHomeIsABounce() {
        launchAt(1_000)
        detector.onLauncherHidden()
        assertEquals(
            LaunchBounceDetector.Bounce("Work App One", "com.example.app", isWorkApp = true, afterMillis = 958),
            detector.onHomeIntent(1_958),
        )
    }

    @Test
    fun homeIntentAfterTheWindowIsAVisit() {
        launchAt(1_000)
        detector.onLauncherHidden()
        assertNull(detector.onHomeIntent(1_000 + LaunchBounceDetector.BOUNCE_WINDOW_MILLIS + 1))
    }

    @Test
    fun launcherThatNeverLeftTheScreenIsNotABounce() {
        launchAt(1_000)
        assertNull(detector.onHomeIntent(1_500))
    }

    @Test
    fun failedLaunchIsNotABounce() {
        launchAt(1_000)
        detector.onLaunchFailed()
        detector.onLauncherHidden()
        assertNull(detector.onHomeIntent(1_500))
    }

    @Test
    fun aBounceIsReportedOnce() {
        launchAt(1_000)
        detector.onLauncherHidden()
        detector.onHomeIntent(1_500)
        detector.onLauncherHidden()
        assertNull(detector.onHomeIntent(1_600))
    }

    @Test
    fun homeIntentWithNoLaunchIsNothing() {
        detector.onLauncherHidden()
        assertNull(detector.onHomeIntent(1_000))
    }
}
