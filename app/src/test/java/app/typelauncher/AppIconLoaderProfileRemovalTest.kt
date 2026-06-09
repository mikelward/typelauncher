package app.typelauncher

import android.content.ComponentName
import android.content.Intent
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.os.Process
import android.os.UserHandle
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowLauncherApps

/**
 * Simulates the work profile being removed (or disabled) while an icon load
 * is in flight: `LauncherApps.getActivityList` throws `SecurityException`
 * once the queried user has left the caller's profile group. The exception
 * used to escape the loader's producer coroutine and re-throw out of
 * `Deferred.await()` inside every awaiting composition — a process crash.
 * The loader must degrade to a null bitmap (missing-icon placeholder).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], shadows = [AppIconLoaderProfileRemovalTest.RejectingShadowLauncherApps::class])
class AppIconLoaderProfileRemovalTest {

    @Implements(LauncherApps::class)
    class RejectingShadowLauncherApps : ShadowLauncherApps() {
        @Implementation
        public override fun getActivityList(
            packageName: String?,
            user: UserHandle?,
        ): List<LauncherActivityInfo> {
            throw SecurityException("user $user is not in the caller's profile group")
        }
    }

    @Test
    fun loadReturnsNullInsteadOfCrashingWhenProfileIsGone() = runBlocking {
        val component = ComponentName(
            "app.typelauncher.profilegone.chat",
            "app.typelauncher.profilegone.chat.LaunchActivity",
        )
        val app = InstalledApp(
            name = "Work Chat",
            packageName = component.packageName,
            launchIntent = Intent.makeMainActivity(component),
            user = Process.myUserHandle(),
            isWorkApp = true,
            launchWithLauncherApps = true,
        )

        val bitmap = AppIconLoader.load(
            context = ApplicationProvider.getApplicationContext(),
            app = app,
            sizePx = 48,
        )

        assertNull("a rejected profile lookup must degrade to no icon, not crash", bitmap)
    }
}
