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

    init {
        // By default the model answers every app, with other.
        model.answer = { prompt -> answerFrom(prompt, emptyMap()) }
    }

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
        // Finance apps often declare "office", since Android's manifest
        // categories have no finance; so the AI is asked about them too
        // (maintainer, 2026-11-19: Robinhood, Monzo and Wallet in Productivity).
        seedApp("Fintastic", "com.example.fintastic", ApplicationInfo.CATEGORY_PRODUCTIVITY)
        seedApp("Clipz", "com.example.clipz", ApplicationInfo.CATEGORY_VIDEO)
        seedApp("Chess", "com.example.chess", ApplicationInfo.CATEGORY_GAME)
        seedApp("Notes", "com.example.notes")
        model.answer = { prompt -> answerFrom(prompt, mapOf("Fintastic" to "finance", "Notes" to "tools")) }
        val viewModel = startedViewModel(fileFirst = "Notes" to AppCategory.Office)

        val state = viewModel.uiState.value
        assertEquals(1, model.prompts.size)
        assertTrue(model.prompts.single(), "Fintastic (com.example.fintastic)" in model.prompts.single())
        assertTrue("manifest-filed apps are asked about too", "Clipz (com.example.clipz; suggested media)" in model.prompts.single())
        // A game that says so goes in Games whatever the AI says (maintainer, 2026-11-19).
        assertFalse("games that say so aren't asked about", "Chess" in model.prompts.single())
        assertFalse("user-filed apps aren't asked about", "Notes" in model.prompts.single())
        assertEquals(AppCategory.Finance.storageKey, state.appFolderAiAssignments[id(viewModel, "Fintastic")])
        // Type Launcher lists itself too, so it's sorted alongside.
        assertEquals(AiSortPhase.Done, state.aiSort.phase)
        assertEquals(state.aiSort.total, state.aiSort.sorted)
        assertEquals(state.appFolderAiAssignments.size, state.aiSort.total)
        // Kept for the next launch.
        assertEquals(AppCategory.Finance.storageKey, AppFolderStore(context).aiAssignments[id(viewModel, "Fintastic")])
    }

    @Test
    fun aNewFolder_isOfferedToTheAi_andEveryAppIsSortedAgain() {
        seedApp("Crayons", "com.example.crayons")
        seedApp("Lego", "com.example.lego")
        model.answer = { prompt -> answerFrom(prompt, emptyMap()) }
        val viewModel = startedViewModel()
        val firstPrompts = model.prompts.size
        model.answer = { prompt -> answerFrom(prompt, mapOf("Lego" to "toys")) }

        viewModel.createAppFolder(
            viewModel.uiState.value.filteredApps.first { it.name == "Crayons" },
            "Toys",
            FolderIcon.ChildCare,
        )
        idle()

        val prompt = model.prompts.drop(firstPrompts).first()
        assertTrue(prompt, prompt.lines().any { it.startsWith("toys: ") })
        assertFalse("the app the user filed isn't asked about", "Crayons" in prompt)
        val toys = viewModel.uiState.value.appFolders.first { it.name == "Toys" }
        assertEquals(toys.key, viewModel.uiState.value.appFolderAiAssignments[id(viewModel, "Lego")])
    }

    @Test
    fun aDeletedFolder_isntOfferedToTheAi_andEveryAppIsSortedAgain() {
        seedApp("Chess", "com.example.chess")
        model.answer = { prompt -> answerFrom(prompt, mapOf("Chess" to "games")) }
        val viewModel = startedViewModel()
        val firstPrompts = model.prompts.size
        model.answer = { prompt -> answerFrom(prompt, emptyMap()) }

        viewModel.deleteAppFolder(AppFolderDef.builtIn(AppCategory.Games))
        idle()

        val prompt = model.prompts.drop(firstPrompts).first()
        assertFalse(prompt, prompt.lines().any { it.startsWith("games: ") })
        assertTrue("Chess" in prompt)
        assertEquals("other", viewModel.uiState.value.appFolderAiAssignments[id(viewModel, "Chess")])
    }

    @Test
    fun aWellKnownApp_isAskedAbout_onceItsFolderIsDeleted() {
        // Codex on PR #860: with Transport gone, Waze needs the AI to find it a home.
        seedApp("Waze", "com.waze")
        model.answer = { prompt -> answerFrom(prompt, emptyMap()) }
        val viewModel = startedViewModel()

        viewModel.deleteAppFolder(AppFolderDef.builtIn(AppCategory.Car))
        idle()

        assertTrue(model.prompts.any { "Waze (com.waze)" in it })
    }

    @Test
    fun aFolderChangeDuringTheFirstSort_dropsItsAnswer_andSortsAgain() {
        // Codex on PR #860: nothing stored yet, so nothing was "forgotten",
        // but the answer in flight is for the old folders.
        seedApp("Lego", "com.example.lego")
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        var calls = 0
        model.suspendBefore = { if (calls++ == 0) gate.await() }
        model.answer = { prompt ->
            if ("toys: " in prompt) answerFrom(prompt, mapOf("Lego" to "toys")) else answerFrom(prompt, emptyMap())
        }
        val viewModel = startedViewModel()

        viewModel.createAppFolder(
            viewModel.uiState.value.filteredApps.first { it.name == "Lego" },
            "Toys",
            FolderIcon.ChildCare,
        )
        gate.complete(Unit)
        idle()

        val prompts = model.prompts
        assertTrue(prompts.size >= 2)
        assertTrue(prompts.last().lines().any { it.startsWith("toys: ") })
    }

    @Test
    fun wellKnownApps_areAskedAbout_whenTheUserHasFoldersOfTheirOwn() {
        // So GitHub can go in a folder the user calls Coding.
        seedApp("GitHub", "com.github.android")
        model.answer = { prompt -> answerFrom(prompt, mapOf("GitHub" to "coding")) }
        val viewModel = startedViewModel()

        viewModel.createAppFolder("Coding", FolderIcon.Code)
        idle()

        val coding = viewModel.uiState.value.appFolders.first { it.name == "Coding" }
        assertTrue(model.prompts.any { "GitHub (com.github.android; suggested code)" in it })
        assertEquals(coding.key, viewModel.uiState.value.appFolderAiAssignments[id(viewModel, "GitHub")])
    }

    @Test
    fun wellKnownApps_arentAskedAbout() {
        seedApp("Waze", "com.waze")
        seedApp("Notes", "com.example.notes")
        model.answer = { prompt -> answerFrom(prompt, emptyMap()) }

        startedViewModel()

        assertFalse(model.prompts.any { "Waze (com.waze" in it })
        assertTrue(model.prompts.any { "Notes (com.example.notes; suggested office)" in it })
    }

    @Test
    fun choicesFromOlderInstructions_areDroppedAtStart_soAppsAreSortedAgain() {
        // An update adds a folder (Home): what the AI chose without it goes.
        AppFolderStore(context).apply {
            forgetAiChoicesUnlessMadeBy(AI_SORT_PROMPT_VERSION + 1)
            assignByAi(mapOf("someone" to AppCategory.Tools.storageKey))
        }

        val viewModel = newViewModel()

        assertEquals(emptyMap<String, String>(), viewModel.uiState.value.appFolderAiAssignments)
        assertEquals(emptyMap<String, String>(), AppFolderStore(context).aiAssignments)
    }

    @Test
    fun anAppTheAnswerLeavesOut_isAskedAgain_thenFiledInOther() {
        seedApp("Fintastic", "com.example.fintastic")
        model.answer = { "Sure, here you go!" }

        val viewModel = startedViewModel()

        val fintastic = id(viewModel, "Fintastic")
        assertEquals(AI_SORT_MAX_MISSES, model.prompts.count { "Fintastic" in it })
        assertEquals(AppCategory.Other.storageKey, viewModel.uiState.value.appFolderAiAssignments[fintastic])
        assertEquals(AiSortPhase.Done, viewModel.uiState.value.aiSort.phase)
    }

    @Test
    fun resort_givesAnAppItsRetriesBack() {
        // Codex on PR #857: an app that used up its misses went straight to
        // Other on "Re-sort all apps" without being asked again.
        seedApp("Fintastic", "com.example.fintastic")
        // Answers every app but Monzo.
        model.answer = { prompt ->
            prompt.lines()
                .filter { Regex("""^\d+\. """).containsMatchIn(it) && "Fintastic" !in it }
                .joinToString("\n") { "${it.substringBefore('.')}: other" }
        }
        val viewModel = startedViewModel()
        assertEquals(AI_SORT_MAX_MISSES, model.prompts.count { "Fintastic" in it })

        viewModel.resortAppsWithAi()
        idle()

        assertEquals(2 * AI_SORT_MAX_MISSES, model.prompts.count { "Fintastic" in it })
    }

    @Test
    fun sortsInBatches() {
        repeat(AI_SORT_BATCH_SIZE + 3) { index -> seedApp("App $index", "com.example.app$index") }
        model.answer = { prompt -> answerFrom(prompt, (0 until AI_SORT_BATCH_SIZE + 3).associate { "App $it" to "tools" }) }

        val viewModel = startedViewModel()

        // 18 apps plus Type Launcher itself: two batches.
        assertEquals(2, model.prompts.size)
        assertEquals(AI_SORT_BATCH_SIZE + 4, viewModel.uiState.value.appFolderAiAssignments.size)
    }

    @Test
    fun waitsForTheForeground_folders_andTheSwitch() {
        seedApp("Fintastic", "com.example.fintastic")
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
        seedApp("Fintastic", "com.example.fintastic")
        model.status = AiModelStatus.Unavailable

        val viewModel = startedViewModel()

        assertEquals(AiSortPhase.Unavailable, viewModel.uiState.value.aiSort.phase)
        assertEquals(0, model.prompts.size)
    }

    @Test
    fun downloadsTheModelFirst() {
        seedApp("Fintastic", "com.example.fintastic")
        model.status = AiModelStatus.Downloadable

        startedViewModel()

        assertEquals(1, model.downloads)
        assertEquals(1, model.prompts.size)
    }

    @Test
    fun busy_waitsAndRetriesTheSameBatch() {
        seedApp("Fintastic", "com.example.fintastic")
        var calls = 0
        model.answer = { prompt ->
            calls++
            if (calls == 1) throw AiModelException.Busy(retryAfterMillis = null)
            answerFrom(prompt, mapOf("Fintastic" to "finance"))
        }

        val viewModel = startedViewModel()

        assertEquals(listOf(AI_SORT_FIRST_RETRY_MILLIS), waits)
        assertEquals(AiSortPhase.Done, viewModel.uiState.value.aiSort.phase)
    }

    @Test
    fun blockedInTheBackground_pauses_andCarriesOnAtTheNextReturn() {
        seedApp("Fintastic", "com.example.fintastic")
        model.answer = { throw AiModelException.Background() }
        val viewModel = startedViewModel()
        assertEquals(AiSortPhase.Paused, viewModel.uiState.value.aiSort.phase)

        model.answer = { prompt -> answerFrom(prompt, mapOf("Fintastic" to "finance")) }
        viewModel.setLauncherForeground(false)
        viewModel.setLauncherForeground(true)
        idle()

        assertEquals(AiSortPhase.Done, viewModel.uiState.value.aiSort.phase)
    }

    @Test
    fun paused_staysPausedThroughListRefreshes_untilTheNextReturn() {
        // An app-list refresh (here, a sort-order change) mustn't restart
        // sorting against a service that just refused it (Codex on PR #852).
        seedApp("Fintastic", "com.example.fintastic")
        model.answer = { throw AiModelException.QuotaExceeded() }
        val viewModel = startedViewModel()
        assertEquals(AiSortPhase.Paused, viewModel.uiState.value.aiSort.phase)
        val asked = model.prompts.size

        viewModel.setAppListSortOrder(AppListSortOrder.Alphabetical)
        idle()
        assertEquals(asked, model.prompts.size)

        model.answer = { prompt -> answerFrom(prompt, mapOf("Fintastic" to "finance")) }
        viewModel.setLauncherForeground(false)
        viewModel.setLauncherForeground(true)
        idle()
        assertEquals(AiSortPhase.Done, viewModel.uiState.value.aiSort.phase)
    }

    @Test
    fun resort_forgetsTheAisChoices_andAsksAgain() {
        seedApp("Fintastic", "com.example.fintastic")
        model.answer = { prompt -> answerFrom(prompt, mapOf("Fintastic" to "social")) }
        val viewModel = startedViewModel()
        assertEquals(AppCategory.Social.storageKey, viewModel.uiState.value.appFolderAiAssignments[id(viewModel, "Fintastic")])

        model.answer = { prompt -> answerFrom(prompt, mapOf("Fintastic" to "finance")) }
        viewModel.resortAppsWithAi()
        idle()

        assertEquals(2, model.prompts.size)
        assertEquals(AppCategory.Finance.storageKey, viewModel.uiState.value.appFolderAiAssignments[id(viewModel, "Fintastic")])
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
        seedApp("Fintastic", "com.example.fintastic")
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
                fileFirst.second.storageKey,
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

    // Answers "n: folder" for every numbered app line: the folder [folders]
    // names for its label, else other (Type Launcher itself, mostly).
    private fun answerFrom(prompt: String, folders: Map<String, String>): String =
        prompt.lines().mapNotNull { line ->
            val match = Regex("""^(\d+)\. (.+) \(""").find(line) ?: return@mapNotNull null
            "${match.groupValues[1]}: ${folders[match.groupValues[2]] ?: "other"}"
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
        var suspendBefore: suspend () -> Unit = {}
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
            suspendBefore()
            return answer(prompt)
        }
    }
}
