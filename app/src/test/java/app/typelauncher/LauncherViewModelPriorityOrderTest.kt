package app.typelauncher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineDispatcher
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.coroutines.CoroutineContext

/**
 * Pins [LauncherViewModel.renderedAppListOrder] to the order the app list actually
 * renders in.
 *
 * That order is what the priority icon set is taken from -- the head of it is the
 * screenful Home paints before the user types anything, and since nothing warms the
 * cache ahead of demand, what falls outside the head is what re-rasterizes on the way
 * back in. Ordering by launch count matches the screen only under a Usage sort; under
 * an alphabetical one the first visible rows are exactly the ones such a ranking puts
 * last, so the screenful kept would be the one the user cannot see.
 *
 * So the order comes from the list's own comparator rather than a second one written
 * beside it. These fix that to the setting: same apps, same launch counts, different
 * "Sort apps by" choice, different order.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LauncherViewModelPriorityOrderTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun clearPrefs() {
        listOf("docked_apps", "work_docked_apps", "dock_settings", "app_launch_stats", "app_metadata")
            .forEach { name ->
                context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
            }
    }

    /**
     * Runs the first block handed to it and parks every later one, holding the view
     * model in the window where `installedApps` is still the metadata prefill.
     *
     * Robolectric has no launchable activities, so letting the fresh load land would
     * replace the fixture with an empty list and leave nothing to order. Parking it is
     * what keeps these three apps in the order. (Same device as
     * `LauncherViewModelIconTrimGuardTest`, for the same reason.)
     */
    private class RunFirstBlockOnlyDispatcher : CoroutineDispatcher() {
        private var seen = 0
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (seen++ == 0) block.run()
        }
    }

    /**
     * Three apps whose alphabetical order is the reverse of their launch order, so a
     * order built the wrong way round is unambiguous rather than coincidentally right.
     */
    private fun seedApps() {
        val entries = listOf("Aardvark" to "aardvark", "Middle" to "middle", "Zebra" to "zebra")
            .map { (label, slug) ->
                JSONObject().apply {
                    put("name", label)
                    put("package", "com.example.$slug")
                    put("component", "com.example.$slug/.Main")
                    put("isWorkApp", false)
                    put("launchWithLauncherApps", true)
                }
            }
        context.getSharedPreferences("app_metadata", Context.MODE_PRIVATE)
            .edit()
            .putString("apps", JSONArray().put(entries[0]).put(entries[1]).put(entries[2]).toString())
            .commit()

        // Keyed off the same id production derives, rather than a spelled-out one:
        // `InstalledApp.id` leads with `user.hashCode()`, which is not a constant.
        context.getSharedPreferences("app_launch_stats", Context.MODE_PRIVATE)
            .edit()
            .putInt(launchCountKey("zebra"), 100)
            .putInt(launchCountKey("middle"), 50)
            .putInt(launchCountKey("aardvark"), 1)
            .commit()
    }

    private fun launchCountKey(slug: String): String = "launch_count:${appId(slug)}"

    private fun appId(slug: String): String {
        val id = InstalledApp(
            name = slug,
            packageName = "com.example.$slug",
            launchIntent = Intent.makeMainActivity(
                ComponentName("com.example.$slug", "com.example.$slug.Main"),
            ),
            user = Process.myUserHandle(),
            isWorkApp = false,
            launchWithLauncherApps = true,
        ).id
        return id
    }

    private fun setSortOrder(order: AppListSortOrder) {
        context.getSharedPreferences("dock_settings", Context.MODE_PRIVATE)
            .edit()
            .putString("app_list_sort_order", order.name)
            .commit()
    }

    /** The rendered order the priority set's head is taken from. */
    private fun renderedOrderNames(): List<String> {
        val viewModel = LauncherViewModel(
            app = ApplicationProvider.getApplicationContext(),
            workPackages = emptySet(),
            ioDispatcher = RunFirstBlockOnlyDispatcher(),
        )
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(
            "the fixture must survive into the order; a landed fresh load would empty it",
            viewModel.uiState.value.isFreshAppLoadComplete,
        )
        return viewModel.renderedAppListOrder().map { installed -> installed.name }
    }

    @Test
    fun theOrderFollowsLaunchCountUnderAUsageSort() {
        seedApps()
        setSortOrder(AppListSortOrder.Usage)

        assertEquals(listOf("Zebra", "Middle", "Aardvark"), renderedOrderNames())
    }

    @Test
    fun theOrderFollowsTheAlphabetUnderANameSort() {
        // The defect this pins. By launch count "Aardvark" ranks last, but under this
        // setting it is the first row on screen -- so a priority set taken by launches
        // drops the one row the user is guaranteed to be looking at.
        seedApps()
        setSortOrder(AppListSortOrder.Alphabetical)

        assertEquals(listOf("Aardvark", "Middle", "Zebra"), renderedOrderNames())
    }

    @Test
    fun aPinnedAppHeadsTheOrderEvenWhenTheAlphabetPutsItLast() {
        // Pinned apps float to the head of the *list* in every state, so with the dock
        // hidden a pin late in the alphabet is the first visible row -- and without
        // floating it here it ranks last, which puts the first row on screen outside
        // the head that is kept.
        seedApps()
        setSortOrder(AppListSortOrder.Alphabetical)
        context.getSharedPreferences("docked_apps", Context.MODE_PRIVATE)
            .edit()
            .putString("docked_app_ids", appId("zebra"))
            .commit()

        assertEquals(listOf("Zebra", "Aardvark", "Middle"), renderedOrderNames())
    }

    @Test
    fun pinsRankInGridOrderNotTheOrderTheyWereDocked() {
        // The stores keep `dockedAppIds` in insertion order; the rendered list floats
        // pins by their persisted grid coordinates. Once a user has rearranged the
        // dock the two disagree, and ranking by insertion order heads the set with the
        // wrong icon -- the second-row pin, while the first visible one is dropped.
        seedApps()
        setSortOrder(AppListSortOrder.Alphabetical)
        // Docked Middle first, Zebra second, then dragged Zebra into slot 0.
        context.getSharedPreferences("docked_apps", Context.MODE_PRIVATE)
            .edit()
            .putString("docked_app_ids", "${appId("middle")}\n${appId("zebra")}")
            .putString(
                "docked_app_positions",
                "${appId("middle")}\t0\t1\n${appId("zebra")}\t0\t0",
            )
            .commit()

        assertEquals(listOf("Zebra", "Middle", "Aardvark"), renderedOrderNames())
    }

    @Test
    fun aReversedSortKeepsItsForwardOrder() {
        // Reversed variants share their forward counterpart's data ordering -- the
        // flip is `reverseLayout` in the UI -- and index 0 renders at the visual
        // bottom under it. So the head of this list is still what the user sees, and
        // reversing the order here would keep the off-screen end instead.
        seedApps()
        setSortOrder(AppListSortOrder.AlphabeticalReversed)

        assertEquals(listOf("Aardvark", "Middle", "Zebra"), renderedOrderNames())
    }
}
