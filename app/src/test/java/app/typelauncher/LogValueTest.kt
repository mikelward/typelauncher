package app.typelauncher

import com.mikelward.androidlog.OFF_DEVICE_PLACEHOLDER
import com.mikelward.androidlog.formatLogMessage
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What *this launcher's* own log lines carry across the boundary.
 *
 * The type rule itself now lives in `mikelward/androidlog` and is tested
 * there, more thoroughly than here — the twelve cases this file used to hold
 * for `safe`, `sensitive`, surplus arguments, literal `%`, and each carried
 * primitive were duplicating that suite, and are gone rather than kept as a
 * second copy that can drift from it.
 *
 * What is left is the part the library cannot know: the launcher's own values,
 * in the shapes its own call sites use them. A rule the library gets right in
 * general is still worth pinning against the string that actually names one of
 * this user's apps.
 */
class LogValueTest {
    private fun offDevice(format: String, vararg args: Any?) =
        formatLogMessage(format, args, leavingDevice = true)

    private fun onDevice(format: String, vararg args: Any?) =
        formatLogMessage(format, args, leavingDevice = false)

    /**
     * The load-bearing one, and the reason the default runs this way: every
     * identifier this launcher knows about a person — a label, a query, a
     * component — arrives as a `String`, and none of them was ever taught to a
     * filter.
     */
    @Test
    fun aStringNobodyWroteARuleForIsStillWithheld() {
        assertEquals(
            "openThing label=$OFF_DEVICE_PLACEHOLDER query=$OFF_DEVICE_PLACEHOLDER " +
                "component=$OFF_DEVICE_PLACEHOLDER",
            offDevice(
                "openThing label=%s query=%s component=%s",
                "Alice Example",
                "ali",
                "com.example.maps/com.example.maps.Main",
            ),
        )
    }

    /** And the same line keeps all three on the device, which is the point. */
    @Test
    fun theSameLineKeepsEveryValueOnTheDevice() {
        assertEquals(
            "openThing label=Alice Example query=ali component=com.example.maps/com.example.maps.Main",
            onDevice(
                "openThing label=%s query=%s component=%s",
                "Alice Example",
                "ali",
                "com.example.maps/com.example.maps.Main",
            ),
        )
    }

    /**
     * A state transition's *name* is fixed vocabulary and survives; the value
     * beside it is classified on its own. Both halves matter — a log that
     * withheld the enum too would say nothing about what happened.
     */
    @Test
    fun aStateTransitionKeepsItsNameWhileItsValueIsClassified() {
        assertEquals(
            "setThemeMode=Dark",
            offDevice("setThemeMode=%s", ThemeMode.Dark),
        )
        assertEquals(
            "renameApp=$OFF_DEVICE_PLACEHOLDER",
            offDevice("renameApp=%s", "Alice's Mail"),
        )
    }

    /**
     * A zone id is coarse location, and the zone-change line records two of
     * them. They must reach the on-device log in full — naming both sides is
     * what makes a step in the log's own timestamps readable — and neither may
     * reach an automatic channel, which has no per-share review.
     */
    @Test
    fun aTimeZoneChangeKeepsBothIdsOnDeviceAndWithholdsThemOffIt() {
        assertEquals(
            "time zone Australia/Sydney -> Europe/London",
            onDevice("time zone %s -> %s", "Australia/Sydney", "Europe/London"),
        )
        assertEquals(
            "time zone $OFF_DEVICE_PLACEHOLDER -> $OFF_DEVICE_PLACEHOLDER",
            offDevice("time zone %s -> %s", "Australia/Sydney", "Europe/London"),
        )
    }
}
