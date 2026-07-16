package app.typelauncher

import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the typed-search content sections — contact rows (monogram + name)
 * and calendar-event rows (time + color stripe + title) appended after the app
 * results with a hairline divider between non-empty sections — so the PR
 * `roborazzi-screenshots` artifact captures the mixed list. Also covers the
 * reversed sort (sections render visually above the apps, which stay anchored
 * to the bottom) and the zero-app-match state (first content row takes the
 * active-row highlight as the Enter target, with no leading divider).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ContentSearchScreenshotTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val apps = listOf("Maps", "Mail").map { installedApp(it) }
    private val contacts = listOf(
        ContactResult(contactId = 1, lookupKey = "l1", displayName = "Maria Lopez"),
        ContactResult(contactId = 2, lookupKey = "l2", displayName = "Mark Chen"),
    )
    private val events = listOf(
        AgendaEvent(
            title = "Marathon training",
            beginMillis = 0L,
            endMillis = 0L,
            isAllDay = false,
            displayTime = "9:00 AM",
            eventId = 10,
            calendarColor = 0xFF4285F4.toInt(),
        ),
        AgendaEvent(
            title = "Market visit",
            beginMillis = 1L,
            endMillis = 1L,
            isAllDay = false,
            displayTime = "2:30 PM – 4:00 PM",
            eventId = 11,
            calendarColor = 0xFF34A853.toInt(),
        ),
    )

    @Test
    fun contentSections_renderBelowApps() {
        composeContent(reverseLayout = false, apps = apps)
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Maria Lopez", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("Marathon training", useUnmergedTree = true).assertExists()

        capture("compose_content_search_sections_robolectric.png")
    }

    @Test
    fun contentSections_reversedSortKeepsAppsAtBottom() {
        composeContent(reverseLayout = true, apps = apps)
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Maria Lopez", useUnmergedTree = true).assertExists()

        capture("compose_content_search_sections_reversed_robolectric.png")
    }

    @Test
    fun contentSections_zeroAppMatchesHighlightFirstContact() {
        composeContent(reverseLayout = false, apps = emptyList())
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Maria Lopez", useUnmergedTree = true).assertExists()

        capture("compose_content_search_no_app_matches_robolectric.png")
    }

    @Test
    fun contentSections_renderAsRowsUnderIconOnlyGrid() {
        // The app grid honors the "Icon only" style; the content sections stay
        // full-span name-beside rows below it (events have no tile form, and a
        // nameless contact tile is useless until profile photos exist).
        composeContent(reverseLayout = false, apps = apps, layout = AppListLayout.IconOnly)
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Maria Lopez", useUnmergedTree = true).assertExists()

        capture("compose_content_search_icon_only_grid_robolectric.png")
    }

    @Test
    fun contentSections_renderAsRowsUnderNameBelowGrid() {
        // Same contract for the "Name below" grid: labeled app tiles, then the
        // full-span content rows.
        composeContent(reverseLayout = false, apps = apps, layout = AppListLayout.NameBelow)
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Maria Lopez", useUnmergedTree = true).assertExists()

        capture("compose_content_search_name_below_grid_robolectric.png")
    }

    @Test
    fun contentOnlyResultsKeepListBoundsPublished() {
        // Regression: with zero app matches but content results, the lazy list
        // renders — so the "no results" effect must not null the published
        // list bounds, or the carousel stops reserving in-list vertical
        // gestures and a pull over the results opens recents/the shade.
        val boundsLog = mutableListOf<androidx.compose.ui.geometry.Rect?>()
        composeRule.setContent {
            TypeLauncherTheme {
                Box(
                    modifier = Modifier
                        .width(320.dp)
                        .height(480.dp),
                ) {
                    AppsCard(
                        apps = emptyList(),
                        dockLimit = Int.MAX_VALUE,
                        layout = AppListLayout.NameBeside,
                        iconSizeDp = 43,
                        highlightFirst = true,
                        contactResults = contacts,
                        eventResults = events,
                        onAppListBoundsChanged = { boundsLog.add(it) },
                        onLaunchApp = {},
                        onOpenAppInfo = {},
                        onToggleDock = { _, _ -> },
                        onResetRank = {},
                        onRenameApp = { _, _ -> },
                        onHideApp = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()

        org.junit.Assert.assertTrue(
            "List bounds must be published for content-only results",
            boundsLog.isNotEmpty(),
        )
        org.junit.Assert.assertNotNull(
            "Published bounds must not be cleared to null while the list renders",
            boundsLog.last(),
        )
    }

    private fun composeContent(
        reverseLayout: Boolean,
        apps: List<InstalledApp>,
        layout: AppListLayout = AppListLayout.NameBeside,
    ) {
        composeRule.setContent {
            TypeLauncherTheme {
                Box(
                    modifier = Modifier
                        .width(320.dp)
                        .height(480.dp)
                        .background(Color(0xFFEFEFEF)),
                ) {
                    AppsCard(
                        apps = apps,
                        dockLimit = Int.MAX_VALUE,
                        layout = layout,
                        iconSizeDp = 43,
                        highlightFirst = true,
                        reverseLayout = reverseLayout,
                        contactResults = contacts,
                        eventResults = events,
                        onLaunchApp = {},
                        onOpenAppInfo = {},
                        onToggleDock = { _, _ -> },
                        onResetRank = {},
                        onRenameApp = { _, _ -> },
                        onHideApp = {},
                    )
                }
            }
        }
    }

    private fun installedApp(name: String): InstalledApp {
        val component = ComponentName("com.example.${name.lowercase()}", "Main")
        return InstalledApp(
            name = name,
            packageName = "com.example.${name.lowercase()}",
            launchIntent = Intent.makeMainActivity(component),
            user = Process.myUserHandle(),
            isWorkApp = false,
            launchWithLauncherApps = true,
        )
    }

    private fun capture(name: String, widthPx: Int = 840, heightPx: Int = 1260) {
        val isRecord = System.getProperty("roborazzi.test.record") == "true"
        val isVerify = System.getProperty("roborazzi.test.verify") == "true"
        if (!isRecord && !isVerify) return
        composeRule.awaitAppIconsResolved()
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
