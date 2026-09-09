package app.typelauncher

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Process
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The app-list / dock long-press menu offers "Uninstall" for an app Android
 * will actually remove, and leaves the item out for one it won't — a system app
 * the user has never updated, where the tap could only end in the system
 * refusing. Tapping it hands the package to the system uninstaller, which is
 * what shows the confirmation; the launcher adds no dialog of its own.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
class AppMenuUninstallTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun fakeApp(name: String, isUninstallable: Boolean = true): InstalledApp =
        InstalledApp(
            name = name,
            packageName = "app.typelauncher.fake.$name",
            launchIntent = Intent(),
            user = Process.myUserHandle(),
            isWorkApp = false,
            launchWithLauncherApps = false,
            isUninstallable = isUninstallable,
        )

    private fun renderHome(apps: List<InstalledApp>) {
        composeRule.setContent {
            TypeLauncherTheme {
                TypeLauncherApp(
                    state = LauncherUiState(filteredApps = apps, isFreshAppLoadComplete = true),
                    onQueryChanged = {},
                    onClearQuery = {},
                    onLaunchActiveApp = {},
                    onLaunchApp = {},
                    onOpenAppInfo = {},
                    onToggleDock = { _, _ -> },
                    onResetRank = {},
                    onRenameApp = { _, _ -> },
                    onHideApp = {},
                    onUninstallApp = {},
                    onUnhideApp = {},
                    onOpenSettings = {},
                    onCloseSettings = {},
                    onRequestDefaultLauncher = {},
                    onDockEnabledChanged = {},
                    onAppListLayoutChanged = {},
                    onDockVisibleIconCountChanged = {},
                    onAppListSortOrderChanged = {},
                    onShowAgenda = {},
                    onShowWidgets = {},
                    onShowHome = {},
                    appWidgetHost = null,
                    appWidgetManager = null,
                    onAddWidget = {},
                    onDismissWidgetPicker = {},
                    onSelectWidget = {},
                    onRemoveWidget = {},
                    onRequestCalendarPermission = {},
                    onOpenAgendaEvent = {},
                )
            }
        }
    }

    @Test
    fun appActionsMenu_offersUninstallForAnOrdinaryApp() {
        renderHome(listOf(fakeApp("Calculator")))

        composeRule.onNodeWithTag("$APP_ROW_TAG:Calculator").performTouchInput { longClick() }

        composeRule.onNodeWithTag("$UNINSTALL_APP_ACTION_TAG:Calculator").assertExists()
    }

    @Test
    fun appActionsMenu_omitsUninstallForAnAppAndroidWillNotRemove() {
        renderHome(listOf(fakeApp("Phone", isUninstallable = false)))

        composeRule.onNodeWithTag("$APP_ROW_TAG:Phone").performTouchInput { longClick() }

        // The menu is open — the rest of it is unchanged — and only Uninstall
        // is missing.
        composeRule.onNodeWithTag("$APP_INFO_ACTION_TAG:Phone").assertExists()
        composeRule.onNodeWithTag("$UNINSTALL_APP_ACTION_TAG:Phone").assertDoesNotExist()
    }

    @Test
    fun uninstallIntent_namesThePackageAndTheProfile() {
        val app = fakeApp("Calculator")

        val intent = app.uninstallIntent

        assertEquals(Intent.ACTION_DELETE, intent.action)
        assertEquals(Uri.parse("package:app.typelauncher.fake.Calculator"), intent.data)
        // Without EXTRA_USER the package resolves against the personal profile,
        // so a work-profile app would uninstall the wrong copy or none at all.
        assertEquals(Process.myUserHandle(), intent.getParcelableExtra(Intent.EXTRA_USER, android.os.UserHandle::class.java))
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TASK != 0)
    }

    @Test
    fun applicationInfoFlags_decideWhetherAnAppCanBeUninstalled() {
        assertTrue(ApplicationInfo().apply { flags = 0 }.isUninstallable)
        assertFalse(ApplicationInfo().apply { flags = ApplicationInfo.FLAG_SYSTEM }.isUninstallable)
        // An updated system app *can* be uninstalled: it reverts to the factory
        // version, which is a real outcome the user may want.
        assertTrue(
            ApplicationInfo()
                .apply { flags = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP }
                .isUninstallable,
        )
    }
}
