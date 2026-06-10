package app.typelauncher

import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Process
import android.os.UserHandle
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowApplicationPackageManager

/**
 * The work-profile briefcase used to be composited into the icon bitmap, which
 * the launcher then clipped to a circle — slicing the corner-placed badge off.
 * The badge is now loaded as a standalone overlay (`loadWorkBadge`) that the
 * launcher draws outside the circular clip, so the base icon bitmap stays
 * unbadged and the briefcase survives in full.
 *
 * These tests pin both halves: `load` returns an unbadged tile, and
 * `loadWorkBadge` returns just the badge in its corner (and null when nothing
 * is badged, e.g. a personal user).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], shadows = [AppIconLoaderWorkBadgeTest.BadgingShadowPackageManager::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppIconLoaderWorkBadgeTest {

    /**
     * Composites a recognizable badge (a solid red disc in the bottom-right
     * quadrant) onto whatever is badged, standing in for the OEM briefcase that
     * a real `getUserBadgedIcon` paints. The base art is drawn untouched so the
     * badge is the only added pixels.
     */
    @Implements(className = "android.app.ApplicationPackageManager")
    class BadgingShadowPackageManager : ShadowApplicationPackageManager() {
        @Implementation
        override fun getUserBadgedIcon(drawable: Drawable, user: UserHandle): Drawable {
            val size = drawable.intrinsicWidth.coerceAtLeast(1)
            val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            drawable.setBounds(0, 0, size, size)
            drawable.draw(canvas)
            val radius = size * 0.2f
            canvas.drawCircle(
                size - radius,
                size - radius,
                radius,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.RED },
            )
            return BitmapDrawable(RuntimeEnvironment.getApplication().resources, output)
        }
    }

    @After
    fun clearCache() {
        AppIconLoader.evict(WORK_PACKAGE, Process.myUserHandle())
    }

    @Test
    fun loadReturnsUnbadgedTileForWorkApp() = runBlocking {
        seedActivityIcon()
        val bitmap = AppIconLoader.load(context(), workApp(), SIZE_PX)
        assertNotNull("a resolvable work app must produce an icon", bitmap)
        val corner = bitmap!!.asAndroidBitmap().getPixel(BADGE_SAMPLE, BADGE_SAMPLE)
        assertTrue(
            "the base icon bitmap must stay unbadged so the circular clip can't slice the briefcase",
            Color.red(corner) < 200 || Color.green(corner) > 50 || Color.blue(corner) > 50,
        )
    }

    @Test
    fun loadWorkBadgeReturnsJustTheBadgeOverlay() = runBlocking {
        val overlay = AppIconLoader.loadWorkBadge(context(), Process.myUserHandle(), SIZE_PX)
        assertNotNull("a badged user must produce a standalone overlay", overlay)
        val android = overlay!!.asAndroidBitmap()
        // The briefcase corner is opaque red (the stand-in badge colour). Allow
        // a little slack on the channels for anti-aliased / filtered draws.
        val corner = android.getPixel(BADGE_SAMPLE, BADGE_SAMPLE)
        assertTrue(
            "badge corner should be the opaque briefcase colour, was #${Integer.toHexString(corner)}",
            Color.alpha(corner) > 200 &&
                Color.red(corner) > 200 &&
                Color.green(corner) < 60 &&
                Color.blue(corner) < 60,
        )
        // ...and the opposite corner is transparent (overlay carries no art).
        assertEquals(
            "overlay must be transparent away from the badge so it doesn't tint the icon",
            0,
            Color.alpha(android.getPixel(1, 1)),
        )
    }

    @Test
    fun loadWorkBadgeCachesPerUserAndSize() = runBlocking {
        val first = AppIconLoader.loadWorkBadge(context(), Process.myUserHandle(), SIZE_PX)
        val cached = AppIconLoader.cachedWorkBadge(Process.myUserHandle(), SIZE_PX)
        assertTrue("a loaded overlay must be served from cache on the next lookup", first === cached)
    }

    private fun context() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun seedActivityIcon() {
        val pm = shadowOf(context().packageManager)
        pm.addActivityIcon(WORK_COMPONENT, ColorDrawable(Color.rgb(0x10, 0x20, 0x30)))
    }

    private fun workApp() = InstalledApp(
        name = "Work Mail",
        packageName = WORK_PACKAGE,
        launchIntent = Intent.makeMainActivity(WORK_COMPONENT),
        user = Process.myUserHandle(),
        isWorkApp = true,
        launchWithLauncherApps = false,
    )

    private companion object {
        const val SIZE_PX = 96
        // The shadow paints the badge disc centred on (size - 0.2*size); sample
        // its centre so anti-aliased disc edges don't make the assertion flaky.
        const val BADGE_SAMPLE = (SIZE_PX * 0.8).toInt()
        const val WORK_PACKAGE = "app.typelauncher.work.mail"
        val WORK_COMPONENT = ComponentName(WORK_PACKAGE, "$WORK_PACKAGE.LaunchActivity")
    }
}
