package app.typelauncher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.graphics.drawable.ColorDrawable
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.ViewAnimator
import android.view.inputmethod.EditorInfo
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowToast
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MainActivityRobolectricScreenshotTest {
    @Before
    fun clearStoredLauncherData() {
        val application = org.robolectric.RuntimeEnvironment.getApplication()
        listOf("docked_apps", "app_launch_stats").forEach { preferenceName ->
            application
                .getSharedPreferences(preferenceName, android.content.Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
        }
    }

    @Test
    fun screenshot_keyboardVisible_keepsSearchAndListAboveImeInset() {
        val activity = buildActivityWithFakeLauncherApps().get()
        val root = activity.findViewById<View>(R.id.main_root)
        val search = activity.findViewById<EditText>(R.id.app_search_input)
        val installedCard = activity.findViewById<LinearLayout>(R.id.installed_apps_card)
        val list = activity.findViewById<ListView>(R.id.installed_apps_list)
        assertTrue("search hint is visible when empty", search.text.isNullOrEmpty())

        val imeBottomInsetPx = dpToPx(320)
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, 0, 0, 0))
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, imeBottomInsetPx))
            .build()
        ViewCompat.dispatchApplyWindowInsets(root, insets)
        layout(root)

        val screenshot = drawToBitmap(root)
        drawFakeKeyboardOverlay(
            screenshot = screenshot,
            imeTop = root.height - imeBottomInsetPx,
            label = "Keyboard (simulated)",
        )
        val file = screenshotOutputFile("main_activity_keyboard_visible_robolectric.png")
        file.parentFile?.mkdirs()
        file.outputStream().buffered().use { output ->
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, output)
        }

        val imeTop = root.height - imeBottomInsetPx
        assertTrue("root is measured", root.height > 0)
        assertTrue("list has launcher app rows", list.adapter != null && list.adapter.count >= 4)
        assertTrue("list rendered rows", list.childCount > 0)
        assertTrue("search remains above ime", search.bottom <= imeTop)
        assertTrue("installed apps card remains above ime", installedCard.bottom <= imeTop)
        assertTrue("installed apps card starts below search", installedCard.top >= search.bottom)
    }

    @Test
    fun screenshot_keyboardHidden_rendersHintAndTypedState() {
        val activity = buildActivityWithFakeLauncherApps().get()
        val root = activity.findViewById<View>(R.id.main_root)
        val search = activity.findViewById<EditText>(R.id.app_search_input)
        assertTrue("search hint is visible when empty", search.text.isNullOrEmpty())
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, 0, 0, 0))
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, 0))
            .build()
        ViewCompat.dispatchApplyWindowInsets(root, insets)
        layout(root)

        val hintVisibleScreenshot = drawToBitmap(root)
        val hintFile = screenshotOutputFile("main_activity_keyboard_hidden_hint_robolectric.png")
        hintFile.parentFile?.mkdirs()
        hintFile.outputStream().buffered().use { output ->
            hintVisibleScreenshot.compress(Bitmap.CompressFormat.PNG, 100, output)
        }
        val hintCrop = cropAroundSearchField(hintVisibleScreenshot, search)
        val hintCropFile = screenshotOutputFile("main_activity_keyboard_hidden_hint_search_crop_robolectric.png")
        hintCropFile.outputStream().buffered().use { output ->
            hintCrop.compress(Bitmap.CompressFormat.PNG, 100, output)
        }
        assertTrue("hint crop has some non-white pixels", hasNonWhitePixels(hintCrop))
        val hintFieldSnapshot = drawToBitmap(search)
        val hintFieldFile = screenshotOutputFile("main_activity_keyboard_hidden_hint_search_field_robolectric.png")
        hintFieldFile.outputStream().buffered().use { output ->
            hintFieldSnapshot.compress(Bitmap.CompressFormat.PNG, 100, output)
        }

        search.setText("settings")
        layout(root)
        val typedScreenshot = drawToBitmap(root)
        val typedFile = screenshotOutputFile("main_activity_keyboard_hidden_typed_robolectric.png")
        typedFile.outputStream().buffered().use { output ->
            typedScreenshot.compress(Bitmap.CompressFormat.PNG, 100, output)
        }
        val typedCrop = cropAroundSearchField(typedScreenshot, search)
        val typedCropFile = screenshotOutputFile("main_activity_keyboard_hidden_typed_search_crop_robolectric.png")
        typedCropFile.outputStream().buffered().use { output ->
            typedCrop.compress(Bitmap.CompressFormat.PNG, 100, output)
        }
        assertTrue("typed crop has some non-white pixels", hasNonWhitePixels(typedCrop))
        val typedFieldSnapshot = drawToBitmap(search)
        val typedFieldFile = screenshotOutputFile("main_activity_keyboard_hidden_typed_search_field_robolectric.png")
        typedFieldFile.outputStream().buffered().use { output ->
            typedFieldSnapshot.compress(Bitmap.CompressFormat.PNG, 100, output)
        }
        assertTrue("hint and typed crop differ", bitmapsDiffer(hintCrop, typedCrop))
        assertTrue("hint and typed field snapshots differ", bitmapsDiffer(hintFieldSnapshot, typedFieldSnapshot))

        assertEquals(root.width, hintVisibleScreenshot.width)
        assertEquals(root.height, hintVisibleScreenshot.height)
        assertEquals(root.width, typedScreenshot.width)
        assertEquals(root.height, typedScreenshot.height)
    }

    @Test
    fun screenshot_minusOneAgenda_withoutPermission_showsPermissionCard() {
        val activity = buildActivityWithFakeLauncherApps().get()
        val agendaRoot = activity.findViewById<View>(R.id.agenda_root)
        val permissionCard = activity.findViewById<View>(R.id.agenda_permission_card)
        val eventsCard = activity.findViewById<View>(R.id.agenda_events_card)
        val emptyState = activity.findViewById<View>(R.id.agenda_empty_state)

        layout(agendaRoot)

        val screenshot = drawToBitmap(agendaRoot)
        val file = screenshotOutputFile("main_activity_minus_one_agenda_permission_robolectric.png")
        file.parentFile?.mkdirs()
        file.outputStream().buffered().use { output ->
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, output)
        }

        assertTrue("permission card is visible", permissionCard.isVisible)
        assertFalse("events card is hidden", eventsCard.isVisible)
        assertFalse("empty state is hidden", emptyState.isVisible)
    }

    @Test
    fun swipingLeftAcrossInstalledAppsList_navigatesToAgendaPage() {
        val activity = buildActivityWithFakeLauncherApps().get()
        val switcher = activity.findViewById<LauncherScreenSwitcher>(R.id.launcher_screen_switcher)
        val installedList = activity.findViewById<ListView>(R.id.installed_apps_list)
        val agendaRoot = activity.findViewById<View>(R.id.agenda_root)
        val root = activity.findViewById<View>(R.id.main_root)
        val switcherView = activity.findViewById<View>(R.id.launcher_screen_switcher)

        layout(root)
        layout(switcherView)
        dispatchSwipe(
            target = installedList,
            startX = 900f,
            endX = 120f,
            y = 900f,
        )
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        assertEquals("agenda page selected after left swipe", 0, switcher.displayedChild)
        assertTrue("agenda root is displayed", agendaRoot.isShown)
    }

    @Test
    fun swipingRightAcrossAgendaEventsList_navigatesBackHomePage() {
        val activity = buildActivityWithFakeLauncherApps().get()
        val switcher = activity.findViewById<LauncherScreenSwitcher>(R.id.launcher_screen_switcher)
        val agendaEventsList = activity.findViewById<ListView>(R.id.agenda_events_list)
        val homeRoot = activity.findViewById<View>(R.id.main_root)
        val agendaRoot = activity.findViewById<View>(R.id.agenda_root)
        val switcherView = activity.findViewById<View>(R.id.launcher_screen_switcher)

        switcher.displayedChild = 0
        layout(agendaRoot)
        layout(switcherView)
        dispatchSwipe(
            target = agendaEventsList,
            startX = 120f,
            endX = 920f,
            y = 900f,
        )
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        assertEquals("home page selected after right swipe", 1, switcher.displayedChild)
        assertTrue("home root is displayed", homeRoot.isShown)
    }

    @Test
    fun typingInSearch_filtersInstalledAppsByNameSubstring() {
        val activity = buildActivityWithFakeLauncherApps().get()
        val search = activity.findViewById<EditText>(R.id.app_search_input)
        val clearButton = activity.findViewById<ImageButton>(R.id.app_search_clear_button)
        val list = activity.findViewById<ListView>(R.id.installed_apps_list)
        val dockedAppsList = activity.findViewById<LinearLayout>(R.id.docked_apps_list)

        assertEquals(ALL_FAKE_APP_NAMES, list.appNames())
        assertEquals(emptyList<String>(), dockedAppsList.appNames())
        assertFalse(clearButton.isVisible)

        search.setText("settings")
        assertEquals(listOf("Settings"), list.appNames())
        assertEquals(emptyList<String>(), dockedAppsList.appNames())
        assertTrue(clearButton.isVisible)

        search.setText("ca")
        assertEquals(listOf("Calculator", "Calendar", "Camera", "Work Calendar"), list.appNames())

        search.setText("er")
        assertEquals(listOf("Browser", "Camera", "Type Launcher"), list.appNames())

        search.setText("")
        assertEquals(ALL_FAKE_APP_NAMES, list.appNames())
        assertFalse(clearButton.isVisible)
    }

    @Test
    fun clearButton_clearsSearchFieldAndRestoresUnfilteredResults() {
        val activity = buildActivityWithFakeLauncherApps().get()
        val search = activity.findViewById<EditText>(R.id.app_search_input)
        val clearButton = activity.findViewById<ImageButton>(R.id.app_search_clear_button)
        val list = activity.findViewById<ListView>(R.id.installed_apps_list)

        search.setText("cal")
        assertEquals(listOf("Calculator", "Calendar", "Work Calendar"), list.appNames())
        assertTrue(clearButton.isVisible)

        clearButton.performClick()

        assertTrue("search query is cleared", search.text.isNullOrEmpty())
        assertFalse(clearButton.isVisible)
        assertEquals(ALL_FAKE_APP_NAMES, list.appNames())
    }

    @Test
    fun workAppBadge_isShownAtSameRightInsetAsSearchClearButton() {
        val activity = buildActivityWithFakeLauncherApps().get()
        val root = activity.findViewById<View>(R.id.main_root)
        val search = activity.findViewById<EditText>(R.id.app_search_input)
        val clearButton = activity.findViewById<ImageButton>(R.id.app_search_clear_button)
        val list = activity.findViewById<ListView>(R.id.installed_apps_list)

        search.setText("work")
        layout(root)

        assertEquals(listOf("Work Calendar"), list.appNames())
        assertTrue("work search row is rendered", list.childCount > 0)
        val workRow = list.getChildAt(0)
        val workBadge = workRow.findViewById<ImageView>(R.id.work_app_badge)
        assertEquals("Work Calendar", workRow.findViewById<TextView>(android.R.id.text1).text.toString())
        assertTrue("work apps show badge", workBadge.isVisible)
        assertEquals("work badge matches clear button width", clearButton.width, workBadge.width)
        assertEquals("work badge matches clear button right inset", clearButton.right, workBadge.right)
    }

    @Test
    fun firstFilteredApp_isHighlightedAndLaunchedBySearchAction() {
        val activity = buildActivityWithFakeLauncherApps().get()
        val root = activity.findViewById<View>(R.id.main_root)
        val search = activity.findViewById<EditText>(R.id.app_search_input)
        val list = activity.findViewById<ListView>(R.id.installed_apps_list)

        search.setText("ca")
        layout(root)

        val activeRow = list.getChildAt(0)
        val inactiveRow = list.getChildAt(1)
        assertEquals("Calculator", activeRow.findViewById<TextView>(android.R.id.text1).text.toString())
        assertEquals(activity.getColor(R.color.active_app_background), activeRow.backgroundColor())
        assertEquals(activity.getColor(android.R.color.white), inactiveRow.backgroundColor())

        search.onEditorAction(EditorInfo.IME_ACTION_SEARCH)

        val startedIntent = shadowOf(activity).nextStartedActivity
        assertEquals("app.typelauncher.fake1", startedIntent.component?.packageName)
        assertEquals("app.typelauncher.fake1.LaunchActivity", startedIntent.component?.className)
        assertStandardLauncherFlags(startedIntent)
    }

    @Test
    fun tappingInstalledApp_launchesAndClearsSearchQuery() {
        val activity = buildActivityWithFakeLauncherApps().get()
        val search = activity.findViewById<EditText>(R.id.app_search_input)
        val clearButton = activity.findViewById<ImageButton>(R.id.app_search_clear_button)
        val list = activity.findViewById<ListView>(R.id.installed_apps_list)

        search.setText("ca")
        assertEquals(listOf("Calculator", "Calendar", "Camera", "Work Calendar"), list.appNames())
        assertTrue(clearButton.isVisible)

        list.onItemClickListener?.onItemClick(list, null, 0, list.adapter.getItemId(0))

        val startedIntent = shadowOf(activity).nextStartedActivity
        assertEquals("app.typelauncher.fake1", startedIntent.component?.packageName)
        assertStandardLauncherFlags(startedIntent)
        assertTrue("search query is cleared after launch", search.text.isNullOrEmpty())
        assertFalse(clearButton.isVisible)
        assertEquals(
            listOf("Calculator", "Browser", "Calendar", "Camera", "Clock", "Files", "Settings", "Type Launcher", "Work Calendar"),
            list.appNames(),
        )
    }

    @Test
    fun launchedAppsMoveAheadOfLessUsedAppsWhenSearchIsEmpty() {
        val activity = buildActivityWithFakeLauncherApps().get()
        val search = activity.findViewById<EditText>(R.id.app_search_input)
        val list = activity.findViewById<ListView>(R.id.installed_apps_list)

        search.setText("calendar")
        list.onItemClickListener?.onItemClick(list, null, 0, list.adapter.getItemId(0))
        search.setText("calculator")
        list.onItemClickListener?.onItemClick(list, null, 0, list.adapter.getItemId(0))
        search.setText("calculator")
        list.onItemClickListener?.onItemClick(list, null, 0, list.adapter.getItemId(0))

        assertTrue("search query is cleared after launch", search.text.isNullOrEmpty())
        assertEquals(
            listOf("Calculator", "Calendar", "Browser", "Camera", "Clock", "Files", "Settings", "Type Launcher", "Work Calendar"),
            list.appNames(),
        )

        search.setText("ca")

        assertEquals(
            "typed searches stay alphabetic",
            listOf("Calculator", "Calendar", "Camera", "Work Calendar"),
            list.appNames(),
        )
    }

    @Test
    fun settingsQuery_highlightsSettingsAndLaunchesAndroidSettingsBySearchAction() {
        val activity = buildActivityWithFakeLauncherApps().get()
        val root = activity.findViewById<View>(R.id.main_root)
        val search = activity.findViewById<EditText>(R.id.app_search_input)
        val list = activity.findViewById<ListView>(R.id.installed_apps_list)

        search.setText("settings")
        layout(root)

        val activeRow = list.getChildAt(0)
        assertEquals("Settings", activeRow.findViewById<TextView>(android.R.id.text1).text.toString())
        assertEquals(activity.getColor(R.color.active_app_background), activeRow.backgroundColor())

        search.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))

        val startedIntent = shadowOf(activity).nextStartedActivity
        assertEquals(android.provider.Settings.ACTION_SETTINGS, startedIntent.action)
        assertStandardLauncherFlags(startedIntent)
    }

    @Test
    fun launchingApp_doesNotMutateCachedLaunchIntent() {
        val activity = buildActivityWithFakeLauncherApps().get()
        val search = activity.findViewById<EditText>(R.id.app_search_input)
        val list = activity.findViewById<ListView>(R.id.installed_apps_list)

        list.onItemClickListener?.onItemClick(list, null, 0, list.adapter.getItemId(0))
        val firstStartedIntent = shadowOf(activity).nextStartedActivity
        list.onItemClickListener?.onItemClick(list, null, 0, list.adapter.getItemId(0))
        val secondStartedIntent = shadowOf(activity).nextStartedActivity

        assertTrue("search query is cleared after first launch", search.text.isNullOrEmpty())
        assertStandardLauncherFlags(firstStartedIntent)
        assertStandardLauncherFlags(secondStartedIntent)
        assertFalse("each launch uses a fresh intent copy", firstStartedIntent === secondStartedIntent)
    }

    @Test
    fun longPressingFilteredApp_showsAppInfoAndDockMenuItems() {
        val activity = buildActivityWithFakeLauncherApps().get()
        val root = activity.findViewById<View>(R.id.main_root)
        val search = activity.findViewById<EditText>(R.id.app_search_input)
        val list = activity.findViewById<ListView>(R.id.installed_apps_list)

        search.setText("calendar")
        layout(root)

        val handled = list.onItemLongClickListener.onItemLongClick(
            list,
            list.getChildAt(0),
            0,
            list.adapter.getItemId(0),
        )

        assertTrue("long click is consumed", handled)
        val menu = activity.latestAppMenu?.menu
        assertEquals("App info", menu?.getItem(0)?.title.toString())
        assertEquals("Dock", menu?.getItem(1)?.title.toString())
    }

    @Test
    fun appMenuAppInfo_opensAndroidAppInfoForThatApp() {
        val activity = buildActivityWithFakeLauncherApps().get()
        val root = activity.findViewById<View>(R.id.main_root)
        val search = activity.findViewById<EditText>(R.id.app_search_input)
        val list = activity.findViewById<ListView>(R.id.installed_apps_list)

        search.setText("calendar")
        layout(root)
        list.longClickItem(0)

        activity.latestAppMenu?.menu?.performIdentifierAction(MENU_ITEM_APP_INFO, 0)

        val startedIntent = shadowOf(activity).nextStartedActivity
        assertEquals(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, startedIntent.action)
        assertEquals(Uri.parse("package:app.typelauncher.fake2"), startedIntent.data)
    }

    @Test
    fun dockingApp_addsItToBottomDockAndOffersUndock() {
        val activity = buildActivityWithFakeLauncherApps().get()
        val root = activity.findViewById<View>(R.id.main_root)
        val search = activity.findViewById<EditText>(R.id.app_search_input)
        val installedList = activity.findViewById<ListView>(R.id.installed_apps_list)
        val dockedAppsCard = activity.findViewById<LinearLayout>(R.id.docked_apps_card)
        val dockedAppsHint = activity.findViewById<TextView>(R.id.docked_apps_hint)
        val dockedAppsList = activity.findViewById<LinearLayout>(R.id.docked_apps_list)

        search.setText("cal")
        layout(root)
        installedList.longClickItem(0)
        activity.latestAppMenu?.menu?.performIdentifierAction(MENU_ITEM_TOGGLE_DOCK, 0)
        layout(root)

        assertTrue("dock is visible", dockedAppsCard.isVisible)
        assertFalse("dock hint is hidden", dockedAppsHint.isVisible)
        assertEquals(listOf("Calculator"), dockedAppsList.appNames())
        installedList.longClickItem(0)
        assertEquals("Undock", activity.latestAppMenu?.menu?.getItem(1)?.title.toString())
    }

    @Test
    fun undockingDockedApp_restoresDockHintWhenEmpty() {
        val activity = buildActivityWithFakeLauncherApps().get()
        val root = activity.findViewById<View>(R.id.main_root)
        val installedList = activity.findViewById<ListView>(R.id.installed_apps_list)
        val dockedAppsCard = activity.findViewById<LinearLayout>(R.id.docked_apps_card)
        val dockedAppsHint = activity.findViewById<TextView>(R.id.docked_apps_hint)
        val dockedAppsList = activity.findViewById<LinearLayout>(R.id.docked_apps_list)

        layout(root)
        installedList.longClickItem(0)
        activity.latestAppMenu?.menu?.performIdentifierAction(MENU_ITEM_TOGGLE_DOCK, 0)
        layout(root)
        dockedAppsList.getChildAt(0).performLongClick()
        activity.latestAppMenu?.menu?.performIdentifierAction(MENU_ITEM_TOGGLE_DOCK, 0)
        layout(root)

        assertTrue("dock remains visible", dockedAppsCard.isVisible)
        assertTrue("dock hint is shown", dockedAppsHint.isVisible)
        assertEquals("Long press apps to dock", dockedAppsHint.text.toString())
        assertEquals(emptyList<String>(), dockedAppsList.appNames())
    }

    @Test
    fun dockedList_isFilteredBySameSearchQuery() {
        val activity = buildActivityWithFakeLauncherApps().get()
        val root = activity.findViewById<View>(R.id.main_root)
        val search = activity.findViewById<EditText>(R.id.app_search_input)
        val installedList = activity.findViewById<ListView>(R.id.installed_apps_list)
        val dockedAppsList = activity.findViewById<LinearLayout>(R.id.docked_apps_list)

        layout(root)
        installedList.dockItems(activity, 0, 1)

        search.setText("cal")
        assertEquals(listOf("Calculator"), dockedAppsList.appNames())

        search.setText("browser")
        assertEquals(listOf("Browser"), dockedAppsList.appNames())
    }

    @Test
    fun dockingApps_allowsSixAppsWhenTheyFitPixelPortraitWidth() {
        val activity = buildActivityWithFakeLauncherApps().get()
        val root = activity.findViewById<View>(R.id.main_root)
        val installedList = activity.findViewById<ListView>(R.id.installed_apps_list)
        val dockedAppsList = activity.findViewById<LinearLayout>(R.id.docked_apps_list)

        layout(root)
        installedList.dockItems(activity, 0, 1, 2, 3, 4, 5, 6, 7)

        assertEquals(
            listOf("Browser", "Calculator", "Calendar", "Camera", "Clock", "Files"),
            dockedAppsList.appNames(),
        )
    }

    @Test
    fun dockingApps_allowsMoreThanSixAppsWhenTheyFitWiderPortraitWidth() {
        val activity = buildActivityWithFakeLauncherApps().get()
        val root = activity.findViewById<View>(R.id.main_root)
        val installedList = activity.findViewById<ListView>(R.id.installed_apps_list)
        val dockedAppsList = activity.findViewById<LinearLayout>(R.id.docked_apps_list)

        layout(root, width = 1440)
        installedList.dockItems(activity, 0, 1, 2, 3, 4, 5, 6, 7)

        assertEquals(ALL_FAKE_APP_NAMES.dropLast(1), dockedAppsList.appNames())
    }

    @Test
    fun dockingMoreAppsThanFitPortraitWidth_stopsAtFitCapacity() {
        val activity = buildActivityWithFakeLauncherApps().get()
        val root = activity.findViewById<View>(R.id.main_root)
        val installedList = activity.findViewById<ListView>(R.id.installed_apps_list)
        val dockedAppsList = activity.findViewById<LinearLayout>(R.id.docked_apps_list)

        layout(root, width = 720)
        installedList.dockItems(activity, 0, 1, 2, 3, 4, 5, 6, 7)

        assertEquals(
            listOf("Browser", "Calculator", "Calendar"),
            dockedAppsList.appNames(),
        )
    }

    @Test
    fun dockingMoreAppsThanFit_showsCurrentDockCapacityMessage() {
        val activity = buildActivityWithFakeLauncherApps().get()
        val root = activity.findViewById<View>(R.id.main_root)
        val installedList = activity.findViewById<ListView>(R.id.installed_apps_list)

        layout(root, width = 720)
        installedList.dockItems(activity, 0, 1, 2, 3)

        assertEquals("Too many apps in dock. Dock up to 3 apps.", ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun dockedList_staysAtBottomAndNoMoreThanHalfUsableHeight() {
        val activity = buildActivityWithFakeLauncherApps().get()
        val root = activity.findViewById<View>(R.id.main_root)
        val installedList = activity.findViewById<ListView>(R.id.installed_apps_list)
        val dockedAppsCard = activity.findViewById<LinearLayout>(R.id.docked_apps_card)
        val dockedAppsList = activity.findViewById<LinearLayout>(R.id.docked_apps_list)
        val imeBottomInsetPx = dpToPx(320)
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, dpToPx(24), 0, dpToPx(48)))
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, imeBottomInsetPx))
            .build()

        ViewCompat.dispatchApplyWindowInsets(root, insets)
        layout(root)
        installedList.dockItems(activity, 0, 1, 2, 3, 4, 5, 6, 7)
        layout(root)
        val screenshot = drawToBitmap(root)
        val file = screenshotOutputFile("main_activity_docked_icon_row_robolectric.png")
        file.parentFile?.mkdirs()
        file.outputStream().buffered().use { output ->
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, output)
        }

        val usableHeight = root.height - root.paddingTop - root.paddingBottom
        assertTrue("dock is visible", dockedAppsCard.isVisible)
        assertEquals(6, dockedAppsList.childCount)
        assertEquals(1, dockedAppsList.distinctRowTops().size)
        assertTrue("dock is at bottom of visible content", dockedAppsCard.bottom <= root.height - root.paddingBottom)
        assertTrue("dock uses no more than half of usable height", dockedAppsCard.height <= usableHeight / 2)
    }

    private fun layout(root: View, width: Int = 1080, height: Int = 2400) {
        val widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        root.measure(widthSpec, heightSpec)
        root.layout(0, 0, width, height)
    }

    private fun drawToBitmap(root: View): Bitmap {
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        root.draw(canvas)
        return bitmap
    }

    private fun drawFakeKeyboardOverlay(screenshot: Bitmap, imeTop: Int, label: String) {
        val top = imeTop.coerceIn(0, screenshot.height)
        val canvas = Canvas(screenshot)
        val keyboardPaint = Paint().apply {
            color = Color.rgb(232, 234, 237)
            style = Paint.Style.FILL
        }
        canvas.drawRect(
            0f,
            top.toFloat(),
            screenshot.width.toFloat(),
            screenshot.height.toFloat(),
            keyboardPaint,
        )
        val textPaint = Paint().apply {
            color = Color.DKGRAY
            textSize = dpToPx(22).toFloat()
            isAntiAlias = true
        }
        canvas.drawText(
            label,
            dpToPx(24).toFloat(),
            (top + dpToPx(56)).toFloat(),
            textPaint,
        )
    }

    private fun cropAroundSearchField(bitmap: Bitmap, search: View): Bitmap {
        val horizontalPadding = dpToPx(8)
        val top = (search.top - dpToPx(8)).coerceAtLeast(0)
        val bottom = (search.bottom + dpToPx(24)).coerceAtMost(bitmap.height)
        val left = (search.left - horizontalPadding).coerceAtLeast(0)
        val right = (search.right + horizontalPadding).coerceAtMost(bitmap.width)
        val width = (right - left).coerceAtLeast(1)
        val height = (bottom - top).coerceAtLeast(1)
        return Bitmap.createBitmap(bitmap, left, top, width, height)
    }

    private fun hasNonWhitePixels(bitmap: Bitmap): Boolean {
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                if (!(Color.red(pixel) > 240 && Color.green(pixel) > 240 && Color.blue(pixel) > 240)) {
                    return true
                }
                x += 4
            }
            y += 4
        }
        return false
    }

    private fun bitmapsDiffer(first: Bitmap, second: Bitmap): Boolean {
        if (first.width != second.width || first.height != second.height) {
            return true
        }
        var y = 0
        while (y < first.height) {
            var x = 0
            while (x < first.width) {
                if (first.getPixel(x, y) != second.getPixel(x, y)) {
                    return true
                }
                x += 2
            }
            y += 2
        }
        return false
    }

    private fun buildActivityWithFakeLauncherApps(): ActivityController<MainActivity> {
        seedFakeLauncherApps()
        return Robolectric.buildActivity(
            MainActivity::class.java,
            Intent().putExtra(TEST_WORK_PACKAGES_EXTRA, arrayOf("app.typelauncher.fake8")),
        ).setup()
    }

    private fun seedFakeLauncherApps() {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val packageManager = shadowOf(org.robolectric.RuntimeEnvironment.getApplication().packageManager)
        ALL_FAKE_APP_NAMES.forEachIndexed { index, label ->
            val packageName = "app.typelauncher.fake$index"
            val resolveInfo = ResolveInfo().apply {
                nonLocalizedLabel = label
                activityInfo = ActivityInfo().apply {
                    this.packageName = packageName
                    name = "$packageName.LaunchActivity"
                }
            }
            @Suppress("DEPRECATION")
            packageManager.addResolveInfoForIntent(launcherIntent, resolveInfo)
        }
    }

    private fun screenshotOutputFile(name: String): File =
        File("build/reports/robolectric-screenshots/$name")

    private fun dpToPx(dp: Int): Int =
        (dp * 420f / 160f).toInt()

    private fun ListView.appNames(): List<String> {
        @Suppress("UNCHECKED_CAST")
        val appNamesAdapter = adapter as ArrayAdapter<*>
        return (0 until appNamesAdapter.count).map { index -> appNamesAdapter.getItem(index).toString() }
    }

    private fun LinearLayout.appNames(): List<String> =
        (0 until childCount).map { index -> getChildAt(index).contentDescription.toString() }

    private fun LinearLayout.distinctRowTops(): Set<Int> =
        (0 until childCount).map { index -> getChildAt(index).top }.toSet()

    private fun ListView.longClickItem(position: Int) {
        onItemLongClickListener.onItemLongClick(
            this,
            getChildAt(position),
            position,
            adapter.getItemId(position),
        )
    }

    private fun ListView.dockItems(activity: MainActivity, vararg positions: Int) {
        positions.forEach { position ->
            longClickItem(position)
            activity.latestAppMenu?.menu?.performIdentifierAction(MENU_ITEM_TOGGLE_DOCK, 0)
        }
    }

    private fun dispatchSwipe(target: View, startX: Float, endX: Float, y: Float) {
        val switcher = target.rootView.findViewById<LauncherScreenSwitcher>(R.id.launcher_screen_switcher)
        val middleX = (startX + endX) / 2f
        val downTime = System.currentTimeMillis()
        var eventTime = downTime
        val down = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_DOWN, startX, y, 0)
        switcher.dispatchTouchEvent(down)
        down.recycle()

        eventTime += 16
        val move = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_MOVE, middleX, y, 0)
        switcher.dispatchTouchEvent(move)
        move.recycle()

        eventTime += 16
        val up = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_UP, endX, y, 0)
        switcher.dispatchTouchEvent(up)
        up.recycle()
    }

    private fun assertStandardLauncherFlags(intent: Intent) {
        val launcherFlags = Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        assertEquals(launcherFlags, intent.flags and launcherFlags)
    }

    private fun View.backgroundColor(): Int =
        (background as ColorDrawable).color

    private companion object {
        const val MENU_ITEM_APP_INFO = 1
        const val MENU_ITEM_TOGGLE_DOCK = 2
        val ALL_FAKE_APP_NAMES = listOf(
            "Browser",
            "Calculator",
            "Calendar",
            "Camera",
            "Clock",
            "Files",
            "Settings",
            "Type Launcher",
            "Work Calendar",
        )
    }
}
