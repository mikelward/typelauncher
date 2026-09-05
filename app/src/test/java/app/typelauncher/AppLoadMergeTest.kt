package app.typelauncher

import android.content.ComponentName
import android.content.Intent
import android.os.Process
import android.os.UserHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The recovery merge, tested directly.
 *
 * These cases could not be reached through the view model: they need an
 * attempt whose profile inventory is *non-empty*, and Robolectric cannot
 * produce a non-empty `LauncherActivityInfo` list — the same limitation that
 * made the enumeration injectable in the first place, and why every view-model
 * test in this area gets its apps from the `PackageManager` fallback rather
 * than from a profile read. Handed inventories directly, the merge is ordinary
 * data in and data out.
 *
 * That gap was not academic. The fallback's relationship to the inventories
 * produced three mirrored bugs in review — dropped when it still applied, kept
 * when it no longer did, and kept again for a profile that had been read and
 * was genuinely empty — because one empty list was being asked to say whether
 * a profile was unread, read empty, or never asked. Each is pinned below.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppLoadMergeTest {
    @Test
    fun anAttemptReplacesTheProfilesItReadAndLeavesTheRest() {
        // The whole of recovery: a read that lost the work profile followed by
        // one that lost the personal profile has, between them, read both.
        val first = mapOf(
            personalUser to inventoryOf("com.example.personal"),
        )

        val merged = first.mergedUnder(
            listedProfiles = setOf(personalUser, workUser),
            attemptInventories = mapOf(workUser to inventoryOf("com.example.work")),
        )

        assertEquals(
            "the profile this attempt could not read keeps the earlier answer",
            listOf("com.example.personal"),
            merged.getValue(personalUser).apps.map { it.packageName },
        )
        assertEquals(
            "and the profile it did read is present",
            listOf("com.example.work"),
            merged.getValue(workUser).apps.map { it.packageName },
        )
    }

    @Test
    fun aProfileThatHasLeftTheListingIsDroppedRatherThanOutlivingItself() {
        // A work profile removed between attempts must not keep its apps on
        // screen on the strength of a read that predates the removal.
        val first = mapOf(
            personalUser to inventoryOf("com.example.personal"),
            workUser to inventoryOf("com.example.work"),
        )

        val merged = first.mergedUnder(
            listedProfiles = setOf(personalUser),
            attemptInventories = mapOf(personalUser to inventoryOf("com.example.personal")),
        )

        assertFalse("the removed profile's entry must not survive", workUser in merged)
    }

    @Test
    fun anAttemptThatCouldNotListTheProfilesKeepsEverything() {
        // Null listed profiles means the listing itself failed, so this
        // attempt is in no position to say any profile is gone.
        val first = mapOf(workUser to inventoryOf("com.example.work"))

        val merged = first.mergedUnder(listedProfiles = null, attemptInventories = emptyMap())

        assertEquals("nothing may be dropped on a failed listing", first, merged)
    }

    @Test
    fun degradationClearsOnlyForProfilesTheMergeNowCovers() {
        val merged = mapOf(personalUser to inventoryOf("com.example.personal"))

        assertFalse(
            "a profile this attempt failed on but an earlier one read is no longer unknown",
            isDegradedAfterMerge(
                degradedBeyondProfiles = false,
                unreadProfiles = setOf(personalUser),
                merged = merged,
            ),
        )
        assertTrue(
            "a profile nothing has read is still unknown",
            isDegradedAfterMerge(
                degradedBeyondProfiles = false,
                unreadProfiles = setOf(workUser),
                merged = merged,
            ),
        )
        assertTrue(
            "and degradation beyond the profiles is never cleared by a merge",
            isDegradedAfterMerge(
                degradedBeyondProfiles = true,
                unreadProfiles = emptySet(),
                merged = merged,
            ),
        )
    }

    @Test
    fun theFallbackBecomesThePersonalInventoryWhenThatProfileWasEnumerated() {
        // The regression behind the first of the three mirrored bugs. Held
        // beside the inventories, these apps were dropped the moment a later
        // attempt read any other profile — publishing, and persisting, a list
        // with every personal app missing.
        val attributed = attributeFallbackApps(
            inventories = mapOf(personalUser to ProfileInventory(emptyList(), isQuietModeKnown = true)),
            personalUser = personalUser,
            fallbackApps = listOf(appNamed("com.example.fallback")),
            isPersonalQuietModeKnown = true,
        )

        assertEquals(
            "the fallback's apps are the personal profile's inventory",
            listOf("com.example.fallback"),
            attributed.inventories.getValue(personalUser).apps.map { it.packageName },
        )
        assertTrue(
            "so nothing travels beside the inventories",
            attributed.unattributedApps.isEmpty(),
        )

        // And being an ordinary inventory, it survives an attempt that could
        // not read the personal profile — which is what the bug lost.
        val merged = attributed.inventories.mergedUnder(
            listedProfiles = setOf(personalUser, workUser),
            attemptInventories = mapOf(workUser to inventoryOf("com.example.work")),
        )
        assertEquals(
            "the recovered personal apps stand while personal stays unread",
            listOf("com.example.fallback"),
            merged.getValue(personalUser).apps.map { it.packageName },
        )
    }

    @Test
    fun aLaterPersonalReadSupersedesTheFallbackItStoodInFor() {
        // The second of the three: keyed on the fallback list's own emptiness,
        // an attempt that *did* enumerate the personal profile still kept the
        // earlier stand-in, reviving components no live read returned.
        val withFallback = attributeFallbackApps(
            inventories = mapOf(personalUser to ProfileInventory(emptyList(), isQuietModeKnown = true)),
            personalUser = personalUser,
            fallbackApps = listOf(appNamed("com.example.fallback")),
            isPersonalQuietModeKnown = true,
        ).inventories

        val merged = withFallback.mergedUnder(
            listedProfiles = setOf(personalUser),
            attemptInventories = mapOf(personalUser to inventoryOf("com.example.real")),
        )

        assertEquals(
            "the real read replaces the stand-in outright",
            listOf("com.example.real"),
            merged.getValue(personalUser).apps.map { it.packageName },
        )
    }

    @Test
    fun aPersonalReadThatIsGenuinelyEmptyClearsTheFallbackItStoodInFor() {
        // The third: a vouched-empty personal read is authoritative — the
        // profile really has no launcher activities — so the stand-in must go
        // rather than being treated as "still unread".
        val withFallback = attributeFallbackApps(
            inventories = mapOf(personalUser to ProfileInventory(emptyList(), isQuietModeKnown = true)),
            personalUser = personalUser,
            fallbackApps = listOf(appNamed("com.example.fallback")),
            isPersonalQuietModeKnown = true,
        ).inventories

        val merged = withFallback.mergedUnder(
            listedProfiles = setOf(personalUser),
            attemptInventories = mapOf(
                personalUser to ProfileInventory(emptyList(), isQuietModeKnown = true),
            ),
        )

        assertTrue(
            "an authoritative empty read leaves nothing behind",
            merged.getValue(personalUser).apps.isEmpty(),
        )
    }

    @Test
    fun theFallbackStaysOutOfTheInventoriesWhenThePersonalReadFailed() {
        // The one case where it genuinely is not a profile read. Letting it
        // vouch here would make a failed enumeration look recovered, so it
        // travels beside the inventories and the profile stays unknown.
        val attributed = attributeFallbackApps(
            inventories = emptyMap(),
            personalUser = personalUser,
            fallbackApps = listOf(appNamed("com.example.fallback")),
            isPersonalQuietModeKnown = true,
        )

        assertFalse(
            "a failed personal read must not gain an inventory",
            personalUser in attributed.inventories,
        )
        assertEquals(
            "its apps travel alongside instead",
            listOf("com.example.fallback"),
            attributed.unattributedApps.map { it.packageName },
        )
        assertTrue(
            "so the profile is still unknown to the merge",
            isDegradedAfterMerge(
                degradedBeyondProfiles = false,
                unreadProfiles = setOf(personalUser),
                merged = attributed.inventories,
            ),
        )
    }

    @Test
    fun anUnreadableQuietModeRidesOnTheProfileItWasReadFor() {
        // Per profile, not per load: one profile's guessed paused state says
        // nothing about another's, and replacing that profile is what clears
        // it.
        val guessed = mapOf(
            personalUser to ProfileInventory(emptyList(), isQuietModeKnown = true),
            workUser to ProfileInventory(emptyList(), isQuietModeKnown = false),
        )

        val merged = guessed.mergedUnder(
            listedProfiles = setOf(personalUser, workUser),
            attemptInventories = mapOf(
                workUser to ProfileInventory(emptyList(), isQuietModeKnown = true),
            ),
        )

        assertTrue(
            "a later read of that profile clears its guess",
            merged.values.all { it.isQuietModeKnown },
        )
    }

    private fun inventoryOf(vararg packageNames: String) = ProfileInventory(
        apps = packageNames.map { appNamed(it) },
        isQuietModeKnown = true,
    )

    private fun appNamed(packageName: String) = InstalledApp(
        name = packageName,
        packageName = packageName,
        launchIntent = Intent.makeMainActivity(
            ComponentName(packageName, "$packageName.LaunchActivity"),
        ),
        user = personalUser,
        isWorkApp = false,
        launchWithLauncherApps = true,
    )

    private companion object {
        val personalUser: UserHandle = Process.myUserHandle()

        // Robolectric's environment has only the personal user, so fabricate a
        // second handle the same way the profile-discovery tests do.
        val workUser: UserHandle = UserHandle::class.java
            .getDeclaredConstructor(Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .newInstance(10)
    }
}
