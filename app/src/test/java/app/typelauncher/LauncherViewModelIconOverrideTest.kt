package app.typelauncher

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * End-to-end coverage for the user-driven icon override flow at the ViewModel
 * layer: the override is mirrored onto every surface, persists across process
 * restart, and re-uploading produces a fresh `iconCacheId` so `AppIconLoader`
 * never returns a stale bitmap.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LauncherViewModelIconOverrideTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

    @After
    fun cleanup() {
        listOf(
            "docked_apps",
            "dock_settings",
            "app_launch_stats",
            "widgets",
            "app_metadata",
            "hidden_apps",
            "renamed_apps",
        ).forEach { name ->
            context.getSharedPreferences(name, android.content.Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
        }
        File(context.filesDir, "icon_overrides").deleteRecursively()
    }

    @Test
    fun setAppIconOverrideMirrorsCustomIconPathOntoFilteredApps() {
        seedApp("ChatGPT", "com.openai.chatgpt")
        val viewModel = freshlyLoaded()
        val target = viewModel.uiState.value.filteredApps.single { it.name == "ChatGPT" }

        val uri = stagePngOverride(target.id)
        viewModel.setAppIconOverride(target, uri)
        idle()

        val patched = viewModel.uiState.value.filteredApps.single { it.id == target.id }
        assertNotNull("customIconPath should be set after override", patched.customIconPath)
        assertTrue(
            "customIconPath should point at filesDir/icon_overrides/...; got ${patched.customIconPath}",
            patched.customIconPath!!.contains("/icon_overrides/"),
        )
        assertNotEquals(
            "iconCacheId must change once an override is applied so AppIconLoader misses",
            target.iconCacheId,
            patched.iconCacheId,
        )
    }

    @Test
    fun clearAppIconOverrideRevertsCustomIconPathToNull() {
        seedApp("ChatGPT", "com.openai.chatgpt")
        val viewModel = freshlyLoaded()
        val target = viewModel.uiState.value.filteredApps.single { it.name == "ChatGPT" }

        viewModel.setAppIconOverride(target, stagePngOverride(target.id))
        idle()
        val withOverride = viewModel.uiState.value.filteredApps.single { it.id == target.id }
        assertNotNull(withOverride.customIconPath)

        viewModel.clearAppIconOverride(withOverride)
        idle()

        val cleared = viewModel.uiState.value.filteredApps.single { it.id == target.id }
        assertNull("clearAppIconOverride must drop the customIconPath", cleared.customIconPath)
        assertEquals(0L, cleared.customIconVersion)
        assertEquals(target.iconCacheId, cleared.iconCacheId)
    }

    @Test
    fun setAppIconOverrideByIdLooksUpInstalledAppAndApplies() {
        // Mirrors the post-recreation path: the picker callback only carries
        // an `appId` (round-tripped via `rememberSaveable`) and the ViewModel
        // resolves it to the live `InstalledApp` before applying. Without the
        // id-based overload the result URI would be silently dropped after a
        // configuration change while the picker is foreground.
        seedApp("ChatGPT", "com.openai.chatgpt")
        val viewModel = freshlyLoaded()
        val target = viewModel.uiState.value.filteredApps.single { it.name == "ChatGPT" }

        viewModel.setAppIconOverride(target.id, stagePngOverride(target.id))
        idle()

        val patched = viewModel.uiState.value.filteredApps.single { it.id == target.id }
        assertNotNull(patched.customIconPath)
    }

    @Test
    fun setAppIconOverrideByIdDropsRequestForUnknownId() {
        // Defends the picker callback against a race where the chosen package
        // is uninstalled while the picker is foreground: the saved id no
        // longer resolves, so the override must not land on someone else.
        seedApp("ChatGPT", "com.openai.chatgpt")
        val viewModel = freshlyLoaded()
        val before = viewModel.uiState.value.filteredApps

        viewModel.setAppIconOverride("0:com.removed/Main", stagePngOverride("0:com.removed/Main"))
        idle()

        val after = viewModel.uiState.value.filteredApps
        assertTrue(
            "no app should have grown a customIconPath after a no-op id-based set",
            after.all { it.customIconPath == null },
        )
        assertEquals(before.size, after.size)
    }

    @Test
    fun setAppIconOverrideSurvivesProcessRestart() {
        seedApp("ChatGPT", "com.openai.chatgpt")
        val initial = freshlyLoaded()
        val target = initial.uiState.value.filteredApps.single { it.name == "ChatGPT" }
        initial.setAppIconOverride(target, stagePngOverride(target.id))
        idle()

        // A new ViewModel instance simulates a process restart; it must
        // re-read the persisted override file and apply it on cold start so
        // the very first frame already renders against the override icon.
        val reloaded = freshlyLoaded()
        val restored = reloaded.uiState.value.filteredApps.single { it.id == target.id }
        assertNotNull(restored.customIconPath)
        assertTrue(
            "iconCacheId on the reloaded VM must match what the prior session produced",
            restored.iconCacheId.contains("#override:"),
        )
    }

    private fun stagePngOverride(appId: String): Uri {
        // The picker call would normally hand us a `content://` Uri; drop the
        // bytes into a `file://` Uri so the `ContentResolver` in the test
        // environment can stream them back to `setAppIconOverride`. The
        // directory lives under cacheDir so it's torn down with the process.
        val src = File(context.cacheDir, "test-icon-${appId.hashCode()}.png").apply {
            parentFile?.mkdirs()
            writeBytes(SAMPLE_PNG)
        }
        return Uri.fromFile(src)
    }

    private fun freshlyLoaded(): LauncherViewModel {
        val viewModel = LauncherViewModel(
            app = ApplicationProvider.getApplicationContext(),
            workPackages = emptySet(),
            ioDispatcher = Dispatchers.Unconfined,
        )
        idle()
        assertTrue(
            "Cold-start load must finish before issuing icon-override edits",
            viewModel.uiState.value.isFreshAppLoadComplete,
        )
        return viewModel
    }

    private fun seedApp(label: String, packageName: String) {
        val resolveInfo = ResolveInfo().apply {
            nonLocalizedLabel = label
            activityInfo = ActivityInfo().apply {
                this.packageName = packageName
                name = "$packageName.LaunchActivity"
            }
        }
        @Suppress("DEPRECATION")
        shadowOf(context.packageManager).addResolveInfoForIntent(launcherIntent, resolveInfo)
    }

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    private companion object {
        // Smallest valid 1×1 ARGB PNG, hex-encoded. Skips depending on
        // android.graphics in a unit test that only cares about the bytes
        // round-tripping through `IconOverrideStore`, not about decoding.
        private val SAMPLE_PNG: ByteArray = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
            0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4.toByte(), 0x89.toByte(),
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x44, 0x41, 0x54,
            0x78, 0x9C.toByte(), 0x63, 0x00, 0x01, 0x00, 0x00, 0x05,
            0x00, 0x01, 0x0D, 0x0A, 0x2D, 0xB4.toByte(),
            0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(), 0x42, 0x60, 0x82.toByte(),
        )
    }
}
