package app.typelauncher

import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun halfWidthWidgetsSitSideBySide() {
        val host = LauncherAppWidgetHost(context, /* hostId = */ 0)
        composeRule.setContent {
            TypeLauncherTheme {
                TestHomeWidgets(
                    widgetIds = listOf(1, 2, 3),
                    isEditing = false,
                    host = host,
                    providerInfoOverride = { providerInfo },
                    createWidgetView = { viewContext, _ -> RecordingHostView(viewContext, host) {} },
                    spans = mapOf(1 to 2, 2 to 2),
                )
            }
        }
        composeRule.waitForIdle()

        val first = composeRule.onNodeWithTag("$WIDGET_CARD_TAG:1").getBoundsInRoot()
        val second = composeRule.onNodeWithTag("$WIDGET_CARD_TAG:2").getBoundsInRoot()
        val third = composeRule.onNodeWithTag("$WIDGET_CARD_TAG:3").getBoundsInRoot()
        // Same row, equal widths, the second starting after the first.
        assertEquals(first.top, second.top)
        assertEquals(first.right - first.left, second.right - second.left)
        assertTrue(second.left > first.right)
        // The full-width widget takes the next row and spans both.
        assertTrue(third.top >= first.bottom)
        assertEquals(first.left, third.left)
        assertEquals(second.right, third.right)
    }

    @Test
    fun draggingTheWidthHandleCommitsASnappedSpan() {
        val host = LauncherAppWidgetHost(context, /* hostId = */ 0)
        val committed = mutableListOf<Pair<Int, Int>>()
        composeRule.setContent {
            TypeLauncherTheme {
                TestHomeWidgets(
                    widgetIds = listOf(1),
                    isEditing = true,
                    host = host,
                    providerInfoOverride = { providerInfo },
                    createWidgetView = { viewContext, _ -> RecordingHostView(viewContext, host) {} },
                    onResizeWidgetSpan = { id, span -> committed += id to span },
                )
            }
        }
        composeRule.onNodeWithTag("$RESIZE_WIDGET_ACTION_TAG:1").performClick()
        val cardBounds = composeRule.onNodeWithTag("$WIDGET_CARD_TAG:1").getBoundsInRoot()
        val cardWidth = cardBounds.right - cardBounds.left
        val cardWidthPx = with(composeRule.density) { cardWidth.toPx() }

        // Drag the end-edge handle a little over half the card toward the
        // start: about 1.8 of the 4 columns remain, which snaps to 2.
        composeRule.onNodeWithTag(WIDGET_WIDTH_HANDLE_TAG).performTouchInput {
            swipe(start = center, end = center.copy(x = center.x - cardWidthPx * 0.55f), durationMillis = 300)
        }
        composeRule.waitForIdle()

        assertEquals(listOf(1 to 2), committed)
        // Resize mode ends with the drag.
        composeRule.onNodeWithTag(WIDGET_WIDTH_HANDLE_TAG).assertDoesNotExist()
    }

    /** What the launcher's page carousel sees: drag consumed by a child, through nested scroll. */
    private class ConsumedRecorder : NestedScrollConnection {
        var consumed = Offset.Zero
            private set

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (source == NestedScrollSource.UserInput) this.consumed += consumed
            return Offset.Zero
        }
    }

    private fun setEditingWidgetUnder(recorder: ConsumedRecorder) {
        val host = LauncherAppWidgetHost(context, /* hostId = */ 0)
        composeRule.setContent {
            TypeLauncherTheme {
                Box(Modifier.fillMaxSize().nestedScroll(recorder)) {
                    TestHomeWidgets(
                        widgetIds = listOf(1),
                        isEditing = true,
                        host = host,
                        providerInfoOverride = { providerInfo },
                        createWidgetView = { viewContext, _ -> RecordingHostView(viewContext, host) {} },
                    )
                }
            }
        }
        composeRule.onNodeWithTag("$RESIZE_WIDGET_ACTION_TAG:1").performClick()
        composeRule.waitForIdle()
    }

    @Test
    fun aWidthDragIsReportedAsConsumedSoThePageDoesNotSwipe() {
        // The carousel only stays out of a horizontal drag once a child reports
        // consuming it; before, the width handle reported nothing and a width
        // drag swiped the page instead of resizing.
        val recorder = ConsumedRecorder()
        setEditingWidgetUnder(recorder)

        composeRule.onNodeWithTag(WIDGET_WIDTH_HANDLE_TAG).performTouchInput {
            down(center)
            repeat(5) { moveBy(Offset(-40f, 0f)) }
        }
        composeRule.waitForIdle()

        assertTrue("width drag must report horizontal consumption", recorder.consumed.x < 0f)
        assertEquals(0f, recorder.consumed.y, 0f)
        composeRule.onNodeWithTag(WIDGET_WIDTH_HANDLE_TAG).performTouchInput { up() }
    }

    @Test
    fun aHeightDragIsReportedAsConsumedSoTheLauncherDoesNotPull() {
        val recorder = ConsumedRecorder()
        setEditingWidgetUnder(recorder)

        composeRule.onNodeWithTag(WIDGET_RESIZE_HANDLE_TAG).performTouchInput {
            down(center)
            repeat(5) { moveBy(Offset(0f, 40f)) }
        }
        composeRule.waitForIdle()

        assertTrue("height drag must report vertical consumption", recorder.consumed.y > 0f)
        assertEquals(0f, recorder.consumed.x, 0f)
        composeRule.onNodeWithTag(WIDGET_RESIZE_HANDLE_TAG).performTouchInput { up() }
    }

    @Test
    fun aWidthDragReleasedBeforeTheNextFrameCommitsWhereTheFingerEnded() {
        val host = LauncherAppWidgetHost(context, /* hostId = */ 0)
        val committed = mutableListOf<Pair<Int, Int>>()
        composeRule.setContent {
            TypeLauncherTheme {
                TestHomeWidgets(
                    widgetIds = listOf(1),
                    isEditing = true,
                    host = host,
                    providerInfoOverride = { providerInfo },
                    createWidgetView = { viewContext, _ -> RecordingHostView(viewContext, host) {} },
                    onResizeWidgetSpan = { id, span -> committed += id to span },
                )
            }
        }
        composeRule.onNodeWithTag("$RESIZE_WIDGET_ACTION_TAG:1").performClick()
        composeRule.waitForIdle()
        val cardBounds = composeRule.onNodeWithTag("$WIDGET_CARD_TAG:1").getBoundsInRoot()
        val cardWidthPx = with(composeRule.density) { (cardBounds.right - cardBounds.left).toPx() }

        // No frame between the drag and the release: nothing recomposes, so a
        // span captured from composition would still be the starting 4.
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithTag(WIDGET_WIDTH_HANDLE_TAG).performTouchInput {
            down(center)
            moveBy(androidx.compose.ui.geometry.Offset(-viewConfiguration.touchSlop - 1f, 0f))
            moveBy(androidx.compose.ui.geometry.Offset(-cardWidthPx * 0.55f, 0f))
            up()
        }
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()

        assertEquals(listOf(1 to 2), committed)
    }

    @Test
    fun widerAndNarrowerStepAnEndOfRowWidgetsWidth() {
        val host = LauncherAppWidgetHost(context, /* hostId = */ 0)
        val committed = mutableListOf<Pair<Int, Int>>()
        composeRule.setContent {
            TypeLauncherTheme {
                // Widget 2 ends its row at the screen edge, where the width
                // handle has no room to be dragged outward.
                TestHomeWidgets(
                    widgetIds = listOf(1, 2, 3),
                    isEditing = true,
                    host = host,
                    providerInfoOverride = { providerInfo },
                    createWidgetView = { viewContext, _ -> RecordingHostView(viewContext, host) {} },
                    spans = mapOf(1 to 2, 2 to 2),
                    onResizeWidgetSpan = { id, span -> committed += id to span },
                )
            }
        }

        composeRule.onNodeWithTag("$WIDER_WIDGET_ACTION_TAG:2").performClick()
        composeRule.onNodeWithTag("$NARROWER_WIDGET_ACTION_TAG:2").performClick()

        assertEquals(listOf(2 to 3, 2 to 1), committed)
        // A full-row widget can't get wider.
        composeRule.onNodeWithTag("$WIDER_WIDGET_ACTION_TAG:3").assertDoesNotExist()
        composeRule.onNodeWithTag("$NARROWER_WIDGET_ACTION_TAG:3").assertExists()
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
        spans: Map<Int, Int> = emptyMap(),
        onResizeWidgetSpan: (Int, Int) -> Unit = { _, _ -> },
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
            widgetSpans = spans,
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
            onResizeWidgetSpan = onResizeWidgetSpan,
            modifier = Modifier.fillMaxSize(),
            providerInfoOverride = providerInfoOverride,
            createWidgetView = createWidgetView,
        )
    }
}
