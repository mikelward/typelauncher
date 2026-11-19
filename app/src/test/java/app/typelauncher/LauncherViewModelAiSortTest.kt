package app.typelauncher

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** "Sort with on-device AI" through the view model, against a stand-in model. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LauncherViewModelAiSortTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    private val model = FakeModel()
    private val waits = mutableListOf<Long>()

    @After
    fun clearPrefs() {
        listOf(
            AppFolderStore.PREFERENCES_NAME,
            "docked_apps",
            "work_docked_apps",
            "dock_settings",
            "app_launch_stats",
            "widgets",
            "app_metadata",
            "hidden_apps",
            "renamed_apps",
        ).forEach { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @Test
    fun sortsEveryAppTheUserLeftUnfiled_manifestCategoryOrNot_andSaysSo() {
        // Finance apps often declare "productivity", since Android's manifest
        // categories have no finance; so the AI is asked about them too
        // (maintainer, 2026-11-19: Robinhood, Monzo and Wallet in Productivity).
        seedApp("Monzo", "co.uk.getmondo", ApplicationInfo.CATEGORY_PRODUCTIVITY)
        seedApp("Chess", "com.chess", ApplicationInfo.CATEGORY_GAME)
        seedApp("Notes", "com.example.notes")
        model.answer = { prompt -> answerFrom(prompt, mapOf("Monzo" to "finance", "Notes" to "tools")) }
        val viewModel = startedViewModel(fileFirst = "Notes" to AppCategory.Productivity)

        val state = viewModel.uiState.value
        assertEquals(1, model.prompts.size)
        assertTrue(model.prompts.single(), "Monzo (co.uk.getmondo)" in model.prompts.single())
        assertTrue("manifest-filed apps are asked about too", "Chess" in model.prompts.single())
        assertFalse("user-filed apps aren't asked about", "Notes" in model.prompts.single())
        assertEquals(AppCategory.Finance, state.appFolderAiAssignments[id(viewModel, "Monzo")])
        // Type Launcher lists itself too, so it's sorted alongside.
        assertEquals(AiSortPhase.Done, state.aiSort.phase)
        assertEquals(state.aiSort.total, state.aiSort.sorted)
        assertEquals(state.appFolderAiAssignments.size, state.aiSort.total)
        // Kept for the next launch.
        assertEquals(AppCategory.Finance, AppFolderStore(context).aiAssignments[id(viewModel, "Monzo")])
    }

    @Test
    fun choicesFromOlderInstructions_areDroppedAtStart_soAppsAreSortedAgain() {
        // An update adds a folder (Home): what the AI chose without it goes.
        AppFolderStore(context).apply {
            forgetAiChoicesUnlessMadeBy(AI_SORT_PROMPT_VERSION + 1)
            assignByAi(mapOf("someone" to AppCategory.Tools))
        }

        val viewModel = newViewModel()

        assertEquals(emptyMap<String, AppCategory>(), viewModel.uiState.value.appFolderAiAssignments)
        assertEquals(emptyMap<String, AppCategory>(), AppFolderStore(context).aiAssignments)
    }

    @Test
    fun sortsInBatches() {
        repeat(AI_SORT_BATCH_SIZE + 3) { index -> seedApp("App $index", "com.example.app$index") }
        model.answer = { "" } // Every app ends up in Other.

        val viewModel = startedViewModel()

        // 18 apps plus Type Launcher itself: two batches.
        assertEquals(2, model.prompts.size)
        assertEquals(AI_SORT_BATCH_SIZE + 4, viewModel.uiState.value.appFolderAiAssignments.size)
    }

    @Test
    fun waitsForTheForeground_folders_andTheSwitch() {
        seedApp("Monzo", "co.uk.getmondo")
        val viewModel = newViewModel()
        idle()
        viewModel.setAppFoldersEnabled(true)
        viewModel.setAiSortEnabled(true)
        idle()
        assertEquals("not the top app yet", 0, model.statusChecks)

        viewModel.setLauncherForeground(true)
        idle()
        assertEquals(1, model.prompts.size)
    }

    @Test
    fun noModelOnThisPhone_saysSo_andAsksNothing() {
        seedApp("Monzo", "co.uk.getmondo")
        model.status = AiModelStatus.Unavailable

        val viewModel = startedViewModel()

        assertEquals(AiSortPhase.Unavailable, viewModel.uiState.value.aiSort.phase)
        assertEquals(0, model.prompts.size)
    }

    @Test
    fun downloadsTheModelFirst() {
        seedApp("Monzo", "co.uk.getmondo")
        model.status = AiModelStatus.Downloadable

        startedViewModel()

        assertEquals(1, model.downloads)
        assertEquals(1, model.prompts.size)
    }

    @Test
    fun busy_waitsAndRetriesTheSameBatch() {
        seedApp("Monzo", "co.uk.getmondo")
        var calls = 0
        model.answer = { prompt ->
            calls++
            if (calls == 1) throw AiModelException.Busy(retryAfterMillis = null)
            answerFrom(prompt, mapOf("Monzo" to "finance"))
        }

        val viewModel = startedViewModel()

        assertEquals(listOf(AI_SORT_FIRST_RETRY_MILLIS), waits)
        assertEquals(AiSortPhase.Done, viewModel.uiState.value.aiSort.phase)
    }

    @Test
    fun blockedInTheBackground_pauses_andCarriesOnAtTheNextReturn() {
        seedApp("Monzo", "co.uk.getmondo")
        model.answer = { throw AiModelException.Background() }
        val viewModel = startedViewModel()
        assertEquals(AiSortPhase.Paused, viewModel.uiState.value.aiSort.phase)

        model.answer = { prompt -> answerFrom(prompt, mapOf("Monzo" to "finance")) }
        viewModel.setLauncherForeground(false)
        viewModel.setLauncherForeground(true)
        idle()

        assertEquals(AiSortPhase.Done, viewModel.uiState.value.aiSort.phase)
    }

    @Test
    fun paused_staysPausedThroughListRefreshes_untilTheNextReturn() {
        // An app-list refresh (here, a sort-order change) mustn't restart
        // sorting against a service that just refused it (Codex on PR #852).
        seedApp("Monzo", "co.uk.getmondo")
        model.answer = { throw AiModelException.QuotaExceeded() }
        val viewModel = startedViewModel()
        assertEquals(AiSortPhase.Paused, viewModel.uiState.value.aiSort.phase)
        val asked = model.prompts.size

        viewModel.setAppListSortOrder(AppListSortOrder.Alphabetical)
        idle()
        assertEquals(asked, model.prompts.size)

        model.answer = { prompt -> answerFrom(prompt, mapOf("Monzo" to "finance")) }
        viewModel.setLauncherForeground(false)
        viewModel.setLauncherForeground(true)
        idle()
        assertEquals(AiSortPhase.Done, viewModel.uiState.value.aiSort.phase)
    }

    @Test
    fun resort_forgetsTheAisChoices_andAsksAgain() {
        seedApp("Monzo", "co.uk.getmondo")
        model.answer = { prompt -> answerFrom(prompt, mapOf("Monzo" to "social")) }
        val viewModel = startedViewModel()
        assertEquals(AppCategory.Social, viewModel.uiState.value.appFolderAiAssignments[id(viewModel, "Monzo")])

        model.answer = { prompt -> answerFrom(prompt, mapOf("Monzo" to "finance")) }
        viewModel.resortAppsWithAi()
        idle()

        assertEquals(2, model.prompts.size)
        assertEquals(AppCategory.Finance, viewModel.uiState.value.appFolderAiAssignments[id(viewModel, "Monzo")])
    }

    @Test
    fun offer_isPendingUntilAnswered_andTurningOnAnswersIt() {
        val viewModel = newViewModel()
        assertTrue(viewModel.uiState.value.isAiSortOfferPending)

        viewModel.dismissAiSortOffer()
        assertFalse(viewModel.uiState.value.isAiSortOfferPending)
        assertFalse(newViewModel().uiState.value.isAiSortOfferPending)
    }

    @Test
    fun unsupportedPhone_neverStarts() {
        seedApp("Monzo", "co.uk.getmondo")
        val viewModel = newViewModel(aiCorePresent = false)
        idle()
        viewModel.setAppFoldersEnabled(true)
        viewModel.setAiSortEnabled(true)
        viewModel.setLauncherForeground(true)
        idle()

        assertFalse(viewModel.uiState.value.isAiSortSupported)
        assertEquals(0, model.statusChecks)
    }

    private fun startedViewModel(fileFirst: Pair<String, AppCategory>? = null): LauncherViewModel {
        val viewModel = newViewModel()
        idle()
        if (fileFirst != null) {
            viewModel.moveAppToFolder(
                viewModel.uiState.value.filteredApps.first { it.name == fileFirst.first },
                fileFirst.second,
            )
        }
        viewModel.setAppFoldersEnabled(true)
        viewModel.setAiSortEnabled(true)
        viewModel.setLauncherForeground(true)
        idle()
        return viewModel
    }

    private fun id(viewModel: LauncherViewModel, name: String): String =
        viewModel.uiState.value.filteredApps.first { it.name == name }.id

    // Answers "n: folder" for each numbered app line whose label is in [folders].
    private fun answerFrom(prompt: String, folders: Map<String, String>): String =
        prompt.lines().mapNotNull { line ->
            val match = Regex("""^(\d+)\. (.+) \(""").find(line) ?: return@mapNotNull null
            folders[match.groupValues[2]]?.let { "${match.groupValues[1]}: $it" }
        }.joinToString("\n")

    private fun newViewModel(aiCorePresent: Boolean = true): LauncherViewModel = LauncherViewModel(
        app = ApplicationProvider.getApplicationContext(),
        workPackages = emptySet(),
        ioDispatcher = Dispatchers.Unconfined,
        aiModelFactory = { model },
        aiCorePresent = { aiCorePresent },
        aiRetryDelay = { millis -> waits += millis },
    )

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun seedApp(label: String, packageName: String, category: Int = ApplicationInfo.CATEGORY_UNDEFINED) {
        val resolveInfo = ResolveInfo().apply {
            nonLocalizedLabel = label
            activityInfo = ActivityInfo().apply {
                this.packageName = packageName
                name = "$packageName.LaunchActivity"
                applicationInfo = ApplicationInfo().apply {
                    this.packageName = packageName
                    this.category = category
                }
            }
        }
        @Suppress("DEPRECATION")
        shadowOf(context.packageManager).addResolveInfoForIntent(launcherIntent, resolveInfo)
    }

    private class FakeModel : AppCategoryModel {
        var status = AiModelStatus.Available
        var answer: (String) -> String = { "" }
        val prompts = mutableListOf<String>()
        var statusChecks = 0
        var downloads = 0

        override suspend fun status(): AiModelStatus {
            statusChecks++
            return status
        }

        override fun download(): Flow<Long> = flow {
            downloads++
            emit(1L)
            status = AiModelStatus.Available
        }

        override suspend fun generate(prompt: String): String {
            prompts += prompt
            return answer(prompt)
        }
    }
}
