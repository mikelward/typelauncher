package app.typelauncher

import android.content.Context
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.os.Looper
import android.os.Process
import android.os.UserHandle
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

/**
 * Simulates the work profile being removed while an installed-apps load is in
 * flight: `LauncherApps.getProfiles` still lists the dying profile, but
 * `getActivityList` (and `UserManager.isQuietModeEnabled`) already throw
 * `SecurityException` because the user has left the caller's profile group.
 * The load must skip the dying profile and keep the remaining profiles' apps
 * instead of letting the exception escape the load coroutine and crash the
 * launcher — the same race `AppIconLoaderProfileRemovalTest` pins for icons.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], shadows = [LauncherViewModelProfileRemovalTest.DyingProfileShadowLauncherApps::class])
class LauncherViewModelProfileRemovalTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Implements(LauncherApps::class)
    class DyingProfileShadowLauncherApps : ShadowLauncherApps() {
        // ShadowLauncherApps doesn't shadow getProfiles (the framework
        // delegates to UserManager), so this is a fresh @Implementation
        // rather than an override.
        @Implementation
        protected fun getProfiles(): List<UserHandle> =
            listOf(Process.myUserHandle(), dyingWorkHandle)

        @Implementation
        public override fun getActivityList(
            packageName: String?,
            user: UserHandle?,
        ): List<LauncherActivityInfo> {
            if (user == dyingWorkHandle) {
                throw SecurityException("user $user is not in the caller's profile group")
            }
            return super.getActivityList(packageName, user)
        }
    }

    @After
    fun clearPrefs() {
        listOf("docked_apps", "dock_settings", "app_launch_stats", "widgets", "app_metadata")
            .forEach { name ->
                context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
            }
    }

    @Test
    fun loadSkipsDyingProfileInsteadOfCrashing() {
        val viewModel = LauncherViewModel(
            app = ApplicationProvider.getApplicationContext(),
            workPackages = emptySet(),
            ioDispatcher = Dispatchers.Unconfined,
        )

        // Before the fix this idle re-threw the SecurityException out of the
        // cold-start load coroutine — a launcher process crash mid-teardown.
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(
            "the cold-start load must complete once the dying profile is skipped",
            viewModel.uiState.value.isFreshAppLoadComplete,
        )
    }

    private companion object {
        // Robolectric's environment only has the personal user, so fabricate a
        // second UserHandle for the dying work profile via the Int constructor
        // (same approach as WorkDockPrefillTest).
        private val dyingWorkHandle: UserHandle by lazy {
            val constructor = UserHandle::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType)
            constructor.isAccessible = true
            constructor.newInstance(10)
        }
    }
}
