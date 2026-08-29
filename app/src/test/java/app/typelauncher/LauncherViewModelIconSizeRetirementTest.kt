package app.typelauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Looper
import androidx.compose.ui.graphics.asImageBitmap
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.coroutines.CoroutineContext

/**
 * Covers retiring the icon-cache keys a rendered-size change orphaned.
 *
 * A layout or icon-size change leaves the old sizes cached and unreadable: nothing will
 * ask for them again, but they still occupy the cache's byte budget, so leaving them
 * lets the LRU evict icons that are on screen at the *new* size to make room. Retiring
 * only the sizes the change actually orphaned is what keeps that from trading a
 * stale-byte problem for a visible one.
 *
 * These drive the real entry point (`onRenderedIconSizes`) rather than the private
 * worker, so deleting the call fails the suite.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LauncherViewModelIconSizeRetirementTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun clearLog() = LauncherDebugLog.clearForTest()

    @After
    fun tearDown() {
        LauncherDebugLog.clearForTest()
        listOf("docked_apps", "dock_settings", "app_launch_stats", "widgets", "app_metadata")
            .forEach { context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
    }

    private fun newViewModel(ioDispatcher: CoroutineDispatcher = Dispatchers.Unconfined) =
        LauncherViewModel(
            app = ApplicationProvider.getApplicationContext(),
            workPackages = emptySet(),
            ioDispatcher = ioDispatcher,
        ).also { shadowOf(Looper.getMainLooper()).idle() }

    /**
     * Runs blocks inline until [hold] is set, then queues them until [release].
     *
     * The retirement race needs a job to still be waiting on the dispatcher while the
     * sizes move underneath it. Holding the dispatcher makes that ordering explicit and
     * deterministic, rather than trying to hit it by timing.
     */
    private class HoldableDispatcher : CoroutineDispatcher() {
        var hold = false
        private val queued = mutableListOf<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (hold) queued += block else block.run()
        }

        fun release() {
            val pending = queued.toList()
            queued.clear()
            pending.forEach { it.run() }
        }
    }

    /** Puts a bitmap straight into the cache, standing in for a rendered icon. */
    private fun seedAt(id: String, sizePx: Int) {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            .apply { eraseColor(Color.MAGENTA) }
            .asImageBitmap()
        AppIconLoader.put(id, sizePx, bitmap)
    }

    /** Stands in for the UI reporting what it renders. */
    private fun LauncherViewModel.reportSizes(
        listPx: Int = 40,
        dockPx: Int = 132,
        folderPx: Int = 56,
    ) = onRenderedIconSizes(listPx = listPx, dockPx = dockPx, folderPx = folderPx)

    @Test
    fun aSizeChangeRetiresTheOrphanedSize() {
        // Nothing reads the old size again, but it still counts against the budget --
        // so left resident it evicts icons that are on screen at the new one.
        val viewModel = newViewModel()
        viewModel.onLauncherVisible()
        viewModel.reportSizes(listPx = 40, dockPx = 132, folderPx = 56)
        seedAt("0:com.example.sizes.old/Main", sizePx = 40)
        assertNotNull(
            "precondition: seeded at the old size",
            AppIconLoader.cached("0:com.example.sizes.old/Main", 40),
        )

        viewModel.reportSizes(listPx = 64, dockPx = 132, folderPx = 56)

        assertNull(
            "the orphaned size must be retired, not left occupying the budget",
            AppIconLoader.cached("0:com.example.sizes.old/Main", 40),
        )
    }

    @Test
    fun aSizeThatBecameCurrentAgainIsNotEvicted() {
        // Dragging the icon-size slider off a notch and back reports A -> B -> A, which
        // leaves a queued job holding A's sizes as retired while A is current again and
        // its icons have reloaded. Evicting them then drops what is on screen -- and a
        // stop landing in that window snapshots the gap, so the placeholders survive
        // into the next cold start. The warm-up used to refill behind this; nothing does
        // now, so the job has to re-check the live tuple before it evicts.
        val io = HoldableDispatcher()
        val viewModel = newViewModel(io)
        viewModel.onLauncherVisible()
        viewModel.reportSizes(listPx = 40)

        io.hold = true
        viewModel.reportSizes(listPx = 64)
        viewModel.reportSizes(listPx = 40)
        shadowOf(Looper.getMainLooper()).idle()

        // The reloads that happen once 40 is on screen again, before the stale job runs.
        seedAt("0:com.example.sizes.reloaded/Main", sizePx = 40)
        seedAt("0:com.example.sizes.orphaned/Main", sizePx = 64)
        io.release()

        assertNotNull(
            "a size that is current again must survive the stale retirement job",
            AppIconLoader.cached("0:com.example.sizes.reloaded/Main", 40),
        )
        assertNull(
            "the size that really was orphaned is still evicted",
            AppIconLoader.cached("0:com.example.sizes.orphaned/Main", 64),
        )
    }

    @Test
    fun aSizeStillInUseIsNotRetired() {
        // Only sizes this tuple orphaned are dropped. The dock size is unchanged here,
        // and its icons are on screen -- evicting them would trade a stale-byte problem
        // for a visible one.
        val viewModel = newViewModel()
        viewModel.onLauncherVisible()
        viewModel.reportSizes(listPx = 40, dockPx = 132, folderPx = 56)
        seedAt("0:com.example.sizes.dock/Main", sizePx = 132)

        viewModel.reportSizes(listPx = 64, dockPx = 132, folderPx = 56)

        assertNotNull(
            "an unchanged size is still rendered and must survive",
            AppIconLoader.cached("0:com.example.sizes.dock/Main", 132),
        )
    }
}
