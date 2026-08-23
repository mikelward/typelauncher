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
 * The key a dismissal is remembered under, so dismissing one update doesn't
 * silence the next one. Play normally reports the waiting build's version code;
 * when it doesn't, fall back to one past the running build — that suppresses
 * the banner for this install but not after the launcher itself has updated,
 * which is the same "until there's a genuinely newer build" promise. Both the
 * store and the read side must apply this same fallback, or a dismissal on an
 * unreported version code never matches the update it was meant to silence.
 */
internal fun playUpdateDismissalKey(versionCode: Int?, currentVersionCode: Int): Int =
    versionCode ?: (currentVersionCode + 1)

/**
 * Play's raw install status → the banner's state. [fallback] carries what we
 * were already showing, and it is what every uninformative status falls back
 * to — not just `UNKNOWN`, and not just when the fallback isn't `Starting`.
 * Play reports `UNKNOWN` before anything starts, in the gap after the user
 * accepts the sheet but before the download registers, and apparently in
 * other gaps too around an in-progress or finished download — so resetting
 * `Starting` on it (an earlier version did, to recover a declined sheet)
 * could just as easily snap a real, just-accepted download's banner back to
 * "Update" if a resume recheck's `UNKNOWN` answer landed before the install
 * listener's first `PENDING`/`DOWNLOADING` callback. `MainActivity`'s launch
 * result now recovers a declined sheet directly instead, so preserving the
 * fallback unconditionally is safe: nothing here is information, only its
 * absence.
 */
internal fun progressForInstallStatus(installStatus: Int, fallback: UpdateProgress): UpdateProgress =
    when (installStatus) {
        InstallStatus.PENDING -> UpdateProgress.Starting
        InstallStatus.DOWNLOADING -> UpdateProgress.Downloading
        InstallStatus.DOWNLOADED -> UpdateProgress.Downloaded
        // The user canceled the Play sheet, or the download failed: back to the
        // plain offer so they can try again.
        InstallStatus.CANCELED, InstallStatus.FAILED -> UpdateProgress.Idle
        // UNKNOWN and anything else Play might report.
        else -> fallback
    }
