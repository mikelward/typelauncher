package app.typelauncher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The motion test behind "Wake up using camera": a person moving changes
 * some of the frame a lot; light drifting changes all of it a little.
 */
class KioskMotionDetectorTest {
    private val width = 320
    private val height = 240

    private fun frame(fill: Int, rowStride: Int = width, paint: (ByteArray) -> Unit = {}): ByteArray =
        ByteArray(rowStride * height) { fill.toByte() }.also(paint)

    @Test
    fun firstFrameOnlySetsTheBaseline() {
        assertFalse(KioskMotionDetector(warmupFrames = 0).onFrame(frame(100), width, height, width))
    }

    @Test
    fun anUnchangedSceneIsNotMotion() {
        val detector = KioskMotionDetector(warmupFrames = 0)
        detector.onFrame(frame(100), width, height, width)
        assertFalse(detector.onFrame(frame(100), width, height, width))
    }

    @Test
    fun aBrightPatchAppearingIsMotion() {
        val detector = KioskMotionDetector(warmupFrames = 0)
        detector.onFrame(frame(60), width, height, width)
        // Someone stepping into the left third of the frame.
        val withPerson = frame(60) { bytes ->
            for (y in 0 until height) for (x in 0 until width / 3) bytes[y * width + x] = 200.toByte()
        }
        assertTrue(detector.onFrame(withPerson, width, height, width))
    }

    @Test
    fun aSmallEvenLightChangeIsNotMotion() {
        val detector = KioskMotionDetector(warmupFrames = 0)
        detector.onFrame(frame(100), width, height, width)
        assertFalse(detector.onFrame(frame(110), width, height, width))
    }

    @Test
    fun rowPaddingIsIgnored() {
        // A row stride wider than the image: the padding bytes change, the
        // image does not.
        val stride = width + 64
        val detector = KioskMotionDetector(warmupFrames = 0)
        detector.onFrame(frame(100, stride), width, height, stride)
        val paddingOnly = frame(100, stride) { bytes ->
            for (y in 0 until height) for (x in width until stride) bytes[y * stride + x] = 255.toByte()
        }
        assertFalse(detector.onFrame(paddingOnly, width, height, stride))
    }

    @Test
    fun exposureSwingsWhileTheCameraStartsAreNotMotion() {
        // A camera that has just started brightens and darkens as its
        // auto-exposure settles; none of that may read as someone there.
        val detector = KioskMotionDetector(warmupFrames = 3)
        assertFalse(detector.onFrame(frame(20), width, height, width))
        assertFalse(detector.onFrame(frame(200), width, height, width))
        assertFalse(detector.onFrame(frame(60), width, height, width))
        // The first settled frame is the baseline; motion counts from then.
        assertFalse(detector.onFrame(frame(100), width, height, width))
        assertFalse(detector.onFrame(frame(100), width, height, width))
        val withPerson = frame(100) { bytes ->
            for (y in 0 until height) for (x in 0 until width / 3) bytes[y * width + x] = 230.toByte()
        }
        assertTrue(detector.onFrame(withPerson, width, height, width))
    }
}
