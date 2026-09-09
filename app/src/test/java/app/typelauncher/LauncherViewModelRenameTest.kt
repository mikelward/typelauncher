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

    @Test
    fun renameWorkAppToRawNamePreservesTheOverride() {
        // Regression: clearing the rename when `trimmed == app.name` (the
        // raw system label) was wrong for work-profile apps, whose rendered
        // default is "Work <name>". A user who renames "Work Calendar" to
        // "Calendar" is deliberately dropping the prefix, and that override
        // must stick — falling back to the prefixed form would silently
        // undo the user's intent.
        seedApp("Calendar", "com.android.calendar")
        val viewModel = freshlyLoaded(workPackages = setOf("com.android.calendar"))
        val target = viewModel.uiState.value.filteredApps.single { it.name == "Calendar" }
        assertEquals(true, target.isWorkApp)
        assertEquals("Work Calendar", target.displayName)

        viewModel.renameApp(target, "Calendar")

        val renamed = viewModel.uiState.value.filteredApps.single { it.id == target.id }
        assertEquals("Calendar", renamed.customName)
        assertEquals("Calendar", renamed.displayName)
    }

    @Test
    fun renameWorkAppToPrefixedDefaultClearsTheOverride() {
        // The other side of the same coin: typing the launcher's own default
        // label ("Work Calendar") must clear the override, because the user
        // is asking to go back to the system default — not to set an
        // override that happens to match the default.
        seedApp("Calendar", "com.android.calendar")
        val viewModel = freshlyLoaded(workPackages = setOf("com.android.calendar"))
        val target = viewModel.uiState.value.filteredApps.single { it.name == "Calendar" }
        viewModel.renameApp(target, "Daily")
        val withOverride = viewModel.uiState.value.filteredApps.single { it.id == target.id }
        assertEquals("Daily", withOverride.customName)

        viewModel.renameApp(withOverride, "Work Calendar")

        val restored = viewModel.uiState.value.filteredApps.single { it.id == target.id }
        assertNull("Typing the default rendered label clears the override", restored.customName)
        assertEquals("Work Calendar", restored.displayName)
    }

    @Test
    fun renameAppSurvivesProcessRestartForSearchToo() {
        // Regression: at one point `renameApp` only mirrored `customName`
        // onto the in-memory `installedApps` for the rename that just
        // happened; a fresh ViewModel reloaded `installedApps` from the
        // package manager with `customName = null`, and `markVisibility`
        // (which runs after `filterByName`) only attached the override
        // to the *derived* lists. So typing the saved override into the
        // search field returned no matches even though the empty list
        // would have rendered the renamed label. The fix applies
        // `RenamedAppStore` overrides to `installedApps` at every load
        // path (cached metadata, fresh load, package-event reload) so
        // `filterByName` sees the right `displayName` on the very first
        // call after restart.
        seedApp("ChatGPT", "com.openai.chatgpt")
        val initial = freshlyLoaded()
        val target = initial.uiState.value.filteredApps.single { it.name == "ChatGPT" }
        initial.renameApp(target, "Codex")

        val reloaded = freshlyLoaded()
        reloaded.setQuery("cod")
        val matches = reloaded.uiState.value.filteredApps.map { it.displayName }
        assertTrue(
            "Search for 'cod' on a freshly-reloaded VM should find the persisted override; got $matches",
            matches.contains("Codex"),
        )
    }

    private fun freshlyLoaded(workPackages: Set<String> = emptySet()): LauncherViewModel {
        val viewModel = LauncherViewModel(
            app = ApplicationProvider.getApplicationContext(),
            workPackages = workPackages,
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
