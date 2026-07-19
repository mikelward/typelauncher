package app.typelauncher

import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.Process
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A work profile that was just turned back on stops the platform from painting
 * its "Couldn't add widget" placeholder inside the profile's host views, but the
 * already-rendered placeholder is not refreshed until the host view re-attaches.
 * `LauncherViewModel` bumps `workProfileWidgetRefreshToken` on
 * `ACTION_MANAGED_PROFILE_AVAILABLE`; a work-profile [HostedWidgetCard] folds
 * that token into its host-view key so the view is recreated (and re-fetches
 * RemoteViews) when it bumps. A personal-profile card must ignore the token so
 * the common case never recreates a host view on a profile toggle.
 *
 * Mirrors `HostedWidgetCardReuseTest`: recreating the same call site with a new
 * token and counting how many times the host view rebinds its id.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WorkProfileWidgetRefreshTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun clearPersistedCache() {
        context.applicationContext
            .getSharedPreferences("widget_size_cache", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    private class RecordingHostView(
        context: Context,
        host: LauncherAppWidgetHost,
        private val onBound: (Int) -> Unit,
    ) : LauncherAppWidgetHostView(context, host) {
        override fun setAppWidget(appWidgetId: Int, info: AppWidgetProviderInfo?) {
            onBound(appWidgetId)
        }

        override fun updateAppWidgetSize(
            newOptions: Bundle?,
            minWidth: Int,
            minHeight: Int,
            maxWidth: Int,
            maxHeight: Int,
        ) {
        }
    }

    // AppWidgetProviderInfo.getProfile() derives the profile from the internal
    // `providerInfo` (an ActivityInfo) via `applicationInfo.uid`, not from a
    // settable field, so seed that internal ActivityInfo with a uid in the
    // requested user so getProfile() returns the matching handle.
    private fun providerInfo(uid: Int) = AppWidgetProviderInfo().apply {
        provider = ComponentName("app.typelauncher.fakewidget", "FakeProvider")
        minWidth = 200
        minHeight = 200
        targetCellWidth = 2
        targetCellHeight = 1
        val activityInfo = ActivityInfo().apply {
            applicationInfo = ApplicationInfo().apply { this.uid = uid }
        }
        AppWidgetProviderInfo::class.java
            .getDeclaredField("providerInfo")
            .apply { isAccessible = true }
            .set(this@apply, activityInfo)
    }

    @Test
    fun bumpingRefreshToken_recreatesWorkProfileHostView() {
        val host = LauncherAppWidgetHost(context, /* hostId = */ 0)
        val boundIds = mutableListOf<Int>()
        var token by mutableStateOf(0)

        composeRule.setContent {
            TypeLauncherTheme {
                HostedWidgetCard(
                    widgetId = WIDGET_ID,
                    appWidgetHost = host,
                    appWidgetManager = null,
                    customHeightDp = null,
                    canMoveUp = false,
                    canMoveDown = false,
                    onRemoveWidget = {},
                    onResizeWidget = {},
                    onMoveWidget = { _, _ -> },
                    workProfileWidgetRefreshToken = token,
                    providerInfoOverride = providerInfo(WORK_UID),
                    createWidgetView = { viewContext, _ ->
                        RecordingHostView(viewContext, host) { id -> boundIds += id }
                    },
                )
            }
        }
        composeRule.waitForIdle()

        assertEquals(listOf(WIDGET_ID), boundIds)

        // The work profile becomes available again — the token bumps. The
        // work-profile card must recreate (and rebind) its host view so the
        // stale placeholder is replaced by a fresh RemoteViews fetch.
        token = 1
        composeRule.waitForIdle()

        assertEquals(listOf(WIDGET_ID, WIDGET_ID), boundIds)
    }

    @Test
    fun bumpingRefreshToken_leavesPersonalHostViewIntact() {
        val host = LauncherAppWidgetHost(context, /* hostId = */ 0)
        val boundIds = mutableListOf<Int>()
        var token by mutableStateOf(0)

        composeRule.setContent {
            TypeLauncherTheme {
                HostedWidgetCard(
                    widgetId = WIDGET_ID,
                    appWidgetHost = host,
                    appWidgetManager = null,
                    customHeightDp = null,
                    canMoveUp = false,
                    canMoveDown = false,
                    onRemoveWidget = {},
                    onResizeWidget = {},
                    onMoveWidget = { _, _ -> },
                    workProfileWidgetRefreshToken = token,
                    providerInfoOverride = providerInfo(Process.myUid()),
                    createWidgetView = { viewContext, _ ->
                        RecordingHostView(viewContext, host) { id -> boundIds += id }
                    },
                )
            }
        }
        composeRule.waitForIdle()

        assertEquals(listOf(WIDGET_ID), boundIds)

        // A personal widget must ignore the profile-availability token, so its
        // host view is never recreated — no flash, no redundant re-inflate.
        token = 1
        composeRule.waitForIdle()

        assertEquals(listOf(WIDGET_ID), boundIds)
    }

    private companion object {
        const val WIDGET_ID = 100

        // A uid in user 10, the conventional first managed-profile user on
        // Android; UserHandle.getUserHandleForUid(uid) derives the user as
        // `uid / 100000`, so getProfile() resolves to the work-profile handle.
        const val WORK_UID = 1_010_000
    }
}
