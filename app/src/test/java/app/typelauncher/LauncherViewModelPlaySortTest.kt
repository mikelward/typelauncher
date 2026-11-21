package app.typelauncher

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** "Sort using Google Play" through the view model, against a stand-in Google Play. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LauncherViewModelPlaySortTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    private val answers = HashMap<String, PlayLookup>()
    private val asked = mutableListOf<String>()
    private val waits = mutableListOf<Long>()
    private val fromPlay = HashSet<String>()

    @After
    fun clearPrefs() {
        listOf(
            AppFolderStore.PREFERENCES_NAME,
            PlaySortStore.PREFERENCES_NAME,
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
    fun offersOnlyAppsTheRulesDontPlace_andListsOthersAsNotFromPlay() {
        seedApp("Thermo", "com.example.thermo", installedFromPlay = true)
        seedApp("Sideload", "com.example.sideload", installedFromPlay = false)
        // A well-known app the rules place is never offered.
        seedApp("Netflix", "com.netflix.mediaclient", installedFromPlay = true)
        val viewModel = startedViewModel()

        val playSort = viewModel.uiState.value.playSort
        assertTrue(playSort.showOffer)
        assertEquals(listOf("com.example.thermo"), playSort.offered.map { it.app.packageName })
        assertEquals(
            setOf("com.example.thermo", "com.example.sideload"),
            playSort.candidates.map { it.app.packageName }.toSet(),
        )
        assertTrue("nothing is looked up before the user agrees", asked.isEmpty())
    }

    @Test
    fun offersOnlyWhatOnDeviceSortingLeftInOther() {
        seedApp("Thermo", "com.example.thermo", installedFromPlay = true)
        // Its manifest says audio, so it's in Music already: not offered.
        seedApp("Tunes", "com.example.tunes", installedFromPlay = true, category = ApplicationInfo.CATEGORY_AUDIO)
        val viewModel = startedViewModel()

        assertEquals(listOf("com.example.thermo"), viewModel.uiState.value.playSort.offered.map { it.app.packageName })
    }

    @Test
    fun waitsForTheAiToFinish_thenOffersWhatItCouldntPlace() {
        seedApp("Thermo", "com.example.thermo", installedFromPlay = true)
        seedApp("Fintastic", "com.example.fintastic", installedFromPlay = true)
        val answer = kotlinx.coroutines.CompletableDeferred<String>()
        val model = object : AppCategoryModel {
            override suspend fun status() = AiModelStatus.Available
            override fun download() = kotlinx.coroutines.flow.emptyFlow<Long>()
            override suspend fun generate(prompt: String): String {
                val reply = answer.await()
                return prompt.lines().mapNotNull { line ->
                    val match = Regex("""^(\d+)\. (.+) \(""").find(line) ?: return@mapNotNull null
                    "${match.groupValues[1]}: ${if (match.groupValues[2] == "Fintastic") reply else "other"}"
                }.joinToString("\n")
            }
        }
        val viewModel = startedViewModel(aiModel = model)
        viewModel.setAiSortEnabled(true)
        idle()
        assertFalse("not while the AI is sorting", viewModel.uiState.value.playSort.showOffer)

        answer.complete("finance")
        idle()

        val playSort = viewModel.uiState.value.playSort
        assertTrue(playSort.showOffer)
        assertEquals(listOf("com.example.thermo"), playSort.offered.map { it.app.packageName })
    }

    @Test
    fun sortingLooksUpOnlyTheChosenApps_filesThem_andReportsBack() {
        seedApp("Thermo", "com.example.thermo", installedFromPlay = true)
        seedApp("Oddity", "com.example.oddity", installedFromPlay = true)
        seedApp("Kept", "com.example.kept", installedFromPlay = true)
        answers["com.example.thermo"] = PlayLookup.Found("HOUSE_AND_HOME")
        answers["com.example.oddity"] = PlayLookup.Found("LIFESTYLE")
        val viewModel = startedViewModel()

        viewModel.startPlaySort(listOf("com.example.thermo", "com.example.oddity"))
        idle()

        val state = viewModel.uiState.value
        assertEquals(listOf("com.example.thermo", "com.example.oddity"), asked)
        assertEquals(mapOf("com.example.thermo" to "HOUSE_AND_HOME", "com.example.oddity" to "LIFESTYLE"), state.playCategories)
        assertEquals(PlaySortPhase.Done, state.playSort.phase)
        assertEquals(1, state.playSort.sorted)
        assertEquals(1, state.playSort.unsorted)
        assertTrue(state.playSort.hasUsed)
        // Both are there to review, placed or not.
        assertEquals(setOf("com.example.thermo", "com.example.oddity"), state.playSort.reviewed.map { it.packageName }.toSet())
        val thermo = state.filteredApps.first { it.name == "Thermo" }
        assertEquals(
            AppCategory.Home.storageKey,
            effectiveFolderKey(thermo, state.appFolders.mapTo(HashSet()) { it.key }, state.appFolderAssignments, playCategories = state.playCategories),
        )
        // The unticked app is still on offer; the looked-up ones aren't.
        assertEquals(listOf("com.example.kept"), state.playSort.offered.map { it.app.packageName })
        // Kept for the next launch.
        assertEquals("HOUSE_AND_HOME", PlaySortStore(context).categories["com.example.thermo"])
        viewModel.acknowledgePlaySortResult()
        assertEquals(PlaySortPhase.Idle, viewModel.uiState.value.playSort.phase)
    }

    @Test
    fun slowDown_pausesThenTriesTheSameAppAgain() {
        seedApp("Thermo", "com.example.thermo", installedFromPlay = true)
        var calls = 0
        val viewModel = startedViewModel(fetch = { packageName ->
            calls++
            if (calls == 1) PlayLookup.SlowDown else PlayLookup.Found("HOUSE_AND_HOME").also { asked += packageName }
        })

        viewModel.startPlaySort(listOf("com.example.thermo"))
        idle()

        assertEquals(2, calls)
        assertTrue(PLAY_SORT_RETRY_MILLIS in waits)
        assertEquals("HOUSE_AND_HOME", viewModel.uiState.value.playCategories["com.example.thermo"])
        assertEquals(PlaySortPhase.Done, viewModel.uiState.value.playSort.phase)
    }

    @Test
    fun notNow_hidesTheOfferUntilAnotherAppArrives() {
        seedApp("Thermo", "com.example.thermo", installedFromPlay = true)
        val viewModel = startedViewModel()

        viewModel.dismissPlaySortOffer()
        assertFalse(viewModel.uiState.value.playSort.showOffer)
        // Folders changing (any refresh) doesn't bring it back for the same apps.
        viewModel.setAppFoldersEnabled(true)
        idle()
        assertFalse(viewModel.uiState.value.playSort.showOffer)
        assertTrue(asked.isEmpty())
    }

    @Test
    fun sortNewApps_looksThemUpWithoutAskingOrReporting() {
        seedApp("Thermo", "com.example.thermo", installedFromPlay = true)
        answers["com.example.thermo"] = PlayLookup.Found("HOUSE_AND_HOME")
        val viewModel = startedViewModel()

        viewModel.setPlaySortAutoEnabled(true)
        idle()

        assertEquals(listOf("com.example.thermo"), asked)
        assertFalse(viewModel.uiState.value.playSort.showOffer)
        assertEquals(PlaySortPhase.Idle, viewModel.uiState.value.playSort.phase)
        assertEquals("HOUSE_AND_HOME", viewModel.uiState.value.playCategories["com.example.thermo"])
        assertTrue("a silent run has nothing to review", viewModel.uiState.value.playSort.reviewed.isEmpty())
    }

    @Test
    fun sortNewApps_neverSendsAnAppTheUserUnticked() {
        seedApp("Thermo", "com.example.thermo", installedFromPlay = true)
        seedApp("Private", "com.example.private", installedFromPlay = true)
        answers["com.example.thermo"] = PlayLookup.Found("HOUSE_AND_HOME")
        val viewModel = startedViewModel()
        viewModel.startPlaySort(listOf("com.example.thermo"), unticked = listOf("com.example.private"))
        idle()

        viewModel.setPlaySortAutoEnabled(true)
        idle()

        assertEquals(listOf("com.example.thermo"), asked)
        assertFalse(viewModel.uiState.value.playSort.showOffer)
    }

    @Test
    fun sortAgain_withSortNewAppsOn_stillNeverSendsAnUntickedApp() {
        seedApp("Thermo", "com.example.thermo", installedFromPlay = true)
        seedApp("Private", "com.example.private", installedFromPlay = true)
        answers["com.example.thermo"] = PlayLookup.Found("HOUSE_AND_HOME")
        val viewModel = startedViewModel()
        viewModel.startPlaySort(listOf("com.example.thermo"), unticked = listOf("com.example.private"))
        idle()
        viewModel.setPlaySortAutoEnabled(true)
        idle()
        asked.clear()

        viewModel.resortWithPlay()
        idle()

        assertFalse("com.example.private" in asked)
        // It's offered again, still unticked, for the user to decide.
        val playSort = viewModel.uiState.value.playSort
        assertTrue(playSort.showOffer)
        assertTrue(playSort.candidates.first { it.app.packageName == "com.example.private" }.declined)
    }

    @Test
    fun anAiThatStopsShort_stillLetsTheOfferThrough() {
        seedApp("Thermo", "com.example.thermo", installedFromPlay = true)
        val model = object : AppCategoryModel {
            override suspend fun status() = AiModelStatus.Available
            override fun download() = kotlinx.coroutines.flow.emptyFlow<Long>()
            override suspend fun generate(prompt: String): String = throw AiModelException.QuotaExceeded()
        }
        val viewModel = startedViewModel(aiModel = model)
        viewModel.setAiSortEnabled(true)
        idle()

        assertEquals(AiSortPhase.Paused, viewModel.uiState.value.aiSort.phase)
        assertTrue(viewModel.uiState.value.playSort.showOffer)
    }

    @Test
    fun sortAgain_forgetsTheAnswers_andOffersThemAgain() {
        seedApp("Thermo", "com.example.thermo", installedFromPlay = true)
        answers["com.example.thermo"] = PlayLookup.Found("HOUSE_AND_HOME")
        val viewModel = startedViewModel()
        viewModel.startPlaySort(listOf("com.example.thermo"))
        idle()

        viewModel.resortWithPlay()
        idle()

        assertTrue(viewModel.uiState.value.playCategories.isEmpty())
        assertTrue(viewModel.uiState.value.playSort.showOffer)
        assertTrue(PlaySortStore(context).categories.isEmpty())
    }

    private fun startedViewModel(
        fetch: (suspend (String) -> PlayLookup)? = null,
        aiModel: AppCategoryModel? = null,
    ): LauncherViewModel {
        val viewModel = LauncherViewModel(
            app = ApplicationProvider.getApplicationContext(),
            workPackages = emptySet(),
            ioDispatcher = Dispatchers.Unconfined,
            aiModelFactory = { aiModel ?: error("no AI in this test") },
            aiCorePresent = { aiModel != null },
            playCategoryFetcher = PlayCategoryFetcher { packageName ->
                fetch?.invoke(packageName) ?: run {
                    asked += packageName
                    answers[packageName] ?: PlayLookup.NotListed
                }
            },
            installerOf = { packageName -> if (packageName in fromPlay) GOOGLE_PLAY_INSTALLER else null },
            playSortDelay = { millis -> waits += millis },
        )
        idle()
        viewModel.setAppFoldersEnabled(true)
        viewModel.setLauncherForeground(true)
        idle()
        return viewModel
    }

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun seedApp(
        label: String,
        packageName: String,
        installedFromPlay: Boolean,
        category: Int = ApplicationInfo.CATEGORY_UNDEFINED,
    ) {
        if (installedFromPlay) fromPlay += packageName
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
}
