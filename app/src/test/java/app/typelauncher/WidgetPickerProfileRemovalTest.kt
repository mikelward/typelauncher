package app.typelauncher

import android.content.Context
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertFalse
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
 * Simulates the work profile being removed while the widget picker loads:
 * `LauncherApps.getProfiles` still lists the dying profile, but
 * `UserManager.isQuietModeEnabled` already throws `SecurityException` because
 * the user has left the caller's profile group. `loadAvailableWidgets` must
 * skip the dying profile — mirroring the guard `loadInstalledApps` gained in
 * `LauncherViewModelProfileRemovalTest` — instead of letting the exception
 * escape `showWidgetPicker`'s coroutine and crash the launcher.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [36],
    shadows = [
        WidgetPickerProfileRemovalTest.DyingProfileShadowLauncherApps::class,
        WidgetPickerProfileRemovalTest.RejectingShadowUserManager::class,
    ],
)
class WidgetPickerProfileRemovalTest {
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

    @Implements(UserManager::class)
    class RejectingShadowUserManager : ShadowUserManager() {
        @Implementation
        public override fun isQuietModeEnabled(userHandle: UserHandle): Boolean {
            if (userHandle == dyingWorkHandle) {
                throw SecurityException("user $userHandle is not in the caller's profile group")
            }
            return super.isQuietModeEnabled(userHandle)
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
    fun widgetPickerSkipsDyingProfileInsteadOfCrashing() {
        val viewModel = LauncherViewModel(
            app = ApplicationProvider.getApplicationContext(),
            workPackages = emptySet(),
            ioDispatcher = Dispatchers.Unconfined,
        )
        shadowOf(Looper.getMainLooper()).idle()

        viewModel.showWidgetPicker()
        // Before the fix this idle re-threw the SecurityException out of
        // `showWidgetPicker`'s load coroutine — a launcher process crash the
        // moment the user tapped "Add widget" during work-profile teardown.
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(
            "the picker must stay open once the dying profile is skipped",
            viewModel.uiState.value.isAddingWidget,
        )
        assertFalse(
            "the provider load must complete instead of crashing",
            viewModel.uiState.value.isLoadingAvailableWidgets,
        )
    }

    private companion object {
        // Robolectric's environment only has the personal user, so fabricate a
        // second UserHandle for the dying work profile via the Int constructor
        // (same approach as LauncherViewModelProfileRemovalTest).
        private val dyingWorkHandle: UserHandle by lazy {
            val constructor = UserHandle::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType)
            constructor.isAccessible = true
            constructor.newInstance(10)
        }
    }
}
