package app.typelauncher

import android.app.Application
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.annotation.VisibleForTesting
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability

internal class PlayUpdateChecker @VisibleForTesting constructor(
    app: Application,
    private val appUpdateManager: AppUpdateManager = AppUpdateManagerFactory.create(app),
    // Debug builds hard-code this false, so a test that wants to exercise
    // checkForUpdate's real branch (with a fake AppUpdateManager) needs a way
    // past the gate. The default preserves production behavior exactly.
    private val checksEnabled: Boolean = BuildConfig.PLAY_UPDATE_CHECKS_ENABLED,
) {
    private var updateInfo: AppUpdateInfo? = null
    private var installListenerRegistered = false

    /**
     * Latched by [unregisterInstallListener] — i.e. by the owning activity's
     * `onDestroy`. Play's check is asynchronous, so a rotation while it is in
     * flight can run the cleanup *before* the answer arrives; registering a
     * listener after that point would leave one behind that nothing ever
     * unregisters, once per recreation.
     */
    private var destroyed = false
    private var onInstallStatus: ((Int) -> Unit)? = null
    private val installListener = InstallStateUpdatedListener { state ->
        onInstallStatus?.invoke(state.installStatus())
    }

    fun setInstallStatusListener(listener: (Int) -> Unit) {
        onInstallStatus = listener
    }

    fun checkForUpdate(
        onAvailable: (availableVersionCode: Int?, installStatus: Int) -> Unit,
        onUnavailable: () -> Unit,
        onCheckFailed: () -> Unit = onUnavailable,
    ) {
        if (!checksEnabled) {
            updateInfo = null
            onUnavailable()
            return
        }
        appUpdateManager.appUpdateInfo
            .addOnSuccessListener { info ->
                if (info.isFlexibleUpdateAvailable() || info.isFlexibleUpdateInProgress()) {
                    updateInfo = info
                    // After process death mid-download, `startUpdate` has not
                    // been called this process — register the listener now so
                    // we still see live PENDING / DOWNLOADING / DOWNLOADED
                    // transitions instead of relying only on onResume polls.
                    if (info.isFlexibleUpdateInProgress()) {
                        registerInstallListener()
                    }
                    onAvailable(info.availableVersionCode(), info.installStatus())
                } else {
                    updateInfo = null
                    onUnavailable()
                }
            }
            .addOnFailureListener { exception ->
                // A failed appUpdateInfo fetch (flaky network, Play transiently
                // unavailable) is inconclusive — it does NOT mean "no update".
                // Report it as a check failure, distinct from the success-but-
                // not-available branch above, so the ViewModel can preserve the
                // last known banner instead of wiping it. Critically, don't
                // null `updateInfo`: if a flexible update is already downloaded,
                // the Restart action and the in-flight install listener must
                // keep working across a transient recheck failure.
                LauncherDebugLog.warning("Play update check failed", exception)
                onCheckFailed()
            }
    }

    /**
     * Launches Play's confirmation sheet. Returns false when it could not be
     * opened at all, so the caller can fall back to the store listing rather
     * than leaving the banner stuck on "Updating…" with no listener event
     * coming to recover it.
     *
     * Takes an [ActivityResultLauncher] rather than an activity + request code
     * because that overload is deprecated; the modern one needs a registered
     * launcher regardless of whether the caller reacts to the eventual
     * result. A canceled sheet fires no install event, but MainActivity's
     * `onResume` — which always runs right after the launched sheet returns
     * — unconditionally rechecks anyway, so the launcher's own result
     * callback deliberately does nothing rather than triggering a second,
     * racing check of its own.
     */
    fun startUpdate(launcher: ActivityResultLauncher<IntentSenderRequest>): Boolean {
        val info = updateInfo?.takeIf { it.isFlexibleUpdateAvailable() } ?: return false
        return try {
            registerInstallListener()
            val launched = appUpdateManager.startUpdateFlowForResult(
                info,
                launcher,
                AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build(),
            )
            if (launched) {
                // An AppUpdateInfo is single-use: once handed to
                // startUpdateFlowForResult it can't drive a second flow, so a
                // canceled sheet followed by another Update tap would throw on
                // the consumed token. Drop it and let the next resume's
                // checkForUpdate fetch a fresh one.
                updateInfo = null
            }
            launched
        } catch (exception: RuntimeException) {
            LauncherDebugLog.warning("Play update flow failed to start", exception)
            false
        }
    }

    /**
     * The banner's Restart: install the downloaded update now. On success the
     * app is restarted by Play, so only the failure path returns here — and it
     * is a real one (the installer is busy, Play errors transiently), where the
     * tap visibly does nothing. Log it rather than discarding the task; the
     * banner is left untouched, so it keeps offering Restart and the tap is
     * simply retryable.
     */
    fun completeFlexibleUpdate() {
        LauncherDebugLog.event("Play update: completing flexible update on user request")
        appUpdateManager.completeUpdate()
            .addOnFailureListener { exception ->
                LauncherDebugLog.warning("Play update install failed to start", exception)
            }
    }

    @VisibleForTesting
    internal fun registerInstallListener() {
        if (destroyed || installListenerRegistered) return
        appUpdateManager.registerListener(installListener)
        installListenerRegistered = true
    }

    /**
     * Unregisters the install-state listener from the [AppUpdateManager].
     * MainActivity owns one checker per activity instance and creates a fresh
     * one on every recreation (rotation, theme change, process restore), so
     * without this the manager would accumulate a dead listener — capturing the
     * old activity's callback — on every recreation. Call from `onDestroy`.
     * Idempotent: a no-op if the listener was never registered.
     *
     * Also closes the checker for good: a check still in flight can land after
     * this runs, and registering then would slip a listener past the only
     * cleanup this checker gets.
     */
    fun unregisterInstallListener() {
        destroyed = true
        if (!installListenerRegistered) return
        appUpdateManager.unregisterListener(installListener)
        installListenerRegistered = false
    }
}

private fun AppUpdateInfo.isFlexibleUpdateAvailable(): Boolean =
    updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE &&
        isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)

private fun AppUpdateInfo.isFlexibleUpdateInProgress(): Boolean =
    updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS &&
        installStatus() != InstallStatus.UNKNOWN &&
        installStatus() != InstallStatus.INSTALLED
