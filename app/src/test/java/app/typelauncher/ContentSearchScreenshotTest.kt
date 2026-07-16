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
import androidx.compose.ui.test.onNodeWithTag
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
 * Renders the typed-search content sections — contact rows (photo-or-monogram
 * circle + name) and calendar-event rows (time + title) appended after the
 * app results with a hairline divider between non-empty sections — so the PR
 * `roborazzi-screenshots` artifact captures the mixed list. Also covers the
 * reversed sort (sections render visually above the apps, which stay anchored
 * to the bottom), the zero-app-match state (first content row takes the
 * active-row highlight as the Enter target, with no leading divider), and the
 * contact-photo row (thumbnail circle beside a monogram fallback row).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ContentSearchScreenshotTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    // Distinct initials so the letter icons differ tile-to-tile.
    private val apps = listOf("Maps", "Phone", "Camera", "Notes").map { installedApp(it) }
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
        ),
        AgendaEvent(
            title = "Market visit",
            beginMillis = 1L,
            endMillis = 1L,
            isAllDay = false,
            displayTime = "2:30 PM – 4:00 PM",
            eventId = 11,
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
    fun contactRows_renderPhotoThumbnailWithMonogramFallback() {
        // One contact with a photo (decoded async from the shadow resolver's
        // registered stream — the capture helper waits for the swap-in), one
        // without: the row renders the circle-cropped thumbnail when a photo
        // exists and keeps the monogram otherwise.
        registerContactPhoto(PHOTO_URI)
        val mixedContacts = listOf(
            ContactResult(
                contactId = 1,
                lookupKey = "l1",
                displayName = "Maria Lopez",
                photoThumbnailUri = PHOTO_URI,
            ),
            ContactResult(contactId = 2, lookupKey = "l2", displayName = "Mark Chen"),
        )
        composeContent(reverseLayout = false, apps = apps, contacts = mixedContacts)
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Maria Lopez", useUnmergedTree = true).assertExists()

        capture("compose_content_search_contact_photo_robolectric.png")
    }

    @Test
    fun contactRows_renderStarBesideStarredContactOnly() {
        // Starred contacts rank first (see LauncherViewModel.loadContactIndex)
        // and show a trailing star; a non-starred contact renders the plain
        // name-beside row with no star. Both contacts here share a tier and
        // are pre-sorted starred-first, matching what the ViewModel would hand
        // the row in practice.
        val mixedContacts = listOf(
            ContactResult(contactId = 2, lookupKey = "l2", displayName = "Mark Chen", starred = true),
            ContactResult(contactId = 1, lookupKey = "l1", displayName = "Maria Lopez"),
        )
        composeContent(reverseLayout = false, apps = apps, contacts = mixedContacts)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(
            "$CONTACT_RESULT_STARRED_TAG:Mark Chen",
            useUnmergedTree = true,
        ).assertExists()
        composeRule.onNodeWithTag(
            "$CONTACT_RESULT_STARRED_TAG:Maria Lopez",
            useUnmergedTree = true,
        ).assertDoesNotExist()

        capture("compose_content_search_contact_starred_robolectric.png")
    }

    @Test
    fun contentSections_renderAsRowsUnderIconOnlyGrid() {
        // The app grid honors the "Icon only" style; the content sections stay
        // full-span name-beside rows below it (events have no tile form, and
        // contacts keep the name-beside row so the name stays scannable).
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
        contacts: List<ContactResult> = this.contacts,
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
        // Give every fake package a recognizable icon — the app's initial in
        // white on a colored plate — so the snapshots show real tiles instead
        // of the blank placeholders an icon-less package renders. A letter
        // (not a bare color swatch) keeps the tile distinguishable from the
        // active-first-result highlight, which also paints a colored rounded
        // square behind the icon. Same addActivityIcon seeding pattern as
        // AppIconDisambiguatorScreenshotTest.
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        org.robolectric.Shadows.shadowOf(context.packageManager).addActivityIcon(
            component,
            LetterIconDrawable(
                letter = name.first().uppercaseChar(),
                color = ICON_COLORS[iconColorCursor++ % ICON_COLORS.size],
            ),
        )
        return InstalledApp(
            name = name,
            packageName = "com.example.${name.lowercase()}",
            launchIntent = Intent.makeMainActivity(component),
            user = Process.myUserHandle(),
            isWorkApp = false,
            // PackageManager path, not LauncherApps: AppIconLoader.resolve
            // reads LauncherApps.getActivityList for launcher-apps entries,
            // which Robolectric's shadow leaves empty — the PM path is what
            // addActivityIcon above seeds.
            launchWithLauncherApps = false,
        )
    }

    private var iconColorCursor = 0

    /**
     * Registers a decodable photo blob for [uri] on the shadow resolver: a
     * simple avatar (light head-and-shoulders silhouette on a teal plate) so
     * the snapshot visibly shows a photo thumbnail rather than anything that
     * could be mistaken for the monogram fallback.
     */
    private fun registerContactPhoto(uri: String) {
        val size = 96
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        paint.color = android.graphics.Color.rgb(0x00, 0x69, 0x6B)
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
        paint.color = android.graphics.Color.rgb(0xF2, 0xDF, 0xCE)
        canvas.drawCircle(size / 2f, size * 0.38f, size * 0.2f, paint)
        canvas.drawOval(size * 0.2f, size * 0.62f, size * 0.8f, size * 1.1f, paint)
        val bytes = java.io.ByteArrayOutputStream()
            .also { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
            .toByteArray()
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        org.robolectric.Shadows.shadowOf(context.contentResolver)
            .registerInputStream(android.net.Uri.parse(uri), java.io.ByteArrayInputStream(bytes))
    }

    /** The app's initial in white, centered on a solid colored plate. */
    private class LetterIconDrawable(
        private val letter: Char,
        private val color: Int,
    ) : android.graphics.drawable.Drawable() {
        override fun draw(canvas: Canvas) {
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            paint.color = color
            canvas.drawRect(bounds, paint)
            paint.color = android.graphics.Color.WHITE
            paint.textAlign = android.graphics.Paint.Align.CENTER
            paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
            paint.textSize = bounds.height() * 0.55f
            val centerY = bounds.exactCenterY() - (paint.descent() + paint.ascent()) / 2f
            canvas.drawText(letter.toString(), bounds.exactCenterX(), centerY, paint)
        }

        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = android.graphics.PixelFormat.OPAQUE
        override fun getIntrinsicWidth(): Int = 108
        override fun getIntrinsicHeight(): Int = 108
    }

    private companion object {
        const val PHOTO_URI = "content://com.android.contacts/contacts/1/photo"
        val ICON_COLORS = intArrayOf(
            android.graphics.Color.rgb(0x42, 0x85, 0xF4),
            android.graphics.Color.rgb(0xEA, 0x43, 0x35),
            android.graphics.Color.rgb(0xFB, 0xBC, 0x05),
            android.graphics.Color.rgb(0x34, 0xA8, 0x53),
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
