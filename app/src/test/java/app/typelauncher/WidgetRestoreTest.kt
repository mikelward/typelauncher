package app.typelauncher

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Process
import android.os.UserHandle
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WidgetRestoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun clearPrefs() {
        context.getSharedPreferences("widgets", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun mappingZipsOldIdsToNewIdsInOrder() {
        val mapping = restoredWidgetIdMapping(
            oldIds = intArrayOf(10, 11, 12),
            newIds = intArrayOf(40, 41, 42),
        )

        assertEquals(mapOf(10 to 40, 11 to 41, 12 to 42), mapping)
    }

    @Test
    fun mappingIsNullWhenEitherArrayIsNull() {
        assertNull(restoredWidgetIdMapping(null, intArrayOf(1)))
        assertNull(restoredWidgetIdMapping(intArrayOf(1), null))
    }

    @Test
    fun mappingIsNullWhenArrayLengthsDiffer() {
        assertNull(restoredWidgetIdMapping(oldIds = intArrayOf(1, 2), newIds = intArrayOf(9)))
    }

    @Test
    fun mappingIsEmptyButNonNullForAWellFormedZeroWidgetRestore() {
        // Both arrays present and same (zero) length: a real restore that
        // brought nothing back — distinct from a malformed (null) payload.
        assertEquals(emptyMap<Int, Int>(), restoredWidgetIdMapping(intArrayOf(), intArrayOf()))
    }

    @Test
    fun mappingDropsPairsTouchingInvalidId() {
        val invalid = AppWidgetManager.INVALID_APPWIDGET_ID
        val mapping = restoredWidgetIdMapping(
            oldIds = intArrayOf(5, invalid, 7),
            newIds = intArrayOf(50, 60, invalid),
        )

        assertEquals(mapOf(5 to 50), mapping)
    }

    @Test
    fun receiverRemapsPersistedWidgetPagesToNewIds() {
        WidgetStore(context).apply {
            add(10)
            add(appWidgetId = 11, pageIndex = 0, addToNewPageAfter = true)
        }

        WidgetRestoredReceiver().onReceive(context, hostRestoredIntent(APP_WIDGET_HOST_ID, intArrayOf(10, 11), intArrayOf(40, 41)))

        assertEquals(listOf(listOf(40), listOf(41)), WidgetStore(context).widgetPages)
    }

    @Test
    fun receiverKeepsWidgetsMissingFromTheRestoreMappingAsPlaceholders() {
        WidgetStore(context).apply {
            add(10)
            add(11)
            // 11 has a remembered provider, so it survives as a placeholder.
            setProvider(11, WidgetProviderRecord(ComponentName("p", "p.W"), 0L, "X"))
        }

        // Only 10 was restored by the platform; 11's binding didn't survive but
        // is kept in place so it can render as a re-bindable restore placeholder.
        WidgetRestoredReceiver().onReceive(context, hostRestoredIntent(APP_WIDGET_HOST_ID, intArrayOf(10), intArrayOf(40)))

        assertEquals(listOf(listOf(40, 11)), WidgetStore(context).widgetPages)
    }

    @Test
    fun receiverWithWellFormedEmptyRestoreDropsRecordlessWidgetsButKeepsPlaceholders() {
        WidgetStore(context).apply {
            add(10)
            add(11)
            // 11 has a provider record; 10 is a legacy widget with none.
            setProvider(11, WidgetProviderRecord(ComponentName("p", "p.W"), 0L, "X"))
        }

        // A well-formed restore that brought nothing back: 10 can't be restored
        // so it's dropped; 11 survives as a re-bindable placeholder.
        WidgetRestoredReceiver().onReceive(context, hostRestoredIntent(APP_WIDGET_HOST_ID, intArrayOf(), intArrayOf()))

        assertEquals(listOf(listOf(11)), WidgetStore(context).widgetPages)
    }

    @Test
    fun receiverIgnoresMalformedRestorePayload() {
        WidgetStore(context).add(10)

        // Mismatched array lengths = malformed: ignored, store untouched.
        WidgetRestoredReceiver().onReceive(
            context,
            hostRestoredIntent(APP_WIDGET_HOST_ID, oldIds = intArrayOf(10, 11), newIds = intArrayOf(40)),
        )

        assertEquals(listOf(listOf(10)), WidgetStore(context).widgetPages)
    }

    @Test
    fun receiverIgnoresBroadcastForAnotherHostId() {
        WidgetStore(context).add(10)

        WidgetRestoredReceiver().onReceive(
            context,
            hostRestoredIntent(hostId = APP_WIDGET_HOST_ID + 1, oldIds = intArrayOf(10), newIds = intArrayOf(40)),
        )

        assertEquals(listOf(listOf(10)), WidgetStore(context).widgetPages)
    }

    private fun hostRestoredIntent(hostId: Int, oldIds: IntArray, newIds: IntArray): Intent =
        Intent(AppWidgetManager.ACTION_APPWIDGET_HOST_RESTORED)
            .putExtra(AppWidgetManager.EXTRA_HOST_ID, hostId)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_OLD_IDS, oldIds)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, newIds)

    @Test
    fun resolveRestoreProfileBindsAPersonalRecordToThePersonalUser() {
        val personal = Process.myUserHandle()
        val record = WidgetProviderRecord(CALENDAR, 0L, "Schedule", profileKind = WidgetProfileKind.PERSONAL)

        val profile = resolveRestoreProfile(
            record = record,
            personalUser = personal,
            profiles = listOf(personal, workUser(10)),
            serialOf = { user -> if (user == personal) 0L else 10L },
            hasProvider = { true },
        )

        assertSame(personal, profile)
    }

    @Test
    fun resolveRestoreProfileNeverBindsAWorkRecordToThePersonalUser() {
        val personal = Process.myUserHandle()
        // A cross-device restore: the record's serial came from the old
        // device, and this one has no work profile yet — the personal user has
        // the same provider installed, but that is not the user's widget.
        val record = WidgetProviderRecord(CALENDAR, 11L, "Schedule", profileKind = WidgetProfileKind.WORK)

        val profile = resolveRestoreProfile(
            record = record,
            personalUser = personal,
            profiles = listOf(personal),
            serialOf = { 0L },
            hasProvider = { true },
        )

        assertNull(profile)
    }

    @Test
    fun resolveRestoreProfileBindsAWorkRecordOnlyIntoTheSoleWorkProfileWithTheProvider() {
        val personal = Process.myUserHandle()
        val work = workUser(10)
        val other = workUser(11)
        val serials = mapOf(personal to 0L, work to 10L, other to 11L)

        // Two non-personal profiles carry the provider and one of them even
        // matches the serial — which after a device transfer can be a reused
        // serial naming the wrong profile — so nothing is guessed.
        assertNull(
            resolveRestoreProfile(
                record = WidgetProviderRecord(CALENDAR, 11L, "Schedule", profileKind = WidgetProfileKind.WORK),
                personalUser = personal,
                profiles = listOf(personal, work, other),
                serialOf = serials::get,
                hasProvider = { true },
            ),
        )
        // New device: the old serial matches nothing, and the one work profile
        // that has the provider is taken.
        assertSame(
            work,
            resolveRestoreProfile(
                record = WidgetProviderRecord(CALENDAR, 42L, "Schedule", profileKind = WidgetProfileKind.WORK),
                personalUser = personal,
                profiles = listOf(personal, work),
                serialOf = serials::get,
                hasProvider = { true },
            ),
        )
        // A work profile that exists but doesn't have the provider (not yet
        // reinstalled) can't take the bind.
        assertNull(
            resolveRestoreProfile(
                record = WidgetProviderRecord(CALENDAR, 42L, "Schedule", profileKind = WidgetProfileKind.WORK),
                personalUser = personal,
                profiles = listOf(personal, work),
                serialOf = serials::get,
                hasProvider = { user -> user == personal },
            ),
        )
        // Two non-personal profiles (work and, say, a private space) both have
        // the provider and neither matches the serial: the record can't say
        // whose account the widget showed, so nothing is guessed.
        assertNull(
            resolveRestoreProfile(
                record = WidgetProviderRecord(CALENDAR, 42L, "Schedule", profileKind = WidgetProfileKind.WORK),
                personalUser = personal,
                profiles = listOf(personal, work, other),
                serialOf = serials::get,
                hasProvider = { true },
            ),
        )
        // ...unless only one of them has it.
        assertSame(
            other,
            resolveRestoreProfile(
                record = WidgetProviderRecord(CALENDAR, 42L, "Schedule", profileKind = WidgetProfileKind.WORK),
                personalUser = personal,
                profiles = listOf(personal, work, other),
                serialOf = serials::get,
                hasProvider = { user -> user == other },
            ),
        )
        // A private space (not a managed profile) that has the provider is no
        // candidate, even as the sole non-personal profile — the work profile
        // may simply not have been recreated yet.
        assertNull(
            resolveRestoreProfile(
                record = WidgetProviderRecord(CALENDAR, 42L, "Schedule", profileKind = WidgetProfileKind.WORK),
                personalUser = personal,
                profiles = listOf(personal, other),
                serialOf = serials::get,
                hasProvider = { true },
                profileKindOf = { user -> if (user == work) WidgetProfileKind.WORK else WidgetProfileKind.OTHER },
            ),
        )
        // A profile whose kind can't be read this run is no candidate either,
        // even as the sole non-personal profile with the provider: a
        // transient failure is not evidence that it is the work profile.
        assertNull(
            resolveRestoreProfile(
                record = WidgetProviderRecord(CALENDAR, 42L, "Schedule", profileKind = WidgetProfileKind.WORK),
                personalUser = personal,
                profiles = listOf(personal, work),
                serialOf = serials::get,
                hasProvider = { true },
                profileKindOf = { user -> if (user == personal) WidgetProfileKind.PERSONAL else null },
            ),
        )
        // And while such a profile has the provider it is ambiguity, not
        // absence: it may be the work profile itself, so the one work
        // profile that is known is not taken either.
        val workOrUnknown: (UserHandle) -> WidgetProfileKind? = { user ->
            when (user) {
                personal -> WidgetProfileKind.PERSONAL
                work -> WidgetProfileKind.WORK
                else -> null
            }
        }
        assertNull(
            resolveRestoreProfile(
                record = WidgetProviderRecord(CALENDAR, 42L, "Schedule", profileKind = WidgetProfileKind.WORK),
                personalUser = personal,
                profiles = listOf(personal, work, other),
                serialOf = serials::get,
                hasProvider = { true },
                profileKindOf = workOrUnknown,
            ),
        )
        // ...unless the unknown profile doesn't have the provider, in which
        // case it can't be the home and the known work profile is taken.
        assertSame(
            work,
            resolveRestoreProfile(
                record = WidgetProviderRecord(CALENDAR, 42L, "Schedule", profileKind = WidgetProfileKind.WORK),
                personalUser = personal,
                profiles = listOf(personal, work, other),
                serialOf = serials::get,
                hasProvider = { user -> user != other },
                profileKindOf = workOrUnknown,
            ),
        )
    }

    @Test
    fun resolveRestoreProfileBindsAPreApi35RecordOnlyWhenTheChoiceIsUnambiguous() {
        val personal = Process.myUserHandle()
        val work = workUser(10)
        val private = workUser(11)
        val serials = mapOf(personal to 0L, work to 10L, private to 11L)
        val kindOf: (UserHandle) -> WidgetProfileKind? = { user ->
            when (user) {
                personal -> WidgetProfileKind.PERSONAL
                work -> WidgetProfileKind.WORK
                else -> WidgetProfileKind.OTHER
            }
        }
        val record = WidgetProviderRecord(CALENDAR, 42L, "Schedule", profileKind = WidgetProfileKind.NON_PERSONAL)

        // The sole non-personal profile with the provider is a work profile:
        // taken.
        assertSame(
            work,
            resolveRestoreProfile(record, personal, listOf(personal, work, private), serials::get, { user -> user != private }, kindOf),
        )
        // A work profile *and* a private space both have it: the record
        // couldn't tell which kind it was, so — unlike a certain work
        // record — the private space counts, and nothing is guessed.
        assertNull(resolveRestoreProfile(record, personal, listOf(personal, work, private), serials::get, { true }, kindOf))
        // The sole one is a private space: never a home for it (the work
        // profile may not be recreated yet), so the placeholder stays.
        assertNull(
            resolveRestoreProfile(record, personal, listOf(personal, work, private), serials::get, { user -> user == private }, kindOf),
        )
        // On a device that still can't tell, every non-personal profile
        // reads the same and the sole one with the provider is taken.
        val cannotTell: (UserHandle) -> WidgetProfileKind? = { user ->
            if (user == personal) WidgetProfileKind.PERSONAL else WidgetProfileKind.NON_PERSONAL
        }
        assertSame(
            private,
            resolveRestoreProfile(record, personal, listOf(personal, work, private), serials::get, { user -> user == private }, cannotTell),
        )
        assertNull(resolveRestoreProfile(record, personal, listOf(personal, work, private), serials::get, { true }, cannotTell))
    }

    @Test
    fun resolveRestoreProfileBindsALegacyRecordOnlyWhereItsSerialStillMatches() {
        val personal = Process.myUserHandle()
        val work = workUser(10)
        val serials = mapOf(personal to 11L, work to 12L)
        // Same device: the serial names the profile exactly, personal or work.
        assertSame(
            personal,
            resolveRestoreProfile(
                record = WidgetProviderRecord(CALENDAR, 11L, "Schedule"),
                personalUser = personal,
                profiles = listOf(personal, work),
                serialOf = serials::get,
                hasProvider = { true },
            ),
        )
        assertSame(
            work,
            resolveRestoreProfile(
                record = WidgetProviderRecord(CALENDAR, 12L, "Schedule"),
                personalUser = personal,
                profiles = listOf(personal, work),
                serialOf = serials::get,
                hasProvider = { true },
            ),
        )
        // Another device: the serial matches nothing, and nothing is guessed —
        // not the personal user (it may have been a work widget), and not the
        // work profile (it may have been a personal one recorded under a
        // secondary user), which could expose the wrong account's widget.
        assertNull(
            resolveRestoreProfile(
                record = WidgetProviderRecord(CALENDAR, 42L, "Schedule"),
                personalUser = personal,
                profiles = listOf(personal, work),
                serialOf = serials::get,
                hasProvider = { true },
            ),
        )
        assertNull(
            resolveRestoreProfile(
                record = WidgetProviderRecord(CALENDAR, 12L, "Schedule"),
                personalUser = personal,
                profiles = listOf(personal, work),
                serialOf = { null },
                hasProvider = { true },
            ),
        )
    }

    @Test
    fun resolveRestoreProfileBindsAPrivateSpaceRecordOnlyIntoAPrivateSpaceWithItsSerial() {
        val personal = Process.myUserHandle()
        val work = workUser(10)
        val private = workUser(11)
        val serials = mapOf(personal to 0L, work to 10L, private to 11L)
        val kindOf: (UserHandle) -> WidgetProfileKind? = { user ->
            when (user) {
                personal -> WidgetProfileKind.PERSONAL
                work -> WidgetProfileKind.WORK
                else -> WidgetProfileKind.OTHER
            }
        }
        val record = WidgetProviderRecord(CALENDAR, 11L, "Schedule", profileKind = WidgetProfileKind.OTHER)

        // Same device: back where its serial still matches.
        assertSame(
            private,
            resolveRestoreProfile(
                record = record,
                personalUser = personal,
                profiles = listOf(personal, work, private),
                serialOf = serials::get,
                hasProvider = { true },
                profileKindOf = kindOf,
            ),
        )
        // The serial alone is not enough: after a device transfer it can be
        // reused by a work profile, which is never a private-space widget's
        // home — the kind has to agree with the serial.
        assertNull(
            resolveRestoreProfile(
                record = record,
                personalUser = personal,
                profiles = listOf(personal, work),
                serialOf = mapOf(personal to 0L, work to 11L)::get,
                hasProvider = { true },
                profileKindOf = kindOf,
            ),
        )
        // Nor the personal user, even with the matching serial and the
        // provider installed.
        assertNull(
            resolveRestoreProfile(
                record = record,
                personalUser = personal,
                profiles = listOf(personal, work),
                serialOf = mapOf(personal to 11L, work to 10L)::get,
                hasProvider = { true },
                profileKindOf = kindOf,
            ),
        )
        // And a private space whose serial no longer matches (a new device)
        // is not guessed, even as the only one around.
        assertNull(
            resolveRestoreProfile(
                record = record,
                personalUser = personal,
                profiles = listOf(personal, work, private),
                serialOf = mapOf(personal to 0L, work to 10L, private to 42L)::get,
                hasProvider = { true },
                profileKindOf = kindOf,
            ),
        )
        // And a matching serial on a profile whose kind can't be read this
        // run proves nothing: it might be the work profile.
        assertNull(
            resolveRestoreProfile(
                record = record,
                personalUser = personal,
                profiles = listOf(personal, work, private),
                serialOf = serials::get,
                hasProvider = { true },
                profileKindOf = { user -> if (user == personal) WidgetProfileKind.PERSONAL else null },
            ),
        )
    }

    private fun workUser(userId: Int): UserHandle = UserHandle.getUserHandleForUid(userId * 100_000 + 10_000)

    private companion object {
        val CALENDAR = ComponentName("com.example", "com.example.CalendarWidget")
    }
}
