package app.typelauncher

import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the Widgets and Agenda pages with the wallpaper backdrop active
 * (the "Show wallpaper" setting, which backs every carousel page) over a
 * bright gradient — standing in for the wallpaper Robolectric can't
 * composite — so the PR `roborazzi-screenshots` artifact shows the
 * treatment: the page background is transparent, so the gradient shows
 * through the margins and the gaps around the opaque cards. The opaque-page
 * default (setting off) is pinned alongside so the pair documents the
 * setting's visual delta.
 *
 * Also covers the settings card's wallpaper pair ([WallpaperSettingsRows] —
 * the "Show wallpaper" switch and the "Change" hand-off to the system
 * wallpaper picker), captured in isolation because those rows sit too far
 * down a scrolling page for a whole-page capture to reach them. They live
 * here, rather than in a class of their own, because the CI screenshot job
 * runs an explicit `--tests` allow-list and the workflow executes under
 * `pull_request_target` — so the allow-list that runs is `main`'s, and a
 * brand-new screenshot class records nothing on the PR that introduces it.
 * Every wallpaper-facing surface in one already-listed class avoids that.
 *
 * "Widgets on home screen" shares the app-list slot the wallpaper reveals, so
 * its captures — the widget area in display, edit, and empty states over the
 * same gradient, and its settings rows — live here for the same reason.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WallpaperAllPagesScreenshotTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun agendaScreen_overWallpaper_revealsBackdropAroundCards() {
        composeRule.setContent {
            TypeLauncherTheme {
                GradientWallpaperStandIn {
                    AgendaScreen(
                        agenda = AgendaUiState.Events(sampleEvents()),
                        innerPadding = PaddingValues(0.dp),
                        wallpaperActive = true,
                        onRequestCalendarPermission = {},
                        onOpenAgendaEvent = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Morning standup").assertExists()

        capture("compose_wallpaper_all_pages_agenda_robolectric.png")
    }

    @Test
    fun agendaScreen_withoutWallpaper_staysOpaque() {
        composeRule.setContent {
            TypeLauncherTheme {
                GradientWallpaperStandIn {
                    AgendaScreen(
                        agenda = AgendaUiState.Events(sampleEvents()),
                        innerPadding = PaddingValues(0.dp),
                        onRequestCalendarPermission = {},
                        onOpenAgendaEvent = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Morning standup").assertExists()

        capture("compose_wallpaper_all_pages_agenda_off_robolectric.png")
    }

    @Test
    fun widgetsScreen_overWallpaper_revealsBackdropAroundCards() {
        composeRule.setContent {
            TypeLauncherTheme {
                GradientWallpaperStandIn {
                    WidgetsScreen(
                        widgetIds = emptyList(),
                        availableWidgets = emptyList(),
                        isAddingWidget = false,
                        appWidgetHost = null,
                        appWidgetManager = null,
                        innerPadding = PaddingValues(0.dp),
                        wallpaperActive = true,
                        onAddWidget = {},
                        onDismissWidgetPicker = {},
                        onSelectWidget = {},
                        onRemoveWidget = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(WIDGETS_SCREEN_TAG).assertExists()

        capture("compose_wallpaper_all_pages_widgets_robolectric.png")
    }

    @Test
    fun homeWidgets_editing_overWallpaper() {
        val host = LauncherAppWidgetHost(composeRule.activity, /* hostId = */ 0)
        val providerInfo = AppWidgetProviderInfo().apply {
            provider = ComponentName("com.example.widget", "SampleProvider")
            minWidth = 200
            minHeight = 300
            targetCellWidth = 4
            targetCellHeight = 2
        }
        composeRule.setContent {
            TypeLauncherTheme(themeMode = ThemeMode.Light, dynamicColor = false) {
                GradientWallpaperStandIn {
                    HomeWidgetsForCapture(
                        widgetIds = listOf(1, 2),
                        isEditing = true,
                        host = host,
                        providerInfo = providerInfo,
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(HOME_WIDGETS_DONE_TAG).assertExists()

        capture("compose_home_widgets_editing_robolectric.png")
    }

    // Two widgets sharing a row: each gets Move left / right beside Move up /
    // down, grayed at the row's ends (and the list's, for up / down).
    @Test
    fun homeWidgets_editing_sharedRow() {
        captureSharedRowEditing(LayoutDirection.Ltr, "compose_home_widgets_editing_shared_row_robolectric.png")
    }

    // The same row right-to-left: the row starts at the right, so the
    // mirrored arrows and their labels swap sides.
    @Test
    fun homeWidgets_editing_sharedRow_rtl() {
        captureSharedRowEditing(LayoutDirection.Rtl, "compose_home_widgets_editing_shared_row_rtl_robolectric.png")
    }

    private fun captureSharedRowEditing(direction: LayoutDirection, fileName: String) {
        val host = LauncherAppWidgetHost(composeRule.activity, /* hostId = */ 0)
        val providerInfo = AppWidgetProviderInfo().apply {
            provider = ComponentName("com.example.widget", "SampleProvider")
            minWidth = 200
            minHeight = 300
            targetCellWidth = 2
            targetCellHeight = 2
        }
        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                TypeLauncherTheme(themeMode = ThemeMode.Light, dynamicColor = false) {
                    GradientWallpaperStandIn {
                        HomeWidgetsForCapture(
                            widgetIds = listOf(1, 2, 3),
                            isEditing = true,
                            host = host,
                            providerInfo = providerInfo,
                            spans = mapOf(1 to 2, 2 to 2, 3 to 4),
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("$MOVE_END_WIDGET_ACTION_TAG:1").assertExists()

        capture(fileName)
    }

    @Test
    fun homeWidgets_display_overWallpaper() {
        val host = LauncherAppWidgetHost(composeRule.activity, /* hostId = */ 0)
        val providerInfo = AppWidgetProviderInfo().apply {
            provider = ComponentName("com.example.widget", "SampleProvider")
            minWidth = 200
            minHeight = 300
            targetCellWidth = 4
            targetCellHeight = 2
        }
        composeRule.setContent {
            TypeLauncherTheme(themeMode = ThemeMode.Light, dynamicColor = false) {
                GradientWallpaperStandIn {
                    HomeWidgetsForCapture(
                        widgetIds = listOf(1, 2),
                        isEditing = false,
                        host = host,
                        providerInfo = providerInfo,
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(HOME_WIDGETS_DONE_TAG).assertDoesNotExist()

        capture("compose_home_widgets_display_robolectric.png")
    }

    @Test
    fun kioskScreen_showsOnlyTheHomeWidgets() {
        val host = LauncherAppWidgetHost(composeRule.activity, /* hostId = */ 0)
        val providerInfo = AppWidgetProviderInfo().apply {
            provider = ComponentName("com.example.widget", "SampleProvider")
            minWidth = 100
            minHeight = 100
            targetCellWidth = 2
            targetCellHeight = 2
        }
        composeRule.setContent {
            TypeLauncherTheme(themeMode = ThemeMode.Light, dynamicColor = false) {
                GradientWallpaperStandIn {
                    KioskScreen(
                        widgetIds = listOf(1, 2, 3),
                        isHomeReady = true,
                        appWidgetHost = host,
                        appWidgetManager = null,
                        widgetHeights = mapOf(1 to 160, 2 to 160, 3 to 120),
                        widgetSpans = mapOf(1 to 2, 2 to 2),
                        widgetProviderLabels = emptyMap(),
                        strandedWidgetIds = emptySet(),
                        workProfileWidgetRefreshToken = 0,
                        onExitHold = {},
                        providerInfoOverride = { providerInfo },
                        createWidgetView = { viewContext, widgetId -> StandInWidgetView(viewContext, host, widgetId) },
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("$WIDGET_CARD_TAG:3").assertExists()

        capture("compose_kiosk_screen_robolectric.png")
    }

    @Test
    fun homeWidgets_mixedWidths_overWallpaper() {
        val host = LauncherAppWidgetHost(composeRule.activity, /* hostId = */ 0)
        val providerInfo = AppWidgetProviderInfo().apply {
            provider = ComponentName("com.example.widget", "SampleProvider")
            minWidth = 100
            minHeight = 100
            targetCellWidth = 2
            targetCellHeight = 2
        }
        composeRule.setContent {
            TypeLauncherTheme(themeMode = ThemeMode.Light, dynamicColor = false) {
                GradientWallpaperStandIn {
                    // Two half-row widgets side by side, then a full-row one.
                    HomeWidgetsForCapture(
                        widgetIds = listOf(1, 2, 3),
                        isEditing = false,
                        host = host,
                        providerInfo = providerInfo,
                        spans = mapOf(1 to 2, 2 to 2),
                    )
                }
            }
        }
        composeRule.waitForIdle()

        capture("compose_home_widgets_mixed_widths_robolectric.png")
    }

    @Test
    @Config(qualifiers = "w914dp-h411dp-420dpi")
    fun homeWidgets_landscape_fullWidthWidgetsSitSideBySide() {
        val host = LauncherAppWidgetHost(composeRule.activity, /* hostId = */ 0)
        val providerInfo = AppWidgetProviderInfo().apply {
            provider = ComponentName("com.example.widget", "SampleProvider")
            minWidth = 100
            minHeight = 100
            targetCellWidth = 4
            targetCellHeight = 2
        }
        composeRule.setContent {
            TypeLauncherTheme(themeMode = ThemeMode.Light, dynamicColor = false) {
                GradientWallpaperStandIn {
                    // Three full-width widgets: landscape pairs the first two
                    // in one row and starts the third on the next.
                    HomeWidgetsForCapture(
                        widgetIds = listOf(1, 2, 3),
                        isEditing = false,
                        host = host,
                        providerInfo = providerInfo,
                    )
                }
            }
        }
        composeRule.waitForIdle()
        val first = composeRule.onNodeWithTag("$WIDGET_CARD_TAG:1").getBoundsInRoot()
        val second = composeRule.onNodeWithTag("$WIDGET_CARD_TAG:2").getBoundsInRoot()
        val third = composeRule.onNodeWithTag("$WIDGET_CARD_TAG:3").getBoundsInRoot()
        assertEquals(first.top, second.top)
        assertEquals(first.right - first.left, second.right - second.left)
        assertTrue("second widget should sit right of the first", second.left > first.right)
        assertEquals(first.left, third.left)
        assertTrue("third widget should start a new row", third.top > first.bottom)

        capture("compose_home_widgets_landscape_robolectric.png", widthPx = 2400, heightPx = 1080)
    }

    @Test
    fun homeWidgets_empty_overWallpaper() {
        composeRule.setContent {
            TypeLauncherTheme(themeMode = ThemeMode.Light, dynamicColor = false) {
                GradientWallpaperStandIn {
                    HomeWidgetsForCapture(widgetIds = emptyList(), isEditing = false)
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(HOME_WIDGETS_EMPTY_ADD_TAG).assertExists()

        capture("compose_home_widgets_empty_robolectric.png")
    }

    @Test
    fun homeWidgetsSettingsRows_light() {
        composeRule.setContent {
            TypeLauncherTheme(themeMode = ThemeMode.Light, dynamicColor = false) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    SectionCard(modifier = Modifier.padding(16.dp)) {
                        HomeWidgetsSettingsRows(
                            isHomeWidgetsShown = true,
                            onHomeWidgetsShownChanged = {},
                            onEditHomeWidgets = {},
                            isKioskMode = true,
                            isKioskDimWhenIdle = true,
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(EDIT_HOME_WIDGETS_BUTTON_TAG).assertExists()
        composeRule.onNodeWithTag(KIOSK_MODE_SWITCH_TAG).assertExists()
        composeRule.onNodeWithTag(KIOSK_DIM_WHEN_IDLE_SWITCH_TAG).assertExists()

        // Tall enough for every row: the switch, Edit, and both kiosk rows.
        capture("compose_home_widgets_settings_light_robolectric.png", heightPx = 800)
    }

    @Test
    fun homeWidgetsSettingsPreviewSketch_light() {
        captureSketch(
            widgetIds = listOf(1, 2, 3),
            heights = mapOf(1 to 160, 3 to 96),
            spans = mapOf(1 to 2, 2 to 2),
        )
        composeRule.onNodeWithTag("$SETTINGS_PREVIEW_HOME_WIDGET_SKETCH_TAG:3").assertExists()

        capture("compose_home_widgets_settings_preview_sketch_robolectric.png", heightPx = 400)
    }

    @Test
    fun homeWidgetsSettingsPreviewSketch_empty() {
        captureSketch(widgetIds = emptyList(), heights = emptyMap(), spans = emptyMap())
        composeRule.onNodeWithText("Add widget").assertExists()

        capture("compose_home_widgets_settings_preview_sketch_empty_robolectric.png", heightPx = 400)
    }

    /**
     * The Settings preview's widget sketch in a slot the size Settings gives
     * the app list (about two bars), over the gradient wallpaper stand-in.
     */
    private fun captureSketch(widgetIds: List<Int>, heights: Map<Int, Int>, spans: Map<Int, Int>) {
        composeRule.setContent {
            TypeLauncherTheme(themeMode = ThemeMode.Light, dynamicColor = false) {
                GradientWallpaperStandIn {
                    Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                        HomeWidgetsSketch(
                            widgetIds = widgetIds,
                            widgetHeights = heights,
                            widgetSpans = spans,
                            modifier = Modifier.height(136.dp),
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    /**
     * Home's widget area on its own, framed with Home's content inset so the
     * capture reads like the app-list slot it occupies. The stand-in host
     * view paints a labeled color block in place of a real provider's
     * RemoteViews, which Robolectric can't bind.
     */
    @Composable
    private fun HomeWidgetsForCapture(
        widgetIds: List<Int>,
        isEditing: Boolean,
        host: LauncherAppWidgetHost? = null,
        providerInfo: AppWidgetProviderInfo? = null,
        spans: Map<Int, Int> = emptyMap(),
    ) {
        HomeWidgets(
            widgetIds = widgetIds,
            isEditing = isEditing,
            isAddingWidget = false,
            isLoadingAvailableWidgets = false,
            availableWidgets = emptyList(),
            appWidgetHost = host,
            appWidgetManager = null,
            widgetHeights = mapOf(1 to 160, 2 to 120, 3 to 120),
            widgetSpans = spans,
            widgetProviderLabels = emptyMap(),
            strandedWidgetIds = emptySet(),
            workProfileWidgetRefreshToken = 0,
            isCurrentPage = true,
            onBoundsChanged = {},
            onStartEditing = {},
            onStopEditing = {},
            onAddWidget = {},
            onDismissWidgetPicker = {},
            onSelectWidget = {},
            onRemoveWidget = {},
            onRestoreWidget = {},
            onResizeWidget = { _, _ -> },
            onMoveWidget = { _, _ -> },
            onResizeWidgetSpan = { _, _ -> },
            modifier = Modifier
                .fillMaxSize()
                // Home's content inset around the app-list slot.
                .padding(8.dp),
            providerInfoOverride = providerInfo?.let { info -> { info } },
            createWidgetView = host?.let { widgetHost ->
                { viewContext, widgetId -> StandInWidgetView(viewContext, widgetHost, widgetId) }
            },
        )
    }

    private class StandInWidgetView(
        context: Context,
        host: LauncherAppWidgetHost,
        widgetId: Int,
    ) : LauncherAppWidgetHostView(context, host) {
        init {
            addView(
                TextView(context).apply {
                    text = "Widget $widgetId"
                    textSize = 18f
                    gravity = Gravity.CENTER
                    setTextColor(android.graphics.Color.WHITE)
                    setBackgroundColor(
                        when (widgetId) {
                            1 -> 0xFF3949AB.toInt()
                            2 -> 0xFF00897B.toInt()
                            else -> 0xFF8E24AA.toInt()
                        },
                    )
                },
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
            )
        }

        override fun setAppWidget(appWidgetId: Int, info: AppWidgetProviderInfo?) = Unit

        override fun updateAppWidgetSize(
            newOptions: Bundle?,
            minWidth: Int,
            minHeight: Int,
            maxWidth: Int,
            maxHeight: Int,
        ) = Unit
    }

    @Composable
    private fun GradientWallpaperStandIn(content: @Composable () -> Unit) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(listOf(Color(0xFF1E88E5), Color(0xFFF4511E))),
                ),
        ) {
            content()
        }
    }

    private fun sampleEvents(): List<AgendaEvent> {
        // Anchored to fixed wall-clock times on today's date so both rows
        // always group under a single deterministic "Today" header — a
        // now-relative anchor (`now + 60min`) crossed midnight when the test
        // ran in the last hour of the local day, growing a "Tomorrow" header
        // into the snapshot. The rendered times come from `displayTime`,
        // which is fixed either way.
        val todayAt9 = LocalDate.now()
            .atTime(9, 0)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        val hourMillis = 60 * 60 * 1000L
        return listOf(
            AgendaEvent(
                title = "Morning standup",
                beginMillis = todayAt9,
                endMillis = todayAt9 + hourMillis / 2,
                isAllDay = false,
                displayTime = "9:00 AM",
                eventId = 1L,
            ),
            AgendaEvent(
                title = "Design review",
                beginMillis = todayAt9 + 2 * hourMillis,
                endMillis = todayAt9 + 3 * hourMillis,
                isAllDay = false,
                displayTime = "11:00 AM",
                eventId = 2L,
            ),
        )
    }

    @Test
    fun wallpaperSettingsRows_light() {
        composeRule.setContent {
            // Fixed scheme (no dynamic color) so the card surface and the
            // action's primary tint are deterministic across runners rather
            // than device-tinted.
            TypeLauncherTheme(themeMode = ThemeMode.Light, dynamicColor = false) {
                WallpaperSettingsCard(isWallpaperShown = false)
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Change").assertExists()

        capture("compose_wallpaper_settings_light_robolectric.png", heightPx = 400)
    }

    @Test
    fun wallpaperSettingsRows_dark() {
        composeRule.setContent {
            TypeLauncherTheme(themeMode = ThemeMode.Dark, dynamicColor = false) {
                // Switch on — the state a user who cares about the wallpaper is
                // in when they reach for Change.
                WallpaperSettingsCard(isWallpaperShown = true)
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Change").assertExists()

        capture("compose_wallpaper_settings_dark_robolectric.png", heightPx = 400)
    }

    @Composable
    private fun WallpaperSettingsCard(isWallpaperShown: Boolean) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.TopCenter,
        ) {
            SectionCard(modifier = Modifier.padding(16.dp)) {
                WallpaperSettingsRows(
                    isWallpaperShown = isWallpaperShown,
                    onWallpaperShownChanged = {},
                    onChangeWallpaper = {},
                )
            }
        }
    }

    private fun capture(name: String, widthPx: Int = 720, heightPx: Int = 900) {
        val isRecord = System.getProperty("roborazzi.test.record") == "true"
        val isVerify = System.getProperty("roborazzi.test.verify") == "true"
        if (!isRecord && !isVerify) return
        val root = composeRule.activity.window.decorView.rootView
        root.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(widthPx, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(heightPx, android.view.View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, widthPx, heightPx)
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        bitmap.captureRoboImage(filePath = "src/test/snapshots/images/$name")
    }
}
