package app.typelauncher

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The fresh-install dock prefill must leave one free cell in the first row for
 * the "+" onboarding button. The stored per-row count is derived from a fixed
 * reference width, but the dock renders clamped to the device's
 * [dockSlotCountRange]; on a narrow phone (short edge < 344 dp, where the
 * default count of 6 clamps down) the prefill must use the device-clamped count
 * so it doesn't fill the whole rendered row.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "sw320dp")
class DockPrefillNarrowScreenTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

    @After
    fun clearPrefs() {
        listOf(
            "docked_apps",
            "dock_settings",
            "app_launch_stats",
            "widgets",
            "app_metadata",
            "hidden_apps",
            "renamed_apps",
        ).forEach { name ->
            context.getSharedPreferences(name, android.content.Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
        }
    }

    @Test
    fun prefillLeavesAFreeSlotForTheAddButtonOnNarrowScreens() {
        // A 320 dp short edge admits at most 5 dock columns (dockSlotCountRange
        // = 3..5), so the default count of 6 clamps to 5. Plenty of popular apps
        // are installed; the prefill must stop at 4 (device count − 1), leaving
        // one of the five rendered cells free for the "+" hint.
        val deviceRange = dockSlotCountRange(320)
        assertEquals("guard: 320 dp tops out at 5 columns", 5, deviceRange.last)

        listOf(
            "com.android.chrome",
            "com.google.android.gm",
            "com.google.android.apps.maps",
            "com.google.android.youtube",
            "com.google.android.dialer",
            "com.google.android.apps.messaging",
        ).forEach { pkg -> seedApp(pkg, pkg) }

        val viewModel = newViewModel()
        idle()

        // Stored count stays at the reference-width default of 6...
        assertEquals(DEFAULT_DOCK_ICON_COUNT, viewModel.uiState.value.dockIconCount)
        // ...but the prefill is clamped to the device's 5 columns minus the
        // reserved slot, so a cell stays open for the onboarding "+".
        val docked = viewModel.uiState.value.dockedApps
        assertEquals("prefill must reserve a slot on a 5-column device", 4, docked.size)
        assertTrue(
            "docked count must stay below the rendered column count so the + cell is free",
            docked.size < deviceRange.last,
        )
    }

    @Test
    @Config(qualifiers = "sw411dp-w320dp-h600dp")
    fun prefillClampsToTheCurrentWindowNotTheDeviceShortEdge() {
        // A 411 dp device (smallestScreenWidthDp) running in a 320 dp-wide
        // window — split-screen, free-form, or a foldable cover screen.
        // HomeScreen renders the dock against the current window
        // (minOf(screenWidthDp, screenHeightDp) = 320 → 5 columns), so the
        // prefill must clamp to that, not the device's 411 dp (which admits 6
        // and would fill the rendered row, hiding the "+").
        val config = context.resources.configuration
        // Guard: confirm the qualifiers set up the divergence we're testing.
        assertEquals("device short edge", 411, config.smallestScreenWidthDp)
        assertEquals(
            "window short edge",
            320,
            minOf(config.screenWidthDp, config.screenHeightDp),
        )

        listOf(
            "com.android.chrome",
            "com.google.android.gm",
            "com.google.android.apps.maps",
            "com.google.android.youtube",
            "com.google.android.dialer",
            "com.google.android.apps.messaging",
        ).forEach { pkg -> seedApp(pkg, pkg) }

        val viewModel = newViewModel()
        idle()

        // 320 dp window → 5 columns → prefill stops at 4, leaving the "+" cell.
        // Clamping on the 411 dp device width would admit 6 and dock 5.
        assertEquals(
            "prefill must size to the current window, not the device short edge",
            4,
            viewModel.uiState.value.dockedApps.size,
        )
    }

    @Test
    fun dockWritesUseTheDeviceClampedColumnGridOnNarrowScreens() {
        // 320 dp tops out at 5 columns but the stored default is 6. A dock write
        // must place apps in the 5-column grid Home actually renders, not a
        // phantom 6-column grid — otherwise the 6th app lands in a column the
        // user never sees. Skip the first-run prefill so the dock starts empty,
        // then dock six apps and confirm they wrap onto a second row.
        context.getSharedPreferences("docked_apps", android.content.Context.MODE_PRIVATE)
            .edit().putBoolean("dock_prefilled", true).commit()
        val names = (1..6).map { "App%02d".format(it) }
        names.forEach { name -> seedApp(name, "app.typelauncher.fake.$name") }

        val viewModel = newViewModel()
        idle()
        viewModel.uiState.value.filteredApps
            .filter { it.name in names }
            .forEach { viewModel.toggleDock(it, maxDockedApps = 12) }
        idle()

        assertEquals(6, viewModel.uiState.value.dockedApps.size)
        val maxRow = viewModel.uiState.value.dockPositions.values.maxOf { it.row }
        assertEquals("six apps must wrap onto a 2nd row in a 5-column dock", 1, maxRow)
    }

    private fun newViewModel(): LauncherViewModel = LauncherViewModel(
        app = ApplicationProvider.getApplicationContext(),
        workPackages = emptySet(),
        ioDispatcher = Dispatchers.Unconfined,
    )

    private fun seedApp(label: String, packageName: String) {
        val resolveInfo = ResolveInfo().apply {
            nonLocalizedLabel = label
            activityInfo = ActivityInfo().apply {
                this.packageName = packageName
                name = "$packageName.LaunchActivity"
            }
        }
        @Suppress("DEPRECATION")
        shadowOf(context.packageManager).addResolveInfoForIntent(launcherIntent, resolveInfo)
    }

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
    }
}
