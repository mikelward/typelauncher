package app.typelauncher

import android.widget.RemoteViews
import android.widget.TextView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WidgetSizingTest {
    @Test
    fun hostedWidgetHeight_convertsProviderPixelsToDpBeforeApplyingFloor() {
        val density = Density(density = 3f)

        assertEquals(100.dp, widgetCardHeight(minHeightPx = 300, density))
    }

    @Test
    fun hostedWidgetHeight_usesLauncherFloorWhenProviderHeightIsShorter() {
        val density = Density(density = 3f)

        assertEquals(96.dp, widgetCardHeight(minHeightPx = 120, density))
    }

    @Test
    fun generatedWidgetPreviewInflation_returnsViewWhenRemoteViewsApplySucceeds() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val preview = RemoteViews("android", android.R.layout.simple_list_item_1).apply {
            setTextViewText(android.R.id.text1, "Preview")
        }

        val view = preview.applyWidgetPreviewOrNull(context)

        assertTrue(view is TextView)
        assertEquals("Preview", (view as TextView).text)
    }

    @Test
    fun generatedWidgetPreviewInflation_returnsNullWhenRemoteViewsApplyThrows() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val invalidPreview = RemoteViews(context.packageName, Int.MAX_VALUE)

        assertNull(invalidPreview.applyWidgetPreviewOrNull(context))
    }
}
