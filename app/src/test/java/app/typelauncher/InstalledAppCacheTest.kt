package app.typelauncher

import android.content.Context
import android.content.Intent
import android.os.UserHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class InstalledAppCacheTest {
    private lateinit var cache: InstalledAppCache

    @Before
    fun setUp() {
        val context: Context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("installed_app_cache", Context.MODE_PRIVATE)
            .edit().clear().commit()
        cache = InstalledAppCache(context)
    }

    @Test
    fun emptyOnFirstUse() {
        assertTrue(cache.topByUsage().isEmpty())
        assertTrue(cache.topAlphabetical().isEmpty())
    }

    @Test
    fun roundTripsUsageEntries() {
        val apps = listOf(
            fakeInstalledApp("com.example.alpha", userHashCode = 0),
            fakeInstalledApp("com.example.beta", userHashCode = 0),
        )
        cache.update(sortedByAlpha = apps, sortedByUsage = apps)

        val entries = cache.topByUsage()
        assertEquals(2, entries.size)
        assertEquals(InstalledAppCache.CachedEntry("com.example.alpha", 0), entries[0])
        assertEquals(InstalledAppCache.CachedEntry("com.example.beta", 0), entries[1])
    }

    @Test
    fun roundTripsAlphabeticalEntries() {
        val alpha = listOf(fakeInstalledApp("com.example.aaa", userHashCode = 0))
        val usage = listOf(fakeInstalledApp("com.example.zzz", userHashCode = 0))
        cache.update(sortedByAlpha = alpha, sortedByUsage = usage)

        assertEquals("com.example.aaa", cache.topAlphabetical().single().packageName)
        assertEquals("com.example.zzz", cache.topByUsage().single().packageName)
    }

    @Test
    fun capsEntriesAtPreloadCount() {
        val apps = (1..InstalledAppCache.PRELOAD_COUNT + 5)
            .map { i -> fakeInstalledApp("com.example.pkg$i", userHashCode = 0) }
        cache.update(sortedByAlpha = apps, sortedByUsage = apps)

        assertEquals(InstalledAppCache.PRELOAD_COUNT, cache.topByUsage().size)
        assertEquals(InstalledAppCache.PRELOAD_COUNT, cache.topAlphabetical().size)
    }

    @Test
    fun handlesNonZeroUserHashCode() {
        val apps = listOf(fakeInstalledApp("com.example.work", userHashCode = 10))
        cache.update(sortedByAlpha = apps, sortedByUsage = apps)

        val entry = cache.topByUsage().single()
        assertEquals("com.example.work", entry.packageName)
        assertEquals(10, entry.userHashCode)
    }

    @Test
    fun updateOverwritesPreviousData() {
        val first = listOf(fakeInstalledApp("com.example.old", userHashCode = 0))
        cache.update(sortedByAlpha = first, sortedByUsage = first)

        val second = listOf(fakeInstalledApp("com.example.new", userHashCode = 0))
        cache.update(sortedByAlpha = second, sortedByUsage = second)

        assertEquals("com.example.new", cache.topByUsage().single().packageName)
    }
}

private fun fakeInstalledApp(packageName: String, userHashCode: Int): InstalledApp =
    InstalledApp(
        name = packageName,
        packageName = packageName,
        launchIntent = Intent().setPackage(packageName),
        icon = null,
        user = UserHandle.of(userHashCode),
        isWorkApp = false,
        launchWithLauncherApps = false,
    )
