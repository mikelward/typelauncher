package app.typelauncher

import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression test for two widgets from the same app collapsing onto a single
 * widget after their `HostedWidgetCard` slot is recycled.
 *
 * The host view is bound to its `appWidgetId` only inside the `AndroidView`
 * factory, which Compose runs once per view instance. When a `LazyColumn`
 * recycles an item slot for a different `widgetId`, the factory is not re-run,
 * so a recycled view keeps the previous widget's binding — and two cards for
 * the same provider end up showing the same widget. Wrapping the `AndroidView`
 * in `key(widgetId)` forces the old view to be disposed and a fresh one created
 * (and rebound) whenever the id changes.
 *
 * Recomposing the same `HostedWidgetCard` call site with a different `widgetId`
 * reproduces the recycle deterministically without a real `LazyColumn`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HostedWidgetCardReuseTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun clearPersistedCache() {
        // HostedWidgetCard's size-hint path persists to this prefs file; clear
        // it so tests don't bleed into each other.
        context.applicationContext
            .getSharedPreferences("widget_size_cache", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    /**
     * Records the id it gets bound to and skips the platform binding work, so
     * the test never needs a real AppWidgetHost/AppWidgetService binding (which
     * doesn't resolve under Robolectric). Same approach as the size-cache test.
     */
    private class RecordingHostView(
        context: Context,
        host: LauncherAppWidgetHost,
        private val onBound: (Int) -> Unit,
    ) : LauncherAppWidgetHostView(context, host) {
        override fun setAppWidget(appWidgetId: Int, info: AppWidgetProviderInfo?) {
            onBound(appWidgetId)
            // Skip the platform binding; the test only cares which id we bound.
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
    fun changingWidgetId_rebindsHostViewToNewId() {
        val host = LauncherAppWidgetHost(context, /* hostId = */ 0)
        val providerInfo = AppWidgetProviderInfo().apply {
            provider = ComponentName("app.typelauncher.fakewidget", "FakeProvider")
            minWidth = 200
            minHeight = 200
            targetCellWidth = 2
            targetCellHeight = 1
        }
        val boundIds = mutableListOf<Int>()
        var widgetId by mutableStateOf(FIRST_ID)

        composeRule.setContent {
            TypeLauncherTheme {
                HostedWidgetCard(
                    widgetId = widgetId,
                    appWidgetHost = host,
                    appWidgetManager = null,
                    customHeightDp = null,
                    canMoveUp = false,
                    canMoveDown = false,
                    onRemoveWidget = {},
                    onResizeWidget = {},
                    onMoveWidget = { _, _ -> },
                    providerInfoOverride = providerInfo,
                    createWidgetView = { viewContext, _ ->
                        RecordingHostView(viewContext, host) { id -> boundIds += id }
                    },
                )
            }
        }
        composeRule.waitForIdle()

        assertEquals(listOf(FIRST_ID), boundIds)

        // Recycle the slot onto the second widget of the same provider.
        widgetId = SECOND_ID
        composeRule.waitForIdle()

        // Without key(widgetId) the factory never re-runs, so the second id is
        // never bound and both cards keep showing the first widget.
        assertEquals(listOf(FIRST_ID, SECOND_ID), boundIds)
    }

    private companion object {
        const val FIRST_ID = 100
        const val SECOND_ID = 101
    }
}
