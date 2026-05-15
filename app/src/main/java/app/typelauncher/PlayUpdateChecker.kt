package app.typelauncher

import android.app.Activity
import android.app.Application
import android.content.IntentSender
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
                updateInfo = null
                LauncherDebugLog.warning("Play update check failed", exception)
                onUnavailable()
            }
    }

    fun startUpdate(activity: Activity, requestCode: Int): Boolean {
        val info = updateInfo?.takeIf { it.isFlexibleUpdateAvailable() } ?: return false
        return try {
            registerInstallListener()
            appUpdateManager.startUpdateFlowForResult(
                info,
                activity,
                AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build(),
                requestCode,
            )
            true
        } catch (exception: IntentSender.SendIntentException) {
            LauncherDebugLog.warning("Play update flow failed to start", exception)
            false
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

}

private fun AppUpdateInfo.isFlexibleUpdateAvailable(): Boolean =
    updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE &&
        isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)

private fun AppUpdateInfo.isFlexibleUpdateInProgress(): Boolean =
    updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS &&
        installStatus() != InstallStatus.UNKNOWN &&
        installStatus() != InstallStatus.INSTALLED
