package app.typelauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the dock's response to the system "Display size" setting. The persisted
 * "Icons per row" count chooses a fixed icon dp (anchored to the device's
 * *stable* density), and the rendered per-row count drops as Display size grows
 * so the now-larger icons keep fitting. At the default Display size (live ==
 * stable density) nothing changes, so existing users see no regression.
 */
class DockIconSizingTest {

    @Test
    fun defaultDisplaySize_isUnchangedFromTheLegacySizing() {
        // live == stable density (factor 1) across a sweep that includes wide
        // tablet/desktop windows, and every slot the slider offers: the icon
        // size matches the old width-fitted value and the rendered count equals
        // the configured count, so the dock looks identical to before. The wide
        // end guards the MAX-icon clamp case where re-packing the capped size
        // would otherwise pack an extra column past the configured count.
        for (widthDp in 320..960) {
            val range = dockSlotCountRange(widthDp)
            for (count in range) {
                val sizing = dockIconSizing(
                    liveReferenceWidthDp = widthDp,
                    liveDensityDpi = 420,
                    stableDensityDpi = 420,
                    persistedIconCount = count,
                )
                assertEquals(
                    "icon dp must match legacy sizing at default Display size (${widthDp}dp / $count)",
                    dockIconSizeForSlotCount(widthDp, count),
                    sizing.iconSizeDp,
                )
                assertEquals(
                    "rendered count must equal configured count at default Display size (${widthDp}dp / $count)",
                    count,
                    sizing.slotCount,
                )
                assertEquals(count, sizing.configuredCount)
            }
        }
    }

    @Test
    fun enlargingDisplaySize_keepsIconDpButRendersFewerPerRow() {
        // A 411dp-at-420dpi phone, with Display size enlarged so Android reports
        // 630dpi: the live width shrinks to 411 * 420/630 = 274dp, but the icon
        // dp stays anchored to the 411dp stable width — so it grows in pixels
        // with the density while fewer icons fit the narrower live row.
        val defaultSizing = dockIconSizing(
            liveReferenceWidthDp = 411,
            liveDensityDpi = 420,
            stableDensityDpi = 420,
            persistedIconCount = 4,
        )
        val enlargedSizing = dockIconSizing(
            liveReferenceWidthDp = 274,
            liveDensityDpi = 630,
            stableDensityDpi = 420,
            persistedIconCount = 4,
        )

        assertEquals(
            "icon dp is pinned to the stable width, independent of Display size",
            defaultSizing.iconSizeDp,
            enlargedSizing.iconSizeDp,
        )
        assertEquals(4, enlargedSizing.configuredCount)
        assertTrue(
            "fewer icons fit per row once Display size grows (${enlargedSizing.slotCount} should be < 4)",
            enlargedSizing.slotCount < 4,
        )
        assertTrue(enlargedSizing.slotCount >= MIN_DOCK_ICON_COUNT)

        // The whole point: the same dp at a higher density is more pixels, so
        // the icon really does get bigger on screen.
        val defaultIconPx = defaultSizing.iconSizeDp * 420 / 160
        val enlargedIconPx = enlargedSizing.iconSizeDp * 630 / 160
        assertTrue(
            "enlarged Display size must render a physically bigger icon ($enlargedIconPx > $defaultIconPx)",
            enlargedIconPx > defaultIconPx,
        )
    }

    @Test
    fun extremeDisplaySize_neverRendersFewerThanOneIcon() {
        // A tiny live row (small phone at the largest in-band Display size) can't
        // fit even one configured icon at its anchored dp, yet the rendered count
        // floors at one rather than zero.
        val sizing = dockIconSizing(
            liveReferenceWidthDp = 80,
            liveDensityDpi = 630,
            stableDensityDpi = 420,
            persistedIconCount = 6,
        )
        assertTrue(sizing.slotCount >= 1)
        assertTrue(sizing.iconSizeDp in MIN_DOCK_APP_ICON_SIZE_DP..MAX_DOCK_APP_ICON_SIZE_DP)
    }

    @Test
    fun reducedScreenResolution_doesNotShrinkDock() {
        // Regression: a Pixel 10 Pro rendering FHD+ (~405dpi) instead of its
        // native QHD panel reports `DENSITY_DEVICE_STABLE` pinned to the native
        // ~480dpi, so `densityDpi / stable` ≈ 0.84 < 1 *even at the default
        // Display size*. That must not shrink the dock — the reference falls back
        // to the live width, so the icon dp and rendered count match the
        // pre-feature, width-fitted sizing exactly.
        val liveWidthDp = 426
        assertEquals(
            "a sub-1 density ratio (reduced resolution, not a smaller Display size) must not shrink the reference",
            liveWidthDp,
            stableDockReferenceWidthDp(liveWidthDp, liveDensityDpi = 405, stableDensityDpi = 480),
        )
        val sizing = dockIconSizing(
            liveReferenceWidthDp = liveWidthDp,
            liveDensityDpi = 405,
            stableDensityDpi = 480,
            persistedIconCount = 6,
        )
        assertEquals(dockIconSizeForSlotCount(liveWidthDp, 6), sizing.iconSizeDp)
        assertEquals(6, sizing.slotCount)
        assertEquals(6, sizing.configuredCount)
    }

    @Test
    fun missingStableDensity_fallsBackToLiveWidth() {
        // A non-positive stable density (unknown) must not divide-by-zero or
        // distort the width — it falls back to the live width unchanged.
        assertEquals(411, stableDockReferenceWidthDp(411, 420, 0))
        assertEquals(411, stableDockReferenceWidthDp(411, 0, 420))
    }

    @Test
    fun implausibleScaleRatio_fallsBackToLiveWidth() {
        // A ratio outside Android's Display-size band (here 420/160 = 2.6x, the
        // value a host that doesn't model DENSITY_DEVICE_STABLE reports) is not a
        // real Display-size pair, so the width is left unscaled.
        assertEquals(411, stableDockReferenceWidthDp(411, 420, 160))
    }
}
