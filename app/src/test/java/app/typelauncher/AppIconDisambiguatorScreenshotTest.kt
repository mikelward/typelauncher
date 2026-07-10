package app.typelauncher

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertTrue
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
    fun chaseVariantsUseCornerBadges() {
        // Same-named "Chase" entries survive the dedup pass. The UK variant
        // gets a flag badge and INTL gets a globe badge; "sig" is not
        // regional, so that entry is left unbadged.
        composeRule.onNodeWithTag("$APP_ROW_TAG:Chase (UK)").assertIsDisplayed()
        composeRule.onNodeWithTag("$APP_ROW_TAG:Chase (INTL)").assertIsDisplayed()
        composeRule.onNodeWithTag("$APP_ROW_TAG:Chase").assertIsDisplayed()
        composeRule.onNodeWithTag("$APP_ICON_DISAMBIGUATOR_TAG:Chase (UK)", useUnmergedTree = true)
            .assertIsDisplayed()
        composeRule.onNodeWithTag("$APP_ICON_DISAMBIGUATOR_TAG:Chase (INTL)", useUnmergedTree = true)
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Chase (UK) flag", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Chase (INTL) globe", useUnmergedTree = true).assertIsDisplayed()
        assertBadgeMatchesCornerSizeAndBottomLeft(
            iconTag = "$APP_ICON_TAG:Chase (UK)",
            badgeTag = "$APP_ICON_DISAMBIGUATOR_TAG:Chase (UK)",
        )
        assertBadgeMatchesCornerSizeAndBottomLeft(
            iconTag = "$APP_ICON_TAG:Chase (INTL)",
            badgeTag = "$APP_ICON_DISAMBIGUATOR_TAG:Chase (INTL)",
        )

        saveScreenshot("compose_disambiguator_chase_pair_robolectric.png")
    }

    @Test
    fun amexThreePackTagsEachWithCountryBadge() {
        // Three Amex variants share a brand and first word; each gets a
        // country-flag badge derived from the package tail. The plain "Amex"
        // entry (US package) gets "(US)" appended; the regional names "Amex
        // UK" / "Amex AU" already contain the country tag so the
        // parenthesised suffix is suppressed.
        composeRule.onNodeWithTag("$APP_ICON_DISAMBIGUATOR_TAG:Amex (US)", useUnmergedTree = true)
            .assertIsDisplayed()
        composeRule.onNodeWithTag("$APP_ICON_DISAMBIGUATOR_TAG:Amex UK", useUnmergedTree = true)
            .assertIsDisplayed()
        composeRule.onNodeWithTag("$APP_ICON_DISAMBIGUATOR_TAG:Amex AU", useUnmergedTree = true)
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Amex (US) flag", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Amex UK flag", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Amex AU flag", useUnmergedTree = true).assertIsDisplayed()

        saveScreenshot("compose_disambiguator_amex_three_pack_robolectric.png")
    }

    @Test
    @Config(qualifiers = "+night")
    fun amexThreePackInDarkTheme() {
        // Same three-pack as the light-mode test but with the night-mode
        // qualifier so the flag badge renders against the dark colour scheme.
        composeRule.onNodeWithTag("$APP_ICON_DISAMBIGUATOR_TAG:Amex (US)", useUnmergedTree = true)
            .assertIsDisplayed()

        saveScreenshot("compose_disambiguator_amex_three_pack_dark_robolectric.png")
    }

    private fun assertBadgeMatchesCornerSizeAndBottomLeft(iconTag: String, badgeTag: String) {
        val iconBounds = composeRule.onNodeWithTag(iconTag, useUnmergedTree = true).getBoundsInRoot()
        val badgeBounds = composeRule.onNodeWithTag(badgeTag, useUnmergedTree = true).getBoundsInRoot()
        val iconWidth = (iconBounds.right - iconBounds.left).value
        val badgeWidth = (badgeBounds.right - badgeBounds.left).value
        val badgeHeight = (badgeBounds.bottom - badgeBounds.top).value
        val expected = iconWidth * APP_ICON_CORNER_BADGE_FRACTION
        assertTrue(
            "badge should be ${APP_ICON_CORNER_BADGE_FRACTION * 100}% of the ${iconWidth}dp icon " +
                "(expected ${expected}dp), was ${badgeWidth}x$badgeHeight",
            kotlin.math.abs(badgeWidth - expected) <= 1f &&
                kotlin.math.abs(badgeHeight - expected) <= 1f,
        )
        assertTrue(
            "badge should sit on the icon's left edge (badge.left=${badgeBounds.left}, icon.left=${iconBounds.left})",
            kotlin.math.abs((badgeBounds.left - iconBounds.left).value) <= 1f,
        )
        assertTrue(
            "badge should sit on the icon's bottom edge (badge.bottom=${badgeBounds.bottom}, icon.bottom=${iconBounds.bottom})",
            kotlin.math.abs((badgeBounds.bottom - iconBounds.bottom).value) <= 1f,
        )
    }

    private fun saveScreenshot(name: String) {
        val isRecord = System.getProperty("roborazzi.test.record") == "true"
        val isVerify = System.getProperty("roborazzi.test.verify") == "true"
        if (!isRecord && !isVerify) return
        // Icon bitmaps load on background dispatchers; capture only once
        // every composed icon has settled (see awaitAppIconsResolved).
        composeRule.awaitAppIconsResolved()
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
                        "renamed_apps",
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
            // Chase entries deliberately share the same display name so the
            // dedup change is exercised. The Amex entries have different names
            // but identical brand and first word so the disambiguator pass
            // picks them up via country-code matching.
            val seeds = listOf(
                "Calculator" to "com.android.calculator2",
                "Browser" to "com.android.browser",
                "Chase" to "com.chase.sig.android",
                "Chase" to "com.chase.uk.consumer",
                "Chase" to "com.chase.intl",
                "Amex" to "com.americanexpress.android.acctsvcs.us",
                "Amex UK" to "com.americanexpress.android.acctsvcs.uk",
                "Amex AU" to "com.americanexpress.android.acctsvcs.au",
            )
            for ((index, seed) in seeds.withIndex()) {
                val (label, packageName) = seed
                val componentName = ComponentName(packageName, "$packageName.LaunchActivity")
                val resolveInfo = ResolveInfo().apply {
                    nonLocalizedLabel = label
                    activityInfo = ActivityInfo().apply {
                        this.packageName = packageName
                        name = componentName.className
                    }
                }
                @Suppress("DEPRECATION")
                pm.addResolveInfoForIntent(launcherIntent, resolveInfo)
                pm.addActivityIcon(componentName, ColorDrawable(ICON_COLORS[index % ICON_COLORS.size]))
            }
        }

        private companion object {
            val ICON_COLORS = intArrayOf(
                Color.rgb(0x1A, 0x73, 0xE8),
                Color.rgb(0xD9, 0x30, 0x25),
                Color.rgb(0x18, 0x80, 0x38),
                Color.rgb(0xF9, 0xAB, 0x00),
            )
        }
    }
}
