package app.typelauncher

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.Process
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `HostedWidgetCard` used to resolve its `AppWidgetProviderInfo` with a
 * synchronous Binder IPC inside a `remember` initializer — on the main thread,
 * during the exact frames a Home → Widgets carousel swipe is animating (widget
 * pages are composed mid-drag by the warm-up gate). The lookup now runs in a
 * `LaunchedEffect` off the main thread; while it is in flight the card must
 * render a size-reserving placeholder (still test-tag addressable), never the
 * "Widget unavailable" error, and the resolved info must drive the same card
 * states as the old synchronous lookup once it lands.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HostedWidgetCardAsyncResolveTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun pendingResolutionShowsPlaceholderNotUnavailable_thenUnavailableOnNullInfo() {
        val gate = CompletableDeferred<AppWidgetProviderInfo?>()
        composeRule.setContent {
            TypeLauncherTheme {
                HostedWidgetCard(
                    widgetId = WIDGET_ID,
                    appWidgetHost = null,
                    appWidgetManager = AppWidgetManager.getInstance(context),
                    customHeightDp = null,
                    canMoveUp = false,
                    canMoveDown = false,
                    onRemoveWidget = {},
                    onResizeWidget = {},
                    onMoveWidget = { _, _ -> },
                    resolveProviderInfo = { gate.await() },
                )
            }
        }
        composeRule.waitForIdle()

        // In flight: the slot is reserved and addressable, but no error text.
        composeRule.onNodeWithTag("$WIDGET_CARD_TAG:$WIDGET_ID").assertExists()
        composeRule.onNodeWithText(context.getString(R.string.widgets_unavailable)).assertDoesNotExist()

        // Resolution lands with no provider (uninstalled app): only now may
        // the unavailable card appear.
        gate.complete(null)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("$WIDGET_CARD_TAG:$WIDGET_ID").assertExists()
        composeRule.onNodeWithText(context.getString(R.string.widgets_unavailable)).assertExists()
    }

    @Test
    fun asyncResolvedProviderInfoBindsTheHostView() {
        val host = LauncherAppWidgetHost(context, /* hostId = */ 0)
        val boundIds = mutableListOf<Int>()
        composeRule.setContent {
            TypeLauncherTheme {
                HostedWidgetCard(
                    widgetId = WIDGET_ID,
                    appWidgetHost = host,
                    appWidgetManager = AppWidgetManager.getInstance(context),
                    customHeightDp = null,
                    canMoveUp = false,
                    canMoveDown = false,
                    onRemoveWidget = {},
                    onResizeWidget = {},
                    onMoveWidget = { _, _ -> },
                    resolveProviderInfo = { fakeProviderInfo() },
                    createWidgetView = { viewContext, _ ->
                        RecordingHostView(viewContext, host) { id -> boundIds += id }
                    },
                )
            }
        }
        composeRule.waitForIdle()

        assertEquals(listOf(WIDGET_ID), boundIds)
    }

    @Test
    fun sharedCacheAvoidsReResolvingWhenTheSlotRecycles() {
        // The resolved-info cache is hoisted to WidgetsScreen (plain
        // composition state, never the saved-state Bundle — the parcelables
        // are heavyweight) so a card whose LazyColumn slot was recycled
        // re-enters composition without re-running the lookup or flashing
        // the placeholder.
        val cache = mutableStateMapOf<Int, AppWidgetProviderInfo?>()
        var resolveCalls = 0
        var composed by mutableStateOf(true)
        composeRule.setContent {
            TypeLauncherTheme {
                if (composed) {
                    HostedWidgetCard(
                        widgetId = WIDGET_ID,
                        appWidgetHost = null,
                        appWidgetManager = AppWidgetManager.getInstance(context),
                        customHeightDp = null,
                        canMoveUp = false,
                        canMoveDown = false,
                        onRemoveWidget = {},
                        onResizeWidget = {},
                        onMoveWidget = { _, _ -> },
                        resolveProviderInfo = {
                            resolveCalls++
                            null
                        },
                        resolvedProviderInfos = cache,
                    )
                }
            }
        }
        composeRule.waitForIdle()
        assertEquals(1, resolveCalls)

        // Leave and re-enter composition, as a recycled LazyColumn slot does.
        composed = false
        composeRule.waitForIdle()
        composed = true
        composeRule.waitForIdle()

        assertEquals("a recycled slot must reuse the cached resolution", 1, resolveCalls)
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

    // Mirrors WorkProfileWidgetRefreshTest: getProfile() reads the internal
    // `providerInfo` ActivityInfo, so seed it with the personal uid.
    private fun fakeProviderInfo() = AppWidgetProviderInfo().apply {
        provider = ComponentName("app.typelauncher.fakewidget", "FakeProvider")
        minWidth = 200
        minHeight = 200
        targetCellWidth = 2
        targetCellHeight = 1
        val activityInfo = ActivityInfo().apply {
            applicationInfo = ApplicationInfo().apply { uid = Process.myUid() }
        }
        AppWidgetProviderInfo::class.java
            .getDeclaredField("providerInfo")
            .apply { isAccessible = true }
            .set(this@apply, activityInfo)
    }

    private companion object {
        const val WIDGET_ID = 7
    }
}
