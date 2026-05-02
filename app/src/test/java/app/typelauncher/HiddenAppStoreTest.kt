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
class HiddenAppStoreTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @After
    fun clearPrefs() {
        context.getSharedPreferences("hidden_apps", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun hidePersistsIdInInsertionOrder() {
        val store = HiddenAppStore(context)
        store.hide("a")
        store.hide("b")
        store.hide("c")

        assertEquals(listOf("a", "b", "c"), store.hiddenAppIds)
        assertTrue(store.contains("b"))
    }

    @Test
    fun hideIsIdempotent() {
        val store = HiddenAppStore(context)
        store.hide("a")
        store.hide("a")

        assertEquals(listOf("a"), store.hiddenAppIds)
    }

    @Test
    fun unhideRemovesId() {
        val store = HiddenAppStore(context)
        store.hide("a")
        store.hide("b")
        store.unhide("a")

        assertEquals(listOf("b"), store.hiddenAppIds)
        assertFalse(store.contains("a"))
    }

    @Test
    fun unhideMissingIdIsNoOp() {
        val store = HiddenAppStore(context)
        store.unhide("never-hidden")

        assertEquals(emptyList<String>(), store.hiddenAppIds)
    }

    @Test
    fun hiddenIdsSurviveProcessRestart() {
        HiddenAppStore(context).hide("a")
        HiddenAppStore(context).hide("b")

        val reloaded = HiddenAppStore(context)
        assertEquals(listOf("a", "b"), reloaded.hiddenAppIds)
    }

    @Test
    fun filterHiddenReturnsAppsInPersistedInsertionOrder() {
        val calculator = installedApp("Calculator")
        val browser = installedApp("Browser")
        val camera = installedApp("Camera")
        // Hidden order is browser-first, regardless of the alphabetical install order.
        val hiddenIds = listOf(browser.id, camera.id)

        val hidden = listOf(calculator, browser, camera).filterHidden(hiddenIds)

        assertEquals(listOf("Browser", "Camera"), hidden.map { it.name })
    }

    @Test
    fun filterHiddenDropsIdsThatNoLongerExist() {
        val mail = installedApp("Mail")

        val hidden = listOf(mail).filterHidden(listOf("ghost", mail.id))

        // Stale ids from uninstalled apps drop out silently rather than
        // producing a row the user can't act on.
        assertEquals(listOf("Mail"), hidden.map { it.name })
    }

    private fun installedApp(name: String): InstalledApp {
        val packageName = "app.${name.lowercase().replace(' ', '.')}"
        val component = ComponentName(packageName, "$packageName.LaunchActivity")
        return InstalledApp(
            name = name,
            packageName = packageName,
            launchIntent = Intent.makeMainActivity(component),
            user = Process.myUserHandle(),
            isWorkApp = false,
            launchWithLauncherApps = true,
        )
    }
}
