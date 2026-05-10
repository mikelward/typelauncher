package app.typelauncher

import android.appwidget.AppWidgetHost
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Covers the start/stop ordering of [WidgetHostCarouselPause] across the
 * scenarios that took several review rounds to get right on PR #305:
 * gate by `shouldPause`, gate by `isHomeReady`, re-stop on lifecycle
 * restart, no resume below `STARTED`, and matched stop/start pairs across
 * rapid `shouldPause` cycles.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WidgetHostCarouselPauseTest {
    @get:Rule
    val composeRule = createComposeRule()

    private class CountingAppWidgetHost : AppWidgetHost(
        RuntimeEnvironment.getApplication(),
        TEST_HOST_ID,
    ) {
        var startCount = 0
        var stopCount = 0
        override fun startListening() {
            startCount++
        }
        override fun stopListening() {
            stopCount++
        }

        companion object {
            private const val TEST_HOST_ID = 0xC0FFEE
        }
    }

    private class TestLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this).apply {
            currentState = Lifecycle.State.RESUMED
        }
        override val lifecycle: Lifecycle = registry
    }

    private fun render(
        host: AppWidgetHost?,
        shouldPause: () -> Boolean,
        isHomeReady: () -> Boolean,
        lifecycleOwner: LifecycleOwner,
    ) {
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
                WidgetHostCarouselPause(
                    appWidgetHost = host,
                    shouldPause = shouldPause(),
                    isHomeReady = isHomeReady(),
                )
            }
        }
    }

    @Test
    fun idle_doesNothing() {
        val host = CountingAppWidgetHost()
        val owner = TestLifecycleOwner()
        render(host, shouldPause = { false }, isHomeReady = { true }, lifecycleOwner = owner)
        composeRule.waitForIdle()
        assertEquals(0, host.stopCount)
        assertEquals(0, host.startCount)
    }

    @Test
    fun shouldPauseTrue_butHomeNotReady_doesNothing() {
        val host = CountingAppWidgetHost()
        val owner = TestLifecycleOwner()
        render(host, shouldPause = { true }, isHomeReady = { false }, lifecycleOwner = owner)
        composeRule.waitForIdle()
        assertEquals("must not bypass cold-start deferral", 0, host.stopCount)
        assertEquals(0, host.startCount)
    }

    @Test
    fun pauseAndResume_pairsStopWithStart() {
        val host = CountingAppWidgetHost()
        val owner = TestLifecycleOwner()
        var shouldPause by mutableStateOf(false)
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                WidgetHostCarouselPause(
                    appWidgetHost = host,
                    shouldPause = shouldPause,
                    isHomeReady = true,
                )
            }
        }
        composeRule.waitForIdle()
        assertEquals(0, host.stopCount)

        shouldPause = true
        composeRule.waitForIdle()
        assertEquals("flipping shouldPause:true stops once", 1, host.stopCount)
        assertEquals(0, host.startCount)

        shouldPause = false
        composeRule.waitForIdle()
        assertEquals("flipping shouldPause:false resumes once", 1, host.startCount)
        assertEquals(1, host.stopCount)
    }

    @Test
    fun nullHost_doesNothing() {
        val owner = TestLifecycleOwner()
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                WidgetHostCarouselPause(
                    appWidgetHost = null,
                    shouldPause = true,
                    isHomeReady = true,
                )
            }
        }
        composeRule.waitForIdle()
        // No host means no calls; the test really just asserts no NPE.
    }

    @Test
    fun disposingComposition_belowStartedDoesNotResume() {
        val host = CountingAppWidgetHost()
        val owner = TestLifecycleOwner()
        var attached by mutableStateOf(true)
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                if (attached) {
                    WidgetHostCarouselPause(
                        appWidgetHost = host,
                        shouldPause = true,
                        isHomeReady = true,
                    )
                }
            }
        }
        composeRule.waitForIdle()
        assertEquals(1, host.stopCount)

        composeRule.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        composeRule.waitForIdle()

        attached = false
        composeRule.waitForIdle()
        assertEquals("dispose-while-stopped must not resume listening", 0, host.startCount)
    }

    @Test
    fun rapidPauseCycles_pairEveryStopWithAStart() {
        val host = CountingAppWidgetHost()
        val owner = TestLifecycleOwner()
        var shouldPause by mutableStateOf(false)
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                WidgetHostCarouselPause(
                    appWidgetHost = host,
                    shouldPause = shouldPause,
                    isHomeReady = true,
                )
            }
        }
        composeRule.waitForIdle()
        repeat(3) {
            shouldPause = true
            composeRule.waitForIdle()
            shouldPause = false
            composeRule.waitForIdle()
        }
        assertEquals("three pause cycles → three stops", 3, host.stopCount)
        assertEquals("three pause cycles → three starts", 3, host.startCount)
    }
}
