package app.typelauncher

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the contact quick-actions sheet so the PR `roborazzi-screenshots`
 * artifact captures both steps: the channel list (Phone, Message, an installed
 * app, Email, and "Open contact") and, after tapping the multi-number Call
 * channel, its number picker with the default number on top. Composes
 * [ContactActionsSheetContent] directly (not the `Dialog` wrapper) so the
 * Robolectric tree renders without a popup window, mirroring the
 * EditAppDialogContent screenshot approach.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ContactActionsSheetScreenshotTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val actions = ContactActions(
        contact = ContactResult(contactId = 1, lookupKey = "l1", displayName = "Jess Ward"),
        channels = listOf(
            // Call carries a default-handler package (the dialer) as well as its
            // glyph — the real shape after default-handler resolution. Its icon
            // can't load under Robolectric, so this exercises the glyph fallback
            // path (a built-in channel with a package must still show the glyph,
            // never a blank plate), which is what the sheet renders on the first
            // frame on-device too, before the real dialer icon swaps in.
            ContactChannel(
                id = "call",
                label = "Call",
                iconPackageName = "com.example.dialer",
                glyph = ContactChannelGlyph.Call,
                actions = listOf(
                    call("Mobile", "+1 555-0100", isDefault = true, dataId = 11L),
                    call("Home", "+1 555-0199", isDefault = false, dataId = 12L),
                ),
            ),
            ContactChannel(
                id = "message",
                label = "Message",
                iconPackageName = null,
                glyph = ContactChannelGlyph.Message,
                actions = listOf(sms("Mobile", "+1 555-0100")),
            ),
            ContactChannel(
                id = "com.whatsapp",
                label = "WhatsApp",
                iconPackageName = "com.whatsapp",
                glyph = null,
                actions = listOf(
                    view("Message"),
                    view("Voice call"),
                ),
            ),
            ContactChannel(
                id = "email",
                label = "Email",
                iconPackageName = null,
                glyph = ContactChannelGlyph.Email,
                actions = listOf(sms("Home", "jess@example.com")),
            ),
        ),
    )

    @Test
    fun sheet_channelList() {
        composeSheet()
        composeRule.waitForIdle()
        capture("compose_contact_actions_channels_robolectric.png", heightPx = 1280)
    }

    @Test
    fun sheet_numberPicker() {
        composeSheet()
        composeRule.waitForIdle()
        // Drill into the multi-number Call channel to show step two.
        composeRule.onNodeWithTag("$CONTACT_ACTIONS_CHANNEL_TAG:call", useUnmergedTree = true).performClick()
        composeRule.waitForIdle()
        capture("compose_contact_actions_numbers_robolectric.png")
    }

    @Test
    fun numberRow_longPressShowsDefaultMenu() {
        composeSheet()
        composeRule.waitForIdle()
        // Drill into the multi-number Call channel, then long-press the
        // non-default number to reveal its "Default" menu item. The menu is a
        // DropdownMenu Compose hosts in a separate popup window, so this captures
        // with captureScreenRoboImage (composites every window) rather than the
        // decorView-only capture the other cases use.
        composeRule.onNodeWithTag("$CONTACT_ACTIONS_CHANNEL_TAG:call", useUnmergedTree = true).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("$CONTACT_ACTIONS_ACTION_TAG:Home", useUnmergedTree = true)
            .performTouchInput { longClick() }
        composeRule.onNodeWithTag("$CONTACT_ACTIONS_DEFAULT_ACTION_TAG:Home", useUnmergedTree = true)
            .assertExists()
        composeRule.waitForIdle()
        captureScreen("compose_contact_actions_default_menu_robolectric.png")
    }

    private fun composeSheet() {
        composeRule.setContent {
            TypeLauncherTheme {
                Box(
                    modifier = Modifier
                        .width(360.dp)
                        .background(Color(0xFFEFEFEF))
                        .padding(16.dp),
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 6.dp,
                    ) {
                        ContactActionsSheetContent(
                            actions = actions,
                            onAction = {},
                            onOpenContactCard = {},
                        )
                    }
                }
            }
        }
    }

    private fun call(label: String, number: String, isDefault: Boolean, dataId: Long? = null) = ContactAction(
        label = label,
        detail = number,
        isDefault = isDefault,
        dataId = dataId,
        kind = ContactActionKind.Call(number),
    )

    private fun sms(label: String, detail: String) = ContactAction(
        label = label,
        detail = detail,
        isDefault = true,
        kind = ContactActionKind.Launch(Intent(Intent.ACTION_SENDTO, "smsto:$detail".toUri())),
    )

    private fun view(label: String) = ContactAction(
        label = label,
        detail = null,
        isDefault = false,
        kind = ContactActionKind.Launch(Intent(Intent.ACTION_VIEW)),
    )

    // Composites every window (the DropdownMenu popup rides in its own), unlike
    // [capture], which draws only the activity decorView.
    private fun captureScreen(name: String) {
        val isRecord = System.getProperty("roborazzi.test.record") == "true"
        val isVerify = System.getProperty("roborazzi.test.verify") == "true"
        if (!isRecord && !isVerify) return
        captureScreenRoboImage(filePath = "src/test/snapshots/images/$name")
    }

    private fun capture(name: String, widthPx: Int = 840, heightPx: Int = 900) {
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
