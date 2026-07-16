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
import org.junit.Assert.assertNotNull
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

        val started = shadowOf(context as android.app.Application).nextStartedActivity
        assertNotNull("Enter with only a contact match must open the contact", started)
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals(
            ContactsContract.Contacts.getLookupUri(7L, "lookup-7"),
            started.data,
        )
        assertEquals("Opening a result clears the search", "", viewModel.uiState.value.query)
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

    private fun newViewModel(): LauncherViewModel = LauncherViewModel(
        app = ApplicationProvider.getApplicationContext(),
        workPackages = emptySet(),
        ioDispatcher = Dispatchers.Unconfined,
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
    private fun registerContactsProvider(contacts: List<FakeContact>): FakeQueryProvider {
        val provider = FakeQueryProvider { projection ->
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
        }
        ShadowContentResolver.registerProviderInternal(ContactsContract.AUTHORITY, provider)
        return provider
    }

    private fun registerCalendarProvider(events: List<FakeEvent>): FakeQueryProvider {
        // Default begin: one hour from now, so the organizer keeps the event
        // (it drops instances that already ended).
        val defaultBegin = System.currentTimeMillis() + 60 * 60 * 1000
        val provider = FakeQueryProvider { projection ->
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
            }
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

    /** Minimal read-only provider: answers every query from [buildCursor], counting calls. */
    private class FakeQueryProvider(
        private val buildCursor: (projection: Array<String>) -> Cursor,
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
        ): Int = 0
    }
}
