package app.typelauncher

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.Window
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

private const val LAUNCHER_DEBUG_TAG = "TypeLauncherDebug"
internal const val LOG_BUFFER_MAX_ENTRIES = 300

/**
 * Cap for a single buffer entry, so one pathological stack trace can't dominate
 * the 300-entry buffer — and, with it, the shareable report. The report's own
 * budget is enforced separately by total characters in [BugReport] (strings
 * parcel as UTF-16, so the Binder limit is about total bytes, not entry count);
 * this is what keeps the count-based buffer bound meaningful.
 */
internal const val LOG_BUFFER_MAX_ENTRY_CHARS = 2_000

/**
 * How many `at …` frames per Throwable in the cause chain an entry keeps. The
 * deep tail of a stack trace is platform plumbing (Looper / Choreographer /
 * ActivityThread, Compose recomposition internals) that tells you nothing, and
 * in a release build every frame is obfuscated anyway — while a single 80-frame
 * dump used to evict a quarter of the buffer the report exists to carry. Keep
 * the throw site and its immediate callers, and say how many were dropped.
 */
private const val COMPACT_STACK_FRAMES = 8

internal object LauncherDebugLog {
    private val buffer = ArrayDeque<String>(LOG_BUFFER_MAX_ENTRIES)
    private val timestampFormat = ThreadLocal.withInitial {
        SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    }

    /**
     * A downstream consumer of every recorded line, in addition to the in-memory
     * buffer — e.g. [DebugFileSink], which mirrors the buffer to disk so it
     * survives the process ending. Registered by the [Application] at startup.
     */
    fun interface Sink {
        fun log(line: String)
    }

    // Copy-on-write so [record] can fan out without holding a lock across the
    // sink call (a sink must never be able to deadlock the buffer). Sinks are
    // added once at startup and effectively never removed, so writes are rare.
    private val sinks = CopyOnWriteArrayList<Sink>()

    fun addSink(sink: Sink) {
        sinks.addIfAbsent(sink)
    }

    fun removeSink(sink: Sink) {
        sinks.remove(sink)
    }

    /** Test-only: drops every registered sink so tests don't leak into each other. */
    internal fun clearSinksForTest() {
        sinks.clear()
    }

    /**
     * Records one line, and mirrors it into Crashlytics as a breadcrumb so the
     * most recent lines ride along on any future crash report.
     *
     * [format] is a hard-coded format string — a source literal, never a value
     * — with one `%s` per argument. That split is what keeps the mirror safe:
     * the literal cannot name anything of the user's, and each argument is
     * carried or withheld on its own by [logArgumentMayLeaveDevice]. The
     * on-device copy always renders every argument in full.
     *
     * Passing a *built* string as [format] would defeat this. There is no way
     * to enforce that in the type system, so it is a rule rather than a
     * guarantee: interpolate nothing, pass values as arguments.
     */
    fun event(format: String, vararg args: Any?) {
        val message = formatLogMessage(format, args, redactSensitive = false)
        record('D', message, throwable = null)
        Log.d(LAUNCHER_DEBUG_TAG, message)
        LauncherTelemetry.log(formatLogMessage(format, args, redactSensitive = true))
    }

    /**
     * Logcat-only diagnostic for high-frequency, per-icon detail (icon-tile
     * composition, dynamic-calendar resolution). Goes to `adb logcat -s
     * TypeLauncherDebug` like [event], but is deliberately kept *out* of the
     * bug-report ring buffer and Crashlytics breadcrumbs: at one line per app
     * icon per render size it would otherwise overflow the 300-entry buffer on
     * a cold start and evict the lifecycle/state context the bug report exists
     * to capture.
     */
    fun trace(message: String) {
        Log.d(LAUNCHER_DEBUG_TAG, message)
    }

