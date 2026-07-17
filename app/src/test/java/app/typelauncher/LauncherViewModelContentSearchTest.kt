package app.typelauncher

import android.Manifest
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Looper
import android.provider.CalendarContract
import android.provider.ContactsContract
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

/**
 * Integration coverage for the typed-search content sections: index loading
 * behind the per-source settings + permissions, per-keystroke section results,
 * the Enter fallback to the first content result when zero apps match, and the
 * query-clearing contract on opening a result.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LauncherViewModelContentSearchTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

    @After
    fun clearPrefs() {
        listOf(
            "docked_apps",
            "dock_settings",
            "app_launch_stats",
            "widgets",
            "app_metadata",
            "hidden_apps",
            "renamed_apps",
        ).forEach { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
        }
    }

    @Test
    fun typedQueryFillsContentSectionsWhenEnabled() {
        seedApp("Mail", "com.example.mail")
        grantPermissions()
        enableBothSources()
        registerContactsProvider(listOf(FakeContact(1, "Maria Lopez"), FakeContact(2, "Bob Oates")))
        registerCalendarProvider(listOf(FakeEvent(10, "Marathon training")))
        val viewModel = newViewModel()
        idle()
        viewModel.onHomeReady()
        idle()

        viewModel.setQuery("mar")

        assertEquals(listOf("Maria Lopez"), viewModel.uiState.value.contactResults.map { it.displayName })
        val event = viewModel.uiState.value.eventResults.single()
        assertEquals("Marathon training", event.title)
        // The time label is formatted per query (not baked into the index), so
        // a matched event carries a non-blank now-relative time column — the
        // default fake event starts an hour out, so it's an upcoming timed row.
        assertTrue("event row must carry a formatted time label", event.displayTime.contains(":"))
        // Blank query empties the sections again — they only exist while typing.
        viewModel.setQuery("")
        assertTrue(viewModel.uiState.value.contactResults.isEmpty())
        assertTrue(viewModel.uiState.value.eventResults.isEmpty())
    }

    @Test
    fun contactIndexCarriesPhotoThumbnailUri() {
        seedApp("Mail", "com.example.mail")
        grantPermissions()
        enableBothSources()
        registerContactsProvider(
            listOf(
                FakeContact(1, "Maria Lopez", photoUri = "content://com.android.contacts/contacts/1/photo"),
                FakeContact(2, "Mark Chen"),
            ),
        )
        registerCalendarProvider(emptyList())
        val viewModel = newViewModel()
        idle()
        viewModel.onHomeReady()
        idle()

        viewModel.setQuery("ma")

        val results = viewModel.uiState.value.contactResults
        assertEquals(listOf("Maria Lopez", "Mark Chen"), results.map { it.displayName })
        // The photo URI rides the index so the row can decode lazily; a
        // photo-less contact carries null and renders the monogram.
        assertEquals(
            listOf("content://com.android.contacts/contacts/1/photo", null),
            results.map { it.photoThumbnailUri },
        )
    }

    @Test
    fun starredContactRanksAboveAlphabeticallyEarlierNonStarredContact() {
        seedApp("Mail", "com.example.mail")
        grantPermissions()
        enableBothSources()
        registerContactsProvider(
            listOf(
                FakeContact(1, "Marcus Aurelius"),
                FakeContact(2, "Marge Simpson"),
                FakeContact(3, "Margot Robbie", starred = true),
            ),
        )
        registerCalendarProvider(emptyList())
        val viewModel = newViewModel()
        idle()
        viewModel.onHomeReady()
        idle()

        viewModel.setQuery("mar")

        // All three are equally-good prefix matches for "mar" — Margot ranks
        // first for being starred despite "Marcus" and "Marge" sorting ahead
        // of her alphabetically; the two non-starred contacts keep their
        // alphabetical order behind her.
        assertEquals(
            listOf("Margot Robbie", "Marcus Aurelius", "Marge Simpson"),
            viewModel.uiState.value.contactResults.map { it.displayName },
        )
    }

    @Test
    fun disabledSourcesStayEmptyEvenWithMatches() {
        seedApp("Mail", "com.example.mail")
        grantPermissions()
        // Providers have data, but neither source is enabled in Settings.
        registerContactsProvider(listOf(FakeContact(1, "Maria Lopez")))
        registerCalendarProvider(listOf(FakeEvent(10, "Marathon training")))
        val viewModel = newViewModel()
        idle()
        viewModel.onHomeReady()
        idle()

        viewModel.setQuery("mar")

        assertTrue(viewModel.uiState.value.contactResults.isEmpty())
        assertTrue(viewModel.uiState.value.eventResults.isEmpty())
    }

    @Test
    fun enterOpensFirstContactWhenNoAppMatches() {
        seedApp("Mail", "com.example.mail")
        grantPermissions()
        enableBothSources()
        registerContactsProvider(listOf(FakeContact(7, "Zoe Quinn")))
        registerCalendarProvider(emptyList())
        val viewModel = newViewModel()
        idle()
        viewModel.onHomeReady()
        idle()

        viewModel.setQuery("zoe")
        assertTrue(
            "No app should match 'zoe'",
            viewModel.uiState.value.filteredApps.isEmpty(),
        )
        viewModel.launchActiveApp()
        idle()

        assertEquals(
            "Enter with only a contact match opens that contact's quick-actions sheet",
            7L,
            viewModel.uiState.value.contactActions?.contact?.contactId,
        )
        assertNull(
            "Opening the sheet launches nothing yet — an action does that",
            shadowOf(context as android.app.Application).nextStartedActivity,
        )
        assertEquals("The query stays while the sheet is up so dismissing returns to search", "zoe", viewModel.uiState.value.query)
    }

    @Test
    fun tappingContactOpensActionsSheet() {
        grantPermissions()
        enableBothSources()
        registerContactsProvider(listOf(FakeContact(7, "Zoe Quinn")))
        registerCalendarProvider(emptyList())
        val viewModel = newViewModel()
        idle()
        viewModel.onHomeReady()
        idle()

        viewModel.setQuery("zoe")
        val contact = viewModel.uiState.value.contactResults.single()
        viewModel.openContactResult(contact)
        idle()

        assertEquals(
            "Tapping a contact opens its quick-actions sheet",
            7L,
            viewModel.uiState.value.contactActions?.contact?.contactId,
        )
        assertNull(
            "The sheet itself launches nothing",
            shadowOf(context as android.app.Application).nextStartedActivity,
        )

        viewModel.dismissContactActions()
        assertNull("Dismissing closes the sheet", viewModel.uiState.value.contactActions)
        assertEquals("Dismissing leaves the search intact", "zoe", viewModel.uiState.value.query)
    }

    @Test
    fun openContactCardLaunchesQuickContactAndClearsQuery() {
        grantPermissions()
        enableBothSources()
        registerContactsProvider(listOf(FakeContact(7, "Zoe Quinn")))
        registerCalendarProvider(emptyList())
        val viewModel = newViewModel()
        idle()
        viewModel.onHomeReady()
        idle()
        viewModel.setQuery("zoe")
        val contact = viewModel.uiState.value.contactResults.single()
        viewModel.openContactResult(contact)
        idle()

        // The "Open contact" escape hatch opens the full QuickContact card.
        viewModel.openContactCard(contact)
        idle()

        val started = shadowOf(context as android.app.Application).nextStartedActivity
        assertNotNull("Open contact must launch the QuickContact card", started)
        assertEquals(ContactsContract.QuickContact.ACTION_QUICK_CONTACT, started.action)
        assertEquals(ContactsContract.Contacts.getLookupUri(7L, "lookup-7"), started.data)
        assertTrue(
            "The card launches as its own document task so Back returns to the launcher",
            started.flags and Intent.FLAG_ACTIVITY_NEW_DOCUMENT != 0,
        )
        assertNull("Acting closes the sheet", viewModel.uiState.value.contactActions)
        assertEquals("Acting clears the search", "", viewModel.uiState.value.query)
    }

    @Test
    fun staleContactResolveIsDiscardedAfterDismiss() {
        // Regression: a slow resolve must not pop a sheet after the user has
        // already dismissed. The QueueDispatcher parks the resolve so the
        // dismiss lands first; draining then runs the stale resolve, which the
        // request token must drop.
        val io = QueueDispatcher()
        grantPermissions()
        enableBothSources()
        registerContactsProvider(listOf(FakeContact(7, "Zoe Quinn")))
        registerCalendarProvider(emptyList())
        val viewModel = LauncherViewModel(
            app = ApplicationProvider.getApplicationContext(),
            workPackages = emptySet(),
            ioDispatcher = io,
        )
        settle(io)
        viewModel.onHomeReady()
        settle(io)

        val contact = ContactResult(contactId = 7, lookupKey = "lookup-7", displayName = "Zoe Quinn")
        viewModel.openContactResult(contact)
        // Dismiss before the parked resolve runs.
        viewModel.dismissContactActions()
        settle(io)

        assertNull(
            "A resolve that returns after dismiss must not re-open the sheet",
            viewModel.uiState.value.contactActions,
        )
    }

    @Test
    fun laterContactOpenSupersedesEarlierResolve() {
        val io = QueueDispatcher()
        grantPermissions()
        enableBothSources()
        registerContactsProvider(listOf(FakeContact(7, "Zoe Quinn"), FakeContact(8, "Bo Vale")))
        registerCalendarProvider(emptyList())
        val viewModel = LauncherViewModel(
            app = ApplicationProvider.getApplicationContext(),
            workPackages = emptySet(),
            ioDispatcher = io,
        )
        settle(io)
        viewModel.onHomeReady()
        settle(io)

        viewModel.openContactResult(ContactResult(7, "lookup-7", "Zoe Quinn"))
        viewModel.openContactResult(ContactResult(8, "lookup-8", "Bo Vale"))
        settle(io)

        assertEquals(
            "The latest opened contact wins regardless of resolve order",
            8L,
            viewModel.uiState.value.contactActions?.contact?.contactId,
        )
    }

    @Test
    fun launchActionStartsIntentAndClearsSearch() {
        val viewModel = viewModelWithOpenSheet()
        val sms = ContactAction(
            label = "Mobile",
            detail = "+15550100",
            isDefault = true,
            kind = ContactActionKind.Launch(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:+15550100"))),
        )

        viewModel.onContactActionSelected(sms)
        idle()

        val started = shadowOf(context as android.app.Application).nextStartedActivity
        assertEquals(Intent.ACTION_SENDTO, started.action)
        assertEquals("smsto:+15550100", started.data.toString())
        assertNull("Acting closes the sheet", viewModel.uiState.value.contactActions)
        assertEquals("Acting clears the search", "", viewModel.uiState.value.query)
    }

    @Test
    fun grantedCallPlacesCallDirectly() {
        shadowOf(context as android.app.Application).grantPermissions(Manifest.permission.CALL_PHONE)
        val viewModel = viewModelWithOpenSheet()

        viewModel.onContactActionSelected(callAction("+15550100"))
        idle()

        val started = shadowOf(context as android.app.Application).nextStartedActivity
        assertEquals("A granted Call is placed with ACTION_CALL", Intent.ACTION_CALL, started.action)
        assertEquals("+15550100", started.data?.schemeSpecificPart)
    }

    @Test
    fun callNumberWithHashIsNotTruncated() {
        // Regression: a '#' in a service / extension / USSD number must reach
        // the dialer intact. Building "tel:$number" as a string would parse the
        // '#' as a URI fragment and truncate the dial string.
        shadowOf(context as android.app.Application).grantPermissions(Manifest.permission.CALL_PHONE)
        val viewModel = viewModelWithOpenSheet()

        viewModel.onContactActionSelected(callAction("+1800555#123"))
        idle()

        val started = shadowOf(context as android.app.Application).nextStartedActivity
        assertEquals(Intent.ACTION_CALL, started.action)
        assertEquals(
            "The '#' must survive into the dial string, not be dropped as a URI fragment",
            "+1800555#123",
            started.data?.schemeSpecificPart,
        )
    }

    @Test
    fun callWithoutPermissionWaitsThenPlacesOnGrant() {
        // CALL_PHONE is not granted, so the tap parks the number and asks the
        // Activity to prompt rather than dialing anything yet.
        val viewModel = viewModelWithOpenSheet()

        viewModel.onContactActionSelected(callAction("+15550100"))
        idle()
        assertNull(
            "Nothing launches until the permission is resolved",
            shadowOf(context as android.app.Application).nextStartedActivity,
        )

        viewModel.onCallPermissionResult(granted = true)
        idle()
        val started = shadowOf(context as android.app.Application).nextStartedActivity
        assertEquals("A granted prompt places the parked call", Intent.ACTION_CALL, started.action)
        assertEquals("+15550100", started.data?.schemeSpecificPart)
    }

    @Test
    fun deniedCallFallsBackToDialer() {
        val viewModel = viewModelWithOpenSheet()

        viewModel.onContactActionSelected(callAction("+15550100"))
        idle()
        viewModel.onCallPermissionResult(granted = false)
        idle()

        val started = shadowOf(context as android.app.Application).nextStartedActivity
        assertEquals("A refused Call falls back to the dialer", Intent.ACTION_DIAL, started.action)
        assertEquals("+15550100", started.data?.schemeSpecificPart)
        assertNull("The fallback still closes the sheet", viewModel.uiState.value.contactActions)
    }

    @Test
    fun enterStillLaunchesAppWhenBothMatch() {
        seedApp("Maps", "com.example.maps")
        grantPermissions()
        enableBothSources()
        registerContactsProvider(listOf(FakeContact(1, "Maria Lopez")))
        registerCalendarProvider(emptyList())
        val viewModel = newViewModel()
        idle()
        viewModel.onHomeReady()
        idle()

        viewModel.setQuery("ma")
        viewModel.launchActiveApp()
        idle()

        val started = shadowOf(context as android.app.Application).nextStartedActivity
        assertNotNull(started)
        assertEquals(
            "Apps own Enter whenever any app matches",
            "com.example.maps",
            started.component?.packageName,
        )
    }

    @Test
    fun futureAllDayEventIsSearchable() {
        // Regression: the search index must use the forSearch organizer — the
        // agenda's forNow drops all-day events that don't intersect today, so
        // a next-week all-day vacation was unreachable from search.
        seedApp("Mail", "com.example.mail")
        grantPermissions()
        enableBothSources()
        registerContactsProvider(emptyList())
        val utcTodayStart = java.time.Instant.ofEpochMilli(System.currentTimeMillis())
            .atZone(java.time.ZoneOffset.UTC)
            .toLocalDate()
        registerCalendarProvider(
            listOf(
                FakeEvent(
                    id = 20,
                    title = "Vacation in Lisbon",
                    allDay = true,
                    beginMillis = utcTodayStart.plusDays(5)
                        .atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli(),
                    durationMillis = 24 * 60 * 60 * 1000L,
                ),
            ),
        )
        val viewModel = newViewModel()
        idle()
        viewModel.onHomeReady()
        idle()

        viewModel.setQuery("vac")

        assertEquals(
            listOf("Vacation in Lisbon"),
            viewModel.uiState.value.eventResults.map { it.title },
        )
    }

    @Test
    fun dateChangedBroadcastReloadsSearchEventIndex() {
        // The event index bakes day-relative labels ("Today", "Fri", "Jul 25")
        // at load time, so the midnight-rollover / clock-change broadcast has
        // to reload it or the visible rows keep yesterday's labels.
        seedApp("Mail", "com.example.mail")
        grantPermissions()
        enableBothSources()
        registerContactsProvider(emptyList())
        val calendarProvider = registerCalendarProvider(listOf(FakeEvent(10, "Marathon training")))
        val viewModel = newViewModel()
        idle()
        viewModel.onHomeReady()
        idle()
        val queriesAfterInitialLoad = calendarProvider.queryCount
        assertTrue("initial load must query the calendar provider", queriesAfterInitialLoad > 0)

        context.sendBroadcast(Intent(Intent.ACTION_DATE_CHANGED))
        idle()

        assertTrue(
            "Date-changed broadcast must reload the search event index",
            calendarProvider.queryCount > queriesAfterInitialLoad,
        )
    }

    @Test
    fun disableInvalidatesInFlightIndexLoad() {
        // Regression: disabling a source while its IO load is still parked
        // must invalidate that load — otherwise it lands after the clear and
        // the launcher keeps holding just-disabled contact data in memory.
        seedApp("Mail", "com.example.mail")
        grantPermissions()
        val contactsProvider = registerContactsProvider(listOf(FakeContact(1, "Maria Lopez")))
        registerCalendarProvider(emptyList())
        val io = QueueDispatcher()
        val viewModel = LauncherViewModel(
            app = ApplicationProvider.getApplicationContext(),
            workPackages = emptySet(),
            ioDispatcher = io,
        )
        settle(io)

        viewModel.setContactSearchEnabled(true)
        idle()
        // The contact-index load is parked on the io queue; disable before it
        // runs.
        viewModel.setContactSearchEnabled(false)
        settle(io)

        assertEquals(0 to 0, viewModel.contentSearchIndexSizesForTest())
        // The parked load must not even *query* the just-opted-out provider —
        // the version re-check runs before the read, not only before publish.
        assertEquals(0, contactsProvider.queryCount)
    }

    @Test
    fun persistedToggleWithoutPermissionIsCoercedOff() {
        // Regression: Android's permission auto-reset (or a backup restore
        // onto a fresh install) can leave the toggle persisted on with no
        // permission behind it. The flag must coerce back to off so the
        // switch renders off and the next tap prompts, instead of a switch
        // that's on but silently loads nothing.
        seedApp("Mail", "com.example.mail")
        // Deliberately NOT granting permissions.
        enableBothSources()
        registerContactsProvider(listOf(FakeContact(1, "Maria Lopez")))
        registerCalendarProvider(emptyList())
        val viewModel = newViewModel()
        idle()

        assertEquals(false, viewModel.uiState.value.isContactSearchEnabled)
        assertEquals(false, viewModel.uiState.value.isCalendarSearchEnabled)
        val prefs = context.getSharedPreferences("dock_settings", Context.MODE_PRIVATE)
        assertEquals(false, prefs.getBoolean("contact_search_enabled", false))
        assertEquals(false, prefs.getBoolean("calendar_search_enabled", false))
    }

    @Test
    fun eventOpenFailurePreservesQuery() {
        // Regression: on a device/profile with no calendar app, opening an
        // events-section result throws ActivityNotFoundException internally —
        // the tap must stay a no-op instead of clearing what the user typed.
        seedApp("Mail", "com.example.mail")
        grantPermissions()
        enableBothSources()
        registerContactsProvider(emptyList())
        registerCalendarProvider(listOf(FakeEvent(10, "Marathon training")))
        val viewModel = newViewModel()
        idle()
        viewModel.onHomeReady()
        idle()
        viewModel.setQuery("mar")
        val event = viewModel.uiState.value.eventResults.single()

        // Make startActivity throw ActivityNotFoundException for intents with
        // no registered handler (the default shadow allows everything).
        shadowOf(context as android.app.Application).checkActivities(true)
        viewModel.openEventResult(event)
        idle()

        assertEquals("Query must survive a failed event handoff", "mar", viewModel.uiState.value.query)
    }

    @Test
    fun contactProviderFailureDegradesToEmptyIndex() {
        // Regression: a provider-side RuntimeException (disabled/corrupt OEM
        // contacts provider) must degrade to an empty section, not escape the
        // index-load coroutine and crash the launcher.
        seedApp("Mail", "com.example.mail")
        grantPermissions()
        enableBothSources()
        ShadowContentResolver.registerProviderInternal(
            ContactsContract.AUTHORITY,
            object : ContentProvider() {
                override fun onCreate(): Boolean = true
                override fun query(
                    uri: Uri,
                    projection: Array<String>?,
                    selection: String?,
                    selectionArgs: Array<String>?,
                    sortOrder: String?,
                ): Cursor = throw IllegalStateException("provider database failure")

                override fun getType(uri: Uri): String? = null
                override fun insert(uri: Uri, values: ContentValues?): Uri? = null
                override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
                override fun update(
                    uri: Uri,
                    values: ContentValues?,
                    selection: String?,
                    selectionArgs: Array<String>?,
                ): Int = 0
            },
        )
        registerCalendarProvider(listOf(FakeEvent(10, "Marathon training")))
        val viewModel = newViewModel()
        idle()
        viewModel.onHomeReady()
        idle()

        viewModel.setQuery("mar")

        assertTrue(viewModel.uiState.value.contactResults.isEmpty())
        // The calendar source is unaffected by the contacts provider failing.
        assertEquals(listOf("Marathon training"), viewModel.uiState.value.eventResults.map { it.title })
    }

    @Test
    fun favoriteWritesStarredAndFlipsTheRowOptimistically() {
        grantPermissions()
        shadowOf(context as android.app.Application).grantPermissions(Manifest.permission.WRITE_CONTACTS)
        enableBothSources()
        val starredWrites = mutableListOf<Int?>()
        registerContactsProvider(
            listOf(FakeContact(7, "Zoe Quinn")),
            onUpdate = { values ->
                starredWrites.add(values?.getAsInteger(ContactsContract.Contacts.STARRED))
                1
            },
        )
        registerCalendarProvider(emptyList())
        val viewModel = newViewModel()
        idle()
        viewModel.onHomeReady()
        idle()
        viewModel.setQuery("zoe")
        val contact = viewModel.uiState.value.contactResults.single()
        assertFalse("Starts non-starred", contact.starred)

        viewModel.toggleContactStarred(contact)
        idle()

        assertEquals("Toggling on writes STARRED = 1", listOf(1), starredWrites)
        assertTrue(
            "The visible row stars immediately, without re-querying the provider",
            viewModel.uiState.value.contactResults.single().starred,
        )
    }

    @Test
    fun favoriteWithoutPermissionWaitsThenWritesOnGrant() {
        grantPermissions() // READ only — WRITE_CONTACTS is not granted.
        enableBothSources()
        val starredWrites = mutableListOf<Int?>()
        registerContactsProvider(
            listOf(FakeContact(7, "Zoe Quinn")),
            onUpdate = { values ->
                starredWrites.add(values?.getAsInteger(ContactsContract.Contacts.STARRED))
                1
            },
        )
        registerCalendarProvider(emptyList())
        val viewModel = newViewModel()
        idle()
        viewModel.onHomeReady()
        idle()
        viewModel.setQuery("zoe")

        viewModel.toggleContactStarred(viewModel.uiState.value.contactResults.single())
        idle()
        assertTrue("Nothing is written until the permission is resolved", starredWrites.isEmpty())

        viewModel.onWriteContactsPermissionResult(granted = true)
        idle()
        assertEquals("The parked write replays on grant", listOf(1), starredWrites)
        assertTrue(viewModel.uiState.value.contactResults.single().starred)
    }

    @Test
    fun favoriteFlipsBeforeTheProviderWriteReturns() {
        val io = QueueDispatcher()
        grantPermissions()
        shadowOf(context as android.app.Application).grantPermissions(Manifest.permission.WRITE_CONTACTS)
        enableBothSources()
        registerContactsProvider(listOf(FakeContact(7, "Zoe Quinn")), onUpdate = { 1 })
        registerCalendarProvider(emptyList())
        val viewModel = LauncherViewModel(
            app = ApplicationProvider.getApplicationContext(),
            workPackages = emptySet(),
            ioDispatcher = io,
        )
        settle(io)
        viewModel.onHomeReady()
        settle(io)
        viewModel.setQuery("zoe")
        val contact = viewModel.uiState.value.contactResults.single()

        viewModel.toggleContactStarred(contact)
        idle()

        // The provider update is still parked on the io queue, yet the star has
        // already flipped — the optimistic flip doesn't wait on I/O.
        assertTrue(
            "The star flips before the provider write returns",
            viewModel.uiState.value.contactResults.single().starred,
        )
        settle(io)
        assertTrue(
            "It stays starred once the write succeeds",
            viewModel.uiState.value.contactResults.single().starred,
        )
    }

    @Test
    fun favoriteRevertsTheFlipWhenTheWriteFails() {
        grantPermissions()
        shadowOf(context as android.app.Application).grantPermissions(Manifest.permission.WRITE_CONTACTS)
        enableBothSources()
        // The provider reports zero rows updated — the write did not take.
        registerContactsProvider(listOf(FakeContact(7, "Zoe Quinn")), onUpdate = { 0 })
        registerCalendarProvider(emptyList())
        val viewModel = newViewModel()
        idle()
        viewModel.onHomeReady()
        idle()
        viewModel.setQuery("zoe")

        viewModel.toggleContactStarred(viewModel.uiState.value.contactResults.single())
        idle()

        assertFalse(
            "A failed write reverts the optimistic flip",
            viewModel.uiState.value.contactResults.single().starred,
        )
    }

    @Test
    fun rapidTogglesThatAllFailLeaveTheRowOnTheConfirmedState() {
        val io = QueueDispatcher()
        grantPermissions()
        shadowOf(context as android.app.Application).grantPermissions(Manifest.permission.WRITE_CONTACTS)
        enableBothSources()
        // Every write is rejected, and the contact starts (and stays) unstarred.
        registerContactsProvider(listOf(FakeContact(7, "Zoe Quinn")), onUpdate = { 0 })
        registerCalendarProvider(emptyList())
        val viewModel = LauncherViewModel(
            app = ApplicationProvider.getApplicationContext(),
            workPackages = emptySet(),
            ioDispatcher = io,
        )
        settle(io)
        viewModel.onHomeReady()
        settle(io)
        viewModel.setQuery("zoe")

        // Favorite then unfavorite while both writes are parked; both fail.
        viewModel.toggleContactStarred(viewModel.uiState.value.contactResults.single())
        viewModel.toggleContactStarred(viewModel.uiState.value.contactResults.single())
        settle(io)

        // The failure re-reads the provider (unstarred) rather than inverting the
        // second failed request, so the row doesn't flip back to starred.
        assertFalse(
            "Both writes failed, so the row stays on the confirmed unstarred state",
            viewModel.uiState.value.contactResults.single().starred,
        )
    }

    @Test
    fun rapidFavoriteTogglesSerializeSoTheLatestWins() {
        val io = QueueDispatcher()
        grantPermissions()
        shadowOf(context as android.app.Application).grantPermissions(Manifest.permission.WRITE_CONTACTS)
        enableBothSources()
        val starredWrites = mutableListOf<Int?>()
        registerContactsProvider(
            listOf(FakeContact(7, "Zoe Quinn")),
            onUpdate = { values ->
                starredWrites.add(values?.getAsInteger(ContactsContract.Contacts.STARRED))
                1
            },
        )
        registerCalendarProvider(emptyList())
        val viewModel = LauncherViewModel(
            app = ApplicationProvider.getApplicationContext(),
            workPackages = emptySet(),
            ioDispatcher = io,
        )
        settle(io)
        viewModel.onHomeReady()
        settle(io)
        viewModel.setQuery("zoe")

        // Favorite, then immediately unfavorite the same row while both writes
        // are still parked on the io queue.
        viewModel.toggleContactStarred(viewModel.uiState.value.contactResults.single())
        viewModel.toggleContactStarred(viewModel.uiState.value.contactResults.single())
        settle(io)

        assertEquals("The two writes land in toggle order, so the latest wins", listOf(1, 0), starredWrites)
        assertFalse(
            "Final state is the latest toggle (unstarred)",
            viewModel.uiState.value.contactResults.single().starred,
        )
    }

    @Test
    fun favoriteSurvivesAnIndexReloadThatRacesTheWrite() {
        val io = QueueDispatcher()
        grantPermissions()
        shadowOf(context as android.app.Application).grantPermissions(Manifest.permission.WRITE_CONTACTS)
        enableBothSources()
        // A provider that reflects the committed STARRED: its query re-reads the
        // current value, and its update commits the new one.
        var providerStarred = false
        val provider = FakeQueryProvider(
            buildCursor = { projection ->
                MatrixCursor(projection).apply {
                    addRow(
                        projection.map { column ->
                            when (column) {
                                ContactsContract.Contacts._ID -> 7L
                                ContactsContract.Contacts.LOOKUP_KEY -> "lookup-7"
                                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY -> "Zoe Quinn"
                                ContactsContract.Contacts.STARRED -> if (providerStarred) 1 else 0
                                else -> null
                            }
                        },
                    )
                }
            },
            onUpdate = { values ->
                providerStarred = (values?.getAsInteger(ContactsContract.Contacts.STARRED) ?: 0) == 1
                1
            },
        )
        ShadowContentResolver.registerProviderInternal(ContactsContract.AUTHORITY, provider)
        registerCalendarProvider(emptyList())
        val viewModel = LauncherViewModel(
            app = ApplicationProvider.getApplicationContext(),
            workPackages = emptySet(),
            ioDispatcher = io,
        )
        settle(io)
        viewModel.onHomeReady()
        settle(io)
        viewModel.setQuery("zoe")
        val contact = viewModel.uiState.value.contactResults.single()

        // A resume-driven contact reload is queued first, so its query re-reads
        // the still-false STARRED and clobbers the optimistic flip mid-write.
        viewModel.refreshPermissionDrivenUi()
        viewModel.toggleContactStarred(contact)
        settle(io)

        // The successful write re-asserts the star, so the row survives the
        // racing reload rather than being left on the stale value.
        assertTrue(
            "Row ends starred despite the mid-write reload",
            viewModel.uiState.value.contactResults.single().starred,
        )
        assertTrue("The provider committed the star", providerStarred)
    }

    @Test
    fun externalFavoriteChangeAfterCommitIsNotMaskedByThePendingOverride() {
        grantPermissions()
        shadowOf(context as android.app.Application).grantPermissions(Manifest.permission.WRITE_CONTACTS)
        enableBothSources()
        var providerStarred = false
        val provider = FakeQueryProvider(
            buildCursor = { projection ->
                MatrixCursor(projection).apply {
                    addRow(
                        projection.map { column ->
                            when (column) {
                                ContactsContract.Contacts._ID -> 7L
                                ContactsContract.Contacts.LOOKUP_KEY -> "lookup-7"
                                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY -> "Zoe Quinn"
                                ContactsContract.Contacts.STARRED -> if (providerStarred) 1 else 0
                                else -> null
                            }
                        },
                    )
                }
            },
            onUpdate = { values ->
                providerStarred = (values?.getAsInteger(ContactsContract.Contacts.STARRED) ?: 0) == 1
                1
            },
        )
        ShadowContentResolver.registerProviderInternal(ContactsContract.AUTHORITY, provider)
        registerCalendarProvider(emptyList())
        val viewModel = newViewModel()
        idle()
        viewModel.onHomeReady()
        idle()
        viewModel.setQuery("zoe")

        // Favorite in Type Launcher: the write commits.
        viewModel.toggleContactStarred(viewModel.uiState.value.contactResults.single())
        idle()
        assertTrue(viewModel.uiState.value.contactResults.single().starred)

        // The user then unfavorites the same contact in the Contacts app, and
        // Type Launcher reloads on resume — reading the fresh (unstarred) value.
        providerStarred = false
        viewModel.refreshPermissionDrivenUi()
        idle()

        // The reload is authoritative (its read is after the write committed), so
        // the external change shows through rather than being masked by the stale
        // optimistic override.
        assertFalse(
            "An external unfavorite after commit is not masked by the pending override",
            viewModel.uiState.value.contactResults.single().starred,
        )
    }

    private fun newViewModel(): LauncherViewModel = LauncherViewModel(
        app = ApplicationProvider.getApplicationContext(),
        workPackages = emptySet(),
        ioDispatcher = Dispatchers.Unconfined,
    )

    /** A view model showing a searched contact's (empty-channel) quick-actions sheet, query "zoe". */
    private fun viewModelWithOpenSheet(): LauncherViewModel {
        grantPermissions()
        enableBothSources()
        registerContactsProvider(listOf(FakeContact(7, "Zoe Quinn")))
        registerCalendarProvider(emptyList())
        val viewModel = newViewModel()
        idle()
        viewModel.onHomeReady()
        idle()
        viewModel.setQuery("zoe")
        viewModel.openContactResult(viewModel.uiState.value.contactResults.single())
        idle()
        return viewModel
    }

    private fun callAction(number: String) = ContactAction(
        label = "Mobile",
        detail = number,
        isDefault = true,
        kind = ContactActionKind.Call(number),
    )

    private fun grantPermissions() {
        shadowOf(context as android.app.Application).grantPermissions(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_CALENDAR,
        )
    }

    private fun enableBothSources() {
        context.getSharedPreferences("dock_settings", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("contact_search_enabled", true)
            .putBoolean("calendar_search_enabled", true)
            .commit()
    }

    private fun seedApp(label: String, packageName: String) {
        val resolveInfo = ResolveInfo().apply {
            nonLocalizedLabel = label
            activityInfo = ActivityInfo().apply {
                this.packageName = packageName
                name = "$packageName.LaunchActivity"
            }
        }
        @Suppress("DEPRECATION")
        shadowOf(context.packageManager).addResolveInfoForIntent(launcherIntent, resolveInfo)
    }

    /** Registers the fake contacts provider; the returned provider exposes [FakeQueryProvider.queryCount]. */
    private fun registerContactsProvider(
        contacts: List<FakeContact>,
        onUpdate: (ContentValues?) -> Int = { 0 },
    ): FakeQueryProvider {
        val provider = FakeQueryProvider(
            buildCursor = { projection ->
                val cursor = MatrixCursor(projection)
                contacts.forEach { contact ->
                    cursor.addRow(
                        projection.map { column ->
                            when (column) {
                                ContactsContract.Contacts._ID -> contact.id
                                ContactsContract.Contacts.LOOKUP_KEY -> "lookup-${contact.id}"
                                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY -> contact.name
                                ContactsContract.Contacts.PHOTO_THUMBNAIL_URI -> contact.photoUri
                                ContactsContract.Contacts.STARRED -> if (contact.starred) 1 else 0
                                else -> null
                            }
                        },
                    )
                }
                cursor
            },
            onUpdate = onUpdate,
        )
        ShadowContentResolver.registerProviderInternal(ContactsContract.AUTHORITY, provider)
        return provider
    }

    private fun registerCalendarProvider(events: List<FakeEvent>): FakeQueryProvider {
        // Default begin: one hour from now, so the organizer keeps the event
        // (it drops instances that already ended).
        val defaultBegin = System.currentTimeMillis() + 60 * 60 * 1000
        val provider = FakeQueryProvider(buildCursor = { projection ->
                val cursor = MatrixCursor(projection)
                events.forEach { event ->
                    val begin = event.beginMillis ?: defaultBegin
                    val end = begin + (event.durationMillis ?: 30 * 60 * 1000L)
                    cursor.addRow(
                        projection.map { column ->
                            when (column) {
                                CalendarContract.Instances.EVENT_ID -> event.id
                                CalendarContract.Instances.TITLE -> event.title
                                CalendarContract.Instances.BEGIN -> begin
                                CalendarContract.Instances.END -> end
                                CalendarContract.Instances.ALL_DAY -> if (event.allDay) 1 else 0
                                else -> null
                            }
                        },
                    )
                }
                cursor
            })
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, provider)
        return provider
    }

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    /**
     * Alternates draining the parked io queue and the main looper until both
     * settle, so cold-start coroutines (apps load, snapshot restore, metadata
     * save) complete when the test uses [QueueDispatcher] instead of
     * `Dispatchers.Unconfined`.
     */
    private fun settle(io: QueueDispatcher) {
        repeat(6) {
            io.drain()
            idle()
        }
    }

    /**
     * Dispatcher that parks dispatched blocks in a queue until [drain] runs
     * them, letting a test interleave main-thread calls between a coroutine's
     * launch and its io stage — the disable-while-loading race needs exactly
     * that window.
     */
    private class QueueDispatcher : kotlinx.coroutines.CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()
        override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
            queue.add(block)
        }

        fun drain() {
            while (queue.isNotEmpty()) queue.removeFirst().run()
        }
    }

    private data class FakeContact(
        val id: Long,
        val name: String,
        val photoUri: String? = null,
        val starred: Boolean = false,
    )
    private data class FakeEvent(
        val id: Long,
        val title: String,
        val allDay: Boolean = false,
        val beginMillis: Long? = null,
        val durationMillis: Long? = null,
    )

    /** Minimal provider: answers every query from [buildCursor] (counting calls) and delegates writes to [onUpdate]. */
    private class FakeQueryProvider(
        private val buildCursor: (projection: Array<String>) -> Cursor,
        private val onUpdate: (ContentValues?) -> Int = { 0 },
    ) : ContentProvider() {
        var queryCount = 0
            private set

        override fun onCreate(): Boolean = true
        override fun query(
            uri: Uri,
            projection: Array<String>?,
            selection: String?,
            selectionArgs: Array<String>?,
            sortOrder: String?,
        ): Cursor {
            queryCount++
            return buildCursor(projection ?: emptyArray())
        }

        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<String>?,
        ): Int = onUpdate(values)
    }
}
