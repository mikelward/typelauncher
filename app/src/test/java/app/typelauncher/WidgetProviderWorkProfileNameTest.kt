package app.typelauncher

import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.os.Process
import android.os.UserHandle
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The widget picker groups providers under their app's name and icon. Work-only
 * packages are invisible to the launcher's own (personal-user) PackageManager,
 * so `toWidgetProvider` must fall back to a cross-profile lookup (LauncherApps
 * in production) — before the fallback, a work-only app's picker section
 * rendered its raw package name with no app icon while its personal-profile
 * peers rendered normally.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WidgetProviderWorkProfileNameTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val personalUser = Process.myUserHandle()

    @Test
    fun workOnlyProviderResolvesAppNameThroughProfileResolver() {
        val resolvedProfiles = mutableListOf<Pair<String, UserHandle>>()
        val provider = workOnlyProviderInfo().toWidgetProvider(context, personalUser) { packageName, profile ->
            resolvedProfiles += packageName to profile
            ApplicationInfo().apply {
                this.packageName = packageName
                nonLocalizedLabel = "Corp Mail"
            }
        }

        assertEquals("Corp Mail", provider.appName)
        assertTrue(provider.isWorkProvider)
        // The lookup must target the provider's own profile, not the personal
        // user whose PackageManager already failed to see the package.
        assertEquals(
            listOf(WORK_PACKAGE to UserHandle.getUserHandleForUid(WORK_UID)),
            resolvedProfiles,
        )
    }

    @Test
    fun unresolvableWorkOnlyProviderKeepsPackageNameFallback() {
        val provider = workOnlyProviderInfo().toWidgetProvider(context, personalUser) { _, _ -> null }

        assertEquals(WORK_PACKAGE, provider.appName)
        assertNull(provider.appIcon)
    }

    // AppWidgetProviderInfo.getProfile() derives the profile from the internal
    // `providerInfo` ActivityInfo via `applicationInfo.uid`, so seed it with a
    // uid in user 10 (the conventional first managed profile). The
    // nonLocalizedLabel keeps AppWidgetProviderInfo.loadLabel() resolvable
    // without installed-package resources.
    private fun workOnlyProviderInfo() = AppWidgetProviderInfo().apply {
        provider = ComponentName(WORK_PACKAGE, "$WORK_PACKAGE.MailWidgetProvider")
        minWidth = 200
        minHeight = 100
        val activityInfo = ActivityInfo().apply {
            packageName = WORK_PACKAGE
            nonLocalizedLabel = "Inbox"
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

        // A uid in user 10; UserHandle.getUserHandleForUid derives the user as
        // uid / 100000, so getProfile() resolves to a non-personal handle.
        const val WORK_UID = 1_010_000
    }
}
