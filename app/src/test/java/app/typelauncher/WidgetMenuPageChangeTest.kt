package app.typelauncher

import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression test for a widget's actions menu (Resize / Move up / Remove)
 * reappearing over a different page after a quick swipe away and back. A
 * `DropdownMenu` popup positions itself once from the anchor's
 * `LayoutCoordinates` and never re-tracks a later `graphicsLayer`-only
 * translation — exactly what the carousel's page-slide animation is — so a
 * menu left open while paging away froze at its original screen position and
 * resurfaced, looking like a spurious long-press menu, once another page
 * settled underneath that stale spot. The fix closes the menu the moment its
 * page stops being current.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WidgetMenuPageChangeTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun clearPersistedCache() {
        context.applicationContext
            .getSharedPreferences("widget_size_cache", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    private class StubHostView(
        context: Context,
        host: LauncherAppWidgetHost,
    ) : LauncherAppWidgetHostView(context, host) {
        override fun setAppWidget(appWidgetId: Int, info: AppWidgetProviderInfo?) {
            // Skip the platform binding; the test never resolves a real widget.
        }

        override fun updateAppWidgetSize(
            newOptions: Bundle?,
            minWidth: Int,
            minHeight: Int,
            maxWidth: Int,
            maxHeight: Int,
        ) {
            // Skip the actual AppWidgetService IPC.
        }
    }

    @Test
    fun menuClosesWhenPageStopsBeingCurrent() {
        val host = LauncherAppWidgetHost(context, /* hostId = */ 0)
        val providerInfo = AppWidgetProviderInfo().apply {
            provider = ComponentName("app.typelauncher.fakewidget", "FakeProvider")
            minWidth = 200
            minHeight = 200
        }
        var isCurrentPage by mutableStateOf(true)

        composeRule.setContent {
            TypeLauncherTheme {
                HostedWidgetCard(
                    widgetId = WIDGET_ID,
                    appWidgetHost = host,
                    appWidgetManager = null,
                    customHeightDp = null,
                    canMoveUp = false,
                    canMoveDown = false,
                    onRemoveWidget = {},
                    onResizeWidget = {},
                    onMoveWidget = { _, _ -> },
                    providerInfoOverride = providerInfo,
                    createWidgetView = { viewContext, _ -> StubHostView(viewContext, host) },
                    initiallyMenuExpanded = true,
                    isCurrentPage = isCurrentPage,
                )
            }
        }
        composeRule.waitForIdle()

        // Sanity check: the menu opens as instructed by the test seam.
        composeRule.onNodeWithTag("$RESIZE_WIDGET_ACTION_TAG:$WIDGET_ID").assertExists()

        // Simulate the carousel settling onto another page.
        isCurrentPage = false
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("$RESIZE_WIDGET_ACTION_TAG:$WIDGET_ID").assertDoesNotExist()
    }

    private companion object {
        const val WIDGET_ID = 42
    }
}
