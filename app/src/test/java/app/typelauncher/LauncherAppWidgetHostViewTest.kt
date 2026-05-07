package app.typelauncher

import android.appwidget.AppWidgetHostView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LauncherAppWidgetHostViewTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun setAppWidget_removesDefaultPaddingWhenEnabled() {
        val view = LauncherAppWidgetHostView(context)

        view.setRemoveWidgetPadding(true)
        view.setAppWidget(42, null)

        assertEquals(0, view.paddingLeft)
        assertEquals(0, view.paddingTop)
        assertEquals(0, view.paddingRight)
        assertEquals(0, view.paddingBottom)
    }

    @Test
    fun setRemoveWidgetPadding_restoresFrameworkPaddingWhenDisabled() {
        val view = LauncherAppWidgetHostView(context)
        val defaultPadding = AppWidgetHostView.getDefaultPaddingForWidget(context, null, null)
        view.setAppWidget(42, null)
        view.setRemoveWidgetPadding(true)

        view.setRemoveWidgetPadding(false)

        assertEquals(defaultPadding.left, view.paddingLeft)
        assertEquals(defaultPadding.top, view.paddingTop)
        assertEquals(defaultPadding.right, view.paddingRight)
        assertEquals(defaultPadding.bottom, view.paddingBottom)
    }
}
