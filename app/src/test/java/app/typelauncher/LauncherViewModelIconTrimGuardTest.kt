package app.typelauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.test.core.app.ApplicationProvider
import android.os.Looper
import kotlinx.coroutines.CoroutineDispatcher
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.coroutines.CoroutineContext

/**
 * Pins the load-completeness guard on the background icon trim.
 *
 * The trap this covers is that `installedApps` being non-empty does **not** mean the
 * app list is known. `AppMetadataStore` prefills it synchronously during init so the
 * first frame has labels, and that store holds personal-profile apps only -- work apps
 * cannot be rebuilt without a live `LauncherApps`. So between the prefill and the fresh
 * load landing, the list is populated and every work app is missing from it.
 *
 * That matters because `priorityIconCacheIds` filters through `installedApps`, so in
 * that window even a work-docked app's id falls out of the priority set. A trim there
 * would evict precisely the restored work icons the snapshot exists to keep warm, and
 * the user would come back to placeholders on the work dock. Hence the guard is
 * `isFreshAppLoadComplete`, not "is the list non-empty".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LauncherViewModelIconTrimGuardTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun clearPrefs() {
        listOf("docked_apps", "dock_settings", "app_launch_stats", "widgets", "app_metadata")
            .forEach { name ->
                context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
            }
    }

    /** Seeds the metadata store the way a previous session's fresh load would have. */
    private fun seedPersonalMetadata() {
        val entry = JSONObject().apply {
            put("name", "App One")
            put("package", "com.example.personal")
            put("component", "com.example.personal/.Main")
            put("isWorkApp", false)
            put("launchWithLauncherApps", true)
        }
        context.getSharedPreferences("app_metadata", Context.MODE_PRIVATE)
            .edit()
            .putString("apps", JSONArray().put(entry).toString())
            .commit()
    }

    /**
     * Runs the first block handed to it and parks every later one.
     *
     * `init` launches the snapshot restore first and the fresh app load second, and
     * both reach their IO work synchronously through this dispatcher, so "first" and
     * "second" are a strict sequence rather than a race. Running one and parking the
     * other is what holds the view model in the window this test is about: restore
     * finished, fresh load still outstanding. The test asserts it got there, so a
     * future reordering of `init` fails loudly instead of quietly testing nothing.
     */
    private class RunFirstBlockOnlyDispatcher : CoroutineDispatcher() {
        private var seen = 0
        private val parked = mutableListOf<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (seen++ == 0) block.run() else parked += block
        }

        /** Lets the fresh load finish, which is what a deferred trim waits on. */
        fun release() {
            val pending = parked.toList()
            parked.clear()
            pending.forEach { it.run() }
        }
    }

    private fun cacheIcon(id: String, sizePx: Int) {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            .apply { eraseColor(Color.MAGENTA) }
            .asImageBitmap()
        AppIconLoader.put(id, sizePx, bitmap)
    }

    @Test
    fun doesNotTrimWhileTheAppListIsStillTheMetadataPrefill() {
        seedPersonalMetadata()

        // Stands in for a work icon the snapshot restore put back into the cache: its
        // id can never appear in a priority set derived from personal-only metadata.
        val restoredWorkIcon = "10:com.example.work.trimguard/Main"
        cacheIcon(restoredWorkIcon, 56)

        val viewModel = LauncherViewModel(
            app = ApplicationProvider.getApplicationContext(),
            workPackages = emptySet(),
            ioDispatcher = RunFirstBlockOnlyDispatcher(),
        )

        // Lets the restore's continuation run -- it resumes on the main dispatcher
        // after its IO block, and that is where `iconSnapshotRestoreComplete` is set.
        // The fresh load is untouched by this: its IO block is the parked one, so it
        // never reaches the state update that would flip `isFreshAppLoadComplete`.
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(
            "the test must observe the pre-fresh-load window to mean anything",
            viewModel.uiState.value.isFreshAppLoadComplete,
        )

        viewModel.trimIconCacheToPriority()

        assertNotNull(
            "a trim before the fresh load completes would drop every work icon, " +
                "because the metadata prefill's app list has none to keep",
            AppIconLoader.cached(restoredWorkIcon, 56),
        )
    }

    @Test
    fun aDeferredTrimRunsOnceTheFreshLoadLands() {
        // Skipping the trim must not mean losing it. Leaving the launcher during its
        // own startup is exactly when the process then sits in the background and
        // cached states Play measures, and neither onStop nor the UI-hidden callback
        // comes round a second time -- so without the retry the cache would keep its
        // full foreground footprint for the whole residency.
        seedPersonalMetadata()
        val io = RunFirstBlockOnlyDispatcher()
        val viewModel = LauncherViewModel(
            app = ApplicationProvider.getApplicationContext(),
            workPackages = emptySet(),
            ioDispatcher = io,
        )
        shadowOf(Looper.getMainLooper()).idle()

        viewModel.trimIconCacheToPriority()
        assertTrue(
            "the test must observe a deferral to mean anything: " +
                "${LauncherDebugLog.snapshot()}",
            LauncherDebugLog.snapshot().any { it.contains("trimIconCacheToPriority deferred") },
        )

        LauncherDebugLog.resetForTest()
        io.release()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(
            "the fresh load landing must retry the deferred trim: " +
                "${LauncherDebugLog.snapshot()}",
            LauncherDebugLog.snapshot().any { it.contains("runDeferredBackgroundTrim retrying") },
        )
    }

    @Test
    fun comingBackBeforeTheLoadLandsCancelsTheDeferredTrim() {
        // The other half: a user who returns while the load is still in flight must
        // not have their visible launcher trimmed a moment later.
        seedPersonalMetadata()
        val io = RunFirstBlockOnlyDispatcher()
        val viewModel = LauncherViewModel(
            app = ApplicationProvider.getApplicationContext(),
            workPackages = emptySet(),
            ioDispatcher = io,
        )
        shadowOf(Looper.getMainLooper()).idle()
        viewModel.trimIconCacheToPriority()

        viewModel.onLauncherVisible()
        LauncherDebugLog.resetForTest()
        io.release()
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(
            "returning to the launcher cancels the deferred trim: " +
                "${LauncherDebugLog.snapshot()}",
            LauncherDebugLog.snapshot().any { it.contains("runDeferredBackgroundTrim retrying") },
        )
    }
}
