package app.typelauncher

import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Home's widget area ("Widgets on home screen"): entering and leaving edit
 * mode must keep every hosted widget view bound (no re-inflation, no
 * RemoteViews re-apply), the inline actions show only while editing, and the
 * empty state, Done, and a long-press on the area route to their actions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HomeWidgetsTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private class RecordingHostView(
        context: Context,
        host: LauncherAppWidgetHost,
        private val onBound: (Int) -> Unit,
    ) : LauncherAppWidgetHostView(context, host) {
        override fun setAppWidget(appWidgetId: Int, info: AppWidgetProviderInfo?) {
            onBound(appWidgetId)
        }

        override fun updateAppWidgetSize(
            newOptions: Bundle?,
            minWidth: Int,
            minHeight: Int,
            maxWidth: Int,
            maxHeight: Int,
        ) = Unit
    }

    private val providerInfo = AppWidgetProviderInfo().apply {
        provider = ComponentName("com.example.widget", "FakeProvider")
        minWidth = 200
        minHeight = 200
        targetCellWidth = 2
        targetCellHeight = 1
    }

    @Test
    fun togglingEditModeNeverRebindsAWidget() {
        val host = LauncherAppWidgetHost(context, /* hostId = */ 0)
        val boundIds = mutableListOf<Int>()
        var isEditing by mutableStateOf(false)
        composeRule.setContent {
            TypeLauncherTheme {
                TestHomeWidgets(
                    widgetIds = listOf(1, 2),
                    isEditing = isEditing,
                    host = host,
                    providerInfoOverride = { providerInfo },
                    createWidgetView = { viewContext, _ ->
                        RecordingHostView(viewContext, host) { id -> boundIds += id }
                    },
                )
            }
        }
        composeRule.waitForIdle()
        assertEquals(listOf(1, 2), boundIds)
        composeRule.onNodeWithTag("$WIDGET_INLINE_ACTIONS_TAG:1").assertDoesNotExist()

        isEditing = true
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("$WIDGET_INLINE_ACTIONS_TAG:1").assertExists()
        // The top widget can't move up, the bottom one can't move down.
        composeRule.onNodeWithTag("$MOVE_UP_WIDGET_ACTION_TAG:1").assertDoesNotExist()
        composeRule.onNodeWithTag("$MOVE_DOWN_WIDGET_ACTION_TAG:2").assertDoesNotExist()

        isEditing = false
        composeRule.waitForIdle()

        assertEquals(listOf(1, 2), boundIds)
    }

    @Test
    fun leavingEditModeHidesAnOpenResizeHandle() {
        val host = LauncherAppWidgetHost(context, /* hostId = */ 0)
        var isEditing by mutableStateOf(true)
        composeRule.setContent {
            TypeLauncherTheme {
                TestHomeWidgets(
                    widgetIds = listOf(1),
                    isEditing = isEditing,
                    host = host,
                    providerInfoOverride = { providerInfo },
                    createWidgetView = { viewContext, _ -> RecordingHostView(viewContext, host) {} },
                )
            }
        }
        composeRule.onNodeWithTag("$RESIZE_WIDGET_ACTION_TAG:1").performClick()
        composeRule.onNodeWithTag(WIDGET_RESIZE_HANDLE_TAG).assertExists()

        isEditing = false
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(WIDGET_RESIZE_HANDLE_TAG).assertDoesNotExist()
    }

    @Test
    fun emptyAreaShowsAddButtonThatOpensThePicker() {
        var adds = 0
        composeRule.setContent {
            TypeLauncherTheme {
                TestHomeWidgets(widgetIds = emptyList(), isEditing = false, onAddWidget = { adds++ })
            }
        }

        composeRule.onNodeWithTag(HOME_WIDGETS_EMPTY_ADD_TAG).performClick()

        assertEquals(1, adds)
    }

    @Test
    fun doneLeavesEditMode() {
        var stops = 0
        composeRule.setContent {
            TypeLauncherTheme {
                TestHomeWidgets(widgetIds = listOf(1), isEditing = true, onStopEditing = { stops++ })
            }
        }

        composeRule.onNodeWithTag(HOME_WIDGETS_DONE_TAG).performClick()

        assertEquals(1, stops)
    }

    @Test
    fun longPressOnTheAreaEntersEditMode() {
        var starts = 0
        composeRule.setContent {
            TypeLauncherTheme {
                TestHomeWidgets(widgetIds = listOf(1), isEditing = false, onStartEditing = { starts++ })
            }
        }

        // Below the (unavailable) widget card: empty space in the area.
        composeRule.onNodeWithTag(HOME_WIDGETS_TAG).performTouchInput {
            longClick(bottomCenter.copy(y = bottom - 10f))
        }
        composeRule.waitForIdle()

        assertEquals(1, starts)
    }

    @Test
    fun slowDragAcrossTheAreaDoesNotEnterEditMode() {
        var starts = 0
        composeRule.setContent {
            TypeLauncherTheme {
                TestHomeWidgets(widgetIds = listOf(1), isEditing = false, onStartEditing = { starts++ })
            }
        }

        // A slow pull that starts on the area: past touch slop well before
        // the long-press timeout, then held past it.
        composeRule.onNodeWithTag(HOME_WIDGETS_TAG).performTouchInput {
            val start = bottomCenter.copy(y = bottom - 200f)
            down(start)
            moveTo(start.copy(y = start.y + 120f), delayMillis = 100)
            advanceEventTime(1_000)
            up()
        }
        composeRule.waitForIdle()

        assertEquals(0, starts)
    }

    @androidx.compose.runtime.Composable
    private fun TestHomeWidgets(
        widgetIds: List<Int>,
        isEditing: Boolean,
        host: LauncherAppWidgetHost? = null,
        providerInfoOverride: ((Int) -> AppWidgetProviderInfo?)? = null,
        createWidgetView: ((Context, Int) -> android.appwidget.AppWidgetHostView)? = null,
        onStartEditing: () -> Unit = {},
        onStopEditing: () -> Unit = {},
        onAddWidget: () -> Unit = {},
    ) {
        HomeWidgets(
            widgetIds = widgetIds,
            isEditing = isEditing,
            isAddingWidget = false,
            isLoadingAvailableWidgets = false,
            availableWidgets = emptyList(),
            appWidgetHost = host,
            appWidgetManager = null,
            widgetHeights = emptyMap(),
            widgetProviderLabels = emptyMap(),
            strandedWidgetIds = emptySet(),
            workProfileWidgetRefreshToken = 0,
            isCurrentPage = true,
            onBoundsChanged = {},
            onStartEditing = onStartEditing,
            onStopEditing = onStopEditing,
            onAddWidget = onAddWidget,
            onDismissWidgetPicker = {},
            onSelectWidget = {},
            onRemoveWidget = {},
            onRestoreWidget = {},
            onResizeWidget = { _, _ -> },
            onMoveWidget = { _, _ -> },
            modifier = Modifier.fillMaxSize(),
            providerInfoOverride = providerInfoOverride,
            createWidgetView = createWidgetView,
        )
    }
}
