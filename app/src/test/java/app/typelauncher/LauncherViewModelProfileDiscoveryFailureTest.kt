package app.typelauncher

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.ResolveInfo
import android.os.BadParcelableException
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import androidx.test.core.app.ApplicationProvider
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowLauncherApps
import org.robolectric.shadows.ShadowUserManager

/**
 * Pins the two binder calls that run *before* the per-profile guard: listing
 * the profiles, and reading each one's quiet-mode state. A full binder buffer
 * doesn't pick which transaction it fails, so guarding only the enumeration
 * left these two able to take the launcher down from inside the very load
 * that is supposed to survive it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LauncherViewModelProfileDiscoveryFailureTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

    /** `getProfiles` failing the way a full binder buffer fails it. */
    @Implements(LauncherApps::class)
    class UnreadableProfilesShadowLauncherApps : ShadowLauncherApps() {
        @Implementation
        protected fun getProfiles(): List<UserHandle> =
            throw BadParcelableException("Failure retrieving array; only received 332 of 490")
    }

    /** Profiles list fine; the quiet-mode read behind them does not. */
    @Implements(LauncherApps::class)
    class TwoProfileShadowLauncherApps : ShadowLauncherApps() {
        @Implementation
        protected fun getProfiles(): List<UserHandle> = listOf(Process.myUserHandle(), workHandle)
    }

    @Implements(UserManager::class)
    class UnreadableQuietModeShadowUserManager : ShadowUserManager() {
        @Implementation
        override fun isQuietModeEnabled(user: UserHandle?): Boolean {
            if (failQuietMode) {
                throw BadParcelableException("Failure retrieving array; only received 332 of 490")
            }
            return false
        }

        companion object {
            @Volatile
            @JvmStatic
            var failQuietMode = true
        }
    }

    @After
    fun reset() {
        UnreadableQuietModeShadowUserManager.failQuietMode = true
        listOf("docked_apps", "work_docked_apps", "dock_settings", "app_launch_stats", "widgets", "app_metadata")
            .forEach { name ->
                context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
            }
    }

    @Test
    @Config(sdk = [36], shadows = [UnreadableProfilesShadowLauncherApps::class])
    fun anUnreadableProfileListFallsBackInsteadOfCrashing() {
        seedApp("Mail", "com.example.mail")

        val viewModel = newViewModel()
        // Before the fix this re-threw out of the cold-start load coroutine,
        // which is a launcher process crash.
        idleThroughRecovery()

        assertTrue(
            "Cold start must complete rather than crash out of the load",
            viewModel.uiState.value.isFreshAppLoadComplete,
        )
        assertTrue(
            "The PackageManager fallback must still surface the personal apps",
            viewModel.uiState.value.filteredApps.map { it.name }.contains("Mail"),
        )
    }

    @Test
    @Config(
        sdk = [36],
        shadows = [
            TwoProfileShadowLauncherApps::class,
            UnreadableQuietModeShadowUserManager::class,
        ],
    )
    fun anUnreadableQuietModeStillPublishesTheAppsItCanRead() {
        // A guessed paused badge is worth far less than the whole app list, so
        // this failure degrades nothing — it just must not escape the load.
        seedApp("Mail", "com.example.mail")

        val viewModel = newViewModel()
        idle()

        assertTrue(
            "Cold start must complete rather than crash out of the load",
            viewModel.uiState.value.isFreshAppLoadComplete,
        )
        assertTrue(
            "An unreadable quiet-mode flag must not withhold the app list",
            viewModel.uiState.value.filteredApps.map { it.name }.contains("Mail"),
        )
    }

    @Test
    @Config(
        sdk = [36],
        shadows = [
            TwoProfileShadowLauncherApps::class,
            UnreadableQuietModeShadowUserManager::class,
        ],
    )
    fun aGuessedQuietModeDoesNotLatchTheWorkDockSeed() {
        // Seeding the work dock is a once-ever decision: it latches, and a
        // paused profile is *supposed* to defer it until the profile resumes.
        // So a guessed "not paused" must not seed a dock of apps the user
        // cannot open and then latch the real seed away for good.
        seedApp("Chrome", "com.android.chrome")
        seedApp("Maps", "com.google.android.apps.maps")
        val viewModel = newViewModel(workPackages = setOf("com.android.chrome"))
        idleThroughRecovery()
        viewModel.setWorkDockEnabled(true)
        idleThroughRecovery()

        assertTrue(
            "a load that guessed the paused state must not seed the work dock",
            viewModel.uiState.value.workDockedApps.isEmpty(),
        )
        // The personal dock has no stake in a work profile's paused state, so
        // it seeds as usual — leaving a first run's dock empty over a display
        // flag would be the worse failure.
        assertTrue(
            "the personal dock must still seed from a read it does not depend on",
            viewModel.uiState.value.dockedApps.any { it.packageName == "com.google.android.apps.maps" },
        )

        // And the seed must still be waiting once the state is readable
        // again, which reaches the view model as a reload.
        UnreadableQuietModeShadowUserManager.failQuietMode = false
        context.sendBroadcast(Intent(Intent.ACTION_MANAGED_PROFILE_AVAILABLE))
        idleThroughRecovery()

        assertTrue(
            "the next readable load must still be able to seed it",
            viewModel.uiState.value.workDockedApps.any { it.packageName == "com.android.chrome" },
        )
    }

    @Test
    @Config(
        sdk = [36],
        shadows = [
            TwoProfileShadowLauncherApps::class,
            UnreadableQuietModeShadowUserManager::class,
        ],
    )
    fun aColdStartWithTheWorkDockAlreadyOnDoesNotSeedOnAGuessedPausedState() {
        // The dock being *already* enabled is the case cold start has to get
        // right on its own: the seed runs from the load itself rather than
        // from the switch, so whatever it consults has to describe the load
        // it is seeding from — not the one before it.
        seedApp("Chrome", "com.android.chrome")
        val firstLaunch = newViewModel(workPackages = setOf("com.android.chrome"))
        idle()
        firstLaunch.setWorkDockEnabled(true)
        idle()

        // A fresh launch with the setting persisted on and the paused state
        // still unreadable.
        val relaunch = newViewModel(workPackages = setOf("com.android.chrome"))
        idle()

        assertTrue(
            "cold start must not seed the work dock from a guessed paused state",
            relaunch.uiState.value.workDockedApps.isEmpty(),
        )
    }

    @Test
    @Config(
        sdk = [36],
        shadows = [
            TwoProfileShadowLauncherApps::class,
            UnreadableQuietModeShadowUserManager::class,
        ],
    )
    fun aProfileReadOnAnEarlierAttemptStillCountsTowardTheMergedRead() {
        // The point of retrying: neither attempt below reads both profiles,
        // but between them they read each one, and a merge that holds every
        // profile the read listed is a complete inventory however many
        // transactions assembling it took. Complete is what licenses the
        // once-ever decisions — here the dock seed, which a degraded read
        // must refuse because a dock seeded wrong stays wrong forever.
        //
        // Recovery runs as reloads, not as a loop inside the cold start: the
        // cold start publishes its first read immediately and hands the rest
        // to `scheduleReload`, which is serialized against every other
        // reload. So the two attempts that between them cover both profiles
        // are the recovery reload and its retry.
        UnreadableQuietModeShadowUserManager.failQuietMode = false
        // A package the one-time dock prefill actually seeds from.
        seedApp("Chrome", "com.android.chrome")
        val personalUser = Process.myUserHandle()
        val enumerations = AtomicInteger(0)
        val viewModel = newViewModel { _, user ->
            // Two profiles per attempt, so every second call starts a round.
            val round = enumerations.getAndIncrement() / 2
            val personal = user == personalUser
                val readable = when (round) {
                // The cold start reads nothing, publishes what the
                // `PackageManager` fallback gives it, and schedules
                // recovery.
                0 -> false
                // The recovery reload gets the personal profile...
                1 -> personal
                // ...and its retry gets the work one.
                else -> !personal
            }
            if (!readable) {
                throw BadParcelableException("Failure retrieving array; only received 332 of 490")
            }
            emptyList()
        }
        idleThroughRecovery()

        assertTrue(
            "Cold start must complete rather than crash out of the load",
            viewModel.uiState.value.isFreshAppLoadComplete,
        )
        assertEquals(
            "Three attempts, two profiles each, and none beyond the one that completed the merge",
            6,
            enumerations.get(),
        )
        assertTrue(
            "A merge covering every listed profile must license the dock seed",
            viewModel.uiState.value.dockedApps.isNotEmpty(),
        )
    }

    @Test
    @Config(
        sdk = [36],
        shadows = [
            TwoProfileShadowLauncherApps::class,
            UnreadableQuietModeShadowUserManager::class,
        ],
    )
    fun aGuessedPausedStateKeepsRecoveringUntilItCanBeRead() {
        // A read can come back with every app and still have guessed the work
        // profile's paused state — the enumeration succeeds, the separate
        // `isQuietModeEnabled` call does not. Nothing is degraded, so recovery
        // used to stop there, and the work dock's once-ever seed then waited on
        // an unrelated later event: the same staleness this change exists to
        // end, reached from the other direction.
        UnreadableQuietModeShadowUserManager.failQuietMode = true
        // A package the work dock's seed actually takes.
        seedApp("Chrome", "com.android.chrome")
        val enumerations = AtomicInteger(0)
        val viewModel = newViewModel(
            workPackages = setOf("com.android.chrome"),
        ) { _, _ ->
            // Two profiles per attempt, so this lets the paused state become
            // readable for the attempt after the cold start's.
            if (enumerations.incrementAndGet() >= 2) {
                UnreadableQuietModeShadowUserManager.failQuietMode = false
            }
            emptyList()
        }
        idleThroughRecovery()

        viewModel.setWorkDockEnabled(true)
        idleThroughRecovery()

        assertTrue(
            "recovery must keep going for a guessed paused state, so the seed can be taken",
            viewModel.uiState.value.workDockedApps.any { it.packageName == "com.android.chrome" },
        )
    }

    @Test
    @Config(
        sdk = [36],
        shadows = [
            TwoProfileShadowLauncherApps::class,
            UnreadableQuietModeShadowUserManager::class,
        ],
    )
    fun aPausedStateThatNeverBecomesReadableStopsRetrying() {
        // The bound has to hold for the quiet-mode retry as much as for the
        // degraded one. Recovering for a guessed paused state runs down the
        // same per-event budget, so a work profile whose `isQuietModeEnabled`
        // keeps throwing must stop rather than scheduling a full app-list
        // read every 300 ms for as long as the failure lasts — which would
        // add to the very binder pressure this path exists to contain.
        UnreadableQuietModeShadowUserManager.failQuietMode = true
        seedApp("Chrome", "com.android.chrome")
        val enumerations = AtomicInteger(0)
        val viewModel = newViewModel(
            workPackages = setOf("com.android.chrome"),
        ) { _, _ ->
            enumerations.incrementAndGet()
            emptyList()
        }
        idleThroughRecovery()

        assertEquals(
            // Two profiles per read; the cold start's read plus the recovery
            // reload's three (its own, then the two retries the budget buys).
            "recovery must stop once its budget is spent, not retry forever",
            8,
            enumerations.get(),
        )
        assertTrue(
            "and nothing may be left queued behind it",
            !viewModel.isReloadInFlight,
        )
    }

    private fun newViewModel(
        workPackages: Set<String> = emptySet(),
        enumerate: (LauncherApps, UserHandle) -> List<LauncherActivityInfo> =
            { launcherApps, user -> launcherApps.getActivityList(null, user) },
    ): LauncherViewModel = LauncherViewModel(
        app = ApplicationProvider.getApplicationContext(),
        workPackages = workPackages,
        ioDispatcher = Dispatchers.Unconfined,
        enumerateLauncherActivities = enumerate,
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

    /**
     * Drains the main looper, advancing past the backoff a degraded read now
     * schedules its retry behind. Robolectric fast-forwards virtual time
     * rather than sleeping, so this stays deterministic: a clock the test
     * advances, not a wait it hopes is long enough.
     */
    private fun idleThroughRecovery() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(RECOVERY_DRAIN_MILLIS))
    }

    private companion object {
        // Past a whole recovery — a degraded read plus two retries, so
        // 300ms + 600ms of backoff — with slack, so a test drains every
        // attempt rather than a subset and then asserts on a half-finished
        // recovery.
        const val RECOVERY_DRAIN_MILLIS = 2_000L

        // Robolectric's environment only has the personal user, so fabricate a
        // second UserHandle for the work profile via the Int constructor.
        private val workHandle: UserHandle by lazy {
            val constructor = UserHandle::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType)
            constructor.isAccessible = true
            constructor.newInstance(10)
        }
    }
}
