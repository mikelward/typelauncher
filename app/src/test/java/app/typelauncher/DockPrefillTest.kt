package app.typelauncher

import android.content.ComponentName
import android.content.Intent
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DockPrefillTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @After
    fun clearPrefs() {
        context.getSharedPreferences("docked_apps", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun prefillDocksPopularInstalledAppsInPriorityOrder() {
        val store = DockedAppStore(context)
        val apps = listOf(
            installedApp("com.reddit.frontpage"),
            installedApp("com.android.chrome"),
            installedApp("com.google.android.gm"),
        )

        prefillDock(apps, store, maxSlots = 4)

        // Chrome and Gmail rank above Reddit in POPULAR_APP_PACKAGES
        val docked = store.dockedAppIds
        assertEquals(3, docked.size)
        assertEquals(apps.first { it.packageName == "com.android.chrome" }.id, docked[0])
        assertEquals(apps.first { it.packageName == "com.google.android.gm" }.id, docked[1])
        assertEquals(apps.first { it.packageName == "com.reddit.frontpage" }.id, docked[2])
    }

    @Test
    fun prefillRespectsMaxSlots() {
        val store = DockedAppStore(context)
        val apps = listOf(
            installedApp("com.android.chrome"),
            installedApp("com.google.android.gm"),
            installedApp("com.google.android.apps.maps"),
            installedApp("com.google.android.youtube"),
            installedApp("com.whatsapp"),
        )

        prefillDock(apps, store, maxSlots = 3)

        assertEquals(3, store.dockedAppIds.size)
    }

    @Test
    fun prefillSkipsAppsNotInstalled() {
        val store = DockedAppStore(context)
        val apps = listOf(
            installedApp("com.google.android.gm"),       // popular, installed
            installedApp("com.example.unknown.app"),      // not in popular list
        )

        prefillDock(apps, store, maxSlots = 4)

        assertEquals(1, store.dockedAppIds.size)
        assertEquals(apps.first { it.packageName == "com.google.android.gm" }.id, store.dockedAppIds[0])
    }

    @Test
    fun prefillSkipsWorkProfileApps() {
        val store = DockedAppStore(context)
        val personal = installedApp("com.android.chrome", isWork = false)
        val work = installedApp("com.google.android.gm", isWork = true)

        prefillDock(listOf(personal, work), store, maxSlots = 4)

        assertEquals(listOf(personal.id), store.dockedAppIds)
    }

    @Test
    fun prefillDoesNothingWhenNoPopularAppsInstalled() {
        val store = DockedAppStore(context)
        val apps = listOf(installedApp("com.example.nothing"))

        prefillDock(apps, store, maxSlots = 4)

        assertTrue(store.dockedAppIds.isEmpty())
    }

    @Test
    fun hasBeenPrefilledReturnsFalseInitially() {
        assertFalse(DockedAppStore(context).hasBeenPrefilled)
    }

    @Test
    fun markPrefilledPersistsAcrossInstances() {
        DockedAppStore(context).markPrefilled()

        assertTrue(DockedAppStore(context).hasBeenPrefilled)
    }

    private fun installedApp(packageName: String, isWork: Boolean = false): InstalledApp {
        val component = ComponentName(packageName, "$packageName.MainActivity")
        return InstalledApp(
            name = packageName,
            packageName = packageName,
            launchIntent = Intent.makeMainActivity(component),
            user = Process.myUserHandle(),
            isWorkApp = isWork,
            launchWithLauncherApps = true,
        )
    }
}
