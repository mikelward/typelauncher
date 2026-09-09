package app.typelauncher

import android.graphics.Bitmap
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers the bug-report screenshot buffer cleanup. The capture allocates a
 * full-window ARGB_8888 bitmap (10-30 MB on current phones), and the original
 * code dropped it unrecycled whenever PixelCopy failed or the caller was
 * cancelled mid-capture — transient memory pressure that lingered until the
 * next GC. Every path that doesn't hand the bitmap to the caller must recycle
 * it; the one path that does must hand it over live.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BugReportScreenshotRecycleTest {
    private fun newBitmap(): Bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)

    @Test
    fun successfulCopyReturnsTheBitmapUnrecycled() = runBlocking {
        val bitmap = newBitmap()

        val result = BugReport.awaitPixelCopyInto(bitmap) { onResult -> onResult(true) }

        assertSame(bitmap, result)
        assertFalse("a successfully captured bitmap belongs to the caller", bitmap.isRecycled)
    }

    @Test
    fun failedCopyRecyclesTheBitmap() = runBlocking {
        val bitmap = newBitmap()

        val result = BugReport.awaitPixelCopyInto(bitmap) { onResult -> onResult(false) }

        assertNull(result)
        assertTrue("a failed copy must not strand the full-window buffer", bitmap.isRecycled)
    }

    @Test
    fun synchronousRequestFailureRecyclesTheBitmap() = runBlocking {
        val bitmap = newBitmap()

        val result = BugReport.awaitPixelCopyInto(bitmap) { throw IllegalStateException("boom") }

        assertNull(result)
        assertTrue("a request that never starts must not strand the buffer", bitmap.isRecycled)
    }

    @Test
    fun cancellationBeforeTheResultRecyclesTheBitmapWhenTheCopyLands() = runBlocking {
        val bitmap = newBitmap()
        var deliverResult: ((Boolean) -> Unit)? = null
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            BugReport.awaitPixelCopyInto(bitmap) { onResult -> deliverResult = onResult }
        }
        job.cancelAndJoin()

        // PixelCopy may still be writing into the buffer until its callback
        // fires, so cancellation alone must not recycle...
        assertFalse("recycle must wait for the in-flight copy to finish", bitmap.isRecycled)

        // ...but once the (now unwanted) result lands, the buffer is freed.
        deliverResult!!(true)
        assertTrue("a result delivered after cancellation must recycle", bitmap.isRecycled)
    }

    @Test
    fun cancellationBeforeAFailedResultRecyclesTheBitmap() = runBlocking {
        val bitmap = newBitmap()
        var deliverResult: ((Boolean) -> Unit)? = null
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            BugReport.awaitPixelCopyInto(bitmap) { onResult -> deliverResult = onResult }
        }
        job.cancelAndJoin()

        deliverResult!!(false)
        assertTrue(bitmap.isRecycled)
    }
}
