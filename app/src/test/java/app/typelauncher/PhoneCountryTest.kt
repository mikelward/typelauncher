package app.typelauncher

import android.content.Context
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow

/**
 * Unit coverage for the country-aware phone number helpers: the ISO →
 * calling-code table, the foreign-number prefix test, and the SIM country
 * lookup (per-subscription with the device-default fallback).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PhoneCountryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun mapsIsoCountriesToCallingCodes() {
        assertEquals("1", countryCallingCodeForIso("us"))
        assertEquals("1", countryCallingCodeForIso("ca"))
        assertEquals("44", countryCallingCodeForIso("gb"))
        assertEquals("61", countryCallingCodeForIso("au"))
        assertEquals("39", countryCallingCodeForIso("it"))
        assertEquals("91", countryCallingCodeForIso("in"))
        assertEquals("7", countryCallingCodeForIso("kz"))
        assertEquals("Case-insensitive, matching any telephony casing", "44", countryCallingCodeForIso("GB"))
    }

    @Test
    fun unknownOrBlankIsoHasNoCallingCode() {
        assertNull(countryCallingCodeForIso("zz"))
        assertNull(countryCallingCodeForIso(""))
        assertNull(countryCallingCodeForIso(null))
    }

    @Test
    fun internationalNumberOfAnotherCountryIsForeign() {
        assertTrue(isForeignPhoneNumber("+44 20 7946 0958", "61"))
        assertTrue(isForeignPhoneNumber("+61 412 345 678", "1"))
    }

    @Test
    fun internationalNumberOfTheLocalCountryIsLocal() {
        assertFalse(isForeignPhoneNumber("+61 412 345 678", "61"))
        assertFalse(isForeignPhoneNumber("+1 (555) 010-0199", "1"))
        // Formatting around the + is stripped before the prefix test.
        assertFalse(isForeignPhoneNumber("(+44) 20 7946 0958", "44"))
    }

    @Test
    fun nationalFormatNumberIsLocal() {
        // No + prefix: the number dials as-is on the local network.
        assertFalse(isForeignPhoneNumber("0412 345 678", "61"))
        assertFalse(isForeignPhoneNumber("555-0100", "1"))
    }

    @Test
    fun unknownLocalCountryMarksNothingForeign() {
        assertFalse(isForeignPhoneNumber("+44 20 7946 0958", null))
    }

    @Test
    fun noDefaultSimForTheChannelYieldsNoCallingCode() {
        // Dual-SIM "ask every time" leaves the channel's default subscription
        // invalid: there is no calling SIM to sort by, so the country of
        // whichever SIM is the device default must NOT leak in.
        shadowOf(context.getSystemService(TelephonyManager::class.java)).setSimCountryIso("us")

        assertNull(simCountryCallingCode(context, SubscriptionManager.INVALID_SUBSCRIPTION_ID))
    }

    @Test
    fun readsTheCallingCodeThroughTheSubscriptionsTelephonyManager() {
        // Robolectric's TelephonyManager shadow keeps one process-global SIM
        // country, so this can't model two SIMs with different countries (the
        // dual-SIM split is covered in ContactActionsTest by injection); it
        // covers the createForSubscriptionId path returning a usable country.
        val defaultShadow = shadowOf(context.getSystemService(TelephonyManager::class.java))
        val auTelephony = Shadow.newInstanceOf(TelephonyManager::class.java)
        shadowOf(auTelephony).setSimCountryIso("au")
        defaultShadow.setTelephonyManagerForSubscriptionId(2, auTelephony)

        assertEquals("61", simCountryCallingCode(context, 2))
    }

    @Test
    fun fallsBackToTheDefaultSimWhenTheSubscriptionHasNoCountry() {
        shadowOf(context.getSystemService(TelephonyManager::class.java)).setSimCountryIso("us")

        assertEquals("Unregistered subscription falls back to the default SIM", "1", simCountryCallingCode(context, 7))
    }

    @Test
    fun noSimCountryAnywhereYieldsNull() {
        // Valid subscription, but no SIM country to be found anywhere.
        shadowOf(context.getSystemService(TelephonyManager::class.java)).setSimCountryIso("")

        assertNull(simCountryCallingCode(context, 7))
    }
}
