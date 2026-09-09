package app.typelauncher

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.BadParcelableException
import android.os.Looper
import android.os.UserHandle
import java.time.Duration
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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
            // The work dock persists both its entries and its once-ever
            // prefill latch, so a test that seeds it would otherwise decide
            // what a later one in the same worker sees.
            "work_docked_apps",
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
    fun aReloadSupersededMidBurstDoesNotPublishItsInterimList() {
        // An upgrade fires PACKAGE_REMOVED then PACKAGE_ADDED. The first
        // reload reads the tree between them — the app gone, its replacement
        // not yet installed — and the second event is already queued behind
        // it. Publishing that interim list is only safe if the queued read
        // answers; when it comes back degraded it keeps whatever is on
        // screen, so the interim list would stand until some unrelated event.
        // Canceling the predecessor used to make this impossible; serializing
        // reloads is what made it reachable.
        seedApp("Mail", "com.example.mail")
        val gateEnumerations = AtomicBoolean(false)
        val firstEnumerationStarted = CountDownLatch(1)
        val releaseFirstEnumeration = CountDownLatch(1)
        val failLater = AtomicBoolean(false)
        val loadDispatcher = SwitchableDispatcher()
        val viewModel = newViewModel(ioDispatcher = loadDispatcher) { _, _ ->
            if (gateEnumerations.get()) {
                if (firstEnumerationStarted.count > 0L) {
                    firstEnumerationStarted.countDown()
                    releaseFirstEnumeration.await(GATE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                } else if (failLater.get()) {
                    throw BadParcelableException("Failure retrieving array; only received 332 of 490")
                }
            }
            emptyList()
        }
        idleThroughRecovery()
        val beforeBurst = viewModel.uiState.value.filteredApps.map { it.name }
        assertTrue("Cold start must publish before the burst", beforeBurst.contains("Mail"))

        loadDispatcher.delegate = Dispatchers.Default
        gateEnumerations.set(true)
        // The removal half of the upgrade: the app is no longer installed, so
        // the interim read this reload is about to take will not contain it.
        removeApp("com.example.mail")
        viewModel.reloadInstalledAppsForTest()
        idleThroughRecovery()
        assertTrue(
            "The first reload's enumeration must reach the gate",
            firstEnumerationStarted.await(GATE_TIMEOUT_SECONDS, TimeUnit.SECONDS),
        )
        // The add half, queued behind it — and it will read degraded.
        viewModel.reloadInstalledAppsForTest()
        idleThroughRecovery()
        failLater.set(true)
        releaseFirstEnumeration.countDown()

        var drained = false
        val deadline = System.nanoTime() + GATE_TIMEOUT_SECONDS * NANOS_PER_SECOND
        while (System.nanoTime() < deadline) {
            idleThroughRecovery()
            if (!viewModel.isReloadInFlight && loadDispatcher.awaitIdle(DRAIN_POLL_MILLIS)) {
                drained = true
                break
            }
        }
        assertTrue("Both reloads must finish before the assertion", drained)

        assertEquals(
            "A superseded reload must not leave its interim list standing",
            beforeBurst,
            viewModel.uiState.value.filteredApps.map { it.name },
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
        idleThroughRecovery()
        val loadedNames = viewModel.uiState.value.filteredApps.map { it.name }
        assertTrue("Cold-start load surfaces the seeded app", loadedNames.contains("Mail"))

        failEnumeration = true
        viewModel.reloadInstalledAppsForTest()
        idleThroughRecovery()

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
        idleThroughRecovery()

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
        idle()

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
    fun aDegradedReloadLeavesTheWorkDockSeedWaitingForAReadThatAnswers() {
        // A degraded reload keeps the list on screen, and that list is the
        // right thing to keep. What it is not is a fresh reading of the work
        // profile's paused state — so enabling the dock after one must not
        // seed and latch it from a state the reload could not confirm.
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
        idleThroughRecovery()

        failEnumeration = true
        viewModel.reloadInstalledAppsForTest()
        idleThroughRecovery()
        viewModel.setWorkDockEnabled(true)
        idleThroughRecovery()

        assertTrue(
            "enabling the work dock after a degraded reload must not latch a seed",
            viewModel.uiState.value.workDockedApps.isEmpty(),
        )

        // Deferred, not lost: the next read that answers takes the seed.
        failEnumeration = false
        viewModel.reloadInstalledAppsForTest()
        idleThroughRecovery()

        assertTrue(
            "the next read that answers seeds the work dock",
            viewModel.uiState.value.workDockedApps.any { it.packageName == "com.android.chrome" },
        )
    }

    @Test
    fun aDegradedColdStartKeepsAQueuedIconPickInsteadOfDroppingIt() {
        // A pick made while cold start is still loading is queued and drained
        // against the fresh list. The drain reads "absent from the list" as
        // "uninstalled" and discards the request — true only of a read that
        // saw every profile. On a degraded one the app may simply belong to
        // the profile that failed, and discarding it loses the user's choice
        // for good, with the picker already closed behind them.
        seedApp("Mail", "com.example.mail")
        val enumerationStarted = CountDownLatch(1)
        val releaseEnumeration = CountDownLatch(1)
        val loadDispatcher = SwitchableDispatcher()
        // Real from the start, so the cold-start load itself can be held open
        // while the pick arrives — the window this drain exists for.
        loadDispatcher.delegate = Dispatchers.Default
        LauncherDebugLog.resetForTest()
        val viewModel = newViewModel(ioDispatcher = loadDispatcher) { _, _ ->
            enumerationStarted.countDown()
            releaseEnumeration.await(GATE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            throw BadParcelableException("Failure retrieving array; only received 332 of 490")
        }
        assertTrue(
            "The cold-start enumeration must reach the gate",
            enumerationStarted.await(GATE_TIMEOUT_SECONDS, TimeUnit.SECONDS),
        )
        // The picker returning mid-load, which is what queues the request.
        viewModel.setAppIconOverride("10:com.example.work/.Main", Uri.parse("content://example/icon.png"))
        releaseEnumeration.countDown()

        var drained = false
        val deadline = System.nanoTime() + GATE_TIMEOUT_SECONDS * NANOS_PER_SECOND
        while (System.nanoTime() < deadline) {
            idleThroughRecovery()
            if (viewModel.uiState.value.isFreshAppLoadComplete &&
                !viewModel.isReloadInFlight &&
                loadDispatcher.awaitIdle(DRAIN_POLL_MILLIS)
            ) {
                drained = true
                break
            }
        }
        assertTrue("Cold start must finish before the assertions", drained)

        assertTrue(
            "A degraded cold start must hold the queued pick: ${LauncherDebugLog.snapshot()}",
            LauncherDebugLog.snapshot().any { it.contains("drain deferred: inventory incomplete") },
        )
        assertFalse(
            "and must not discard it as uninstalled: ${LauncherDebugLog.snapshot()}",
            LauncherDebugLog.snapshot().any { it.contains("drained dropped") },
        )
    }

    @Test
    fun aPickArrivingAfterADegradedColdStartIsQueuedRatherThanDropped() {
        // The picker can return *after* cold start has published — a
        // process-death recreation with the picker still foreground. A
        // degraded cold start publishes, so "has cold start finished" is true
        // while the list is still missing the profile that failed; resolving
        // the pick against it would drop the user's choice as uninstalled.
        seedApp("Mail", "com.example.mail")
        LauncherDebugLog.resetForTest()
        val viewModel = newViewModel { _, _ ->
            throw BadParcelableException("Failure retrieving array; only received 332 of 490")
        }
        idleThroughRecovery()
        assertTrue(
            "The degraded cold start must still publish",
            viewModel.uiState.value.isFreshAppLoadComplete,
        )

        viewModel.setAppIconOverride("10:com.example.work/.Main", Uri.parse("content://example/icon.png"))
        idleThroughRecovery()

        assertTrue(
            "A pick after a degraded cold start must be queued: ${LauncherDebugLog.snapshot()}",
            LauncherDebugLog.snapshot().any { it.contains("deferred: id=") },
        )
        assertFalse(
            "and must not be dropped as uninstalled: ${LauncherDebugLog.snapshot()}",
            LauncherDebugLog.snapshot().any { it.contains("setAppIconOverride dropped") },
        )
    }

    @Test
    fun twoPicksQueuedBeforeAHealthyLoadBothSurvive() {
        // A degraded read leaves the queue open until some later event brings
        // a complete one, which is long enough for a second pick to arrive.
        // A single pending slot would drop the first choice the user already
        // confirmed, and they would have no way to know. Asserted at the
        // drain, not at the queueing: both calls log "deferred" either way,
        // so only what the drain does distinguishes one slot from two.
        seedApp("Mail", "com.example.mail")
        var failEnumeration = true
        val viewModel = newViewModel { launcherApps, user ->
            if (failEnumeration) {
                throw BadParcelableException("Failure retrieving array; only received 332 of 490")
            }
            launcherApps.getActivityList(null, user)
        }
        idleThroughRecovery()

        viewModel.setAppIconOverride("10:com.example.first/.Main", Uri.parse("content://example/one.png"))
        viewModel.setAppIconOverride("10:com.example.second/.Main", Uri.parse("content://example/two.png"))
        idleThroughRecovery()

        // The first read that answers drains the queue. Neither id resolves
        // against it, so each is reported — one line per pick that survived.
        LauncherDebugLog.resetForTest()
        failEnumeration = false
        viewModel.reloadInstalledAppsForTest()
        idleThroughRecovery()

        val drained = LauncherDebugLog.snapshot().filter { it.contains("drained dropped") }
        assertEquals(
            "Every queued pick must reach the drain, not just the last: $drained",
            2,
            drained.size,
        )
    }

    @Test
    fun aColdStartWhoseEnumerationFailsStillPublishesAnAppList() {
        // Cold start cannot decline to publish the way a reload can — every
        // package event defers until it completes — so a failed enumeration
        // falls through to the PackageManager fallback rather than leaving
        // the home screen with no apps on it. Before the guard, the
        // exception escaped the load coroutine and took the process down.
        seedApp("Mail", "com.example.mail")
        val viewModel = newViewModel { _, _ ->
            throw BadParcelableException("Failure retrieving array; only received 332 of 490")
        }
        idleThroughRecovery()

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
            idleThroughRecovery()

            ThrowingPackageInfoShadow.failPackageInfo = true
            val viewModel = newViewModel()
            idleThroughRecovery()
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
            idleThroughRecovery()
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
            idleThroughRecovery()
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
            idleThroughRecovery()

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
            idleThroughRecovery()
            assertTrue(
                "The first launch must read and cache the app list",
                healthy.uiState.value.filteredApps.map { it.name }.contains("Mail"),
            )

            ThrowingPackageInfoShadow.failPackageInfo = true
            val viewModel = newViewModel()
            idleThroughRecovery()

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

    @Test
    fun aDegradedReloadRetriesItselfAndPublishesWhatTheRetryReads() {
        // A degraded reload kept the list and waited for the *next* package
        // event. On a quiet device that can be hours away, so a burst that
        // failed the one reload it fired left the launcher stale until
        // somebody happened to install something. It tries again now.
        seedApp("Mail", "com.example.mail")
        val failNextEnumeration = AtomicBoolean(false)
        val viewModel = newViewModel { launcherApps, user ->
            if (failNextEnumeration.compareAndSet(true, false)) {
                throw BadParcelableException("Failure retrieving array; only received 332 of 490")
            }
            launcherApps.getActivityList(null, user)
        }
        idle()
        assertTrue(
            "Cold start must publish before the reload under test",
            viewModel.uiState.value.filteredApps.map { it.name }.contains("Mail"),
        )

        seedApp("Chat", "com.example.chat")
        failNextEnumeration.set(true)
        viewModel.reloadInstalledAppsForTest()
        idle()
        assertFalse(
            "The degraded read itself must still keep the previous list",
            viewModel.uiState.value.filteredApps.map { it.name }.contains("Chat"),
        )

        idleThroughRecovery()
        assertTrue(
            "The retry must publish the app the degraded read could not",
            viewModel.uiState.value.filteredApps.map { it.name }.contains("Chat"),
        )
    }

    @Test
    fun aDegradedReloadStopsRetryingOnceItsBudgetIsSpent() {
        // The bound matters as much as the retry: reloads are serial, so an
        // unbounded one would spin a failing enumeration against the buffer
        // it is failing on for as long as the failure lasts.
        seedApp("Mail", "com.example.mail")
        val failEnumeration = AtomicBoolean(false)
        val failedEnumerations = AtomicInteger(0)
        val viewModel = newViewModel { launcherApps, user ->
            if (failEnumeration.get()) {
                failedEnumerations.incrementAndGet()
                throw BadParcelableException("Failure retrieving array; only received 332 of 490")
            }
            launcherApps.getActivityList(null, user)
        }
        idle()

        failEnumeration.set(true)
        viewModel.reloadInstalledAppsForTest()
        idleThroughRecovery()

        assertEquals(
            "A reload retries within a bound rather than spinning on a failing transaction",
            // The read that came back degraded, plus its two retries.
            3,
            failedEnumerations.get(),
        )
        assertFalse(
            "Giving up must leave nothing queued behind it",
            viewModel.isReloadInFlight,
        )
        assertTrue(
            "The list the launcher already had stands",
            viewModel.uiState.value.filteredApps.map { it.name }.contains("Mail"),
        )
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

    /** The uninstall half of an upgrade: the activity stops resolving. */
    private fun removeApp(packageName: String) {
        @Suppress("DEPRECATION")
        shadowOf(context.packageManager).removeResolveInfosForIntent(launcherIntent, packageName)
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
        // Past a whole recovery — a degraded read plus two retries, so
        // 300ms + 600ms of backoff — with slack, so a test drains every
        // attempt rather than a subset and then asserts on a half-finished
        // recovery.
        const val RECOVERY_DRAIN_MILLIS = 2_000L
        const val GATE_TIMEOUT_SECONDS = 10L

        /**
         * How long a second enumeration is given to *not* start. Before the
         * fix it started within a millisecond of the second event, so this is
         * three orders of magnitude of headroom rather than a race.
         */
        const val NO_SECOND_ENUMERATION_MILLIS = 500L

        const val NANOS_PER_SECOND = 1_000_000_000L

        /** How long each drain pass waits on the dispatcher before pumping the looper again. */
        const val DRAIN_POLL_MILLIS = 20L
    }

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    /**
     * Drains the main looper, advancing past the backoff a degraded read now
     * schedules its retry behind. Robolectric fast-forwards virtual time
     * rather than sleeping, so this stays deterministic: a clock the test
     * advances, not a wait it hopes is long enough.
     */
    private fun idleThroughRecovery() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(RECOVERY_DRAIN_MILLIS))
    }
}
