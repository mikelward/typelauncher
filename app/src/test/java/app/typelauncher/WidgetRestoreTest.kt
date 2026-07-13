package app.typelauncher

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WidgetRestoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun clearPrefs() {
        context.getSharedPreferences("widgets", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun mappingZipsOldIdsToNewIdsInOrder() {
        val mapping = restoredWidgetIdMapping(
            oldIds = intArrayOf(10, 11, 12),
            newIds = intArrayOf(40, 41, 42),
        )

        assertEquals(mapOf(10 to 40, 11 to 41, 12 to 42), mapping)
    }

    @Test
    fun mappingIsEmptyWhenEitherArrayIsNull() {
        assertEquals(emptyMap<Int, Int>(), restoredWidgetIdMapping(null, intArrayOf(1)))
        assertEquals(emptyMap<Int, Int>(), restoredWidgetIdMapping(intArrayOf(1), null))
    }

    @Test
    fun mappingIsEmptyWhenArrayLengthsDiffer() {
        val mapping = restoredWidgetIdMapping(
            oldIds = intArrayOf(1, 2),
            newIds = intArrayOf(9),
        )

        assertEquals(emptyMap<Int, Int>(), mapping)
    }

    @Test
    fun mappingDropsPairsTouchingInvalidId() {
        val invalid = AppWidgetManager.INVALID_APPWIDGET_ID
        val mapping = restoredWidgetIdMapping(
            oldIds = intArrayOf(5, invalid, 7),
            newIds = intArrayOf(50, 60, invalid),
        )

        assertEquals(mapOf(5 to 50), mapping)
    }

    @Test
    fun receiverRemapsPersistedWidgetPagesToNewIds() {
        WidgetStore(context).apply {
            add(10)
            add(appWidgetId = 11, pageIndex = 0, addToNewPageAfter = true)
        }

        WidgetRestoredReceiver().onReceive(context, hostRestoredIntent(APP_WIDGET_HOST_ID, intArrayOf(10, 11), intArrayOf(40, 41)))

        assertEquals(listOf(listOf(40), listOf(41)), WidgetStore(context).widgetPages)
    }

    @Test
    fun receiverKeepsWidgetsMissingFromTheRestoreMappingAsPlaceholders() {
        WidgetStore(context).apply {
            add(10)
            add(11)
            // 11 has a remembered provider, so it survives as a placeholder.
            setProvider(11, WidgetProviderRecord(ComponentName("p", "p.W"), 0L, "X"))
        }

        // Only 10 was restored by the platform; 11's binding didn't survive but
        // is kept in place so it can render as a re-bindable restore placeholder.
        WidgetRestoredReceiver().onReceive(context, hostRestoredIntent(APP_WIDGET_HOST_ID, intArrayOf(10), intArrayOf(40)))

        assertEquals(listOf(listOf(40, 11)), WidgetStore(context).widgetPages)
    }

    @Test
    fun receiverIgnoresBroadcastForAnotherHostId() {
        WidgetStore(context).add(10)

        WidgetRestoredReceiver().onReceive(
            context,
            hostRestoredIntent(hostId = APP_WIDGET_HOST_ID + 1, oldIds = intArrayOf(10), newIds = intArrayOf(40)),
        )

        assertEquals(listOf(listOf(10)), WidgetStore(context).widgetPages)
    }

    private fun hostRestoredIntent(hostId: Int, oldIds: IntArray, newIds: IntArray): Intent =
        Intent(AppWidgetManager.ACTION_APPWIDGET_HOST_RESTORED)
            .putExtra(AppWidgetManager.EXTRA_HOST_ID, hostId)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_OLD_IDS, oldIds)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, newIds)
}
