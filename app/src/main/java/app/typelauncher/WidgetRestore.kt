package app.typelauncher

import android.appwidget.AppWidgetManager
import android.content.pm.LauncherApps
import android.os.Build
import android.os.UserHandle
import android.os.UserManager

/**
 * Stable ID this launcher registers its [android.appwidget.AppWidgetHost]
 * under. It must never change: the platform keys a host's backed-up widget
 * bindings by host ID, and the restore broadcast
 * ([AppWidgetManager.ACTION_APPWIDGET_HOST_RESTORED]) is only honored when its
 * [AppWidgetManager.EXTRA_HOST_ID] matches the value the host was constructed
 * with. Shared between [MainActivity] (host construction, orphan sweep) and
 * [WidgetRestoredReceiver] (restore-broadcast filtering).
 */
internal const val APP_WIDGET_HOST_ID = 1024

/**
 * Builds the old-ID → new-ID mapping the platform hands us after a cloud
 * backup restore, from the parallel arrays carried by
 * [AppWidgetManager.ACTION_APPWIDGET_HOST_RESTORED]
 * ([AppWidgetManager.EXTRA_APPWIDGET_OLD_IDS] and
 * [AppWidgetManager.EXTRA_APPWIDGET_IDS]).
 *
 * Widget IDs are host-local integers the system reallocates on the new device,
 * so the launcher's persisted references (see [WidgetStore]) still name the
 * *old* device's IDs and would resolve to nothing until remapped. The two
 * extras are index-aligned — `old[i]` became `new[i]` — so the mapping is the
 * zip, minus any pair touching [AppWidgetManager.INVALID_APPWIDGET_ID].
 *
 * Returns `null` for a **malformed** broadcast — a null array or mismatched
 * lengths — so the caller ignores it entirely. A **well-formed but empty**
 * payload (both arrays present and same length, e.g. a restore where every
 * provider opted out so nothing came back) returns an empty map, which is
 * distinct: the caller still runs it through the restore reconciliation so
 * widgets that can't be re-bound are cleaned up. An invalid ID on either side
 * of an otherwise valid pair just drops that pair. A [LinkedHashMap] preserves
 * order purely for deterministic logging and tests.
 */
internal fun restoredWidgetIdMapping(oldIds: IntArray?, newIds: IntArray?): Map<Int, Int>? {
    if (oldIds == null || newIds == null || oldIds.size != newIds.size) return null
    val mapping = LinkedHashMap<Int, Int>(oldIds.size)
    for (index in oldIds.indices) {
        val oldId = oldIds[index]
        val newId = newIds[index]
        if (oldId == AppWidgetManager.INVALID_APPWIDGET_ID ||
            newId == AppWidgetManager.INVALID_APPWIDGET_ID
        ) {
            continue
        }
        mapping[oldId] = newId
    }
    return mapping
}

/**
 * Picks the profile a restore placeholder's remembered provider should be
 * re-bound into, or `null` when no suitable profile exists yet.
 *
 * A personal record always re-binds into [personalUser]. A record written
 * before the kind existed says nothing about its profile beyond a serial
 * from whichever device wrote it, so it re-binds only into the profile whose
 * serial still matches (and has the provider) — the same-device case — and
 * otherwise stays a placeholder; guessing a work profile for it could expose
 * the wrong account's widget. A work record binds into the *only* managed
 * work profile ([profileKindOf] says [WidgetProfileKind.WORK]) that actually
 * has [WidgetProviderRecord.component] installed ([hasProvider]) — a private
 * space or clone profile is not a candidate even when it is the only
 * non-personal profile around, which it is precisely while the work profile
 * is still waiting to be recreated at the end of setup. Its serial is
 * not consulted: after a cross-device restore the recreated work profile
 * carries a serial the old device never saw — and it isn't created until the
 * end of setup, so a work record resolves to `null` until the profile exists,
 * the placeholder staying put rather than binding the personal copy of the
 * same provider (which is what a serial-only lookup silently did when the
 * serial happened to be reused, and what the platform's own restore does; see
 * `misboundRestoredWidgetIds`) — while a serial that *does* match can be that
 * same accident, naming a private space or clone profile rather than the
 * recreated work profile. So with several non-personal profiles carrying the
 * provider nothing is guessed: the record can't say which profile's account
 * the widget showed, and a wrong pick would show the other's. An
 * [WidgetProfileKind.OTHER] record (a private space or clone profile) binds
 * only into a profile of that same kind whose serial still matches and has
 * the provider: the serial alone is the reused-serial accident again, and
 * could name a work profile, so the kind has to agree with it; which of
 * several such profiles it was is never guessed either. A profile whose kind
 * can't be read this run ([profileKindOf] returns `null`) is no candidate
 * for anything: nothing is known about it, and a wrong bind shows another
 * account's widget. For a work record it is worse than no candidate — one
 * that has the provider may *be* the work profile, so its presence is
 * ambiguity, and the record stays a placeholder rather than bind the other
 * work profile that is known. A [WidgetProfileKind.NON_PERSONAL] record was
 * written where the platform couldn't tell a work profile from a clone
 * profile, so *every* non-personal profile with the provider counts toward
 * its ambiguity, not only the managed ones: it binds only when exactly one
 * has it, and never into a private space or clone profile
 * ([WidgetProfileKind.OTHER]) — a private space didn't exist where the
 * record was written, and is the "work profile not recreated yet" trap; a
 * clone widget from before API 35 therefore stays a placeholder after the
 * upgrade, to be removed and re-added. On a device that still can't tell,
 * every non-personal profile reads as [WidgetProfileKind.NON_PERSONAL] and
 * the sole one with the provider is taken, as before.
 */
