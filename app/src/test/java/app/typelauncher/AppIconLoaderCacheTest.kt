package app.typelauncher

import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Process
import android.os.UserHandle
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
    fun evictDropsEntriesForPackageAndUserButLeavesOthersAlone() {
        val sizePx = 24
        val workUser = UserHandle.getUserHandleForUid(WORK_USER_TEST_UID)
        // Fake packages so the global `AppIconLoader.cache` doesn't pick up entries from
        // sibling tests (this object's cache is process-wide and Robolectric doesn't reset it).
        val gmailLike = "app.typelauncher.evicttest.gmailish"
        val chatLike = "app.typelauncher.evicttest.chatish"
        val workTarget = installedApp(
            ComponentName(gmailLike, ".LaunchActivity"),
            iconCacheToken = "100",
            user = workUser,
        )
        val personalSamePackage = installedApp(
            ComponentName(gmailLike, ".LaunchActivity"),
            iconCacheToken = "100",
            user = Process.myUserHandle(),
        )
        val workSiblingPackage = installedApp(
            ComponentName(chatLike, ".LaunchActivity"),
            iconCacheToken = "100",
            user = workUser,
        )
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            .apply { eraseColor(Color.BLUE) }
            .asImageBitmap()
        AppIconLoader.put(workTarget.iconCacheId, sizePx, bitmap)
        AppIconLoader.put(personalSamePackage.iconCacheId, sizePx, bitmap)
        AppIconLoader.put(workSiblingPackage.iconCacheId, sizePx, bitmap)

        AppIconLoader.evict(gmailLike, workUser)

        assertNull(
            "evict should drop the work-profile entry for the named package",
            AppIconLoader.cached(workTarget, sizePx),
        )
        assertNotNull(
            "evict must scope by user — personal copy of the same package must survive",
            AppIconLoader.cached(personalSamePackage, sizePx),
        )
        assertNotNull(
            "evict must scope by package — sibling work-profile package must survive",
            AppIconLoader.cached(workSiblingPackage, sizePx),
        )
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

    private fun installedApp(
        component: ComponentName,
        iconCacheToken: String,
        user: UserHandle = Process.myUserHandle(),
    ): InstalledApp =
        InstalledApp(
            name = "Cache",
            packageName = component.packageName,
            launchIntent = Intent.makeMainActivity(component),
            user = user,
            isWorkApp = user != Process.myUserHandle(),
            launchWithLauncherApps = true,
            iconCacheToken = iconCacheToken,
        )

    private companion object {
        // Encodes user 10 (the conventional first managed-profile user on Android).
        // UserHandle.getUserHandleForUid(uid) extracts the user id as `uid / 100000`.
        const val WORK_USER_TEST_UID = 1_010_000
    }
}
