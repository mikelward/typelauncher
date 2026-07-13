package app.typelauncher

import android.content.ComponentName
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
    fun applyRestoredIdMappingKeepsUnmappedWidgetsInPlace() {
        val store = WidgetStore(context)
        store.add(1)
        store.add(appWidgetId = 2, pageIndex = 0, addToNewPageAfter = true)

        // 2 wasn't in the restore mapping, but it's kept in its slot as a
        // restore placeholder rather than dropped; 1 is remapped.
        store.applyRestoredIdMapping(mapOf(1 to 51))

        assertEquals(listOf(listOf(51), listOf(2)), store.widgetPages)
    }

    @Test
    fun applyRestoredIdMappingDropsUnmappedIdCollidingWithNewId() {
        val store = WidgetStore(context)
        store.add(5)
        store.add(7)

        // old 5 becomes new 7; old 7 is unmapped and collides with that new id,
        // so the stale duplicate is dropped and the remapped widget owns 7.
        store.applyRestoredIdMapping(mapOf(5 to 7))

        assertEquals(listOf(listOf(7)), store.widgetPages)
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
    fun applyRestoredIdMappingKeepsHeightOfUnmappedWidget() {
        val store = WidgetStore(context)
        store.add(1)
        store.add(2)
        store.setCustomHeight(2, 300)

        // 2 is kept in place, so its height stays with it.
        store.applyRestoredIdMapping(mapOf(1 to 51))

        assertEquals(mapOf(2 to 300), WidgetStore(context).customHeights)
    }

    @Test
    fun applyRestoredIdMappingWithEmptyMappingIsNoOp() {
        val store = WidgetStore(context)
        store.add(1)
        store.add(2)

        store.applyRestoredIdMapping(emptyMap())

        assertEquals(listOf(listOf(1, 2)), store.widgetPages)
    }

    @Test
    fun setProviderRoundTripsAndSurfacesLabel() {
        val store = WidgetStore(context)
        store.add(1)
        val record = WidgetProviderRecord(
            component = ComponentName("com.example", "com.example.WeatherWidget"),
            profileSerial = 0L,
            label = "Weather",
        )

        store.setProvider(1, record)

        assertEquals(record, WidgetStore(context).providerRecord(1))
        assertEquals(mapOf(1 to "Weather"), WidgetStore(context).providerLabels)
    }

    @Test
    fun providerRecordSurvivesLabelsContainingTheSeparator() {
        val store = WidgetStore(context)
        store.add(1)
        val record = WidgetProviderRecord(
            component = ComponentName("com.example", "com.example.W"),
            profileSerial = 7L,
            label = "A | B",
        )

        store.setProvider(1, record)

        assertEquals(record, WidgetStore(context).providerRecord(1))
    }

    @Test
    fun removeAlsoDropsProviderRecord() {
        val store = WidgetStore(context)
        store.add(1)
        store.setProvider(1, WidgetProviderRecord(ComponentName("p", "p.W"), 0L, "X"))

        store.remove(1)

        assertEquals(null, WidgetStore(context).providerRecord(1))
    }

    @Test
    fun applyRestoredIdMappingCarriesProviderRecordToNewId() {
        val store = WidgetStore(context)
        store.add(1)
        val record = WidgetProviderRecord(ComponentName("p", "p.W"), 0L, "X")
        store.setProvider(1, record)

        store.applyRestoredIdMapping(mapOf(1 to 51))

        val reloaded = WidgetStore(context)
        assertEquals(record, reloaded.providerRecord(51))
        assertEquals(null, reloaded.providerRecord(1))
    }

    @Test
    fun replaceIdSwapsInPlaceCarryingHeightAndProvider() {
        val store = WidgetStore(context)
        store.add(10)
        store.add(appWidgetId = 20, pageIndex = 0, addToNewPageAfter = true)
        store.setCustomHeight(20, 260)
        val record = WidgetProviderRecord(ComponentName("p", "p.W"), 0L, "Weather")
        store.setProvider(20, record)

        store.replaceId(20, 99)

        val reloaded = WidgetStore(context)
        assertEquals(listOf(listOf(10), listOf(99)), reloaded.widgetPages)
        assertEquals(mapOf(99 to 260), reloaded.customHeights)
        assertEquals(record, reloaded.providerRecord(99))
        assertEquals(null, reloaded.providerRecord(20))
    }

    @Test
    fun replaceIdIsNoOpWhenNewIdAlreadyTracked() {
        val store = WidgetStore(context)
        store.add(10)
        store.add(20)

        store.replaceId(10, 20)

        assertEquals(listOf(listOf(10, 20)), store.widgetPages)
    }

    @Test
    fun replaceIdIsNoOpForUnknownOldId() {
        val store = WidgetStore(context)
        store.add(10)

        store.replaceId(99, 100)

        assertEquals(listOf(listOf(10)), store.widgetPages)
    }
}
