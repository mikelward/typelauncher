package app.typelauncher

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.os.BadParcelableException
import android.os.Looper
import android.os.UserHandle
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowApplicationPackageManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LauncherViewModelReloadTest {
    /**
     * A `PackageManager` whose per-package reads fail the way a full binder
     * buffer fails. The load's fallback path reads each result's package
     * timestamp, so this is the transaction that is left after the big
     * queries are guarded.
     */
    @Implements(className = "android.app.ApplicationPackageManager")
    class ThrowingPackageInfoShadow : ShadowApplicationPackageManager() {
        @Implementation
        override fun getPackageInfo(packageName: String?, flags: Int): PackageInfo? {
            if (failPackageInfo) {
                throw BadParcelableException("Failure retrieving array; only received 332 of 490")
            }
            return super.getPackageInfo(packageName, flags)
        }

        companion object {
            @Volatile
            @JvmStatic
            var failPackageInfo = false
        }
    }

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
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
            context.getSharedPreferences(name, android.content.Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
        }
    }

    @Test
    fun personalProfileAppsAreNotMarkedQuiet() {
        seedApp("Mail", "com.example.mail")
        val viewModel = newViewModel()
        idle()
        val mail = viewModel.uiState.value.filteredApps.first { it.name == "Mail" }
        // Personal-profile apps must never be marked as quiet because the
        // personal profile cannot be paused. The dimmed-icon treatment in
        // `AppIcon` is reserved for work-profile apps when their profile is
        // in quiet mode.
        assertFalse("Personal-profile app must not be marked quiet", mail.isQuietMode)
    }

    @Test
    fun reloadAfterPackageInstallSurfacesNewApp() {
        seedApp("Mail", "com.example.mail")
        val viewModel = newViewModel()
        idle()
        assertTrue(
            "Cold-start load must complete before reload runs",
            viewModel.uiState.value.isFreshAppLoadComplete,
        )
        val initialNames = viewModel.uiState.value.filteredApps.map { it.name }
        assertTrue("Cold-start load surfaces seeded app", initialNames.contains("Mail"))
        assertFalse("Newly added package isn't visible yet", initialNames.contains("Chat"))

        seedApp("Chat", "com.example.chat")
        viewModel.reloadInstalledAppsForTest()
        idle()

        val updatedNames = viewModel.uiState.value.filteredApps.map { it.name }
        assertTrue(
            "Reload picks up the post-install package",
            updatedNames.containsAll(listOf("Mail", "Chat")),
        )
    }

    @Test
    fun aPackageEventDuringAReloadDoesNotStartASecondEnumerationBesideIt() {
        // The crash this guards: `getActivityList` is a blocking binder call,
        // so canceling the reload that owns it does not stop it. Six package
        // events inside a second (a carrier-update storm) therefore put six
        // ~490-activity enumerations on the wire at once, exhausted the
        // process's binder buffer, and killed the launcher with
        // BadParcelableException "Failure retrieving array; only received 332
        // of 490" — in the background, so all the user saw was the post-crash
        // banner on the next launch.
        //
        // Only expressible across threads: the failure is a second
        // enumeration *starting* while the first is still blocked in binder,
        // so the load runs on a real dispatcher here and the first
        // enumeration is held open while the second event arrives.
        seedApp("Mail", "com.example.mail")
        val gateEnumerations = AtomicBoolean(false)
        val firstEnumerationStarted = CountDownLatch(1)
        val secondEnumerationStarted = CountDownLatch(1)
        val releaseFirstEnumeration = CountDownLatch(1)
        // Cold start runs inline (Unconfined) and the gated reloads run on a
        // real dispatcher. Nothing here may depend on when a worker gets
        // around to the cold-start load: `idle()` drains the main looper and
        // does not wait for a worker, so a real dispatcher there could gate
        // the cold-start enumeration instead of a reload's — and a reload
        // requested before cold start publishes is deferred rather than run,
        // which would pass the assertion below without testing what it names.
        val loadDispatcher = SwitchableDispatcher()
        val viewModel = newViewModel(ioDispatcher = loadDispatcher) { _, _ ->
            if (gateEnumerations.get()) {
                if (firstEnumerationStarted.count > 0) {
                    firstEnumerationStarted.countDown()
                    // Stand in for a binder transaction that has not come back
                    // yet. Bounded so a regression hangs the assertion below
                    // rather than the suite.
                    releaseFirstEnumeration.await(GATE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                } else {
                    secondEnumerationStarted.countDown()
                }
            }
            // Robolectric's LauncherApps surfaces no activities; the load's
            // PackageManager fallback fills the list, as in every other test
            // here.
            emptyList()
        }
        idle()
        assertTrue(
            "Cold start must have published before the gate arms",
            viewModel.uiState.value.isFreshAppLoadComplete,
        )
        loadDispatcher.delegate = Dispatchers.Default
        gateEnumerations.set(true)

        viewModel.reloadInstalledAppsForTest()
        idle()
        assertTrue(
            "The first reload's enumeration must reach the gate",
            firstEnumerationStarted.await(GATE_TIMEOUT_SECONDS, TimeUnit.SECONDS),
        )
        // The next package event of the storm, arriving while that
        // enumeration is still outstanding.
        viewModel.reloadInstalledAppsForTest()
        idle()

        assertFalse(
            "A reload requested mid-enumeration must wait, not enumerate alongside",
            secondEnumerationStarted.await(NO_SECOND_ENUMERATION_MILLIS, TimeUnit.MILLISECONDS),
        )

        releaseFirstEnumeration.countDown()

        // Draining means pumping two schedulers alternately, and only this
        // thread can pump either: a reload's load runs on the dispatcher's
        // worker and its publish comes back to the main looper, which
        // `idle()` advances but a blocked test thread never would. Not a
        // sleep hiding a race — the loop makes no timing assumption, and
        // both facts it waits for are then asserted.
        //
        // Both matter. That the queued reload *ran* is the other half of the
        // assertion above: coalescing must defer the second event, not drop
        // it. And that nothing is still running is what keeps this test's
        // workers — the load, the publish, the metadata save the publish
        // launches — from writing shared preferences during whichever test
        // runs after `@After` clears them.
        var drained = false
        val deadline = System.nanoTime() + GATE_TIMEOUT_SECONDS * NANOS_PER_SECOND
        while (System.nanoTime() < deadline) {
            idle()
            // The reload job outlives its enumeration — the publish it posts
            // back to the main looper is part of it, and the metadata save is
            // launched from there — so an idle dispatcher alone would let the
            // loop break with that continuation still queued, which is the
            // leak this drain exists to prevent. Both conditions, in this
            // order: no reload in flight, and then nothing left dispatched.
            if (secondEnumerationStarted.count == 0L &&
                !viewModel.isReloadInFlight &&
                loadDispatcher.awaitIdle(DRAIN_POLL_MILLIS)
            ) {
                drained = true
                break
            }
        }
        assertTrue(
            "The queued reload must run, and leave nothing behind it, before the test returns",
            drained,
        )
    }

    @Test
    fun aFailedEnumerationKeepsThePreviousListInsteadOfCrashing() {
        // Same failure, seen from inside the load: the transaction comes back
        // truncated and `getActivityList` throws. It used to escape the load
        // coroutine and take the process down. Now the read is degraded, the
        // list the launcher already has stands, and the dock keeps its apps.
        seedApp("Mail", "com.example.mail")
        var failEnumeration = false
        val viewModel = newViewModel { launcherApps, user ->
            if (failEnumeration) {
                throw BadParcelableException("Failure retrieving array; only received 332 of 490")
            }
            launcherApps.getActivityList(null, user)
        }
        idle()
        val loadedNames = viewModel.uiState.value.filteredApps.map { it.name }
        assertTrue("Cold-start load surfaces the seeded app", loadedNames.contains("Mail"))

        failEnumeration = true
        viewModel.reloadInstalledAppsForTest()
        // Past the pause a degraded read takes before its bounded retry, so
        // the retries this triggers have run (and given up) by the assertions
        // rather than leaving a reload in flight underneath them.
        idleFor(RETRY_DRAIN_MILLIS)

        assertEquals(
            "A degraded reload publishes nothing and leaves the list it had",
            loadedNames,
            viewModel.uiState.value.filteredApps.map { it.name },
        )

        // ... and the launcher converges once the enumeration works again:
        // a degraded read must not strand the reload queue behind it.
        failEnumeration = false
        seedApp("Chat", "com.example.chat")
        viewModel.reloadInstalledAppsForTest()
        idleFor(RETRY_DRAIN_MILLIS)

        assertTrue(
            "The next healthy reload picks the list back up",
            viewModel.uiState.value.filteredApps.map { it.name }.containsAll(listOf("Mail", "Chat")),
        )
    }

    @Test
    fun aMappingReadRefusedMidProfileKeepsThePreviousListInsteadOfDroppingIt() {
        // The enumeration returning and the per-app reads succeeding are two
        // different things. A `SecurityException` from the enumeration means
        // the profile has left the caller's group and is skipped — but the
        // same exception out of a per-app read (a package gone mid-read, a
        // profile locking) says nothing of the kind, and treating it as a
        // dead profile published the surviving profiles as the whole truth,
        // dropping this one's apps as though they had been uninstalled.
        seedApp("Mail", "com.example.mail")
        var refuseMapping = false
        val viewModel = newViewModel { launcherApps, user ->
            if (refuseMapping) {
                // Enumeration itself returns; the refusal lands while the
                // list is being read, which is where the per-app label and
                // package-timestamp calls happen.
                object : AbstractList<LauncherActivityInfo>() {
                    override val size: Int get() = 1

                    override fun get(index: Int): LauncherActivityInfo =
                        throw SecurityException("user $user is not in the caller's profile group")
                }
            } else {
                launcherApps.getActivityList(null, user)
            }
        }
        idle()
        val loadedNames = viewModel.uiState.value.filteredApps.map { it.name }
        assertTrue("Cold-start load surfaces the seeded app", loadedNames.contains("Mail"))

        refuseMapping = true
        LauncherDebugLog.resetForTest()
        viewModel.reloadInstalledAppsForTest()
        idleFor(RETRY_DRAIN_MILLIS)

        // The list assertion alone can't tell the two readings apart here —
        // with one profile, a "dead profile" reading falls through to the
        // PackageManager fallback and republishes the same apps. What
        // distinguishes them is whether the read counted as degraded: on a
        // device where another profile *did* return apps, that is the
        // difference between keeping the list and publishing it without
        // this profile's apps.
        assertTrue(
            "A read refused partway through its per-app reads is degraded, not a dead profile: " +
                "${LauncherDebugLog.snapshot()}",
            LauncherDebugLog.snapshot().any { it.contains("scheduleReload degraded") },
        )
        assertEquals(
            "and the list the launcher already had stands",
            loadedNames,
            viewModel.uiState.value.filteredApps.map { it.name },
        )
    }

    @Test
    fun aDegradedReloadHoldsTheWorkDockSeedUntilAReadAnswers() {
        // The list on screen is still the last complete one, so a degraded
        // reload changes nothing about the personal dock. The work dock is
        // different: a read that just failed is exactly what a work profile
        // pausing or going away looks like from here, and its seed latches
        // — so enabling the dock inside the retry window must not seed it
        // from the pre-event list.
        seedApp("Chrome", "com.android.chrome")
        var failEnumeration = false
        val viewModel = newViewModel(
            workPackages = setOf("com.android.chrome"),
        ) { launcherApps, user ->
            if (failEnumeration) {
                throw BadParcelableException("Failure retrieving array; only received 332 of 490")
            }
            launcherApps.getActivityList(null, user)
        }
        idle()

        failEnumeration = true
        viewModel.reloadInstalledAppsForTest()
        // Inside the window: the degraded read has landed and its retry is
        // still waiting out its pause.
        idle()
        viewModel.setWorkDockEnabled(true)
        idle()

        assertTrue(
            "enabling the work dock while a read has just failed must not latch a seed",
            viewModel.uiState.value.workDockedApps.isEmpty(),
        )

        // And the seed is deferred, not lost: the retry reads cleanly and
        // takes it.
        failEnumeration = false
        idleFor(RETRY_DRAIN_MILLIS)

        assertTrue(
            "the next read that answers seeds the work dock",
            viewModel.uiState.value.workDockedApps.any { it.packageName == "com.android.chrome" },
        )
    }

    @Test
    fun aPackageEventLandingMidRetryGetsItsOwnAttempts() {
        // The retry budget belongs to the package event, not to a stretch of
        // wall clock. An event that arrives while an earlier event's retry is
        // still pending replaces that retry — so if it inherited the spent
        // budget it would get a single attempt and nothing queued behind it,
        // and the last event of a storm is exactly the one that has nothing
        // else coming to reload the list afterwards.
        seedApp("Mail", "com.example.mail")
        var failEnumeration = false
        var enumerations = 0
        val viewModel = newViewModel { launcherApps, user ->
            enumerations++
            if (failEnumeration) {
                throw BadParcelableException("Failure retrieving array; only received 332 of 490")
            }
            launcherApps.getActivityList(null, user)
        }
        idle()

        // The first event, run until it has spent all but the last of its
        // attempts: one immediate, one retry a pause later. The clock stops
        // inside the pause before the attempt that would exhaust the budget,
        // which is the interleaving that matters — an event arriving here is
        // the one that would inherit a spent budget.
        failEnumeration = true
        viewModel.reloadInstalledAppsForTest()
        idleFor(FIRST_RETRY_DRAIN_MILLIS)
        // A second event, landing inside that pause and replacing the
        // queued retry with itself.
        viewModel.reloadInstalledAppsForTest()
        val enumerationsBeforeSecondEvent = enumerations
        idleFor(RETRY_DRAIN_MILLIS)

        assertTrue(
            "The event that replaced a pending retry must get attempts of its own " +
                "(enumerations after it: ${enumerations - enumerationsBeforeSecondEvent})",
            enumerations - enumerationsBeforeSecondEvent > 1,
        )
    }

    @Test
    fun aColdStartWhoseEnumerationFailsStillPublishesAnAppList() {
        // Cold start cannot decline to publish the way a reload can — every
        // package event defers until it completes — so it retries, and its
        // last attempt falls back to PackageManager rather than leaving the
        // home screen with no apps on it.
        seedApp("Mail", "com.example.mail")
        val viewModel = newViewModel { _, _ ->
            throw BadParcelableException("Failure retrieving array; only received 332 of 490")
        }
        idleFor(RETRY_DRAIN_MILLIS)

        assertTrue(
            "Cold start completes even when every enumeration fails",
            viewModel.uiState.value.isFreshAppLoadComplete,
        )
        assertTrue(
            "The PackageManager fallback still surfaces installed apps",
            viewModel.uiState.value.filteredApps.map { it.name }.contains("Mail"),
        )
    }

    @Test
    fun aColdStartRecoveredEntirelyThroughPackageManagerCountsAsComplete() {
        // The personal profile is the one `PackageManager` can enumerate
        // authoritatively, so a read where it was the only failure and the
        // fallback succeeded has every profile accounted for. Calling that
        // degraded would cost what a degraded read gives up — the snapshot
        // write, the first-run dock seeding — and would merge in cached ids,
        // reviving apps uninstalled since the snapshot.
        // Chrome for the dock seeding (the prefill only seeds well-known
        // packages), Mail for the app list, which excludes docked apps.
        seedApp("Chrome", "com.android.chrome")
        seedApp("Mail", "com.example.mail")
        val viewModel = newViewModel { _, _ ->
            throw BadParcelableException("Failure retrieving array; only received 332 of 490")
        }
        idleFor(RETRY_DRAIN_MILLIS)

        assertTrue(
            "The fallback's list is published",
            viewModel.uiState.value.filteredApps.map { it.name }.contains("Mail"),
        )
        assertTrue(
            "A fully recovered read seeds the dock like any healthy one",
            viewModel.uiState.value.dockedApps.isNotEmpty(),
        )
    }

    @Test
    @Config(sdk = [36], shadows = [ThrowingPackageInfoShadow::class])
    fun aDegradedColdStartDoesNotLicenseTheIconTrim() {
        // Publishing and knowing what is installed are different things. A
        // degraded cold start has to publish — every package event defers
        // until it does — but the list it publishes is missing whatever
        // profile failed to read, and the icon snapshot and trim treat the
        // list as proof that anything absent from it is unwanted. Trimming
        // there would drop the restored icons of the profile that failed.
        seedApp("Mail", "com.example.mail")
        try {
            // A healthy launch first, so the degraded one below publishes a
            // *non-empty* list: an empty one defers the trim on its own and
            // would make the assertion below pass for the wrong reason.
            newViewModel()
            idle()

            ThrowingPackageInfoShadow.failPackageInfo = true
            val viewModel = newViewModel()
            idleFor(RETRY_DRAIN_MILLIS)
            assertTrue(
                "The degraded cold start must still publish",
                viewModel.uiState.value.isFreshAppLoadComplete,
            )
            assertTrue(
                "and it must publish the cached list, so emptiness is not what defers the trim",
                viewModel.uiState.value.filteredApps.isNotEmpty(),
            )

            LauncherDebugLog.resetForTest()
            viewModel.trimIconCacheToPriority()
            assertTrue(
                "A degraded load must not license the trim: ${LauncherDebugLog.snapshot()}",
                LauncherDebugLog.snapshot().any { it.contains("trimIconCacheToPriority deferred") },
            )

            // ... and the first healthy read makes it known, retrying what
            // was deferred.
            ThrowingPackageInfoShadow.failPackageInfo = false
            LauncherDebugLog.resetForTest()
            viewModel.reloadInstalledAppsForTest()
            idleFor(RETRY_DRAIN_MILLIS)
            assertTrue(
                "The healthy reload must retry the deferred trim: ${LauncherDebugLog.snapshot()}",
                LauncherDebugLog.snapshot().any { it.contains("trimIconCacheToPriority priority=") },
            )
        } finally {
            ThrowingPackageInfoShadow.failPackageInfo = false
        }
    }

    @Test
    @Config(sdk = [36], shadows = [ThrowingPackageInfoShadow::class])
    fun aFirstLaunchThatReadsNothingLeavesTheDockPrefillForTheNextHealthyLoad() {
        // A first run whose every read fails has no cache to fall back on, so
        // the list it publishes is empty. Seeding the dock from that and then
        // latching "prefilled" would leave the user with an empty dock for
        // good — the seed never runs again.
        // A package the one-time dock prefill actually seeds from.
        seedApp("Chrome", "com.android.chrome")
        try {
            ThrowingPackageInfoShadow.failPackageInfo = true
            val viewModel = newViewModel()
            idleFor(RETRY_DRAIN_MILLIS)
            assertTrue(
                "The degraded cold start must still complete",
                viewModel.uiState.value.isFreshAppLoadComplete,
            )
            assertTrue(
                "Nothing was read, so nothing may be docked yet",
                viewModel.uiState.value.dockedApps.isEmpty(),
            )

            // The binder recovers and a package event lands.
            ThrowingPackageInfoShadow.failPackageInfo = false
            viewModel.reloadInstalledAppsForTest()
            idleFor(RETRY_DRAIN_MILLIS)

            assertTrue(
                "The first healthy reload must seed the dock the degraded load could not",
                viewModel.uiState.value.dockedApps.isNotEmpty(),
            )
        } finally {
            ThrowingPackageInfoShadow.failPackageInfo = false
        }
    }

    @Test
    @Config(sdk = [36], shadows = [ThrowingPackageInfoShadow::class])
    fun aFallbackWhosePerAppReadsFailKeepsTheCachedListInsteadOfCrashing() {
        // The enumeration is not the only binder call in a load: reading each
        // result's package timestamp is one too, and it runs while the buffer
        // that failed the enumeration is still full. Guarding only the big
        // query left that read able to take the process down from inside the
        // recovery path — the one place that exists to survive this.
        seedApp("Mail", "com.example.mail")
        try {
            // A first, healthy launch, so there is a metadata cache to lose.
            val healthy = newViewModel()
            idle()
            assertTrue(
                "The first launch must read and cache the app list",
                healthy.uiState.value.filteredApps.map { it.name }.contains("Mail"),
            )

            ThrowingPackageInfoShadow.failPackageInfo = true
            val viewModel = newViewModel()
            idleFor(RETRY_DRAIN_MILLIS)

            assertTrue(
                "Cold start must complete rather than crash out of the load",
                viewModel.uiState.value.isFreshAppLoadComplete,
            )
            // And it must publish the cached list rather than the nothing it
            // managed to read: replacing it would empty the app list and the
            // dock, and the snapshot write would carry that into the next
            // launch as well.
            assertTrue(
                "A degraded cold start with nothing to publish keeps the cached apps",
                viewModel.uiState.value.filteredApps.map { it.name }.contains("Mail"),
            )
        } finally {
            ThrowingPackageInfoShadow.failPackageInfo = false
        }
    }

    private fun newViewModel(
        // Unconfined by default: the load IO runs synchronously on the calling
        // thread, so the test doesn't have to coordinate with a real
        // `Dispatchers.IO` worker, and `idle()` drains the Main-dispatched
        // continuations. The concurrency test overrides it, because what it
        // covers only exists across threads.
        ioDispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
        workPackages: Set<String> = emptySet(),
        enumerate: (LauncherApps, UserHandle) -> List<LauncherActivityInfo> =
            { launcherApps, user -> launcherApps.getActivityList(null, user) },
    ): LauncherViewModel = LauncherViewModel(
        app = ApplicationProvider.getApplicationContext(),
        workPackages = workPackages,
        ioDispatcher = ioDispatcher,
        enumerateLauncherActivities = enumerate,
    )

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

    /**
     * Runs work inline until [delegate] is swapped for a real dispatcher.
     * Lets one test have a deterministic cold start and genuinely concurrent
     * reloads afterwards, which is the only way to express a second
     * enumeration starting while the first is still outstanding.
     */
    private class SwitchableDispatcher : CoroutineDispatcher() {
        @Volatile
        var delegate: CoroutineDispatcher = Dispatchers.Unconfined

        private val lock = Object()
        private var outstanding = 0

        override fun isDispatchNeeded(context: CoroutineContext): Boolean =
            delegate.isDispatchNeeded(context)

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            synchronized(lock) { outstanding++ }
            delegate.dispatch(context) {
                try {
                    block.run()
                } finally {
                    synchronized(lock) {
                        outstanding--
                        lock.notifyAll()
                    }
                }
            }
        }

        /** Waits for every dispatched block to return. False on timeout. */
        fun awaitIdle(timeoutMillis: Long): Boolean {
            val deadline = System.nanoTime() + timeoutMillis * NANOS_PER_MILLI
            synchronized(lock) {
                while (outstanding > 0) {
                    val remaining = (deadline - System.nanoTime()) / NANOS_PER_MILLI
                    if (remaining <= 0) return false
                    lock.wait(remaining)
                }
            }
            return true
        }

        private companion object {
            const val NANOS_PER_MILLI = 1_000_000L
        }
    }

    private companion object {
        /** Generous: it bounds a regression's hang, it isn't a timing assumption. */
        const val GATE_TIMEOUT_SECONDS = 10L

        /**
         * How long a second enumeration is given to *not* start. Before the
         * fix it started within a millisecond of the second event, so this is
         * three orders of magnitude of headroom rather than a race.
         */
        const val NO_SECOND_ENUMERATION_MILLIS = 500L

        /**
         * Long enough to cover the pause before a degraded read's retry and
         * the retries themselves. Scheduler time, not wall-clock: Robolectric
         * advances the looper's own clock, so this costs the suite nothing.
         */
        const val RETRY_DRAIN_MILLIS = 2_000L

        /**
         * Past a degraded read's first retry pause and into the second, so
         * the sequence has made two attempts and is waiting on the third.
         */
        const val FIRST_RETRY_DRAIN_MILLIS = 400L

        const val NANOS_PER_SECOND = 1_000_000_000L

        /** How long each drain pass waits on the dispatcher before pumping the looper again. */
        const val DRAIN_POLL_MILLIS = 20L
    }

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** [idle], plus the scheduler's clock, for the cold-start load's retry pause. */
    private fun idleFor(millis: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(millis))
    }
}
