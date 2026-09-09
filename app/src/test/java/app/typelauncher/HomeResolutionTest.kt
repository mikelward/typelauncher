package app.typelauncher

import android.app.role.RoleManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowRoleManager

/**
 * A report arrived showing the system's "which Home app" chooser appearing
 * while the launcher held `ROLE_HOME`, its process alive and its activity
 * intact — so the role alone does not say what a Home press would reach.
 * [HomeResolution] is what records the difference, and these cover the two
 * things that would make it useless: naming the wrong target, and being so
 * chatty that it is evicted like everything else.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HomeResolutionTest {

    private companion object {
        const val OWN_PACKAGE = "app.typelauncher"
    }

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun reset() {
        LauncherDebugLog.resetForTest()
    }

    private fun resolutionLines(): List<String> =
        LauncherDebugLog.snapshot().filter { it.contains("homeResolution ") }

    @Test
    fun namesTheChooserApartFromOurselvesAndAnotherLauncher() {
        // `self` versus `chooser` is the entire diagnostic: the first says a
        // Home press reaches the launcher, the second says the system would
        // stop and ask. Reading one as the other would make the log worse than
        // silent.
        val candidates = setOf(
            "app.typelauncher/app.typelauncher.MainActivity",
            "com.example.launcher/.Home",
        )

        assertEquals(
            "self",
            HomeResolution.homeTargetName(
                "app.typelauncher",
                "app.typelauncher.MainActivity",
                candidates,
                OWN_PACKAGE,
            ),
        )
        assertEquals(
            "other",
            HomeResolution.homeTargetName("com.example.launcher", ".Home", candidates, OWN_PACKAGE),
        )
        assertEquals(
            "none",
            HomeResolution.homeTargetName(null, null, candidates, OWN_PACKAGE),
        )
    }

    @Test
    fun theChooserIsRecognizedByAbsenceRatherThanByItsPackage() {
        // The resolver has no CATEGORY_HOME filter of its own — the platform
        // substitutes it when it cannot pick among the real candidates — so it
        // is the one resolution that is not in the candidate list. Matching the
        // framework package instead broke on any build shipping the resolver
        // from a Mainline or OEM package, and broke in the worst direction: it
        // named another launcher at the moment Android was in fact asking
        // (Codex on PR #689).
        val candidates = setOf("app.typelauncher/app.typelauncher.MainActivity")

        assertEquals(
            "the framework's own resolver",
            "chooser",
            HomeResolution.homeTargetName(
                "android",
                "com.android.internal.app.ResolverActivity",
                candidates,
                OWN_PACKAGE,
            ),
        )
        assertEquals(
            "a resolver shipped from a Mainline module",
            "chooser",
            HomeResolution.homeTargetName(
                "com.android.intentresolver",
                "com.android.intentresolver.ResolverActivity",
                candidates,
                OWN_PACKAGE,
            ),
        )
    }

    @Test
    fun aFailedCandidateQueryReadsAsUnknownRatherThanAsTheChooser() {
        // A null candidate list means the query failed, not that nothing
        // filters for Home. Treating the two alike would turn every failed read
        // into the most alarming answer the log has.
        assertEquals(
            "unknown",
            HomeResolution.homeTargetName("com.example.launcher", ".Home", null, OWN_PACKAGE),
        )
        // Except for `self`, which needs no candidate list to be sure of.
        assertEquals(
            "self",
            HomeResolution.homeTargetName(OWN_PACKAGE, ".MainActivity", null, OWN_PACKAGE),
        )
    }

    @Test
    fun recordsTheResolvedTargetAndTheRoleTogether() {
        val roleManager = context.getSystemService(RoleManager::class.java)
        (shadowOf(roleManager) as ShadowRoleManager).addHeldRole(RoleManager.ROLE_HOME)

        HomeResolution.record(context, moment = "processStart")

        val line = resolutionLines().single()
        assertTrue("names the occasion", line.contains("moment=processStart"))
        assertTrue("names what Home resolves to", line.contains("resolvesTo="))
        assertTrue("names the role beside it", line.contains("roleHeld=true"))
    }

    @Test
    fun everyOccasionIsRecordedEvenWhenTheReadingIsUnchanged() {
        // Both call sites mark an occasion rather than poll for a change: the
        // chooser hand-off means the user has just been asked which launcher to
        // use, and that is worth a line whatever the values read back as — even
        // when they match the reading taken at process start.
        HomeResolution.record(context, moment = "processStart")
        HomeResolution.record(context, moment = "homeChooser")

        val lines = resolutionLines()
        assertEquals(2, lines.size)
        assertTrue(lines.last().contains("moment=homeChooser"))
    }

    @Test
    fun everyRecordedLineIsPinned() {
        // A resolution change is noticed hours later, if at all — the ring
        // buffer will not still be holding it.
        HomeResolution.record(context, moment = "processStart")

        assertTrue(LauncherDebugLog.pinnedSnapshot().any { it.contains("homeResolution ") })
    }
}
