package app.typelauncher

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
    fun setAppWidget_removesDefaultPadding() {
        val host = LauncherAppWidgetHost(context, hostId = 0)
        val view = LauncherAppWidgetHostView(context, host = host)

        view.setAppWidget(42, null)

        assertEquals(0, view.paddingLeft)
        assertEquals(0, view.paddingTop)
        assertEquals(0, view.paddingRight)
        assertEquals(0, view.paddingBottom)
    }
}
