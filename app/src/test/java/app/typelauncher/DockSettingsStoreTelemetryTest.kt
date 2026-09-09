package app.typelauncher

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers [DockSettingsStore.isTelemetryEnabled] and
 * [DockSettingsStore.isTelemetryChoiceAnswered] — the stored halves of the
 * Analytics choice.
 *
 * Both default to off, and both are needed. The preference records what the
 * user chose; the answered flag records *that* they chose, which is the only
 * thing separating "hasn't been asked" from "said no" now that the two store
 * the same value. Collection requires both, which is what makes an install
 * upgrading from a build with the Analytics switch answer the card like anyone
 * else — covered here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DockSettingsStoreTelemetryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun prefs() =
        context.getSharedPreferences("dock_settings", Context.MODE_PRIVATE)

    @After
    fun clearPrefs() {
        prefs().edit().clear().commit()
    }

    @Test
    fun defaultsToDisabledWhenNothingPersisted() {
        assertFalse(DockSettingsStore(context).isTelemetryEnabled)
    }

    @Test
    fun optingOutPersistsAcrossStoreInstances() {
        DockSettingsStore(context).isTelemetryEnabled = false

        assertFalse(DockSettingsStore(context).isTelemetryEnabled)
    }

    @Test
    fun optingBackInPersists() {
        DockSettingsStore(context).isTelemetryEnabled = false
        DockSettingsStore(context).isTelemetryEnabled = true

        assertTrue(DockSettingsStore(context).isTelemetryEnabled)
    }

    @Test
    fun theQuestionStartsUnanswered() {
        assertFalse(DockSettingsStore(context).isTelemetryChoiceAnswered)
    }

    // Saying no answers the question as much as saying yes does, and in the
    // same transaction as the opt-out itself — a death that left this unset
    // would re-ask someone who had already declined.
    @Test
    fun decliningAnswersTheQuestionAndPersists() {
        assertTrue(DockSettingsStore(context).recordTelemetryOptOut())

        val reloaded = DockSettingsStore(context)
        assertFalse(reloaded.isTelemetryEnabled)
        assertTrue(reloaded.isTelemetryChoiceAnswered)
        assertTrue("declining still owes the discard", reloaded.isReportDiscardOwed)
    }

    // Durable, like the opt-out — because the transition it precedes turns
    // Firebase's own persisted flags on, and a consent write that never landed
    // would leave the next launch unanswered while the SDKs auto-start enabled.
    @Test
    fun allowingAnswersTheQuestionAndPersists() {
        assertTrue(DockSettingsStore(context).recordTelemetryOptIn())

        val reloaded = DockSettingsStore(context)
        assertTrue(reloaded.isTelemetryEnabled)
        assertTrue(reloaded.isTelemetryChoiceAnswered)
    }

    // A first yes owes the discard: whatever was recorded while the question
    // stood was recorded without consent, and saying yes permits what comes
    // next, not what came before.
    @Test
    fun theFirstYesStillOwesTheDiscard() {
        DockSettingsStore(context).recordTelemetryOptIn()

        assertTrue(DockSettingsStore(context).isReportDiscardOwed)
    }

    // Later yeses do not, or turning Analytics back on would throw away reports
    // the user had already consented to.
    @Test
    fun aLaterYesOwesNothing() {
        val store = DockSettingsStore(context)
        store.recordTelemetryOptIn()
        store.setReportDiscardOwed(false)

        store.recordTelemetryOptIn()

        assertFalse(DockSettingsStore(context).isReportDiscardOwed)
    }

    // The case that matters on upgrade: an install that had the Analytics switch
    // on before the consent card existed carries a stored `true`, and it is
    // still not consent. Everyone answers, including them.
    @Test
    fun aPreferenceInheritedFromBeforeTheConsentCardIsNotAnAnswer() {
        prefs().edit().putBoolean("telemetry_enabled", true).commit()
        val store = DockSettingsStore(context)
        assertTrue("the inherited preference says yes", store.isTelemetryEnabled)
        assertFalse("but nobody answered the question", store.isTelemetryChoiceAnswered)

        assertFalse(
            "so collection stays off until they do",
            StoredTelemetryPreferences(store).isEnabled()!!,
        )
    }

    @Test
    fun answeringYesIsAYes() {
        val store = DockSettingsStore(context)
        store.recordTelemetryOptIn()

        assertTrue(StoredTelemetryPreferences(store).isEnabled()!!)
    }
}
