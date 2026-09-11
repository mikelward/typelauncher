package app.typelauncher

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

/**
 * The host's providers-changed callback is how MainActivity learns that a
 * provider still reinstalling at startup is back, and re-runs its
 * misbound-widget check — so the listener must actually fire.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LauncherAppWidgetHostProvidersChangedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun providersChangedInvokesTheListener() {
        val host = LauncherAppWidgetHost(context, /* hostId = */ 0, cacheLoadExecutor = Executor { it.run() })
        var calls = 0
        host.onProvidersChangedListener = { calls++ }

        host.onProvidersChanged()
        host.onProvidersChanged()

        assertEquals(2, calls)
    }

    @Test
    fun providersChangedWithoutAListenerIsANoOp() {
        val host = LauncherAppWidgetHost(context, /* hostId = */ 0, cacheLoadExecutor = Executor { it.run() })

        host.onProvidersChanged()
    }
}
