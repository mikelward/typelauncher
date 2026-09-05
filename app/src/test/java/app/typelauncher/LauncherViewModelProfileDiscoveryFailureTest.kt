package app.typelauncher

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.ResolveInfo
import android.os.BadParcelableException
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import androidx.test.core.app.ApplicationProvider
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
        idle()

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
        idle()
        viewModel.setWorkDockEnabled(true)
        idle()

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
        idle()

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

    private companion object {
        // Robolectric's environment only has the personal user, so fabricate a
        // second UserHandle for the work profile via the Int constructor.
        private val workHandle: UserHandle by lazy {
            val constructor = UserHandle::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType)
            constructor.isAccessible = true
            constructor.newInstance(10)
        }
    }
}
