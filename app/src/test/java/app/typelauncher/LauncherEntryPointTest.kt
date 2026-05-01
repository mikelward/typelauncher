package app.typelauncher

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LauncherEntryPointTest {
    @Test
    fun homeCategoryLaunchOpensHomeScreen() {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)

        assertEquals(LauncherEntryPoint.HomeScreen, intent.launcherEntryPoint())
    }

    @Test
    fun launcherCategoryLaunchOpensAppIconEntry() {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

        assertEquals(LauncherEntryPoint.AppIcon, intent.launcherEntryPoint())
    }

    @Test
    fun nonMainLaunchDoesNotChangeCurrentScreen() {
        val intent = Intent(Intent.ACTION_VIEW)

        assertEquals(LauncherEntryPoint.Other, intent.launcherEntryPoint())
    }

    @Test
    fun nullIntentDoesNotChangeCurrentScreen() {
        assertEquals(LauncherEntryPoint.Other, null.launcherEntryPoint())
    }
}
