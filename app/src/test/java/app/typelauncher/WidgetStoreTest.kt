package app.typelauncher

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WidgetStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun clearPrefs() {
        context.getSharedPreferences("widgets", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun addCanInsertNewPageAfterTargetPage() {
        val store = WidgetStore(context)
        store.add(1)
        store.add(2)

        store.add(appWidgetId = 3, pageIndex = 0, addToNewPageAfter = true)

        assertEquals(listOf(listOf(1, 2), listOf(3)), store.widgetPages)
        assertEquals(listOf(1, 2, 3), store.widgetIds)
    }

    @Test
    fun addWithoutNewPageAppendsToTargetPage() {
        val store = WidgetStore(context)
        store.add(1)

        store.add(appWidgetId = 2, pageIndex = 0)

        assertEquals(listOf(listOf(1, 2)), store.widgetPages)
    }

    @Test
    fun pagesPersistAcrossStoreReload() {
        WidgetStore(context).apply {
            add(1)
            add(appWidgetId = 2, pageIndex = 0, addToNewPageAfter = true)
        }

        val reloaded = WidgetStore(context)

        assertEquals(listOf(listOf(1), listOf(2)), reloaded.widgetPages)
    }

    @Test
    fun legacyFlatIdsLoadAsSinglePage() {
        context.getSharedPreferences("widgets", Context.MODE_PRIVATE)
            .edit()
            .putString("app_widget_ids", "7\n8")
            .commit()

        val store = WidgetStore(context)

        assertEquals(listOf(listOf(7, 8)), store.widgetPages)
    }

    @Test
    fun removingLastWidgetFromPageDropsEmptyPage() {
        val store = WidgetStore(context)
        store.add(1)
        store.add(appWidgetId = 2, pageIndex = 0, addToNewPageAfter = true)

        store.remove(2)

        assertEquals(listOf(listOf(1)), store.widgetPages)
    }

    @Test
    fun moveDownSwapsWidgetWithNextOnSamePage() {
        val store = WidgetStore(context)
        store.add(1)
        store.add(2)
        store.add(3)

        store.move(1, WidgetMoveDirection.DOWN)

        assertEquals(listOf(listOf(2, 1, 3)), store.widgetPages)
    }

    @Test
    fun moveUpSwapsWidgetWithPreviousOnSamePage() {
        val store = WidgetStore(context)
        store.add(1)
        store.add(2)
        store.add(3)

        store.move(3, WidgetMoveDirection.UP)

        assertEquals(listOf(listOf(1, 3, 2)), store.widgetPages)
    }

    @Test
    fun moveUpAtTopOfPageIsNoOp() {
        val store = WidgetStore(context)
        store.add(1)
        store.add(2)

        store.move(1, WidgetMoveDirection.UP)

        assertEquals(listOf(listOf(1, 2)), store.widgetPages)
    }

    @Test
    fun moveDownAtBottomOfPageIsNoOp() {
        val store = WidgetStore(context)
        store.add(1)
        store.add(2)

        store.move(2, WidgetMoveDirection.DOWN)

        assertEquals(listOf(listOf(1, 2)), store.widgetPages)
    }

    @Test
    fun moveDoesNotCrossPageBoundaries() {
        val store = WidgetStore(context)
        store.add(1)
        store.add(appWidgetId = 2, pageIndex = 0, addToNewPageAfter = true)

        // 1 is the only (and last) widget on page 0; moving it down must not
        // pull it onto page 1.
        store.move(1, WidgetMoveDirection.DOWN)

        assertEquals(listOf(listOf(1), listOf(2)), store.widgetPages)
    }

    @Test
    fun moveOnlyReordersWithinItsOwnPage() {
        val store = WidgetStore(context)
        store.add(1)
        store.add(2)
        store.add(appWidgetId = 3, pageIndex = 0, addToNewPageAfter = true)
        store.add(appWidgetId = 4, pageIndex = 1)

        store.move(4, WidgetMoveDirection.UP)

        assertEquals(listOf(listOf(1, 2), listOf(4, 3)), store.widgetPages)
    }

    @Test
    fun moveUnknownWidgetIsNoOp() {
        val store = WidgetStore(context)
        store.add(1)
        store.add(2)

        store.move(99, WidgetMoveDirection.UP)

        assertEquals(listOf(listOf(1, 2)), store.widgetPages)
    }

    @Test
    fun movePersistsAcrossStoreReload() {
        WidgetStore(context).apply {
            add(1)
            add(2)
            move(1, WidgetMoveDirection.DOWN)
        }

        val reloaded = WidgetStore(context)

        assertEquals(listOf(listOf(2, 1)), reloaded.widgetPages)
    }

    @Test
    fun applyRestoredIdMappingRewritesPagesPreservingLayout() {
        val store = WidgetStore(context)
        store.add(1)
        store.add(2)
        store.add(appWidgetId = 3, pageIndex = 0, addToNewPageAfter = true)

        store.applyRestoredIdMapping(mapOf(1 to 51, 2 to 52, 3 to 53))

        assertEquals(listOf(listOf(51, 52), listOf(53)), store.widgetPages)
        assertEquals(listOf(listOf(51, 52), listOf(53)), WidgetStore(context).widgetPages)
    }

    @Test
    fun applyRestoredIdMappingDropsUnmappedWidgetsAndEmptiedPages() {
        val store = WidgetStore(context)
        store.add(1)
        store.add(appWidgetId = 2, pageIndex = 0, addToNewPageAfter = true)

        // 2 was not restored by the platform, so its page collapses away.
        store.applyRestoredIdMapping(mapOf(1 to 51))

        assertEquals(listOf(listOf(51)), store.widgetPages)
    }

    @Test
    fun applyRestoredIdMappingMovesCustomHeightsToNewIds() {
        val store = WidgetStore(context)
        store.add(1)
        store.setCustomHeight(1, 240)

        store.applyRestoredIdMapping(mapOf(1 to 51))

        val reloaded = WidgetStore(context)
        assertEquals(mapOf(51 to 240), reloaded.customHeights)
    }

    @Test
    fun applyRestoredIdMappingPreservesHeightsWhenIdSpacesOverlap() {
        val store = WidgetStore(context)
        store.add(2)
        store.add(3)
        store.setCustomHeight(2, 220)
        store.setCustomHeight(3, 330)

        // The restored new IDs overlap the old ones: old 2 -> new 3 while old
        // 3 -> new 4. Each widget must keep its own height across the remap.
        store.applyRestoredIdMapping(mapOf(2 to 3, 3 to 4))

        val reloaded = WidgetStore(context)
        assertEquals(listOf(listOf(3, 4)), reloaded.widgetPages)
        assertEquals(mapOf(3 to 220, 4 to 330), reloaded.customHeights)
    }

    @Test
    fun applyRestoredIdMappingDropsHeightOfUnmappedWidget() {
        val store = WidgetStore(context)
        store.add(1)
        store.add(2)
        store.setCustomHeight(2, 300)

        store.applyRestoredIdMapping(mapOf(1 to 51))

        assertEquals(emptyMap<Int, Int>(), WidgetStore(context).customHeights)
    }

    @Test
    fun applyRestoredIdMappingWithEmptyMappingIsNoOp() {
        val store = WidgetStore(context)
        store.add(1)
        store.add(2)

        store.applyRestoredIdMapping(emptyMap())

        assertEquals(listOf(listOf(1, 2)), store.widgetPages)
    }
}
