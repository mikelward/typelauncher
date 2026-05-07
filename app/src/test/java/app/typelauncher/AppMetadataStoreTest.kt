package app.typelauncher

import android.content.ComponentName
import android.content.Intent
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppMetadataStoreTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @After
    fun clearPrefs() {
        context.getSharedPreferences("app_metadata", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun roundTripsPersonalApp() {
        val app = personalApp(name = "Browser", packageName = "app.browser")
        val store = AppMetadataStore(context)
        store.save(listOf(app))

        val loaded = AppMetadataStore(context).load()
        assertEquals(1, loaded.size)
        val restored = loaded.first()
        assertEquals("Browser", restored.name)
        assertEquals("app.browser", restored.packageName)
        assertEquals(app.id, restored.id)
        assertEquals(app.launchIntent.component, restored.launchIntent.component)
        assertEquals(Process.myUserHandle(), restored.user)
    }

    @Test
    fun preservesIconCacheToken() {
        val app = personalApp(name = "Browser", packageName = "app.browser")
        AppMetadataStore(context).save(listOf(app))

        assertEquals("1234", AppMetadataStore(context).load().single().iconCacheToken)
    }

    @Test
    fun preservesIsWorkAppFlagForPersonalProfileApps() {
        // Personal-profile apps tagged via TEST_WORK_PACKAGES_EXTRA still need
        // to round-trip their work identity across cold starts.
        val app = personalApp(name = "Work mail", packageName = "app.workmail").copy(isWorkApp = true)
        AppMetadataStore(context).save(listOf(app))

        val restored = AppMetadataStore(context).load().single()
        assertTrue(restored.isWorkApp)
    }

    @Test
    fun returnsEmptyListWhenNothingSaved() {
        val store = AppMetadataStore(context)
        assertEquals(emptyList<InstalledApp>(), store.load())
    }

    @Test
    fun returnsEmptyListWhenStoredJsonIsCorrupt() {
        context.getSharedPreferences("app_metadata", android.content.Context.MODE_PRIVATE)
            .edit().putString("apps", "not-json").commit()
        assertEquals(emptyList<InstalledApp>(), AppMetadataStore(context).load())
    }

    @Test
    fun saveReplacesPreviousSnapshot() {
        val store = AppMetadataStore(context)
        store.save(listOf(personalApp(name = "Old", packageName = "app.old")))
        store.save(listOf(personalApp(name = "New", packageName = "app.new")))
        val loaded = AppMetadataStore(context).load()
        assertEquals(listOf("New"), loaded.map { it.name })
    }

    private fun personalApp(name: String, packageName: String): InstalledApp {
        val component = ComponentName(packageName, "$packageName.LaunchActivity")
        return InstalledApp(
            name = name,
            packageName = packageName,
            launchIntent = Intent.makeMainActivity(component),
            user = Process.myUserHandle(),
            isWorkApp = false,
            launchWithLauncherApps = true,
            iconCacheToken = "1234",
        )
    }
}