    /**
     * A warning with no exception behind it. Same [format]-plus-arguments
     * contract as [event]; see there.
     *
     * To report a warning that *does* have an exception, call [failure] — the
     * throwable is a separate parameter on a separately-named function on
     * purpose. An overload taking it alongside `vararg args` would accept every
     * existing `warning(message, exception)` call unchanged and silently bind
     * the exception as a formatting argument, so it would render into the text
     * and never reach Crashlytics as a non-fatal. A distinct name makes the
     * compiler find those instead of leaving them to be noticed in production.
     */
    fun warning(format: String, vararg args: Any?) {
        // Belt and braces for the hazard above: nothing stops a future call
        // site passing a throwable as an argument, and losing a non-fatal that
        // way would be invisible. Route it properly and say that it happened.
        val throwable = args.filterIsInstance<Throwable>().firstOrNull()
        if (throwable != null) {
            failure(throwable, "$format [throwable passed to warning(); use failure()]", *args)
            return
        }
        val message = formatLogMessage(format, args, redactSensitive = false)
        record('W', message, throwable = null)
        Log.w(LAUNCHER_DEBUG_TAG, message)
        LauncherTelemetry.log("WARN " + formatLogMessage(format, args, redactSensitive = true))
    }

    /**
     * A warning with the exception that caused it, reported to Crashlytics as a
     * non-fatal as well as a breadcrumb.
     *
     * The throwable is redacted too, not just the breadcrumb: Crashlytics
     * uploads an exception's message verbatim, and a platform exception
     * routinely quotes what failed — `ActivityNotFoundException` carries the
     * intent, a `LauncherApps` `SecurityException` the package.
     */
    fun failure(throwable: Throwable, format: String, vararg args: Any?) {
        val message = formatLogMessage(format, args, redactSensitive = false)
        record('W', message, throwable)
        Log.w(LAUNCHER_DEBUG_TAG, message, throwable)
        LauncherTelemetry.log("WARN " + formatLogMessage(format, args, redactSensitive = true))
        LauncherTelemetry.recordException(throwable.redactedForTelemetry())
    }

    /**
     * Records an uncaught exception to the buffer (and logcat) for the crash
     * handler, **without** the Crashlytics non-fatal path. Crashlytics' own
     * chained uncaught handler reports the same throwable as a fatal, so routing
     * it through [warning]'s `recordException` too would double-count it as both
     * a non-fatal and a fatal event (Codex on PR #592). The buffer record still
     * fans out to the file sink, so the crash line is persisted for the report.
     */
    internal fun recordUncaught(message: String, throwable: Throwable) {
        record('W', message, throwable)
        Log.w(LAUNCHER_DEBUG_TAG, message, throwable)
    }

    fun activityCallback(activity: Activity, callback: String, intent: Intent? = activity.intent) {
        event(
            "%s taskId=%s finishing=%s changingConfig=%s intent=%s",
            // Every caller passes a lifecycle-method name literal, which is
            // fixed vocabulary rather than anything of the user's.
            safe(callback),
            activity.taskId,
            activity.isFinishing,
            activity.isChangingConfigurations,
            intent.debugSummary(),
        )
    }

    /** Returns the captured log lines, oldest first. */
    fun snapshot(): List<String> = synchronized(buffer) { buffer.toList() }

    /** Test-only: empties the in-memory ring buffer so tests start from a known state. */
    internal fun clearForTest() {
        synchronized(buffer) { buffer.clear() }
    }

    private fun record(level: Char, message: String, throwable: Throwable?) {
        val timestamp = timestampFormat.get().format(Date())
        val entry = if (throwable == null) {
            "$timestamp $level $LAUNCHER_DEBUG_TAG: $message"
        } else {
            "$timestamp $level $LAUNCHER_DEBUG_TAG: $message\n${compactStackTrace(throwable).trimEnd()}"
        }
        val bounded = if (entry.length > LOG_BUFFER_MAX_ENTRY_CHARS) {
            entry.take(LOG_BUFFER_MAX_ENTRY_CHARS) + "…(truncated)"
        } else {
            entry
        }
        synchronized(buffer) {
            if (buffer.size >= LOG_BUFFER_MAX_ENTRIES) buffer.removeFirst()
            buffer.addLast(bounded)
        }
        // Fan out to disk (and any other) sinks outside the buffer lock. Each is
        // best-effort: a sink that throws must never lose the log line for the
        // others or crash the caller (this runs on the app's own threads).
        sinks.forEach { runCatching { it.log(bounded) } }
    }

