package app.typelauncher

import android.os.Handler
import android.os.HandlerThread
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Covers the scheduler behind the notification listener's snapshot refresh.
 * The listener callbacks arrive on the launcher's UI thread and used to call
 * the `activeNotifications` binder read inline — one main-thread IPC per
 * notification posted or removed device-wide. The scheduler must (a) run the
 * refresh on its own handler thread, never the caller's, and (b) collapse a
 * burst of schedules into a single refresh.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NotificationSnapshotSchedulerTest {

    @Test
    fun burstOfSchedulesCoalescesIntoOneRefreshOnTheHandlerThread() {
        val thread = HandlerThread("snapshot-test").apply { start() }
        try {
            val refreshThreads = mutableListOf<Thread>()
            val scheduler = NotificationSnapshotScheduler(
                handler = Handler(thread.looper),
                debounceMs = 50L,
            ) {
                synchronized(refreshThreads) { refreshThreads += Thread.currentThread() }
            }

            repeat(8) { scheduler.schedule() }
            shadowOf(thread.looper).idleFor(Duration.ofMillis(50))

            synchronized(refreshThreads) {
                assertEquals("a burst must coalesce into one refresh", 1, refreshThreads.size)
                assertEquals(
                    "the refresh must run on the handler thread, not the caller's",
                    thread,
                    refreshThreads.single(),
                )
            }
        } finally {
            thread.quitSafely()
        }
    }

    @Test
    fun separateBurstsRefreshSeparately() {
        val thread = HandlerThread("snapshot-test-2").apply { start() }
        try {
            var refreshes = 0
            val scheduler = NotificationSnapshotScheduler(
                handler = Handler(thread.looper),
                debounceMs = 50L,
            ) { refreshes += 1 }

            scheduler.schedule()
            shadowOf(thread.looper).idleFor(Duration.ofMillis(50))
            scheduler.schedule()
            shadowOf(thread.looper).idleFor(Duration.ofMillis(50))

            assertEquals(2, refreshes)
        } finally {
            thread.quitSafely()
        }
    }
}
