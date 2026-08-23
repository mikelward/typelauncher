package app.typelauncher

import com.google.android.play.core.install.model.InstallStatus

internal sealed interface PlayUpdateState {
    val shouldPrompt: Boolean
    val showBadge: Boolean
        get() = shouldPrompt

    data object NotAvailable : PlayUpdateState {
        override val shouldPrompt: Boolean = false
    }

    data class Available(
        val versionCode: Int?,
        val isDismissed: Boolean = false,
        val progress: UpdateProgress = UpdateProgress.Idle,
    ) : PlayUpdateState {
        override val shouldPrompt: Boolean
            get() = !isDismissed
    }
}

internal sealed interface UpdateProgress {
    data object Idle : UpdateProgress
    data object Starting : UpdateProgress
    data object Downloading : UpdateProgress
    data object Downloaded : UpdateProgress
}

/**
 * Play's raw install status → the banner's state. [fallback] carries what we
 * were already showing: Play reports `UNKNOWN` both before anything starts and
 * in the gap after the user accepts the sheet but before the download is
 * registered. A resume recheck that lands there while the sheet was actually
 * canceled has no listener event to tell it so, so a `Starting` fallback is
 * reverted to `Idle` so the user can tap Update again; any other in-flight
 * fallback (`Downloading`, `Downloaded`) is preserved rather than snapped
 * back to "Update" under the user's finger.
 */
internal fun progressForInstallStatus(installStatus: Int, fallback: UpdateProgress): UpdateProgress =
    when (installStatus) {
        InstallStatus.PENDING -> UpdateProgress.Starting
        InstallStatus.DOWNLOADING -> UpdateProgress.Downloading
        InstallStatus.DOWNLOADED -> UpdateProgress.Downloaded
        // The user canceled the Play sheet, or the download failed: back to the
        // plain offer so they can try again.
        InstallStatus.CANCELED, InstallStatus.FAILED -> UpdateProgress.Idle
        InstallStatus.UNKNOWN -> if (fallback == UpdateProgress.Starting) UpdateProgress.Idle else fallback
        else -> fallback
    }
