package app.typelauncher

import android.content.ComponentName
import android.content.Intent
import android.os.Process
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Coverage for [searchAppNameHint], which decides whether (and what) the
 * icon-only autocomplete hint shows. The hint must name the Enter target only
 * when the setting is on, the list is effectively icon-only, and the user has
 * typed something — and stay hidden otherwise.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SearchAppNameHintTest {
    private val calculator = installedApp("Calculator")

    @Test
    fun showsTopMatchNameWhenEnabledIconOnlyAndQueried() {
        assertEquals(
            "Calculator",
            searchAppNameHint(
                showHint = true,
                effectiveIconOnly = true,
                query = "ca",
                topMatch = calculator,
            ),
        )
    }

    @Test
    fun hiddenWhenSettingOff() {
        assertNull(
            searchAppNameHint(
                showHint = false,
                effectiveIconOnly = true,
                query = "ca",
                topMatch = calculator,
            ),
        )
    }

    @Test
    fun hiddenInTextMode() {
        assertNull(
            searchAppNameHint(
                showHint = true,
                effectiveIconOnly = false,
                query = "ca",
                topMatch = calculator,
            ),
        )
    }

    @Test
    fun hiddenWhenQueryBlank() {
        assertNull(
            searchAppNameHint(
                showHint = true,
                effectiveIconOnly = true,
                query = "   ",
                topMatch = calculator,
            ),
        )
    }

    @Test
    fun hiddenWhenNoMatch() {
        assertNull(
            searchAppNameHint(
                showHint = true,
                effectiveIconOnly = true,
                query = "ca",
                topMatch = null,
            ),
        )
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