    /**
     * Formats [t] like [Throwable.printStackTrace] but keeps only the top
     * [maxFrames] frames per Throwable in the cause chain, summarising the rest
     * as `... N more` so a reader can tell frames were elided rather than
     * absent. Replaces `Log.getStackTraceString`, which dumps every frame — the
     * deep tail is platform plumbing, and one such entry used to crowd real
     * events out of the buffer.
     *
     * Cyclic cause chains (`a.cause = b; b.cause = a`) are guarded via an
     * identity set, as `printStackTrace` does, so a pathological Throwable can't
     * spin the calling thread. Suppressed exceptions surface as a one-line
     * summary per parent so `use { … }` close failures aren't silently lost;
     * their frames are dropped to keep the entry tight. Not backed by
     * `android.util.Log`, so it also works in plain JUnit tests. Visible for
     * tests.
     */
    internal fun compactStackTrace(t: Throwable, maxFrames: Int = COMPACT_STACK_FRAMES): String =
        buildString {
            val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
            var current: Throwable? = t
            var depth = 0
            while (current != null) {
                val cur = current
                if (!seen.add(cur)) {
                    append("\n\t[CIRCULAR REFERENCE: ").append(cur.javaClass.name).append(']')
                    break
                }
                if (depth > 0) append("\nCaused by: ")
                append(cur.javaClass.name)
                // A getMessage() override can throw, and this trace is rendered
                // while already logging a failure — keep the type and frames
                // rather than let a second throwable escape the logger.
                runCatching { cur.message }.getOrNull()?.let { append(": ").append(it) }
                val frames = cur.stackTrace
                val keep = minOf(maxFrames, frames.size)
                for (i in 0 until keep) append("\n\tat ").append(frames[i])
                if (frames.size > keep) append("\n\t... ").append(frames.size - keep).append(" more")
                for (suppressed in cur.suppressed) {
                    append("\n\tSuppressed: ").append(suppressed.javaClass.name)
                    runCatching { suppressed.message }.getOrNull()?.let { append(": ").append(it) }
                }
                current = cur.cause
                depth++
            }
        }
}

/**
 * A telemetry-safe stand-in for [this]: the exception types and stack traces of
 * the whole cause chain, and **no messages at all**.
 *
 * Crashlytics uploads an exception's message verbatim, and a platform exception
 * routinely quotes what failed — `ActivityNotFoundException` embeds the intent,
 * so a contact's number or address can ride in it; `LauncherApps` failures name
 * the package. Two rounds of trying to *sanitize* those messages each turned up
 * another payload the sanitizer didn't know about (URIs after packages), which
 * is the signal to stop sanitizing and start omitting: the type and the stack
 * trace are what a non-fatal is read for, and they cannot carry a payload.
 * The full message stays in the on-device log, which the user reviews before
 * sharing.
 *
 * Iterative and identity-guarded rather than recursive, because a cause chain
 * can be cyclic (`a.initCause(b); b.initCause(a)`) — the same guard
 * [compactStackTrace] already keeps. A `StackOverflowError` raised while
 * *preparing* a log line would take out the caller it was logging for.
 */
internal fun Throwable.redactedForTelemetry(): Throwable {
    val chain = mutableListOf<Throwable>()
    val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
    var current: Throwable? = this
    while (current != null && seen.add(current)) {
        chain += current
        current = current.cause
    }
    var redacted: Throwable? = null
    for (link in chain.asReversed()) {
        redacted = RedactedThrowable(link.javaClass.name, redacted).also { it.stackTrace = link.stackTrace }
    }
    return redacted ?: RedactedThrowable(javaClass.name, null).also { it.stackTrace = stackTrace }
}

/**
 * Stand-in produced by [redactedForTelemetry]. Its message is the original
 * exception's class name, so a Crashlytics report still says what was thrown
 * even though every report of this kind shares one type.
 */
