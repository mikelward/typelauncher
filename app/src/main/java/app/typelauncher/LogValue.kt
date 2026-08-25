package app.typelauncher

/**
 * Decides, per argument, what the Crashlytics mirror of a debug-log line is
 * allowed to carry.
 *
 * The rule this file exists to enforce is **default-safe**: a log call is a
 * hard-coded format string plus arguments, and an argument only leaves the
 * device if its *type* says it cannot name anything of the user's. The format
 * string is a source literal, so it is safe by construction. Everything the
 * launcher knows about a person — a package name, a contact or calendar row
 * id, a label, a query, a URI — arrives as a [String], and every [String]
 * argument is therefore redacted in the mirror unless the call site says
 * otherwise.
 *
 * This replaces a filter that worked the other way round. That one matched
 * each token against the set of packages the launcher had seen and redacted
 * the hits, which meant it was correct only for the categories it had been
 * taught and only for values it had already observed: a contact id, a calendar
 * row id, or a package it had never loaded went off the device untouched. Every
 * review round found another category it did not know about, which is the shape
 * of a rule that fails *open*. Inverting the default retires that whole class
 * of finding — a call site added next year is safe without anyone remembering
 * to teach a filter about it.
 *
 * The on-device log is unaffected and always renders every argument in full.
 * It is what the user reviews and consents to before sharing a bug report, and
 * keeping a package name or a row id in it is what makes a dock or launch bug
 * reproducible.
 */

/** Rendered in the mirror in place of an argument that may not leave the device. */
internal const val REDACTED_PLACEHOLDER = "<redacted>"

/**
 * Marks a value the mirror may carry in full even though its type would
 * otherwise withhold it — a string that is genuinely fixed vocabulary rather
 * than anything of the user's (an intent action, a lifecycle callback name, a
 * reason code, a URI reduced to its scheme).
 *
 * Reach for this only when the value cannot vary with who is holding the phone.
 * "It looks harmless" is not the test; "a different user would produce the same
 * value" is.
 */
@JvmInline
internal value class SafeLogValue(val value: Any?)

/**
 * Marks a value the mirror must withhold even though its type would allow it —
 * an identifying number, where the type rule alone would let it through.
 *
 * The counterpart to [SafeLogValue], and the reason the type rule is a default
 * rather than a verdict: safety is decided per value, not per category.
 */
@JvmInline
internal value class SensitiveLogValue(val value: Any?)

/**
 * A summary that has already decided, field by field, what may leave the device
 * — so a composite value is not forced to choose between going off device whole
 * and being withheld whole.
 *
 * [Intent.debugSummary] is the case this exists for. Its action, flags and a
 * URI's scheme are fixed vocabulary and are exactly what a failed-launch report
 * is read for; its component and package name the user's installed apps and
 * must not leave. As a plain [String] the whole summary would be withheld, and
 * a failure nobody can diagnose is its own kind of loss.
 */
internal class LogSummary(
    /** Rendered on device, in full. */
    val full: String,
    /** Rendered in the Crashlytics mirror, with the identifying fields removed. */
    val mirrored: String,
) {
    override fun toString(): String = full
}

/** See [SafeLogValue]. */
internal fun safe(value: Any?): SafeLogValue = SafeLogValue(value)

/** See [SensitiveLogValue]. */
internal fun sensitive(value: Any?): SensitiveLogValue = SensitiveLogValue(value)

/**
 * Whether [argument] may appear in full in the Crashlytics mirror.
 *
 * Numbers, booleans, chars and enum constants are safe: their whole range is
 * fixed by the code rather than by the device, so one user's value is another's.
 * That deliberately includes an identifying-looking `Int` such as a
 * `UserHandle.hashCode()` profile id — a device-local slot number, of which
 * there are a handful across every Android device in existence. Where a
 * specific number genuinely does identify someone, the call site wraps it in
 * [sensitive]; the default is not the verdict.
 *
 * Everything else is withheld. [String] is the case that matters and the reason
 * the default runs this way: it is the type every identifier in this app
 * arrives as.
 */
internal fun logArgumentMayLeaveDevice(argument: Any?): Boolean = when (argument) {
    is SafeLogValue -> true
    is SensitiveLogValue -> false
    // Carries both renderings and picks between them itself.
    is LogSummary -> true
    null -> true
    is Boolean -> true
    is Char -> true
    is Byte -> true
    is Short -> true
    is Int -> true
    is Long -> true
    is Float -> true
    is Double -> true
    is Enum<*> -> true
    else -> false
}

/** Unwraps a tag, if any, and renders the value the way the on-device log shows it. */
private fun renderLogArgument(argument: Any?, redactSensitive: Boolean): String = when (argument) {
    is SafeLogValue -> argument.value.toString()
    is SensitiveLogValue -> argument.value.toString()
    is LogSummary -> if (redactSensitive) argument.mirrored else argument.full
    else -> argument.toString()
}

/**
 * Substitutes [args] into [format], replacing each `%s` in order. `%%` renders a
 * literal `%`. When [redactSensitive] is set, any argument
 * [logArgumentMayLeaveDevice] withholds renders as [REDACTED_PLACEHOLDER]
 * instead — that is the only difference between the on-device rendering and the
 * mirrored one.
 *
 * Deliberately not `String.format`: this needs no locale (whose default would
 * be a live trap for `%d`), raises no `FormatException` from a stray `%` in a
 * message, and supports exactly the one placeholder the call sites use.
 *
 * A mismatch between placeholders and arguments is surfaced rather than
 * swallowed — a surplus `%s` is left in place and a surplus argument is
 * appended — so a wrong format string reads as obviously wrong in the log
 * instead of quietly dropping the value someone was trying to record. Surplus
 * arguments go through the same redaction as placed ones, so a mismatch can
 * never become a leak.
 */
internal fun formatLogMessage(
    format: String,
    args: Array<out Any?>,
    redactSensitive: Boolean,
): String {
    fun render(argument: Any?): String =
        if (redactSensitive && !logArgumentMayLeaveDevice(argument)) {
            REDACTED_PLACEHOLDER
        } else {
            renderLogArgument(argument, redactSensitive)
        }

    if (args.isEmpty() && '%' !in format) return format

    val out = StringBuilder(format.length + args.size * 8)
    var index = 0
    var next = 0
    while (index < format.length) {
        val char = format[index]
        if (char == '%' && index + 1 < format.length) {
            when (format[index + 1]) {
                's' -> {
                    if (next < args.size) out.append(render(args[next++])) else out.append("%s")
                    index += 2
                    continue
                }
                '%' -> {
                    out.append('%')
                    index += 2
                    continue
                }
            }
        }
        out.append(char)
        index++
    }
    while (next < args.size) {
        out.append(" [unplaced arg] ").append(render(args[next++]))
    }
    return out.toString()
}
