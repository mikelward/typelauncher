package app.typelauncher

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The decode-sampling math shared by [AppIconLoader] and [ContactPhotoLoader].
 * Pure arithmetic, so it runs on the JVM with no Robolectric: the sample size
 * is what bounds a decode's allocation, and the cases that matter are a source
 * far larger than the target, one already small enough, and a lopsided one
 * whose shorter axis must not be sampled below the target.
 */
class BitmapSamplingTest {
    @Test
    fun leavesASourceAlreadyNearTheTargetUnsampled() {
        // Within 2× on both axes: halving here would decode below the target
        // and scale back up, which shows as a soft image.
        assertEquals(1, computeInSampleSize(srcWidth = 64, srcHeight = 64, targetPx = 40))
        assertEquals(1, computeInSampleSize(srcWidth = 80, srcHeight = 80, targetPx = 40))
    }

    @Test
    fun halvesUntilBothAxesAreWithinTwiceTheTarget() {
        // 512 → 64 (÷8) is the last power of two still above 40; ÷16 would be 32.
        assertEquals(8, computeInSampleSize(srcWidth = 512, srcHeight = 512, targetPx = 40))
        assertEquals(2, computeInSampleSize(srcWidth = 96, srcHeight = 96, targetPx = 40))
    }

    @Test
    fun stopsAtTheShorterAxisOnALopsidedSource() {
        // The long axis is wildly oversized, but sampling is driven by the
        // short one — otherwise a panoramic source decodes to a strip too
        // small to crop a square out of.
        assertEquals(1, computeInSampleSize(srcWidth = 4000, srcHeight = 50, targetPx = 40))
        assertEquals(1, computeInSampleSize(srcWidth = 50, srcHeight = 4000, targetPx = 40))
    }

    @Test
    fun neverSamplesBelowOneForANonPositiveTarget() {
        // inSampleSize < 1 is treated as 1 by BitmapFactory, but a zero or
        // negative target reaching the loop would spin on `> 0`.
        assertEquals(1, computeInSampleSize(srcWidth = 512, srcHeight = 512, targetPx = 0))
        assertEquals(1, computeInSampleSize(srcWidth = 512, srcHeight = 512, targetPx = -40))
    }
}
