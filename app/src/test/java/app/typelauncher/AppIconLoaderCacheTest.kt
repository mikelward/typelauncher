package app.typelauncher

import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Process
import androidx.compose.ui.graphics.asImageBitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers the snapshot-friendly entry points added so `IconSnapshotStore` can rehydrate
 * the in-memory icon cache before the first frame of a cold start.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppIconLoaderCacheTest {
    @Test
    fun putMakesBitmapAvailableViaCachedAndSnapshot() {
        val id = "0:com.example.cache/com.example.cache.LaunchActivity"
        val sizePx = 24
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            .apply { eraseColor(Color.CYAN) }
            .asImageBitmap()

        AppIconLoader.put(id, sizePx, bitmap)

        assertNotNull(AppIconLoader.cached(id, sizePx))
        // Different size shouldn't share the entry.
        assertNull(AppIconLoader.cached(id, sizePx + 1))

        val snapshot = AppIconLoader.cacheSnapshot()
        val key = AppIconLoader.CacheKey(id = id, sizePx = sizePx)
        assertTrue("snapshot should contain the key we just put", snapshot.containsKey(key))
        assertEquals(bitmap, snapshot[key])
    }

    @Test
    fun cachedUsesPackageUpdateToken() {
        val sizePx = 24
        val component = ComponentName("com.example.cache", "com.example.cache.LaunchActivity")
        val stale = installedApp(component, iconCacheToken = "100")
        val current = installedApp(component, iconCacheToken = "200")
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            .apply { eraseColor(Color.GREEN) }
            .asImageBitmap()

        AppIconLoader.put(stale.iconCacheId, sizePx, bitmap)

        assertNotNull(AppIconLoader.cached(stale, sizePx))
        assertNull(AppIconLoader.cached(current, sizePx))
    }

    private fun installedApp(component: ComponentName, iconCacheToken: String): InstalledApp =
        InstalledApp(
            name = "Cache",
            packageName = component.packageName,
            launchIntent = Intent.makeMainActivity(component),
            user = Process.myUserHandle(),
            isWorkApp = false,
            launchWithLauncherApps = true,
            iconCacheToken = iconCacheToken,
        )
}