internal fun resolveRestoreProfile(
    record: WidgetProviderRecord,
    personalUser: UserHandle,
    profiles: List<UserHandle>,
    serialOf: (UserHandle) -> Long?,
    hasProvider: (UserHandle) -> Boolean,
    profileKindOf: (UserHandle) -> WidgetProfileKind? = { profile ->
        if (profile == personalUser) WidgetProfileKind.PERSONAL else WidgetProfileKind.WORK
    },
): UserHandle? = when (record.profileKind) {
    null -> profiles.firstOrNull { profile -> serialOf(profile) == record.profileSerial && hasProvider(profile) }
    WidgetProfileKind.PERSONAL -> personalUser
    WidgetProfileKind.WORK -> soleCandidate(profiles, personalUser, hasProvider, profileKindOf) { kind ->
        kind == WidgetProfileKind.WORK
    }
    WidgetProfileKind.NON_PERSONAL -> soleCandidate(profiles, personalUser, hasProvider, profileKindOf) { kind ->
        kind != WidgetProfileKind.PERSONAL
    }?.takeIf { profile -> profileKindOf(profile) != WidgetProfileKind.OTHER }
    WidgetProfileKind.OTHER -> profiles.firstOrNull { profile ->
        profileKindOf(profile) == WidgetProfileKind.OTHER &&
            serialOf(profile) == record.profileSerial &&
            hasProvider(profile)
    }
}

/**
 * The one non-personal profile a work-side record may bind into, or `null`:
 * every non-personal profile with the provider is a candidate, an unknown
 * kind among them is ambiguity (it may be the home), and of the known ones
 * exactly one must pass [eligible]. For [WidgetProfileKind.NON_PERSONAL]
 * the caller then also refuses an [WidgetProfileKind.OTHER] sole candidate:
 * here it counts toward ambiguity (the record can't rule it out), there it
 * is refused as a home (see [resolveRestoreProfile]).
 */
private fun soleCandidate(
    profiles: List<UserHandle>,
    personalUser: UserHandle,
    hasProvider: (UserHandle) -> Boolean,
    profileKindOf: (UserHandle) -> WidgetProfileKind?,
    eligible: (WidgetProfileKind) -> Boolean,
): UserHandle? {
    val kindsWithProvider = profiles
        .filter { profile -> profile != personalUser && hasProvider(profile) }
        .map { profile -> profile to profileKindOf(profile) }
    if (kindsWithProvider.any { (_, kind) -> kind == null }) return null
    return kindsWithProvider
        .filter { (_, kind) -> kind != null && eligible(kind) }
        .map { (profile, _) -> profile }
        .singleOrNull()
}

/**
 * The [WidgetProfileKind] of [profile] — the classification a widget's record
 * is written with when it is added, the one a live binding is read back as
 * when the sweep judges it, and the one a restore tap picks a bind target by,
 * so every side of every comparison uses the same words. Everything not
 * personal and not managed (see [isManagedProfile]) is
 * [WidgetProfileKind.OTHER]. Before API 35 nothing public tells the two
 * apart, so every non-personal profile is [WidgetProfileKind.NON_PERSONAL]
 * there — a kind that says exactly what was known, so a record written on
 * such a device is never read as a certain "work" by a later one that can
 * tell. `null` when the kind can't be read this run: an unknown kind is
 * never a mismatch the sweep may act on, never a home a tap may bind into,
 * and a record written with it says nothing (like a legacy one) — a
 * transient failure must not read as a certain "not work".
 */
internal fun LauncherApps?.widgetProfileKind(profile: UserHandle, personalUser: UserHandle): WidgetProfileKind? = when {
    profile == personalUser -> WidgetProfileKind.PERSONAL
    Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM -> WidgetProfileKind.NON_PERSONAL
    else -> when (isManagedProfile(profile)) {
        true -> WidgetProfileKind.WORK
        false -> WidgetProfileKind.OTHER
        null -> null
    }
}

/**
 * Whether [profile] is a managed (work) profile, as opposed to a private
 * space or clone profile the launcher can also see. "Work" in a
 * [WidgetProviderRecord] means exactly this, on both sides of every
 * comparison: the kind written when a widget is added, the profile a restore
 * tap may bind into, and the profile of a live binding the sweep judges — so a
 * private-space widget is never recorded as work, and a private space is
 * never a home for a work record (it is the only non-personal profile around
 * precisely while the work profile waits to be recreated at the end of
 * setup). The profile kind is public from API 35; below that no public API
 * tells the kinds apart, and every non-personal profile the launcher sees is
 * treated as work here, as it was before — for the picker's work dressing
 * and the sweep's work-provider set, which cost nothing when wrong; what a
 * record carries from such a device is [widgetProfileKind]'s business, and
 * says less. `null` when the profile's info can't be
 * read this run — no service, a profile mid-removal whose info comes back
 * null, or a transient service failure — which is not a "no": callers that
 * only dress or offer treat it as not work, and callers that release or bind
 * treat it as unknown.
 */
internal fun LauncherApps?.isManagedProfile(profile: UserHandle): Boolean? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return true
    val info = try {
        this?.getLauncherUserInfo(profile)
    } catch (exception: RuntimeException) {
        LauncherDebugLog.failure(exception, "isManagedProfile: user info unavailable for profile=%s", profile.hashCode())
        return null
    }
    if (info == null) {
        LauncherDebugLog.warning("isManagedProfile: no user info for profile=%s", profile.hashCode())
        return null
    }
    return info.userType == UserManager.USER_TYPE_PROFILE_MANAGED
}
