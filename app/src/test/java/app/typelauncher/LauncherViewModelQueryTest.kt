package app.typelauncher

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Regression coverage for the query-typing fast path: a `setQuery` call must
 * recompute `filteredApps` only and leave dock / recents / hidden
 * lists reference-equal so unrelated UI does not recompose on every keystroke
 * (notably backspace, which grows the result set back toward the full list).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LauncherViewModelQueryTest {
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
    fun setQueryDoesNotRebuildQueryIndependentLists() {
        seedApp("Mail", "com.example.mail")
        seedApp("Maps", "com.example.maps")
        seedApp("Chat", "com.example.chat")
        val viewModel = newViewModel()
        idle()
        assertTrue(
            "Cold-start load must complete before exercising setQuery",
            viewModel.uiState.value.isFreshAppLoadComplete,
        )

        val before = viewModel.uiState.value
        viewModel.setQuery("Ma")
        val after = viewModel.uiState.value

        assertNotSame(
            "filteredApps must update when the query changes",
            before.filteredApps,
            after.filteredApps,
        )
        assertSame(
            "dockedApps does not depend on the query and must not be rebuilt",
            before.dockedApps,
            after.dockedApps,
        )
        assertSame(
            "recentApps does not depend on the query and must not be rebuilt",
            before.recentApps,
            after.recentApps,
        )
        assertSame(
            "hiddenApps does not depend on the query and must not be rebuilt",
            before.hiddenApps,
            after.hiddenApps,
        )
    }

    @Test
    fun freshLoadPublishFiltersWithTheTrimmedQuery() {
        // The keyboard auto-shows at cold start, so the user can be mid-typing
        // when the fresh LauncherApps load publishes. That publish must filter
        // with the same trimmed query every steady-state refresh uses — before
        // the fix it filtered with the raw string, so a trailing space at
        // publish time ("Maps ") silently emptied the visible results the
        // identical query had just matched.
        seedApp("Mail", "com.example.mail")
        seedApp("Maps", "com.example.maps")
        val viewModel = newViewModel()
        // Type before the load lands (the load publishes on the main looper,
        // which hasn't idled yet).
        viewModel.setQuery("Maps ")
        idle()

        assertTrue(
            "Cold-start load must have completed",
            viewModel.uiState.value.isFreshAppLoadComplete,
        )
        assertEquals(
            listOf("Maps"),
            viewModel.uiState.value.filteredApps.map { it.displayName },
        )
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
