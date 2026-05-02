package app.typelauncher

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppCategoryInferrerTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val shadowPackageManager = shadowOf(context.packageManager)

    @Before
    fun resetOverridesCache() {
        resetCategoryOverridesCacheForTest()
    }

    @After
    fun tearDownOverridesCache() {
        resetCategoryOverridesCacheForTest()
    }

    @Test
    fun returnsEmptyMapForNoApps() {
        assertEquals(emptyMap<String, AppCategory>(), inferCategories(context, emptyList()))
    }

    @Test
    fun mapsAppCategoryFromManifestForKnownValues() {
        installPackage("app.game", category = ApplicationInfo.CATEGORY_GAME)
        installPackage("app.audio", category = ApplicationInfo.CATEGORY_AUDIO)
        installPackage("app.video", category = ApplicationInfo.CATEGORY_VIDEO)
        installPackage("app.image", category = ApplicationInfo.CATEGORY_IMAGE)
        installPackage("app.social", category = ApplicationInfo.CATEGORY_SOCIAL)
        installPackage("app.news", category = ApplicationInfo.CATEGORY_NEWS)
        installPackage("app.maps", category = ApplicationInfo.CATEGORY_MAPS)
        installPackage("app.productivity", category = ApplicationInfo.CATEGORY_PRODUCTIVITY)
        installPackage("app.accessibility", category = ApplicationInfo.CATEGORY_ACCESSIBILITY)

        val apps = listOf(
            installedApp("app.game"),
            installedApp("app.audio"),
            installedApp("app.video"),
            installedApp("app.image"),
            installedApp("app.social"),
            installedApp("app.news"),
            installedApp("app.maps"),
            installedApp("app.productivity"),
            installedApp("app.accessibility"),
        )

        val result = inferCategories(context, apps)

        assertEquals(AppCategory.Games, result[apps[0].id])
        assertEquals(AppCategory.Music, result[apps[1].id])
        assertEquals(AppCategory.Video, result[apps[2].id])
        assertEquals(AppCategory.Photos, result[apps[3].id])
        assertEquals(AppCategory.Social, result[apps[4].id])
        assertEquals(AppCategory.News, result[apps[5].id])
        assertEquals(AppCategory.Maps, result[apps[6].id])
        assertEquals(AppCategory.Productivity, result[apps[7].id])
        assertEquals(AppCategory.Accessibility, result[apps[8].id])
    }

    @Test
    fun fallsBackToAppIntentCategoryWhenManifestUndefined() {
        installPackage("app.browser", category = ApplicationInfo.CATEGORY_UNDEFINED)
        installPackage("app.email", category = ApplicationInfo.CATEGORY_UNDEFINED)
        installPackage("app.gallery", category = ApplicationInfo.CATEGORY_UNDEFINED)
        installPackage("app.messaging", category = ApplicationInfo.CATEGORY_UNDEFINED)
        installPackage("app.calendar", category = ApplicationInfo.CATEGORY_UNDEFINED)
        installPackage("app.contacts", category = ApplicationInfo.CATEGORY_UNDEFINED)
        installPackage("app.files", category = ApplicationInfo.CATEGORY_UNDEFINED)
        installPackage("app.fitness", category = ApplicationInfo.CATEGORY_UNDEFINED)

        registerAppIntentCategory("app.browser", Intent.CATEGORY_APP_BROWSER)
        registerAppIntentCategory("app.email", Intent.CATEGORY_APP_EMAIL)
        registerAppIntentCategory("app.gallery", Intent.CATEGORY_APP_GALLERY)
        registerAppIntentCategory("app.messaging", Intent.CATEGORY_APP_MESSAGING)
        registerAppIntentCategory("app.calendar", Intent.CATEGORY_APP_CALENDAR)
        registerAppIntentCategory("app.contacts", Intent.CATEGORY_APP_CONTACTS)
        registerAppIntentCategory("app.files", Intent.CATEGORY_APP_FILES)
        registerAppIntentCategory("app.fitness", Intent.CATEGORY_APP_FITNESS)

        val apps = listOf(
            installedApp("app.browser"),
            installedApp("app.email"),
            installedApp("app.gallery"),
            installedApp("app.messaging"),
            installedApp("app.calendar"),
            installedApp("app.contacts"),
            installedApp("app.files"),
            installedApp("app.fitness"),
        )

        val result = inferCategories(context, apps)

        assertEquals(AppCategory.Browser, result[apps[0].id])
        assertEquals(AppCategory.Email, result[apps[1].id])
        assertEquals(AppCategory.Photos, result[apps[2].id])
        assertEquals(AppCategory.Messaging, result[apps[3].id])
        assertEquals(AppCategory.Calendar, result[apps[4].id])
        assertEquals(AppCategory.Contacts, result[apps[5].id])
        assertEquals(AppCategory.Files, result[apps[6].id])
        assertEquals(AppCategory.Fitness, result[apps[7].id])
    }

    @Test
    fun manifestCategoryWinsOverAppIntentCategory() {
        installPackage("app.dual", category = ApplicationInfo.CATEGORY_PRODUCTIVITY)
        registerAppIntentCategory("app.dual", Intent.CATEGORY_APP_BROWSER)

        val apps = listOf(installedApp("app.dual"))
        val result = inferCategories(context, apps)

        assertEquals(AppCategory.Productivity, result[apps[0].id])
    }

    @Test
    fun fallsBackToOtherWhenNeitherSignalMatches() {
        installPackage("app.unknown", category = ApplicationInfo.CATEGORY_UNDEFINED)

        val apps = listOf(installedApp("app.unknown"))
        val result = inferCategories(context, apps)

        assertEquals(AppCategory.Other, result[apps[0].id])
    }

    @Test
    fun missingPackageInfoStillFallsBackToOther() {
        val apps = listOf(installedApp("app.never.installed"))
        val result = inferCategories(context, apps)

        assertEquals(AppCategory.Other, result[apps[0].id])
    }

    @Test
    fun assetOverridesAssignFinanceWhenManifestAndIntentSignalsMiss() {
        // Use a package that the curated overrides file maps to Finance and
        // for which we deliberately register no manifest category and no
        // APP_* intent category, so only the third pass can match.
        installPackage("com.paypal.android.p2pmobile", category = ApplicationInfo.CATEGORY_UNDEFINED)

        val apps = listOf(installedApp("com.paypal.android.p2pmobile"))
        val result = inferCategories(context, apps)

        assertEquals(AppCategory.Finance, result[apps[0].id])
    }

    @Test
    fun assetOverridesDoNotOverrideManifestSignal() {
        // Pick a package the overrides file maps to Finance, but declare a
        // manifest category of PRODUCTIVITY. The first pass must win.
        installPackage("com.paypal.android.p2pmobile", category = ApplicationInfo.CATEGORY_PRODUCTIVITY)

        val apps = listOf(installedApp("com.paypal.android.p2pmobile"))
        val result = inferCategories(context, apps)

        assertEquals(AppCategory.Productivity, result[apps[0].id])
    }

    @Test
    fun callerSuppliedManifestCategoriesWinsOverPackageManagerLookup() {
        // Simulates the work-profile case: the package isn't installed on the
        // launcher's owner profile (PackageManager.getApplicationInfo throws
        // NameNotFoundException), but the caller has a per-profile manifest
        // category from LauncherActivityInfo.applicationInfo. The inferrer
        // must use the caller's value rather than fall through to Other.
        val apps = listOf(installedApp("com.example.workgame"))

        val result = inferCategories(
            context = context,
            apps = apps,
            manifestCategories = mapOf("com.example.workgame" to ApplicationInfo.CATEGORY_GAME),
        )

        assertEquals(AppCategory.Games, result[apps[0].id])
    }

    @Test
    fun callerSuppliedManifestCategoryOverridesPackageManagerLookup() {
        // If the caller supplies a manifest category, it takes precedence over
        // whatever the owner-profile PackageManager would return (defensive in
        // case the same package is installed on both profiles with different
        // declared categories — unusual but possible across profile policies).
        installPackage("com.example.dual", category = ApplicationInfo.CATEGORY_PRODUCTIVITY)

        val apps = listOf(installedApp("com.example.dual"))
        val result = inferCategories(
            context = context,
            apps = apps,
            manifestCategories = mapOf("com.example.dual" to ApplicationInfo.CATEGORY_GAME),
        )

        assertEquals(AppCategory.Games, result[apps[0].id])
    }

    @Test
    fun parseCategoryOverridesIgnoresCommentsAndBlankLinesAndUnknownCategories() {
        val parsed = parseCategoryOverrides(
            """
            # comment

            com.example.bank=Finance
              com.example.wallet  =  Finance
            com.example.broken=NotARealCategory
            =Finance
            com.example.empty=
            com.example.misc=Productivity
            """.trimIndent(),
        )

        assertEquals(AppCategory.Finance, parsed["com.example.bank"])
        assertEquals(AppCategory.Finance, parsed["com.example.wallet"])
        assertEquals(AppCategory.Productivity, parsed["com.example.misc"])
        assertNull(parsed["com.example.broken"])
        assertNull(parsed["com.example.empty"])
        assertEquals(3, parsed.size)
    }

    private fun installPackage(packageName: String, category: Int) {
        val packageInfo = PackageInfo().apply {
            this.packageName = packageName
            applicationInfo = ApplicationInfo().apply {
                this.packageName = packageName
                this.category = category
            }
        }
        shadowPackageManager.installPackage(packageInfo)
    }

    private fun registerAppIntentCategory(packageName: String, intentCategory: String) {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(intentCategory)
        val resolveInfo = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                this.packageName = packageName
                name = "$packageName.LaunchActivity"
            }
        }
        @Suppress("DEPRECATION")
        shadowPackageManager.addResolveInfoForIntent(intent, resolveInfo)
    }

    private fun installedApp(packageName: String): InstalledApp {
        val component = ComponentName(packageName, "$packageName.LaunchActivity")
        return InstalledApp(
            name = packageName,
            packageName = packageName,
            launchIntent = Intent.makeMainActivity(component),
            user = Process.myUserHandle(),
            isWorkApp = false,
            launchWithLauncherApps = true,
        )
    }
}
