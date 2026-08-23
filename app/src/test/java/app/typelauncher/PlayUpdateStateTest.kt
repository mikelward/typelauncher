package app.typelauncher

import com.google.android.play.core.install.model.InstallStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The update banner's pure state mapping: what Play's raw install status means
 * for the banner's copy, and what a dismissal is remembered under.
 */
class PlayUpdateStateTest {

    @Test
    fun `a download reports its progress through to the restart offer`() {
        assertEquals(
            UpdateProgress.Starting,
            progressForInstallStatus(InstallStatus.PENDING, fallback = UpdateProgress.Idle),
        )
        assertEquals(
            UpdateProgress.Downloading,
            progressForInstallStatus(InstallStatus.DOWNLOADING, fallback = UpdateProgress.Starting),
        )
        assertEquals(
            UpdateProgress.Downloaded,
            progressForInstallStatus(InstallStatus.DOWNLOADED, fallback = UpdateProgress.Downloading),
        )
    }

    @Test
    fun `a canceled or failed download returns the banner to its plain offer`() {
        // Otherwise the banner would sit on "Updating…" forever with no way to
        // start over after the user backs out of the Play sheet.
        assertEquals(
            UpdateProgress.Idle,
            progressForInstallStatus(InstallStatus.CANCELED, fallback = UpdateProgress.Starting),
        )
        assertEquals(
            UpdateProgress.Idle,
            progressForInstallStatus(InstallStatus.FAILED, fallback = UpdateProgress.Downloading),
        )
    }

    @Test
    fun `an unknown status reverts a just-started update but preserves an in-flight download`() {
        // Play reports UNKNOWN both before anything starts and in the gap
        // after the user accepts the sheet but before the download is
        // registered. A canceled sheet fires no listener event, so a resume
        // recheck landing on UNKNOWN with a Starting fallback reverts to Idle
        // so the user can tap Update again; an in-flight download fallback is
        // left alone rather than snapped back to "Update" under their finger.
        assertEquals(
            UpdateProgress.Idle,
            progressForInstallStatus(InstallStatus.UNKNOWN, fallback = UpdateProgress.Starting),
        )
        assertEquals(
            UpdateProgress.Idle,
            progressForInstallStatus(InstallStatus.UNKNOWN, fallback = UpdateProgress.Idle),
        )
        assertEquals(
            UpdateProgress.Downloading,
            progressForInstallStatus(InstallStatus.UNKNOWN, fallback = UpdateProgress.Downloading),
        )
    }

    @Test
    fun `an install in progress keeps whatever the banner was showing`() {
        // INSTALLING / INSTALLED arrive after the restart the banner asked for;
        // they carry nothing the banner needs to change to.
        assertEquals(
            UpdateProgress.Downloaded,
            progressForInstallStatus(InstallStatus.INSTALLING, fallback = UpdateProgress.Downloaded),
        )
    }

    @Test
    fun `a dismissal is remembered against the waiting build`() {
        assertEquals(101, playUpdateDismissalKey(versionCode = 101, currentVersionCode = 100))
    }

    @Test
    fun `a dismissal with no reported version lasts until the launcher itself updates`() {
        // Play doesn't always report a version code. Keying the dismissal one
        // past the running build silences this install without silencing the
        // banner on the build that follows it.
        assertEquals(101, playUpdateDismissalKey(versionCode = null, currentVersionCode = 100))
    }

    @Test
    fun `only an available and undismissed update prompts`() {
        assertFalse(PlayUpdateState.NotAvailable.shouldPrompt)
        assertTrue(PlayUpdateState.Available(versionCode = 101).shouldPrompt)
        assertFalse(PlayUpdateState.Available(versionCode = 101, isDismissed = true).shouldPrompt)
    }
}