internal class RedactedThrowable(message: String, cause: Throwable?) : RuntimeException(message, cause)

/**
 * Summarizes an intent for the log, deciding field by field what the
 * Crashlytics mirror may carry.
 *
 * Off device it keeps the action, the categories, the flags, the data URI's
 * scheme and the extras' key/type shape — fixed vocabulary that says what was
 * attempted and is the whole diagnostic value of a failed-launch report. It
 * withholds the component and the package, which name the user's installed
 * apps.
 *
 * Returning a [LogSummary] rather than a `String` is what makes that split
 * possible: as a plain string the summary would be withheld whole, and a
 * launch failure nobody can diagnose is a loss of its own.
 */
internal fun Intent?.debugSummary(): LogSummary {
    if (this == null) return LogSummary("null", "null")
    val categories = categories?.sorted().orEmpty()
    val flagsText = " flags=0x" + Integer.toHexString(flags)
    val dataSummary = data.redactedSummary()
    val extrasSummary = extras.debugSummary()

    fun head(actionText: String, categoriesText: String) =
        "action=$actionText categories=$categoriesText$flagsText"

    val fullHead = head(action ?: "null", categories.toString())
    val mirroredHead = head(
        mirroredVocabulary(action),
        categories.map { mirroredVocabulary(it) }.toString(),
    )
    val fullData = dataSummary
    val mirroredData = mirroredScheme(data?.scheme, dataSummary)

    return LogSummary(
        full = "$fullHead component=${component?.flattenToShortString() ?: "null"} " +
            "package=${`package` ?: "null"} data=$fullData extras=${extrasSummary.full}",
        mirrored = "$mirroredHead component=$REDACTED_PLACEHOLDER " +
            "package=$REDACTED_PLACEHOLDER data=$mirroredData extras=${extrasSummary.mirrored}",
    )
}

/**
 * `MainActivity` is exported, so any app can start it — through `onNewIntent`
 * too — with an action, categories, extra keys and data scheme of its choosing.
 * Those fields are fixed vocabulary only for the intents the launcher builds
 * itself; on a received one they are attacker-chosen strings that can carry an
 * installed package's name, or text of the caller's own.
 *
 * So the mirror carries such a field only when it **is** one of the constants
 * below. A namespace-prefix test is not enough: nothing stops a caller naming
 * its action `android.alice@example.com`, and a prefix check would wave that
 * through. Exact membership of a set fixed at compile time cannot be spoofed.
 *
 * The constants are referenced through the framework's own symbols rather than
 * spelled out, so a rename cannot silently turn a carried value into a withheld
 * one. Anything absent from the set is withheld, which is the safe direction:
 * an action this launcher never handles is one whose diagnostic value is low
 * and whose provenance is unknown. Add to the set when a value proves worth
 * seeing, rather than widening the test.
 */
private val KNOWN_INTENT_VOCABULARY: Set<String> = setOf(
    Intent.ACTION_MAIN,
    Intent.ACTION_VIEW,
    Intent.ACTION_SENDTO,
    Intent.ACTION_SEND,
    Intent.ACTION_DIAL,
    Intent.ACTION_CALL,
    Intent.ACTION_EDIT,
    Intent.ACTION_INSERT,
    Intent.ACTION_PICK,
    Intent.ACTION_CHOOSER,
    Intent.ACTION_CREATE_DOCUMENT,
    Intent.ACTION_OPEN_DOCUMENT,
    Intent.ACTION_GET_CONTENT,
    Intent.ACTION_SET_WALLPAPER,
    Intent.ACTION_DATE_CHANGED,
    Intent.ACTION_TIME_CHANGED,
    Intent.ACTION_TIMEZONE_CHANGED,
    Intent.ACTION_LOCALE_CHANGED,
    Intent.ACTION_MANAGED_PROFILE_ADDED,
    Intent.ACTION_MANAGED_PROFILE_REMOVED,
    Intent.ACTION_MANAGED_PROFILE_AVAILABLE,
    Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE,
    Intent.CATEGORY_DEFAULT,
    Intent.CATEGORY_HOME,
    Intent.CATEGORY_LAUNCHER,
    Intent.CATEGORY_BROWSABLE,
    Intent.CATEGORY_OPENABLE,
    Intent.CATEGORY_APP_CALENDAR,
    Intent.CATEGORY_APP_CONTACTS,
    Intent.EXTRA_TEXT,
    Intent.EXTRA_SUBJECT,
    Intent.EXTRA_TITLE,
    Intent.EXTRA_STREAM,
    Intent.EXTRA_INTENT,
    Intent.EXTRA_MIME_TYPES,
    AppWidgetManager.EXTRA_APPWIDGET_ID,
    AppWidgetManager.EXTRA_APPWIDGET_PROVIDER,
    AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE,
    AppWidgetManager.EXTRA_APPWIDGET_OPTIONS,
)

