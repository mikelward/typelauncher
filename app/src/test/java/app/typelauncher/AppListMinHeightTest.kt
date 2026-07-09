package app.typelauncher

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers the apps-list height floor the home layout reserves against the dock
 * and the recents bar. Icon-only rows scale with the dock icon-size slider but
 * text rows are a fixed 56dp (40dp icon + 2×8dp padding — see `AppRow`), so
 * the per-row floor must be the larger of the two: before the `maxOf`, small
 * icon sizes under-reserved (32dp icons → 96dp for two rows) and the dock plus
 * an open recents bar could squeeze the text-mode list below its guaranteed
 * two visible rows.
 */
class AppListMinHeightTest {
    @Test
    fun smallIconSizesStillReserveTwoTextRows() {
        // 32dp icons: icon rows need 2 × (32 + 16) = 96dp, but two 56dp text
        // rows need 112dp — the floor must take the larger.
        assertEquals(112, appListMinVisibleRowsHeightDp(dockIconSizeDp = 32))
    }

    @Test
    fun largeIconSizesReserveTheIconRowHeight() {
        // 64dp icons: icon rows (2 × 80 = 160dp) exceed text rows (112dp).
        assertEquals(160, appListMinVisibleRowsHeightDp(dockIconSizeDp = 64))
    }

    @Test
    fun crossoverSitsWhereIconRowsOvertakeTextRows() {
        // Row heights tie at dockIconSizeDp = 40 (40 + 16 = 56); the floor is
        // identical on both sides of the tie.
        assertEquals(112, appListMinVisibleRowsHeightDp(dockIconSizeDp = 40))
        assertEquals(114, appListMinVisibleRowsHeightDp(dockIconSizeDp = 41))
    }

    @Test
    fun nameBelowRowsReserveTheLabelStrip() {
        // A NameBelow grid tile is the icon row plus the 20dp label strip, so
        // two rows reserve 2 × (64 + 16 + 20) = 200dp — and the strip grows
        // with the font scale (2× → 2 × (80 + 40) = 240dp).
        assertEquals(
            200,
            appListMinVisibleRowsHeightDp(dockIconSizeDp = 64, appListLayout = AppListLayout.NameBelow),
        )
        assertEquals(
            240,
            appListMinVisibleRowsHeightDp(
                dockIconSizeDp = 64,
                appListLayout = AppListLayout.NameBelow,
                fontScale = 2f,
            ),
        )
    }
}
