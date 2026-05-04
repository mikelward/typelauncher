package app.typelauncher

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlayUpdateStoreTest {
    private val context: android.content.Context = ApplicationProvider.getApplicationContext()

    @Before
    fun clearStore() {
        context
            .getSharedPreferences("play_update", android.content.Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun dismissedVersionCodeHidesSameUpdateOnly() {
        val store = PlayUpdateStore(context)
        store.dismissedVersionCode = 101

        assertEquals(101, store.dismissedVersionCode)
        assertFalse(PlayUpdateState.Available(versionCode = 101, isDismissed = true).shouldPrompt)
        assertTrue(PlayUpdateState.Available(versionCode = 102).shouldPrompt)
    }

    @Test
    fun defaultDismissedVersionCodeIsZero() {
        val store = PlayUpdateStore(context)

        assertEquals(0, store.dismissedVersionCode)
    }

    @Test
    fun buildSourceInfoFormatsRelativeAgeAndDirtyState() {
        val info = BuildSourceInfo(branch = "cursor/demo", sha = "abc1234", isDirty = true, configuredAtMillis = 1_000L)

        assertEquals(
            "demo · abc1234 (dirty) · 2 hours ago",
            info.displayText(nowMillis = 1_000L + 2 * 60 * 60 * 1_000L),
        )
    }

    @Test
    fun buildSourceInfoLeavesUnprefixedBranchNameIntact() {
        val info = BuildSourceInfo(branch = "main", sha = "abc1234", isDirty = false, configuredAtMillis = 1_000L)

        assertEquals(
            "main · abc1234 · 0 minutes ago",
            info.displayText(nowMillis = 1_000L),
        )
    }

    @Test
    fun buildSourceInfoUsesPlainRelativeAgeUnits() {
        val info = BuildSourceInfo(branch = "claude/demo", sha = "abc1234", isDirty = false, configuredAtMillis = 1_000L)

        assertEquals("demo · abc1234 · 1 minute ago", info.displayText(nowMillis = 61_000L))
        assertEquals("demo · abc1234 · 2 hours ago", info.displayText(nowMillis = 1_000L + 2 * 60 * 60 * 1_000L))
        assertEquals("demo · abc1234 · 3 days ago", info.displayText(nowMillis = 1_000L + 3 * 24 * 60 * 60 * 1_000L))
    }
}
