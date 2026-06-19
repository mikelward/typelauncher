package app.typelauncher

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers [dockFolderExpandDurationMs], the snap-vs-animate decision for the folder
 * open/close fly-out. The key cases are the ones a folder opened with animations
 * disabled must hit: a known scale of 0, and a not-yet-known (null) scale during
 * the startup window — both must snap (duration 0) so the expand never plays.
 */
class DockFolderExpandDurationTest {
    @Test
    fun nullScaleSnaps() {
        // Scale not yet read (e.g. a folder opened immediately at startup): snap
        // rather than animate, so animations-disabled is honored before the read.
        assertEquals(0, dockFolderExpandDurationMs(null))
    }

    @Test
    fun zeroScaleSnaps() {
        assertEquals(0, dockFolderExpandDurationMs(0f))
    }

    @Test
    fun negativeScaleSnaps() {
        assertEquals(0, dockFolderExpandDurationMs(-1f))
    }

    @Test
    fun normalScaleUsesFullDuration() {
        assertEquals(110, dockFolderExpandDurationMs(1f))
    }

    @Test
    fun fractionalScaleShortensDuration() {
        assertEquals(55, dockFolderExpandDurationMs(0.5f))
    }

    @Test
    fun largerScaleLengthensDuration() {
        assertEquals(220, dockFolderExpandDurationMs(2f))
    }
}
