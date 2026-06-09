package app.typelauncher

import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.geometry.Offset
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression test for the resize drag firing one size-hint push per drag
 * frame. Every frame of the drag changes the card height by >= 1 dp, so the
 * `applyAppWidgetSizeIfChanged` cache never short-circuited and each frame
 * paid a binder IPC into AppWidgetService, a provider
 * `onAppWidgetOptionsChanged` wake, and a persisted-cache prefs write — on
 * the gesture's hot path. The hint must be withheld while the resize drag is
 * active and pushed once the drag ends.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
class WidgetResizeDragTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun clearPersistedCache() {
        context.getSharedPreferences("widget_size_cache", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    private class SizeCountingHostView(
        context: Context,
        host: LauncherAppWidgetHost,
    ) : LauncherAppWidgetHostView(context, host) {
        var sizePushes = 0
            private set

        override fun setAppWidget(appWidgetId: Int, info: AppWidgetProviderInfo?) {
            // Skip the platform binding; only the size pushes matter here.
        }

        override fun updateAppWidgetSize(
            newOptions: Bundle?,
            minWidth: Int,
            minHeight: Int,
            maxWidth: Int,
            maxHeight: Int,
        ) {
            sizePushes += 1
        }
    }

    @Test
    fun resizeDragPushesNoSizeHintsUntilTheDragEnds() {
        val host = LauncherAppWidgetHost(context, hostId = 0)
        val providerInfo = AppWidgetProviderInfo().apply {
            provider = ComponentName("app.typelauncher.fakewidget", "FakeProvider")
            minWidth = 200
            minHeight = 200
        }
        lateinit var hostView: SizeCountingHostView
        var persistedHeight: Int? = null

        composeRule.setContent {
            TypeLauncherTheme {
                HostedWidgetCard(
                    widgetId = 7,
                    appWidgetHost = host,
                    appWidgetManager = null,
                    customHeightDp = null,
                    canMoveUp = false,
                    canMoveDown = false,
                    onRemoveWidget = {},
                    onResizeWidget = { heightDp -> persistedHeight = heightDp },
                    onMoveWidget = { _, _ -> },
                    providerInfoOverride = providerInfo,
                    createWidgetView = { viewContext, _ ->
                        SizeCountingHostView(viewContext, host).also { hostView = it }
                    },
                    initiallyResizing = true,
                )
            }
        }
        composeRule.waitForIdle()
        val pushesBeforeDrag = hostView.sizePushes

        // Drag the resize handle through many discrete frames, each changing
        // the height — without the in-drag guard every step pushed a hint.
        composeRule.onNodeWithTag(WIDGET_RESIZE_HANDLE_TAG).performTouchInput {
            down(center)
            repeat(10) { moveBy(Offset(0f, 24f)) }
        }
        composeRule.waitForIdle()

        assertEquals(
            "no size hints may be pushed while the resize drag is active",
            pushesBeforeDrag,
            hostView.sizePushes,
        )

        composeRule.onNodeWithTag(WIDGET_RESIZE_HANDLE_TAG).performTouchInput { up() }
        composeRule.waitForIdle()

        assertTrue("the drag end must persist the new height", (persistedHeight ?: 0) > 0)
        assertTrue(
            "the settled size must be pushed after the drag ends",
            hostView.sizePushes > pushesBeforeDrag,
        )
    }
}
