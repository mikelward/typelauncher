package app.typelauncher

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import com.github.takahirom.roborazzi.captureRoboImage
import java.time.LocalDate
import java.time.ZoneId

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
