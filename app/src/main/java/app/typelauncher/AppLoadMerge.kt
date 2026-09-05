package app.typelauncher

import android.os.UserHandle

/**
 * One profile's read, as `LauncherViewModel.loadInstalledApps` found it.
 *
 * [apps] is *raw* — before the disambiguator, rename, badge, icon and
 * dynamic-calendar passes, which are cross-profile and therefore have to run
 * over the assembled list rather than over each profile separately. Two
 * same-named apps in different profiles only earn their badges when the pass
 * sees both, so anything that combines inventories has to combine these and
 * re-run the passes, never stitch finished lists together.
 */
internal data class ProfileInventory(
    val apps: List<InstalledApp>,
    /**
     * False when this profile's paused state could not be read, so its apps
     * carry a *guessed* `isQuietMode = false`. Per profile rather than per
     * load: the work dock's seed is gated on it, and one profile's unreadable
     * flag says nothing about another's.
     */
    val isQuietModeKnown: Boolean,
)

/**
 * Fold one attempt's reads onto what earlier attempts established.
 *
 * The whole of recovery's bookkeeping: an attempt replaces the profiles it
 * vouched for and leaves the rest alone, so what comes out is the freshest
 * answer available per profile rather than the freshest whole read. An entry
 * for a profile this attempt listed and did *not* read is kept — that is the
 * recovery — while one for a profile that has since left the listing is
 * dropped, so a removed work profile's apps do not outlive it.
 *
 * A healthy attempt needs no special case: it has an entry for every profile
 * it listed, so every survivor of the filter is overwritten and the result is
 * exactly what it read.
 *
 * @param listedProfiles every profile the attempt listed, or null when
 *   listing them was itself what failed — a real distinction, since "no
 *   profile called X exists" and "we could not ask" want opposite answers.
 */
internal fun Map<UserHandle, ProfileInventory>.mergedUnder(
    listedProfiles: Set<UserHandle>?,
    attemptInventories: Map<UserHandle, ProfileInventory>,
): Map<UserHandle, ProfileInventory> {
    // Null means the listing itself failed, so this attempt is in no position
    // to say a profile is gone; keep everything.
    val kept = if (listedProfiles == null) this else filterKeys { user -> user in listedProfiles }
    return kept + attemptInventories
}

/**
 * Whether an attempt still leaves something unknown once [merged] — what
 * every attempt so far established between them — is taken into account.
 *
 * Only the recoverable half clears: a profile this attempt failed on but an
 * earlier one read is no longer unknown. Degradation beyond the profiles (a
 * failed listing, a failed `PackageManager` fallback) belongs to the latest
 * attempt alone and no accumulation of earlier reads touches it.
 */
internal fun isDegradedAfterMerge(
    degradedBeyondProfiles: Boolean,
    unreadProfiles: Set<UserHandle>,
    merged: Map<UserHandle, ProfileInventory>,
): Boolean = degradedBeyondProfiles || unreadProfiles.any { user -> user !in merged }

/**
 * Where the `PackageManager` fallback's apps belong in a read's result.
 *
 * [inventories] is the profile map with the fallback folded in where it
 * stands for a profile read; [unattributedApps] is what could not be folded
 * in and rides alongside instead.
 */
internal data class FallbackAttribution(
    val inventories: Map<UserHandle, ProfileInventory>,
    val unattributedApps: List<InstalledApp>,
)

/**
 * Decide whether the `PackageManager` fallback's apps *are* the personal
 * profile's inventory, or merely travel beside it.
 *
 * Where the personal profile was enumerated and simply returned nothing, they
 * are that profile's inventory, and recording them as such is what keeps them
 * under the ordinary merge rules: a later attempt that enumerates the personal
 * profile replaces them like any other profile read, one that cannot read it
 * leaves them standing, and a vouched-empty read clears them.
 *
 * Held apart from the inventories instead, they needed a rule of their own for
 * "does this still apply", and one empty list cannot say whether a profile was
 * unread, read empty, or never asked — which is three states and produced
 * three mirrored bugs in review before this shape replaced it.
 *
 * The one case where they genuinely are not a profile read is when the
 * personal enumeration *failed*: the profile is unknown, and letting the
 * fallback vouch for it would make a failed enumeration look recovered. Such a
 * read is degraded, so it never publishes a merged result on its own.
 */
internal fun attributeFallbackApps(
    inventories: Map<UserHandle, ProfileInventory>,
    personalUser: UserHandle,
    fallbackApps: List<InstalledApp>,
    isPersonalQuietModeKnown: Boolean,
): FallbackAttribution {
    val personalWasEnumerated = personalUser in inventories
    if (fallbackApps.isEmpty() || !personalWasEnumerated) {
        return FallbackAttribution(
            inventories = inventories,
            unattributedApps = if (personalWasEnumerated) emptyList() else fallbackApps,
        )
    }
    return FallbackAttribution(
        inventories = inventories + (
            personalUser to ProfileInventory(
                apps = fallbackApps,
                isQuietModeKnown = isPersonalQuietModeKnown,
            )
            ),
        unattributedApps = emptyList(),
    )
}
