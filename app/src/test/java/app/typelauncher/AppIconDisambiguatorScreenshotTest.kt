package app.typelauncher

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppIconDisambiguatorScreenshotTest {

    val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val ruleChain: RuleChain = RuleChain
        .outerRule(SeedAmbiguousAppsRule())
        .around(composeRule)

    @Before
    fun awaitInitialAppLoad() {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            !composeRule.activity.viewModel.uiState.value.isLoadingApps
        }
        composeRule.waitForIdle()
    }

    @Test
    fun bothChasesSurviveAndCarryDistinctBadges() {
        // Both same-named "Chase" entries survive the dedup pass. Test tags
        // include the disambiguated displayName so the two rows can be
        // addressed individually: Chase UK picks up "UK" from its package
        // tail; Chase US falls back to "SIG" (com.chase.sig.android) — not
        // pretty, but distinct, and the user can tell them apart in the
        // grid.
        composeRule.onNodeWithTag("$APP_ROW_TAG:Chase (UK)").assertIsDisplayed()
        composeRule.onNodeWithTag("$APP_ROW_TAG:Chase (SIG)").assertIsDisplayed()
        composeRule.onNodeWithTag("$APP_ICON_DISAMBIGUATOR_TAG:Chase (UK)").assertIsDisplayed()
        composeRule.onNodeWithTag("$APP_ICON_DISAMBIGUATOR_TAG:Chase (SIG)").assertIsDisplayed()

        saveScreenshot("compose_disambiguator_chase_pair_robolectric.png")
    }

    @Test
    fun amexThreePackTagsEachWithCountryBadge() {
        // Three Amex variants share a brand and first word; each gets a
        // country-code badge derived from the package tail. The plain "Amex"
        // entry (US package) gets "(US)" appended; the regional names "Amex
        // UK" / "Amex AU" already contain the country tag so the
        // parenthesised suffix is suppressed.
        composeRule.onNodeWithTag("$APP_ICON_DISAMBIGUATOR_TAG:Amex (US)").assertIsDisplayed()
        composeRule.onNodeWithTag("$APP_ICON_DISAMBIGUATOR_TAG:Amex UK").assertIsDisplayed()
        composeRule.onNodeWithTag("$APP_ICON_DISAMBIGUATOR_TAG:Amex AU").assertIsDisplayed()

        saveScreenshot("compose_disambiguator_amex_three_pack_robolectric.png")
    }

    private fun saveScreenshot(name: String) {
        val isRecord = System.getProperty("roborazzi.test.record") == "true"
        val isVerify = System.getProperty("roborazzi.test.verify") == "true"
        if (!isRecord && !isVerify) return
        val root = composeRule.activity.window.decorView.rootView
        root.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(2400, android.view.View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, 1080, 2400)
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        bitmap.captureRoboImage(filePath = "src/test/snapshots/images/$name")
    }

    private class SeedAmbiguousAppsRule : TestRule {
        override fun apply(base: Statement, description: Description): Statement =
            object : Statement() {
                override fun evaluate() {
                    val application = RuntimeEnvironment.getApplication()
                    listOf(
                        "docked_apps",
                        "dock_settings",
                        "app_launch_stats",
                        "widgets",
                        "app_metadata",
                        "hidden_apps",
                    ).forEach { name ->
                        application.getSharedPreferences(name, android.content.Context.MODE_PRIVATE)
                            .edit().clear().commit()
                    }
                    seedAmbiguousApps()
                    base.evaluate()
                }
            }

        private fun seedAmbiguousApps() {
            val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val pm = shadowOf(
                ApplicationProvider.getApplicationContext<android.content.Context>().packageManager,
            )
            // Both Chase entries deliberately share the same display name so
            // the dedup change is exercised. The Amex entries have different
            // names but identical brand and first word so the disambiguator
            // pass picks them up via country-code matching.
            val seeds = listOf(
                "Calculator" to "com.android.calculator2",
                "Browser" to "com.android.browser",
                "Chase" to "com.chase.sig.android",
                "Chase" to "com.chase.uk.consumer",
                "Amex" to "com.americanexpress.android.acctsvcs.us",
                "Amex UK" to "com.americanexpress.android.acctsvcs.uk",
                "Amex AU" to "com.americanexpress.android.acctsvcs.au",
            )
            for ((label, packageName) in seeds) {
                val resolveInfo = ResolveInfo().apply {
                    nonLocalizedLabel = label
                    activityInfo = ActivityInfo().apply {
                        this.packageName = packageName
                        name = "$packageName.LaunchActivity"
                    }
                }
                @Suppress("DEPRECATION")
                pm.addResolveInfoForIntent(launcherIntent, resolveInfo)
            }
        }
    }
}
