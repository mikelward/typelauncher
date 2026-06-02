package app.typelauncher

import android.content.ComponentName
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Verifies the package-wide metadata scan that resolves Google Calendar's
 * per-day icon. The dynamic-icon array is declared on Calendar's launcher
 * **activity-alias**, not the activity the launcher resolves and not the
 * application element — so a lookup that only checks the launcher component
 * misses it. This is the regression that left the icon stuck on the static
 * "31".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppIconLoaderCalendarMetadataTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val packageName = "com.google.android.calendar"
    private val key = dynamicCalendarIconsMetadataKey(packageName)

    @Test
    fun findsArrayDeclaredOnANonLauncherComponent() {
        val arrayResId = 0x7f030010
        // Launcher component carries no metadata (mirrors the activity the alias
        // targets); the dynamic-icon array lives on a *different* component.
        installCalendar(
            launcherActivity = activity("$packageName.LaunchTarget", metaData = null),
            otherComponents = arrayOf(
                activity(
                    "com.android.calendar.AllInOneActivity",
                    metaData = Bundle().apply { putInt(key, arrayResId) },
                ),
            ),
        )

        val found = AppIconLoader.findDynamicCalendarArrayResId(
            context.packageManager,
            ComponentName(packageName, "$packageName.LaunchTarget"),
            key,
        )

        assertEquals(
            "Scan must find the dynamic-icon array on the alias, not just the launcher component",
            arrayResId,
            found,
        )
    }

    @Test
    fun returnsZeroWhenNoComponentDeclaresTheArray() {
        installCalendar(
            launcherActivity = activity("$packageName.LaunchTarget", metaData = null),
            otherComponents = arrayOf(activity("$packageName.Other", metaData = Bundle())),
        )

        val found = AppIconLoader.findDynamicCalendarArrayResId(
            context.packageManager,
            ComponentName(packageName, "$packageName.LaunchTarget"),
            key,
        )

        assertEquals("No dynamic-icon metadata means a 0 result (caller falls back)", 0, found)
    }

    private fun activity(name: String, metaData: Bundle?): ActivityInfo =
        ActivityInfo().apply {
            this.packageName = this@AppIconLoaderCalendarMetadataTest.packageName
            this.name = name
            this.metaData = metaData
            applicationInfo = ApplicationInfo().apply {
                packageName = this@AppIconLoaderCalendarMetadataTest.packageName
            }
        }

    private fun installCalendar(launcherActivity: ActivityInfo, otherComponents: Array<ActivityInfo>) {
        val packageInfo = PackageInfo().apply {
            packageName = this@AppIconLoaderCalendarMetadataTest.packageName
            applicationInfo = ApplicationInfo().apply {
                packageName = this@AppIconLoaderCalendarMetadataTest.packageName
            }
            activities = arrayOf(launcherActivity, *otherComponents)
        }
        shadowOf(context.packageManager).installPackage(packageInfo)
    }
}
