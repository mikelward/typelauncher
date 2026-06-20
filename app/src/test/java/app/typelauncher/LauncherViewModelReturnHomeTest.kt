package app.typelauncher

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Coverage for the launcher-entry-intent reset (`returnToLauncherHome`), which
 * runs when Android re-enters the running activity for a home press or the
 * system "swipe up to home" gesture. Returning to Home must drop the user back
 * into the default empty-query state, not whatever search text was left over.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LauncherViewModelReturnHomeTest {
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
    fun returnToLauncherHomeClearsSearchQueryAndRebuildsList() {
        seedApp("Mail", "com.example.mail")
        seedApp("Maps", "com.example.maps")
        seedApp("Chat", "com.example.chat")
        val viewModel = newViewModel()
        idle()
        assertTrue(
            "Cold-start load must complete before exercising the reset",
            viewModel.uiState.value.isFreshAppLoadComplete,
        )

        viewModel.setQuery("Ma")
        val filteredWhileTyping = viewModel.uiState.value.filteredApps.map { it.name }
        assertTrue(
            "Typing 'Ma' filters the list down to the matching apps",
            filteredWhileTyping.containsAll(listOf("Mail", "Maps")) && !filteredWhileTyping.contains("Chat"),
        )

        viewModel.returnToLauncherHome()

        val after = viewModel.uiState.value
        assertEquals("Returning to Home clears the search field", "", after.query)
        assertEquals(
            "Returning to Home lands on the Home destination",
            LauncherDestination.Home,
            after.destination,
        )
        assertTrue(
            "Clearing the query rebuilds the filtered list back to the full set",
            after.filteredApps.map { it.name }.containsAll(listOf("Mail", "Maps", "Chat")),
        )
    }

    @Test
    fun returnToLauncherHomeWithEmptyQueryDoesNotRebuildFilteredApps() {
        seedApp("Mail", "com.example.mail")
        val viewModel = newViewModel()
        idle()
        assertTrue(
            "Cold-start load must complete before exercising the reset",
            viewModel.uiState.value.isFreshAppLoadComplete,
        )

        // A plain home press on an already-empty Home must not churn the
        // filtered list — the reference has to stay identical so the app list
        // doesn't recompose for nothing.
        val before = viewModel.uiState.value.filteredApps
        viewModel.returnToLauncherHome()
        val after = viewModel.uiState.value.filteredApps

        assertSame(
            "An already-empty query must not rebuild filteredApps on home press",
            before,
            after,
        )
    }

    @Test
    fun returnToLauncherHomeBumpsHomeTransientResetTokenEachTime() {
        seedApp("Mail", "com.example.mail")
        val viewModel = newViewModel()
        idle()

        val before = viewModel.uiState.value.homeTransientResetToken
        viewModel.returnToLauncherHome()
        val afterFirst = viewModel.uiState.value.homeTransientResetToken
        viewModel.returnToLauncherHome()
        val afterSecond = viewModel.uiState.value.homeTransientResetToken

        // The token is what an open dock folder watches to close itself; it must
        // change on every return to Home, including a plain home press on an
        // already-empty Home (which otherwise leaves the rest of the state alone).
        assertEquals(before + 1, afterFirst)
        assertEquals(before + 2, afterSecond)
    }

    @Test
    fun homeTransientResetTokenBumpsWhenLeavingEnteringAndResumingHome() {
        seedApp("Mail", "com.example.mail")
        val viewModel = newViewModel()
        idle()

        val before = viewModel.uiState.value.homeTransientResetToken
        viewModel.showWidgets()
        val afterLeavingHome = viewModel.uiState.value.homeTransientResetToken
        viewModel.showHome()
        val afterEnteringHome = viewModel.uiState.value.homeTransientResetToken
        viewModel.resetHomeTransientUiOnResume()
        val afterResumingHome = viewModel.uiState.value.homeTransientResetToken

        // Dock folders hold their open/closed state in Compose, so the ViewModel
        // token must move across every Home boundary the UI should reset for.
        assertEquals(before + 1, afterLeavingHome)
        assertEquals(before + 2, afterEnteringHome)
        assertEquals(before + 3, afterResumingHome)
    }

    @Test
    fun openAppInfoBumpsHomeTransientResetToken() {
        seedApp("Mail", "com.example.mail")
        val viewModel = newViewModel()
        idle()
        val app = viewModel.uiState.value.filteredApps.single { it.packageName == "com.example.mail" }

        val before = viewModel.uiState.value.homeTransientResetToken
        viewModel.openAppInfo(app)
        val after = viewModel.uiState.value.homeTransientResetToken

        // App info is an external activity launched while Home remains the
        // current destination, so it must still close any open Home-only UI.
        assertEquals(before + 1, after)
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
