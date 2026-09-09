package app.typelauncher

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * End-to-end coverage of the user-driven custom-badge flow at the ViewModel
 * layer: `setAppBadge` mirrors a chosen glyph onto every surface, an empty
 * value clears it, and the override survives a process restart so the badge
 * the user picked still renders on the next cold start.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LauncherViewModelBadgeTest {
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
            "custom_badges",
        ).forEach { name ->
            context.getSharedPreferences(name, android.content.Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
        }
    }

    @Test
    fun setAppBadgeMirrorsCustomBadgeOntoFilteredApp() {
        seedApp("ChatGPT", "com.openai.chatgpt")
        val viewModel = freshlyLoaded()
        val target = viewModel.uiState.value.filteredApps.single { it.name == "ChatGPT" }

        viewModel.setAppBadge(target, "🏠")

        val patched = viewModel.uiState.value.filteredApps.single { it.id == target.id }
        assertEquals("🏠", patched.customBadge)
    }

    @Test
    fun setAppBadgeWithBlankClearsExistingOverride() {
        seedApp("ChatGPT", "com.openai.chatgpt")
        val viewModel = freshlyLoaded()
        val target = viewModel.uiState.value.filteredApps.single { it.name == "ChatGPT" }
        viewModel.setAppBadge(target, "🏠")

        viewModel.setAppBadge(target, null)

        val cleared = viewModel.uiState.value.filteredApps.single { it.id == target.id }
        assertNull("Null badge must clear the override", cleared.customBadge)
    }

    @Test
    fun setAppBadgeWithWhitespaceOnlyClearsExistingOverride() {
        seedApp("ChatGPT", "com.openai.chatgpt")
        val viewModel = freshlyLoaded()
        val target = viewModel.uiState.value.filteredApps.single { it.name == "ChatGPT" }
        viewModel.setAppBadge(target, "🏠")

        viewModel.setAppBadge(target, "   ")

        val cleared = viewModel.uiState.value.filteredApps.single { it.id == target.id }
        assertNull(cleared.customBadge)
    }

    @Test
    fun setAppBadgeOverrideSurvivesProcessRestart() {
        seedApp("ChatGPT", "com.openai.chatgpt")
        val initial = freshlyLoaded()
        val target = initial.uiState.value.filteredApps.single { it.name == "ChatGPT" }
        initial.setAppBadge(target, "🇺🇸")

        // A new ViewModel instance simulates a process restart; it must
        // re-read the persisted override and apply it on cold start.
        val reloaded = freshlyLoaded()
        val restored = reloaded.uiState.value.filteredApps.single { it.id == target.id }
        assertEquals("🇺🇸", restored.customBadge)
    }

    private fun freshlyLoaded(): LauncherViewModel {
        val viewModel = LauncherViewModel(
            app = ApplicationProvider.getApplicationContext(),
            workPackages = emptySet(),
            ioDispatcher = Dispatchers.Unconfined,
        )
        idle()
        assertTrue(
            "Cold-start load must finish before setting a badge",
            viewModel.uiState.value.isFreshAppLoadComplete,
        )
        return viewModel
    }

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
