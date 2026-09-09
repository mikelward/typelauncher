package app.typelauncher

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The icon-cache version token must exist for every profile's apps, not just
 * the personal user's. `iconCacheToken` prefers the personal-user
 * PackageManager's `lastUpdateTime`, but a package installed only in a work
 * profile is invisible to that PackageManager — before the fallback, such an
 * app had no token at all, so its cached icon was never invalidated after the
 * app updated while the launcher process was dead. The fallback is the
 * profile's own `sourceDir`: the platform installs every update into a fresh
 * directory, so the path changes exactly when the icon art can.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class IconCacheTokenTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun personalPackageUsesLastUpdateTime() {
        val packageName = "app.typelauncher.testpersonal"
        shadowOf(context.packageManager).installPackage(
            PackageInfo().apply {
                this.packageName = packageName
                lastUpdateTime = 42L
                applicationInfo = ApplicationInfo().apply { this.packageName = packageName }
            },
        )
        val appInfo = ApplicationInfo().apply {
            this.packageName = packageName
            sourceDir = "/data/app/personal==/base.apk"
        }

        assertEquals("42", appInfo.iconCacheToken(context.packageManager))
    }

    @Test
    fun workOnlyPackageFallsBackToItsProfileSourceDir() {
        // Not installed for the personal user: getPackageInfo throws, and the
        // token must come from the work profile's own ApplicationInfo.
        val appInfo = ApplicationInfo().apply {
            packageName = "app.typelauncher.workonly"
            sourceDir = "/data/app/workonly-Abc123==/base.apk"
        }

        assertEquals(
            "/data/app/workonly-Abc123==/base.apk",
            appInfo.iconCacheToken(context.packageManager),
        )
    }

    @Test
    fun unresolvablePackageWithoutSourceDirHasNoToken() {
        val appInfo = ApplicationInfo().apply {
            packageName = "app.typelauncher.ghost"
        }

        assertNull(appInfo.iconCacheToken(context.packageManager))
    }
}
