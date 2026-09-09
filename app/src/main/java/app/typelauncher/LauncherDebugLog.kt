package app.typelauncher

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.Window
import com.mikelward.androidlog.DebugLog
import com.mikelward.androidlog.OFF_DEVICE_PLACEHOLDER
import com.mikelward.androidlog.safe
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal const val LAUNCHER_DEBUG_TAG = "TypeLauncherDebug"

/**
 * The timestamp format for the bug report's own header:
 * `2026-08-26T14:03:11.482+10:00`.
 *
 * The log's lines are stamped by [DebugLog], which writes local time without
 * the year and emits the UTC offset as its own marker entry rather than on
 * every line. This one keeps the full form because the header is written once
 * and is what a reader dates the whole report by.
 *
 * The UTC offset is the point. This log is mirrored to disk and survives the
 * process, so a report is routinely read days after the lines in it were
 * written; a bare wall clock is unreadable once the device has crossed a zone
 * or a DST boundary, because the timestamps jump with nothing saying why.
 * [Locale.US] pins the digits to ASCII, which a locale carrying its own
 * numbering system would otherwise change.
 */
internal val LOG_TIMESTAMP_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)

/**
 * Renders [epochMillis] in [zone], which defaults to the device's zone *as it
 * is right now*.
 *
 * Resolving the zone per call, rather than once, is the whole reason this
 * exists. A `SimpleDateFormat` captures `TimeZone.getDefault()` when it is
 * constructed, and `DateTimeFormatter.withZone(ZoneId.systemDefault())` does
 * the same — while these formatters live as long as the process. So after the
 * device changed zone (a flight, or the user changing the setting), every
 * later line was still stamped in the *old* zone: silently wrong, with no
 * marker in the file to reveal it. DST transitions were never the problem —
 * a zone carries its own rules — zone switches were. Re-reading costs a field
 * read and a small clone, and does no IPC.
 */
internal fun formatLogTimestamp(
    epochMillis: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): String = LOG_TIMESTAMP_FORMAT.format(Instant.ofEpochMilli(epochMillis).atZone(zone))

/**
 * This launcher's debug log: the shared recorder from `mikelward/androidlog`,
 * plus the handful of things only this app has.
 *
 * The buffer, the ring's bounds, the pinned reserve, the timestamps, the
 * stack-trace compaction and the privacy floor all live in [DebugLog] now.
 * What is left here is what a shared library cannot know: a logcat-only
 * channel for per-icon detail, the lifecycle summary every Activity callback
 * writes, and the crash handler's one entry point.
 *
 * **Where lines go is decided by the sinks, not here.** `TypeLauncherApp`
 * registers a [Destination.DEVICE] logcat sink, a [Destination.DEVICE] file
 * sink, and a [Destination.OFF_DEVICE] Crashlytics sink; the library hands
 * each the rendering its destination may have. That replaces the fan-out this
 * object used to write by hand at four call sites, which is where the two
 * renderings could drift apart.
 */
internal object LauncherDebugLog : DebugLog() {

    /**
     * Logcat-only diagnostic for high-frequency, per-icon detail (icon-tile
     * composition, dynamic-calendar resolution). Goes to `adb logcat -s
     * TypeLauncherDebug` like an ordinary event, but is deliberately kept *out*
     * of the buffer and the Crashlytics breadcrumbs: at one line per app icon
     * per render size it would otherwise overflow the ring on a cold start and
     * evict the lifecycle/state context the bug report exists to capture.
     *
     * Not a [DebugLog] level, because it is not one — the levels all record.
     * This is the deliberate absence of recording, so it stays a separate name
     * that says so.
     */
    fun trace(message: String) {
        Log.d(LAUNCHER_DEBUG_TAG, message)
    }

    /**
     * Records an uncaught exception for the crash handler, **without** the
     * Crashlytics non-fatal that [failure] produces.
     *
     * Crashlytics' own chained uncaught handler reports this same throwable as
     * a fatal, so a non-fatal here would double-count it (Codex on PR #592).
     * The level is what carries that: the off-device sink raises a non-fatal
     * for `W` and not for `E`. It reads backwards — the more severe level
     * reports less — and it is right for exactly this reason, which is why the
     * sink says so at the branch.
     */
    internal fun recordUncaught(threadName: String, throwable: Throwable) {
        // The thread's name is fixed vocabulary the platform chose, not
        // anything of the user's, so it crosses the boundary tagged.
        error(throwable, "Uncaught exception in thread %s", safe(threadName))
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
}

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
        mirrored = "$mirroredHead component=$OFF_DEVICE_PLACEHOLDER " +
            "package=$OFF_DEVICE_PLACEHOLDER data=$mirroredData extras=${extrasSummary.mirrored}",
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
    else -> OFF_DEVICE_PLACEHOLDER
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
    else -> OFF_DEVICE_PLACEHOLDER
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
        mirrored = "action=$action keyCode=$OFF_DEVICE_PLACEHOLDER $tail",
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
