package app.typelauncher

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.net.Uri
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PinnedShortcutAppsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun shortcutConvertsToLauncherApp() {
        val shortcut = ShortcutInfo.Builder(context, "open-example")
            .setShortLabel("Example")
            .setLongLabel("Example PWA")
            .setIntent(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.test/app")))
            .build()

        val app = shortcut.toInstalledApp()!!

        assertEquals("Example PWA", app.name)
        assertEquals(context.packageName, app.packageName)
        assertEquals("open-example", app.shortcutId)
        assertEquals(Uri.parse("https://example.test/app"), app.launchIntent.data)
        assertEquals(Process.myUserHandle(), app.user)
        assertFalse(app.launchWithLauncherApps)
        assertNull(app.launchIntent.component)
        assertEquals("${Process.myUserHandle().hashCode()}:shortcut:${context.packageName}/open-example", app.id)
    }

    @Test
    fun shortcutUsesShortLabelWhenLongLabelIsMissing() {
        val shortcut = ShortcutInfo.Builder(context, "short-only")
            .setShortLabel("Short PWA")
            .setIntent(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.test/short")))
            .build()

        assertEquals("Short PWA", shortcut.toInstalledApp()!!.name)
    }
}
