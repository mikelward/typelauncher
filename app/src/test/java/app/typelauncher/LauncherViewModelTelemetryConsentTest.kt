package app.typelauncher

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
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

/**
 * The ViewModel glue behind the Analytics consent card and the gear dot.
 *
 * The storage itself is covered in [DockSettingsStoreTelemetryTest] and the SDK
 * transition in [LauncherTelemetryStateTest]; this is about which of them the
 * UI is told to show, and that both answers retire the question rather than
 * only the affirmative one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LauncherViewModelTelemetryConsentTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun clearPrefs() {
        context.getSharedPreferences("dock_settings", Context.MODE_PRIVATE).edit().clear().commit()
        LauncherTelemetry.resetForTest()
    }

    @Test
    fun `the state's default agrees with what the launcher actually does`() {
        // Not a production path: `LauncherViewModel` supplies
        // `isTelemetryEnabled` explicitly, so nothing user-facing reads this
        // default (Codex, PR #687 — the first version of this test implied
        // otherwise). What reads it are the previews and the tests, and a
        // default that contradicts the product is how one of those quietly
        // becomes a wrong fixture.
        //
        // Both halves are asserted together on purpose: the default alone
        // would pass while disagreeing with the ViewModel, and the ViewModel
        // alone would pass while the default stayed a trap.
        assertFalse(LauncherUiState().isTelemetryEnabled)

        val viewModel = newViewModel()
        idle()

        assertFalse(viewModel.uiState.value.isTelemetryEnabled)
    }

    @Test
    fun `a fresh install is asked`() {
        val viewModel = newViewModel()
        idle()

        assertTrue(viewModel.uiState.value.isTelemetryConsentPending)
    }

    @Test
    fun `allowing retires the question and turns collection on`() {
        val viewModel = newViewModel()
        idle()

        viewModel.setTelemetryEnabled(true)
        idle()

        assertFalse(viewModel.uiState.value.isTelemetryConsentPending)
        assertTrue(viewModel.uiState.value.isTelemetryEnabled)
        assertTrue(DockSettingsStore(context).isTelemetryChoiceAnswered)
    }

    // The half that is easy to get wrong: declining is an answer too, so the
    // card and the dot go away rather than nagging someone who already said no.
    @Test
    fun `declining retires the question and leaves collection off`() {
        val viewModel = newViewModel()
        idle()

        viewModel.setTelemetryEnabled(false)
        idle()

        assertFalse(viewModel.uiState.value.isTelemetryConsentPending)
        assertFalse(viewModel.uiState.value.isTelemetryEnabled)
        assertTrue(DockSettingsStore(context).isTelemetryChoiceAnswered)
    }

    @Test
    fun `an answered question is not re-asked on the next launch`() {
        newViewModel().also { idle() }.setTelemetryEnabled(false)
        idle()

        val relaunched = newViewModel()
        idle()

        assertFalse(relaunched.uiState.value.isTelemetryConsentPending)
    }

    // Upgrades answer too. A stored `true` from before the consent card existed
    // is a preference, not an answer, and collection stays off until its user
    // gives one.
    @Test
    fun `an install that had the switch on still has to answer`() {
        context.getSharedPreferences("dock_settings", Context.MODE_PRIVATE)
            .edit().putBoolean("telemetry_enabled", true).commit()

        val viewModel = newViewModel()
        idle()

        assertTrue(viewModel.uiState.value.isTelemetryConsentPending)
        assertFalse(viewModel.uiState.value.isTelemetryEnabled)
        assertFalse(StoredTelemetryPreferences(DockSettingsStore(context)).isEnabled()!!)
    }

    // The switch and the card must not contradict each other: while the question
    // stands, collection is not happening, so the switch says off however the
    // raw preference reads.
    @Test
    fun `the switch reads off while the question is unanswered`() {
        val viewModel = newViewModel()
        idle()

        assertTrue(viewModel.uiState.value.isTelemetryConsentPending)
        assertFalse("but the switch reports what is actually happening", viewModel.uiState.value.isTelemetryEnabled)
    }

    // Allow then immediately decline: the decline wins, recording the durable
    // discard debt, so anything queued is deleted rather than uploaded on the
    // strength of an answer already withdrawn.
    @Test
    fun `allowing then declining leaves the pending report owed a discard`() {
        val viewModel = newViewModel()
        idle()

        viewModel.setTelemetryEnabled(true)
        viewModel.setTelemetryEnabled(false)
        idle()

        assertFalse(viewModel.uiState.value.isTelemetryEnabled)
        assertTrue(
            "the withdrawn answer owes a discard, so nothing is sent on it",
            LauncherTelemetry.isDeletionOwedForTest() ||
                DockSettingsStore(context).isReportDiscardOwed,
        )
        assertFalse(StoredTelemetryPreferences(DockSettingsStore(context)).isEnabled()!!)
    }

    // An unanswered question is a no, including at startup: the launch after a
    // pre-consent crash discards the report rather than holding it in case the
    // user later allows. So allowing never sends anything recorded before the
    // answer — which is what `PRIVACY.md` says.
    @Test
    fun `an unanswered launch discards rather than holding for a later yes`() {
        val sdk = RecordingSdk()
        LauncherTelemetry.useSdkForTest(sdk)

        LauncherTelemetry.applyCollectionPreference(
            StoredTelemetryPreferences(DockSettingsStore(context)),
        )

        assertEquals(
            "unanswered stops the SDKs and discards, exactly as a decline does",
            listOf("crashlytics=false", "performance=false", "delete"),
            sdk.calls,
        )
    }

    /** Records the transition's side effects in order. */
    private class RecordingSdk : TelemetrySdk {
        val calls = mutableListOf<String>()

        override fun setCrashlyticsCollectionEnabled(enabled: Boolean) {
            calls += "crashlytics=$enabled"
        }

        override fun setPerformanceCollectionEnabled(enabled: Boolean) {
            calls += "performance=$enabled"
        }

        override fun deleteUnsentReports() {
            calls += "delete"
        }
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
