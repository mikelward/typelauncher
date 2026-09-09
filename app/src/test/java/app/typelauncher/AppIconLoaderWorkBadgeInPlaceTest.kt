package app.typelauncher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Process
import android.os.UserHandle
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowApplicationPackageManager

/**
 * Regression guard for the overlay being dropped on OEMs whose
 * `getUserBadgedIcon` badges the backing bitmap *in place* and returns the
 * *same* drawable. The first cut detected "no badge" by drawable-instance
 * identity, so an in-place badge looked unchanged and was discarded — leaving
 * those devices with no work badge at all (the base bitmap is unbadged). The
 * loader now decides by pixel content, so an in-place badge is kept.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], shadows = [AppIconLoaderWorkBadgeInPlaceTest.InPlaceBadgingShadowPackageManager::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppIconLoaderWorkBadgeInPlaceTest {

    /**
     * Stands in for an OEM that mutates the passed-in bitmap and returns the
     * very same [BitmapDrawable] — the documented-but-awkward path the identity
     * check used to mishandle.
     */
    @Implements(className = "android.app.ApplicationPackageManager")
    class InPlaceBadgingShadowPackageManager : ShadowApplicationPackageManager() {
        @Implementation
        override fun getUserBadgedIcon(drawable: Drawable, user: UserHandle): Drawable {
            val bitmap = (drawable as BitmapDrawable).bitmap
            val size = bitmap.width
            val radius = size * 0.2f
            Canvas(bitmap).drawCircle(
                size - radius,
                size - radius,
                radius,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.RED },
            )
            return drawable
        }
    }

    @Test
    fun inPlaceBadgingStillProducesAnOverlay() = runBlocking {
        // `AppIconLoader`'s cache is a process-wide singleton and `evict` leaves
        // `workbadge:<user>` entries alone, so a sibling test that loaded the
        // overlay at a common size (e.g. 96 px) could leave a cached hit that
        // returns before this shadow runs. A size no other test uses guarantees
        // a cache miss, so the in-place shadow is actually exercised here.
        val sizePx = 113
        val overlay = AppIconLoader.loadWorkBadge(
            context = ApplicationProvider.getApplicationContext(),
            user = Process.myUserHandle(),
            sizePx = sizePx,
        )
        assertNotNull(
            "an in-place badge that returns the same drawable must still yield an overlay",
            overlay,
        )
        val sample = (sizePx * 0.8).toInt()
        val pixel = overlay!!.asAndroidBitmap().getPixel(sample, sample)
        assertTrue(
            "the in-place-badged briefcase must survive into the overlay, was #${Integer.toHexString(pixel)}",
            Color.alpha(pixel) > 200 && Color.red(pixel) > 200 &&
                Color.green(pixel) < 60 && Color.blue(pixel) < 60,
        )
    }
}
