package app.typelauncher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Simulates an icon whose rasterize stage throws: `Drawable.draw` runs
 * third-party drawable code that can raise `Resources.NotFoundException`
 * when the app updates on disk mid-load and the resolved drawable's resource
 * table has gone stale. The exception used to escape the loader's producer
 * coroutine and re-throw out of `Deferred.await()` inside every awaiting
 * composition — a process crash that, for a HOME app, relaunches straight
 * into the same load. The loader must degrade to a null bitmap
 * (missing-icon placeholder) instead, exactly as it already does when the
 * resolve stage throws (`AppIconLoaderProfileRemovalTest`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppIconLoaderNormalizeCrashTest {

    private class ThrowingDrawable : Drawable() {
        override fun draw(canvas: Canvas) {
            throw Resources.NotFoundException("resource table went stale mid-load")
        }

        override fun setAlpha(alpha: Int) = Unit

        override fun setColorFilter(colorFilter: ColorFilter?) = Unit

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        override fun getIntrinsicWidth(): Int = 48

        override fun getIntrinsicHeight(): Int = 48
    }

    @Test
    fun loadReturnsNullInsteadOfCrashingWhenDrawThrows() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val component = ComponentName(
            "app.typelauncher.normalizecrash.chat",
            "app.typelauncher.normalizecrash.chat.LaunchActivity",
        )
        shadowOf(context.packageManager).addActivityIcon(component, ThrowingDrawable())
        val app = InstalledApp(
            name = "Stale Chat",
            packageName = component.packageName,
            launchIntent = Intent.makeMainActivity(component),
            user = Process.myUserHandle(),
            isWorkApp = false,
            launchWithLauncherApps = false,
        )

        val bitmap = AppIconLoader.load(context = context, app = app, sizePx = 48)

        assertNull("a drawable that fails to draw must degrade to no icon, not crash", bitmap)
    }
}
