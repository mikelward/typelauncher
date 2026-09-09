package app.typelauncher

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.google.android.play.core.install.model.InstallStatus
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LauncherViewModelPlayUpdateTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun clearPrefs() {
        context.getSharedPreferences("play_update", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun availableUpdateStartsInIdleProgress() {
        val viewModel = newViewModel()
        viewModel.setPlayUpdateAvailable(101)
        idle()

        val state = viewModel.uiState.value.playUpdate as PlayUpdateState.Available
        assertTrue("banner should prompt when no in-flight progress", state.shouldPrompt)
        assertEquals(UpdateProgress.Idle, state.progress)
    }

    @Test
    fun setProgressStartingKeepsBannerVisibleWithInFlightState() {
        val viewModel = newViewModel()
        viewModel.setPlayUpdateAvailable(101)
        idle()

        viewModel.setPlayUpdateProgress(UpdateProgress.Starting)
        idle()

        val state = viewModel.uiState.value.playUpdate as PlayUpdateState.Available
        // The banner now stays visible after tap and reflects the in-flight
        // download state instead of session-dismissing.
        assertTrue(state.shouldPrompt)
        assertFalse(state.isDismissed)
        assertEquals(UpdateProgress.Starting, state.progress)
    }

    @Test
    fun setProgressDoesNotPersistDismissal() {
        val store = PlayUpdateStore(context)
        val before = store.dismissedVersionCode
        val viewModel = newViewModel()
        viewModel.setPlayUpdateAvailable(101)

        viewModel.setPlayUpdateProgress(UpdateProgress.Starting)
        idle()

        assertEquals(
            "in-flight progress must not write to the persisted dismissed version code",
            before,
            store.dismissedVersionCode,
        )
    }

    @Test
    fun setProgressIsNoopWhenNoUpdateAvailable() {
        val viewModel = newViewModel()
        viewModel.setPlayUpdateProgress(UpdateProgress.Starting)
        idle()

        assertEquals(PlayUpdateState.NotAvailable, viewModel.uiState.value.playUpdate)
    }

    @Test
    fun recheckPreservesInFlightProgressWhenInstallStatusUnknown() {
        val viewModel = newViewModel()
        viewModel.setPlayUpdateAvailable(101, InstallStatus.DOWNLOADING)
        idle()

        // onResume re-checks with the same version; Play often reports UNKNOWN
        // mid-download — we should preserve the in-flight state from the listener.
        viewModel.setPlayUpdateAvailable(101, InstallStatus.UNKNOWN)
        idle()

        val state = viewModel.uiState.value.playUpdate as PlayUpdateState.Available
        assertEquals(UpdateProgress.Downloading, state.progress)
    }

    @Test
    fun recheckDoesNotClobberAStartingDownloadOnUnknownStatus() {
        val viewModel = newViewModel()
        viewModel.setPlayUpdateAvailable(101)
        viewModel.setPlayUpdateProgress(UpdateProgress.Starting)
        idle()

        // A resume recheck can land on UNKNOWN both for a declined sheet and
        // for a genuinely just-accepted one whose download hasn't registered
        // yet — the signal alone can't tell them apart. MainActivity now
        // recovers a decline directly (onPlayUpdateLaunchResult), so this
        // must not revert a Starting fallback on its own; otherwise a
        // real, in-progress download's banner could snap back to "Update"
        // with its handle already consumed.
        viewModel.setPlayUpdateAvailable(101, InstallStatus.UNKNOWN)
        idle()

        val state = viewModel.uiState.value.playUpdate as PlayUpdateState.Available
        assertEquals(UpdateProgress.Starting, state.progress)
    }

    @Test
    fun recheckPromotesProgressFromDownloadingToDownloaded() {
        val viewModel = newViewModel()
        viewModel.setPlayUpdateAvailable(101, InstallStatus.DOWNLOADING)
        idle()

        viewModel.setPlayUpdateProgress(UpdateProgress.Downloaded)
        idle()

        val state = viewModel.uiState.value.playUpdate as PlayUpdateState.Available
        assertEquals(UpdateProgress.Downloaded, state.progress)
        assertTrue("banner stays visible to surface the Restart action", state.shouldPrompt)
    }

    /**
     * A failed/canceled download must not expose the tappable "Update" CTA
     * before the fresh check it triggers has repopulated the handle
     * `PlayUpdateChecker.startUpdate` already consumed — a tap before then
     * would find nothing to launch and fall back to the external Play
     * listing instead of retrying in-app (Codex on PR #648). The banner
     * stays in the busy `Starting` state; only the recheck's own
     * `setPlayUpdateAvailable`/`setPlayUpdateUnavailable` may land it on the
     * real `Idle`.
     */
    @Test
    fun installStatusFailedStaysBusyUntilTheRecheckLands() {
        val viewModel = newViewModel()
        viewModel.setPlayUpdateAvailable(101)
        viewModel.setPlayUpdateProgress(UpdateProgress.Downloading)
        idle()

        viewModel.onPlayUpdateInstallStatus(InstallStatus.FAILED)
        idle()

        val state = viewModel.uiState.value.playUpdate as PlayUpdateState.Available
        assertEquals(
            "premature Idle exposes an Update tap with no handle to launch",
            UpdateProgress.Starting,
            state.progress,
        )

        // The recheck this transition triggers is what actually lands on Idle.
        viewModel.setPlayUpdateUnavailable()
        idle()
        assertEquals(PlayUpdateState.NotAvailable, viewModel.uiState.value.playUpdate)
    }

    /**
     * A recovery check's own success must not inherit the `Starting`
     * placeholder it was parked on — Play typically answers a recovery
     * check (from a decline, or the failed-download path above) with the
     * same update still available but `UNKNOWN`, and `progressForInstallStatus`
     * ordinarily preserves whatever was showing under `UNKNOWN` to protect a
     * genuine accept in progress. No such accept can be racing a recovery
     * check (there is nothing offering Update to tap while the banner is
     * busy), so treating the placeholder as the thing to protect would
     * strand the banner on the spinner forever despite the checker having a
     * fresh, working handle (Codex on PR #648).
     */
    @Test
    fun aRecoveryCheckSuccessLandsOnIdleRatherThanInheritingTheBusyPlaceholder() {
        val viewModel = newViewModel()
        viewModel.setPlayUpdateAvailable(101)
        viewModel.beginPlayUpdateRecovery()
        idle()

        viewModel.setPlayUpdateAvailable(101, InstallStatus.UNKNOWN)
        idle()

        val state = viewModel.uiState.value.playUpdate as PlayUpdateState.Available
        assertEquals(
            "a resolved recovery check must expose the retry action, not stay parked on the spinner",
            UpdateProgress.Idle,
            state.progress,
        )
    }

    /**
     * The recovery placeholder must resolve from *whichever* answer arrives
     * first, not only one flagged as "the" recovery check — an unrelated
     * ambient check (`onResume`) can land while a recovery check is still
     * in flight, and it carries the same conclusive information. Tracking
     * "is this call the recovery" per call, rather than "is a recovery
     * still outstanding" as banner state, would let this ambient answer
     * apply without resolving the placeholder, stranding the spinner even
     * though a perfectly good answer just arrived (Codex on PR #648,
     * round 6).
     */
    @Test
    fun anAmbientAnswerThatLandsFirstStillResolvesAPendingRecovery() {
        val viewModel = newViewModel()
        viewModel.setPlayUpdateAvailable(101)
        viewModel.beginPlayUpdateRecovery()
        idle()

        // An ordinary ambient answer, not the recovery's own check — a
        // success always repopulates the checker's handle regardless of
        // which check delivered it, so it qualifies unconditionally.
        viewModel.setPlayUpdateAvailable(101, InstallStatus.UNKNOWN)
        idle()

        val state = viewModel.uiState.value.playUpdate as PlayUpdateState.Available
        assertEquals(
            "an unflagged answer must still resolve a pending recovery",
            UpdateProgress.Idle,
            state.progress,
        )
    }

    /**
     * A failure can resolve a pending recovery too — not only a success —
     * but only the recovery's *own* originating check's failure, per
     * `SPEC.md`'s update-banner paragraph: leaving the placeholder
     * untouched forever would strand the banner on the spinner with no
     * retry action, but resolving it from just any failure would expose a
     * tappable Update before the actual recovery has had a chance to
     * repopulate the handle (see the next test).
     */
    @Test
    fun aFailureFromTheRecoverysOwnCheckResolvesToIdle() {
        val viewModel = newViewModel()
        viewModel.setPlayUpdateAvailable(101)
        val recoveryToken = viewModel.beginPlayUpdateRecovery()
        idle()

        viewModel.setPlayUpdateCheckFailed(recoveryToken)
        idle()

        val state = viewModel.uiState.value.playUpdate as PlayUpdateState.Available
        assertEquals(UpdateProgress.Idle, state.progress)
    }

    /**
     * An unrelated ambient check's failure carries no information about
     * whether the recovery's own check will still succeed — the checker's
     * `updateInfo` is untouched by a failure, so the recovery might
     * repopulate it a moment later. Resolving from *any* overlapping
     * failure (an earlier version compared this activity's own request
     * generation against a value stored on the ViewModel, rather than
     * requiring the exact originating check) would expose a tappable
     * Update before that happens, and a tap would fall back to the
     * external Play listing with no handle to launch (Codex on PR #648,
     * round 8). Only the exact token [beginPlayUpdateRecovery] returned
     * may resolve it — an ambient check passes none at all.
     */
    @Test
    fun anUnrelatedAmbientFailureDoesNotResolveAPendingRecovery() {
        val viewModel = newViewModel()
        viewModel.setPlayUpdateAvailable(101)
        val recoveryToken = viewModel.beginPlayUpdateRecovery()
        idle()

        // An ordinary ambient check's failure — no token at all.
        viewModel.setPlayUpdateCheckFailed()
        idle()

        val stillBusy = viewModel.uiState.value.playUpdate as PlayUpdateState.Available
        assertEquals(
            "an unrelated check's failure must not resolve the recovery",
            UpdateProgress.Starting,
            stillBusy.progress,
        )

        // The recovery's own check answers next, and correctly resolves it.
        viewModel.setPlayUpdateCheckFailed(recoveryToken)
        idle()

        val resolved = viewModel.uiState.value.playUpdate as PlayUpdateState.Available
        assertEquals(UpdateProgress.Idle, resolved.progress)
    }

    /**
     * A second recovery superseding the first (e.g. two declines in quick
     * succession) must invalidate the first's token — the same pattern
     * already applied to Restart-attempt tracking — so a delayed failure
     * from the *first* recovery's own check can't resolve the *second*,
     * still-pending one.
     */
    @Test
    fun aFailureFromASupersededRecoveryDoesNotResolveTheCurrentOne() {
        val viewModel = newViewModel()
        viewModel.setPlayUpdateAvailable(101)
        val firstToken = viewModel.beginPlayUpdateRecovery()
        val secondToken = viewModel.beginPlayUpdateRecovery()
        idle()

        viewModel.setPlayUpdateCheckFailed(firstToken)
        idle()

        val stillBusy = viewModel.uiState.value.playUpdate as PlayUpdateState.Available
        assertEquals(
            "a superseded recovery's failure must not resolve the current one",
            UpdateProgress.Starting,
            stillBusy.progress,
        )

        viewModel.setPlayUpdateCheckFailed(secondToken)
        idle()

        val resolved = viewModel.uiState.value.playUpdate as PlayUpdateState.Available
        assertEquals(UpdateProgress.Idle, resolved.progress)
    }

    /**
     * `MainActivity.onDestroy()` calls this when a recovery it started is
     * still pending, since its own `PlayUpdateChecker` callbacks are being
     * torn down and can never resolve that token now. Without it, the
     * recreated activity's tokenless ambient checks could only resolve the
     * recovery on success, stranding the banner on the spinner if the
     * immediate next check merely fails (Codex on PR #648, round 10).
     */
    @Test
    fun anAbandonedRecoveryResolvesOnTheNextFailureEvenWithoutItsToken() {
        val viewModel = newViewModel()
        viewModel.setPlayUpdateAvailable(101)
        viewModel.beginPlayUpdateRecovery()
        idle()

        viewModel.abandonPendingPlayUpdateRecovery()
        // The recreated activity's own ambient check, carrying no token.
        viewModel.setPlayUpdateCheckFailed()
        idle()

        val state = viewModel.uiState.value.playUpdate as PlayUpdateState.Available
        assertEquals(UpdateProgress.Idle, state.progress)
    }

    /**
     * `PlayUpdateChecker.startUpdate` clears its cached `AppUpdateInfo` the
     * moment the sheet opens — single-use, per Play's own API — and nothing
     * else refreshes it until the next `checkForUpdate`. That normally
     * happens on the next `onResume`, but a download that fails or is
     * canceled while the user never left this screen is heard only through
     * the install listener, with no resume in between: without a recheck
     * triggered from there too, a repeat Update tap would find no handle and
     * silently fall back to the external Play listing instead of retrying
     * the in-app flow.
     */
    @Test
    fun aDownloadThatFailsSignalsThatAFreshCheckIsNeeded() {
        val viewModel = newViewModel()
        viewModel.setPlayUpdateAvailable(101)
        viewModel.setPlayUpdateProgress(UpdateProgress.Downloading)
        idle()

        val recoveryToken = viewModel.onPlayUpdateInstallStatus(InstallStatus.FAILED)

        assertTrue("a FAILED/CANCELED transition must trigger a fresh check", recoveryToken != null)
    }

    @Test
    fun anInProgressOrInconclusiveStatusDoesNotTriggerARedundantCheck() {
        val viewModel = newViewModel()
        viewModel.setPlayUpdateAvailable(101)
        idle()

        assertEquals(null, viewModel.onPlayUpdateInstallStatus(InstallStatus.DOWNLOADING))
        assertEquals(null, viewModel.onPlayUpdateInstallStatus(InstallStatus.UNKNOWN))
    }

    @Test
    fun newerVersionResetsInFlightProgress() {
        val viewModel = newViewModel()
        viewModel.setPlayUpdateAvailable(101, InstallStatus.DOWNLOADING)
        idle()

        viewModel.setPlayUpdateAvailable(102, InstallStatus.UNKNOWN)
        idle()

        val state = viewModel.uiState.value.playUpdate as PlayUpdateState.Available
        assertEquals(102, state.versionCode)
        assertEquals(
            "a newer available version must reset in-flight progress to Idle",
            UpdateProgress.Idle,
            state.progress,
        )
    }

    @Test
    fun unavailableClearsInFlightProgress() {
        val viewModel = newViewModel()
        viewModel.setPlayUpdateAvailable(101, InstallStatus.DOWNLOADING)
        viewModel.setPlayUpdateUnavailable()
        idle()

        viewModel.setPlayUpdateAvailable(101)
        idle()

        val state = viewModel.uiState.value.playUpdate as PlayUpdateState.Available
        assertEquals(UpdateProgress.Idle, state.progress)
    }

    @Test
    fun dismissPlayUpdateStillPersistsForCurrentVersion() {
        val store = PlayUpdateStore(context)
        val viewModel = newViewModel()
        viewModel.setPlayUpdateAvailable(101)
        idle()

        viewModel.dismissPlayUpdate()
        idle()

        assertEquals(101, store.dismissedVersionCode)
        val state = viewModel.uiState.value.playUpdate as PlayUpdateState.Available
        assertTrue("X dismiss should persist for the current version", state.isDismissed)
        assertFalse(state.shouldPrompt)
    }

    @Test
    fun dismissPlayUpdateWithNoReportedVersionSticksOnRecheck() {
        // Play doesn't always report a version code. The dismissal is then
        // keyed one past the running build (playUpdateDismissalKey), and the
        // read side must apply the exact same fallback — otherwise a resume
        // recheck that again sees a null version code would never match the
        // stored key and the banner would silently reappear.
        val viewModel = newViewModel()
        viewModel.setPlayUpdateAvailable(availableVersionCode = null)
        idle()

        viewModel.dismissPlayUpdate()
        idle()
        viewModel.setPlayUpdateAvailable(availableVersionCode = null)
        idle()

        val state = viewModel.uiState.value.playUpdate as PlayUpdateState.Available
        assertTrue("a re-dismissed unreported-version update must stay dismissed", state.isDismissed)
        assertFalse(state.shouldPrompt)
    }

    @Test
    fun checkFailedPreservesDownloadedBanner() {
        val viewModel = newViewModel()
        viewModel.setPlayUpdateAvailable(101, InstallStatus.DOWNLOADED)
        idle()

        // The update finished downloading and the banner shows Restart. A
        // resume-time recheck then fails (flaky network / Play unavailable).
        viewModel.setPlayUpdateCheckFailed()
        idle()

        // Before the fix the failure routed to setPlayUpdateUnavailable and
        // wiped the banner to NotAvailable, hiding Restart and stranding the
        // already-downloaded update. It must survive a failed check.
        val state = viewModel.uiState.value.playUpdate as PlayUpdateState.Available
        assertEquals(UpdateProgress.Downloaded, state.progress)
        assertTrue("Restart banner must survive a failed recheck", state.shouldPrompt)
    }

    @Test
    fun checkFailedPreservesInFlightDownloadProgress() {
        val viewModel = newViewModel()
        viewModel.setPlayUpdateAvailable(101, InstallStatus.DOWNLOADING)
        idle()

        viewModel.setPlayUpdateCheckFailed()
        idle()

        val state = viewModel.uiState.value.playUpdate as PlayUpdateState.Available
        assertEquals(UpdateProgress.Downloading, state.progress)
    }

    @Test
    fun checkFailedDoesNotClobberAStartingDownload() {
        val viewModel = newViewModel()
        viewModel.setPlayUpdateAvailable(101)
        // User taps Update: the banner shows the "Updating…" spinner while the
        // Play sheet opens, which already registers the install listener and
        // consumes the cached update handle.
        viewModel.setPlayUpdateProgress(UpdateProgress.Starting)
        idle()

        // A failed recheck landing before the listener's first
        // PENDING/DOWNLOADING callback must not reset a download that is
        // actually in flight — that would expose an Update button whose
        // handle is already gone. A declined sheet is now recovered directly
        // by MainActivity.onPlayUpdateLaunchResult instead of relying on
        // this path.
        viewModel.setPlayUpdateCheckFailed()
        idle()

        val state = viewModel.uiState.value.playUpdate as PlayUpdateState.Available
        assertEquals(
            "a failed recheck must not reset a download already in flight",
            UpdateProgress.Starting,
            state.progress,
        )
        assertTrue(state.shouldPrompt)
    }

    @Test
    fun successfulUnavailableStillClearsBanner() {
        // A check that *succeeds* and reports no update still clears the banner,
        // so the fix distinguishes "check failed" from "no update available".
        val viewModel = newViewModel()
        viewModel.setPlayUpdateAvailable(101, InstallStatus.DOWNLOADED)
        idle()

        viewModel.setPlayUpdateUnavailable()
        idle()

        assertEquals(PlayUpdateState.NotAvailable, viewModel.uiState.value.playUpdate)
    }

    /**
     * A stale "Couldn't restart" must not survive to describe an update it
     * wasn't about. Once the failure is recorded against a `Downloaded`
     * banner, every way progress can leave `Downloaded` for a reason other
     * than another Restart tap clears it too (Codex on PR #648).
     */
    @Test
    fun restartFailureClearsWhenTheUpdateBecomesUnavailable() {
        val viewModel = newViewModel()
        viewModel.setPlayUpdateAvailable(101, InstallStatus.DOWNLOADED)
        val attempt = viewModel.beginPlayUpdateRestartAttempt()
        viewModel.setPlayUpdateRestartFailed(attempt, 101)
        idle()
        assertTrue(viewModel.uiState.value.playUpdateRestartFailed)

        viewModel.setPlayUpdateUnavailable()
        idle()

        assertFalse(viewModel.uiState.value.playUpdateRestartFailed)
    }

    @Test
    fun restartFailureClearsWhenANewerVersionIsOffered() {
        val viewModel = newViewModel()
        viewModel.setPlayUpdateAvailable(101, InstallStatus.DOWNLOADED)
        val attempt = viewModel.beginPlayUpdateRestartAttempt()
        viewModel.setPlayUpdateRestartFailed(attempt, 101)
        idle()

        viewModel.setPlayUpdateAvailable(102, InstallStatus.UNKNOWN)
        idle()

        assertFalse(
            "the new version's banner must not show the old version's restart error",
            viewModel.uiState.value.playUpdateRestartFailed,
        )
    }

    /**
     * The version-swap branch must clear the flag independently of the
     * *resulting* progress, not by folding the check into "is progress
     * Downloaded" — a staged rollout can report the replacement version as
     * already `DOWNLOADED` on its very first answer, which previously left
     * `progress == Downloaded` true on both sides of the swap and let the
     * old version's failure survive onto the new one (Codex on PR #648).
     */
    @Test
    fun restartFailureClearsWhenANewerVersionArrivesAlreadyDownloaded() {
        val viewModel = newViewModel()
        viewModel.setPlayUpdateAvailable(101, InstallStatus.DOWNLOADED)
        val attempt = viewModel.beginPlayUpdateRestartAttempt()
        viewModel.setPlayUpdateRestartFailed(attempt, 101)
        idle()

        viewModel.setPlayUpdateAvailable(102, InstallStatus.DOWNLOADED)
        idle()

        assertFalse(
            "a version swap must clear the flag even when the new version is also Downloaded",
            viewModel.uiState.value.playUpdateRestartFailed,
        )
    }

    @Test
    fun restartFailureClearsWhenProgressLeavesDownloaded() {
        val viewModel = newViewModel()
        viewModel.setPlayUpdateAvailable(101, InstallStatus.DOWNLOADED)
        val attempt = viewModel.beginPlayUpdateRestartAttempt()
        viewModel.setPlayUpdateRestartFailed(attempt, 101)
        idle()

        viewModel.setPlayUpdateProgress(UpdateProgress.Idle)
        idle()

        assertFalse(viewModel.uiState.value.playUpdateRestartFailed)
    }

    @Test
    fun restartFailurePersistsWhileStillDownloaded() {
        // A no-op state refresh while still Downloaded must not clear a
        // restart failure the user hasn't retried yet.
        val viewModel = newViewModel()
        viewModel.setPlayUpdateAvailable(101, InstallStatus.DOWNLOADED)
        val attempt = viewModel.beginPlayUpdateRestartAttempt()
        viewModel.setPlayUpdateRestartFailed(attempt, 101)
        idle()

        viewModel.setPlayUpdateAvailable(101, InstallStatus.DOWNLOADED)
        idle()

        assertTrue(viewModel.uiState.value.playUpdateRestartFailed)
    }

    /**
     * `completeFlexibleUpdate`'s failure callback is asynchronous, so by the
     * time it lands the banner may already show a different build — a
     * recheck arriving between the tap and the failure. The failure must be
     * ignored rather than shown against whatever the banner has moved on to
     * (Codex on Simmo PR #238, the identical bug): clearing the flag on the
     * way *in* isn't enough, since nothing about that clear stops a
     * *later*-arriving callback for the old attempt from repopulating it.
     */
    @Test
    fun aRestartFailureForAVersionNoLongerShowingIsIgnored() {
        val viewModel = newViewModel()
        viewModel.setPlayUpdateAvailable(101, InstallStatus.DOWNLOADED)
        idle()

        // The recheck this simulates raced ahead of the async completeUpdate
        // failure below, swapping the banner to version 102 first.
        viewModel.setPlayUpdateAvailable(102, InstallStatus.DOWNLOADED)
        idle()
        val attempt = viewModel.beginPlayUpdateRestartAttempt()
        viewModel.setPlayUpdateRestartFailed(attempt, 101)
        idle()

        assertFalse(
            "a stale failure for version 101 must not show against 102's banner",
            viewModel.uiState.value.playUpdateRestartFailed,
        )
    }

    @Test
    fun aRestartFailureForAVersionThatLeftDownloadedIsIgnored() {
        val viewModel = newViewModel()
        viewModel.setPlayUpdateAvailable(101, InstallStatus.DOWNLOADED)
        idle()

        viewModel.setPlayUpdateProgress(UpdateProgress.Idle)
        idle()
        val attempt = viewModel.beginPlayUpdateRestartAttempt()
        viewModel.setPlayUpdateRestartFailed(attempt, 101)
        idle()

        assertFalse(
            "a stale failure must not resurrect the error once progress moved on",
            viewModel.uiState.value.playUpdateRestartFailed,
        )
    }

    /**
     * A second Restart tap for the *same* build, before the first tap's
     * async failure lands, must invalidate the first attempt — the
     * version+progress check alone can't tell the two attempts apart, since
     * neither changes between them (Codex on Simmo PR #238).
     */
    @Test
    fun aFailureFromASupersededRestartAttemptIsIgnored() {
        val viewModel = newViewModel()
        viewModel.setPlayUpdateAvailable(101, InstallStatus.DOWNLOADED)
        idle()

        val firstAttempt = viewModel.beginPlayUpdateRestartAttempt()
        viewModel.beginPlayUpdateRestartAttempt() // The user taps Restart again before the first attempt's callback lands.
        viewModel.setPlayUpdateRestartFailed(firstAttempt, 101)
        idle()

        assertFalse(
            "a superseded attempt's failure must not redisplay against the newer, still-in-flight attempt",
            viewModel.uiState.value.playUpdateRestartFailed,
        )
    }

    /**
     * If the same version leaves `Downloaded` and later re-enters it (a
     * failed install recovered by a fresh download) while the *original*
     * Restart attempt's callback is still in flight, that stale callback
     * must not pass — the version and progress checks alone can't tell it
     * apart from a fresh attempt against the re-downloaded build (Codex on
     * PR #648, round 9).
     */
    @Test
    fun aFailureFromAnAttemptWhoseDownloadRestartedIsIgnored() {
        val viewModel = newViewModel()
        viewModel.setPlayUpdateAvailable(101, InstallStatus.DOWNLOADED)
        idle()
        val staleAttempt = viewModel.beginPlayUpdateRestartAttempt()

        // The install failed and Play re-downloads the same version.
        viewModel.setPlayUpdateProgress(UpdateProgress.Downloading)
        viewModel.setPlayUpdateAvailable(101, InstallStatus.DOWNLOADED)
        idle()

        // The original (now stale) attempt's async failure finally lands.
        viewModel.setPlayUpdateRestartFailed(staleAttempt, 101)
        idle()

        assertFalse(
            "a stale attempt from before the redownload must not redisplay against it",
            viewModel.uiState.value.playUpdateRestartFailed,
        )
    }

    private fun newViewModel(): LauncherViewModel = LauncherViewModel(
        app = ApplicationProvider.getApplicationContext(),
        workPackages = emptySet(),
        ioDispatcher = Dispatchers.Unconfined,
    )

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
    }
}
