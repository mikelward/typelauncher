package app.typelauncher

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.LauncherUserInfo
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import androidx.test.core.app.ApplicationProvider
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

/**
 * The picker classifies a profile as work through `LauncherApps`, a Binder
 * call. The answer belongs to the profile, so it is read once per profile,
 * not once per provider: a work profile with dozens of widgets must not cost
 * dozens of IPCs on the picker's load, and a lookup that fails partway must
 * not split one profile's providers between a work group and a plain one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], shadows = [WidgetPickerProfileKindLookupTest.CountingShadowLauncherApps::class])
class WidgetPickerProfileKindLookupTest {
    @Implements(LauncherApps::class)
    class CountingShadowLauncherApps : ShadowLauncherApps() {
        @Implementation
        protected fun getProfiles(): List<UserHandle> = listOf(Process.myUserHandle(), workHandle)

        @Implementation
        protected fun getLauncherUserInfo(user: UserHandle): LauncherUserInfo? {
            lookups += user
            // LauncherUserInfo.Builder is not in the public SDK stubs, but the
            // framework class Robolectric runs against has it.
            val builderClass = Class.forName("android.content.pm.LauncherUserInfo\$Builder")
            val builder = builderClass
                .getConstructor(String::class.java, Int::class.javaPrimitiveType)
                .newInstance(UserManager.USER_TYPE_PROFILE_MANAGED, user.hashCode())
            return builderClass.getMethod("build").invoke(builder) as LauncherUserInfo
        }

        companion object {
            val lookups = mutableListOf<UserHandle>()
        }
    }

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun clearPrefs() {
        CountingShadowLauncherApps.lookups.clear()
        listOf("docked_apps", "dock_settings", "app_launch_stats", "widgets", "app_metadata")
            .forEach { name ->
                context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
            }
    }

    @Test
    fun pickerLoadClassifiesAProfileOnceHoweverManyProvidersItHas() {
        val widgetManager = shadowOf(AppWidgetManager.getInstance(context))
        repeat(3) { index -> widgetManager.addInstalledProvidersForProfile(workHandle, workProviderInfo(index)) }
        val viewModel = LauncherViewModel(
            app = ApplicationProvider.getApplicationContext(),
            workPackages = emptySet(),
            ioDispatcher = Dispatchers.Unconfined,
        )
        shadowOf(Looper.getMainLooper()).idle()
        CountingShadowLauncherApps.lookups.clear()

        viewModel.showWidgetPicker()
        shadowOf(Looper.getMainLooper()).idle()

        val workProviders = viewModel.uiState.value.availableWidgets.filter { provider -> provider.profile == workHandle }
        assertEquals(3, workProviders.size)
        assertTrue("every provider of the managed profile is a work provider", workProviders.all { it.isWorkProvider })
        assertEquals(
            "one profile-kind lookup for the work profile, none for the personal user",
            listOf(workHandle),
            CountingShadowLauncherApps.lookups,
        )
    }

    // Mirrors WidgetProviderWorkProfileNameTest: getProfile() reads the
    // internal providerInfo's applicationInfo.uid, and nonLocalizedLabel keeps
    // loadLabel() resolvable without installed-package resources.
    private fun workProviderInfo(index: Int) = AppWidgetProviderInfo().apply {
        provider = ComponentName(WORK_PACKAGE, "$WORK_PACKAGE.Widget$index")
        minWidth = 200
        minHeight = 100
        val activityInfo = ActivityInfo().apply {
            packageName = WORK_PACKAGE
            nonLocalizedLabel = "Widget $index"
            applicationInfo = ApplicationInfo().apply {
                packageName = WORK_PACKAGE
                uid = WORK_UID
            }
        }
        AppWidgetProviderInfo::class.java
            .getDeclaredField("providerInfo")
            .apply { isAccessible = true }
            .set(this@apply, activityInfo)
    }

    private companion object {
        const val WORK_PACKAGE = "app.typelauncher.corpmail"

        // A uid in user 10; getProfile() resolves it to workHandle.
        const val WORK_UID = 1_010_000
        val workHandle: UserHandle = UserHandle.getUserHandleForUid(WORK_UID)
    }
}
