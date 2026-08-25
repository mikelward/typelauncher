package app.typelauncher

import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Both widget-preview failure paths recover to a lesser preview. These assert
 * they also leave a trace, so the diagnostic can't be dropped without the
 * suite noticing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WidgetPreviewFailureLogTest {
    @Before
    fun resetBuffer() {
        LauncherDebugLog.clearForTest()
    }

    @Test
    fun generatedPreviewFetchFailureIsLoggedAndRecoversToNoPreview() {
        val result = generatedPreviewOrNull(PROVIDER) { throw IllegalStateException("no preview") }

        assertNull(result)
        val logged = LauncherDebugLog.snapshot().single()
        assertTrue(logged, logged.contains("generated widget preview unavailable"))
        assertTrue(logged, logged.contains("IllegalStateException"))
        // Naming the provider is the point; on-device only, by the
        // default-safe rule that withholds a String from the mirror.
        assertTrue(logged, logged.contains(PROVIDER))
    }

    @Test
    fun generatedPreviewFetchSuccessIsNotLogged() {
        val result = generatedPreviewOrNull(PROVIDER) { null }

        assertNull(result)
        assertEquals(emptyList<String>(), LauncherDebugLog.snapshot())
    }

    @Test
    fun previewInflationFailureIsLoggedAndFallsBackToTheStaticPreview() {
        val result = inflatedPreviewOrNull(PROVIDER) { throw IllegalArgumentException("bad layout") }

        // Null is what tells the caller to set `generatedInflationFailed` and
        // render the static preview instead.
        assertNull(result)
        val logged = LauncherDebugLog.snapshot().single()
        assertTrue(logged, logged.contains("generated widget preview would not inflate"))
        assertTrue(logged, logged.contains("IllegalArgumentException"))
        assertTrue(logged, logged.contains(PROVIDER))
    }

    @Test
    fun previewInflationSuccessIsNotLogged() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val result = inflatedPreviewOrNull(PROVIDER) { FrameLayout(context) }

        assertNotNull(result)
        assertEquals(emptyList<String>(), LauncherDebugLog.snapshot())
    }

    private companion object {
        const val PROVIDER = "com.example.widgets/.AgendaProvider"
    }
}
