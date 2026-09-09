package app.typelauncher

import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NotificationShadeTest {
    @Test
    fun expand_doesNotThrowWhenStatusBarServiceIsUnavailable() {
        // Robolectric may not provide the StatusBarManager service; the helper must
        // swallow reflective and security failures so a missing service never crashes
        // the launcher gesture.
        NotificationShade.expand(ApplicationProvider.getApplicationContext())
    }
}
