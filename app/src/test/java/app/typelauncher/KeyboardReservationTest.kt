package app.typelauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Direct coverage of [KeyboardReservation.appliesUnder] — the gate that makes
 * the cached reservation shrink-safe across configuration changes. Exercised
 * end-to-end by the Compose tests in [CarouselKeyboardTest], but pinned here
 * because the rule is small, testable in isolation, and central to the
 * shrink/reset behaviour.
 */
class KeyboardReservationTest {
    private val portraitConfig = KeyboardReservationConfig(
        orientation = 1,
        screenWidthDp = 411,
        screenHeightDp = 914,
        densityDpi = 420,
        navBottomPx = 0,
    )
    private val landscapeConfig = portraitConfig.copy(orientation = 2, screenWidthDp = 914, screenHeightDp = 411)

    @Test
    fun nullFingerprint_actsAsWildcard_forLegacyPersistence() {
        val legacy = KeyboardReservation(bottomPx = 900, configFingerprint = null)
        assertTrue(
            "Legacy persisted reservation (no fingerprint) must apply on the first cold start " +
                "after the upgrade, then be replaced by the next IME observation.",
            legacy.appliesUnder(portraitConfig),
        )
    }

    @Test
    fun matchingFingerprint_applies() {
        val reservation = KeyboardReservation(
            bottomPx = 900,
            configFingerprint = portraitConfig,
        )
        assertTrue(reservation.appliesUnder(portraitConfig))
    }

    @Test
    fun rotationMismatch_doesNotApply() {
        val reservation = KeyboardReservation(
            bottomPx = 900,
            configFingerprint = portraitConfig,
        )
        assertFalse(
            "A reservation captured in portrait must not seed Home in landscape — " +
                "the keyboard's pixel height changes.",
            reservation.appliesUnder(landscapeConfig),
        )
    }

    @Test
    fun navBottomChange_doesNotApply() {
        val reservation = KeyboardReservation(
            bottomPx = 900,
            configFingerprint = portraitConfig,
        )
        val gestureNav = portraitConfig.copy(navBottomPx = 0)
        val threeButtonNav = portraitConfig.copy(navBottomPx = 132)
        assertTrue(reservation.appliesUnder(gestureNav))
        assertFalse(
            "Switching from gesture nav to 3-button nav changes the IME's bottom inset " +
                "arithmetic; the cache must reset.",
            reservation.copy(configFingerprint = gestureNav).appliesUnder(threeButtonNav),
        )
    }

    @Test
    fun densityChange_doesNotApply() {
        val reservation = KeyboardReservation(
            bottomPx = 900,
            configFingerprint = portraitConfig,
        )
        val highDensity = portraitConfig.copy(densityDpi = 560)
        assertFalse(reservation.appliesUnder(highDensity))
    }

    @Test
    fun defaultSource_isAnimationTarget() {
        // Defaulting to AnimationTarget keeps the "untrusted until visibly confirmed"
        // contract: animation-target-only persistence cannot pull the entry cache down,
        // because the within-entry shrink path checks `source == VisibleIme`.
        assertEquals(KeyboardReservationSource.AnimationTarget, KeyboardReservation().source)
    }
}
