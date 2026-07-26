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
) {
    private var updateInfo: AppUpdateInfo? = null
    private var installListenerRegistered = false
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
        if (!BuildConfig.PLAY_UPDATE_CHECKS_ENABLED) {
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
     * Takes an [ActivityResultLauncher] rather than an activity + request code
     * so a *canceled* sheet is visible to the caller: backing out of it fires
     * no install event, and the next resume's check reports the same update
     * with an UNKNOWN status that deliberately preserves "Starting" — so
     * without that result the banner spins with no action reachable.
     */
    fun startUpdate(launcher: ActivityResultLauncher<IntentSenderRequest>): Boolean {
        val info = updateInfo?.takeIf { it.isFlexibleUpdateAvailable() } ?: return false
        return try {
            registerInstallListener()
            // The boolean return signals whether Play actually launched the
            // confirmation flow — propagate it so MainActivity can clear the
            // in-flight banner state on failure instead of leaving it stuck
            // on "Updating…" with no listener event coming to recover.
            val launched = appUpdateManager.startUpdateFlowForResult(
                info,
                launcher,
                AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build(),
            )
            if (launched) {
                // An AppUpdateInfo is single-use: once handed to
                // startUpdateFlowForResult it can't drive a second flow. If the
                // user cancels the Play sheet and taps Update again, reusing the
                // consumed token throws — so drop it here and let onResume's
                // checkForUpdate re-fetch a fresh one before the next attempt.
                updateInfo = null
            }
            launched
        } catch (exception: RuntimeException) {
            LauncherDebugLog.warning("Play update flow failed to start", exception)
            false
        }
    }

    fun completeFlexibleUpdate() {
        LauncherDebugLog.event("Play update: completing flexible update on user request")
        appUpdateManager.completeUpdate()
    }

    private fun registerInstallListener() {
        if (installListenerRegistered) return
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
     */
    fun unregisterInstallListener() {
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
