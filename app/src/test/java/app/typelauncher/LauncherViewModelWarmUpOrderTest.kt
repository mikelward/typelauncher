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
 * Pins the warm-up's tail to the order the app list actually renders in.
 *
 * The warm-up stops at a fraction of the byte budget, so on a device with enough apps
 * to reach it the tail's order decides which icons end up warm. Ordering it by launch
 * count only matches the screen under a Usage sort; under an alphabetical one it warms
 * the visible rows *last*, which is the opposite of what the ceiling is for -- the user
 * looks at the top of their list and finds exactly the icons the sweep never got to.
 *
 * So the plan sorts with the list's own comparator rather than a second one written
 * beside it. These fix that to the setting: same apps, same launch counts, different
 * "Sort apps by" choice, different warm order.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LauncherViewModelWarmUpOrderTest {
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
     * what keeps these three apps in the plan. (Same device as
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
     * plan built the wrong way round is unambiguous rather than coincidentally right.
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

    /** The list-size half of the plan, which is the pass this ordering governs. */
    private fun warmOrderNames(): List<String> {
        val viewModel = LauncherViewModel(
            app = ApplicationProvider.getApplicationContext(),
            workPackages = emptySet(),
            ioDispatcher = RunFirstBlockOnlyDispatcher(),
        )
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(
            "the fixture must survive into the plan; a landed fresh load would empty it",
            viewModel.uiState.value.isFreshAppLoadComplete,
        )
        return viewModel
            .buildWarmUpPlan(RenderedIconSizes(listPx = 40, dockPx = 132, folderPx = 56))
            .filter { (_, sizePx) -> sizePx == 40 }
            .map { (installed, _) -> installed.name }
    }

    @Test
    fun theTailFollowsLaunchCountUnderAUsageSort() {
        seedApps()
        setSortOrder(AppListSortOrder.Usage)

        assertEquals(listOf("Zebra", "Middle", "Aardvark"), warmOrderNames())
    }

    @Test
    fun theTailFollowsTheAlphabetUnderANameSort() {
        // The defect this pins. By launch count "Aardvark" is warmed last, but under
        // this setting it is the first row on screen -- so on a device that reaches
        // the ceiling it is the one row guaranteed to still be a placeholder.
        seedApps()
        setSortOrder(AppListSortOrder.Alphabetical)

        assertEquals(listOf("Aardvark", "Middle", "Zebra"), warmOrderNames())
    }

    @Test
    fun aPinnedAppHeadsTheTailEvenWhenTheAlphabetPutsItLast() {
        // The defect this pins: the dock and folder passes warm `dockPx`/`folderPx`,
        // never `listPx`. Pinned apps float to the head of the *list* in every state,
        // so with the dock hidden a pin late in the alphabet is the first visible row
        // -- and without floating it here it warms last, which on a device that
        // reaches the ceiling means it is the one row that never warms at all.
        seedApps()
        setSortOrder(AppListSortOrder.Alphabetical)
        context.getSharedPreferences("docked_apps", Context.MODE_PRIVATE)
            .edit()
            .putString("docked_app_ids", appId("zebra"))
            .commit()

        assertEquals(listOf("Zebra", "Aardvark", "Middle"), warmOrderNames())
    }

    @Test
    fun aReversedSortWarmsItsForwardOrder() {
        // Reversed variants share their forward counterpart's data ordering -- the
        // flip is `reverseLayout` in the UI -- and index 0 renders at the visual
        // bottom under it. So the head of this list is still what the user sees, and
        // reversing the warm order here would warm the off-screen end first.
        seedApps()
        setSortOrder(AppListSortOrder.AlphabeticalReversed)

        assertEquals(listOf("Aardvark", "Middle", "Zebra"), warmOrderNames())
    }
}
