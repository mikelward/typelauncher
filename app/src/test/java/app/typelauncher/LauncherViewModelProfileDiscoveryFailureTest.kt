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

    /** Two profiles, and a buffer failure that takes both enumerations. */
    @Implements(LauncherApps::class)
    class UnreadableTwoProfileShadowLauncherApps : ShadowLauncherApps() {
        @Implementation
        protected fun getProfiles(): List<UserHandle> = listOf(Process.myUserHandle(), workHandle)

        @Implementation
        public override fun getActivityList(
            packageName: String?,
            user: UserHandle?,
        ): List<LauncherActivityInfo> =
            throw BadParcelableException("Failure retrieving array; only received 332 of 490")
    }

    /**
     * A device whose failure keeps changing shape. Every other *load* fails
     * its enumeration — degraded, nothing published — and the loads in
     * between enumerate fine but cannot read quiet mode, which publishes and
     * defers only the work-dock seed. Each read is therefore a different
     * kind of incomplete from the one before it. The profile listing is what
     * counts the loads: it runs exactly once per read.
     */
    @Implements(LauncherApps::class)
    class AlternatingFailureShadowLauncherApps : ShadowLauncherApps() {
        @Implementation
        protected fun getProfiles(): List<UserHandle> {
            loads.incrementAndGet()
            return listOf(Process.myUserHandle(), workHandle)
        }

        @Implementation
        public override fun getActivityList(
            packageName: String?,
            user: UserHandle?,
        ): List<LauncherActivityInfo> {
            if (loads.get() % 2 == 1) {
                throw BadParcelableException("Failure retrieving array; only received 332 of 490")
            }
            return emptyList()
        }

        companion object {
            @JvmStatic
            val loads = AtomicInteger(0)
        }
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
        AlternatingFailureShadowLauncherApps.loads.set(0)
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
        idleFor(RETRY_DRAIN_MILLIS)

        assertTrue(
            "Cold start must complete rather than crash out of the load",
            viewModel.uiState.value.isFreshAppLoadComplete,
        )
        assertTrue(
            "The last attempt must recover the personal apps through PackageManager",
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
        idleFor(RETRY_DRAIN_MILLIS)

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
        idleFor(RETRY_DRAIN_MILLIS)
        viewModel.setWorkDockEnabled(true)
        idleFor(RETRY_DRAIN_MILLIS)

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
        idleFor(RETRY_DRAIN_MILLIS)

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
    fun aGuessedQuietModeQueuesItsOwnRetryUntilTheStateIsReadable() {
        // Deferring the seed is only half an answer: the read published, so
        // nothing marks it degraded and no retry follows it, and toggling the
        // dock later re-reads the same latched verdict. Left there, a
        // transient binder failure costs the work dock its seed until some
        // unrelated package or profile event happens along.
        seedApp("Chrome", "com.android.chrome")
        val viewModel = newViewModel(workPackages = setOf("com.android.chrome"))
        idle()
        viewModel.setWorkDockEnabled(true)
        idle()
        assertTrue(
            "the test must observe the deferred seed to mean anything",
            viewModel.uiState.value.workDockedApps.isEmpty(),
        )

        // No package event, no profile broadcast — only the buffer
        // recovering, which is what the queued retry is waiting for.
        UnreadableQuietModeShadowUserManager.failQuietMode = false
        idleFor(RETRY_DRAIN_MILLIS)

        assertTrue(
            "the read that guessed must retry until the seed can be made: " +
                "${LauncherDebugLog.snapshot()}",
            viewModel.uiState.value.workDockedApps.any { it.packageName == "com.android.chrome" },
        )
    }

    @Test
    @Config(sdk = [36], shadows = [UnreadableTwoProfileShadowLauncherApps::class])
    fun aWorkProfileStillMissingDoesNotHoldBackThePersonalDockSeed() {
        // Cold start recovers the personal profile through PackageManager but
        // has no way to enumerate the work one, so the read stays degraded —
        // and nothing retries a degraded read once cold start's attempts are
        // spent. The personal dock seeds from personal apps only, so gating
        // it on the aggregate verdict would leave a first run's dock empty
        // over a profile that dock never reads.
        seedApp("Maps", "com.google.android.apps.maps")

        val viewModel = newViewModel()
        idleFor(RETRY_DRAIN_MILLIS)

        assertTrue(
            "the personal dock seeds from a read whose only gap is a work profile",
            viewModel.uiState.value.dockedApps.any { it.packageName == "com.google.android.apps.maps" },
        )
    }

    @Test
    @Config(
        sdk = [36],
        shadows = [
            AlternatingFailureShadowLauncherApps::class,
            UnreadableQuietModeShadowUserManager::class,
        ],
    )
    fun aFailureThatChangesShapeStillRunsOutOfAttempts() {
        // Two kinds of incomplete read, each with its own budget, let a
        // device that alternates between them reset the other budget on
        // every transition — an unbounded chain reissuing the very
        // enumeration it is recovering from, for as long as the failure
        // lasts. One budget across both is what bounds it.
        seedApp("Maps", "com.google.android.apps.maps")
        // The buffer is process-wide, so an earlier test's loads would be
        // counted below.
        LauncherDebugLog.resetForTest()
        val viewModel = newViewModel()

        idleFor(LONG_DRAIN_MILLIS)

        val loads = LauncherDebugLog.snapshot().count { it.contains("loadInstalledApps begin") }
        assertTrue(
            "the recovery chain must run out of attempts however the failure changes shape, " +
                "but it ran $loads loads",
            loads <= MAX_EXPECTED_LOADS,
        )
        // The launcher is still alive and publishing, not merely quiet.
        assertTrue(
            "cold start must still complete",
            viewModel.uiState.value.isFreshAppLoadComplete,
        )
    }

    private fun newViewModel(
        workPackages: Set<String> = emptySet(),
    ): LauncherViewModel = LauncherViewModel(
        app = ApplicationProvider.getApplicationContext(),
        workPackages = workPackages,
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

    /** Drains the main looper past the degraded-load retry delays. */
    private fun idleFor(millis: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))
    }

    private companion object {
        // Three attempts, each waiting DEGRADED_LOAD_RETRY_MILLIS, plus slack.
        private const val RETRY_DRAIN_MILLIS = 2_000L

        // Far longer than any bounded chain needs, so an unbounded one has
        // room to show itself.
        private const val LONG_DRAIN_MILLIS = 120_000L

        // Cold start's three attempts, plus the reload chain's three, plus
        // slack for the retry it schedules on publishing. An unbounded chain
        // runs well past this within the drain above.
        private const val MAX_EXPECTED_LOADS = 10

        // Robolectric's environment only has the personal user, so fabricate a
        // second UserHandle for the work profile via the Int constructor.
        private val workHandle: UserHandle by lazy {
            val constructor = UserHandle::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType)
            constructor.isAccessible = true
            constructor.newInstance(10)
        }
    }
}
