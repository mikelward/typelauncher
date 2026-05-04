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
}
