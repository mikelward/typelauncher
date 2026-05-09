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
 * Verifies the user-driven app rename flow end-to-end at the ViewModel layer:
 * the override propagates to every surface, search matches the new label, and
 * a blank rename clears the override and restores the system label.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LauncherViewModelRenameTest {
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
    fun renameAppOverridesDisplayNameInAppList() {
        seedApp("ChatGPT", "com.openai.chatgpt")
        val viewModel = freshlyLoaded()
        val target = viewModel.uiState.value.filteredApps.single { it.name == "ChatGPT" }

        viewModel.renameApp(target, "Codex")

        val renamed = viewModel.uiState.value.filteredApps.single { it.id == target.id }
        assertEquals("Codex", renamed.displayName)
        // The original system label is preserved on the model so a later
        // reset can restore it without re-querying PackageManager.
        assertEquals("ChatGPT", renamed.name)
    }

    @Test
    fun renameAppMakesSearchMatchTheOverride() {
        seedApp("ChatGPT", "com.openai.chatgpt")
        val viewModel = freshlyLoaded()
        val target = viewModel.uiState.value.filteredApps.single { it.name == "ChatGPT" }

        viewModel.renameApp(target, "Codex")
        viewModel.setQuery("cod")

        val matches = viewModel.uiState.value.filteredApps.map { it.displayName }
        assertTrue(
            "Search for 'cod' should surface the renamed entry; got $matches",
            matches.contains("Codex"),
        )
    }

    @Test
    fun renameAppWithBlankClearsExistingOverride() {
        seedApp("ChatGPT", "com.openai.chatgpt")
        val viewModel = freshlyLoaded()
        val target = viewModel.uiState.value.filteredApps.single { it.name == "ChatGPT" }
        viewModel.renameApp(target, "Codex")
        val renamed = viewModel.uiState.value.filteredApps.single { it.id == target.id }

        viewModel.renameApp(renamed, "")

        val restored = viewModel.uiState.value.filteredApps.single { it.id == target.id }
        assertNull("Blank rename must clear the override", restored.customName)
        assertEquals("ChatGPT", restored.displayName)
    }

    @Test
    fun renameAppOverrideSurvivesProcessRestart() {
        seedApp("ChatGPT", "com.openai.chatgpt")
        val initial = freshlyLoaded()
        val target = initial.uiState.value.filteredApps.single { it.name == "ChatGPT" }
        initial.renameApp(target, "Codex")

        // A new ViewModel instance simulates a process restart; it must
        // re-read the persisted override and apply it on cold start.
        val reloaded = freshlyLoaded()
        val restored = reloaded.uiState.value.filteredApps.single { it.id == target.id }
        assertEquals("Codex", restored.displayName)
    }

    private fun freshlyLoaded(): LauncherViewModel {
        val viewModel = LauncherViewModel(
            app = ApplicationProvider.getApplicationContext(),
            workPackages = emptySet(),
            ioDispatcher = Dispatchers.Unconfined,
        )
        idle()
        assertTrue(
            "Cold-start load must finish before renaming",
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