private fun mirroredVocabulary(value: String?): String = when {
    value == null -> "null"
    value in KNOWN_INTENT_VOCABULARY -> value
    else -> REDACTED_PLACEHOLDER
}

/**
 * Schemes the platform defines, which therefore say nothing about who is
 * holding the phone. A custom scheme is usually an app's own and names it, so
 * it is withheld — the URI's authority and payload are already gone either way
 * (see [redactedSummary]). Exact membership for the same reason as above.
 */
private val KNOWN_URI_SCHEMES = setOf(
    "content", "file", "http", "https", "tel", "mailto", "smsto", "sms",
    "geo", "market", "package", "android_resource", "intent", "voicemail",
)

private fun mirroredScheme(scheme: String?, fullSummary: String): String = when {
    scheme == null -> fullSummary
    scheme.lowercase() in KNOWN_URI_SCHEMES -> fullSummary
    else -> REDACTED_PLACEHOLDER
}

/**
 * A URI reduced to the one part that cannot name anything of the user's: its
 * scheme. Everything after that is payload, and for this launcher the payload
 * is routinely somebody's data — `smsto:` / `mailto:` carry a contact's phone
 * number or email address verbatim ([ContactActions]), `content://` carries a
 * contacts or calendar row id, `market://details?id=` carries a package name.
 *
 * The stakes are higher here than for the on-device log alone: every
 * [LauncherDebugLog.event] mirrors into Crashlytics as a breadcrumb, so an
 * un-redacted `dataString` uploads those values off the device on the next
 * crash — which both the *Privacy* rule in `AGENTS.md` and the published
 * `PRIVACY.md` ("these breadcrumbs do not include ... contact names") forbid.
 * Redacting at the one place that renders a URI for the log fixes every call
 * site at once, including ones added later.
 *
 * The authority goes too, though it reads like pure component identity. A
 * hierarchical URI's authority carries any userinfo — `https://alice:secret@
 * example.com/...` — and a `content://` authority names an installed app,
 * which is the inventory this redaction exists to keep off the device. An
 * allowlist of known-safe hosts would buy back "contacts or calendar" at the
 * cost of a list to maintain and get wrong.
 *
 * The scheme is the whole diagnostic value anyway: "the SENDTO for smsto:
 * found no handler" is the failure; which number it was addressed to is not.
 */
internal fun Uri?.redactedSummary(): String {
    if (this == null) return "null"
    val scheme = scheme ?: return "\u2026"
    val hasRedactedRemainder = if (isOpaque) {
        !schemeSpecificPart.isNullOrEmpty()
    } else {
        !authority.isNullOrEmpty() || !path.isNullOrEmpty() ||
            !query.isNullOrEmpty() || !fragment.isNullOrEmpty()
    }
    return scheme + ":" + if (hasRedactedRemainder) "\u2026" else ""
}

