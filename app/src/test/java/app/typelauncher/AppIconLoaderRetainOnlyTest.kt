package app.typelauncher

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers [AppIconLoader.retainOnly], the background trim that keeps the launcher's
 * bitmap footprint out of the states Play measures from February 2027.
 *
 * The cache is a process-wide object, so every id here is deliberately unique to this
 * class: a shared prefix would let one test's leftovers decide another's assertions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppIconLoaderRetainOnlyTest {
    private fun put(id: String, sizePx: Int) {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            .apply { eraseColor(Color.MAGENTA) }
            .asImageBitmap()
        AppIconLoader.put(id, sizePx, bitmap)
    }

    @Test
    fun dropsUnlistedIdsAndKeepsListedOnes() {
        val keep = "0:com.example.retain.keep/Main"
        val drop = "0:com.example.retain.drop/Main"
        put(keep, 24)
        put(drop, 24)

        AppIconLoader.retainOnly(setOf(keep))

        assertNotNull("a priority icon must survive the trim", AppIconLoader.cached(keep, 24))
        assertNull("a non-priority icon must be dropped", AppIconLoader.cached(drop, 24))
    }

    @Test
    fun keepsEverySizeOfARetainedId() {
        // A docked app also shown in the list renders at two sizes. Retention is by id
        // precisely so both survive: dropping one would leave half that app's surfaces
        // reloading for a saving not worth having.
        val id = "0:com.example.retain.twosizes/Main"
        put(id, 40)
        put(id, 56)

        AppIconLoader.retainOnly(setOf(id))

        assertNotNull("list size should survive", AppIconLoader.cached(id, 40))
        assertNotNull("dock size should survive", AppIconLoader.cached(id, 56))
    }

    @Test
    fun keepsTheSharedWorkBadgeOverlayThatNoAppIdCanName() {
        // The badge is keyed on the user, not the package, so a caller's set of app
        // ids can never name it -- the same reason `evict` leaves it alone. Dropping
        // it would let a retained work app paint its base icon immediately and its
        // briefcase only after getUserBadgedIcon returns, rendering it as a personal
        // app in between: confidently wrong rather than visibly loading.
        val workApp = "0:com.example.retain.work/Main"
        val badge = "workbadge:1010000"
        put(workApp, 24)
        put(badge, 24)

        AppIconLoader.retainOnly(setOf(workApp))

        assertNotNull("the work app must survive", AppIconLoader.cached(workApp, 24))
        assertNotNull("its badge overlay must survive with it", AppIconLoader.cached(badge, 24))
    }

    @Test
    fun anEmptyRetainSetDropsEverything() {
        val id = "0:com.example.retain.empty/Main"
        put(id, 24)

        AppIconLoader.retainOnly(emptySet())

        assertNull(AppIconLoader.cached(id, 24))
    }

    @Test
    fun doesNotBumpCacheGenerationSoSurvivingIconsAreNotReloaded() {
        // The distinction from `evict` and `evictAll`: those re-key live compositions
        // because what they drop was stale. A trim drops nothing stale -- the surviving
        // bitmaps are exactly what the next foreground frame paints -- so bumping the
        // generation here would force every one of them through a needless reload.
        val id = "0:com.example.retain.generation/Main"
        put(id, 24)
        val before = AppIconLoader.cacheGenerationValue

        AppIconLoader.retainOnly(setOf(id))

        assertEquals(
            "retainOnly must not bump the generation",
            before,
            AppIconLoader.cacheGenerationValue,
        )
    }
}
