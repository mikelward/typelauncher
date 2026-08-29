package app.typelauncher

import android.content.ComponentName
import android.content.Intent
import android.os.Process
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins which apps survive the background trim: the dock, plus the head of the app list
 * in the order Home renders it.
 *
 * Nothing warms the icon cache ahead of demand, so this set is the whole guarantee that
 * a return to Home paints real icons rather than placeholders -- what falls outside it
 * re-rasterizes on the way back in. The head therefore has to be taken in *rendered*
 * order. Ranking by launch count, which this did before, matches the screen only under
 * a Usage sort; under an alphabetical one it keeps the most-launched apps while
 * dropping the rows actually on screen.
 *
 * The ordering itself is [LauncherViewModelPriorityOrderTest]'s subject. These cover
 * what is done with it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PriorityAppIdsTest {

    private fun app(slug: String) = InstalledApp(
        name = slug,
        packageName = "com.example.$slug",
        launchIntent = Intent.makeMainActivity(
            ComponentName("com.example.$slug", "com.example.$slug.Main"),
        ),
        user = Process.myUserHandle(),
        isWorkApp = false,
        launchWithLauncherApps = true,
    )

    @Test
    fun theHeadOfTheRenderedOrderIsKept() {
        val order = listOf(app("aardvark"), app("middle"), app("zebra"))

        val kept = priorityAppIdsFor(emptySet(), order, headCount = 2)

        assertEquals(setOf(order[0].id, order[1].id), kept)
    }

    @Test
    fun theHeadIsTakenInRenderedOrderNotByLaunchCount() {
        // The defect this pins. Under an alphabetical sort the rendered head is the
        // start of the alphabet, which is exactly what a most-launched ranking drops --
        // so the screenful kept would be the one the user cannot see.
        val alphabetical = listOf(app("aardvark"), app("middle"), app("zebra"))

        val kept = priorityAppIdsFor(emptySet(), alphabetical, headCount = 1)

        assertEquals(setOf(app("aardvark").id), kept)
    }

    @Test
    fun everythingDockedIsKeptEvenWhenItFallsOutsideTheHead() {
        // A pin late in the alphabet sits below the head under an alphabetical sort and
        // is still drawn on every Home frame, so the dock is unioned in rather than
        // left to the list.
        val order = listOf(app("aardvark"), app("middle"), app("zebra"))
        val dockedZebra = setOf(app("zebra").id)

        val kept = priorityAppIdsFor(dockedZebra, order, headCount = 1)

        assertTrue("a docked app outside the head must survive", app("zebra").id in kept)
        assertTrue("the rendered head must survive too", app("aardvark").id in kept)
    }

    @Test
    fun dockMembersDoNotConsumeTheHead() {
        // The defect this pins (Codex, PR #692). Pins float to the front of the
        // rendered order, so counting them toward the head spends it on apps the dock
        // union already keeps -- and a dock with a full folder in it can fill the whole
        // head by itself, pushing every row the user is looking at out of the set. With
        // the dock on screen the list excludes its members anyway; with it hidden they
        // are in the list but still kept by the union. Skipping them is right in both.
        val docked = setOf(app("pinned").id)
        val order = listOf(app("pinned"), app("first"), app("second"))

        val kept = priorityAppIdsFor(docked, order, headCount = 1)

        assertTrue("the docked pin is kept by the union", app("pinned").id in kept)
        assertTrue(
            "the head must reach past the pin to the first list row",
            app("first").id in kept,
        )
    }

    @Test
    fun everythingDockedSurvivesEvenWhenTheHeadKeepsNothing() {
        // Docked apps are the most important set there is: they are drawn on every Home
        // frame and are among the first icons a cold start paints. So the dock union is
        // unconditional -- not subject to the head, to where its apps rank, or to
        // whether the dock is currently switched on. `headCount = 0` is the strongest
        // form of that: nothing qualifies on merit and the dock still survives whole.
        val docked = setOf(app("pinned").id, app("foldered").id)

        val kept = priorityAppIdsFor(docked, renderedOrder = emptyList(), headCount = 0)

        assertEquals(docked, kept)
    }

    @Test
    fun aDockedAppMissingFromTheListIsStillKept() {
        // The dock set is passed in from the stores rather than read back out of the
        // rendered order, so an app the list does not currently carry -- a dock whose
        // row is switched off, a folder member, a work pin while the list is filtered --
        // cannot fall out of the priority set through the list's back door.
        val docked = setOf(app("pinned").id)
        val orderWithoutThePin = listOf(app("first"), app("second"))

        val kept = priorityAppIdsFor(docked, orderWithoutThePin, headCount = 50)

        assertTrue("a docked app absent from the list must still be kept", app("pinned").id in kept)
    }

    @Test
    fun aListShorterThanTheHeadKeepsAllOfIt() {
        val order = listOf(app("aardvark"), app("middle"))

        val kept = priorityAppIdsFor(emptySet(), order, headCount = PRIORITY_LIST_HEAD_COUNT)

        assertEquals(order.map { it.id }.toSet(), kept)
    }
}
