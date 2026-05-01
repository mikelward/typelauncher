package app.typelauncher

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MainActivityLaunchIntentTest {
    @Test
    fun appIconLaunchStartsInSettings() {
        val controller = Robolectric.buildActivity(MainActivity::class.java, appIconIntent()).setup()
        try {
            val activity = controller.get()

            assertEquals(true, activity.viewModel.uiState.value.isSettingsOpen)
        } finally {
            controller.close()
        }
    }

    @Test
    fun homeLaunchStartsOnLauncherHomeScreen() {
        val controller = Robolectric.buildActivity(MainActivity::class.java, homeIntent()).setup()
        try {
            val activity = controller.get()

            assertEquals(LauncherScreen.Home, activity.viewModel.uiState.value.screen)
            assertEquals(false, activity.viewModel.uiState.value.isSettingsOpen)
        } finally {
            controller.close()
        }
    }

    private fun appIconIntent(): Intent =
        Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(RuntimeEnvironment.getApplication().packageName)

    private fun homeIntent(): Intent =
        Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .addCategory(Intent.CATEGORY_DEFAULT)
            .setPackage(RuntimeEnvironment.getApplication().packageName)
}
