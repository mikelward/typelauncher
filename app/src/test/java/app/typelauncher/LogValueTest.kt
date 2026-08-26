package app.typelauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The default-safe contract for what a debug-log line may mirror to Crashlytics.
 *
 * The value of these tests is the *default*: the previous filter had to be
 * taught each category it redacted and so failed open on every category nobody
 * anticipated. Here the interesting assertions are the ones about types nobody
 * wrote a rule for.
 */
class LogValueTest {
    private fun mirrored(format: String, vararg args: Any?) =
        formatLogMessage(format, args, redactSensitive = true)

    private fun onDevice(format: String, vararg args: Any?) =
        formatLogMessage(format, args, redactSensitive = false)

    @Test
    fun theOnDeviceCopyKeepsEveryArgumentInFull() {
        assertEquals(
            "launchApp package=com.example.mail work=false",
            onDevice("launchApp package=%s work=%s", "com.example.mail", false),
        )
    }

    @Test
    fun stringArgumentsAreWithheldFromTheMirror() {
        assertEquals(
            "launchApp package=<redacted> work=false",
            mirrored("launchApp package=%s work=%s", "com.example.mail", false),
        )
    }

    // The point of inverting the default: nobody taught this about contact
    // labels, query text or a component name, and it withholds them anyway.
    @Test
    fun aStringNobodyWroteARuleForIsStillWithheld() {
        assertEquals(
            "openThing label=<redacted> query=<redacted> component=<redacted>",
            mirrored(
                "openThing label=%s query=%s component=%s",
                "Alice Example",
                "ali",
                "com.example.maps/com.example.maps.Main",
            ),
        )
    }

    @Test
    fun numbersBooleansCharsAndEnumsAreCarried() {
        assertEquals(
            "state count=3 big=9000000000 on=true key=k mode=Dark ratio=1.5 nothing=null",
            mirrored(
                "state count=%s big=%s on=%s key=%s mode=%s ratio=%s nothing=%s",
                3,
                9_000_000_000L,
                true,
                'k',
                ThemeMode.Dark,
                1.5,
                null,
            ),
        )
    }

    @Test
    fun aSafeTagCarriesAStringThatIsFixedVocabulary() {
        assertEquals(
            "onCreate action=android.intent.action.MAIN",
            mirrored("onCreate action=%s", safe("android.intent.action.MAIN")),
        )
    }

    // The counterpart, and the reason the type rule is a default rather than a
    // verdict. A provider row id is a `Long`, so the type rule alone would
    // carry it — and it names one contact or one calendar entry.
    @Test
    fun aSensitiveTagWithholdsAnIdentifyingNumber() {
        assertEquals(
            "openContactResult contactId=<redacted> token=7",
            mirrored("openContactResult contactId=%s token=%s", sensitive(42L), 7),
        )
        assertEquals(
            "openContactResult contactId=42 token=7",
            onDevice("openContactResult contactId=%s token=%s", sensitive(42L), 7),
        )
    }

    @Test
    fun aSummaryChoosesItsOwnRenderingPerField() {
        val summary = LogSummary(full = "action=MAIN package=com.example.mail", mirrored = "action=MAIN package=<redacted>")

        assertEquals("intent=action=MAIN package=com.example.mail", onDevice("intent=%s", summary))
        assertEquals("intent=action=MAIN package=<redacted>", mirrored("intent=%s", summary))
    }

    @Test
    fun classificationFollowsTheType() {
        assertTrue(logArgumentMayLeaveDevice(1))
        assertTrue(logArgumentMayLeaveDevice(1L))
        assertTrue(logArgumentMayLeaveDevice(true))
        assertTrue(logArgumentMayLeaveDevice(null))
        assertTrue(logArgumentMayLeaveDevice(ThemeMode.Dark))
        assertFalse(logArgumentMayLeaveDevice("com.example.mail"))
        assertFalse(logArgumentMayLeaveDevice(listOf("com.example.mail")))
        assertFalse(logArgumentMayLeaveDevice(Any()))
    }

    // A wrong format string must never turn into a silent leak: a surplus
    // argument is appended, and it goes through the same redaction.
    @Test
    fun aSurplusArgumentIsSurfacedAndStillRedacted() {
        val line = mirrored("scheduleReload reason=%s", safe("packageAdded"), "com.example.mail")

        assertTrue(line.startsWith("scheduleReload reason=packageAdded"))
        assertTrue(line.contains("unplaced arg"))
        assertFalse("a formatting mistake must not become a leak", line.contains("com.example.mail"))
    }

    @Test
    fun aSurplusPlaceholderIsLeftVisibleRatherThanDropped() {
        assertEquals("a=1 b=%s", mirrored("a=%s b=%s", 1))
    }

    @Test
    fun aLiteralPercentSurvives() {
        assertEquals("battery=50% level=1", mirrored("battery=50%% level=%s", 1))
    }

    // The transition name is what explains the state snapshot beneath it. It is
    // the caller's format string, so it survives; the value interpolated into
    // it is a separate argument and is classified on its own.
    @Test
    fun aStateTransitionKeepsItsNameWhileItsValueIsClassified() {
        assertEquals(
            "setThemeMode=Dark destination=Home",
            mirrored("setThemeMode=%s %s", ThemeMode.Dark, LogSummary("destination=Home", "destination=Home")),
        )
        assertEquals(
            "renameApp=<redacted> destination=Home",
            mirrored("renameApp=%s %s", "Alice's Mail", LogSummary("destination=Home", "destination=Home")),
        )
    }

    @Test
    fun aFormatWithNoArgumentsIsUnchanged() {
        assertEquals("launchActiveApp opening system settings", mirrored("launchActiveApp opening system settings"))
    }

    // A zone id is coarse location, and the zone-change line records two of
    // them. They must reach the on-device log in full — naming both sides is
    // what makes a step in the log's own timestamps readable — and neither may
    // reach the Crashlytics mirror, which has no per-share review.
    @Test
    fun aTimeZoneChangeKeepsBothIdsOnDeviceAndWithholdsThemFromTheMirror() {
        assertEquals(
            "time zone Australia/Sydney -> Europe/London",
            onDevice("time zone %s -> %s", "Australia/Sydney", "Europe/London"),
        )
        assertEquals(
            "time zone <redacted> -> <redacted>",
            mirrored("time zone %s -> %s", "Australia/Sydney", "Europe/London"),
        )
    }
}
