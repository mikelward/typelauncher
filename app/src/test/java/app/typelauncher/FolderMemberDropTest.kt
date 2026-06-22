package app.typelauncher

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit coverage for [resolveFolderMemberDrop] — where a folder member lands when
 * dragged out of the open folder and released. Geometry: a one-row dock of four
 * slots, centers at x = 50 / 150 / 250 / 350, all at y = 500 inside a dock card
 * that spans y ∈ [460, 540]. Slot 1 holds the source folder, slot 2 a loose app,
 * slots 0 and 3 are empty.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FolderMemberDropTest {
    private val sourceFolderId = DOCK_FOLDER_ID_PREFIX + "src"
    private val dockBounds = Rect(left = 0f, top = 460f, right = 400f, bottom = 540f)
    private val slotCenters = mapOf(
        DockPosition(0, 0) to Offset(50f, 500f),
        DockPosition(0, 1) to Offset(150f, 500f),
        DockPosition(0, 2) to Offset(250f, 500f),
        DockPosition(0, 3) to Offset(350f, 500f),
    )
    private val occupantByPosition = mapOf(
        DockPosition(0, 1) to sourceFolderId,
        DockPosition(0, 2) to "loose-app",
    )

    private fun resolve(dropCenter: Offset, mergeRadiusPx: Float = 40f): FolderMemberDropTarget =
        resolveFolderMemberDrop(
            dropCenter = dropCenter,
            sourceFolderId = sourceFolderId,
            dockBounds = dockBounds,
            dockSlotCenters = slotCenters,
            occupantByPosition = occupantByPosition,
            mergeRadiusPx = mergeRadiusPx,
        )

    @Test
    fun releasedAboveTheDockUndocks() {
        // y=200 is up in the app list, well above the dock card.
        assertEquals(FolderMemberDropTarget.Undock, resolve(Offset(150f, 200f)))
    }

    @Test
    fun releasedOnTheSourceFolderSlotKeepsInFolder() {
        assertEquals(FolderMemberDropTarget.KeepInFolder, resolve(Offset(150f, 500f)))
    }

    @Test
    fun releasedOnAnEmptySlotDocksLooseThere() {
        assertEquals(FolderMemberDropTarget.DockSlot(0, 0), resolve(Offset(55f, 505f)))
        assertEquals(FolderMemberDropTarget.DockSlot(0, 3), resolve(Offset(345f, 495f)))
    }

    @Test
    fun settledOnAnOccupiedNeighborCenterMerges() {
        // Within 40px of the loose app's center (250, 500).
        assertEquals(FolderMemberDropTarget.MergeWith("loose-app"), resolve(Offset(260f, 510f)))
    }

    @Test
    fun nearAnOccupiedSlotButBeyondMergeRadiusDocksLoose() {
        // 250-208 = 42 > 40px radius: not a merge, so a loose drop at that slot
        // (the store's move swaps the occupant aside, like a dock drag).
        assertEquals(FolderMemberDropTarget.DockSlot(0, 2), resolve(Offset(208f, 500f)))
    }

    @Test
    fun releasedInsideTheCardButWithNoSlotsUndocks() {
        val target = resolveFolderMemberDrop(
            dropCenter = Offset(150f, 500f),
            sourceFolderId = sourceFolderId,
            dockBounds = dockBounds,
            dockSlotCenters = emptyMap(),
            occupantByPosition = emptyMap(),
            mergeRadiusPx = 40f,
        )
        assertEquals(FolderMemberDropTarget.Undock, target)
    }

    @Test
    fun nullDockBoundsUndocks() {
        val target = resolveFolderMemberDrop(
            dropCenter = Offset(150f, 500f),
            sourceFolderId = sourceFolderId,
            dockBounds = null,
            dockSlotCenters = slotCenters,
            occupantByPosition = occupantByPosition,
            mergeRadiusPx = 40f,
        )
        assertEquals(FolderMemberDropTarget.Undock, target)
    }
}