// Dumps each extra as `key=ValueClassName` for debug logs. The type-safe
// Bundle getters can't stand in here — the value type is unknown, which is
// exactly what the untyped `get` reports — so the deprecation is suppressed.
@Suppress("DEPRECATION")
/**
 * Extras rendered as `key=ValueTypeName` — never a value.
 *
 * The value *types* are safe: they are class names, fixed by whoever compiled
 * the sender. The **keys** are not, on an intent the launcher received rather
 * than built: an arbitrary caller picks them, so a key can name a package or
 * carry text of its own. The mirror therefore keeps a key only when it is
 * framework, AndroidX or this app's own vocabulary ([isKnownVocabulary]), and
 * withholds the rest while still showing how many extras there were and of
 * what types — which is what an extras-shape bug is actually read for.
 */
internal fun Bundle?.debugSummary(): LogSummary {
    fun render(mirror: Boolean) = try {
        this?.keySet()
            ?.sorted()
            ?.joinToString(prefix = "[", postfix = "]") { key ->
                val name = if (mirror) mirroredVocabulary(key) else key
                "$name=${get(key)?.javaClass?.simpleName ?: "null"}"
            }
            ?: "null"
    } catch (_: RuntimeException) {
        "[unreadable]"
    }
    return LogSummary(full = render(mirror = false), mirrored = render(mirror = true))
}

/**
 * Summarizes a key event, withholding **which key** from the mirror.
 *
 * `keyCode` is the one field here that is the user's rather than the device's.
 * This is a type-to-search launcher: keys pressed on the home screen are the
 * query, so a run of key codes in a crash report reconstructs what someone
 * typed — which is search history, and off limits by the *Privacy* rule.
 * It stays in the on-device log, where the timing and ordering of key events
 * is what makes an input-handling bug reproducible and where the user reviews
 * the report before sharing it.
 *
 * The rest — the action, the repeat count and the timestamps — is what a stuck
 * key or a swallowed event is actually diagnosed from, and none of it says
 * which key it was.
 */
internal fun KeyEvent?.debugSummary(): LogSummary {
    if (this == null) return LogSummary("null", "null")
    val tail = "repeat=$repeatCount downTime=$downTime eventTime=$eventTime"
    return LogSummary(
        full = "action=$action keyCode=$keyCode $tail",
        mirrored = "action=$action keyCode=$REDACTED_PLACEHOLDER $tail",
    )
}

internal fun Window.debugSummary(): LogSummary {
    val text = "attributes={type=${attributes.type}, flags=0x${attributes.flags.toString(16)}, " +
        "softInputMode=0x${attributes.softInputMode.toString(16)}} " +
        "decor=${decorView.width}x${decorView.height} visibility=${decorView.visibility}"
    // Window geometry and flags, all of it set by the launcher itself.
    return LogSummary(text, text)
}

internal fun LauncherUiState.debugSummary(): LogSummary {
    // Enums, counts and flags only. `queryLength` is deliberately the query's
    // length and never its text, which is search history.
    val text = "destination=$destination lastWidgetPage=$lastWidgetPage " +
        "settingsOpen=$isSettingsOpen queryLength=${query.length} " +
        "filtered=${filteredApps.size} docked=${dockedApps.size} widgets=${widgetIds.size} " +
        "widgetPages=${widgetPages.size} " +
        "addingWidget=$isAddingWidget loadingAvailableWidgets=$isLoadingAvailableWidgets " +
        "availableWidgets=${availableWidgets.size} " +
        "agenda=${agenda::class.simpleName} agendaEnabled=$isAgendaEnabled dockEnabled=$isDockEnabled " +
        "appListLayout=$appListLayout dockIconSizeDp=$dockIconSizeDp " +
        "sortOrder=$appListSortOrder keyboardReservation=${keyboardReservation.bottomPx}/${keyboardReservation.source} " +
        "loadingApps=$isLoadingApps " +
        "freshAppLoadComplete=$isFreshAppLoadComplete homeReady=$isHomeReady " +
        "recents=${recentApps.size} recentsOpen=$isRecentsOpen " +
        "hidden=${hiddenApps.size} " +
        "themeMode=$themeMode playUpdate=${playUpdate::class.simpleName}"
    return LogSummary(text, text)
}
